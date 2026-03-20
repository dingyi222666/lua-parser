package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.Type

internal object TypeExpansion {
    fun expandForCallableSurface(type: Type, lexicalScopeId: ScopeId, binder: BinderPassResult): Type {
        return expandSurface(type, lexicalScopeId, binder, mutableListOf())
    }

    fun expandForMemberSurface(type: Type, lexicalScopeId: ScopeId, binder: BinderPassResult): Type {
        return expandSurface(type, lexicalScopeId, binder, mutableListOf())
    }

    fun normalizeStructurally(type: Type): Type = TypeNormalizer.normalize(type)

    private fun expandSurface(
        type: Type,
        lexicalScopeId: ScopeId,
        binder: BinderPassResult,
        aliasStack: MutableList<AliasType>
    ): Type {
        return when (type) {
            is AliasType -> {
                if (aliasStack.any { it === type }) {
                    type
                } else {
                    aliasStack += type
                    val expanded = expandSurface(type.target, lexicalScopeId, binder, aliasStack)
                    aliasStack.removeAt(aliasStack.lastIndex)
                    expanded
                }
            }

            is AppliedType -> TypeSubstitutor().substituteApplied(type, lexicalScopeId, binder)
                ?.let { expandSurface(it, lexicalScopeId, binder, aliasStack) }
                ?: type

            else -> type
        }
    }
}
