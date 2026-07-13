package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-218 — LSP completion keyword and snippet corpus.
 *
 * ## Actual completion surface (current product)
 *
 * JVM LSP completion ([LuaLanguageService.completion]) maps semantic
 * [io.github.dingyi222666.luaparser.semantic.model.CompletionProvider] results only:
 *
 * - **Lexical / symbol completions**: visible locals, parameters, functions, and
 *   seeded builtins (e.g. `print`), with kinds derived from symbol kinds
 *   (Variable / Function / …). Labels and `insertText` are the symbol names.
 * - **Member completions**: after `.` / `:` on member expressions.
 * - **Insert format**: always [InsertTextFormat.PlainText] on the LSP mapping path.
 * - **Keywords**: statement-level Lua control-structure keywords (`if`, `while`,
 *   `for`, …) are **not** emitted as [CompletionItemKind.Keyword] items today.
 * - **Snippets**: [CompletionItemKind.Snippet] items are **not** emitted today.
 *
 * This corpus locks the actual surface and documents the keyword/snippet policy
 * for when (if) product adds those categories. Product keyword emission is out
 * of scope for this test-only task (REVIEW23: align to actual surface).
 *
 * ## Keyword policy (documented; currently a product gap)
 *
 * 1. Desired: statement-level control keywords appear with stable lowercase Lua
 *    spellings and [CompletionItemKind.Keyword], plain insert text equal to label.
 * 2. Current product: no keyword items — tests assert absence so regressions that
 *    accidentally invent unstable pseudo-keyword labels without the Keyword kind
 *    contract are visible; a product follow-up should flip absence → presence
 *    assertions once [CompletionProvider] seeds keywords.
 * 3. Partial-prefix filtering of keywords is likewise not product behavior yet.
 *
 * ## Snippet policy (documented; optional)
 *
 * 1. Snippets MAY be absent. Absence is not a failure.
 * 2. When present, items with [CompletionItemKind.Snippet] MUST carry non-blank
 *    `insertText` and stable labels across repeated requests. Preferred insert
 *    format is [InsertTextFormat.Snippet] once the LSP path supports it
 *    end-to-end (today the mapping forces PlainText for all items).
 * 3. Snippet presence must not replace or rename ordinary lexical labels
 *    (locals / builtins) that the current surface already exposes.
 *
 * Workers must not run Gradle; verification is review-owned and serial.
 */
class LspCompletionKeywordSnippetTddTest {

    // -------------------------------------------------------------------------
    // Actual lexical / symbol completion surface
    // -------------------------------------------------------------------------

    @Test
    fun statement_completions_include_visible_locals_with_stable_labels() {
        val service = service()
        val document = service.open(
            "workspace/keyword-control.lua",
            """
            local alpha = 1

            return alpha
            """.trimIndent()
        )

        val labels = service.completionLabelsAt(document, blankLineAfterLocals())

        assertTrue(
            "alpha" in labels,
            "Expected visible local 'alpha' in statement completions; actual=$labels"
        )
        // Builtin surface is part of the same lexical path (CompletionProvider).
        assertTrue(
            "print" in labels,
            "Expected seeded builtin 'print' in statement completions; actual=$labels"
        )
    }

