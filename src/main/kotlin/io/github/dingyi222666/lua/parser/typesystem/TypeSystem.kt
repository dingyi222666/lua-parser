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

    fun removeType(typeName: String) {
        allType.remove(typeName)
    }

    fun unionType(type1: Type, type2: Type): Type {
        val unionTypeLists = mutableListOf<Type>()
        if (type1 is UnionType) {
            unionTypeLists.addAll(type1.types)
        } else {
            unionTypeLists.add(type1)
        }

        if (type2 is UnionType) {
            unionTypeLists.addAll(type2.types)
        } else {
            unionTypeLists.add(type2)
        }

        if (unionTypeLists.size > 4) {
            return BaseType.ANY
        }

        return UnionType(listOf(type1, type2))
    }
}