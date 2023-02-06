package io.github.dingyi222666.lua.typesystem

/**
 * @author: dingyi
 * @date: 2023/2/6
 * @description:
 **/
class ParameterType(
    override val typeVariableName: String
) : Type {

    var realType: Type = Type.ANY

    override val kind: TypeKind
        get() = TypeKind.Parameter

    override fun getTypeName(): String {
        return "(parameter) ${realType.getTypeName()}"
    }

    override fun getSimpleTypeName(): String {
        return realType.getTypeName()
    }

    override fun subTypeOf(type: Type): Boolean {
        return realType.subTypeOf(type)
    }

    override fun toString(): String {
        return getTypeName()
    }


}