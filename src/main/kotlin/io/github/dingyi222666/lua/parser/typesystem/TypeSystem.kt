package io.github.dingyi222666.lua.parser.typesystem

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/
class TypeSystem {
    private val allType = mutableMapOf<String, Type>()

    fun addType(type: Type) {
        allType[type.getTypeName()] = type
    }

    fun getType(typeName: String): Type? {
        return allType[typeName]
    }



}