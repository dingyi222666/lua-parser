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

            override fun clone(): ExpressionNode {
                return EMPTY
            }
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
}

data class Range(
    var start: Position,
    var end: Position
) {
    companion object {
        val EMPTY = Range(Position.EMPTY, Position.EMPTY)
    }

}

data class Position(
    val line: Int,
    val column: Int
) : Comparable<Position> {
    override operator fun compareTo(other: Position): Int {
        if (other.line > line) {
            return other.line - line
        }
        if (other.line < line) {
            return other.line - line
        }
        if (other.column > column) {
            return 1
        }
        if (other.column < column) {
            return -1
        }
        return 0
    }

    companion object {
        val EMPTY = Position(1, 1)
    }

    override fun toString(): String {
        return "($line, $column)"
    }
}