package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Range

data class CollectedCommentBlock(
    val comments: List<CommentStatement>,
    val range: Range,
    val startLine: Int,
    val endLine: Int,
    val visibleEndLine: Int,
    val docComment: DocCommentSyntax? = null,
    val inlineTypeText: String? = null
) {
    init {
        require(comments.isNotEmpty()) { "Comment blocks must contain at least one comment." }
    }
}
