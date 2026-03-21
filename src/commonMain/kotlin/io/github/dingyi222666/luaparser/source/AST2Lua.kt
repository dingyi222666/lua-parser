package io.github.dingyi222666.luaparser.source

import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import kotlin.math.max

/**
 * @author: dingyi
 * @date: 2023/2/10
 * @description:
 **/
class AST2Lua : ASTVisitor<StringBuilder> {

    private var currentDepth = -1

    var indentSize = 4

    private fun indent(): String {
        return " ".repeat(indentSize).repeat(max(currentDepth, 0))
    }

    private fun indent(sb: StringBuilder) {
        sb.append(indent())
    }

    private fun appendLineAndIndent(value: StringBuilder) {
        value.appendLine()
        indent(value)
    }

    private fun appendCompactCallSuffix(value: StringBuilder, arguments: List<ExpressionNode>) {
        if (arguments.size == 1) {
            when (val argument = arguments.single()) {
                is ConstantNode -> if (argument.constantType == ConstantNode.TYPE.STRING) {
                    value.append(" ")
                    appendExpression(value, argument)
                    return
                }
                is TableConstructorExpression -> {
                    value.append(" ")
                    appendExpression(value, argument)
                    return
                }
            }
        }

        value.append("(")
        arguments.forEachIndexed { index, argument ->
            if (index != 0) {
                value.append(", ")
            }
            appendExpression(value, argument)
        }
        value.append(")")
    }

    override fun visitBlockNode(node: BlockNode, value: StringBuilder) {
        currentDepth++
        super.visitBlockNode(node, value)
        currentDepth--
        appendLineAndIndent(value)
    }

    override fun visitStatementNode(node: StatementNode, value: StringBuilder) {
        appendLineAndIndent(value)
        super.visitStatementNode(node, value)
    }

    fun asCode(node: ChunkNode): String {
        val builder = StringBuilder()
        visitChunkNode(node, builder)
        return builder.toString()
    }


    override fun visitAssignmentStatement(node: AssignmentStatement, value: StringBuilder) {
        node.init.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
        value.append(" = ")
        node.variables.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
    }

    override fun visitConstantNode(node: ConstantNode, value: StringBuilder) {
        value.append(node.rawValue)
    }

    override fun visitBinaryExpression(node: BinaryExpression, value: StringBuilder) {
        appendExpression(value, node)
    }

    override fun visitUnaryExpression(node: UnaryExpression, value: StringBuilder) {
        appendExpression(value, node)
    }

    override fun visitCallExpression(node: CallExpression, value: StringBuilder) {
        appendExpression(value, node)
    }


    override fun visitFunctionDeclaration(node: FunctionDeclaration, value: StringBuilder) {
        value.append("function ")
        node.identifier?.let { visitExpressionNode(it, value) }
        value.append("(")
        node.params.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
        value.append(")")

        node.body?.let { visitBlockNode(it, value) }

        value.append("end")
    }

    override fun visitArrayConstructorExpression(node: ArrayConstructorExpression, value: StringBuilder) {
        value.append("[")
        node.values.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
        value.append("]")
    }

    override fun visitBreakStatement(node: BreakStatement, value: StringBuilder) {
        indent(value)
        value.appendLine("break")
    }

    override fun visitCaseCause(node: CaseCause, value: StringBuilder) {
        appendLineAndIndent(value)
        value.append("case ")
        node.conditions.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
        value.append(" then")

        visitBlockNode(node.body, value)

    }


    override fun visitCommentStatement(commentStatement: CommentStatement, value: StringBuilder) {
        value.append(commentStatement.comment)
    }

    override fun visitCallStatement(node: CallStatement, value: StringBuilder) {
        visitExpressionNode(node.expression, value)
    }

    override fun visitContinueStatement(node: ContinueStatement, value: StringBuilder) {
        value.appendLine("continue")
    }

    override fun visitDefaultCause(node: DefaultCause, value: StringBuilder) {
        appendLineAndIndent(value)
        value.append("default")

        visitBlockNode(node.body, value)


    }

    override fun visitSwitchStatement(node: SwitchStatement, value: StringBuilder) {
        value.append("switch ")
        visitExpressionNode(node.condition, value)
        value.append(" do")
        node.causes.forEach { cause ->
            when (cause) {
                is CaseCause -> visitCaseCause(cause, value)
                is DefaultCause -> visitDefaultCause(cause, value)
            }
        }
        appendLineAndIndent(value)
        value.append("end")
    }

