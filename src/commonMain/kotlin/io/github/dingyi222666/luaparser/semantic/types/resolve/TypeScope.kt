package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import kotlin.jvm.JvmInline

@JvmInline
value class TypeScopeId(val value: Int)

enum class TypeScopeKind {
    LEXICAL,
    DECLARATION
}

data class TypeScope(
    val id: TypeScopeId,
    val kind: TypeScopeKind,
    val parentId: TypeScopeId?,
    val lexicalScopeId: ScopeId? = null,
    val ownerDeclarationId: DeclarationId? = null,
    val declarationIds: List<DeclarationId> = emptyList()
)
