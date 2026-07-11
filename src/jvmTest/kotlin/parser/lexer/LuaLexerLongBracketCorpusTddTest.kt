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

    @Test
    fun ignoresInnerLowerLevelClosesInsideHigherLevelLongStrings() {
        assertTokenCases(
            LexerCase(
                "level-1 string keeps embedded level-0 close",
                "[=[keep ]] inside]=]",
                token(LuaTokenTypes.LONG_STRING, "[=[keep ]] inside]=]")
            ),
            LexerCase(
                "level-2 string keeps embedded level-1 close",
                "[==[outer ]=] still]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[outer ]=] still]==]")
            ),
            LexerCase(
                "level-2 string keeps embedded openers and closes of lower levels",
                "[==[outer [=[inner]=] outer]==]",
                token(LuaTokenTypes.LONG_STRING, "[==[outer [=[inner]=] outer]==]")
            ),
            LexerCase(
                "level-0 string ends at first level-0 close even if equals later",
                "[[early]]]=]",
                token(LuaTokenTypes.LONG_STRING, "[[early]]"),
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.RBRACK, "]")
            )
        )
    }

    @Test
    fun ignoresInnerLowerLevelClosesInsideHigherLevelBlockComments() {
        assertTokenCases(
            LexerCase(
                "level-1 comment keeps embedded level-0 close",
                "--[=[keep ]] inside]=]x",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[=[keep ]] inside]=]"),
                token(LuaTokenTypes.NAME, "x")
            ),
            LexerCase(
                "level-2 comment keeps embedded level-1 close",
                "--[==[outer ]=] still]==]y",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[==[outer ]=] still]==]"),
                token(LuaTokenTypes.NAME, "y")
            ),
            LexerCase(
                "level-1 comment with nested same-level open when outer close exists",
                "--[=[outer [=[ // nested ]=] /]=]z",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[=[outer [=[ // nested ]=] /]=]"),
                token(LuaTokenTypes.NAME, "z")
            )
        )
    }

    @Test
    fun reportsLevelMismatchLongStringsAsMalformed() {
        assertMalformedCases(
            MalformedCase("open level-1 closed by level-2", "[=[mismatch]==]"),
            MalformedCase("open level-2 closed by level-1", "[==[mismatch]=]"),
            MalformedCase("open level-0 closed only by level-1", "[[mismatch]=]"),
            MalformedCase("open level-3 closed by level-1", "[===[mismatch]=]"),
            MalformedCase("open level-1 closed by level-0 only", "[=[mismatch]]"),
            MalformedCase(
                "level mismatch with trailing code is still one bad span",
                "[=[mismatch]==] local x=1"
            )
        )
    }

    @Test
    fun reportsLevelMismatchBlockCommentsAsMalformed() {
        assertMalformedCases(
            MalformedCase("comment open level-1 closed by level-2", "--[=[mismatch]==]"),
            MalformedCase("comment open level-2 closed by level-1", "--[==[mismatch]=]"),
            MalformedCase("comment open level-0 closed only by level-1", "--[[mismatch]=]"),
            MalformedCase(
                "comment level mismatch with trailing code is still one bad span",
                "--[=[mismatch]==] return 1"
            )
        )
    }

    @Test
    fun reportsEofUnclosedLongStringsAndCommentsAsMalformed() {
        assertMalformedCases(
            MalformedCase("unclosed empty level-0 open", "[["),
            MalformedCase("unclosed empty level-1 open", "[=["),
            MalformedCase("unclosed empty level-2 open", "[==["),
            MalformedCase("unclosed level-0 body", "[[unterminated"),
            MalformedCase("unclosed level-1 body", "[=[unterminated"),
            MalformedCase("unclosed level-2 multiline body", "[==[line1\nline2"),
            MalformedCase("unclosed block comment level-0", "--[["),
            MalformedCase("unclosed block comment level-1", "--[=["),
            MalformedCase("unclosed block comment with body", "--[=[unterminated body"),
            MalformedCase("unclosed block comment multiline", "--[==[a\nb\nc")
        )
    }

    @Test
    fun incompleteLongOpenersDoNotConsumeAsLongBrackets() {
        assertTokenCases(
            LexerCase(
                "lone left bracket is LBRACK",
                "[",
                token(LuaTokenTypes.LBRACK, "[")
            ),
            LexerCase(
                "bracket equals without second bracket is LBRACK then ASSIGN",
                "[=",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.ASSIGN, "=")
            ),
            LexerCase(
                "bracket double-equals without second bracket splits operators",
                "[==",
                token(LuaTokenTypes.LBRACK, "["),
                token(LuaTokenTypes.EQ, "==")
            ),
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
                "right close fragments outside long brackets stay operators",
                "]=]",
                token(LuaTokenTypes.RBRACK, "]"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.RBRACK, "]")
            )
        )
    }

    @Test
    fun preservesMultilineBodiesAndBoundaryTokensForLongBrackets() {
        assertTokenCases(
            LexerCase(
                "multiline long string body",
                "[[alpha\nbeta]]",
                token(LuaTokenTypes.LONG_STRING, "[[alpha\nbeta]]")
            ),
            LexerCase(
                "multiline equals long string then name",
                "[=[line1\nline2]=]z",
                token(LuaTokenTypes.LONG_STRING, "[=[line1\nline2]=]"),
                token(LuaTokenTypes.NAME, "z")
            ),
            LexerCase(
                "multiline block comment then operator",
                "--[[a\nb]]+",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[a\nb]]"),
                token(LuaTokenTypes.PLUS, "+")
            ),
            LexerCase(
                "long string containing comment-like text",
                "[[-- not a comment]]",
                token(LuaTokenTypes.LONG_STRING, "[[-- not a comment]]")
            ),
            LexerCase(
                "block comment containing string-like text",
                "--[[ \"not\" 'a' [[string]] ]]x",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[[ \"not\" 'a' [[string]] ]]"),
                token(LuaTokenTypes.NAME, "x")
            )
        )
    }

    @Test
    fun eofUnclosedFormsConsumeRestWithoutInventingFollowingTokens() {
        assertEofMalformedConsumesAll(
            "unclosed long string consumes remainder",
            "[=[no close and more text"
        )
        assertEofMalformedConsumesAll(
            "unclosed block comment consumes remainder",
            "--[==[no close\nstill going"
        )
        assertEofMalformedConsumesAll(
            "level mismatch long string with trailing tokens consumes remainder",
            "[=[x]==] print(1)"
        )
        assertEofMalformedConsumesAll(
            "level mismatch block comment with trailing tokens consumes remainder",
            "--[=[x]==] local a"
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
