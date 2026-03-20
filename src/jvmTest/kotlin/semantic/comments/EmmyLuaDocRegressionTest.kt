package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.comments.AliasTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EmmyLuaDocRegressionTest {

    private val parser = LuaParser()
    private val syntaxParser = DocCommentSyntaxParser()

    @Test
    fun parsesCoreEmmyLuaTagsIntoExpectedModels() {
        val source = """
            --- Widget docs
            --- More details
            ---@class Widget<T>: BaseWidget
            ---@field public id integer stable identifier
            ---@param value string input value
            ---@return number result value
            ---@type Widget<T>
            ---@alias Identifier string | number identifier alias
            ---@generic T: BaseValue, U
            ---@overload fun(value: string): number
            ---@method Widget.render fun(self: Widget, value: string): boolean
            local widget = {}
            """.trimIndent()

        val syntax = assertNotNull(syntaxParser.parse(docComments(source)))

        assertEquals("Widget docs\nMore details", syntax.description)
        assertEquals(9, syntax.tags.size)

        val classTag = assertIs<ClassTagSyntax>(syntax.tags[0])
        assertEquals("Widget", classTag.name)
        assertEquals("BaseWidget", classTag.parentName)
        assertEquals(listOf("T"), classTag.declaredTypeParameters)

        val fieldTag = assertIs<FieldTagSyntax>(syntax.tags[1])
        assertEquals("id", fieldTag.name)
        assertEquals("integer", fieldTag.typeText)
        assertEquals("stable identifier", fieldTag.description)

        val paramTag = assertIs<ParamTagSyntax>(syntax.tags[2])
        assertEquals("value", paramTag.name)
        assertEquals("string", paramTag.typeText)
        assertEquals("input value", paramTag.description)

        val returnTag = assertIs<ReturnTagSyntax>(syntax.tags[3])
        assertEquals(listOf("number"), returnTag.typeTexts)
        assertEquals("result value", returnTag.description)

        val typeTag = assertIs<TypeTagSyntax>(syntax.tags[4])
        assertEquals("Widget<T>", typeTag.typeText)

        val aliasTag = assertIs<AliasTagSyntax>(syntax.tags[5])
        assertEquals("Identifier", aliasTag.name)
        assertEquals("string | number", aliasTag.targetTypeText)
        assertEquals("identifier alias", aliasTag.description)

        val genericTag = assertIs<GenericTagSyntax>(syntax.tags[6])
        assertEquals("T", genericTag.parameters[0].name)
        assertEquals("BaseValue", genericTag.parameters[0].constraintText)
        assertEquals("U", genericTag.parameters[1].name)
        assertEquals(null, genericTag.parameters[1].constraintText)

        val overloadTag = assertIs<OverloadTagSyntax>(syntax.tags[7])
        assertEquals("fun(value: string): number", overloadTag.signatureText)

        val methodTag = assertIs<MethodTagSyntax>(syntax.tags[8])
        assertEquals("Widget", methodTag.className)
        assertEquals("render", methodTag.name)
        assertEquals("fun(self: Widget, value: string): boolean", methodTag.signatureText)
    }

    @Test
    fun attachesDocBlocksToLocalAndLocalFunctionDeclarations() {
        val chunk = parse(
            """
            ---@type table<string, number>
            local value = {}

            --- function docs
            ---@param input string render input
            ---@return boolean render result
            local function render(input)
                return input
            end
            """.trimIndent()
        )

        val localValue = chunk.body.statements.filterIsInstance<LocalStatement>().first()
        val localFunction = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = CommentAttachPass().attach(chunk)

        assertNotNull(index.getAttachment(localValue))
        assertEquals("table<string, number>", index.getInlineTypeText(localValue))
        assertEquals("table<string, number>", assertIs<TypeTagSyntax>(assertNotNull(index.getDocComment(localValue)).tags.single()).typeText)

        assertNotNull(index.getAttachment(localFunction))
        val functionDoc = assertNotNull(index.getDocComment(localFunction))
        assertEquals("function docs", functionDoc.description)
        assertIs<ParamTagSyntax>(functionDoc.tags[0])
        assertIs<ReturnTagSyntax>(functionDoc.tags[1])
    }

    @Test
    fun attachesDocBlocksToMethodsAndNestedDeclarations() {
        val chunk = parse(
            """
            local widget = {}

            ---@param value string
            ---@return boolean
            function widget:render(value)
                do
                    ---@type integer
                    local inner = 1
                end

                return value
            end
            """.trimIndent()
        )

        val method = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val nestedDo = method.body!!.statements.filterIsInstance<DoStatement>().single()
        val innerLocal = nestedDo.body.statements.filterIsInstance<LocalStatement>().single()
        val index = CommentAttachPass().attach(chunk)

        val methodAttachment = assertNotNull(index.getAttachment(method))
        val innerAttachment = assertNotNull(index.getAttachment(innerLocal))

        assertEquals(listOf("param", "return"), methodAttachment.docComment?.tags.orEmpty().map { it.tagName })
        assertEquals("integer", innerAttachment.inlineTypeText)
        assertEquals("integer", index.getInlineTypeText(innerLocal))
        assertNull(index.getAttachment(nestedDo))
        assertNull(index.getInlineTypeText(method))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun preservesOrphanDocBlocksWhenNoAdjacentTargetExists() {
        val chunk = parse(
            """
            ---@alias Identifier string | number

            local value = 1
            """.trimIndent()
        )

        val target = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = CommentAttachPass().attach(chunk)
        val orphan = index.orphanAttachments.single()
        val aliasTag = assertIs<AliasTagSyntax>(assertNotNull(orphan.docComment).tags.single())

        assertNull(index.getAttachment(target))
        assertEquals("Identifier", aliasTag.name)
        assertEquals("string | number", aliasTag.targetTypeText)
    }

    @Test
    fun malformedTagsArePreservedWithoutCrashing() {
        val source = """
            ---@param
            ---@field private payload?
            ---@alias Result<T
            ---@generic T: Foo<, U, V: { nested: string | }
            ---@method Service.run(value: table<string, { ok: boolean }>, extra
            ---@mystery still here
            ---@
            local value = 1
            """.trimIndent()

        val syntax = assertNotNull(syntaxParser.parse(docComments(source)))

        assertEquals(6, syntax.tags.size)
        assertEquals("", assertIs<ParamTagSyntax>(syntax.tags[0]).name)
        val fieldTag = assertIs<FieldTagSyntax>(syntax.tags[1])
        assertEquals("payload", fieldTag.name)
        assertEquals(true, fieldTag.optional)
        assertEquals("Result<T", assertIs<AliasTagSyntax>(syntax.tags[2]).name)
        assertEquals(3, assertIs<GenericTagSyntax>(syntax.tags[3]).parameters.size)

        val methodTag = assertIs<MethodTagSyntax>(syntax.tags[4])
        assertEquals("Service", methodTag.className)
        assertEquals("run", methodTag.name)
        assertEquals("(value: table<string, { ok: boolean }>, extra", methodTag.signatureText)

        val unknownTag = assertIs<UnknownTagSyntax>(syntax.tags[5])
        assertEquals("mystery", unknownTag.tagName)
        assertEquals("still here", unknownTag.content)
    }

    @Test
    fun inlineTypeExtractionStopsBeforeProseButKeepsTypeTagDescription() {
        val source = """
            ---@type table<string, { value: number }> trailing prose that is not type syntax
            local value = {}
            """.trimIndent()

        val syntax = assertNotNull(syntaxParser.parse(docComments(source)))
        val chunk = parse(source)
        val target = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = CommentAttachPass().attach(chunk)
        val typeTag = assertIs<TypeTagSyntax>(syntax.tags.single())

        assertEquals("table<string, { value: number }>", typeTag.typeText)
        assertEquals("trailing prose that is not type syntax", typeTag.description)
        assertEquals("table<string, { value: number }>", index.getInlineTypeText(target))
    }

    @Test
    fun mixedMalformedAndValidTagsStillAttachToNearestTarget() {
        val chunk = parse(
            """
            ---@param value
            ---@type table<string, number> trailing words
            local value = {}
            """.trimIndent()
        )

        val target = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = CommentAttachPass().attach(chunk)
        val attachment = assertNotNull(index.getAttachment(target))
        val docComment = assertNotNull(index.getDocComment(target))

        assertEquals(0, index.orphanAttachments.size)
        assertEquals("table<string, number>", attachment.inlineTypeText)
        assertEquals("table<string, number>", index.getInlineTypeText(target))
        assertEquals("value", assertIs<ParamTagSyntax>(docComment.tags[0]).name)
        assertEquals("table<string, number>", assertIs<TypeTagSyntax>(docComment.tags[1]).typeText)
        assertEquals("trailing words", assertIs<TypeTagSyntax>(docComment.tags[1]).description)
    }

    private fun parse(source: String) = parser.parse(source)

    private fun docComments(source: String) = parse(source).body.statements
        .filterIsInstance<CommentStatement>()
        .filter { it.isDocComment }
}
