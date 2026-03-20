package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.Range

sealed interface DocTagSyntax {
    val tagName: String
    val description: String
    val range: Range?
}

data class ParamTagSyntax(
    val name: String,
    val typeText: String? = null,
    val optional: Boolean = false,
    val vararg: Boolean = false,
    override val description: String = "",
    override val range: Range? = null
) : DocTagSyntax {
    override val tagName: String = "param"
}

data class ReturnTagSyntax(
    val typeTexts: List<String> = emptyList(),
    override val description: String = "",
    override val range: Range? = null
) : DocTagSyntax {
    override val tagName: String = "return"
}

data class TypeTagSyntax(
    val typeText: String,
    override val description: String = "",
    override val range: Range? = null
) : DocTagSyntax {
    override val tagName: String = "type"
}

data class ClassTagSyntax(
    val name: String,
    val parentName: String? = null,
    val declaredTypeParameters: List<String> = emptyList(),
    override val description: String = "",
    override val range: Range? = null
) : DocTagSyntax {
    override val tagName: String = "class"
}

data class FieldTagSyntax(
    val name: String,
    val typeText: String? = null,
    val optional: Boolean = false,
    override val description: String = "",
    override val range: Range? = null
) : DocTagSyntax {
    override val tagName: String = "field"
}

data class GenericParameterSyntax(
    val name: String,
    val constraintText: String? = null,
    val range: Range? = null
)

data class GenericTagSyntax(
    val parameters: List<GenericParameterSyntax> = emptyList(),
    override val description: String = "",
    override val range: Range? = null
) : DocTagSyntax {
    override val tagName: String = "generic"
}

data class AliasTagSyntax(
    val name: String,
    val declaredTypeParameters: List<String> = emptyList(),
    val targetTypeText: String? = null,
    override val description: String = "",
    override val range: Range? = null
) : DocTagSyntax {
    override val tagName: String = "alias"
}

data class OverloadTagSyntax(
    val signatureText: String,
    override val description: String = "",
    override val range: Range? = null
) : DocTagSyntax {
    override val tagName: String = "overload"
}

data class MethodTagSyntax(
    val name: String,
    val className: String? = null,
    val signatureText: String? = null,
    override val description: String = "",
    override val range: Range? = null
) : DocTagSyntax {
    override val tagName: String = "method"
}

data class UnknownTagSyntax(
    override val tagName: String,
    val content: String,
    override val description: String = "",
    override val range: Range? = null
) : DocTagSyntax
