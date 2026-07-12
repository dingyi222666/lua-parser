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
    fun incompleteOpenersSplitIntoLbrackAndOperators() {
        // Product: longBracketEqualsCount requires '[' '='* '[' with no gaps.
        // Incomplete openers never become LONG_STRING.
        assertTokenCases(
            LexerCase(
                "lone left bracket",
                "[",
                token(LuaTokenTypes.LBRACK, "[")
            ),
            LexerCase(
                "bracket then single equals (missing final open bracket)",
                "[=",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.ASSIGN, "=")
            ),
            LexerCase(
                "bracket then double equals (missing final open bracket)",
                "[==",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.EQ, "==")
            ),
            LexerCase(
                "bracket then triple equals (missing final open bracket)",
                "[===",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.ASSIGN, "=")
            ),
            LexerCase(
                "bracket then quadruple equals (missing final open bracket)",
                "[====",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "==")
            ),
            LexerCase(
                "bracket then five equals (missing final open bracket)",
                "[=====",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.ASSIGN, "=")
            ),
            LexerCase(
                "bracket then six equals (missing final open bracket)",
                "[======",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "==")
            ),
            LexerCase(
                "well-formed empty then incomplete high opener stays split",
                "[[]][=====",
                token(LuaTokenTypes.LONG_STRING, "[[]]"),
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.ASSIGN, "=")
            )
        )
    }

    @Test
    fun whitespaceBrokenOpenersAreNotLongStringDelimiters() {
        // Any gap (space/tab/newline) between '[' and '=' or between '=' and '['
        // breaks the long-bracket opener; tokens fall back to LBRACK + operators.
        assertTokenCases(
            LexerCase(
                "space between bracket and equals",
                "[ =[",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LBRACK, "[")
            ),
            LexerCase(
                "tab between bracket and equals",
                "[\t=[",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LBRACK, "[")
            ),
            LexerCase(
                "space between equals and second bracket",
                "[= [",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LBRACK, "[")
            ),
            LexerCase(
                "LF between bracket and equals",
                "[\n=[",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LBRACK, "[")
            ),
            LexerCase(
                "CRLF between equals and second bracket",
                "[=\r\n[",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LBRACK, "[")
            ),
            LexerCase(
                "space inside multi-equals open sequence",
                "[== =[",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LBRACK, "[")
            ),
            LexerCase(
                "space before high-level second bracket",
                "[===== [",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LBRACK, "[")
            )
        )
    }

    @Test
    fun closeFragmentsOutsideLongBracketsStayOperators() {
        assertTokenCases(
            LexerCase(
                "level-0 close fragment alone",
                "]]",
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.RBRACK, "]")
            ),
            LexerCase(
                "level-1 close fragment alone",
                "]=]",
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.RBRACK, "]")
            ),
            LexerCase(
                "level-2 close fragment alone",
                "]==]",
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.RBRACK, "]")
            ),
            LexerCase(
                "level-3 close fragment alone",
                "]===]",
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.RBRACK, "]")
            ),
            LexerCase(
                "level-4 close fragment alone",
                "]====]",
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.RBRACK, "]")
            ),
            LexerCase(
                "level-6 close fragment alone",
                "]======]",
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.RBRACK, "]")
            ),
            LexerCase(
                "partial close without trailing bracket",
                "]===",
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.ASSIGN, "=")
            ),
            LexerCase(
                "well-formed long string then stray higher close fragment",
                "[[x]]]====]",
                token(LuaTokenTypes.LONG_STRING, "[[x]]"),
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.RBRACK, "]")
            )
        )
    }

    @Test
    fun matchingDelimiterLevelsPreserveExactOpenCloseText() {
        assertTokenCases(
            LexerCase(
                "level-0 open/close with short body",
                "[[body]]",
                token(LuaTokenTypes.LONG_STRING, "[[body]]")
            ),
            LexerCase(
                "level-1 open/close ignores level-0 close in body",
                "[=[keep ]] still]=]",
                token(LuaTokenTypes.LONG_STRING, "[=[keep ]] still]=]")
            ),
            LexerCase(
                "level-2 open/close ignores level-1 close in body",
                "[==[keep ]=] still]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[keep ]=] still]==]")
            ),
            LexerCase(
                "level-6 open/close ignores all lower closes in body",
                "[======[L6 ]=====] ]====] ]===] ]==] ]=] ]] end]======]",
                token(
                    LuaTokenTypes.LONG_STRING,
                    "[======[L6 ]=====] ]====] ]===] ]==] ]=] ]] end]======]"
                )
            ),
            LexerCase(
                "level-0 closes at first matching delimiter even if higher follows",
                "[[early]]]=====]",
                token(LuaTokenTypes.LONG_STRING, "[[early]]"),
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.RBRACK, "]")
            ),
            LexerCase(
                "adjacent different delimiter levels stay two tokens",
                "[[a]][======[b]======]",
                token(LuaTokenTypes.LONG_STRING, "[[a]]"),
                token(LuaTokenTypes.LONG_STRING, "[======[b]======]")
            ),
            LexerCase(
                "level-3 then level-1 adjacency after space",
                "[===[x]===] [=[y]=]",
                token(LuaTokenTypes.LONG_STRING, "[===[x]===]"),
                token(LuaTokenTypes.LONG_STRING, "[=[y]=]")
            )
        )
    }

    @Test
    fun delimiterAdjacencyWithOperatorsAndCallsKeepsBoundaries() {
        assertTokenCases(
            LexerCase(
                "string-call style with level-0 delimiters",
                "print[[hello]]",
                token(LuaTokenTypes.NAME, "print"),
                token(LuaTokenTypes.LONG_STRING, "[[hello]]")
            ),
            LexerCase(
                "string-call style with level-6 delimiters",
                "print[======[hi]======]",
                token(LuaTokenTypes.NAME, "print"),
                token(LuaTokenTypes.LONG_STRING, "[======[hi]======]")
            ),
            LexerCase(
                "concat of two different delimiter levels",
                "[[a]]..[======[b]======]",
                token(LuaTokenTypes.LONG_STRING, "[[a]]"),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.LONG_STRING, "[======[b]======]")
            ),
            LexerCase(
                "assignment RHS high-level delimiter then number",
                "s=[======[payload]======]1",
                token(LuaTokenTypes.NAME, "s"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LONG_STRING, "[======[payload]======]"),
                token(LuaTokenTypes.NUMBER, "1")
            ),
            LexerCase(
                "table field value with level-2 delimiters",
                "{k=[==[v]==],}",
                token(LuaTokenTypes.LCURLY, "{"),
                token(LuaTokenTypes.NAME, "k"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LONG_STRING, "[==[v]==]"),
                token(LuaTokenTypes.COMMA, ","),
                token(LuaTokenTypes.RCURLY, "}")
            ),
            LexerCase(
                "parens around level-1 delimiters",
                "([=[x]=])",
                token(LuaTokenTypes.LPAREN, "("),
                token(LuaTokenTypes.LONG_STRING, "[=[x]=]"),
                token(LuaTokenTypes.RPAREN, ")")
            ),
            LexerCase(
                "return keyword then high-level delimiter",
                "return[======[ok]======]",
                token(LuaTokenTypes.RETURN, "return"),
                token(LuaTokenTypes.LONG_STRING, "[======[ok]======]")
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

    @Test
    fun unclosedHighLevelDelimiterLargeBodyDoesNotHang() {
        // True unclosed form: lower-level noise is incomplete (no full `]`…`]` close),
        // so BAD_CHARACTER still spans to EOF. Hang-freedom is the primary lock.
        val body = buildString {
            append("[======[")
            repeat(6_000) { i ->
                append("delim-")
                append(i)
                // incomplete fragments only — missing final `]` so not a full close
                append(" ]===== ]==== ]=== ]== ]= ] ")
            }
            // deliberately no matching ]======]
        }
        val started = System.nanoTime()
        val result = runCatching { significantTokens(body) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue(
            elapsedMs < 5_000L,
            "Unclosed level-6 large delimiter body must finish without hang; elapsedMs=$elapsedMs"
        )
        result.onSuccess { tokens ->
            assertTrue(
                tokens.any { it.type == LuaTokenTypes.BAD_CHARACTER },
                "Expected BAD_CHARACTER for unclosed large delimiter body, got $tokens"
            )
            val firstBad = tokens.indexOfFirst { it.type == LuaTokenTypes.BAD_CHARACTER }
            assertTrue(
                tokens.drop(firstBad + 1).isEmpty(),
                "Unclosed large delimiter body must not invent tokens after BAD_CHARACTER: $tokens"
            )
        }.onFailure { failure ->
            assertTrue(
                failure is IllegalStateException || failure is IllegalArgumentException,
                "Expected lexer exception for unclosed large delimiter body, got ${failure::class.simpleName}: ${failure.message}"
            )
        }
    }

    @Test
    fun partialCloseNoiseInsideMatchingDelimitersDoesNotTruncateEarly() {
        assertTokenCases(
            LexerCase(
                "level-0 ignores lone ] until full ]]",
                "[[keep ] still]]",
                token(LuaTokenTypes.LONG_STRING, "[[keep ] still]]")
            ),
            LexerCase(
                "level-1 ignores ]= without final ]",
                "[=[keep ]= still]=]",
                token(LuaTokenTypes.LONG_STRING, "[=[keep ]= still]=]")
            ),
            LexerCase(
                "level-2 ignores ]== and ]= fragments",
                "[==[keep ]== ]= ] end]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[keep ]== ]= ] end]==]")
            ),
            LexerCase(
                "level-6 ignores level-5 partial/full lower closes",
                "[======[keep ]===== ]====] ]===] ]==] ]=] ]] end]======]",
                token(
                    LuaTokenTypes.LONG_STRING,
                    "[======[keep ]===== ]====] ]===] ]==] ]=] ]] end]======]"
                )
            ),
            LexerCase(
                "level-6 ignores exact lower full close then ends at level-6",
                "[======[keep ]=====] end]======]",
                token(LuaTokenTypes.LONG_STRING, "[======[keep ]=====] end]======]")
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
