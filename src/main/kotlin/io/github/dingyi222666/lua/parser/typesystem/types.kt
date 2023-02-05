package io.github.dingyi222666.lua.parser.typesystem

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

class UnionType(private val types: List<Type>) : Type {
    override fun getTypeName(): String {
        return types.joinToString("|") { it.getTypeName() }
    }
}


