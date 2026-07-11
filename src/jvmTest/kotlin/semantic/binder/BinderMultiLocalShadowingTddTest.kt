package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeGraph
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Binder multi-local shadowing corpus (TASK-484).
 *
 * Complements:
 * - [BinderMultiLocalSameLineRangeTddTest] (TASK-285) dense same-line packing
 * - [BinderMultiLocalExtraShapeTddTest] (TASK-361) RHS shapes / owners / light shadowing
 * - [BinderMultiLocalLineBreakTddTest] (TASK-392) multi-line multi-local parse/bind
 * - [ScopeGraphParentChainTddTest] (TASK-234) parent-chain queries for single locals
 *
 * Focus (multi-local + shadowing only):
 * - Nested `do` / function / if / while / for scopes shadow multi-local outer names
 * - Same-block redeclare of multi-local names: later bindings win on the scope chain
 * - Sibling blocks do not leak multi-local shadows across parent chains
 * - Partial multi-local shadow (one name only) leaves the unshadowed sibling outer
 * - Position queries hit the correct declaration site; usage sites are not anchors
 * - AST quirk: [LocalStatement.init] = names / LHS, [LocalStatement.variables] = RHS
 * - Per-name ranges stay pairwise disjoint across outer and shadowing multi-locals
 *
 * Test-only; production defects surface as assertion failures (review-owned
 * verification via `jvmTest --tests semantic.binder.BinderMultiLocalShadowingTddTest`).
 *
 * Host android.jar policy: SDK
 * `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` only; never G:/.
 */
class BinderMultiLocalShadowingTddTest {

    private val parser = LuaParser()

    // --- nested do: multi-local shadows multi-local --------------------------------

