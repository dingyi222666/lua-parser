package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.ValueSequence
import io.github.dingyi222666.luaparser.semantic.checker.isColonMethodDeclaration
import io.github.dingyi222666.luaparser.semantic.checker.resolveOwningFunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf

internal class NodeTypeIndex(
    private val binder: BinderPassResult,
    private val evaluator: ExpressionTypeEvaluator,
    private val adapters: ApiAdapters
) {
    private val cachedNodes = mutableListOf<BaseASTNode>()
    private val cachedTypes = mutableListOf<io.github.dingyi222666.luaparser.semantic.api.TypeInfo?>()

    fun getTypeAt(node: BaseASTNode): io.github.dingyi222666.luaparser.semantic.api.TypeInfo? {
        cachedNodes.indexOfFirst { it === node }
            .takeIf { it >= 0 }
            ?.let(cachedTypes::get)
            ?.let { return it }

        val declaration = binder.declarationIndex.getDeclarations(node).firstOrNull()
        val type = when {
            declaration?.declaredType != null -> {
                // Unannotated local/global functions bind as fun(...): unknown. Prefer body
                // inference (TextView after `return tv`) so hover matches call-result typing.
                val declared = declaration.declaredType
                if (shouldPreferInferredCallable(declaration, declared)) {
                    adapters.toTypeInfo(inferredDeclarationType(declaration), declaration)
                } else {
                    adapters.toTypeInfo(declared, declaration)
                }
            }

            declaration != null -> {
                adapters.toTypeInfo(inferredDeclarationType(declaration), declaration)
            }

            node is ExpressionNode -> adapters.toTypeInfo(expressionType(node))

            else -> {
                binder.scopeGraph.getScope(node)
                    ?.ownerDeclarationId
                    ?.let(binder.declarationIndex::getDeclaration)
                    ?.declaredType
                    ?.let(adapters::toTypeInfo)
            }
        }

        cachedNodes += node
        cachedTypes += type
        return type
    }

    /**
     * Declared FunctionType with only-unknown returns should not freeze hover/type-at when the
     * body can infer a concrete return (e.g. `local function build() return TextView() end`).
     * Documented/Emmy returns keep declaredType priority.
     */
    private fun shouldPreferInferredCallable(declaration: BinderDeclaration, declared: Type?): Boolean {
        if (declaration.kind != DeclarationKind.FUNCTION &&
            declaration.kind != DeclarationKind.GLOBAL &&
            declaration.kind != DeclarationKind.METHOD
        ) {
            return false
        }
        val callable = declared as? CallableType ?: return false
        return callable.callSignatures.isNotEmpty() &&
            callable.callSignatures.all { signature -> signature.returnType == UnknownType }
    }

    fun getInferredType(declaration: BinderDeclaration): io.github.dingyi222666.luaparser.semantic.api.TypeInfo? {
        return adapters.toTypeInfo(inferredDeclarationType(declaration), declaration)
    }

    private fun inferredDeclarationType(declaration: BinderDeclaration): Type {
        return when (declaration.kind) {
            DeclarationKind.LOCAL -> {
                val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return UnknownType
                val initializerIndex = localStatement.init.indexOf(declaration.anchorNode)
                if (initializerIndex < 0) {
                    UnknownType
                } else {
                    evaluator.evaluate(localStatement.variables.getOrNull(initializerIndex) ?: return UnknownType)
                }
            }

            DeclarationKind.FUNCTION,
            DeclarationKind.GLOBAL,
            DeclarationKind.METHOD -> inferFunctionCallableType(declaration) ?: declaration.declaredType ?: UnknownType

            DeclarationKind.MODULE,
            DeclarationKind.PARAMETER -> declaration.declaredType ?: UnknownType

            else -> declaration.declaredType ?: UnknownType
        }
    }

    private fun expressionType(node: ExpressionNode): Type {
        val evaluated = evaluator.evaluate(node)
        if (evaluated != UnknownType) {
            return evaluated
        }

        if (node !is CallExpression) {
            return evaluated
        }

        val memberBase = node.base as? MemberExpression
        if (memberBase?.indexer == ":") {
            val baseIdentifier = memberBase.base as? Identifier ?: return evaluated
            val declaration = astMethodDeclarationForBase(baseIdentifier, node.range.start) ?: return evaluated
            val callableType = callableTypeForDeclaration(declaration) ?: return evaluated
            return callableType.callSignatures.firstOrNull()?.returnType ?: evaluated
        }

        val baseIdentifier = node.base as? Identifier ?: return evaluated
        val declaration = visibleValueDeclaration(baseIdentifier.name, node.range.start) ?: return evaluated
        val callableType = callableTypeForDeclaration(declaration) ?: return evaluated
        return callableType.callSignatures.firstOrNull()?.returnType ?: evaluated
    }

    private fun callableTypeForDeclaration(
        declaration: io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
    ): CallableType? {
        return inferFunctionCallableType(declaration) ?: (declaration.declaredType as? CallableType)
    }

    private fun astMethodDeclarationForBase(
        baseIdentifier: Identifier,
        position: io.github.dingyi222666.luaparser.parser.ast.node.Position
    ): BinderDeclaration? {
        var scope = binder.positionQueries.getScopeAt(position)
        while (scope != null) {
            val lexicalOwner = scope.ownerNode
            val declaration = binder.declarationIndex.declarations.firstOrNull { candidate ->
                candidate.kind == DeclarationKind.METHOD &&
                    lexicalOwner != null &&
                    isDeclaredInLexicalOwnerChain(candidate, lexicalOwner) &&
                    isMethodBoundToBaseIdentifier(candidate, baseIdentifier.name) &&
                    (candidate.range == null || compare(candidate.range.start, position) <= 0)
            }
            if (declaration != null) {
                return declaration
            }
            scope = scope.parentId?.let(binder.scopeGraph::getScope)
        }
        return null
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

    private fun visibleValueDeclaration(
        name: String,
        position: io.github.dingyi222666.luaparser.parser.ast.node.Position
    ): io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration? {
        var scope = binder.positionQueries.getScopeAt(position)
        while (scope != null) {
            val declaration = scope.declarationIds
                .asReversed()
                .mapNotNull(binder.declarationIndex::getDeclaration)
                .firstOrNull { candidate ->
                    candidate.kind.namespace == DeclarationNamespace.VALUE &&
                        candidate.name == name &&
                        (candidate.range == null || compare(candidate.range.start, position) <= 0)
                }
            if (declaration != null) {
                return declaration
            }
            scope = scope.parentId?.let(binder.scopeGraph::getScope)
        }
        return null
    }

    private fun inferFunctionCallableType(
        declaration: io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
    ): CallableType? {
        val declared = declaration.declaredType as? CallableType
        val functionNode = resolveOwningFunctionDeclaration(binder, declaration)
        val inferred = functionNode?.let(evaluator::inferImplementationFunctionType)

        return when {
            declared == null -> {
                if (declaration.kind == DeclarationKind.METHOD && inferred != null) {
                    enrichMethodCallableType(declaration, inferred)
                } else {
                    inferred
                }
            }
            inferred == null -> {
                if (declaration.kind == DeclarationKind.METHOD) {
                    enrichMethodCallableType(declaration, declared)
                } else {
                    declared
                }
            }
            declaration.kind == DeclarationKind.METHOD -> mergeCallableInference(declaration, declared, inferred)
            else -> mergeCallableInference(declaration, declared, inferred)
        }
    }

    private fun mergeCallableInference(
        declaration: BinderDeclaration,
        declared: CallableType,
        inferred: CallableType
    ): CallableType {
        val declaredSignature = declared.callSignatures.firstOrNull() ?: return enrichMethodCallableType(declaration, inferred)
        val inferredSignature = inferred.callSignatures.firstOrNull() ?: return enrichMethodCallableType(declaration, declared)
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
            name = FunctionType(
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

    private fun compare(a: Position, b: Position): Int {
        val lineComparison = a.line.compareTo(b.line)
        if (lineComparison != 0) {
            return lineComparison
        }
        return a.column.compareTo(b.column)
    }
}
