package parser.lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Edge corpus for Lua 5.3 hex-float numerics (`0x1.fp3`) and short-string
 * escapes (`\xHH`, `\u{...}`, decimal `\ddd`).
 *
 * Asserts token kinds + raw lexeme text, including recoverable BAD_CHARACTER
 * boundaries for invalid escapes. Test-only; production lexer is not modified
 * by this task. Red is acceptable until review-owned verification.
 */
class LuaLexerHexFloatEscapeTddTest {

    @Test
    fun tokenizesHexFloatEdgesWithoutHang() {
        assertTokenCases(
            LexerCase(
                "canonical hex float fraction with p exponent",
                "0x1.fp3",
                token(LuaTokenTypes.NUMBER, "0x1.fp3")
            ),
            LexerCase(
                "hex float fraction with signed plus exponent",
                "0x1.fp+3",
                token(LuaTokenTypes.NUMBER, "0x1.fp+3")
            ),
            LexerCase(
                "hex float fraction with signed minus exponent",
                "0x1.8p-2",
                token(LuaTokenTypes.NUMBER, "0x1.8p-2")
            ),
            LexerCase(
                "hex float without integer digits",
                "0x.fp1",
                token(LuaTokenTypes.NUMBER, "0x.fp1")
            ),
            LexerCase(
                "hex integer with binary exponent only",
                "0x1p10",
                token(LuaTokenTypes.NUMBER, "0x1p10")
            ),
            LexerCase(
                "uppercase hex float with uppercase P",
                "0X1.FP+0",
                token(LuaTokenTypes.NUMBER, "0X1.FP+0")
            ),
            LexerCase(
                "hex float without binary exponent still NUMBER",
                "0x1.f",
                token(LuaTokenTypes.NUMBER, "0x1.f")
            ),
            LexerCase(
                "hex float zero fraction and p0",
                "0x0.0p0",
                token(LuaTokenTypes.NUMBER, "0x0.0p0")
            ),
            LexerCase(
                "hex float then name splits at digit boundary",
                "0x1.fp3name",
                token(LuaTokenTypes.NUMBER, "0x1.fp3"),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "hex float then concat keeps operator boundary",
                "0x1.fp3..x",
                token(LuaTokenTypes.NUMBER, "0x1.fp3"),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.NAME, "x")
            ),
            LexerCase(
                "hex float in assignment expression",
                "a=0x1.fp3",
                token(LuaTokenTypes.NAME, "a"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.NUMBER, "0x1.fp3")
            ),
            LexerCase(
                "adjacent hex floats require whitespace separator",
                "0x1.fp3 0x.8p-1",
                token(LuaTokenTypes.NUMBER, "0x1.fp3"),
                token(LuaTokenTypes.NUMBER, "0x.8p-1")
            ),
            LexerCase(
                "hex float inside table punctuation",
                "{0x1.fp3,0x.ap+1}",
                token(LuaTokenTypes.LCURLY, "{"),
                token(LuaTokenTypes.NUMBER, "0x1.fp3"),
                token(LuaTokenTypes.COMMA, ","),
                token(LuaTokenTypes.NUMBER, "0x.ap+1"),
                token(LuaTokenTypes.RCURLY, "}")
            ),
            LexerCase(
                "call-shaped adjacency with hex float string-less arg form",
                "f(0x1.fp3)",
                token(LuaTokenTypes.NAME, "f"),
                token(LuaTokenTypes.LPAREN, "("),
                token(LuaTokenTypes.NUMBER, "0x1.fp3"),
                token(LuaTokenTypes.RPAREN, ")")
            )
        )
    }

    @Test
    fun tokenizesHexUnicodeAndDecimalEscapesWithoutHang() {
        assertTokenCases(
            LexerCase(
                "hex escape two digits",
                "\"\\x41\"",
                token(LuaTokenTypes.STRING, "\"\\x41\"")
            ),
            LexerCase(
                "hex escape lowercase ff",
                "\"\\xff\"",
                token(LuaTokenTypes.STRING, "\"\\xff\"")
            ),
            LexerCase(
                "hex escape uppercase AF",
                "\"\\xAF\"",
                token(LuaTokenTypes.STRING, "\"\\xAF\"")
            ),
            LexerCase(
                "unicode escape minimal A",
                "\"\\u{41}\"",
                token(LuaTokenTypes.STRING, "\"\\u{41}\"")
            ),
            LexerCase(
                "unicode escape multi-digit emoji codepoint text",
                "\"\\u{1F600}\"",
                token(LuaTokenTypes.STRING, "\"\\u{1F600}\"")
            ),
            LexerCase(
                "unicode escape max plane text kept raw",
                "\"\\u{10FFFF}\"",
                token(LuaTokenTypes.STRING, "\"\\u{10FFFF}\"")
            ),
            LexerCase(
                "decimal escape three digits",
                "\"\\065\"",
                token(LuaTokenTypes.STRING, "\"\\065\"")
            ),
            LexerCase(
                "decimal escape two digits",
                "\"\\65\"",
                token(LuaTokenTypes.STRING, "\"\\65\"")
            ),
            LexerCase(
                "decimal escape one digit",
                "\"\\9\"",
                token(LuaTokenTypes.STRING, "\"\\9\"")
            ),
            LexerCase(
                "mixed hex unicode and decimal escapes stay one STRING",
                "\"\\x41\\u{42}\\066\"",
                token(LuaTokenTypes.STRING, "\"\\x41\\u{42}\\066\"")
            ),
            LexerCase(
                "escapes inside single-quoted string",
                "'\\x61\\u{62}\\099'",
                token(LuaTokenTypes.STRING, "'\\x61\\u{62}\\099'")
            ),
            LexerCase(
                "standard control escapes still one STRING",
                "\"\\a\\b\\f\\n\\r\\t\\v\\\\\\\"\"",
                token(LuaTokenTypes.STRING, "\"\\a\\b\\f\\n\\r\\t\\v\\\\\\\"\"")
            ),
            LexerCase(
                "z escape consumes following whitespace inside STRING",
                "\"a\\z \n\tb\"",
                token(LuaTokenTypes.STRING, "\"a\\z \n\tb\"")
            ),
            LexerCase(
                "escape then plain text stays one STRING",
                "\"pre\\x41mid\\u{42}post\"",
                token(LuaTokenTypes.STRING, "\"pre\\x41mid\\u{42}post\"")
            ),
            LexerCase(
                "string concat of hex-escaped strings keeps three tokens",
                "\"\\x41\"..\"\\x42\"",
                token(LuaTokenTypes.STRING, "\"\\x41\""),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.STRING, "\"\\x42\"")
            )
        )
    }

    @Test
    fun surfacesInvalidEscapesAsRecoverableBadCharacterTokens() {
        // Current policy: invalid escapes stop the short string as BAD_CHARACTER
        // at the bad escape span; remaining source is re-tokenized (recovery).
        assertTokenCases(
            LexerCase(
                "hex escape incomplete single digit",
                "\"\\xA\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\x"),
                token(LuaTokenTypes.NAME, "A"),
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
                "hex escape non-hex second digit",
                "\"\\xAG\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\x"),
                token(LuaTokenTypes.NAME, "AG"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "hex escape bare x before closing quote",
                "\"\\x\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\x"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
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
                "unicode escape open brace at eof",
                "\"\\u{",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\u{")
            ),
            LexerCase(
                "unknown short escape letter",
                "\"\\q\"",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\q"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"")
            ),
            LexerCase(
                "backslash at eof is incomplete escape",
                "\"\\",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\")
            ),
            LexerCase(
                "invalid escape mid expression recovers trailing source",
                "x=\"\\q\"+1",
                token(LuaTokenTypes.NAME, "x"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\q"),
                token(LuaTokenTypes.BAD_CHARACTER, "\"+1")
            ),
            LexerCase(
                "invalid hex escape recovers following name digits",
                "\"\\xG0\";y",
                token(LuaTokenTypes.BAD_CHARACTER, "\"\\x"),
                token(LuaTokenTypes.NAME, "G0"),
                token(LuaTokenTypes.BAD_CHARACTER, "\";y")
            )
        )
    }

    @Test
    fun surfacesMalformedHexFloatAsRecoverableBadCharacterTokens() {
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
                "hex float p without exponent digits",
                "0x1.fp",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1.fp")
            ),
            LexerCase(
                "hex float p with sign only",
                "0x1.fp+",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1.fp+")
            ),
            LexerCase(
                "hex float p with minus only",
                "0x1.fp-",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1.fp-")
            ),
            LexerCase(
                "hex integer p without digits",
                "0x1p",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1p")
            ),
            LexerCase(
                "malformed hex float recovers following name",
                "0x1.fp name",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1.fp"),
                token(LuaTokenTypes.NAME, "name")
            ),
            LexerCase(
                "malformed hex prefix recovers following semicolon",
                "0x; y",
                token(LuaTokenTypes.BAD_CHARACTER, "0x"),
                token(LuaTokenTypes.SEMI, ";"),
                token(LuaTokenTypes.NAME, "y")
            ),
            LexerCase(
                "malformed hex exponent sign recovers following name",
                "0x1.fp+ name",
                token(LuaTokenTypes.BAD_CHARACTER, "0x1.fp+"),
                token(LuaTokenTypes.NAME, "name")
            )
        )
    }

    @Test
    fun mixesValidHexFloatWithValidEscapesInOneStream() {
        assertTokenCases(
            LexerCase(
                "local-shaped assignment of hex float and escaped string",
                "local n=0x1.fp3 s=\"\\x41\\u{42}\\066\"",
                token(LuaTokenTypes.LOCAL, "local"),
                token(LuaTokenTypes.NAME, "n"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.NUMBER, "0x1.fp3"),
                token(LuaTokenTypes.NAME, "s"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.STRING, "\"\\x41\\u{42}\\066\"")
            ),
            LexerCase(
                "return hex float and unicode string",
                "return 0x.8p-1,\"\\u{1F4A9}\"",
                token(LuaTokenTypes.RETURN, "return"),
                token(LuaTokenTypes.NUMBER, "0x.8p-1"),
                token(LuaTokenTypes.COMMA, ","),
                token(LuaTokenTypes.STRING, "\"\\u{1F4A9}\"")
            ),
            LexerCase(
                "print call with string escapes and hex float arg",
                "print(\"\\x48\\u{69}\",0x1.fp3)",
                token(LuaTokenTypes.NAME, "print"),
                token(LuaTokenTypes.LPAREN, "("),
                token(LuaTokenTypes.STRING, "\"\\x48\\u{69}\""),
                token(LuaTokenTypes.COMMA, ","),
                token(LuaTokenTypes.NUMBER, "0x1.fp3"),
                token(LuaTokenTypes.RPAREN, ")")
            ),
            LexerCase(
                "string adjacency call form then hex float",
                "f\"\\x41\"0x1p0",
                token(LuaTokenTypes.NAME, "f"),
                token(LuaTokenTypes.STRING, "\"\\x41\""),
                token(LuaTokenTypes.NUMBER, "0x1p0")
            )
        )
    }

    @Test
    fun invalidEscapeAndHexFloatCasesAlwaysSurfaceBadCharacterOrException() {
        assertMalformedCases(
            MalformedCase("unknown escape letter", "\"bad\\q\""),
            MalformedCase("incomplete hex escape", "\"\\xA\""),
            MalformedCase("non-hex hex escape", "\"\\xG0\""),
            MalformedCase("unicode missing brace", "\"\\u41\""),
            MalformedCase("unicode empty braces", "\"\\u{}\""),
            MalformedCase("unicode unclosed brace", "\"\\u{1F"),
            MalformedCase("backslash at eof", "\"\\"),
            MalformedCase("hex prefix bare", "0x"),
            MalformedCase("hex only dot", "0x."),
            MalformedCase("hex float missing exponent digits", "0x1.fp+"),
            MalformedCase("hex p bare", "0x1p")
        )
    }

    @Test
    fun lexingCorpusReachesEofWithoutInfiniteLoop() {
        // Smoke: a dense mix of valid/invalid hex floats and escapes must finish.
        val dense = buildString {
            repeat(32) {
                append("0x1.fp3 ")
                append("0x.8p-1 ")
                append("\"\\x41\\u{42}\\066\" ")
                append("\"\\xG0\" ")
                append("0x1.fp ")
                append("local x=0x1p4 ")
            }
        }

        val tokens = significantTokens(dense)
        assertTrue(tokens.isNotEmpty(), "expected non-empty token stream for dense corpus")
        assertTrue(
            tokens.any { it.type == LuaTokenTypes.NUMBER },
            "expected at least one NUMBER in dense corpus, got $tokens"
        )
        assertTrue(
            tokens.any { it.type == LuaTokenTypes.STRING },
            "expected at least one STRING in dense corpus"
        )
        assertTrue(
            tokens.any { it.type == LuaTokenTypes.BAD_CHARACTER },
            "expected recoverable BAD_CHARACTER tokens for invalid edges"
        )

        // Reaching here means nextToken eventually produced EOF (significantTokens stops on EOF).
        val again = significantTokens(dense)
        assertContentEquals(tokens, again, "token stream must be deterministic and finite")
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
        // Hard cap guards against accidental infinite nextToken loops in review.
        val maxTokens = source.length * 4 + 64
        var count = 0

        while (true) {
            val type = lexer.nextToken()
            if (type == LuaTokenTypes.EOF) {
                return result
            }
            count++
            if (count > maxTokens) {
                fail("Lexer appears to hang: exceeded $maxTokens tokens for source length ${source.length}")
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
