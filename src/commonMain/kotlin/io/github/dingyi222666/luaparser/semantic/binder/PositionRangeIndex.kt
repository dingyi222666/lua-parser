package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range

class PositionRangeIndex<T>(entries: List<Entry<T>>) {

    data class Entry<T>(
        val range: Range?,
        val payload: T,
        val stableOrder: Int
    )

    private data class IndexedEntry<T>(
        val range: Range,
        val payload: T,
        val stableOrder: Int
    )

    private val entries: List<IndexedEntry<T>> = entries
        .mapNotNull { entry ->
            val range = entry.range ?: return@mapNotNull null
            if (!isValidNonEmptyRange(range)) {
                return@mapNotNull null
            }
            IndexedEntry(range = range, payload = entry.payload, stableOrder = entry.stableOrder)
        }
        .sortedWith(
            compareBy<IndexedEntry<T>>(
                { it.range.start.line },
                { it.range.start.column },
                { it.stableOrder }
            ).thenBy { it.range.end.line }
                .thenBy { it.range.end.column }
        )

    // A long enclosing range must not keep every preceding short range alive.
    // Each midpoint stores only its balanced subtree's greatest end, so expired
    // sibling subtrees can be skipped even when the root scope covers the file.
    private val subtreeMaxEnds: List<Position?> = MutableList<Position?>(this.entries.size) { null }.also { ends ->
        fun build(from: Int, until: Int): Position? {
            if (from >= until) return null
            val middle = (from + until) ushr 1
            var maximum = this.entries[middle].range.end
            for (end in listOfNotNull(build(from, middle), build(middle + 1, until))) {
                if (comparePositions(end, maximum) > 0) maximum = end
            }
            ends[middle] = maximum
            return maximum
        }
        build(0, this.entries.size)
    }

    fun query(position: Position): List<T> = matchingEntries(position).map { it.payload }

    fun queryEntries(position: Position): List<Entry<T>> = matchingEntries(position).map { entry ->
        Entry(range = entry.range, payload = entry.payload, stableOrder = entry.stableOrder)
    }

    private fun matchingEntries(position: Position): List<IndexedEntry<T>> {
        val matches = mutableListOf<IndexedEntry<T>>()
        fun collect(from: Int, until: Int) {
            if (from >= until || comparePositions(entries[from].range.start, position) > 0) return
            val middle = (from + until) ushr 1
            if (comparePositions(subtreeMaxEnds[middle]!!, position) <= 0) return
            collect(from, middle)
            val entry = entries[middle]
            if (rangeContains(entry.range, position)) matches += entry
            if (comparePositions(entry.range.start, position) <= 0) collect(middle + 1, until)
        }
        collect(0, entries.size)

        return matches
            .sortedWith { a, b ->
                val specificity = compareRangeSpecificity(a.range, b.range)
                if (specificity != 0) {
                    specificity
                } else {
                    a.stableOrder.compareTo(b.stableOrder)
                }
            }
    }
}
