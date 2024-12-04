package io.github.dingyi222666.luaparser.parser.ast.node

import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
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

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitLocalStatement(this, value)
    }

    override fun clone(): LocalStatement {
        return LocalStatement().also { stat ->
            variables.forEach {
                stat.variables.add(it.clone())
            }
            init.forEach {
                stat.init.add(it.clone())
            }
        }
    }
}


class AssignmentStatement : StatementNode, ASTNode() {

    val variables: MutableList<ExpressionNode> = mutableListOf()
    val init: MutableList<ExpressionNode> = mutableListOf()
    override fun toString(): String {
        return "AssignmentStatement(variables=$variables, init=$init)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitAssignmentStatement(this, value)
    }

    override fun clone(): AssignmentStatement {
        return AssignmentStatement().also { stat ->
            variables.forEach {
                stat.variables.add(it.clone())
            }
            init.forEach {
                stat.init.add(it.clone())
            }
        }
    }
}


class ForGenericStatement : StatementNode, ASTNode() {
    val variables: MutableList<Identifier> = mutableListOf()
    val iterators: MutableList<ExpressionNode> = mutableListOf()
    lateinit var body: BlockNode

    override fun toString(): String {
        return "ForGenericStatement(variables=$variables, iterators=$iterators, body=$body)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitForGenericStatement(this, value)
    }

    override fun clone(): ForGenericStatement {
        return ForGenericStatement().also { stat ->
            variables.forEach {
                stat.variables.add(it.clone())
            }
            iterators.forEach {
                stat.iterators.add(it.clone())
            }
            stat.body = body.clone()
        }
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

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitForNumericStatement(this, value)
    }

    override fun clone(): ForNumericStatement {
        return ForNumericStatement().also { stat ->
            stat.variable = variable.clone()
            stat.start = start.clone()
            stat.end = end.clone()
            stat.step = step?.clone()
            stat.body = body.clone()
        }
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

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitCallStatement(this, value)
    }

    override fun clone(): CallStatement {
        return CallStatement().also { stat ->
            stat.expression = expression.clone()
        }
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

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitWhileStatement(this, value)
    }

    override fun clone(): WhileStatement {
        return WhileStatement().also { stat ->
            stat.condition = condition.clone()
            stat.body = body.clone()
        }
    }
}

class RepeatStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var body: BlockNode
    override fun toString(): String {
        return "RepeatStatement(condition=$condition, body=$body)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitRepeatStatement(this, value)
    }

    override fun clone(): RepeatStatement {
        return RepeatStatement().also { stat ->
            stat.condition = condition.clone()
            stat.body = body.clone()
        }
    }
}


class BreakStatement : StatementNode, ASTNode() {
    override fun toString(): String {
        return "BreakStatement()"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitBreakStatement(this, value)
    }

    override fun clone(): BreakStatement {
        return BreakStatement()
    }
}

class LabelStatement : StatementNode, ASTNode() {
    lateinit var identifier: Identifier
    override fun toString(): String {
        return "LabelStatement(identifier=$identifier)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitLabelStatement(this, value)
    }

    override fun clone(): LabelStatement {
        return LabelStatement().also { stat ->
            stat.identifier = identifier.clone()
        }
    }
}

class GotoStatement : StatementNode, ASTNode() {
    lateinit var identifier: Identifier
    override fun toString(): String {
        return "GotoStatement(identifier=$identifier)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitGotoStatement(this, value)
    }

    override fun clone(): GotoStatement {
        return GotoStatement().also { stat ->
            stat.identifier = identifier.clone()
        }
    }
}


class ContinueStatement : StatementNode, ASTNode() {
    override fun toString(): String {
        return "ContinueStatement()"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitContinueStatement(this, value)
    }

    override fun clone(): ContinueStatement {
        return ContinueStatement()
    }
}

class ReturnStatement : StatementNode, ASTNode() {
    val arguments = mutableListOf<ExpressionNode>()

    override fun toString(): String {
        return "ReturnStatement(arguments=$arguments)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitReturnStatement(this, value)
    }

