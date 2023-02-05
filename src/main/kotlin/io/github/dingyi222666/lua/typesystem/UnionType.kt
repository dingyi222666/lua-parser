package io.github.dingyi222666.lua.typesystem

/**
 * @author: dingyi
 * @date: 2023/2/6
 * @description:
 **/
class UnionType(internal val types: Set<Type>) : Type {

    override val kind: TypeKind
        get() = TypeKind.Union

    override fun getTypeName(): String {
        return types.joinToString("|") { it.getTypeName() }
    }

    operator fun plus(type: Type): UnionType {
        return UnionType(types + type)
    }
}
