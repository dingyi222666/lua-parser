package io.github.dingyi222666.luaparser.lexer

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
        column = currentLexer.tokenColumn,
        length = currentLexer.tokenLength,
        line = currentLexer.tokenLine,
        text = currentLexer.tokenText,
        type = LuaTokenTypes.WHITE_SPACE
    )

    fun text() = currentState.text

    fun length() = currentState.length


    fun line() = currentState.line

    fun column() = currentState.column + 1

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

    fun pushback(size: Int) {
        if (currentStates.isNotEmpty()) {
            currentStates.addFirst(currentState)
            return
        }
        currentLexer.pushBack(size)
        doAdvance()

        if (currentStates.isEmpty()) {
            currentStates.addFirst(currentState)
        }
    }


    fun back(tokenSize: Int) {
        for (i in 0..<tokenSize) {
            val state = lastStates.removeFirst()
            currentStates.addFirst(state)
        }
    }


    private fun doAdvance() {
        val type = currentLexer.nextToken()


        val newState = LexerState(
            column = currentLexer.tokenColumn,
            length = currentLexer.tokenLength,
            line = currentLexer.tokenLine,
            text = currentLexer.tokenText,
            type = type
        )
        currentState = newState
    }


    fun close() {
        //currentLexer.yyclose()
        lastStates.clear()
        currentStates.clear()
    }

    private fun clearStates() {
        if (lastStates.size >= 6) {
            lastStates.removeLast()
        }
    }
}

internal data class LexerState(
    val text: CharSequence,
    val line: Int,
    val column: Int,
    val type: LuaTokenTypes,
    val length: Int
)