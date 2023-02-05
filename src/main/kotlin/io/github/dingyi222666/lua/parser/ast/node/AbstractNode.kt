package io.github.dingyi222666.lua.parser.ast.node

import com.google.gson.annotations.SerializedName
import io.github.dingyi222666.lua.parser.ast.visitor.ASTVisitor
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

interface StatementNode : BaseASTNode

interface ExpressionNode : BaseASTNode {
    companion object {
        val EMPTY = ExpressionNodeSupport()

        class ExpressionNodeSupport : ExpressionNode, ASTNode() {
            override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
                visitor.visitExpressionNode(this, value)
            }
        }
    }
}


abstract class ASTNode : BaseASTNode {
    @delegate:Transient
    override var parent: BaseASTNode by Delegates.notNull()

    @SerializedName("location")
    override var range = Range.EMPTY

    abstract fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T)
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
) {
    operator fun compareTo(position: Position): Int {
        if (position.line > line) {
            return 1
        }
        if (position.line < line) {
            return -1
        }
        if (position.column > column) {
            return 1
        }
        if (position.column < column) {
            return -1
        }
        return 0
    }

    companion object {
        val EMPTY = Position(1, 0)
    }
}