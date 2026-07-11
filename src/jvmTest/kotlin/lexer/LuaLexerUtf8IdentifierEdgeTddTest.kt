package lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Edge corpus for UTF-8 / non-ASCII **identifier** tokenization (NAME tokens).
 *
 * Acceptance (TASK-460):
 * - UTF-8 / high-code-point identifier edges tokenize deterministically as NAME
 *   (or BAD_CHARACTER for non-identifier ASCII control / punctuation).
 * - Complements escape/numeric UTF-8 suites without rewriting them:
 *   - [parser.lexer.LuaLexerUtf8EscapeTddTest]
 *   - [parser.lexer.LuaLexerUtf8NumericCorpusTddTest]
 * - Product contract (LuaLexer):
 *   - Identifier start: `a-z` `A-Z` `_` `$` or any char `>= U+0080`.
 *   - Identifier part: decimal digit or identifier start.
 *   - Leading UTF-8 BOM (`U+FEFF`) is stripped from the source buffer so it is
 *     not glued into a following NAME; mid-buffer `U+FEFF` is left unchanged
 *     and is itself an identifier part (`>= U+0080`).
 * - Test-only; production lexer is not modified by this task.
 * - Verification deferred to review / TASK-043 (workers must not run Gradle).
 *
 * Host android.jar paths (docs only): /Users/dingyi/Downloads/android.jar and
 * /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar — never G:/.
 */
class LuaLexerUtf8IdentifierEdgeTddTest {

    @Test
    fun asciiIdentifierBasicsStaySingleNameTokens() {
        assertTokenCases(
            LexerCase(
                "simple ascii name",
                "foo",
                token(LuaTokenTypes.NAME, "foo")
            ),
            LexerCase(
                "underscore start",
                "_x",
                token(LuaTokenTypes.NAME, "_x")
            ),
            LexerCase(
                "dollar start (AndroLua-friendly)",
                "\$var",
                token(LuaTokenTypes.NAME, "\$var")
            ),
            LexerCase(
                "mixed letters digits underscore dollar",
                "a1_b2\$c3",
                token(LuaTokenTypes.NAME, "a1_b2\$c3")
            ),
            LexerCase(
                "uppercase and lowercase",
                "CamelCase_id",
                token(LuaTokenTypes.NAME, "CamelCase_id")
            )
        )
    }

    @Test
    fun highCodePointIdentifiersTokenizeAsSingleName() {
        // Product: any char >= U+0080 is an identifier start/part.
        assertTokenCases(
            LexerCase(
                "latin-1 accented start",
                "école",
                token(LuaTokenTypes.NAME, "école")
            ),
            LexerCase(
                "greek identifier",
                "αβγ",
                token(LuaTokenTypes.NAME, "αβγ")
            ),
            LexerCase(
                "cjk identifier",
                "变量",
                token(LuaTokenTypes.NAME, "变量")
            ),
            LexerCase(
                "cjk mixed with ascii digits",
                "变1x2",
                token(LuaTokenTypes.NAME, "变1x2")
            ),
            LexerCase(
                "emoji code point as name (UTF-16 surrogate pair units are both >= U+0080)",
                "😀",
                token(LuaTokenTypes.NAME, "😀")
            ),
            LexerCase(
                "emoji then ascii letter stays one name",
                "😀name",
                token(LuaTokenTypes.NAME, "😀name")
            ),
            LexerCase(
                "ascii then cjk stays one name",
                "var名",
                token(LuaTokenTypes.NAME, "var名")
            ),
            LexerCase(
                "U+0080 boundary start",
                "id",
                token(LuaTokenTypes.NAME, "id")
            ),
            LexerCase(
                "U+00FF then ascii",
                "ÿid",
                token(LuaTokenTypes.NAME, "ÿid")
            )
        )
    }

