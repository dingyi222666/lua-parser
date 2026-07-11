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
    fun initialize_advertises_symbol_and_signature_capabilities() {
        val server = LuaLanguageServer()

        val capabilities = server.initialize(InitializeParams()).get().capabilities

        assertNotNull(capabilities.documentSymbolProvider)
        assertNotNull(capabilities.workspaceSymbolProvider)
        assertNotNull(capabilities.signatureHelpProvider)
        assertEquals(listOf("(", ","), capabilities.signatureHelpProvider.triggerCharacters)
        assertEquals(listOf(")"), capabilities.signatureHelpProvider.retriggerCharacters)
    }

    @Test
    fun initialize_accepts_workspace_folders_without_losing_services() {
        val server = LuaLanguageServer()

        server.initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
            }
        ).get()

        assertNotNull(server.textDocumentService)
        assertNotNull(server.workspaceService)
    }

    @Test
    fun shutdown_after_initialize_completes_with_success_code() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()

        assertEquals(0, server.shutdown().get())
    }

    @Test
    fun shutdown_before_initialize_is_rejected() {
        val server = LuaLanguageServer()

        assertFutureFails(server.shutdown(), "shutdown before initialize should complete exceptionally")
    }

    @Test
    fun initialize_after_shutdown_is_rejected() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        server.shutdown().get()

        assertFutureFails(server.initialize(InitializeParams()), "initialize after shutdown should complete exceptionally")
    }

    @Test
    fun initialize_after_exit_is_rejected() {
        val server = LuaLanguageServer()
        server.exit()

        assertFutureFails(server.initialize(InitializeParams()), "initialize after exit should complete exceptionally")
    }

    @Test
    fun exit_after_shutdown_allows_no_more_diagnostics_publication() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        server.shutdown().get()
        server.exit()

        server.textDocumentService.didOpen(openParams("file:///workspace/after-exit.lua", "return 1"))

        assertTrue(client.published.isEmpty(), "text document notifications after exit should be ignored")
    }

    @Test
    fun did_open_valid_document_publishes_empty_diagnostics_from_text_document_service() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { diagnostics -> published += diagnostics })
        val uri = "file:///workspace/open-valid.lua"

        textDocuments.didOpen(openParams(uri, "local value = 1\nreturn value"))

        assertEquals(listOf(uri), published.map { it.uri })
        assertTrue(published.single().diagnostics.isEmpty())
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
    fun did_open_keeps_document_available_for_symbol_queries() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val uri = "file:///workspace/open-symbols.lua"

        service.didOpen(openParams(uri, "local value = 1\nlocal function render()\n    return value\nend"))

        val symbols = service.documentSymbols("workspace/open-symbols.lua").map { it.name }
        assertTrue("value" in symbols)
        assertTrue("render" in symbols)
    }

    @Test
    fun server_connected_client_receives_open_diagnostics() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()

        server.textDocumentService.didOpen(openParams("file:///workspace/server-open.lua", "return 1"))

        assertEquals(listOf("file:///workspace/server-open.lua"), client.published.map { it.uri })
        assertTrue(client.published.single().diagnostics.isEmpty())
    }

    @Test
    fun did_change_full_sync_replaces_document_text_for_symbols() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val uri = "file:///workspace/change-symbols.lua"
        service.didOpen(openParams(uri, "local before = 1\nreturn before"))

        service.didChange(changeParams(uri, 2, "local after = 2\nreturn after"))

        val symbols = service.documentSymbols("workspace/change-symbols.lua").map { it.name }
        assertFalse("before" in symbols)
        assertTrue("after" in symbols)
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
    fun did_change_with_stale_version_is_ignored() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { diagnostics -> published += diagnostics })
        val uri = "file:///workspace/change-stale.lua"
        textDocuments.didOpen(openParams(uri, "local current = 1\nreturn current", version = 4))

        textDocuments.didChange(changeParams(uri, 3, "local stale ="))

        assertEquals(1, published.size, "stale didChange should not publish diagnostics")
        assertTrue(service.diagnostics("workspace/change-stale.lua").diagnostics.isEmpty())
    }

    @Test
    fun did_change_with_range_edit_updates_only_that_range() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val uri = "file:///workspace/change-range.lua"
        service.didOpen(openParams(uri, "local first = 1\nlocal second = 2\nreturn first + second"))

        service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(
                    TextDocumentContentChangeEvent(
                        Range(Position(1, 6), Position(1, 12)),
                        6,
                        "renamed"
                    )
                )
            )
        )

        val symbols = service.documentSymbols("workspace/change-range.lua").map { it.name }
        assertTrue("first" in symbols)
        assertTrue("renamed" in symbols)
    }

    @Test
    fun did_change_unknown_document_is_rejected() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { diagnostics -> published += diagnostics })

        textDocuments.didChange(changeParams("file:///workspace/missing.lua", 1, "return 1"))

        assertTrue(published.isEmpty(), "didChange for an unopened document should be rejected")
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
    fun did_close_removes_document_from_symbol_index() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val uri = "file:///workspace/close-symbols.lua"
        service.didOpen(openParams(uri, "local removed = 1\nreturn removed"))

        service.didClose(closeParams(uri))

        assertTrue(service.documentSymbols("workspace/close-symbols.lua").isEmpty())
    }

    @Test
    fun did_close_unknown_document_still_clears_client_diagnostics() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val diagnostics = service.didClose(closeParams("file:///workspace/never-opened.lua"))

        assertEquals("file:///workspace/never-opened.lua", diagnostics.uri)
        assertTrue(diagnostics.diagnostics.isEmpty())
    }

    @Test
    fun diagnostics_query_uses_current_open_document_snapshot() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val uri = "file:///workspace/query-current.lua"
        service.didOpen(openParams(uri, "local ="))

        val first = service.diagnostics("workspace/query-current.lua")
        service.didChange(changeParams(uri, 2, "local current = 1\nreturn current"))
        val second = service.diagnostics("workspace/query-current.lua")

        assertTrue(first.diagnostics.isNotEmpty())
        assertTrue(second.diagnostics.isEmpty())
    }

    @Test
    fun invalid_lua_diagnostic_has_precise_range() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val diagnostics = service.didOpen(openParams("file:///workspace/range.lua", "local value =\nreturn value"))

        val range = diagnostics.diagnostics.first().range
        assertEquals(0, range.start.line)
        assertTrue(range.start.character >= 12, "diagnostic should point near the missing expression")
    }

    @Test
    fun invalid_lua_recovery_keeps_symbols_before_error_available() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        service.didOpen(openParams("file:///workspace/recovery-before.lua", "local before = 1\nlocal =\nreturn before"))

        val symbols = service.documentSymbols("workspace/recovery-before.lua").map { it.name }
        assertTrue("before" in symbols)
    }

    @Test
    fun invalid_lua_recovery_keeps_symbols_after_error_available() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        service.didOpen(openParams("file:///workspace/recovery-after.lua", "local =\nlocal after = 2\nreturn after"))

        val symbols = service.documentSymbols("workspace/recovery-after.lua").map { it.name }
        assertTrue("after" in symbols)
    }

    @Test
    fun workspace_metadata_direct_class_list_enables_definitions() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        service.setWorkspaceMetadata(mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays"))
        val uri = "file:///workspace/direct-metadata.lua"
        service.didOpen(openParams(uri, "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current"))

        val definitions = service.definition(
            org.eclipse.lsp4j.DefinitionParams(TextDocumentIdentifier(uri), Position(1, 23))
        )

        assertEquals("file:///__jvm__/classes/java/util/Arrays.lua", definitions.single().uri)
    }

    @Test
    fun workspace_service_flat_configuration_updates_metadata() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        LuaWorkspaceService(service).didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "androlua.imports" to listOf("String"),
                    "jvm.importPrefixes" to listOf("java.lang")
                )
            )
        )
        val uri = "file:///workspace/flat-config.lua"
        service.didOpen(openParams(uri, "local String = require(\"String\")\nreturn String.__class"))

        val definitions = service.definition(
            org.eclipse.lsp4j.DefinitionParams(TextDocumentIdentifier(uri), Position(1, 14))
        )

        assertEquals("file:///__jvm__/classes/java/lang/String.lua", definitions.single().uri)
    }

    @Test
    fun workspace_service_nested_configuration_updates_metadata() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        LuaWorkspaceService(service).didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "jvm" to mapOf(
                        "classes" to "java.util.Locale",
                        "importPrefixes" to "java.util"
                    ),
                    "androlua" to mapOf("imports" to "Locale")
                )
            )
        )
        val uri = "file:///workspace/nested-config.lua"
        service.didOpen(openParams(uri, "local Locale = require(\"Locale\")\nlocal current = Locale.getDefault\nreturn current"))

        val definitions = service.definition(
            org.eclipse.lsp4j.DefinitionParams(TextDocumentIdentifier(uri), Position(1, 24))
        )

        assertEquals("file:///__jvm__/classes/java/util/Locale.lua", definitions.single().uri)
    }

    @Test
    fun workspace_metadata_without_import_prefixes_preserves_base_custom_prefixes() {
        val emptyMetadataService = customPrefixService()
        emptyMetadataService.setWorkspaceMetadata(emptyMap())
        val emptyUri = "file:///workspace/base-prefix-empty-metadata.lua"
        emptyMetadataService.didOpen(openParams(emptyUri, "local BigDecimal = require(\"BigDecimal\")\nreturn BigDecimal.ZERO"))

        val emptyDefinitions = emptyMetadataService.definition(
            org.eclipse.lsp4j.DefinitionParams(TextDocumentIdentifier(emptyUri), Position(1, 20))
        )

        assertEquals("file:///__jvm__/classes/java/math/BigDecimal.lua", emptyDefinitions.single().uri)

        val unrelatedMetadataService = customPrefixService()
        unrelatedMetadataService.setWorkspaceMetadata(mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Locale"))
        val unrelatedUri = "file:///workspace/base-prefix-unrelated-metadata.lua"
        unrelatedMetadataService.didOpen(openParams(unrelatedUri, "local BigDecimal = require(\"BigDecimal\")\nreturn BigDecimal.ZERO"))

        val unrelatedDefinitions = unrelatedMetadataService.definition(
            org.eclipse.lsp4j.DefinitionParams(TextDocumentIdentifier(unrelatedUri), Position(1, 20))
        )

        assertEquals("file:///__jvm__/classes/java/math/BigDecimal.lua", unrelatedDefinitions.single().uri)
    }

    @Test
    fun server_workspace_service_configuration_affects_text_document_queries() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        server.workspaceService.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "androlua.imports" to listOf("String"),
                    "jvm.importPrefixes" to listOf("java.lang")
                )
            )
        )
        val uri = "file:///workspace/server-config.lua"
        server.textDocumentService.didOpen(openParams(uri, "local String = require(\"String\")\nreturn String.__class"))

        val definitions = server.textDocumentService.definition(
            org.eclipse.lsp4j.DefinitionParams(TextDocumentIdentifier(uri), Position(1, 14))
        ).get().left

        assertEquals("file:///__jvm__/classes/java/lang/String.lua", definitions.single().uri)
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

    @Test
    fun unchanged_configuration_does_not_republish_open_document_diagnostics() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/config-unchanged.lua"
        val settings = mapOf(
            "androlua.imports" to listOf("String"),
            "jvm.importPrefixes" to listOf("java.lang")
        )
        server.textDocumentService.didOpen(openParams(uri, "local String = require(\"String\")\nreturn String.__class"))
        client.published.clear()
        server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings))
        client.published.clear()

        server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings.toMap()))

        assertTrue(client.published.isEmpty(), "unchanged configuration should not republish diagnostics")
    }

    @Test
    fun empty_configuration_does_not_republish_open_document_diagnostics() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/config-empty.lua"
        server.textDocumentService.didOpen(openParams(uri, "return 1"))
        client.published.clear()

        server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(emptyMap<String, Any>()))

        assertTrue(client.published.isEmpty(), "empty configuration should not republish diagnostics")
    }

    @Test
    fun unrelated_configuration_does_not_republish_open_document_diagnostics() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/config-unrelated.lua"
        server.textDocumentService.didOpen(openParams(uri, "return 1"))
        client.published.clear()

        server.workspaceService.didChangeConfiguration(
            DidChangeConfigurationParams(mapOf("editor" to mapOf("tabSize" to 4)))
        )

        assertTrue(client.published.isEmpty(), "unrelated configuration should not republish diagnostics")
    }

    @Test
    fun unrelated_configuration_after_effective_configuration_does_not_republish_open_document_diagnostics() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/config-unrelated-after-effective.lua"
        val settings = mapOf(
            "androlua.imports" to listOf("String"),
            "jvm.importPrefixes" to listOf("java.lang")
        )
        server.textDocumentService.didOpen(openParams(uri, "local String = require(\"String\")\nreturn String.__class"))
        client.published.clear()
        server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(settings))
        client.published.clear()

        server.workspaceService.didChangeConfiguration(
            DidChangeConfigurationParams(mapOf("workspace" to mapOf("ignored" to true)))
        )

        assertTrue(client.published.isEmpty(), "unrelated configuration should not republish diagnostics")
    }

    @Test
    fun invalid_configuration_does_not_republish_open_document_diagnostics() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/config-invalid.lua"
        server.textDocumentService.didOpen(openParams(uri, "return 1"))
        client.published.clear()

        server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams("not-a-settings-map"))

        assertTrue(client.published.isEmpty(), "invalid configuration should not republish diagnostics")
    }

    @Test
    fun server_did_close_clears_connected_client_diagnostics() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/server-close.lua"
        server.textDocumentService.didOpen(openParams(uri, "local ="))

        server.textDocumentService.didClose(closeParams(uri))

        assertTrue(client.published.first().diagnostics.isNotEmpty())
        assertEquals(uri, client.published.last().uri)
        assertTrue(client.published.last().diagnostics.isEmpty())
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
