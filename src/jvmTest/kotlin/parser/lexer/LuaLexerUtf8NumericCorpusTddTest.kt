package parser.lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Edge corpus for Lua 5.3 UTF-8/unicode string escapes, hex float numerics,
 * and invalid-escape recovery token boundaries.
 *
 * Asserts both token kinds and raw lexeme text. Production lexer is not
 * modified by this task; red is acceptable until review verification.
 */
class LuaLexerUtf8NumericCorpusTddTest {

    @Test
    fun tokenizesUnicodeUtf8EscapesInShortStrings() {
        assertTokenCases(
            LexerCase(
                "minimal unicode escape A",
                "\"\\u{41}\"",
                token(LuaTokenTypes.STRING, "\"\\u{41}\"")
            ),
            LexerCase(
                "unicode escape lowercase hex multi-digit",
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
                "mixed unicode hex and decimal escapes stay one string",
                "\"\\u{41}\\x42\\066\"",
                token(LuaTokenTypes.STRING, "\"\\u{41}\\x42\\066\"")
            ),
            LexerCase(
                "unicode escape between plain text",
                "\"A\\u{42}C\"",
                token(LuaTokenTypes.STRING, "\"A\\u{42}C\"")
            ),
            LexerCase(
                "adjacent unicode escapes",
                "\"\\u{41}\\u{42}\"",
                token(LuaTokenTypes.STRING, "\"\\u{41}\\u{42}\"")
            ),
            LexerCase(
                "long hex run inside braces",
                "\"\\u{00000041}\"",
                token(LuaTokenTypes.STRING, "\"\\u{00000041}\"")
            )
        )
    }

