package io.github.dingyi222666.luaparser.semantic.types.model

data class LiteralType(
    val value: Any?,
    val baseType: PrimitiveType,
    override val name: String = literalTypeName(value)
) : Type

private fun literalTypeName(value: Any?): String = when (value) {
    null -> "nil"
    is String -> '"' + value + '"'
    else -> value.toString()
}