    @Test
    fun statement_completions_do_not_emit_control_structure_keyword_items() {
        // Documented product gap: control keywords are not on the completion surface.
        val service = service()
        val document = service.open(
            "workspace/keyword-loops-branches.lua",
            """
            local ready = true

            return ready
            """.trimIndent()
        )

        val items = service.completionItemsAt(document, blankLineAfterLocals())
        val labels = items.map { it.label }

        for (keyword in ALL_CONTROL_KEYWORDS) {
            val keywordKindHits = items.filter {
                it.label == keyword && it.kind == CompletionItemKind.Keyword
            }
            assertTrue(
                keywordKindHits.isEmpty(),
                "Product gap lock: control keyword '$keyword' must not appear as " +
                    "CompletionItemKind.Keyword until product seeds keywords; actual=$labels"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Insert text / kinds on the actual surface
    // -------------------------------------------------------------------------

    @Test
    fun lexical_items_use_symbol_kinds_not_keyword_kind() {
        val service = service()
        val document = service.open(
            "workspace/keyword-kind.lua",
            """
            local ready = true

            return ready
            """.trimIndent()
        )

        val items = service.completionItemsAt(document, blankLineAfterLocals())
        val ready = items.firstOrNull { it.label == "ready" }
        assertNotNull(ready, "Expected local 'ready' in completions; labels=${items.map { it.label }}")
        assertEquals(
            CompletionItemKind.Variable,
            ready.kind,
            "Local 'ready' must use Variable kind (symbol surface), not Keyword"
        )

        val keywordItems = items.filter { it.kind == CompletionItemKind.Keyword }
        assertTrue(
            keywordItems.isEmpty(),
            "Product gap lock: no CompletionItemKind.Keyword items expected; was ${keywordItems.map { it.label }}"
        )
    }

    // -------------------------------------------------------------------------
    // Optional snippet policy
    // -------------------------------------------------------------------------

    @Test
    fun snippet_items_when_present_follow_documented_snippet_policy() {
        val service = service()
        val document = service.open(
            "workspace/keyword-snippet-policy.lua",
            """
            local alpha = 1

            return alpha
            """.trimIndent()
        )

        val items = service.completionItemsAt(document, blankLineAfterLocals())
        val snippets = items.filter { it.kind == CompletionItemKind.Snippet }

        // Policy §1: snippets MAY be absent — that is not a failure (current product: absent).
        if (snippets.isEmpty()) {
            return
        }

        for (snippet in snippets) {
            assertTrue(snippet.label.isNotBlank(), "Snippet labels must be non-blank")
            val insert = snippet.insertText
            assertNotNull(insert, "Snippet '${snippet.label}' must provide insertText")
            assertTrue(insert.isNotBlank(), "Snippet '${snippet.label}' insertText must be non-blank")
            // Note: current LSP mapping forces PlainText for all items; if product starts
            // emitting Snippet kind, insertTextFormat should become Snippet as well.
            assertTrue(
                snippet.insertTextFormat == InsertTextFormat.Snippet ||
                    snippet.insertTextFormat == InsertTextFormat.PlainText ||
                    snippet.insertTextFormat == null,
                "Snippet '${snippet.label}' insertTextFormat unexpected: ${snippet.insertTextFormat}"
            )
        }
    }

    @Test
    fun snippet_presence_does_not_replace_lexical_labels() {
        val service = service()
        val document = service.open(
            "workspace/keyword-snippet-no-replace.lua",
            """
            local alpha = 1

            return alpha
            """.trimIndent()
        )

        val items = service.completionItemsAt(document, blankLineAfterLocals())
        val labels = items.map { it.label }.toSet()

        // Policy §3: ordinary lexical labels remain even if snippet variants exist.
        assertTrue(
            "alpha" in labels,
            "Lexical local label 'alpha' must remain available alongside any snippets; actual=$labels"
        )
        assertTrue(
            "print" in labels,
            "Builtin label 'print' must remain available alongside any snippets; actual=$labels"
        )

        // Keywords remain a product gap; do not require Keyword-kind coexistence.
        val keywordKindLabels = items
            .filter { it.kind == CompletionItemKind.Keyword }
            .map { it.label }
            .toSet()
        assertTrue(
            keywordKindLabels.isEmpty(),
            "Product gap lock: Keyword-kind set stays empty until product seeds keywords; was $keywordKindLabels"
        )
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
        val document = OpenDocument(path = path, source = source)
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
    }

    private fun LuaLanguageService.completionItemsAt(document: OpenDocument, position: Position): List<CompletionItem> {
        return completion(document.path, position.line, position.character).items
    }

    private fun LuaLanguageService.completionLabelsAt(document: OpenDocument, position: Position): List<String> {
        return completionItemsAt(document, position).map { it.label }
    }

    /**
     * Cursor on the blank line between the local binding and the trailing return
     * in the standard two-statement fixtures (line index 1, character 0).
     */
    private fun blankLineAfterLocals(): Position = Position(1, 0)

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

    private companion object {
        /** Core statement openers — documented desired Keyword surface (product gap). */
        val CORE_CONTROL_KEYWORDS = listOf(
            "if",
            "while",
            "for",
            "function",
            "repeat",
            "do"
        )

        /** Loop/branch continuations and terminators with stable Lua spellings. */
        val LOOP_AND_BRANCH_KEYWORDS = listOf(
            "else",
            "elseif",
            "then",
            "end",
            "until",
            "in"
        )

        /** Declaration and flow-control keywords. */
        val DECLARATION_AND_FLOW_KEYWORDS = listOf(
            "local",
            "return",
            "break",
            "goto"
        )

        val ALL_CONTROL_KEYWORDS: Set<String> =
            (CORE_CONTROL_KEYWORDS + LOOP_AND_BRANCH_KEYWORDS + DECLARATION_AND_FLOW_KEYWORDS).toSet()
    }
}
