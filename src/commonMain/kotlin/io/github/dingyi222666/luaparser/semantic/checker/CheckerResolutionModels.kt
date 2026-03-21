package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.Type

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
    findFunctionByMatchingIdentifierAnchor(binder, declaration)?.let { return it }
    findFunctionByOwner(binder, declaration)?.let { return it }

    binder.declarationIndex
        .getOwnedDeclarations(DeclarationOwner.Declaration(declaration.id))
        .firstNotNullOfOrNull { owned ->
            findFunctionByAnchorInContainer(owned.anchorNode)
                ?: findAncestorFunction(owned.anchorNode)
                ?: findFunctionByMatchingIdentifierAnchor(binder, owned)
                ?: findFunctionByOwner(binder, owned)
        }
        ?.let { return it }

    val functionOwnerNode = binder.scopeGraph.scopes
        .firstOrNull { it.ownerDeclarationId == declaration.id }
        ?.ownerNode

    return findFunctionOwnedByNode(functionOwnerNode) ?: findAncestorFunction(functionOwnerNode)
}

internal fun isColonMethodDeclaration(
    binder: BinderPassResult,
    declaration: BinderDeclaration
): Boolean {
    if (declaration.kind != DeclarationKind.METHOD) {
        return false
    }

    val anchorMember = runCatching { declaration.anchorNode?.parent }.getOrNull() as? io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
    if (anchorMember?.indexer == ":") {
        return true
    }

    val functionNode = resolveOwningFunctionDeclaration(binder, declaration) ?: return false
    val methodIdentifier = functionNode.identifier as? io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression ?: return false
    return methodIdentifier.indexer == ":"
}

private fun findFunctionByMatchingIdentifierAnchor(
    binder: BinderPassResult,
    declaration: BinderDeclaration
): FunctionDeclaration? {
    val anchor = declaration.anchorNode ?: return null
    val rootNode = findRootNode(anchor) ?: return null
    return functionDeclarationsIn(rootNode)
        .firstOrNull { function ->
            when (val identifier = function.identifier) {
                anchor -> true
                is io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression -> identifier.identifier === anchor
                else -> false
            }
        }
}

private fun findRootNode(node: BaseASTNode): BaseASTNode? {
    var current: BaseASTNode? = node
    var root: BaseASTNode? = node
    while (current != null) {
        root = current
        current = runCatching { current.parent }.getOrNull()
    }
    return root
}

private fun findFunctionByOwner(
    binder: BinderPassResult,
    declaration: BinderDeclaration
): FunctionDeclaration? {
    val owner = declaration.owner as? DeclarationOwner.Declaration ?: return null
    val ownerScopeNode = binder.scopeGraph.scopes
        .firstOrNull { it.ownerDeclarationId == owner.declarationId }
        ?.ownerNode
    return findFunctionOwnedByNode(ownerScopeNode) ?: findAncestorFunction(ownerScopeNode)
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
    return functionDeclarationsIn(container).firstOrNull { declaration ->
        when (val identifier = declaration.identifier) {
            anchorNode -> true
            is io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression -> identifier.identifier === anchorNode
            else -> false
        }
    }
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
        is ChunkNode -> buildList { collectFunctionDeclarations(node.body, this) }
        is BlockNode -> buildList { collectFunctionDeclarations(node, this) }
        else -> emptyList()
    }
}

private fun collectFunctionDeclarations(node: BlockNode, output: MutableList<FunctionDeclaration>) {
    node.statements.forEach { statement ->
        when (statement) {
            is FunctionDeclaration -> {
                output += statement
                statement.body?.let { collectFunctionDeclarations(it, output) }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.DoStatement -> collectFunctionDeclarations(statement.body, output)
            is io.github.dingyi222666.luaparser.parser.ast.node.IfStatement -> statement.causes.forEach { collectFunctionDeclarations(it.body, output) }
            is io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement -> collectFunctionDeclarations(statement.body, output)
            is io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement -> collectFunctionDeclarations(statement.body, output)
            is io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement -> collectFunctionDeclarations(statement.body, output)
            is io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement -> collectFunctionDeclarations(statement.body, output)
            is io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement -> statement.causes.forEach { cause ->
                when (cause) {
                    is io.github.dingyi222666.luaparser.parser.ast.node.CaseCause -> collectFunctionDeclarations(cause.body, output)
                    is io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause -> collectFunctionDeclarations(cause.body, output)
                }
            }
        }
    }
}
