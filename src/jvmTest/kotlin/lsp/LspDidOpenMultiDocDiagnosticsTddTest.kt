package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageClient
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TASK-374 corpus: multi-document `textDocument/didOpen` diagnostics isolation.
 *
 * Product surface exercised only via public APIs:
 * - [LuaTextDocumentService.didOpen] / [LuaTextDocumentService.didClose]
 * - [LuaLanguageService.didOpen] / [LuaLanguageService.diagnostics] /
 *   [LuaLanguageService.diagnosticsForUri] / [LuaLanguageService.documentSymbols]
 * - Connected [LuaLanguageServer] client publish path
 *
 * Acceptance locks (no invented APIs):
 * - Each didOpen publishes exactly one diagnostics payload for that URI.
 * - Valid docs publish empty diagnostics; invalid docs publish non-empty Errors.
 * - Diagnostics for one open document do not clobber sibling open documents.
 * - Queried diagnostics for each open URI/path match the last published snapshot.
 * - Closing one document publishes empty diagnostics for it while siblings stay.
 * - Re-opening a URI replaces that URI's diagnostics snapshot only.
 * - Server-connected client receives multi-doc open publishes in open order.
 *
 * Test-only. Verification is review-owned (TASK-043). Workers must not run Gradle.
 */
class LspDidOpenMultiDocDiagnosticsTddTest {

