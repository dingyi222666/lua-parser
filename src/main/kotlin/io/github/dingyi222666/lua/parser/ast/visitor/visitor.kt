package io.github.dingyi222666.lua.parser.ast.visitor

import io.github.dingyi222666.lua.parser.ast.node.*

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/

abstract class ASTVisitor<R, T> {

    fun visitIfStatement(node: IfStatement, value: T): R? {
        node.causes.forEach {
            when (it) {
                is ElseClause -> visitElseClause(it, value)
                is ElseIfClause -> visitElseIfClause(it, value)
                else -> visitIfClause(it, value)
            }
        }
        return null
    }

    fun visitGotoStatement(node: GotoStatement, value: T): R? {
        visitIdentifier(node.identifier, value)
        return null
    }

    fun visitLabelStatement(node: LabelStatement, value: T): R? {
        visitIdentifier(node.identifier, value)
        return null
    }

    fun visitBreakStatement(node: BreakStatement, value: T): R? {
        return null
    }

    fun visitContinueStatement(node: ContinueStatement, value: T): R? {
        return null
    }

    fun visitWhileStatement(node: WhileStatement, value: T): R? {
        visitExpressionNode(node.condition, value)
        visitBlockNode(node.body, value)
        return null
    }

    fun visitDoStatement(node: DoStatement, value: T): R? {
        visitBlockNode(node.body, value)
        return null
    }

    fun visitForGenericStatement(node: ForGenericStatement, value: T): R? {
        node.variables.forEach {
            visitIdentifier(it, value)
        }
        node.iterators.forEach {
            visitExpressionNode(it, value)
        }
        visitBlockNode(node.body, value)
        return null
    }

    fun visitForNumericStatement(node: ForNumericStatement, value: T): R? {
        visitIdentifier(node.variable, value)
        visitExpressionNode(node.start, value)
        visitExpressionNode(node.end, value)
        node.step?.let {
            visitExpressionNode(it, value)
        }
        visitBlockNode(node.body, value)
        return null
    }

    fun visitWhenStatement(node: WhenStatement, value: T): R? {
        visitExpressionNode(node.condition, value)
        visitStatementNode(node.ifCause, value)
        node.elseCause?.let {
            visitStatementNode(it, value)
        }
        return null
    }

    fun visitRepeatStatement(node: RepeatStatement, value: T): R? {
        visitBlockNode(node.body, value)
        visitExpressionNode(node.condition, value)
        return null
    }

    fun visitReturnStatement(node: ReturnStatement, value: T): R? {
        node.arguments.forEach {
            visitExpressionNode(it, value)
        }
        return null
    }

    fun visitCallStatement(node: CallStatement, value: T): R? {
        visitExpressionNode(node.expression, value)
        return null
    }

    fun visitAssignmentStatement(node: AssignmentStatement, value: T): R? {
        node.init.forEach {
            visitExpressionNode(it, value)
        }
        node.variables.forEach {
            visitExpressionNode(it, value)
        }
        return null
    }

    fun visitSwitchStatement(node: SwitchStatement, value: T): R? {
        visitExpressionNode(node.condition, value)
        node.causes.forEach {
            when (it) {
                is DefaultCause -> visitDefaultCause(it, value)
                is CaseCause -> visitCaseCause(it, value)
            }
        }
        return null
    }


    fun visitLocalStatement(node: LocalStatement, value: T): R? {
        node.init.forEach {
            visitIdentifier(it, value)
        }
        node.variables.forEach {
            visitExpressionNode(it, value)
        }
        return null
    }

    fun visitFunctionDeclaration(node: FunctionDeclaration, value: T): R? {
        node.identifier?.let { visitExpressionNode(it, value) }
        node.params.forEach {
            visitIdentifier(it, value)
        }
        node.body?.let { visitBlockNode(it, value) }
        return null
    }

    fun visitLambdaDeclaration(node: LambdaDeclaration, value: T): R? {
        node.params.forEach {
            visitIdentifier(it, value)
        }
        visitExpressionNode(node.expression, value)
        return null
    }

    fun visitIfClause(node: IfClause, value: T): R? {
        visitExpressionNode(node.condition, value)
        visitBlockNode(node.body, value)
        return null
    }

    fun visitElseClause(node: ElseClause, value: T): R? {
        visitBlockNode(node.body, value)
        return null
    }

    fun visitElseIfClause(node: ElseIfClause, value: T): R? {
        visitExpressionNode(node.condition, value)
        visitBlockNode(node.body, value)
        return null
    }

    fun visitCaseCause(node: CaseCause, value: T): R? {
        node.conditions.forEach {
            visitExpressionNode(it, value)
        }
        visitBlockNode(node.body, value)
        return null
    }

    fun visitDefaultCause(node: DefaultCause, value: T): R? {
        visitBlockNode(node.body, value)
        return null
    }

    fun visitConstantNode(node: ConstantNode, value: T): R? {
        return null
    }