    override fun visitDoStatement(node: DoStatement, value: StringBuilder) {
        value.append("do")

        visitBlockNode(node.body, value)

        value.append("end")
    }

    override fun visitElseClause(node: ElseClause, value: StringBuilder) {
        appendLineAndIndent(value)
        value.append("else")

        visitBlockNode(node.body, value)

    }

    override fun visitElseIfClause(node: ElseIfClause, value: StringBuilder) {
        appendLineAndIndent(value)
        value.append("elseif ")
        visitExpressionNode(node.condition, value)
        value.append(" then")

        appendLineAndIndent(value)
        visitBlockNode(node.body, value)
    }

    override fun visitForGenericStatement(node: ForGenericStatement, value: StringBuilder) {
        value.append("for ")
        node.variables.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
        value.append(" in ")
        node.iterators.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
        value.append(" do")

        visitBlockNode(node.body, value)

        value.append("end")
    }

    override fun visitIdentifier(node: Identifier, value: StringBuilder) {
        value.append(node.name)
    }

    override fun visitGotoStatement(node: GotoStatement, value: StringBuilder) {
        value.append("goto ")
        visitExpressionNode(node.identifier, value)
        value.appendLine()
    }

    override fun visitIfClause(node: IfClause, value: StringBuilder) {
        appendLineAndIndent(value)
        value.append("if ")
        visitExpressionNode(node.condition, value)
        value.append(" then")
        indent(value)

        visitBlockNode(node.body, value)

    }

    override fun visitMemberExpression(node: MemberExpression, value: StringBuilder) {
        appendExpression(value, node)
    }

    override fun visitReturnStatement(node: ReturnStatement, value: StringBuilder) {
        value.append("return ")
        node.arguments.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
    }

    override fun visitIndexExpression(node: IndexExpression, value: StringBuilder) {
        appendExpression(value, node)
    }

    override fun visitForNumericStatement(node: ForNumericStatement, value: StringBuilder) {
        value.append("for ")
        visitExpressionNode(node.variable, value)
        value.append(" = ")
        visitExpressionNode(node.start, value)
        value.append(", ")
        visitExpressionNode(node.end, value)
        node.step?.let {
            value.append(", ")
            visitExpressionNode(it, value)
        }
        value.append(" do ")


        visitBlockNode(node.body, value)


        indent(value)
        value.appendLine("end")
    }


    override fun visitLabelStatement(node: LabelStatement, value: StringBuilder) {
        value.append("::")
        visitExpressionNode(node.identifier, value)
        value.appendLine("::")
    }

    override fun visitLambdaDeclaration(node: LambdaDeclaration, value: StringBuilder) {
        value.append("lambda ")
        if (node.params.isNotEmpty()) {
            value.append("(")
            node.params.forEachIndexed { index, baseASTNode ->
                if (index != 0) {
                    value.append(", ")
                }
                visitExpressionNode(baseASTNode, value)
            }
            value.append(")")
        }
        value.append(" : ")
        visitExpressionNode(node.expression, value)
    }

    override fun visitRepeatStatement(node: RepeatStatement, value: StringBuilder) {
        value.append("repeat")

        visitBlockNode(node.body, value)

        indent(value)
        value.append("until ")
        visitExpressionNode(node.condition, value)
    }

    override fun visitStringCallExpression(node: StringCallExpression, value: StringBuilder) {
        appendExpression(value, node)
    }

    override fun visitTableConstructorExpression(node: TableConstructorExpression, value: StringBuilder) {
        value.append("{")
        node.fields.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
        value.append("}")
    }

    override fun visitTableKey(node: TableKey, value: StringBuilder) {
        if (isImplicitArrayField(node)) {
            visitExpressionNode(node.value, value)
            return
        }

        value.append("[")
        visitExpressionNode(node.key, value)
        value.append("] = ")
        visitExpressionNode(node.value, value)
    }

    override fun visitTableKeyString(node: TableKeyString, value: StringBuilder) {
        visitExpressionNode(node.key, value)
        value.append(" = ")
        visitExpressionNode(node.value, value)
    }

    override fun visitWhenStatement(node: WhenStatement, value: StringBuilder) {
        value.append("when ")
        visitExpressionNode(node.condition, value)
        value.append(" ")
        visitStatementNode(node.ifCause, value)
        value.append(" ")
        node.elseCause?.let { visitStatementNode(it, value) }
    }

