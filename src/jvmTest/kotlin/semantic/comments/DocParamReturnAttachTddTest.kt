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
 * REVIEW19 / TASK-199 corpus: EmmyLua @param / @return doc tags must attach to the
 * correct function declaration targets. Malformed tags must not crash the attach pass.
 *
 * Test-only; verification is review-owned (no Gradle in worker waves).
 *
 * Note: function bodies intentionally avoid multi-identifier returns such as
 * `return b, c` (parser currently rejects those with IllegalStateException near eof).
 * Multi-return *tags* and constant multi-returns remain covered.
 */
class DocParamReturnAttachTddTest {

    private val luaParser = LuaParser()
    private val attachPass = CommentAttachPass()

    @Test
    fun attachesParamAndReturnTagsToLocalFunctionDeclaration() {
        val chunk = parse(
            """
            --- render input
            ---@param input string render input
            ---@return boolean render result
            local function render(input)
                return true
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        assertTrue(function.isLocal)
        assertEquals("render", assertIs<Identifier>(function.identifier).name)

        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals("render input", doc.description)
        assertEquals(2, doc.tags.size)

        val param = assertIs<ParamTagSyntax>(doc.tags[0])
        assertEquals("input", param.name)
        assertEquals("string", param.typeText)
        assertEquals("render input", param.description)
        assertEquals(false, param.optional)
        assertEquals(false, param.vararg)

        val ret = assertIs<ReturnTagSyntax>(doc.tags[1])
        assertEquals(listOf("boolean"), ret.typeTexts)
        assertEquals("render result", ret.description)

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesParamAndReturnTagsToGlobalFunctionDeclaration() {
        val chunk = parse(
            """
            ---@param x number
            ---@param y number
            ---@return number sum
            function add(x, y)
                return x + y
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        assertEquals(false, function.isLocal)
        assertEquals("add", assertIs<Identifier>(function.identifier).name)

        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))
        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        val returns = doc.tags.filterIsInstance<ReturnTagSyntax>()

