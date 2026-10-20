package lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Long-string **delimiter** edge corpus for Lua 5.3 long brackets.
 *
 * Acceptance (TASK-465 / TASK-595 product lock):
 * - Focus on open/close delimiter shapes themselves (equals levels, incomplete
 *   openers, whitespace-broken openers, high levels, close-fragment operators,
 *   empty-delimiter forms, and adjacency of mismatched delimiters).
 * - Complements body-focused suites without rewriting them:
 *   - [parser.lexer.LuaLexerLongStringEdgeTddTest]
 *   - [parser.lexer.LuaLexerLongStringNestedEqualsTddTest]
 *   - [lexer.LuaLexerLongStringEdgeRefineTddTest]
 *   - [parser.lexer.LuaLexerLongBracketCorpusTddTest]
 * - Well-formed delimiter pairs stay a single LONG_STRING; incomplete / broken
 *   openers split into LBRACK + operators; EOF-unclosed recover as
 *   BAD_CHARACTER (or lexer exception) without hang.
 * - **Unclosed / level-mismatch product lock (TASK-633):** when no matching
 *   same-level close exists, BAD_CHARACTER consumes through EOF so lower-level
 *   close noise and trailing source are not re-lexed as RBRACK/EQ/NAME/print.
 *   Pure level-mismatch forms (wrong-level close only) are the same remainder
 *   contract. Hang-free large-body unclosed forms must still finish.
 * - Prefer product lexer recovery over empty dual-path.
 * - Verification deferred to review / TASK-043 (workers must not run Gradle).
 *
 * Host android.jar paths (docs only):
 * /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar — never G:/.
 */
class LuaLexerLongStringDelimiterEdgeTddTest {

    @Test
    fun emptyDelimiterFormsAtLevels0Through6StaySingleLongStringTokens() {
        assertTokenCases(
            LexerCase(
                "level-0 empty delimiter pair",
                "[[]]",
                token(LuaTokenTypes.LONG_STRING, "[[]]")
            ),
            LexerCase(
                "level-1 empty delimiter pair",
                "[=[]=]",
                token(LuaTokenTypes.LONG_STRING, "[=[]=]")
            ),
            LexerCase(
                "level-2 empty delimiter pair",
                "[==[]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[]==]")
            ),
            LexerCase(
                "level-3 empty delimiter pair",
                "[===[]===]",
                token(LuaTokenTypes.LONG_STRING, "[===[]===]")
            ),
            LexerCase(
                "level-4 empty delimiter pair",
                "[====[]====]",
                token(LuaTokenTypes.LONG_STRING, "[====[]====]")
            ),
            LexerCase(
                "level-5 empty delimiter pair",
                "[=====[]=====]",
                token(LuaTokenTypes.LONG_STRING, "[=====[]=====]")
            ),
            LexerCase(
                "level-6 empty delimiter pair (high equals count)",
                "[======[]======]",
                token(LuaTokenTypes.LONG_STRING, "[======[]======]")
            ),
            LexerCase(
                "chained empty delimiter pairs levels 0..3",
                "[[]][=[]=][==[]==][===[]===]",
                token(LuaTokenTypes.LONG_STRING, "[[]]"),
                token(LuaTokenTypes.LONG_STRING, "[=[]=]"),
                token(LuaTokenTypes.LONG_STRING, "[==[]==]"),
                token(LuaTokenTypes.LONG_STRING, "[===[]===]")
            )
        )
    }

    @Test
    fun unclosedDelimiterOpenersRecoverWithoutHang() {
        assertEofMalformedConsumesAll("unclosed empty level-0 opener", "[[")
        assertEofMalformedConsumesAll("unclosed empty level-1 opener", "[=[")
        assertEofMalformedConsumesAll("unclosed empty level-2 opener", "[==[")
        assertEofMalformedConsumesAll("unclosed empty level-3 opener", "[===[")
        assertEofMalformedConsumesAll("unclosed empty level-4 opener", "[====[")
        assertEofMalformedConsumesAll("unclosed empty level-5 opener", "[=====[")
        assertEofMalformedConsumesAll("unclosed empty level-6 opener", "[======[")
        assertEofMalformedConsumesAll("unclosed level-0 body after opener", "[[unterminated")
        // Complete lower-level closes inside an unclosed high-level body are not
        // matching terminators: BAD_CHARACTER still consumes remainder to EOF.
        assertEofMalformedConsumesAll(
            "unclosed level-6 body with lower close noise consumes remainder",
            "[======[L6 ]=====] ]====] ]===] open"
        )
        assertEofMalformedConsumesAll(
            "EOF mid level-6 close (missing final ])",
            "[======[body]======"
        )
        assertEofMalformedConsumesAll(
            "EOF mid level-3 close after partial equals",
            "[===[body]==="
        )
        assertEofMalformedConsumesAll(
            "EOF after level-0 body single ]",
            "[[body]"
        )
    }

    @Test
    fun levelMismatchDelimitersRecoverWithoutInventingFollowingTokens() {
        // TASK-633 product lock: wrong-level close never matches the opener, so
        // BAD_CHARACTER consumes through EOF (including trailing source).
        assertEofMalformedConsumesAll(
            "level-1 open closed by level-2 then trailing space+local",
            "[=[mismatch]==] local x=1"
        )
        assertEofMalformedConsumesAll(
            "level-6 open closed by level-5 then print call",
            "[======[x]=====] print(1)"
        )
        assertEofMalformedConsumesAll(
            "level-2 open closed by level-1 then name",
            "[==[x]=]name"
        )
        assertTokenCases(
            LexerCase(
                "level-1 open closed by level-2 only",
                "[=[x]==]",
                token(LuaTokenTypes.BAD_CHARACTER, "[=[x]==]")
            ),
            LexerCase(
                "level-2 open closed by level-1 only",
                "[==[x]=]",
                token(LuaTokenTypes.BAD_CHARACTER, "[==[x]=]")
            ),
            LexerCase(
                "level-3 open closed by level-0 only",
                "[===[x]]",
                token(LuaTokenTypes.BAD_CHARACTER, "[===[x]]")
            ),
            LexerCase(
                "level-6 open closed by level-0 only",
                "[======[x]]",
                token(LuaTokenTypes.BAD_CHARACTER, "[======[x]]")
            ),
            LexerCase(
                "level-6 open closed by level-5 only",
                "[======[x]=====]",
                token(LuaTokenTypes.BAD_CHARACTER, "[======[x]=====]")
            )
        )
        assertMalformedCases(
            MalformedCase("level-1 open closed by level-2", "[=[x]==]"),
            MalformedCase("level-2 open closed by level-1", "[==[x]=]"),
            MalformedCase("level-3 open closed by level-0", "[===[x]]"),
            MalformedCase("level-6 open closed by level-5", "[======[x]=====]"),
            MalformedCase("level-6 open closed by level-0 only", "[======[x]]")
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
