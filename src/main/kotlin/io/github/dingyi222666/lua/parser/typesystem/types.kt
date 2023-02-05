package io.github.dingyi222666.lua.parser.typesystem

import io.github.dingyi222666.lua.parser.ast.node.ConstantNode
import io.github.dingyi222666.lua.parser.symbol.Symbol

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
    Primitive,
    Array,
    Parameter,
    Nil,
    Tuple,
    Unknown,
    String,
    Boolean,
    Table
}


/**
 * mark as a type
 */
interface Type {
    fun getTypeName(): String

    fun getParent(): Type {
        return empty
    }
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
        return UnionType(listOf(this, type))
    }

    companion object {
        val empty = object : Type {
            override fun getTypeName() = "empty"
        }
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





