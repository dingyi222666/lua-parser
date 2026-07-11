package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraphBuilder
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-207 corpus: workspace module-graph require-cycle detection / reporting.
 *
 * Acceptance:
 * - Simple require cycles are detected (or otherwise reported) without infinite analysis.
 * - Non-cyclic graphs stay quiet (no multi-file SCC noise).
 *
 * Test-only; product sources are out of scope. Verification is review-owned (no Gradle here).
 *
 * Cycle detection surface is [WorkspaceModuleGraph.stronglyConnectedComponents] /
 * [WorkspaceModuleGraph.stronglyConnectedComponentByFile] produced by
 * [WorkspaceModuleGraphBuilder]. Engine-level cases assert analysis terminates and
 * keeps the cycle visible on the snapshot graph.
 */
class WorkspaceModuleGraphCycleTddTest {

    @Test
    fun simple_two_module_require_cycle_is_collapsed_into_one_scc() {
        val graph = buildGraph(
            "cycle/a.lua" to """
                local b = require("cycle.b")
                return { value = b.value }
            """.trimIndent(),
            "cycle/b.lua" to """
                local a = require("cycle.a")
                return { value = a.value }
            """.trimIndent()
        )

        val a = path("cycle/a.lua")
        val b = path("cycle/b.lua")
        val component = graph.stronglyConnectedComponentByFile.getValue(a)

        assertEquals(setOf(a, b), component)
        assertEquals(component, graph.stronglyConnectedComponentByFile.getValue(b))
        assertTrue(graph.stronglyConnectedComponents.any { it == setOf(a, b) })
        assertEquals(setOf(b), graph.reverseDependencies.getValue(a))
        assertEquals(setOf(a), graph.reverseDependencies.getValue(b))
        assertEquals(listOf("cycle.b"), graph.resolvedDependencies.getValue(a).map { it.moduleName })
        assertEquals(listOf("cycle.a"), graph.resolvedDependencies.getValue(b).map { it.moduleName })
    }

    @Test
    fun three_module_require_cycle_is_detected_as_single_scc() {
        val graph = buildGraph(
            "cycle/a.lua" to "local b = require(\"cycle.b\")\nreturn { next = b }",
            "cycle/b.lua" to "local c = require(\"cycle.c\")\nreturn { next = c }",
            "cycle/c.lua" to "local a = require(\"cycle.a\")\nreturn { next = a }"
        )

        val a = path("cycle/a.lua")
        val b = path("cycle/b.lua")
        val c = path("cycle/c.lua")
        val component = graph.stronglyConnectedComponentByFile.getValue(a)

        assertEquals(setOf(a, b, c), component)
        assertEquals(component, graph.stronglyConnectedComponentByFile.getValue(b))
        assertEquals(component, graph.stronglyConnectedComponentByFile.getValue(c))
        assertTrue(graph.stronglyConnectedComponents.any { it == setOf(a, b, c) })
        assertTrue(multiMemberComponents(graph).size == 1)
    }

    @Test
    fun self_require_is_recorded_as_resolved_dependency_without_hanging() {
        val graph = buildGraph(
            "loop/self.lua" to """
                local self = require("loop.self")
                return { self = self }
            """.trimIndent()
        )

        val self = path("loop/self.lua")
        val deps = graph.resolvedDependencies.getValue(self)

        assertEquals(listOf("loop.self"), deps.map { it.moduleName })
        assertEquals(self, deps.single().provider.path)
        assertEquals(setOf(self), graph.reverseDependencies.getValue(self))
        assertEquals(setOf(self), graph.stronglyConnectedComponentByFile.getValue(self))
        // Tarjan always emits a component for the node; self-edge is the cycle signal.
        assertTrue(graph.stronglyConnectedComponents.any { it == setOf(self) })
    }

    @Test
    fun mutual_cycle_plus_outside_consumer_keeps_consumer_outside_scc() {
        val graph = buildGraph(
            "cycle/left.lua" to "local right = require(\"cycle.right\")\nreturn { peer = right }",
            "cycle/right.lua" to "local left = require(\"cycle.left\")\nreturn { peer = left }",
            "app/main.lua" to "local left = require(\"cycle.left\")\nreturn left"
        )

        val left = path("cycle/left.lua")
        val right = path("cycle/right.lua")
        val main = path("app/main.lua")
        val cycle = graph.stronglyConnectedComponentByFile.getValue(left)

        assertEquals(setOf(left, right), cycle)
        assertEquals(setOf(main), graph.stronglyConnectedComponentByFile.getValue(main))
        assertTrue(main !in cycle)
        assertTrue(main in graph.reverseDependencies.getValue(left))
        assertFalse(main in graph.reverseDependencies.getValue(right))
    }

