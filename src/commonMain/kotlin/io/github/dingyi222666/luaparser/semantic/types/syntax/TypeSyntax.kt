package io.github.dingyi222666.luaparser.semantic.types.syntax

sealed interface TypeSyntax

data class NamedTypeSyntax(
    val name: String
) : TypeSyntax

data class LiteralTypeSyntax(
    val value: String
) : TypeSyntax

data class UnionTypeSyntax(
    val options: List<TypeSyntax>
) : TypeSyntax

data class IntersectionTypeSyntax(
    val types: List<TypeSyntax>
) : TypeSyntax

data class ArrayTypeSyntax(
    val elementType: TypeSyntax
) : TypeSyntax

data class GenericTypeSyntax(
    val baseType: TypeSyntax,
    val arguments: List<TypeSyntax>
) : TypeSyntax

data class NullableTypeSyntax(
    val innerType: TypeSyntax
) : TypeSyntax

data class TupleTypeSyntax(
    val elements: List<TypeSyntax>
) : TypeSyntax

data class MultiReturnTypeSyntax(
    val types: List<TypeSyntax>
) : TypeSyntax

data class VarargTypeSyntax(
    val elementType: TypeSyntax
) : TypeSyntax

data class FunctionParameterSyntax(
    val name: String?,
    val type: TypeSyntax,
    val optional: Boolean = false,
    val vararg: Boolean = false
)

data class FunctionTypeSyntax(
    val parameters: List<FunctionParameterSyntax>,
    val returnType: TypeSyntax,
    val typeParameters: List<TypeParameterSyntax> = emptyList()
) : TypeSyntax

sealed interface ObjectFieldNameSyntax

data class IdentifierObjectFieldNameSyntax(
    val value: String
) : ObjectFieldNameSyntax

data class QuotedObjectFieldNameSyntax(
    val literal: String
) : ObjectFieldNameSyntax

data class ObjectFieldSyntax(
    val name: ObjectFieldNameSyntax,
    val type: TypeSyntax,
    val optional: Boolean = false
)

data class ObjectIndexerSyntax(
    val keyType: TypeSyntax,
    val valueType: TypeSyntax,
    val keyName: String? = null
)

data class ObjectTypeSyntax(
    val fields: List<ObjectFieldSyntax> = emptyList(),
    val indexers: List<ObjectIndexerSyntax> = emptyList()
) : TypeSyntax

data class IndexTableTypeSyntax(
    val keyType: TypeSyntax,
    val valueType: TypeSyntax
) : TypeSyntax
