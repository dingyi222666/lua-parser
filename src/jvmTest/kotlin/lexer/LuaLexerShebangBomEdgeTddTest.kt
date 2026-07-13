package lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Lexer edge corpus for leading `#!` shebang and UTF-8 BOM (`U+FEFF`) tokenization.
 *
 * Acceptance (TASK-466):
 * - Complements parser-level suites without rewriting them:
 *   - [parser.ParserShebangBomTddTest]
 *   - [parser.ast.ShebangOnlyChunkShapeTddTest]
 *   - [lexer.LuaLexerUtf8IdentifierEdgeTddTest] (BOM-on-NAME only)
 * - Product contract ([io.github.dingyi222666.luaparser.lexer.LuaLexer]):
 *   - Leading `#!` at buffer offset 0 → single [LuaTokenTypes.SHEBANG_CONTENT]
 *     consuming the rest of the line (stops before `\n` / `\r`; terminator is a
 *     separate [LuaTokenTypes.NEW_LINE] or EOF).
 *   - `#` not at offset 0, or `#` without following `!`, → [LuaTokenTypes.GETN].
 *   - Leading UTF-8 BOM (`U+FEFF`) is stripped once from the source buffer so it is
 *     invisible: BOM + `#!...` still yields SHEBANG_CONTENT; BOM + keyword/name
 *     yields clean LOCAL/NAME (not glued).
 *   - Mid-buffer `U+FEFF` is left unchanged and is an identifier start/part
 *     (`>= U+0080`).
 * - Test-only; production lexer is not modified by this task.
 * - Verification deferred to review / TASK-043 (workers must not run Gradle).
 *
 * Host android.jar paths (docs only): /Users/dingyi/Downloads/android.jar and
 * /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar — never G:/.
 */
class LuaLexerShebangBomEdgeTddTest {

    /** UTF-8 BOM as a Kotlin char (`U+FEFF`). */
    private val bom = "﻿"

    @Test
    fun leadingShebangAtOffsetZeroIsSingleShebangContentToken() {
        assertTokenCases(
            LexerCase(
                "env shebang without newline",
                "#!/usr/bin/env lua",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua")
            ),
            LexerCase(
                "env shebang with LF",
                "#!/usr/bin/env lua\n",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua")
            ),
            LexerCase(
                "minimal shebang #!",
                "#!",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!")
            ),
            LexerCase(
                "shebang with args and spaces",
                "#!/usr/bin/env lua -i --flag",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua -i --flag")
            ),
            LexerCase(
                "shebang then name on next line",
                "#!/usr/bin/lua\nprint",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/lua"),
                token(LuaTokenTypes.NAME, "print")
            ),
            LexerCase(
                "shebang then local keyword",
                "#!/usr/bin/env lua\nlocal",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua"),
                token(LuaTokenTypes.LOCAL, "local")
            ),
            LexerCase(
                "shebang then return and number",
                "#!/bin/lua\nreturn 1",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/bin/lua"),
                token(LuaTokenTypes.RETURN, "return"),
                token(LuaTokenTypes.NUMBER, "1")
            )
        )
    }

    @Test
    fun leadingBomIsStrippedSoShebangAndKeywordsStayClean() {
        assertTokenCases(
            LexerCase(
                "BOM + shebang is SHEBANG_CONTENT",
                "${bom}#!/usr/bin/env lua",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua")
            ),
            LexerCase(
                "BOM + shebang LF then local",
                "${bom}#!/usr/bin/env lua\nlocal x",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua"),
                token(LuaTokenTypes.LOCAL, "local"),
                token(LuaTokenTypes.NAME, "x")
            ),
            LexerCase(
                "BOM + shebang CRLF then return",
                "${bom}#!/usr/bin/lua\r\nreturn 1",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/lua"),
                token(LuaTokenTypes.RETURN, "return"),
                token(LuaTokenTypes.NUMBER, "1")
            ),
            LexerCase(
                "BOM + local keyword stays LOCAL",
                "${bom}local",
                token(LuaTokenTypes.LOCAL, "local")
            ),
            LexerCase(
                "BOM + print name stays NAME print",
                "${bom}print",
                token(LuaTokenTypes.NAME, "print")
            ),
            LexerCase(
                "BOM + function keyword",
                "${bom}function",
                token(LuaTokenTypes.FUNCTION, "function")
            ),
            LexerCase(
                "BOM + GETN hash alone (no bang)",
                "${bom}#",
                token(LuaTokenTypes.GETN, "#")
            ),
            LexerCase(
                "BOM + #x is GETN then NAME (not shebang without !)",
                "${bom}#x",
                token(LuaTokenTypes.GETN, "#"),
                token(LuaTokenTypes.NAME, "x")
            ),
            LexerCase(
                "BOM + minimal shebang #!",
                "${bom}#!",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!")
            )
        )
    }

    @Test
    fun shebangDoesNotConsumeFollowingStatementTokens() {
        assertTokenCases(
            LexerCase(
                "shebang then assignment",
                "#!/usr/bin/env lua\nx=1",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua"),
                token(LuaTokenTypes.NAME, "x"),
                token(LuaTokenTypes.ASSIGN, "="),
                token(LuaTokenTypes.NUMBER, "1")
            ),
            LexerCase(
                "shebang then short comment",
                "#!/usr/bin/env lua\n-- hi",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua"),
                token(LuaTokenTypes.SHORT_COMMENT, "-- hi")
            ),
            LexerCase(
                "shebang then string and call-like tokens",
                "#!/usr/bin/env lua\nprint(\"ok\")",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua"),
                token(LuaTokenTypes.NAME, "print"),
                token(LuaTokenTypes.LPAREN, "("),
                token(LuaTokenTypes.STRING, "\"ok\""),
                token(LuaTokenTypes.RPAREN, ")")
            ),
            LexerCase(
                "shebang then getn length operator",
                "#!/usr/bin/env lua\n#t",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua"),
                token(LuaTokenTypes.GETN, "#"),
                token(LuaTokenTypes.NAME, "t")
            ),
            LexerCase(
                "BOM shebang then getn",
                "${bom}#!/usr/bin/env lua\n#t",
                token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua"),
                token(LuaTokenTypes.GETN, "#"),
                token(LuaTokenTypes.NAME, "t")
            )
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun assertTokenCases(vararg cases: LexerCase) {
        val failures = cases.mapNotNull { case ->
            runCatching {
                assertContentEquals(case.expected, significantTokens(case.source), case.name)
            }.exceptionOrNull()?.let { failure ->
                "${case.name}\nsource: ${escapeForMessage(case.source)}\n${failure.message}"
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

    private fun escapeForMessage(source: String): String =
        source
            .replace("﻿", "<BOM>")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")

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
