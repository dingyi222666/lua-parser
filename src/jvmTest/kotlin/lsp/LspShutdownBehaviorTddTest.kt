package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LspTextDocumentRequestPolicy
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LspShutdownBehaviorTddTest {
    @Test
    fun before_initialize_returns_quiet_text_document_requests_and_rejects_workspace_requests() {
        val server = LuaLanguageServer()
        val uri = "file:///workspace/pre-initialize.lua"

        assertQuietTextDocumentRequests(server.textDocumentService, uri)
        assertFutureFails(
            server.workspaceService.symbol(WorkspaceSymbolParams("value")),
            "workspace requests before initialize should complete exceptionally"
        )
    }

    @Test
    fun after_shutdown_before_exit_rejects_text_document_and_workspace_requests() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/post-shutdown-requests.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))

        server.shutdown().get()

        val requests = listOf<CompletableFuture<*>>(
            server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), Position(1, 7))),
            server.textDocumentService.completion(CompletionParams(TextDocumentIdentifier(uri), Position(1, 7))),
            server.textDocumentService.signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri), Position(1, 7))),
            server.textDocumentService.definition(DefinitionParams(TextDocumentIdentifier(uri), Position(1, 7))),
            server.textDocumentService.declaration(DeclarationParams(TextDocumentIdentifier(uri), Position(1, 7))),
            server.textDocumentService.documentHighlight(DocumentHighlightParams(TextDocumentIdentifier(uri), Position(1, 7))),
            server.textDocumentService.references(
                ReferenceParams(TextDocumentIdentifier(uri), Position(1, 7), ReferenceContext(true))
            ),
            server.textDocumentService.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))),
            server.workspaceService.symbol(WorkspaceSymbolParams("value"))
        )

        requests.forEach { request ->
            assertFutureFails(request, "requests after shutdown and before exit should complete exceptionally")
        }
    }

    @Test
    fun after_shutdown_before_exit_ignores_text_document_and_workspace_notifications() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/post-shutdown-notifications.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))
        client.published.clear()

        server.shutdown().get()

        server.textDocumentService.didOpen(openParams("file:///workspace/ignored-open.lua", "local ="))
        server.textDocumentService.didChange(changeParams(uri, 2, "local ="))
        server.textDocumentService.didClose(closeParams(uri))
        server.workspaceService.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "androlua.imports" to listOf("String"),
                    "jvm.importPrefixes" to listOf("java.lang")
                )
            )
        )
        server.workspaceService.didChangeWatchedFiles(DidChangeWatchedFilesParams(emptyList()))

        assertTrue(client.published.isEmpty(), "notifications after shutdown and before exit should be ignored")
    }

    @Test
    fun server_requests_and_notifications_can_overlap_shutdown_without_escaping_post_shutdown_state() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/concurrent-shutdown.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))
        val position = Position(1, 7)

        runConcurrently(
            listOf(
                {
                    awaitRequestOrLifecycleRejection(
                        server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), position))
                    )
                },
                {
                    awaitRequestOrLifecycleRejection(
                        server.workspaceService.symbol(WorkspaceSymbolParams("value"))
                    )
                },
                {
                    server.textDocumentService.didChange(
                        changeParams(uri, 2, "local nextValue = 2\nreturn nextValue")
                    )
                },
                {
                    server.workspaceService.didChangeConfiguration(
                        DidChangeConfigurationParams(
                            mapOf("jvm" to mapOf("classes" to listOf("java.lang.String")))
                        )
                    )
                },
                {
                    assertEquals(0, server.shutdown().get(5, TimeUnit.SECONDS))
                }
            )
        )

        assertEquals(0, server.shutdown().get(5, TimeUnit.SECONDS))
        assertFutureFails(
            server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), position)),
            "text document requests should be rejected after shutdown overlap completes"
        )
        assertFutureFails(
            server.workspaceService.symbol(WorkspaceSymbolParams("value")),
            "workspace requests should be rejected after shutdown overlap completes"
        )
    }

    @Test
    fun exit_after_shutdown_preserves_quiet_text_document_requests_and_rejected_workspace_requests() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/post-exit.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))

        server.shutdown().get()
        server.exit()

        assertQuietTextDocumentRequests(server.textDocumentService, uri)
        assertFutureFails(
            server.workspaceService.symbol(WorkspaceSymbolParams("value")),
            "workspace requests after exit should remain rejected"
        )
    }

    private fun openParams(
        uri: String,
        text: String,
        languageId: String = "lua",
        version: Int = 1
    ): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, languageId, version, text))
    }

    private fun changeParams(
        uri: String,
        version: Int,
        text: String
    ): DidChangeTextDocumentParams {
        return DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(text))
        )
    }

    private fun closeParams(uri: String): DidCloseTextDocumentParams {
        return DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
    }

    private fun assertFutureFails(future: CompletableFuture<*>, message: String) {
        if (!future.isCompletedExceptionally) {
            fail(message)
        }
    }

    private fun awaitRequestOrLifecycleRejection(future: CompletableFuture<*>) {
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            if (error.cause !is IllegalStateException) {
                throw error
            }
        }
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

    private fun assertQuietTextDocumentRequests(textDocuments: TextDocumentService, uri: String) {
        val position = Position(1, 7)

        val hover = textDocuments.hover(HoverParams(TextDocumentIdentifier(uri), position)).get()
        val completion = textDocuments.completion(
            CompletionParams(TextDocumentIdentifier(uri), position)
        ).get().right
        val signatureHelp = textDocuments.signatureHelp(
            SignatureHelpParams(TextDocumentIdentifier(uri), position)
        ).get()
        val definition = textDocuments.definition(
            DefinitionParams(TextDocumentIdentifier(uri), position)
        ).get().left
        val declaration = textDocuments.declaration(
            DeclarationParams(TextDocumentIdentifier(uri), position)
        ).get().left
        val documentHighlights = textDocuments.documentHighlight(
            DocumentHighlightParams(TextDocumentIdentifier(uri), position)
        ).get()
        val references = textDocuments.references(
            ReferenceParams(TextDocumentIdentifier(uri), position, ReferenceContext(true))
        ).get()
        val documentSymbols = textDocuments.documentSymbol(
            DocumentSymbolParams(TextDocumentIdentifier(uri))
        ).get()

        assertNull(hover)
        assertTrue(completion.items.isEmpty())
        assertNull(signatureHelp)
        assertTrue(definition.isEmpty())
        assertTrue(declaration.isEmpty())
        assertTrue(documentHighlights.isEmpty())
        assertTrue(references.isEmpty())
        assertTrue(documentSymbols.isEmpty())
    }

    private fun assertRejectedTextDocumentRequests(textDocuments: TextDocumentService, uri: String) {
        val position = Position(1, 7)
        val requests = listOf<CompletableFuture<*>>(
            textDocuments.hover(HoverParams(TextDocumentIdentifier(uri), position)),
            textDocuments.completion(CompletionParams(TextDocumentIdentifier(uri), position)),
            textDocuments.signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri), position)),
            textDocuments.definition(DefinitionParams(TextDocumentIdentifier(uri), position)),
            textDocuments.declaration(DeclarationParams(TextDocumentIdentifier(uri), position)),
            textDocuments.documentHighlight(DocumentHighlightParams(TextDocumentIdentifier(uri), position)),
            textDocuments.references(
                ReferenceParams(TextDocumentIdentifier(uri), position, ReferenceContext(true))
            ),
            textDocuments.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri)))
        )

        requests.forEach { request ->
            assertFutureFails(request, "text document reject policy should complete exceptionally")
        }
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
}
