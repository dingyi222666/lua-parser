package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LuaLanguageServiceTest {
    @Test
    fun language_service_exposes_signature_help_for_workspace_functions() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/signature-help.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "---@param value number\n---@param label string\nlocal function render(value, label)\n    return label\nend\nlocal current = render(1, \"hi\")\nreturn current")
            )
        )

        val signatureHelp = service.signatureHelp(
            SignatureHelpParams(
                TextDocumentIdentifier(uri),
                Position(5, 25)
            )
        )

        assertNotNull(signatureHelp)
        assertEquals("fun(value: number, label: string): string", signatureHelp.signatures.single().label)
        assertEquals(listOf("value: number", "label: string"), signatureHelp.signatures.single().parameters.map { it.label.left })
        assertEquals(0, signatureHelp.activeSignature)
        assertEquals(1, signatureHelp.activeParameter)
    }

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
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { diagnostics -> published += diagnostics.uri })
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
    fun language_service_uses_androlua_overlay_globals_by_default() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/androlua-overlay.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "return import, loadlayout, loadbitmap, loadmenu")
            )
        )

        val importDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(0, 7))
        )
        val loadlayoutDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(0, 15))
        )
        val loadbitmapDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(0, 27))
        )
        val loadmenuDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(0, 39))
        )

        assertTrue(importDefinition.isEmpty())
        assertTrue(loadlayoutDefinition.isEmpty())
        assertTrue(loadbitmapDefinition.isEmpty())
        assertTrue(loadmenuDefinition.isEmpty())
    }

    @Test
    fun language_service_accumulates_wildcard_import_packages_for_later_unqualified_android_lua_resolution() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val workspace = LuaWorkspaceService(service)
        workspace.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "jvm.androidJar" to "G:/Android/Sdk/platforms/android-35/android.jar"
                )
            )
        )

        val uri = "file:///workspace/wildcard-prefix.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "import \"android.widget.*\"\nlocal current = TextView.BufferType\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(1, 16))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(1, 16))
        )
        val completions = service.completion("workspace/wildcard-prefix.lua", 1, 23)

        assertEquals("file:///__jvm__/classes/android/widget/TextView.lua", definition.single().uri)
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("TextView"))
        assertTrue(completions.items.any { it.label == "TextView" })
    }

    @Test
    fun language_service_exposes_dynamic_wildcard_import_package_members() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val workspace = LuaWorkspaceService(service)
        workspace.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "jvm.androidJar" to "G:/Android/Sdk/platforms/android-35/android.jar"
                )
            )
        )

        val uri = "file:///workspace/import-package.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local import = require(\"import\")\nlocal widget = import(\"android.widget.*\")\nlocal current = widget.TextView.BufferType\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(2, 23))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(2, 23))
        )
        val completions = service.completion("workspace/import-package.lua", 1, 20)

        assertEquals("file:///__jvm__/classes/android/widget/TextView.lua", definition.single().uri)
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("TextView"))
        assertTrue(completions.items.any { it.label == "TextView" })
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
        assertNotNull(initialize.capabilities.declarationProvider)
        assertNotNull(initialize.capabilities.documentSymbolProvider)
        assertNotNull(initialize.capabilities.workspaceSymbolProvider)
        assertNotNull(server.textDocumentService)
        assertNotNull(server.workspaceService)
        assertEquals(0, server.shutdown().get())
    }
}
