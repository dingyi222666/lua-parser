package io.github.dingyi222666.luaparser.semantic.comments

data class DocCommentSyntax(
    val description: String = "",
    val tags: List<DocTagSyntax> = emptyList()
)