    fun visitStatementNode(node: StatementNode, value: T): R? {
        when (node) {
            is IfStatement -> visitIfStatement(node, value)
            is GotoStatement -> visitGotoStatement(node, value)
            is LabelStatement -> visitLabelStatement(node, value)
            is BreakStatement -> visitBreakStatement(node, value)
            is ContinueStatement -> visitContinueStatement(node, value)
            is WhileStatement -> visitWhileStatement(node, value)
            is DoStatement -> visitDoStatement(node, value)
            is ForGenericStatement -> visitForGenericStatement(node, value)
            is ForNumericStatement -> visitForNumericStatement(node, value)
            is WhenStatement -> visitWhenStatement(node, value)
            is RepeatStatement -> visitRepeatStatement(node, value)
            is ReturnStatement -> visitReturnStatement(node, value)
            is CallStatement -> visitCallStatement(node, value)
            is AssignmentStatement -> visitAssignmentStatement(node, value)
            is SwitchStatement -> visitSwitchStatement(node, value)
            is LocalStatement -> visitLocalStatement(node, value)
            is FunctionDeclaration -> visitFunctionDeclaration(node, value)
        }
        return null
    }

    fun visitExpressionNode(node: ExpressionNode, value: T): R? {
        when (node) {
            is CallExpression -> visitCallExpression(node, value)
            is BinaryExpression -> visitBinaryExpression(node, value)
            is UnaryExpression -> visitUnaryExpression(node, value)
            is Identifier -> visitIdentifier(node, value)
            is ConstantNode -> visitConstantNode(node, value)
            is LambdaDeclaration -> visitLambdaDeclaration(node, value)
            is TableConstructorExpression -> visitTableConstructorExpression(node, value)
            is ArrayConstructorExpression -> visitArrayConstructorExpression(node, value)
            is IndexExpression -> visitIndexExpression(node, value)
            is VarargLiteral -> visitVarargLiteral(node, value)
            is MemberExpression -> visitMemberExpression(node, value)
            is FunctionDeclaration -> visitFunctionDeclaration(node, value)

        }
        return null
    }

    fun visitExpressionNodes(list: List<ExpressionNode>, value: T): R? {
        list.forEach {
            visitExpressionNode(it, value)
        }
        return null
    }

    fun visitCallExpression(node: CallExpression, value: T): R? {
        when (node) {
            is StringCallExpression -> return visitStringCallExpression(node, value)
            is TableCallExpression -> return visitTableCallExpression(node, value)
        }
        visitExpressionNode(node.base, value)
        node.arguments.forEach {
            visitExpressionNode(it, value)
        }
        return null
    }

    fun visitBinaryExpression(node: BinaryExpression, value: T): R? {
        node.left?.let { visitExpressionNode(it, value) }
        node.right?.let { visitExpressionNode(it, value) }
        return null
    }

    fun visitStringCallExpression(node: StringCallExpression, value: T): R? {
        visitExpressionNode(node.base, value)
        visitExpressionNodes(node.arguments, value)
        return null
    }

    fun visitIndexExpression(node: IndexExpression, value: T): R? {
        visitExpressionNode(node.base, value)
        visitExpressionNode(node.index, value)
        return null
    }

    fun visitTableCallExpression(node: TableCallExpression, value: T): R? {
        visitExpressionNode(node.base, value)
        visitExpressionNodes(node.arguments, value)
        return null
    }

    fun visitArrayConstructorExpression(node: ArrayConstructorExpression, value: T): R? {
        node.values.forEach {
            visitExpressionNode(it, value)
        }
        return null
    }

    fun visitTableConstructorExpression(node: TableConstructorExpression, value: T): R? {
        node.fields.forEach {
            when (it) {
                is TableKeyString -> visitTableKeyString(it, value)
                else -> visitTableKey(it, value)
            }
        }
        return null
    }

    fun visitTableKey(node: TableKey, value: T): R? {
        // key always null
        visitExpressionNode(node.value, value)
        return null
    }

    fun visitTableKeyString(node: TableKeyString, value: T): R? {
        node.key?.let { visitExpressionNode(it, value) }
        visitExpressionNode(node.value, value)
        return null
    }


    fun visitVarargLiteral(node: VarargLiteral, value: T): R? {
        return null
    }

    fun visitIdentifier(node: Identifier, value: T): R? {
        return null
    }

    fun visitUnaryExpression(node: UnaryExpression, value: T): R? {
        visitExpressionNode(node.arg, value)
        return null
    }

    fun visitMemberExpression(node: MemberExpression, value: T): R? {
        visitExpressionNode(node.base, value)
        visitExpressionNode(node.identifier, value)
        return null
    }

    fun visitChunkNode(node: ChunkNode, value: T): R? {
        return visitBlockNode(node.body, value)
    }


    fun visit(node: ChunkNode, value: T): R? = visitChunkNode(node, value)

    fun visitBlockNode(node: BlockNode, value: T): R? {
        node.statements.forEach {
            visitStatementNode(it, value)
        }
        return null
    }
}