    @Test
    fun non_cyclic_require_chain_stays_quiet_with_only_singleton_sccs() {
        val graph = buildGraph(
            "lib/leaf.lua" to "return { value = 1 }",
            "lib/mid.lua" to "local leaf = require(\"lib.leaf\")\nreturn { value = leaf.value }",
            "app/top.lua" to "local mid = require(\"lib.mid\")\nreturn { value = mid.value }",
            "app/unrelated.lua" to "return { ok = true }"
        )

        assertTrue(
            multiMemberComponents(graph).isEmpty(),
            "Acyclic chain must not report multi-file SCCs: ${multiMemberComponents(graph)}"
        )
        assertEquals(
            setOf(path("lib/mid.lua")),
            graph.reverseDependencies.getValue(path("lib/leaf.lua"))
        )
        assertEquals(
            setOf(path("app/top.lua")),
            graph.reverseDependencies.getValue(path("lib/mid.lua"))
        )
        assertFalse(graph.reverseDependencies.containsKey(path("app/unrelated.lua")))
        assertFalse(graph.resolvedDependencies.containsKey(path("app/unrelated.lua")))
    }

    @Test
    fun diamond_shared_dependency_is_not_a_cycle() {
        // top -> left -> leaf
        // top -> right -> leaf
        val graph = buildGraph(
            "mods/leaf.lua" to "return { value = 1 }",
            "mods/left.lua" to "local leaf = require(\"mods.leaf\")\nreturn leaf",
            "mods/right.lua" to "local leaf = require(\"mods.leaf\")\nreturn leaf",
            "mods/top.lua" to """
                local left = require("mods.left")
                local right = require("mods.right")
                return left, right
            """.trimIndent()
        )

        assertTrue(
            multiMemberComponents(graph).isEmpty(),
            "Diamond (shared dep, no back-edge) must stay cycle-quiet: ${multiMemberComponents(graph)}"
        )
        assertEquals(
            setOf(path("mods/left.lua"), path("mods/right.lua")),
            graph.reverseDependencies.getValue(path("mods/leaf.lua"))
        )
        assertEquals(
            setOf(path("mods/top.lua")),
            graph.reverseDependencies.getValue(path("mods/left.lua"))
        )
        assertEquals(
            setOf(path("mods/top.lua")),
            graph.reverseDependencies.getValue(path("mods/right.lua"))
        )
    }

    @Test
    fun unresolved_and_dynamic_requires_do_not_fabricate_cycle_edges() {
        val graph = buildGraph(
            "app/main.lua" to """
                local missing = require("does.not.exist")
                local dyn = require(name)
                return missing, dyn
            """.trimIndent(),
            "app/other.lua" to "return { ok = true }"
        )

        val main = path("app/main.lua")
        assertFalse(graph.resolvedDependencies.containsKey(main))
        assertEquals(listOf("does.not.exist"), graph.unresolvedStaticRequires.getValue(main).map { it.moduleName })
        assertEquals(1, graph.dynamicRequireSites.getValue(main).size)
        assertTrue(
            multiMemberComponents(graph).isEmpty(),
            "Missing/dynamic requires must not invent multi-file cycles"
        )
    }

