package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ReturnTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.UnknownTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-339 corpus: @see and @deprecated attach without breaking other tags.
 *
 * Product currently maps unknown EmmyLua tags (including see/deprecated) to
 * [UnknownTagSyntax] while preserving tagName/content. Missing targets must not throw.
 *
 * Test-only. Verification is review-owned (no Gradle).
 */
class DocSeeDeprecatedAttachTddTest {

    private val luaParser = LuaParser()
    private val attachPass = CommentAttachPass()

    @Test
    fun attachesSeeAndDeprecatedAsUnknownTagsAlongsideParamReturn() {
        val chunk = parse(
            """
            --- render helper
            ---@param input string
            ---@return boolean
            ---@see other.render
            ---@deprecated use render2
            local function render(input)
                return true
            end
            """.trimIndent()
        )
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        assertEquals("render", assertIs<Identifier>(function.identifier).name)
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))
        assertTrue(doc.tags.any { it is ParamTagSyntax })
        assertTrue(doc.tags.any { it is ReturnTagSyntax })
        val see = doc.tags.filterIsInstance<UnknownTagSyntax>().single { it.tagName == "see" }
        val deprecated = doc.tags.filterIsInstance<UnknownTagSyntax>().single { it.tagName == "deprecated" }
        assertEquals("other.render", see.content.trim())
        assertTrue(deprecated.content.contains("render2"))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun missingSeeTargetDoesNotThrow() {
        val chunk = parse(
            """
            ---@see
            local function f()
            end
            """.trimIndent()
        )
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = runCatching { attachPass.attach(chunk) }
            .getOrElse { error("@see without target must not throw: $it") }
        val doc = assertNotNull(index.getDocComment(function))
        assertTrue(doc.tags.any { it.tagName == "see" })
    }

    @Test
    fun missingDeprecatedBodyDoesNotThrow() {
        val chunk = parse(
            """
            ---@deprecated
            local function f()
            end
            """.trimIndent()
        )
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = runCatching { attachPass.attach(chunk) }
            .getOrElse { error("empty @deprecated must not throw: $it") }
        val doc = assertNotNull(index.getDocComment(function))
        assertTrue(doc.tags.any { it.tagName == "deprecated" })
    }

    @Test
    fun seeDoesNotBreakClassFieldAttachOnLocal() {
        val chunk = parse(
            """
            ---@class Widget
            ---@field id number
            ---@see WidgetFactory
            local widget = {}
            """.trimIndent()
        )
        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))
        assertTrue(doc.tags.any { it.tagName == "class" })
        assertTrue(doc.tags.any { it.tagName == "field" })
        assertTrue(doc.tags.any { it.tagName == "see" })
    }

    @Test
    fun multipleSeeTagsPreserveOrder() {
        val chunk = parse(
            """
            ---@see A
            ---@see B
            ---@deprecated old
            local function f()
            end
            """.trimIndent()
        )
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val tags = assertNotNull(index.getDocComment(function)).tags
        val names = tags.map { it.tagName }
        assertEquals(listOf("see", "see", "deprecated"), names)
        val sees = tags.filterIsInstance<UnknownTagSyntax>().filter { it.tagName == "see" }
        assertEquals(listOf("A", "B"), sees.map { it.content.trim() })
    }

    @Test
    fun adjacentFunctionsDoNotCrossAttachSeeDeprecated() {
        val chunk = parse(
            """
            ---@deprecated first
            local function first()
            end

            ---@see second.target
            local function second()
            end
            """.trimIndent()
        )
        val functions = chunk.body.statements.filterIsInstance<FunctionDeclaration>()
        assertEquals(2, functions.size)
        val index = attachPass.attach(chunk)
        val firstDoc = assertNotNull(index.getDocComment(functions[0]))
        val secondDoc = assertNotNull(index.getDocComment(functions[1]))
        assertTrue(firstDoc.tags.any { it.tagName == "deprecated" })
        assertTrue(firstDoc.tags.none { it.tagName == "see" })
        assertTrue(secondDoc.tags.any { it.tagName == "see" })
        assertTrue(secondDoc.tags.none { it.tagName == "deprecated" })
    }

    private fun parse(source: String) = luaParser.parse(source)
}
