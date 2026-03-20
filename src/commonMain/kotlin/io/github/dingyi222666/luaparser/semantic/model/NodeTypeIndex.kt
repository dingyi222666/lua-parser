package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.ValueSequence
import io.github.dingyi222666.luaparser.semantic.checker.resolveOwningFunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
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
                adapters.toTypeInfo(declaration.declaredType, declaration)
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

        val baseIdentifier = node.base as? Identifier ?: return evaluated
        val declaration = visibleValueDeclaration(baseIdentifier.name, node.range.start) ?: return evaluated
        val callableType = callableTypeForDeclaration(declaration) ?: return evaluated
        return callableType.callSignatures.firstOrNull()?.returnType ?: evaluated
    }

    private fun callableTypeForDeclaration(
        declaration: io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
    ): CallableType? {
        val declared = declaration.declaredType as? CallableType
        if (declared != null && declared.callSignatures.any { it.returnType != UnknownType }) {
            return declared
        }

        return inferFunctionCallableType(declaration) ?: declared
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
        val functionNode = resolveOwningFunctionDeclaration(binder, declaration) ?: return null
        val body = functionNode.body ?: return null
        val functionScopeId = binder.scopeGraph.getScope(body)?.id ?: binder.scopeGraph.rootScope.id
        val parameterDeclarations = binder.scopeGraph.getScope(body)
            ?.declarationIds
            .orEmpty()
            .mapNotNull(binder.declarationIndex::getDeclaration)
            .filter { it.kind == io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind.PARAMETER }

        val parameters = functionNode.params.mapIndexed { index, parameterNode ->
            val parameterType = parameterDeclarations.getOrNull(index)?.declaredType ?: UnknownType
            FunctionParameter(
                name = parameterNode.name,
                type = parameterType,
                vararg = parameterNode.name == "..." || parameterType is VarargType
            )
        }
        val varargType = parameters.lastOrNull { it.vararg }?.type ?: VarargType(UnknownType)
        val context = evaluator.buildFunctionBodyContext(functionNode, parameters, functionScopeId, varargType)
        val returnSites = evaluator.collectReturnSites(body, context)
        val returnType = inferReturnType(returnSites.map { it.values })
        return FunctionType(parameters = parameters, returnType = returnType)
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
