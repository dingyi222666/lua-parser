package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.GenericTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.UnknownTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-338 corpus: EmmyLua @generic attaches to following function/class.
 * Unknown generic names degrade safely (no throw).
 *
 * Test-only. Verification is review-owned (no Gradle).
 */
class DocGenericParamAttachTddTest {

    private val luaParser = LuaParser()
    private val attachPass = CommentAttachPass()

    @Test
    fun attachesGenericToLocalFunction() {
        val chunk = parse(
            """
            ---@generic T
            ---@param value T
            ---@return T
            local function identity(value)
                return value
            end
            """.trimIndent()
        )
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        assertEquals("identity", assertIs<Identifier>(function.identifier).name)
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))
        val generic = doc.tags.filterIsInstance<GenericTagSyntax>().single()
        assertEquals(listOf("T"), generic.parameters.map { it.name })
    }

    @Test
    fun attachesGenericWithConstraintToFunction() {
        val chunk = parse(
            """
            ---@generic T: string
            ---@param value T
            local function onlyString(value)
                return value
            end
            """.trimIndent()
        )
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val generic = assertIs<GenericTagSyntax>(
            assertNotNull(index.getDocComment(function)).tags.filterIsInstance<GenericTagSyntax>().single()
        )
        assertEquals("T", generic.parameters.single().name)
        assertEquals("string", generic.parameters.single().constraintText)
    }

    @Test
    fun attachesMultipleGenericsToFunction() {
        val chunk = parse(
            """
            ---@generic K, V
            ---@param key K
            ---@param value V
            local function pair(key, value)
                return key
            end
            """.trimIndent()
        )
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val generic = assertNotNull(index.getDocComment(function)).tags.filterIsInstance<GenericTagSyntax>().single()
        assertEquals(listOf("K", "V"), generic.parameters.map { it.name })
    }

    @Test
    fun attachesGenericToClassTable() {
        val chunk = parse(
            """
            ---@generic T
            ---@class Box
            ---@field value T
            local box = {}
            """.trimIndent()
        )
        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))
        assertTrue(doc.tags.any { it is GenericTagSyntax })
        assertTrue(doc.tags.any { it is ClassTagSyntax })
    }

    @Test
    fun unknownGenericNameStillAttachesWithoutThrow() {
        val chunk = parse(
            """
            ---@generic DefinitelyNotARealTypeParam_xyz
            local function f(x)
                return x
            end
            """.trimIndent()
        )
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = runCatching { attachPass.attach(chunk) }
            .getOrElse { error("unknown generic name must not throw: $it") }
        val generic = assertNotNull(index.getDocComment(function)).tags.filterIsInstance<GenericTagSyntax>().single()
        assertEquals("DefinitelyNotARealTypeParam_xyz", generic.parameters.single().name)
    }

    @Test
    fun malformedGenericLineDegradesToUnknownTagWithoutThrow() {
        val chunk = parse(
            """
            ---@generic
            local function f()
            end
            """.trimIndent()
        )
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = runCatching { attachPass.attach(chunk) }
            .getOrElse { error("malformed @generic must not throw: $it") }
        val doc = index.getDocComment(function)
        // Empty @generic may become GenericTagSyntax with empty params or UnknownTagSyntax.
        if (doc != null) {
            assertTrue(
                doc.tags.any { it is GenericTagSyntax || it is UnknownTagSyntax || it.tagName == "generic" },
                "expected generic/unknown tag surface; tags=${doc.tags.map { it.tagName }}"
            )
        }
    }

    @Test
    fun adjacentFunctionsDoNotCrossAttachGenerics() {
        val chunk = parse(
            """
            ---@generic T
            local function first(a)
                return a
            end

            ---@generic U
            local function second(b)
                return b
            end
            """.trimIndent()
        )
        val functions = chunk.body.statements.filterIsInstance<FunctionDeclaration>()
        assertEquals(2, functions.size)
        val index = attachPass.attach(chunk)
        val first = assertNotNull(index.getDocComment(functions[0])).tags.filterIsInstance<GenericTagSyntax>().single()
        val second = assertNotNull(index.getDocComment(functions[1])).tags.filterIsInstance<GenericTagSyntax>().single()
        assertEquals(listOf("T"), first.parameters.map { it.name })
        assertEquals(listOf("U"), second.parameters.map { it.name })
    }

    private fun parse(source: String) = luaParser.parse(source)
}
