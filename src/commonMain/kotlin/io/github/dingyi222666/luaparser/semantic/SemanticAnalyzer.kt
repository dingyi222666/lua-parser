package io.github.dingyi222666.luaparser.semantic

import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.symbol.*
import io.github.dingyi222666.luaparser.semantic.typesystem.*

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/
class SemanticAnalyzer : ASTVisitor<BaseASTNode> {

    private val scopeStack = ArrayDeque<Scope>()

    private val globalScope = GlobalScope(range = Range.EMPTY)

    private fun createLocalScope(node: BaseASTNode): LocalScope {
        val parent = currentScope
        val localScope = LocalScope(parent, node.range)
        scopeStack.addFirst(localScope)
        globalScope.addScope(localScope)
        return localScope
    }

    private fun createFunctionScope(node: BaseASTNode): FunctionScope {
        val parent = currentScope
        val localScope = FunctionScope(parent, node.range)
        scopeStack.addFirst(localScope)
        globalScope.addScope(localScope)
        return localScope
    }


    private fun createLoopScope(node: BaseASTNode): LoopScope {
        val parent = currentScope
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
        //createFunctionScope(node)
        super.visitChunkNode(node, value)
        //destroyScope()
    }

    override fun visitBlockNode(node: BlockNode, value: BaseASTNode) {
        super.visitBlockNode(node, node)
        destroyScope()
    }

    override fun visitDoStatement(node: DoStatement, value: BaseASTNode) {
        createFunctionScope(node)
        super.visitDoStatement(node, value)
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

        val type = getCallExpressionType(node, currentScope)

        when (value) {
            is Identifier -> setIdentifierType(
                value, type, currentScope
            )

            is MemberExpression -> setMemberExpressionType(
                value, type, currentScope
            )
        }
    }


    override fun visitConstantNode(node: ConstantNode, value: BaseASTNode) {
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
        if (currentScope !is LoopScope) {
            error(node) { "no loop to break" }
        }
    }

    override fun visitContinueStatement(node: ContinueStatement, value: BaseASTNode) {
        if (currentScope !is LoopScope) {
            error(node) { "no loop to continue" }
        }
    }

    override fun visitBinaryExpression(node: BinaryExpression, value: BaseASTNode) {
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

        currentScope.addSymbol(symbol)
        super.visitReturnStatement(node, node)
    }

    override fun visitMemberExpression(node: MemberExpression, value: BaseASTNode) {
        val currentType = resolveMemberExpressionType(node, currentScope)
        when (value) {
            is Identifier -> setIdentifierType(value, currentType, currentScope)

            is MemberExpression -> setMemberExpressionType(value, currentType, currentScope)

            is ReturnStatement -> setReturnStatementType(value, node, currentType, currentScope)
        }
    }

    override fun visitIdentifier(node: Identifier, value: BaseASTNode) {

        when (value) {
            // params: function(a)
            is FunctionDeclaration -> {
                val parameterSymbol = createParamsVariable(node, currentScope)
                if (node.name == "self" && value.params.indexOf(node) == 0 && value.identifier is MemberExpression) {
                    setSelfType(value, parameterSymbol, currentScope)
                }
            }

            // identifier: a = b
            is Identifier -> {
                //  val symbolForVariableName = currentScope.resolveSymbol(value.name, node.range.start)
                val symbolForValue = currentScope.resolveSymbol(node.name, node.range.start)

                val type = unpackType(symbolForValue?.type ?: Type.ANY)

                setIdentifierType(value, type, currentScope)
            }

            // member: a.b = c
            is MemberExpression -> {
                val symbolForValue = currentScope.resolveSymbol(node.name, node.range.start) ?: return

                setMemberExpressionType(value, symbolForValue.type, currentScope)
            }

            // return: return a
            is ReturnStatement -> setReturnStatementType(
                value, node, resolveExpressionNodeType(node) ?: Type.ANY, currentScope
            )
        }
    }

    override fun visitTableConstructorExpression(node: TableConstructorExpression, value: BaseASTNode) {
        // make parent as table
        super.visitTableConstructorExpression(node, node)

        when (value) {
            is Identifier -> setIdentifierType(
                value,
                getTableConstructorExpressionType(node, value.name),
                currentScope,
            )

            is MemberExpression -> setMemberExpressionType(
                value, getTableConstructorExpressionType(node), currentScope
            )

            is ReturnStatement -> setReturnStatementType(
                value, node, getTableConstructorExpressionType(node), currentScope
            )
        }
    }


