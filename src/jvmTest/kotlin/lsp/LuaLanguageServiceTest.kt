package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.messages.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LuaLanguageServiceTest {
    @Test
    fun language_service_exposes_hover_completion_definition_and_diagnostics() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        service.setWorkspaceMetadata(mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays"))

        val uri = "file:///workspace/main.lua"
        val source = "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current"
        val diagnostics = service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, source)
            )
        )

        val hover = service.hover(
            HoverParams(
                TextDocumentIdentifier(uri),
                Position(1, 28)
            )
        )
        val completions = service.completion("workspace/main.lua", 1, 28)
        val definitions = service.definition(
            DefinitionParams(
                TextDocumentIdentifier(uri),
                Position(1, 28)
            )
        )

        assertTrue(diagnostics.diagnostics.isEmpty())
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("asList"))
        assertTrue(completions.items.any { it.label == "asList" })
        assertEquals("file:///__jvm__/classes/java/util/Arrays.lua", definitions.single().uri)
    }

    @Test
    fun text_document_service_publishes_diagnostics_and_returns_query_results() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        service.setWorkspaceMetadata(mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays"))

        val published = mutableListOf<String>()
        val textDocuments = LuaTextDocumentService(service) { published += it.uri }
        val uri = "file:///workspace/main.lua"
        textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn Arrays.asList")
            )
        )

        val hover = textDocuments.hover(HoverParams(TextDocumentIdentifier(uri), Position(1, 28))).get()
        val completion = textDocuments.completion(
            CompletionParams(TextDocumentIdentifier(uri), Position(1, 28))
        ).get()
        val definitions = textDocuments.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(1, 28))
        ).get()
        val references = textDocuments.references(
            ReferenceParams(TextDocumentIdentifier(uri), Position(1, 28), ReferenceContext(true))
        ).get()
        val locations = definitions.left

        assertEquals(listOf(uri), published)
        assertNotNull(hover)
        assertTrue(completion.right.items.any { it.label == "asList" })
        assertEquals("file:///__jvm__/classes/java/util/Arrays.lua", locations.single().uri)
        assertTrue(references.any { it.uri == "file:///__jvm__/classes/java/util/Arrays.lua" })
        assertTrue(references.any { it.uri == uri })
    }

    @Test
    fun language_service_exposes_references_for_workspace_and_provider_symbols() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        service.setWorkspaceMetadata(mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays"))

        val uri = "file:///workspace/main.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn Arrays.asList")
            )
        )

        val references = service.references(
            ReferenceParams(TextDocumentIdentifier(uri), Position(1, 28), ReferenceContext(true))
        )

        assertTrue(references.any { it.uri == "file:///__jvm__/classes/java/util/Arrays.lua" })
        assertEquals(2, references.count { it.uri == uri })
    }

    @Test
    fun workspace_service_applies_jvm_configuration_metadata() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val workspace = LuaWorkspaceService(service)
        workspace.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "androlua.imports" to listOf("String"),
                    "jvm.importPrefixes" to listOf("java.lang")
                )
            )
        )

        val uri = "file:///workspace/main.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local String = require(\"String\")\nreturn String.__class")
            )
        )

        val definitions = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(1, 14))
        )

        assertEquals("file:///__jvm__/classes/java/lang/String.lua", definitions.single().uri)
    }

    @Test
    fun language_service_resolves_wildcard_imports_and_dynamic_jvm_bindings() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val wildcardUri = "file:///workspace/wildcard.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(wildcardUri, "lua", 1, "import \"java.util.*\"\nlocal current = Locale.getDefault\nreturn current")
            )
        )
        val wildcardDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(wildcardUri), Position(1, 16))
        )

        val importUri = "file:///workspace/import.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(importUri, "lua", 1, "local import = require(\"import\")\nlocal Locale = import(\"java.util.Locale\")\nlocal current = Locale.getDefault\nreturn current")
            )
        )
        val importDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(importUri), Position(2, 23))
        )

        val bindUri = "file:///workspace/bind.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(bindUri, "lua", 1, "local Locale = luajava.bindClass(\"java.util.Locale\")\nlocal current = Locale.getDefault\nreturn current")
            )
        )
        val bindDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(bindUri), Position(1, 23))
        )

        assertEquals("file:///__jvm__/classes/java/util/Locale.lua", wildcardDefinition.single().uri)
        assertEquals("file:///__jvm__/classes/java/util/Locale.lua", importDefinition.single().uri)
        assertEquals("file:///__jvm__/classes/java/util/Locale.lua", bindDefinition.single().uri)
    }

    @Test
    fun language_server_initializes_and_exposes_services() {
        val server = LuaLanguageServer()
        val initialize = server.initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
            }
        ).get()

        assertNotNull(initialize.capabilities.completionProvider)
        assertNotNull(initialize.capabilities.referencesProvider)
        assertNotNull(server.textDocumentService)
        assertNotNull(server.workspaceService)
        assertEquals(0, server.shutdown().get())
    }
}
