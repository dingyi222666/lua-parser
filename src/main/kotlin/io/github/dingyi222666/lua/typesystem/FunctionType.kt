package io.github.dingyi222666.lua.typesystem

/**
 * @author: dingyi
 * @date: 2023/2/6
 * @description:
 **/
class FunctionType(
    override val typeVariableName: String
) : Type {

    override val kind: TypeKind = TypeKind.Function

    val paramTypes = mutableListOf<Type>()

    val returnTypes = mutableListOf<Type>()

    override fun getSimpleTypeName(): String {
        return "function"
    }

    override fun getTypeName(): String {
        return "function(${paramTypes.joinToString(",") { it.getTypeName() }}):${returnTypes.joinToString(",") { it.getTypeName() }}"
    }

    fun addParamType(type: Type) {
        paramTypes.add(type)
    }

    fun addReturnType(type: Type) {
        returnTypes.add(type)
    }

    fun getParamType(index: Int): Type {
        return paramTypes[index]
    }

    fun getReturnType(index: Int): Type {
        return returnTypes[index]
    }

    override fun subTypeOf(type: Type): Boolean {
        return when (type) {
            is FunctionType -> {
                paramTypes.size == type.paramTypes.size && returnTypes.size == type.returnTypes.size &&
                        paramTypes.zip(type.paramTypes).all { (a, b) -> a.subTypeOf(b) } &&
                        returnTypes.zip(type.returnTypes).all { (a, b) -> a.subTypeOf(b) }
            }
            is UnionType -> type.types.any { it.subTypeOf(this) }
            else -> false
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other is FunctionType) {
            return paramTypes == other.paramTypes && returnTypes == other.returnTypes
        }
        return false
    }

    override fun hashCode(): Int {
        var result = paramTypes.hashCode()
        result = 31 * result + returnTypes.hashCode()
        return result
    }
}