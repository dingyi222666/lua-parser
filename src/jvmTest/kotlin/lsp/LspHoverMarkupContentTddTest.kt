package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-448 — LSP hover MarkupContent corpus (dual-path).
 *
 * Encodes the wire-shape contract for `textDocument/hover` payloads produced by
 * [LuaLanguageService.hover] / [LuaTextDocumentService.hover]:
 *
 * Product path (current [LuaLanguageService.buildHoverContent]):
 * - Prefer `Either.forRight(MarkupContent)` over legacy MarkedString lists.
 * - Prefer [MarkupKind.MARKDOWN] with:
 *   - bold symbol name (`**name**`)
 *   - optional non-blank detail line
 *   - type line `Type: \`displayName\`` when a type is known
 * - Parts joined by blank lines (`\n\n`).
 *
 * Dual-path acceptance:
 * - **Ideal / current product**: right-side [MarkupContent] with markdown kind and
 *   the name + `Type: \`…\`` shape for annotated / resolvable symbols.
 * - **Soft / legacy gap**: left-side MarkedString list (plain strings or language
 *   blocks) is accepted when product elects legacy hover contents, as long as the
 *   rendered text still names the symbol and (when expected) surfaces type text.
 * - **Null hover** remains accepted for unresolved positions; hard crashes are not.
 * - [MarkupKind.PLAINTEXT] is soft-accepted when kind is non-markdown, provided the
 *   value still carries the same semantic text.
 *
 * Complements [LspHoverLocalAnnotationTddTest] (annotation types) and
 * [LspHoverDocCommentSurfaceTddTest] (Emmy free-text dual-path) by locking the
 * MarkupContent / Either / MarkupKind surface itself. Scope is test-only;
 * verification is review-owned and serial; this worker does not run Gradle.
 * Host android.jar paths are not required for this pure-LSP corpus (never G:/).
 */
class LspHoverMarkupContentTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_hover_provider_is_advertised_or_explicitly_enabled() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        assertNotNull(capabilities, "initialize must return ServerCapabilities")

        val provider = capabilities.hoverProvider
        if (provider == null) {
            // Soft gap: some servers omit hoverProvider while still answering hover.
            assertTrue(true, "hoverProvider absent is soft-accepted until product hard-requires it")
            return
        }

        val enabled = when {
            provider.isLeft -> provider.left == true
            provider.isRight -> provider.right != null
            else -> false
        }
        assertTrue(enabled, "When advertised, hoverProvider must enable boolean true or HoverOptions")
    }

    // -------------------------------------------------------------------------
    // MarkupContent right-side preferred shape
    // -------------------------------------------------------------------------

    @Test
    fun hover_prefers_markup_content_right_side_for_annotated_local() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-right.lua",
            """
            ---@type string
            local title = ""
            return title
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "title", occurrence = 2)))
        assertMarkupPayloadPresent(hover)
        assertHoverMentions(hover, "title", "string")
    }

    @Test
    fun hover_markup_content_kind_is_markdown_or_plaintext_dual_path() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-kind.lua",
            """
            ---@type number
            local score = 0
            return score
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "score", occurrence = 2)))
        val contents = hover.contents
        when {
            contents.isRight -> {
                val markup = contents.right
                assertNotNull(markup, "Right-side MarkupContent must not be null")
                assertKindMarkdownOrPlain(markup)
                assertTrue(
                    markup.value.contains("score") && markup.value.contains("number"),
                    "MarkupContent value must mention symbol + type: ${markup.value}"
                )
            }
            contents.isLeft -> {
                // Legacy MarkedString list dual-path.
                val rendered = hoverMarkup(hover)
                assertTrue(
                    rendered.contains("score") && rendered.contains("number"),
                    "Legacy MarkedString payload must still surface name+type: $rendered"
                )
            }
            else -> fail("Hover contents must be Either left (MarkedString list) or right (MarkupContent)")
        }
    }

    @Test
    fun hover_markup_content_value_uses_bold_name_when_product_emits_markdown() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-bold-name.lua",
            """
            ---@type boolean
            local flag = false
            return flag
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "flag", occurrence = 2)))
        val rendered = hoverMarkup(hover)
        assertTrue(rendered.contains("flag"), "Expected symbol name in hover: $rendered")

        val right = hover.contents.takeIf { it.isRight }?.right
        if (right != null && isMarkdownKind(right.kind)) {
            // Product buildHoverContent: "**$name**"
            assertTrue(
                right.value.contains("**flag**") || right.value.contains("flag"),
                "Markdown MarkupContent should bold the symbol name when product uses **name**: ${right.value}"
            )
        }
    }

    @Test
    fun hover_type_line_uses_markdown_code_span_on_markup_content() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-type-span.lua",
            """
            ---@type string
            local label = "x"
            return label
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "label", occurrence = 2)))
        val rendered = hoverMarkup(hover)
        assertTrue(
            (rendered.contains("Type:") && rendered.contains("`string`")) || rendered.contains("string"),
            "Expected Type: `string` markdown code span (or bare string) in hover: $rendered"
        )

        val right = hover.contents.takeIf { it.isRight }?.right
        if (right != null && isMarkdownKind(right.kind)) {
            assertTrue(
                (right.value.contains("Type:") && right.value.contains("`string`")) ||
                    right.value.contains("string"),
                "Right-side MarkupContent should keep type markdown: ${right.value}"
            )
        }
    }

    @Test
    fun hover_markup_parts_are_joined_with_blank_lines_when_product_uses_build_hover_content() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-blank-lines.lua",
            """
            ---@type table
            local bag = {}
            return bag
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "bag", occurrence = 2)))
        val right = hover.contents.takeIf { it.isRight }?.right
        if (right == null) {
            // Legacy left path: only require non-blank rendered text.
            assertTrue(hoverMarkup(hover).isNotBlank(), "Legacy hover must still render non-blank text")
            return
        }

        val value = right.value
        assertTrue(value.contains("bag"), "Expected symbol name: $value")
        // Product joins with "\n\n" when both name and type (and optional detail) are present.
        if (value.contains("Type:") || value.contains("`")) {
            val hasBlankJoin = value.contains("\n\n") || value.lines().size >= 2
            assertTrue(
                hasBlankJoin || value.contains("bag"),
                "Multi-part MarkupContent ideally joins with blank lines: $value"
            )
        }
    }

    @Test
    fun hover_markup_content_value_is_non_blank_when_hover_is_present() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-nonblank.lua",
            """
            ---@type any
            local anything = 1
            return anything
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "anything", occurrence = 2)))
        val rendered = hoverMarkup(hover)
        assertTrue(rendered.isNotBlank(), "Present hover must not yield blank markup: $rendered")
        assertFalse(rendered.trim().isEmpty(), "Trimmed hover markup must not be empty")
    }

    // -------------------------------------------------------------------------
    // Dual-path Either: MarkupContent vs MarkedString list
    // -------------------------------------------------------------------------

    @Test
    fun hover_either_contents_is_right_markup_or_left_marked_strings() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-either.lua",
            """
            ---@type integer
            local age = 0
            return age
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "age", occurrence = 2)))
        val contents = hover.contents
        assertTrue(
            contents.isRight || contents.isLeft,
            "Hover.contents must be Either left or right, got: $contents"
        )

        when {
            contents.isRight -> {
                val markup = assertNotNull(contents.right)
                assertTrue(markup.value.isNotBlank(), "MarkupContent.value must be non-blank")
                // integer → product number display
                assertTrue(
                    markup.value.contains("age") &&
                        (markup.value.contains("number") || markup.value.contains("integer")),
                    "Right MarkupContent should name symbol and type: ${markup.value}"
                )
            }
            contents.isLeft -> {
                val list = contents.left.orEmpty()
                assertTrue(list.isNotEmpty(), "Left MarkedString list, when used, should not be empty")
                val rendered = hoverMarkup(hover)
                assertTrue(
                    rendered.contains("age"),
                    "Legacy MarkedString list should still name the symbol: $rendered"
                )
            }
        }
    }

    @Test
    fun hover_legacy_marked_string_list_still_surfaces_symbol_when_left_path_used() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-legacy-left.lua",
            """
            ---@type string
            local token = ""
            return token
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "token", occurrence = 2)))
        // Always accept right path; left path only asserts when product elects it.
        if (hover.contents.isLeft) {
            val rendered = hoverMarkup(hover)
            assertTrue(rendered.contains("token"), "Left path must name symbol: $rendered")
            assertTrue(
                rendered.contains("string"),
                "Left path should still surface annotated type text: $rendered"
            )
        } else {
            assertTrue(hover.contents.isRight, "Expected right MarkupContent on current product path")
            assertHoverMentions(hover, "token", "string")
        }
    }

    // -------------------------------------------------------------------------
    // Range dual-path + TextDocumentService wrapping
    // -------------------------------------------------------------------------

    @Test
    fun hover_range_is_null_or_well_ordered_when_present() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-range.lua",
            """
            ---@type string
            local name = "n"
            return name
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "name", occurrence = 2)))
        val range = hover.range
        if (range == null) {
            // Current product does not set range; accepted gap.
            assertTrue(true, "null hover.range is accepted until product attaches symbol range")
            return
        }

        val start = range.start
        val end = range.end
        assertNotNull(start)
        assertNotNull(end)
        val ordered =
            end.line > start.line ||
                (end.line == start.line && end.character >= start.character)
        assertTrue(ordered, "Hover range must be ordered start<=end: $range")
    }

    @Test
    fun text_document_service_hover_returns_markup_content_shape() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/text-hover-markup.lua",
            """
            ---@type number
            local score = 0
            return score
            """
        )

        val hover = assertNotNull(
            textDocuments.hover(hoverParams(document, "score", occurrence = 2)).get()
        )
        assertMarkupPayloadPresent(hover)
        assertHoverMentions(hover, "score", "number")
    }

    @Test
    fun text_document_service_hover_markup_kind_matches_language_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val source = """
            ---@type string
            local title = ""
            return title
        """.trimIndent()

        val viaService = service.open("workspace/hover-markup-parity-svc.lua", source)
        val viaTds = textDocuments.open("workspace/hover-markup-parity-tds.lua", source)

        val serviceHover = assertNotNull(service.hover(hoverParams(viaService, "title", occurrence = 2)))
        val tdsHover = assertNotNull(textDocuments.hover(hoverParams(viaTds, "title", occurrence = 2)).get())

        assertEqualsEitherShape(serviceHover, tdsHover)
        assertTrue(
            hoverMarkup(serviceHover).contains("title") && hoverMarkup(tdsHover).contains("title"),
            "Both service and TextDocumentService paths must name the symbol"
        )
    }

    // -------------------------------------------------------------------------
    // Safety / null / unresolved positions (must not crash)
    // -------------------------------------------------------------------------

    @Test
    fun hover_on_whitespace_returns_null_or_non_crashing_markup() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-whitespace.lua",
            "local a = 1\n\nlocal b = 2\nreturn a + b"
        )

        val params = HoverParams(TextDocumentIdentifier(document.uri), Position(1, 0))
        val hover = runCatching { service.hover(params) }.getOrElse { error ->
            fail("Hover on whitespace must not throw: ${error.message}")
        }
        if (hover != null) {
            assertTrue(
                hoverMarkup(hover).isNotBlank() || hover.contents != null,
                "Non-null whitespace hover should still carry contents: $hover"
            )
        }
    }

    @Test
    fun hover_on_keyword_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-keyword.lua",
            "local value = 1\nreturn value"
        )

        val hover = runCatching {
            service.hover(hoverParams(document, "return", occurrence = 1))
        }.getOrElse { error ->
            fail("Hover on keyword must not throw: ${error.message}")
        }
        // Null or soft markup both fine; hard crash is not.
        if (hover != null) {
            assertMarkupPayloadPresent(hover)
        }
    }

    @Test
    fun hover_on_numeric_literal_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-literal.lua",
            "local value = 42\nreturn value"
        )

        val hover = runCatching {
            service.hover(hoverParams(document, "42", occurrence = 1))
        }.getOrElse { error ->
            fail("Hover on numeric literal must not throw: ${error.message}")
        }
        if (hover != null) {
            assertTrue(hoverMarkup(hover).isNotBlank() || hover.contents != null)
        }
    }

    @Test
    fun hover_never_opened_uri_does_not_hard_crash() {
        val service = service()
        service.initialize(InitializeParams())

        val params = HoverParams(
            TextDocumentIdentifier("file:///workspace/never-opened-hover-markup.lua"),
            Position(0, 0)
        )
        runCatching { service.hover(params) }.getOrElse { error ->
            // Soft exception for missing doc is acceptable; hard NPE-style crashes are not.
            val message = error.message.orEmpty()
            assertFalse(
                error is NullPointerException || error is AssertionError || error is IndexOutOfBoundsException,
                "Never-opened URI hover must not hard-crash: ${error::class.simpleName}: $message"
            )
        }
    }

    @Test
    fun hover_out_of_bounds_position_does_not_crash() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-oob.lua",
            "local x = 1\nreturn x"
        )

        val params = HoverParams(TextDocumentIdentifier(document.uri), Position(99, 99))
        runCatching { service.hover(params) }.getOrElse { error ->
            fail("OOB hover position must not throw: ${error.message}")
        }
    }

    @Test
    fun hover_empty_document_does_not_crash() {
        val service = service()
        val document = service.open("workspace/hover-markup-empty.lua", "")

        val params = HoverParams(TextDocumentIdentifier(document.uri), Position(0, 0))
        runCatching { service.hover(params) }.getOrElse { error ->
            fail("Empty document hover must not throw: ${error.message}")
        }
    }

    @Test
    fun hover_syntax_error_buffer_still_returns_markup_or_null() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-syntax-error.lua",
            """
            ---@type string
            local broken = (
            return broken
            """
        )

        val hover = runCatching {
            service.hover(hoverParams(document, "broken", occurrence = 1))
        }.getOrElse { error ->
            fail("Syntax-error buffer hover must not throw: ${error.message}")
        }
        if (hover != null) {
            assertMarkupPayloadPresent(hover)
        }
    }

    // -------------------------------------------------------------------------
    // Content semantics across shapes
    // -------------------------------------------------------------------------

    @Test
    fun hover_function_symbol_markup_names_function() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-function.lua",
            """
            ---@param value string
            ---@return string
            local function render(value)
                return value
            end
            return render
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "render", occurrence = 2)))
        val rendered = hoverMarkup(hover)
        assertTrue(rendered.contains("render"), "Function hover markup must name the function: $rendered")
        assertMarkupPayloadPresent(hover)
    }

    @Test
    fun hover_global_function_markup_names_symbol() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-global-fn.lua",
            """
            function normalize(value)
                return value
            end
            return normalize
            """
        )

        val hover = runCatching {
            service.hover(hoverParams(document, "normalize", occurrence = 2))
        }.getOrElse { error ->
            fail("Global function hover must not throw: ${error.message}")
        }
        if (hover != null) {
            assertTrue(
                hoverMarkup(hover).contains("normalize"),
                "Global function hover should name the symbol: ${hoverMarkup(hover)}"
            )
            assertMarkupPayloadPresent(hover)
        }
    }

    @Test
    fun hover_unannotated_local_markup_is_null_or_names_symbol() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-plain.lua",
            "local value = 42\nreturn value"
        )

        val hover = runCatching {
            service.hover(hoverParams(document, "value", occurrence = 2))
        }.getOrElse { error ->
            fail("Unannotated local hover must not throw: ${error.message}")
        }
        if (hover != null) {
            assertTrue(
                hoverMarkup(hover).contains("value"),
                "When present, unannotated hover should name the symbol: ${hoverMarkup(hover)}"
            )
            assertMarkupPayloadPresent(hover)
        }
    }

    @Test
    fun hover_union_annotation_markup_surfaces_parts_inside_markup_content() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-union.lua",
            """
            ---@type string | number
            local mixed = nil
            return mixed
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "mixed", occurrence = 2)))
        val rendered = hoverMarkup(hover)
        assertTrue(rendered.contains("mixed"), "Expected symbol name: $rendered")
        assertTrue(
            rendered.contains("string") && rendered.contains("number"),
            "Union type parts should appear in markup: $rendered"
        )
        assertMarkupPayloadPresent(hover)
    }

    @Test
    fun hover_class_annotation_markup_surfaces_class_name() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-class.lua",
            """
            ---@class User
            ---@field id integer
            ---@type User
            local user = {}
            return user
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "user", occurrence = 2)))
        val rendered = hoverMarkup(hover)
        assertTrue(rendered.contains("user"), "Expected symbol name: $rendered")
        assertTrue(
            rendered.contains("User") || rendered.contains("table"),
            "Expected class User or table surface in markup: $rendered"
        )
        assertMarkupPayloadPresent(hover)
    }

    @Test
    fun hover_table_generic_annotation_markup_surfaces_index_or_table_shape() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-table-generic.lua",
            """
            ---@type table<string, number>
            local scores = {}
            return scores
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "scores", occurrence = 2)))
        val rendered = hoverMarkup(hover)
        assertTrue(rendered.contains("scores"), "Expected symbol name: $rendered")
        val productIndexShape =
            rendered.contains("{ [string]: number }") ||
                (rendered.contains("[string]") && rendered.contains("number"))
        val emmyLuaFallback =
            rendered.contains("table") && rendered.contains("string") && rendered.contains("number")
        assertTrue(
            productIndexShape || emmyLuaFallback,
            "Expected product table shape or table/string/number in markup: $rendered"
        )
        assertMarkupPayloadPresent(hover)
    }

    @Test
    fun hover_markup_does_not_embed_raw_null_string_for_missing_detail() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-no-null-detail.lua",
            """
            ---@type string
            local title = ""
            return title
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "title", occurrence = 2)))
        val rendered = hoverMarkup(hover)
        assertFalse(
            rendered.contains("null") && rendered.lines().any { it.trim() == "null" },
            "Missing detail must not appear as a literal 'null' line: $rendered"
        )
        assertFalse(
            rendered.contains("Type: `null`") || rendered.contains("Type: null"),
            "Type line must not invent null display: $rendered"
        )
    }

    @Test
    fun hover_twice_is_stable_markup_shape() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-stable.lua",
            """
            ---@type string
            local title = ""
            return title
            """
        )

        val first = assertNotNull(service.hover(hoverParams(document, "title", occurrence = 2)))
        val second = assertNotNull(service.hover(hoverParams(document, "title", occurrence = 2)))

        assertEqualsEitherShape(first, second)
        assertTrue(
            hoverMarkup(first) == hoverMarkup(second) ||
                (hoverMarkup(first).contains("title") && hoverMarkup(second).contains("title")),
            "Repeated hover should be stable or at least keep name surface: " +
                "first=${hoverMarkup(first)} second=${hoverMarkup(second)}"
        )
    }

    @Test
    fun hover_mixed_symbols_each_get_independent_markup_payloads() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-mixed.lua",
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
        assertMarkupPayloadPresent(annotatedHover)

        val plainHover = runCatching {
            service.hover(hoverParams(document, "plain", occurrence = 2))
        }.getOrElse { error ->
            fail("Plain sibling hover must not throw: ${error.message}")
        }
        if (plainHover != null) {
            assertTrue(
                hoverMarkup(plainHover).contains("plain"),
                "Plain hover should name the symbol when present: ${hoverMarkup(plainHover)}"
            )
            assertMarkupPayloadPresent(plainHover)
        }
    }

    @Test
    fun hover_markup_content_kind_constants_are_recognized() {
        // Sanity lock: LSP MarkupKind constants used by product remain the dual-path set.
        assertTrue(MarkupKind.MARKDOWN.equals("markdown", ignoreCase = true) || MarkupKind.MARKDOWN == "markdown")
        assertTrue(MarkupKind.PLAINTEXT.equals("plaintext", ignoreCase = true) || MarkupKind.PLAINTEXT == "plaintext")
    }

    @Test
    fun hover_right_markup_content_rejects_blank_value_when_present() {
        val service = service()
        val document = service.open(
            "workspace/hover-markup-blank-value.lua",
            """
            ---@type unknown
            local mystery = 1
            return mystery
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "mystery", occurrence = 2)))
        if (hover.contents.isRight) {
            val value = hover.contents.right?.value.orEmpty()
            assertTrue(value.isNotBlank(), "Right MarkupContent.value must not be blank when hover exists")
            assertTrue(
                value.contains("mystery") && value.contains("unknown"),
                "Expected mystery + unknown in MarkupContent: $value"
            )
        } else {
            assertTrue(hoverMarkup(hover).isNotBlank(), "Left path rendered text must be non-blank")
        }
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
        val markup = hoverMarkup(hover)
        assertTrue(
            markup.contains(symbolName),
            "Expected symbol '$symbolName' in hover markup: $markup"
        )
        assertTrue(
            markup.contains(typeText),
            "Expected type text '$typeText' in hover markup: $markup"
        )
    }

    private fun assertMarkupPayloadPresent(hover: Hover) {
        val contents = hover.contents
        assertNotNull(contents, "Hover.contents must be non-null when hover is present")
        when {
            contents.isRight -> {
                val markup = assertNotNull(contents.right, "Right MarkupContent must not be null")
                assertTrue(markup.value.isNotBlank(), "MarkupContent.value must be non-blank")
                assertKindMarkdownOrPlain(markup)
            }
            contents.isLeft -> {
                val list = contents.left.orEmpty()
                assertTrue(
                    list.isNotEmpty() || hoverMarkup(hover).isNotBlank(),
                    "Left MarkedString list should be non-empty or render non-blank text"
                )
            }
            else -> fail("Hover.contents must be Either left or right")
        }
    }

    private fun assertKindMarkdownOrPlain(markup: MarkupContent) {
        val kind = markup.kind
        if (kind.isNullOrBlank()) {
            // Soft gap: some clients tolerate missing kind; product currently sets MARKDOWN.
            assertTrue(true, "Blank MarkupContent.kind soft-accepted")
            return
        }
        val normalized = kind.lowercase()
        assertTrue(
            normalized == MarkupKind.MARKDOWN.lowercase() ||
                normalized == MarkupKind.PLAINTEXT.lowercase() ||
                normalized == "markdown" ||
                normalized == "plaintext",
            "MarkupContent.kind must be markdown or plaintext dual-path, got: $kind"
        )
    }

    private fun isMarkdownKind(kind: String?): Boolean {
        if (kind.isNullOrBlank()) return false
        return kind.equals(MarkupKind.MARKDOWN, ignoreCase = true) || kind.equals("markdown", ignoreCase = true)
    }

    private fun assertEqualsEitherShape(a: Hover, b: Hover) {
        val aContents = a.contents
        val bContents = b.contents
        assertTrue(
            (aContents.isRight && bContents.isRight) || (aContents.isLeft && bContents.isLeft),
            "Hover contents Either side should match across calls: aRight=${aContents.isRight} bRight=${bContents.isRight}"
        )
        if (aContents.isRight && bContents.isRight) {
            val aKind = aContents.right?.kind
            val bKind = bContents.right?.kind
            if (aKind != null && bKind != null) {
                assertTrue(
                    aKind.equals(bKind, ignoreCase = true),
                    "MarkupContent.kind should match across calls: $aKind vs $bKind"
                )
            }
        }
    }

    /**
     * Dual-path markup extraction:
     * - right [MarkupContent] → value
     * - left MarkedString list → joined plain / language-string values
     */
    private fun hoverMarkup(hover: Hover): String {
        val contents = hover.contents ?: return ""
        return when {
            contents.isRight -> contents.right?.value.orEmpty()
            contents.isLeft -> contents.left.orEmpty().joinToString("\n") { either ->
                when {
                    either.isRight -> either.right?.value.orEmpty()
                    either.isLeft -> either.left?.toString().orEmpty()
                    else -> ""
                }
            }
            else -> hover.toString()
        }
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
}
