package io.github.dingyi222666.lua.parser.typesystem

import io.github.dingyi222666.lua.parser.ast.node.ConstantNode
import io.github.dingyi222666.lua.parser.symbol.Symbol

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/

/**
 * mark as a type
 */
interface Type {
    fun getTypeName(): String
}

enum class BaseType(private val typeName: String) : Type {
    NUMBER("number"),
    STRING("string"),
    TABLE("table"),
    FUNCTION("function"),
    THREAD("thread"),
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

class UnionType(internal val types: List<Type>) : Type {
    override fun getTypeName(): String {
        return types.joinToString("|") { it.getTypeName() }
    }
}

class SymbolType(
    private val typeName: String,
    val symbol: Symbol
) : Type {
    override fun getTypeName(): String {
        return typeName
    }
}


