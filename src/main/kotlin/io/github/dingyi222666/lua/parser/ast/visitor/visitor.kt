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
            is TableKeyString -> visitTableKeyString(node, value)
            is TableKey -> visitTableKey(node, value)
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
        node.returnStatement?.let { visitStatementNode(it, value) }
    }
}

interface ASTModifier<T> {
    fun visitIfStatement(node: IfStatement, value: T): IfStatement {
        node.causes.forEachIndexed { index, it ->
            node.causes[index] = when (it) {
                is ElseClause -> visitElseClause(it, value)
                is ElseIfClause -> visitElseIfClause(it, value)
                else -> visitIfClause(it, value)
            }.also {
                it.parent = node
            }
        }
        return node
    }

    fun visitGotoStatement(node: GotoStatement, value: T): GotoStatement {
        node.identifier = visitIdentifier(node.identifier, value).also {
            it.parent = node
        }
        return node
    }

    fun visitLabelStatement(node: LabelStatement, value: T): LabelStatement {
        node.identifier = visitIdentifier(node.identifier, value).also {
            it.parent = node
        }
        return node
    }

    fun visitBreakStatement(node: BreakStatement, value: T): BreakStatement {
        return node
    }

    fun visitContinueStatement(node: ContinueStatement, value: T): ContinueStatement {
        return node
    }

    fun visitWhileStatement(node: WhileStatement, value: T): WhileStatement {
        node.condition = visitExpressionNode(node.condition, value).also {
            it.parent = node
        }
        node.body = visitBlockNode(node.body, value).also {
            it.parent = node
        }
        return node
    }

    fun visitDoStatement(node: DoStatement, value: T): DoStatement {
        node.body = visitBlockNode(node.body, value).also {
            it.parent = node
        }
        return node
    }

    fun visitForGenericStatement(node: ForGenericStatement, value: T): ForGenericStatement {
        node.variables.forEachIndexed { index, identifier ->
            node.variables[index] = visitIdentifier(identifier, value).also {
                it.parent = node
            }
        }
        node.iterators.forEachIndexed { index, iterator ->
            node.iterators[index] = visitExpressionNode(iterator, value).also {
                it.parent = node
            }
        }
        node.body = visitBlockNode(node.body, value).also {
            it.parent = node
        }
        return node
    }

    fun visitForNumericStatement(node: ForNumericStatement, value: T): ForNumericStatement {
        node.variable = visitIdentifier(node.variable, value).also {
            it.parent = node
        }
        node.step = visitExpressionNode(node.start, value).also {
            it.parent = node
        }
        node.end = visitExpressionNode(node.end, value).also {
            it.parent = node
        }
        node.step = node.step?.let {
            val expr = visitExpressionNode(it, value)
            expr.parent = node
            expr
        }
        node.body = visitBlockNode(node.body, value).also {
            it.parent = node
        }
        return node
    }

    fun visitWhenStatement(node: WhenStatement, value: T): WhenStatement {
        node.condition = visitExpressionNode(node.condition, value).also {
            it.parent = node
        }

        node.ifCause = visitStatementNode(node.ifCause, value).also {
            it.parent = node
        }

        node.elseCause = node.elseCause?.let {
            val stat = visitStatementNode(it, value)

            stat.parent = node

            stat
        }
        return node
    }

    fun visitRepeatStatement(node: RepeatStatement, value: T): RepeatStatement {
        node.body = visitBlockNode(node.body, value).also {
            it.parent = node
        }
        node.condition = visitExpressionNode(node.condition, value).also {
            it.parent = node
        }
        return node
    }

    fun visitReturnStatement(node: ReturnStatement, value: T): ReturnStatement {

        node.arguments.forEachIndexed { index, identifier ->
            node.arguments[index] = visitExpressionNode(identifier, value).also {
                it.parent = node
            }
        }
        return node
    }

    fun visitCallStatement(node: CallStatement, value: T): CallStatement {
        node.expression = visitCallExpression(node.expression, value).also {
            it.parent = node
        }
        return node
    }

