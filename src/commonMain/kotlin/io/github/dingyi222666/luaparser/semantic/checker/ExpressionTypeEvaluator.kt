package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionOperator
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.comparePositions
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.DocFunctionTypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolutionContext
import io.github.dingyi222666.luaparser.semantic.types.resolve.intersectionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import io.github.dingyi222666.luaparser.semantic.checker.resolveOwningFunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.types.syntax.ArrayTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.GenericTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IdentifierObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IndexTableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IntersectionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.LiteralTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.MultiReturnTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NullableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.QuotedObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TupleTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.UnionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.VarargTypeSyntax

class ExpressionTypeEvaluator internal constructor(
    private val binder: BinderPassResult,
    private val workspaceContext: SemanticWorkspaceContext
) {

    constructor(binder: BinderPassResult) : this(binder, SemanticWorkspaceContext())

    data class ReturnSite(
        val statement: ReturnStatement?,
        val values: ValueSequence
    )

    data class Context(
        val lexicalScopeId: ScopeId,
        val localOverrides: Map<String, Type> = emptyMap(),
        val excludedDeclarations: Set<DeclarationId> = emptySet(),
        val varargType: Type = VarargType(UnknownType)
    )

    private val expressionTypeCache = mutableMapOf<ExpressionNode, Type>()
    private val declarationValueTypeCache = mutableMapOf<DeclarationId, Type>()
    private val activeDeclarationIds = mutableSetOf<DeclarationId>()
    private val memberResolver = MemberResolver(binder)
    private val callChecker = CallChecker(binder)

    fun evaluate(node: ExpressionNode): Type {
        expressionTypeCache[node]?.let { return it }
        val scopeId = binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val type = evaluate(node, Context(lexicalScopeId = scopeId))
        expressionTypeCache[node] = type
        return type
    }

    fun evaluate(node: ExpressionNode, context: Context): Type {
        return when (node) {
            is ConstantNode -> evaluateConstant(node)
            is Identifier -> evaluateIdentifier(node, context)
            is VarargLiteral -> context.varargType
            is UnaryExpression -> evaluateUnary(node)
            is BinaryExpression -> evaluateBinary(node, context)
            is ArrayConstructorExpression -> evaluateArrayConstructor(node, context)
            is TableConstructorExpression -> evaluateTableConstructor(node, context)
            is MemberExpression -> evaluateMemberExpression(node, context)
            is IndexExpression -> evaluateIndexExpression(node, context)
            is StringCallExpression -> evaluateCallExpression(node, context)
            is TableCallExpression -> evaluateCallExpression(node, context)
            is CallExpression -> evaluateCallExpression(node, context)
            is FunctionDeclaration -> evaluateFunctionDeclaration(node, context)
            is LambdaDeclaration -> evaluateLambdaDeclaration(node, context)
            else -> UnknownType
        }
    }

    private fun evaluateConstant(node: ConstantNode): Type {
        return when (node.constantType) {
            ConstantNode.TYPE.NIL -> PrimitiveType.NIL
            ConstantNode.TYPE.BOOLEAN -> LiteralType(node.rawValue.toString() == "true", PrimitiveType.BOOLEAN)
            ConstantNode.TYPE.STRING -> LiteralType(node.stringOf(), PrimitiveType.STRING)
            ConstantNode.TYPE.INTERGER -> LiteralType(node.rawValue.toString().toIntOrNull() ?: node.rawValue, PrimitiveType.NUMBER)
            ConstantNode.TYPE.FLOAT -> LiteralType(node.rawValue.toString().toDoubleOrNull() ?: node.rawValue, PrimitiveType.NUMBER)
            ConstantNode.TYPE.UNKNOWN -> UnknownType
        }
    }

    private fun evaluateIdentifier(node: Identifier, context: Context): Type {
        context.localOverrides[node.name]?.let { return it }
        val declaration = findVisibleValueDeclaration(node.name, node.range.start, context)
        if (declaration != null) {
            return typeOfDeclaration(declaration, context)
        }
        workspaceContext.importedSymbols[node.name]?.let { return it.moduleType }
        workspaceContext.resolveImportedSymbol?.invoke(node.name)?.let { return it.moduleType }
        return UnknownType
    }

    private fun evaluateUnary(node: UnaryExpression): Type {
        return when (node.operator) {
            ExpressionOperator.MINUS,
            ExpressionOperator.BIT_TILDE,
            ExpressionOperator.GETLEN -> PrimitiveType.NUMBER

            ExpressionOperator.NOT -> PrimitiveType.BOOLEAN
            else -> UnknownType
        }
    }

    private fun evaluateBinary(node: BinaryExpression, context: Context): Type {
        val leftType = node.left?.let { evaluate(it, context) } ?: UnknownType
        val rightType = node.right?.let { evaluate(it, context) } ?: UnknownType

        return when (node.operator) {
            ExpressionOperator.ADD,
            ExpressionOperator.MINUS,
            ExpressionOperator.MULT,
            ExpressionOperator.DIV,
            ExpressionOperator.MOD,
            ExpressionOperator.DOUBLE_DIV,
            ExpressionOperator.BIT_EXP,
            ExpressionOperator.BIT_AND,
            ExpressionOperator.BIT_OR,
            ExpressionOperator.BIT_LT,
            ExpressionOperator.BIT_GT -> PrimitiveType.NUMBER

            ExpressionOperator.CONCAT -> PrimitiveType.STRING

            ExpressionOperator.LT,
            ExpressionOperator.GT,
            ExpressionOperator.LE,
            ExpressionOperator.GE,
            ExpressionOperator.EQ,
            ExpressionOperator.NE -> PrimitiveType.BOOLEAN

            ExpressionOperator.AND,
            ExpressionOperator.OR -> unionTypeOf(leftType, rightType)

            else -> UnknownType
        }
    }

    private fun evaluateArrayConstructor(node: ArrayConstructorExpression, context: Context): Type {
        val elementTypes = node.values.map { evaluate(it, context) }
        return ArrayType(
            elementType = if (elementTypes.isEmpty()) UnknownType else unionTypeOf(elementTypes)
        )
    }

    private fun evaluateTableConstructor(node: TableConstructorExpression, context: Context): Type {
        val fields = linkedMapOf<String, Type>()
        val methods = linkedMapOf<String, Type>()

        node.fields.forEach { field ->
            val keyName = staticTableKeyName(field)
            if (keyName != null) {
                val valueType = evaluate(field.value, context)
                fields[keyName] = valueType
                if (callChecker.resolveCallable(valueType, context.lexicalScopeId).isSuccess) {
                    methods[keyName] = valueType
                }
            }
        }

        return TableType(fields = fields, methods = methods)
    }

    private fun evaluateMemberExpression(node: MemberExpression, context: Context): Type {
        val baseType = evaluateReferenceBaseType(node.base, context)
        return memberResolver.resolveMember(baseType, node.identifier.name, node.indexer == ":", context.lexicalScopeId).type
            ?: UnknownType
    }

    private fun evaluateIndexExpression(node: IndexExpression, context: Context): Type {
        val baseType = evaluateReferenceBaseType(node.base, context)
        val indexType = evaluate(node.index, context)
        return memberResolver.resolveIndex(baseType, node.index, indexType, context.lexicalScopeId).type ?: UnknownType
    }

    private fun evaluateCallExpression(node: CallExpression, context: Context): Type {
        resolveBuiltinRequire(node, context)?.let { return it }
        resolveDynamicImportCall(node, context)?.let { return it }
        resolveBindClassCall(node, context)?.let { return it }
        resolveNewInstanceCall(node, context)?.let { return it }
        resolveCreateProxyCall(node, context)?.let { return it }
        resolveLoadLibCall(node, context)?.let { return it }
        resolveJvmConstructorCall(node, context)?.let { return it }
        val declaration = callableDeclaration(node.base, context)
        val callableType = evaluateReferenceBaseType(node.base, context)
        val argumentSequences = buildCallArgumentSequences(node, context)
        return callChecker.checkCallValues(callableType, argumentSequences, context.lexicalScopeId, declaration).returnType ?: UnknownType
    }


    private fun resolveDynamicImportCall(node: CallExpression, context: Context): Type? {
        val identifier = effectiveCallBase(node) as? Identifier ?: return null
        val declaration = callableDeclaration(identifier, context)
        if (identifier.name != "import" && !isRequireImportAlias(declaration, context)) {
            return null
        }
        val importedTargets = importTargets(node)
        if (importedTargets.isEmpty()) {
            return null
        }
        val importedTypes = importedTargets.mapNotNull { target ->
            workspaceContext.resolveImportTarget?.invoke(target)?.moduleType
        }
        if (importedTypes.isEmpty()) {
            return null
        }
        return if (importedTargets.size == 1 && importedTypes.size == 1) {
            importedTypes.single()
        } else {
            ArrayType(elementType = unionTypeOf(importedTypes))
        }
    }

    private fun isRequireImportAlias(declaration: BinderDeclaration?, context: Context): Boolean {
        return aliasResolvesTo(declaration, context, matches = { expression ->
            val call = expression as? CallExpression ?: return@aliasResolvesTo false
            isBuiltinRequireImportCall(call, context)
        })
    }

    private fun resolveBindClassCall(node: CallExpression, context: Context): ModuleType? {
        val target = stringCallTarget(node) ?: return null
        val base = effectiveCallBase(node)
        val isBindClassCall = when (base) {
            is MemberExpression -> {
                val owner = base.base as? Identifier
                owner?.name == "luajava" && base.identifier.name == "bindClass"
            }
            is Identifier -> {
                if (base.name == "bindClass") {
                    true
                } else {
                    val declaration = callableDeclaration(base, context)
                    declaration != null && isBindClassAlias(declaration, context)
                }
            }
            else -> false
        }
        if (!isBindClassCall) {
            return null
        }
        return workspaceContext.resolveImportTarget?.invoke(target)?.moduleType
    }

    private fun resolveNewInstanceCall(node: CallExpression, context: Context): Type? {
        val target = stringCallTarget(node) ?: return null
        val base = effectiveCallBase(node)
        val isNewInstanceCall = when (base) {
            is MemberExpression -> {
                val owner = base.base as? Identifier
                owner?.name == "luajava" && base.identifier.name == "newInstance"
            }
            is Identifier -> {
                if (base.name == "newInstance") {
                    true
                } else {
                    val declaration = callableDeclaration(base, context)
                    declaration != null && isNewInstanceAlias(declaration, context)
                }
            }
            else -> false
        }
        if (!isNewInstanceCall) {
            return null
        }
        return workspaceContext.resolveImportTarget?.invoke(target)?.moduleType?.fields?.get("__class") ?: UnknownType
    }

    private fun resolveCreateProxyCall(node: CallExpression, context: Context): Type? {
        val interfaceTargets = createProxyTargets(node)
        if (interfaceTargets.isEmpty()) {
            return null
        }
        val base = effectiveCallBase(node)
        val isCreateProxyCall = when (base) {
            is MemberExpression -> {
                val owner = base.base as? Identifier
                owner?.name == "luajava" && base.identifier.name == "createProxy"
            }
            is Identifier -> {
                if (base.name == "createProxy") {
                    true
                } else {
                    val declaration = callableDeclaration(base, context)
                    declaration != null && isCreateProxyAlias(declaration, context)
                }
            }
            else -> false
        }
        if (!isCreateProxyCall) {
            return null
        }
        val interfaceTypes = interfaceTargets.mapNotNull { target ->
            workspaceContext.resolveImportTarget?.invoke(target)?.moduleType?.fields?.get("__class")
        }
        if (interfaceTypes.isEmpty()) {
            return UnknownType
        }
        return intersectionTypeOf(interfaceTypes)
    }

    private fun resolveLoadLibCall(node: CallExpression, context: Context): Type? {
        val target = stringCallTarget(node) ?: return null
        val memberName = stringCallTarget(node, argumentIndex = 1) ?: return null
        val base = effectiveCallBase(node)
        val isLoadLibCall = when (base) {
            is MemberExpression -> {
                val owner = base.base as? Identifier
                owner?.name == "luajava" && base.identifier.name == "loadLib"
            }
            is Identifier -> {
                if (base.name == "loadLib") {
                    true
                } else {
                    val declaration = callableDeclaration(base, context)
                    declaration != null && isLoadLibAlias(declaration, context)
                }
            }
            else -> false
        }
        if (!isLoadLibCall) {
            return null
        }
        val moduleType = workspaceContext.resolveImportTarget?.invoke(target)?.moduleType ?: return UnknownType
        return moduleType.methods[memberName] ?: moduleType.fields[memberName] ?: UnknownType
    }

    private fun resolveJvmConstructorCall(node: CallExpression, context: Context): Type? {
        val moduleType = evaluateReferenceBaseType(node.base, context) as? ModuleType ?: return null
        return moduleType.fields["__class"]
    }

    private fun isBindClassAlias(declaration: BinderDeclaration, context: Context): Boolean {
        return aliasResolvesTo(declaration, context, matches = { expression ->
            val initializer = expression as? MemberExpression ?: return@aliasResolvesTo false
            val owner = initializer.base as? Identifier ?: return@aliasResolvesTo false
            owner.name == "luajava" && initializer.identifier.name == "bindClass"
        })
    }

    private fun isNewInstanceAlias(declaration: BinderDeclaration, context: Context): Boolean {
        return aliasResolvesTo(declaration, context, matches = { expression ->
            val initializer = expression as? MemberExpression ?: return@aliasResolvesTo false
            val owner = initializer.base as? Identifier ?: return@aliasResolvesTo false
            owner.name == "luajava" && initializer.identifier.name == "newInstance"
        })
    }

    private fun isCreateProxyAlias(declaration: BinderDeclaration, context: Context): Boolean {
        return aliasResolvesTo(declaration, context, matches = { expression ->
            val initializer = expression as? MemberExpression ?: return@aliasResolvesTo false
            val owner = initializer.base as? Identifier ?: return@aliasResolvesTo false
            owner.name == "luajava" && initializer.identifier.name == "createProxy"
        })
    }

    private fun isLoadLibAlias(declaration: BinderDeclaration, context: Context): Boolean {
        return aliasResolvesTo(declaration, context, matches = { expression ->
            val initializer = expression as? MemberExpression ?: return@aliasResolvesTo false
            val owner = initializer.base as? Identifier ?: return@aliasResolvesTo false
            owner.name == "luajava" && initializer.identifier.name == "loadLib"
        })
    }

    private fun aliasResolvesTo(
        declaration: BinderDeclaration?,
        context: Context,
        matches: (ExpressionNode) -> Boolean,
        visited: MutableSet<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId> = linkedSetOf()
    ): Boolean {
        declaration ?: return false
        if (declaration.kind != DeclarationKind.LOCAL) {
            return false
        }
        if (!visited.add(declaration.id)) {
            return false
        }
        val initializer = localDeclarationInitializer(declaration) ?: return false
        if (matches(initializer)) {
            return true
        }
        val aliasIdentifier = initializer as? Identifier ?: return false
        val aliasedDeclaration = findVisibleValueDeclaration(aliasIdentifier.name, aliasIdentifier.range.start, context) ?: return false
        return aliasResolvesTo(aliasedDeclaration, context, matches, visited)
    }

    private fun localDeclarationInitializer(declaration: BinderDeclaration): ExpressionNode? {
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return null
        val initializerIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (initializerIndex < 0) {
            return null
        }
        return localStatement.variables.getOrNull(initializerIndex)
    }

    private fun resolveBuiltinRequire(node: CallExpression, context: Context): ModuleType? {
        val moduleName = builtinRequireModuleName(node, context) ?: return null
        val currentPath = workspaceContext.currentPath ?: return null
        val resolver = workspaceContext.workspaceResolver ?: return null
        return resolver.resolveRequire(currentPath, moduleName)?.surface?.moduleType
    }

    private fun builtinRequireModuleName(node: CallExpression, context: Context): String? {
        val identifier = node.base as? Identifier ?: return null
        if (identifier.name != "require") {
            return null
        }
        val declaration = findVisibleValueDeclaration(identifier.name, identifier.range.start, context) ?: return null
        if (declaration.origin != DeclarationOrigin.BUILTIN || declaration.name != "require") {
            return null
        }
        return (node.arguments.singleOrNull() as? ConstantNode)
            ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
            ?.stringOf()
    }

    private fun isBuiltinRequireImportCall(node: CallExpression, context: Context): Boolean {
        return builtinRequireModuleName(node, context) == "import"
    }

    private fun effectiveCallBase(node: CallExpression): ExpressionNode {
        return if (node.base is StringCallExpression && node.arguments.isEmpty()) {
            node.base
        } else {
            node.base
        }.let { base ->
            if (base is StringCallExpression) base.base else base
        }
    }

    private fun importTargets(node: CallExpression): List<String> {
        val arguments = callArguments(node)
        val firstArgument = arguments.singleOrNull()
        return when (firstArgument) {
            is ConstantNode -> {
                if (firstArgument.constantType == ConstantNode.TYPE.STRING) listOf(firstArgument.stringOf()) else emptyList()
            }
            is ArrayConstructorExpression -> firstArgument.values.mapNotNull(::stringLiteralOf)
            is TableConstructorExpression -> firstArgument.fields.mapNotNull { stringLiteralOf(it.value) }
            else -> emptyList()
        }
    }

    private fun createProxyTargets(node: CallExpression): List<String> {
        val arguments = callArguments(node)
        if (arguments.isEmpty()) {
            return emptyList()
        }
        return arguments.mapNotNull(::stringLiteralOf)
    }

    private fun stringLiteralOf(expression: ExpressionNode): String? {
        val constant = expression as? ConstantNode ?: return null
        return constant.takeIf { it.constantType == ConstantNode.TYPE.STRING }?.stringOf()
    }

    private fun callArguments(node: CallExpression): List<ExpressionNode> {
        val stringCallBase = node.base as? StringCallExpression
        return buildList {
            if (stringCallBase != null) {
                addAll(stringCallBase.arguments)
            }
            addAll(node.arguments)
        }
    }

    private fun stringCallTarget(node: CallExpression, argumentIndex: Int = 0): String? {
        return stringLiteralOf(callArguments(node).getOrNull(argumentIndex) ?: return null)
    }

    private fun evaluateFunctionDeclaration(node: FunctionDeclaration, context: Context): Type {
        return evaluateFunctionDeclaration(node, context, preferDeclaredReturn = true)
    }

    internal fun inferImplementationFunctionType(node: FunctionDeclaration): CallableType? {
        val scopeId = node.body
            ?.let(binder.scopeGraph::getScope)
            ?.id
            ?: binder.positionQueries.getScopeAt(node.range.start)?.id
            ?: binder.scopeGraph.rootScope.id
        return evaluateFunctionDeclaration(
            node = node,
            context = Context(lexicalScopeId = scopeId),
            preferDeclaredReturn = false
        ) as? CallableType
    }

    private fun evaluateFunctionDeclaration(
        node: FunctionDeclaration,
        context: Context,
        preferDeclaredReturn: Boolean
    ): Type {
        val ownedDeclaration = node.body
            ?.let(binder.scopeGraph::getScope)
            ?.ownerDeclarationId
            ?.let(binder.declarationIndex::getDeclaration)
        val declaredSignature = (ownedDeclaration?.declaredType as? CallableType)?.callSignatures?.firstOrNull()
        val declaredParameterOffset = if (ownedDeclaration?.let { isColonMethodDeclaration(binder, it) } == true) 1 else 0
        val documentedParameterTypes = ownedDeclaration?.documentation?.resolvedParameterTypes.orEmpty()
        val documentedReturnType = ownedDeclaration?.documentation?.resolvedReturnTypes
            ?.takeIf { it.isNotEmpty() }
            ?.let { returnTypes ->
                if (returnTypes.size == 1) {
                    returnTypes.single()
                } else {
                    MultiReturnType(returnTypes)
                }
            }

        val body = node.body
        val functionScopeId = body?.let(binder.scopeGraph::getScope)?.id ?: context.lexicalScopeId
        val parameterDeclarations = body
            ?.let(binder.scopeGraph::getScope)
            ?.declarationIds
            .orEmpty()
            .mapNotNull(binder.declarationIndex::getDeclaration)
            .filter { it.kind == DeclarationKind.PARAMETER }

        val parameters = node.params.mapIndexed { index, parameterNode ->
            val declaration = parameterDeclarations.getOrNull(index)
            val declaredParameterType = declaredSignature?.parameters?.getOrNull(index + declaredParameterOffset)?.type
            val parameterType = declaration?.declaredType
                ?: declaredParameterType
                ?: documentedParameterTypes[parameterNode.name]
                ?: context.localOverrides[parameterNode.name]
                ?: UnknownType
            FunctionParameter(
                name = parameterNode.name,
                type = parameterType,
                vararg = parameterNode.name == "..." || parameterType is VarargType
            )
        }

        val varargType = parameters.lastOrNull { it.vararg }?.type ?: VarargType(UnknownType)
        val childContext = buildFunctionBodyContext(
            function = node,
            parameters = parameters,
            fallbackScopeId = functionScopeId,
            fallbackVarargType = varargType
        )
        val inferredReturnType = body?.let { inferFunctionReturnType(it, childContext) } ?: PrimitiveType.NIL
        val returnType = if (preferDeclaredReturn) {
            declaredSignature?.returnType?.takeIf { it != UnknownType }
                ?: documentedReturnType?.takeIf { it != UnknownType }
                ?: inferredReturnType
        } else {
            inferredReturnType
        }
        return FunctionType(parameters = parameters, returnType = returnType)
    }

    private fun evaluateLambdaDeclaration(node: LambdaDeclaration, context: Context): Type {
        val parameters = node.params.map { parameter ->
            val parameterType = context.localOverrides[parameter.name] ?: UnknownType
            FunctionParameter(
                name = parameter.name,
                type = parameterType,
                vararg = parameter.name == "..." || parameterType is VarargType
            )
        }
        val childContext = context.copy(
            localOverrides = context.localOverrides + parameters.associate { it.name to it.type },
            varargType = parameters.lastOrNull { it.vararg }?.type ?: context.varargType
        )
        return FunctionType(
            parameters = parameters,
            returnType = evaluate(node.expression, childContext)
        )
    }

    private fun typeOfDeclaration(declaration: BinderDeclaration, context: Context): Type {
        if (declaration.id in context.excludedDeclarations) {
            return UnknownType
        }

        if (declaration.kind !in setOf(DeclarationKind.FUNCTION, DeclarationKind.GLOBAL, DeclarationKind.METHOD)) {
            declaration.declaredType?.let { return it }
        }
        declarationValueTypeCache[declaration.id]?.let { return it }

        if (!activeDeclarationIds.add(declaration.id)) {
            return UnknownType
        }

        return try {
            val type = deriveDeclarationValueType(declaration, context)
            declarationValueTypeCache[declaration.id] = type
            type
        } finally {
            activeDeclarationIds.remove(declaration.id)
        }
    }

    private fun deriveDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        return when (declaration.kind) {
            DeclarationKind.LOCAL -> deriveLocalDeclarationValueType(declaration, context)
            DeclarationKind.FUNCTION -> deriveFunctionDeclarationValueType(declaration, context)
            DeclarationKind.PARAMETER -> declaration.declaredType ?: UnknownType
            DeclarationKind.GLOBAL -> deriveGlobalDeclarationValueType(declaration, context)
            DeclarationKind.METHOD -> deriveMethodDeclarationValueType(declaration, context)
            DeclarationKind.MODULE -> declaration.declaredType ?: UnknownType
            else -> UnknownType
        }
    }

    private fun deriveLocalDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return UnknownType
        val declarationIdsInStatement = localStatement.init.mapNotNull { identifier ->
            binder.declarationIndex.getDeclarations(identifier)
                .firstOrNull { it.kind == DeclarationKind.LOCAL }
                ?.id
        }.toSet()
        val initializerIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (initializerIndex < 0) {
            return UnknownType
        }

        val initializerContext = context.copy(
            excludedDeclarations = context.excludedDeclarations + declarationIdsInStatement
        )
        val baseType = resolveAssignedValueType(localStatement.variables, initializerIndex, initializerContext)
        return if (baseType is TableType) {
            attachVisibleAstMethodsToTable(declaration, baseType, initializerContext)
        } else {
            baseType
        }
    }

    private fun deriveFunctionDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        val functionNode = declaration.anchorNode?.parent as? FunctionDeclaration ?: return UnknownType
        return evaluateFunctionDeclaration(functionNode, context)
    }

    private fun deriveGlobalDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        val functionNode = declaration.anchorNode?.parent as? FunctionDeclaration ?: return UnknownType
        return evaluateFunctionDeclaration(functionNode, context)
    }

    private fun deriveMethodDeclarationValueType(declaration: BinderDeclaration, context: Context): Type {
        val functionNode = resolveOwningFunctionDeclaration(binder, declaration) ?: return declaration.declaredType ?: UnknownType
        val inferred = inferImplementationFunctionType(functionNode)
        val declared = declaration.declaredType as? CallableType ?: return inferred ?: UnknownType
        val inferredCallable = inferred as? CallableType ?: return enrichMethodCallableType(declaration, declared)
        return mergeDeclaredAndInferredCallableType(declaration, declared, inferredCallable)
    }

    private fun findVisibleValueDeclaration(name: String, position: Position, context: Context): BinderDeclaration? {
        var scope: Scope? = binder.scopeGraph.getScope(context.lexicalScopeId)
        while (scope != null) {
            val declaration = scope.declarationIds
                .asReversed()
                .mapNotNull(binder.declarationIndex::getDeclaration)
                .firstOrNull { candidate ->
                    candidate.kind.namespace == DeclarationNamespace.VALUE &&
                        candidate.name == name &&
                        candidate.id !in context.excludedDeclarations &&
                        isVisibleAt(candidate, position)
                }
            if (declaration != null) {
                return declaration
            }
            scope = scope.parentId?.let(binder.scopeGraph::getScope)
        }
        return null
    }

    private fun evaluateReferenceBaseType(node: ExpressionNode, context: Context): Type {
        val identifier = node as? Identifier ?: return evaluate(node, context)
        context.localOverrides[identifier.name]?.let { return it }
        val declaration = findVisibleValueDeclaration(identifier.name, identifier.range.start, context)
        return declaration?.declaredType ?: declaration?.let { typeOfDeclaration(it, context) } ?: evaluate(node, context)
    }

    private fun callableDeclaration(node: ExpressionNode, context: Context): BinderDeclaration? {
        val identifier = node as? Identifier ?: return null
        if (context.localOverrides.containsKey(identifier.name)) {
            return null
        }
        return findVisibleValueDeclaration(identifier.name, identifier.range.start, context)
    }

    private fun isVisibleAt(declaration: BinderDeclaration, position: Position): Boolean {
        val range = declaration.range ?: return true
        return comparePositions(range.start, position) <= 0
    }

    private fun resolveAssignedValueType(
        expressions: List<ExpressionNode>,
        targetIndex: Int,
        context: Context
    ): Type {
        if (expressions.isEmpty()) {
            return PrimitiveType.NIL
        }

        val lastExpressionIndex = expressions.lastIndex
        return if (targetIndex < lastExpressionIndex) {
            ValueSequence.of(evaluate(expressions[targetIndex], context)).collapseToSingle().typeAt(0)
        } else {
            ValueSequence.of(evaluate(expressions[lastExpressionIndex], context)).typeAt(targetIndex - lastExpressionIndex)
        }
    }

    private fun attachVisibleAstMethodsToTable(
        declaration: BinderDeclaration,
        tableType: TableType,
        context: Context
    ): TableType {
        val methodDeclarations = visibleMethodDeclarationsForValue(declaration, context)
        if (methodDeclarations.isEmpty()) {
            return tableType
        }

        val methods = linkedMapOf<String, Type>()
        methods.putAll(tableType.methods)
        methodDeclarations.forEach { methodDeclaration ->
            val methodType = typeOfDeclaration(methodDeclaration, context)
            if (methodType != UnknownType) {
                methods[methodDeclaration.name] = methodType
            }
        }
        return tableType.copy(methods = methods)
    }

    private fun visibleMethodDeclarationsForValue(
        declaration: BinderDeclaration,
        context: Context
    ): List<BinderDeclaration> {
        val anchor = declaration.anchorNode as? Identifier ?: return emptyList()
        val lexicalOwner = binder.scopeGraph.getScope(context.lexicalScopeId)?.ownerNode
        val scopedMatches = lexicalOwner?.let { owner ->
            binder.declarationIndex.declarations.filter { candidate ->
                candidate.kind == DeclarationKind.METHOD &&
                    isDeclaredInLexicalOwnerChain(candidate, owner) &&
                    isMethodBoundToBaseIdentifier(candidate, anchor.name)
            }
        }.orEmpty()
        if (scopedMatches.isNotEmpty()) {
            return scopedMatches
        }
        return binder.declarationIndex.declarations.filter { candidate ->
            candidate.kind == DeclarationKind.METHOD &&
                isMethodBoundToBaseIdentifier(candidate, anchor.name)
        }
    }

    private fun isDeclaredInLexicalOwnerChain(declaration: BinderDeclaration, lexicalOwner: BaseASTNode): Boolean {
        var current: BaseASTNode? = lexicalOwner
        while (current != null) {
            if (declaration.owner == DeclarationOwner.Lexical(current)) {
                return true
            }
            current = runCatching { current.parent }.getOrNull()
        }
        return false
    }

    private fun isMethodBoundToBaseIdentifier(declaration: BinderDeclaration, baseName: String): Boolean {
        val anchorMember = declaration.anchorNode?.parent as? MemberExpression
        val anchorBase = anchorMember?.base as? Identifier
        if (anchorBase?.name == baseName) {
            return true
        }

        val function = resolveOwningFunctionDeclaration(binder, declaration) ?: return false
        val identifier = function.identifier as? MemberExpression ?: return false
        return identifier.base is Identifier && (identifier.base as Identifier).name == baseName
    }

    private fun inferDeclaredFunctionValueType(functionNode: FunctionDeclaration, context: Context): Type {
        val parameters = functionNode.params.map { parameterNode ->
            val parameterType = context.localOverrides[parameterNode.name] ?: UnknownType
            FunctionParameter(
                name = parameterNode.name,
                type = parameterType,
                vararg = parameterNode.name == "..." || parameterType is VarargType
            )
        }
        val varargType = parameters.lastOrNull { it.vararg }?.type ?: context.varargType
        val childContext = buildFunctionBodyContext(functionNode, parameters, context.lexicalScopeId, varargType)
        return evaluateFunctionDeclaration(functionNode, childContext)
    }

    private fun mergeDeclaredAndInferredCallableType(
        declaration: BinderDeclaration,
        declared: CallableType,
        inferred: CallableType
    ): CallableType {
        val declaredSignature = declared.callSignatures.firstOrNull() ?: return inferred
        val inferredSignature = inferred.callSignatures.firstOrNull() ?: return declared
        val declaredParameterOffset = if (
            declaration.kind == DeclarationKind.METHOD &&
                declaredSignature.parameters.firstOrNull()?.name == "self" &&
                inferredSignature.parameters.firstOrNull()?.name != "self"
        ) {
            1
        } else {
            0
        }
        val mergedParameters = when {
            declaration.kind == DeclarationKind.METHOD -> inferredSignature.parameters.mapIndexed { index, parameter ->
                val declaredParameter = declaredSignature.parameters.getOrNull(index + declaredParameterOffset)
                val parameterType = when {
                    declaredParameter == null -> parameter.type
                    parameter.type == UnknownType -> declaredParameter.type
                    declaredParameter.type == UnknownType -> parameter.type
                    else -> declaredParameter.type
                }
                parameter.copy(type = parameterType)
            }
            else -> inferredSignature.parameters.mapIndexed { index, parameter ->
                val declaredParameter = declaredSignature.parameters.getOrNull(index)
                val parameterType = when {
                    declaredParameter == null -> parameter.type
                    parameter.type == UnknownType -> declaredParameter.type
                    declaredParameter.type == UnknownType -> parameter.type
                    else -> declaredParameter.type
                }
                parameter.copy(type = parameterType)
            }
        }
        val mergedSignature = inferredSignature.copy(
            parameters = mergedParameters,
            returnType = if (inferredSignature.returnType != UnknownType) inferredSignature.returnType else declaredSignature.returnType,
            name = io.github.dingyi222666.luaparser.semantic.types.model.FunctionType(
                parameters = mergedParameters,
                returnType = if (inferredSignature.returnType != UnknownType) inferredSignature.returnType else declaredSignature.returnType,
                typeParameters = inferredSignature.typeParameters
            ).name
        )
        return if (declaration.kind == DeclarationKind.METHOD) {
            enrichMethodCallableType(declaration, mergedSignature)
        } else {
            mergedSignature
        }
    }

    private fun enrichMethodCallableType(declaration: BinderDeclaration, declared: CallableType): CallableType {
        if (declaration.kind != DeclarationKind.METHOD) {
            return declared
        }

        if (!isColonMethodDeclaration(binder, declaration)) {
            return declared
        }

        val selfType = declaration.documentation?.resolvedParameterTypes?.get("self") ?: UnknownType
        val enrichedSignatures = declared.callSignatures.map { signature ->
            val parameters = if (signature.parameters.firstOrNull()?.name == "self") {
                signature.parameters.mapIndexed { index, parameter ->
                    if (index == 0 && parameter.type == UnknownType && selfType != UnknownType) {
                        parameter.copy(type = selfType)
                    } else {
                        parameter
                    }
                }
            } else {
                listOf(FunctionParameter(name = "self", type = selfType)) + signature.parameters
            }
            signature.copy(parameters = parameters, name = io.github.dingyi222666.luaparser.semantic.types.model.FunctionType(parameters = parameters, returnType = signature.returnType, typeParameters = signature.typeParameters).name)
        }
        return when (enrichedSignatures.size) {
            0 -> declared
            1 -> enrichedSignatures.single()
            else -> io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType(enrichedSignatures)
        }
    }

    private fun staticTableKeyName(field: io.github.dingyi222666.luaparser.parser.ast.node.TableKey): String? {
        return when (val key = field.key) {
            is Identifier -> key.name
            is ConstantNode -> when (key.constantType) {
                ConstantNode.TYPE.STRING -> key.stringOf()
                ConstantNode.TYPE.INTERGER -> key.rawValue.toString().toIntOrNull()?.toString() ?: key.rawValue.toString()
                else -> null
            }

            else -> null
        }
    }

    private fun buildCallArgumentSequences(node: CallExpression, context: Context): List<ValueSequence> {
        val argumentSequences = mutableListOf<ValueSequence>()
        if (node.base is MemberExpression && (node.base as MemberExpression).indexer == ":") {
            argumentSequences += ValueSequence.of(evaluate((node.base as MemberExpression).base, context)).collapseToSingle()
        }

        node.arguments.forEachIndexed { index, argument ->
            val sequence = ValueSequence.of(evaluate(argument, context))
            argumentSequences += if (index == node.arguments.lastIndex) sequence else sequence.collapseToSingle()
        }
        return argumentSequences
    }

    private fun inferFunctionReturnType(body: BlockNode, context: Context): Type {
        val returnSites = collectReturnSites(body, context)
        if (returnSites.isEmpty()) {
            return PrimitiveType.NIL
        }

        val returnSequences = returnSites.map(ReturnSite::values)
        val openTailTypes = returnSequences.mapNotNull(ValueSequence::variadicTail)
        val maxFixedArity = returnSequences.maxOf { it.fixed.size }
        val totalArity = if (openTailTypes.isEmpty()) maxFixedArity else maxOf(maxFixedArity, 1)

        if (totalArity <= 1 && openTailTypes.isEmpty()) {
            return unionTypeOf(returnSequences.map { it.typeAt(0) })
        }

        val slots = (0 until maxFixedArity).map { index -> unionTypeOf(returnSequences.map { it.typeAt(index) }) }.toMutableList()
        if (openTailTypes.isNotEmpty()) {
            slots += VarargType(unionTypeOf(openTailTypes))
        }

        return if (slots.size == 1 && slots.single() is VarargType) {
            slots.single()
        } else {
            MultiReturnType(types = slots)
        }
    }

    internal fun buildFunctionBodyContext(
        function: FunctionDeclaration,
        parameters: List<FunctionParameter>,
        fallbackScopeId: ScopeId,
        fallbackVarargType: Type = VarargType(UnknownType)
    ): Context {
        val functionScopeId = function.body?.let(binder.scopeGraph::getScope)?.id ?: fallbackScopeId
        return Context(
            lexicalScopeId = functionScopeId,
            localOverrides = parameters.associate { it.name to it.type },
            varargType = parameters.lastOrNull { it.vararg }?.type ?: fallbackVarargType
        )
    }

    internal fun collectReturnSites(body: BlockNode, context: Context): List<ReturnSite> {
        val output = mutableListOf<ReturnSite>()
        collectReturnTypes(body, context, output)
        return output
    }

    private fun collectReturnTypes(block: BlockNode, context: Context, output: MutableList<ReturnSite>) {
        block.returnStatement?.let { statement ->
            output += ReturnSite(statement = statement, values = collectReturnTuple(statement.arguments, context))
        }

        block.statements.forEach { statement ->
            when (statement) {
                is IfStatement -> statement.causes.forEach { collectReturnTypes(it.body, context, output) }
                is WhileStatement -> collectReturnTypes(statement.body, context, output)
                is DoStatement -> collectReturnTypes(statement.body, context, output)
                is RepeatStatement -> collectReturnTypes(statement.body, context, output)
                is ForGenericStatement -> collectReturnTypes(statement.body, context, output)
                is ForNumericStatement -> collectReturnTypes(statement.body, context, output)
                is SwitchStatement -> statement.causes.forEach { cause ->
                    when (cause) {
                        is CaseCause -> collectReturnTypes(cause.body, context, output)
                        is DefaultCause -> collectReturnTypes(cause.body, context, output)
                    }
                }

                is WhenStatement -> {
                    collectStatementReturns(statement.ifCause, context, output)
                    statement.elseCause?.let { collectStatementReturns(it, context, output) }
                }

                is FunctionDeclaration -> Unit
                else -> Unit
            }
        }
    }

    private fun collectStatementReturns(statement: StatementNode, context: Context, output: MutableList<ReturnSite>) {
        when (statement) {
            is IfClause -> collectReturnTypes(statement.body, context, output)
            is ElseIfClause -> collectReturnTypes(statement.body, context, output)
            is ElseClause -> collectReturnTypes(statement.body, context, output)
            is FunctionDeclaration -> Unit
            else -> Unit
        }
    }

    private fun collectReturnTuple(arguments: List<ExpressionNode>, context: Context): ValueSequence {
        return ValueSequence.fromExpressionResults(arguments.map { evaluate(it, context) })
    }

    private fun resolveTypeSyntax(typeSyntax: TypeSyntax, context: TypeResolutionContext): Type = when (typeSyntax) {
        is NamedTypeSyntax -> resolveNamedType(typeSyntax.name, context)
        is LiteralTypeSyntax -> normalizeLiteral(typeSyntax.value)
        is UnionTypeSyntax -> unionTypeOf(typeSyntax.options.map { resolveTypeSyntax(it, context) })
        is IntersectionTypeSyntax -> intersectionTypeOf(typeSyntax.types.map { resolveTypeSyntax(it, context) })
        is ArrayTypeSyntax -> ArrayType(resolveTypeSyntax(typeSyntax.elementType, context))
        is GenericTypeSyntax -> resolveGenericType(typeSyntax, context)
        is NullableTypeSyntax -> unionTypeOf(resolveTypeSyntax(typeSyntax.innerType, context), PrimitiveType.NIL)
        is TupleTypeSyntax -> TupleType(typeSyntax.elements.map { resolveTypeSyntax(it, context) })
        is MultiReturnTypeSyntax -> MultiReturnType(typeSyntax.types.map { resolveTypeSyntax(it, context) })
        is VarargTypeSyntax -> VarargType(resolveTypeSyntax(typeSyntax.elementType, context))
        is FunctionTypeSyntax -> resolveFunctionTypeSyntax(typeSyntax, context)
        is ObjectTypeSyntax -> resolveObjectTypeSyntax(typeSyntax, context)
        is IndexTableTypeSyntax -> TableType(
            indexSignature = TableType.IndexSignature(
                keyType = resolveTypeSyntax(typeSyntax.keyType, context),
                valueType = resolveTypeSyntax(typeSyntax.valueType, context)
            )
        )
    }

    private fun resolveNamedType(name: String, context: TypeResolutionContext): Type {
        primitiveTypeFor(name)?.let { return it }
        val declaration = context.resolveTypeReference(name) ?: return io.github.dingyi222666.luaparser.semantic.types.model.CustomType(name)
        return if (declaration.kind == DeclarationKind.TYPE_PARAMETER) {
            TypeParameterType(
                name = declaration.name,
                constraint = declaration.declaredTypeSyntax?.let { resolveTypeSyntax(it, context) },
                defaultType = declaration.declaredType
            )
        } else {
            binder.declarationIndex.getDeclaration(declaration.id)?.declaredType
                ?: declaration.declaredType
                ?: io.github.dingyi222666.luaparser.semantic.types.model.CustomType(name)
        }
    }

    private fun resolveGenericType(typeSyntax: GenericTypeSyntax, context: TypeResolutionContext): Type {
        val baseName = (typeSyntax.baseType as? NamedTypeSyntax)?.name
            ?: resolveTypeSyntax(typeSyntax.baseType, context).displayName
        val arguments = typeSyntax.arguments.map { resolveTypeSyntax(it, context) }
        return if (baseName == "table" && arguments.size == 2) {
            TableType(indexSignature = TableType.IndexSignature(arguments[0], arguments[1]))
        } else {
            AppliedType(baseName = baseName, typeArguments = arguments)
        }
    }

    private fun resolveFunctionTypeSyntax(typeSyntax: FunctionTypeSyntax, context: TypeResolutionContext): FunctionType {
        val parameters = typeSyntax.parameters.map { parameter ->
            val baseType = resolveTypeSyntax(parameter.type, context)
            FunctionParameter(
                name = parameter.name ?: if (parameter.vararg) "..." else "_",
                type = if (parameter.vararg) VarargType(baseType) else baseType,
                optional = parameter.optional,
                vararg = parameter.vararg
            )
        }
        val returnType = resolveTypeSyntax(typeSyntax.returnType, context)
        return FunctionType(parameters = parameters, returnType = returnType)
    }

    private fun resolveObjectTypeSyntax(typeSyntax: ObjectTypeSyntax, context: TypeResolutionContext): Type {
        val fields = linkedMapOf<String, Type>()
        typeSyntax.fields.forEach { field ->
            val name = when (val fieldName = field.name) {
                is IdentifierObjectFieldNameSyntax -> fieldName.value
                is QuotedObjectFieldNameSyntax -> fieldName.literal.removeSurrounding("\"").removeSurrounding("'")
            }
            val valueType = resolveTypeSyntax(field.type, context)
            fields[name] = if (field.optional) unionTypeOf(valueType, PrimitiveType.NIL) else valueType
        }

        val indexSignature = typeSyntax.indexers.firstOrNull()?.let { indexer ->
            TableType.IndexSignature(
                keyType = resolveTypeSyntax(indexer.keyType, context),
                valueType = resolveTypeSyntax(indexer.valueType, context)
            )
        }
        return TableType(fields = fields, indexSignature = indexSignature)
    }

    private fun normalizeLiteral(value: String): Type {
        val normalized = value.trim()
        return when {
            normalized == "true" -> LiteralType(true, PrimitiveType.BOOLEAN)
            normalized == "false" -> LiteralType(false, PrimitiveType.BOOLEAN)
            normalized == "nil" -> PrimitiveType.NIL
            normalized.startsWith("\"") || normalized.startsWith("'") -> {
                LiteralType(normalized.removeSurrounding("\"").removeSurrounding("'"), PrimitiveType.STRING)
            }

            normalized.contains('.') -> LiteralType(normalized.toDoubleOrNull() ?: normalized, PrimitiveType.NUMBER)
            else -> LiteralType(normalized.toLongOrNull() ?: normalized, PrimitiveType.NUMBER)
        }
    }

    private fun primitiveTypeFor(name: String): Type? = when (name) {
        "string" -> PrimitiveType.STRING
        "number", "integer" -> PrimitiveType.NUMBER
        "boolean", "bool" -> PrimitiveType.BOOLEAN
        "nil", "void" -> PrimitiveType.NIL
        "function" -> PrimitiveType.FUNCTION
        "table" -> PrimitiveType.TABLE
        "thread" -> PrimitiveType.THREAD
        "userdata" -> PrimitiveType.USERDATA
        "any" -> PrimitiveType.ANY
        "unknown" -> UnknownType
        else -> null
    }
}
