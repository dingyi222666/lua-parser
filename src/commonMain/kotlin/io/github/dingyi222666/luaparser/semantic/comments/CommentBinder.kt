package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement

class CommentBinder {

    fun bind(chunk: ChunkNode, collectedBlocks: List<CollectedCommentBlock>): CommentAttachmentIndex {
        val attachments = mutableListOf<CommentAttachment>()
        val blockByFirstComment = collectedBlocks.associateBy { it.comments.first() }

        bindBlock(chunk.body, blockByFirstComment, attachments)

        return CommentAttachmentIndex(attachments)
    }

    private fun bindBlock(
        block: BlockNode,
        blockByFirstComment: Map<CommentStatement, CollectedCommentBlock>,
        attachments: MutableList<CommentAttachment>
    ) {
        var pendingCommentBlock: CollectedCommentBlock? = null

        block.statements.forEach { statement ->
            if (statement is CommentStatement) {
                val nextCommentBlock = blockByFirstComment[statement]
                if (nextCommentBlock != null) {
                    pendingCommentBlock?.let { attachments.add(it.toAttachment()) }
                    pendingCommentBlock = nextCommentBlock
                }
                return@forEach
            }

            pendingCommentBlock?.let {
                attachments.add(it.toAttachment(statement.takeIf { target -> it.isAdjacentTo(target) }))
                pendingCommentBlock = null
            }

            bindNested(statement, blockByFirstComment, attachments)
        }

        block.returnStatement?.let { returnStatement ->
            pendingCommentBlock?.let {
                attachments.add(it.toAttachment(returnStatement.takeIf { target -> it.isAdjacentTo(target) }))
                pendingCommentBlock = null
            }
            bindNested(returnStatement, blockByFirstComment, attachments)
        }

        pendingCommentBlock?.let { attachments.add(it.toAttachment()) }
    }

    private fun bindNested(
        statement: StatementNode,
        blockByFirstComment: Map<CommentStatement, CollectedCommentBlock>,
        attachments: MutableList<CommentAttachment>
    ) {
        when (statement) {
            is AssignmentStatement -> {
                statement.variables.forEach { bindNested(it, blockByFirstComment, attachments) }
                statement.init.forEach { bindNested(it, blockByFirstComment, attachments) }
            }

            is CallStatement -> bindNested(statement.expression, blockByFirstComment, attachments)
            is DoStatement -> bindBlock(statement.body, blockByFirstComment, attachments)
            is ForGenericStatement -> {
                statement.iterators.forEach { bindNested(it, blockByFirstComment, attachments) }
                bindBlock(statement.body, blockByFirstComment, attachments)
            }

            is ForNumericStatement -> {
                bindNested(statement.start, blockByFirstComment, attachments)
                bindNested(statement.end, blockByFirstComment, attachments)
                statement.step?.let { bindNested(it, blockByFirstComment, attachments) }
                bindBlock(statement.body, blockByFirstComment, attachments)
            }

            is FunctionDeclaration -> statement.body?.let { bindBlock(it, blockByFirstComment, attachments) }
            is IfClause -> {
                if (statement !is ElseClause) {
                    bindNested(statement.condition, blockByFirstComment, attachments)
                }
                bindBlock(statement.body, blockByFirstComment, attachments)
            }

            is IfStatement -> statement.causes.forEach { bindNested(it, blockByFirstComment, attachments) }
            is LocalStatement -> {
                statement.variables.forEach { bindNested(it, blockByFirstComment, attachments) }
                statement.init.forEach { bindNested(it, blockByFirstComment, attachments) }
            }

            is RepeatStatement -> {
                bindBlock(statement.body, blockByFirstComment, attachments)
                bindNested(statement.condition, blockByFirstComment, attachments)
            }

            is ReturnStatement -> statement.arguments.forEach { bindNested(it, blockByFirstComment, attachments) }
            is SwitchStatement -> {
                bindNested(statement.condition, blockByFirstComment, attachments)
                statement.causes.forEach { cause ->
                    when (cause) {
                        is CaseCause -> {
                            cause.conditions.forEach { bindNested(it, blockByFirstComment, attachments) }
                            bindBlock(cause.body, blockByFirstComment, attachments)
                        }

                        is DefaultCause -> bindBlock(cause.body, blockByFirstComment, attachments)
                    }
                }
            }

            is WhenStatement -> {
                bindNested(statement.condition, blockByFirstComment, attachments)
                bindNested(statement.ifCause, blockByFirstComment, attachments)
                statement.elseCause?.let { bindNested(it, blockByFirstComment, attachments) }
            }

            is WhileStatement -> {
                bindNested(statement.condition, blockByFirstComment, attachments)
                bindBlock(statement.body, blockByFirstComment, attachments)
            }
        }
    }

    private fun bindNested(
        expression: ExpressionNode,
        blockByFirstComment: Map<CommentStatement, CollectedCommentBlock>,
        attachments: MutableList<CommentAttachment>
    ) {
        when (expression) {
            is ArrayConstructorExpression -> expression.values.forEach { bindNested(it, blockByFirstComment, attachments) }
            is BinaryExpression -> {
                expression.left?.let { bindNested(it, blockByFirstComment, attachments) }
                expression.right?.let { bindNested(it, blockByFirstComment, attachments) }
            }

            is CallExpression -> {
                bindNested(expression.base, blockByFirstComment, attachments)
                expression.arguments.forEach { bindNested(it, blockByFirstComment, attachments) }
            }

            is FunctionDeclaration -> expression.body?.let { bindBlock(it, blockByFirstComment, attachments) }
            is IndexExpression -> {
                bindNested(expression.base, blockByFirstComment, attachments)
                bindNested(expression.index, blockByFirstComment, attachments)
            }

            is LambdaDeclaration -> bindNested(expression.expression, blockByFirstComment, attachments)
            is MemberExpression -> bindNested(expression.base, blockByFirstComment, attachments)
            is TableConstructorExpression -> expression.fields.forEach {
                bindNested(it.key, blockByFirstComment, attachments)
                bindNested(it.value, blockByFirstComment, attachments)
            }

            is TableKey -> {
                bindNested(expression.key, blockByFirstComment, attachments)
                bindNested(expression.value, blockByFirstComment, attachments)
            }

            is UnaryExpression -> bindNested(expression.arg, blockByFirstComment, attachments)
        }
    }

    private fun CollectedCommentBlock.isAdjacentTo(target: BaseASTNode): Boolean {
        val lineDelta = target.range.start.line - visibleEndLine
        return lineDelta in 0..1
    }

    private fun CollectedCommentBlock.toAttachment(target: BaseASTNode? = null): CommentAttachment {
        return CommentAttachment(
            comments = comments,
            target = target,
            docComment = docComment,
            inlineTypeText = inlineTypeText
        )
    }
}
