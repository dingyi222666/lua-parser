package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-261 corpus: workspace require-chain visibility across two hops.
 *
 * Acceptance:
 * - `require("a")` → `require("b")` surfaces exported symbols across two hops when the
 *   module graph is complete (resolved edges + mid-module re-surfaced leaf members via
 *   explicit field copies).
 * - Missing modules degrade without hang (null provider / unresolved static require
 *   recorded; analysis still returns a snapshot). Product currently keeps require-bound
 *   locals navigable as in-file "phantom" locals when a *leaf* provider is missing, and
 *   pure `return require(...)` re-exports do not invent an export surface. When the *mid*
 *   module itself is missing, goto on the require alias / usage currently degrades to an
 *   empty definition list (no invented provider path, no hang). Goldens follow that
 *   product snapshot (test-only; no production edits).

 *
 * Test-only. Product sources are out of scope. Verification is review-owned (no Gradle).
 */
class WorkspaceRequireChainVisibilityTddTest {

    @Test
    fun two_hop_require_chain_records_complete_graph_edges() {
        val harness = chainHarness()
        val graph = harness.snapshot.graph
        val main = harness.path("main.lua")
        val a = harness.path("a.lua")
        val b = harness.path("b.lua")

        val mainDeps = graph.resolvedDependencies.getValue(main)
        val aDeps = graph.resolvedDependencies.getValue(a)

        assertEquals(listOf("a"), mainDeps.map { it.moduleName })
        assertEquals(a, mainDeps.single().provider.path)
        assertEquals(listOf("b"), aDeps.map { it.moduleName })
        assertEquals(b, aDeps.single().provider.path)

        assertEquals(setOf(main), graph.reverseDependencies.getValue(a))
        assertEquals(setOf(a), graph.reverseDependencies.getValue(b))
        assertFalse(graph.unresolvedStaticRequires.containsKey(main))
        assertFalse(graph.unresolvedStaticRequires.containsKey(a))
        assertFalse(graph.unresolvedStaticRequires.containsKey(b))

        // Acyclic two-hop chain: only singleton SCCs.
        assertTrue(
            graph.stronglyConnectedComponents.none { it.size > 1 },
            "Complete acyclic chain must not form multi-file SCCs: ${graph.stronglyConnectedComponents}"
        )
    }

    @Test
    fun two_hop_require_chain_surfaces_leaf_exports_through_mid_module() {
        // main → require("a") → require("b"); a re-surfaces leaf export `value` and `run`
        // via explicit field copies (not pure `return require`).
        val harness = chainHarness()
        val bPath = harness.path("b.lua")
        val aPath = harness.path("a.lua")
        val mainPath = harness.path("main.lua")

        val resolvedA = harness.queries.resolveRequire(mainPath, "a")
        val resolvedB = harness.queries.resolveRequire(aPath, "b")
        val lookedUpB = harness.queries.lookupModule("b")

        assertEquals(aPath, resolvedA.provider?.path)
        assertEquals(bPath, resolvedB.provider?.path)
        assertEquals(bPath, lookedUpB.provider?.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, resolvedA.provider?.source)
        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, resolvedB.provider?.source)

        val aSurface = assertNotNull(resolvedA.exportSurface, "Expected mid-module export surface for a.")
        val bSurface = assertNotNull(resolvedB.exportSurface, "Expected leaf export surface for b.")

        assertTrue(bSurface.members.any { it.exportPath == listOf("value") })
        assertTrue(bSurface.members.any { it.exportPath == listOf("run") })
        assertTrue(
            aSurface.members.any { it.exportPath == listOf("value") },
            "Mid module must re-surface leaf field `value` when graph is complete."
        )
        assertTrue(
            aSurface.members.any { it.exportPath == listOf("run") },
            "Mid module must re-surface leaf member `run` when graph is complete."
        )

