package io.github.dingyi222666.luaparser.semantic.typesystem

/**
 * @author: dingyi
 * @date: 2023/2/6
 * @description:
 **/
class UnionType(
    internal val types: Set<Type>
) : Type {

    constructor(vararg types: Type) : this(types.toSet())

    override val kind: TypeKind
        get() = TypeKind.Union

    override val typeVariableName: String
        get() = getTypeName()

    override fun getTypeName(): String {
        return types.joinToString("|") { it.getTypeName() }
    }

    override fun subTypeOf(type: Type): Boolean {
        return types.any { it.subTypeOf(type) }
    }

    operator fun plus(type: Type): UnionType {
        return UnionType(types + type)
    }

    override fun toString(): String {
        return getTypeName()
    }
}
