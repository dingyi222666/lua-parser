package io.github.dingyi222666.luaparser.semantic.types

sealed interface TypeSyntax {
    val text: String
}

data class NamedTypeSyntax(
    val identifier: String,
    override val text: String = identifier
) : TypeSyntax

data class LiteralTypeSyntax(
    val value: String,
    override val text: String = value
) : TypeSyntax

data class UnionTypeSyntax(
    val types: List<TypeSyntax>,
    override val text: String = types.joinToString(" | ") { it.text }
) : TypeSyntax

data class IntersectionTypeSyntax(
    val types: List<TypeSyntax>,
    override val text: String = types.joinToString(" & ") { it.text }
) : TypeSyntax

data class ArrayTypeSyntax(
    val elementType: TypeSyntax,
    override val text: String = "${elementType.text}[]"
) : TypeSyntax

data class GenericTypeSyntax(
    val baseName: String,
    val arguments: List<TypeSyntax>,
    override val text: String = "$baseName<${arguments.joinToString(", ") { it.text }}>"
) : TypeSyntax

data class NullableTypeSyntax(
    val innerType: TypeSyntax,
    override val text: String = "${innerType.text}?"
) : TypeSyntax

data class TupleTypeSyntax(
    val elementTypes: List<TypeSyntax>,
    override val text: String = "[${elementTypes.joinToString(", ") { it.text }}]"
) : TypeSyntax

data class MultiReturnTypeSyntax(
    val types: List<TypeSyntax>,
    override val text: String = types.joinToString(", ") { it.text }
) : TypeSyntax

data class VarargTypeSyntax(
    val elementType: TypeSyntax,
    override val text: String = "${elementType.text}..."
) : TypeSyntax

data class FunctionParameterSyntax(
    val name: String?,
    val type: TypeSyntax,
    val optional: Boolean = false,
    val vararg: Boolean = false
)

data class TypeParameterDeclarationSyntax(
    val name: String,
    val constraint: TypeSyntax? = null
)

data class FunctionTypeSyntax(
    val parameters: List<FunctionParameterSyntax>,
    val returnTypes: List<TypeSyntax>,
    val typeParameters: List<TypeParameterDeclarationSyntax> = emptyList(),
    override val text: String = buildFunctionTypeText(parameters, returnTypes, typeParameters)
) : TypeSyntax

data class ObjectFieldSyntax(
    val name: String,
    val type: TypeSyntax,
    val optional: Boolean = false
)

data class ObjectTypeSyntax(
    val fields: List<ObjectFieldSyntax>,
    override val text: String = buildObjectTypeText(fields)
) : TypeSyntax

data class IndexTypeSyntax(
    val keyType: TypeSyntax,
    val valueType: TypeSyntax,
    override val text: String = "table<${keyType.text}, ${valueType.text}>"
) : TypeSyntax

private fun buildFunctionTypeText(
    parameters: List<FunctionParameterSyntax>,
    returnTypes: List<TypeSyntax>,
    typeParameters: List<TypeParameterDeclarationSyntax>
): String {
    val genericText = if (typeParameters.isEmpty()) {
        ""
    } else {
        "<${typeParameters.joinToString(", ") { parameter ->
            parameter.constraint?.let { "${parameter.name}: ${it.text}" } ?: parameter.name
        }}>"
    }

    val parameterText = parameters.joinToString(", ") { parameter ->
        buildString {
            parameter.name?.let {
                append(it)
                if (parameter.optional) {
                    append('?')
                }
                append(": ")
            }
            append(parameter.type.text)
            if (parameter.vararg) {
                append("...")
            }
        }
    }

    val returnText = if (returnTypes.isEmpty()) {
        "nil"
    } else {
        returnTypes.joinToString(", ") { it.text }
    }

    return "fun$genericText($parameterText): $returnText"
}

private fun buildObjectTypeText(fields: List<ObjectFieldSyntax>): String {
    return "{ ${fields.joinToString(", ") { field ->
        val suffix = if (field.optional) "?" else ""
        "${field.name}$suffix: ${field.type.text}"
    }} }"
}