    @Test
    fun nestedDo_sameLineMultiLocalShadowsOuterMultiLocalWithDistinctSymbols() {
        val source = """
            local left, right = 1, 2
            do
                local left, right = 3, 4
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        val lefts = localsNamed(result, "left")
        val rights = localsNamed(result, "right")
        assertEquals(2, lefts.size)
        assertEquals(2, rights.size)

        val outerLeft = lefts[0]
        val innerLeft = lefts[1]
        val outerRight = rights[0]
        val innerRight = rights[1]

        assertNotEquals(outerLeft.id, innerLeft.id)
        assertNotEquals(outerRight.id, innerRight.id)
        assertNotEquals(outerLeft.symbolId, innerLeft.symbolId)
        assertNotEquals(outerRight.symbolId, innerRight.symbolId)

        assertEquals(DeclarationOwner.Lexical(chunk.body), outerLeft.owner)
        assertEquals(DeclarationOwner.Lexical(chunk.body), outerRight.owner)
        val doBody = chunk.body.statements.filterIsInstance<DoStatement>().single().body
        assertEquals(DeclarationOwner.Lexical(doBody), innerLeft.owner)
        assertEquals(DeclarationOwner.Lexical(doBody), innerRight.owner)

        // Co-declared multi-locals share owner + stay pairwise disjoint.
        assertEquals(outerLeft.owner, outerRight.owner)
        assertEquals(innerLeft.owner, innerRight.owner)
        assertRangesDisjoint(outerLeft.range!!, outerRight.range!!)
        assertRangesDisjoint(innerLeft.range!!, innerRight.range!!)
        assertRangesDisjoint(outerLeft.range!!, innerLeft.range!!)
        assertRangesDisjoint(outerRight.range!!, innerRight.range!!)

        assertEquals(outerLeft, result.positionQueries.getDeclarationAt(positionOf(source, "left", 1)))
        assertEquals(innerLeft, result.positionQueries.getDeclarationAt(positionOf(source, "left", 2)))
        assertEquals(outerRight, result.positionQueries.getDeclarationAt(positionOf(source, "right", 1)))
        assertEquals(innerRight, result.positionQueries.getDeclarationAt(positionOf(source, "right", 2)))
    }

    @Test
    fun nestedDo_multiLocalShadowResolvesInnermostAlongParentChain() {
        val source = """
            local a, b = 1, 2
            do
                local a, b = 3, 4
                local pickA, pickB = a, b
            end
            """.trimIndent()
        val result = bind(source)

        val outerA = localsNamed(result, "a")[0]
        val innerA = localsNamed(result, "a")[1]
        val outerB = localsNamed(result, "b")[0]
        val innerB = localsNamed(result, "b")[1]
        val pickA = localOf(result, "pickA")
        val pickB = localOf(result, "pickB")

        val pickScope = assertNotNull(result.scopeGraph.getDeclarationScope(pickA.id))
        assertEquals(innerA.id, resolveNameAlongParentChain(result, pickScope.id, "a")?.id)
        assertEquals(innerB.id, resolveNameAlongParentChain(result, pickScope.id, "b")?.id)
        assertNotEquals(outerA.id, resolveNameAlongParentChain(result, pickScope.id, "a")?.id)
        assertNotEquals(outerB.id, resolveNameAlongParentChain(result, pickScope.id, "b")?.id)

        // Usage of a/b on the pick RHS are not declaration anchors.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "a", occurrence = 3)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "b", occurrence = 3)))
    }

    @Test
    fun tripleNestedMultiLocalShadow_eachLevelDistinctAndInnermostWins() {
        val source = """
            local x, y = 1, 2
            do
                local x, y = 3, 4
                do
                    local x, y = 5, 6
                    local chosenX, chosenY = x, y
                end
            end
            """.trimIndent()
        val result = bind(source)

        val xs = localsNamed(result, "x")
        val ys = localsNamed(result, "y")
        assertEquals(3, xs.size)
        assertEquals(3, ys.size)
        assertEquals(3, xs.map { it.symbolId }.toSet().size)
        assertEquals(3, ys.map { it.symbolId }.toSet().size)

        val chosenScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "chosenX").id)
        )
        assertEquals(xs[2].id, resolveNameAlongParentChain(result, chosenScope.id, "x")?.id)
        assertEquals(ys[2].id, resolveNameAlongParentChain(result, chosenScope.id, "y")?.id)

        // Middle scope must not see deepest bindings.
        val middleScope = assertNotNull(result.scopeGraph.getDeclarationScope(xs[1].id))
        assertEquals(xs[1].id, resolveNameAlongParentChain(result, middleScope.id, "x")?.id)
        assertEquals(ys[1].id, resolveNameAlongParentChain(result, middleScope.id, "y")?.id)

        // Root still binds the outermost pair.
        val rootScope = result.scopeGraph.rootScope
        assertEquals(xs[0].id, resolveNameAlongParentChain(result, rootScope.id, "x")?.id)
        assertEquals(ys[0].id, resolveNameAlongParentChain(result, rootScope.id, "y")?.id)
    }

    // --- partial multi-local shadow ------------------------------------------------

    @Test
    fun nestedDo_partialMultiLocalShadow_onlyShadowsNamedSlots() {
        // Outer multi-local; inner multi-local redeclares only `left` (+ a new name).
        val source = """
            local left, right = 1, 2
            do
                local left, other = 3, 4
                local useLeft, useRight, useOther = left, right, other
            end
            """.trimIndent()
        val result = bind(source)

        val lefts = localsNamed(result, "left")
        assertEquals(2, lefts.size)
        assertEquals(1, localsNamed(result, "right").size)
        assertEquals(1, localsNamed(result, "other").size)

        val outerLeft = lefts[0]
        val innerLeft = lefts[1]
        val outerRight = localOf(result, "right")
        val other = localOf(result, "other")
        val useScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "useLeft").id)
        )

        assertEquals(innerLeft.id, resolveNameAlongParentChain(result, useScope.id, "left")?.id)
        assertEquals(outerRight.id, resolveNameAlongParentChain(result, useScope.id, "right")?.id)
        assertEquals(other.id, resolveNameAlongParentChain(result, useScope.id, "other")?.id)
        assertNotEquals(outerLeft.id, resolveNameAlongParentChain(result, useScope.id, "left")?.id)
    }

    // --- same-block multi-local redeclare (later wins) -----------------------------

    @Test
    fun sameBlock_laterMultiLocalRedeclareWinsAlongScopeChain() {
        // Lua allows multiple locals of the same name in one block; later wins.
        val source = """
            local a, b = 1, 2
            local a, b = 3, 4
            local pickA, pickB = a, b
            """.trimIndent()
        val result = bind(source)

        val aDecls = localsNamed(result, "a")
        val bDecls = localsNamed(result, "b")
        assertEquals(2, aDecls.size)
        assertEquals(2, bDecls.size)
        assertNotEquals(aDecls[0].id, aDecls[1].id)
        assertNotEquals(aDecls[0].symbolId, aDecls[1].symbolId)

        // Both live in the chunk root scope; later declarationIds win when walking
        // reversed (Lua-style same-scope shadowing — see SymbolTableBuilder).
        val root = result.scopeGraph.rootScope
        assertTrue(result.scopeGraph.getDeclarations(root.id).contains(aDecls[0].id))
        assertTrue(result.scopeGraph.getDeclarations(root.id).contains(aDecls[1].id))
        assertTrue(result.scopeGraph.getDeclarations(root.id).contains(bDecls[0].id))
        assertTrue(result.scopeGraph.getDeclarations(root.id).contains(bDecls[1].id))

        val pickScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "pickA").id)
        )
        assertEquals(aDecls[1].id, resolveNameAlongParentChain(result, pickScope.id, "a")?.id)
        assertEquals(bDecls[1].id, resolveNameAlongParentChain(result, pickScope.id, "b")?.id)

        // Position queries still hit each declaration site independently.
        assertEquals(aDecls[0], result.positionQueries.getDeclarationAt(positionOf(source, "a", 1)))
        assertEquals(aDecls[1], result.positionQueries.getDeclarationAt(positionOf(source, "a", 2)))
        // Third `a` is the usage on the pick RHS.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "a", 3)))
    }

    @Test
    fun sameBlock_multiLocalRhsSeesOuterBindingNotSiblingBeingDeclared() {
        // Classic Lua: RHS of `local a, b = a, 1` sees the prior `a`, not the new one.
        val source = """
            local a, b = 10, 20
            local a, b = a, b
            """.trimIndent()
        val result = bind(source)
        val aDecls = localsNamed(result, "a")
        val bDecls = localsNamed(result, "b")

        assertEquals(2, aDecls.size)
        assertEquals(2, bDecls.size)

        // Declaration sites: first multi-local, then second multi-local.
        assertEquals(aDecls[0], result.positionQueries.getDeclarationAt(positionOf(source, "a", 1)))
        assertEquals(aDecls[1], result.positionQueries.getDeclarationAt(positionOf(source, "a", 2)))
        // Third `a` is the RHS usage of the outer binding — not a declaration.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "a", 3)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "b", 3)))

        // After the second multi-local, scope-chain resolve prefers the later pair.
        val root = result.scopeGraph.rootScope
        assertEquals(aDecls[1].id, resolveNameAlongParentChain(result, root.id, "a")?.id)
        assertEquals(bDecls[1].id, resolveNameAlongParentChain(result, root.id, "b")?.id)
    }

    // --- sibling blocks: multi-local shadows must not leak -------------------------

    @Test
    fun siblingDoBlocks_multiLocalShadowDoesNotCrossBleed() {
        val source = """
            local sharedL, sharedR = 1, 2
            do
                local sharedL, sharedR = 3, 4
                local leftPickL, leftPickR = sharedL, sharedR
            end
            do
                local rightPickL, rightPickR = sharedL, sharedR
            end
            """.trimIndent()
        val result = bind(source)
        val graph = result.scopeGraph

        val rootL = localsNamed(result, "sharedL")[0]
        val leftL = localsNamed(result, "sharedL")[1]
        val rootR = localsNamed(result, "sharedR")[0]
        val leftR = localsNamed(result, "sharedR")[1]

        val leftPickScope = assertNotNull(
            graph.getDeclarationScope(localOf(result, "leftPickL").id)
        )
        val rightPickScope = assertNotNull(
            graph.getDeclarationScope(localOf(result, "rightPickL").id)
        )

        assertEquals(leftL.id, resolveNameAlongParentChain(result, leftPickScope.id, "sharedL")?.id)
        assertEquals(leftR.id, resolveNameAlongParentChain(result, leftPickScope.id, "sharedR")?.id)
        assertEquals(rootL.id, resolveNameAlongParentChain(result, rightPickScope.id, "sharedL")?.id)
        assertEquals(rootR.id, resolveNameAlongParentChain(result, rightPickScope.id, "sharedR")?.id)

        // Sibling scopes share root parent but not each other.
        assertEquals(graph.rootScope.id, leftPickScope.parentId)
        assertEquals(graph.rootScope.id, rightPickScope.parentId)
        assertTrue(parentChainIds(graph, leftPickScope.id).none { it == rightPickScope.id })
        assertTrue(parentChainIds(graph, rightPickScope.id).none { it == leftPickScope.id })
    }

    // --- function body multi-local shadowing ---------------------------------------

    @Test
    fun functionBodyMultiLocalShadowsOuterMultiLocal() {
        val source = """
            local outerA, outerB = 1, 2
            local function pack()
                local outerA, outerB = 3, 4
                local useA, useB = outerA, outerB
            end
            local afterA, afterB = outerA, outerB
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        val aDecls = localsNamed(result, "outerA")
        val bDecls = localsNamed(result, "outerB")
        assertEquals(2, aDecls.size)
        assertEquals(2, bDecls.size)

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionScope = assertNotNull(result.scopeGraph.getScope(function.body!!))
        assertEquals(ScopeKind.FUNCTION, functionScope.kind)

        assertTrue(result.scopeGraph.getDeclarations(functionScope.id).contains(aDecls[1].id))
        assertTrue(result.scopeGraph.getDeclarations(functionScope.id).contains(bDecls[1].id))
        assertTrue(
            result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(aDecls[0].id)
        )
        assertTrue(
            result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id)
                .none { it == aDecls[1].id || it == bDecls[1].id }
        )

