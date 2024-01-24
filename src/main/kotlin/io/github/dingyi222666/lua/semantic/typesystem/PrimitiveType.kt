package io.github.dingyi222666.lua.semantic.typesystem

import java.util.*

/**
 * @author: dingyi
 * @date: 2023/2/6
 * @description:
 **/
class PrimitiveType(override val kind: TypeKind) : Type {

    override val typeVariableName: String
        get() = getTypeName()

    override fun getTypeName(): String {
        return kind.name.lowercase()
    }

    override fun getParent(): Type? {
        return null
    }

    override fun subTypeOf(type: Type): Boolean {
        return when (type) {
            is PrimitiveType -> type.kind == kind
            is UnionType -> type.subTypeOf(this)
            else -> false
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other is PrimitiveType) {
            return kind == other.kind
        }
        return false
    }

    override fun hashCode(): Int {
        return kind.hashCode()
    }

    override fun toString(): String {
        return getTypeName()
    }
}