package io.github.dingyi222666.luaparser.parser.ast.node

import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import kotlin.properties.Delegates

private fun <T : IfClause> T.copyIfClauseFrom(source: IfClause, includeCondition: Boolean = true): T =
    copyCloneMetadataFrom(source).also { clause ->
        if (includeCondition) {
            clause.condition = source.condition.clone().withCloneParent(clause)
        }
        clause.body = source.body.cloneBlockFor(clause)
    }

private fun <T : TableKey> T.copyTableKeyFrom(source: TableKey): T =
    copyCloneMetadataFrom(source).also { key ->
        key.key = source.key.clone().withCloneParent(key)
        key.value = source.value.clone().withCloneParent(key)
    }

/**
 * @author: dingyi
 * @date: 2021/10/7 10:23
 * @description:
 **/
class LocalStatement : StatementNode, ASTNode() {

    val variables: MutableList<ExpressionNode> = mutableListOf()
    val init: MutableList<Identifier> = mutableListOf()
    override fun toString(): String = "LocalStatement(variables=$variables, init=$init)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitLocalStatement(this, value)
    }

    override fun clone(): LocalStatement =
        LocalStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.variables.addClonedExpressionsFrom(variables, stat)
            stat.init.addClonedExpressionsFrom(init, stat)
        }
}


class AssignmentStatement : StatementNode, ASTNode() {

    val variables: MutableList<ExpressionNode> = mutableListOf()
    val init: MutableList<ExpressionNode> = mutableListOf()
    override fun toString(): String = "AssignmentStatement(variables=$variables, init=$init)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitAssignmentStatement(this, value)
    }

    override fun clone(): AssignmentStatement =
        AssignmentStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.variables.addClonedExpressionsFrom(variables, stat)
            stat.init.addClonedExpressionsFrom(init, stat)
        }
}


class ForGenericStatement : StatementNode, ASTNode() {
    val variables: MutableList<Identifier> = mutableListOf()
    val iterators: MutableList<ExpressionNode> = mutableListOf()
    lateinit var body: BlockNode

    override fun toString(): String = "ForGenericStatement(variables=$variables, iterators=$iterators, body=$body)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitForGenericStatement(this, value)
    }

    override fun clone(): ForGenericStatement =
        ForGenericStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.variables.addClonedExpressionsFrom(variables, stat)
            stat.iterators.addClonedExpressionsFrom(iterators, stat)
            stat.body = body.cloneBlockFor(stat)
        }
}


class ForNumericStatement : StatementNode, ASTNode() {
    lateinit var variable: Identifier
    lateinit var start: ExpressionNode
    lateinit var end: ExpressionNode
    var step: ExpressionNode? = null
    lateinit var body: BlockNode
    override fun toString(): String = "ForNumericStatement(variable=$variable, start=$start, end=$end, step=$step, body=$body)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitForNumericStatement(this, value)
    }

    override fun clone(): ForNumericStatement =
        ForNumericStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.variable = variable.clone().withCloneParent(stat)
            stat.start = start.clone().withCloneParent(stat)
            stat.end = end.clone().withCloneParent(stat)
            stat.step = step?.clone()?.withCloneParent(stat)
            stat.body = body.cloneBlockFor(stat)
        }
}

/**
 * @author: dingyi
 * @date: 2021/10/9 14:58
 * @description:
 **/
class CallStatement : StatementNode, ASTNode() {
    lateinit var expression: CallExpression

    override fun toString(): String = "CallStatement(expression=$expression)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitCallStatement(this, value)
    }

    override fun clone(): CallStatement =
        CallStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.expression = expression.clone().withCloneParent(stat)
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

    override fun toString(): String = "WhileStatement(condition=$condition, body=$body)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitWhileStatement(this, value)
    }

    override fun clone(): WhileStatement =
        WhileStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.condition = condition.clone().withCloneParent(stat)
            stat.body = body.cloneBlockFor(stat)
        }
}

class RepeatStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var body: BlockNode
    override fun toString(): String = "RepeatStatement(condition=$condition, body=$body)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitRepeatStatement(this, value)
    }

    override fun clone(): RepeatStatement =
        RepeatStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.condition = condition.clone().withCloneParent(stat)
            stat.body = body.cloneBlockFor(stat)
        }
}


class BreakStatement : StatementNode, ASTNode() {
    override fun toString(): String = "BreakStatement()"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitBreakStatement(this, value)
    }

    override fun clone(): BreakStatement = BreakStatement().copyCloneMetadataFrom(this)
}

class LabelStatement : StatementNode, ASTNode() {
    lateinit var identifier: Identifier
    override fun toString(): String = "LabelStatement(identifier=$identifier)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitLabelStatement(this, value)
    }

    override fun clone(): LabelStatement =
        LabelStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.identifier = identifier.clone().withCloneParent(stat)
        }
}

class GotoStatement : StatementNode, ASTNode() {
    lateinit var identifier: Identifier
    override fun toString(): String = "GotoStatement(identifier=$identifier)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitGotoStatement(this, value)
    }

    override fun clone(): GotoStatement =
        GotoStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.identifier = identifier.clone().withCloneParent(stat)
        }
}


class ContinueStatement : StatementNode, ASTNode() {
    override fun toString(): String = "ContinueStatement()"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitContinueStatement(this, value)
    }

    override fun clone(): ContinueStatement = ContinueStatement().copyCloneMetadataFrom(this)
}

