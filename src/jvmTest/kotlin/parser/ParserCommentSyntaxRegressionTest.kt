package parser

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParserCommentSyntaxRegressionTest {

    @Test
    fun distinguishesPlainAndDocLineComments() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            -- note
            ---@type string
            local value = input
            """.trimIndent()
        )

        val comments = chunk.comments()
        val declaration = assertIs<LocalStatement>(chunk.body.statements[2])

        assertEquals(2, comments.size)
        assertFalse(comments[0].isDocComment)
        assertTrue(comments[1].isDocComment)
        assertEquals("-- note", comments[0].comment.trimEnd())
        assertEquals("---@type string", comments[1].comment.trimEnd())
        assertEquals(1, comments[0].range.start.line)
        assertEquals(2, comments[1].range.start.line)
        assertEquals(3, declaration.range.start.line)
    }

    @Test
    fun preservesMixedCommentBlocksAroundDeclarations() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            -- leading plain
            ---@param x integer
            ---@return integer
            local function compute(x)
                -- body plain
                ---body doc
                local inner = x
            end
            -- trailing plain
            local other = 1
            """.trimIndent()
        )

        assertEquals(5, chunk.body.statements.size)

        val leadingPlain = assertIs<CommentStatement>(chunk.body.statements[0])
        val leadingDoc = assertIs<CommentStatement>(chunk.body.statements[1])
        val function = assertIs<FunctionDeclaration>(chunk.body.statements[2])
        val trailingPlain = assertIs<CommentStatement>(chunk.body.statements[3])
        val other = assertIs<LocalStatement>(chunk.body.statements[4])

        assertFalse(leadingPlain.isDocComment)
        assertTrue(leadingDoc.isDocComment)
        assertFalse(trailingPlain.isDocComment)
        assertEquals("-- leading plain", leadingPlain.comment.trimEnd())
        assertEquals("---@param x integer\n---@return integer", leadingDoc.comment.trimEnd())
        assertEquals("-- trailing plain", trailingPlain.comment.trimEnd())
        assertEquals(1, leadingPlain.range.start.line)
        assertEquals(2, leadingDoc.range.start.line)
        assertEquals(3, leadingDoc.range.end.line)
        assertEquals(4, function.range.start.line)
        assertEquals(9, trailingPlain.range.start.line)
        assertEquals(10, other.range.start.line)

        val functionBody = function.body!!
        assertEquals(3, functionBody.statements.size)

        val bodyPlain = assertIs<CommentStatement>(functionBody.statements[0])
        val bodyDoc = assertIs<CommentStatement>(functionBody.statements[1])
        val inner = assertIs<LocalStatement>(functionBody.statements[2])

        assertFalse(bodyPlain.isDocComment)
        assertTrue(bodyDoc.isDocComment)
        assertEquals("-- body plain", bodyPlain.comment.trimEnd())
        assertEquals("---body doc", bodyDoc.comment.trimEnd())
        assertEquals(5, bodyPlain.range.start.line)
        assertEquals(6, bodyDoc.range.start.line)
        assertEquals(6, bodyDoc.range.end.line)
        assertEquals(7, inner.range.start.line)
    }

    @Test
    fun acceptsShebangOnlyAtFileStart() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            "#!/usr/bin/env lua\nprint(1)"
        )

        assertEquals(2, chunk.body.statements.size)

        val shebang = assertIs<CommentStatement>(chunk.body.statements[0])

        assertFalse(shebang.isDocComment)
        assertEquals("#!/usr/bin/env lua", shebang.comment.trimEnd())
        assertEquals(1, shebang.range.start.line)
        assertEquals(1, shebang.range.start.column)
        assertEquals(2, chunk.body.statements[1].range.start.line)
        assertParseFails(LuaVersion.LUA_5_3, "print(1)\n#!/usr/bin/env lua")
    }

    @Test
    fun preservesDocCommentAstOrderAndMultiLineRange() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            --- first line
            --- second line
            local value = 1
            """.trimIndent()
        )

        assertEquals(2, chunk.body.statements.size)

        val comment = assertIs<CommentStatement>(chunk.body.statements[0])
        val declaration = assertIs<LocalStatement>(chunk.body.statements[1])

        assertEquals(1, chunk.comments().size)
        assertTrue(comment.isDocComment)
        assertEquals("--- first line\n--- second line", comment.comment.trimEnd())
        assertEquals(1, comment.range.start.line)
        assertEquals(1, comment.range.start.column)
        assertEquals(2, comment.range.end.line)
        assertEquals(3, declaration.range.start.line)
    }
}
