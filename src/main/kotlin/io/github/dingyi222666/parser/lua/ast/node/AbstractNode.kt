package io.github.dingyi222666.parser.lua.ast.node

/**
 * @author: dingyi
 * @date: 2021/10/20 11:39
 * @description:
 **/

abstract class StatementNode : ASTNode() {
}

abstract class ExpressionNode : ASTNode() {
}

abstract class ASTNode() {
    lateinit var parent: ASTNode
}