package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Range

data class Scope(
    val id: ScopeId,
    val kind: ScopeKind,
    val range: Range,
    val ownerNode: BaseASTNode?,
    val ownerDeclarationId: DeclarationId? = null,
    val parentId: ScopeId? = null,
    val childIds: List<ScopeId> = emptyList(),
    val declarationIds: List<DeclarationId> = emptyList()
)

enum class ScopeKind {
    CHUNK,
    BLOCK,
    FUNCTION,
    MODULE,
    LOOP,
    CONDITIONAL
}
