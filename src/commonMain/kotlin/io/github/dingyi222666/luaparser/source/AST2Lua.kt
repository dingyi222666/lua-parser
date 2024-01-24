package io.github.dingyi222666.luaparser.source

import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor

/**
 * @author: dingyi
 * @date: 2023/2/10
 * @description:
 **/
class AST2Lua : ASTVisitor<StringBuilder> {

    private var currentDepth = -1

    var indentSize = 4


    private fun indent(): String {
        return " ".repeat(indentSize).repeat(currentDepth)
    }

    private fun indent(sb: StringBuilder) {
        sb.append(indent())
    }

    fun asCode(node: ChunkNode): String {
        val builder = StringBuilder()
        visitChunkNode(node, builder)
        return builder.toString()
    }

    override fun visitBlockNode(node: BlockNode, value: StringBuilder) {
        currentDepth++
        indent(value)
        super.visitBlockNode(node, value)
        currentDepth--
        if (currentDepth >= 0)
            indent(value)
    }


    override fun visitAssignmentStatement(node: AssignmentStatement, value: StringBuilder) {
        value.appendLine()
        indent(value)
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
        node.left?.let { visitExpressionNode(it, value) }
        value.append(" ${operatorToLua(node.operator)} ")
        node.right?.let { visitExpressionNode(it, value) }
    }

    override fun visitUnaryExpression(node: UnaryExpression, value: StringBuilder) {
        value.append(operatorToLua(node.operator))
        visitExpressionNode(node.arg, value)
    }

    override fun visitCallExpression(node: CallExpression, value: StringBuilder) {
        visitExpressionNode(node.base, value)
        value.append("(")
        node.arguments.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
        value.append(")")
    }

    override fun visitFunctionDeclaration(node: FunctionDeclaration, value: StringBuilder) {
        indent(value)
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
        value.appendLine()
        indent(value)
        node.body?.let { visitBlockNode(it, value) }
        indent(value)
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
        indent(value)
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

    override fun visitCallStatement(node: CallStatement, value: StringBuilder) {
        indent(value)
        visitExpressionNode(node.expression, value)
        value.appendLine()
    }

    override fun visitContinueStatement(node: ContinueStatement, value: StringBuilder) {
        indent(value)
        value.appendLine("continue")
    }

    override fun visitDefaultCause(node: DefaultCause, value: StringBuilder) {
        indent(value)
        value.append("default")
        visitBlockNode(node.body, value)
        indent(value)
    }

    override fun visitDoStatement(node: DoStatement, value: StringBuilder) {
        indent(value)
        value.append("do")
        visitBlockNode(node.body, value)
        indent(value)
        value.append("end")
    }

    override fun visitElseClause(node: ElseClause, value: StringBuilder) {
        indent(value)
        value.append("else")
        visitBlockNode(node.body, value)
        indent(value)
    }

    override fun visitElseIfClause(node: ElseIfClause, value: StringBuilder) {
        indent(value)
        value.append("elseif ")
        visitExpressionNode(node.condition, value)
        value.append(" then")
        visitBlockNode(node.body, value)
        indent(value)
    }

    override fun visitForGenericStatement(node: ForGenericStatement, value: StringBuilder) {
        indent(value)
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
        indent(value)
        value.append("end")
    }

    override fun visitIdentifier(node: Identifier, value: StringBuilder) {
        value.append(node.name)
    }

    override fun visitGotoStatement(node: GotoStatement, value: StringBuilder) {
        indent(value)
        value.append("goto ")
        visitExpressionNode(node.identifier, value)
        value.appendLine()
    }

    override fun visitIfClause(node: IfClause, value: StringBuilder) {
        indent(value)
        value.append("if ")
        visitExpressionNode(node.condition, value)
        value.append(" then")
        indent(value)
        visitBlockNode(node.body, value)
    }

    override fun visitMemberExpression(node: MemberExpression, value: StringBuilder) {
        visitExpressionNode(node.base, value)
        value.append(".")
        visitExpressionNode(node.identifier, value)
    }

    override fun visitReturnStatement(node: ReturnStatement, value: StringBuilder) {
        indent(value)
        value.append("return ")
        node.arguments.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
        value.appendLine()
    }

    override fun visitIndexExpression(node: IndexExpression, value: StringBuilder) {
        visitExpressionNode(node.base, value)
        value.append("[")
        visitExpressionNode(node.index, value)
        value.append("]")
    }

    override fun visitForNumericStatement(node: ForNumericStatement, value: StringBuilder) {
        indent(value)
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
        indent(value)
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
        indent(value)
        value.append("repeat")
        visitBlockNode(node.body, value)
        indent(value)
        value.append("until ")
        visitExpressionNode(node.condition, value)
    }

    override fun visitStringCallExpression(node: StringCallExpression, value: StringBuilder) {
        visitExpressionNode(node.base, value)
        value.append("(")
        node.arguments.forEachIndexed { index, baseASTNode ->
            if (index != 0) {
                value.append(", ")
            }
            visitExpressionNode(baseASTNode, value)
        }
        value.append(")")
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
        visitExpressionNode(node.key, value)
        value.append(" = ")
        visitExpressionNode(node.value, value)
    }

    override fun visitTableKeyString(node: TableKeyString, value: StringBuilder) {
        value.append("[")
        visitExpressionNode(node.key, value)
        value.append("] = ")
        visitExpressionNode(node.value, value)
    }

    override fun visitWhenStatement(node: WhenStatement, value: StringBuilder) {
        indent(value)
        value.append("when ")
        visitExpressionNode(node.condition, value)
        value.append(" ")
        visitStatementNode(node.ifCause, value)
        value.append(" ")
        node.elseCause?.let { visitStatementNode(it, value) }
    }

    override fun visitWhileStatement(node: WhileStatement, value: StringBuilder) {
        indent(value)
        value.append("while ")
        visitExpressionNode(node.condition, value)
        value.append(" do")
        visitBlockNode(node.body, value)
        indent(value)
        value.appendLine("end")
    }

    override fun visitLocalStatement(node: LocalStatement, value: StringBuilder) {
        if (node.init.isNotEmpty()) {
            indent(value)
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
        value.appendLine()
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
}