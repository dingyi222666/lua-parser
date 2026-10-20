package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeGraph
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REVIEW19 / TASK-234 corpus: ScopeGraph parent-chain queries.
 *
 * Acceptance:
 * - Scope parent chains for nested blocks terminate at the global/chunk root.
 * - Shadowed names resolve innermost-first when walking parent chains.
 *
 * Test-only; production defects surface as assertion failures (review-owned
 * verification via `jvmTest --tests semantic.binder.ScopeGraphParentChainTddTest`).
 */
class ScopeGraphParentChainTddTest {

    private val parser = LuaParser()

    // -------------------------------------------------------------------------
    // Parent chains terminate at global/chunk root
    // -------------------------------------------------------------------------

    @Test
    fun nestedBlockParentChainTerminatesAtChunkRoot() {
        val chunk = parser.parse(
            """
            do
                do
                    do
                        local deepest = 1
                    end
                end
            end
            """.trimIndent()
        )
        val result = bind(chunk)
        val graph = result.scopeGraph
        val root = graph.rootScope

        assertEquals(ScopeKind.CHUNK, root.kind)
        assertNull(root.parentId)
        assertNull(graph.getParent(root.id))

        val outerDo = chunk.body.statements.filterIsInstance<DoStatement>().single()
        val midDo = outerDo.body.statements.filterIsInstance<DoStatement>().single()
        val innerDo = midDo.body.statements.filterIsInstance<DoStatement>().single()

        val outerScope = assertNotNull(graph.getScope(outerDo.body))
        val midScope = assertNotNull(graph.getScope(midDo.body))
        val innerScope = assertNotNull(graph.getScope(innerDo.body))

        assertEquals(
            listOf(innerScope.id, midScope.id, outerScope.id, root.id),
            parentChainIds(graph, innerScope.id)
        )
        assertEquals(root.id, parentChainIds(graph, innerScope.id).last())
        assertEquals(root, terminalScope(graph, innerScope.id))
        assertParentLinksConsistent(graph, innerScope.id)
    }

    @Test
    fun mixedConstructParentChainTerminatesAtGlobal() {
        val chunk = parser.parse(
            """
            local function render()
                do
                    if true then
                        while true do
                            for i = 1, 2 do
                                local deepest = i
                            end
                        end
                    end
                end
            end
            """.trimIndent()
        )
        val result = bind(chunk)
        val graph = result.scopeGraph
        val root = graph.rootScope

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val doStatement = function.body!!.statements.filterIsInstance<DoStatement>().single()
        val ifStatement = doStatement.body.statements.filterIsInstance<IfStatement>().single()
        val whileStatement = ifStatement.causes.first().body.statements.filterIsInstance<WhileStatement>().single()
        val forStatement = whileStatement.body.statements.filterIsInstance<ForNumericStatement>().single()

        val functionScope = assertNotNull(graph.getScope(function.body!!))
        val doScope = assertNotNull(graph.getScope(doStatement.body))
        val ifScope = assertNotNull(graph.getScope(ifStatement.causes.first().body))
        val whileScope = assertNotNull(graph.getScope(whileStatement.body))
        val forScope = assertNotNull(graph.getScope(forStatement.body))

        val chain = parentChainIds(graph, forScope.id)
        assertEquals(
            listOf(forScope.id, whileScope.id, ifScope.id, doScope.id, functionScope.id, root.id),
            chain
        )
        assertEquals(root.id, chain.last())
        assertNull(graph.getParent(root.id))

        // Kind tags along the chain (excluding root) stay non-chunk.
        assertEquals(ScopeKind.LOOP, forScope.kind)
        assertEquals(ScopeKind.LOOP, whileScope.kind)
        assertEquals(ScopeKind.CONDITIONAL, ifScope.kind)
        assertEquals(ScopeKind.BLOCK, doScope.kind)
        assertEquals(ScopeKind.FUNCTION, functionScope.kind)
        assertEquals(ScopeKind.CHUNK, root.kind)
    }

