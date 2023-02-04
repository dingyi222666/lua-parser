package io.github.dingyi222666.lua.parser.ast.node

import com.google.gson.annotations.SerializedName
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

        class ExpressionNodeSupport : ExpressionNode, ASTNode()
    }
}


abstract class ASTNode : BaseASTNode {
    @delegate:Transient
    override var parent: BaseASTNode by Delegates.notNull()

    @SerializedName("location")
    override var range = Range.EMPTY
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
    companion object {
        val EMPTY = Position(1, 0)
    }
}