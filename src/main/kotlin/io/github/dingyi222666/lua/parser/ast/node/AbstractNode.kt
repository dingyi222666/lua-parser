package io.github.dingyi222666.lua.parser.ast.node

import kotlin.properties.Delegates

/**
 * @author: dingyi
 * @date: 2021/10/20 11:39
 * @description:
 **/

interface BaseASTNode {
    var parent: ASTNode
    var location: Location
}

interface StatementNode : BaseASTNode

interface ExpressionNode : BaseASTNode

class StatementNodeSupport : StatementNode, ASTNode()

class ExpressionNodeSupport : ExpressionNode, ASTNode()


abstract class ASTNode : BaseASTNode {
    override var parent: ASTNode by Delegates.notNull()
    override var location: Location by Delegates.notNull()
}

data class Location(
    val line: Int,
    val column: Int
)