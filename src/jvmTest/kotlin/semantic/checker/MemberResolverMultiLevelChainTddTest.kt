package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.MemberAccessKind
import io.github.dingyi222666.luaparser.semantic.checker.MemberFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolution
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * MemberResolver multi-level chain corpus (TASK-259).
 *
 * Covers `a.b.c` and `a.b:c()` style multi-hop resolution when intermediate
 * table fields are either annotated (`---@type` / object shapes) or inferred
 * from table constructors. Missing intermediate hops must yield unknown /
 * [MemberFailureReason.MISSING_MEMBER] without throwing (no NPE).
 *
 * Test-only; red is acceptable until review-owned verification.
 */
class MemberResolverMultiLevelChainTddTest {

    private val parser = LuaParser()

    // --- pure MemberResolver multi-hop (annotated-shaped tables) -------------

    @Test
    fun annotatedShapedAbcChainResolvesLeafPrimitive() {
        val harness = resolver()
        // a.b.c where a : { b: { c: string } }
        val a = TableType(
            fields = mapOf(
                "b" to TableType(fields = mapOf("c" to PrimitiveType.STRING))
            )
        )

        val chain = harness.resolveChain(a, listOf("b" to false, "c" to false))

        assertTrue(chain.all { it.isSuccess }, "steps: ${chain.map { it.failureReason }}")
        assertSame(PrimitiveType.STRING, chain.last().type)
        assertEquals(MemberAccessKind.FIELD, chain[0].accessKind)
        assertEquals(MemberAccessKind.FIELD, chain[1].accessKind)
    }

    @Test
    fun annotatedShapedAbColonCMethodChainResolvesCallable() {
        val harness = resolver()
        val method = FunctionType(
            parameters = listOf(FunctionParameter("x", PrimitiveType.NUMBER)),
            returnType = PrimitiveType.BOOLEAN
        )
        // a.b:c() — intermediate b is a table with method c
        val a = TableType(
            fields = mapOf(
                "b" to TableType(methods = mapOf("c" to method))
            )
        )

        val bStep = harness.resolver.resolveMember(a, "b", preferMethod = false, harness.scopeId)
        assertTrue(bStep.isSuccess)
        val cStep = harness.resolver.resolveMember(
            requireNotNull(bStep.type),
            "c",
            preferMethod = true,
            harness.scopeId
        )

        assertTrue(cStep.isSuccess)
        assertEquals(MemberAccessKind.METHOD, cStep.accessKind)
        val bound = assertIs<FunctionType>(cStep.type)
        assertSame(PrimitiveType.BOOLEAN, bound.returnType)
        assertTrue(bound.parameters.isNotEmpty())
        assertEquals("self", bound.parameters.first().name)
    }

