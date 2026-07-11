package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
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
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * MemberResolver nested table field-chain corpus.
 *
 * Encodes conservative multi-hop table resolution (root.mid.leaf and deeper)
 * and colon-vs-dot (`preferMethod`) priority consistent with existing single-hop
 * rules: methods first under colon, fields first under dot. Intermediate hops
 * that fail short-circuit the chain; missing nested members report
 * [MemberFailureReason.MISSING_MEMBER]. Test-only; red is acceptable until
 * review-owned verification.
 */
class MemberResolverNestedTableTddTest {

    private val parser = LuaParser()

    // --- two-hop nested field chains ----------------------------------------

    @Test
    fun twoHopNestedFieldChainResolvesLeafPrimitive() {
        val harness = resolver()
        val root = nestedRoot(
            midFields = mapOf("leaf" to PrimitiveType.STRING)
        )

        val chain = harness.resolveChain(root, listOf("mid" to false, "leaf" to false))

        assertTrue(chain.all { it.isSuccess }, "chain steps: ${chain.map { it.failureReason }}")
        assertSame(PrimitiveType.STRING, chain.last().type)
        assertEquals(MemberAccessKind.FIELD, chain.last().accessKind)
        assertEquals(MemberAccessKind.FIELD, chain.first().accessKind)
    }

    @Test
    fun threeHopNestedFieldChainResolvesDeepLeaf() {
        val harness = resolver()
        val inner = TableType(fields = mapOf("value" to PrimitiveType.NUMBER))
        val mid = TableType(fields = mapOf("inner" to inner))
        val root = TableType(fields = mapOf("mid" to mid))

        val chain = harness.resolveChain(
            root,
            listOf("mid" to false, "inner" to false, "value" to false)
        )

        assertTrue(chain.all { it.isSuccess })
        assertSame(PrimitiveType.NUMBER, chain.last().type)
        assertEquals(MemberAccessKind.FIELD, chain.last().accessKind)
    }

    @Test
    fun fourHopNestedFieldChainResolvesDeepestLeaf() {
        val harness = resolver()
        val d = TableType(fields = mapOf("flag" to PrimitiveType.BOOLEAN))
        val c = TableType(fields = mapOf("d" to d))
        val b = TableType(fields = mapOf("c" to c))
        val a = TableType(fields = mapOf("b" to b))

        val chain = harness.resolveChain(
            a,
            listOf("b" to false, "c" to false, "d" to false, "flag" to false)
        )

        assertTrue(chain.all { it.isSuccess })
        assertSame(PrimitiveType.BOOLEAN, chain.last().type)
    }

    // --- missing / conservative intermediate failure ------------------------

    @Test
    fun missingIntermediateMemberFailsChainWithoutInventingLeaf() {
        val harness = resolver()
        val root = TableType(fields = mapOf("other" to PrimitiveType.STRING))

        val mid = harness.resolver.resolveMember(
            root, "mid", preferMethod = false, lexicalScopeId = harness.scopeId
        )

        assertNull(mid.type)
        assertEquals(MemberFailureReason.MISSING_MEMBER, mid.failureReason)
        // Conservative: no synthetic table is invented for further hops.
        assertTrue(!mid.isSuccess)
    }

    @Test
    fun presentIntermediateMissingLeafReportsMissingMember() {
        val harness = resolver()
        val root = nestedRoot(midFields = mapOf("known" to PrimitiveType.STRING))

        val chain = harness.resolveChain(root, listOf("mid" to false, "missing" to false))

        assertTrue(chain.first().isSuccess)
        assertEquals(MemberFailureReason.MISSING_MEMBER, chain.last().failureReason)
        assertNull(chain.last().type)
    }

    @Test
    fun partialDeepChainStopsAtFirstMissingHop() {
        val harness = resolver()
        val mid = TableType(fields = mapOf("inner" to TableType(fields = mapOf("ok" to PrimitiveType.NIL))))
        val root = TableType(fields = mapOf("mid" to mid))

        val midStep = harness.resolver.resolveMember(root, "mid", false, harness.scopeId)
        assertTrue(midStep.isSuccess)
        val gap = harness.resolver.resolveMember(
            requireNotNull(midStep.type), "gap", false, harness.scopeId
        )
        assertEquals(MemberFailureReason.MISSING_MEMBER, gap.failureReason)
        // Do not continue into "ok" through a failed intermediate.
        assertTrue(!gap.isSuccess)

        // resolveChain must also short-circuit: only mid + gap, never invents ok.
        val chain = harness.resolveChain(
            root,
            listOf("mid" to false, "gap" to false, "ok" to false)
        )
        assertEquals(2, chain.size)
        assertTrue(chain[0].isSuccess)
        assertEquals(MemberFailureReason.MISSING_MEMBER, chain[1].failureReason)
    }