    @Test
    fun sequential_did_open_publishes_one_payload_per_document_in_open_order() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )

        val first = Doc("file:///workspace/multi-open-first.lua", "workspace/multi-open-first.lua", VALID_A)
        val second = Doc("file:///workspace/multi-open-second.lua", "workspace/multi-open-second.lua", VALID_B)
        val third = Doc("file:///workspace/multi-open-third.lua", "workspace/multi-open-third.lua", VALID_C)

        textDocuments.didOpen(openParams(first.uri, first.source))
        textDocuments.didOpen(openParams(second.uri, second.source))
        textDocuments.didOpen(openParams(third.uri, third.source))

        assertEquals(
            listOf(first.uri, second.uri, third.uri),
            published.map { it.uri },
            "didOpen must publish diagnostics once per document in open order"
        )
        published.forEach { payload ->
            assertTrue(payload.diagnostics.isEmpty(), "valid multi-doc open should be clean: ${payload.uri}")
        }
    }

    @Test
    fun mixed_valid_and_invalid_opens_isolate_diagnostics_per_uri() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )

        val valid = Doc("file:///workspace/multi-mixed-valid.lua", "workspace/multi-mixed-valid.lua", VALID_A)
        val invalid = Doc("file:///workspace/multi-mixed-invalid.lua", "workspace/multi-mixed-invalid.lua", INVALID)
        val alsoValid = Doc("file:///workspace/multi-mixed-also-valid.lua", "workspace/multi-mixed-also-valid.lua", VALID_B)

        textDocuments.didOpen(openParams(valid.uri, valid.source))
        textDocuments.didOpen(openParams(invalid.uri, invalid.source))
        textDocuments.didOpen(openParams(alsoValid.uri, alsoValid.source))

        val byUri = lastPublishedByUri(published)
        assertEquals(setOf(valid.uri, invalid.uri, alsoValid.uri), byUri.keys)

        assertTrue(byUri.getValue(valid.uri).diagnostics.isEmpty(), "valid sibling must stay clean")
        assertTrue(byUri.getValue(alsoValid.uri).diagnostics.isEmpty(), "second valid sibling must stay clean")
        assertTrue(
            byUri.getValue(invalid.uri).diagnostics.isNotEmpty(),
            "invalid open must publish parse diagnostics for its own URI only"
        )
        byUri.getValue(invalid.uri).diagnostics.forEach { diagnostic ->
            assertEquals(DiagnosticSeverity.Error, diagnostic.severity)
        }
    }

    @Test
    fun queried_diagnostics_match_last_published_for_every_open_document() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )

        val docs = listOf(
            Doc("file:///workspace/multi-query-a.lua", "workspace/multi-query-a.lua", VALID_A),
            Doc("file:///workspace/multi-query-b.lua", "workspace/multi-query-b.lua", INVALID),
            Doc("file:///workspace/multi-query-c.lua", "workspace/multi-query-c.lua", VALID_C)
        )
        docs.forEach { textDocuments.didOpen(openParams(it.uri, it.source)) }

        docs.forEach { doc ->
            val lastPublished = lastPublishedFor(published, doc.uri)
            val byPath = languageService.diagnostics(doc.path)
            val byUri = languageService.diagnosticsForUri(doc.uri)

            assertDiagnosticsMatch(lastPublished, byPath)
            assertDiagnosticsMatch(lastPublished, byUri)
            assertEquals(doc.uri, byPath.uri)
            assertEquals(doc.uri, byUri.uri)
        }
    }

    @Test
    fun opening_invalid_sibling_does_not_republish_or_dirty_prior_valid_document() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )

        val validUri = "file:///workspace/multi-no-clobber-valid.lua"
        val invalidUri = "file:///workspace/multi-no-clobber-invalid.lua"
        val validPath = "workspace/multi-no-clobber-valid.lua"

        textDocuments.didOpen(openParams(validUri, VALID_A))
        assertEquals(listOf(validUri), published.map { it.uri })
        assertTrue(published.single().diagnostics.isEmpty())
        published.clear()

        textDocuments.didOpen(openParams(invalidUri, INVALID))

        assertEquals(
            listOf(invalidUri),
            published.map { it.uri },
            "opening a second document must publish only that document's diagnostics"
        )
        assertTrue(published.single().diagnostics.isNotEmpty())
        assertTrue(
            languageService.diagnostics(validPath).diagnostics.isEmpty(),
            "prior valid document diagnostics must remain empty after sibling invalid open"
        )
        assertTrue(
            languageService.documentSymbols(validPath).any { it.name == "valueA" },
            "prior valid document symbols must remain queryable"
        )
    }

    @Test
    fun close_one_document_clears_only_that_uri_while_siblings_keep_diagnostics() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )

        val keepValid = Doc("file:///workspace/multi-close-keep-valid.lua", "workspace/multi-close-keep-valid.lua", VALID_A)
        val closeInvalid = Doc(
            "file:///workspace/multi-close-invalid.lua",
            "workspace/multi-close-invalid.lua",
            INVALID
        )
        val keepInvalid = Doc(
            "file:///workspace/multi-close-keep-invalid.lua",
            "workspace/multi-close-keep-invalid.lua",
            "local ="
        )

        textDocuments.didOpen(openParams(keepValid.uri, keepValid.source))
        textDocuments.didOpen(openParams(closeInvalid.uri, closeInvalid.source))
        textDocuments.didOpen(openParams(keepInvalid.uri, keepInvalid.source))
        published.clear()

        textDocuments.didClose(closeParams(closeInvalid.uri))

        assertEquals(listOf(closeInvalid.uri), published.map { it.uri })
        assertTrue(published.single().diagnostics.isEmpty(), "didClose must publish empty diagnostics")

        assertTrue(languageService.diagnostics(keepValid.path).diagnostics.isEmpty())
        assertTrue(
            languageService.diagnostics(keepInvalid.path).diagnostics.isNotEmpty(),
            "sibling invalid document must keep diagnostics after another close"
        )
        assertTrue(
            languageService.diagnostics(closeInvalid.path).diagnostics.isEmpty(),
            "closed document diagnostics query should be empty"
        )
        assertTrue(
            languageService.documentSymbols(closeInvalid.path).isEmpty(),
            "closed document should leave no document symbols"
        )
        assertTrue(
            languageService.documentSymbols(keepValid.path).any { it.name == "valueA" },
            "remaining open document symbols must stay available"
        )
    }

    @Test
    fun reopen_same_uri_replaces_diagnostics_snapshot_without_touching_siblings() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )

        val target = Doc("file:///workspace/multi-reopen-target.lua", "workspace/multi-reopen-target.lua", INVALID)
        val sibling = Doc("file:///workspace/multi-reopen-sibling.lua", "workspace/multi-reopen-sibling.lua", VALID_B)

        textDocuments.didOpen(openParams(target.uri, target.source, version = 1))
        textDocuments.didOpen(openParams(sibling.uri, sibling.source, version = 1))
        assertTrue(lastPublishedFor(published, target.uri).diagnostics.isNotEmpty())
        published.clear()

        // Re-open target with valid body; version may advance independently per document.
        textDocuments.didOpen(openParams(target.uri, VALID_A, version = 2))

        assertEquals(listOf(target.uri), published.map { it.uri })
        assertTrue(published.single().diagnostics.isEmpty(), "reopen with valid body must clear diagnostics")
        assertTrue(
            languageService.diagnostics(sibling.path).diagnostics.isEmpty(),
            "sibling valid diagnostics must remain empty"
        )
        assertTrue(
            languageService.documentSymbols(target.path).any { it.name == "valueA" },
            "reopened document should expose the new symbols"
        )
        assertTrue(
            languageService.documentSymbols(sibling.path).any { it.name == "valueB" },
            "sibling symbols must remain available after target reopen"
        )
    }

    @Test
    fun multi_doc_open_keeps_each_documents_symbols_queryable() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )

        val alpha = Doc(
            "file:///workspace/multi-symbols-alpha.lua",
            "workspace/multi-symbols-alpha.lua",
            "local alphaValue = 1\nlocal function alphaRender()\n    return alphaValue\nend\nreturn alphaRender"
        )
        val beta = Doc(
            "file:///workspace/multi-symbols-beta.lua",
            "workspace/multi-symbols-beta.lua",
            "local betaValue = 2\nlocal function betaRender()\n    return betaValue\nend\nreturn betaRender"
        )

        textDocuments.didOpen(openParams(alpha.uri, alpha.source))
        textDocuments.didOpen(openParams(beta.uri, beta.source))

        val alphaSymbols = languageService.documentSymbols(alpha.path).map { it.name }
        val betaSymbols = languageService.documentSymbols(beta.path).map { it.name }

        assertTrue("alphaValue" in alphaSymbols)
        assertTrue("alphaRender" in alphaSymbols)
        assertTrue("betaValue" in betaSymbols)
        assertTrue("betaRender" in betaSymbols)
        assertTrue("betaValue" !in alphaSymbols, "document symbols must stay document-scoped")
        assertTrue("alphaValue" !in betaSymbols, "document symbols must stay document-scoped")
        assertEquals(listOf(alpha.uri, beta.uri), published.map { it.uri })
    }

    @Test
    fun server_connected_client_receives_multi_doc_open_diagnostics_in_order() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()

        val first = "file:///workspace/multi-server-first.lua"
        val second = "file:///workspace/multi-server-second.lua"
        val third = "file:///workspace/multi-server-third.lua"

        server.textDocumentService.didOpen(openParams(first, VALID_A))
        server.textDocumentService.didOpen(openParams(second, INVALID))
        server.textDocumentService.didOpen(openParams(third, VALID_C))

        assertEquals(listOf(first, second, third), client.published.map { it.uri })
        assertTrue(client.published[0].diagnostics.isEmpty())
        assertTrue(client.published[1].diagnostics.isNotEmpty())
        assertTrue(client.published[2].diagnostics.isEmpty())
        assertTrue(client.published[1].diagnostics.all { it.severity == DiagnosticSeverity.Error })
    }

    @Test
    fun multi_doc_did_open_return_value_matches_published_payload_for_each_uri() {
        // Direct language-service path: didOpen returns the same snapshot that
        // LuaTextDocumentService would publish for that URI.
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )

        val docs = listOf(
            Doc("file:///workspace/multi-return-a.lua", "workspace/multi-return-a.lua", VALID_A),
            Doc("file:///workspace/multi-return-b.lua", "workspace/multi-return-b.lua", INVALID),
            Doc("file:///workspace/multi-return-c.lua", "workspace/multi-return-c.lua", VALID_B)
        )

        // Drive text-document service (publish path) and cross-check language service return values
        // by re-query after each open rather than inventing dual-open side effects.
        docs.forEach { doc ->
            textDocuments.didOpen(openParams(doc.uri, doc.source))
            val lastPublished = lastPublishedFor(published, doc.uri)
            val queried = languageService.diagnosticsForUri(doc.uri)
            assertDiagnosticsMatch(lastPublished, queried)
        }

        // Independent direct didOpen return values on a fresh service still isolate per URI.
        val direct = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val returned = docs.map { doc ->
            doc.uri to direct.didOpen(openParams(doc.uri, doc.source))
        }
        assertEquals(docs.map { it.uri }, returned.map { it.first })
        assertTrue(returned[0].second.diagnostics.isEmpty())
        assertTrue(returned[1].second.diagnostics.isNotEmpty())
        assertTrue(returned[2].second.diagnostics.isEmpty())
        returned.forEach { (uri, payload) ->
            assertEquals(uri, payload.uri)
            assertDiagnosticsMatch(payload, direct.diagnosticsForUri(uri))
        }
    }

    @Test
    fun multi_doc_corpus_table_covers_valid_invalid_and_reopen_matrix() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )

        data class Case(
            val uri: String,
            val path: String,
            val source: String,
            val expectEmpty: Boolean,
            val expectedSymbol: String?
        )

        val cases = listOf(
            Case("file:///workspace/multi-table-0.lua", "workspace/multi-table-0.lua", VALID_A, true, "valueA"),
            Case("file:///workspace/multi-table-1.lua", "workspace/multi-table-1.lua", INVALID, false, null),
            Case("file:///workspace/multi-table-2.lua", "workspace/multi-table-2.lua", VALID_B, true, "valueB"),
            Case("file:///workspace/multi-table-3.lua", "workspace/multi-table-3.lua", "local = 1", false, null),
            Case("file:///workspace/multi-table-4.lua", "workspace/multi-table-4.lua", VALID_C, true, "valueC")
        )

        cases.forEach { case ->
            textDocuments.didOpen(openParams(case.uri, case.source))
        }

        cases.forEach { case ->
            val lastPublished = lastPublishedFor(published, case.uri)
            val queried = languageService.diagnostics(case.path)
            assertDiagnosticsMatch(lastPublished, queried)
            if (case.expectEmpty) {
                assertTrue(queried.diagnostics.isEmpty(), "${case.uri} expected clean")
            } else {
                assertTrue(queried.diagnostics.isNotEmpty(), "${case.uri} expected diagnostics")
                assertTrue(queried.diagnostics.all { it.severity == DiagnosticSeverity.Error })
            }
            case.expectedSymbol?.let { symbol ->
                assertTrue(
                    languageService.documentSymbols(case.path).any { it.name == symbol },
                    "${case.uri} should expose symbol $symbol"
                )
            }
        }

        // Reopen the invalid slot as valid; only that URI's publish should appear.
        val reopenUri = cases[1].uri
        val reopenPath = cases[1].path
        published.clear()
        textDocuments.didOpen(openParams(reopenUri, "local repaired = 1\nreturn repaired", version = 2))
        assertEquals(listOf(reopenUri), published.map { it.uri })
        assertTrue(published.single().diagnostics.isEmpty())
        assertTrue(languageService.documentSymbols(reopenPath).any { it.name == "repaired" })

        // Remaining corpus rows stay as last opened.
        cases.filterNot { it.uri == reopenUri }.forEach { case ->
            val queried = languageService.diagnostics(case.path)
            if (case.expectEmpty) {
                assertTrue(queried.diagnostics.isEmpty(), "sibling ${case.uri} must stay clean after reopen")
            } else {
                assertTrue(queried.diagnostics.isNotEmpty(), "sibling ${case.uri} must keep diagnostics")
            }
        }
    }

    private fun lastPublishedByUri(
        published: List<PublishDiagnosticsParams>
    ): Map<String, PublishDiagnosticsParams> {
        val result = linkedMapOf<String, PublishDiagnosticsParams>()
        published.forEach { result[it.uri] = it }
        return result
    }

    private fun lastPublishedFor(
        published: List<PublishDiagnosticsParams>,
        uri: String
    ): PublishDiagnosticsParams {
        val matching = published.filter { it.uri == uri }
        assertTrue(matching.isNotEmpty(), "expected at least one publish for $uri")
        return matching.last()
    }

    private fun assertDiagnosticsMatch(
        published: PublishDiagnosticsParams,
        queried: PublishDiagnosticsParams
    ) {
        assertEquals(published.uri, queried.uri)
        assertEquals(
            published.diagnostics.map { diagnosticKey(it) }.sorted(),
            queried.diagnostics.map { diagnosticKey(it) }.sorted(),
            "queried diagnostics must match last published snapshot for ${published.uri}"
        )
    }

    private fun diagnosticKey(diagnostic: Diagnostic): String {
        return listOf(
            diagnostic.severity?.toString().orEmpty(),
            diagnostic.message.orEmpty(),
            diagnostic.range?.start?.line?.toString().orEmpty(),
            diagnostic.range?.start?.character?.toString().orEmpty(),
            diagnostic.range?.end?.line?.toString().orEmpty(),
            diagnostic.range?.end?.character?.toString().orEmpty()
        ).joinToString("|")
    }

    private fun openParams(uri: String, text: String, version: Int = 1): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", version, text))
    }

    private fun closeParams(uri: String): DidCloseTextDocumentParams {
        return DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
    }

    private data class Doc(
        val uri: String,
        val path: String,
        val source: String
    )

    private class RecordingLanguageClient : InvocationHandler {
        val published = mutableListOf<PublishDiagnosticsParams>()

        fun asClient(): LanguageClient {
            return Proxy.newProxyInstance(
                LanguageClient::class.java.classLoader,
                arrayOf(LanguageClient::class.java),
                this
            ) as LanguageClient
        }

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.name == "publishDiagnostics") {
                published += args?.single() as PublishDiagnosticsParams
                return null
            }
            if (method.declaringClass == Object::class.java) {
                return when (method.name) {
                    "toString" -> "RecordingLanguageClient"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            return when (method.returnType) {
                java.lang.Void.TYPE -> null
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                CompletableFuture::class.java -> CompletableFuture.completedFuture<Any?>(null)
                else -> null
            }
        }
    }

    companion object {
        private const val VALID_A = "local valueA = 1\nreturn valueA"
        private const val VALID_B = "local valueB = 2\nreturn valueB"
        private const val VALID_C = "local valueC = 3\nreturn valueC"
        private const val INVALID = "local ="
    }
}
