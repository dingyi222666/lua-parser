package io.github.dingyi222666.lua.semantic

import io.github.dingyi222666.lua.parser.ast.node.*
import io.github.dingyi222666.lua.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.lua.semantic.symbol.*
import io.github.dingyi222666.lua.typesystem.*

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/
class SemanticAnalyzer : ASTVisitor<BaseASTNode> {

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


    override fun visitCallExpression(node: CallExpression, value: BaseASTNode) {
        when (node) {
            is StringCallExpression -> return visitStringCallExpression(node, value)
            is TableCallExpression -> return visitTableCallExpression(node, value)
        }

        val currentScope = scopeStack.first()
        when (value) {
            is Identifier -> setIdentifierType(
                value, getCallExpressionType(node, currentScope), currentScope
            )

            is MemberExpression -> setMemberExpressionType(
                value, getCallExpressionType(node, currentScope), currentScope
            )
        }
    }


    override fun visitConstantNode(node: ConstantNode, value: BaseASTNode) {
        val currentScope = scopeStack.first()
        when (value) {
            is Identifier -> setIdentifierType(value, node.asType(), currentScope)

            is MemberExpression -> setMemberExpressionType(value, node.asType(), currentScope)

            // return value
            is ReturnStatement -> setReturnStatementType(value, node, node.asType(), currentScope)
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
            is Identifier -> setIdentifierType(
                value,

                resolveExpressionNodeType(node, currentScope) ?: Type.ANY, currentScope
            )

            is MemberExpression -> setMemberExpressionType(
                value, resolveExpressionNodeType(node, currentScope) ?: Type.ANY, currentScope
            )

            is ReturnStatement -> setReturnStatementType(
                value, node, resolveExpressionNodeType(node, currentScope) ?: Type.ANY, currentScope
            )
        }
    }


    override fun visitReturnStatement(node: ReturnStatement, value: BaseASTNode) {
        val tupleType = TupleType(node.arguments.map { Type.ANY })
        val symbol = createStatementSymbol(
            "return", node, tupleType
        )
        val currentScope = scopeStack.first()
        currentScope.addSymbol(symbol)
        super.visitReturnStatement(node, node)
    }

    override fun visitMemberExpression(node: MemberExpression, value: BaseASTNode) {
        val currentScope = scopeStack.first()

        val currentType = resolveMemberExpressionType(node, currentScope)
        when (value) {
            is Identifier -> setIdentifierType(value, currentType, currentScope)

            is MemberExpression -> setMemberExpressionType(value, currentType, currentScope)

            is ReturnStatement -> setReturnStatementType(value, node, currentType, currentScope)
        }
    }

    override fun visitIdentifier(node: Identifier, value: BaseASTNode) {
        val currentScope = scopeStack.first()
        when (value) {
            // params?
            is FunctionDeclaration -> {
                val parameterSymbol = createParamsVariable(node, currentScope)
                if (node.name == "self" && value.params.indexOf(node) == 0 &&
                    value.identifier is MemberExpression
                ) {
                    setSelfType(value, parameterSymbol, currentScope)
                }
            }
            // identifier
            is Identifier -> {
                //  val symbolForVariableName = currentScope.resolveSymbol(value.name, node.range.start)
                val symbolForValue = currentScope.resolveSymbol(node.name, node.range.start)

                val type = unpackType(symbolForValue?.type ?: Type.ANY)

                setIdentifierType(value, type, currentScope)
            }
        }
    }

