package io.github.dingyi222666.lua.lexer

import io.github.dingyi222666.lua.parser.util.require

/**
 * @author: dingyi
 * @date: 2023/2/3
 * @description:
 **/
class WrapperLuaLexer(
    private val currentLexer: LuaLexer
) {

    private val lastStates = ArrayDeque<LexerState>()
    private val currentStates = ArrayDeque<LexerState>(5)
    private var currentState = LexerState(
        yychar = currentLexer.yychar(),
        yycolumn = currentLexer.yycolumn(),
        yylength = currentLexer.yylength(),
        yyline = currentLexer.yyline(),
        yytext = currentLexer.yytext(),
        type = LuaTokenTypes.WHITE_SPACE
    )
    fun yytext() = currentState.require().yytext
    fun yychar() = currentState.require().yychar
    fun yylength() = currentState.yylength

    //粗暴加1，无所谓，需要的时候自己记得换算
    fun yyline() = currentState.yyline + 1

    fun yycolumn() = currentState.yycolumn + 1

    fun advance(): LuaTokenTypes {
        if (currentStates.isNotEmpty()) {
            currentState = currentStates.removeFirst()
        } else {
            doAdvance()
            currentState.let(lastStates::addFirst)
        }

        clearStates()
        return currentState.type
    }

    fun yypushback(size: Int) {

        if (currentStates.isNotEmpty()) {
            currentStates.addFirst(currentState.require())
            return
        }

        currentLexer.yypushback(size)
        doAdvance()

        if (currentStates.isEmpty()) {
            currentStates.addFirst(currentState.require())
        }
    }


    fun yyback(tokenSize: Int) {
        for (i in 0 until tokenSize) {
            val state = lastStates.removeFirst()
            currentStates.addFirst(state)
        }
    }


    private fun doAdvance() {
        var type = currentLexer.advance()

        if (type == null) {
            type = LuaTokenTypes.EOF
        }

        val newState = LexerState(
            yychar = currentLexer.yychar(),
            yycolumn = currentLexer.yycolumn(),
            yylength = currentLexer.yylength(),
            yyline = currentLexer.yyline(),
            yytext = currentLexer.yytext(),
            type = type
        )
        currentState = newState
    }


    fun close() {
        currentLexer.yyclose()
    }

    private fun clearStates() {
        if (lastStates.size >= 6) {
            lastStates.removeLast()
        }
    }
}

internal data class LexerState(
    val yychar: Int,
    val yytext: String,
    val yyline: Int,
    val yycolumn: Int,
    val type: LuaTokenTypes,
    val yylength: Int
) {

}