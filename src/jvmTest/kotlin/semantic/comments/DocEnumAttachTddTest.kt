package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.UnknownTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-340 corpus: @enum attaches member surfaces when supported.
 *
 * Product currently treats @enum as [UnknownTagSyntax] (no dedicated EnumTagSyntax).
 * Malformed enum docs must degrade safely without throw. When dedicated enum support
 * lands, this corpus should be updated deliberately.
 *
 * Test-only. Verification is review-owned (no Gradle).
 */
class DocEnumAttachTddTest {

    private val luaParser = LuaParser()
    private val attachPass = CommentAttachPass()

    @Test
    fun attachesEnumTagToLocalTableAsUnknownTag() {
        val chunk = parse(
            """
            --- color enum
            ---@enum Color
            local Color = {
                RED = 1,
                GREEN = 2,
                BLUE = 3,
            }
            """.trimIndent()
        )
        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))
        assertEquals("color enum", doc.description)
        val enumTag = doc.tags.filterIsInstance<UnknownTagSyntax>().single { it.tagName == "enum" }
        assertEquals("Color", enumTag.content.trim())
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun enumWithFieldTagsStillAttachesAllTags() {
        val chunk = parse(
            """
            ---@enum Status
            ---@field OK number
            ---@field ERR number
            local Status = { OK = 0, ERR = 1 }
            """.trimIndent()
        )
        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))
        assertTrue(doc.tags.any { it.tagName == "enum" })
        assertTrue(doc.tags.count { it.tagName == "field" } >= 2)
    }

    @Test
    fun malformedEmptyEnumDoesNotThrow() {
        val chunk = parse(
            """
            ---@enum
            local E = {}
            """.trimIndent()
        )
        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = runCatching { attachPass.attach(chunk) }
            .getOrElse { error("empty @enum must not throw: $it") }
        val doc = assertNotNull(index.getDocComment(local))
        assertTrue(doc.tags.any { it.tagName == "enum" })
    }

    @Test
    fun malformedEnumJunkContentDoesNotThrow() {
        val chunk = parse(
            """
            ---@enum <<<not a name>>>
            local E = {}
            """.trimIndent()
        )
        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = runCatching { attachPass.attach(chunk) }
            .getOrElse { error("junk @enum must not throw: $it") }
        val doc = assertNotNull(index.getDocComment(local))
        val tag = doc.tags.single { it.tagName == "enum" }
        assertTrue(tag is UnknownTagSyntax)
        assertTrue((tag as UnknownTagSyntax).content.contains("<<<"))
    }

    @Test
    fun adjacentEnumsDoNotCrossAttach() {
        val chunk = parse(
            """
            ---@enum A
            local A = { X = 1 }

            ---@enum B
            local B = { Y = 2 }
            """.trimIndent()
        )
        val locals = chunk.body.statements.filterIsInstance<LocalStatement>()
        assertEquals(2, locals.size)
        val index = attachPass.attach(chunk)
        val a = assertNotNull(index.getDocComment(locals[0])).tags.single { it.tagName == "enum" }
        val b = assertNotNull(index.getDocComment(locals[1])).tags.single { it.tagName == "enum" }
        assertEquals("A", assertIsUnknown(a).content.trim())
        assertEquals("B", assertIsUnknown(b).content.trim())
    }

    private fun assertIsUnknown(tag: io.github.dingyi222666.luaparser.semantic.comments.DocTagSyntax): UnknownTagSyntax {
        assertTrue(tag is UnknownTagSyntax, "expected UnknownTagSyntax, got ${tag::class.simpleName}")
        return tag as UnknownTagSyntax
    }

    private fun parse(source: String) = luaParser.parse(source)
}
