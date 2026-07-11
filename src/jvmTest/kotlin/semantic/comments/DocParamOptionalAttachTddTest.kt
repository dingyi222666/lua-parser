package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ReturnTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.UnknownTagSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-365 corpus: EmmyLua optional `@param name?` tags attach with
 * [ParamTagSyntax.optional] true and the trailing `?` stripped from [ParamTagSyntax.name].
 *
 * Complements DocParamReturnAttachTddTest / DocParamReturnMultiAttachTddTest by densifying
 * optional-marker edge cases (mixed required/optional/vararg, methods, orphans, malformed).
 *
 * Product surface (existing parser only — no new APIs):
 * - `---@param name? type desc` → name="name", optional=true, typeText/description preserved
 * - Required params keep optional=false
 * - `---@param ... type` remains vararg=true, optional=false (token is exactly "...")
 * - Malformed optional markers degrade without throwing
 *
 * Test-only; verification is review-owned (no Gradle in worker waves).
 *
 * Note: function bodies intentionally avoid multi-identifier returns such as
 * `return a, b` (parser currently rejects those with IllegalStateException near eof).
 */
class DocParamOptionalAttachTddTest {

    private val luaParser = LuaParser()
    private val attachPass = CommentAttachPass()

    @Test
    fun attachesOptionalParamTrailingQuestionToLocalFunction() {
        val chunk = parse(
            """
            --- optional name helper
            ---@param name? string optional name
            ---@return boolean
            local function greet(name)
                return true
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        assertTrue(function.isLocal)
        assertEquals("greet", assertIs<Identifier>(function.identifier).name)

        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals("optional name helper", doc.description)
        val param = doc.tags.filterIsInstance<ParamTagSyntax>().single()
        assertEquals("name", param.name)
        assertEquals(true, param.optional)
        assertEquals(false, param.vararg)
        assertEquals("string", param.typeText)
        assertEquals("optional name", param.description)
        // Trailing ? is marker only — not part of the name surface.
        assertTrue(!param.name.endsWith("?"))

        assertEquals(listOf("boolean"), assertIs<ReturnTagSyntax>(doc.tags.last()).typeTexts)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun mixedRequiredOptionalAndVarargParamsPreserveFlagsAndOrder() {
        val chunk = parse(
            """
            ---@param required string must
            ---@param optional? number maybe
            ---@param flag? boolean
            ---@param ... any rest
            ---@return any
            local function pack(required, optional, flag, ...)
                return required
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(4, params.size)
        assertEquals(listOf("required", "optional", "flag", "..."), params.map { it.name })
        assertEquals(listOf(false, true, true, false), params.map { it.optional })
        assertEquals(listOf(false, false, false, true), params.map { it.vararg })
        assertEquals(listOf("string", "number", "boolean", "any"), params.map { it.typeText })
        assertEquals("must", params[0].description)
        assertEquals("maybe", params[1].description)

        assertEquals(
            listOf("param", "param", "param", "param", "return"),
            doc.tags.map { it.tagName }
        )
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun optionalParamOnGlobalFunctionAndColonDotMethods() {
        val chunk = parse(
            """
            ---@param x? number
            ---@param y number
            ---@return number
            function addMaybe(x, y)
                return y
            end

            local widget = {}

            ---@param value? string
            ---@return boolean
            function widget:render(value)
                return true
            end

            ---@param key string
            ---@param val? any
            function widget.set(key, val)
            end
            """.trimIndent()
        )

        val functions = chunk.body.statements.filterIsInstance<FunctionDeclaration>()
        assertEquals(3, functions.size)

        val global = functions[0]
        assertEquals(false, global.isLocal)
        assertEquals("addMaybe", assertIs<Identifier>(global.identifier).name)

        val colon = functions[1]
        val colonId = assertIs<MemberExpression>(colon.identifier)
        assertEquals(":", colonId.indexer)
        assertEquals("render", colonId.identifier.name)

        val dot = functions[2]
        val dotId = assertIs<MemberExpression>(dot.identifier)
        assertEquals(".", dotId.indexer)
        assertEquals("set", dotId.identifier.name)

        val index = attachPass.attach(chunk)

        val globalParams = assertNotNull(index.getDocComment(global)).tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("x", "y"), globalParams.map { it.name })
        assertEquals(listOf(true, false), globalParams.map { it.optional })

        val colonParams = assertNotNull(index.getDocComment(colon)).tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("value"), colonParams.map { it.name })
        assertEquals(true, colonParams.single().optional)
        assertEquals("string", colonParams.single().typeText)

        val dotParams = assertNotNull(index.getDocComment(dot)).tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("key", "val"), dotParams.map { it.name })
        assertEquals(listOf(false, true), dotParams.map { it.optional })