    @Test
    fun engine_build_on_simple_cycle_terminates_and_exposes_scc() {
        val engine = LuaWorkspaceEngine()
        val result = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    path("cycle/a.lua") to """
                        local b = require("cycle.b")
                        local M = { tag = "a", peer = b }
                        return M
                    """.trimIndent(),
                    path("cycle/b.lua") to """
                        local a = require("cycle.a")
                        local M = { tag = "b", peer = a }
                        return M
                    """.trimIndent(),
                    path("app/main.lua") to """
                        local a = require("cycle.a")
                        return a.tag
                    """.trimIndent()
                )
            )
        )

        val graph = result.snapshot.graph
        val a = path("cycle/a.lua")
        val b = path("cycle/b.lua")
        val main = path("app/main.lua")
        val component = graph.stronglyConnectedComponentByFile.getValue(a)

        assertEquals(setOf(a, b), component)
        assertTrue(graph.stronglyConnectedComponents.any { it == component })
        assertTrue(main !in component)
        // Full build marks every input file affected; must still finish with a finite set.
        assertEquals(setOf(a, b, main), result.affectedDocuments)
        assertNotNull(result.snapshot.files[a])
        assertNotNull(result.snapshot.files[b])
        assertNotNull(result.snapshot.files[main])
    }

    @Test
    fun engine_build_on_acyclic_workspace_stays_cycle_quiet() {
        val engine = LuaWorkspaceEngine()
        val result = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    path("lib/dep.lua") to "return { value = 1 }",
                    path("app/main.lua") to "local dep = require(\"lib.dep\")\nreturn dep.value",
                    path("app/other.lua") to "return { ok = true }"
                )
            )
        )

        assertTrue(
            multiMemberComponents(result.snapshot.graph).isEmpty(),
            "Acyclic engine workspace must not report multi-file SCCs"
        )
        assertEquals(
            setOf(path("app/main.lua")),
            result.snapshot.graph.reverseDependencies.getValue(path("lib/dep.lua"))
        )
    }

    @Test
    fun harness_cycle_analysis_terminates_and_keeps_exports_queryable() {
        val harness = WorkspaceSemanticHarness.build(
            "cycle/a.lua" to """
                local b = require("cycle.b")
                local M = { value = 1, fromB = b.value }
                return M
            """.trimIndent(),
            "cycle/b.lua" to """
                local a = require("cycle.a")
                local M = { value = 2, fromA = a.value }
                return M
            """.trimIndent(),
            "main.lua" to """
                local a = require("cycle.a")
                return a.value
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
        )

        val component = harness.snapshot.graph.stronglyConnectedComponentByFile
            .getValue(harness.path("cycle/a.lua"))
        val definition = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "value")
        )
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "cycle.a")

        assertEquals(setOf(harness.path("cycle/a.lua"), harness.path("cycle/b.lua")), component)
        assertEquals(listOf(harness.path("cycle/a.lua")), definition.map { it.path })
        assertEquals(harness.path("cycle/a.lua"), resolved.provider?.path)

        // Cycle files must not flood infinite diagnostics; analysis finished with a finite list.
        val cycleDiagnostics = harness.queries.diagnostics(harness.path("cycle/a.lua")) +
            harness.queries.diagnostics(harness.path("cycle/b.lua"))
        assertTrue(cycleDiagnostics.size < 10_000, "Cycle analysis must terminate with a finite diagnostic set")
    }

    @Test
    fun acyclic_harness_workspace_does_not_emit_cycle_noise_on_scc_surface() {
        val harness = WorkspaceSemanticHarness.build(
            "lib/util.lua" to "return { id = \"util\" }",
            "app/main.lua" to """
                local util = require("lib.util")
                return util.id
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
        )

        assertTrue(
            multiMemberComponents(harness.snapshot.graph).isEmpty(),
            "Non-cyclic harness graph must stay SCC-quiet"
        )
        val diagnostics = harness.queries.diagnostics(harness.path("app/main.lua")) +
            harness.queries.diagnostics(harness.path("lib/util.lua"))
        assertTrue(
            diagnostics.none { diagnostic ->
                val text = listOfNotNull(diagnostic.message, diagnostic.code).joinToString(" ").lowercase()
                text.contains("cycle") || text.contains("circular")
            },
            "Acyclic workspace must not report cycle diagnostics: $diagnostics"
        )
    }

    @Test
    fun two_independent_cycles_are_reported_as_separate_sccs() {
        val graph = buildGraph(
            "c1/a.lua" to "local b = require(\"c1.b\")\nreturn b",
            "c1/b.lua" to "local a = require(\"c1.a\")\nreturn a",
            "c2/x.lua" to "local y = require(\"c2.y\")\nreturn y",
            "c2/y.lua" to "local x = require(\"c2.x\")\nreturn x",
            "app/main.lua" to """
                local a = require("c1.a")
                local x = require("c2.x")
                return a, x
            """.trimIndent()
        )

        val c1 = setOf(path("c1/a.lua"), path("c1/b.lua"))
        val c2 = setOf(path("c2/x.lua"), path("c2/y.lua"))
        val multi = multiMemberComponents(graph)

        assertTrue(c1 in multi, "first cycle must be detected")
        assertTrue(c2 in multi, "second cycle must be detected")
        assertEquals(2, multi.size, "independent cycles must not merge into one SCC")
        assertEquals(setOf(path("app/main.lua")), graph.stronglyConnectedComponentByFile.getValue(path("app/main.lua")))
    }

    private fun multiMemberComponents(graph: WorkspaceModuleGraph): Set<Set<VirtualPath>> {
        return graph.stronglyConnectedComponents
            .filter { it.size > 1 }
            .toSet()
    }

    private fun path(value: String): VirtualPath = VirtualPath.of(value)

    private fun buildGraph(vararg files: Pair<String, String>): WorkspaceModuleGraph {
        val snapshots = linkedMapOf<VirtualPath, WorkspaceSnapshot.FileSnapshot>()
        files.forEach { (rawPath, source) ->
            val virtualPath = VirtualPath.of(rawPath)
            snapshots[virtualPath] = WorkspaceSnapshot.FileSnapshot(
                documentFacts = DocumentFactsCollector.collect(virtualPath, LuaParser().parse(source))
            )
        }
        val overlay = BuiltinOverlayLoader.load(LuaVersion.LUA_5_3) { overlayPath, overlaySource ->
            WorkspaceSnapshot.FileSnapshot(
                documentFacts = DocumentFactsCollector.collect(overlayPath, LuaParser().parse(overlaySource))
            )
        }
        return WorkspaceModuleGraphBuilder.build(snapshots, overlay)
    }
}
