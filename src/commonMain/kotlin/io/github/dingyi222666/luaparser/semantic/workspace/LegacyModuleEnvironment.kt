package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.binder.positionLt
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

    // Position comparisons share the binder's PositionOrdering helpers (same line/column
    // order): `positionLt(position, start)` is false exactly when the caret sits at or after
    // the segment start, and before the segment end closes the half-open range.
    fun segmentAt(position: Position): Segment? =
        segments.firstOrNull { segment ->
            !positionLt(position, segment.range.start) && positionLt(position, segment.range.end)
        }

    companion object {
        val EMPTY = LegacyModuleEnvironment()
    }
}

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
