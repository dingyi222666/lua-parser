package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationDocumentation
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationIndex
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.aliasDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.classDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.fieldDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.functionDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.methodDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.toDeclarationDocumentation
import io.github.dingyi222666.luaparser.semantic.comments.AliasTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.FieldTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.MethodTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ReturnTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeclarationCommentInteropTest {

    private val parser = LuaParser()

    @Test
    fun convertsAttachedCommentBlocksIntoDeclarationDocumentation() {
        val chunk = parser.parse(
            """
            --- local value docs
            ---@type table<string, number>
            local value = {}
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val attachment = assertNotNull(CommentAttachPass().attach(chunk).getAttachment(local))

        val documentation = attachment.toDeclarationDocumentation()

        assertEquals(listOf("--- local value docs", "---@type table<string, number>"), documentation.comments.single().comment.trimEnd().lines())
        assertEquals("local value docs", documentation.docComment?.description)
        assertEquals("table<string, number>", documentation.inlineTypeText)
    }

    @Test
    fun supportsOrphanDocDrivenDeclarationsWithoutAstAnchors() {
        val chunk = parser.parse(
            """
            ---@alias Name string
            --- alias docs

            ---@class Widget: Base
            --- class docs
            """.trimIndent()
        )

        val attachments = CommentAttachPass().attach(chunk).orphanAttachments
        val aliasAttachment = attachments.first { attachment ->
            attachment.docComment?.tags?.any { it is AliasTagSyntax } == true
        }
        val classAttachment = attachments.first { attachment ->
            attachment.docComment?.tags?.any { it is ClassTagSyntax } == true
        }

        val aliasDeclaration = aliasDeclaration(
            id = DeclarationId(1),
            name = "Name",
            origin = DeclarationOrigin.DOC_COMMENT,
            anchorNode = null,
            range = commentRange(aliasAttachment.toDeclarationDocumentation()),
            documentation = aliasAttachment.toDeclarationDocumentation()
        )
        val classDeclaration = classDeclaration(
            id = DeclarationId(2),
            name = "Widget",
            origin = DeclarationOrigin.DOC_COMMENT,
            anchorNode = null,
            range = commentRange(classAttachment.toDeclarationDocumentation()),
            documentation = classAttachment.toDeclarationDocumentation()
        )

        assertNull(aliasDeclaration.anchorNode)
        assertNotNull(aliasDeclaration.range)
        assertTrue(aliasDeclaration.documentation?.docComment?.tags?.any { it.tagName == "alias" } == true)
        assertNull(classDeclaration.anchorNode)
        assertTrue(classDeclaration.documentation?.docComment?.tags?.any { it.tagName == "class" } == true)
    }

    @Test
    fun attachesFunctionDocsWithoutCreatingParameterDeclarations() {
        val chunk = parser.parse(
            """
            --- function docs
            ---@param value string input value
            ---@return boolean success flag
            local function render(value)
                return true
            end
            """.trimIndent()
        )

        val functionNode = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val attachment = assertNotNull(CommentAttachPass().attach(chunk).getAttachment(functionNode))
        val functionDeclaration = functionDeclaration(
            id = DeclarationId(1),
            name = "render",
            anchorNode = functionNode,
            documentation = attachment.toDeclarationDocumentation()
        )
        val index = DeclarationIndex(listOf(functionDeclaration), emptyList())

        val tags = functionDeclaration.documentation?.docComment?.tags.orEmpty()

        assertEquals("function docs", functionDeclaration.documentation?.docComment?.description)
        assertTrue(tags.any { it is ParamTagSyntax })
        assertTrue(tags.any { it is ReturnTagSyntax })
        assertEquals(emptyList(), index.getOwnedDeclarations(DeclarationOwner.Declaration(functionDeclaration.id)))
    }

    @Test
    fun supportsClassOwnedFieldAndMethodDeclarationsFromDocComments() {
        val chunk = parser.parse(
            """
            ---@class Widget
            --- widget docs
            ---@field id integer
            ---@method Widget:render(value string): boolean
            """.trimIndent()
        )

        val attachment = CommentAttachPass().attach(chunk).orphanAttachments.single()
        val documentation = attachment.toDeclarationDocumentation()
        val docComment = assertNotNull(documentation.docComment)
        val classDeclaration = classDeclaration(
            id = DeclarationId(1),
            name = "Widget",
            origin = DeclarationOrigin.DOC_COMMENT,
            documentation = documentation,
            range = commentRange(documentation)
        )
        val fieldDeclaration = fieldDeclaration(
            id = DeclarationId(2),
            name = "id",
            owner = DeclarationOwner.Declaration(classDeclaration.id),
            documentation = DeclarationDocumentation(docComment = docComment.copy(tags = docComment.tags.filterIsInstance<FieldTagSyntax>()))
        )
        val methodDeclaration = methodDeclaration(
            id = DeclarationId(3),
            name = "render",
            owner = DeclarationOwner.Declaration(classDeclaration.id),
            documentation = DeclarationDocumentation(docComment = docComment.copy(tags = docComment.tags.filterIsInstance<MethodTagSyntax>()))
        )
        val index = DeclarationIndex(
            declarations = listOf(classDeclaration, fieldDeclaration, methodDeclaration),
            symbols = emptyList()
        )

        val owned = index.getOwnedDeclarations(DeclarationOwner.Declaration(classDeclaration.id))

        assertEquals(listOf(fieldDeclaration, methodDeclaration), owned)
        assertTrue(classDeclaration.documentation?.docComment?.tags?.any { it is ClassTagSyntax } == true)
        assertTrue(fieldDeclaration.documentation?.docComment?.tags?.single() is FieldTagSyntax)
        assertTrue(methodDeclaration.documentation?.docComment?.tags?.single() is MethodTagSyntax)
    }

    private fun commentRange(documentation: DeclarationDocumentation): Range? {
        val comments = documentation.comments
        if (comments.isEmpty()) {
            return null
        }

        return Range(
            start = comments.first().range.start,
            end = comments.last().range.end
        )
    }
}
