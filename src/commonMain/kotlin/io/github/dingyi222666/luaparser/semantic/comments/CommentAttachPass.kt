package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode

class CommentAttachPass(
    private val commentCollector: CommentCollector = CommentCollector()
) {

    fun attach(chunk: ChunkNode): CommentAttachmentIndex {
        return CommentAttachmentIndex(commentCollector.scan(chunk).attachments)
    }
}