        assertEquals(0, index.orphanAttachments.size)
        assertTrue(index.getAttachment(global) !== index.getAttachment(colon))
        assertTrue(index.getAttachment(colon) !== index.getAttachment(dot))
    }

    @Test
    fun optionalParamOnAssignmentStyleLocalFunctionExpression() {
        val chunk = parse(
            """
            ---@param msg string
            ---@param level? integer optional level
            ---@return nil
            local log = function(msg, level)
            end
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("msg", "level"), params.map { it.name })
        assertEquals(false, params[0].optional)
        assertEquals(true, params[1].optional)
        assertEquals("integer", params[1].typeText)
        assertEquals("optional level", params[1].description)
        assertEquals(listOf("nil"), assertIs<ReturnTagSyntax>(doc.tags.last()).typeTexts)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun optionalParamWithUnionTypeAndDescriptionStillOptional() {
        val chunk = parse(
            """
            ---@param path? string|nil maybe path
            ---@param mode? string open mode
            ---@return string|nil
            local function openMaybe(path, mode)
                return nil
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))
        val params = doc.tags.filterIsInstance<ParamTagSyntax>()

        assertEquals(2, params.size)
        assertEquals("path", params[0].name)
        assertEquals(true, params[0].optional)
        assertEquals("string|nil", params[0].typeText)
        assertEquals("maybe path", params[0].description)

        assertEquals("mode", params[1].name)
        assertEquals(true, params[1].optional)
        assertEquals("string", params[1].typeText)
        assertEquals("open mode", params[1].description)

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun optionalWithoutTypeStillMarksOptionalAndAttaches() {
        val chunk = parse(
            """
            ---@param bare?
            ---@param named? with only description
            local function soft(bare, named)
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))
        val params = doc.tags.filterIsInstance<ParamTagSyntax>()

        assertEquals(2, params.size)
        assertEquals("bare", params[0].name)
        assertEquals(true, params[0].optional)
        // No type token — typeText may be null/blank depending on remainder parse.
        assertTrue(params[0].typeText.isNullOrBlank())

        assertEquals("named", params[1].name)
        assertEquals(true, params[1].optional)
        // Description may land in typeText or description depending on type-prefix recovery;
        // product must still strip ? and mark optional without inventing a new field.
        assertTrue(
            params[1].description.contains("description") ||
                (params[1].typeText?.contains("description") == true) ||
                params[1].description.isNotBlank() ||
                !params[1].typeText.isNullOrBlank(),
            "expected remainder preserved on optional param without typed prefix; " +
                "typeText=${params[1].typeText} description=${params[1].description}"
        )

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun adjacentFunctionsDoNotCrossAttachOptionalParams() {
        val chunk = parse(
            """
            ---@param a? string
            ---@return string
            local function first(a)
                return a
            end

            ---@param b number
            ---@param c? boolean
            ---@return number
            local function second(b, c)
                return b
            end
            """.trimIndent()
        )

        val functions = chunk.body.statements.filterIsInstance<FunctionDeclaration>()
        assertEquals(2, functions.size)
        assertEquals("first", assertIs<Identifier>(functions[0].identifier).name)
        assertEquals("second", assertIs<Identifier>(functions[1].identifier).name)

        val index = attachPass.attach(chunk)

        val firstParams = assertNotNull(index.getDocComment(functions[0])).tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("a"), firstParams.map { it.name })
        assertEquals(listOf(true), firstParams.map { it.optional })

        val secondParams = assertNotNull(index.getDocComment(functions[1])).tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("b", "c"), secondParams.map { it.name })
        assertEquals(listOf(false, true), secondParams.map { it.optional })

        assertEquals(0, index.orphanAttachments.size)
        assertTrue(index.getAttachment(functions[0]) !== index.getAttachment(functions[1]))
    }

    @Test
    fun nestedFunctionsAttachOptionalParamsIndependently() {
        val chunk = parse(
            """
            ---@param outer? string
            ---@return fun(inner?: number): boolean
            local function outer(outer)
                ---@param inner? number
                ---@return boolean
                local function inner(inner)
                    return true
                end
                return inner
            end
            """.trimIndent()
        )

        val outerFn = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val innerFn = outerFn.body!!.statements.filterIsInstance<FunctionDeclaration>().single()
        assertEquals("outer", assertIs<Identifier>(outerFn.identifier).name)
        assertEquals("inner", assertIs<Identifier>(innerFn.identifier).name)

        val index = attachPass.attach(chunk)

        val outerParam = assertNotNull(index.getDocComment(outerFn)).tags.filterIsInstance<ParamTagSyntax>().single()
        assertEquals("outer", outerParam.name)
        assertEquals(true, outerParam.optional)

        val innerParam = assertNotNull(index.getDocComment(innerFn)).tags.filterIsInstance<ParamTagSyntax>().single()
        assertEquals("inner", innerParam.name)
        assertEquals(true, innerParam.optional)
        assertEquals("number", innerParam.typeText)

        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun blankLineSeparatedOptionalParamBlockIsOrphanAndDoesNotSteal() {
        val chunk = parse(
            """
            ---@param orphan? string

            ---@param real? number
            ---@return number
            local function onlyReal(real)
                return real
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("real"), params.map { it.name })
        assertEquals(true, params.single().optional)
        assertEquals("number", params.single().typeText)

        val orphan = index.orphanAttachments.single()
        val orphanDoc = assertNotNull(orphan.docComment)
        val orphanParam = orphanDoc.tags.filterIsInstance<ParamTagSyntax>().single()
        assertEquals("orphan", orphanParam.name)
        assertEquals(true, orphanParam.optional)
    }

    @Test
    fun trailingOrphanOptionalParamsDoNotAttachBackwards() {
        val chunk = parse(
            """
            ---@param a? number
            ---@return number
            local function square(a)
                return a
            end

            ---@param leftover? string
            ---@param unused? number
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)

        val doc = assertNotNull(index.getDocComment(function))
        val attached = doc.tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("a"), attached.map { it.name })
        assertEquals(true, attached.single().optional)

        val orphan = index.orphanAttachments.single()
        val orphanParams = assertNotNull(orphan.docComment).tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(listOf("leftover", "unused"), orphanParams.map { it.name })
        assertTrue(orphanParams.all { it.optional })
    }

    @Test
    fun malformedOptionalMarkersDoNotCrashAttachPass() {
        val chunk = parse(
            """
            ---@param ?
            ---@param ?? string
            ---@param name?
            ---@param good? number ok
            ---@param ...
            ---@mystery keep
            local function weird(name, good, ...)
                return nil
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()

        val index = runCatching { attachPass.attach(chunk) }
            .getOrElse { error("malformed optional @param must not throw: $it") }
        val doc = assertNotNull(index.getDocComment(function))
        val attachment = assertNotNull(index.getAttachment(function))

        assertEquals(0, index.orphanAttachments.size)
        assertTrue(doc.tags.any { it is ParamTagSyntax })
        assertTrue(doc.tags.any { it is UnknownTagSyntax })

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        // Bare "?" becomes empty optional name (same surface as DocParamReturnAttachTddTest).
        assertTrue(params.any { it.name.isEmpty() && it.optional })
        // Valid optional remains discoverable.
        val good = params.single { it.name == "good" }
        assertEquals(true, good.optional)
        assertEquals("number", good.typeText)
        assertEquals("ok", good.description)
        // name? without type still optional with stripped name.
        val nameOnly = params.single { it.name == "name" }
        assertEquals(true, nameOnly.optional)

        assertNotNull(attachment.comments)
        assertTrue(attachment.comments.isNotEmpty())
    }

    @Test
    fun repeatedSameNameOptionalParamsDoNotCollapse() {
        // EmmyLua overload-style repeated names with optional markers keep source order.
        val chunk = parse(
            """
            ---@param value string
            ---@param value? number
            ---@param value? boolean
            ---@return any
            local function overloadish(value)
                return value
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        val params = doc.tags.filterIsInstance<ParamTagSyntax>()
        assertEquals(3, params.size)
        assertTrue(params.all { it.name == "value" })
        assertEquals(listOf(false, true, true), params.map { it.optional })
        assertEquals(listOf("string", "number", "boolean"), params.map { it.typeText })
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun optionalParamDocsDoNotAttachToFollowingUnrelatedLocal() {
        val chunk = parse(
            """
            ---@param x? number
            ---@return number
            local function square(x)
                return x
            end

            local other = 1
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val other = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)

        val param = assertNotNull(index.getDocComment(function)).tags.filterIsInstance<ParamTagSyntax>().single()
        assertEquals("x", param.name)
        assertEquals(true, param.optional)
        assertNull(index.getAttachment(other))
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun requiredParamNeverFalsePositiveOptionalWithoutQuestionMark() {
        val chunk = parse(
            """
            ---@param name string required name
            ---@param count number
            local function f(name, count)
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val params = assertNotNull(index.getDocComment(function)).tags.filterIsInstance<ParamTagSyntax>()

        assertEquals(listOf("name", "count"), params.map { it.name })
        assertTrue(params.none { it.optional })
        assertTrue(params.none { it.vararg })
        assertEquals(0, index.orphanAttachments.size)
    }

    private fun parse(source: String) = luaParser.parse(source)
}