    fun visitAssignmentStatement(node: AssignmentStatement, value: T): AssignmentStatement {
        node.init.forEachIndexed { index, identifier ->
            node.init[index] = visitExpressionNode(identifier, value).also {
                it.parent = node
            }
        }
        node.variables.forEachIndexed { index, identifier ->
            node.variables[index] = visitExpressionNode(identifier, value).also {
                it.parent = node
            }
        }
        return node
    }

    fun visitSwitchStatement(node: SwitchStatement, value: T): SwitchStatement {
        node.condition = visitExpressionNode(node.condition, value).also {
            it.parent = node
        }
        node.causes.forEachIndexed { index, it ->
            node.causes[index] = when (it) {
                is DefaultCause -> visitDefaultCause(it, value)
                is CaseCause -> visitCaseCause(it, value)
                else -> throw Exception("Unknown cause type $it")
            }.also {
                it.parent = node
            }
        }
        return node
    }


    fun visitLocalStatement(node: LocalStatement, value: T): LocalStatement {
        node.init.forEachIndexed { index, identifier ->
            node.init[index] = visitIdentifier(identifier, value).also {
                it.parent = node
            }
        }
        node.variables.forEachIndexed { index, identifier ->
            node.variables[index] = visitExpressionNode(identifier, value).also {
                it.parent = node
            }
        }
        return node
    }

    fun visitFunctionDeclaration(node: FunctionDeclaration, value: T): FunctionDeclaration {
        node.identifier = node.identifier?.let {
            val expr = visitExpressionNode(it, value)
            expr.parent = node
            expr
        }
        node.params.forEachIndexed { index, identifier ->
            node.params[index] = visitIdentifier(identifier, value).also {
                it.parent = node
            }
        }
        node.body = node.body?.let {
            val block = visitBlockNode(it, value)
            block.parent = node
            block
        }
        return node
    }

    fun visitLambdaDeclaration(node: LambdaDeclaration, value: T): LambdaDeclaration {
        node.params.forEachIndexed { index, identifier ->
            node.params[index] = visitIdentifier(identifier, value)
        }
        node.expression = visitExpressionNode(node.expression, value)
        return node
    }

    fun visitIfClause(node: IfClause, value: T): IfClause {
        node.condition = visitExpressionNode(node.condition, value).also {
            it.parent = node
        }
        node.body = visitBlockNode(node.body, value).also {
            it.parent = node
        }
        return node
    }

    fun visitElseClause(node: ElseClause, value: T): ElseClause {
        node.body = visitBlockNode(node.body, value).also {
            it.parent = node
        }
        return node
    }

    fun visitElseIfClause(node: ElseIfClause, value: T): ElseIfClause {
        node.condition = visitExpressionNode(node.condition, value).also {
            it.parent = node
        }
        node.body = visitBlockNode(node.body, value).also {
            it.parent = node
        }
        return node
    }

    fun visitCaseCause(node: CaseCause, value: T): CaseCause {
        node.conditions.forEachIndexed { index, expressionNode ->
            node.conditions[index] = visitExpressionNode(expressionNode, value).also {
                it.parent = node
            }
        }
        node.body = visitBlockNode(node.body, value).also {
            it.parent = node
        }
        return node
    }

    fun visitDefaultCause(node: DefaultCause, value: T): DefaultCause {
        node.body = visitBlockNode(node.body, value).also {
            it.parent = node
        }
        return node
    }

    fun visitConstantNode(node: ConstantNode, value: T): ConstantNode {
        return node
    }

    fun visitStatementNode(node: StatementNode, value: T): StatementNode {
        return when (node) {
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
            else -> throw Exception("Unknown statement type $node")
        }

    }

    fun visitExpressionNode(node: ExpressionNode, value: T): ExpressionNode {
        return when (node) {
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
            is TableKeyString -> visitTableKeyString(node, value)
            is TableKey -> visitTableKey(node, value)
            else -> throw Exception("Unknown expression type $node")
        }
    }


