package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
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
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LspLifecycleDiagnosticsTddTest {
    @Test
    fun initialize_advertises_full_text_document_sync() {
        val server = LuaLanguageServer()

        val capabilities = server.initialize(InitializeParams()).get().capabilities

        assertEquals(TextDocumentSyncKind.Full, capabilities.textDocumentSync.left)
    }

    @Test
    fun initialize_advertises_core_document_query_capabilities() {
        val server = LuaLanguageServer()

        val capabilities = server.initialize(InitializeParams()).get().capabilities

        assertNotNull(capabilities.hoverProvider)
        assertNotNull(capabilities.completionProvider)
        assertNotNull(capabilities.definitionProvider)
        assertNotNull(capabilities.declarationProvider)
        assertNotNull(capabilities.referencesProvider)
        assertNotNull(capabilities.documentHighlightProvider)
    }

    @Test
    fun shutdown_after_initialize_completes_with_success_code() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()

        assertEquals(0, server.shutdown().get())
    }

    @Test
    fun did_open_invalid_document_publishes_parse_diagnostics() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { diagnostics -> published += diagnostics })

        textDocuments.didOpen(openParams("file:///workspace/open-invalid.lua", "local ="))

        assertTrue(published.single().diagnostics.isNotEmpty(), "invalid Lua should publish parse diagnostics")
        assertEquals(DiagnosticSeverity.Error, published.single().diagnostics.first().severity)
    }

    @Test
    fun did_change_valid_to_invalid_publishes_new_diagnostics() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { diagnostics -> published += diagnostics })
        val uri = "file:///workspace/change-invalid.lua"
        textDocuments.didOpen(openParams(uri, "local value = 1\nreturn value"))

        textDocuments.didChange(changeParams(uri, 2, "local ="))

        assertTrue(published.first().diagnostics.isEmpty())
        assertTrue(published.last().diagnostics.isNotEmpty(), "invalid replacement should publish diagnostics")
    }

    @Test
    fun did_change_invalid_to_valid_clears_diagnostics() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { diagnostics -> published += diagnostics })
        val uri = "file:///workspace/change-valid.lua"
        textDocuments.didOpen(openParams(uri, "local ="))

        textDocuments.didChange(changeParams(uri, 2, "local value = 1\nreturn value"))

        assertTrue(published.first().diagnostics.isNotEmpty())
        assertTrue(published.last().diagnostics.isEmpty(), "valid replacement should clear diagnostics")
    }

    @Test
    fun did_close_publishes_empty_diagnostics() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { diagnostics -> published += diagnostics })
        val uri = "file:///workspace/close-invalid.lua"
        textDocuments.didOpen(openParams(uri, "local ="))

        textDocuments.didClose(closeParams(uri))

        assertTrue(published.first().diagnostics.isNotEmpty())
        assertEquals(uri, published.last().uri)
        assertTrue(published.last().diagnostics.isEmpty())
    }

    @Test
    fun configuration_change_republishes_open_document_diagnostics() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/config-republish.lua"
        server.textDocumentService.didOpen(openParams(uri, "local String = require(\"String\")\nreturn String.__class"))
        client.published.clear()

        server.workspaceService.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "androlua.imports" to listOf("String"),
                    "jvm.importPrefixes" to listOf("java.lang")
                )
            )
        )

        assertEquals(listOf(uri), client.published.map { it.uri })
        assertTrue(client.published.single().diagnostics.isEmpty())
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

    private fun customPrefixService(): LuaLanguageService {
        return LuaLanguageService(
            JvmWorkspaceEngine(
                configuration = JvmWorkspaceConfiguration(
                    androluaImports = listOf("BigDecimal"),
                    importPrefixes = listOf("java.math")
                )
            )
        ).also { it.initialize(InitializeParams()) }
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
