package io.github.dingyi222666.lua.parser.ast.node

import kotlin.properties.Delegates

/**
 * @author: dingyi
 * @date: 2021/10/7 10:23
 * @description:
 **/
class LocalStatement : StatementNode, ASTNode() {

    val variables: MutableList<ExpressionNode> = mutableListOf()
    val init: MutableList<Identifier> = mutableListOf()
    override fun toString(): String {
        return "LocalStatement(variables=$variables, init=$init)"
    }
}


class AssignmentStatement : StatementNode, ASTNode() {

    val variables: MutableList<ExpressionNode> = mutableListOf()
    val init: MutableList<ExpressionNode> = mutableListOf()
    override fun toString(): String {
        return "AssignmentStatement(variables=$variables, init=$init)"
    }
}


class ForGenericStatement : StatementNode, ASTNode() {
    val variables: MutableList<Identifier> = mutableListOf()
    val iterators: MutableList<ExpressionNode> = mutableListOf()
    lateinit var body: BlockNode
}


class ForNumericStatement : StatementNode, ASTNode() {
    lateinit var variable: Identifier
    lateinit var start: ExpressionNode
    lateinit var end: ExpressionNode
    var step: ExpressionNode? = null
    lateinit var body: BlockNode
    override fun toString(): String {
        return "ForNumericStatement(variable=$variable, start=$start, end=$end, step=$step, body=$body)"
    }
}

/**
 * @author: dingyi
 * @date: 2021/10/9 14:58
 * @description:
 **/
class CallStatement : StatementNode, ASTNode() {
    lateinit var expression: CallExpression
}

/**
 * @author: dingyi
 * @date: 2021/10/20 11:41
 * @description:
 **/
class WhileStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var body: BlockNode

    override fun toString(): String {
        return "WhileStatement(condition=$condition, body=$body)"
    }
}

class RepeatStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var body: BlockNode
    override fun toString(): String {
        return "RepeatStatement(condition=$condition, body=$body)"
    }
}


class BreakStatement : StatementNode, ASTNode() {
    override fun toString(): String {
        return "BreakStatement()"
    }
}

class LabelStatement : StatementNode, ASTNode() {
    lateinit var identifier: Identifier
    override fun toString(): String {
        return "LabelStatement(identifier=$identifier)"
    }
}

class GotoStatement : StatementNode, ASTNode() {
    lateinit var identifier: Identifier
    override fun toString(): String {
        return "GotoStatement(identifier=$identifier)"
    }
}


class ContinueStatement : StatementNode, ASTNode() {
    override fun toString(): String {
        return "ContinueStatement()"
    }
}

class ReturnStatement : StatementNode, ASTNode() {
    val arguments = mutableListOf<ExpressionNode>()
}

class WhenStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var ifCause: StatementNode
    var elseCause: StatementNode? = null
}

class SwitchStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    val causes = mutableListOf<AbsSwitchCause>()
}

open class AbsSwitchCause : StatementNode, ASTNode()
class CaseCause : AbsSwitchCause() {
    val conditions = mutableListOf<ExpressionNode>()
    lateinit var body: BlockNode
}

class DefaultCause : AbsSwitchCause() {
    lateinit var body: BlockNode
}

open class IfClause : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var body: BlockNode
}


class ElseIfClause : IfClause()

class ElseClause : IfClause()

open class TableKey : StatementNode, ASTNode() {
    var key: ExpressionNode? = null
    lateinit var value: ExpressionNode
}

open class TableKeyString : TableKey()


class IfStatement : StatementNode, ASTNode() {
    val causes = mutableListOf<IfClause>()
}

/**
 * @author: dingyi
 * @date: 2021/10/8 20:08
 * @description:
 **/
class DoStatement : StatementNode, ASTNode() {
    var body by Delegates.notNull<BlockNode>()
    override fun toString(): String {
        return "DoStatement(body=$body)"
    }
}