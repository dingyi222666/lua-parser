package io.github.dingyi222666.lua.parser.ast.node

import io.github.dingyi222666.lua.parser.ast.visitor.ASTVisitor
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

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitLocalStatement(this, value)
    }
}


class AssignmentStatement : StatementNode, ASTNode() {

    val variables: MutableList<ExpressionNode> = mutableListOf()
    val init: MutableList<ExpressionNode> = mutableListOf()
    override fun toString(): String {
        return "AssignmentStatement(variables=$variables, init=$init)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitAssignmentStatement(this, value)
    }
}


class ForGenericStatement : StatementNode, ASTNode() {
    val variables: MutableList<Identifier> = mutableListOf()
    val iterators: MutableList<ExpressionNode> = mutableListOf()
    lateinit var body: BlockNode

    override fun toString(): String {
        return "ForGenericStatement(variables=$variables, iterators=$iterators, body=$body)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitForGenericStatement(this, value)
    }
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

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitForNumericStatement(this, value)
    }
}

/**
 * @author: dingyi
 * @date: 2021/10/9 14:58
 * @description:
 **/
class CallStatement : StatementNode, ASTNode() {
    lateinit var expression: CallExpression

    override fun toString(): String {
        return "CallStatement(expression=$expression)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitCallStatement(this, value)
    }
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

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitWhileStatement(this, value)
    }
}

class RepeatStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var body: BlockNode
    override fun toString(): String {
        return "RepeatStatement(condition=$condition, body=$body)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitRepeatStatement(this, value)
    }
}


class BreakStatement : StatementNode, ASTNode() {
    override fun toString(): String {
        return "BreakStatement()"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitBreakStatement(this, value)
    }
}

class LabelStatement : StatementNode, ASTNode() {
    lateinit var identifier: Identifier
    override fun toString(): String {
        return "LabelStatement(identifier=$identifier)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitLabelStatement(this, value)
    }
}

class GotoStatement : StatementNode, ASTNode() {
    lateinit var identifier: Identifier
    override fun toString(): String {
        return "GotoStatement(identifier=$identifier)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitGotoStatement(this, value)
    }
}


class ContinueStatement : StatementNode, ASTNode() {
    override fun toString(): String {
        return "ContinueStatement()"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitContinueStatement(this, value)
    }
}

class ReturnStatement : StatementNode, ASTNode() {
    val arguments = mutableListOf<ExpressionNode>()

    override fun toString(): String {
        return "ReturnStatement(arguments=$arguments)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitReturnStatement(this, value)
    }
}

class WhenStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var ifCause: StatementNode
    var elseCause: StatementNode? = null

    override fun toString(): String {
        return "WhenStatement(condition=$condition, ifCause=$ifCause, elseCause=$elseCause)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitWhenStatement(this, value)
    }
}

class SwitchStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    val causes = mutableListOf<AbsSwitchCause>()

    override fun toString(): String {
        return "SwitchStatement(condition=$condition, causes=$causes)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitSwitchStatement(this, value)
    }
}

abstract class AbsSwitchCause : StatementNode, ASTNode()

class CaseCause : AbsSwitchCause() {
    val conditions = mutableListOf<ExpressionNode>()
    lateinit var body: BlockNode

    override fun toString(): String {
        return "CaseCause(conditions=$conditions, body=$body)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitCaseCause(this, value)
    }
}

class DefaultCause : AbsSwitchCause() {
    lateinit var body: BlockNode

    override fun toString(): String {
        return "DefaultCause(body=$body)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitDefaultCause(this, value)
    }
}

open class IfClause : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var body: BlockNode

    override fun toString(): String {
        return "IfClause(condition=$condition, body=$body)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitIfClause(this, value)
    }
}


class ElseIfClause : IfClause() {
    override fun toString(): String {
        return "ElseIfClause(condition=$condition, body=$body)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitElseIfClause(this, value)
    }
}

class ElseClause : IfClause() {
    override fun toString(): String {
        return "ElseClause(body=$body)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitElseClause(this, value)
    }
}


open class TableKey : ExpressionNode, ASTNode() {
    var key: ExpressionNode? = null
    lateinit var value: ExpressionNode

    override fun toString(): String {
        return "TableKey(key=$key, value=$value)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitTableKey(this, value)
    }
}

open class TableKeyString : TableKey() {
    override fun toString(): String {
        return "TableKeyString(key=$key, value=$value)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitTableKeyString(this, value)
    }
}


class IfStatement : StatementNode, ASTNode() {
    val causes = mutableListOf<IfClause>()

    override fun toString(): String {
        return "IfStatement(causes=$causes)"
    }

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitIfStatement(this, value)
    }
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

    override fun <R, T> accept(visitor: ASTVisitor<R, T>, value: T) {
        visitor.visitDoStatement(this, value)
    }
}