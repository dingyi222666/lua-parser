package lsp

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
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
 * Corpus for LSP workspace/didChangeConfiguration → open-document diagnostics republish.
 *
 * Acceptance:
 * - Settings changes republish diagnostics for open documents without a full server restart.
 * - Invalid configuration values degrade safely and keep the prior workspace metadata snapshot.
 *
 * Production [LuaWorkspaceService] is exercised only via public APIs; this task is test-only.
 */
class LspDidChangeConfigurationRepublishTddTest {

    @Test
    fun settings_change_republishes_open_document_diagnostics_without_server_restart() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()

        val uri = "file:///workspace/config-republish-no-restart.lua"
        server.textDocumentService.didOpen(
            openParams(uri, "local String = require(\"String\")\nreturn String.__class")
        )
        client.published.clear()

        // No shutdown/re-initialize: configuration is applied live.
        server.workspaceService.didChangeConfiguration(
            DidChangeConfigurationParams(stringImportSettings())
        )

        assertEquals(listOf(uri), client.published.map { it.uri })
        assertTrue(
            client.published.single().diagnostics.isEmpty(),
            "Republished diagnostics after configuration should clear unresolved-require noise once imports resolve"
        )

        val definitions = server.textDocumentService.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(1, 14))
        ).get().left
        assertEquals("file:///__jvm__/classes/java/lang/String.lua", definitions.single().uri)
    }

    @Test
    fun invalid_configuration_keeps_prior_metadata_snapshot_and_does_not_republish() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { diagnostics -> published += diagnostics }
        )
        val workspace = LuaWorkspaceService(
            languageService = languageService,
            onConfigurationChanged = textDocuments::republishDiagnostics
        )

        val uri = "file:///workspace/config-invalid-keeps-snapshot.lua"
        textDocuments.didOpen(
            openParams(uri, "local String = require(\"String\")\nreturn String.__class")
        )
        workspace.didChangeConfiguration(DidChangeConfigurationParams(stringImportSettings()))
        val metadataAfterValid = workspace.currentWorkspaceMetadata()
        assertTrue(metadataAfterValid.isNotEmpty(), "valid configuration must establish a metadata snapshot")
        published.clear()

        workspace.didChangeConfiguration(DidChangeConfigurationParams("not-a-settings-map"))
        workspace.didChangeConfiguration(DidChangeConfigurationParams(42))
        workspace.didChangeConfiguration(DidChangeConfigurationParams(listOf("jvm.classes")))

        assertTrue(
            published.isEmpty(),
            "invalid configuration values must not republish open-document diagnostics"
        )
        assertEquals(
            metadataAfterValid,
            workspace.currentWorkspaceMetadata(),
            "invalid configuration must keep the prior workspace metadata snapshot"
        )

        // Prior snapshot remains live for queries without restart.
        val definitions = textDocuments.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(1, 14))
        ).get().left
        assertEquals("file:///__jvm__/classes/java/lang/String.lua", definitions.single().uri)
    }

    @Test
    fun nested_jvm_and_androlua_sections_update_metadata_and_republish() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { diagnostics -> published += diagnostics }
        )
        val workspace = LuaWorkspaceService(
            languageService = languageService,
            onConfigurationChanged = textDocuments::republishDiagnostics
        )

        val uri = "file:///workspace/config-nested-sections.lua"
        textDocuments.didOpen(
            openParams(uri, "local String = require(\"String\")\nreturn String.__class")
        )
        published.clear()

        workspace.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "jvm" to mapOf(
                        "classes" to listOf("java.lang.String"),
                        "importPrefixes" to listOf("java.lang")
                    ),
                    "androlua" to mapOf(
                        "imports" to listOf("String")
                    )
                )
            )
        )

        assertEquals(listOf(uri), published.map { it.uri })
        val metadata = workspace.currentWorkspaceMetadata()
        assertTrue(
            metadata[JvmClassModuleProvider.CLASSES_METADATA_KEY]?.contains("java.lang.String") == true,
            "nested jvm.classes must be applied to workspace metadata"
        )
        assertTrue(
            metadata[JvmClassModuleProvider.IMPORTS_METADATA_KEY]?.contains("String") == true,
            "nested androlua.imports must be applied to workspace metadata"
        )

        val definitions = textDocuments.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(1, 14))
        ).get().left
        assertEquals("file:///__jvm__/classes/java/lang/String.lua", definitions.single().uri)
    }

    @Test
    fun gson_json_object_settings_republish_open_document_diagnostics() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { diagnostics -> published += diagnostics }
        )
        val workspace = LuaWorkspaceService(
            languageService = languageService,
            onConfigurationChanged = textDocuments::republishDiagnostics
        )

        val uri = "file:///workspace/config-json-object.lua"
        textDocuments.didOpen(openParams(uri, "return 1"))
        published.clear()

        val settings = JsonObject().apply {
            add(
                "jvm",
                JsonObject().apply {
                    add("classes", com.google.gson.JsonArray().apply { add(JsonPrimitive("java.util.Locale")) })
                }
            )
        }

        workspace.didChangeConfiguration(DidChangeConfigurationParams(settings))

        assertEquals(listOf(uri), published.map { it.uri })
        assertTrue(
            workspace.currentWorkspaceMetadata()[JvmClassModuleProvider.CLASSES_METADATA_KEY]
                ?.contains("java.util.Locale") == true,
            "JsonObject settings must parse into workspace metadata"
        )
    }

    private fun stringImportSettings(): Map<String, Any> {
        return mapOf(
            "androlua.imports" to listOf("String"),
            "jvm.importPrefixes" to listOf("java.lang")
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