        val valueDefinition = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "value", occurrence = 2)
        )
        val runDefinition = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "run", occurrence = 2)
        )
        val valueHover = harness.queries.hover(
            mainPath,
            harness.positionOf("main.lua", "value", occurrence = 2)
        )
        val completions = harness.queries.completions(
            mainPath,
            harness.positionOf("main.lua", "value", occurrence = 2)
        )

        // Consumer sees symbols through the two-hop chain (definition lands on mid module writes).
        assertEquals(listOf(aPath), valueDefinition.map { it.path })
        assertEquals(listOf(aPath), runDefinition.map { it.path })
        assertNotNull(valueHover?.symbol)
        assertTrue(
            completions.any { it.label == "value" },
            "Completions through require(\"a\") must include re-surfaced leaf field `value`."
        )
        assertTrue(
            completions.any { it.label == "run" },
            "Completions through require(\"a\") must include re-surfaced leaf member `run`."
        )
    }

    @Test
    fun two_hop_require_chain_with_return_require_keeps_leaf_surface_and_graph_edges() {
        // a.lua is a pure re-export site: return require("b")
        // Product degrade: ModuleExportCollector does not invent members for pure require
        // returns, so resolveRequire(consumer, "a") yields no provider/surface even though
        // the module graph still records the hop. Leaf `b` remains fully queryable.
        val harness = WorkspaceSemanticHarness.build(
            "b.lua" to """
                local M = {}
                M.title = "leaf"
                function M:render()
                  return M.title
                end
                return M
            """.trimIndent(),
            "a.lua" to """
                return require("b")
            """.trimIndent(),
            "main.lua" to """
                local a = require("a")
                local title = a.title
                local render = a.render
                return title, render
            """.trimIndent()
        )

        val graph = harness.snapshot.graph
        val main = harness.path("main.lua")
        val a = harness.path("a.lua")
        val b = harness.path("b.lua")

        assertEquals(listOf("a"), graph.resolvedDependencies.getValue(main).map { it.moduleName })
        assertEquals(listOf("b"), graph.resolvedDependencies.getValue(a).map { it.moduleName })
        assertEquals(a, graph.resolvedDependencies.getValue(main).single().provider.path)
        assertEquals(b, graph.resolvedDependencies.getValue(a).single().provider.path)
        assertEquals(setOf(main), graph.reverseDependencies.getValue(a))
        assertEquals(setOf(a), graph.reverseDependencies.getValue(b))
        assertFalse(graph.unresolvedStaticRequires.containsKey(main))
        assertFalse(graph.unresolvedStaticRequires.containsKey(a))

        // Graph-level provider for `a` still exists; resolveRequire needs an export surface and
        // therefore degrades to a null lookup result for pure re-export modules.
        assertEquals(a, graph.activeProviders["a"]?.path)
        val resolvedA = harness.queries.resolveRequire(main, "a")
        assertNull(
            resolvedA.provider,
            "Pure return-require mid module has no export surface, so resolveRequire degrades."
        )
        assertNull(resolvedA.exportSurface)

        val resolvedB = harness.queries.resolveRequire(a, "b")
        assertEquals(b, resolvedB.provider?.path)
        val leafSurface = assertNotNull(resolvedB.exportSurface, "Leaf exports remain queryable via require(\"b\").")
        assertTrue(leafSurface.members.any { it.exportPath == listOf("title") })
        assertTrue(leafSurface.members.any { it.exportPath == listOf("render") && it.kind == SymbolKind.METHOD })

        // Direct leaf lookup remains stable regardless of pure re-export surface fidelity on `a`.
        val leafLookup = harness.queries.lookupModule("b")
        assertEquals(b, leafLookup.provider?.path)
        assertTrue(assertNotNull(leafLookup.exportSurface).members.any { it.name == "title" })

        // Active provider for the re-export path is still registered; surface stays empty/null.
        val midLookup = harness.queries.lookupModule("a")
        assertEquals(a, midLookup.provider?.path)
        assertNull(midLookup.exportSurface)
    }

    @Test
    fun missing_leaf_module_degrades_without_hanging_analysis() {
        val harness = WorkspaceSemanticHarness.build(
            "a.lua" to """
                local b = require("b")
                return { value = b.value }
            """.trimIndent(),
            "main.lua" to """
                local a = require("a")
                local value = a.value
                return value
            """.trimIndent()
        )

        val graph = harness.snapshot.graph
        val a = harness.path("a.lua")
        val main = harness.path("main.lua")

        // Analysis produced a usable snapshot (no hang).
        assertTrue(harness.snapshot.files.containsKey(main))
        assertTrue(harness.snapshot.files.containsKey(a))

        assertEquals(listOf("a"), graph.resolvedDependencies.getValue(main).map { it.moduleName })
        assertFalse(graph.resolvedDependencies.containsKey(a))
        val unresolved = graph.unresolvedStaticRequires.getValue(a).single()
        assertEquals("b", unresolved.moduleName)
        assertEquals(a, unresolved.consumerPath)

        val missing = harness.queries.resolveRequire(a, "b")
        assertNull(missing.provider)
        assertNull(missing.exportSurface)

        // Product degrade: require-bound local `b` stays an in-file phantom local when the
        // provider is missing (goto lands on the local declaration, never a fabricated module).
        val localBDefinition = harness.queries.gotoDefinition(
            a,
            harness.positionOf("a.lua", "b", occurrence = 1)
        )
        assertEquals(listOf(a), localBDefinition.map { it.path })
        assertTrue(localBDefinition.isNotEmpty())

        // Require string site for the missing module must not invent a provider path.
        val requireStringDefinition = harness.queries.gotoDefinition(
            a,
            harness.positionOf("a.lua", "b", occurrence = 2)
        )
        assertTrue(
            requireStringDefinition.none { it.path != a },
            "Missing require(\"b\") must not jump to a fabricated non-local provider path: $requireStringDefinition"
        )
        assertNull(harness.queries.lookupModule("b").provider)

        // Mid module still resolves for the consumer; leaf absence does not poison the graph.
        val resolvedA = harness.queries.resolveRequire(main, "a")
        assertEquals(a, resolvedA.provider?.path)
        assertNotNull(resolvedA.exportSurface)
    }

    @Test
    fun missing_mid_module_degrades_without_hanging_analysis() {
        val harness = WorkspaceSemanticHarness.build(
            "b.lua" to """
                return { value = 1 }
            """.trimIndent(),
            "main.lua" to """
                local a = require("a")
                local value = a.value
                return value
            """.trimIndent()
        )

        val graph = harness.snapshot.graph
        val main = harness.path("main.lua")

        assertTrue(harness.snapshot.files.containsKey(main))
        assertTrue(harness.snapshot.files.containsKey(harness.path("b.lua")))

        assertFalse(graph.resolvedDependencies.containsKey(main))
        val unresolved = graph.unresolvedStaticRequires.getValue(main).single()
        assertEquals("a", unresolved.moduleName)
        assertEquals(main, unresolved.consumerPath)

        val missing = harness.queries.resolveRequire(main, "a")
        assertNull(missing.provider)
        assertNull(missing.exportSurface)

        // Product degrade (REVIEW27 snapshot): when the mid module itself is missing, goto on
        // the require-bound alias / usage returns an empty definition list — not [main.lua]
        // phantom navigation and not a fabricated provider path. Analysis still completed.
        // occurrence 1 = declaration, occurrence 2 = require string "a", occurrence 3 = a.value use.
        val localADeclaration = harness.queries.gotoDefinition(
            main,
            harness.positionOf("main.lua", "a", occurrence = 1)
        )
        assertEquals(
            emptyList(),
            localADeclaration.map { it.path },
            "Missing mid-module require alias declaration degrades to empty file set, not [main.lua]."
        )

        val localAUsage = harness.queries.gotoDefinition(
            main,
            harness.positionOf("main.lua", "a", occurrence = 3)
        )
        assertEquals(
            emptyList(),
            localAUsage.map { it.path },
            "Missing mid-module require alias usage degrades to empty file set (no invented module path)."
        )


        // Require string for missing "a" must not invent a provider path outside main.
        val requireStringDefinition = harness.queries.gotoDefinition(
            main,
            harness.positionOf("main.lua", "a", occurrence = 2)
        )
        assertTrue(
            requireStringDefinition.none { it.path != main },
            "Missing require(\"a\") must not jump to a fabricated provider: $requireStringDefinition"
        )
        assertNull(harness.queries.lookupModule("a").provider)

        // Member access through the missing mid module must not invent a foreign definition.
        val valueDefinition = harness.queries.gotoDefinition(
            main,
            harness.positionOf("main.lua", "value", occurrence = 2)
        )
        assertTrue(
            valueDefinition.none { it.path == harness.path("b.lua") },
            "Unrelated leaf b.lua must not be used as a fabricated definition for a.value: $valueDefinition"
        )

        // Leaf that is not required stays available via lookup without being forced into the chain.
        val leaf = harness.queries.lookupModule("b")
        assertEquals(harness.path("b.lua"), leaf.provider?.path)
        assertNotNull(leaf.exportSurface)
    }

    @Test
    fun missing_module_in_longer_chain_degrades_without_hanging() {
        // main → a → b → missing("c")
        val harness = WorkspaceSemanticHarness.build(
            "b.lua" to """
                local c = require("c")
                return { value = c.value }
            """.trimIndent(),
            "a.lua" to """
                local b = require("b")
                return { value = b.value }
            """.trimIndent(),
            "main.lua" to """
                local a = require("a")
                return a.value
            """.trimIndent()
        )

        val graph = harness.snapshot.graph
        val main = harness.path("main.lua")
        val a = harness.path("a.lua")
        val b = harness.path("b.lua")

        assertTrue(harness.snapshot.files.containsKey(main))
        assertTrue(harness.snapshot.files.containsKey(a))
        assertTrue(harness.snapshot.files.containsKey(b))
        assertEquals(listOf("a"), graph.resolvedDependencies.getValue(main).map { it.moduleName })
        assertEquals(listOf("b"), graph.resolvedDependencies.getValue(a).map { it.moduleName })
        assertFalse(graph.resolvedDependencies.containsKey(b))

        val unresolved = graph.unresolvedStaticRequires.getValue(b).single()
        assertEquals("c", unresolved.moduleName)

        val missing = harness.queries.resolveRequire(b, "c")
        assertNull(missing.provider)
        assertNull(missing.exportSurface)

        // Phantom local degrade: local `c` stays in-file; never a fabricated provider path.
        val localCDefinition = harness.queries.gotoDefinition(
            b,
            harness.positionOf("b.lua", "c", occurrence = 1)
        )
        assertTrue(
            localCDefinition.all { it.path == b },
            "Missing require local must not leave the consumer file: $localCDefinition"
        )
        assertNull(harness.queries.lookupModule("c").provider)

        // Upstream edges remain complete; only the broken hop is unresolved.
        assertFalse(graph.unresolvedStaticRequires.containsKey(main))
        assertFalse(graph.unresolvedStaticRequires.containsKey(a))
    }

    private fun chainHarness(): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            "b.lua" to """
                local M = {}
                M.value = 1
                function M.run()
                  return M.value
                end
                return M
            """.trimIndent(),
            "a.lua" to """
                local b = require("b")
                local M = {}
                M.value = b.value
                M.run = b.run
                return M
            """.trimIndent(),
            "main.lua" to """
                local a = require("a")
                local value = a.value
                local run = a.run
                return value, run
            """.trimIndent()
        )
    }
}
