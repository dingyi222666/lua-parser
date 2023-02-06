package io.github.dingyi222666.lua.symbol

import io.github.dingyi222666.lua.parser.ast.node.*
import io.github.dingyi222666.lua.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.lua.typesystem.*
import kotlin.reflect.typeOf

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

    private fun createFunctionScope(node: BaseASTNode): FunctionScope {
        val parent = scopeStack.first()
        val localScope = FunctionScope(parent, node.range)
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
        visitChunkNode(node, node)
        return globalScope
    }


    override fun visitChunkNode(node: ChunkNode, value: BaseASTNode) {
        globalScope.range = node.range
        createFunctionScope(node)
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
            is Identifier -> setIdentifierType(value, currentScope, node.asType())

            is MemberExpression -> setMemberExpressionType(value, node.asType(), currentScope)
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
            is Identifier -> setIdentifierType(value, currentScope, getBinaryBinaryExpressionType(node))
            is MemberExpression -> setMemberExpressionType(value, getBinaryBinaryExpressionType(node), currentScope)
        }
    }


    override fun visitIdentifier(node: Identifier, value: BaseASTNode) {
        val currentScope = scopeStack.first()
        when (value) {
            // params?
            is FunctionDeclaration -> {
                // val symbol = currentScope.resolveSymbol(node.name, node.range.start)
                /*if (symbol != null) {
                    error(node) { "redefinition of '${node.name}'" }
                }*/
                createParamsVariable(node, currentScope)
            }
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

                val first = list.first()

                val findAssignedSymbol = currentScope.resolveSymbol(first.name, first.range.start)

                if (findAssignedSymbol != null) {
                    continue
                }
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

        val funcType = FunctionType("anonymous")

        if (node.isLocal) {
            visitLocalFunctionDeclaration(node, value, funcType)
        }
        val funcScope = createFunctionScope(node)
        visitIdentifiers(node.params, node)
        node.body?.let {
            visitBlockNode(it, value)
        }

        node.params.forEach {
            funcScope.resolveSymbol(it.name, it.range.start)?.let { paramSymbol ->
                funcType.addParamType(paramSymbol.type)
            }
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


    private fun visitLocalFunctionDeclaration(
        node: FunctionDeclaration,
        value: BaseASTNode,
        functionType: FunctionType
    ) {
        val currentScope = scopeStack.first()

        // local function
        if (node.identifier is Identifier && node.isLocal) {
            createLocalFunctionSymbol(node, currentScope, functionType)
        }
    }


    private fun setIdentifierType(identifier: Identifier, currentScope: Scope, targetType: Type) {
        currentScope.resolveSymbol(identifier.name, identifier.range.start)
            ?.let { symbol ->
                setType(symbol, targetType)
            }
    }


    private fun setMemberExpressionType(expression: MemberExpression, targetType: Type, currentScope: Scope) {
        val list = transformationMemberExpressionToList(expression)
        val first = list.removeFirst()

        val currentSymbol = currentScope.resolveSymbol(first.name, first.range.start)

        var currentType = currentSymbol?.type

        while (list.size < 2) {
            val identifier = list.removeFirst()

            currentType = when (currentType) {
                is UnknownLikeTableType -> currentType.searchMember(identifier.name)

                else -> break
            }
        }

        when (currentType) {
            is UnknownLikeTableType -> currentType.setMember(list.removeFirst().name, targetType)
        }

    }


    private fun setType(symbol: Symbol<Type>, type: Type) {
        val originType = symbol.type
        val setType = originType.union(type)
        symbol.type = setType
    }


    private fun getBinaryBinaryExpressionType(expression: BinaryExpression): Type {
        return when (expression.operator) {
            // ExpressionOperator.CONCAT -> BaseType.STRING
            ExpressionOperator.AND,
            ExpressionOperator.OR,
            ExpressionOperator.NOT -> Type.BOOLEAN

            else -> Type.NUMBER
        }

    }

    private fun createUnknownLikeTableSymbol(list: ArrayDeque<Identifier>) {
        var identifier = list.removeFirst()
        var mainSymbol = globalScope.resolveSymbol(identifier.name, identifier.range.start)

        if (mainSymbol == null) {
            mainSymbol = createUnknownLikeTableSymbol(identifier) as Symbol<Type>?
        }
        var parentType = mainSymbol?.type

        for (index in list.indices) {
            identifier = list[index]

            var currentType: Type?

            if (parentType is UnknownLikeTableType) {
                currentType = parentType.searchMember(identifier.name)

                if (index < list.lastIndex && currentType is TableType) {
                    parentType = currentType
                    continue
                }
            }

            currentType = if (index == list.lastIndex) {
                Type.ANY
            } else {
                UnknownLikeTableType(identifier.name)
            }

            if (parentType is UnknownLikeTableType) {
                parentType.setMember(identifier.name, currentType)
            }

            parentType = currentType
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
            type = Type.UnDefined,
            isLocal = true
        )
    }


    private fun createParamsVariable(node: Identifier, currentScope: Scope) {
        // val indexOfParent = value.params.indexOf(node)
        val symbol = ParameterSymbol(
            variable = node.name,
            range = currentScope.range,
            node = node,
        )
        currentScope.addSymbol(symbol)
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
            type = Type.UnDefined,
            isLocal = false
        )
        globalScope.addSymbol(symbol)
    }

    private fun createLocalFunctionSymbol(
        node: FunctionDeclaration,
        currentScope: Scope,
        functionType: FunctionType
    ): FunctionSymbol {
        val identifier = node.identifier as Identifier

        functionType.typeVariableName = identifier.name

        val symbol = FunctionSymbol(
            variable = identifier.name,
            // 范围是整个作用域
            range = Range(
                identifier.range.start,
                currentScope.range.end
            ),
            node = node,
            type = functionType
        )
        currentScope.addSymbol(symbol)
        return symbol
    }
}