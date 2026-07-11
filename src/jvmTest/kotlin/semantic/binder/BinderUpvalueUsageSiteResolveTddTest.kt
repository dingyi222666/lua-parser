package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.model.ApiAdapters
import io.github.dingyi222666.luaparser.semantic.model.ReferenceQueries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Binder upvalue *usage-site* resolution corpus via [ReferenceQueries]
 * (TASK-391).
 *
 * Complements TASK-328 ([BinderUpvalueCaptureRangeTddTest]), which pins
 * declaration-site ranges / scope ownership. This suite asserts that nested
 * reads resolve through
 * [ReferenceQueries.findNearestVisibleValueDeclaration] to the correct outer
 * local / parameter declarations (single-AST bind; no double-parse identity
 * bugs).
 *
 * Fixture constraints (shared with sibling binder corpora):
 * - Avoid multi-identifier returns such as `return left, right` — the current
 *   parser rejects those with IllegalStateException near eof. Use single
 *   returns / body locals that *read* each outer name instead.
 *
 * Important: binder scopeGraph / declaration anchors are keyed by AST identity.
 * Always bind the same [ChunkNode] instance used for assertions (no double-parse).
 * Use [bindChunk] so parse + bind share one AST.
 */
class BinderUpvalueUsageSiteResolveTddTest {

    @Test
    fun nestedFunctionReadingOuterLocal_usageResolvesToOuterDeclaration() {
        val source = """
            local outer = 1
            local function inner()
                return outer
            end
            """.trimIndent()
        val (_, result, queries) = bindChunk(source)

        val outer = localOf(result, "outer")
        // occurrence 1 = declaration; occurrence 2 = nested read
        val usagePos = positionOf(source, "outer", occurrence = 2)

        val resolved = queries.findNearestVisibleValueDeclaration("outer", usagePos)
        assertNotNull(resolved, "usage of outer must resolve via ReferenceQueries")
        assertSame(outer, resolved)
        assertEquals(outer.id, resolved.id)
        assertEquals(outer.symbolId, resolved.symbolId)
        assertEquals(DeclarationKind.LOCAL, resolved.kind)
        assertEquals(outer.range, resolved.range)
    }

    @Test
    fun deeplyNestedCapture_usageResolvesToOutermostLocal() {
        val source = """
            local captured = 42
            local function level1()
                local function level2()
                    local function level3()
                        return captured
                    end
                    return level3
                end
                return level2
            end
            """.trimIndent()
        val (_, result, queries) = bindChunk(source)

        val captured = localOf(result, "captured")
        val usagePos = positionOf(source, "captured", occurrence = 2)

        val resolved = queries.findNearestVisibleValueDeclaration("captured", usagePos)
        assertNotNull(resolved)
        assertSame(captured, resolved)
        assertEquals(1, nonBuiltinLocals(result).count { it.name == "captured" })
    }

