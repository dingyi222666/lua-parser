package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceSymbolParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LspNavigationSymbolsTddTest {
    @Test
    fun hover_local_variable_reports_literal_type() {
        val service = service()
        val document = service.open("workspace/hover-local.lua", "local value = 42\nreturn value")

        val hover = assertNotNull(service.hover(hoverParams(document, "value", occurrence = 2)))

        assertTrue(hover.markup.contains("value"))
        assertTrue(hover.markup.contains("42"))
    }

    @Test
    fun hover_local_function_reports_signature() {
        val service = service()
        val document = service.open(
            "workspace/hover-function.lua",
            """
            ---@param value number
            ---@return string
            local function render(value)
                return tostring(value)
            end
            return render(1)
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "render", occurrence = 2)))

        assertTrue(hover.markup.contains("render"))
        assertTrue(hover.markup.contains("number"))
        assertTrue(hover.markup.contains("string"))
    }

    @Test
    fun hover_workspace_module_field_reports_export_type() {
        val service = service()
        service.open("workspace/dep.lua", "return { value = 1, run = function() return true end }")
        val document = service.open("workspace/hover-module-field.lua", "local dep = require(\"dep\")\nlocal current = dep.value\nreturn current")

        val hover = assertNotNull(service.hover(hoverParams(document, "value")))

        assertTrue(hover.markup.contains("value"))
        assertTrue(hover.markup.contains("1"))
    }

    @Test
    fun hover_jdk_class_member_reports_provider_symbol() {
        val service = service(jdkMetadata)
        val document = service.open("workspace/hover-jdk-member.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val hover = assertNotNull(service.hover(hoverParams(document, "asList")))

        assertTrue(hover.markup.contains("asList"))
    }

    @Test
    fun text_document_service_hover_returns_markup_for_open_document() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open("workspace/text-hover.lua", "local value = 7\nreturn value")

        val hover = assertNotNull(textDocuments.hover(hoverParams(document, "value", occurrence = 2)).get())

        assertTrue(hover.markup.contains("value"))
        assertTrue(hover.markup.contains("7"))
    }

    @Test
    fun completion_includes_visible_locals_and_functions() {
        val service = service()
        val document = service.open(
            "workspace/completion-locals.lua",
            """
            local alpha = 1
            local function render()
                return alpha
            end
            local current = alpha
            """
        )

        val completions = service.completionAt(document, "current")

        assertCompletion(completions, "alpha")
        assertCompletion(completions, "render")
    }

    @Test
    fun completion_for_workspace_module_member_includes_exported_field() {
        val service = service()
        service.open("workspace/dep.lua", "return { value = 1, run = function() return true end }")
        val document = service.open("workspace/completion-module-field.lua", "local dep = require(\"dep\")\nlocal current = dep.value\nreturn current")

        val completions = service.completionAt(document, "value")

        assertCompletion(completions, "value")
    }

    @Test
    fun completion_for_workspace_module_member_includes_exported_function() {
        val service = service()
        service.open("workspace/dep.lua", "return { value = 1, run = function() return true end }")
        val document = service.open("workspace/completion-module-function.lua", "local dep = require(\"dep\")\nlocal current = dep.value\nreturn dep.run")

        val completions = service.completionAt(document, "value")

        assertCompletion(completions, "run")
    }

    @Test
    fun completion_for_jdk_class_member_includes_static_method() {
        val service = service(jdkMetadata)
        val document = service.open("workspace/completion-jdk.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val completions = service.completionAt(document, "asList")

        assertCompletion(completions, "asList")
    }

    @Test
    fun text_document_service_completion_wraps_completion_list() {
        val service = service(jdkMetadata)
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open("workspace/text-completion.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val completions = textDocuments.completion(completionParams(document, "asList")).get().right

        assertCompletion(completions, "asList")
    }

    @Test
    fun workspace_configuration_completion_exposes_imported_class() {
        val service = service()
        LuaWorkspaceService(service).didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "androlua.imports" to listOf("String"),
                    "jvm.importPrefixes" to listOf("java.lang")
                )
            )
        )
        val document = service.open("workspace/completion-configured-import.lua", "local current = String\nreturn current")

        val completions = service.completionAt(document, "String")

        assertCompletion(completions, "String")
    }

    @Test
    fun signature_help_reports_local_function_signature() {
        val service = service()
        val document = service.open(
            "workspace/signature-local.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )

        val help = assertNotNull(service.signatureHelp(signatureParams(document, "\"hi\"")))

        assertEquals("fun(value: number, label: string): string", help.signatures.single().label)
        assertEquals(listOf("value: number", "label: string"), help.signatures.single().parameters.map { it.label.left })
        assertEquals(1, help.activeParameter)
    }

    @Test
    fun signature_help_keeps_first_parameter_before_first_argument() {
        val service = service()
        val document = service.open(
            "workspace/signature-first.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )

        val firstArgument = document.positionOf("1,")
        val help = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(TextDocumentIdentifier(document.uri), Position(firstArgument.line, firstArgument.character - 1))
            )
        )

        assertEquals(0, help.activeParameter)
    }

    @Test
    fun signature_help_advances_to_second_parameter() {
        val service = service()
        val document = service.open(
            "workspace/signature-second.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )

        val help = assertNotNull(service.signatureHelp(signatureParams(document, "\"hi\"")))

        assertEquals(1, help.activeParameter)
    }

    @Test
    fun signature_help_offsets_method_receiver() {
        val service = service()
        val document = service.open(
            "workspace/signature-method.lua",
            """
            local box = {}
            ---@param self table
            ---@param value number
            ---@param label string
            function box:render(value, label)
                return label
            end
            local current = box:render(11, "done")
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "11")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "\"done\"")))

        assertEquals(listOf("self: table", "value: number", "label: string"), first.signatures.single().parameters.map { it.label.left })
        assertEquals(1, first.activeParameter)
        assertEquals(2, second.activeParameter)
    }

    @Test
    fun signature_help_supports_short_string_call_syntax() {
        val service = service()
        val document = service.open(
            "workspace/signature-short-string.lua",
            """
            ---@param className string
            ---@param memberName string
            ---@return fun(): string
            local function loadLib(className, memberName)
                return function()
                    return memberName
                end
            end
            local current = loadLib "java.util.Locale", "getDefault"
            return current
            """
        )

        val help = assertNotNull(service.signatureHelp(signatureParams(document, "\"getDefault\"")))

        assertEquals("fun(className: string, memberName: string): fun(): string", help.signatures.single().label)
        assertEquals(1, help.activeParameter)
    }

    @Test
    fun text_document_service_signature_help_wraps_response() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/text-signature.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )

        val help = assertNotNull(textDocuments.signatureHelp(signatureParams(document, "\"hi\"")).get())

        assertEquals("fun(value: number, label: string): string", help.signatures.single().label)
        assertEquals(1, help.activeParameter)
    }

    @Test
    fun go_to_definition_resolves_local_read_to_local_binding() {
        val service = service()
        val document = service.open("workspace/definition-local.lua", "local value = 1\nreturn value")

        val locations = service.definition(definitionParams(document, "value", occurrence = 2))

        assertEquals(1, locations.size)
        assertEquals(document.uri, locations.single().uri)
        assertEquals(0, locations.single().range.start.line)
    }

    @Test
    fun go_to_definition_resolves_required_module_alias_to_file() {
        val service = service()
        val dep = service.open("workspace/definition-dep.lua", "return { value = 1 }")
        val document = service.open("workspace/definition-require.lua", "local dep = require(\"definition-dep\")\nreturn dep")

        val locations = service.definition(definitionParams(document, "dep", occurrence = 3))

        assertEquals(dep.uri, locations.single().uri)
    }

    @Test
    fun go_to_definition_resolves_workspace_module_field_to_provider_file() {
        val service = service()
        val dep = service.open("workspace/definition-field-dep.lua", "local M = {}\nM.value = 1\nreturn M")
        val document = service.open("workspace/definition-field-main.lua", "local dep = require(\"definition-field-dep\")\nlocal current = dep.value\nreturn current")

        val locations = service.definition(definitionParams(document, "value"))

        assertEquals(dep.uri, locations.single().uri)
    }

    @Test
    fun go_to_definition_resolves_jdk_class_member_to_provider_file() {
        val service = service(jdkMetadata)
        val document = service.open("workspace/definition-jdk.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val locations = service.definition(definitionParams(document, "asList"))

        assertEquals("file:///__jvm__/classes/java/util/Arrays.lua", locations.single().uri)
    }

    @Test
    fun go_to_definition_resolves_constructor_style_jdk_instance_member() {
        val service = service()
        val document = service.open("workspace/definition-constructor.lua", "import \"java.lang.StringBuilder\"\nlocal builder = StringBuilder()\nlocal current = builder.append\nreturn current")

        val locations = service.definition(definitionParams(document, "append"))

        assertEquals("file:///__jvm__/classes/java/lang/StringBuilder.lua", locations.single().uri)
    }

    @Test
    fun text_document_service_definition_wraps_locations() {
        val service = service(jdkMetadata)
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open("workspace/text-definition.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val locations = textDocuments.definition(definitionParams(document, "asList")).get().left

        assertEquals("file:///__jvm__/classes/java/util/Arrays.lua", locations.single().uri)
    }

    @Test
    fun declaration_resolves_local_read_to_binding() {
        val service = service()
        val document = service.open("workspace/declaration-local.lua", "local value = 1\nlocal copy = value\nreturn copy")

        val locations = service.declaration(declarationParams(document, "value", occurrence = 2))

        assertEquals(1, locations.size)
        assertEquals(document.uri, locations.single().uri)
        assertEquals(0, locations.single().range.start.line)
    }

    @Test
    fun declaration_resolves_workspace_module_field_to_provider_file() {
        val service = service()
        val dep = service.open("workspace/declaration-field-dep.lua", "local M = {}\nM.value = 1\nreturn M")
        val document = service.open("workspace/declaration-field-main.lua", "local dep = require(\"declaration-field-dep\")\nlocal current = dep.value\nreturn current")

        val locations = service.declaration(declarationParams(document, "value"))

        assertEquals(dep.uri, locations.single().uri)
    }

    @Test
    fun declaration_resolves_jdk_member_to_provider_file() {
        val service = service(jdkMetadata)
        val document = service.open("workspace/declaration-jdk.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val locations = service.declaration(declarationParams(document, "asList"))

        assertEquals("file:///__jvm__/classes/java/util/Arrays.lua", locations.single().uri)
    }

    @Test
    fun text_document_service_declaration_wraps_locations() {
        val service = service(jdkMetadata)
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open("workspace/text-declaration.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val locations = textDocuments.declaration(declarationParams(document, "asList")).get().left

        assertEquals("file:///__jvm__/classes/java/util/Arrays.lua", locations.single().uri)
    }

    @Test
    fun references_collect_local_declaration_and_reads() {
        val service = service()
        val document = service.open("workspace/references-local.lua", "local value = 1\nlocal copy = value\nreturn value + copy")

        val references = service.references(referenceParams(document, "value", occurrence = 2))

        assertEquals(3, references.count { it.uri == document.uri })
        assertEquals(listOf(0, 1, 2), references.map { it.range.start.line }.sorted())
    }

    @Test
    fun references_cross_workspace_module_field_include_provider_and_usages() {
        val service = service()
        val dep = service.open("workspace/references-field-dep.lua", "local M = {}\nM.value = 1\nreturn M")
        val document = service.open("workspace/references-field-main.lua", "local dep = require(\"references-field-dep\")\nlocal first = dep.value\nlocal second = dep.value\nreturn second")

        val references = service.references(referenceParams(document, "value", occurrence = 1))

        assertTrue(references.any { it.uri == dep.uri })
        assertEquals(2, references.count { it.uri == document.uri })
    }

    @Test
    fun references_for_jdk_member_include_provider_and_workspace_usages() {
        val service = service(jdkMetadata)
        val document = service.open("workspace/references-jdk.lua", "local Arrays = require(\"Arrays\")\nlocal first = Arrays.asList\nlocal second = Arrays.asList\nreturn second")

        val references = service.references(referenceParams(document, "asList", occurrence = 1))

        assertTrue(references.any { it.uri == "file:///__jvm__/classes/java/util/Arrays.lua" })
        assertEquals(2, references.count { it.uri == document.uri })
    }

    @Test
    fun document_highlights_collect_local_occurrences() {
        val service = service()
        val document = service.open("workspace/highlights-local.lua", "local value = 1\nlocal copy = value\nreturn value + copy")

        val highlights = service.documentHighlights(highlightParams(document, "value", occurrence = 2))

        assertEquals(3, highlights.size)
        assertEquals(listOf(0, 1, 2), highlights.map { it.range.start.line }.sorted())
    }

    @Test
    fun document_highlights_for_provider_symbol_preserve_all_ranges() {
        val service = service(jdkMetadata)
        val document = service.open("workspace/highlights-jdk.lua", "local Arrays = require(\"Arrays\")\nlocal first = Arrays.asList\nlocal second = Arrays.asList\nreturn second")

        val highlights = service.documentHighlights(highlightParams(document, "asList", occurrence = 1))

        assertTrue(highlights.size >= 3)
    }

    @Test
    fun text_document_service_document_highlight_wraps_results() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open("workspace/text-highlights.lua", "local value = 1\nlocal copy = value\nreturn value + copy")

        val highlights = textDocuments.documentHighlight(highlightParams(document, "value", occurrence = 2)).get()

        assertEquals(3, highlights.size)
    }

    @Test
    fun document_symbols_list_top_level_locals_and_functions() {
        val service = service()
        service.open("workspace/document-symbols.lua", "local value = 1\nlocal function render()\n    return value\nend\nreturn render")

        val names = service.documentSymbols("workspace/document-symbols.lua").map { it.name }

        assertTrue("value" in names)
        assertTrue("render" in names)
    }

    @Test
    fun document_symbols_include_provider_backed_local_aliases() {
        val service = service(jdkMetadata)
        service.open("workspace/document-symbols-provider-alias.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val names = service.documentSymbols("workspace/document-symbols-provider-alias.lua").map { it.name }

        assertTrue("Arrays" in names)
        assertTrue("current" in names)
    }

    @Test
    fun text_document_service_document_symbol_wraps_document_symbols() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open("workspace/text-document-symbols.lua", "local value = 1\nlocal function render()\n    return value\nend")

        val names = textDocuments.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(document.uri))).get().map { it.left.name }

        assertTrue("value" in names)
        assertTrue("render" in names)
    }

    @Test
    fun workspace_symbols_find_symbols_across_open_files() {
        val service = service()
        service.open("workspace/symbol-alpha.lua", "local function alpha()\n    return 1\nend\nreturn alpha")
        service.open("workspace/symbol-beta.lua", "local beta = 2\nreturn beta")

        val alphaSymbols = service.workspaceSymbols("alpha")
        val betaSymbols = service.workspaceSymbols("beta")

        assertTrue(alphaSymbols.any { it.name == "alpha" && it.location.uri == "file:///workspace/symbol-alpha.lua" })
        assertTrue(betaSymbols.any { it.name == "beta" && it.location.uri == "file:///workspace/symbol-beta.lua" })
    }

    @Test
    fun workspace_symbols_include_provider_modules() {
        val service = service(jdkMetadata)
        service.open("workspace/workspace-symbols-provider.lua", "local Arrays = require(\"Arrays\")\nreturn Arrays")

        val symbols = service.workspaceSymbols("Arrays")

        assertTrue(symbols.any { it.name == "Arrays" && it.location.uri == "file:///__jvm__/classes/java/util/Arrays.lua" })
    }

    @Test
    fun workspace_symbols_include_provider_members() {
        val service = service(jdkMetadata)
        service.open("workspace/workspace-symbols-provider-member.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val symbols = service.workspaceSymbols("asList")

        assertTrue(symbols.any { it.name == "asList" && it.location.uri == "file:///__jvm__/classes/java/util/Arrays.lua" })
    }

    @Test
    fun workspace_service_symbol_wraps_workspace_symbols() {
        val service = service(jdkMetadata)
        val workspace = LuaWorkspaceService(service)
        service.open("workspace/workspace-service-symbol.lua", "local Arrays = require(\"Arrays\")\nreturn Arrays")

        val symbols = workspace.symbol(WorkspaceSymbolParams("Arrays")).get().left

        assertTrue(symbols.any { it.name == "Arrays" && it.location.uri == "file:///__jvm__/classes/java/util/Arrays.lua" })
    }

    @Test
    fun workspace_service_configuration_enables_provider_backed_symbols() {
        val service = service()
        val workspace = LuaWorkspaceService(service)
        workspace.didChangeConfiguration(DidChangeConfigurationParams(mapOf("jvm" to mapOf("classes" to "java.util.Locale"))))
        service.open("workspace/workspace-service-config-symbol.lua", "local Locale = require(\"Locale\")\nreturn Locale")

        val symbols = workspace.symbol(WorkspaceSymbolParams("Locale")).get().left

        assertTrue(symbols.any { it.name == "Locale" && it.location.uri == "file:///__jvm__/classes/java/util/Locale.lua" })
    }

    @Test
    fun hierarchical_document_symbols_nest_class_methods_and_fields() {
        val service = service()
        service.open(
            "workspace/hierarchical-class.lua",
            """
            ---@class Box
            ---@field width number
            ---@field height number
            local Box = {}

            ---@param self Box
            ---@return number
            function Box:area()
                return self.width * self.height
            end

            return Box
            """
        )

        val roots = service.hierarchicalDocumentSymbols("workspace/hierarchical-class.lua")
        fun find(symbols: List<DocumentSymbol>, name: String): DocumentSymbol? {
            symbols.forEach { symbol ->
                if (symbol.name == name) {
                    return symbol
                }
                find(symbol.children.orEmpty(), name)?.let { return it }
            }
            return null
        }

        val box = assertNotNull(find(roots, "Box"))
        assertTrue(box.kind == SymbolKind.Class || box.kind == SymbolKind.Variable || box.kind == SymbolKind.Module)
        assertNotNull(box.range)
        assertNotNull(box.selectionRange)

        val allNames = flattenHierarchicalNames(roots)
        assertTrue("Box" in allNames)
        assertTrue("width" in allNames || "height" in allNames || "area" in allNames)

        val flattened = service.documentSymbols("workspace/hierarchical-class.lua").map { it.name }
        assertTrue("Box" in flattened)
    }

    @Test
    fun hierarchical_document_symbols_include_module_export_fields() {
        val service = service()
        service.open(
            "workspace/hierarchical-module.lua",
            """
            local M = {}
            function M.render()
                return 1
            end
            M.label = "ok"
            return M
            """
        )

        val roots = service.hierarchicalDocumentSymbols("workspace/hierarchical-module.lua")
        val names = flattenHierarchicalNames(roots)

        assertTrue("render" in names)
        assertTrue("label" in names || "M" in names)
        assertTrue(roots.isNotEmpty())
        assertTrue(roots.any { it.children.orEmpty().isNotEmpty() || it.name == "render" || it.name == "M" })
    }

    @Test
    fun hierarchical_document_symbols_preserve_provider_backed_local_aliases() {
        val service = service(jdkMetadata)
        service.open(
            "workspace/hierarchical-provider-alias.lua",
            "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current"
        )

        val roots = service.hierarchicalDocumentSymbols("workspace/hierarchical-provider-alias.lua")
        val names = roots.map { it.name }

        assertTrue("Arrays" in names)
        assertTrue("current" in names)
    }

    @Test
    fun hierarchical_document_symbols_preserve_top_level_locals() {
        val service = service()
        service.open(
            "workspace/hierarchical-locals.lua",
            "local value = 1\nlocal function render()\n    return value\nend\nreturn render"
        )

        val roots = service.hierarchicalDocumentSymbols("workspace/hierarchical-locals.lua")
        val names = roots.map { it.name }

        assertTrue("value" in names)
        assertTrue("render" in names)
        roots.forEach { symbol ->
            assertNotNull(symbol.range)
            assertNotNull(symbol.selectionRange)
        }
    }

    @Test
    fun modern_workspace_symbols_include_provider_modules_and_members() {
        val service = service(jdkMetadata)
        service.open(
            "workspace/modern-workspace-symbols.lua",
            "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current"
        )

        val modules = service.modernWorkspaceSymbols("Arrays")
        val members = service.modernWorkspaceSymbols("asList")

        assertTrue(modules.any { it.name == "Arrays" && it.location.left.uri == "file:///__jvm__/classes/java/util/Arrays.lua" })
        assertTrue(members.any { it.name == "asList" && it.location.left.uri == "file:///__jvm__/classes/java/util/Arrays.lua" })
        assertEquals(SymbolKind.Module, modules.first { it.name == "Arrays" }.kind)
    }

    @Test
    fun modern_workspace_symbols_preserve_legacy_workspace_symbol_behavior() {
        val service = service()
        service.open("workspace/symbol-alpha.lua", "local function alpha()\n    return 1\nend\nreturn alpha")
        service.open("workspace/symbol-beta.lua", "local beta = 2\nreturn beta")

        val legacy = service.workspaceSymbols("a").map { Triple(it.name, it.location.uri, it.kind) }.sortedBy { it.first + it.second }
        val modern = service.modernWorkspaceSymbols("a").map {
            Triple(it.name, it.location.left.uri, it.kind)
        }.sortedBy { it.first + it.second }

        assertEquals(legacy, modern)
    }

    @Test
    fun modern_workspace_symbols_preserve_provider_module_locations() {
        val service = service(jdkMetadata)
        service.open("workspace/modern-workspace-symbols-provider.lua", "local Arrays = require(\"Arrays\")\nreturn Arrays")

        val symbols = service.modernWorkspaceSymbols("Arrays")

        assertTrue(
            symbols.any { symbol ->
                symbol.name == "Arrays" &&
                    symbol.location.left.uri == "file:///__jvm__/classes/java/util/Arrays.lua"
            }
        )
    }

    @Test
    fun modern_workspace_symbols_preserve_provider_member_locations() {
        val service = service(jdkMetadata)
        service.open(
            "workspace/modern-workspace-symbols-member.lua",
            "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current"
        )

        val symbols = service.modernWorkspaceSymbols("asList")

        assertTrue(
            symbols.any { symbol ->
                symbol.name == "asList" &&
                    symbol.location.left.uri == "file:///__jvm__/classes/java/util/Arrays.lua"
            }
        )
    }

    private fun service(metadata: Map<String, String> = emptyMap()): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(InitializeParams())
            if (metadata.isNotEmpty()) {
                setWorkspaceMetadata(metadata)
            }
        }
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
    }

    private fun LuaTextDocumentService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
    }

    private fun LuaLanguageService.completionAt(
        document: OpenDocument,
        needle: String,
        occurrence: Int = 1
    ): org.eclipse.lsp4j.CompletionList {
        val position = document.positionOf(needle, occurrence)
        return completion(document.path, position.line, position.character)
    }

    private fun hoverParams(document: OpenDocument, needle: String, occurrence: Int = 1): HoverParams {
        return HoverParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence))
    }

    private fun completionParams(document: OpenDocument, needle: String, occurrence: Int = 1): CompletionParams {
        return CompletionParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence))
    }

    private fun signatureParams(document: OpenDocument, needle: String, occurrence: Int = 1): SignatureHelpParams {
        return SignatureHelpParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence))
    }

    private fun definitionParams(document: OpenDocument, needle: String, occurrence: Int = 1): DefinitionParams {
        return DefinitionParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence))
    }

    private fun declarationParams(document: OpenDocument, needle: String, occurrence: Int = 1): DeclarationParams {
        return DeclarationParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence))
    }

    private fun referenceParams(document: OpenDocument, needle: String, occurrence: Int = 1): ReferenceParams {
        return ReferenceParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence), ReferenceContext(true))
    }

    private fun highlightParams(document: OpenDocument, needle: String, occurrence: Int = 1): DocumentHighlightParams {
        return DocumentHighlightParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence))
    }

    private fun assertCompletion(completions: org.eclipse.lsp4j.CompletionList, label: String) {
        assertTrue(completions.items.any { it.label == label }, "Expected completion '$label' in ${completions.items.map { it.label }}")
    }

    private fun flattenHierarchicalNames(symbols: List<DocumentSymbol>): Set<String> {
        val names = linkedSetOf<String>()
        fun walk(nodes: List<DocumentSymbol>) {
            nodes.forEach { node ->
                names += node.name
                walk(node.children.orEmpty())
            }
        }
        walk(symbols)
        return names
    }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        fun positionOf(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence $occurrence of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index)
        }

        private fun positionAt(offset: Int): Position {
            var line = 0
            var lineStart = 0
            for (i in 0 until offset) {
                if (source[i] == '\n') {
                    line += 1
                    lineStart = i + 1
                }
            }
            return Position(line, offset - lineStart)
        }
    }

    private val org.eclipse.lsp4j.Hover.markup: String
        get() = contents.right.value

    private companion object {
        val jdkMetadata = mapOf(
            JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                "java.util.Arrays",
                "java.util.Locale",
                "java.lang.StringBuilder",
                "java.lang.System"
            ).joinToString("\n")
        )
    }
}
