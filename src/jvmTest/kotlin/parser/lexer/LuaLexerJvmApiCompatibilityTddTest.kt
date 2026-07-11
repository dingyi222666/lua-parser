package parser.lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import kotlin.test.Test
import kotlin.test.assertEquals

class LuaLexerJvmApiCompatibilityTddTest {

    @Test
    fun exposesJavaVisibleSingleCharSequenceConstructor() {
        val constructor = LuaLexer::class.java.getConstructor(CharSequence::class.java)
        val lexer = constructor.newInstance("switch")

        assertEquals(LuaTokenTypes.SWITCH, lexer.nextToken())
        assertEquals("switch", lexer.tokenText.toString())
    }

    @Test
    fun preservesKotlinExplicitAndroLuaKeywordPolicyConstructorPath() {
        val lexer = LuaLexer("switch", supportAndroLuaKeywords = false)

        assertEquals(LuaTokenTypes.NAME, lexer.nextToken())
        assertEquals("switch", lexer.tokenText.toString())
    }
}