    override fun visitAssignmentStatement(node: AssignmentStatement, value: BaseASTNode) {
        val initSymbols = node.init.map { initNode ->
            when (initNode) {
                is Identifier -> {
                    val symbol = currentScope.resolveSymbol(initNode.name, initNode.range.start)
                    if (symbol != null) {
                        return@map symbol
                    }
                    createGlobalVariable(initNode)
                }

                is MemberExpression -> {
                    val list = transformMemberExpressionToList(initNode)

                    val first = list.first()

                    val assignedSymbol = currentScope.resolveSymbol(first.name, first.range.start)

                    if (assignedSymbol != null) {
                        return@map assignedSymbol
                    }
                    createUnknownLikeTableSymbol(list)
                }

                else -> null
            }
        }

        // TODO: check var > init

        var tupleType: TupleType? = null
        var lastTupleTypeIndex = 0
        for (index in node.init.indices) {
            val varNode = node.variables.getOrNull(index)
            val initNode = node.init[index]

            varNode?.let { visitExpressionNode(it, initNode) }

            val initSymbol = initSymbols.getOrNull(index) ?: break

            if (initSymbol.type is TupleType) {
                lastTupleTypeIndex = index
                tupleType = initSymbol.type as TupleType
            }

            if (tupleType != null) {
                initSymbol.type = tupleType.get(index - lastTupleTypeIndex)
            }

        }
    }

    override fun visitForNumericStatement(node: ForNumericStatement, value: BaseASTNode) {
        createLoopScope(node.body)

        createLocalVariable(node.variable)

        visitExpressionNode(node.start, node.variable)
        visitExpressionNode(node.end, node.variable)
        node.step?.let { visitExpressionNode(it, node) }

        visitBlockNode(node.body, node)
    }

    override fun visitForGenericStatement(node: ForGenericStatement, value: BaseASTNode) {
        super.visitForGenericStatement(node, value)
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

        node.params.forEach {
            funcScope.resolveSymbol(it.name, it.range.start)?.let { paramSymbol ->
                val paramType = paramSymbol.type
                funcType.addParamType(paramType)
            }
        }

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

    }


    private fun transformMemberExpressionToList(node: MemberExpression): ArrayDeque<Identifier> {
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
            val list = transformMemberExpressionToList(identifier)
            val isCallSelf = identifier.indexer == ":"

            val last = list.first()
            val selfSymbol = currentScope.resolveSymbol(last.name, last.range.start)

            if (selfSymbol == null) {
                createUnknownLikeTableSymbol(list)
            }

            functionType.isSelf = isCallSelf

            if (isCallSelf) {
                currentScope.addSymbol(createSelfVariableSymbol(node, selfSymbol?.type))
            }

            setMemberExpressionType(identifier, functionType, currentScope, list)
        }


