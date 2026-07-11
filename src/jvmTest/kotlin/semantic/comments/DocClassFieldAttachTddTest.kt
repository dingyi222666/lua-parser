package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.FieldTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.TypeTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.UnknownTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-257 corpus: EmmyLua @class / @field doc tags must attach to the following
 * table/local (and assignment) declarations when present. Malformed tags must not
 * crash CommentAttachPass.
 *
 * Test-only; verification is review-owned (no Gradle in worker waves).
 */
class DocClassFieldAttachTddTest {

    private val luaParser = LuaParser()
    private val attachPass = CommentAttachPass()

    @Test
    fun attachesClassAndFieldTagsToLocalTableDeclaration() {
        val chunk = parse(
            """
            --- Widget shape
            ---@class Widget
            ---@field id integer stable id
            ---@field label string display label
            local widget = {}
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        assertEquals("widget", assertIs<Identifier>(local.variables.single()).name)
        assertIs<TableConstructorExpression>(local.init.single())

        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))

        assertEquals("Widget shape", doc.description)
        assertEquals(listOf("class", "field", "field"), doc.tags.map { it.tagName })

        val classTag = assertIs<ClassTagSyntax>(doc.tags[0])
        assertEquals("Widget", classTag.name)
        assertNull(classTag.parentName)
        assertEquals(emptyList(), classTag.declaredTypeParameters)

        val fields = doc.tags.filterIsInstance<FieldTagSyntax>()
        assertEquals(listOf("id", "label"), fields.map { it.name })
        assertEquals(listOf("integer", "string"), fields.map { it.typeText })
        assertEquals("stable id", fields[0].description)
        assertEquals("display label", fields[1].description)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesClassWithParentAndTypeParametersToLocalTable() {
        val chunk = parse(
            """
            ---@class Box<T>: Base
            ---@field value T payload
            local box = {}
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))

        val classTag = assertIs<ClassTagSyntax>(doc.tags[0])
        assertEquals("Box", classTag.name)
        assertEquals("Base", classTag.parentName)
        assertEquals(listOf("T"), classTag.declaredTypeParameters)

        val field = assertIs<FieldTagSyntax>(doc.tags[1])
        assertEquals("value", field.name)
        assertEquals("T", field.typeText)
        assertEquals("payload", field.description)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesClassAndFieldTagsToGlobalAssignmentTable() {
        val chunk = parse(
            """
            ---@class GlobalWidget
            ---@field name string
            Widget = {}
            """.trimIndent()
        )

        val assignment = chunk.body.statements.filterIsInstance<AssignmentStatement>().single()
        assertEquals("Widget", assertIs<Identifier>(assignment.variables.single()).name)
        assertIs<TableConstructorExpression>(assignment.init.single())

        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(assignment))

