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
            // Must NOT fall through to doAdvance() here: that would rescan a brand-new
            // token instead of leaving the requeued one as the next replay entry.
            return
        }
        currentLexer.pushBack(size)
        doAdvance()

        // After the real pushback + rescan, currentStates is always empty at this
        // point (nothing was queued on this path, and doAdvance/pushBack never touch
        // the queue), so this requeue always runs.
        currentStates.addFirst(currentState)
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
     * True when the most recent [back] could not rewind the full requested
     * [back]-count because the bounded history ([MAX_LAST_STATES]) had already
     * trimmed the older entries. Reset to `false` at the start of every [back]
     * call, so the flag always describes the latest call and callers (parser
     * recovery paths, tests) can assert a shortfall instead of it staying silent:
     * a truncated back leaves the earliest rewound tokens un-restored, and a deep
     * probe (e.g. tokenAfterMatchingParen over a >512-token span) would resume
     * mid-stream before tokens it already consumed. There is no stderr/log noise
     * by design — this is a library.
     */
    var lastBackTruncated: Boolean = false
        private set

    /**
     * Rewind the last [tokenSize] advanced tokens into [currentStates] for re-play.
     *
     * Recovery look-ahead (call-shaped NAME chains, bare-NAME call-arg sibling checks,
     * peekN) advances whitespace + significant tokens then restores with back(). History
     * is bounded, so never throw on underflow: restore what is available, stop, and
     * raise [lastBackTruncated] instead of failing silently (TASK-612 ArrayDeque empty
     * during Android-Lua workspace parse; the silent stop is what let the 64-token cap
     * corrupt deep-probe resumes — see [MAX_LAST_STATES]).
     *
     * Soundness bound: a [back] within [MAX_LAST_STATES] always restores every
     * requested token exactly. Deeper rewinds are truncated with
     * [lastBackTruncated] set; a fully sound deep-probe rewind needs a parser-side
     * snapshot + [LuaLexer.restoreTo], which the wrapper cannot trigger on its own.
     */
    fun back(tokenSize: Int) {
        lastBackTruncated = false
        var remaining = tokenSize
        while (remaining > 0 && lastStates.isNotEmpty()) {
            val state = lastStates.removeFirst()
            currentStates.addFirst(state)
            remaining--
        }
        if (remaining > 0) {
            // History exhausted: fall through to the replay queue (tokens the parser
            // pushed back but hasn't re-advanced past yet). Moving them back onto
            // lastStates lets the caller's next advance() replay them in order.
            while (remaining > 0 && currentStates.isNotEmpty()) {
                val state = currentStates.removeLast()
                lastStates.addLast(state)
                remaining--
            }
        }
        if (remaining > 0) {
            lastBackTruncated = true
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
         * whitespace and member chains (e.g. activity.setContentView forms) AND the
         * parser's deep probes, the deepest being tokenAfterMatchingParen over a
         * whole argument list (every token of the list is advanced before back()
         * rewinds it).
         *
         * Raised from 64 to 512 because the 64-token cap was proven lossy:
         * clearStates() trimmed the oldest entries after every advance and back()
         * silently stopped at the oldest survivor, so a deep probe over a >64-token
         * argument list permanently lost the earliest lookahead tokens and the
         * parser resumed mid-stream believing it sat before a token it had already
         * consumed. Realistic trigger: a plain-Lua identifier literally named
         * `lambda` called with a >64-token argument list.
         *
         * History entries are cheap: LexerState holds an Int offset, a CharSequence
         * slice (view over the source, no copy), an enum and two small Ints, so
         * 512 entries cost only a few KiB per lexer. The 8x headroom over the old
         * cap covers the realistic bound — tokenAfterMatchingParen over
         * human-written argument lists.
         *
         * KNOWN LIMIT: deep probes rewinding more than 512 tokens remain unsound —
         * the earliest history entries are gone and the wrapper cannot re-derive
         * them without a parser-side snapshot + LuaLexer.restoreTo (LuaParser is
         * out of scope for the wrapper). Such rewinds are now loud instead of
         * silent: [lastBackTruncated] is set so callers/tests can assert them.
         */
        private const val MAX_LAST_STATES = 512
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
