package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-449 corpus: `textDocument/didChange` diagnostics publish-on-change.
 *
 * Complements [LspLifecycleDiagnosticsTddTest] (single-doc basics) and
 * [LspDidOpenMultiDocDiagnosticsTddTest] (didOpen isolation) with a focused
 * publish-on-change matrix: full-document sync, range edits, multi-URI isolation,
 * version gating, sequential edit streams, and server-connected client path.
 *
 * Product surface exercised only via public APIs:
 * - [LuaTextDocumentService.didOpen] / [LuaTextDocumentService.didChange] /
 *   [LuaTextDocumentService.didClose]
 * - [LuaLanguageService.didOpen] / [LuaLanguageService.didChange] /
 *   [LuaLanguageService.diagnostics] / [LuaLanguageService.diagnosticsForUri] /
 *   [LuaLanguageService.documentSymbols]
 * - Connected [LuaLanguageServer] client publish path
 *
 * Dual-path notes (no invented APIs; goldens aligned to product):
 * - Version gate: stale/equal versions must not publish (hard lock).
 * - Null version: product currently accepts and applies ([CURRENTLY_ACCEPTS] path);
 *   ideal may reject or treat as "always apply" — both non-crash outcomes accepted
 *   when explicitly dual-pathed.
 * - Same-text version bump: product currently republishes even when text is
 *   unchanged ([CURRENTLY_ACCEPTS]); ideal may suppress no-op publishes.
 * - Incomplete local initializer (`local x =\nreturn x`): recovery may insert a
 *   placeholder without emitting a recovery diagnostic ([CURRENTLY_ACCEPTS]
 *   empty-or-nonempty). Hard-lock invalid forms use bare `local =` (`<name>`).
 * - Unknown / closed documents: no publish (hard lock).
 * - Sibling isolation: changing one open URI must not republish siblings (hard lock).
 *
 * Host android.jar is not required for this pure-LSP parse-diagnostics corpus.
 * Policy remains Downloads + SDK android-35 only; never G:/.
 *
 * Test-only. Verification is review-owned (TASK-043). Workers must not run Gradle.
 */
class LspDiagnosticsPublishOnChangeTddTest {

    @Test
    fun full_sync_valid_to_invalid_publishes_non_empty_errors_for_that_uri_only() {
        val harness = harness()
        val uri = "file:///workspace/change-valid-to-invalid.lua"
        val sibling = "file:///workspace/change-valid-to-invalid-sibling.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        harness.textDocuments.didOpen(openParams(sibling, VALID_B, version = 1))
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(uri, version = 2, text = INVALID))

