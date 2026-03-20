package semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.ASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachment
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachmentIndex
import io.github.dingyi222666.luaparser.semantic.comments.DocCommentSyntax
import io.github.dingyi222666.luaparser.semantic.comments.FieldTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommentSyntaxModelTest {

    @Test
    fun syntaxModelsAndAttachmentIndexAreUsable() {
        val target = TestNode()
        val comment = CommentStatement().apply {
            this.comment = "--- docs"
            isDocComment = true
            range = Range(Position(1, 1), Position(1, 8))
        }
        val docComment = DocCommentSyntax(
            description = "Example class",
            tags = listOf(
                ClassTagSyntax(name = "Example", parentName = "Base"),
                ParamTagSyntax(name = "value", typeText = "string", description = "input value"),
                FieldTagSyntax(name = "id", typeText = "integer")
            )
        )
        val attached = CommentAttachment(
            comments = listOf(comment),
            target = target,
            docComment = docComment,
            inlineTypeText = "Example"
        )
        val orphan = CommentAttachment(
            comments = listOf(comment.copyForTest("--- orphan")),
            docComment = DocCommentSyntax(description = "Orphan")
        )

        val index = CommentAttachmentIndex(listOf(attached, orphan))

        assertEquals(docComment, index.getDocComment(target))
        assertEquals("Example", index.getInlineTypeText(target))
        assertEquals(listOf(comment), index.getLeadingComments(target))
        assertEquals(1, index.orphanAttachments.size)
        assertEquals("Orphan", index.orphanDocComments.single().description)
        assertNull(index.getAttachment(TestNode()))
    }

    private class TestNode : ASTNode() {
        override fun <T> accept(visitor: io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor<T>, value: T) {
        }

        override fun clone(): BaseASTNode = TestNode().also { node ->
            node.range = range
            node.bad = bad
        }
    }

    private fun CommentStatement.copyForTest(text: String): CommentStatement = CommentStatement().apply {
        comment = text
        isDocComment = this@copyForTest.isDocComment
        range = this@copyForTest.range
    }
}
