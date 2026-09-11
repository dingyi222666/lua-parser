package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tail-of-body queries: a caret between the last statement of a for body and the closing
 * `end` sits OUTSIDE the loop scope's end-exclusive range (the loop scope is created with
 * exactly `body.range`, which ends at the last committed body token), so scope resolution
 * used to fall through to the ENCLOSING scope and completion enumeration lost the loop's
 * control variables while the block was still open.
 *
 * Locked surface: `model.getCompletionsAt` — lexical completions enumerate the scope chain
 * that ReferenceQueries resolves from the free query position (there is no AST node at a
 * trailing position, so node-anchored queries like getSymbolAt have nothing to resolve).
 *
 * - Numeric AND generic for loops must surface their control/iterator variables at the
 *   trailing position before `end`.
 * - Header positions keep enclosing-scope semantics: the control variable must NOT be
 *   offered there (see BinderLoopHeaderScopeTddTest for the binder-level lock).
 * - After a nested for's `end`, the outer variable surfaces while the inner one is gone.
 * - The promotion itself lives on the shared seam
 *   `BinderPositionQueries.scopeForFreePositionQuery` consumed by every free-position
 *   surface (ReferenceQueries, workspace facade, legacy adapters); the binder-level test
 *   locks that seam and the raw getScopeAt enclosing-scope behavior side by side.
 */
class BinderLoopTailSurfaceTddTest {

    @Test
    fun numericForControlVariableIsOfferedAtTrailingPositionBeforeEnd() {
        val source = """
            for i = 1, 10 do
                print(i)
            end
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        // Caret at the start of the `end` line (line 3, column 1): inside the for statement's
        // full range but after the body's end-exclusive range — the tail-of-body gap.
        val labels = model.getCompletionsAt(Position(3, 1)).map { it.label }

        assertTrue(
            "i" in labels,
            "the numeric for control variable must be offered at the tail-of-body position; " +
                "actual=$labels"
        )
    }

    @Test
    fun genericForIteratorVariablesAreOfferedAtTrailingPositionBeforeEnd() {
        val source = """
            for k, v in pairs(t) do
                print(k)
            end
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        val labels = model.getCompletionsAt(Position(3, 1)).map { it.label }

        assertTrue(
            "k" in labels && "v" in labels,
            "the generic for iterator variables must be offered at the tail-of-body position; " +
                "actual=$labels"
        )
    }

    @Test
    fun controlVariableIsNotOfferedAtHeaderPosition() {
        val source = """
            for i = 1, 10 do
                print(i)
            end
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        // Caret on the `10` limit expression in the header: header expressions evaluate in
        // the ENCLOSING scope, so the freshly declared control variable must not surface.
        val labels = model.getCompletionsAt(positionOf(source, "10")).map { it.label }

        assertFalse(
            "i" in labels,
            "the control variable must not be offered at a header position; actual=$labels"
        )
    }

    @Test
    fun nestedForTailPositionsScopeVariablesByTheirOwnLoopEnd() {
        val source = """
            for i = 1, 10 do
                for j = 1, 5 do
                    print(j)
                end
            end
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        // Caret before the INNER `end` (line 4, column 5): the inner loop's tail gap — both
        // the inner control variable and the outer one are still in scope.
        val innerTailLabels = model.getCompletionsAt(Position(4, 5)).map { it.label }
        assertTrue(
            "j" in innerTailLabels && "i" in innerTailLabels,
            "both loop variables must be offered before the inner `end`; actual=$innerTailLabels"
        )

        // Caret before the OUTER `end` (line 5, column 1): the inner loop has closed, so only
        // the outer control variable survives.
        val outerTailLabels = model.getCompletionsAt(Position(5, 1)).map { it.label }
        assertTrue(
            "i" in outerTailLabels,
            "the outer control variable must be offered before the outer `end`; " +
                "actual=$outerTailLabels"
        )
        assertFalse(
            "j" in outerTailLabels,
            "the inner control variable must be gone after the inner `end`; " +
                "actual=$outerTailLabels"
        )
    }

    @Test
    fun freePositionScopeSeamPromotesLoopTailWhileRawLookupStaysEnclosing() {
        val source = """
            for i = 1, 10 do
                print(i)
            end
        """.trimIndent()
        val chunk = LuaParser().parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val tailPosition = Position(3, 1)

        // The hoisted seam (BinderPositionQueries.scopeForFreePositionQuery) is the single
        // scope entry every free-position surface must share: completion enumeration
        // (ReferenceQueries), the workspace facade's local name/declaration walks, and the
        // legacy adapters. At the tail-of-body caret it must surface the loop scope ...
        val tailScope = assertNotNull(
            binder.positionQueries.scopeForFreePositionQuery(tailPosition),
            "the free-position seam must resolve a scope at the tail-of-body caret"
        )
        assertEquals(
            ScopeKind.LOOP,
            tailScope.kind,
            "the free-position seam must promote the loop scope at the tail-of-body caret"
        )

        // ... while the raw positional lookup keeps its enclosing-scope semantics, so
        // node-anchored lookups (hover/type on REAL nodes) are unaffected by the promotion.
        assertEquals(
            binder.scopeGraph.rootScope,
            binder.positionQueries.getScopeAt(tailPosition),
            "the raw positional lookup must still resolve the enclosing scope at the tail caret"
        )

        // Header positions keep enclosing-scope semantics through the seam as well — Lua
        // header expressions must not see the freshly declared control variables.
        assertEquals(
            binder.scopeGraph.rootScope,
            binder.positionQueries.scopeForFreePositionQuery(positionOf(source, "10")),
            "the free-position seam must keep header positions in the enclosing scope"
        )
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var fromIndex = 0
        var matchIndex = -1
        repeat(occurrence) {
            matchIndex = source.indexOf(needle, fromIndex)
            require(matchIndex >= 0) { "Needle '$needle' occurrence $occurrence not found" }
            fromIndex = matchIndex + needle.length
        }
        val lineStart = source.lastIndexOf('\n', startIndex = matchIndex).let { if (it < 0) 0 else it + 1 }
        val line = source.substring(0, matchIndex).count { it == '\n' } + 1
        return Position(line, matchIndex - lineStart + 1)
    }
}
