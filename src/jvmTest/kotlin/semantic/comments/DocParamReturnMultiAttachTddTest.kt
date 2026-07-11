package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ReturnTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.TypeTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.UnknownTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-289 corpus: multiple EmmyLua @param / @return tags must attach in source order
 * on the same function declaration. Orphan multi-tag blocks must not crash
 * CommentAttachPass.
 *
 * Complements DocParamReturnAttachTddTest (TASK-199) with denser multi-tag order,
 * interleaved param/return sequences, multi-orphan resilience, and long arity lists.
 *
 * Test-only; verification is review-owned (no Gradle in worker waves).
 *
 * Note: function bodies intentionally avoid multi-identifier returns such as
 * `return a, b` (parser currently rejects those with IllegalStateException near eof).
 * Multi-return *tags* and constant multi-returns remain covered.
 */
class DocParamReturnMultiAttachTddTest {

    private val luaParser = LuaParser()
    private val attachPass = CommentAttachPass()

    @Test
    fun multipleParamsAndReturnsAttachInSourceOrderOnLocalFunction() {
        val chunk = parse(
            """
            --- multi attach
            ---@param a string first
            ---@param b number second
            ---@param c boolean third
            ---@return string out1
            ---@return number out2
            local function pack(a, b, c)
                return a
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        assertEquals("pack", assertIs<Identifier>(function.identifier).name)

        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals("multi attach", doc.description)
        assertEquals(
            listOf("param", "param", "param", "return", "return"),
            doc.tags.map { it.tagName }
        )

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("a", "b", "c"), params.map { it.name })
        assertEquals(listOf("string", "number", "boolean"), params.map { it.typeText })
        assertEquals(listOf("first", "second", "third"), params.map { it.description })

        val returns = doc.tags.filterIsInstance<ReturnTagSyntax>()
        assertEquals(2, returns.size)
        assertEquals(listOf("string"), returns[0].typeTexts)
        assertEquals("out1", returns[0].description)
        assertEquals(listOf("number"), returns[1].typeTexts)
        assertEquals("out2", returns[1].description)

        // Full tag list order is source order (params then returns), not regrouped.
        assertIs<ParamTagSyntax>(doc.tags[0])
        assertIs<ParamTagSyntax>(doc.tags[1])
        assertIs<ParamTagSyntax>(doc.tags[2])
        assertIs<ReturnTagSyntax>(doc.tags[3])
        assertIs<ReturnTagSyntax>(doc.tags[4])

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun interleavedParamReturnTagsPreserveExactDocumentOrder() {
        // Unusual but legal EmmyLua: return tags can appear between params.
        val chunk = parse(
            """
            ---@param x number
            ---@return number half
            ---@param y number
            ---@return number sum
            ---@param z number
            local function weird(x, y, z)
                return x + y + z
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals(
            listOf("param", "return", "param", "return", "param"),
            doc.tags.map { it.tagName }
        )
        assertEquals(
            listOf("x", "y", "z"),
            doc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        )
        val returns = doc.tags.filterIsInstance<ReturnTagSyntax>()
        assertEquals(2, returns.size)
        assertEquals(listOf("number"), returns[0].typeTexts)
        assertEquals("half", returns[0].description)
        assertEquals(listOf("number"), returns[1].typeTexts)
        assertEquals("sum", returns[1].description)

        // Positions in full tag list.
        assertEquals("x", assertIs<ParamTagSyntax>(doc.tags[0]).name)
        assertEquals("half", assertIs<ReturnTagSyntax>(doc.tags[1]).description)
        assertEquals("y", assertIs<ParamTagSyntax>(doc.tags[2]).name)
        assertEquals("sum", assertIs<ReturnTagSyntax>(doc.tags[3]).description)
        assertEquals("z", assertIs<ParamTagSyntax>(doc.tags[4]).name)

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun manyParamsKeepDeclarationOrderIncludingOptionalAndVararg() {
        val chunk = parse(
            """
            ---@param p0 string
            ---@param p1 number
            ---@param p2? boolean optional flag
            ---@param p3 table
            ---@param p4 any
            ---@param ... string rest
            ---@return any
            ---@return nil
            local function long(p0, p1, p2, p3, p4, ...)
                return p0
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(6, params.size)
        assertEquals(listOf("p0", "p1", "p2", "p3", "p4", "..."), params.map { it.name })
        assertEquals(listOf(false, false, true, false, false, false), params.map { it.optional })
        assertEquals(listOf(false, false, false, false, false, true), params.map { it.vararg })
        assertEquals("optional flag", params[2].description)
        assertEquals("rest", params[5].description)

        val returns = doc.tags.filterIsInstance<ReturnTagSyntax>()
        assertEquals(2, returns.size)
        assertEquals(listOf("any"), returns[0].typeTexts)
        assertEquals(listOf("nil"), returns[1].typeTexts)

        assertEquals(
            listOf("param", "param", "param", "param", "param", "param", "return", "return"),
            doc.tags.map { it.tagName }
        )
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun multipleReturnTagsWithUnionAndCommaSeparatedMultiTypesPreserveOrder() {
        val chunk = parse(
            """
            ---@param path string
            ---@return string|nil content
            ---@return string|nil err
            ---@return integer, integer lineAndCol
            local function read(path)
                return nil, "missing"
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals(listOf("param", "return", "return", "return"), doc.tags.map { it.tagName })

        val returns = doc.tags.filterIsInstance<ReturnTagSyntax>()
        assertEquals(3, returns.size)
        assertEquals(listOf("string|nil"), returns[0].typeTexts)
        assertEquals("content", returns[0].description)
        assertEquals(listOf("string|nil"), returns[1].typeTexts)
        assertEquals("err", returns[1].description)
        assertEquals(listOf("integer", "integer"), returns[2].typeTexts)
        assertEquals("lineAndCol", returns[2].description)

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun multiParamReturnOnGlobalColonAndDotMethodsPreserveOrderIndependently() {
        val chunk = parse(
            """
            local api = {}

            ---@param self any
            ---@param key string
            ---@param value any
            ---@return boolean ok
            ---@return string|nil err
            function api:put(key, value)
                return true
            end

            ---@param key string
            ---@return any value
            ---@return boolean found
            function api.get(key)
                return nil
            end
            """.trimIndent()
        )

        val methods = chunk.body.statements.filterIsInstance<FunctionDeclaration>()
        assertEquals(2, methods.size)

        val colon = methods[0]
        val colonId = assertIs<MemberExpression>(colon.identifier)
        assertEquals(":", colonId.indexer)
        assertEquals("put", colonId.identifier.name)

        val dot = methods[1]
        val dotId = assertIs<MemberExpression>(dot.identifier)
        assertEquals(".", dotId.indexer)
        assertEquals("get", dotId.identifier.name)

        val index = attachPass.attach(chunk)

        val colonDoc = assertNotNull(index.getDocComment(colon))
        assertEquals(
            listOf("param", "param", "param", "return", "return"),
            colonDoc.tags.map { it.tagName }
        )
        assertEquals(
            listOf("self", "key", "value"),
            colonDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        )
        assertEquals(
            listOf(listOf("boolean"), listOf("string|nil")),
            colonDoc.tags.filterIsInstance<ReturnTagSyntax>().map { it.typeTexts }
        )

        val dotDoc = assertNotNull(index.getDocComment(dot))
        assertEquals(listOf("param", "return", "return"), dotDoc.tags.map { it.tagName })
        assertEquals(listOf("key"), dotDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
        assertEquals(
            listOf("value", "found"),
            dotDoc.tags.filterIsInstance<ReturnTagSyntax>().map { it.description }
        )

        assertEquals(0, index.orphanAttachments.size)
        assertTrue(index.getAttachment(colon) !== index.getAttachment(dot))
    }

    @Test
    fun adjacentFunctionsEachKeepOwnMultiParamReturnBlocksWithoutCrossTalk() {
        val chunk = parse(
            """
            ---@param a string
            ---@param b string
            ---@return string
            local function first(a, b)
                return a
            end

            ---@param x number
            ---@param y number
            ---@param z number
            ---@return number, number
            ---@return boolean ok
            local function second(x, y, z)
                return x + y + z
            end
            """.trimIndent()
        )

        val functions = chunk.body.statements.filterIsInstance<FunctionDeclaration>()
        assertEquals(2, functions.size)
        assertEquals("first", assertIs<Identifier>(functions[0].identifier).name)
        assertEquals("second", assertIs<Identifier>(functions[1].identifier).name)

        val index = attachPass.attach(chunk)

        val firstDoc = assertNotNull(index.getDocComment(functions[0]))
        assertEquals(listOf("param", "param", "return"), firstDoc.tags.map { it.tagName })
        assertEquals(listOf("a", "b"), firstDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
        assertEquals(listOf("string"), assertIs<ReturnTagSyntax>(firstDoc.tags[2]).typeTexts)

        val secondDoc = assertNotNull(index.getDocComment(functions[1]))
        assertEquals(
            listOf("param", "param", "param", "return", "return"),
            secondDoc.tags.map { it.tagName }
        )
        assertEquals(
            listOf("x", "y", "z"),
            secondDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        )
        val secondReturns = secondDoc.tags.filterIsInstance<ReturnTagSyntax>()
        assertEquals(listOf("number", "number"), secondReturns[0].typeTexts)
        assertEquals(listOf("boolean"), secondReturns[1].typeTexts)
        assertEquals("ok", secondReturns[1].description)

        assertEquals(0, index.orphanAttachments.size)
        assertTrue(index.getAttachment(functions[0]) !== index.getAttachment(functions[1]))
    }

    @Test
    fun nestedFunctionsEachAttachIndependentMultiParamReturnBlocks() {
        val chunk = parse(
            """
            ---@param outerA string
            ---@param outerB number
            ---@return fun(innerA: boolean, innerB: string): number, string
            local function outer(outerA, outerB)
                ---@param innerA boolean
                ---@param innerB string
                ---@return number
                ---@return string
                local function inner(innerA, innerB)
                    return outerB
                end
                return inner
            end
            """.trimIndent()
        )

        val outer = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val inner = outer.body!!.statements.filterIsInstance<FunctionDeclaration>().single()
        assertEquals("outer", assertIs<Identifier>(outer.identifier).name)
        assertEquals("inner", assertIs<Identifier>(inner.identifier).name)

        val index = attachPass.attach(chunk)

        val outerDoc = assertNotNull(index.getDocComment(outer))
        assertEquals(listOf("param", "param", "return"), outerDoc.tags.map { it.tagName })
        assertEquals(
            listOf("outerA", "outerB"),
            outerDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        )
        assertEquals(
            listOf("fun(innerA: boolean, innerB: string): number, string"),
            assertIs<ReturnTagSyntax>(outerDoc.tags[2]).typeTexts
        )

        val innerDoc = assertNotNull(index.getDocComment(inner))
        assertEquals(listOf("param", "param", "return", "return"), innerDoc.tags.map { it.tagName })
        assertEquals(
            listOf("innerA", "innerB"),
            innerDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        )
        assertEquals(
            listOf(listOf("number"), listOf("string")),
            innerDoc.tags.filterIsInstance<ReturnTagSyntax>().map { it.typeTexts }
        )

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun multiParamReturnOnAssignmentStyleLocalFunctionExpressionAttachesInOrder() {
        val chunk = parse(
            """
            ---@param msg string
            ---@param level? integer
            ---@return nil
            ---@return boolean written
            local log = function(msg, level)
            end
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)

        val doc = assertNotNull(index.getDocComment(local))
        assertEquals(listOf("param", "param", "return", "return"), doc.tags.map { it.tagName })

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("msg", "level"), params.map { it.name })
        assertEquals(false, params[0].optional)
        assertEquals(true, params[1].optional)
        assertEquals("integer", params[1].typeText)

        val returns = doc.tags.filterIsInstance<ReturnTagSyntax>()
        assertEquals(listOf("nil"), returns[0].typeTexts)
        assertEquals(listOf("boolean"), returns[1].typeTexts)
        assertEquals("written", returns[1].description)

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun blankLineSeparatedMultiParamBlockIsOrphanAndDoesNotCrashOrSteal() {
        val chunk = parse(
            """
            ---@param orphanA string
            ---@param orphanB number
            ---@return boolean

            ---@param real string
            ---@param extra number
            ---@return string
            ---@return number
            local function onlyReal(real, extra)
                return real
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)

        val doc = assertNotNull(index.getDocComment(function))
        assertEquals(listOf("param", "param", "return", "return"), doc.tags.map { it.tagName })
        assertEquals(
            listOf("real", "extra"),
            doc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        )
        assertEquals(
            listOf(listOf("string"), listOf("number")),
            doc.tags.filterIsInstance<ReturnTagSyntax>().map { it.typeTexts }
        )

        // Orphan multi-tag block is retained, not dropped, and attach does not throw.
        val orphan = index.orphanAttachments.single()
        val orphanDoc = assertNotNull(orphan.docComment)
        assertEquals(
            listOf("param", "param", "return"),
            orphanDoc.tags.map { it.tagName }
        )
        assertEquals(
            listOf("orphanA", "orphanB"),
            orphanDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        )
        assertEquals(listOf("boolean"), assertIs<ReturnTagSyntax>(orphanDoc.tags[2]).typeTexts)
    }

    @Test
    fun trailingOrphanMultiParamBlockAfterFunctionDoesNotCrashOrAttachBackwards() {
        val chunk = parse(
            """
            ---@param a number
            ---@return number
            local function square(a)
                return a * a
            end

            ---@param leftover string
            ---@param unused number
            ---@return nil
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)

        val doc = assertNotNull(index.getDocComment(function))
        assertEquals(listOf("param", "return"), doc.tags.map { it.tagName })
        assertEquals(listOf("a"), doc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
        assertEquals(listOf("number"), assertIs<ReturnTagSyntax>(doc.tags[1]).typeTexts)

        val orphan = index.orphanAttachments.single()
        val orphanDoc = assertNotNull(orphan.docComment)
        assertEquals(
            listOf("leftover", "unused"),
            orphanDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        )
        assertEquals(listOf("nil"), assertIs<ReturnTagSyntax>(orphanDoc.tags.last()).typeTexts)
    }

    @Test
    fun multiOrphanOnlyFileDoesNotCrashAndReportsOrphans() {
        val chunk = parse(
            """
            ---@param floating string
            ---@param also number
            ---@return boolean
            ---@return string|nil
            """.trimIndent()
        )

        // No declarations; attach must still succeed.
        assertTrue(chunk.body.statements.none { it is FunctionDeclaration || it is LocalStatement })

        val index = attachPass.attach(chunk)
        assertTrue(index.orphanAttachments.isNotEmpty())

        val orphanDoc = assertNotNull(index.orphanAttachments.first().docComment)
        assertEquals(
            listOf("param", "param", "return", "return"),
            orphanDoc.tags.map { it.tagName }
        )
        assertEquals(
            listOf("floating", "also"),
            orphanDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        )
        val returns = orphanDoc.tags.filterIsInstance<ReturnTagSyntax>()
        assertEquals(2, returns.size)
        assertEquals(listOf("boolean"), returns[0].typeTexts)
        assertEquals(listOf("string|nil"), returns[1].typeTexts)
    }

    @Test
    fun multiMalformedParamAndReturnTagsDoNotCrashAndStillAttachInOrder() {
        val chunk = parse(
            """
            ---@param
            ---@param first string ok
            ---@param ?
            ---@param second number
            ---@return
            ---@return boolean, string result
            ---@param ...
            ---@mystery multi
            ---@return table<string, { broken: boolean
            local function resilient(first, second, ...)
                return true
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()

        // Must not throw even with a dense multi-malformed block.
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))
        val attachment = assertNotNull(index.getAttachment(function))

        assertEquals(0, index.orphanAttachments.size)
        assertTrue(doc.tags.isNotEmpty())
        assertTrue(doc.tags.any { it is ParamTagSyntax })
        assertTrue(doc.tags.any { it is ReturnTagSyntax })
        assertTrue(doc.tags.any { it is UnknownTagSyntax })

        // Valid named params remain discoverable in relative order.
        val namedParams = doc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        assertTrue(namedParams.contains("first"))
        assertTrue(namedParams.contains("second"))
        assertTrue(namedParams.indexOf("first") < namedParams.indexOf("second"))

        val returns = doc.tags.filterIsInstance<ReturnTagSyntax>()
        assertTrue(returns.size >= 2)
        // A well-formed multi-type return appears among the returns.
        assertTrue(returns.any { it.typeTexts == listOf("boolean", "string") })

        assertNotNull(attachment.comments)
        assertTrue(attachment.comments.isNotEmpty())
    }

    @Test
    fun multiParamWithMixedTypeAndUnknownTagsPreservesParamReturnOrderRelativeToEachOther() {
        val chunk = parse(
            """
            ---@param a string
            ---@type ignored_on_function
            ---@param b number
            ---@mystery keep
            ---@return boolean
            ---@param c boolean
            ---@return string|nil
            local function mixed(a, b, c)
                return true
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        // Full order includes non-param/return tags where they appear.
        assertEquals(
            listOf("param", "type", "param", "mystery", "return", "param", "return"),
            doc.tags.map { it.tagName }
        )
        assertIs<TypeTagSyntax>(doc.tags[1])
        assertIs<UnknownTagSyntax>(doc.tags[3])

        assertEquals(
            listOf("a", "b", "c"),
            doc.tags.filterIsInstance<ParamTagSyntax>().map { it.name }
        )
        assertEquals(
            listOf(listOf("boolean"), listOf("string|nil")),
            doc.tags.filterIsInstance<ReturnTagSyntax>().map { it.typeTexts }
        )
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun multiParamReturnDoesNotAttachToFollowingUnrelatedLocal() {
        val chunk = parse(
            """
            ---@param x number
            ---@param y number
            ---@return number
            ---@return number
            local function add(x, y)
                return x + y
            end

            local other = 1
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val other = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)

        val doc = assertNotNull(index.getDocComment(function))
        assertEquals(listOf("param", "param", "return", "return"), doc.tags.map { it.tagName })
        assertNull(index.getAttachment(other))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun denseRepeatedSameNameParamsStillAttachInSourceOrder() {
        // EmmyLua overload-style repeated names should not collapse.
        val chunk = parse(
            """
            ---@param value string
            ---@param value number
            ---@param value boolean
            ---@return string
            ---@return number
            ---@return boolean
            local function overloadish(value)
                return value
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals(
            listOf("param", "param", "param", "return", "return", "return"),
            doc.tags.map { it.tagName }
        )

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(3, params.size)
        assertTrue(params.all { it.name == "value" })
        assertEquals(listOf("string", "number", "boolean"), params.map { it.typeText })

        val returns = doc.tags.filterIsInstance<ReturnTagSyntax>()
        assertEquals(3, returns.size)
        assertEquals(
            listOf(listOf("string"), listOf("number"), listOf("boolean")),
            returns.map { it.typeTexts }
        )

        assertEquals(0, index.orphanAttachments.size)
    }

    private fun parse(source: String) = luaParser.parse(source)
}
