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

            override fun clone(): ExpressionNode {
                return EMPTY
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

data class Range(
    var start: Position,
    var end: Position
) {
    companion object {
        val EMPTY = Range(Position.EMPTY, Position.EMPTY)
    }

    override fun toString(): String {
        return "($start, $end)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Range) return false
        if (start != other.start) return false
        if (end != other.end) return false
        return true
    }

    override fun hashCode(): Int {
        var result = start.hashCode()
        result = 31 * result + end.hashCode()
        return result
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Position) return false

        if (line != other.line) return false
        if (column != other.column) return false

        return true
    }

    override fun toString(): String {
        return "[$line, $column]"
    }

    override fun hashCode(): Int {
        var result = line
        result = 31 * result + column
        return result
    }
}