package semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.binder.PositionRangeIndex
import kotlin.test.Test
import kotlin.test.assertEquals

class PositionRangeIndexTest {

    @Test
    fun returnsNoHitBeforeFirstRange() {
        val index = indexOf(range(1, 1, 1, 4) to "a")

        assertEquals(emptyList(), index.query(pos(1, 0)))
    }

    @Test
    fun returnsHitAtStart() {
        val index = indexOf(range(1, 1, 1, 4) to "a")

        assertEquals(listOf("a"), index.query(pos(1, 1)))
    }

    @Test
    fun doesNotReturnHitAtEnd() {
        val index = indexOf(range(1, 1, 1, 4) to "a")

        assertEquals(emptyList(), index.query(pos(1, 4)))
    }

    @Test
    fun returnsNestedIntervalsInSpecificityOrder() {
        val index = indexOf(
            range(1, 1, 1, 10) to "outer",
            range(1, 3, 1, 8) to "middle",
            range(1, 5, 1, 6) to "inner"
        )

        assertEquals(listOf("inner", "middle", "outer"), index.query(pos(1, 5)))
    }

    @Test
    fun handlesUnsortedInputDeterministically() {
        val index = PositionRangeIndex(
            listOf(
                PositionRangeIndex.Entry(range(1, 4, 1, 8), "later", 1),
                PositionRangeIndex.Entry(range(1, 1, 1, 10), "outer", 0),
                PositionRangeIndex.Entry(range(1, 4, 1, 8), "later-second", 2)
            )
        )

        assertEquals(listOf("later", "later-second", "outer"), index.query(pos(1, 5)))
    }

    @Test
    fun stopsWhenEarlierRangesCannotReachQueryPoint() {
        val index = indexOf(
            range(1, 1, 1, 2) to "too-early",
            range(1, 3, 1, 7) to "match",
            range(1, 8, 1, 10) to "after"
        )

        assertEquals(listOf("match"), index.query(pos(1, 5)))
    }

    @Test
    fun ignoresNullEmptyAndInvalidRanges() {
        val index = PositionRangeIndex(
            listOf(
                PositionRangeIndex.Entry<String>(null, "null", 0),
                PositionRangeIndex.Entry(range(1, 2, 1, 2), "empty", 1),
                PositionRangeIndex.Entry(range(1, 4, 1, 3), "invalid", 2),
                PositionRangeIndex.Entry(range(1, 1, 1, 5), "valid", 3)
            )
        )

        assertEquals(listOf("valid"), index.query(pos(1, 3)))
    }

    private fun indexOf(vararg entries: Pair<Range, String>): PositionRangeIndex<String> {
        return PositionRangeIndex(
            entries.mapIndexed { index, (range, payload) ->
                PositionRangeIndex.Entry(range, payload, index)
            }
        )
    }

    private fun pos(line: Int, column: Int): Position = Position(line, column)

    private fun range(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int
    ): Range = Range(pos(startLine, startColumn), pos(endLine, endColumn))
}
