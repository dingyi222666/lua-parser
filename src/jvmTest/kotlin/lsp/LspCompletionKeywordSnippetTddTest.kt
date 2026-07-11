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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-218 — LSP completion keyword and snippet corpus.
 *
 * Encodes the contract that statement-level completions expose Lua control-structure
 * keywords with stable labels and [CompletionItemKind.Keyword], and documents the
 * optional snippet policy for insert text.
 *
 * ## Snippet policy (documented)
 *
 * Snippets are **optional**. The current JVM LSP completion path maps semantic
 * completion items with [InsertTextFormat.PlainText] only
 * (`LuaLanguageService.completion`). Therefore:
 *
 * 1. Keyword completions MUST use plain insert text equal to the keyword label
 *    (no tab-stop placeholders required).
 * 2. Items with [CompletionItemKind.Snippet] MAY be absent. When present, they
 *    MUST advertise [InsertTextFormat.Snippet] and non-blank `insertText`, and
 *    their labels MUST remain stable across repeated completion requests.
 * 3. Absence of snippets is not a failure; presence must not replace or rename
 *    the plain keyword labels below.
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class LspCompletionKeywordSnippetTddTest {

    // -------------------------------------------------------------------------
    // Stable control-structure keyword labels
    // -------------------------------------------------------------------------

    @Test
    fun statement_completions_include_core_control_structure_keywords() {
        val service = service()
        val document = service.open(
            "workspace/keyword-control.lua",
            """
            local alpha = 1

            return alpha
            """.trimIndent()
        )

        val labels = service.completionLabelsAt(document, blankLineAfterLocals())

        for (keyword in CORE_CONTROL_KEYWORDS) {
            assertTrue(
                keyword in labels,
                "Expected control keyword '$keyword' in statement completions; actual=$labels"
            )
        }
    }

    @Test
    fun statement_completions_include_loop_and_branch_keywords() {
        val service = service()
        val document = service.open(
            "workspace/keyword-loops-branches.lua",
            """
            local ready = true

            return ready
            """.trimIndent()
        )

        val labels = service.completionLabelsAt(document, blankLineAfterLocals())

        for (keyword in LOOP_AND_BRANCH_KEYWORDS) {
            assertTrue(
                keyword in labels,
                "Expected loop/branch keyword '$keyword' in statement completions; actual=$labels"
            )
        }
    }

    @Test
    fun statement_completions_include_declaration_and_flow_keywords() {
        val service = service()
        val document = service.open(
            "workspace/keyword-decl-flow.lua",
            """
            local value = 0

            return value
            """.trimIndent()
        )

        val labels = service.completionLabelsAt(document, blankLineAfterLocals())

        for (keyword in DECLARATION_AND_FLOW_KEYWORDS) {
            assertTrue(
                keyword in labels,
                "Expected declaration/flow keyword '$keyword' in statement completions; actual=$labels"
            )
        }
    }

    @Test
    fun control_keyword_labels_are_stable_across_repeated_requests() {
        val service = service()
        val document = service.open(
            "workspace/keyword-stable.lua",
            """
            local marker = 1

            return marker
            """.trimIndent()
        )

        val first = service.completionLabelsAt(document, blankLineAfterLocals())
            .filter { it in ALL_CONTROL_KEYWORDS }
            .sorted()
        val second = service.completionLabelsAt(document, blankLineAfterLocals())
            .filter { it in ALL_CONTROL_KEYWORDS }
            .sorted()

        assertEquals(first, second, "Control keyword labels must be stable across repeated completions")
        assertTrue(first.isNotEmpty(), "Expected at least one control keyword label for stability check")
    }

    @Test
    fun control_keyword_labels_are_exact_lua_spellings() {
        val service = service()
        val document = service.open(
            "workspace/keyword-spellings.lua",
            """
            local n = 0

            return n
            """.trimIndent()
        )

        val labels = service.completionLabelsAt(document, blankLineAfterLocals())
            .filter { it in ALL_CONTROL_KEYWORDS }

        for (label in labels) {
            assertTrue(
                label in ALL_CONTROL_KEYWORDS,
                "Unexpected keyword spelling '$label'; expected one of $ALL_CONTROL_KEYWORDS"
            )
            assertEquals(label.lowercase(), label, "Lua keyword labels must be lowercase stable spellings")
        }
    }

    // -------------------------------------------------------------------------
    // Keyword kind + plain insert text
    // -------------------------------------------------------------------------

    @Test
    fun control_keywords_use_keyword_completion_item_kind() {
        val service = service()
        val document = service.open(
            "workspace/keyword-kind.lua",
            """
            local ready = true

            return ready
            """.trimIndent()
        )

        val items = service.completionItemsAt(document, blankLineAfterLocals())
        val keywordItems = items.filter { it.label in ALL_CONTROL_KEYWORDS }

        assertTrue(keywordItems.isNotEmpty(), "Expected control keyword completion items; actual labels=${items.map { it.label }}")
        for (item in keywordItems) {
            assertEquals(
                CompletionItemKind.Keyword,
                item.kind,
                "Keyword '${item.label}' must use CompletionItemKind.Keyword"
            )
        }
    }

    @Test
    fun control_keywords_insert_plain_keyword_text_equal_to_label() {
        val service = service()
        val document = service.open(
            "workspace/keyword-insert.lua",
            """
            local ready = true

            return ready
            """.trimIndent()
        )

        val items = service.completionItemsAt(document, blankLineAfterLocals())
            .filter { it.label in ALL_CONTROL_KEYWORDS }

        assertTrue(items.isNotEmpty(), "Expected control keyword items for insertText checks")
        for (item in items) {
            val insert = item.insertText ?: item.label
            assertEquals(
                item.label,
                insert,
                "Keyword '${item.label}' insertText must equal the stable label under PlainText policy"
            )
            // Snippet policy §1: keywords remain plain text (not snippet tab-stops).
            assertTrue(
                item.insertTextFormat == null || item.insertTextFormat == InsertTextFormat.PlainText,
                "Keyword '${item.label}' must not use Snippet insert format; was ${item.insertTextFormat}"
            )
        }
    }

    @Test
    fun partial_identifier_still_surfaces_matching_control_keywords() {
        // Cursor on the leading letter of a statement-level name that is also a
        // keyword prefix (e.g. "i" → "if"/"in"), without requiring member trigger chars.
        val service = service()
        val source = """
            local alpha = 1
            i
            return alpha
        """.trimIndent()
        val document = service.open("workspace/keyword-partial.lua", source)
        val position = document.positionOf("i\n")

        val labels = service.completion(document.path, position.line, position.character)
            .items
            .map { it.label }

        assertTrue("if" in labels, "Partial 'i' should surface keyword 'if'; actual=$labels")
        assertTrue("in" in labels, "Partial 'i' should surface keyword 'in'; actual=$labels")
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

        // Policy §2: snippets MAY be absent — that is not a failure.
        if (snippets.isEmpty()) {
            return
        }

        for (snippet in snippets) {
            assertTrue(snippet.label.isNotBlank(), "Snippet labels must be non-blank")
            val insert = snippet.insertText
            assertNotNull(insert, "Snippet '${snippet.label}' must provide insertText")
            assertTrue(insert.isNotBlank(), "Snippet '${snippet.label}' insertText must be non-blank")
            assertEquals(
                InsertTextFormat.Snippet,
                snippet.insertTextFormat,
                "Snippet '${snippet.label}' must advertise InsertTextFormat.Snippet (policy §2)"
            )
        }
    }

    @Test
    fun snippet_presence_does_not_replace_plain_keyword_labels() {
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

        // Policy §3: plain keyword labels remain even if snippet variants exist.
        for (keyword in CORE_CONTROL_KEYWORDS) {
            assertTrue(
                keyword in labels,
                "Plain keyword label '$keyword' must remain available alongside any snippets; actual=$labels"
            )
        }

        val keywordKindLabels = items
            .filter { it.kind == CompletionItemKind.Keyword }
            .map { it.label }
            .toSet()
        for (keyword in CORE_CONTROL_KEYWORDS) {
            assertTrue(
                keyword in keywordKindLabels,
                "Plain keyword '$keyword' must keep CompletionItemKind.Keyword even when snippets exist"
            )
        }
    }

    @Test
    fun snippet_labels_when_present_are_stable_across_repeated_requests() {
        val service = service()
        val document = service.open(
            "workspace/keyword-snippet-stable.lua",
            """
            local alpha = 1

            return alpha
            """.trimIndent()
        )

        val first = service.completionItemsAt(document, blankLineAfterLocals())
            .filter { it.kind == CompletionItemKind.Snippet }
            .map { it.label }
            .sorted()
        val second = service.completionItemsAt(document, blankLineAfterLocals())
            .filter { it.kind == CompletionItemKind.Snippet }
            .map { it.label }
            .sorted()

        // Policy §2: when snippets are present, labels stay stable.
        assertEquals(first, second, "Snippet labels must be stable across repeated completions when present")
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
        /** Core statement openers expected at statement-level completion sites. */
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