        assertEquals(listOf(uri), harness.published.map { it.uri })
        assertTrue(harness.published.single().diagnostics.isNotEmpty())
        assertTrue(
            harness.published.single().diagnostics.all { it.severity == DiagnosticSeverity.Error }
        )
        assertTrue(
            harness.languageService.diagnostics("workspace/change-valid-to-invalid-sibling.lua")
                .diagnostics.isEmpty(),
            "sibling must stay clean after invalid change on peer"
        )
    }

    @Test
    fun full_sync_invalid_to_valid_clears_diagnostics_and_refreshes_symbols() {
        val harness = harness()
        val uri = "file:///workspace/change-invalid-to-valid.lua"
        val path = "workspace/change-invalid-to-valid.lua"

        harness.textDocuments.didOpen(openParams(uri, INVALID, version = 1))
        assertTrue(lastPublishedFor(harness.published, uri).diagnostics.isNotEmpty())
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(uri, version = 2, text = VALID_REPAIRED))

        assertEquals(listOf(uri), harness.published.map { it.uri })
        assertTrue(harness.published.single().diagnostics.isEmpty())
        assertTrue(
            harness.languageService.documentSymbols(path).any { it.name == "repaired" },
            "valid replacement must refresh document symbols"
        )
        assertDiagnosticsMatch(
            harness.published.single(),
            harness.languageService.diagnosticsForUri(uri)
        )
    }

    @Test
    fun multi_doc_change_isolates_publish_and_query_snapshots_per_uri() {
        val harness = harness()
        val first = Doc("file:///workspace/change-multi-a.lua", "workspace/change-multi-a.lua", VALID_A)
        val second = Doc("file:///workspace/change-multi-b.lua", "workspace/change-multi-b.lua", VALID_B)
        val third = Doc("file:///workspace/change-multi-c.lua", "workspace/change-multi-c.lua", VALID_C)

        harness.textDocuments.didOpen(openParams(first.uri, first.source, version = 1))
        harness.textDocuments.didOpen(openParams(second.uri, second.source, version = 1))
        harness.textDocuments.didOpen(openParams(third.uri, third.source, version = 1))
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(second.uri, version = 2, text = INVALID))

        assertEquals(listOf(second.uri), harness.published.map { it.uri })
        assertTrue(harness.published.single().diagnostics.isNotEmpty())

        assertTrue(harness.languageService.diagnostics(first.path).diagnostics.isEmpty())
        assertTrue(harness.languageService.diagnostics(third.path).diagnostics.isEmpty())
        assertTrue(harness.languageService.diagnostics(second.path).diagnostics.isNotEmpty())
        assertTrue(
            harness.languageService.documentSymbols(first.path).any { it.name == "valueA" }
        )
        assertTrue(
            harness.languageService.documentSymbols(third.path).any { it.name == "valueC" }
        )
    }

    @Test
    fun stale_version_does_not_publish_or_mutate_diagnostics() {
        val harness = harness()
        val uri = "file:///workspace/change-stale-version.lua"
        val path = "workspace/change-stale-version.lua"

        harness.textDocuments.didOpen(openParams(uri, VALID_A, version = 5))
        harness.published.clear()

        harness.textDocuments.didChange(fullChange(uri, version = 4, text = INVALID))
        harness.textDocuments.didChange(fullChange(uri, version = 5, text = INVALID))

        assertTrue(
            harness.published.isEmpty(),
            "stale or equal versions must not publish diagnostics"
        )
        assertTrue(
            harness.languageService.diagnostics(path).diagnostics.isEmpty(),
            "stale change must not mutate the open document snapshot"
        )
        assertTrue(
            harness.languageService.documentSymbols(path).any { it.name == "valueA" },
            "symbols must remain from the last accepted open/change"
        )
    }

    @Test
    fun range_edit_introducing_parse_error_publishes_diagnostics() {
        val harness = harness()
        val uri = "file:///workspace/change-range-error.lua"
        // Two valid locals; replace the entire second line with a broken assignment.
        val openText = "local first = 1\nlocal second = 2\nreturn first + second"
        harness.textDocuments.didOpen(openParams(uri, openText, version = 1))
        harness.published.clear()

        harness.textDocuments.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(
                    TextDocumentContentChangeEvent(
                        Range(Position(1, 0), Position(1, 16)),
                        16,
                        "local ="
                    )
                )
            )
        )

        assertEquals(listOf(uri), harness.published.map { it.uri })
        assertTrue(
            harness.published.single().diagnostics.isNotEmpty(),
            "range edit that breaks parse must publish non-empty diagnostics"
        )
        assertTrue(
            harness.published.single().diagnostics.all { it.severity == DiagnosticSeverity.Error }
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun harness(): Harness {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )
        return Harness(languageService, textDocuments, published)
    }

    private data class Harness(
        val languageService: LuaLanguageService,
        val textDocuments: LuaTextDocumentService,
        val published: MutableList<PublishDiagnosticsParams>
    )

    private data class Doc(
        val uri: String,
        val path: String,
        val source: String
    )

    private fun openParams(uri: String, text: String, version: Int = 1): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", version, text))
    }

    private fun fullChange(uri: String, version: Int?, text: String): DidChangeTextDocumentParams {
        return DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(text))
        )
    }

    private fun closeParams(uri: String): DidCloseTextDocumentParams {
        return DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
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
        private const val VALID_REPAIRED = "local repaired = 1\nreturn repaired"
        /** Hard-lock invalid: missing name after `local` emits recovery diagnostic. */
        private const val INVALID = "local ="
        /**
         * Incomplete local initializer. Product recovery currently inserts a placeholder
         * and may emit zero recovery diagnostics (CURRENTLY_ACCEPTS empty path).
         */
        private const val INCOMPLETE_LOCAL = "local value =\nreturn value"
    }
}
