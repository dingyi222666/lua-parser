package io.github.dingyi222666.luaparser.parser.ast.node

import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import kotlin.jvm.Transient
import kotlin.properties.Delegates

/**
 * @author: dingyi
 * @date: 2021/10/20 11:39
 * @description:
 **/

interface BaseASTNode {
    var parent: BaseASTNode
    var range: Range
    var bad: Boolean
}

interface StatementNode : BaseASTNode {
    fun clone(): StatementNode
}

interface ExpressionNode : BaseASTNode {
    companion object {
        val EMPTY = ExpressionNodeSupport()

        class ExpressionNodeSupport : ExpressionNode, ASTNode() {
            override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
                visitor.visitExpressionNode(this, value)
            }

            override fun clone(): ExpressionNode = ExpressionNodeSupport().also {
                it.range = range.copy()
                it.bad = bad
            }

            override var bad = false
        }
    }

    fun clone(): ExpressionNode
}


abstract class ASTNode : BaseASTNode {
    @delegate:Transient
    override var parent: BaseASTNode by Delegates.notNull()

    override var range = Range.EMPTY

    abstract fun <T> accept(visitor: ASTVisitor<T>, value: T)

    abstract fun clone(): BaseASTNode

    override var bad = false
}

/**
 * Shared clone plumbing (previously duplicated file-privately in blockNode.kt,
 * statementNode.kt and expressionNode.kt): copy range/bad metadata from the source
 * node, relink a cloned node under its new parent, and clone body blocks.
 */
internal fun <T : BaseASTNode> T.copyCloneMetadataFrom(source: BaseASTNode): T = also {
    range = source.range.copy()
    bad = source.bad
}

internal fun <T : BaseASTNode> T.withCloneParent(parentNode: BaseASTNode): T = also {
    parent = parentNode
}

internal fun BlockNode.cloneBlockFor(parentNode: BaseASTNode): BlockNode =
    clone().copyCloneMetadataFrom(this).withCloneParent(parentNode).also { block ->
        block.statements.forEach { it.parent = block }
        block.returnStatement?.parent = block
    }

/**
 * Shared list-clone step: append deep clones of [source] to this list, copying
 * range/bad metadata and reparenting each clone under [parentNode]. Replaces the
 * per-node `copy.forEach { copy.add(it.clone().withCloneParent(copy)) }` pattern
 * (the cast is safe: clone() always returns the source node's runtime type).
 */
@Suppress("UNCHECKED_CAST")
internal fun <E : ExpressionNode> MutableList<E>.addClonedExpressionsFrom(
    source: List<ExpressionNode>,
    parentNode: BaseASTNode
) {
    for (element in source) {
        add(element.clone().withCloneParent(parentNode) as E)
    }
}

/** [StatementNode]-list variant of [addClonedExpressionsFrom]. */
@Suppress("UNCHECKED_CAST")
internal fun <E : StatementNode> MutableList<E>.addClonedStatementsFrom(
    source: List<StatementNode>,
    parentNode: BaseASTNode
) {
    for (element in source) {
        add(element.clone().withCloneParent(parentNode) as E)
    }
}

data class Range(
    var start: Position,
    var end: Position
) {
    companion object {
        val EMPTY = Range(Position.EMPTY, Position.EMPTY)
    }

    override fun toString(): String = "($start, $end)"

    // equals/hashCode are the data class-generated ones (same field order / 31-multiplication).
}

data class Position(
    val line: Int,
    val column: Int
) : Comparable<Position> {
    override operator fun compareTo(other: Position): Int {
        if (other.line != line) return other.line - line
        return column.compareTo(other.column)
    }

    companion object {
        val EMPTY = Position(1, 1)
    }

    override fun toString(): String = "[$line, $column]"

    // equals/hashCode are the data class-generated ones (same field order / 31-multiplication).
}
