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
    fun hover_jdk_class_member_reports_provider_symbol() {
        val service = service(jdkMetadata)
        val document = service.open("workspace/hover-jdk-member.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val hover = assertNotNull(service.hover(hoverParams(document, "asList")))

        assertTrue(hover.markup.contains("asList"))
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
    fun completion_for_jdk_class_member_includes_static_method() {
        val service = service(jdkMetadata)
        val document = service.open("workspace/completion-jdk.lua", "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current")

        val completions = service.completionAt(document, "asList")

        assertCompletion(completions, "asList")
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
    fun go_to_definition_resolves_local_read_to_local_binding() {
        val service = service()
        val document = service.open("workspace/definition-local.lua", "local value = 1\nreturn value")

        val locations = service.definition(definitionParams(document, "value", occurrence = 2))

        assertEquals(1, locations.size)
        assertEquals(document.uri, locations.single().uri)
        assertEquals(0, locations.single().range.start.line)
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
    fun document_symbols_list_top_level_locals_and_functions() {
        val service = service()
        service.open("workspace/document-symbols.lua", "local value = 1\nlocal function render()\n    return value\nend\nreturn render")

        val names = service.documentSymbols("workspace/document-symbols.lua").map { it.name }

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
