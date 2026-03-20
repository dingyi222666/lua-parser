package io.github.dingyi222666.luaparser.semantic.types.model

sealed interface CallableType : Type {
    val callSignatures: List<FunctionType>
}

data class FunctionParameter(
    val name: String,
    val type: Type,
    val optional: Boolean = false,
    val vararg: Boolean = false
)

data class FunctionType(
    val parameters: List<FunctionParameter> = emptyList(),
    val returnType: Type = UnknownType,
    val typeParameters: List<TypeParameterType> = emptyList(),
    override val name: String = buildFunctionTypeName(parameters, returnType, typeParameters)
) : CallableType {
    override val callSignatures: List<FunctionType>
        get() = listOf(this)
}

data class OverloadedFunctionType(
    override val callSignatures: List<FunctionType>,
    override val name: String = callSignatures.joinToString(" & ") { it.displayName }
) : CallableType

private fun buildFunctionTypeName(
    parameters: List<FunctionParameter>,
    returnType: Type,
    typeParameters: List<TypeParameterType>
): String {
    val typeParameterText = typeParameters
        .takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "<", postfix = ">") { parameter ->
            parameter.constraint?.let { "${parameter.name}: ${it.displayName}" } ?: parameter.name
        }
        .orEmpty()

    val parameterText = parameters.joinToString(", ") { parameter ->
        buildString {
            if (parameter.name != "...") {
                append(parameter.name)
                if (parameter.optional) append('?')
                append(": ")
            }
            append(parameter.type.displayName)
            if (parameter.vararg && parameter.type !is VarargType) append("...")
        }
    }

    return "fun$typeParameterText($parameterText): ${returnType.displayName}"
}