    @Test
    fun everyNonRootScopeParentChainReachesRootExactlyOnce() {
        val chunk = parser.parse(
            """
            local top = 0
            do
                local outer = 1
                if true then
                    local mid = 2
                    while false do
                        local inner = 3
                    end
                else
                    for j = 1, 1 do
                        local loopBody = j
                    end
                end
            end

            local function helper(param)
                local body = param
            end
            """.trimIndent()
        )
        val result = bind(chunk)
        val graph = result.scopeGraph
        val root = graph.rootScope

        graph.scopes.forEach { scope ->
            val chain = parentChainIds(graph, scope.id)
            assertEquals(scope.id, chain.first(), "chain must start at the queried scope")
            assertEquals(root.id, chain.last(), "chain for ${scope.id} must terminate at root")
            assertEquals(chain.size, chain.toSet().size, "parent chain must be cycle-free for ${scope.id}")
            assertParentLinksConsistent(graph, scope.id)

            if (scope.id == root.id) {
                assertEquals(1, chain.size)
                assertNull(scope.parentId)
            } else {
                assertTrue(chain.size >= 2)
                assertNotNull(scope.parentId)
            }
        }
    }

    @Test
    fun getParentMatchesParentIdAndChildBackEdgesAlongChain() {
        val chunk = parser.parse(
            """
            do
                if true then
                    local value = 1
                end
            end
            """.trimIndent()
        )
        val result = bind(chunk)
        val graph = result.scopeGraph

        val doStatement = chunk.body.statements.filterIsInstance<DoStatement>().single()
        val ifStatement = doStatement.body.statements.filterIsInstance<IfStatement>().single()
        val ifScope = assertNotNull(graph.getScope(ifStatement.causes.first().body))

        var current: Scope? = ifScope
        while (current != null) {
            val parent = graph.getParent(current.id)
            assertEquals(current.parentId, parent?.id)
            if (parent != null) {
                assertTrue(
                    graph.getChildren(parent.id).any { it.id == current!!.id },
                    "parent ${parent.id} must list child ${current.id}"
                )
            }
            current = parent
        }
    }

    // -------------------------------------------------------------------------
    // Shadowed names resolve innermost-first along parent chain
    // -------------------------------------------------------------------------

    @Test
    fun shadowedLocalResolvesInnermostFirstAlongParentChain() {
        val chunk = parser.parse(
            """
            local value = "outer"
            do
                local value = "middle"
                do
                    local value = "inner"
                    local chosen = value
                end
            end
            """.trimIndent()
        )
        val result = bind(chunk)
        val graph = result.scopeGraph
        val declarations = nonBuiltinLocals(result)

        val outer = declarations.single { it.name == "value" && ownerDepth(result, it) == 0 }
        val middle = declarations.single { it.name == "value" && ownerDepth(result, it) == 1 }
        val inner = declarations.single { it.name == "value" && ownerDepth(result, it) == 2 }
        val chosen = declarations.single { it.name == "chosen" }

        val chosenScope = assertNotNull(graph.getDeclarationScope(chosen.id))
        val resolved = assertNotNull(
            resolveNameAlongParentChain(result, chosenScope.id, "value"),
            "expected value to resolve from chosen's scope chain"
        )

        assertEquals(inner.id, resolved.id)
        assertNotEquals(middle.id, resolved.id)
        assertNotEquals(outer.id, resolved.id)

        // Walking outward from the middle scope must not see the inner binding.
        val middleScope = assertNotNull(graph.getDeclarationScope(middle.id))
        assertEquals(middle.id, resolveNameAlongParentChain(result, middleScope.id, "value")?.id)

        val outerScope = assertNotNull(graph.getDeclarationScope(outer.id))
        assertEquals(outer.id, resolveNameAlongParentChain(result, outerScope.id, "value")?.id)
    }

