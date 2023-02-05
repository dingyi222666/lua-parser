package io.github.dingyi222666.lua.parser.typesystem

/**
 * @author: dingyi
 * @date: 2023/2/6
 * @description:
 **/
class UnionType(internal val types: List<Type>) : Type {
    override fun getTypeName(): String {
        return types.joinToString("|") { it.getTypeName() }
    }

    operator fun plus(type: Type): UnionType {
        return UnionType(types + type)
    }
}
