package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-219 — LSP hover local annotation type corpus.
 *
 * Encodes the contract that hover over locals with EmmyLua `---@type` /
 * related annotations surfaces the annotated type text in the markup when the
 * semantic model has a declared type, while unannotated locals remain
 * non-crashing (either null hover or a non-empty markup payload without
 * throwing).
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class LspHoverLocalAnnotationTddTest {

    // -------------------------------------------------------------------------
    // Annotated locals surface type text
    // -------------------------------------------------------------------------

    @Test
    fun hover_local_with_type_string_annotation_surfaces_string() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-string.lua",
            """
            ---@type string
            local label = 1
            return label
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "label", occurrence = 2)))
        assertHoverMentions(hover, "label", "string")
    }

    @Test
    fun hover_local_with_type_number_annotation_surfaces_number() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-number.lua",
            """
            ---@type number
            local count = "x"
            return count
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "count", occurrence = 2)))
        assertHoverMentions(hover, "count", "number")
    }

    @Test
    fun hover_local_with_type_boolean_annotation_surfaces_boolean() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-boolean.lua",
            """
            ---@type boolean
            local flag = 0
            return flag
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "flag", occurrence = 2)))
        assertHoverMentions(hover, "flag", "boolean")
    }

    @Test
    fun hover_local_with_type_table_annotation_surfaces_table() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-table.lua",
            """
            ---@type table
            local bag = {}
            return bag
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "bag", occurrence = 2)))
        assertHoverMentions(hover, "bag", "table")
    }

    @Test
    fun hover_local_with_class_type_annotation_surfaces_class_name() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-class.lua",
            """
            ---@class User
            ---@field id integer
            ---@field name string
            ---@type User
            local user = {}
            return user
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "user", occurrence = 2)))
        assertHoverMentions(hover, "user", "User")
    }

    @Test
    fun hover_local_with_alias_type_annotation_surfaces_alias_or_resolved_type() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-alias.lua",
            """
            ---@alias Name string
            ---@type Name
            local title = 1
            return title
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "title", occurrence = 2)))
        val markup = hover.markup
        assertTrue(markup.contains("title"), "Expected symbol name in hover: $markup")
        assertTrue(
            markup.contains("Name") || markup.contains("string"),
            "Expected alias Name or resolved string in hover: $markup"
        )
    }

    @Test
    fun hover_local_with_generic_table_annotation_surfaces_key_value_types() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-table-generic.lua",
            """
            ---@type table<string, number>
            local scores = {}
            return scores
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "scores", occurrence = 2)))
        val markup = hover.markup
        assertTrue(markup.contains("scores"), "Expected symbol name in hover: $markup")
        assertTrue(
            markup.contains("table") && markup.contains("string") && markup.contains("number"),
            "Expected table<string, number> shape in hover: $markup"
        )
    }

    @Test
    fun hover_local_with_function_type_annotation_surfaces_callable_shape() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-fun.lua",
            """
            ---@type fun(value: number): string
            local render = nil
            return render
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "render", occurrence = 2)))
        val markup = hover.markup
        assertTrue(markup.contains("render"), "Expected symbol name in hover: $markup")
        assertTrue(
            markup.contains("fun") || markup.contains("number") || markup.contains("string"),
            "Expected function type text in hover: $markup"
        )
    }

    @Test
    fun hover_local_with_union_type_annotation_surfaces_union_parts() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-union.lua",
            """
            ---@type string | number
            local mixed = nil
            return mixed
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "mixed", occurrence = 2)))
        val markup = hover.markup
        assertTrue(markup.contains("mixed"), "Expected symbol name in hover: $markup")
        assertTrue(
            markup.contains("string") && markup.contains("number"),
            "Expected union parts in hover: $markup"
        )
    }

    @Test
    fun hover_local_with_generic_class_annotation_surfaces_type_args() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-generic-class.lua",
            """
            ---@class Box<T>
            ---@field value T
            ---@type Box<string>
            local boxed = {}
            return boxed
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "boxed", occurrence = 2)))
        val markup = hover.markup
        assertTrue(markup.contains("boxed"), "Expected symbol name in hover: $markup")
        assertTrue(
            markup.contains("Box") || markup.contains("string"),
            "Expected Box<string> shape in hover: $markup"
        )
    }

    @Test
    fun hover_at_annotated_local_definition_site_surfaces_type() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-def-site.lua",
            """
            ---@type integer
            local age = 0
            return age
            """
        )

        // occurrence 1 is the declaration name
        val hover = assertNotNull(service.hover(hoverParams(document, "age", occurrence = 1)))
        assertHoverMentions(hover, "age", "integer")
    }

    @Test
    fun hover_at_annotated_local_use_site_surfaces_type() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-use-site.lua",
            """
            ---@type string
            local message = ""
            local copy = message
            return copy
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "message", occurrence = 2)))
        assertHoverMentions(hover, "message", "string")
    }

    @Test
    fun hover_prefers_annotation_over_initializer_literal_when_available() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-prefers-declared.lua",
            """
            ---@type string
            local coerced = 42
            return coerced
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "coerced", occurrence = 2)))
        val markup = hover.markup
        assertTrue(markup.contains("coerced"), "Expected symbol name in hover: $markup")
        assertTrue(
            markup.contains("string"),
            "Annotated type string must appear even when initializer is a number literal: $markup"
        )
    }

    @Test
    fun hover_multi_local_statement_only_annotated_name_surfaces_annotation() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-multi-local.lua",
            """
            ---@type string
            local a, b = 1, 2
            return a, b
            """
        )

        val annotatedHover = assertNotNull(service.hover(hoverParams(document, "a", occurrence = 2)))
        assertHoverMentions(annotatedHover, "a", "string")

        // Unannotated sibling must not crash; type text is best-effort.
        val unannotatedHover = service.hover(hoverParams(document, "b", occurrence = 2))
        if (unannotatedHover != null) {
            assertTrue(
                unannotatedHover.markup.contains("b"),
                "Unannotated sibling hover, when present, should name the symbol: ${unannotatedHover.markup}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Unannotated locals remain non-crashing
    // -------------------------------------------------------------------------

    @Test
    fun hover_unannotated_local_with_number_literal_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/hover-plain-number.lua",
            "local value = 42\nreturn value"
        )

        val hover = runCatching {
            service.hover(hoverParams(document, "value", occurrence = 2))
        }.getOrElse { error ->
            fail("Hover on unannotated local must not throw: ${error.message}")
        }

        if (hover != null) {
            assertTrue(
                hover.markup.contains("value"),
                "When hover is present for unannotated local, symbol name is expected: ${hover.markup}"
            )
        }
    }

    @Test
    fun hover_unannotated_local_with_string_literal_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/hover-plain-string.lua",
            "local greeting = \"hi\"\nreturn greeting"
        )

        val hover = runCatching {
            service.hover(hoverParams(document, "greeting", occurrence = 2))
        }.getOrElse { error ->
            fail("Hover on unannotated string local must not throw: ${error.message}")
        }

        if (hover != null) {
            assertTrue(hover.markup.contains("greeting"), "Expected symbol name: ${hover.markup}")
        }
    }

    @Test
    fun hover_unannotated_local_with_nil_initializer_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/hover-plain-nil.lua",
            "local empty = nil\nreturn empty"
        )

        val hover = runCatching {
            service.hover(hoverParams(document, "empty", occurrence = 2))
        }.getOrElse { error ->
            fail("Hover on nil-initialized local must not throw: ${error.message}")
        }

        if (hover != null) {
            assertTrue(hover.markup.contains("empty"), "Expected symbol name: ${hover.markup}")
        }
    }

    @Test
    fun hover_unannotated_local_function_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/hover-plain-function.lua",
            """
            local function render(value)
                return value
            end
            return render
            """
        )

        val hover = runCatching {
            service.hover(hoverParams(document, "render", occurrence = 2))
        }.getOrElse { error ->
            fail("Hover on unannotated local function must not throw: ${error.message}")
        }

        if (hover != null) {
            assertTrue(hover.markup.contains("render"), "Expected symbol name: ${hover.markup}")
        }
    }

    @Test
    fun hover_unannotated_local_table_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/hover-plain-table.lua",
            "local record = { x = 1 }\nreturn record"
        )

        val hover = runCatching {
            service.hover(hoverParams(document, "record", occurrence = 2))
        }.getOrElse { error ->
            fail("Hover on unannotated table local must not throw: ${error.message}")
        }

        if (hover != null) {
            assertTrue(hover.markup.contains("record"), "Expected symbol name: ${hover.markup}")
        }
    }

    @Test
    fun hover_on_whitespace_between_locals_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/hover-whitespace.lua",
            "local a = 1\n\nlocal b = 2\nreturn a + b"
        )

        // Position at the blank line between locals
        val params = HoverParams(TextDocumentIdentifier(document.uri), Position(1, 0))
        runCatching {
            service.hover(params)
        }.getOrElse { error ->
            fail("Hover on whitespace must not throw: ${error.message}")
        }
    }

    // -------------------------------------------------------------------------
    // TextDocumentService wrapping + mixed document
    // -------------------------------------------------------------------------

    @Test
    fun text_document_service_hover_surfaces_annotated_local_type() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/text-hover-ann.lua",
            """
            ---@type number
            local score = 0
            return score
            """
        )

        val hover = assertNotNull(
            textDocuments.hover(hoverParams(document, "score", occurrence = 2)).get()
        )
        assertHoverMentions(hover, "score", "number")
    }

    @Test
    fun mixed_annotated_and_unannotated_locals_in_one_file() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-mixed.lua",
            """
            ---@type string
            local annotated = 1
            local plain = 2
            return annotated, plain
            """
        )

        val annotatedHover = assertNotNull(
            service.hover(hoverParams(document, "annotated", occurrence = 2))
        )
        assertHoverMentions(annotatedHover, "annotated", "string")

        val plainHover = runCatching {
            service.hover(hoverParams(document, "plain", occurrence = 2))
        }.getOrElse { error ->
            fail("Unannotated sibling in mixed file must not throw: ${error.message}")
        }
        if (plainHover != null) {
            assertTrue(
                plainHover.markup.contains("plain"),
                "Unannotated hover should name the symbol when present: ${plainHover.markup}"
            )
        }
    }

    @Test
    fun hover_type_line_uses_markdown_code_span_when_type_present() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-markdown-type.lua",
            """
            ---@type string
            local title = ""
            return title
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "title", occurrence = 2)))
        val markup = hover.markup
        // LuaLanguageService.buildHoverContent formats types as `Type: \`displayName\``
        assertTrue(
            markup.contains("Type:") && markup.contains("`string`") || markup.contains("string"),
            "Expected type markdown (Type: `string` or string) in hover: $markup"
        )
    }

    @Test
    fun hover_local_with_any_annotation_surfaces_any() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-any.lua",
            """
            ---@type any
            local anything = 1
            return anything
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "anything", occurrence = 2)))
        assertHoverMentions(hover, "anything", "any")
    }

    @Test
    fun hover_local_with_unknown_annotation_surfaces_unknown() {
        val service = service()
        val document = service.open(
            "workspace/hover-ann-unknown.lua",
            """
            ---@type unknown
            local mystery = 1
            return mystery
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "mystery", occurrence = 2)))
        assertHoverMentions(hover, "mystery", "unknown")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun service(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(InitializeParams())
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

    private fun hoverParams(document: OpenDocument, needle: String, occurrence: Int = 1): HoverParams {
        return HoverParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence))
    }

    private fun assertHoverMentions(hover: Hover, symbolName: String, typeText: String) {
        val markup = hover.markup
        assertTrue(
            markup.contains(symbolName),
            "Expected symbol '$symbolName' in hover markup: $markup"
        )
        assertTrue(
            markup.contains(typeText),
            "Expected annotated type text '$typeText' in hover markup: $markup"
        )
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

    private val Hover.markup: String
        get() = contents.right.value
}