    @Test
    fun tokenizesHexFloatNumericBoundaries() {
        assertTokenCases(
            LexerCase(
                "hex float fraction with positive p exponent",
                "0x1.8p+2",
                token(LuaTokenTypes.NUMBER, "0x1.8p+2")
            ),
            LexerCase(
                "hex float fraction without integer part",
                "0x.8p-1",
                token(LuaTokenTypes.NUMBER, "0x.8p-1")
            ),
            LexerCase(
                "hex integer with binary exponent",
                "0x1p4",
                token(LuaTokenTypes.NUMBER, "0x1p4")
            ),
            LexerCase(
                "uppercase hex float with uppercase P",
                "0X.FFp-2",
                token(LuaTokenTypes.NUMBER, "0X.FFp-2")
            ),
            LexerCase(
                "hex zero with p0 exponent",
                "0x0p0",
                token(LuaTokenTypes.NUMBER, "0x0p0")
            ),
            LexerCase(
                "hex fraction only with plus exponent",
                "0x.1P+0",
                token(LuaTokenTypes.NUMBER, "0x.1P+0")
            ),
            LexerCase(
                "hex digits then binary exponent no fraction",
                "0xABp+10",
                token(LuaTokenTypes.NUMBER, "0xABp+10")
            ),
            LexerCase(
                "hex float with trailing integer part and fraction",
                "0x1.Fp10",
                token(LuaTokenTypes.NUMBER, "0x1.Fp10")
            ),
            LexerCase(
                "hex float then name keeps split boundary",
                "0x1p4name",
                token(LuaTokenTypes.NUMBER, "0x1p4"),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "hex float then concat keeps split boundary",
                "0x1.0p0..x",
                token(LuaTokenTypes.NUMBER, "0x1.0p0"),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.NAME, "x")
            ),
            LexerCase(
                "adjacent hex float and decimal number need separator whitespace",
                "0x1p0 2",
                token(LuaTokenTypes.NUMBER, "0x1p0"),
                token(LuaTokenTypes.NUMBER, "2")
            ),
            LexerCase(
                "hex float assignment keeps operator boundary",
                "x=0x.8p-1",
                token(LuaTokenTypes.NAME, "x"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.NUMBER, "0x.8p-1")
            ),
            LexerCase(
                "decimal float with signed exponent still NUMBER",
                "1.0e-10",
                token(LuaTokenTypes.NUMBER, "1.0e-10")
            ),
            LexerCase(
                "leading-dot decimal with positive exponent",
                ".5E+2",
                token(LuaTokenTypes.NUMBER, ".5E+2")
            )
        )
    }

    @Test
    fun reportsInvalidUnicodeAndHexEscapeAsBadWithLexemeText() {
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
                "hex escape incomplete single digit",
                "\"\\xA\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\x"),
                token(LuaTokenTypes.NAME, "A"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "hex escape non-hex second digit",
                "\"\\xAG\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\x"),
                token(LuaTokenTypes.NAME, "AG"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "hex escape non-hex first digit",
                "\"\\xG0\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\x"),
                token(LuaTokenTypes.NAME, "G0"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "unknown short escape letter",
                "\"\\q\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\q"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "backslash at end of short string content is incomplete escape",
                "\"\\",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\")
            )
        )
    }

    @Test
    fun recoversTokensAfterInvalidEscapeBadCharacter() {
        // On invalid escapes the lexer stops the string as BAD_CHARACTER at the
        // bad escape span; remaining source is re-tokenized (recovery).
        assertTokenCases(
            LexerCase(
                "invalid letter escape then plain name",
                "\"a\\qb",
                token(LuaTokenTypes.BAD_CHARACTER, "\"a\\q"),
                token(LuaTokenTypes.NAME, "b")
            ),
            LexerCase(
                "invalid letter escape then closing quote restarts string recovery",
                "\"a\\q\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"a\\q"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "invalid letter escape then statement after space",
                "\"\\q\" local x",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\q"),
                token(LuaTokenTypes.BAD_CHARACTER, "\" local x")
            ),
            LexerCase(
                "invalid unicode then trailing name after incomplete brace",
                "\"\\u{41 name",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u{41"),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "invalid hex escape then digits re-lexed",
                "\"\\xG0\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\x"),
                token(LuaTokenTypes.NAME, "G0"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "invalid escape mid expression keeps following operators as recovered stream",
                "x=\"\\q\"+1",
                token(LuaTokenTypes.NAME, "x"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\q"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"+1")
            ),
            LexerCase(
                "invalid escape then semicolon-separated statement when quote closes separately",
                "\"\\q\";y=1",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\q"),
                token(LuaTokenTypes.BAD_CHARACTER, "\";y=1")
            ),
            LexerCase(
                "bad escape in call argument recovers trailing source as bad string span",
                "print(\"\\q\")1",
                token(LuaTokenTypes.NAME, "print"),
                token(LuaTokenTypes.LPAREN, "("),
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\q"),
                token(LuaTokenTypes.BAD_CHARACTER, "\")1")
            ),
            LexerCase(
                "invalid unicode missing brace recovers following digits",
                "\"\\u41\" end",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u"),
                token(LuaTokenTypes.NUMBER, "41"),
                token(LuaTokenTypes.BAD_CHARACTER, "\" end")
            ),
            LexerCase(
                "invalid empty unicode braces recovers close brace and quote",
                "\"\\u{}\"+x",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u{"),
                token(LuaTokenTypes.RCURLY, "}"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"+x")
            )
        )
    }

    @Test
    fun reportsMalformedHexFloatAndExponentBoundaries() {
        assertTokenCases(
            LexerCase(
                "hex prefix without digits",
                "0x",
                token(LuaTokenTypes.BAD_CHARACTER, "0x")
            ),
            LexerCase(
                "hex prefix with only dot",
                "0x.",
                token(LuaTokenTypes.BAD_CHARACTER, "0x.")
            ),
            LexerCase(
                "hex digits then p without exponent digits",
                "0x1p",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1p")
            ),
            LexerCase(
                "hex digits then p with sign only",
                "0x1p+",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1p+")
            ),
            LexerCase(
                "hex digits then p with minus only",
                "0x1p-",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1p-")
            ),
            LexerCase(
                "hex float fraction then p without digits",
                "0x1.8p",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1.8p")
            ),
            LexerCase(
                "decimal exponent without digits",
                "1e",
                token(LuaTokenTypes.BAD_CHARACTER, "1e")
            ),
            LexerCase(
                "decimal exponent with sign only",
                "1E+",
                token(LuaTokenTypes.BAD_CHARACTER, "1E+")
            ),
            LexerCase(
                "leading-dot decimal with empty exponent",
                ".5e",
                token(LuaTokenTypes.BAD_CHARACTER, ".5e")
            ),
            LexerCase(
                "malformed hex float then name still recovers name",
                "0x1p name",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1p"),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "malformed hex prefix then identifier-like continuation is not glued as NAME",
                "0x; y",
                token(LuaTokenTypes.BAD_CHARACTER, "0x"),
                token(LuaTokenTypes.SEMI, ";"),
                token(LuaTokenTypes.NAME, "y")
            ),
            LexerCase(
                "malformed hex exponent sign recovers following number as separate token",
                "0x1p+ name",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1p+"),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "malformed decimal exponent recovers following operator",
                "1e+;",
                token(LuaTokenTypes.BAD_CHARACTER, "1e+"),
                token(LuaTokenTypes.SEMI, ";")
            )
        )
    }

    @Test
    fun keepsValidEscapesAndNumericsStableBesideOperators() {
        assertTokenCases(
            LexerCase(
                "unicode string concat keeps three tokens",
                "\"\\u{41}\"..\"\\u{42}\"",
                token(LuaTokenTypes.STRING, "\"\\u{41}\""),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.STRING, "\"\\u{42}\"")
            ),
            LexerCase(
                "hex float in table-like punctuation",
                "{0x1.0p0,0x.8p-1}",
                token(LuaTokenTypes.LCURLY, "{"),
                token(LuaTokenTypes.NUMBER, "0x1.0p0"),
                token(LuaTokenTypes.COMMA, ","),
                token(LuaTokenTypes.NUMBER, "0x.8p-1"),
                token(LuaTokenTypes.RCURLY, "}")
            ),
            LexerCase(
                "call with unicode string argument adjacency",
                "print\"\\u{48}\\u{69}\"",
                token(LuaTokenTypes.NAME, "print"),
                token(LuaTokenTypes.STRING, "\"\\u{48}\\u{69}\"")
            ),
            LexerCase(
                "hex escape valid two digits stays STRING",
                "\"\\x41\"",
                token(LuaTokenTypes.STRING, "\"\\x41\"")
            ),
            LexerCase(
                "decimal escape three digits stays STRING",
                "\"\\123\"",
                token(LuaTokenTypes.STRING, "\"\\123\"")
            ),
            LexerCase(
                "z escape with spaces stays STRING",
                "\"a\\z \tb\"",
                token(LuaTokenTypes.STRING, "\"a\\z \tb\"")
            ),
            LexerCase(
                "valid unicode and hex float side by side",
                "\"\\u{41}\"0x1p0",
                token(LuaTokenTypes.STRING, "\"\\u{41}\""),
                token(LuaTokenTypes.NUMBER, "0x1p0")
            )
        )
    }

    @Test
    fun invalidEscapeRecoveryAlwaysSurfacesBadCharacterAmongTokens() {
        assertMalformedCases(
            MalformedCase("unknown escape", "\"bad\\q\""),
            MalformedCase("broken unicode braces", "\"\\u{}\""),
            MalformedCase("broken unicode no brace", "\"\\u41\""),
            MalformedCase("broken hex escape", "\"\\xG0\""),
            MalformedCase("incomplete unicode at eof", "\"\\u{1F"),
            MalformedCase("hex float missing exponent digits", "0x1p+"),
            MalformedCase("hex missing digits", "0x"),
            MalformedCase("hex only dot", "0x."),
            MalformedCase("decimal exponent bare", "1e"),
            MalformedCase("backslash eof", "\"\\")
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