    override fun visitWhileStatement(node: WhileStatement, value: StringBuilder) {
        value.append("while ")
        visitExpressionNode(node.condition, value)
        value.append(" do")
        visitBlockNode(node.body, value)
        indent(value)
        value.appendLine("end")
    }

    override fun visitLocalStatement(node: LocalStatement, value: StringBuilder) {
        if (node.init.isNotEmpty()) {
            value.append("local ")
            node.init.forEachIndexed { index, baseASTNode ->
                if (index != 0) {
                    value.append(", ")
                }
                visitExpressionNode(baseASTNode, value)
            }
        }
        if (node.variables.isNotEmpty()) {
            value.append(" = ")
            node.variables.forEachIndexed { index, baseASTNode ->
                if (index != 0) {
                    value.append(", ")
                }
                visitExpressionNode(baseASTNode, value)
            }
        }
    }

    override fun visitVarargLiteral(node: VarargLiteral, value: StringBuilder) {
        value.append("...")
    }

    private fun operatorToLua(operator: ExpressionOperator): String {
        return when (operator) {
            ExpressionOperator.NOT -> "not"
            ExpressionOperator.AND -> "and"
            ExpressionOperator.OR -> "or"
            ExpressionOperator.EQ -> "=="
            ExpressionOperator.NE -> "~="
            ExpressionOperator.LT -> "<"
            ExpressionOperator.LE -> "<="
            ExpressionOperator.GT -> ">"
            ExpressionOperator.GE -> ">="
            ExpressionOperator.ADD -> "+"
            ExpressionOperator.MINUS -> "-"
            ExpressionOperator.MULT -> "*"
            ExpressionOperator.DIV -> "/"
            ExpressionOperator.MOD -> "%"
            ExpressionOperator.DOUBLE_DIV -> "//"
            ExpressionOperator.BIT_AND -> "&"
            ExpressionOperator.BIT_OR -> "|"
            ExpressionOperator.BIT_TILDE -> "~"
            ExpressionOperator.BIT_LT -> "<<"
            ExpressionOperator.BIT_GT -> ">>"
            ExpressionOperator.BIT_EXP -> "^"
            ExpressionOperator.CONCAT -> ".."
            ExpressionOperator.GETLEN -> "#"
        }
    }

