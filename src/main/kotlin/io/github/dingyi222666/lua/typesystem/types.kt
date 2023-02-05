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


}

enum class BaseType(private val typeName: String) : Type {
    NUMBER("number"),
    STRING("string"),
    TABLE("table"),
    FUNCTION("function"),
    ANY("any"),
    BOOLEAN("boolean");

    override fun getTypeName(): String = typeName
}

fun ConstantNode.asType(): Type {
    return when (this.constantType) {
        ConstantNode.TYPE.STRING -> BaseType.STRING
        ConstantNode.TYPE.FLOAT, ConstantNode.TYPE.INTERGER -> BaseType.NUMBER
        ConstantNode.TYPE.BOOLEAN -> BaseType.BOOLEAN
        else -> BaseType.ANY
    }
}





