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
    fun two_hop_require_chain_completion_kinds_match_export_surface() {
        val harness = chainHarness()
        val main = harness.path("main.lua")

        val completions = harness.queries.completions(
            main,
            harness.positionOf("main.lua", "value", occurrence = 2)
        )

        val valueItem = assertNotNull(
            completions.firstOrNull { it.label == "value" },
            "Expected completion label `value`; actual=$completions"
        )
        val runItem = assertNotNull(
            completions.firstOrNull { it.label == "run" },
            "Expected completion label `run`; actual=$completions"
        )

        // Field vs function kinds follow export collector surface (product snapshot).
        assertTrue(
            valueItem.kind == CompletionItemKind.FIELD || valueItem.kind == CompletionItemKind.VARIABLE,
            "Leaf field `value` should complete as FIELD/VARIABLE, was ${valueItem.kind}"
        )
        assertTrue(
            runItem.kind == CompletionItemKind.FUNCTION ||
                runItem.kind == CompletionItemKind.METHOD ||
                runItem.kind == CompletionItemKind.FIELD,
            "Leaf function `run` should complete as FUNCTION/METHOD/FIELD, was ${runItem.kind}"
        )
    }

    @Test
    fun pure_return_require_mid_module_completions_degrade_empty_for_leaf_labels() {
        // a.lua = return require("b") — product does not invent a mid export surface.
        // Completions through require("a") must not invent leaf labels from b.
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

        val main = harness.path("main.lua")
        val resolvedA = harness.queries.resolveRequire(main, "a")
        assertNull(
            resolvedA.provider,
            "Pure return-require mid module has no export surface for resolveRequire."
        )
        assertNull(resolvedA.exportSurface)

        val titleCompletions = harness.queries.completions(
            main,
            harness.positionOf("main.lua", "title", occurrence = 2)
        )
        val renderCompletions = harness.queries.completions(
            main,
            harness.positionOf("main.lua", "render", occurrence = 2)
        )

        assertFalse(
            titleCompletions.any { it.label == "title" },
            "Pure re-export mid must not invent leaf completion `title`: $titleCompletions"
        )
        assertFalse(
            titleCompletions.any { it.label == "render" },
            "Pure re-export mid must not invent leaf completion `render`: $titleCompletions"
        )
        assertFalse(
            renderCompletions.any { it.label == "title" },
            "Pure re-export mid must not invent leaf completion `title` via render site: $renderCompletions"
        )
        assertFalse(
            renderCompletions.any { it.label == "render" },
            "Pure re-export mid must not invent leaf completion `render`: $renderCompletions"
        )

        // Leaf remains queryable when required directly (chain mid does not poison leaf).
        val aPath = harness.path("a.lua")
        val leaf = harness.queries.resolveRequire(aPath, "b")
        assertEquals(harness.path("b.lua"), leaf.provider?.path)
        assertTrue(assertNotNull(leaf.exportSurface).members.any { it.name == "title" })
    }

    @Test
    fun missing_mid_module_member_completions_degrade_empty() {
        val harness = WorkspaceSemanticHarness.build(
            "b.lua" to """
                return { value = 1, run = function() end }
            """.trimIndent(),
            "main.lua" to """
                local a = require("a")
                local value = a.value
                local run = a.run
                return value, run
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        assertTrue(harness.snapshot.files.containsKey(main))

        val missing = harness.queries.resolveRequire(main, "a")
        assertNull(missing.provider)
        assertNull(missing.exportSurface)
        assertNull(harness.queries.lookupModule("a").provider)

        val valueCompletions = harness.queries.completions(
            main,
            harness.positionOf("main.lua", "value", occurrence = 2)
        )
        val runCompletions = harness.queries.completions(
            main,
            harness.positionOf("main.lua", "run", occurrence = 2)
        )

        // Empty degrade: no invented leaf labels from unrelated b.lua or a phantom module.
        assertFalse(
            valueCompletions.any { it.label == "value" },
            "Missing mid module must not invent completion `value`: $valueCompletions"
        )
        assertFalse(
            valueCompletions.any { it.label == "run" },
            "Missing mid module must not invent completion `run`: $valueCompletions"
        )
        assertFalse(
            runCompletions.any { it.label == "value" },
            "Missing mid module must not invent completion `value` at run site: $runCompletions"
        )
        assertFalse(
            runCompletions.any { it.label == "run" },
            "Missing mid module must not invent completion `run`: $runCompletions"
        )

        // Unrelated present leaf stays independently queryable.
        val leaf = harness.queries.lookupModule("b")
        assertEquals(harness.path("b.lua"), leaf.provider?.path)
        assertNotNull(leaf.exportSurface)
    }

    @Test
    fun missing_leaf_module_does_not_poison_mid_module_completion_surface() {
        // main → a → missing("b"). Mid still resolves; leaf absence does not hang analysis.
        val harness = WorkspaceSemanticHarness.build(
            "a.lua" to """
                local b = require("b")
                local M = {}
                M.value = 42
                M.tag = "mid"
                return M
            """.trimIndent(),
            "main.lua" to """
                local a = require("a")
                local value = a.value
                local tag = a.tag
                return value, tag
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val a = harness.path("a.lua")

        assertTrue(harness.snapshot.files.containsKey(main))
        assertTrue(harness.snapshot.files.containsKey(a))

        val graph = harness.snapshot.graph
        assertEquals(listOf("a"), graph.resolvedDependencies.getValue(main).map { it.moduleName })
        assertFalse(graph.resolvedDependencies.containsKey(a))
        assertEquals("b", graph.unresolvedStaticRequires.getValue(a).single().moduleName)

        val missingLeaf = harness.queries.resolveRequire(a, "b")
        assertNull(missingLeaf.provider)
        assertNull(missingLeaf.exportSurface)

        val resolvedA = harness.queries.resolveRequire(main, "a")
        assertEquals(a, resolvedA.provider?.path)
        assertNotNull(resolvedA.exportSurface)

        val completions = harness.queries.completions(
            main,
            harness.positionOf("main.lua", "value", occurrence = 2)
        )
        assertTrue(
            completions.any { it.label == "value" },
            "Mid-owned export `value` must still complete when leaf hop is missing: $completions"
        )
        assertTrue(
            completions.any { it.label == "tag" },
            "Mid-owned export `tag` must still complete when leaf hop is missing: $completions"
        )
        // Do not invent a completion label that only exists on the missing leaf.
        assertFalse(
            completions.any { it.label == "from_missing_leaf" },
            "Missing leaf must not invent phantom completion labels: $completions"
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
