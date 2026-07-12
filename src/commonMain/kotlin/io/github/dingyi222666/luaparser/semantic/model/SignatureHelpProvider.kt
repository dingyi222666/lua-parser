package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.semantic.api.ParameterInformation
import io.github.dingyi222666.luaparser.semantic.api.SignatureHelp
import io.github.dingyi222666.luaparser.semantic.api.SignatureInformation
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.checker.CallChecker
import io.github.dingyi222666.luaparser.semantic.checker.CallResolution
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.checker.ValueSequence
import io.github.dingyi222666.luaparser.semantic.checker.isColonMethodDeclaration
import io.github.dingyi222666.luaparser.semantic.checker.resolveOwningFunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf

internal class SignatureHelpProvider(
    private val binder: BinderPassResult,
    private val nodePositionIndex: NodePositionIndex,
    private val evaluator: ExpressionTypeEvaluator
) {
    private val callChecker = CallChecker(binder)
    private val memberResolver = MemberResolver(binder)

    fun getSignatureHelpAt(position: Position): SignatureHelp? {
        val call = enclosingCallExpression(position) ?: return null
        if (!isWithinCallArguments(call, position)) {
            return null
        }
        val lexicalScopeId = binder.positionQueries.getScopeAt(call.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val callableBase = callableBase(call)
        val declaration = callableDeclaration(callableBase)
        val inferredCallableType = evaluateCallableType(callableBase, lexicalScopeId, declaration)
        // TASK-394: product wire for luajava.createProxy SignatureHelp. Prefer documented
        // interfaceNames/callbacks/JavaProxy overloads when the call is a recognized helper
        // (direct member or local alias). Null remains acceptable when the call is not createProxy.
        // No runtime proxy validation is invented here — labels only.
        val documentedCreateProxy = documentedCreateProxyCallableType(callableBase, declaration)
        val callableType = when {
            documentedCreateProxy != null && shouldPreferDocumentedCreateProxy(inferredCallableType) ->
                documentedCreateProxy
            inferredCallableType != null -> inferredCallableType
            documentedCreateProxy != null -> documentedCreateProxy
            else -> return null
        }
        // Rank with CallChecker first so activeSignature uses the same signature list
        // the checker scored (primary + @overload / OverloadedFunctionType), not a second
        // resolve pass that could drift.
        val argumentTypes = argumentTypesForResolution(call, lexicalScopeId)
        val callResolution = callChecker.checkCall(callableType, argumentTypes, lexicalScopeId, declaration)
        val resolutionSignatures = callResolution.callableResolution?.signatures
            ?.takeIf { it.isNotEmpty() }
            ?: callChecker.resolveCallable(callableType, lexicalScopeId, declaration)
                .takeIf { it.isSuccess }
                ?.signatures
                .orEmpty()
        if (resolutionSignatures.isEmpty()) {
            return null
        }

        // Reflected Java static/instance members often surface generic callables as
        // fun<T>(...): R. That form does not contain the literal "fun(" substring and also
        // omits the Java method name, so LSP hard-locks for Arrays.asList / similar static
        // members fail. Prefer labels that include the call-site member name when present.
        val methodName = callableMethodName(callableBase, declaration)
        val signatures = resolutionSignatures.map { toSignatureInformation(it, methodName) }
        val activeParameter = activeParameterIndex(call, position)
        val activeSignature = selectActiveSignatureIndex(resolutionSignatures, callResolution)
            .coerceIn(0, signatures.lastIndex)
        return SignatureHelp(
            signatures = signatures,
            activeSignature = activeSignature,
            activeParameter = clampActiveParameter(resolutionSignatures.getOrNull(activeSignature), activeParameter)
        )
    }

    /**
     * Map CallChecker-selected [CallResolution.selectedSignature] onto the multi-signature
     * help list. Prefer exact identity/equality, then label, then parameter-shape so
     * activeSignature is the ranked best match (not always 0) when argument types discriminate.
     */
    private fun selectActiveSignatureIndex(
        signatures: List<FunctionType>,
        callResolution: CallResolution
    ): Int {
        if (signatures.isEmpty()) {
            return 0
        }
        val selected = callResolution.selectedSignature ?: return 0
        val exact = signatures.indexOf(selected)
        if (exact >= 0) {
            return exact
        }
        val byLabel = signatures.indexOfFirst { candidate ->
            candidate.displayName == selected.displayName || candidate.name == selected.name
        }
        if (byLabel >= 0) {
            return byLabel
        }
        val byShape = signatures.indexOfFirst { candidate ->
            signatureShapeMatches(candidate, selected)
        }
        return byShape.takeIf { it >= 0 } ?: 0
    }

    private fun signatureShapeMatches(left: FunctionType, right: FunctionType): Boolean {
        if (left.parameters.size != right.parameters.size) {
            return false
        }
        if (left.returnType != right.returnType &&
            left.returnType.displayName != right.returnType.displayName
        ) {
            return false
        }
        return left.parameters.zip(right.parameters).all { (a, b) ->
            a.name == b.name &&
                a.optional == b.optional &&
                a.vararg == b.vararg &&
                (a.type == b.type || a.type.displayName == b.type.displayName)
        }
    }

    private fun clampActiveParameter(signature: FunctionType?, activeParameter: Int): Int {
        if (signature == null || signature.parameters.isEmpty()) {
            return 0
        }
        // Vararg / trailing optionals: keep the cursor index inside the active signature.
        return activeParameter.coerceIn(0, signature.parameters.lastIndex)
    }

    private fun toSignatureInformation(
        signature: FunctionType,
        methodName: String? = null
    ): SignatureInformation {
        return SignatureInformation(
            label = signatureHelpLabel(signature, methodName),
            parameters = signature.parameters.map(::toParameterInformation)
        )
    }

    /**
     * Build the LSP signature label for one overload.
     *
     * Plain Lua/function surfaces already use `fun(...): R` and must keep that exact form
     * for existing exact-label asserts. Generic Java reflection surfaces render as
     * `fun<T>(...): R` (type parameters sit between `fun` and `(`), so they match neither
     * `fun(` nor the member name. When the call base is a named member (e.g. asList),
     * prefix the member name so signature help remains discoverable.
     */
    private fun signatureHelpLabel(signature: FunctionType, methodName: String?): String {
        val display = signature.displayName
        if (display.contains("fun(")) {
            return display
        }
        val trimmedName = methodName?.trim().orEmpty()
        if (trimmedName.isNotEmpty() && !display.contains(trimmedName)) {
            return "$trimmedName $display"
        }
        return display
    }

    private fun callableMethodName(
        callableBase: ExpressionNode,
        declaration: BinderDeclaration?
    ): String? {
        return when (callableBase) {
            is MemberExpression -> callableBase.identifier.name.takeIf { it.isNotBlank() }
            is Identifier -> callableBase.name.takeIf { it.isNotBlank() }
            else -> declaration?.name?.takeIf { it.isNotBlank() }
        }
    }

    private fun toParameterInformation(parameter: FunctionParameter): ParameterInformation {
        return ParameterInformation(parameterLabel(parameter))
    }

    private fun parameterLabel(parameter: FunctionParameter): String {
        return buildString {
            if (parameter.name != "...") {
                append(parameter.name)
                if (parameter.optional) append('?')
                append(": ")
            }
            append(parameter.type.displayName)
            if (parameter.vararg && parameter.type !is VarargType) {
                append("...")
            }
        }
    }

    private fun callArguments(call: CallExpression): List<ExpressionNode> {
        val stringCall = call.base as? StringCallExpression
        return buildList {
            if (stringCall != null) {
                addAll(stringCall.arguments)
            }
            addAll(call.arguments)
        }
    }

    private fun activeParameterIndex(call: CallExpression, position: Position): Int {
        val arguments = callArguments(call)
        val receiverOffset = receiverOffset(call)
        if (arguments.isEmpty()) {
            return receiverOffset
        }

        val firstArgumentStart = arguments.first().range.start
        if (compare(position, firstArgumentStart) < 0) {
            return receiverOffset
        }

        arguments.forEachIndexed { index, argument ->
            val range = argument.range
            if (containsExclusive(range, position)) {
                return receiverOffset + index
            }

            val nextArgumentStart = arguments.getOrNull(index + 1)?.range?.start
            if (nextArgumentStart != null && compare(position, range.end) >= 0 && compare(position, nextArgumentStart) < 0) {
                return receiverOffset + index + 1
            }
        }

        return receiverOffset + arguments.lastIndex
    }

    private fun receiverOffset(call: CallExpression): Int {
        return if (call.base is MemberExpression && (call.base as MemberExpression).indexer == ":") 1 else 0
    }

    private fun argumentTypesForResolution(call: CallExpression, lexicalScopeId: ScopeId): List<Type> {
        val context = ExpressionTypeEvaluator.Context(lexicalScopeId = lexicalScopeId)
        val arguments = callArguments(call)
        val argumentTypes = mutableListOf<Type>()
        if (call.base is MemberExpression && (call.base as MemberExpression).indexer == ":") {
            argumentTypes += ValueSequence.of(evaluator.evaluate((call.base as MemberExpression).base, context)).collapseToSingle().fixed
        }
        arguments.forEachIndexed { index, argument ->
            val sequence = ValueSequence.of(evaluator.evaluate(argument, context))
            val effective = if (index == arguments.lastIndex) sequence else sequence.collapseToSingle()
            effective.appendToCallArguments(argumentTypes)
        }
        return argumentTypes
    }

    private fun evaluateCallableType(node: ExpressionNode, lexicalScopeId: ScopeId, declaration: BinderDeclaration?): Type? {
        val context = ExpressionTypeEvaluator.Context(lexicalScopeId = lexicalScopeId)
        val inferredType = evaluator.evaluate(node, context)
        val declarationCallableType = declaration?.let(::callableTypeForDeclaration)

        if (declarationCallableType != null && shouldPreferDeclarationCallableType(inferredType)) {
            return declarationCallableType
        }

        val resolved = callChecker.resolveCallable(inferredType, lexicalScopeId, declaration)
        if (inferredType is CallableType || resolved.isSuccess) {
            return inferredType
        }

        if (declarationCallableType != null) {
            return declarationCallableType
        }

        return inferredType.takeIf { it is CallableType }
    }

    private fun shouldPreferDeclarationCallableType(type: Type): Boolean {
        val callable = type as? CallableType ?: return true
        return callable.callSignatures.all { it.returnType == UnknownType }
    }

    /**
     * Documented Android-Lua / LuaJava createProxy overload surface (labels only).
     * Matches builtin overlay / luajava.lua:
     * - fun(interfaceNames: string, callbacks: { [string]: function }): JavaProxy
     * - fun(interfaceName1: string, interfaceName2: string, callbacks: { [string]: function }): JavaProxy
     * - fun(interfaceName: string, callbacks: { [string]: function }): JavaProxy
     *
     * Does not invent runtime proxy construction or callback-table validation.
     */
    private fun documentedCreateProxyCallableType(
        callableBase: ExpressionNode,
        declaration: BinderDeclaration?
    ): CallableType? {
        if (!isCreateProxyHelperCallBase(callableBase, declaration)) {
            return null
        }
        return documentedCreateProxyOverloads()
    }

    private fun shouldPreferDocumentedCreateProxy(type: Type?): Boolean {
        if (type == null) {
            return true
        }
        if (type !is CallableType) {
            return true
        }
        if (looksLikeDocumentedCreateProxyCallable(type)) {
            return false
        }
        // Prefer documented labels over incomplete/unknown/generic callable surfaces.
        return type.callSignatures.isEmpty() ||
            type.callSignatures.all { signature ->
                signature.returnType == UnknownType ||
                    signature.parameters.isEmpty() ||
                    !looksLikeDocumentedCreateProxyLabel(signature.displayName)
            }
    }

    private fun looksLikeDocumentedCreateProxyCallable(type: CallableType): Boolean {
        return type.callSignatures.any { looksLikeDocumentedCreateProxyLabel(it.displayName) } ||
            looksLikeDocumentedCreateProxyLabel(type.displayName)
    }

    private fun looksLikeDocumentedCreateProxyLabel(label: String): Boolean {
        if (label.isBlank()) {
            return false
        }
        val hasInterface =
            label.contains("interfaceNames", ignoreCase = true) ||
                label.contains("interfaceName", ignoreCase = true)
        val hasCallbacks = label.contains("callbacks", ignoreCase = true)
        val hasProxy = label.contains("JavaProxy", ignoreCase = true)
        return (hasInterface && hasCallbacks) ||
            (hasInterface && hasProxy) ||
            (hasCallbacks && hasProxy)
    }

    private fun documentedCreateProxyOverloads(): CallableType {
        val callbacksType = TableType(
            indexSignature = TableType.IndexSignature(
                keyType = PrimitiveType.STRING,
                valueType = PrimitiveType.FUNCTION
            )
        )
        val javaProxy = CustomType("JavaProxy")
        val singleInterfaceCommaList = FunctionType(
            parameters = listOf(
                FunctionParameter(name = "interfaceNames", type = PrimitiveType.STRING),
                FunctionParameter(name = "callbacks", type = callbacksType)
            ),
            returnType = javaProxy
        )
        val twoInterfaceNames = FunctionType(
            parameters = listOf(
                FunctionParameter(name = "interfaceName1", type = PrimitiveType.STRING),
                FunctionParameter(name = "interfaceName2", type = PrimitiveType.STRING),
                FunctionParameter(name = "callbacks", type = callbacksType)
            ),
            returnType = javaProxy
        )
        val singleInterfaceName = FunctionType(
            parameters = listOf(
                FunctionParameter(name = "interfaceName", type = PrimitiveType.STRING),
                FunctionParameter(name = "callbacks", type = callbacksType)
            ),
            returnType = javaProxy
        )
        return OverloadedFunctionType(
            callSignatures = listOf(singleInterfaceCommaList, twoInterfaceNames, singleInterfaceName)
        )
    }

    private fun isCreateProxyHelperCallBase(
        callableBase: ExpressionNode,
        declaration: BinderDeclaration?
    ): Boolean {
        return when (callableBase) {
            is MemberExpression -> isLuaJavaCreateProxyMember(callableBase)
            is Identifier -> {
                // Prefer declaration chain for aliases; fall back to name-based lookup.
                val resolved = declaration ?: findVisibleValueDeclaration(callableBase.name, callableBase.range.start)
                resolved != null && declarationResolvesToLuaJavaCreateProxy(resolved, linkedSetOf())
            }
            else -> false
        }
    }

    private fun isLuaJavaCreateProxyMember(member: MemberExpression): Boolean {
        val owner = member.base as? Identifier ?: return false
        if (member.indexer != "." || member.identifier.name != "createProxy" || owner.name != "luajava") {
            return false
        }
        // Unshadowed / builtin `luajava` is the helper owner; local non-builtin bindings shadow it.
        val ownerDeclaration = findVisibleValueDeclaration(owner.name, owner.range.start)
        return ownerDeclaration == null || ownerDeclaration.origin == DeclarationOrigin.BUILTIN
    }

    private fun declarationResolvesToLuaJavaCreateProxy(
        declaration: BinderDeclaration,
        visited: MutableSet<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId>
    ): Boolean {
        if (declaration.kind != DeclarationKind.LOCAL || !visited.add(declaration.id)) {
            return false
        }
        return when (val initializer = localDeclarationInitializer(declaration)) {
            is MemberExpression -> isLuaJavaCreateProxyMember(initializer)
            is Identifier -> {
                val next = findVisibleValueDeclaration(initializer.name, initializer.range.start) ?: return false
                // Same-statement locals should not be treated as earlier aliases.
                if (next.id == declaration.id) {
                    return false
                }
                declarationResolvesToLuaJavaCreateProxy(next, visited)
            }
            else -> false
        }
    }

    private fun localDeclarationInitializer(declaration: BinderDeclaration): ExpressionNode? {
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return null
        val initializerIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (initializerIndex < 0) {
            return null
        }
        // LocalStatement: init = names, variables = RHS expressions.
        return localStatement.variables.getOrNull(initializerIndex)
    }

    private fun callableTypeForDeclaration(declaration: BinderDeclaration): CallableType? {
        val declared = declaration.declaredType as? CallableType
        if (declared != null && declared.callSignatures.any { it.returnType != UnknownType }) {
            return declared
        }
        return inferFunctionCallableType(declaration) ?: declared
    }

    private fun inferFunctionCallableType(declaration: BinderDeclaration): CallableType? {
        declaration.declaredType?.let { declared ->
            if (declared is CallableType && declared.callSignatures.any { it.returnType != UnknownType }) {
                return enrichMethodCallableType(declaration, declared)
            }
        }

        val functionNode = functionNodeForDeclaration(declaration) ?: return null
        val body = functionNode.body ?: return null
        val functionScopeId = functionNode.body?.let(binder.scopeGraph::getScope)?.id
            ?: binder.positionQueries.getScopeAt(functionNode.range.start)?.id
            ?: binder.scopeGraph.rootScope.id
        val parameterDeclarations = binder.declarationIndex
            .getOwnedDeclarations(DeclarationOwner.Declaration(declaration.id))
            .filter { it.kind.name == "PARAMETER" }
        val parameterDeclarationsByName = parameterDeclarations.associateBy { it.name }
        val parameterTags = declaration.documentation?.docComment?.tags.orEmpty().filterIsInstance<ParamTagSyntax>()
        val declaredSignature = (declaration.declaredType as? CallableType)?.callSignatures?.firstOrNull()
        val declaredParametersByName = declaredSignature?.parameters.orEmpty().associateBy { it.name }

        val parameters = buildList {
            if (isColonMethodDeclaration(binder, declaration)) {
                val selfType = parameterDeclarations.firstOrNull { it.name == "self" }?.declaredType
                    ?: declaration.documentation?.resolvedParameterTypes?.get("self")
                    ?: parameterTags.firstOrNull { it.name == "self" }?.typeText?.let { declaration.documentation?.resolvedParameterTypes?.get("self") }
                    ?: UnknownType
                add(FunctionParameter(name = "self", type = selfType, vararg = false))
            }
            addAll(functionNode.params.mapIndexed { index, parameterNode ->
                val parameterType = firstKnownType(
                    parameterDeclarationsByName[parameterNode.name]?.declaredType,
                    parameterDeclarations.getOrNull(index)?.declaredType,
                    declaredParametersByName[parameterNode.name]?.type,
                    declaredSignature?.parameters?.getOrNull(index)?.type,
                    declaration.documentation?.resolvedParameterTypes?.get(parameterNode.name)
                )
                FunctionParameter(
                    name = parameterNode.name,
                    type = parameterType,
                    vararg = parameterNode.name == "..." || parameterType is VarargType
                )
            })
        }
        val varargType = parameters.lastOrNull { it.vararg }?.type ?: VarargType(UnknownType)
        val context = evaluator.buildFunctionBodyContext(functionNode, parameters, functionScopeId, varargType)
        val returnSites = evaluator.collectReturnSites(body, context)
        val inferredReturnType = inferReturnType(returnSites.map { it.values })
        val returnType = if (inferredReturnType == UnknownType) {
            inferReturnedParameterType(declaration, returnSites, parameters.associate { it.name to it.type }) ?: inferredReturnType
        } else {
            inferredReturnType
        }
        return FunctionType(parameters = parameters, returnType = returnType)
    }

    private fun inferReturnedParameterType(
        declaration: BinderDeclaration,
        returnSites: List<ExpressionTypeEvaluator.ReturnSite>,
        parameterTypesByName: Map<String, Type>
    ): Type? {
        if (returnSites.isEmpty()) {
            return null
        }

        val returnedTypes = mutableListOf<Type>()
        for (returnSite in returnSites) {
            val returnedIdentifier = returnSite.statement?.arguments?.singleOrNull() as? Identifier ?: return null
            val returnedDeclaration = findVisibleValueDeclaration(returnedIdentifier.name, returnedIdentifier.range.start)
            if (returnedDeclaration?.kind?.name != "PARAMETER" || returnedDeclaration.owner != DeclarationOwner.Declaration(declaration.id)) {
                return null
            }
            val returnedType = parameterTypesByName[returnedIdentifier.name]
                ?.takeIf { it != UnknownType }
                ?: return null
            returnedTypes += returnedType
        }
        return unionTypeOf(returnedTypes)
    }

    private fun firstKnownType(vararg types: Type?): Type {
        return types.firstOrNull { it != null && it != UnknownType } ?: UnknownType
    }

    private fun functionNodeForDeclaration(declaration: BinderDeclaration): FunctionDeclaration? {
        return resolveOwningFunctionDeclaration(binder, declaration)
            ?: declaration.anchorNode?.parent as? FunctionDeclaration
    }

    private fun enrichMethodCallableType(declaration: BinderDeclaration, declared: CallableType): CallableType {
        if (declaration.kind.name != "METHOD") {
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
            signature.copy(
                parameters = parameters,
                name = FunctionType(
                    parameters = parameters,
                    returnType = signature.returnType,
                    typeParameters = signature.typeParameters
                ).name
            )
        }
        return when (enrichedSignatures.size) {
            0 -> declared
            1 -> enrichedSignatures.single()
            else -> OverloadedFunctionType(enrichedSignatures)
        }
    }


    private fun inferReturnType(returnSequences: List<ValueSequence>): Type {
        if (returnSequences.isEmpty()) {
            return PrimitiveType.NIL
        }

        val openTailTypes = returnSequences.mapNotNull(ValueSequence::variadicTail)
        val maxFixedArity = returnSequences.maxOf { it.fixed.size }
        val totalArity = if (openTailTypes.isEmpty()) maxFixedArity else maxOf(maxFixedArity, 1)

        if (totalArity <= 1 && openTailTypes.isEmpty()) {
            return unionTypeOf(returnSequences.map { it.typeAt(0) })
        }

        val slots = (0 until maxFixedArity)
            .map { index -> unionTypeOf(returnSequences.map { it.typeAt(index) }) }
            .toMutableList()
        if (openTailTypes.isNotEmpty()) {
            slots += VarargType(unionTypeOf(openTailTypes))
        }

        return if (slots.size == 1 && slots.single() is VarargType) {
            slots.single()
        } else {
            MultiReturnType(slots)
        }
    }

    private fun callableDeclaration(node: ExpressionNode): BinderDeclaration? {
        return when (node) {
            is MemberExpression -> memberCallableDeclaration(node)
            else -> {
                val identifier = effectiveCallBase(node) as? Identifier ?: return null
                findVisibleValueDeclaration(identifier.name, identifier.range.start)
            }
        }
    }

    private fun memberCallableDeclaration(node: MemberExpression): BinderDeclaration? {
        val lexicalScopeId = binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val visibleDeclaration = findVisibleValueDeclaration(node.identifier.name, node.identifier.range.start)
        if (visibleDeclaration != null) {
            return visibleDeclaration
        }

        val astMethodDeclaration = findVisibleAstMethodDeclaration(node, lexicalScopeId)
        if (astMethodDeclaration != null) {
            return astMethodDeclaration
        }

        val baseType = evaluator.evaluate(node.base, ExpressionTypeEvaluator.Context(lexicalScopeId = lexicalScopeId))
        val resolution = memberResolver.resolveMember(baseType, node.identifier.name, node.indexer == ":", lexicalScopeId)
        if (!resolution.isSuccess) {
            return null
        }

        return findBackingMemberDeclaration(
            baseType,
            node.identifier.name,
            resolution.accessKind == io.github.dingyi222666.luaparser.semantic.checker.MemberAccessKind.METHOD,
            lexicalScopeId
        )
    }

    private fun findVisibleAstMethodDeclaration(node: MemberExpression, lexicalScopeId: ScopeId): BinderDeclaration? {
        val baseIdentifier = node.base as? Identifier ?: return null
        var scope = binder.scopeGraph.getScope(lexicalScopeId)
        while (scope != null) {
            val lexicalOwner = scope.ownerNode
            val declaration = binder.declarationIndex.declarations.firstOrNull { candidate ->
                candidate.kind.name == "METHOD" &&
                    lexicalOwner != null &&
                    isDeclaredInLexicalOwnerChain(candidate, lexicalOwner) &&
                    isMethodBoundToBaseIdentifier(candidate, baseIdentifier.name) &&
                    isVisibleAt(candidate, node.identifier.range.start)
            }
            if (declaration != null) {
                return declaration
            }
            scope = scope.parentId?.let(binder.scopeGraph::getScope)
        }
        return binder.declarationIndex.declarations.firstOrNull { candidate ->
            candidate.kind.name == "METHOD" &&
                isMethodBoundToBaseIdentifier(candidate, baseIdentifier.name) &&
                isVisibleAt(candidate, node.identifier.range.start)
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
        val base = identifier.base as? Identifier ?: return false
        return base.name == baseName
    }

    private fun findVisibleValueDeclaration(name: String, position: Position): BinderDeclaration? {
        var scope = binder.positionQueries.getScopeAt(position) ?: return null
        while (true) {
            val declaration = scope.declarationIds
                .asReversed()
                .mapNotNull(binder.declarationIndex::getDeclaration)
                .firstOrNull { candidate ->
                    candidate.kind.namespace == DeclarationNamespace.VALUE &&
                        candidate.name == name &&
                        isVisibleAt(candidate, position)
                }
            if (declaration != null) {
                return declaration
            }
            scope = scope.parentId?.let(binder.scopeGraph::getScope) ?: return null
        }
    }

    private fun isVisibleAt(declaration: BinderDeclaration, position: Position): Boolean {
        val range = declaration.range ?: return true
        return compare(range.start, position) <= 0
    }

    private fun findBackingMemberDeclaration(
        baseType: Type,
        memberName: String,
        preferMethod: Boolean,
        lexicalScopeId: ScopeId
    ): BinderDeclaration? {
        val ownerName = memberOwnerName(baseType, lexicalScopeId) ?: return null
        return binder.declarationIndex.declarations
            .filter { declaration ->
                declaration.name == memberName &&
                    declaration.kind.namespace == DeclarationNamespace.MEMBER &&
                    declaration.owner == io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner.Declaration(ownerName.id)
            }
            .firstOrNull { declaration ->
                if (preferMethod) declaration.kind.name == "METHOD" else true
            }
            ?: binder.declarationIndex.declarations.firstOrNull { declaration ->
                declaration.name == memberName &&
                    declaration.kind.namespace == DeclarationNamespace.MEMBER &&
                    declaration.owner == io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner.Declaration(ownerName.id)
            }
    }

    private fun memberOwnerName(baseType: Type, lexicalScopeId: ScopeId): BinderDeclaration? {
        val normalized = io.github.dingyi222666.luaparser.semantic.types.resolve.TypeExpansion.expandForMemberSurface(baseType, lexicalScopeId, binder)
        val displayName = normalized.displayName.substringBefore('<')
        return binder.declarationIndex.declarations.firstOrNull { declaration ->
            declaration.kind.name == "CLASS" && declaration.name == displayName
        }
    }

    private fun callableBase(call: CallExpression): ExpressionNode {
        return when (val base = call.base) {
            is StringCallExpression -> effectiveCallBase(base.base)
            else -> effectiveCallBase(base)
        }
    }

    private fun effectiveCallBase(node: ExpressionNode): ExpressionNode {
        return when (node) {
            is MemberExpression -> node
            is CallExpression -> effectiveCallBase(node.base)
            else -> node
        }
    }

    private fun enclosingCallExpression(position: Position): CallExpression? {
        val enclosing = nodePositionIndex.findEnclosing(position)
        return enclosing
            .filterIsInstance<CallExpression>()
            .firstOrNull { isWithinCallArguments(it, position) }
            ?: enclosing
                .filterIsInstance<StringCallExpression>()
                .firstOrNull { isWithinCallArguments(it, position) }
    }

    private fun isWithinCallArguments(call: CallExpression, position: Position): Boolean {
        val arguments = callArguments(call)
        if (arguments.isEmpty()) {
            val argumentRegion = argumentRegion(call) ?: return false
            return contains(argumentRegion, position)
        }

        val firstArgumentStart = arguments.first().range.start
        if (compare(position, call.base.range.end) >= 0 && compare(position, firstArgumentStart) < 0) {
            return true
        }

        arguments.forEachIndexed { index, argument ->
            if (contains(argument.range, position)) {
                return true
            }

            val nextArgumentStart = arguments.getOrNull(index + 1)?.range?.start
            if (nextArgumentStart != null && compare(position, argument.range.end) >= 0 && compare(position, nextArgumentStart) < 0) {
                return true
            }
        }

        return compare(position, arguments.last().range.end) >= 0 && compare(position, call.range.end) <= 0
    }

    private fun argumentRegion(call: CallExpression): Range? {
        val start = call.base.range.end
        val end = call.range.end
        return if (compare(start, end) < 0) Range(start, end) else null
    }

    private fun contains(range: Range, position: Position): Boolean {
        return compare(position, range.start) >= 0 && compare(position, range.end) <= 0
    }

    private fun containsExclusive(range: Range, position: Position): Boolean {
        return compare(position, range.start) >= 0 && compare(position, range.end) < 0
    }

    private fun compare(left: Position, right: Position): Int {
        return when {
            left.line != right.line -> left.line.compareTo(right.line)
            else -> left.column.compareTo(right.column)
        }
    }
}
