package parser.lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Edge corpus for Lua 5.3 UTF-8 / unicode string escapes (`\u{...}`).
 *
 * Acceptance (TASK-322):
 * - `\u{...}` and invalid UTF-8 escape edges tokenize deterministically.
 * - Does not claim runtime decode beyond the lexer contract (raw lexeme text only).
 * - Test-only; production lexer is not modified by this task.
 *
 * Complements the broader [LuaLexerUtf8NumericCorpusTddTest] with a focused
 * escape-only inventory: valid brace forms, mixed short-string contexts, and
 * invalid/open/empty/non-hex recovery token boundaries.
 */
class LuaLexerUtf8EscapeTddTest {

    @Test
    fun tokenizesValidUnicodeUtf8EscapesAsSingleStringTokens() {
        assertTokenCases(
            LexerCase(
                "minimal unicode escape A",
                "\"\\u{41}\"",
                token(LuaTokenTypes.STRING, "\"\\u{41}\"")
            ),
            LexerCase(
                "unicode escape lowercase multi-digit",
                "\"\\u{1f4a9}\"",
                token(LuaTokenTypes.STRING, "\"\\u{1f4a9}\"")
            ),
            LexerCase(
                "unicode escape uppercase hex",
                "\"\\u{1F600}\"",
                token(LuaTokenTypes.STRING, "\"\\u{1F600}\"")
            ),
            LexerCase(
                "unicode escape zero",
                "\"\\u{0}\"",
                token(LuaTokenTypes.STRING, "\"\\u{0}\"")
            ),
            LexerCase(
                "unicode escape max code point text kept raw",
                "\"\\u{10FFFF}\"",
                token(LuaTokenTypes.STRING, "\"\\u{10FFFF}\"")
            ),
            LexerCase(
                "unicode escape inside single-quoted string",
                "'\\u{61}'",
                token(LuaTokenTypes.STRING, "'\\u{61}'")
            ),
            LexerCase(
                "unicode escape between plain text",
                "\"A\\u{42}C\"",
                token(LuaTokenTypes.STRING, "\"A\\u{42}C\"")
            ),
            LexerCase(
                "adjacent unicode escapes stay one string",
                "\"\\u{41}\\u{42}\"",
                token(LuaTokenTypes.STRING, "\"\\u{41}\\u{42}\"")
            ),
            LexerCase(
                "leading zero padding kept raw",
                "\"\\u{00000041}\"",
                token(LuaTokenTypes.STRING, "\"\\u{00000041}\"")
            ),
            LexerCase(
                "mixed unicode hex and decimal escapes stay one string",
                "\"\\u{41}\\x42\\066\"",
                token(LuaTokenTypes.STRING, "\"\\u{41}\\x42\\066\"")
            )
        )
    }

    @Test
    fun invalidUnicodeEscapeEdgesTokenizeDeterministically() {
        // Invalid escapes stop the string as BAD_CHARACTER at the escape span;
        // remaining source is re-tokenized (recovery). Assert full token stream.
        assertTokenCases(
            LexerCase(
                "unicode escape missing opening brace",
                "\"\\u41\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u"),
                token(LuaTokenTypes.NUMBER, "41"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "unicode escape empty braces",
                "\"\\u{}\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u{"),
                token(LuaTokenTypes.RCURLY, "}"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "unicode escape missing closing brace",
                "\"\\u{41\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u{41"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "unicode escape only u before closing quote",
                "\"\\u\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "unicode escape open brace at eof",
                "\"\\u{",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u{")
            ),
            LexerCase(
                "unicode escape non-hex inside braces",
                "\"\\u{G1}\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u{"),
                token(LuaTokenTypes.NAME, "G1"),
                token(LuaTokenTypes.RCURLY, "}"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "unicode escape truncated after u at eof",
                "\"\\u",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u")
            ),
            LexerCase(
                "single-quoted unicode escape missing brace",
                "'\\u41'",
                token(LuaTokenTypes.BAD_CHARACTER, "'\\u"),
                token(LuaTokenTypes.NUMBER, "41"),
                token(LuaTokenTypes.BAD_CHARACTER, "'")
            )
        )
    }

    @Test
    fun invalidUnicodeEscapeDoesNotClaimRuntimeDecodeBeyondLexerContract() {
        // Lexer contract: preserve raw escape text in STRING / BAD_CHARACTER tokens.
        // Do not assert decoded code points or runtime string values.
        assertTokenCases(
            LexerCase(
                "valid escape keeps raw escape text not decoded glyph",
                "\"\\u{41}\"",
                token(LuaTokenTypes.STRING, "\"\\u{41}\"")
            ),
            LexerCase(
                "invalid escape keeps raw bad span text",
                "\"\\u{ZZ}\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u{"),
                token(LuaTokenTypes.NAME, "ZZ"),
                token(LuaTokenTypes.RCURLY, "}"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            )
        )

        val tokens = significantTokens("\"\\u{41}\"")
        assertEqualsSingleStringWithRawEscape(tokens, "\"\\u{41}\"")
    }

    @Test
    fun unicodeEscapeBoundariesKeepFollowingSignificantTokens() {
        assertTokenCases(
            LexerCase(
                "string with unicode escape then name",
                "\"\\u{41}\"name",
                token(LuaTokenTypes.STRING, "\"\\u{41}\""),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "assignment rhs unicode escape keeps operator boundary",
                "x=\"\\u{61}\"",
                token(LuaTokenTypes.NAME, "x"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.STRING, "\"\\u{61}\"")
            ),
            LexerCase(
                "concat after unicode escape string",
                "\"\\u{41}\"..y",
                token(LuaTokenTypes.STRING, "\"\\u{41}\""),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.NAME, "y")
            ),
            LexerCase(
                "invalid unicode escape then later name still re-tokenized",
                "\"\\u{41 name",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u{41"),
                token(LuaTokenTypes.NAME, "name")
            )
        )
    }

    @Test
    fun malformedUnicodeEscapeEitherBadCharacterOrLexerException() {
        assertMalformedCases(
            MalformedCase("empty braces", "\"\\u{}\""),
            MalformedCase("missing brace", "\"\\u41\""),
            MalformedCase("open brace eof", "\"\\u{"),
            MalformedCase("non hex", "\"\\u{G}\""),
            MalformedCase("only u", "\"\\u\"")
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun assertEqualsSingleStringWithRawEscape(
        tokens: List<TokenSnapshot>,
        expectedText: String
    ) {
        assertEquals(1, tokens.size, "expected single string token, got $tokens")
        assertEquals(LuaTokenTypes.STRING, tokens.single().type)
        assertEquals(expectedText, tokens.single().text)
        // Explicit non-claim: raw text still contains the escape sequence, not a decoded char alone.
        assertTrue(tokens.single().text.contains("\\u{"), tokens.single().text)
    }

    private fun assertEquals(expected: Int, actual: Int, message: String) {
        if (expected != actual) {
            fail("$message (expected=$expected actual=$actual)")
        }
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) {
            fail("expected=$expected actual=$actual")
        }
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
