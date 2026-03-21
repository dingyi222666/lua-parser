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
    fun language_service_advances_signature_help_when_cursor_is_between_arguments() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/signature-help-between.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "---@param value number\n---@param label string\nlocal function render(value, label)\n    return label\nend\nlocal current = render(1,  \"hi\")\nreturn current")
            )
        )

        val signatureHelp = service.signatureHelp(
            SignatureHelpParams(
                TextDocumentIdentifier(uri),
                Position(5, 24)
            )
        )

        assertNotNull(signatureHelp)
        assertEquals(1, signatureHelp.activeParameter)
    }

    @Test
    fun language_service_signature_help_supports_short_string_calls_with_trailing_arguments() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/signature-help-short-string.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    uri,
                    "lua",
                    1,
                    "---@param className string\n---@param memberName string\n---@return fun(): string\nlocal function loadLib(className, memberName)\n    return function()\n        return memberName\n    end\nend\nlocal current = loadLib \"java.util.Locale\", \"getDefault\"\nreturn current"
                )
            )
        )

        val signatureHelp = service.signatureHelp(
            SignatureHelpParams(
                TextDocumentIdentifier(uri),
                Position(8, 48)
            )
        )

        assertNotNull(signatureHelp)
        assertEquals("fun(className: string, memberName: string): fun(): string", signatureHelp.signatures.single().label)
        assertEquals(listOf("className: string", "memberName: string"), signatureHelp.signatures.single().parameters.map { it.label.left })
        assertEquals(1, signatureHelp.activeParameter)
    }

    @Test
    fun language_service_offsets_signature_help_for_method_receivers() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/signature-help-method.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local box = {}\n---@param self table\n---@param value number\n---@param label string\nfunction box:render(value, label)\n    return label\nend\nlocal current = box:render(1, \"hi\")\nreturn current")
            )
        )

        val firstArgumentHelp = service.signatureHelp(
            SignatureHelpParams(
                TextDocumentIdentifier(uri),
                Position(7, 27)
            )
        )
        val secondArgumentHelp = service.signatureHelp(
            SignatureHelpParams(
                TextDocumentIdentifier(uri),
                Position(7, 30)
            )
        )

        assertNotNull(firstArgumentHelp)
        assertEquals(listOf("self: table", "value: number", "label: string"), firstArgumentHelp.signatures.single().parameters.map { it.label.left })
        assertEquals(1, firstArgumentHelp.activeParameter)
        assertNotNull(secondArgumentHelp)
        assertEquals(2, secondArgumentHelp.activeParameter)
    }

    @Test
    fun text_document_service_exposes_signature_help_queries() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/signature-help-text-document.lua"
        val textDocuments = LuaTextDocumentService(service)
        textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "---@param value number\n---@param label string\nlocal function render(value, label)\n    return label\nend\nlocal current = render(1, \"hi\")\nreturn current")
            )
        )

        val signatureHelp = textDocuments.signatureHelp(
            SignatureHelpParams(
                TextDocumentIdentifier(uri),
                Position(5, 25)
            )
        ).get()

        assertNotNull(signatureHelp)
        assertEquals("fun(value: number, label: string): string", signatureHelp.signatures.single().label)
        assertEquals(1, signatureHelp.activeParameter)
    }

    @Test
    fun language_service_exposes_document_highlights_for_local_symbols() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/highlights.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local value = 1\nlocal copy = value\nreturn value + copy")
            )
        )

        val highlights = service.documentHighlights(
            DocumentHighlightParams(
                TextDocumentIdentifier(uri),
                Position(1, 13)
            )
        )

        assertEquals(3, highlights.size)
        assertEquals(listOf(0, 1, 2), highlights.map { it.range.start.line }.sorted())
    }

    @Test
    fun text_document_service_exposes_document_highlights() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/highlights-text-document.lua"
        val textDocuments = LuaTextDocumentService(service)
        textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local value = 1\nlocal copy = value\nreturn value + copy")
            )
        )

        val highlights = textDocuments.documentHighlight(
            DocumentHighlightParams(
                TextDocumentIdentifier(uri),
                Position(1, 13)
            )
        ).get()

        assertEquals(3, highlights.size)
        assertEquals(listOf(0, 1, 2), highlights.map { it.range.start.line }.sorted())
    }

    @Test
    fun language_service_exposes_declaration_queries_for_local_symbols() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/declaration.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local value = 1\nlocal copy = value\nreturn value + copy")
            )
        )

        val declaration = service.declaration(
            DeclarationParams(
                TextDocumentIdentifier(uri),
                Position(2, 7)
            )
        )

        assertEquals(1, declaration.size)
        assertEquals(uri, declaration.single().uri)
        assertEquals(0, declaration.single().range.start.line)
    }

    @Test
    fun text_document_service_exposes_declaration_queries() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        service.setWorkspaceMetadata(mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays"))

        val uri = "file:///workspace/declaration-text-document.lua"
        val textDocuments = LuaTextDocumentService(service)
        textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn Arrays.asList")
            )
        )

        val declaration = textDocuments.declaration(
            DeclarationParams(
                TextDocumentIdentifier(uri),
                Position(2, 14)
            )
        ).get()

        assertEquals("file:///__jvm__/classes/java/util/Arrays.lua", declaration.left.single().uri)
    }

    @Test
    fun language_service_exposes_document_symbols_for_workspace_and_provider_entries() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        service.setWorkspaceMetadata(mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays"))

        val uri = "file:///workspace/symbols.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local Arrays = require(\"Arrays\")\nlocal function render(input)\n    local current = Arrays.asList\n    return current(input)\nend\nreturn render")
            )
        )

        val documentSymbols = service.documentSymbols("workspace/symbols.lua")
        val workspaceSymbols = service.workspaceSymbols("Arrays")

        assertTrue(documentSymbols.any { it.name == "Arrays" })
        assertTrue(documentSymbols.any { it.name == "render" })
        assertTrue(documentSymbols.any { it.name == "current" })
        assertTrue(workspaceSymbols.any { it.name == "Arrays" && it.location.uri == uri })
        assertTrue(workspaceSymbols.any { it.name == "Arrays" && it.location.uri == "file:///__jvm__/classes/java/util/Arrays.lua" })
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
    fun workspace_service_accepts_nested_object_and_string_configuration_shapes() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val workspace = LuaWorkspaceService(service)
        workspace.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "jvm" to mapOf(
                        "androidJar" to "G:/Android/Sdk/platforms/android-35/android.jar",
                        "importPrefixes" to "java.lang\nandroid.widget"
                    ),
                    "androlua" to mapOf(
                        "imports" to "String"
                    )
                )
            )
        )

        val importUri = "file:///workspace/nested-config-import.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(importUri, "lua", 1, "local String = require(\"String\")\nreturn String.__class")
            )
        )

        val importDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(importUri), Position(1, 14))
        )

        val wildcardUri = "file:///workspace/nested-config-wildcard.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(wildcardUri, "lua", 1, "import \"android.widget.*\"\nlocal current = TextView.BufferType\nreturn current")
            )
        )

        val wildcardDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(wildcardUri), Position(1, 16))
        )

        assertEquals("file:///__jvm__/classes/java/lang/String.lua", importDefinition.single().uri)
        assertEquals("file:///__jvm__/classes/android/widget/TextView.lua", wildcardDefinition.single().uri)
    }

    @Test
    fun language_service_resolves_android_lua_default_prefix_fallbacks_without_explicit_imports() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/default-prefix.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local current = Locale.getDefault\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(0, 16))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(0, 16))
        )
        val completions = service.completion("workspace/default-prefix.lua", 0, 27)

        assertEquals("file:///__jvm__/classes/java/util/Locale.lua", definition.single().uri)
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("Locale"))
        assertTrue(completions.items.any { it.label == "getDefault" })
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
    fun language_service_accumulates_table_wildcard_import_packages_for_later_unqualified_android_lua_resolution() {
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

        val uri = "file:///workspace/wildcard-prefix-table.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local import = require(\"import\")\nlocal loaded = import({ \"android.widget.*\" })\nlocal current = TextView.BufferType\nreturn current and loaded")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(2, 16))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(2, 16))
        )
        val completions = service.completion("workspace/wildcard-prefix-table.lua", 2, 23)

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
    fun language_service_exposes_dex_prefixed_dynamic_wildcard_import_package_members() {
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

        val uri = "file:///workspace/import-package-dex.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local import = require(\"import\")\nlocal widget = import(\"plugin.dex:android.widget.*\")\nlocal current = widget.TextView.BufferType\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(2, 23))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(2, 23))
        )
        val completions = service.completion("workspace/import-package-dex.lua", 1, 20)

        assertEquals("file:///__jvm__/classes/android/widget/TextView.lua", definition.single().uri)
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("TextView"))
        assertTrue(completions.items.any { it.label == "TextView" })
    }

    @Test
    fun language_service_exposes_realiased_dynamic_wildcard_import_package_members() {
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

        val uri = "file:///workspace/import-package-aliased.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local import = require(\"import\")\nlocal load = import\nlocal again = load\nlocal widget = again \"android.widget.*\"\nlocal current = widget.TextView.BufferType\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(4, 23))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(4, 23))
        )
        val completions = service.completion("workspace/import-package-aliased.lua", 4, 20)

        assertEquals("file:///__jvm__/classes/android/widget/TextView.lua", definition.single().uri)
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("TextView"))
        assertTrue(completions.items.any { it.label == "TextView" })
    }

    @Test
    fun language_service_exposes_short_string_dynamic_wildcard_import_package_members() {
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

        val uri = "file:///workspace/import-package-short.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local import = require \"import\"\nlocal widget = import \"android.widget.*\"\nlocal current = widget.TextView.BufferType\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(2, 23))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(2, 23))
        )
        val completions = service.completion("workspace/import-package-short.lua", 1, 20)

        assertEquals("file:///__jvm__/classes/android/widget/TextView.lua", definition.single().uri)
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("TextView"))
        assertTrue(completions.items.any { it.label == "TextView" })
    }

    @Test
    fun language_service_references_dynamic_wildcard_import_members_through_class_providers() {
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

        val uri = "file:///workspace/import-package-references.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local import = require(\"import\")\nlocal widget = import(\"android.widget.*\")\nlocal current = widget.TextView\nreturn widget.TextView")
            )
        )

        val references = service.references(
            ReferenceParams(TextDocumentIdentifier(uri), Position(2, 23), ReferenceContext(true))
        )

        assertTrue(references.any { it.uri == "file:///__jvm__/classes/android/widget/TextView.lua" })
        assertEquals(2, references.count { it.uri == uri })
    }

    @Test
    fun language_service_references_realiased_dynamic_wildcard_import_members_through_class_providers() {
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

        val uri = "file:///workspace/import-package-aliased-references.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local import = require(\"import\")\nlocal load = import\nlocal again = load\nlocal widget = again \"android.widget.*\"\nlocal current = widget.TextView\nreturn widget.TextView")
            )
        )

        val references = service.references(
            ReferenceParams(TextDocumentIdentifier(uri), Position(4, 23), ReferenceContext(true))
        )

        assertTrue(references.any { it.uri == "file:///__jvm__/classes/android/widget/TextView.lua" })
        assertEquals(2, references.count { it.uri == uri })
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
    fun language_service_resolves_androlua_short_string_bind_and_import_calls() {
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

        val bindUri = "file:///workspace/bind-short.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(bindUri, "lua", 1, "local bindClass = luajava.bindClass\nlocal Context = bindClass \"android.content.Context\"\nlocal current = Context.WINDOW_SERVICE\nreturn current")
            )
        )
        val bindDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(bindUri), Position(2, 25))
        )

        val importUri = "file:///workspace/import-short.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(importUri, "lua", 1, "local import = require \"import\"\nlocal Context = import \"android.content.Context\"\nlocal current = Context.WINDOW_SERVICE\nreturn current")
            )
        )
        val importDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(importUri), Position(2, 25))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(importUri), Position(2, 29))
        )

        assertEquals("file:///__jvm__/classes/android/content/Context.lua", bindDefinition.single().uri)
        assertEquals("file:///__jvm__/classes/android/content/Context.lua", importDefinition.single().uri)
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("WINDOW_SERVICE"))
    }



    @Test
    fun language_service_resolves_aliased_import_calls_from_document_facts() {
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

        val uri = "file:///workspace/import-aliased.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local load = require(\"import\")\nlocal Context = load \"android.content.Context\"\nlocal current = Context.WINDOW_SERVICE\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(2, 25))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(2, 29))
        )

        assertEquals("file:///__jvm__/classes/android/content/Context.lua", definition.single().uri)
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("WINDOW_SERVICE"))
    }

    @Test
    fun language_service_resolves_realiased_import_and_bind_helpers() {
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

        val importUri = "file:///workspace/import-realiased.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(importUri, "lua", 1, "local import = require(\"import\")\nlocal load = import\nlocal again = load\nlocal Locale = again(\"java.util.Locale\")\nlocal current = Locale.getDefault\nreturn current")
            )
        )
        val importDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(importUri), Position(4, 25))
        )

        val bindUri = "file:///workspace/bind-realiased.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(bindUri, "lua", 1, "local bindClass = luajava.bindClass\nlocal bind = bindClass\nlocal again = bind\nlocal Context = again \"android.content.Context\"\nlocal current = Context.WINDOW_SERVICE\nreturn current")
            )
        )
        val bindDefinition = service.definition(
            DefinitionParams(TextDocumentIdentifier(bindUri), Position(4, 25))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(bindUri), Position(4, 29))
        )

        assertEquals("file:///__jvm__/classes/java/util/Locale.lua", importDefinition.single().uri)
        assertEquals("file:///__jvm__/classes/android/content/Context.lua", bindDefinition.single().uri)
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("WINDOW_SERVICE"))
    }

    @Test
    fun language_service_resolves_realiased_create_proxy_helpers() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/create-proxy-realiased.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local createProxy = luajava.createProxy\nlocal create = createProxy\nlocal proxy = create(\"java.lang.Runnable\", {})\nlocal current = proxy.run\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(3, 22))
        )
        val declaration = service.declaration(
            DeclarationParams(TextDocumentIdentifier(uri), Position(3, 22))
        )
        val references = service.references(
            ReferenceParams(TextDocumentIdentifier(uri), Position(3, 22), ReferenceContext(true))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(3, 22))
        )

        assertEquals("file:///__jvm__/classes/java/lang/Runnable.lua", definition.single().uri)
        assertEquals("file:///__jvm__/classes/java/lang/Runnable.lua", declaration.single().uri)
        assertTrue(references.any { it.uri == "file:///__jvm__/classes/java/lang/Runnable.lua" })
        assertTrue(references.any { it.uri == uri })
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("run"))
    }

    @Test
    fun language_service_resolves_multi_interface_create_proxy_helpers() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/create-proxy-multi-interface.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local proxy = luajava.createProxy(\"java.lang.Runnable\", \"java.util.Comparator\", {})\nlocal current = proxy.compare\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(1, 22))
        )
        val declaration = service.declaration(
            DeclarationParams(TextDocumentIdentifier(uri), Position(1, 22))
        )
        val references = service.references(
            ReferenceParams(TextDocumentIdentifier(uri), Position(1, 22), ReferenceContext(true))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(1, 22))
        )

        assertEquals("file:///__jvm__/classes/java/util/Comparator.lua", definition.single().uri)
        assertEquals("file:///__jvm__/classes/java/util/Comparator.lua", declaration.single().uri)
        assertTrue(references.any { it.uri == "file:///__jvm__/classes/java/util/Comparator.lua" })
        assertTrue(references.any { it.uri == uri })
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("compare"))
    }

    @Test
    fun language_service_resolves_realiased_load_lib_helpers() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/load-lib-realiased.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local loadLib = luajava.loadLib\nlocal load = loadLib\nlocal currentTimeMillis = load(\"java.lang.System\", \"currentTimeMillis\")\nlocal current = System.currentTimeMillis\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(3, 23))
        )
        val declaration = service.declaration(
            DeclarationParams(TextDocumentIdentifier(uri), Position(3, 23))
        )
        val references = service.references(
            ReferenceParams(TextDocumentIdentifier(uri), Position(3, 23), ReferenceContext(true))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(3, 23))
        )

        assertEquals("file:///__jvm__/classes/java/lang/System.lua", definition.single().uri)
        assertEquals("file:///__jvm__/classes/java/lang/System.lua", declaration.single().uri)
        assertTrue(references.any { it.uri == "file:///__jvm__/classes/java/lang/System.lua" })
        assertTrue(references.any { it.uri == uri })
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("currentTimeMillis"))
    }

    @Test
    fun language_service_resolves_short_string_realiased_create_proxy_helpers() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/create-proxy-short-realiased.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local createProxy = luajava.createProxy\nlocal create = createProxy\nlocal proxy = create \"java.lang.Runnable\", {}\nlocal current = proxy.run\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(3, 22))
        )
        val declaration = service.declaration(
            DeclarationParams(TextDocumentIdentifier(uri), Position(3, 22))
        )
        val references = service.references(
            ReferenceParams(TextDocumentIdentifier(uri), Position(3, 22), ReferenceContext(true))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(3, 22))
        )

        assertEquals("file:///__jvm__/classes/java/lang/Runnable.lua", definition.single().uri)
        assertEquals("file:///__jvm__/classes/java/lang/Runnable.lua", declaration.single().uri)
        assertTrue(references.any { it.uri == "file:///__jvm__/classes/java/lang/Runnable.lua" })
        assertTrue(references.any { it.uri == uri })
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("run"))
    }

    @Test
    fun language_service_resolves_short_string_realiased_load_lib_helpers() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/load-lib-short-realiased.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local loadLib = luajava.loadLib\nlocal load = loadLib\nlocal currentTimeMillis = load \"java.lang.System\", \"currentTimeMillis\"\nlocal current = System.currentTimeMillis\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(3, 23))
        )
        val declaration = service.declaration(
            DeclarationParams(TextDocumentIdentifier(uri), Position(3, 23))
        )
        val references = service.references(
            ReferenceParams(TextDocumentIdentifier(uri), Position(3, 23), ReferenceContext(true))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(3, 23))
        )

        assertEquals("file:///__jvm__/classes/java/lang/System.lua", definition.single().uri)
        assertEquals("file:///__jvm__/classes/java/lang/System.lua", declaration.single().uri)
        assertTrue(references.any { it.uri == "file:///__jvm__/classes/java/lang/System.lua" })
        assertTrue(references.any { it.uri == uri })
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("currentTimeMillis"))
    }

    @Test
    fun language_service_resolves_constructor_style_imported_jvm_class_members() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        val uri = "file:///workspace/constructor-call.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "import \"java.lang.StringBuilder\"\nlocal builder = StringBuilder()\nlocal current = builder.append\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(2, 24))
        )
        val declaration = service.declaration(
            DeclarationParams(TextDocumentIdentifier(uri), Position(2, 24))
        )
        val references = service.references(
            ReferenceParams(TextDocumentIdentifier(uri), Position(2, 24), ReferenceContext(true))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(2, 24))
        )

        assertEquals("file:///__jvm__/classes/java/lang/StringBuilder.lua", definition.single().uri)
        assertEquals("file:///__jvm__/classes/java/lang/StringBuilder.lua", declaration.single().uri)
        assertTrue(references.any { it.uri == "file:///__jvm__/classes/java/lang/StringBuilder.lua" })
        assertTrue(references.any { it.uri == uri })
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("append"))
    }

    @Test
    fun text_document_service_declaration_uses_native_declaration_params() {
        val languageService = LuaLanguageService()
        languageService.initialize(InitializeParams())
        val service = LuaTextDocumentService(languageService)

        val uri = "file:///workspace/text-document-declaration.lua"
        languageService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "import \"java.lang.StringBuilder\"\nlocal builder = StringBuilder()\nlocal current = builder.append\nreturn current")
            )
        )

        val declaration = service.declaration(
            DeclarationParams(TextDocumentIdentifier(uri), Position(2, 24))
        ).get().left

        assertEquals(1, declaration.size)
        assertEquals("file:///__jvm__/classes/java/lang/StringBuilder.lua", declaration.single().uri)
    }

    @Test
    fun language_service_resolves_dex_prefixed_import_calls() {
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

        val uri = "file:///workspace/import-dex.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local import = require(\"import\")\nlocal Context = import(\"plugin.dex:android.content.Context\")\nlocal current = Context.WINDOW_SERVICE\nreturn current")
            )
        )

        val definition = service.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(2, 25))
        )
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(2, 29))
        )

        assertEquals("file:///__jvm__/classes/android/content/Context.lua", definition.single().uri)
        assertNotNull(hover)
        assertTrue(hover.contents.right.value.contains("WINDOW_SERVICE"))
    }

    @Test
    fun text_document_and_workspace_services_expose_symbol_queries() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        service.setWorkspaceMetadata(mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays"))

        val uri = "file:///workspace/symbol-service.lua"
        val textDocuments = LuaTextDocumentService(service)
        val workspace = LuaWorkspaceService(service)
        textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")
            )
        )

        val documentSymbols = textDocuments.documentSymbol(org.eclipse.lsp4j.DocumentSymbolParams(TextDocumentIdentifier(uri))).get()
        val workspaceSymbols = workspace.symbol(WorkspaceSymbolParams("Arrays")).get()

        assertTrue(documentSymbols.any { either -> either.left.name == "Arrays" })
        assertTrue(documentSymbols.any { either -> either.left.name == "current" })
        assertTrue(workspaceSymbols.left.any { it.name == "Arrays" && it.location.uri == uri })
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