    override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: StringBuilder) {
        value.append(identifier.name)

        val attributeName = identifier.attributeName

        if (attributeName != null) {
            value.append(" ")
            value.append("<")
            value.append(attributeName)
            value.append(">")
        }
    }

    private fun appendExpression(value: StringBuilder, expression: ExpressionNode) {
        appendExpression(value, expression, parentPrecedence = Int.MIN_VALUE, parentOperator = null, isRightChild = false)
    }

    private fun appendExpression(
        value: StringBuilder,
        expression: ExpressionNode,
        parentPrecedence: Int,
        parentOperator: ExpressionOperator?,
        isRightChild: Boolean
    ) {
        val needsParens = needsParentheses(expression, parentPrecedence, parentOperator, isRightChild)
        if (needsParens) {
            value.append("(")
        }

        when (expression) {
            is BinaryExpression -> {
                val precedence = expressionPrecedence(expression)
                expression.left?.let {
                    appendExpression(
                        value = value,
                        expression = it,
                        parentPrecedence = precedence,
                        parentOperator = expression.operator,
                        isRightChild = false
                    )
                }
                value.append(" ${operatorToLua(expression.operator)} ")
                expression.right?.let {
                    appendExpression(
                        value = value,
                        expression = it,
                        parentPrecedence = precedence,
                        parentOperator = expression.operator,
                        isRightChild = true
                    )
                }
            }

            is UnaryExpression -> {
                value.append(operatorToLua(expression.operator))
                if (expression.operator == ExpressionOperator.NOT) {
                    value.append(" ")
                }
                appendExpression(
                    value = value,
                    expression = expression.arg,
                    parentPrecedence = expressionPrecedence(expression),
                    parentOperator = expression.operator,
                    isRightChild = true
                )
            }

            is CallExpression -> {
                appendExpression(value, expression.base, expressionPrecedence(expression), null, false)
                value.append("(")
                expression.arguments.forEachIndexed { index, baseASTNode ->
                    if (index != 0) {
                        value.append(", ")
                    }
                    appendExpression(value, baseASTNode)
                }
                value.append(")")
            }

            is MemberExpression -> {
                appendExpression(value, expression.base, expressionPrecedence(expression), null, false)
                value.append(expression.indexer)
                visitExpressionNode(expression.identifier, value)
            }

            is IndexExpression -> {
                appendExpression(value, expression.base, expressionPrecedence(expression), null, false)
                value.append("[")
                appendExpression(value, expression.index)
                value.append("]")
            }

            else -> visitExpressionNodeFallback(expression, value)
        }

        if (needsParens) {
            value.append(")")
        }
    }

    private fun visitExpressionNodeFallback(node: ExpressionNode, value: StringBuilder) {
        when (node) {
            is AttributeIdentifier -> visitAttributeIdentifier(node, value)
            is ConstantNode -> visitConstantNode(node, value)
            is Identifier -> visitIdentifier(node, value)
            is ArrayConstructorExpression -> visitArrayConstructorExpression(node, value)
            is FunctionDeclaration -> visitFunctionDeclaration(node, value)
            is LambdaDeclaration -> visitLambdaDeclaration(node, value)
            is StringCallExpression -> {
                appendExpression(value, node.base, expressionPrecedence(node), null, false)
                appendCompactCallSuffix(value, node.arguments)
            }
            is TableCallExpression -> {
                appendExpression(value, node.base, expressionPrecedence(node), null, false)
                appendCompactCallSuffix(value, node.arguments)
            }
            is TableConstructorExpression -> visitTableConstructorExpression(node, value)
            is VarargLiteral -> visitVarargLiteral(node, value)
            ExpressionNode.EMPTY -> Unit
            else -> error("Unsupported expression node: ${node::class.simpleName}")
        }
    }

    private fun needsParentheses(
        expression: ExpressionNode,
        parentPrecedence: Int,
        parentOperator: ExpressionOperator?,
        isRightChild: Boolean
    ): Boolean {
        val precedence = expressionPrecedence(expression)
        if (precedence == Int.MAX_VALUE) {
            return false
        }
        if (precedence < parentPrecedence) {
            return true
        }
        if (precedence > parentPrecedence) {
            return false
        }

        return when (expression) {
            is BinaryExpression -> when {
                parentOperator == null -> false
                isRightAssociative(parentOperator) && !isRightChild -> true
                !isRightAssociative(parentOperator) && isRightChild -> true
                else -> false
            }

            else -> false
        }
    }

    private fun expressionPrecedence(expression: ExpressionNode): Int {
        return when (expression) {
            is BinaryExpression -> binaryPrecedence(expression.operator)
            is UnaryExpression -> 11
            is CallExpression, is MemberExpression, is IndexExpression -> 13
            else -> Int.MAX_VALUE
        }
    }

    private fun binaryPrecedence(operator: ExpressionOperator): Int {
        return when (operator) {
            ExpressionOperator.OR -> 1
            ExpressionOperator.AND -> 2
            ExpressionOperator.LT, ExpressionOperator.GT, ExpressionOperator.LE, ExpressionOperator.GE, ExpressionOperator.EQ, ExpressionOperator.NE -> 3
            ExpressionOperator.BIT_OR -> 4
            ExpressionOperator.BIT_TILDE -> 5
            ExpressionOperator.BIT_AND -> 6
            ExpressionOperator.BIT_LT, ExpressionOperator.BIT_GT -> 7
            ExpressionOperator.CONCAT -> 8
            ExpressionOperator.ADD, ExpressionOperator.MINUS -> 9
            ExpressionOperator.DOUBLE_DIV, ExpressionOperator.DIV, ExpressionOperator.MOD, ExpressionOperator.MULT -> 10
            ExpressionOperator.BIT_EXP -> 12
            ExpressionOperator.NOT, ExpressionOperator.GETLEN -> 11
        }
    }

    private fun isRightAssociative(operator: ExpressionOperator): Boolean {
        return operator == ExpressionOperator.CONCAT || operator == ExpressionOperator.BIT_EXP
    }

    private fun isImplicitArrayField(node: TableKey): Boolean {
        val key = node.key as? ConstantNode ?: return false
        if (key.constantType != ConstantNode.TYPE.INTERGER) {
            return false
        }
        return key.range.start == key.range.end
    }
}
