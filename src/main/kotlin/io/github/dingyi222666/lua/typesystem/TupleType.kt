package io.github.dingyi222666.lua.typesystem

/**
 * @author: dingyi
 * @date: 2023/2/6
 * @description:
 **/
class TupleType(
    createTypes: List<Type>,
    override val kind: TypeKind = TypeKind.Tuple,
) : Type {

    constructor(vararg createTypes: Type, kind: TypeKind = TypeKind.Tuple) : this(createTypes.toList(), kind)

    private val types = createTypes.toMutableList()

    override val typeVariableName: String
        get() = getTypeName()

    override fun getTypeName(): String {
        return types.joinToString(", ", "(", ")") { it.getTypeName() }
    }

    override fun subTypeOf(type: Type): Boolean {
        return when (type) {
            is TupleType -> {
                if (types.size != type.types.size) {
                    return false
                }
                types.zip(type.types).all { (t1, t2) -> t1.subTypeOf(t2) }
            }

            is UnionType -> type.types.any { it.subTypeOf(this) }
            else -> false
        }
    }

    fun set(index: Int, type: Type) {
        types[index] = type
    }

    fun get(index: Int): Type {
        return types[index]
    }

    fun list(): List<Type> {
        return types
    }

    override fun toString(): String {
        return getTypeName()
    }
}