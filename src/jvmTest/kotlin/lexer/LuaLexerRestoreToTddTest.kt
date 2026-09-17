package lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Lock for the new [LuaLexer.restoreTo] primitive (adversarial audit wave FF).
 *
 * restoreTo(index, tokenLength, line, column) must put the lexer back to the
 * exact cursor tuple visible right after nextToken() returned the token starting
 * at `index` (the same tuple a WrapperLuaLexer LexerState snapshot stores, with
 * the offset == index invariant applied). The next nextToken() call must then
 * re-derive the FOLLOWING token with identical type, text, line and column —
 * this is what lets a future parser-side deep-probe snapshot/restore replace
 * history-based back() without re-lex drift.
 *
 * Hand-verified token layout of "a1 = f(2)":
 * NAME a1(idx0) WS(1) ASSIGN(2) WS(3) NAME f(4->idx5) LPAREN(idx6) NUMBER 2(idx7) RPAREN(idx8) EOF.
 *
 * Verification deferred to review / TASK-043 (workers must not run Gradle).
 */
class LuaLexerRestoreToTddTest {

    @Test
    fun restoreToReplaysExactTokenContinuation() {
        val lexer = LuaLexer("a1 = f(2)")
        assertEquals(LuaTokenTypes.NAME, lexer.nextToken())
        assertEquals(LuaTokenTypes.WHITE_SPACE, lexer.nextToken())
        assertEquals(LuaTokenTypes.ASSIGN, lexer.nextToken())
        assertEquals(LuaTokenTypes.WHITE_SPACE, lexer.nextToken())
        assertEquals(LuaTokenTypes.NAME, lexer.nextToken())
        assertEquals(LuaTokenTypes.LPAREN, lexer.nextToken())

        // Snapshot of the LPAREN token start — the LexerState tuple.
        val index = lexer.index
        val length = lexer.tokenLength
        val line = lexer.tokenLine
        val column = lexer.tokenColumn
        assertEquals(6, index, "LPAREN starts at offset 6")
        assertEquals(1, length)
        assertEquals(1, line)
        assertEquals(6, column)

        assertEquals(LuaTokenTypes.NUMBER, lexer.nextToken())
        assertEquals("2", lexer.tokenText.toString())
        assertEquals(LuaTokenTypes.RPAREN, lexer.nextToken())
        assertEquals(LuaTokenTypes.EOF, lexer.nextToken())

        lexer.restoreTo(index, length, line, column)

        assertEquals(LuaTokenTypes.NUMBER, lexer.nextToken(), "replay must re-derive the token after LPAREN")
        assertEquals("2", lexer.tokenText.toString())
        assertEquals(1, lexer.tokenLine, "replayed token start line must match the original")
        assertEquals(7, lexer.tokenColumn, "replayed token start column must match the original")
        assertEquals(LuaTokenTypes.RPAREN, lexer.nextToken())
        assertEquals(LuaTokenTypes.EOF, lexer.nextToken())
    }

    @Test
    fun restoreToPreservesLineColumnAcrossNewlines() {
        // Tokens: NAME a(0) WS(1) ASSIGN(2) WS(3) NUMBER 1(4) NEW_LINE(5)
        //         NAME b(6) WS(7) ASSIGN(8) WS(9) NUMBER 2(10) EOF(11)
        val lexer = LuaLexer("a = 1\nb = 2")
        assertEquals(LuaTokenTypes.NAME, lexer.nextToken())
        assertEquals(LuaTokenTypes.WHITE_SPACE, lexer.nextToken())
        assertEquals(LuaTokenTypes.ASSIGN, lexer.nextToken())
        assertEquals(LuaTokenTypes.WHITE_SPACE, lexer.nextToken())
        assertEquals(LuaTokenTypes.NUMBER, lexer.nextToken())
        assertEquals(LuaTokenTypes.NEW_LINE, lexer.nextToken())
        assertEquals(LuaTokenTypes.NAME, lexer.nextToken()) // b, starts line 2 column 0

        val index = lexer.index
        val length = lexer.tokenLength
        val line = lexer.tokenLine
        val column = lexer.tokenColumn
        assertEquals(6, index)
        assertEquals(1, length)
        assertEquals(2, line, "NAME b sits on line 2")
        assertEquals(0, column, "NAME b starts at column 0")

        while (lexer.nextToken() != LuaTokenTypes.EOF) {
            // drain to EOF
        }

        lexer.restoreTo(index, length, line, column)

        // The restored lexer must rebuild line/column for the FOLLOWING tokens by
        // re-counting newlines across the restored span, exactly like the original pass.
        assertEquals(LuaTokenTypes.WHITE_SPACE, lexer.nextToken())
        assertEquals(LuaTokenTypes.ASSIGN, lexer.nextToken())
        assertEquals(LuaTokenTypes.WHITE_SPACE, lexer.nextToken())
        assertEquals(LuaTokenTypes.NUMBER, lexer.nextToken())
        assertEquals("2", lexer.tokenText.toString())
        assertEquals(2, lexer.tokenLine, "NUMBER 2 must stay on line 2 after restore")
        assertEquals(4, lexer.tokenColumn, "NUMBER 2 must start at column 4 after restore")
        assertEquals(LuaTokenTypes.EOF, lexer.nextToken())
    }
}
