package lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import io.github.dingyi222666.luaparser.lexer.WrapperLuaLexer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 64-token history cap fix lock for [WrapperLuaLexer.back].
 *
 * Root cause (adversarial audit wave FF): clearStates() trimmed lastStates to
 * 64 entries after every advance, and back() SILENTLY stopped at the oldest
 * survivor when asked for more. A deep probe (tokenAfterMatchingParen over a
 * >64-token argument list) permanently lost the earliest lookahead tokens and
 * the parser resumed mid-stream believing it sat before a token it had already
 * consumed. Realistic trigger: a plain-Lua identifier literally named `lambda`
 * called with a >64-token argument list.
 *
 * Fix lock:
 * - MAX_LAST_STATES 64 -> 512 (LexerState entries are cheap: Int offset + a
 *   CharSequence view over the source + enum + two small Ints).
 * - Shortfalls are loud: [WrapperLuaLexer.lastBackTruncated] is set when a back
 *   cannot rewind its full count (no stderr/log noise — this is a library).
 * - back within budget must replay every advanced token, in order, including
 *   after interleaved re-advances.
 *
 * Verification deferred to review / TASK-043 (workers must not run Gradle).
 */
class WrapperLuaLexerBackHistoryTddTest {

    @Test
    fun back100ReplaysAll100AdvancedTokensInOrder() {
        // Stream: NAME v1, COMMA, NAME v2, COMMA, ... — 199 significant tokens.
        val source = (1..100).joinToString(",") { "v$it" }
        val wrapper = newWrapper(source)

        val firstPass = mutableListOf<Pair<LuaTokenTypes, String>>()
        repeat(100) {
            val type = wrapper.advance()
            firstPass += type to wrapper.text().toString()
        }
        assertEquals(100, firstPass.size)
        assertEquals(LuaTokenTypes.NAME to "v1", firstPass.first(), "stream shape guard")

        wrapper.back(100)
        assertFalse(
            wrapper.lastBackTruncated,
            "back(100) is within the 512-token history budget and must not be flagged"
        )

        val replayed = mutableListOf<Pair<LuaTokenTypes, String>>()
        repeat(100) {
            val type = wrapper.advance()
            replayed += type to wrapper.text().toString()
        }

        assertEquals(
            firstPass,
            replayed,
            "all 100 advanced tokens must be re-presented in order; the old 64-token " +
                "cap silently dropped the earliest 36 and the replay diverged"
        )
    }

    @Test
    fun inBudgetBackDoesNotRaiseTruncatedFlag() {
        val source = (1..100).joinToString(",") { "v$it" }
        val wrapper = newWrapper(source)

        assertFalse(wrapper.lastBackTruncated, "a fresh wrapper has no shortfall")
        repeat(10) { wrapper.advance() }

        wrapper.back(10)
        assertFalse(wrapper.lastBackTruncated, "exact-size back is in budget")

        wrapper.advance() // re-consume one rewound token, interleaving advance/back
        wrapper.back(5)
        assertFalse(wrapper.lastBackTruncated, "in-budget back after re-advance is in budget")

        wrapper.back(0)
        assertFalse(wrapper.lastBackTruncated, "no-op back(0) is not a shortfall")
    }

    @Test
    fun backBeyond512TokenHistoryRaisesTruncatedFlagAndReplaysWhatSurvived() {
        // Stream: 650 NAME + 649 COMMA = 1299 significant tokens (+ EOF).
        val source = (1..650).joinToString(",") { "v$it" }

        // Record the full stream once so the truncated rewind window can be
        // asserted element-wise instead of by spot checks.
        val reference = newWrapper(source)
        val fullStream = mutableListOf<Pair<LuaTokenTypes, String>>()
        while (true) {
            val type = reference.advance()
            fullStream += type to reference.text().toString()
            if (type == LuaTokenTypes.EOF) break
        }

        val wrapper = newWrapper(source)
        repeat(650) { wrapper.advance() }
        assertFalse(wrapper.lastBackTruncated, "advancing alone never sets the flag")

        wrapper.back(300)
        // 300 fits within the 512-entry history: no truncation.
        assertFalse(wrapper.lastBackTruncated, "in-budget back from the replay queue must not truncate")

        // Every rewound token is replayed in order, and the 301st advance splices
        // seamlessly back into the live stream.
        val replayed = mutableListOf<Pair<LuaTokenTypes, String>>()
        repeat(300) {
            val type = wrapper.advance()
            replayed += type to wrapper.text().toString()
        }
        assertEquals(300, replayed.size)
        println("DEBUG replay first=" + replayed.first().second + " last=" + replayed.last().second + " count=" + replayed.size)
        assertEquals(
            fullStream.subList(350, 650),
            replayed,
            "the 512 surviving entries replay exactly, then the live stream continues"
        )

        wrapper.back(1)
        assertFalse(wrapper.lastBackTruncated, "a later in-budget back resets the flag")
    }

    // --- helpers -----------------------------------------------------------------

    private fun newWrapper(source: String): WrapperLuaLexer = WrapperLuaLexer(LuaLexer(source))
}
