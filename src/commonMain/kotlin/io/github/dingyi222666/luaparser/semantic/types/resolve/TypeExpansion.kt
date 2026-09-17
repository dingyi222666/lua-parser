package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.Type

internal object TypeExpansion {
    /**
     * Expand alias/applied types down to their member/callable surface for resolution.
     *
     * The two entry points were byte-identical twins (both delegated to the same
     * [expandSurface] with a fresh stack); kept as one function with a name that covers
     * both call shapes. Callers: member surfaces (MemberResolver, ReferenceQueries,
     * SignatureHelpProvider, ExpressionUsageChecker) and callable surfaces (CallChecker).
     */
    fun expandForSurface(type: Type, lexicalScopeId: ScopeId, binder: BinderPassResult): Type {
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