    fun visitCallExpression(node: CallExpression, value: T): CallExpression {
        when (node) {
            is StringCallExpression -> return visitStringCallExpression(node, value).also {
                it.parent = node
            }

            is TableCallExpression -> return visitTableCallExpression(node, value).also {
                it.parent = node
            }
        }
        node.base = visitExpressionNode(node.base, value).also {
            it.parent = node
        }

        node.arguments.forEachIndexed { index, expressionNode ->
            node.arguments[index] = visitExpressionNode(expressionNode, value).also {
                it.parent = node
            }
        }
        return node
    }

    fun visitBinaryExpression(node: BinaryExpression, value: T): BinaryExpression {
        node.left = node.left?.let {
            val left = visitExpressionNode(it, value)
            left.parent = node
            left
        }
        node.right = node.right?.let {
            val right = visitExpressionNode(it, value)
            right.parent = node
            right
        }
        return node
    }

    fun visitStringCallExpression(node: StringCallExpression, value: T): StringCallExpression {
        node.base = visitExpressionNode(node.base, value)

        node.arguments.forEachIndexed { index, expressionNode ->
            node.arguments[index] = visitExpressionNode(expressionNode, value).also {
                it.parent = node
            }
        }

        return node
    }

    fun visitIndexExpression(node: IndexExpression, value: T): IndexExpression {
        node.base = visitExpressionNode(node.base, value).also {
            it.parent = node
        }
        node.index = visitExpressionNode(node.index, value).also {
            it.parent = node
        }

        return node
    }

    fun visitTableCallExpression(node: TableCallExpression, value: T): TableCallExpression {
        node.base = visitExpressionNode(node.base, value).also {
            it.parent = node
        }


        node.arguments.forEachIndexed { index, expressionNode ->
            node.arguments[index] = visitExpressionNode(expressionNode, value).also {
                it.parent = node
            }
        }

        return node
    }

    fun visitArrayConstructorExpression(node: ArrayConstructorExpression, value: T): ArrayConstructorExpression {
        node.values.forEachIndexed { index, expressionNode ->
            node.values[index] = visitExpressionNode(expressionNode, value).also {
                it.parent = node
            }
        }

        return node
    }

    fun visitTableConstructorExpression(node: TableConstructorExpression, value: T): TableConstructorExpression {
        node.fields.forEachIndexed { index, it ->
            node.fields[index] = when (it) {
                is TableKeyString -> visitTableKeyString(it, value)
                else -> visitTableKey(it, value)
            }.also {
                it.parent = node
            }
        }
        return node

    }

    fun visitTableKey(node: TableKey, value: T): TableKey {
        // key always null
        node.value = visitExpressionNode(node.value, value).also {
            it.parent = node
        }
        return node
    }

    fun visitTableKeyString(node: TableKeyString, value: T): TableKeyString {
        node.key = visitExpressionNode(node.key, value).also {
            it.parent = node
        }
        node.value = visitExpressionNode(node.value, value).also {
            it.parent = node
        }
        return node
    }


    fun visitVarargLiteral(node: VarargLiteral, value: T): VarargLiteral {
        return node
    }

    fun visitIdentifier(node: Identifier, value: T): Identifier {
        return node
    }

    fun visitUnaryExpression(node: UnaryExpression, value: T): UnaryExpression {
        node.arg = visitExpressionNode(node.arg, value).also {
            it.parent = node
        }
        return node
    }

    fun visitMemberExpression(node: MemberExpression, value: T): MemberExpression {
        node.base = visitExpressionNode(node.base, value).also {
            it.parent = node
        }
        node.identifier = visitIdentifier(node.identifier, value).also {
            it.parent = node
        }
        return node
    }

    fun visitChunkNode(node: ChunkNode, value: T): ChunkNode {
        node.body = visitBlockNode(node.body, value).apply {
            parent = node
        }
        return node
    }


    fun visitBlockNode(node: BlockNode, value: T): BlockNode {
        node.statements.forEachIndexed { index, it ->
            node.statements[index] = visitStatementNode(it, value)
        }

        val returnStatement = node.returnStatement

        if (returnStatement != null) {
            node.returnStatement = (visitStatementNode(returnStatement, value) as ReturnStatement).apply {
                parent = node
            }
        }

        return node
    }
}