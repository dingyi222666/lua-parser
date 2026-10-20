package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement

class CommentAttachmentIndex(
    val attachments: List<CommentAttachment>
) {
    private val attachmentsByTarget = attachments
        .filter { it.target != null }
        .associateBy { it.target!! }

    val orphanAttachments: List<CommentAttachment> = attachments.filter { it.target == null }
    val orphanDocComments: List<DocCommentSyntax> = orphanAttachments.mapNotNull(CommentAttachment::docComment)

    fun getAttachment(node: BaseASTNode): CommentAttachment? = attachmentsByTarget[node]

    fun getLeadingComments(node: BaseASTNode): List<CommentStatement> =
        attachmentsByTarget[node]?.comments ?: emptyList()

    fun getDocComment(node: BaseASTNode): DocCommentSyntax? = attachmentsByTarget[node]?.docComment

    fun getInlineTypeText(node: BaseASTNode): String? = attachmentsByTarget[node]?.inlineTypeText
}
