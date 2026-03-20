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

    private val prefixMaxEnds: List<Position> = buildList(entries.size) {
        var currentMax: Position? = null
        for (entry in this@PositionRangeIndex.entries) {
            currentMax = when {
                currentMax == null -> entry.range.end
                comparePositions(entry.range.end, currentMax!!) > 0 -> entry.range.end
                else -> currentMax
            }
            add(currentMax!!)
        }
    }

    fun query(position: Position): List<T> = queryEntries(position).map { it.payload }

    fun queryEntries(position: Position): List<Entry<T>> {
        val lastCandidateIndex = findLastCandidateIndex(position)
        if (lastCandidateIndex < 0) {
            return emptyList()
        }

        val matches = mutableListOf<IndexedEntry<T>>()
        var index = lastCandidateIndex
        while (index >= 0 && comparePositions(prefixMaxEnds[index], position) > 0) {
            val entry = entries[index]
            if (rangeContains(entry.range, position)) {
                matches += entry
            }
            index--
        }

        return matches
            .sortedWith { a, b ->
                val specificity = compareRangeSpecificity(a.range, b.range)
                if (specificity != 0) {
                    specificity
                } else {
                    a.stableOrder.compareTo(b.stableOrder)
                }
            }
            .map { entry ->
                Entry(range = entry.range, payload = entry.payload, stableOrder = entry.stableOrder)
            }
    }

    private fun findLastCandidateIndex(position: Position): Int {
        var low = 0
        var high = entries.lastIndex
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (comparePositions(entries[mid].range.start, position) <= 0) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return result
    }
}