        assertEquals("GlobalWidget", assertIs<ClassTagSyntax>(doc.tags[0]).name)
        assertEquals("name", assertIs<FieldTagSyntax>(doc.tags[1]).name)
        assertEquals("string", assertIs<FieldTagSyntax>(doc.tags[1]).typeText)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesOptionalAndVisibilityPrefixedFieldTags() {
        val chunk = parse(
            """
            ---@class User
            ---@field public id integer stable identifier
            ---@field private payload? string secret
            ---@field label? string
            local user = {}
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))
        val fields = doc.tags.filterIsInstance<FieldTagSyntax>()

        assertEquals(3, fields.size)
        // Visibility keywords are stripped; name is the field identifier.
        assertEquals("id", fields[0].name)
        assertEquals("integer", fields[0].typeText)
        assertEquals(false, fields[0].optional)
        assertEquals("stable identifier", fields[0].description)

        assertEquals("payload", fields[1].name)
        assertEquals(true, fields[1].optional)
        assertEquals("string", fields[1].typeText)
        assertEquals("secret", fields[1].description)

        assertEquals("label", fields[2].name)
        assertEquals(true, fields[2].optional)
        assertEquals("string", fields[2].typeText)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesDistinctClassFieldBlocksToAdjacentLocalsWithoutCrossTalk() {
        val chunk = parse(
            """
            ---@class First
            ---@field a string
            local first = {}

            ---@class Second
            ---@field b number
            ---@field c boolean
            local second = {}
            """.trimIndent()
        )

        val locals = chunk.body.statements.filterIsInstance<LocalStatement>()
        assertEquals(2, locals.size)
        assertEquals("first", assertIs<Identifier>(locals[0].variables.single()).name)
        assertEquals("second", assertIs<Identifier>(locals[1].variables.single()).name)

        val index = attachPass.attach(chunk)

        val firstDoc = assertNotNull(index.getDocComment(locals[0]))
        assertEquals("First", assertIs<ClassTagSyntax>(firstDoc.tags[0]).name)
        assertEquals(listOf("a"), firstDoc.tags.filterIsInstance<FieldTagSyntax>().map { it.name })

        val secondDoc = assertNotNull(index.getDocComment(locals[1]))
        assertEquals("Second", assertIs<ClassTagSyntax>(secondDoc.tags[0]).name)
        assertEquals(listOf("b", "c"), secondDoc.tags.filterIsInstance<FieldTagSyntax>().map { it.name })

        assertEquals(0, index.orphanAttachments.size)
        assertTrue(index.getAttachment(locals[0]) !== index.getAttachment(locals[1]))
    }

    @Test
    fun attachesNestedClassFieldDocsIndependentlyFromOuter() {
        val chunk = parse(
            """
            ---@class Outer
            ---@field child table
            local function outer()
                ---@class Inner
                ---@field flag boolean
                local inner = {}
                return inner
            end
            """.trimIndent()
        )

        val outerFn = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val innerLocal = outerFn.body!!.statements.filterIsInstance<LocalStatement>().single()
        assertEquals("outer", assertIs<Identifier>(outerFn.identifier).name)
        assertEquals("inner", assertIs<Identifier>(innerLocal.variables.single()).name)

        val index = attachPass.attach(chunk)

        val outerDoc = assertNotNull(index.getDocComment(outerFn))
        assertEquals("Outer", assertIs<ClassTagSyntax>(outerDoc.tags[0]).name)
        assertEquals(listOf("child"), outerDoc.tags.filterIsInstance<FieldTagSyntax>().map { it.name })

        val innerDoc = assertNotNull(index.getDocComment(innerLocal))
        assertEquals("Inner", assertIs<ClassTagSyntax>(innerDoc.tags[0]).name)
        assertEquals(listOf("flag"), innerDoc.tags.filterIsInstance<FieldTagSyntax>().map { it.name })

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesClassFieldDocsInsideDoBlockLocal() {
        val chunk = parse(
            """
            do
                ---@class Nested
                ---@field x number
                local nested = {}
            end
            """.trimIndent()
        )

        val doStmt = chunk.body.statements.filterIsInstance<DoStatement>().single()
        val nested = doStmt.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)

        val doc = assertNotNull(index.getDocComment(nested))
        assertEquals("Nested", assertIs<ClassTagSyntax>(doc.tags[0]).name)
        assertEquals("x", assertIs<FieldTagSyntax>(doc.tags[1]).name)
        assertEquals("number", assertIs<FieldTagSyntax>(doc.tags[1]).typeText)
        assertNull(index.getAttachment(doStmt))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun blankLineSeparatedClassBlockDoesNotAttachAcrossGap() {
        val chunk = parse(
            """
            ---@class Orphan
            ---@field ghost string

            ---@class Real
            ---@field present number
            local real = {}
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))

        assertEquals("Real", assertIs<ClassTagSyntax>(doc.tags[0]).name)
        assertEquals(listOf("present"), doc.tags.filterIsInstance<FieldTagSyntax>().map { it.name })

