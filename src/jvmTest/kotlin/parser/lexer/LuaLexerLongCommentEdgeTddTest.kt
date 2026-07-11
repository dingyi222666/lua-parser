package parser.lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Long-comment edge corpus for Lua 5.3 block comments (`--[[...]]`, `--[=[...]=]`, …).
 *
 * Acceptance (TASK-323):
 * - Long comments with nested equals/edges lex without hang.
 * - EOF unclosed long comment is deterministic.
 * - Test-only; production lexer is not modified by this task.
 *
 * Complements [LuaLexerLongBracketCorpusTddTest] / [LuaLexerLongStringNestedEqualsTddTest]
 * with a comment-focused inventory: nested equals levels 0..4, level-mismatch recovery,
 * large unclosed bodies (no hang), and EOF-unclosed deterministic BAD_CHARACTER spans.
 */
class LuaLexerLongCommentEdgeTddTest {

    @Test
    fun longCommentsAtEqualsLevels0Through4TokenizeWithoutTruncating() {
        assertTokenCases(
            LexerCase(
                "level-0 body with nested-looking equals fragments",
                "--[[keep ]=] and ]==] inside]]",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[keep ]=] and ]==] inside]]")
            ),
            LexerCase(
                "level-1 embeds level-0 open/close without truncating",
                "--[=[outer [[inner]] still]=]",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[=[outer [[inner]] still]=]")
            ),
            LexerCase(
                "level-2 embeds level-0 and level-1 without truncating",
                "--[==[L2 [=[L1]=] [[L0]] end]==]",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[==[L2 [=[L1]=] [[L0]] end]==]")
            ),
            LexerCase(
                "level-3 embeds all lower closes without truncating",
                "--[===[L3 ]=] ]==] ]] still]===]",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[===[L3 ]=] ]==] ]] still]===]")
            ),
            LexerCase(
                "level-4 embeds all lower open/close fragments without truncating",
                "--[====[L4 [===[x]===] [==[y]==] [=[z]=] [[w]] end]====]",
                token(
                    LuaTokenTypes.BLOCK_COMMENT,
                    "--[====[L4 [===[x]===] [==[y]==] [=[z]=] [[w]] end]====]"
                )
            ),
            LexerCase(
                "empty forms at levels 0..4 remain single tokens",
                "--[[]]--[=[]=]--[==[]==]--[===[]===]--[====[]====]",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[]]"),
                token(LuaTokenTypes.BLOCK_COMMENT, "--[=[]=]"),
                token(LuaTokenTypes.BLOCK_COMMENT, "--[==[]==]"),
                token(LuaTokenTypes.BLOCK_COMMENT, "--[===[]===]"),
                token(LuaTokenTypes.BLOCK_COMMENT, "--[====[]====]")
            )
        )
    }

    @Test
    fun nestedEqualsCommentBodiesPreserveFullTokenTextAndFollowingBoundaries() {
        assertTokenCases(
            LexerCase(
                "level-1 body with lower closes then name",
                "--[=[a ]] b]=]name",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[=[a ]] b]=]"),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "level-2 body with lower closes then concat",
                "--[==[x ]=] y]==]..z",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[==[x ]=] y]==]"),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.NAME, "z")
            ),
            LexerCase(
                "level-3 multiline body keeps newlines then return",
                "--[===[line1\n]=] still\n]===]return",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[===[line1\n]=] still\n]===]"),
                token(LuaTokenTypes.RETURN, "return")
            ),
            LexerCase(
                "level-4 comment then number keeps following number",
                "--[====[payload ]===] ]==] ]=] ]]]====]1",
                token(
                    LuaTokenTypes.BLOCK_COMMENT,
                    "--[====[payload ]===] ]==] ]=] ]]]====]"
                ),
                token(LuaTokenTypes.NUMBER, "1")
            ),
            LexerCase(
                "adjacent levels 0 and 4 stay two comment tokens",
                "--[[a]]--[====[b]====]",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[a]]"),
                token(LuaTokenTypes.BLOCK_COMMENT, "--[====[b]====]")
            ),
            LexerCase(
                "block comment containing string-like and nested openers",
                "--[[ \"not\" 'a' [[string]] ]]x",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[ \"not\" 'a' [[string]] ]]"),
                token(LuaTokenTypes.NAME, "x")
            )
        )
    }

    @Test
    fun matchingCloseOnlyAtExactEqualsLevelDoesNotTruncateEarly() {
        assertTokenCases(
            LexerCase(
                "level-2 ignores level-1 close then ends at level-2",
                "--[==[keep ]=] end]==]",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[==[keep ]=] end]==]")
            ),
            LexerCase(
                "level-3 ignores level-2 close then ends at level-3",
                "--[===[keep ]==] end]===]",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[===[keep ]==] end]===]")
            ),
            LexerCase(
                "level-4 ignores level-3 close then ends at level-4",
                "--[====[keep ]===] end]====]",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[====[keep ]===] end]====]")
            ),
            LexerCase(
                "level-0 still closes at first level-0 even if higher closes follow",
                "--[[early]]]====]",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[early]]"),
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.RBRACK, "]")
            )
        )
    }

    @Test
    fun unclosedLongCommentsRecoverWithoutHangAtLevels0Through4() {
        assertEofMalformedConsumesAll("unclosed empty level-0", "--[[")
        assertEofMalformedConsumesAll("unclosed empty level-1", "--[=[")
        assertEofMalformedConsumesAll("unclosed empty level-2", "--[==[")
        assertEofMalformedConsumesAll("unclosed empty level-3", "--[===[")
        assertEofMalformedConsumesAll("unclosed empty level-4", "--[====[")
        assertEofMalformedConsumesAll("unclosed level-0 body", "--[[unterminated payload")
        assertEofMalformedConsumesAll("unclosed level-1 body with lower close noise", "--[=[has ]] noise")
        assertEofMalformedConsumesAll(
            "unclosed level-2 multiline body",
            "--[==[line1\nline2 still open"
        )
        assertEofMalformedConsumesAll(
            "unclosed level-3 with embedded lower closes",
            "--[===[x ]=] ]==] still open"
        )
        assertEofMalformedConsumesAll(
            "unclosed level-4 with embedded lower opens and closes",
            "--[====[L4 [===[a]===] [==[b]==] open"
        )
    }

    @Test
    fun unclosedLongCommentDoesNotHangOnLargeBody() {
        val body = buildString {
            append("--[====[")
            repeat(8_000) { i ->
                append("chunk-")
                append(i)
                append(" ]===] ]==] ]=] ]] ")
            }
            // deliberately no matching ]====]
        }
        val started = System.nanoTime()
        val result = runCatching { significantTokens(body) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue(
            elapsedMs < 5_000L,
            "Unclosed level-4 large comment body must finish without hang; elapsedMs=$elapsedMs"
        )
        result.onSuccess { tokens ->
            assertTrue(
                tokens.any { it.type == LuaTokenTypes.BAD_CHARACTER },
                "Expected BAD_CHARACTER for unclosed large comment body, got $tokens"
            )
            val firstBad = tokens.indexOfFirst { it.type == LuaTokenTypes.BAD_CHARACTER }
            assertTrue(
                tokens.drop(firstBad + 1).isEmpty(),
                "Unclosed large comment must not invent tokens after BAD_CHARACTER: $tokens"
            )
        }.onFailure { failure ->
            assertTrue(
                failure is IllegalStateException || failure is IllegalArgumentException,
                "Expected lexer exception for unclosed large comment, got ${failure::class.simpleName}: ${failure.message}"
            )
        }
    }

    @Test
    fun levelMismatchAndPartialCloseRecoverWithoutHang() {
        assertMalformedCases(
            MalformedCase("level-1 open closed by level-2", "--[=[x]==]"),
            MalformedCase("level-2 open closed by level-1", "--[==[x]=]"),
            MalformedCase("level-3 open closed by level-2", "--[===[x]==]"),
            MalformedCase("level-4 open closed by level-3", "--[====[x]===]"),
            MalformedCase("level-4 open closed by level-0 only", "--[====[x]]"),
            MalformedCase(
                "level mismatch with trailing code is one bad span",
                "--[=[mismatch]==] local x=1"
            )
        )
        assertEofMalformedConsumesAll(
            "level-4 mismatch with trailing tokens consumes remainder",
            "--[====[x]===] print(1)"
        )
    }

    @Test
    fun incompleteCommentOpenersDoNotConsumeAsLongComments() {
        assertTokenCases(
            LexerCase(
                "dash dash bracket without long open is short comment",
                "--[not long\nx",
                token(LuaTokenTypes.SHORT_COMMENT, "--[not long"),
                token(LuaTokenTypes.NAME, "x")
            ),
            LexerCase(
                "dash dash bracket equals without second bracket is short comment",
                "--[=not long\ny",
                token(LuaTokenTypes.SHORT_COMMENT, "--[=not long"),
                token(LuaTokenTypes.NAME, "y")
            ),
            LexerCase(
                "short comment then long comment stay separate",
                "-- line\n--[[block]]z",
                token(LuaTokenTypes.SHORT_COMMENT, "-- line"),
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[block]]"),
                token(LuaTokenTypes.NAME, "z")
            )
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun assertTokenCases(vararg cases: LexerCase) {
        val failures = cases.mapNotNull { case ->
            runCatching {
                assertContentEquals(case.expected, significantTokens(case.source), case.name)
            }.exceptionOrNull()?.let { failure ->
                "${case.name}\nsource: ${case.source}\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    private fun assertMalformedCases(vararg cases: MalformedCase) {
        val failures = cases.mapNotNull { case ->
            runCatching {
                val result = runCatching { significantTokens(case.source) }
                result.onSuccess { tokens ->
                    assertTrue(
                        tokens.any { it.type == LuaTokenTypes.BAD_CHARACTER },
                        "Expected a BAD_CHARACTER token or lexer exception for ${case.name}, but got $tokens"
                    )
                }.onFailure { failure ->
                    assertTrue(
                        failure is IllegalStateException || failure is IllegalArgumentException,
                        "Expected a lexer literal exception for ${case.name}, but got ${failure::class.simpleName}: ${failure.message}"
                    )
                }
            }.exceptionOrNull()?.let { failure ->
                "${case.name}\nsource: ${case.source}\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    private fun assertEofMalformedConsumesAll(name: String, source: String) {
        val started = System.nanoTime()
        val result = runCatching { significantTokens(source) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue(
            elapsedMs < 2_000L,
            "$name: unclosed long comment must recover without hang; elapsedMs=$elapsedMs"
        )
        result.onSuccess { tokens ->
            assertTrue(
                tokens.isNotEmpty() && tokens.any { it.type == LuaTokenTypes.BAD_CHARACTER },
                "$name: expected BAD_CHARACTER among tokens, got $tokens"
            )
            val firstBad = tokens.indexOfFirst { it.type == LuaTokenTypes.BAD_CHARACTER }
            assertTrue(firstBad >= 0, "$name: missing BAD_CHARACTER in $tokens")
            val afterBad = tokens.drop(firstBad + 1)
            assertTrue(
                afterBad.isEmpty(),
                "$name: expected no tokens after EOF-consuming BAD_CHARACTER, got after=$afterBad full=$tokens"
            )
            val badText = tokens[firstBad].text
            assertTrue(
                source.endsWith(badText) || badText == source,
                "$name: BAD_CHARACTER text should be a suffix of source. bad='$badText' source='$source'"
            )
        }.onFailure { failure ->
            assertTrue(
                failure is IllegalStateException || failure is IllegalArgumentException,
                "$name: expected lexer exception, got ${failure::class.simpleName}: ${failure.message}"
            )
        }
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
                result.add(token(type, normalizeTokenText(type, lexer.tokenText.toString())))
            }
        }
    }

    private fun normalizeTokenText(type: LuaTokenTypes, text: String): String {
        return when (type) {
            LuaTokenTypes.SHORT_COMMENT,
            LuaTokenTypes.DOC_COMMENT,
            LuaTokenTypes.SHEBANG_CONTENT -> text.trimEnd('\r', '\n')
            else -> text
        }
    }

    private class LexerCase(
        val name: String,
        val source: String,
        vararg expected: TokenSnapshot
    ) {
        val expected: List<TokenSnapshot> = expected.toList()
    }

    private data class MalformedCase(
        val name: String,
        val source: String
    )

    private data class TokenSnapshot(
        val type: LuaTokenTypes,
        val text: String
    )

    private companion object {
        fun token(type: LuaTokenTypes, text: String): TokenSnapshot = TokenSnapshot(type, text)
    }
}
