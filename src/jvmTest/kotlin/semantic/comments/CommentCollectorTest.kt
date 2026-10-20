package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.comments.AliasTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.CommentCollector
import io.github.dingyi222666.luaparser.semantic.comments.DocCommentSyntaxParser
import io.github.dingyi222666.luaparser.semantic.comments.FieldTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.GenericTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.MethodTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.OverloadTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ReturnTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.TypeTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.UnknownTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CommentCollectorTest {

    private val luaParser = LuaParser()

    @Test
    fun parsesSupportedDocTagsIntoSyntaxModels() {
        val chunk = luaParser.parse(
            """
            --- Widget docs
            --- More details
            ---@class Widget<T>: BaseWidget
            ---@field public id integer stable identifier
            ---@generic T: BaseValue, U
            ---@alias Identifier string | number identifier alias
            ---@overload fun(value: string): number
            ---@method Widget.render fun(self: Widget, value: string): boolean
            ---@param value string input value
            ---@return number result value
            ---@type Widget<T>
            local widget = {}
            """.trimIndent()
        )

        val block = CommentCollector().collect(chunk).single()
        val docComment = block.docComment ?: error("Expected parsed doc comment")

        assertEquals("Widget docs\nMore details", docComment.description)
        assertEquals("Widget<T>", block.inlineTypeText)

        val classTag = assertIs<ClassTagSyntax>(docComment.tags[0])
        assertEquals("Widget", classTag.name)
        assertEquals("BaseWidget", classTag.parentName)
        assertEquals(listOf("T"), classTag.declaredTypeParameters)

        val fieldTag = assertIs<FieldTagSyntax>(docComment.tags[1])
        assertEquals("id", fieldTag.name)
        assertEquals("integer", fieldTag.typeText)
        assertEquals("stable identifier", fieldTag.description)

        val genericTag = assertIs<GenericTagSyntax>(docComment.tags[2])
        assertEquals("T", genericTag.parameters[0].name)
        assertEquals("BaseValue", genericTag.parameters[0].constraintText)
        assertEquals("U", genericTag.parameters[1].name)
        assertNull(genericTag.parameters[1].constraintText)

        val aliasTag = assertIs<AliasTagSyntax>(docComment.tags[3])
        assertEquals("Identifier", aliasTag.name)
        assertEquals("string | number", aliasTag.targetTypeText)
        assertEquals("identifier alias", aliasTag.description)

        val overloadTag = assertIs<OverloadTagSyntax>(docComment.tags[4])
        assertEquals("fun(value: string): number", overloadTag.signatureText)

        val methodTag = assertIs<MethodTagSyntax>(docComment.tags[5])
        assertEquals("Widget", methodTag.className)
        assertEquals("render", methodTag.name)
        assertEquals("fun(self: Widget, value: string): boolean", methodTag.signatureText)

        val paramTag = assertIs<ParamTagSyntax>(docComment.tags[6])
        assertEquals("value", paramTag.name)
        assertEquals("string", paramTag.typeText)
        assertEquals("input value", paramTag.description)

        val returnTag = assertIs<ReturnTagSyntax>(docComment.tags[7])
        assertEquals(listOf("number"), returnTag.typeTexts)
        assertEquals("result value", returnTag.description)

        val typeTag = assertIs<TypeTagSyntax>(docComment.tags[8])
        assertEquals("Widget<T>", typeTag.typeText)
    }

    @Test
    fun preservesUnknownTags() {
        val chunk = luaParser.parse(
            """
            ---@mystery value payload
            --- extra details
            local value = 1
            """.trimIndent()
        )

        val block = CommentCollector().collect(chunk).single()
        val unknownTag = assertIs<UnknownTagSyntax>(block.docComment?.tags?.single())

        assertEquals("mystery", unknownTag.tagName)
        assertEquals("value payload", unknownTag.content)
        assertEquals("extra details", unknownTag.description)
    }

    @Test
    fun groupsAdjacentCommentsIntoBlocks() {
        val chunk = luaParser.parse(
            """
            -- first
            -- second
            local a = 1
            -- third
            local b = 2
            """.trimIndent()
        )

        val blocks = CommentCollector().collect(chunk)
        val firstBlockComments = blocks[0].comments
        val secondBlockComment = blocks[1].comments.single()

        assertEquals(2, blocks.size)
        assertEquals(listOf("-- first", "-- second"), firstBlockComments.map { it.comment.trimEnd() })
        assertEquals(firstBlockComments.first().range.start.line, blocks[0].startLine)
        assertEquals(firstBlockComments.last().range.end.line, blocks[0].endLine)
        assertEquals("-- third", secondBlockComment.comment.trimEnd())
        assertEquals(secondBlockComment.range.start.line, blocks[1].startLine)
        assertEquals(secondBlockComment.range.end.line, blocks[1].endLine)
    }

    @Test
    fun collectsNestedBlocksFromNestedFunctions() {
        val chunk = luaParser.parse(
            """
            -- outer docs
            local function outer()
                -- inner block
                -- still inner
                local nested = function()
                    ---@param value string nested input
                    return value
                end
            end
            """.trimIndent()
        )

        val blocks = CommentCollector().collect(chunk)

        assertEquals(3, blocks.size)
        assertEquals(listOf("-- outer docs"), blocks[0].comments.map { it.comment.trimEnd() })
        assertEquals(listOf("-- inner block", "-- still inner"), blocks[1].comments.map { it.comment.trimEnd() })
        assertEquals(listOf("---@param value string nested input"), blocks[2].comments.map { it.comment.trimEnd() })
        assertEquals("value", assertIs<ParamTagSyntax>(blocks[2].docComment?.tags?.single()).name)
    }

    @Test
    fun parsesDocCommentsWithoutOldCommentProcessor() {
        val chunk = luaParser.parse(
            """
            --- Example docs
            ---@type table<string, number>
            local value = {}
            """.trimIndent()
        )

        val comment = chunk.body.statements
            .filterIsInstance<io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement>()
            .single()
        val syntax = DocCommentSyntaxParser().parse(listOf(comment)) ?: error("Expected syntax")

        assertEquals("Example docs", syntax.description)
        assertEquals("table<string, number>", assertIs<TypeTagSyntax>(syntax.tags.single()).typeText)
    }

    @Test
    fun collectsMalformedDocBlocksAndKeepsInlineTypePrefix() {
        val chunk = luaParser.parse(
            """
            --- broken docs
            ---@param value
            ---@type table<string, number> trailing words here
            local value = {}
            """.trimIndent()
        )

        val block = CommentCollector().collect(chunk).single()

        assertEquals("broken docs", block.docComment?.description)
        assertEquals("table<string, number>", block.inlineTypeText)
        assertEquals("value", assertIs<ParamTagSyntax>(block.docComment?.tags?.first()).name)
    }
}
