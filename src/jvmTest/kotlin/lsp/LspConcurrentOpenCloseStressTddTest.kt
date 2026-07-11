package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TASK-221 stress corpus: rapid concurrent open/change/close sequences must not
 * corrupt [LuaLanguageService] / [LuaTextDocumentService] state, and final
 * diagnostics queries must match the last published snapshot for each URI.
 */
class LspConcurrentOpenCloseStressTddTest {

    @Test
    fun concurrent_open_change_close_on_single_document_does_not_corrupt_final_state() {
        val languageService = LuaLanguageService()
        languageService.initialize(InitializeParams())
        val published = ConcurrentLinkedQueue<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(languageService, publishDiagnostics = published::add)
        val uri = "file:///workspace/stress-single.lua"
        val path = "workspace/stress-single.lua"

        // Seed a known open document so concurrent change/close have something to race against.
        textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        published.clear()

        val version = AtomicInteger(1)
        runConcurrently(
            listOf(
                {
                    repeat(8) {
                        textDocuments.didOpen(openParams(uri, VALID_A, version = version.incrementAndGet()))
                    }
                },
                {
                    repeat(8) {
                        textDocuments.didChange(
                            changeParams(uri, version.incrementAndGet(), VALID_B)
                        )
                    }
                },
                {
                    repeat(4) {
                        textDocuments.didClose(closeParams(uri))
                        textDocuments.didOpen(openParams(uri, VALID_B, version = version.incrementAndGet()))
                    }
                },
                {
                    repeat(8) {
                        textDocuments.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri)))
                            .get(5, TimeUnit.SECONDS)
                    }
                }
            )
        )

        // Converge to a deterministic final open snapshot and assert service/publish agreement.
        textDocuments.didOpen(openParams(uri, FINAL_VALID, version = version.incrementAndGet()))
        val lastPublished = lastPublishedFor(published, uri)
        val queried = languageService.diagnostics(path)

        assertEquals(uri, lastPublished.uri)
        assertEquals(uri, queried.uri)
        assertDiagnosticsMatch(lastPublished, queried)
        assertTrue(queried.diagnostics.isEmpty(), "final valid open should leave no diagnostics")
        assertTrue(
            languageService.documentSymbols(path).any { it.name == "finalValue" },
            "final open content should remain queryable"
        )
    }

    @Test
    fun concurrent_multi_document_open_change_close_keeps_last_published_diagnostics() {
        val languageService = LuaLanguageService()
        languageService.initialize(InitializeParams())
        val published = ConcurrentLinkedQueue<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(languageService, publishDiagnostics = published::add)

        val docs = (0 until DOCUMENT_COUNT).map { index ->
            StressDocument(
                uri = "file:///workspace/stress-multi-$index.lua",
                path = "workspace/stress-multi-$index.lua",
                finalText = "local finalValue$index = $index\nreturn finalValue$index",
                invalidText = "local =",
                validText = "local value$index = $index\nreturn value$index"
            )
        }

        docs.forEach { doc ->
            textDocuments.didOpen(openParams(doc.uri, doc.validText, version = 1))
        }
        published.clear()

        val versions = ConcurrentHashMap<String, AtomicInteger>()
        docs.forEach { versions[it.uri] = AtomicInteger(1) }

        runConcurrently(
            docs.flatMap { doc ->
                listOf(
                    {
                        repeat(6) {
                            textDocuments.didChange(
                                changeParams(
                                    doc.uri,
                                    versions.getValue(doc.uri).incrementAndGet(),
                                    if (it % 2 == 0) doc.invalidText else doc.validText
                                )
                            )
                        }
                    },
                    {
                        repeat(3) {
                            textDocuments.didClose(closeParams(doc.uri))
                            textDocuments.didOpen(
                                openParams(
                                    doc.uri,
                                    doc.validText,
                                    version = versions.getValue(doc.uri).incrementAndGet()
                                )
                            )
                        }
                    },
                    {
                        repeat(4) {
                            textDocuments.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(doc.uri)))
                                .get(5, TimeUnit.SECONDS)
                        }
                    }
                )
            }
        )

        // Deterministic final contents for every document.
        docs.forEach { doc ->
            textDocuments.didOpen(
                openParams(
                    doc.uri,
                    doc.finalText,
                    version = versions.getValue(doc.uri).incrementAndGet()
                )
            )
        }

        docs.forEach { doc ->
            val lastPublished = lastPublishedFor(published, doc.uri)
            val queried = languageService.diagnostics(doc.path)
            assertEquals(doc.uri, lastPublished.uri)
            assertEquals(doc.uri, queried.uri)
            assertDiagnosticsMatch(lastPublished, queried)
            assertTrue(queried.diagnostics.isEmpty(), "final content for ${doc.uri} should be clean")
            assertTrue(
                languageService.documentSymbols(doc.path).any { it.name == "finalValue${docs.indexOf(doc)}" },
                "final symbols for ${doc.uri} should remain available"
            )
        }
    }

    @Test
    fun concurrent_close_all_then_reopen_final_diagnostics_match_publish() {
        val languageService = LuaLanguageService()
        languageService.initialize(InitializeParams())
        val published = ConcurrentLinkedQueue<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(languageService, publishDiagnostics = published::add)
        val uris = (0 until 4).map { "file:///workspace/stress-close-all-$it.lua" }
        val paths = uris.map { it.removePrefix("file:///") }

        uris.forEachIndexed { index, uri ->
            textDocuments.didOpen(openParams(uri, "local seed$index = $index\nreturn seed$index", version = 1))
        }
        published.clear()

        runConcurrently(
            uris.map { uri ->
                {
                    repeat(5) {
                        textDocuments.didClose(closeParams(uri))
                        textDocuments.didOpen(openParams(uri, "local =", version = it + 2))
                        textDocuments.didChange(changeParams(uri, it + 10, "local tmp = 1\nreturn tmp"))
                        textDocuments.didClose(closeParams(uri))
                    }
                }
            }
        )

        // After concurrent churn, force closed then open final valid bodies.
        uris.forEach { textDocuments.didClose(closeParams(it)) }
        uris.forEachIndexed { index, uri ->
            textDocuments.didOpen(
                openParams(uri, "local closedThenOpen$index = $index\nreturn closedThenOpen$index", version = 100 + index)
            )
        }

        uris.forEachIndexed { index, uri ->
            val path = paths[index]
            val lastPublished = lastPublishedFor(published, uri)
            val queried = languageService.diagnostics(path)
            assertDiagnosticsMatch(lastPublished, queried)
            assertTrue(queried.diagnostics.isEmpty(), "reopened document $uri should be clean")
            assertTrue(
                languageService.documentSymbols(path).any { it.name == "closedThenOpen$index" },
                "reopened document $uri should expose final symbols"
            )
        }
    }

    @Test
    fun rapid_sequential_open_change_close_loop_matches_last_publish() {
        val languageService = LuaLanguageService()
        languageService.initialize(InitializeParams())
        val published = ConcurrentLinkedQueue<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(languageService, publishDiagnostics = published::add)
        val uri = "file:///workspace/stress-sequential.lua"
        val path = "workspace/stress-sequential.lua"

        var version = 1
        repeat(20) { round ->
            textDocuments.didOpen(openParams(uri, if (round % 2 == 0) VALID_A else "local =", version = version++))
            textDocuments.didChange(
                changeParams(uri, version++, if (round % 3 == 0) VALID_B else "local broken =")
            )
            if (round % 4 == 0) {
                textDocuments.didClose(closeParams(uri))
                textDocuments.didOpen(openParams(uri, VALID_A, version = version++))
            }
        }

        textDocuments.didOpen(openParams(uri, FINAL_VALID, version = version++))
        val lastPublished = lastPublishedFor(published, uri)
        val queried = languageService.diagnostics(path)

        assertDiagnosticsMatch(lastPublished, queried)
        assertTrue(queried.diagnostics.isEmpty())
        assertTrue(languageService.documentSymbols(path).any { it.name == "finalValue" })
    }

    @Test
    fun concurrent_invalid_and_valid_changes_final_open_wins_for_diagnostics() {
        val languageService = LuaLanguageService()
        languageService.initialize(InitializeParams())
        val published = ConcurrentLinkedQueue<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(languageService, publishDiagnostics = published::add)
        val uri = "file:///workspace/stress-valid-wins.lua"
        val path = "workspace/stress-valid-wins.lua"

        textDocuments.didOpen(openParams(uri, VALID_A, version = 1))
        published.clear()
        val version = AtomicInteger(1)

        runConcurrently(
            listOf(
                {
                    repeat(10) {
                        textDocuments.didChange(
                            changeParams(uri, version.incrementAndGet(), "local =")
                        )
                    }
                },
                {
                    repeat(10) {
                        textDocuments.didChange(
                            changeParams(uri, version.incrementAndGet(), VALID_B)
                        )
                    }
                },
                {
                    repeat(5) {
                        textDocuments.didClose(closeParams(uri))
                        textDocuments.didOpen(openParams(uri, VALID_A, version = version.incrementAndGet()))
                    }
                }
            )
        )

        textDocuments.didOpen(openParams(uri, FINAL_VALID, version = version.incrementAndGet()))
        val lastPublished = lastPublishedFor(published, uri)
        val queried = languageService.diagnostics(path)

        assertDiagnosticsMatch(lastPublished, queried)
        assertTrue(queried.diagnostics.isEmpty(), "final open must dominate concurrent invalid edits")
    }

    @Test
    fun closed_document_after_stress_publishes_empty_and_clears_symbols() {
        val languageService = LuaLanguageService()
        languageService.initialize(InitializeParams())
        val published = ConcurrentLinkedQueue<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(languageService, publishDiagnostics = published::add)
        val uri = "file:///workspace/stress-closed-final.lua"
        val path = "workspace/stress-closed-final.lua"

        textDocuments.didOpen(openParams(uri, "local =", version = 1))
        val version = AtomicInteger(1)

        runConcurrently(
            listOf(
                {
                    repeat(6) {
                        textDocuments.didChange(
                            changeParams(uri, version.incrementAndGet(), VALID_A)
                        )
                    }
                },
                {
                    repeat(6) {
                        textDocuments.didOpen(openParams(uri, "local =", version = version.incrementAndGet()))
                    }
                },
                {
                    repeat(6) {
                        textDocuments.didClose(closeParams(uri))
                        textDocuments.didOpen(openParams(uri, VALID_B, version = version.incrementAndGet()))
                    }
                }
            )
        )

        textDocuments.didClose(closeParams(uri))
        val lastPublished = lastPublishedFor(published, uri)
        val queried = languageService.diagnostics(path)

        assertEquals(uri, lastPublished.uri)
        assertTrue(lastPublished.diagnostics.isEmpty(), "close must publish empty diagnostics")
        // Closed documents have no open snapshot; diagnostics query should stay empty.
        assertTrue(queried.diagnostics.isEmpty())
        assertTrue(languageService.documentSymbols(path).isEmpty(), "closed document should leave no symbols")
    }

    private fun lastPublishedFor(
        published: ConcurrentLinkedQueue<PublishDiagnosticsParams>,
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

    private fun diagnosticKey(diagnostic: org.eclipse.lsp4j.Diagnostic): String {
        return listOf(
            diagnostic.severity?.toString().orEmpty(),
            diagnostic.message.orEmpty(),
            diagnostic.range?.start?.line?.toString().orEmpty(),
            diagnostic.range?.start?.character?.toString().orEmpty(),
            diagnostic.range?.end?.line?.toString().orEmpty(),
            diagnostic.range?.end?.character?.toString().orEmpty()
        ).joinToString("|")
    }

    private fun runConcurrently(actions: List<() -> Unit>) {
        val executor = Executors.newFixedThreadPool(actions.size.coerceAtLeast(1))
        val barrier = CyclicBarrier(actions.size)
        try {
            val futures = actions.map { action ->
                executor.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    action()
                }
            }
            futures.forEach { future -> future.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun openParams(uri: String, source: String, version: Int): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", version, source))
    }

    private fun changeParams(uri: String, version: Int, source: String): DidChangeTextDocumentParams {
        return DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(source))
        )
    }

    private fun closeParams(uri: String): DidCloseTextDocumentParams {
        return DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
    }

    private data class StressDocument(
        val uri: String,
        val path: String,
        val finalText: String,
        val invalidText: String,
        val validText: String
    )

    companion object {
        private const val DOCUMENT_COUNT = 4
        private const val VALID_A = "local valueA = 1\nreturn valueA"
        private const val VALID_B = "local valueB = 2\nreturn valueB"
        private const val FINAL_VALID = "local finalValue = 99\nreturn finalValue"
    }
}
