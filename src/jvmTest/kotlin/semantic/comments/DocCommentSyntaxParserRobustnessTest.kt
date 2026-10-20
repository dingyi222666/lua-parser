package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.semantic.comments.AliasTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.DocCommentSyntaxParser
import io.github.dingyi222666.luaparser.semantic.comments.MethodTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.TypeTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.UnknownTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocCommentSyntaxParserRobustnessTest {

    private val luaParser = LuaParser()
    private val parser = DocCommentSyntaxParser()

    @Test
    fun malformedAndIncompleteTagsDoNotThrow() {
        val syntax = parseDoc(
            """
            ---@param
            ---@field private payload?
            ---@alias Result<T
            ---@generic T: Foo<, U, V: { nested: string | }
            ---@method Service.run(value: table<string, { ok: boolean }>, extra
            ---@
            ---@mystery still here
            local value = 1
            """.trimIndent()
        )

        assertTrue(syntax.tags.isNotEmpty())
        assertEquals("", assertIs<ParamTagSyntax>(syntax.tags[0]).name)
        assertEquals("payload", assertIs<io.github.dingyi222666.luaparser.semantic.comments.FieldTagSyntax>(syntax.tags[1]).name)
        assertEquals("Result<T", assertIs<AliasTagSyntax>(syntax.tags[2]).name)
        assertEquals(3, assertIs<io.github.dingyi222666.luaparser.semantic.comments.GenericTagSyntax>(syntax.tags[3]).parameters.size)
        assertEquals("run", assertIs<MethodTagSyntax>(syntax.tags[4]).name)
        assertEquals("mystery", assertIs<UnknownTagSyntax>(syntax.tags.last()).tagName)
    }

    @Test
    fun typePrefixExtractionStopsBeforeTrailingProse() {
        val syntax = parseDoc(
            """
            ---@type table<string, { value: number }> trailing prose that is not type syntax
            local value = {}
            """.trimIndent()
        )

        val typeTag = assertIs<TypeTagSyntax>(syntax.tags.single())
        assertEquals("table<string, { value: number }>", typeTag.typeText)
        assertEquals("trailing prose that is not type syntax", typeTag.description)
    }

    @Test
    fun commentLevelInlineTypeLookupKeepsParseablePrefix() {
        val comments = extractDocComments(
            """
            --- details
            ---@type fun(value: string): number extra explanation
            local value = 1
            """.trimIndent()
        )

        assertEquals("fun(value: string): number", parser.findInlineTypeText(comments))
    }

    private fun parseDoc(source: String) = assertNotNull(parser.parse(extractDocComments(source)))

    private fun extractDocComments(source: String): List<CommentStatement> {
        return luaParser.parse(source).body.statements.filterIsInstance<CommentStatement>().filter { it.isDocComment }
    }
}
