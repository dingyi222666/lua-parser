package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceSymbolParams
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LspServiceStateConcurrencyTddTest {
    @Test
    fun document_and_workspace_requests_can_overlap_lifecycle_state_changes() {
        val languageService = LuaLanguageService()
        languageService.initialize(InitializeParams())

        val publishedDiagnostics = ConcurrentLinkedQueue<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(languageService, publishDiagnostics = publishedDiagnostics::add)
        val workspace = LuaWorkspaceService(languageService, onConfigurationChanged = textDocuments::republishDiagnostics)
        val uri = "file:///workspace/concurrent-state.lua"

        textDocuments.didOpen(openParams(uri, "local value = 1\nreturn value", version = 1))

        runConcurrently(
            listOf(
                {
                    textDocuments.didChange(
                        changeParams(uri, "local nextValue = 2\nreturn nextValue", version = 2)
                    )
                },
                {
                    workspace.didChangeConfiguration(
                        DidChangeConfigurationParams(
                            mapOf("jvm" to mapOf("classes" to listOf("java.lang.String")))
                        )
                    )
                },
                { textDocuments.republishDiagnostics() },
                {
                    textDocuments.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri)))
                        .get(5, TimeUnit.SECONDS)
                },
                {
                    textDocuments.hover(HoverParams(TextDocumentIdentifier(uri), Position(1, 12)))
                        .get(5, TimeUnit.SECONDS)
                },
                {
                    workspace.symbol(WorkspaceSymbolParams("nextValue"))
                        .get(5, TimeUnit.SECONDS)
                }
            )
        )

        val diagnostics = languageService.diagnostics("workspace/concurrent-state.lua")
        assertEquals(uri, diagnostics.uri)
        assertTrue(diagnostics.diagnostics.isEmpty())

        val publishedBeforeClose = publishedDiagnostics.size
        textDocuments.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))

        val closeDiagnostics = publishedDiagnostics.drop(publishedBeforeClose).single()
        assertEquals(uri, closeDiagnostics.uri)
        assertTrue(closeDiagnostics.diagnostics.isEmpty())
    }

    private fun runConcurrently(actions: List<() -> Unit>) {
        val executor = Executors.newFixedThreadPool(actions.size)
        val barrier = CyclicBarrier(actions.size)
        try {
            val futures = actions.map { action ->
                executor.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    action()
                }
            }
            futures.forEach { future -> future.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun openParams(uri: String, source: String, version: Int): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", version, source))
    }

    private fun changeParams(uri: String, source: String, version: Int): DidChangeTextDocumentParams {
        return DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(source))
        )
    }
}