class ReturnStatement : StatementNode, ASTNode() {
    val arguments = mutableListOf<ExpressionNode>()

    override fun toString(): String = "ReturnStatement(arguments=$arguments)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitReturnStatement(this, value)
    }

    override fun clone(): ReturnStatement =
        ReturnStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.arguments.addClonedExpressionsFrom(arguments, stat)
        }
}

class WhenStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var ifCause: StatementNode
    var elseCause: StatementNode? = null

    override fun toString(): String = "WhenStatement(condition=$condition, ifCause=$ifCause, elseCause=$elseCause)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitWhenStatement(this, value)
    }

    override fun clone(): WhenStatement =
        WhenStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.condition = condition.clone().withCloneParent(stat)
            stat.ifCause = ifCause.clone().withCloneParent(stat)
            stat.elseCause = elseCause?.clone()?.withCloneParent(stat)
        }
}

class SwitchStatement : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    val causes = mutableListOf<AbsSwitchCause>()

    override fun toString(): String = "SwitchStatement(condition=$condition, causes=$causes)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitSwitchStatement(this, value)
    }


    override fun clone(): SwitchStatement =
        SwitchStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.condition = condition.clone().withCloneParent(stat)
            stat.causes.addClonedStatementsFrom(causes, stat)
        }
}

abstract class AbsSwitchCause : StatementNode, ASTNode() {
    abstract override fun clone(): AbsSwitchCause
}

class CaseCause : AbsSwitchCause() {
    val conditions = mutableListOf<ExpressionNode>()
    lateinit var body: BlockNode

    /**
     * AndroLua `case` allows an optional `then` after the condition list.
     * When true, the source included `then`; when false, the keyword was omitted.
     * Defaults to true so hand-built AST nodes keep the historical printer form.
     */
    var hasThen: Boolean = true

    override fun toString(): String = "CaseCause(conditions=$conditions, body=$body, hasThen=$hasThen)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitCaseCause(this, value)
    }

    override fun clone(): CaseCause =
        CaseCause().copyCloneMetadataFrom(this).also { stat ->
            stat.conditions.addClonedExpressionsFrom(conditions, stat)
            stat.body = body.cloneBlockFor(stat)
            stat.hasThen = hasThen
        }
}

class DefaultCause : AbsSwitchCause() {
    lateinit var body: BlockNode

    override fun toString(): String = "DefaultCause(body=$body)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitDefaultCause(this, value)
    }

    override fun clone(): DefaultCause =
        DefaultCause().copyCloneMetadataFrom(this).also { stat ->
            stat.body = body.cloneBlockFor(stat)
        }
}

open class IfClause : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var body: BlockNode

    protected fun hasInitializedCondition(): Boolean = this::condition.isInitialized

    override fun toString(): String = "IfClause(condition=$condition, body=$body)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitIfClause(this, value)
    }

    override fun clone(): IfClause = IfClause().copyIfClauseFrom(this)
}


class ElseIfClause : IfClause() {
    override fun toString(): String = "ElseIfClause(condition=$condition, body=$body)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitElseIfClause(this, value)
    }

    override fun clone(): ElseIfClause = ElseIfClause().copyIfClauseFrom(this)
}

class ElseClause : IfClause() {
    override fun toString(): String = "ElseClause(body=$body)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitElseClause(this, value)
    }

    override fun clone(): ElseClause =
        ElseClause().copyIfClauseFrom(this, includeCondition = hasInitializedCondition())
}



open class TableKey : ExpressionNode, ASTNode() {
    lateinit var key: ExpressionNode
    lateinit var value: ExpressionNode

    override fun toString(): String = "TableKey(key=$key, value=$value)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitTableKey(this, value)
    }

    override fun clone(): TableKey = TableKey().copyTableKeyFrom(this)
}

open class TableKeyString : TableKey() {
    override fun toString(): String = "TableKeyString(key=$key, value=$value)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitTableKeyString(this, value)
    }

    override fun clone(): TableKeyString = TableKeyString().copyTableKeyFrom(this)
}


class IfStatement : StatementNode, ASTNode() {
    val causes = mutableListOf<IfClause>()

    override fun toString(): String = "IfStatement(causes=$causes)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitIfStatement(this, value)
    }

    override fun clone(): IfStatement =
        IfStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.causes.addClonedStatementsFrom(causes, stat)
        }
}

/**
 * @author: dingyi
 * @date: 2021/10/8 20:08
 * @description:
 **/
class DoStatement : StatementNode, ASTNode() {
    var body by Delegates.notNull<BlockNode>()
    override fun toString(): String = "DoStatement(body=$body)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitDoStatement(this, value)
    }

    override fun clone(): DoStatement =
        DoStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.body = body.cloneBlockFor(stat)
        }
}

class CommentStatement : StatementNode, ASTNode() {
    var comment by Delegates.notNull<String>()

    var isDocComment = false

    override fun toString(): String = "CommentStatement(comment=$comment)"

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitCommentStatement(this, value)
    }

    override fun clone(): CommentStatement =
        CommentStatement().copyCloneMetadataFrom(this).also { stat ->
            stat.comment = comment
            stat.isDocComment = isDocComment
        }
}
