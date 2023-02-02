package io.github.dingyi222666.lua.parser.ast.node

import kotlin.properties.Delegates

/**
 * @author: dingyi
 * @date: 2021/10/7 10:23
 * @description:
 **/
class LocalStatement(
    val variables: MutableList<ExpressionNode> = mutableListOf(),
    val init: MutableList<Identifier> = mutableListOf(),
) : StatementNode by StatementNodeSupport() {

    override fun toString(): String {
        return "LocalStatement(variables=$variables, init=$init)"
    }
}

/**
 * @author: dingyi
 * @date: 2021/10/9 14:58
 * @description:
 **/
class CallStatement : StatementNode by StatementNodeSupport() {
    lateinit var expression: CallExpression
}

/**
 * @author: dingyi
 * @date: 2021/10/20 11:41
 * @description:
 **/
class WhileStatement : StatementNode by StatementNodeSupport() {
    lateinit var condition: ASTNode
    lateinit var body: BlockNode

    override fun toString(): String {
        return "WhileStatement(condition=$condition, body=$body)"
    }

}


/**
 * @author: dingyi
 * @date: 2021/10/8 20:08
 * @description:
 **/
class DoStatement : StatementNode by StatementNodeSupport() {

    var body by Delegates.notNull<BlockNode>()
    override fun toString(): String {
        return "DoStatement(body=$body)"
    }


}