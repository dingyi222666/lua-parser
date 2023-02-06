package io.github.dingyi222666.lua.parser.ast.visitor

import io.github.dingyi222666.lua.parser.ast.node.*

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/

interface ASTVisitor<T> {

    fun visitIfStatement(node: IfStatement, value: T) {
        node.causes.forEach {
            when (it) {
                is ElseClause -> visitElseClause(it, value)
                is ElseIfClause -> visitElseIfClause(it, value)
                else -> visitIfClause(it, value)
            }
        }
    }

    fun visitGotoStatement(node: GotoStatement, value: T) {
        visitIdentifier(node.identifier, value)
    }

    fun visitLabelStatement(node: LabelStatement, value: T) {
        visitIdentifier(node.identifier, value)
    }

    fun visitBreakStatement(node: BreakStatement, value: T) {

    }

    fun visitContinueStatement(node: ContinueStatement, value: T) {

    }

    fun visitWhileStatement(node: WhileStatement, value: T) {
        visitExpressionNode(node.condition, value)
        visitBlockNode(node.body, value)
    }

    fun visitDoStatement(node: DoStatement, value: T) {
        visitBlockNode(node.body, value)

    }

    fun visitForGenericStatement(node: ForGenericStatement, value: T) {
        visitIdentifiers(node.variables, value)
        visitExpressionNodes(node.iterators, value)
        visitBlockNode(node.body, value)

    }

    fun visitForNumericStatement(node: ForNumericStatement, value: T) {
        visitIdentifier(node.variable, value)
        visitExpressionNode(node.start, value)
        visitExpressionNode(node.end, value)
        node.step?.let {
            visitExpressionNode(it, value)
        }
        visitBlockNode(node.body, value)

    }

    fun visitWhenStatement(node: WhenStatement, value: T) {
        visitExpressionNode(node.condition, value)
        visitStatementNode(node.ifCause, value)
        node.elseCause?.let {
            visitStatementNode(it, value)
        }
    }

    fun visitRepeatStatement(node: RepeatStatement, value: T) {
        visitBlockNode(node.body, value)
        visitExpressionNode(node.condition, value)

    }

    fun visitReturnStatement(node: ReturnStatement, value: T) {
        visitExpressionNodes(node.arguments, value)
    }

    fun visitCallStatement(node: CallStatement, value: T) {
        visitExpressionNode(node.expression, value)

    }

    fun visitAssignmentStatement(node: AssignmentStatement, value: T) {
        visitExpressionNodes(node.init, value)
        visitExpressionNodes(node.variables, value)

    }

    fun visitSwitchStatement(node: SwitchStatement, value: T) {
        visitExpressionNode(node.condition, value)
        node.causes.forEach {
            when (it) {
                is DefaultCause -> visitDefaultCause(it, value)
                is CaseCause -> visitCaseCause(it, value)
            }
        }
    }


    fun visitLocalStatement(node: LocalStatement, value: T) {
        visitIdentifiers(node.init, value)
        visitExpressionNodes(node.variables, value)

    }

    fun visitFunctionDeclaration(node: FunctionDeclaration, value: T) {
        node.identifier?.let { visitExpressionNode(it, value) }
        visitIdentifiers(node.params, value)
        node.body?.let { visitBlockNode(it, value) }

    }

    fun visitLambdaDeclaration(node: LambdaDeclaration, value: T) {
        visitIdentifiers(node.params, value)
        visitExpressionNode(node.expression, value)
    }

    fun visitIfClause(node: IfClause, value: T) {
        visitExpressionNode(node.condition, value)
        visitBlockNode(node.body, value)

    }

    fun visitElseClause(node: ElseClause, value: T) {
        visitBlockNode(node.body, value)

    }

    fun visitElseIfClause(node: ElseIfClause, value: T) {
        visitExpressionNode(node.condition, value)
        visitBlockNode(node.body, value)

    }

    fun visitCaseCause(node: CaseCause, value: T) {
        visitExpressionNodes(node.conditions, value)
        visitBlockNode(node.body, value)
    }

    fun visitDefaultCause(node: DefaultCause, value: T) {
        visitBlockNode(node.body, value)

    }

    fun visitConstantNode(node: ConstantNode, value: T) {

    }

    fun visitStatementNode(node: StatementNode, value: T) {
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

    }

    fun visitExpressionNode(node: ExpressionNode, value: T) {
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
    }

    fun visitIdentifiers(list: List<Identifier>, value: T) {
        list.forEach {
            visitIdentifier(it, value)
        }
    }


    fun visitExpressionNodes(list: List<ExpressionNode>, value: T) {
        list.forEach {
            visitExpressionNode(it, value)
        }
    }

    fun visitCallExpression(node: CallExpression, value: T) {
        when (node) {
            is StringCallExpression -> return visitStringCallExpression(node, value)
            is TableCallExpression -> return visitTableCallExpression(node, value)
        }
        visitExpressionNode(node.base, value)
        visitExpressionNodes(node.arguments, value)

    }

    fun visitBinaryExpression(node: BinaryExpression, value: T) {
        node.left?.let { visitExpressionNode(it, value) }
        node.right?.let { visitExpressionNode(it, value) }
    }

    fun visitStringCallExpression(node: StringCallExpression, value: T) {
        visitExpressionNode(node.base, value)
        visitExpressionNodes(node.arguments, value)
    }

    fun visitIndexExpression(node: IndexExpression, value: T) {
        visitExpressionNode(node.base, value)
        visitExpressionNode(node.index, value)

    }

    fun visitTableCallExpression(node: TableCallExpression, value: T) {
        visitExpressionNode(node.base, value)
        visitExpressionNodes(node.arguments, value)
    }

    fun visitArrayConstructorExpression(node: ArrayConstructorExpression, value: T) {
        node.values.forEach {
            visitExpressionNode(it, value)
        }

    }

    fun visitTableConstructorExpression(node: TableConstructorExpression, value: T) {
        node.fields.forEach {
            when (it) {
                is TableKeyString -> visitTableKeyString(it, value)
                else -> visitTableKey(it, value)
            }
        }

    }

    fun visitTableKey(node: TableKey, value: T) {
        // key always null
        visitExpressionNode(node.value, value)

    }

    fun visitTableKeyString(node: TableKeyString, value: T) {
        visitExpressionNode(node.key, value)
        visitExpressionNode(node.value, value)
    }


    fun visitVarargLiteral(node: VarargLiteral, value: T) {

    }

    fun visitIdentifier(node: Identifier, value: T) {

    }

    fun visitUnaryExpression(node: UnaryExpression, value: T) {
        visitExpressionNode(node.arg, value)

    }

    fun visitMemberExpression(node: MemberExpression, value: T) {
        visitExpressionNode(node.base, value)
        visitExpressionNode(node.identifier, value)

    }

    fun visitChunkNode(node: ChunkNode, value: T) {
        return visitBlockNode(node.body, value)
    }


    fun visitBlockNode(node: BlockNode, value: T) {
        node.statements.forEach {
            visitStatementNode(it, value)
        }
        node.returnStatement?.let { visitStatementNode(it,value) }
    }
}