    // --- colon vs dot (preferMethod) consistency on nested surfaces ---------

    @Test
    fun nestedMethodLeafPrefersMethodUnderColonAndFieldUnderDotWhenBothExist() {
        val harness = resolver()
        val methodType = FunctionType(returnType = PrimitiveType.BOOLEAN)
        val fieldType = FunctionType(returnType = PrimitiveType.STRING)
        val mid = TableType(
            fields = mapOf("run" to fieldType),
            methods = mapOf("run" to methodType)
        )
        val root = TableType(fields = mapOf("mid" to mid))

        val midType = requireNotNull(
            harness.resolver.resolveMember(root, "mid", preferMethod = false, lexicalScopeId = harness.scopeId).type
        )

        val colon = harness.resolver.resolveMember(
            midType, "run", preferMethod = true, lexicalScopeId = harness.scopeId
        )
        val dot = harness.resolver.resolveMember(
            midType, "run", preferMethod = false, lexicalScopeId = harness.scopeId
        )

        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertEquals(MemberAccessKind.FIELD, dot.accessKind)
        // Colon binds method surface; return types distinguish field vs method.
        assertSame(PrimitiveType.BOOLEAN, assertIs<FunctionType>(colon.type).returnType)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(dot.type).returnType)
    }

    @Test
    fun nestedMethodOnlyLeafResolvesUnderBothColonAndDot() {
        val harness = resolver()
        val methodType = FunctionType(returnType = PrimitiveType.NUMBER)
        val mid = TableType(methods = mapOf("push" to methodType))
        val root = TableType(fields = mapOf("mid" to mid))

        val midType = requireNotNull(
            harness.resolver.resolveMember(root, "mid", false, harness.scopeId).type
        )

        val colon = harness.resolver.resolveMember(midType, "push", true, harness.scopeId)
        val dot = harness.resolver.resolveMember(midType, "push", false, harness.scopeId)

        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertEquals(MemberAccessKind.METHOD, dot.accessKind)
        assertIs<FunctionType>(colon.type)
        assertIs<FunctionType>(dot.type)
    }

    @Test
    fun nestedCallableFieldOnlyLeafStaysFieldEvenUnderColon() {
        val harness = resolver()
        val callableField = FunctionType(returnType = PrimitiveType.STRING)
        val mid = TableType(fields = mapOf("run" to callableField))
        val root = TableType(fields = mapOf("mid" to mid))

        val midType = requireNotNull(
            harness.resolver.resolveMember(root, "mid", false, harness.scopeId).type
        )

        val colon = harness.resolver.resolveMember(midType, "run", true, harness.scopeId)
        val dot = harness.resolver.resolveMember(midType, "run", false, harness.scopeId)

        // No methods map entry → callable field remains FIELD under both indexers.
        assertEquals(MemberAccessKind.FIELD, colon.accessKind)
        assertEquals(MemberAccessKind.FIELD, dot.accessKind)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(colon.type).returnType)
    }

    @Test
    fun intermediateHopUsesFieldPriorityUnderDotEvenWhenMethodSharesName() {
        val harness = resolver()
        val fieldTable = TableType(fields = mapOf("leaf" to PrimitiveType.STRING))
        val methodTable = TableType(fields = mapOf("leaf" to PrimitiveType.NUMBER))
        val root = TableType(
            fields = mapOf("mid" to fieldTable),
            methods = mapOf("mid" to FunctionType(returnType = methodTable))
        )

        // Dot prefers field → nested table with string leaf.
        val midDot = harness.resolver.resolveMember(root, "mid", preferMethod = false, harness.scopeId)
        assertEquals(MemberAccessKind.FIELD, midDot.accessKind)
        val leafFromField = harness.resolver.resolveMember(
            requireNotNull(midDot.type), "leaf", false, harness.scopeId
        )
        assertSame(PrimitiveType.STRING, leafFromField.type)

        // Colon prefers method → function surface, not the nested table field.
        val midColon = harness.resolver.resolveMember(root, "mid", preferMethod = true, harness.scopeId)
        assertEquals(MemberAccessKind.METHOD, midColon.accessKind)
        assertIs<FunctionType>(midColon.type)
    }

    // --- method receiver binding on nested method leaves --------------------

    @Test
    fun nestedColonMethodBindsSelfReceiverFromIntermediateTable() {
        val harness = resolver()
        val methodType = FunctionType(
            parameters = emptyList(),
            returnType = PrimitiveType.NIL
        )
        val mid = TableType(methods = mapOf("touch" to methodType))
        val root = TableType(fields = mapOf("mid" to mid))

        val midType = requireNotNull(
            harness.resolver.resolveMember(root, "mid", false, harness.scopeId).type
        )
        val colon = harness.resolver.resolveMember(midType, "touch", true, harness.scopeId)
        val bound = assertIs<FunctionType>(colon.type)

        assertTrue(bound.parameters.isNotEmpty(), "colon method must bind self receiver")
        assertEquals("self", bound.parameters.first().name)
        assertSame(midType, bound.parameters.first().type)
    }

    @Test
    fun nestedMethodWithExistingCompatibleSelfKeepsDeclaredParameters() {
        val harness = resolver()
        // Compatible structural self type (subset of the receiver table).
        val selfShape = TableType(fields = mapOf("tag" to PrimitiveType.STRING))
        val methodType = FunctionType(
            parameters = listOf(FunctionParameter("self", selfShape)),
            returnType = PrimitiveType.BOOLEAN
        )
        val midWithMethod = TableType(
            fields = mapOf("tag" to PrimitiveType.STRING),
            methods = mapOf("ok" to methodType)
        )
        val root = TableType(fields = mapOf("mid" to midWithMethod))

        val midType = requireNotNull(
            harness.resolver.resolveMember(root, "mid", false, harness.scopeId).type
        )
        val colon = harness.resolver.resolveMember(midType, "ok", true, harness.scopeId)
        val bound = assertIs<FunctionType>(colon.type)

        // Compatible self already present → no extra self prepended.
        assertEquals(1, bound.parameters.size)
        assertEquals("self", bound.parameters.single().name)
        assertSame(PrimitiveType.BOOLEAN, bound.returnType)
    }

    // --- union / unknown intermediate conservatism --------------------------

    @Test
    fun unionIntermediateRequiresMemberOnEveryBranchBeforeLeafHop() {
        val harness = resolver()
        val branchA = TableType(fields = mapOf("leaf" to PrimitiveType.STRING))
        val branchB = TableType(fields = mapOf("leaf" to PrimitiveType.NUMBER))
        val midUnion = UnionType(linkedSetOf(branchA, branchB))
        val root = TableType(fields = mapOf("mid" to midUnion))

        val mid = harness.resolver.resolveMember(root, "mid", false, harness.scopeId)
        assertTrue(mid.isSuccess)
        val leaf = harness.resolver.resolveMember(
            requireNotNull(mid.type), "leaf", false, harness.scopeId
        )

        assertTrue(leaf.isSuccess)
        val unionLeaf = assertIs<UnionType>(leaf.type)
        assertEquals(setOf(PrimitiveType.STRING, PrimitiveType.NUMBER), unionLeaf.types)
    }

    @Test
    fun unionIntermediateMissingOnOneBranchFailsLeafConservatively() {
        val harness = resolver()
        val branchA = TableType(fields = mapOf("leaf" to PrimitiveType.STRING))
        val branchB = TableType(fields = emptyMap())
        val midUnion = UnionType(linkedSetOf(branchA, branchB))
        val root = TableType(fields = mapOf("mid" to midUnion))

        val mid = requireNotNull(
            harness.resolver.resolveMember(root, "mid", false, harness.scopeId).type
        )
        val leaf = harness.resolver.resolveMember(mid, "leaf", false, harness.scopeId)

        assertEquals(MemberFailureReason.MISSING_MEMBER, leaf.failureReason)
        assertNull(leaf.type)
    }

    @Test
    fun unknownIntermediateDoesNotInventNestedMembers() {
        val harness = resolver()
        val root = TableType(fields = mapOf("mid" to UnknownType))

        val mid = harness.resolver.resolveMember(root, "mid", false, harness.scopeId)
        assertSame(UnknownType, mid.type)

        val leaf = harness.resolver.resolveMember(
            requireNotNull(mid.type), "leaf", false, harness.scopeId
        )
        // Unknown is not a table surface → unsupported / non-success for members.
        assertTrue(!leaf.isSuccess)
        assertEquals(MemberFailureReason.UNSUPPORTED_BASE_TYPE, leaf.failureReason)
    }

    @Test
    fun primitiveIntermediateDoesNotAcceptNestedFieldAccess() {
        val harness = resolver()
        val root = TableType(fields = mapOf("mid" to PrimitiveType.STRING))

        val mid = harness.resolver.resolveMember(root, "mid", false, harness.scopeId)
        assertSame(PrimitiveType.STRING, mid.type)

        val leaf = harness.resolver.resolveMember(
            requireNotNull(mid.type), "len", false, harness.scopeId
        )
        assertEquals(MemberFailureReason.UNSUPPORTED_BASE_TYPE, leaf.failureReason)
    }

    // --- empty nested tables ------------------------------------------------

    @Test
    fun emptyNestedTableYieldsMissingMemberOnAnyLeaf() {
        val harness = resolver()
        val root = TableType(fields = mapOf("mid" to TableType()))

        val mid = requireNotNull(
            harness.resolver.resolveMember(root, "mid", false, harness.scopeId).type
        )
        val leaf = harness.resolver.resolveMember(mid, "anything", false, harness.scopeId)

        assertEquals(MemberFailureReason.MISSING_MEMBER, leaf.failureReason)
    }

    // --- chain of nested methods as fields returning tables -----------------

    @Test
    fun nestedFieldHoldingTableThenMethodLeafUsesColonRules() {
        val harness = resolver()
        val methodType = FunctionType(
            parameters = listOf(FunctionParameter("x", PrimitiveType.NUMBER)),
            returnType = PrimitiveType.STRING
        )
        val leafTable = TableType(methods = mapOf("fmt" to methodType))
        val root = TableType(fields = mapOf("cfg" to leafTable))

        val cfg = requireNotNull(
            harness.resolver.resolveMember(root, "cfg", false, harness.scopeId).type
        )
        val colon = harness.resolver.resolveMember(cfg, "fmt", true, harness.scopeId)
        val dot = harness.resolver.resolveMember(cfg, "fmt", false, harness.scopeId)

        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertEquals(MemberAccessKind.METHOD, dot.accessKind)
        val bound = assertIs<FunctionType>(colon.type)
        // Self prepended when declared first param is not compatible with receiver table.
        assertTrue(bound.parameters.size >= 2)
        assertEquals("self", bound.parameters.first().name)
        assertSame(PrimitiveType.STRING, bound.returnType)
    }

    @Test
    fun siblingNestedFieldsDoNotLeakAcrossBranches() {
        val harness = resolver()
        val left = TableType(fields = mapOf("a" to PrimitiveType.STRING))
        val right = TableType(fields = mapOf("b" to PrimitiveType.NUMBER))
        val root = TableType(fields = mapOf("left" to left, "right" to right))

        val leftType = requireNotNull(
            harness.resolver.resolveMember(root, "left", false, harness.scopeId).type
        )
        val rightType = requireNotNull(
            harness.resolver.resolveMember(root, "right", false, harness.scopeId).type
        )

        val leakFromLeft = harness.resolver.resolveMember(leftType, "b", false, harness.scopeId)
        val leakFromRight = harness.resolver.resolveMember(rightType, "a", false, harness.scopeId)
        val okLeft = harness.resolver.resolveMember(leftType, "a", false, harness.scopeId)
        val okRight = harness.resolver.resolveMember(rightType, "b", false, harness.scopeId)

        assertEquals(MemberFailureReason.MISSING_MEMBER, leakFromLeft.failureReason)
        assertEquals(MemberFailureReason.MISSING_MEMBER, leakFromRight.failureReason)
        assertSame(PrimitiveType.STRING, okLeft.type)
        assertSame(PrimitiveType.NUMBER, okRight.type)
    }

    // --- helpers ------------------------------------------------------------

    private fun nestedRoot(midFields: Map<String, Type>): TableType {
        return TableType(fields = mapOf("mid" to TableType(fields = midFields)))
    }

    private fun resolver(source: String = ""): Harness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            resolver = MemberResolver(resolved),
            declarations = resolved,
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private data class Harness(
        val resolver: MemberResolver,
        val declarations: BinderPassResult,
        val scopeId: ScopeId
    ) {
        /**
         * Resolve a multi-hop chain. Each pair is (memberName, preferMethod).
         * Stops early on failure so later hops are not invented.
         */
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
}
