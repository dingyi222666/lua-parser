package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Range

/**
 * One shared write-target/access-path descent for [DocumentFactsCollector] (require
 * alias/module-environment tracking) and [ModuleExportCollector] (export write
 * anchors): a member/index chain rooted at a bare identifier whose index keys are
 * string constants. Each consumer projects the neutral segments onto its own result
 * shape (facts keep the Member/Index split; the export collector adds the method
 * flag and per-segment ranges).
 */
internal data class AccessPathSegments(
    val rootIdentifier: String,
    val segments: List<String>,
    val segmentRanges: List<Range?>,
    /** The outermost descent step was a colon member access (`a:b`). */
    val lastWasMethodAccess: Boolean
)

internal fun extractAccessPathSegments(expression: ExpressionNode): AccessPathSegments? {
    return when (expression) {
        is Identifier ->
            AccessPathSegments(expression.name, emptyList(), emptyList(), lastWasMethodAccess = false)

        is MemberExpression -> {
            val base = extractAccessPathSegments(expression.base) ?: return null
            AccessPathSegments(
                rootIdentifier = base.rootIdentifier,
                segments = base.segments + expression.identifier.name,
                segmentRanges = base.segmentRanges + expression.identifier.range,
                lastWasMethodAccess = expression.indexer == ":"
            )
        }

        is IndexExpression -> {
            val key = (expression.index as? ConstantNode)
                ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                ?.stringOf()
                ?: return null
            val base = extractAccessPathSegments(expression.base) ?: return null
            AccessPathSegments(
                rootIdentifier = base.rootIdentifier,
                segments = base.segments + key,
                segmentRanges = base.segmentRanges + expression.index.range,
                lastWasMethodAccess = false
            )
        }

        else -> null
    }
}
