package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.model.unwrapAliases

data class ValueSequence(
    val fixed: List<Type> = emptyList(),
    val variadicTail: Type? = null
) {
    val isOpenEnded: Boolean
        get() = variadicTail != null

    /**
     * True when this sequence is exactly one closed [UnknownType] slot
     * (`ValueSequence.of(UnknownType)`). Freeform / unannotated function returns
     * use this shape and must not be treated as a hard single-value contract for
     * `extraValues` diagnostics.
     *
     * Synthetic multi-unknown packs such as `MultiReturnType(listOf(Unknown, Unknown))`
     * are not bare — they keep fixed slot cardinality.
     */
    val isBareUnknownReturn: Boolean
        get() = !isOpenEnded && fixed.size == 1 && fixed[0].unwrapAliases() == UnknownType

    fun hasValueAt(index: Int): Boolean {
        return index in fixed.indices || variadicTail != null
    }

    fun typeAt(index: Int): Type {
        return fixed.getOrNull(index) ?: variadicTail ?: PrimitiveType.NIL
    }

    fun collapseToSingle(): ValueSequence {
        return ValueSequence(fixed = listOf(typeAt(0)))
    }

    fun appendToCallArguments(output: MutableList<Type>) {
        output += fixed
        variadicTail?.let(output::add)
    }

    companion object {
        fun of(type: Type): ValueSequence {
            return when (val normalized = type.unwrapAliases()) {
                is MultiReturnType -> fromTypes(normalized.types)
                is VarargType -> ValueSequence(variadicTail = normalized.elementType)
                else -> ValueSequence(fixed = listOf(type))
            }
        }

        fun fromTypes(types: List<Type>): ValueSequence {
            if (types.isEmpty()) {
                return ValueSequence(fixed = listOf(PrimitiveType.NIL))
            }

            val fixed = mutableListOf<Type>()
            var variadicTail: Type? = null
            types.forEachIndexed { index, type ->
                val normalized = type.unwrapAliases()
                if (index == types.lastIndex && normalized is VarargType) {
                    variadicTail = normalized.elementType
                } else {
                    fixed += type
                }
            }
            return ValueSequence(fixed = fixed, variadicTail = variadicTail)
        }

        fun fromExpressionResults(results: List<Type>): ValueSequence {
            if (results.isEmpty()) {
                return ValueSequence(fixed = listOf(PrimitiveType.NIL))
            }

            val fixed = mutableListOf<Type>()
            var variadicTail: Type? = null
            results.forEachIndexed { index, type ->
                val sequence = of(type)
                if (index == results.lastIndex) {
                    fixed += sequence.fixed
                    variadicTail = sequence.variadicTail
                } else {
                    fixed += sequence.collapseToSingle().fixed
                }
            }
            return ValueSequence(fixed = fixed, variadicTail = variadicTail)
        }
    }
}
