package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
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
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
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

        if (declarationCallableType != null && shouldPreferDeclarationCallableType(inferredType, declarationCallableType)) {
            return declarationCallableType
        }

        val resolved = callChecker.resolveCallable(inferredType, lexicalScopeId, declaration)
        if (inferredType is CallableType || resolved.isSuccess) {
            // Body inference / merge often drops @generic type-parameter labels even when the
            // declaration still carries fun<T>(...): R. Restore those labels for signature help.
            return preserveGenericTypeParameterLabels(inferredType, declarationCallableType)
        }

        if (declarationCallableType != null) {
            return declarationCallableType
        }

        return inferredType.takeIf { it is CallableType }
    }

    private fun shouldPreferDeclarationCallableType(type: Type, declared: CallableType?): Boolean {
        val callable = type as? CallableType ?: return true
        if (callable.callSignatures.all { it.returnType == UnknownType }) {
            return true
        }
        // Prefer the documented generic form when inference produced a non-generic callable with
        // otherwise comparable parameter/return surfaces (TASK-670).
        return declared != null &&
            hasGenericTypeParameterLabels(declared) &&
            !hasGenericTypeParameterLabels(callable)
    }

    /**
     * Keep signature-help labels like `fun<T>(value: T): T` after type resolution.
     * Expression evaluation may rebuild FunctionType without typeParameters even though the
     * binder/TypeResolver declaration still owns @generic TYPE_PARAMETER children.
     */
    private fun preserveGenericTypeParameterLabels(type: Type, declared: CallableType?): Type {
        if (declared == null || type !is CallableType) {
            return type
        }
        if (!hasGenericTypeParameterLabels(declared) || hasGenericTypeParameterLabels(type)) {
            return type
        }
        return enrichCallableWithTypeParameters(type, declared)
    }

    private fun hasGenericTypeParameterLabels(callable: CallableType): Boolean {
        return callable.callSignatures.any { it.typeParameters.isNotEmpty() }
    }

    private fun enrichCallableWithTypeParameters(
        inferred: CallableType,
        declared: CallableType
    ): CallableType {
        val declaredSignatures = declared.callSignatures
        val fallbackTypeParameters = declaredSignatures
            .firstOrNull { it.typeParameters.isNotEmpty() }
            ?.typeParameters
            .orEmpty()
        val enriched = inferred.callSignatures.mapIndexed { index, signature ->
            if (signature.typeParameters.isNotEmpty()) {
                signature
            } else {
                val typeParameters = declaredSignatures.getOrNull(index)?.typeParameters
                    ?.takeIf { it.isNotEmpty() }
                    ?: fallbackTypeParameters
                if (typeParameters.isEmpty()) {
                    signature
                } else {
                    FunctionType(
                        parameters = signature.parameters,
                        returnType = signature.returnType,
                        typeParameters = typeParameters
                    )
                }
            }
        }
        return when (enriched.size) {
            0 -> inferred
            1 -> enriched.single()
            else -> OverloadedFunctionType(enriched)
        }
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
        val typeParameters = ownedTypeParametersForDeclaration(declaration)
            .ifEmpty { declaredSignature?.typeParameters.orEmpty() }
        return FunctionType(
            parameters = parameters,
            returnType = returnType,
            typeParameters = typeParameters
        )
    }

    private fun ownedTypeParametersForDeclaration(declaration: BinderDeclaration): List<TypeParameterType> {
        return binder.declarationIndex
            .getOwnedDeclarations(DeclarationOwner.Declaration(declaration.id))
            .filter { it.kind == DeclarationKind.TYPE_PARAMETER }
            .map { parameterDeclaration ->
                (parameterDeclaration.declaredType as? TypeParameterType)
                    ?: TypeParameterType(name = parameterDeclaration.name)
            }
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
        // Prefer the most-specific enclosing call from the half-open node index.
        findCallInEnclosingNodes(position, position)?.let { return it }

        // NodePositionIndex is half-open [start, end): the exact call.range.end cursor
        // (immediately after `)`) is outside the call node. Probe the previous column so
        // end-inclusive argument-region help can still resolve the call.
        if (position.column > 1) {
            val probe = Position(line = position.line, column = position.column - 1)
            findCallInEnclosingNodes(probe, position)?.let { return it }
        }

        // Parser-tight call ranges (and historical next-token finishNode inflation) leave
        // the following significant statement token outside the call node while product
        // policy still treats that token as inside the argument region. Scan preceding
        // siblings under the enclosing block for a covering call.
        return findCallCoveringFromEnclosingContext(position)
    }

    private fun findCallInEnclosingNodes(indexPosition: Position, argumentPosition: Position): CallExpression? {
        val enclosing = nodePositionIndex.findEnclosing(indexPosition)
        return enclosing
            .filterIsInstance<CallExpression>()
            .firstOrNull { isWithinCallArguments(it, argumentPosition) }
            ?: enclosing
                .filterIsInstance<StringCallExpression>()
                .firstOrNull { isWithinCallArguments(it, argumentPosition) }
    }

    private fun findCallCoveringFromEnclosingContext(position: Position): CallExpression? {
        val seeds = nodePositionIndex.findEnclosing(position)
        if (seeds.isEmpty()) {
            return null
        }
        val candidates = LinkedHashSet<CallExpression>()
        for (seed in seeds) {
            collectCallExpressions(seed, candidates)
            var current: BaseASTNode = seed
            while (true) {
                val parent = runCatching { current.parent }.getOrNull() ?: break
                if (parent is BlockNode) {
                    val statements = parent.statements
                    val idx = statements.indexOf(current)
                    if (idx > 0) {
                        // Walk nearest preceding statements first (typical next-token inflation).
                        for (i in (idx - 1) downTo 0) {
                            collectCallExpressions(statements[i], candidates)
                        }
                    }
                    if (parent.returnStatement === current) {
                        for (statement in statements) {
                            collectCallExpressions(statement, candidates)
                        }
                    }
                }
                collectCallExpressions(parent, candidates)
                current = parent
            }
        }
        return candidates
            .filter { isWithinCallArguments(it, position) }
            .minWithOrNull(compareBy({ argumentRegionSpan(it) }, { it.range.start.line }, { it.range.start.column }))
    }

    private fun collectCallExpressions(node: BaseASTNode, out: MutableSet<CallExpression>) {
        when (node) {
            is CallExpression -> {
                out += node
                collectCallExpressions(node.base, out)
                for (argument in node.arguments) {
                    collectCallExpressions(argument, out)
                }
            }
            is LocalStatement -> {
                for (value in node.variables) {
                    collectCallExpressions(value, out)
                }
            }
            is AssignmentStatement -> {
                // AssignmentStatement: init = LHS, variables = RHS.
                for (value in node.variables) {
                    collectCallExpressions(value, out)
                }
                for (target in node.init) {
                    collectCallExpressions(target, out)
                }
            }
            is ReturnStatement -> {
                for (argument in node.arguments) {
                    collectCallExpressions(argument, out)
                }
            }
            is CallStatement -> {
                runCatching { node.expression }.getOrNull()?.let { collectCallExpressions(it, out) }
            }
            is MemberExpression -> {
                collectCallExpressions(node.base, out)
            }
            is BlockNode -> {
                for (statement in node.statements) {
                    collectCallExpressions(statement, out)
                }
                node.returnStatement?.let { collectCallExpressions(it, out) }
            }
            else -> Unit
        }
    }

    private fun argumentRegionSpan(call: CallExpression): Int {
        val start = call.base.range.end
        val end = effectiveArgumentRegionEnd(call)
        val lineDelta = (end.line - start.line).coerceAtLeast(0)
        val columnDelta = end.column - start.column
        return lineDelta * 1_000_000 + columnDelta
    }

    private fun isWithinCallArguments(call: CallExpression, position: Position): Boolean {
        val arguments = callArguments(call)
        val regionEnd = effectiveArgumentRegionEnd(call)
        if (arguments.isEmpty()) {
            val start = call.base.range.end
            if (compare(start, regionEnd) >= 0) {
                return false
            }
            // End-inclusive argument region (empty `f()` and inflated next-token end).
            return compare(position, start) >= 0 && compare(position, regionEnd) <= 0
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

        // From the last argument through call.range.end (inclusive) and, when product policy
        // inflates past a tight `)`, through the next significant statement token start.
        return compare(position, arguments.last().range.end) >= 0 && compare(position, regionEnd) <= 0
    }

    /**
     * Inclusive end of the call argument region used for signature help.
     *
     * Always at least [CallExpression.range].end so the exact post-`)` cursor stays inside.
     * When the next sibling statement / return begins after that end, inflate to that token
     * start so help remains available on the historical finishNode next-token end (e.g. the
     * following `local` / `return`) while positions past it (e.g. `sentinel`) stay outside.
     */
    private fun effectiveArgumentRegionEnd(call: CallExpression): Position {
        val callEnd = call.range.end
        val inflated = nextSiblingStatementStart(call) ?: return callEnd
        return if (compare(inflated, callEnd) > 0) inflated else callEnd
    }

    private fun nextSiblingStatementStart(call: CallExpression): Position? {
        var current: BaseASTNode = call
        while (true) {
            val parent = runCatching { current.parent }.getOrNull() ?: return null
            when (parent) {
                is CallExpression -> {
                    val args = callArguments(parent)
                    val idx = args.indexOf(current)
                    if (idx >= 0 && idx + 1 < args.size) {
                        // Nested call followed by another argument: do not inflate into the
                        // outer call's later args (outer call owns that region).
                        return null
                    }
                    current = parent
                }
                is LocalStatement -> {
                    val idx = parent.variables.indexOf(current)
                    if (idx >= 0 && idx + 1 < parent.variables.size) {
                        return null
                    }
                    current = parent
                }
                is AssignmentStatement -> {
                    val idx = parent.variables.indexOf(current)
                    if (idx >= 0 && idx + 1 < parent.variables.size) {
                        return null
                    }
                    current = parent
                }
                is ReturnStatement -> {
                    val idx = parent.arguments.indexOf(current)
                    if (idx >= 0 && idx + 1 < parent.arguments.size) {
                        return null
                    }
                    current = parent
                }
                is CallStatement -> {
                    current = parent
                }
                is MemberExpression -> {
                    current = parent
                }
                is BlockNode -> {
                    val statements = parent.statements
                    val idx = statements.indexOf(current)
                    if (idx >= 0) {
                        if (idx + 1 < statements.size) {
                            return statements[idx + 1].range.start
                        }
                        return parent.returnStatement?.range?.start
                    }
                    if (parent.returnStatement === current) {
                        return null
                    }
                    current = parent
                }
                is FunctionDeclaration -> {
                    current = parent
                }
                else -> {
                    current = parent
                }
            }
        }
    }

    private fun argumentRegion(call: CallExpression): Range? {
        val start = call.base.range.end
        val end = effectiveArgumentRegionEnd(call)
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
