package io.github.dingyi222666.parser.lua.ast.node

import kotlin.properties.Delegates

/**
 * @author: dingyi
 * @date: 2021/10/7 10:23
 * @description:
 **/
class LocalStatement(
    val variables: MutableList<ASTNode> = mutableListOf(),
    val init: MutableList<ASTNode> = mutableListOf(),
) : StatementNode() {

    override fun toString(): String {
        return "LocalStatement(variables=$variables, init=$init)"
    }
}

/**
 * @author: dingyi
 * @date: 2021/10/9 14:58
 * @description:
 **/
class CallStatement : StatementNode() {
    var expression by Delegates.notNull<CallExpression>()
}

/**
 * @author: dingyi
 * @date: 2021/10/20 11:41
 * @description:
 **/
class WhileStatement : StatementNode() {
    var condition by Delegates.notNull<ASTNode>()
    var body by Delegates.notNull<BlockNode>()

    override fun toString(): String {
        return "WhileStatement(condition=$condition, body=$body)"
    }

}

/**
 * @author: dingyi
 * @date: 2021/10/8 20:08
 * @description:
 **/
class DoStatement : StatementNode() {

    var body by Delegates.notNull<BlockNode>()

}