    @Test
    fun digitStartIsNumberNotNameAndSplitsFromFollowingName() {
        assertTokenCases(
            LexerCase(
                "leading digit is NUMBER then NAME",
                "1foo",
                token(LuaTokenTypes.NUMBER, "1"),
                token(LuaTokenTypes.NAME, "foo")
            ),
            LexerCase(
                "leading digit then cjk name after number",
                "2变",
                token(LuaTokenTypes.NUMBER, "2"),
                token(LuaTokenTypes.NAME, "变")
            ),
            LexerCase(
                "zero alone is NUMBER",
                "0",
                token(LuaTokenTypes.NUMBER, "0")
            )
        )
    }

    @Test
    fun leadingBomIsStrippedSoFollowingIdentifierIsCleanName() {
        // Product strips a single leading U+FEFF from the buffer.
        assertTokenCases(
            LexerCase(
                "leading BOM before ascii name",
                "﻿print",
                token(LuaTokenTypes.NAME, "print")
            ),
            LexerCase(
                "leading BOM before local keyword",
                "﻿local",
                token(LuaTokenTypes.LOCAL, "local")
            ),
            LexerCase(
                "leading BOM before cjk name",
                "﻿变量",
                token(LuaTokenTypes.NAME, "变量")
            ),
            LexerCase(
                "leading BOM before dollar name",
                "﻿\$x",
                token(LuaTokenTypes.NAME, "\$x")
            )
        )
    }

    @Test
    fun midBufferBomIsIdentifierPartAndGluesNames() {
        // Mid-buffer U+FEFF is not stripped and is >= U+0080 → identifier part.
        assertTokenCases(
            LexerCase(
                "mid BOM glues left and right ascii into one NAME",
                "a﻿b",
                token(LuaTokenTypes.NAME, "a﻿b")
            ),
            LexerCase(
                "mid BOM after whitespace is NAME",
                " ﻿",
                token(LuaTokenTypes.NAME, "﻿")
            ),
            LexerCase(
                "cjk then mid BOM then ascii",
                "中﻿x",
                token(LuaTokenTypes.NAME, "中﻿x")
            ),
            LexerCase(
                "name then mid BOM then keyword letters become one NAME",
                "x﻿end",
                token(LuaTokenTypes.NAME, "x﻿end")
            )
        )
    }

    @Test
    fun identifierBoundariesKeepOperatorsAndKeywords() {
        assertTokenCases(
            LexerCase(
                "assignment with cjk lhs",
                "变=1",
                token(LuaTokenTypes.NAME, "变"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.NUMBER, "1")
            ),
            LexerCase(
                "call with greek name",
                "α()",
                token(LuaTokenTypes.NAME, "α"),
                token(LuaTokenTypes.LPAREN, "("),
                token(LuaTokenTypes.RPAREN, ")")
            ),
            LexerCase(
                "dot member access with unicode names",
                "obj.字段",
                token(LuaTokenTypes.NAME, "obj"),
                token(LuaTokenTypes.DOT, "."),
                token(LuaTokenTypes.NAME, "字段")
            ),
            LexerCase(
                "colon method with unicode name",
                "t:方法()",
                token(LuaTokenTypes.NAME, "t"),
                token(LuaTokenTypes.COLON, ":"),
                token(LuaTokenTypes.NAME, "方法"),
                token(LuaTokenTypes.LPAREN, "("),
                token(LuaTokenTypes.RPAREN, ")")
            ),
            LexerCase(
                "local keyword then unicode name",
                "local 变",
                token(LuaTokenTypes.LOCAL, "local"),
                token(LuaTokenTypes.NAME, "变")
            ),
            LexerCase(
                "function keyword then unicode name",
                "function 函数() end",
                token(LuaTokenTypes.FUNCTION, "function"),
                token(LuaTokenTypes.NAME, "函数"),
                token(LuaTokenTypes.LPAREN, "("),
                token(LuaTokenTypes.RPAREN, ")"),
                token(LuaTokenTypes.END, "end")
            ),
            LexerCase(
                "concat between unicode names",
                "一..二",
                token(LuaTokenTypes.NAME, "一"),
                token(LuaTokenTypes.CONCAT, ".."),
                token(LuaTokenTypes.NAME, "二")
            ),
            LexerCase(
                "comma separates unicode names",
                "甲,乙",
                token(LuaTokenTypes.NAME, "甲"),
                token(LuaTokenTypes.COMMA, ","),
                token(LuaTokenTypes.NAME, "乙")
            )
        )
    }

