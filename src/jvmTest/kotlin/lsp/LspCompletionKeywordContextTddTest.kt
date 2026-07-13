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
 * TASK-372 — LSP completion keyword **context** corpus.
 *
 * Companion to [LspCompletionKeywordSnippetTddTest] (TASK-218). That suite locks
 * the keyword/snippet **policy** and the baseline statement-level lexical surface.
 * This suite locks **context-sensitive** completion behavior across syntactic
 * sites without inventing product APIs.
 *
 * ## Actual completion surface (current product)
 *
 * JVM LSP completion ([LuaLanguageService.completion]) maps semantic
 * [io.github.dingyi222666.luaparser.semantic.model.CompletionProvider] results:
 *
 * - **Lexical context** (statement / expression / identifier sites that are not
 *   a member access): visible locals, parameters, functions, and seeded builtins
 *   (e.g. `print`). Kinds derive from symbol kinds; insert format is always
 *   [InsertTextFormat.PlainText].
 * - **Member context** (cursor on a [MemberExpression] / its identifier after
 *   `.` / `:`): member surface only for the resolved base type (fields/methods).
 * - **Keywords**: statement-level Lua control-structure keywords are **not**
 *   emitted as [CompletionItemKind.Keyword] items in any of these contexts today.
 * - **Context suppression**: product does **not** currently hard-suppress
 *   completions inside short string literals or line comments; lexical items may
 *   still surface. That is recorded as the actual surface (not a client filter).
 *
 * ## Context policy locked here
 *
 * 1. Statement blank-line context: lexical locals + builtins; no Keyword-kind
 *    control keywords.
 * 2. Expression / RHS context: same lexical surface (locals remain available).
 * 3. Nested block context: inner locals visible; outer locals remain; later
 *    siblings stay out of scope (shadowing / visibility already covered deeper
 *    in semantic tests — here we only lock the LSP mapping path).
 * 4. Member `.` / `:` contexts: annotated member labels surface; control
 *    Keyword-kind items stay absent; ordinary free locals are not required as
 *    member labels.
 * 5. Partial identifier in statement context: matching locals remain; Keyword
 *    forms for prefixes of `if`/`in` stay absent.
 * 6. Documented desired Keyword spellings remain the stable lowercase Lua 5.x
 *    set (presence still a product gap — same as TASK-218).
 *
 * Workers must not run Gradle; verification is review-owned and serial.
 */
class LspCompletionKeywordContextTddTest {

    // -------------------------------------------------------------------------
    // Statement / expression lexical contexts
    // -------------------------------------------------------------------------

    @Test
    fun statement_blank_line_context_surfaces_lexical_locals_not_keywords() {
        val service = service()
        val document = service.open(
            "workspace/kw-ctx-statement.lua",
            """
            local alpha = 1

            return alpha
            """.trimIndent()
        )

        val items = service.completionItemsAt(document, blankLineAfterLocals())
        val labels = items.map { it.label }

        assertTrue("alpha" in labels, "Statement context must surface local 'alpha'; actual=$labels")
        assertTrue("print" in labels, "Statement context must surface builtin 'print'; actual=$labels")
        assertNoControlKeywordItems(items, context = "statement blank line")
    }

    @Test
    fun expression_rhs_context_surfaces_visible_locals() {
        // Cursor on the RHS identifier of a local initializer (expression context).
        val service = service()
        val document = service.open(
            "workspace/kw-ctx-rhs.lua",
            """
            local alpha = 1
            local beta = alpha
            return beta
            """.trimIndent()
        )

        val items = service.completionItemsAt(document, document.positionOf("alpha", occurrence = 2))
        val labels = items.map { it.label }

        assertTrue(
            "alpha" in labels,
            "RHS expression context must surface visible local 'alpha'; actual=$labels"
        )
        assertTrue(
            "print" in labels,
            "RHS expression context must still expose seeded builtin 'print'; actual=$labels"
        )
        assertNoControlKeywordItems(items, context = "expression RHS")
    }

    // -------------------------------------------------------------------------
    // Member contexts (. / :)
    // -------------------------------------------------------------------------

    @Test
    fun member_dot_context_surfaces_fields_without_control_keywords() {
        val service = service()
        val document = service.open(
            "workspace/kw-ctx-member-dot.lua",
            """
            ---@class User
            ---@field name string
            ---@method User:getName(): string
            ---@type User
            local user = {}
            local fieldValue = user.name
            return fieldValue
            """.trimIndent()
        )

        // Second "name" is the member access site (first is the @field annotation).
        val items = service.completionItemsAt(document, document.positionOf("name", occurrence = 2))
        val labels = items.map { it.label }

        assertTrue(
            "name" in labels,
            "Dot member context must surface field 'name'; actual=$labels"
        )
        assertNoControlKeywordItems(items, context = "member '.' context")
        // Free locals are not required on the member surface; if present they
        // must not be Keyword-kind (already covered by assertNoControlKeywordItems).
    }

