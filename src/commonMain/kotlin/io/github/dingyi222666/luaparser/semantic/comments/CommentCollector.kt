package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.*

class CommentCollector(
    private val docCommentSyntaxParser: DocCommentSyntaxParser = DocCommentSyntaxParser()
) {

    fun collect(chunk: ChunkNode): List<CollectedCommentBlock> {
        val blocks = mutableListOf<CollectedCommentBlock>()
        collectBlock(chunk.body, blocks)
        return blocks
    }

    private fun collectBlock(block: BlockNode, output: MutableList<CollectedCommentBlock>) {
        val pending = mutableListOf<CommentStatement>()

        block.statements.forEach { statement ->
            if (statement is CommentStatement) {
                if (pending.isNotEmpty() && !areAdjacentInSource(pending.last(), statement)) {
                    flushPending(pending, output)
                }
                pending.add(statement)
            } else {
                flushPending(pending, output)
                collectNestedComments(statement, output)
            }
        }

        flushPending(pending, output)
        block.returnStatement?.let { collectNestedComments(it, output) }
    }

    private fun flushPending(
        pending: MutableList<CommentStatement>,
        output: MutableList<CollectedCommentBlock>
    ) {
        if (pending.isEmpty()) {
            return
        }

        val comments = pending.toList()
        val docComments = comments.filter(CommentStatement::isDocComment)
        val range = Range(
            start = comments.first().range.start,
            end = comments.last().range.end
        )
        val visibleEndLine = comments.last().visibleEndLine()

        output.add(
            CollectedCommentBlock(
                comments = comments,
                range = range,
                startLine = range.start.line,
                endLine = range.end.line,
                visibleEndLine = visibleEndLine,
                docComment = docCommentSyntaxParser.parse(docComments),
                inlineTypeText = docCommentSyntaxParser.findInlineTypeText(comments)
            )
        )
        pending.clear()
    }

    private fun collectNestedComments(statement: StatementNode, output: MutableList<CollectedCommentBlock>) {
        when (statement) {
            is AssignmentStatement -> {
                statement.variables.forEach { collectNestedComments(it, output) }
                statement.init.forEach { collectNestedComments(it, output) }
            }

            is CallStatement -> collectNestedComments(statement.expression, output)
            is DoStatement -> collectBlock(statement.body, output)
            is ForGenericStatement -> {
                statement.iterators.forEach { collectNestedComments(it, output) }
                collectBlock(statement.body, output)
            }

            is ForNumericStatement -> {
                collectNestedComments(statement.start, output)
                collectNestedComments(statement.end, output)
                statement.step?.let { collectNestedComments(it, output) }
                collectBlock(statement.body, output)
            }

            is FunctionDeclaration -> statement.body?.let { collectBlock(it, output) }
            is IfClause -> {
                if (statement !is ElseClause) {
                    collectNestedComments(statement.condition, output)
                }
                collectBlock(statement.body, output)
            }

            is IfStatement -> statement.causes.forEach { collectNestedComments(it, output) }
            is LocalStatement -> {
                statement.variables.forEach { collectNestedComments(it, output) }
                statement.init.forEach { collectNestedComments(it, output) }
            }

            is RepeatStatement -> {
                collectBlock(statement.body, output)
                collectNestedComments(statement.condition, output)
            }

            is ReturnStatement -> statement.arguments.forEach { collectNestedComments(it, output) }
            is SwitchStatement -> {
                collectNestedComments(statement.condition, output)
                statement.causes.forEach { cause ->
                    when (cause) {
                        is CaseCause -> {
                            cause.conditions.forEach { collectNestedComments(it, output) }
                            collectBlock(cause.body, output)
                        }

                        is DefaultCause -> collectBlock(cause.body, output)
                    }
                }
            }

            is WhenStatement -> {
                collectNestedComments(statement.condition, output)
                collectNestedComments(statement.ifCause, output)
                statement.elseCause?.let { collectNestedComments(it, output) }
            }

            is WhileStatement -> {
                collectNestedComments(statement.condition, output)
                collectBlock(statement.body, output)
            }
        }
    }

    private fun collectNestedComments(expression: ExpressionNode, output: MutableList<CollectedCommentBlock>) {
        when (expression) {
            is ArrayConstructorExpression -> expression.values.forEach { collectNestedComments(it, output) }
            is BinaryExpression -> {
                expression.left?.let { collectNestedComments(it, output) }
                expression.right?.let { collectNestedComments(it, output) }
            }

            is CallExpression -> {
                collectNestedComments(expression.base, output)
                expression.arguments.forEach { collectNestedComments(it, output) }
            }

            is FunctionDeclaration -> expression.body?.let { collectBlock(it, output) }
            is IndexExpression -> {
                collectNestedComments(expression.base, output)
                collectNestedComments(expression.index, output)
            }

            is LambdaDeclaration -> collectNestedComments(expression.expression, output)
            is MemberExpression -> collectNestedComments(expression.base, output)
            is TableConstructorExpression -> expression.fields.forEach {
                collectNestedComments(it.key, output)
                collectNestedComments(it.value, output)
            }

            is TableKey -> {
                collectNestedComments(expression.key, output)
                collectNestedComments(expression.value, output)
            }

            is UnaryExpression -> collectNestedComments(expression.arg, output)
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
}
