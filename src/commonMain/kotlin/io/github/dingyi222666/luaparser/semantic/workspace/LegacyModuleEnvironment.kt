package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type

data class LegacyModuleEnvironment(
    val segments: List<Segment> = emptyList()
) {
    data class Segment(
        val moduleName: String,
        val mode: ModuleEnvironmentMode,
        val hasSeeAllFallback: Boolean,
        val range: Range,
        val triggerRange: Range,
        val bindings: Map<String, Type>
    )

    fun segmentAt(position: Position): Segment? =
        segments.firstOrNull { segment ->
            positionAtOrAfter(position, segment.range.start) && positionBefore(position, segment.range.end)
        }

    companion object {
        val EMPTY = LegacyModuleEnvironment()
    }
}

internal fun positionAtOrAfter(left: Position, right: Position): Boolean =
    left.line > right.line || (left.line == right.line && left.column >= right.column)

internal fun positionBefore(left: Position, right: Position): Boolean =
    left.line < right.line || (left.line == right.line && left.column < right.column)

internal fun legacyEnvironmentBindings(moduleName: String): Map<String, Type> {
    val packageName = moduleName.substringBeforeLast('.', missingDelimiterValue = "")
    val packagePrefix = if (packageName.isEmpty()) "" else "$packageName."

    return mapOf(
        "_M" to ModuleType(moduleName = moduleName),
        "_NAME" to literalStringType(moduleName),
        "_PACKAGE" to literalStringType(packagePrefix),
        "..." to literalStringType(moduleName)
    )
}
