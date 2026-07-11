package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
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

/**
 * Corpus for LSP shutdown/exit idempotency and post-shutdown request policy.
 *
 * Policy under test (mirrors [LuaLanguageServer] lifecycle):
 * - Repeated [LanguageServer.shutdown] after a successful shutdown stays successful (result 0).
 * - Repeated [LanguageServer.exit] is a no-op that keeps EXITED state.
 * - After shutdown (before exit): text-document + workspace *requests* complete exceptionally;
 *   notifications are ignored.
 * - After exit: text-document requests are quiet/empty; workspace requests still rejected;
 *   notifications ignored; further initialize/shutdown rejected.
 */
class LspShutdownExitIdempotencyTddTest {

    @Test
    fun repeated_shutdown_after_initialize_is_idempotent() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()

        assertEquals(0, server.shutdown().get(5, TimeUnit.SECONDS))
        assertEquals(0, server.shutdown().get(5, TimeUnit.SECONDS))
        assertEquals(0, server.shutdown().get(5, TimeUnit.SECONDS))
    }

    @Test
    fun repeated_exit_after_shutdown_is_idempotent() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        server.shutdown().get()

        server.exit()
        server.exit()
        server.exit()

        val uri = "file:///workspace/idempotent-exit.lua"
        assertQuietTextDocumentRequests(server.textDocumentService, uri)
        assertFutureFails(
            server.workspaceService.symbol(WorkspaceSymbolParams("value")),
            "workspace requests after repeated exit should remain rejected"
        )
        assertFutureFails(
            server.initialize(InitializeParams()),
            "initialize after repeated exit should remain rejected"
        )
        assertFutureFails(
            server.shutdown(),
            "shutdown after repeated exit should remain rejected"
        )
    }

    @Test
    fun shutdown_exit_shutdown_exit_sequence_stays_stable() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/shutdown-exit-sequence.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))

        assertEquals(0, server.shutdown().get(5, TimeUnit.SECONDS))
        assertRejectedTextDocumentRequests(server.textDocumentService, uri)
        assertFutureFails(
            server.workspaceService.symbol(WorkspaceSymbolParams("value")),
            "workspace requests after first shutdown should be rejected"
        )

        server.exit()
        assertQuietTextDocumentRequests(server.textDocumentService, uri)

        // Further lifecycle traffic must not resurrect INITIALIZED.
        assertFutureFails(server.shutdown(), "shutdown after exit should complete exceptionally")
        server.exit()
        assertFutureFails(
            server.initialize(InitializeParams()),
            "initialize after exit sequence should complete exceptionally"
        )
        assertQuietTextDocumentRequests(server.textDocumentService, uri)
        assertFutureFails(
            server.workspaceService.symbol(WorkspaceSymbolParams("value")),
            "workspace requests after exit sequence should remain rejected"
        )
    }

    @Test
    fun post_shutdown_requests_are_refused_across_repeated_shutdown_calls() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/post-shutdown-refuse.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))

        assertEquals(0, server.shutdown().get(5, TimeUnit.SECONDS))
        assertEquals(0, server.shutdown().get(5, TimeUnit.SECONDS))

        val position = Position(1, 7)
        val requests = listOf<CompletableFuture<*>>(
            server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), position)),
            server.textDocumentService.completion(CompletionParams(TextDocumentIdentifier(uri), position)),
            server.textDocumentService.signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri), position)),
            server.textDocumentService.definition(DefinitionParams(TextDocumentIdentifier(uri), position)),
            server.textDocumentService.declaration(DeclarationParams(TextDocumentIdentifier(uri), position)),
            server.textDocumentService.documentHighlight(DocumentHighlightParams(TextDocumentIdentifier(uri), position)),
            server.textDocumentService.references(
                ReferenceParams(TextDocumentIdentifier(uri), position, ReferenceContext(true))
            ),
            server.textDocumentService.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))),
            server.workspaceService.symbol(WorkspaceSymbolParams("value"))
        )

        requests.forEach { request ->
            assertFutureFails(request, "post-shutdown requests must complete exceptionally after repeated shutdown")
        }
    }

    @Test
    fun post_shutdown_notifications_remain_ignored_after_repeated_shutdown() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/post-shutdown-notifications-idempotent.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))
        client.published.clear()

        server.shutdown().get()
        server.shutdown().get()

        server.textDocumentService.didOpen(openParams("file:///workspace/ignored-open-after-shutdown.lua", "local ="))
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

        assertTrue(client.published.isEmpty(), "notifications after repeated shutdown should stay ignored")
    }

    @Test
    fun exit_without_shutdown_is_idempotent_and_quiets_text_document_requests() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/exit-without-shutdown.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))

        server.exit()
        server.exit()

        assertQuietTextDocumentRequests(server.textDocumentService, uri)
        assertFutureFails(
            server.workspaceService.symbol(WorkspaceSymbolParams("value")),
            "workspace requests after direct exit should be rejected"
        )
        assertFutureFails(
            server.shutdown(),
            "shutdown after direct exit should complete exceptionally"
        )
        assertFutureFails(
            server.initialize(InitializeParams()),
            "initialize after direct exit should complete exceptionally"
        )
    }

    @Test
    fun concurrent_repeated_shutdown_calls_remain_idempotent() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/concurrent-repeated-shutdown.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))

        runConcurrently(
            List(6) {
                {
                    assertEquals(0, server.shutdown().get(5, TimeUnit.SECONDS))
                }
            }
        )

        assertRejectedTextDocumentRequests(server.textDocumentService, uri)
        assertFutureFails(
            server.workspaceService.symbol(WorkspaceSymbolParams("value")),
            "workspace requests after concurrent shutdowns should be rejected"
        )
    }

    @Test
    fun concurrent_exit_calls_remain_idempotent() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/concurrent-repeated-exit.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))

        runConcurrently(
            List(6) {
                { server.exit() }
            }
        )

        assertQuietTextDocumentRequests(server.textDocumentService, uri)
        assertFutureFails(
            server.workspaceService.symbol(WorkspaceSymbolParams("value")),
            "workspace requests after concurrent exits should be rejected"
        )
    }

    @Test
    fun interleaved_shutdown_and_exit_converge_to_exited_policy() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/interleaved-shutdown-exit.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))
        val position = Position(1, 7)

        runConcurrently(
            listOf(
                {
                    try {
                        server.shutdown().get(5, TimeUnit.SECONDS)
                    } catch (_: ExecutionException) {
                        // May lose the race to exit; still must end EXITED.
                    }
                },
                { server.exit() },
                {
                    try {
                        server.shutdown().get(5, TimeUnit.SECONDS)
                    } catch (_: ExecutionException) {
                    }
                },
                { server.exit() },
                {
                    awaitRequestOrLifecycleRejection(
                        server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), position))
                    )
                },
                {
                    awaitRequestOrLifecycleRejection(
                        server.workspaceService.symbol(WorkspaceSymbolParams("value"))
                    )
                }
            )
        )

        // Stable post-condition: EXITED policy (quiet text docs, rejected workspace/lifecycle).
        assertQuietTextDocumentRequests(server.textDocumentService, uri)
        assertFutureFails(
            server.workspaceService.symbol(WorkspaceSymbolParams("value")),
            "workspace requests after interleaved shutdown/exit should be rejected"
        )
        assertFutureFails(
            server.initialize(InitializeParams()),
            "initialize after interleaved shutdown/exit should be rejected"
        )
        assertFutureFails(
            server.shutdown(),
            "shutdown after interleaved shutdown/exit should be rejected"
        )
    }

    @Test
    fun post_exit_notifications_stay_ignored_after_repeated_exit() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/post-exit-notifications-idempotent.lua"
        server.textDocumentService.didOpen(openParams(uri, "local value = 1\nreturn value"))
        client.published.clear()

        server.shutdown().get()
        server.exit()
        server.exit()

        server.textDocumentService.didOpen(openParams("file:///workspace/ignored-open-after-exit.lua", "local ="))
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

        assertTrue(client.published.isEmpty(), "notifications after repeated exit should stay ignored")
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
            assertFutureFails(request, "text document requests after shutdown should complete exceptionally")
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
