package io.github.dingyi222666.luaparser.parser.ast.node

import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import kotlin.properties.Delegates

private fun <T : BaseASTNode> T.copyStatementCloneMetadataFrom(source: BaseASTNode): T = also {
    range = source.range.copy()
    bad = source.bad
}

private fun <T : BaseASTNode> T.withStatementCloneParent(parentNode: BaseASTNode): T = also {
    parent = parentNode
}

private fun BlockNode.cloneStatementBlockFor(parentNode: BaseASTNode): BlockNode =
    clone().copyStatementCloneMetadataFrom(this).withStatementCloneParent(parentNode).also { block ->
        block.statements.forEach { it.parent = block }
        block.returnStatement?.parent = block
    }

private fun <T : IfClause> T.copyIfClauseFrom(source: IfClause, includeCondition: Boolean = true): T =
    copyStatementCloneMetadataFrom(source).also { clause ->
        if (includeCondition) {
            clause.condition = source.condition.clone().withStatementCloneParent(clause)
        }
        clause.body = source.body.cloneStatementBlockFor(clause)
    }

private fun <T : TableKey> T.copyTableKeyFrom(source: TableKey): T =
    copyStatementCloneMetadataFrom(source).also { key ->
        key.key = source.key.clone().withStatementCloneParent(key)
        key.value = source.value.clone().withStatementCloneParent(key)
    }

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
        return LocalStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            variables.forEach {
                stat.variables.add(it.clone().withStatementCloneParent(stat))
            }
            init.forEach {
                stat.init.add(it.clone().withStatementCloneParent(stat))
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
        return AssignmentStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            variables.forEach {
                stat.variables.add(it.clone().withStatementCloneParent(stat))
            }
            init.forEach {
                stat.init.add(it.clone().withStatementCloneParent(stat))
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
        return ForGenericStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            variables.forEach {
                stat.variables.add(it.clone().withStatementCloneParent(stat))
            }
            iterators.forEach {
                stat.iterators.add(it.clone().withStatementCloneParent(stat))
            }
            stat.body = body.cloneStatementBlockFor(stat)
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
        return ForNumericStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.variable = variable.clone().withStatementCloneParent(stat)
            stat.start = start.clone().withStatementCloneParent(stat)
            stat.end = end.clone().withStatementCloneParent(stat)
            stat.step = step?.clone()?.withStatementCloneParent(stat)
            stat.body = body.cloneStatementBlockFor(stat)
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
        return CallStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.expression = expression.clone().withStatementCloneParent(stat)
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
        return WhileStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.condition = condition.clone().withStatementCloneParent(stat)
            stat.body = body.cloneStatementBlockFor(stat)
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
        return RepeatStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.condition = condition.clone().withStatementCloneParent(stat)
            stat.body = body.cloneStatementBlockFor(stat)
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
        return BreakStatement().copyStatementCloneMetadataFrom(this)
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
        return LabelStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.identifier = identifier.clone().withStatementCloneParent(stat)
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
        return GotoStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.identifier = identifier.clone().withStatementCloneParent(stat)
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
        return ContinueStatement().copyStatementCloneMetadataFrom(this)
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
        return ReturnStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            arguments.forEach {
                stat.arguments.add(it.clone().withStatementCloneParent(stat))
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
        return WhenStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.condition = condition.clone().withStatementCloneParent(stat)
            stat.ifCause = ifCause.clone().withStatementCloneParent(stat)
            stat.elseCause = elseCause?.clone()?.withStatementCloneParent(stat)
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
        return SwitchStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.condition = condition.clone().withStatementCloneParent(stat)
            causes.forEach {
                stat.causes.add(it.clone().withStatementCloneParent(stat))
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

    /**
     * AndroLua `case` allows an optional `then` after the condition list.
     * When true, the source included `then`; when false, the keyword was omitted.
     * Defaults to true so hand-built AST nodes keep the historical printer form.
     */
    var hasThen: Boolean = true

    override fun toString(): String {
        return "CaseCause(conditions=$conditions, body=$body, hasThen=$hasThen)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitCaseCause(this, value)
    }

    override fun clone(): CaseCause {
        return CaseCause().copyStatementCloneMetadataFrom(this).also { stat ->
            conditions.forEach {
                stat.conditions.add(it.clone().withStatementCloneParent(stat))
            }
            stat.body = body.cloneStatementBlockFor(stat)
            stat.hasThen = hasThen
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
        return DefaultCause().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.body = body.cloneStatementBlockFor(stat)
        }
    }
}

open class IfClause : StatementNode, ASTNode() {
    lateinit var condition: ExpressionNode
    lateinit var body: BlockNode

    protected fun hasInitializedCondition(): Boolean = this::condition.isInitialized

    override fun toString(): String {
        return "IfClause(condition=$condition, body=$body)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitIfClause(this, value)
    }

    override fun clone(): IfClause {
        return IfClause().copyIfClauseFrom(this)
    }
}


class ElseIfClause : IfClause() {
    override fun toString(): String {
        return "ElseIfClause(condition=$condition, body=$body)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitElseIfClause(this, value)
    }

    override fun clone(): ElseIfClause {
        return ElseIfClause().copyIfClauseFrom(this)
    }
}

class ElseClause : IfClause() {
    override fun toString(): String {
        return "ElseClause(body=$body)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitElseClause(this, value)
    }

    override fun clone(): ElseClause {
        return ElseClause().copyIfClauseFrom(this, includeCondition = hasInitializedCondition())
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
        return TableKey().copyTableKeyFrom(this)
    }
}

open class TableKeyString : TableKey() {
    override fun toString(): String {
        return "TableKeyString(key=$key, value=$value)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitTableKeyString(this, value)
    }

    override fun clone(): TableKeyString {
        return TableKeyString().copyTableKeyFrom(this)
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
        return IfStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            causes.forEach {
                stat.causes.add(it.clone().withStatementCloneParent(stat))
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
        return DoStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.body = body.cloneStatementBlockFor(stat)
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
        return CommentStatement().copyStatementCloneMetadataFrom(this).also { stat ->
            stat.comment = comment
            stat.isDocComment = isDocComment
        }
    }
}
