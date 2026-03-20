package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode

class CommentAttachPass(
    private val commentCollector: CommentCollector = CommentCollector(),
    private val commentBinder: CommentBinder = CommentBinder()
) {

    fun attach(chunk: ChunkNode): CommentAttachmentIndex {
        val collectedBlocks = commentCollector.collect(chunk)
        return commentBinder.bind(chunk, collectedBlocks)
    }
}