    override fun visitTableConstructorExpression(node: TableConstructorExpression, value: BaseASTNode) {

        val currentScope = scopeStack.first()
        when (value) {
            is Identifier -> setIdentifierType(
                value,
                getTableConstructorExpressionType(node, value.name),
                currentScope,
            )

            is MemberExpression -> setMemberExpressionType(
                value, getTableConstructorExpressionType(node), currentScope
            )
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
            visitLocalFunctionDeclaration(node, funcType)
        } else {
            visitGlobalFunctionDeclaration(node, value, funcType)
        }

        val funcScope = createFunctionScope(node)

        visitIdentifiers(node.params, node)
        node.body?.let {
            visitBlockNode(it, value)
            it.returnStatement?.let {
                val returnSymbol = funcScope.resolveSymbol("return", funcScope.range.start, true)

                val returnTupleType = returnSymbol?.type as TupleType

                returnTupleType.list().forEach { type ->
                    funcType.addReturnType(type)
                }
                funcScope.removeSymbol(returnSymbol)
            }
        }

        node.params.forEach {
            funcScope.resolveSymbol(it.name, it.range.start)?.let { paramSymbol ->
                val paramType = paramSymbol.type
                if (paramType.typeVariableName == "self" && !node.isLocal && node.identifier is MemberExpression && paramType is ParameterType) {
                    val list = transformationMemberExpressionToList(node.identifier as MemberExpression)
                    paramType.isSelf = true
                    // set to self
                    paramType.realType =
                        funcScope.resolveSymbol(list.first().name, list.first().range.start)?.type ?: paramType.realType
                }
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


    private fun visitGlobalFunctionDeclaration(
        node: FunctionDeclaration, value: BaseASTNode, functionType: FunctionType
    ) {
        val currentScope = scopeStack.first()
        val identifier = node.identifier
        val variable: String

        // function xx() end
        if (identifier is Identifier) {
            variable = identifier.name
            val symbol = currentScope.resolveSymbol(variable, identifier.range.start)
            if (symbol != null) {
                createGlobalFunctionSymbol(identifier, node, functionType)
            }
        }


        if (identifier is MemberExpression) {
            val list = transformationMemberExpressionToList(identifier)
            val isCallSelf =
                list.find {
                    it.parent is MemberExpression && (it.parent as MemberExpression).indexer == ":"
                } != null

            val last = list.first()

            if (currentScope.resolveSymbol(last.name, last.range.start) == null) {
                createUnknownLikeTableSymbol(list)
            }

            functionType.isSelf = isCallSelf

            setMemberExpressionType(identifier, functionType, currentScope, list)
        }


        if (value is MemberExpression) {
            setMemberExpressionType(value, functionType, currentScope)
        }
    }


    private fun visitLocalFunctionDeclaration(
        node: FunctionDeclaration, functionType: FunctionType
    ) {
        val currentScope = scopeStack.first()

        // local function
        if (node.identifier is Identifier && node.isLocal) {
            createLocalFunctionSymbol(node, currentScope, functionType)
        }

    }


    private fun setIdentifierType(identifier: Identifier, targetType: Type, currentScope: Scope) {
        currentScope.resolveSymbol(identifier.name, identifier.range.start)?.let { symbol ->
            symbol.type = targetType.union(symbol.type)
        }
    }

    private fun setReturnStatementType(
        returnStatement: ReturnStatement, node: ExpressionNode, targetType: Type, currentScope: Scope
    ) {
        currentScope.resolveSymbol("return", returnStatement.range.start)?.let {
            val type = it.type as TupleType
            val indexOfParent = returnStatement.arguments.indexOf(node)
            type.set(indexOfParent, targetType)
        }
    }


    private fun getTableConstructorExpressionType(
        node: TableConstructorExpression, name: String = "anonymous"
    ): Type {
        val rootType = TableType(TypeKind.Table, name)
        val tableConstructorStack = ArrayDeque<Pair<TableConstructorExpression, TableType>>()
        var currentType: TableType

        tableConstructorStack.addFirst(node to rootType)

        while (tableConstructorStack.isNotEmpty()) {

            val pair = tableConstructorStack.removeFirst()
            val currentTableConstructor = pair.first
            currentType = pair.second

            for (field in currentTableConstructor.fields) {

                val key = field.key
                val value = field.value

                var keyValue = if (field is TableKeyString) (key as Identifier).name
                else resolveExpressionNodeValue(key)

                val keyType = if (field is TableKeyString) Type.STRING else resolveExpressionNodeType(key)


                if (keyValue is ConstantNode) {
                    keyValue = keyValue.rawValue
                }


                if (value is TableConstructorExpression) {
                    val valueType = TableType(TypeKind.Table, keyValue.toString())
                    currentType.setMember(keyValue.toString(), keyType ?: Type.ANY, valueType)
                    tableConstructorStack.addLast(value to valueType)
                    currentType = valueType
                } else {
                    val valueType = resolveExpressionNodeType(value)
                    if (valueType is Type) {
                        currentType.setMember(keyValue.toString(), keyType ?: Type.ANY, valueType)
                    }
                }

            }

        }

        return rootType
    }

    private fun getCallExpressionType(
        node: CallExpression,
        currentScope: Scope,
    ): Type {

        val callExpressionTypeMap = mutableMapOf<CallExpression, Type>()

        val callExpressionStack = ArrayDeque<CallExpression>()
        val tmpCallExpressionStack = ArrayDeque<CallExpression>()

        tmpCallExpressionStack.add(node)
        callExpressionStack.add(node)

        while (tmpCallExpressionStack.isNotEmpty()) {
            val args = tmpCallExpressionStack.removeFirst().arguments
            val callExpressionList = args.filterIsInstance(CallExpression::class.java)
            if (args.isEmpty() || callExpressionStack.isEmpty()) {
                break
            }
            callExpressionList.forEach {
                callExpressionStack.addFirst(it)
                tmpCallExpressionStack.addFirst(it)
            }

        }

        tmpCallExpressionStack.clear()


        while (callExpressionStack.isNotEmpty()) {
            val callExpression = callExpressionStack.removeFirst()

            val base = callExpression.base
            val args = callExpression.arguments
            val baseType = resolveExpressionNodeType(base, currentScope)

            val callSelf = if (base is MemberExpression) {
                base.indexer == ":"
            } else false

            if (baseType is FunctionType) {
                val params = baseType.parameterTypes
                val paramsSize = params.size


                if (!callSelf && baseType.isSelf && args.isEmpty()) {
                    println("需要使用:来加上self")
                }


                for (i in 0 until paramsSize) {
                    val paramType = params[i]
                    val argNode = args.getOrNull(i) ?: break
                    val argType = if (argNode is CallExpression) {
                        callExpressionTypeMap[argNode] ?: Type.ANY
                    } else resolveExpressionNodeType(argNode)

                    if (argType is Type && paramType is ParameterType) {
                        paramType.realType = argType
                    }
                }

                callExpressionTypeMap[callExpression] = createTupleType(baseType.returnTypes)
            }

            if (baseType is ParameterType) {
                val argsSize = args.size
                if (argsSize != 2) {
                    continue
                }
                val argType = resolveExpressionNodeType(args[0])
                if (argType is Type) {
                    baseType.realType = argType
                }
            }
        }

        val result = callExpressionTypeMap[node] ?: Type.ANY

        callExpressionTypeMap.clear()

        return result
    }

    private fun createTupleType(types: List<Type>): Type {
        if (types.size == 1) {
            return types[0]
        }

        return TupleType(types.distinct())
    }

    private fun setSelfType(declaration: FunctionDeclaration, symbol: ParameterSymbol, currentScope: Scope) {
        val identifier = declaration.identifier as MemberExpression
        val list = transformationMemberExpressionToList(identifier)
        val last = list.first()
        val selfSymbol = currentScope.resolveSymbol(last.name, last.range.start)
        val currentType = resolveMemberExpressionType(identifier,currentScope)
        if (currentType is FunctionType) {
            currentType.isSelf = true
        }
        symbol.type.isSelf = true
        symbol.type.realType = selfSymbol?.type ?: Type.ANY
    }

    private fun resolveMemberExpressionType(
        node: MemberExpression,
        currentScope: Scope
    ): Type {
        val list = transformationMemberExpressionToList(node)
        var last = list.removeFirst()
        val symbol = currentScope.resolveSymbol(last.name, last.range.start)
        var currentType: Type? = unpackType(symbol?.type ?: Type.ANY)

        while (list.isNotEmpty()) {
            last = list.removeFirstOrNull() ?: break

            val lastType = currentType
            currentType = when (currentType) {
                is TableType ->
                    currentType.searchMember(last.name)

                else -> null
            }

            currentType = if (currentType is ParameterType) currentType.realType else currentType

            if (currentType != null) {
                continue
            }

            currentType = when (lastType) {
                is UnknownLikeTableType, is TableType -> {
                    // Why not find the key? Maybe it isn't assigned yet.
                    // So we create a new UnknownLikeTableType and set it to the parent.
                    if (list.size > 0) {
                        Type.ANY
                    } else UnknownLikeTableType(last.name)
                }

                else -> break
            }

            if (lastType is TableType) {
                lastType.setMember(last.name, currentType)
            }

        }

        return unpackType(currentType ?: Type.ANY)
    }

    private fun resolveExpressionNodeType(node: ExpressionNode, scope: Scope = globalScope): Type? {
        val resultType = when (node) {
            is ConstantNode -> node.asType()
            is TableConstructorExpression -> getTableConstructorExpressionType(node)

            is Identifier -> {
                val symbol = scope.resolveSymbol(node.name, node.range.start)

                symbol?.type
            }

            is BinaryExpression -> when (node.operator) {
                ExpressionOperator.CONCAT -> Type.STRING
                ExpressionOperator.AND, ExpressionOperator.OR, ExpressionOperator.NOT -> Type.BOOLEAN

                else -> Type.NUMBER
            }


            is MemberExpression -> resolveMemberExpressionType(node, scope)

            else -> null
        }


        return unpackType(resultType ?: Type.ANY)
    }

    private fun resolveExpressionNodeValue(node: ExpressionNode): Any? {
        return when (node) {
            is ConstantNode -> node.rawValue
            else -> null
        }
    }

    private fun setMemberExpressionType(
        expression: MemberExpression,
        targetType: Type,
        currentScope: Scope,
        list: ArrayDeque<Identifier> = transformationMemberExpressionToList(expression)
    ) {
        var last = list.removeFirst()

        val currentSymbol = currentScope.resolveSymbol(last.name, last.range.start)

        var currentType = currentSymbol?.type

        while (list.size > 1) {
            last = list.removeFirstOrNull() ?: break

            val lastType = currentType
            currentType = when (currentType) {
                is UnknownLikeTableType -> currentType.searchMember(last.name)
                is TableType -> currentType.searchMember(last.name)
                else -> break
            }

            if (currentType != null) {
                continue
            }

            currentType = when (lastType) {
                is UnknownLikeTableType, is TableType -> {
                    // Why not find the key? Maybe it isn't assigned yet.
                    // So we create a new UnknownLikeTableType and set it to the parent.
                    if (list.size > 0) {
                        Type.ANY
                    } else UnknownLikeTableType(last.name)
                }

                else -> break
            }

            if (lastType is TableType) {
                lastType.setMember(last.name, currentType)
            }

        }


        last = list.removeFirst()
        when (currentType) {
            is TableType -> currentType.setMember(last.name, targetType)
        }

    }

    private fun createUnknownLikeTableSymbol(list: ArrayDeque<Identifier>): Symbol<Type>? {
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
        return mainSymbol
    }

    private fun unpackType(type: Type): Type {
        if (type is ParameterType) {
            return type.realType
        }
        return type
    }

    private fun createUnknownLikeTableSymbol(identifier: Identifier): UnknownLikeTableSymbol {
        return UnknownLikeTableSymbol(
            variable = identifier.name,
            range = Range(
                identifier.range.start, globalScope.range.end
            ),
            node = identifier,
        )
    }

    private fun createStatementSymbol(name: String, node: StatementNode, type: Type): StatementSymbol {
        return StatementSymbol(
            variable = name, range = Range(
                node.range.start, globalScope.range.end
            ), node = node, type = type
        )
    }

    private fun createVariableSymbol(identifier: Identifier, scope: Scope): VariableSymbol {
        return VariableSymbol(
            variable = identifier.name, range = Range(
                identifier.range.start, scope.range.end
            ), node = identifier, type = Type.UnDefined, isLocal = true
        )
    }

    private fun createGlobalFunctionSymbol(
        identifier: Identifier, node: FunctionDeclaration, targetType: FunctionType
    ): FunctionSymbol {
        return FunctionSymbol(
            variable = identifier.name,
            range = Range(
                identifier.range.start, globalScope.range.end
            ),
            node = node,
            type = targetType,
        ).apply {
            isLocal = false
        }
    }

    private fun createParamsVariable(node: Identifier, currentScope: Scope): ParameterSymbol {
        // val indexOfParent = value.params.indexOf(node)
        val symbol = ParameterSymbol(
            variable = node.name,
            range = currentScope.range,
            node = node,
        )
        currentScope.addSymbol(symbol)
        return symbol
    }

    private fun createLocalVariable(identifier: Identifier) {
        val currentScope = scopeStack.first()

        val symbol = createVariableSymbol(identifier, currentScope)
        currentScope.addSymbol(symbol)
    }

    private fun createGlobalVariable(identifier: Identifier) {
        val symbol = VariableSymbol(
            variable = identifier.name,
            range = globalScope.range,
            node = identifier,
            type = Type.UnDefined,
            isLocal = false
        )
        globalScope.addSymbol(symbol)
    }

    private fun createLocalFunctionSymbol(
        node: FunctionDeclaration, currentScope: Scope, functionType: FunctionType
    ): FunctionSymbol {
        val identifier = node.identifier as Identifier

        functionType.typeVariableName = identifier.name
        val symbol = FunctionSymbol(
            variable = identifier.name, range = Range(
                identifier.range.start, currentScope.range.end
            ), node = node, type = functionType
        )
        currentScope.addSymbol(symbol)
        return symbol
    }
}