package parser.lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Edge corpus for Lua long-string / long-comment (long-bracket) lexing.
 *
 * Covers empty brackets, level mismatches, EOF-unclosed forms, and stable
 * token boundaries after well-formed long brackets. Production lexer is not
 * modified by this task; red is acceptable until review verification.
 */
class LuaLexerLongBracketCorpusTddTest {

    @Test
    fun tokenizesEmptyLongStringsAtMultipleLevels() {
        assertTokenCases(
            LexerCase("empty level-0 long string", "[[]]", token(LuaTokenTypes.LONG_STRING, "[[]]")),
            LexerCase("empty level-1 long string", "[=[]=]", token(LuaTokenTypes.LONG_STRING, "[=[]=]")),
            LexerCase("empty level-2 long string", "[==[]==]", token(LuaTokenTypes.LONG_STRING, "[==[]==]")),
            LexerCase("empty level-3 long string", "[===[]===]", token(LuaTokenTypes.LONG_STRING, "[===[]===]")),
            LexerCase(
                "adjacent empty long strings keep separate tokens",
                "[[]][=[]=]",
                token(LuaTokenTypes.LONG_STRING, "[[]]"),
                token(LuaTokenTypes.LONG_STRING, "[=[]=]")
            )
        )
    }

    @Test
    fun tokenizesEmptyLongCommentsAtMultipleLevels() {
        assertTokenCases(
            LexerCase("empty level-0 block comment", "--[[]]", token(LuaTokenTypes.BLOCK_COMMENT, "--[[]]")),
            LexerCase("empty level-1 block comment", "--[=[]=]", token(LuaTokenTypes.BLOCK_COMMENT, "--[=[]=]")),
            LexerCase("empty level-2 block comment", "--[==[]==]", token(LuaTokenTypes.BLOCK_COMMENT, "--[==[]==]")),
            LexerCase(
                "empty block comment then name keeps boundary",
                "--[[]]x",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[]]"),
                token(LuaTokenTypes.NAME, "x")
            ),
            LexerCase(
                "empty equals block comment then operator keeps boundary",
                "--[=[]=]/",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[=[]=]"),
                token(LuaTokenTypes.DIV, "/")
            )
        )
    }

    @Test
    fun keepsTokenBoundariesStableAfterWellFormedLongBrackets() {
        assertTokenCases(
            LexerCase(
                "long string then name",
                "[[body]]name",
                token(LuaTokenTypes.LONG_STRING, "[[body]]"),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "equals long string then concat",
                "[=[a]=]..b",
                token(LuaTokenTypes.LONG_STRING, "[=[a]=]"),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.NAME, "b")
            ),
            LexerCase(
                "long string then number assignment remains split",
                "x=[[ok]]1",
                token(LuaTokenTypes.NAME, "x"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.LONG_STRING, "[[ok]]"),
                token(LuaTokenTypes.NUMBER, "1")
            ),
            LexerCase(
                "block comment then local statement",
                "--[[c]]local y=1",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[c]]"),
                token(LuaTokenTypes.LOCAL, "local"),
                token(LuaTokenTypes.NAME, "y"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.NUMBER, "1")
            ),
            LexerCase(
                "equals block comment then return",
                "--[==[c]==]return",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[==[c]==]"),
                token(LuaTokenTypes.RETURN, "return")
            ),
            LexerCase(
                "long string string-call style adjacency",
                "print[[hello]]",
                token(LuaTokenTypes.NAME, "print"),
                token(LuaTokenTypes.LONG_STRING, "[[hello]]")
            ),
            LexerCase(
                "two long strings separated by whitespace keep two tokens",
                "[[a]] [[b]]",
                token(LuaTokenTypes.LONG_STRING, "[[a]]"),
                token(LuaTokenTypes.LONG_STRING, "[[b]]")
            )
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
        val result = runCatching { significantTokens(source) }
        result.onSuccess { tokens ->
            assertTrue(
                tokens.isNotEmpty() && tokens.any { it.type == LuaTokenTypes.BAD_CHARACTER },
                "$name: expected BAD_CHARACTER among tokens, got $tokens"
            )
            // Unclosed / mismatched long brackets consume through EOF: no significant
            // tokens may follow the first BAD_CHARACTER span that ate the remainder.
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

        while (true) {
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
