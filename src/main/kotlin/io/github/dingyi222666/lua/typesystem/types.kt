package io.github.dingyi222666.lua.typesystem

import io.github.dingyi222666.lua.parser.ast.node.ConstantNode
import io.github.dingyi222666.lua.symbol.Symbol

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
    Table
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

    val typeVariableName:String

    fun getTypeName(): String

    fun getSimpleTypeName() = getTypeName()

    fun getParent(): Type? = null

    fun subTypeOf(type: Type): Boolean {
        return this == type
    }

    fun union(type: Type): Type {
        if (this is UnionType && type !is UnionType) {
            return this + type
        }
        if (this !is UnionType && type is UnionType) {
            return type + this
        }
        if (this is UnionType && type is UnionType) {
            return UnionType(this.types + type.types)
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
        val ANY = AnyType()

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

fun ConstantNode.asType(): Type {
    return when (this.constantType) {
        // ConstantNode.TYPE.STRING -> BaseType.STRING
        ConstantNode.TYPE.FLOAT, ConstantNode.TYPE.INTERGER -> Type.NUMBER
        ConstantNode.TYPE.BOOLEAN -> Type.BOOLEAN
        ConstantNode.TYPE.NIL -> Type.Nil
        else -> Type.ANY
    }
}





