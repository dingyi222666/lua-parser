package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.*

/**
 * Groups adjacent comments into blocks and attaches each block to the construct it documents.
 *
 * Grouping and attachment used to be two separate, structurally identical walks over the same
 * AST (collect-then-bind). They are fused here into a single traversal: a comment group is
 * complete exactly when the next group starts, a statement follows, or the block ends — which is
 * precisely the moment the attachment target becomes known.
 */
class CommentCollector(
    private val docCommentSyntaxParser: DocCommentSyntaxParser = DocCommentSyntaxParser()
) {

    fun collect(chunk: ChunkNode): List<CollectedCommentBlock> = scan(chunk).blocks

    internal fun scan(chunk: ChunkNode): CommentScanResult {
        val scan = Scan()
        scan.walkBlock(chunk.body)
        return CommentScanResult(scan.blocks, scan.attachments)
    }

    private inner class Scan {
        val blocks = mutableListOf<CollectedCommentBlock>()
        val attachments = mutableListOf<CommentAttachment>()

        fun walkBlock(block: BlockNode) {
            val pending = mutableListOf<CommentStatement>()

            block.statements.forEach { statement ->
                if (statement is CommentStatement) {
                    // A non-adjacent comment starts a new group, so the previous one is complete
                    // and can never gain a target.
                    if (pending.isNotEmpty() && !areAdjacentInSource(pending.last(), statement)) {
                        closeGroup(pending)?.let { attachments.add(it.toAttachment()) }
                    }
                    pending.add(statement)
                    return@forEach
                }

                closeGroup(pending)?.let { group ->
                    attachments.add(group.toAttachment(statement.takeIf { target -> group.isAdjacentTo(target) }))
                }
                walkStatement(statement)
            }

            val returnStatement = block.returnStatement
            if (returnStatement == null) {
                closeGroup(pending)?.let { attachments.add(it.toAttachment()) }
                return
            }

            closeGroup(pending)?.let { group ->
                attachments.add(group.toAttachment(returnStatement.takeIf { target -> group.isAdjacentTo(target) }))
            }
            walkStatement(returnStatement)
        }

        /** Materializes the buffered comments as a block, or returns null when none are buffered. */
        private fun closeGroup(pending: MutableList<CommentStatement>): CollectedCommentBlock? {
            if (pending.isEmpty()) {
                return null
            }

            val comments = pending.toList()
            pending.clear()
            val docComments = comments.filter(CommentStatement::isDocComment)
            val range = Range(
                start = comments.first().range.start,
                end = comments.last().range.end
            )

            val block = CollectedCommentBlock(
                comments = comments,
                range = range,
                startLine = range.start.line,
                endLine = range.end.line,
                visibleEndLine = comments.last().visibleEndLine(),
                docComment = docCommentSyntaxParser.parse(docComments),
                inlineTypeText = docCommentSyntaxParser.findInlineTypeText(comments)
            )
            blocks.add(block)
            return block
        }

        fun walkStatement(statement: StatementNode) {
            when (statement) {
                is AssignmentStatement -> {
                    statement.variables.forEach(::walkExpression)
                    statement.init.forEach(::walkExpression)
                }

                is CallStatement -> walkExpression(statement.expression)
                is DoStatement -> walkBlock(statement.body)
                is ForGenericStatement -> {
                    statement.iterators.forEach(::walkExpression)
                    walkBlock(statement.body)
                }

                is ForNumericStatement -> {
                    walkExpression(statement.start)
                    walkExpression(statement.end)
                    statement.step?.let(::walkExpression)
                    walkBlock(statement.body)
                }

                is FunctionDeclaration -> statement.body?.let(::walkBlock)
                is IfClause -> {
                    if (statement !is ElseClause) {
                        walkExpression(statement.condition)
                    }
                    walkBlock(statement.body)
                }

                is IfStatement -> statement.causes.forEach(::walkStatement)
                is LocalStatement -> {
                    statement.variables.forEach(::walkExpression)
                    statement.init.forEach(::walkExpression)
                }

                is RepeatStatement -> {
                    walkBlock(statement.body)
                    walkExpression(statement.condition)
                }

                is ReturnStatement -> statement.arguments.forEach(::walkExpression)
                is SwitchStatement -> {
                    walkExpression(statement.condition)
                    statement.causes.forEach { cause ->
                        when (cause) {
                            is CaseCause -> {
                                cause.conditions.forEach(::walkExpression)
                                walkBlock(cause.body)
                            }

                            is DefaultCause -> walkBlock(cause.body)
                        }
                    }
                }

                is WhenStatement -> {
                    walkExpression(statement.condition)
                    walkStatement(statement.ifCause)
                    statement.elseCause?.let(::walkStatement)
                }

                is WhileStatement -> {
                    walkExpression(statement.condition)
                    walkBlock(statement.body)
                }
            }
        }

        fun walkExpression(expression: ExpressionNode) {
            when (expression) {
                is ArrayConstructorExpression -> expression.values.forEach(::walkExpression)
                is BinaryExpression -> {
                    expression.left?.let(::walkExpression)
                    expression.right?.let(::walkExpression)
                }

                is CallExpression -> {
                    walkExpression(expression.base)
                    expression.arguments.forEach(::walkExpression)
                }

                is FunctionDeclaration -> expression.body?.let(::walkBlock)
                is IndexExpression -> {
                    walkExpression(expression.base)
                    walkExpression(expression.index)
                }

                is LambdaDeclaration -> walkExpression(expression.expression)
                is MemberExpression -> walkExpression(expression.base)
                is TableConstructorExpression -> expression.fields.forEach {
                    walkExpression(it.key)
                    walkExpression(it.value)
                }

                is TableKey -> {
                    walkExpression(expression.key)
                    walkExpression(expression.value)
                }

                is UnaryExpression -> walkExpression(expression.arg)
            }
        }
    }

    private fun areAdjacentInSource(previous: CommentStatement, current: CommentStatement): Boolean {
        return current.range.start.line - previous.visibleEndLine() <= 1
    }

    private fun CommentStatement.visibleEndLine(): Int {
        val visibleText = comment.trimEnd('\r', '\n')
        val lineCount = if (visibleText.isEmpty()) 1 else visibleText.lineSequence().count()
        return range.start.line + lineCount - 1
    }

    private fun CollectedCommentBlock.isAdjacentTo(target: BaseASTNode): Boolean {
        return target.range.start.line - visibleEndLine in 0..1
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

internal class CommentScanResult(
    val blocks: List<CollectedCommentBlock>,
    val attachments: List<CommentAttachment>
)
