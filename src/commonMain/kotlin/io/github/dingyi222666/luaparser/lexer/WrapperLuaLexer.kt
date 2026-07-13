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
        // advance() always records currentState into lastStates. When we re-queue that
        // token for re-play, drop the matching history head so back() does not see a
        // ghost duplicate (TASK-612 recovery underflow / history starvation).
        dropHistoryHeadMatchingCurrent()
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

    private fun dropHistoryHeadMatchingCurrent() {
        if (lastStates.isEmpty()) {
            return
        }
        val head = lastStates.first()
        if (head.index == currentState.index &&
            head.length == currentState.length &&
            head.type == currentState.type
        ) {
            lastStates.removeFirst()
        }
    }


    /**
     * Rewind the last [tokenSize] advanced tokens into [currentStates] for re-play.
     *
     * Recovery look-ahead (call-shaped NAME chains, bare-NAME call-arg sibling checks,
     * peekN) advances whitespace + significant tokens then restores with back(). History
     * is bounded, so never throw on underflow: restore what is available and stop
     * (TASK-612 ArrayDeque empty during Android-Lua workspace parse).
     */
    fun back(tokenSize: Int) {
        if (tokenSize <= 0) {
            return
        }
        var remaining = tokenSize
        while (remaining > 0 && lastStates.isNotEmpty()) {
            val state = lastStates.removeFirst()
            currentStates.addFirst(state)
            remaining--
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
        // Keep enough history for recovery look-ahead (member chains + whitespace).
        // The previous cap of 5 overflowed on peekN / isCallShapedNameStatementStart
        // and threw NoSuchElementException from back() (TASK-612 full jvmTest).
        while (lastStates.size > MAX_LAST_STATES) {
            lastStates.removeLast()
        }
    }

    private fun versionAwareTokenType(type: LuaTokenTypes): LuaTokenTypes {
        return if (supportAndroLuaKeywords || !LuaLexer.isAndroLuaKeyword(type)) type else LuaTokenTypes.NAME
    }

    companion object {
        /**
         * Max tokens retained for [back]. Must cover recovery look-aheads that walk
         * whitespace and member chains (e.g. activity.setContentView forms).
         */
        private const val MAX_LAST_STATES = 64
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
