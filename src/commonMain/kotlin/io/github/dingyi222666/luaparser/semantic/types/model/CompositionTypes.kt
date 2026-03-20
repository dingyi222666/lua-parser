package io.github.dingyi222666.luaparser.semantic.types.model

data class UnionType(
    val types: Set<Type>,
    override val name: String = types.joinToString(" | ") { it.displayName }
) : Type

data class IntersectionType(
    val types: Set<Type>,
    override val name: String = types.joinToString(" & ") { it.displayName }
) : Type

data class TupleType(
    val elementTypes: List<Type>,
    override val name: String = "[${elementTypes.joinToString(", ") { it.displayName }}]"
) : Type

data class MultiReturnType(
    val types: List<Type>,
    override val name: String = types.joinToString(", ") { it.displayName }
) : Type

data class VarargType(
    val elementType: Type,
    override val name: String = "${elementType.displayName}..."
) : Type

data class ArrayType(
    val elementType: Type,
    override val name: String = "${elementType.displayName}[]"
) : Type
