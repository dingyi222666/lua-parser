package integration

import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-305 corpus: integration require-chain completion surface.
 *
 * Acceptance:
 * - Two-hop `require("a")` → `require("b")` completions surface leaf exports when the mid
 *   module re-surfaces them via explicit field copies (graph complete).
 * - Missing module degrades empty (no invented member labels, no hang; analysis still yields
 *   a snapshot and completion queries return without fabricated foreign providers).
 *
 * Test-only. Product sources are out of scope. Verification is review-owned (no Gradle).
 * Distinct from TASK-261 (workspace graph/goto visibility): this suite focuses on the
 * completion query surface at the integration package boundary.
 */
class RequireChainCompletionIntegrationTddTest {

    @Test
    fun two_hop_require_chain_completions_surface_leaf_exports() {
        // main → require("a") → require("b"); a re-surfaces leaf `value` / `run`.
        val harness = chainHarness()
        val main = harness.path("main.lua")
        val a = harness.path("a.lua")
        val b = harness.path("b.lua")

        // Sanity: graph is complete (integration fixture, not the primary assertion surface).
        val graph = harness.snapshot.graph
        assertEquals(listOf("a"), graph.resolvedDependencies.getValue(main).map { it.moduleName })
        assertEquals(listOf("b"), graph.resolvedDependencies.getValue(a).map { it.moduleName })
        assertEquals(a, graph.resolvedDependencies.getValue(main).single().provider.path)
        assertEquals(b, graph.resolvedDependencies.getValue(a).single().provider.path)

        val resolvedA = harness.queries.resolveRequire(main, "a")
        val resolvedB = harness.queries.resolveRequire(a, "b")
        assertEquals(a, resolvedA.provider?.path)
        assertEquals(b, resolvedB.provider?.path)
        val aSurface = assertNotNull(resolvedA.exportSurface)
        val bSurface = assertNotNull(resolvedB.exportSurface)
        assertTrue(bSurface.members.any { it.exportPath == listOf("value") })
        assertTrue(bSurface.members.any { it.exportPath == listOf("run") })
        assertTrue(aSurface.members.any { it.exportPath == listOf("value") })
        assertTrue(aSurface.members.any { it.exportPath == listOf("run") })

        // Completions on `a.value` / `a.run` must surface re-exported leaf members.
        val valueCompletions = harness.queries.completions(
            main,
            harness.positionOf("main.lua", "value", occurrence = 2)
        )
        val runCompletions = harness.queries.completions(
            main,
            harness.positionOf("main.lua", "run", occurrence = 2)
        )

        assertTrue(
            valueCompletions.any { it.label == "value" },
            "Two-hop completions through require(\"a\") must include leaf field `value`: $valueCompletions"
        )
        assertTrue(
            valueCompletions.any { it.label == "run" },
            "Two-hop completions through require(\"a\") must include leaf member `run`: $valueCompletions"
        )
        assertTrue(
            runCompletions.any { it.label == "value" },
            "Member completions on require alias must still list sibling leaf field `value`: $runCompletions"
        )
        assertTrue(
            runCompletions.any { it.label == "run" },
            "Member completions on require alias must list leaf member `run`: $runCompletions"
        )
    }

    @Test
    fun missing_module_in_longer_chain_completion_query_does_not_hang() {
        // main → a → b → missing("c")
        val harness = WorkspaceSemanticHarness.build(
            "b.lua" to """
                local c = require("c")
                local M = {}
                M.value = 1
                return M
            """.trimIndent(),
            "a.lua" to """
                local b = require("b")
                local M = {}
                M.value = b.value
                return M
            """.trimIndent(),
            "main.lua" to """
                local a = require("a")
                local value = a.value
                return value
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val a = harness.path("a.lua")
        val b = harness.path("b.lua")

        assertTrue(harness.snapshot.files.containsKey(main))
        assertTrue(harness.snapshot.files.containsKey(a))
        assertTrue(harness.snapshot.files.containsKey(b))
        assertNull(harness.queries.resolveRequire(b, "c").provider)

        // Completion query completes (no hang) and surfaces re-exported mid field when present.
        val completions = harness.queries.completions(
            main,
            harness.positionOf("main.lua", "value", occurrence = 2)
        )
        assertTrue(
            completions.any { it.label == "value" },
            "Complete upstream hops should still surface re-exported `value`: $completions"
        )
        assertNull(harness.queries.lookupModule("c").provider)
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