    @Test
    fun multiLevelShadowingPrefersNearestEnclosingDeclaration() {
        val chunk = parser.parse(
            """
            local name = 1
            local function render(name)
                do
                    local name = 2
                    local pick = name
                end
                local after = name
            end
            """.trimIndent()
        )
        val result = bind(chunk)
        val graph = result.scopeGraph

        val topLocal = result.declarationIndex.declarations.single {
            it.name == "name" && it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN &&
                graph.getDeclarationScope(it.id)?.kind == ScopeKind.CHUNK
        }
        val parameter = result.declarationIndex.declarations.single {
            it.name == "name" && it.kind == DeclarationKind.PARAMETER
        }
        val innerLocal = result.declarationIndex.declarations.single {
            it.name == "name" && it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN &&
                graph.getDeclarationScope(it.id)?.kind == ScopeKind.BLOCK
        }
        val pick = result.declarationIndex.declarations.single {
            it.name == "pick" && it.kind == DeclarationKind.LOCAL
        }
        val after = result.declarationIndex.declarations.single {
            it.name == "after" && it.kind == DeclarationKind.LOCAL
        }

        val pickScope = assertNotNull(graph.getDeclarationScope(pick.id))
        val afterScope = assertNotNull(graph.getDeclarationScope(after.id))

        assertEquals(innerLocal.id, resolveNameAlongParentChain(result, pickScope.id, "name")?.id)
        assertEquals(parameter.id, resolveNameAlongParentChain(result, afterScope.id, "name")?.id)
        assertNotEquals(topLocal.id, resolveNameAlongParentChain(result, afterScope.id, "name")?.id)

        // From the function body scope, parameter wins over chunk local.
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionScope = assertNotNull(graph.getScope(function.body!!))
        assertEquals(parameter.id, resolveNameAlongParentChain(result, functionScope.id, "name")?.id)
    }

    @Test
    fun loopVariableShadowsOuterLocalAlongParentChain() {
        val chunk = parser.parse(
            """
            local i = 100
            for i = 1, 3 do
                local body = i
            end
            local after = i
            """.trimIndent()
        )
        val result = bind(chunk)
        val graph = result.scopeGraph

        val outerI = result.declarationIndex.declarations.single {
            it.name == "i" &&
                it.kind == DeclarationKind.LOCAL &&
                it.origin != DeclarationOrigin.BUILTIN &&
                graph.getDeclarationScope(it.id)?.kind == ScopeKind.CHUNK
        }
        val loopI = result.declarationIndex.declarations.single {
            it.name == "i" &&
                it.kind == DeclarationKind.LOCAL &&
                it.origin != DeclarationOrigin.BUILTIN &&
                graph.getDeclarationScope(it.id)?.kind == ScopeKind.LOOP
        }
        val body = result.declarationIndex.declarations.single { it.name == "body" }
        val after = result.declarationIndex.declarations.single { it.name == "after" }

        val bodyScope = assertNotNull(graph.getDeclarationScope(body.id))
        val afterScope = assertNotNull(graph.getDeclarationScope(after.id))

        assertEquals(loopI.id, resolveNameAlongParentChain(result, bodyScope.id, "i")?.id)
        assertEquals(outerI.id, resolveNameAlongParentChain(result, afterScope.id, "i")?.id)

        val chainFromLoop = parentChainIds(graph, bodyScope.id)
        assertEquals(graph.rootScope.id, chainFromLoop.last())
        assertTrue(chainFromLoop.contains(assertNotNull(graph.getDeclarationScope(loopI.id)).id))
    }

