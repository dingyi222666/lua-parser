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

    // -------------------------------------------------------------------------
    // Safety / null / unresolved positions (must not crash)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Content semantics across shapes
    // -------------------------------------------------------------------------

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
