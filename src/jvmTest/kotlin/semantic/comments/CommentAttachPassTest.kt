package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.GenericTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommentAttachPassTest {

    private val luaParser = LuaParser()

    @Test
    fun bindsLeadingCommentsToNextLocalDeclaration() {
        val chunk = luaParser.parse(
            """
            -- local value docs
            local value = 1
            """.trimIndent()
        )

        val target = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = CommentAttachPass().attach(chunk)

        assertEquals(listOf("-- local value docs"), index.getLeadingComments(target).map { it.comment.trimEnd() })
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun bindsDocCommentsToLocalFunctionDeclarations() {
        val chunk = luaParser.parse(
            """
            --- outer docs
            ---@param value string
            local function outer(value)
                return value
            end
            """.trimIndent()
        )

        val target = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = CommentAttachPass().attach(chunk)
        val docComment = assertNotNull(index.getDocComment(target))

        assertEquals("outer docs", docComment.description)
        assertEquals("param", docComment.tags.single().tagName)
    }

    @Test
    fun bindsNestedCommentsInsideNestedBodies() {
        val chunk = luaParser.parse(
            """
            local function outer()
                do
                    --- inner docs
                    local inner = 1
                end
            end
            """.trimIndent()
        )

        val outer = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val doStatement = outer.body!!.statements.filterIsInstance<DoStatement>().single()
        val nestedLocal = doStatement.body.statements.filterIsInstance<LocalStatement>().single()
        val index = CommentAttachPass().attach(chunk)

        assertEquals(listOf("--- inner docs"), index.getLeadingComments(nestedLocal).map { it.comment.trimEnd() })
        assertNull(index.getAttachment(outer))
    }

    @Test
    fun leavesNonAdjacentCommentBlocksAsOrphans() {
        val chunk = luaParser.parse(
            """
            -- orphaned

            local value = 1
            """.trimIndent()
        )

        val target = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = CommentAttachPass().attach(chunk)

        assertNull(index.getAttachment(target))
        assertEquals(listOf("-- orphaned"), index.orphanAttachments.single().comments.map { it.comment.trimEnd() })
    }

    @Test
    fun keepsBlankLineSeparatedDocBlocksIndependent() {
        val chunk = luaParser.parse(
            """
            ---@alias T string

            ---@generic T
            local function build(value)
                return value
            end
            """.trimIndent()
        )

        val target = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = CommentAttachPass().attach(chunk)
        val attachment = assertNotNull(index.getAttachment(target))

        assertEquals(listOf("---@generic T"), attachment.comments.map { it.comment.trimEnd() })
        assertEquals(listOf("---@alias T string"), index.orphanAttachments.single().comments.map { it.comment.trimEnd() })
        assertEquals(
            listOf("T"),
            attachment.docComment?.tags.orEmpty().map { assertIs<GenericTagSyntax>(it).parameters.single().name }
        )
    }

    @Test
    fun exposesInlineTypeTextForBoundTargets() {
        val chunk = luaParser.parse(
            """
            --- docs
            ---@type table<string, number>
            local value = {}
            """.trimIndent()
        )

        val target = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = CommentAttachPass().attach(chunk)

        assertEquals("table<string, number>", index.getInlineTypeText(target))
    }

    @Test
    fun attachesMalformedDocBlocksWithoutOrphaningThem() {
        val chunk = luaParser.parse(
            """
            ---@param value
            ---@type table<string, number> trailing words
            local value = {}
            """.trimIndent()
        )

        val target = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = CommentAttachPass().attach(chunk)

        assertNotNull(index.getAttachment(target))
        assertEquals(0, index.orphanAttachments.size)
        assertEquals("table<string, number>", index.getInlineTypeText(target))
    }
}
