package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement

data class CommentAttachment(
    val comments: List<CommentStatement>,
    val target: BaseASTNode? = null,
    val docComment: DocCommentSyntax? = null,
    val inlineTypeText: String? = null
)
