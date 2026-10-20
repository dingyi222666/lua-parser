package io.github.dingyi222666.luaparser.semantic.api

data class SignatureHelp(
    val signatures: List<SignatureInformation>,
    val activeSignature: Int = 0,
    val activeParameter: Int = 0
)

data class SignatureInformation(
    val label: String,
    val parameters: List<ParameterInformation> = emptyList(),
    val documentation: String? = null
)

data class ParameterInformation(
    val label: String,
    val documentation: String? = null
)
