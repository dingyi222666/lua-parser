package io.github.dingyi222666.lua.typesystem

/**
 * @author: dingyi
 * @date: 2023/2/6
 * @description:
 **/
class UnionType(
    types: Iterable<Type>,
    private val simplifyType: Boolean = false
) : Type {

    internal val types: Set<Type> = types.filter {
        if (!simplifyType)
            true
        else {
            when (it) {
                is UnDefinedType, Type.ANY -> false
                else -> true
            }
        }
    }.toSet()

    constructor(vararg types: Type) : this(types.toSet())

    override val kind: TypeKind
        get() = TypeKind.Union

    override val typeVariableName: String
        get() = getTypeName()

    override fun getTypeName(): String {
        return types.joinToString("|") { it.getSimpleTypeName() }
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
