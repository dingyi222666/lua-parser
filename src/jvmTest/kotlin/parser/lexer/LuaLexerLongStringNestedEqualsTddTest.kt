package parser.lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Nested-equals long-string corpus for Lua 5.3 long brackets.
 *
 * Focuses on acceptance for TASK-278:
 * - levels 0..4 tokenize as a single LONG_STRING without truncating on
 *   embedded lower-level open/close fragments
 * - unclosed long strings recover (BAD_CHARACTER / exception) without hang
 *   and without inventing following significant tokens
 *
 * Test-only; production lexer is not modified by this task.
 */
class LuaLexerLongStringNestedEqualsTddTest {

    @Test
    fun longStringsAtEqualsLevels0Through4TokenizeWithoutTruncating() {
        assertTokenCases(
            LexerCase(
                "level-0 body with nested-looking equals fragments",
                "[[keep ]=] and ]==] inside]]",
                token(LuaTokenTypes.LONG_STRING, "[[keep ]=] and ]==] inside]]")
            ),
            LexerCase(
                "level-1 embeds level-0 open/close without truncating",
                "[=[outer [[inner]] still]=]",
                token(LuaTokenTypes.LONG_STRING, "[=[outer [[inner]] still]=]")
            ),
            LexerCase(
                "level-2 embeds level-0 and level-1 without truncating",
                "[==[L2 [=[L1]=] [[L0]] end]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[L2 [=[L1]=] [[L0]] end]==]")
            ),
            LexerCase(
                "level-3 embeds all lower closes without truncating",
                "[===[L3 ]=] ]==] ]] still]===]",
                token(LuaTokenTypes.LONG_STRING, "[===[L3 ]=] ]==] ]] still]===]")
            ),
            LexerCase(
                "level-4 embeds all lower open/close fragments without truncating",
                "[====[L4 [===[x]===] [==[y]==] [=[z]=] [[w]] end]====]",
                token(
                    LuaTokenTypes.LONG_STRING,
                    "[====[L4 [===[x]===] [==[y]==] [=[z]=] [[w]] end]====]"
                )
            ),
            LexerCase(
                "empty forms at levels 0..4 remain single tokens",
                "[[]][=[]=][==[]==][===[]===][====[]====]",
                token(LuaTokenTypes.LONG_STRING, "[[]]"),
                token(LuaTokenTypes.LONG_STRING, "[=[]=]"),
                token(LuaTokenTypes.LONG_STRING, "[==[]==]"),
                token(LuaTokenTypes.LONG_STRING, "[===[]===]"),
                token(LuaTokenTypes.LONG_STRING, "[====[]====]")
            )
        )
    }

    @Test
    fun nestedEqualsBodiesPreserveFullTokenTextAndFollowingBoundaries() {
        assertTokenCases(
            LexerCase(
                "level-1 body with lower closes then name",
                "[=[a ]] b]=]name",
                token(LuaTokenTypes.LONG_STRING, "[=[a ]] b]=]"),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "level-2 body with lower closes then concat",
                "[==[x ]=] y]==]..z",
                token(LuaTokenTypes.LONG_STRING, "[==[x ]=] y]==]"),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.NAME, "z")
            ),
            LexerCase(
                "level-3 multiline body keeps newlines then return",
                "[===[line1\n]=] still\n]===]return",
                token(LuaTokenTypes.LONG_STRING, "[===[line1\n]=] still\n]===]"),
                token(LuaTokenTypes.RETURN, "return")
            ),
            LexerCase(
                "level-4 assignment RHS keeps following number",
                "s=[====[payload ]===] ]==] ]=] ]]]====]1",
                token(LuaTokenTypes.NAME, "s"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(
                    LuaTokenTypes.LONG_STRING,
                    "[====[payload ]===] ]==] ]=] ]]]====]"
                ),
                token(LuaTokenTypes.NUMBER, "1")
            ),
            LexerCase(
                "print string-call style level-2 adjacency",
                "print[==[hello ]=] world]==]",
                token(LuaTokenTypes.NAME, "print"),
                token(LuaTokenTypes.LONG_STRING, "[==[hello ]=] world]==]")
            ),
            LexerCase(
                "adjacent levels 0 and 4 stay two tokens",
                "[[a]][====[b]====]",
                token(LuaTokenTypes.LONG_STRING, "[[a]]"),
                token(LuaTokenTypes.LONG_STRING, "[====[b]====]")
            )
        )
    }

    @Test
    fun matchingCloseOnlyAtExactEqualsLevelDoesNotTruncateEarly() {
        assertTokenCases(
            LexerCase(
                "level-2 ignores level-1 close then ends at level-2",
                "[==[keep ]=] end]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[keep ]=] end]==]")
            ),
            LexerCase(
                "level-3 ignores level-2 close then ends at level-3",
                "[===[keep ]==] end]===]",
                token(LuaTokenTypes.LONG_STRING, "[===[keep ]==] end]===]")
            ),
            LexerCase(
                "level-4 ignores level-3 close then ends at level-4",
                "[====[keep ]===] end]====]",
                token(LuaTokenTypes.LONG_STRING, "[====[keep ]===] end]====]")
            ),
            LexerCase(
                "level-0 still closes at first level-0 even if higher closes follow",
                "[[early]]]====]",
                token(LuaTokenTypes.LONG_STRING, "[[early]]"),
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.RBRACK, "]")
            )
        )
    }

    @Test
    fun unclosedLongStringsRecoverWithoutHangAtLevels0Through4() {
        assertEofMalformedConsumesAll("unclosed empty level-0", "[[")
        assertEofMalformedConsumesAll("unclosed empty level-1", "[=[")
        assertEofMalformedConsumesAll("unclosed empty level-2", "[==[")
        assertEofMalformedConsumesAll("unclosed empty level-3", "[===[")
        assertEofMalformedConsumesAll("unclosed empty level-4", "[====[")
        assertEofMalformedConsumesAll("unclosed level-0 body", "[[unterminated payload")
        assertEofMalformedConsumesAll("unclosed level-1 body with lower close noise", "[=[has ]] noise")
        assertEofMalformedConsumesAll(
            "unclosed level-2 multiline body",
            "[==[line1\nline2 still open"
        )
        assertEofMalformedConsumesAll(
            "unclosed level-3 with embedded lower closes",
            "[===[x ]=] ]==] still open"
        )
        assertEofMalformedConsumesAll(
            "unclosed level-4 with embedded lower opens and closes",
            "[====[L4 [===[a]===] [==[b]==] open"
        )
    }

    @Test
    fun unclosedLongStringDoesNotHangOnLargeBody() {
        // Synthetic large unclosed body: must finish in bounded steps (no hang).
        val body = buildString {
            append("[====[")
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
            "Unclosed level-4 large body must finish without hang; elapsedMs=$elapsedMs"
        )
        result.onSuccess { tokens ->
            assertTrue(
                tokens.any { it.type == LuaTokenTypes.BAD_CHARACTER },
                "Expected BAD_CHARACTER for unclosed large body, got $tokens"
            )
            val firstBad = tokens.indexOfFirst { it.type == LuaTokenTypes.BAD_CHARACTER }
            assertTrue(
                tokens.drop(firstBad + 1).isEmpty(),
                "Unclosed large body must not invent tokens after BAD_CHARACTER: $tokens"
            )
        }.onFailure { failure ->
            assertTrue(
                failure is IllegalStateException || failure is IllegalArgumentException,
                "Expected lexer exception for unclosed large body, got ${failure::class.simpleName}: ${failure.message}"
            )
        }
    }

    @Test
    fun levelMismatchAndPartialCloseRecoverWithoutHang() {
        assertMalformedCases(
            MalformedCase("level-1 open closed by level-2", "[=[x]==]"),
            MalformedCase("level-2 open closed by level-1", "[==[x]=]"),
            MalformedCase("level-3 open closed by level-2", "[===[x]==]"),
            MalformedCase("level-4 open closed by level-3", "[====[x]===]"),
            MalformedCase("level-4 open closed by level-0 only", "[====[x]]"),
            MalformedCase(
                "level mismatch with trailing code is one bad span",
                "[=[mismatch]==] local x=1"
            )
        )
        assertEofMalformedConsumesAll(
            "level-4 mismatch with trailing tokens consumes remainder",
            "[====[x]===] print(1)"
        )
    }

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
            "$name: unclosed long string must recover without hang; elapsedMs=$elapsedMs"
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
        // Hard cap guards against accidental infinite nextToken loops.
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
