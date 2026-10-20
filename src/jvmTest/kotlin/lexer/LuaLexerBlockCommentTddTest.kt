package lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Block-comment (long-bracket comment) semantics lock: real Lua block comments
 * DO NOT NEST.
 *
 * Acceptance (adversarial audit wave DD fix lock):
 * - `--[[ toggle [[\n--]]\ncode_here()\n--[[ end ]]` ends the comment at the
 *   FIRST same-level close (`--]]`); `code_here()` stays executable and the
 *   trailing `--[[ end ]]` is a second comment (2 comments total).
 * - `--[==[ a [==[ b ]==] c ]==]` is one comment ending at the first `]==]`
 *   (level-matched, non-nesting); the inner `[==[` open is inert text.
 * - Unclosed `--[==[` followed by many same-level opens must terminate without
 *   the removed quadratic rescan and consume through EOF (TASK-633
 *   remainder-consume contract); the test-framework timeout plus an explicit
 *   2s budget is the hang guard.
 * - Level mismatch (`[====[ x ]=] y ]====]`) keeps the wrong-level closer
 *   inside the body for both LONG_STRING and BLOCK_COMMENT (existing behavior
 *   preserved).
 * - Parser integration: source whose pseudo-nested comment used to swallow
 *   `code_here()` must yield a CallStatement (LuaParser, ANDROLUA default).
 *
 * Verification deferred to review / TASK-043 (workers must not run Gradle).
 */
class LuaLexerBlockCommentTddTest {

    @Test
    fun blockCommentEndsAtFirstSameLevelCloseAndCodeAfterIsLexed() {
        val source = "--[[ toggle [[\n--]]\ncode_here()\n--[[ end ]]"
        val tokens = significantTokens(source)

        assertContentEquals(
            listOf(
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[ toggle [[\n--]]"),
                token(LuaTokenTypes.NAME, "code_here"),
                token(LuaTokenTypes.LPAREN, "("),
                token(LuaTokenTypes.RPAREN, ")"),
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[ end ]]")
            ),
            tokens,
            "comment must end at the first same-level close (--]]); code_here() must be lexed"
        )
        assertEquals(
            2,
            tokens.count { it.type == LuaTokenTypes.BLOCK_COMMENT },
            "exactly two comments: the opener and the trailing --[[ end ]]"
        )
    }

    @Test
    fun level2BlockCommentEndsAtFirstSameLevelCloseWithoutNesting() {
        val source = "--[==[ a [==[ b ]==] c ]==]"
        val tokens = significantTokens(source)

        assertContentEquals(
            listOf(
                token(LuaTokenTypes.BLOCK_COMMENT, "--[==[ a [==[ b ]==]"),
                token(LuaTokenTypes.NAME, "c"),
                token(LuaTokenTypes.RBRACK, "]"),
                // `==` lexes as the equality operator in the trailing code.
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.RBRACK, "]")
            ),
            tokens,
            "level-2 comment must end at the first ]==]; the inner [==[ is inert text"
        )
        assertEquals(
            " a [==[ b ",
            tokens.first().text.removePrefix("--[==[").removeSuffix("]==]"),
            "comment body stops right before the first same-level close"
        )
    }

    @Test
    fun unclosedLevel2CommentWithManySameLevelOpensTerminatesAndConsumesEof() {
        // Pre-fix shape was quadratic: every same-level open without an outer close
        // re-scanned the rest of the buffer (~7s at 40KB). The test-framework
        // timeout plus this explicit budget is the hang guard; the correctness lock
        // is a single BAD_CHARACTER consuming through EOF.
        val source = "--[==[" + "[==[".repeat(100) + " fill".repeat(64)

        val started = System.nanoTime()
        val tokens = significantTokens(source)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertTrue(
            elapsedMs < 2_000L,
            "unclosed comment with many same-level opens must finish without quadratic rescan; elapsedMs=$elapsedMs"
        )
        assertEquals(
            listOf(token(LuaTokenTypes.BAD_CHARACTER, source)),
            tokens,
            "unclosed comment must consume through EOF as one BAD_CHARACTER"
        )
    }

    @Test
    fun levelMismatchCloserStaysInsideLongBracketBody() {
        // Existing behavior preserved: a wrong-level close inside the body does not
        // terminate the span; only the exact same-level close does. Applies equally
        // to long strings and block comments.
        assertSingleToken("[====[ x ]=] y ]====]", LuaTokenTypes.LONG_STRING)
        assertSingleToken("--[====[ x ]=] y ]====]", LuaTokenTypes.BLOCK_COMMENT)
    }

    @Test
    fun parserYieldsCallStatementForCodePseudoNestingUsedToSwallow() {
        val source = "--[[ toggle [[\n--]]\ncode_here()\n--[[ end ]]"

        // LuaParser's constructor default is LuaVersion.ANDROLUA_5_3; passed
        // explicitly to pin the AndroLua surface under test.
        val chunk = LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3).parse(source)

        val callStatements = chunk.body.statements.filterIsInstance<CallStatement>()
        assertEquals(
            1,
            callStatements.size,
            "code_here() must survive as a call statement instead of being swallowed by the comment"
        )
        val base = assertIs<Identifier>(callStatements.single().expression.base)
        assertEquals("code_here", base.name)

        val comments = chunk.body.statements.filterIsInstance<CommentStatement>()
        assertTrue(
            comments.isNotEmpty() && comments.first().comment.startsWith("--[[ toggle [["),
            "leading block comment must stay a statement-level comment; statements=${chunk.body.statements}"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun assertSingleToken(source: String, type: LuaTokenTypes) {
        val tokens = significantTokens(source)
        assertEquals(
            listOf(token(type, source)),
            tokens,
            "$type must stay one token with the mismatched closer inside its body"
        )
    }

    private fun significantTokens(source: String): List<TokenSnapshot> {
        val lexer = LuaLexer(source)
        val result = mutableListOf<TokenSnapshot>()
        val maxSteps = source.length * 4 + 64
        var steps = 0
        while (true) {
            steps++
            assertTrue(steps <= maxSteps, "Lexer appeared to hang after $steps steps on source length ${source.length}")
            val type = lexer.nextToken()
            if (type == LuaTokenTypes.EOF) {
                return result
            }
            if (type != LuaTokenTypes.WHITE_SPACE && type != LuaTokenTypes.NEW_LINE) {
                result.add(token(type, lexer.tokenText.toString()))
            }
        }
    }

    private data class TokenSnapshot(
        val type: LuaTokenTypes,
        val text: String
    )

    private companion object {
        fun token(type: LuaTokenTypes, text: String): TokenSnapshot = TokenSnapshot(type, text)
    }
}
