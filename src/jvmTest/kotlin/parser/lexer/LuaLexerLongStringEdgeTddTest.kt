package parser.lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Long-string edge-shape corpus for Lua 5.3 long brackets (`[[...]]`, `[=[...]=]`, …).
 *
 * Acceptance (TASK-357):
 * - Edge shapes around open/close, first-line newlines, CR/LF mixes, and adjacency
 *   lex as stable LONG_STRING tokens without hang.
 * - EOF unclosed long strings recover deterministically (BAD_CHARACTER / exception).
 * - Test-only; production lexer is not modified by this task.
 *
 * Complements [LuaLexerLongBracketCorpusTddTest] / [LuaLexerLongStringNestedEqualsTddTest]
 * / [LuaLexerLongCommentEdgeTddTest] with a string-focused edge inventory:
 * leading newline after open, CR/LF/CRLF bodies, quote/escape-looking payloads,
 * partial close noise, incomplete openers, operator/call/table adjacency, and
 * large unclosed bodies that must finish without hang.
 */
class LuaLexerLongStringEdgeTddTest {

    @Test
    fun leadingNewlineAndEmptyEdgeShapesStaySingleLongStringTokens() {
        assertTokenCases(
            LexerCase(
                "level-0 empty",
                "[[]]",
                token(LuaTokenTypes.LONG_STRING, "[[]]")
            ),
            LexerCase(
                "level-0 body is only LF after open",
                "[[\n]]",
                token(LuaTokenTypes.LONG_STRING, "[[\n]]")
            ),
            LexerCase(
                "level-0 body is only CRLF after open",
                "[[\r\n]]",
                token(LuaTokenTypes.LONG_STRING, "[[\r\n]]")
            ),
            LexerCase(
                "level-0 body is only CR after open",
                "[[\r]]",
                token(LuaTokenTypes.LONG_STRING, "[[\r]]")
            ),
            LexerCase(
                "level-1 empty and leading LF remain single tokens",
                "[=[]=][=[\n]=]",
                token(LuaTokenTypes.LONG_STRING, "[=[]=]"),
                token(LuaTokenTypes.LONG_STRING, "[=[\n]=]")
            ),
            LexerCase(
                "level-2 empty forms chained",
                "[==[]==][===[]===]",
                token(LuaTokenTypes.LONG_STRING, "[==[]==]"),
                token(LuaTokenTypes.LONG_STRING, "[===[]===]")
            )
        )
    }

    @Test
    fun crLfAndMixedNewlineBodiesPreserveRawTokenText() {
        assertTokenCases(
            LexerCase(
                "LF-only multiline body",
                "[[a\nb\nc]]",
                token(LuaTokenTypes.LONG_STRING, "[[a\nb\nc]]")
            ),
            LexerCase(
                "CRLF multiline body",
                "[[a\r\nb\r\nc]]",
                token(LuaTokenTypes.LONG_STRING, "[[a\r\nb\r\nc]]")
            ),
            LexerCase(
                "mixed CR and LF in body",
                "[[a\rb\nc\r\nd]]",
                token(LuaTokenTypes.LONG_STRING, "[[a\rb\nc\r\nd]]")
            ),
            LexerCase(
                "level-1 leading LF then body then name boundary",
                "[=[\nline]=]x",
                token(LuaTokenTypes.LONG_STRING, "[=[\nline]=]"),
                token(LuaTokenTypes.NAME, "x")
            ),
            LexerCase(
                "level-2 CRLF body then concat",
                "[==[\r\npayload]==]..z",
                token(LuaTokenTypes.LONG_STRING, "[==[\r\npayload]==]"),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.NAME, "z")
            )
        )
    }

    @Test
    fun quoteEscapeAndCommentLookingPayloadsStayLiteralInsideLongStrings() {
        assertTokenCases(
            LexerCase(
                "short double quotes inside long string",
                "[[ \"hello\" ]]",
                token(LuaTokenTypes.LONG_STRING, "[[ \"hello\" ]]")
            ),
            LexerCase(
                "short single quotes and escapes lookalike",
                "[[ 'a\\nb' \\z \\x41 ]]",
                token(LuaTokenTypes.LONG_STRING, "[[ 'a\\nb' \\z \\x41 ]]")
            ),
            LexerCase(
                "comment-looking and short-comment text is literal until first level-0 close",
                "[[-- not a comment\n--[[ still string]]]]",
                // Level-0 closes at the first `]]` (after "string"); remaining `]]` is two RBRACKs.
                token(LuaTokenTypes.LONG_STRING, "[[-- not a comment\n--[[ still string]]"),
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.RBRACK, "]")

            ),
            LexerCase(
                "level-1 keeps comment-looking payload including embedded level-0 close",
                "[=[-- not a comment\n--[[ still string]] still]=]",
                token(
                    LuaTokenTypes.LONG_STRING,
                    "[=[-- not a comment\n--[[ still string]] still]=]"
                )
            ),
            LexerCase(
                "level-1 keeps embedded level-0 open/close and quotes",
                "[=[outer [[ \"inner\" ]] still]=]",
                token(LuaTokenTypes.LONG_STRING, "[=[outer [[ \"inner\" ]] still]=]")
            ),
            LexerCase(
                "level-2 keeps lower closes plus escape-looking noise",
                "[==[\\u{1F600} ]=] ]] end]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[\\u{1F600} ]=] ]] end]==]")
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