    override fun clone(): ReturnStatement {
        return ReturnStatement().also { stat ->
            arguments.forEach {
                stat.arguments.add(it.clone())
            }
        }
    }
}

class WhenStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var ifCause: StatementNode
    var elseCause: StatementNode? = null

    override fun toString(): String {
        return "WhenStatement(condition=$condition, ifCause=$ifCause, elseCause=$elseCause)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitWhenStatement(this, value)
    }

    override fun clone(): WhenStatement {
        return WhenStatement().also { stat ->
            stat.condition = condition.clone()
            stat.ifCause = ifCause.clone()
            stat.elseCause = elseCause?.clone()
        }
    }
}

class SwitchStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    val causes = mutableListOf<AbsSwitchCause>()

    override fun toString(): String {
        return "SwitchStatement(condition=$condition, causes=$causes)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitSwitchStatement(this, value)
    }


    override fun clone(): SwitchStatement {
        return SwitchStatement().also { stat ->
            stat.condition = condition.clone()
            stat.causes.forEach {
                stat.causes.add(it.clone())
            }
        }
    }
}

abstract class AbsSwitchCause : StatementNode, ASTNode() {
    abstract override fun clone(): AbsSwitchCause
}

class CaseCause : AbsSwitchCause() {
    val conditions = mutableListOf<ExpressionNode>()
    lateinit var body: BlockNode

    override fun toString(): String {
        return "CaseCause(conditions=$conditions, body=$body)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitCaseCause(this, value)
    }

    override fun clone(): CaseCause {
        return CaseCause().also { stat ->
            conditions.forEach {
                stat.conditions.add(it.clone())
            }
            stat.body = body.clone()
        }
    }
}

class DefaultCause : AbsSwitchCause() {
    lateinit var body: BlockNode

    override fun toString(): String {
        return "DefaultCause(body=$body)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitDefaultCause(this, value)
    }

    override fun clone(): DefaultCause {
        return DefaultCause().also { stat ->
            stat.body = body.clone()
        }
    }
}

open class IfClause : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var body: BlockNode

    override fun toString(): String {
        return "IfClause(condition=$condition, body=$body)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitIfClause(this, value)
    }

    override fun clone(): IfClause {
        return IfClause().also { stat ->
            stat.condition = condition.clone()
            stat.body = body.clone()
        }
    }
}


class ElseIfClause : IfClause() {
    override fun toString(): String {
        return "ElseIfClause(condition=$condition, body=$body)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitElseIfClause(this, value)
    }
}

class ElseClause : IfClause() {
    override fun toString(): String {
        return "ElseClause(body=$body)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitElseClause(this, value)
    }
}



open class TableKey : ExpressionNode, ASTNode() {
    lateinit var key: ExpressionNode
    lateinit var value: ExpressionNode

    override fun toString(): String {
        return "TableKey(key=$key, value=$value)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitTableKey(this, value)
    }

    override fun clone(): TableKey {
        return TableKey().also {
            it.key = key.clone()
            it.value = value.clone()
        }
    }
}

open class TableKeyString : TableKey() {
    override fun toString(): String {
        return "TableKeyString(key=$key, value=$value)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitTableKeyString(this, value)
    }
}


class IfStatement : StatementNode, ASTNode() {
    val causes = mutableListOf<IfClause>()

    override fun toString(): String {
        return "IfStatement(causes=$causes)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitIfStatement(this, value)
    }

    override fun clone(): IfStatement {
        return IfStatement().also { stat ->
            stat.causes.forEach {
                stat.causes.add(it.clone())
            }
        }
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

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitDoStatement(this, value)
    }

    override fun clone(): DoStatement {
        return DoStatement().also { stat ->
            stat.body = body.clone()
        }
    }
}

class CommentStatement : StatementNode, ASTNode() {
    var comment by Delegates.notNull<String>()

    var isDocComment = false

    override fun toString(): String {
        return "CommentStatement(comment=$comment)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitCommentStatement(this, value)
    }

    override fun clone(): CommentStatement {
        return CommentStatement().also { stat ->
            stat.comment = comment
        }
    }
}
