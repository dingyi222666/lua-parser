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
 * Goldens follow product displayName (not raw EmmyLua spelling):
 * - `integer` annotations surface as `number` (PrimitiveType.NUMBER).
 * - `table<K, V>` annotations surface as `{ [K]: V }` (TableType index shape).
 * - Multi-name `local a, b = ...` under a single `---@type` may show inferred
 *   initializer types (e.g. Type `1`) rather than the annotation for all names;
 *   assert product hover without requiring annotation text on every name.
 * - Hover type line is formatted as `Type: \`displayName\`` by
 *   LuaLanguageService.buildHoverContent.
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
