package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult

enum class MemberAccessKind {
    FIELD,
    METHOD,
    INDEX
}

enum class MemberFailureReason {
    MISSING_MEMBER,
    INVALID_INDEX_TYPE,
    UNSUPPORTED_BASE_TYPE,
    UNRESOLVED_APPLIED_TYPE
}

data class MemberResolution(
    val type: Type? = null,
    val accessKind: MemberAccessKind? = null,
    val baseType: Type? = null,
    val failureReason: MemberFailureReason? = null
) {
    val isSuccess: Boolean
        get() = type != null
}

enum class CallFailureReason {
    NON_CALLABLE,
    NO_MATCHING_SIGNATURE,
    AMBIGUOUS_MATCH
}

data class CallableResolution(
    val callableType: CallableType? = null,
    val normalizedType: Type? = null,
    val signatures: List<FunctionType> = emptyList(),
    val failureReason: CallFailureReason? = null
) {
    val isSuccess: Boolean
        get() = callableType != null
}

data class CallResolution(
    val returnType: Type? = null,
    val callableResolution: CallableResolution? = null,
    val selectedSignature: FunctionType? = null,
    val failureReason: CallFailureReason? = null,
    val ambiguous: Boolean = false
) {
    val isSuccess: Boolean
        get() = returnType != null
}

internal fun resolveOwningFunctionDeclaration(
    binder: BinderPassResult,
    declaration: BinderDeclaration
): FunctionDeclaration? {
    findFunctionByAnchorInContainer(declaration.anchorNode)?.let { return it }
    findAncestorFunction(declaration.anchorNode)?.let { return it }

    binder.declarationIndex
        .getOwnedDeclarations(DeclarationOwner.Declaration(declaration.id))
        .firstNotNullOfOrNull { owned ->
            findFunctionByAnchorInContainer(owned.anchorNode) ?: findAncestorFunction(owned.anchorNode)
        }
        ?.let { return it }

    val functionOwnerNode = binder.scopeGraph.scopes
        .firstOrNull { it.ownerDeclarationId == declaration.id }
        ?.ownerNode

    return findFunctionOwnedByNode(functionOwnerNode) ?: findAncestorFunction(functionOwnerNode)
}

private fun findAncestorFunction(node: BaseASTNode?): FunctionDeclaration? {
    var current = node
    while (current != null) {
        if (current is FunctionDeclaration) {
            return current
        }
        current = runCatching { current.parent }.getOrNull()
    }
    return null
}

private fun findFunctionByAnchorInContainer(anchorNode: BaseASTNode?): FunctionDeclaration? {
    val container = runCatching { anchorNode?.parent }.getOrNull() ?: return null
    return functionDeclarationsIn(container).firstOrNull { it.identifier === anchorNode }
}

private fun findFunctionOwnedByNode(node: BaseASTNode?): FunctionDeclaration? {
    return when (node) {
        is FunctionDeclaration -> node
        is BlockNode -> node.parent as? FunctionDeclaration
        else -> null
    }
}

private fun functionDeclarationsIn(node: BaseASTNode): List<FunctionDeclaration> {
    return when (node) {
        is BlockNode -> node.statements.filterIsInstance<FunctionDeclaration>()
        is ChunkNode -> node.body.statements.filterIsInstance<FunctionDeclaration>()
        else -> emptyList()
    }
}
