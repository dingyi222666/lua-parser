package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType

internal class NodeTypeIndex(
    private val binder: BinderPassResult,
    private val evaluator: ExpressionTypeEvaluator,
    private val adapters: ApiAdapters
) {
    private val cachedTypes = mutableMapOf<BaseASTNode, io.github.dingyi222666.luaparser.semantic.api.TypeInfo?>()

    fun getTypeAt(node: BaseASTNode): io.github.dingyi222666.luaparser.semantic.api.TypeInfo? {
        if (cachedTypes.containsKey(node)) return cachedTypes[node]

        val declaration = binder.declarationIndex.getDeclarations(node).firstOrNull()
        val type = when {
            declaration?.declaredType != null -> {
                // Unannotated local/global functions bind as fun(...): unknown. Prefer body
                // inference (TextView after `return tv`) so hover matches call-result typing.
                val declared = declaration.declaredType
                if (shouldPreferInferredCallable(declaration, declared)) {
                    adapters.toTypeInfo(evaluator.inferDeclarationType(declaration), declaration)
                } else {
                    adapters.toTypeInfo(declared, declaration)
                }
            }

            declaration != null -> {
                adapters.toTypeInfo(evaluator.inferDeclarationType(declaration), declaration)
            }

            node is ExpressionNode -> adapters.toTypeInfo(evaluator.evaluate(node))

            else -> {
                binder.scopeGraph.getScope(node)
                    ?.ownerDeclarationId
                    ?.let(binder.declarationIndex::getDeclaration)
                    ?.declaredType
                    ?.let(adapters::toTypeInfo)
            }
        }

        cachedTypes[node] = type
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
        return adapters.toTypeInfo(evaluator.inferDeclarationType(declaration), declaration)
    }

}

/**
 * Returns the RHS expression matching [declaration] within its enclosing LocalStatement,
 * used by reference and import queries.
 */
internal fun localDeclarationInitializer(declaration: BinderDeclaration): ExpressionNode? {
    val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return null
    val initializerIndex = localStatement.init.indexOf(declaration.anchorNode)
    if (initializerIndex < 0) {
        return null
    }
    // LocalStatement: init = names, variables = RHS expressions.
    return localStatement.variables.getOrNull(initializerIndex)
}