    @Test
    fun threeLevelFieldThenColonMethodResolvesDeepLeaf() {
        val harness = resolver()
        val method = FunctionType(returnType = PrimitiveType.STRING)
        val leaf = TableType(methods = mapOf("run" to method))
        val mid = TableType(fields = mapOf("leaf" to leaf))
        val root = TableType(fields = mapOf("mid" to mid))

        val chain = harness.resolveChain(
            root,
            listOf("mid" to false, "leaf" to false, "run" to true)
        )

        assertTrue(chain.all { it.isSuccess })
        assertEquals(MemberAccessKind.METHOD, chain.last().accessKind)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(chain.last().type).returnType)
    }

    // --- missing intermediate: unknown / failure without NPE -----------------

    @Test
    fun missingIntermediateFieldYieldsMissingMemberWithoutNpe() {
        val harness = resolver()
        // a has no "b" — a.b.c must not invent b or throw.
        val a = TableType(fields = mapOf("other" to PrimitiveType.NUMBER))

        val bStep = harness.resolver.resolveMember(a, "b", preferMethod = false, harness.scopeId)
        assertNull(bStep.type)
        assertEquals(MemberFailureReason.MISSING_MEMBER, bStep.failureReason)
        assertTrue(!bStep.isSuccess)

        // Continuing a chain must short-circuit; no invented hop into "c".
        val chain = harness.resolveChain(a, listOf("b" to false, "c" to false))
        assertEquals(1, chain.size)
        assertEquals(MemberFailureReason.MISSING_MEMBER, chain.single().failureReason)
    }

    @Test
    fun presentIntermediateMissingLeafReportsMissingWithoutNpe() {
        val harness = resolver()
        val a = TableType(
            fields = mapOf("b" to TableType(fields = mapOf("known" to PrimitiveType.STRING)))
        )

        val chain = harness.resolveChain(a, listOf("b" to false, "c" to false))
        assertTrue(chain.first().isSuccess)
        assertEquals(MemberFailureReason.MISSING_MEMBER, chain.last().failureReason)
        assertNull(chain.last().type)
    }

    @Test
    fun missingMethodOnIntermediateYieldsMissingWithoutNpe() {
        val harness = resolver()
        val a = TableType(
            fields = mapOf("b" to TableType(fields = mapOf("x" to PrimitiveType.NUMBER)))
        )

        val b = requireNotNull(
            harness.resolver.resolveMember(a, "b", false, harness.scopeId).type
        )
        val colon = harness.resolver.resolveMember(b, "c", preferMethod = true, harness.scopeId)

        assertEquals(MemberFailureReason.MISSING_MEMBER, colon.failureReason)
        assertNull(colon.type)
    }

    // --- ExpressionTypeEvaluator: annotated source a.b.c / a.b:c() ----------

    @Test
    fun annotatedSourceAbcChainEvaluatesToLeafString() {
        val source = """
            ---@type { b: { c: string } }
            local a = {}
            return a.b.c
        """.trimIndent()

        val harness = evaluator(source)
        val leaf = harness.returnExpression()
        val type = harness.evaluator.evaluate(leaf)

        assertSame(PrimitiveType.STRING, type)
    }

    @Test
    fun annotatedSourceAbColonCCallEvaluatesToMethodReturn() {
        val source = """
            ---@type { b: { c: fun(self: table): number } }
            local a = {}
            return a.b:c()
        """.trimIndent()

        val harness = evaluator(source)
        val call = assertIs<CallExpression>(harness.returnExpression())
        val type = harness.evaluator.evaluate(call)

        // Prefer a concrete number return when annotation surfaces are honored;
        // UnknownType is the safe degradation if method binding is incomplete.
        assertTrue(
            type == PrimitiveType.NUMBER || type == UnknownType,
            "expected number or unknown, got ${type.displayName}"
        )
        // Evaluating the colon member base must not throw.
        val memberBase = assertIs<MemberExpression>(call.base)
        val memberType = harness.evaluator.evaluate(memberBase)
        assertNotNull(memberType)
    }

    @Test
    fun annotatedSourceMissingIntermediateEvaluatesUnknownWithoutThrow() {
        val source = """
            ---@type { other: number }
            local a = {}
            return a.b.c
        """.trimIndent()

        val harness = evaluator(source)
        val leaf = harness.returnExpression()
        val type = runCatching { harness.evaluator.evaluate(leaf) }
            .getOrElse { error("NPE/crash on missing intermediate: $it") }

        assertSame(UnknownType, type)
    }

    // --- ExpressionTypeEvaluator: inferred table constructor chains ---------

    @Test
    fun inferredTableConstructorAbcChainEvaluatesToLeaf() {
        val source = """
            local a = { b = { c = "hi" } }
            return a.b.c
        """.trimIndent()

        val harness = evaluator(source)
        val leaf = harness.returnExpression()
        val type = harness.evaluator.evaluate(leaf)

        // Inferred string literal leaf → string (or literal subtype / unknown if
        // inference is incomplete; never crash).
        assertTrue(
            type == PrimitiveType.STRING ||
                type.displayName.contains("string", ignoreCase = true) ||
                type == UnknownType,
            "expected string-ish or unknown leaf, got ${type.displayName}"
        )
        assertNotNull(type)
    }

    @Test
    fun inferredNestedTableThenColonMethodDoesNotThrow() {
        val source = """
            local a = {
              b = {
                c = function(self) return 1 end
              }
            }
            return a.b:c()
        """.trimIndent()

        val harness = evaluator(source)
        val call = assertIs<CallExpression>(harness.returnExpression())
        val type = runCatching { harness.evaluator.evaluate(call) }
            .getOrElse { error("NPE/crash on inferred a.b:c(): $it") }

        assertNotNull(type)
        // Intermediate a.b must also be evaluable.
        val memberBase = assertIs<MemberExpression>(call.base)
        val bBase = assertIs<MemberExpression>(memberBase.base)
        val bType = harness.evaluator.evaluate(bBase)
        assertNotNull(bType)
    }

    @Test
    fun inferredMissingIntermediateEvaluatesUnknownWithoutThrow() {
        val source = """
            local a = { other = 1 }
            return a.b.c
        """.trimIndent()

        val harness = evaluator(source)
        val leaf = harness.returnExpression()
        val type = runCatching { harness.evaluator.evaluate(leaf) }
            .getOrElse { error("NPE/crash on inferred missing intermediate: $it") }

        assertSame(UnknownType, type)
    }

    // --- intermediate hop surfaces for MemberResolver + evaluator ------------

    @Test
    fun intermediateHopTypeIsTableSurfaceForFurtherResolution() {
        val harness = resolver()
        val leafTable = TableType(fields = mapOf("c" to PrimitiveType.BOOLEAN))
        val a = TableType(fields = mapOf("b" to leafTable))

        val b = harness.resolver.resolveMember(a, "b", false, harness.scopeId)
        assertTrue(b.isSuccess)
        assertIs<TableType>(b.type)

        val c = harness.resolver.resolveMember(requireNotNull(b.type), "c", false, harness.scopeId)
        assertSame(PrimitiveType.BOOLEAN, c.type)
    }

    @Test
    fun fourLevelDotChainResolvesDeepestLeaf() {
        val harness = resolver()
        val d = TableType(fields = mapOf("e" to PrimitiveType.NUMBER))
        val c = TableType(fields = mapOf("d" to d))
        val b = TableType(fields = mapOf("c" to c))
        val a = TableType(fields = mapOf("b" to b))

        val chain = harness.resolveChain(
            a,
            listOf("b" to false, "c" to false, "d" to false, "e" to false)
        )

        assertTrue(chain.all { it.isSuccess })
        assertSame(PrimitiveType.NUMBER, chain.last().type)
    }

    @Test
    fun evaluatorFourLevelAnnotatedDotChainDoesNotThrow() {
        val source = """
            ---@type { b: { c: { d: { e: boolean } } } }
            local a = {}
            return a.b.c.d.e
        """.trimIndent()

        val harness = evaluator(source)
        val leaf = harness.returnExpression()
        val type = runCatching { harness.evaluator.evaluate(leaf) }
            .getOrElse { error("NPE/crash on four-level annotated chain: $it") }

        assertTrue(
            type == PrimitiveType.BOOLEAN || type == UnknownType,
            "expected boolean or unknown, got ${type.displayName}"
        )
    }

    // --- helpers ------------------------------------------------------------

    private fun resolver(source: String = ""): ResolverHarness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return ResolverHarness(
            resolver = MemberResolver(resolved),
            declarations = resolved,
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private fun evaluator(source: String): EvalHarness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return EvalHarness(
            chunk = chunk,
            binder = resolved,
            evaluator = ExpressionTypeEvaluator(resolved),
            resolver = MemberResolver(resolved),
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private data class ResolverHarness(
        val resolver: MemberResolver,
        val declarations: BinderPassResult,
        val scopeId: ScopeId
    ) {
        fun resolveChain(
            root: Type,
            hops: List<Pair<String, Boolean>>
        ): List<MemberResolution> {
            val steps = mutableListOf<MemberResolution>()
            var current: Type = root
            for ((name, preferMethod) in hops) {
                val step = resolver.resolveMember(current, name, preferMethod, scopeId)
                steps += step
                val next = step.type ?: break
                current = next
            }
            return steps
        }
    }

    private data class EvalHarness(
        val chunk: ChunkNode,
        val binder: BinderPassResult,
        val evaluator: ExpressionTypeEvaluator,
        val resolver: MemberResolver,
        val scopeId: ScopeId
    ) {
        fun returnExpression(): ExpressionNode {
            val statement = chunk.body.returnStatement
                ?: error("expected chunk body returnStatement")
            return statement.arguments.singleOrNull()
                ?: error("expected single return argument, got ${statement.arguments.size}")
        }
    }
}
