package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range

internal fun comparePositions(a: Position, b: Position): Int {
    val lineComparison = a.line.compareTo(b.line)
    if (lineComparison != 0) {
        return lineComparison
    }
    return a.column.compareTo(b.column)
}

internal fun positionLte(a: Position, b: Position): Boolean = comparePositions(a, b) <= 0

internal fun positionLt(a: Position, b: Position): Boolean = comparePositions(a, b) < 0

internal fun isValidNonEmptyRange(range: Range): Boolean = positionLt(range.start, range.end)

internal fun rangeContains(range: Range, position: Position): Boolean {
    if (!isValidNonEmptyRange(range)) {
        return false
    }
    return positionLte(range.start, position) && positionLt(position, range.end)
}

internal fun compareRangeSpecificity(a: Range, b: Range): Int {
    val startComparison = comparePositions(b.start, a.start)
    if (startComparison != 0) {
        return startComparison
    }

    val endComparison = comparePositions(a.end, b.end)
    if (endComparison != 0) {
        return endComparison
    }

    return 0
}
