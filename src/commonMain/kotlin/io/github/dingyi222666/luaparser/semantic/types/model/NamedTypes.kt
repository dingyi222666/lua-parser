package io.github.dingyi222666.luaparser.semantic.types.model

data class AliasType(
    override val name: String,
    val target: Type
) : Type

data class TypeParameterType(
    override val name: String,
    val constraint: Type? = null,
    val defaultType: Type? = null
) : Type

data class CustomType(
    override val name: String
) : Type

data class AppliedType(
    val baseName: String,
    val typeArguments: List<Type>,
    override val name: String = "$baseName<${typeArguments.joinToString(", ") { it.displayName }}>"
) : Type