        if (value is MemberExpression) {
            setMemberExpressionType(value, functionType, currentScope)
        }
    }


    private fun visitLocalFunctionDeclaration(
        node: FunctionDeclaration, functionType: FunctionType
    ) {


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
        val scope = currentScope
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
                    val valueType = resolveExpressionNodeType(value, scope)
                    currentType.setMember(keyValue.toString(), keyType ?: Type.ANY, valueType)
                }

            }

        }

        return rootType
    }

    private fun getCallExpressionType(
        callExpression: CallExpression,
        currentScope: Scope,
    ): Type {

        val base = callExpression.base
        val args = callExpression.arguments

        val baseType = resolveExpressionNodeType(base, currentScope)

        val callSelf = if (base is MemberExpression) {
            base.indexer == ":"
        } else false


        if (baseType is FunctionType || ((baseType is UnionType) && baseType.types.any { it is FunctionType })) {
            val funcType =
                if (baseType is FunctionType) baseType else (baseType as UnionType).types.filterIsInstance<FunctionType>()
                    .getOrNull(0) ?: return Type.ANY
            val params = funcType.parameterTypes
            val paramsSize = params.size


            if (!callSelf && funcType.isSelf && args.isEmpty()) {
                println("need add : to call with self")
            }

            for (i in 0..<paramsSize) {
                val paramType = params[i]
                val argNode = args.getOrNull(i) ?: break
                val argType = resolveExpressionNodeType(argNode)

                if (paramType is ParameterType) {
                    paramType.realType = paramType.realType.union(argType)
                } else {
                    funcType.setParamType(i, paramType.union(argType))
                }
            }

            return createTupleType(funcType.returnTypes)
        }

        if (base is Identifier && (baseType is UnDefinedType || baseType is AnyType) && args.isNotEmpty()) {
            val symbol = currentScope.resolveSymbol(base.name, base.range.start) ?: return Type.ANY

            val functionType = LikeFunctionType()

            functionType.addReturnType(Type.ANY)

            for (argIndex in 0..<args.size) {
                val argType = resolveExpressionNodeType(args[argIndex])
                val rawParamType = functionType.getParamTypeOrNull(argIndex)
                val paramType = (rawParamType ?: argType).union(argType)
                if (rawParamType == null) functionType.addParamType(paramType)
                else functionType.setParamType(argIndex, paramType)
            }

            symbol.type = symbol.type.union(Type.ANY).union(functionType)

            return createTupleType(functionType.returnTypes)
        }

        return Type.ANY
    }

    private fun createTupleType(types: List<Type>): Type {
        if (types.size == 1) {
            return types[0]
        }

        return TupleType(types.distinct())
    }

    private fun setSelfType(declaration: FunctionDeclaration, symbol: ParameterSymbol, currentScope: Scope) {
        val identifier = declaration.identifier as MemberExpression
        val list = transformMemberExpressionToList(identifier)
        val last = list.first()
        val selfSymbol = currentScope.resolveSymbol(last.name, last.range.start)
        val currentType = resolveMemberExpressionType(identifier, currentScope)
        if (currentType is FunctionType) {
            currentType.isSelf = true
        }
        symbol.type.isSelf = true
        symbol.type.realType = selfSymbol?.type ?: Type.ANY
    }

    private fun resolveMemberExpressionType(
        node: MemberExpression, currentScope: Scope
    ): Type {
        val list = transformMemberExpressionToList(node)
        var last = list.removeFirst()
        val symbol = currentScope.resolveSymbol(last.name, last.range.start)
        var currentType: Type? = unpackType(symbol?.type ?: Type.ANY)

        while (list.isNotEmpty()) {
            last = list.removeFirstOrNull() ?: break

            val lastType = currentType
            currentType = when (currentType) {
                is TableType -> currentType.searchMember(last.name)

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

    private fun resolveExpressionNodeType(node: ExpressionNode, scope: Scope = currentScope): Type {
        val resultType = when (node) {
            is ConstantNode -> node.asType()
            is TableConstructorExpression -> getTableConstructorExpressionType(node)

            is Identifier -> {
                val symbol = scope.resolveSymbol(node.name, node.range.start)
                // unknown symbol, create a global symbol
                symbol?.type ?: createGlobalVariable(node).type
            }

            is CallExpression -> getCallExpressionType(node, scope)

            is BinaryExpression -> when (node.operator) {
                ExpressionOperator.CONCAT -> Type.STRING
                ExpressionOperator.AND, ExpressionOperator.OR, ExpressionOperator.NOT -> Type.BOOLEAN
                else -> {
                    val leftType = node.left?.let { resolveExpressionNodeType(it, scope) }
                    val rightType = node.right?.let { resolveExpressionNodeType(it, scope) }

                    if (leftType?.kind == TypeKind.Number && rightType?.kind == TypeKind.Number) {
                        Type.NUMBER
                    } else {
                        (leftType ?: Type.ANY).union(rightType ?: Type.ANY)
                    }
                }
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
        list: ArrayDeque<Identifier> = transformMemberExpressionToList(expression)
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

    private fun createSelfVariableSymbol(node: FunctionDeclaration, type: Type?): VariableSymbol {
        return VariableSymbol(
            variable = "self",
            range = node.body?.range ?: node.range,
            type = type ?: UnknownLikeTableType("self"),
            isLocal = true,
            node = node.identifier ?: node
        )
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

    private fun createLocalVariable(identifier: Identifier): VariableSymbol {


        val symbol = createVariableSymbol(identifier, currentScope)
        currentScope.addSymbol(symbol)
        return symbol
    }

    private fun createGlobalVariable(identifier: Identifier): VariableSymbol {
        val symbol = VariableSymbol(
            variable = identifier.name,
            range = globalScope.range,
            node = identifier,
            type = Type.UnDefined,
            isLocal = false
        )
        globalScope.addSymbol(symbol)
        return symbol
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

    private val currentScope
        get() = scopeStack.first()
}