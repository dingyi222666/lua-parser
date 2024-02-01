package io.github.dingyi222666.luaparser.semantic.typesystem

/**
 * @author: dingyi
 * @date: 2023/2/6
 * @description:
 **/
class FunctionType(
    override var typeVariableName: String
) : Type {

    override val kind: TypeKind = TypeKind.Function

    val parameterTypes = mutableListOf<Type>()

    val returnTypes = mutableListOf<Type>()

    // x:xx xx(self,...)
    var isSelf: Boolean = false

    override fun getSimpleTypeName(): String {
        return "function"
    }

    private fun wrapReturnTypesToString(): String {
        return when (returnTypes.size) {
            0 -> {
                "void"
            }
            1 -> {
                returnTypes[0].getTypeName()
            }
            else -> {
                "(${returnTypes.joinToString(",") { it.getTypeName() }})"
            }
        }
    }

    override fun getTypeName(): String {
        return "fun(${parameterTypes.joinToString(",") { it.getSimpleTypeName() }}):${wrapReturnTypesToString()}"
    }

    fun addParamType(type: Type) {
        parameterTypes.add(type)
    }

    fun addReturnType(type: Type) {
        returnTypes.add(type)
    }

    fun getParamType(index: Int): Type {
        return parameterTypes[index]
    }

    fun getReturnType(index: Int): Type {
        return returnTypes[index]
    }

    override fun subTypeOf(type: Type): Boolean {
        return when (type) {
            is FunctionType -> {
                parameterTypes.size == type.parameterTypes.size && returnTypes.size == type.returnTypes.size &&
                        parameterTypes.zip(type.parameterTypes).all { (a, b) -> a.subTypeOf(b) } &&
                        returnTypes.zip(type.returnTypes).all { (a, b) -> a.subTypeOf(b) }
            }

            is UnionType -> type.types.any { it.subTypeOf(this) }
            else -> false
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other is FunctionType) {
            return parameterTypes == other.parameterTypes && returnTypes == other.returnTypes
        }
        return false
    }

    override fun hashCode(): Int {
        var result = parameterTypes.hashCode()
        result = 31 * result + returnTypes.hashCode()
        return result
    }

    override fun toString(): String {
        return getTypeName()
    }


}