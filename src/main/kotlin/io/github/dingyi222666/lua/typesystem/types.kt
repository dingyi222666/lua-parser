package io.github.dingyi222666.lua.typesystem

import io.github.dingyi222666.lua.parser.ast.node.ConstantNode
import io.github.dingyi222666.lua.semantic.symbol.Symbol

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/


enum class TypeKind {
    Void,
    Function,
    Class,
    Union,
    Array,
    Parameter,
    Nil,
    Number,
    Tuple,
    Unknown,
    String,
    Boolean,
    Table,
    UnDefined,
}

fun TypeKind.isPrimitive(): Boolean {
    return when (this) {
        TypeKind.Nil, TypeKind.Boolean, TypeKind.Number,
        TypeKind.String, TypeKind.Table,
        TypeKind.Function -> true

        else -> false
    }
}

/**
 * mark as a type
 */
interface Type {

    val kind: TypeKind

    val typeVariableName: String

    fun getTypeName(): String

    fun getSimpleTypeName() = getTypeName()

    fun getParent(): Type? = null

    fun subTypeOf(type: Type): Boolean {
        return this == type
    }

    private fun simplifyType(t1: Type, t2: Type): Type? {
        if (t1 is UnDefinedType && t2 !is UnDefinedType) {
            return t2
        }

        if (t2 is UnDefinedType && t1 !is UnDefinedType) {
            return t1
        }

        if (t1 is UnionType || t2 is UnionType) {
            val set = mutableSetOf<Type>()
            if (t1 is UnionType)
                set.addAll(t1.types)
            else set.add(t1)

            if (t2 is UnionType)
                set.addAll(t2.types)
            else set.add(t2)

            if (set.size > 3) {
                return ANY
            }
            return UnionType(set)
        }
        return null
    }

    fun union(type: Type): Type {
        val simplifyType = simplifyType(this, type)

        if (simplifyType != null) {
            return simplifyType
        }

        return UnionType(setOf(this, type))
    }

    companion object {
        val NUMBER = PrimitiveType(
            TypeKind.Number
        )
        val BOOLEAN = PrimitiveType(
            TypeKind.Boolean
        )
        val Nil = PrimitiveType(
            TypeKind.Nil
        )

        // TODO: Use ClassType instead of PrimitiveType
        val STRING = PrimitiveType(
            TypeKind.String
        )

        val ANY = AnyType()

        val UnDefined = UnDefinedType()
    }

}

class AnyType : Type {
    override val kind: TypeKind
        get() = TypeKind.Unknown

    override val typeVariableName: String
        get() = getTypeName()

    override fun getTypeName(): String {
        return "any"
    }

    override fun getParent(): Type? {
        return null
    }

    override fun subTypeOf(type: Type): Boolean {
        return true
    }
}

class UnDefinedType : Type {
    override val kind: TypeKind
        get() = TypeKind.UnDefined

    override val typeVariableName: String
        get() = getTypeName()

    override fun getTypeName(): String {
        return "undefined"
    }

    override fun getParent(): Type? {
        return null
    }

    override fun subTypeOf(type: Type): Boolean {
        return false
    }
}

fun ConstantNode.asType(): Type {
    return when (this.constantType) {
        ConstantNode.TYPE.STRING -> Type.STRING
        ConstantNode.TYPE.FLOAT, ConstantNode.TYPE.INTERGER -> Type.NUMBER
        ConstantNode.TYPE.BOOLEAN -> Type.BOOLEAN
        ConstantNode.TYPE.NIL -> Type.Nil
        else -> Type.ANY
    }
}





