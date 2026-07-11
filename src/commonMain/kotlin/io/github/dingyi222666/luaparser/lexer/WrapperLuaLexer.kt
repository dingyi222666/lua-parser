package io.github.dingyi222666.luaparser.lexer

/**
 * @author: dingyi
 * @date: 2023/2/3
 * @description:
 **/
class WrapperLuaLexer(
    private val currentLexer: LuaLexer,
    private val supportAndroLuaKeywords: Boolean = true
) {

    private val lastStates = ArrayDeque<LexerState>()
    private val currentStates = ArrayDeque<LexerState>(5)
    private var currentState = LexerState(
        index = currentLexer.index,
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

    fun hasLineBreakBeforeNextSignificantToken(): Boolean {
        // After pushback/back, the next significant token is queued in currentStates.
        // Look for a line break immediately before that queued token — not past the
        // already-re-advanced currentState (which would skip a NAME RHS and see the
        // newline before a following statement).
        val fromIndex = if (currentStates.isNotEmpty()) {
            currentStates.first().index
        } else {
            currentState.index + currentState.length
        }
        return currentLexer.hasLineBreakBeforeNextSignificantToken(fromIndex)
    }

    fun advance(): LuaTokenTypes {
        if (currentStates.isNotEmpty()) {
            currentState = currentStates.removeFirst()
            // Record queued tokens in lastStates so back()/peekN work after pushback.
            currentState.let(lastStates::addFirst)
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
        val type = versionAwareTokenType(currentLexer.nextToken())

        val newState = LexerState(
            index = currentLexer.index,
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

    private fun versionAwareTokenType(type: LuaTokenTypes): LuaTokenTypes {
        return if (supportAndroLuaKeywords || !LuaLexer.isAndroLuaKeyword(type)) type else LuaTokenTypes.NAME
    }
}

internal data class LexerState(
    val index: Int,
    val text: CharSequence,
    val line: Int,
    val column: Int,
    val type: LuaTokenTypes,
    val length: Int
)