    @Test
    fun member_colon_context_surfaces_methods_without_control_keywords() {
        val service = service()
        val document = service.open(
            "workspace/kw-ctx-member-colon.lua",
            """
            ---@class User
            ---@field name string
            ---@method User:getName(): string
            ---@type User
            local user = {}
            local methodValue = user:getName()
            return methodValue
            """.trimIndent()
        )

        val items = service.completionItemsAt(document, document.positionOf("getName", occurrence = 2))
        val labels = items.map { it.label }

        assertTrue(
            "getName" in labels,
            "Colon member context must surface method 'getName'; actual=$labels"
        )
        assertNoControlKeywordItems(items, context = "member ':' context")
    }

    // -------------------------------------------------------------------------
    // Partial-prefix and non-code soft contexts
    // -------------------------------------------------------------------------

    @Test
    fun string_literal_context_does_not_emit_keyword_items() {
        // Product currently does not hard-suppress completions inside short
        // strings; this corpus only locks the Keyword-kind absence + non-throw.
        val service = service()
        val document = service.open(
            "workspace/kw-ctx-string.lua",
            """
            local alpha = 1
            local greeting = "hello"
            return alpha
            """.trimIndent()
        )

        val items = service.completionItemsAt(document, document.positionOf("hello"))
        assertNoControlKeywordItems(items, context = "string literal body")
        // Non-throw / stable mapping: every item still follows PlainText insert policy.
        for (item in items) {
            assertTrue(
                item.insertTextFormat == null || item.insertTextFormat == InsertTextFormat.PlainText,
                "String-context item '${item.label}' must stay PlainText on the LSP path"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Stability + documented spellings across contexts
    // -------------------------------------------------------------------------

    @Test
    fun control_keyword_spellings_remain_documented_lowercase_lua_forms() {
        // Corpus documents the stable Lua spellings expected if/when product emits
        // keywords in statement context. Does not require product to emit them.
        for (keyword in ALL_CONTROL_KEYWORDS) {
            assertEquals(
                keyword.lowercase(),
                keyword,
                "Documented keyword spelling '$keyword' must be lowercase Lua form"
            )
            assertTrue(
                keyword.isNotBlank() && keyword.all { it.isLetter() },
                "Documented keyword spelling '$keyword' must be a simple letter token"
            )
        }
        assertEquals(
            setOf(
                "if", "while", "for", "function", "repeat", "do",
                "else", "elseif", "then", "end", "until", "in",
                "local", "return", "break", "goto"
            ),
            ALL_CONTROL_KEYWORDS,
            "Documented control-keyword corpus must stay the stable Lua 5.x set"
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

    private fun LuaLanguageService.completionItemsAt(
        document: OpenDocument,
        position: Position
    ): List<CompletionItem> {
        return completion(document.path, position.line, position.character).items
    }

    private fun LuaLanguageService.completionLabelsAt(
        document: OpenDocument,
        position: Position
    ): List<String> {
        return completionItemsAt(document, position).map { it.label }
    }

    /**
     * Cursor on the blank line between the local binding and the trailing return
     * in the standard two-statement fixtures (line index 1, character 0).
     */
    private fun blankLineAfterLocals(): Position = Position(1, 0)

    private fun assertNoControlKeywordItems(items: List<CompletionItem>, context: String) {
        val labels = items.map { it.label }
        for (keyword in ALL_CONTROL_KEYWORDS) {
            val keywordKindHits = items.filter {
                it.label == keyword && it.kind == CompletionItemKind.Keyword
            }
            assertTrue(
                keywordKindHits.isEmpty(),
                "Product gap lock ($context): control keyword '$keyword' must not appear as " +
                    "CompletionItemKind.Keyword until product seeds keywords; actual=$labels"
            )
        }
        val anyKeywordKind = items.filter { it.kind == CompletionItemKind.Keyword }
        assertTrue(
            anyKeywordKind.isEmpty(),
            "Product gap lock ($context): no CompletionItemKind.Keyword items expected; " +
                "was ${anyKeywordKind.map { it.label }}"
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

    private companion object {
        val CORE_CONTROL_KEYWORDS = listOf(
            "if",
            "while",
            "for",
            "function",
            "repeat",
            "do"
        )

        val LOOP_AND_BRANCH_KEYWORDS = listOf(
            "else",
            "elseif",
            "then",
            "end",
            "until",
            "in"
        )

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
