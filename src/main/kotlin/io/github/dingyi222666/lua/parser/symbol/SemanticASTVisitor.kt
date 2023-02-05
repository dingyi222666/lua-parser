package io.github.dingyi222666.lua.parser.symbol

import io.github.dingyi222666.lua.parser.ast.node.*
import io.github.dingyi222666.lua.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.lua.parser.typesystem.BaseType
import io.github.dingyi222666.lua.parser.typesystem.asType
import io.github.dingyi222666.lua.parser.util.require

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/
class SemanticASTVisitor : ASTVisitor<BaseASTNode> {

    private val scopeStack = ArrayDeque<Scope>()

    private val globalScope = GlobalScope(range = Range.EMPTY)

    private fun createLocalScope(node: BaseASTNode): LocalScope {
        val parent = scopeStack.first()
        val localScope = LocalScope(parent, node.range)
        scopeStack.addFirst(localScope)
        globalScope.addScope(localScope)
        return localScope
    }

    private fun createLoopScope(node: BaseASTNode): LoopScope {
        val parent = scopeStack.first()
        val loopScope = LoopScope(parent, node.range)
        scopeStack.addFirst(loopScope)
        globalScope.addScope(loopScope)
        return loopScope
    }

    private fun destroyScope() {
        scopeStack.removeFirst()
    }

    private inline fun error(node: BaseASTNode, crossinline messageBuilder: () -> String) {
        error("${node.range.start}: ${messageBuilder()}")
    }

    fun analyze(node: ChunkNode): GlobalScope {
        globalScope.range = node.range
        scopeStack.addFirst(globalScope)
        createLocalScope(node)
        visitChunkNode(node, node)
        return globalScope
    }


    override fun visitChunkNode(node: ChunkNode, value: BaseASTNode) {
        globalScope.range = node.range
        scopeStack.addFirst(globalScope)
        createLocalScope(node)
        super.visitChunkNode(node, value)
        destroyScope()
    }

    override fun visitBlockNode(node: BlockNode, value: BaseASTNode) {
        super.visitBlockNode(node, node)
        destroyScope()
    }

    override fun visitStatementNode(node: StatementNode, value: BaseASTNode) {
        super.visitStatementNode(node, node)
    }

    override fun visitLocalStatement(node: LocalStatement, value: BaseASTNode) {
        node.init.forEach {
            createLocalVariable(it)
        }

        for (index in node.variables.indices) {
            val varNode = node.variables[index]
            val initNode = node.init.getOrNull(index)
            if (initNode != null) {
                visitExpressionNode(varNode, initNode)
            }
        }
    }

    override fun visitConstantNode(node: ConstantNode, value: BaseASTNode) {
        val currentScope = scopeStack.first()
        when (value) {
            is Identifier -> setIdentifierType(value, node, currentScope)

            is MemberExpression -> setMemberExpressionType(value, node, currentScope)

        }
    }

    override fun visitWhileStatement(node: WhileStatement, value: BaseASTNode) {
        createLoopScope(node.body)
        super.visitWhileStatement(node, node)
    }

    override fun visitBreakStatement(node: BreakStatement, value: BaseASTNode) {
        val currentScope = scopeStack.first()
        if (currentScope !is LoopScope) {
            error(node) { "no loop to break" }
        }
    }

    override fun visitContinueStatement(node: ContinueStatement, value: BaseASTNode) {
        val currentScope = scopeStack.first()
        if (currentScope !is LoopScope) {
            error(node) { "no loop to continue" }
        }
    }

    override fun visitBinaryExpression(node: BinaryExpression, value: BaseASTNode) {
        val currentScope = scopeStack.first()
        when (value) {
            is Identifier -> setIdentifierType(value, node, currentScope)

            // is MemberExpression -> setMemberExpressionType(value, node, currentScope)

        }
    }


    override fun visitAssignmentStatement(node: AssignmentStatement, value: BaseASTNode) {
        val currentScope = scopeStack.first()
        for (initNode in node.init) {
            if (initNode is Identifier) {
                if (currentScope.resolveSymbol(initNode.name, initNode.range.start) != null) {
                    continue
                }
                createGlobalVariable(initNode)
            }
            if (initNode is MemberExpression) {
                val list = transformationMemberExpressionToList(initNode)

                //TODO: 检查是否为局部变量或者全局变量的table类型
                createUnknownLikeTableSymbol(list)

            }
        }

        for (index in node.variables.indices) {
            val varNode = node.variables[index]
            node.init.getOrNull(index)?.let { initNode ->
                visitExpressionNode(varNode, initNode)
            }
        }
    }

    override fun visitFunctionDeclaration(node: FunctionDeclaration, value: BaseASTNode) {
        if (node.isLocal) {
            visitLocalFunctionDeclaration(node, value)
        }
        node.body?.let {
            createLocalScope(it)
            visitBlockNode(it, value)
        }
    }