    @Test
    fun multiOuterLocalsCapturedByNestedFunction_eachUsageResolvesSeparately() {
        // Avoid multi-identifier return (`return left, right` currently fails parse).
        val source = """
            local left, right = 1, 2
            local function pack()
                local usedLeft = left
                local usedRight = right
                return usedLeft
            end
            """.trimIndent()
        val (_, result, queries) = bindChunk(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")

        // "left" occurrences: decl (1), read in usedLeft init (2)
        // "right" occurrences: decl (1), read in usedRight init (2)
        val leftUsage = positionOf(source, "left", occurrence = 2)
        val rightUsage = positionOf(source, "right", occurrence = 2)

        val resolvedLeft = queries.findNearestVisibleValueDeclaration("left", leftUsage)
        val resolvedRight = queries.findNearestVisibleValueDeclaration("right", rightUsage)

        assertNotNull(resolvedLeft)
        assertNotNull(resolvedRight)
        assertSame(left, resolvedLeft)
        assertSame(right, resolvedRight)
        assertNotEquals(left.symbolId, right.symbolId)
        assertEquals(1, nonBuiltinLocals(result).count { it.name == "left" })
        assertEquals(1, nonBuiltinLocals(result).count { it.name == "right" })
    }

    @Test
    fun shadowedOuterName_nestedUsageResolvesToInnermostLocalNotOuter() {
        val source = """
            local value = 1
            local function outer()
                local value = 2
                local function inner()
                    return value
                end
                return inner
            end
            """.trimIndent()
        val (_, result, queries) = bindChunk(source)

        val values = nonBuiltinLocals(result).filter { it.name == "value" }
        assertEquals(2, values.size, "outer and shadowed inner value locals")

        val outerDecl = values.single { it.range!!.start == positionOf(source, "value", occurrence = 1) }
        val innerDecl = values.single { it.range!!.start == positionOf(source, "value", occurrence = 2) }
        val usagePos = positionOf(source, "value", occurrence = 3)

        val resolved = queries.findNearestVisibleValueDeclaration("value", usagePos)
        assertNotNull(resolved)
        assertSame(innerDecl, resolved)
        assertTrue(resolved !== outerDecl)
        assertNotEquals(outerDecl.id, resolved.id)
    }

    @Test
    fun parameterAsUpvalueFromNestedFunction_usageResolvesToParameter() {
        val source = """
            local function outer(param)
                local function inner()
                    return param
                end
                return inner
            end
            """.trimIndent()
        val (_, result, queries) = bindChunk(source)

        val param = result.declarationIndex.declarations.single {
            it.name == "param" &&
                it.kind == DeclarationKind.PARAMETER &&
                it.origin != DeclarationOrigin.BUILTIN
        }
        // occurrence 1 = parameter site; occurrence 2 = nested read
        val usagePos = positionOf(source, "param", occurrence = 2)

        val resolved = queries.findNearestVisibleValueDeclaration("param", usagePos)
        assertNotNull(resolved)
        assertSame(param, resolved)
        assertEquals(DeclarationKind.PARAMETER, resolved.kind)
        assertEquals(param.range, resolved.range)
    }

    @Test
    fun anonymousNestedFunction_usageResolvesToOuterLocal() {
        val source = """
            local outer = 0
            local callback = function()
                return outer
            end
            """.trimIndent()
        val (_, result, queries) = bindChunk(source)

        val outer = localOf(result, "outer")
        val usagePos = positionOf(source, "outer", occurrence = 2)

        val resolved = queries.findNearestVisibleValueDeclaration("outer", usagePos)
        assertNotNull(resolved)
        assertSame(outer, resolved)
    }

    @Test
    fun doBlockLocalCapturedByNestedFunction_usageResolvesToBlockLocal() {
        val source = """
            do
                local only = 7
                local function reader()
                    return only
                end
            end
            """.trimIndent()
        val (_, result, queries) = bindChunk(source)

        val only = localOf(result, "only")
        val usagePos = positionOf(source, "only", occurrence = 2)

        val resolved = queries.findNearestVisibleValueDeclaration("only", usagePos)
        assertNotNull(resolved)
        assertSame(only, resolved)
        assertEquals("only".length, only.range!!.end.column - only.range!!.start.column)
    }

    @Test
    fun singleAstContract_sameChunkBindAndQueryShareDeclarationIdentity() {
        val source = """
            local shared = 9
            local function f()
                return shared
            end
            """.trimIndent()
        val (chunk, result, queries) = bindChunk(source)

        // Re-using the bound result (not re-parsing) is the only identity-safe path.
        assertSame(chunk.body, result.scopeGraph.rootScope.ownerNode)

        val shared = localOf(result, "shared")
        val usagePos = positionOf(source, "shared", occurrence = 2)
        val resolved = queries.findNearestVisibleValueDeclaration("shared", usagePos)
        assertNotNull(resolved)
        assertSame(shared, resolved)
        assertSame(shared.anchorNode, resolved.anchorNode)
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Parse once and bind that same [ChunkNode]. Returns chunk + binder +
     * [ReferenceQueries] built from the same binder result (single-AST contract).
     */
    private fun bindChunk(source: String): Triple<ChunkNode, BinderPassResult, ReferenceQueries> {
        val chunk = LuaParser().parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        assertSame(
            chunk.body,
            result.scopeGraph.rootScope.ownerNode,
            "BinderPass root scope ownerNode must be the bound chunk.body (single-AST contract)"
        )
        // ReferenceQueries is internal; jvmTest shares the module and can construct it
        // the same way SemanticModelBuilder does for declaration visibility queries.
        val queries = ReferenceQueries(
            binder = result,
            evaluator = ExpressionTypeEvaluator(result),
            memberResolver = MemberResolver(result),
            adapters = ApiAdapters(result)
        )
        return Triple(chunk, result, queries)
    }

    private fun nonBuiltinLocals(result: BinderPassResult): List<BinderDeclaration> {
        return result.declarationIndex.declarations.filter {
            it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN
        }
    }

    private fun localOf(result: BinderPassResult, name: String): BinderDeclaration {
        return nonBuiltinLocals(result).single { it.name == name }
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var fromIndex = 0
        var found = 0
        val requireWordBoundary = needle.all { isIdentChar(it) }
        while (true) {
            val index = source.indexOf(needle, fromIndex)
            require(index >= 0) { "Missing '$needle' occurrence $occurrence in:\n$source" }
            val match = if (!requireWordBoundary) {
                true
            } else {
                val beforeOk = index == 0 || !isIdentChar(source[index - 1])
                val afterIndex = index + needle.length
                val afterOk = afterIndex >= source.length || !isIdentChar(source[afterIndex])
                beforeOk && afterOk
            }
            if (match) {
                found++
                if (found == occurrence) {
                    var line = 1
                    var column = 1
                    for (i in 0 until index) {
                        if (source[i] == '\n') {
                            line++
                            column = 1
                        } else {
                            column++
                        }
                    }
                    return Position(line, column)
                }
            }
            fromIndex = index + 1
        }
    }

    private fun isIdentChar(ch: Char): Boolean = ch == '_' || ch.isLetterOrDigit()
}