    @Test
    fun nonIdentifierAsciiControlsAndPunctuationAreBadOrOperators() {
        assertTokenCases(
            LexerCase(
                "ascii control BEL is BAD_CHARACTER",
                "",
                token(LuaTokenTypes.BAD_CHARACTER, "")
            ),
            LexerCase(
                "ascii DEL is BAD_CHARACTER",
                "",
                token(LuaTokenTypes.BAD_CHARACTER, "")
            ),
            LexerCase(
                "question mark is BAD_CHARACTER",
                "?",
                token(LuaTokenTypes.BAD_CHARACTER, "?")
            ),
            LexerCase(
                "name then control then name splits",
                "ab",
                token(LuaTokenTypes.NAME, "a"),
                token(LuaTokenTypes.BAD_CHARACTER, ""),
                token(LuaTokenTypes.NAME, "b")
            ),
            LexerCase(
                "unicode name then question then ascii name",
                "变?x",
                token(LuaTokenTypes.NAME, "变"),
                token(LuaTokenTypes.BAD_CHARACTER, "?"),
                token(LuaTokenTypes.NAME, "x")
            )
        )
    }

    @Test
    fun keywordsRemainKeywordsAndAreNotSwallowedByUnicodeNeighbors() {
        assertTokenCases(
            LexerCase(
                "keyword alone stays keyword",
                "return",
                token(LuaTokenTypes.RETURN, "return")
            ),
            LexerCase(
                "keyword glued to unicode becomes NAME (not keyword)",
                "return变",
                token(LuaTokenTypes.NAME, "return变")
            ),
            LexerCase(
                "unicode then keyword letters as one NAME",
                "变end",
                token(LuaTokenTypes.NAME, "变end")
            ),
            LexerCase(
                "keyword space unicode name stays split",
                "return 变",
                token(LuaTokenTypes.RETURN, "return"),
                token(LuaTokenTypes.NAME, "变")
            )
        )
    }

    @Test
    fun adjacentUnicodeIdentifiersNeedSeparatorsToSplit() {
        assertTokenCases(
            LexerCase(
                "two cjk runs without separator are one NAME",
                "一二",
                token(LuaTokenTypes.NAME, "一二")
            ),
            LexerCase(
                "whitespace separates unicode names",
                "一 二",
                token(LuaTokenTypes.NAME, "一"),
                token(LuaTokenTypes.NAME, "二")
            ),
            LexerCase(
                "newline separates unicode names",
                "一\n二",
                token(LuaTokenTypes.NAME, "一"),
                token(LuaTokenTypes.NAME, "二")
            ),
            LexerCase(
                "semicolon separates unicode names",
                "一;二",
                token(LuaTokenTypes.NAME, "一"),
                token(LuaTokenTypes.SEMI, ";"),
                token(LuaTokenTypes.NAME, "二")
            )
        )
    }

    @Test
    fun dollarAndUnicodeEdgesStayDeterministic() {
        assertTokenCases(
            LexerCase(
                "dollar then cjk",
                "\$变",
                token(LuaTokenTypes.NAME, "\$变")
            ),
            LexerCase(
                "cjk then dollar then digit",
                "变\$1",
                token(LuaTokenTypes.NAME, "变\$1")
            ),
            LexerCase(
                "only dollar is NAME",
                "\$",
                token(LuaTokenTypes.NAME, "\$")
            ),
            LexerCase(
                "underscore then emoji",
                "_💩",
                token(LuaTokenTypes.NAME, "_💩")
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

    private data class TokenSnapshot(
        val type: LuaTokenTypes,
        val text: String
    )

    private companion object {
        fun token(type: LuaTokenTypes, text: String): TokenSnapshot = TokenSnapshot(type, text)
    }
}
