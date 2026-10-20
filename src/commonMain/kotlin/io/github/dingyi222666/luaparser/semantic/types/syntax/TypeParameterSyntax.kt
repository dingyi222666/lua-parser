package io.github.dingyi222666.luaparser.semantic.types.syntax

data class TypeParameterSyntax(
    val name: String,
    val constraint: TypeSyntax? = null
)