    private fun transformationMemberExpressionToList(node: MemberExpression): ArrayDeque<Identifier> {
        val result = ArrayDeque<Identifier>()

        var currentNode: ExpressionNode = node

        while (currentNode !is Identifier) {
            val memberExpression = currentNode as MemberExpression
            result.addFirst(memberExpression.identifier)
            currentNode = memberExpression.base
        }

        result.addFirst(currentNode)


        return result
    }


    private fun visitLocalFunctionDeclaration(node: FunctionDeclaration, value: BaseASTNode) {
        val currentScope = scopeStack.first()
        if (node.identifier is Identifier) {
            val identifier = node.identifier as Identifier
            if (currentScope.resolveSymbol(identifier.name) == null) {
                createLocalFunctionName(node)
            }
        }
    }


    private fun setIdentifierType(identifier: Identifier, expression: BinaryExpression, currentScope: Scope) {
        TODO("Not yet implemented")
    }

    private fun setMemberExpressionType(value: MemberExpression, node: ConstantNode, currentScope: Scope) {
        val list = transformationMemberExpressionToList(value)
        val first = list.removeFirst()
        var currentSymbol = currentScope.resolveSymbol(first.name, first.range.start)

        while (list.isNotEmpty()) {
            val identifier = list.removeFirst()

            currentSymbol = when (currentSymbol) {
                is UnknownLikeTableSymbol ->
                    currentSymbol.getKeyValue(identifier.name)

                else -> null
            }
        }
        currentSymbol?.type = node.asType()

    }

    private fun setIdentifierType(identifier: Identifier, constant: ConstantNode, currentScope: Scope) {
        currentScope.resolveSymbol(identifier.name, identifier.range.start)?.let { symbol ->
            symbol.type = constant.asType()
        }
    }

    private fun createUnknownLikeTableSymbol(list: ArrayDeque<Identifier>) {
        var parentSymbol: Symbol? = null
        for (index in list.indices) {
            val identifier = list[index]
            println(identifier)
            // variable ?

            if (parentSymbol == null &&
                (globalScope.resolveSymbol(identifier.name, identifier.range.start) != null)
            ) {
                continue
            } else if (parentSymbol is UnknownLikeTableSymbol &&
                parentSymbol.getKeyValue(identifier.name) != null
            ) {
                continue
            }

            val symbol = if (index < list.lastIndex) {
                createUnknownLikeTableSymbol(identifier)
            } else {
                createVariableSymbol(identifier, globalScope)
            }

            if (parentSymbol is VariableSymbol) {
                val parentIdentifier = list.getOrNull(index - 1)

                parentSymbol = createUnknownLikeTableSymbol(parentIdentifier.require())

                val topSymbol = list.getOrNull(index - 2)?.let {
                    globalScope.resolveSymbol(it.name, identifier.range.start)
                }

                if (topSymbol == null) {
                    globalScope.renameSymbol(parentSymbol.variable, parentSymbol)
                } else if (topSymbol is UnknownLikeTableSymbol) {
                    topSymbol.addKeyValue(parentSymbol.variable, parentSymbol)
                }

            }


            if (parentSymbol == null) {
                globalScope.addSymbol(symbol)
            } else if (parentSymbol is UnknownLikeTableSymbol) {

                parentSymbol.addKeyValue(symbol.variable, symbol)
            }

            parentSymbol = symbol
        }
    }

    private fun createUnknownLikeTableSymbol(identifier: Identifier): UnknownLikeTableSymbol {
        return UnknownLikeTableSymbol(
            variable = identifier.name,
            range = Range(
                identifier.range.start,
                globalScope.range.end
            ),
            node = identifier,
        )
    }

    private fun createVariableSymbol(identifier: Identifier, scope: Scope): VariableSymbol {
        return VariableSymbol(
            variable = identifier.name,
            range = Range(
                identifier.range.start,
                scope.range.end
            ),
            node = identifier,
            type = BaseType.ANY,
            isLocal = true
        )
    }


    private fun createLocalVariable(identifier: Identifier) {
        val currentScope = scopeStack.first()

        val symbol = createVariableSymbol(identifier, currentScope)
        currentScope.addSymbol(symbol)
    }

    private fun createGlobalVariable(identifier: Identifier) {
        val symbol = VariableSymbol(
            variable = identifier.name,
            // 范围是整个作用域
            range = globalScope.range,
            node = identifier,
            type = BaseType.ANY,
            isLocal = false
        )
        globalScope.addSymbol(symbol)
    }

    private fun createLocalFunctionName(node: FunctionDeclaration) {
        val currentScope = scopeStack.first()
        val identifier = node.identifier as Identifier

        val symbol = createVariableSymbol(identifier, currentScope)
        currentScope.addSymbol(symbol)
    }
}