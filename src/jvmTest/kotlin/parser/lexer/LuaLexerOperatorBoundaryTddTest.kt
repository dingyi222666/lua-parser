package parser.lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.fail

class LuaLexerOperatorBoundaryTddTest {

    @Test
    fun tokenizesSlashOperatorsAtEofBoundaries() {
        assertTokenCases(
            LexerCase("lone slash at eof", "/", token(LuaTokenTypes.DIV, "/")),
            LexerCase("double slash at eof", "//", token(LuaTokenTypes.DOUBLE_DIV, "//")),
            LexerCase("slash assign at eof", "/=", token(LuaTokenTypes.DIV_ASSIGN, "/=")),
            LexerCase("double slash assign at eof", "//=", token(LuaTokenTypes.DOUBLE_DIV_ASSIGN, "//="))
        )
    }

    @Test
    fun keepsSlashLikeTextInsideLiteralsAndComments() {
        assertTokenCases(
            LexerCase("string keeps slash text", "\"http://host/path/\"", token(LuaTokenTypes.STRING, "\"http://host/path/\"")),
            LexerCase("short comment keeps slash text", "-- // /= /\n/", token(LuaTokenTypes.SHORT_COMMENT, "-- // /= /"), token(LuaTokenTypes.DIV, "/")),
            LexerCase("block comment keeps slash text", "--[[// /= /]]/", token(LuaTokenTypes.BLOCK_COMMENT, "--[[// /= /]]"), token(LuaTokenTypes.DIV, "/")),
            LexerCase(
                "equals block comment keeps nested slash text",
                "--[=[outer [=[ // /= / ]=] /]=]/",
                token(LuaTokenTypes.BLOCK_COMMENT, "--[=[outer [=[ // /= / ]=] /]=]"),
                token(LuaTokenTypes.DIV, "/")
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

    private data class TokenSnapshot(
        val type: LuaTokenTypes,
        val text: String
    )

    private companion object {
        fun token(type: LuaTokenTypes, text: String): TokenSnapshot = TokenSnapshot(type, text)
    }
}