    @Test
    fun siblingBlockShadowDoesNotLeakAcrossParentChains() {
        val chunk = parser.parse(
            """
            local shared = "root"
            do
                local shared = "left"
                local leftPick = shared
            end
            do
                local rightPick = shared
            end
            """.trimIndent()
        )
        val result = bind(chunk)
        val graph = result.scopeGraph

        val rootShared = result.declarationIndex.declarations.single {
            it.name == "shared" &&
                graph.getDeclarationScope(it.id)?.kind == ScopeKind.CHUNK &&
                it.origin != DeclarationOrigin.BUILTIN
        }
        val leftShared = result.declarationIndex.declarations.single {
            it.name == "shared" &&
                graph.getDeclarationScope(it.id)?.kind == ScopeKind.BLOCK &&
                it.origin != DeclarationOrigin.BUILTIN
        }
        val leftPick = result.declarationIndex.declarations.single { it.name == "leftPick" }
        val rightPick = result.declarationIndex.declarations.single { it.name == "rightPick" }

        val leftScope = assertNotNull(graph.getDeclarationScope(leftPick.id))
        val rightScope = assertNotNull(graph.getDeclarationScope(rightPick.id))

        assertEquals(leftShared.id, resolveNameAlongParentChain(result, leftScope.id, "shared")?.id)
        assertEquals(rootShared.id, resolveNameAlongParentChain(result, rightScope.id, "shared")?.id)

        // Sibling scopes share the same parent (chunk root) but not each other.
        assertEquals(graph.rootScope.id, leftScope.parentId)
        assertEquals(graph.rootScope.id, rightScope.parentId)
        assertNull(graph.getParent(graph.rootScope.id))
        assertTrue(parentChainIds(graph, leftScope.id).none { it == rightScope.id })
        assertTrue(parentChainIds(graph, rightScope.id).none { it == leftScope.id })
    }

    @Test
    fun missingNameWalksFullChainToRootWithoutFalseHit() {
        val chunk = parser.parse(
            """
            local present = 1
            do
                local alsoPresent = 2
            end
            """.trimIndent()
        )
        val result = bind(chunk)
        val graph = result.scopeGraph

        val alsoPresent = result.declarationIndex.declarations.single {
            it.name == "alsoPresent" && it.origin != DeclarationOrigin.BUILTIN
        }
        val innerScope = assertNotNull(graph.getDeclarationScope(alsoPresent.id))
        val chain = parentChainIds(graph, innerScope.id)

        assertEquals(graph.rootScope.id, chain.last())
        assertNull(resolveNameAlongParentChain(result, innerScope.id, "absentName"))
        assertEquals(
            alsoPresent.id,
            resolveNameAlongParentChain(result, innerScope.id, "alsoPresent")?.id
        )
        assertEquals(
            result.declarationIndex.declarations.single {
                it.name == "present" && it.origin != DeclarationOrigin.BUILTIN
            }.id,
            resolveNameAlongParentChain(result, innerScope.id, "present")?.id
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun bind(chunk: ChunkNode): BinderPassResult =
        BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

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

    private fun terminalScope(graph: ScopeGraph, start: ScopeId): Scope {
        val chain = parentChainIds(graph, start)
        return assertNotNull(graph.getScope(chain.last()))
    }

    private fun assertParentLinksConsistent(graph: ScopeGraph, start: ScopeId) {
        var current = assertNotNull(graph.getScope(start))
        while (true) {
            val parent = graph.getParent(current.id)
            assertEquals(current.parentId, parent?.id)
            if (parent == null) {
                assertNull(current.parentId)
                break
            }
            current = parent
        }
    }

    /**
     * Innermost-first name resolution over [ScopeGraph] parent links:
     * walk `start` → parent → … → root and return the first declaration whose
     * name matches in that scope's declaration list.
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
                .mapNotNull(result.declarationIndex::getDeclaration)
                .firstOrNull { it.name == name && it.origin != DeclarationOrigin.BUILTIN }
            if (hit != null) {
                return hit
            }
            current = graph.getParent(current.id)
        }
        return null
    }

    private fun nonBuiltinLocals(result: BinderPassResult): List<BinderDeclaration> =
        result.declarationIndex.declarations.filter {
            it.origin != DeclarationOrigin.BUILTIN && it.kind == DeclarationKind.LOCAL
        }

    /** Nesting depth of the declaration's owning scope relative to the chunk root. */
    private fun ownerDepth(result: BinderPassResult, declaration: BinderDeclaration): Int {
        val scope = assertNotNull(result.scopeGraph.getDeclarationScope(declaration.id))
        return parentChainIds(result.scopeGraph, scope.id).size - 1
    }
}