        assertEquals(listOf("x", "y"), params.map { it.name })
        assertEquals(listOf("number", "number"), params.map { it.typeText })
        assertEquals(listOf("number"), returns.single().typeTexts)
        assertEquals("sum", returns.single().description)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesParamAndReturnTagsToColonMethodAndDotMethod() {
        val chunk = parse(
            """
            local widget = {}

            ---@param value string
            ---@return boolean
            function widget:render(value)
                return true
            end

            ---@param key string
            ---@param val any
            ---@return nil
            function widget.set(key, val)
            end
            """.trimIndent()
        )

        val methods = chunk.body.statements.filterIsInstance<FunctionDeclaration>()
        assertEquals(2, methods.size)

        val colonMethod = methods[0]
        val colonId = assertIs<MemberExpression>(colonMethod.identifier)
        assertEquals(":", colonId.indexer)
        assertEquals("render", colonId.identifier.name)

        val dotMethod = methods[1]
        val dotId = assertIs<MemberExpression>(dotMethod.identifier)
        assertEquals(".", dotId.indexer)
        assertEquals("set", dotId.identifier.name)

        val index = attachPass.attach(chunk)

        val colonDoc = assertNotNull(index.getDocComment(colonMethod))
        assertEquals(listOf("param", "return"), colonDoc.tags.map { it.tagName })
        assertEquals("value", assertIs<ParamTagSyntax>(colonDoc.tags[0]).name)
        assertEquals(listOf("boolean"), assertIs<ReturnTagSyntax>(colonDoc.tags[1]).typeTexts)

        val dotDoc = assertNotNull(index.getDocComment(dotMethod))
        assertEquals(listOf("param", "param", "return"), dotDoc.tags.map { it.tagName })
        assertEquals(listOf("key", "val"), dotDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
        assertEquals(listOf("nil"), assertIs<ReturnTagSyntax>(dotDoc.tags[2]).typeTexts)

        assertEquals(0, index.orphanAttachments.size)
        // Local table declaration has no doc block.
        val localWidget = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        assertNull(index.getAttachment(localWidget))
    }

    @Test
    fun attachesDistinctParamReturnBlocksToAdjacentFunctionsWithoutCrossTalk() {
        // Bodies avoid multi-identifier returns (`return b, c`) which currently fail parse.
        // Multi-return tags still exercise comma-separated @return type lists on second.
        val chunk = parse(
            """
            ---@param a string
            ---@return string
            local function first(a)
                return a
            end

            ---@param b number
            ---@param c number
            ---@return number, number
            local function second(b, c)
                return b + c
            end
            """.trimIndent()
        )

        val functions = chunk.body.statements.filterIsInstance<FunctionDeclaration>()
        assertEquals(2, functions.size)
        assertEquals("first", assertIs<Identifier>(functions[0].identifier).name)
        assertEquals("second", assertIs<Identifier>(functions[1].identifier).name)

        val index = attachPass.attach(chunk)

        val firstDoc = assertNotNull(index.getDocComment(functions[0]))
        assertEquals(listOf("a"), firstDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
        assertEquals(listOf("string"), assertIs<ReturnTagSyntax>(firstDoc.tags.single { it is ReturnTagSyntax }).typeTexts)

        val secondDoc = assertNotNull(index.getDocComment(functions[1]))
        assertEquals(listOf("b", "c"), secondDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
        assertEquals(
            listOf("number", "number"),
            assertIs<ReturnTagSyntax>(secondDoc.tags.single { it is ReturnTagSyntax }).typeTexts
        )

        // No shared attachment / no orphans.
        assertEquals(0, index.orphanAttachments.size)
        assertTrue(index.getAttachment(functions[0]) !== index.getAttachment(functions[1]))
    }

    @Test
    fun attachesNestedFunctionParamReturnIndependentlyFromOuter() {
        val chunk = parse(
            """
            ---@param outerArg string
            ---@return fun(innerArg: number): boolean
            local function outer(outerArg)
                ---@param innerArg number
                ---@return boolean
                local function inner(innerArg)
                    return innerArg > 0
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
        assertEquals(listOf("outerArg"), outerDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
        assertEquals(
            listOf("fun(innerArg: number): boolean"),
            assertIs<ReturnTagSyntax>(outerDoc.tags.single { it is ReturnTagSyntax }).typeTexts
        )

        val innerDoc = assertNotNull(index.getDocComment(inner))
        assertEquals(listOf("innerArg"), innerDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
        assertEquals(listOf("boolean"), assertIs<ReturnTagSyntax>(innerDoc.tags.single { it is ReturnTagSyntax }).typeTexts)

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesOptionalAndVarargParamTags() {
        val chunk = parse(
            """
            ---@param name? string optional name
            ---@param ... any rest
            ---@return any
            local function pack(name, ...)
                return name
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))
        val params = doc.tags.filterIsInstance<ParamTagSyntax>()

        assertEquals(2, params.size)
        assertEquals("name", params[0].name)
        assertEquals(true, params[0].optional)
        assertEquals(false, params[0].vararg)
        assertEquals("string", params[0].typeText)
        assertEquals("optional name", params[0].description)

        assertEquals("...", params[1].name)
        assertEquals(true, params[1].vararg)
        assertEquals(false, params[1].optional)
        assertEquals("any", params[1].typeText)
        assertEquals("rest", params[1].description)

        assertEquals(listOf("any"), assertIs<ReturnTagSyntax>(doc.tags.last()).typeTexts)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesMultipleReturnTagsAndMultiTypeReturn() {
        val chunk = parse(
            """
            ---@param path string
            ---@return string|nil content
            ---@return string|nil err
            local function read(path)
                return nil, "missing"
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals(1, doc.tags.filterIsInstance<ParamTagSyntax>().size)
        val returns = doc.tags.filterIsInstance<ReturnTagSyntax>()
        assertEquals(2, returns.size)
        assertEquals(listOf("string|nil"), returns[0].typeTexts)
        assertEquals("content", returns[0].description)
        assertEquals(listOf("string|nil"), returns[1].typeTexts)
        assertEquals("err", returns[1].description)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun blankLineSeparatedParamBlockDoesNotAttachAcrossGap() {
        val chunk = parse(
            """
            ---@param orphan string

            ---@param real number
            ---@return number
            local function onlyReal(real)
                return real
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals(listOf("real"), doc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
        assertEquals(listOf("number"), assertIs<ReturnTagSyntax>(doc.tags.single { it is ReturnTagSyntax }).typeTexts)

        val orphan = index.orphanAttachments.single()
        val orphanDoc = assertNotNull(orphan.docComment)
        assertEquals(listOf("orphan"), orphanDoc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
    }

    @Test
    fun malformedParamAndReturnTagsDoNotCrashAttachPass() {
        val chunk = parse(
            """
            ---@param
            ---@param ?
            ---@param ...
            ---@return
            ---@return table<string, { ok: boolean
            ---@mystery still here
            local function weird(...)
                return nil
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()

        // Must not throw.
        val index = attachPass.attach(chunk)
        val attachment = assertNotNull(index.getAttachment(function))
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals(0, index.orphanAttachments.size)
        assertTrue(doc.tags.isNotEmpty())
        assertTrue(doc.tags.any { it is ParamTagSyntax })
        assertTrue(doc.tags.any { it is ReturnTagSyntax })
        assertTrue(doc.tags.any { it is UnknownTagSyntax })

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        // Bare ---@param yields empty name; optional "?" token becomes empty optional name.
        assertTrue(params.any { it.name.isEmpty() })
        assertTrue(params.any { it.vararg && it.name == "..." })
        assertNotNull(attachment.comments)
        assertTrue(attachment.comments.isNotEmpty())
    }

    @Test
    fun mixedMalformedAndValidParamReturnStillAttachToFunction() {
        val chunk = parse(
            """
            ---@param value
            ---@param count number valid count
            ---@return
            ---@return boolean ok
            ---@type never used as inline on function
            local function check(value, count)
                return true
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals(0, index.orphanAttachments.size)

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(2, params.size)
        assertEquals("value", params[0].name)
        assertEquals(null, params[0].typeText)
        assertEquals("count", params[1].name)
        assertEquals("number", params[1].typeText)
        assertEquals("valid count", params[1].description)

        val returns = doc.tags.filterIsInstance<ReturnTagSyntax>()
        assertEquals(2, returns.size)
        // First return is empty/incomplete; second is valid.
        assertEquals(listOf("boolean"), returns[1].typeTexts)
        assertEquals("ok", returns[1].description)

        // @type on a function still attaches in the doc block (inline type may or may not apply).
        assertTrue(doc.tags.any { it is TypeTagSyntax })
    }

    @Test
    fun doesNotAttachFunctionDocsToFollowingUnrelatedLocal() {
        val chunk = parse(
            """
            ---@param x number
            ---@return number
            local function square(x)
                return x * x
            end

            local other = 1
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val other = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)

        assertNotNull(index.getDocComment(function))
        assertNull(index.getAttachment(other))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesParamReturnOnAssignmentStyleLocalFunctionExpression() {
        // local f = function(...) end is LocalStatement with FunctionDeclaration expression;
        // leading docs should bind to the local statement (declaration site), not orphan.
        val chunk = parse(
            """
            ---@param msg string
            ---@return nil
            local log = function(msg)
            end
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)

        val doc = assertNotNull(index.getDocComment(local))
        assertEquals(listOf("msg"), doc.tags.filterIsInstance<ParamTagSyntax>().map { it.name })
        assertEquals(listOf("nil"), assertIs<ReturnTagSyntax>(doc.tags.single { it is ReturnTagSyntax }).typeTexts)
        assertEquals(0, index.orphanAttachments.size)
    }

    private fun parse(source: String) = luaParser.parse(source)
}
