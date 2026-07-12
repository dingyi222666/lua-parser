package lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Refined long-string edge corpus for Lua 5.3 long brackets.
 *
 * Acceptance (TASK-411):
 * - Test-only refine of historical long-string red suites (nested equals, EOF,
 *   CR/LF) without rewriting sibling corpora under parser.lexer.
 * - Nested-equals levels, EOF mid-close / unclosed recovery, and CR/LF mixes
 *   lock stable LONG_STRING / BAD_CHARACTER surfaces without hang.
 * - TASK-633 remainder-consume: when no matching same-level close exists,
 *   BAD_CHARACTER spans through EOF (wrong-level closes and trailing source
 *   stay inside the bad span). True EOF-unclosed forms are the same contract.
 * - Verification deferred to review / TASK-043.
 *
 * Complements:
 * - [parser.lexer.LuaLexerLongStringEdgeTddTest]
 * - [parser.lexer.LuaLexerLongStringNestedEqualsTddTest]
 * - [parser.lexer.LuaLexerLongBracketCorpusTddTest]
 */
class LuaLexerLongStringEdgeRefineTddTest {

    @Test
    fun nestedEqualsWithCrLfBodiesStaySingleLongStringTokens() {
        assertTokenCases(
            LexerCase(
                "level-1 LF body keeps embedded level-0 close",
                "[=[\nkeep ]] inside\n]=]",
                token(LuaTokenTypes.LONG_STRING, "[=[\nkeep ]] inside\n]=]")
            ),
            LexerCase(
                "level-2 CRLF body keeps embedded level-0/1 fragments",
                "[==[\r\nouter ]=] [[x]] still\r\n]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[\r\nouter ]=] [[x]] still\r\n]==]")
            ),
            LexerCase(
                "level-3 CR-only body keeps lower closes",
                "[===[\rL3 ]=] ]==] ]] end\r]===]",
                token(LuaTokenTypes.LONG_STRING, "[===[\rL3 ]=] ]==] ]] end\r]===]")
            ),
            LexerCase(
                "level-4 mixed CR/LF/CRLF with nested lower open/close",
                "[====[L4\r\n[===[a]===]\rb\n[==[c]==] end]====]",
                token(
                    LuaTokenTypes.LONG_STRING,
                    "[====[L4\r\n[===[a]===]\rb\n[==[c]==] end]====]"
                )
            ),
            LexerCase(
                "level-5 nested-equals refine beyond historical 0..4 inventory",
                "[=====[L5 ]====] ]===] ]==] ]=] ]] still]=====]",
                token(
                    LuaTokenTypes.LONG_STRING,
                    "[=====[L5 ]====] ]===] ]==] ]=] ]] still]=====]"
                )
            ),
            LexerCase(
                "level-1 then level-2 adjacency after CRLF separator",
                "[=[a]=]\r\n[==[b]==]",
                token(LuaTokenTypes.LONG_STRING, "[=[a]=]"),
                token(LuaTokenTypes.LONG_STRING, "[==[b]==]")
            )
        )
    }

    @Test
    fun nestedEqualsCrLfBoundariesPreserveFollowingTokens() {
        assertTokenCases(
            LexerCase(
                "level-1 CRLF body then name",
                "[=[\r\nline]=]name",
                token(LuaTokenTypes.LONG_STRING, "[=[\r\nline]=]"),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "level-2 mixed newlines then concat",
                "[==[x\r]=]\ny]==]..z",
                token(LuaTokenTypes.LONG_STRING, "[==[x\r]=]\ny]==]"),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.NAME, "z")
            ),
            LexerCase(
                "level-3 leading LF then lower closes then return",
                "[===[\n]=] still]===]return",
                token(LuaTokenTypes.LONG_STRING, "[===[\n]=] still]===]"),
                token(LuaTokenTypes.RETURN, "return")
            ),
            LexerCase(
                "level-4 assignment RHS with CR body then number",
                "s=[====[\rpayload ]===] end]====]1",
                token(LuaTokenTypes.NAME, "s"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LONG_STRING, "[====[\rpayload ]===] end]====]"),
                token(LuaTokenTypes.NUMBER, "1")
            ),
            LexerCase(
                "print string-call level-2 with embedded CRLF and lower close",
                "print[==[hello\r\n]=] world]==]",
                token(LuaTokenTypes.NAME, "print"),
                token(LuaTokenTypes.LONG_STRING, "[==[hello\r\n]=] world]==]")
            ),
            LexerCase(
                "level-0 first-close still wins after CR then higher close noise",
                "[[early\r]]]====]",
                token(LuaTokenTypes.LONG_STRING, "[[early\r]]"),
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.EQ, "=="),
                token(LuaTokenTypes.RBRACK, "]")
            )
        )
    }

    @Test
    fun eofMidCloseAndPartialCloseSequencesRecoverWithoutHang() {
        // EOF while a close sequence is incomplete must not hang and must not
        // invent following significant tokens when recovered as BAD_CHARACTER.
        assertEofMalformedConsumesAll("EOF after open only level-0", "[[")
        assertEofMalformedConsumesAll("EOF after open only level-2", "[==[")
        assertEofMalformedConsumesAll("EOF after open only level-5", "[=====[")

        assertEofMalformedConsumesAll("EOF mid level-0 close (single ])", "[[body]")
        assertEofMalformedConsumesAll("EOF mid level-1 close (]= missing final])", "[=[body]=")
        assertEofMalformedConsumesAll("EOF mid level-2 close (]== missing final])", "[==[body]==")
        assertEofMalformedConsumesAll("EOF mid level-3 close (]=== missing final])", "[===[body]===")
        assertEofMalformedConsumesAll("EOF mid level-4 close after CR body", "[====[\rbody]===")
        // Incomplete same-level close fragments (no final `]`) are not full closes: BAD to EOF.
        assertEofMalformedConsumesAll("EOF mid level-5 close (]===== missing final])", "[=====[body]=====")
        // Complete lower-level closes inside an unclosed body are not matching
        // terminators: BAD_CHARACTER still consumes remainder to EOF (TASK-633).
        assertEofMalformedConsumesAll(
            "EOF mid level-2 close after nested lower noise consumes remainder",
            "[==[keep ]=] ]] still]=="
        )
        assertEofMalformedConsumesAll(
            "EOF unclosed level-3 with CRLF and lower closes consumes remainder",
            "[===[\r\nx ]=] ]==] still open"
        )
        assertEofMalformedConsumesAll(
            "EOF unclosed level-5 with embedded lower opens/closes consumes remainder",
            "[=====[L5 [====[a]====] [===[b]===] open"
        )
    }

    @Test
    fun levelMismatchWithCrLfRecoverWithoutHang() {
        assertMalformedCases(
            MalformedCase("level-1 open closed by level-2 after LF", "[=[\nx]==]"),
            MalformedCase("level-2 open closed by level-1 after CR", "[==[\rx]=]"),
            MalformedCase("level-3 open closed by level-2 after CRLF", "[===[\r\nx]==]"),
            MalformedCase("level-4 open closed by level-0 only after LF", "[====[\nx]]"),
            MalformedCase("level-5 open closed by level-4", "[=====[x]====]"),
            MalformedCase(
                "level mismatch with trailing code after CR body",
                "[=[\rmismatch]==] local x=1"
            ),
            MalformedCase(
                "level-2 mismatch after mixed newlines",
                "[==[\r\na\nb]=]"
            )
        )
        // TASK-633: wrong-level close never matches; BAD_CHARACTER consumes remainder.
        assertEofMalformedConsumesAll(
            "level-5 mismatch with trailing tokens consumes remainder",
            "[=====[x]====] print(1)"
        )
        assertEofMalformedConsumesAll(
            "level-3 mismatch after CRLF with trailing name consumes remainder",
            "[===[\r\nmismatch]==]name"
        )
        assertEofMalformedConsumesAll(
            "level mismatch with trailing code after CR body consumes remainder",
            "[=[\rmismatch]==] local x=1"
        )
    }

    @Test
    fun leadingNewlineVariantsAtNestedLevelsStayLongStringOrRecover() {
        assertTokenCases(
            LexerCase(
                "level-0 leading LF empty body",
                "[[\n]]",
                token(LuaTokenTypes.LONG_STRING, "[[\n]]")
            ),
            LexerCase(
                "level-0 leading CR empty body",
                "[[\r]]",
                token(LuaTokenTypes.LONG_STRING, "[[\r]]")
            ),
            LexerCase(
                "level-0 leading CRLF empty body",
                "[[\r\n]]",
                token(LuaTokenTypes.LONG_STRING, "[[\r\n]]")
            ),
            LexerCase(
                "level-1 leading CRLF then lower close noise then close",
                "[=[\r\n]] still]=]",
                token(LuaTokenTypes.LONG_STRING, "[=[\r\n]] still]=]")
            ),
            LexerCase(
                "level-2 leading CR then embedded openers",
                "[==[\r[[inner]] end]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[\r[[inner]] end]==]")
            ),
            LexerCase(
                "level-3 empty leading LF then adjacent name",
                "[===[\n]===]x",
                token(LuaTokenTypes.LONG_STRING, "[===[\n]===]"),
                token(LuaTokenTypes.NAME, "x")
            )
        )
        assertEofMalformedConsumesAll(
            "unclosed level-1 after leading CRLF only",
            "[=[\r\n"
        )
        // Complete lower-level closes inside unclosed body: BAD still to EOF (TASK-633).
        assertEofMalformedConsumesAll(
            "unclosed level-4 after leading LF and lower close noise consumes remainder",
            "[====[\n]=] ]==] ]]"
        )
    }

    @Test
    fun unclosedNestedEqualsLargeCrLfBodyDoesNotHang() {
        // True unclosed form with incomplete lower-level noise (no full close):
        // BAD_CHARACTER spans to EOF. Hang-freedom is the primary lock.
        val body = buildString {
            append("[=====[")
            repeat(6_000) { i ->
                append("refine-")
                append(i)
                // incomplete fragments only — missing final `]` so not a full close
                append(" ]==== ]=== ]== ]= ] \r\n")
            }
            // deliberately no matching ]=====]
        }
        val started = System.nanoTime()
        val result = runCatching { significantTokens(body) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue(
            elapsedMs < 5_000L,
            "Unclosed level-5 large CR/LF refine body must finish without hang; elapsedMs=$elapsedMs"
        )
        result.onSuccess { tokens ->
            assertTrue(
                tokens.any { it.type == LuaTokenTypes.BAD_CHARACTER },
                "Expected BAD_CHARACTER for unclosed large refine body, got $tokens"
            )
            val firstBad = tokens.indexOfFirst { it.type == LuaTokenTypes.BAD_CHARACTER }
            assertTrue(
                tokens.drop(firstBad + 1).isEmpty(),
                "Unclosed large refine body must not invent tokens after BAD_CHARACTER: $tokens"
            )
        }.onFailure { failure ->
            assertTrue(
                failure is IllegalStateException || failure is IllegalArgumentException,
                "Expected lexer exception for unclosed large refine body, got ${failure::class.simpleName}: ${failure.message}"
            )
        }
    }

    @Test
    fun partialCloseNoiseAcrossCrLfDoesNotTruncateEarly() {
        assertTokenCases(
            LexerCase(
                "level-1 ignores ]= without final ] across LF",
                "[=[keep ]=\nstill]=]",
                token(LuaTokenTypes.LONG_STRING, "[=[keep ]=\nstill]=]")
            ),
            LexerCase(
                "level-2 ignores ]== and ]= fragments across CRLF",
                "[==[keep ]==\r\n]= ] end]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[keep ]==\r\n]= ] end]==]")
            ),
            LexerCase(
                "level-3 ignores lower/partial closes across CR",
                "[===[keep ]===\r]==] ]=] ]] end]===]",
                token(LuaTokenTypes.LONG_STRING, "[===[keep ]===\r]==] ]=] ]] end]===]")
            ),
            LexerCase(
                "level-4 ignores level-3 close across mixed newlines",
                "[====[keep\n]===]\rend]====]",
                token(LuaTokenTypes.LONG_STRING, "[====[keep\n]===]\rend]====]")
            ),
            LexerCase(
                "level-5 ignores level-4 close then ends at level-5",
                "[=====[keep ]====] end]=====]",
                token(LuaTokenTypes.LONG_STRING, "[=====[keep ]====] end]=====]")
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