        val useScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "useA").id)
        )
        assertEquals(aDecls[1].id, resolveNameAlongParentChain(result, useScope.id, "outerA")?.id)
        assertEquals(bDecls[1].id, resolveNameAlongParentChain(result, useScope.id, "outerB")?.id)

        // After the function, outer multi-locals remain visible.
        val afterScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "afterA").id)
        )
        assertEquals(aDecls[0].id, resolveNameAlongParentChain(result, afterScope.id, "outerA")?.id)
        assertEquals(bDecls[0].id, resolveNameAlongParentChain(result, afterScope.id, "outerB")?.id)
    }

    @Test
    fun parameterMultiLocalBodyShadow_prefersBodyLocalsInsideFunction() {
        val source = """
            local function render(left, right)
                local left, right = 1, 2
                local pickL, pickR = left, right
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        val leftParam = result.declarationIndex.declarations.single {
            it.name == "left" && it.kind == DeclarationKind.PARAMETER
        }
        val rightParam = result.declarationIndex.declarations.single {
            it.name == "right" && it.kind == DeclarationKind.PARAMETER
        }
        val leftLocal = localOf(result, "left")
        val rightLocal = localOf(result, "right")

        assertNotEquals(leftParam.id, leftLocal.id)
        assertNotEquals(rightParam.id, rightLocal.id)
        assertNotEquals(leftParam.symbolId, leftLocal.symbolId)

        val pickScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "pickL").id)
        )
        // Body multi-local shadows parameters for VALUE-namespace lookup.
        assertEquals(leftLocal.id, resolveNameAlongParentChain(result, pickScope.id, "left")?.id)
        assertEquals(rightLocal.id, resolveNameAlongParentChain(result, pickScope.id, "right")?.id)

        // Function scope still lists parameters; body multi-locals live there too.
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionScope = assertNotNull(result.scopeGraph.getScope(function.body!!))
        val declIds = result.scopeGraph.getDeclarations(functionScope.id)
        assertTrue(declIds.contains(leftParam.id))
        assertTrue(declIds.contains(leftLocal.id))
        // Reversed walk prefers the later multi-local over the parameter.
        assertEquals(leftLocal.id, resolveNameAlongParentChain(result, functionScope.id, "left")?.id)
        assertEquals(rightLocal.id, resolveNameAlongParentChain(result, functionScope.id, "right")?.id)
    }

    // --- control-flow multi-local shadowing ----------------------------------------

    @Test
    fun ifThenElse_multiLocalShadowsAreBranchLocal() {
        val source = """
            local a, b = 0, 0
            if ok then
                local a, b = 1, 2
                local thenPick = a
            else
                local a, b = 3, 4
                local elsePick = b
            end
            local afterA, afterB = a, b
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        val aDecls = localsNamed(result, "a")
        val bDecls = localsNamed(result, "b")
        assertEquals(3, aDecls.size)
        assertEquals(3, bDecls.size)

        val thenPickScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "thenPick").id)
        )
        val elsePickScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "elsePick").id)
        )
        val afterScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "afterA").id)
        )

        // then branch multi-local = aDecls[1]; else branch = aDecls[2]; root = aDecls[0]
        assertEquals(aDecls[1].id, resolveNameAlongParentChain(result, thenPickScope.id, "a")?.id)
        assertEquals(bDecls[1].id, resolveNameAlongParentChain(result, thenPickScope.id, "b")?.id)
        assertEquals(aDecls[2].id, resolveNameAlongParentChain(result, elsePickScope.id, "a")?.id)
        assertEquals(bDecls[2].id, resolveNameAlongParentChain(result, elsePickScope.id, "b")?.id)
        assertEquals(aDecls[0].id, resolveNameAlongParentChain(result, afterScope.id, "a")?.id)
        assertEquals(bDecls[0].id, resolveNameAlongParentChain(result, afterScope.id, "b")?.id)

        // Branches do not share each other's multi-local shadows.
        assertTrue(parentChainIds(result.scopeGraph, thenPickScope.id).none {
            it == elsePickScope.id
        })
        assertTrue(parentChainIds(result.scopeGraph, elsePickScope.id).none {
            it == thenPickScope.id
        })

        val ifStatement = chunk.body.statements.filterIsInstance<IfStatement>().single()
        val thenScope = assertNotNull(
            result.scopeGraph.getScope(ifStatement.causes.first().body)
        )
        assertEquals(ScopeKind.CONDITIONAL, thenScope.kind)
    }

    @Test
    fun whileBody_multiLocalShadowsOuterAndDoesNotEscape() {
        val source = """
            local wL, wR = 1, 2
            while true do
                local wL, wR = 3, 4
                local bodyL, bodyR = wL, wR
            end
            local afterL, afterR = wL, wR
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        val lefts = localsNamed(result, "wL")
        val rights = localsNamed(result, "wR")
        assertEquals(2, lefts.size)
        assertEquals(2, rights.size)

        val bodyScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "bodyL").id)
        )
        val afterScope = assertNotNull(
            result.scopeGraph.getDeclarationScope(localOf(result, "afterL").id)
        )
        assertEquals(lefts[1].id, resolveNameAlongParentChain(result, bodyScope.id, "wL")?.id)
        assertEquals(rights[1].id, resolveNameAlongParentChain(result, bodyScope.id, "wR")?.id)
        assertEquals(lefts[0].id, resolveNameAlongParentChain(result, afterScope.id, "wL")?.id)
        assertEquals(rights[0].id, resolveNameAlongParentChain(result, afterScope.id, "wR")?.id)

        val whileStatement = chunk.body.statements.filterIsInstance<WhileStatement>().single()
        val whileScope = assertNotNull(result.scopeGraph.getScope(whileStatement.body))
        assertEquals(ScopeKind.LOOP, whileScope.kind)
        assertTrue(result.scopeGraph.getDeclarations(whileScope.id).contains(lefts[1].id))
        assertTrue(
            result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id)
                .none { it == lefts[1].id || it == rights[1].id }
        )
    }

    // --- for-loop multi-name shadowing ---------------------------------------------

    @Test
    fun forNumeric_loopVarShadowsOuterMultiLocalSlot() {
        val source = """
            local i, j = 100, 200
            for i = 1, 3 do
                local bodyI, bodyJ = i, j
            end
            local afterI, afterJ = i, j
            """.trimIndent()
        val result = bind(source)
        val graph = result.scopeGraph

        val outerI = localsNamed(result, "i").single {
            graph.getDeclarationScope(it.id)?.kind == ScopeKind.CHUNK
        }
        val loopI = localsNamed(result, "i").single {
            graph.getDeclarationScope(it.id)?.kind == ScopeKind.LOOP
        }
        val outerJ = localOf(result, "j")

        val bodyScope = assertNotNull(graph.getDeclarationScope(localOf(result, "bodyI").id))
        val afterScope = assertNotNull(graph.getDeclarationScope(localOf(result, "afterI").id))

        assertEquals(loopI.id, resolveNameAlongParentChain(result, bodyScope.id, "i")?.id)
        // `j` is not a loop var; outer multi-local remains visible inside the loop.
        assertEquals(outerJ.id, resolveNameAlongParentChain(result, bodyScope.id, "j")?.id)
        assertEquals(outerI.id, resolveNameAlongParentChain(result, afterScope.id, "i")?.id)
        assertEquals(outerJ.id, resolveNameAlongParentChain(result, afterScope.id, "j")?.id)
    }

    @Test
    fun forGeneric_multiNamesShadowOuterMultiLocalPair() {
        val source = """
            local key, value = "outerK", "outerV"
            for key, value in pairs({}) do
                local bodyK, bodyV = key, value
            end
            local afterK, afterV = key, value
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val graph = result.scopeGraph

        val outerKey = localsNamed(result, "key").single {
            graph.getDeclarationScope(it.id)?.kind == ScopeKind.CHUNK
        }
        val loopKey = localsNamed(result, "key").single {
            graph.getDeclarationScope(it.id)?.kind == ScopeKind.LOOP
        }
        val outerValue = localsNamed(result, "value").single {
            graph.getDeclarationScope(it.id)?.kind == ScopeKind.CHUNK
        }
        val loopValue = localsNamed(result, "value").single {
            graph.getDeclarationScope(it.id)?.kind == ScopeKind.LOOP
        }

        val bodyScope = assertNotNull(graph.getDeclarationScope(localOf(result, "bodyK").id))
        val afterScope = assertNotNull(graph.getDeclarationScope(localOf(result, "afterK").id))

        assertEquals(loopKey.id, resolveNameAlongParentChain(result, bodyScope.id, "key")?.id)
        assertEquals(loopValue.id, resolveNameAlongParentChain(result, bodyScope.id, "value")?.id)
        assertEquals(outerKey.id, resolveNameAlongParentChain(result, afterScope.id, "key")?.id)
        assertEquals(outerValue.id, resolveNameAlongParentChain(result, afterScope.id, "value")?.id)

        val genericFor = chunk.body.statements.filterIsInstance<ForGenericStatement>().single()
        val loopScope = assertNotNull(graph.getScope(genericFor.body))
        assertEquals(ScopeKind.LOOP, loopScope.kind)
        assertTrue(graph.getDeclarations(loopScope.id).contains(loopKey.id))
        assertTrue(graph.getDeclarations(loopScope.id).contains(loopValue.id))
        assertRangesDisjoint(loopKey.range!!, loopValue.range!!)
        assertSameLine(loopKey.range!!, loopValue.range!!)
    }

    // --- AST quirk + multi-local shadow anchors ------------------------------------

    @Test
    fun multiLocalShadow_astQuirkInitAreNamesVariablesAreRhs() {
        val source = """
            local left, right = 1, 2
            do
                local left, right = left, right
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        val outer = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val inner = chunk.body.statements
            .filterIsInstance<DoStatement>()
            .single()
            .body
            .statements
            .filterIsInstance<LocalStatement>()
            .single()

        // Legacy mapping (do not invert): init = names, variables = RHS values.
        assertEquals(listOf("left", "right"), outer.init.map { it.name })
        assertEquals(listOf("left", "right"), inner.init.map { it.name })
        assertEquals(2, outer.variables.size)
        assertEquals(2, inner.variables.size)
        assertIs<Identifier>(inner.variables[0])
        assertIs<Identifier>(inner.variables[1])

        val outerLeft = localsNamed(result, "left")[0]
        val innerLeft = localsNamed(result, "left")[1]
        val outerRight = localsNamed(result, "right")[0]
        val innerRight = localsNamed(result, "right")[1]

        assertSame(outer.init[0], outerLeft.anchorNode)
        assertSame(outer.init[1], outerRight.anchorNode)
        assertSame(inner.init[0], innerLeft.anchorNode)
        assertSame(inner.init[1], innerRight.anchorNode)

        // RHS identifiers on the inner multi-local are usages of the outer pair, not
        // declaration sites (position queries must miss them).
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "left", 3)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "right", 3)))
    }

    @Test
    fun multiLocalShadow_perNameRangesMatchIdentifierAnchorsNotWholeStatement() {
        val source = """
            local alpha, beta = true, false
            do
                local alpha, beta = false, true
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        val outer = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val inner = chunk.body.statements
            .filterIsInstance<DoStatement>()
            .single()
            .body
            .statements
            .filterIsInstance<LocalStatement>()
            .single()

        listOf(
            outer.init[0] to localsNamed(result, "alpha")[0],
            outer.init[1] to localsNamed(result, "beta")[0],
            inner.init[0] to localsNamed(result, "alpha")[1],
            inner.init[1] to localsNamed(result, "beta")[1]
        ).forEach { (identifier, declaration) ->
            assertSame(identifier, declaration.anchorNode)
            assertEquals(identifier.range, declaration.range)
            assertNotEquals(outer.range, declaration.range)
            assertNotEquals(inner.range, declaration.range)
            assertPerNameRange(source, declaration, declaration.name)
        }

        assertRangesDisjoint(
            localsNamed(result, "alpha")[0].range!!,
            localsNamed(result, "alpha")[1].range!!
        )
        assertRangesDisjoint(
            localsNamed(result, "beta")[0].range!!,
            localsNamed(result, "beta")[1].range!!
        )
    }

    // --- dense multi-local shadow + position queries -------------------------------

    @Test
    fun denseSameLineMultiLocalShadow_everyColumnHitsCorrectLevel() {
        val source = """
            local aa,bb,cc=1,2,3
            do
                local aa,bb,cc=4,5,6
            end
            """.trimIndent()
        val result = bind(source)

        listOf("aa", "bb", "cc").forEach { name ->
            val decls = localsNamed(result, name)
            assertEquals(2, decls.size)
            assertEveryColumnHits(source, result, decls[0], name, occurrence = 1)
            assertEveryColumnHits(source, result, decls[1], name, occurrence = 2)
            assertRangesDisjoint(decls[0].range!!, decls[1].range!!)
        }

        // Commas never resolve on either line.
        var from = 0
        repeat(4) {
            val idx = source.indexOf(',', from)
            require(idx >= 0)
            assertNull(result.positionQueries.getDeclarationAt(indexToPosition(source, idx)))
            from = idx + 1
        }
    }

    @Test
    fun multiLocalShadow_halfOpenEndBoundaryMissesAndDoesNotBleedToSibling() {
        val source = """
            local name, other = 1, 2
            do
                local name, other = 3, 4
            end
            """.trimIndent()
        val result = bind(source)

        listOf("name", "other").forEach { n ->
            localsNamed(result, n).forEach { declaration ->
                val range = assertNotNull(declaration.range)
                assertEquals(declaration, result.positionQueries.getDeclarationAt(range.start))
                assertNull(result.positionQueries.getDeclarationAt(range.end))
                assertTrue(result.positionQueries.getDeclarationsAt(range.end).isEmpty())
            }
        }
    }

    @Test
    fun multiLocalShadow_usageSitesDoNotReportDeclaration() {
        val source = """
            local left, right = 1, 2
            do
                local left, right = 3, 4
                print(left, right)
            end
            print(left, right)
            """.trimIndent()
        val result = bind(source)

        val outerLeft = localsNamed(result, "left")[0]
        val innerLeft = localsNamed(result, "left")[1]
        val outerRight = localsNamed(result, "right")[0]
        val innerRight = localsNamed(result, "right")[1]

        assertEquals(outerLeft, result.positionQueries.getDeclarationAt(positionOf(source, "left", 1)))
        assertEquals(innerLeft, result.positionQueries.getDeclarationAt(positionOf(source, "left", 2)))
        // print(left) inside do — usage of inner
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "left", 3)))
        // print(left) after do — usage of outer
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "left", 4)))

        assertEquals(outerRight, result.positionQueries.getDeclarationAt(positionOf(source, "right", 1)))
        assertEquals(innerRight, result.positionQueries.getDeclarationAt(positionOf(source, "right", 2)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "right", 3)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "right", 4)))
    }

    // --- multi-local shadow under nested function + do mix -------------------------

    @Test
    fun nestedFunctionThenDo_multiLocalShadowChainTerminatesAtChunkRoot() {
        val source = """
            local topL, topR = 0, 0
            local function outer()
                local midL, midR = 1, 2
                do
                    local deepL, deepR = 3, 4
                    local pickL, pickR = deepL, midL
                end
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val graph = result.scopeGraph

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val doStatement = function.body!!.statements.filterIsInstance<DoStatement>().single()
        val functionScope = assertNotNull(graph.getScope(function.body!!))
        val doScope = assertNotNull(graph.getScope(doStatement.body))
        val pickScope = assertNotNull(graph.getDeclarationScope(localOf(result, "pickL").id))

        val chain = parentChainIds(graph, pickScope.id)
        assertEquals(graph.rootScope.id, chain.last())
        assertTrue(chain.contains(doScope.id))
        assertTrue(chain.contains(functionScope.id))
        assertEquals(ScopeKind.CHUNK, graph.rootScope.kind)
        assertNull(graph.rootScope.parentId)

        assertEquals(
            localOf(result, "deepL").id,
            resolveNameAlongParentChain(result, pickScope.id, "deepL")?.id
        )
        assertEquals(
            localOf(result, "midL").id,
            resolveNameAlongParentChain(result, pickScope.id, "midL")?.id
        )
        assertEquals(
            localOf(result, "topL").id,
            resolveNameAlongParentChain(result, pickScope.id, "topL")?.id
        )
        // Names only present deeper must not resolve from the function scope alone.
        assertNull(resolveNameAlongParentChain(result, functionScope.id, "deepL"))
        assertEquals(
            localOf(result, "midL").id,
            resolveNameAlongParentChain(result, functionScope.id, "midL")?.id
        )
    }

    @Test
    fun multiLocalShadow_coDeclaredNamesShareOwnerAndKeepDistinctSymbolIds() {
        val source = """
            local a, b, c = 1, 2, 3
            do
                local a, b, c = 4, 5, 6
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val doBody = chunk.body.statements.filterIsInstance<DoStatement>().single().body

        listOf("a", "b", "c").forEach { name ->
            val decls = localsNamed(result, name)
            assertEquals(2, decls.size)
            assertEquals(DeclarationOwner.Lexical(chunk.body), decls[0].owner)
            assertEquals(DeclarationOwner.Lexical(doBody), decls[1].owner)
        }

        val outer = listOf("a", "b", "c").map { localsNamed(result, it)[0] }
        val inner = listOf("a", "b", "c").map { localsNamed(result, it)[1] }
        assertEquals(1, outer.map { it.owner }.toSet().size)
        assertEquals(1, inner.map { it.owner }.toSet().size)
        assertEquals(3, outer.map { it.symbolId }.toSet().size)
        assertEquals(3, inner.map { it.symbolId }.toSet().size)
        // No symbolId reuse across outer/inner multi-local pairs.
        assertTrue(outer.map { it.symbolId }.intersect(inner.map { it.symbolId }.toSet()).isEmpty())
    }

    // --- helpers -------------------------------------------------------------------

    private fun bind(source: String): BinderPassResult {
        val chunk = parser.parse(source)
        return BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
    }

    private fun nonBuiltinLocals(result: BinderPassResult): List<BinderDeclaration> {
        return result.declarationIndex.declarations.filter {
            it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN
        }
    }

    private fun localsNamed(result: BinderPassResult, name: String): List<BinderDeclaration> {
        return nonBuiltinLocals(result).filter { it.name == name }
    }

    private fun localOf(result: BinderPassResult, name: String): BinderDeclaration {
        return nonBuiltinLocals(result).single { it.name == name }
    }

    /**
     * Innermost-first name resolution over [ScopeGraph] parent links:
     * walk `start` → parent → … → root and return the first VALUE declaration whose
     * name matches. Within a scope, later declarationIds win (reversed walk),
     * matching [io.github.dingyi222666.luaparser.semantic.binder.SymbolTableBuilder.findVisibleValueDeclaration].
     */
    private fun resolveNameAlongParentChain(
        result: BinderPassResult,
        start: ScopeId,
        name: String
    ): BinderDeclaration? {
        val graph = result.scopeGraph
        var current: Scope? = graph.getScope(start)
        while (current != null) {
            val hit = graph.getDeclarations(current.id)
                .asReversed()
                .mapNotNull(result.declarationIndex::getDeclaration)
                .firstOrNull {
                    it.name == name && it.origin != DeclarationOrigin.BUILTIN
                }
            if (hit != null) {
                return hit
            }
            current = graph.getParent(current.id)
        }
        return null
    }

    private fun parentChainIds(graph: ScopeGraph, start: ScopeId): List<ScopeId> {
        val chain = mutableListOf<ScopeId>()
        var current: Scope? = graph.getScope(start)
        val seen = mutableSetOf<ScopeId>()
        while (current != null) {
            assertTrue(seen.add(current.id), "cycle detected at scope ${current.id}")
            chain += current.id
            current = graph.getParent(current.id)
        }
        return chain
    }

    private fun assertPerNameRange(source: String, declaration: BinderDeclaration, name: String) {
        val range = assertNotNull(declaration.range, "Declaration '$name' must expose a range")
        val anchor = assertNotNull(declaration.anchorNode, "Declaration '$name' must keep an anchor node")
        val identifier = assertIs<Identifier>(anchor)
        assertEquals(name, identifier.name)
        assertEquals(name, declaration.name)
        assertEquals(DeclarationKind.LOCAL, declaration.kind)
        assertEquals(identifier.range, range)

        val expectedStart = positionOf(source, name, occurrence = occurrenceOf(source, name, range.start))
        assertEquals(expectedStart, range.start, "Range start for '$name' should match identifier start")
        assertEquals(expectedStart.line, range.end.line)
        assertEquals(expectedStart.column + name.length, range.end.column)
        assertTrue(range.end.column > range.start.column)
    }

    /**
     * Which word-boundary occurrence of [name] starts at [start] (1-based).
     * Used when the same name appears multiple times (shadowing corpora).
     */
    private fun occurrenceOf(source: String, name: String, start: Position): Int {
        var occurrence = 1
        while (true) {
            val pos = positionOf(source, name, occurrence)
            if (pos.line == start.line && pos.column == start.column) {
                return occurrence
            }
            occurrence++
            require(occurrence < 64) { "Could not map $start to an occurrence of '$name'" }
        }
    }

    private fun assertEveryColumnHits(
        source: String,
        result: BinderPassResult,
        declaration: BinderDeclaration,
        name: String,
        occurrence: Int = 1
    ) {
        val start = positionOf(source, name, occurrence)
        for (offset in 0 until name.length) {
            val pos = Position(start.line, start.column + offset)
            assertEquals(
                declaration,
                result.positionQueries.getDeclarationAt(pos),
                "expected '$name' (occ $occurrence) at column offset $offset ($pos)"
            )
            assertEquals(listOf(declaration), result.positionQueries.getDeclarationsAt(pos))
            assertEquals(declaration.symbolId, result.positionQueries.getSymbolAt(pos)?.id)
        }
    }

    private fun assertRangesDisjoint(a: Range, b: Range) {
        val aEndsBeforeB = comparePositions(a.end, b.start) <= 0
        val bEndsBeforeA = comparePositions(b.end, a.start) <= 0
        assertTrue(
            aEndsBeforeB || bEndsBeforeA,
            "Expected disjoint ranges but got $a and $b"
        )
    }

    private fun assertSameLine(a: Range, b: Range) {
        assertEquals(a.start.line, b.start.line, "Expected same-line declarations")
        assertEquals(a.end.line, b.end.line)
    }

    private fun comparePositions(left: Position, right: Position): Int {
        val line = left.line.compareTo(right.line)
        return if (line != 0) line else left.column.compareTo(right.column)
    }

    /**
     * Locate the start Position of [needle] in [source].
     *
     * Identifier needles are matched as whole words so short names do not hit
     * substrings inside `local` / longer identifiers. Punctuation needles keep
     * plain substring match.
     */
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
                    return indexToPosition(source, index)
                }
            }
            fromIndex = index + 1
        }
    }

    private fun indexToPosition(source: String, index: Int): Position {
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

    private fun isIdentChar(ch: Char): Boolean {
        return ch == '_' || ch.isLetterOrDigit()
    }
}