        val orphan = index.orphanAttachments.single()
        val orphanDoc = assertNotNull(orphan.docComment)
        assertEquals("Orphan", assertIs<ClassTagSyntax>(orphanDoc.tags[0]).name)
        assertEquals(listOf("ghost"), orphanDoc.tags.filterIsInstance<FieldTagSyntax>().map { it.name })
    }

    @Test
    fun classOnlyBlockWithoutFollowingDeclarationIsOrphan() {
        val chunk = parse(
            """
            ---@class FreeStanding
            ---@field alone boolean

            local unrelated = 1
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)

        assertNull(index.getAttachment(local))
        val orphan = index.orphanAttachments.single()
        val orphanDoc = assertNotNull(orphan.docComment)
        assertEquals("FreeStanding", assertIs<ClassTagSyntax>(orphanDoc.tags[0]).name)
        assertEquals(listOf("alone"), orphanDoc.tags.filterIsInstance<FieldTagSyntax>().map { it.name })
    }

    @Test
    fun doesNotAttachClassDocsToFollowingUnrelatedLocalAfterFunction() {
        val chunk = parse(
            """
            ---@class Point
            ---@field x number
            ---@field y number
            local point = {}

            local other = 1
            """.trimIndent()
        )

        val locals = chunk.body.statements.filterIsInstance<LocalStatement>()
        assertEquals(2, locals.size)
        val index = attachPass.attach(chunk)

        assertNotNull(index.getDocComment(locals[0]))
        assertNull(index.getAttachment(locals[1]))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun malformedClassAndFieldTagsDoNotCrashAttachPass() {
        val chunk = parse(
            """
            ---@class
            ---@class <T>
            ---@field
            ---@field private payload?
            ---@field ?
            ---@mystery still here
            local weird = {}
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()

        // Must not throw.
        val index = attachPass.attach(chunk)
        val attachment = assertNotNull(index.getAttachment(local))
        val doc = assertNotNull(index.getDocComment(local))

        assertEquals(0, index.orphanAttachments.size)
        assertTrue(doc.tags.isNotEmpty())
        assertTrue(doc.tags.any { it is ClassTagSyntax })
        assertTrue(doc.tags.any { it is FieldTagSyntax })
        assertTrue(doc.tags.any { it is UnknownTagSyntax })

        val fields = doc.tags.filterIsInstance<FieldTagSyntax>()
        // Bare ---@field yields empty name; optional "?" may become empty optional name;
        // visibility-prefixed incomplete field still produces a FieldTagSyntax.
        assertTrue(fields.any { it.name == "payload" && it.optional })
        assertTrue(fields.any { it.name.isEmpty() })
        assertNotNull(attachment.comments)
        assertTrue(attachment.comments.isNotEmpty())
    }

    @Test
    fun mixedMalformedAndValidClassFieldStillAttachToLocalTable() {
        val chunk = parse(
            """
            ---@class
            ---@class Valid: Base
            ---@field
            ---@field count number valid count
            ---@type never used as class shape alone
            local value = {}
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))

        assertEquals(0, index.orphanAttachments.size)

        val classes = doc.tags.filterIsInstance<ClassTagSyntax>()
        assertTrue(classes.size >= 2)
        val valid = classes.first { it.name == "Valid" }
        assertEquals("Base", valid.parentName)

        val fields = doc.tags.filterIsInstance<FieldTagSyntax>()
        assertTrue(fields.any { it.name.isEmpty() })
        val count = fields.first { it.name == "count" }
        assertEquals("number", count.typeText)
        assertEquals("valid count", count.description)

        assertTrue(doc.tags.any { it is TypeTagSyntax })
    }

    @Test
    fun attachesClassWithComplexFieldTypeText() {
        val chunk = parse(
            """
            ---@class MapHolder
            ---@field items table<string, { ok: boolean }> map of results
            local holder = { items = {} }
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))

        assertEquals("MapHolder", assertIs<ClassTagSyntax>(doc.tags[0]).name)
        val field = assertIs<FieldTagSyntax>(doc.tags[1])
        assertEquals("items", field.name)
        assertEquals("table<string, { ok: boolean }>", field.typeText)
        assertEquals("map of results", field.description)
        assertEquals(0, index.orphanAttachments.size)
    }

    private fun parse(source: String) = luaParser.parse(source)
}
