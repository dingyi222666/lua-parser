package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.compactCallBase
import io.github.dingyi222666.luaparser.parser.compactCallArguments

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
import io.github.dingyi222666.luaparser.semantic.api.ParameterInformation
import io.github.dingyi222666.luaparser.semantic.api.SignatureHelp
import io.github.dingyi222666.luaparser.semantic.api.SignatureInformation
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.comparePositions
import io.github.dingyi222666.luaparser.semantic.binder.positionLte
import io.github.dingyi222666.luaparser.semantic.binder.rangeContains
import io.github.dingyi222666.luaparser.semantic.checker.CallChecker
import io.github.dingyi222666.luaparser.semantic.checker.CallResolution
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.hydrateJavaProviderType
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.model.callableSummary

internal class SignatureHelpProvider(
    private val binder: BinderPassResult,
    private val nodePositionIndex: NodePositionIndex,
    private val evaluator: ExpressionTypeEvaluator,
    private val workspaceContext: SemanticWorkspaceContext = SemanticWorkspaceContext()
) {
    private val callChecker = CallChecker(binder)

    fun getSignatureHelpAt(position: Position): SignatureHelp? {
        val call = enclosingCallExpression(position) ?: return null
        if (!isWithinCallArguments(call, position)) {
            return null
        }
        val lexicalScopeId = binder.positionQueries.getScopeAt(call.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val callableBase = callableBase(call)
        val declaration = evaluator.callableDeclarationAt(callableBase)
        val callableType = evaluateCallableType(callableBase, lexicalScopeId, declaration) ?: return null
        // Rank with CallChecker first so activeSignature uses the same signature list
        // the checker scored (primary + @overload / OverloadedFunctionType), not a second
        // resolve pass that could drift.
        val argumentTypes = argumentTypesForResolution(call, lexicalScopeId)
        val callResolution = resolveCallForPresentation(callableType, argumentTypes, lexicalScopeId, declaration)
        val resolutionSignatures = callResolution.callableResolution?.signatures.orEmpty()
        if (resolutionSignatures.isEmpty()) {
            return null
        }

        val methodName = callableMethodName(callableBase, declaration)
        // FunctionType -> presentation signature: canonical label + per-parameter labels.
        val signatures = resolutionSignatures.map { signature ->
            SignatureInformation(
                label = signatureHelpLabel(signature, methodName),
                parameters = signature.parameters.map { ParameterInformation(parameterLabel(it)) }
            )
        }
        val activeParameter = activeParameterIndex(call, position)
        val activeSignature = selectActiveSignatureIndex(resolutionSignatures, callResolution)
            .coerceIn(0, signatures.lastIndex)
        return SignatureHelp(
            signatures = signatures,
            activeSignature = activeSignature,
            activeParameter = clampActiveParameter(resolutionSignatures.getOrNull(activeSignature), activeParameter)
        )
    }

    fun getCallableHoverAt(position: Position): CallableHoverInfo? {
        val enclosing = nodePositionIndex.findEnclosing(position)
        val expression = enclosing.filterIsInstance<MemberExpression>()
            .firstOrNull { contains(it.identifier.range, position) }
            ?: enclosing.filterIsInstance<Identifier>().firstOrNull()
            ?: return null
        val scope = binder.positionQueries.getScopeAt(expression.range.start)?.id ?: binder.scopeGraph.rootScope.id
        val declaration = evaluator.callableDeclarationAt(expression)
        val type = evaluateCallableType(expression, scope, declaration) ?: return null
        return CallableHoverInfo(callableDetail(type, expression, position, declaration))
    }

    fun callableDetail(
        type: Type,
        expression: ExpressionNode?,
        position: Position,
        declaration: BinderDeclaration? = null
    ): String {
        // Other rows need no callable expansion, normalization, or argument analysis.
        if (type !is CallableType && type !is AliasType && type !is IntersectionType &&
            type !is UnionType && type !is AppliedType && type !is TypeParameterType && type !is ModuleType) return type.displayName
        if (type is CallableType && expression == null && declaration == null) {
            return callableSummary(type.callSignatures)
        }
        val call = expression?.let { node ->
            (runCatching { node.parent }.getOrNull() as? CallExpression)
                ?.takeIf { callableBase(it) === node }
        }
        // Completion rows at `receiver.` / `receiver:prefix` are not calls yet. Do not
        // resolve every overload for every row; signature help performs the contextual
        // selection once the call actually exists.
        if (expression != null && call == null) {
            // Function declarations keep @overload tags separately from the primary type.
            // Include that metadata for the idle preview as well as at actual call sites.
            val overloads = declaration?.documentation?.resolvedOverloadTypes.orEmpty()
            return (type as? CallableType)?.let {
                callableSummary((it.callSignatures + overloads).distinct())
            } ?: type.displayName
        }
        val scope = binder.positionQueries.getScopeAt(position)?.id ?: binder.scopeGraph.rootScope.id
        val resolved = callChecker.resolveCallable(type, scope, declaration)
        if (!resolved.isSuccess) {
            return (type as? CallableType)?.let { callableSummary(it.callSignatures) }
                ?: type.displayName
        }
        // The checker ranks complete and partial calls deterministically. Even tied candidates
        // have a best preview; show that signature rather than resetting to the entire set.
        val selection = call?.let {
            resolveCallForPresentation(type, argumentTypesForResolution(it, scope), scope, declaration)
        }
        return selection?.selectedSignature?.let { FunctionType(it.parameters, it.returnType, it.typeParameters).displayName }
            ?: callableSummary(resolved.signatures)
    }

    private fun resolveCallForPresentation(
        type: Type,
        arguments: List<Type>,
        scope: ScopeId,
        declaration: BinderDeclaration?
    ): CallResolution {
        val checked = callChecker.checkCall(type, arguments, scope, declaration)
        return if (checked.selectedSignature != null) checked else
            callChecker.checkCall(type, arguments, scope, declaration, allowIncomplete = true)
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
        callResolution.selectedSignatureIndex?.takeIf { it in signatures.indices }?.let { return it }
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

    /** Vararg / trailing optionals: keep the cursor index inside the active signature. */
    private fun clampActiveParameter(signature: FunctionType?, activeParameter: Int): Int =
        if (signature == null || signature.parameters.isEmpty()) 0
        else activeParameter.coerceIn(0, signature.parameters.lastIndex)

    /** Render canonical type parameters and the call-site member name. */
    private fun signatureHelpLabel(signature: FunctionType, methodName: String?): String {
        val display = FunctionType(signature.parameters, signature.returnType, signature.typeParameters).displayName
        return if (signature.typeParameters.isNotEmpty() && !methodName.isNullOrBlank()) "$methodName $display" else display
    }

    private fun callableMethodName(
        callableBase: ExpressionNode,
        declaration: BinderDeclaration?
    ): String? {
        // Only member-access call sites need a discoverable method-name prefix on generic
        // labels (Arrays.asList, receiver:method). Bare Identifier/local function calls must
        // keep the pure fun(...) / fun<T>(...) form expected by workspace exact-label asserts.
        return (callableBase as? MemberExpression)
            ?.identifier?.name?.takeIf { it.isNotBlank() }
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

    private fun callArguments(call: CallExpression): List<ExpressionNode> = call.compactCallArguments()

    private fun activeParameterIndex(call: CallExpression, position: Position): Int {
        val arguments = callArguments(call)
        val receiverOffset = receiverOffset(call)
        if (arguments.isEmpty()) {
            return receiverOffset
        }

        val firstArgumentStart = arguments.first().range.start
        if (comparePositions(position, firstArgumentStart) < 0) {
            return receiverOffset
        }

        arguments.forEachIndexed { index, argument ->
            val range = argument.range
            if (rangeContains(range, position)) {
                return receiverOffset + index
            }

            val nextArgumentStart = arguments.getOrNull(index + 1)?.range?.start
            if (nextArgumentStart != null && comparePositions(position, range.end) >= 0 && comparePositions(position, nextArgumentStart) < 0) {
                return receiverOffset + index + 1
            }
        }

        return receiverOffset + arguments.lastIndex
    }

    private fun receiverOffset(call: CallExpression): Int {
        return if ((callableBase(call) as? MemberExpression)?.indexer == ":") 1 else 0
    }

    private fun argumentTypesForResolution(call: CallExpression, lexicalScopeId: ScopeId): List<Type> = buildList {
        evaluator.callArgumentValues(call, lexicalScopeId).forEach { it.appendToCallArguments(this) }
    }

    private fun evaluateCallableType(node: ExpressionNode, lexicalScopeId: ScopeId, declaration: BinderDeclaration?): Type? {
        val type = evaluator.evaluate(node, ExpressionTypeEvaluator.Context(lexicalScopeId))
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        return type.takeIf { callChecker.resolveCallable(it, lexicalScopeId, declaration).isSuccess }
    }

    private fun callableBase(call: CallExpression): ExpressionNode = call.compactCallBase()

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
        // StringCallExpression is a CallExpression subclass, so one filter covers both
        // `f(args)` and `f"str"` call shapes in enclosing-order.
        return nodePositionIndex.findEnclosing(indexPosition)
            .filterIsInstance<CallExpression>()
            .firstOrNull { isWithinCallArguments(it, argumentPosition) }
    }

    private fun findCallCoveringFromEnclosingContext(position: Position): CallExpression? =
        binder.scopeGraph.calls.asSequence()
            .filter { isWithinCallArguments(it, position) }
            .minWithOrNull(compareBy({ argumentRegionSpan(it) }, { it.range.start.line }, { it.range.start.column }))

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
            // End-inclusive argument region (empty `f()` and inflated next-token end).
            val start = call.base.range.end
            return comparePositions(start, regionEnd) < 0 &&
                comparePositions(position, start) >= 0 && comparePositions(position, regionEnd) <= 0
        }

        val firstArgumentStart = arguments.first().range.start
        if (comparePositions(position, call.base.range.end) >= 0 && comparePositions(position, firstArgumentStart) < 0) {
            return true
        }

        arguments.forEachIndexed { index, argument ->
            if (contains(argument.range, position)) {
                return true
            }

            val nextArgumentStart = arguments.getOrNull(index + 1)?.range?.start
            if (nextArgumentStart != null && comparePositions(position, argument.range.end) >= 0 && comparePositions(position, nextArgumentStart) < 0) {
                return true
            }
        }

        // From the last argument through call.range.end (inclusive) and, when product policy
        // inflates past a tight `)`, through the next significant statement token start.
        return comparePositions(position, arguments.last().range.end) >= 0 && comparePositions(position, regionEnd) <= 0
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
        return if (comparePositions(inflated, callEnd) > 0) inflated else callEnd
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
                is LocalStatement,
                is AssignmentStatement,
                is ReturnStatement -> {
                    // Same "nested call is not the last slot" rule for local/assignment
                    // initializers and return arguments: a later slot owns the following
                    // token, so do not inflate into it.
                    val slots = when (parent) {
                        is LocalStatement -> parent.variables
                        is AssignmentStatement -> parent.variables
                        else -> (parent as ReturnStatement).arguments
                    }
                    val idx = slots.indexOf(current)
                    if (idx >= 0 && idx + 1 < slots.size) {
                        return null
                    }
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
                // Wrapper statements / member chains: climb through them unchanged.
                is CallStatement,
                is MemberExpression,
                is FunctionDeclaration -> {
                    current = parent
                }
                else -> {
                    current = parent
                }
            }
        }
    }

    private fun contains(range: Range, position: Position): Boolean =
        positionLte(range.start, position) && positionLte(position, range.end)
}
