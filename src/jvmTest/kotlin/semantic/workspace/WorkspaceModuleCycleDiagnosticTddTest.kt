package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-262 corpus: workspace module-cycle **diagnostic stability** across reanalyze.
 *
 * Acceptance:
 * - Cycles A→B→A produce stable diagnostic codes/messages across reanalyze.
 * - No infinite loop on cycle detection.
 * - Test-only; product sources are out of scope. Verification is review-owned (no Gradle here).
 *
 * Cycle presence is observed on [WorkspaceModuleGraph.stronglyConnectedComponents] /
 * [WorkspaceModuleGraph.stronglyConnectedComponentByFile]. Per-file diagnostics come from
 * [LuaWorkspaceQueryFacade.diagnostics] (checker pipeline surface). Product currently does
 * not emit a dedicated `workspace.module.cycle` code; this corpus locks **stability** of
 * whatever codes/messages are produced for a cyclic workspace, plus termination.
 *
 * "Reanalyze" means both:
 * 1. Independent full [LuaWorkspaceEngine.build] passes on identical sources.
 * 2. [LuaWorkspaceEngine.update] that re-upserts the same cycle sources (dirty rebuild).
 */
class WorkspaceModuleCycleDiagnosticTddTest {

    @Test
    fun mutual_ab_cycle_is_detected_as_one_scc_without_hanging() {
        val engine = LuaWorkspaceEngine()
        val result = engine.build(LuaWorkspaceInput(files = abCycleFiles()))

        val a = path("cycle/a.lua")
        val b = path("cycle/b.lua")
        val graph = result.snapshot.graph
        val component = graph.stronglyConnectedComponentByFile.getValue(a)

        assertEquals(setOf(a, b), component)
        assertEquals(component, graph.stronglyConnectedComponentByFile.getValue(b))
        assertTrue(graph.stronglyConnectedComponents.any { it == setOf(a, b) })
        assertEquals(setOf(a, b, path("app/main.lua")), result.affectedDocuments)
        assertTrue(result.affectedDocuments.size < 10_000)
    }

    @Test
    fun ab_cycle_with_return_mismatch_emits_stable_codes_across_independent_builds() {
        val sources = abCycleWithReturnMismatchFiles()
        val first = collectWorkspaceDiagnostics(sources)
        val second = collectWorkspaceDiagnostics(sources)
        val third = collectWorkspaceDiagnostics(sources)

        assertTrue(
            first.codes.isNotEmpty(),
            "fixture must surface at least one checker diagnostic so stability is meaningful; got empty codes"
        )
        assertEquals(first.codes, second.codes, "diagnostic codes must be stable across reanalyze builds")
        assertEquals(first.codes, third.codes, "diagnostic codes must remain stable on third reanalyze")
        assertEquals(
            first.fingerprints,
            second.fingerprints,
            "diagnostic code/message/severity/range fingerprints must be stable across reanalyze"
        )
        assertEquals(first.fingerprints, third.fingerprints)
        assertTrue(first.totalCount < 10_000, "cycle analysis must terminate with finite diagnostics")
        assertEquals(first.totalCount, second.totalCount)
        assertEquals(first.totalCount, third.totalCount)
    }

    @Test
    fun ab_cycle_diagnostics_remain_stable_after_engine_update_reupsert() {
        val engine = LuaWorkspaceEngine()
        val sources = abCycleWithReturnMismatchFiles()
        val initial = engine.build(LuaWorkspaceInput(files = sources))
        val firstFacade = LuaWorkspaceQueryFacade(initial.snapshot)
        val first = diagnosticBundle(firstFacade, sources.keys)

        // Re-upsert identical sources → dirty reanalyze of the cycle without content change.
        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(upserts = sources)
        )
        val secondFacade = LuaWorkspaceQueryFacade(updated.snapshot)
        val second = diagnosticBundle(secondFacade, sources.keys)

        val a = path("cycle/a.lua")
        val b = path("cycle/b.lua")
        val component = updated.snapshot.graph.stronglyConnectedComponentByFile.getValue(a)

        assertEquals(setOf(a, b), component)
        assertEquals(first.codes, second.codes, "update reanalyze must keep diagnostic codes stable")
        assertEquals(
            first.fingerprints,
            second.fingerprints,
            "update reanalyze must keep diagnostic messages/codes fingerprints stable"
        )
        assertTrue(second.totalCount < 10_000, "update reanalyze of cycle must not loop infinitely")
        assertEquals(first.totalCount, second.totalCount)
    }

    @Test
    fun ab_cycle_harness_diagnostics_are_order_normalized_stable_across_rebuild() {
        val sources = abCycleWithReturnMismatchFiles().mapKeys { it.key.value }
        val firstHarness = WorkspaceSemanticHarness.build(
            *sources.toList().toTypedArray(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
        )
        val secondHarness = WorkspaceSemanticHarness.build(
            *sources.toList().toTypedArray(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
        )

        val first = diagnosticBundle(
            firstHarness.queries,
            sources.keys.map { VirtualPath.of(it) }.toSet()
        )
        val second = diagnosticBundle(
            secondHarness.queries,
            sources.keys.map { VirtualPath.of(it) }.toSet()
        )

        val cycle = firstHarness.snapshot.graph.stronglyConnectedComponentByFile
            .getValue(firstHarness.path("cycle/a.lua"))
        assertEquals(
            setOf(firstHarness.path("cycle/a.lua"), firstHarness.path("cycle/b.lua")),
            cycle
        )
        assertEquals(first.codes, second.codes)
        assertEquals(first.fingerprints, second.fingerprints)
        assertTrue(first.totalCount < 10_000)
    }

    @Test
    fun three_module_cycle_terminates_and_keeps_stable_diagnostic_surface() {
        val sources = mapOf(
            path("cycle/a.lua") to """
                local b = require("cycle.b")
                ---@return string
                local function tag()
                    return 1
                end
                return { next = b, tag = tag }
            """.trimIndent(),
            path("cycle/b.lua") to """
                local c = require("cycle.c")
                return { next = c }
            """.trimIndent(),
            path("cycle/c.lua") to """
                local a = require("cycle.a")
                return { next = a }
            """.trimIndent()
        )

        val first = collectWorkspaceDiagnostics(sources)
        val second = collectWorkspaceDiagnostics(sources)

        val engine = LuaWorkspaceEngine()
        val graph = engine.build(LuaWorkspaceInput(files = sources)).snapshot.graph
        val component = graph.stronglyConnectedComponentByFile.getValue(path("cycle/a.lua"))

        assertEquals(
            setOf(path("cycle/a.lua"), path("cycle/b.lua"), path("cycle/c.lua")),
            component
        )
        assertEquals(first.codes, second.codes)
        assertEquals(first.fingerprints, second.fingerprints)
        assertTrue(first.totalCount < 10_000, "three-module cycle must not hang diagnostics")
        assertTrue(
            "checker.function.return.typeMismatch" in first.codes,
            "annotated return mismatch in cycle fixture must keep stable code; codes=${first.codes}"
        )
    }

    @Test
    fun acyclic_workspace_does_not_report_cycle_noise_and_stays_stable() {
        val sources = mapOf(
            path("lib/dep.lua") to "return { value = 1 }",
            path("app/main.lua") to """
                ---@return string
                local function render()
                    local dep = require("lib.dep")
                    return dep.value
                end
                return render()
            """.trimIndent()
        )

        val first = collectWorkspaceDiagnostics(sources)
        val second = collectWorkspaceDiagnostics(sources)
        val graph = LuaWorkspaceEngine()
            .build(LuaWorkspaceInput(files = sources))
            .snapshot
            .graph

        assertTrue(
            graph.stronglyConnectedComponents.none { it.size > 1 },
            "acyclic workspace must not invent multi-file SCCs"
        )
        assertEquals(first.codes, second.codes)
        assertEquals(first.fingerprints, second.fingerprints)
        assertFalse(
            first.fingerprints.any { fingerprint ->
                val message = fingerprint[1] as? String ?: ""
                val code = fingerprint[0] as? String ?: ""
                message.contains("cycle", ignoreCase = true) ||
                    message.contains("circular", ignoreCase = true) ||
                    code.contains("cycle", ignoreCase = true)
            },
            "acyclic workspace must not invent cycle diagnostics; fingerprints=${first.fingerprints}"
        )
        assertTrue(
            "checker.function.return.typeMismatch" in first.codes,
            "acyclic control fixture still expects stable return mismatch; codes=${first.codes}"
        )
    }

    @Test
    fun repeated_empty_delta_update_on_cycle_does_not_grow_diagnostics() {
        val engine = LuaWorkspaceEngine()
        val sources = abCycleWithReturnMismatchFiles()
        var snapshot = engine.build(LuaWorkspaceInput(files = sources)).snapshot
        val baseline = diagnosticBundle(LuaWorkspaceQueryFacade(snapshot), sources.keys)

        repeat(5) {
            val updated = engine.update(
                previous = snapshot,
                delta = WorkspaceDelta()
            )
            snapshot = updated.snapshot
            assertTrue(updated.affectedDocuments.isEmpty(), "empty delta must not re-dirty cycle")
            val bundle = diagnosticBundle(LuaWorkspaceQueryFacade(snapshot), sources.keys)
            assertEquals(baseline.codes, bundle.codes)
            assertEquals(baseline.fingerprints, bundle.fingerprints)
            assertEquals(baseline.totalCount, bundle.totalCount)
        }
    }

    @Test
    fun cycle_detection_plus_diagnostics_complete_within_finite_bound_for_large_repeat_reanalyze() {
        val sources = abCycleWithReturnMismatchFiles()
        val engine = LuaWorkspaceEngine()
        val seen = mutableListOf<DiagnosticBundle>()

        // Bounded reanalyze loop: must finish and keep a constant diagnostic surface.
        repeat(8) {
            val result = engine.build(LuaWorkspaceInput(files = sources))
            val facade = LuaWorkspaceQueryFacade(result.snapshot)
            val bundle = diagnosticBundle(facade, sources.keys)
            seen += bundle
            assertTrue(bundle.totalCount < 10_000, "reanalyze #$it must terminate")
            val component = result.snapshot.graph.stronglyConnectedComponentByFile
                .getValue(path("cycle/a.lua"))
            assertEquals(setOf(path("cycle/a.lua"), path("cycle/b.lua")), component)
        }

        val baseline = seen.first()
        seen.forEachIndexed { index, bundle ->
            assertEquals(baseline.codes, bundle.codes, "codes drifted at reanalyze #$index")
            assertEquals(
                baseline.fingerprints,
                bundle.fingerprints,
                "fingerprints drifted at reanalyze #$index"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures / helpers
    // -------------------------------------------------------------------------

    private fun abCycleFiles(): Map<VirtualPath, String> = mapOf(
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

    /**
     * A↔B require cycle with an intentional annotated return mismatch so the checker
     * emits a stable product code (`checker.function.return.typeMismatch`) inside the
     * cyclic workspace. That gives the stability corpus a non-empty diagnostic surface.
     */
    private fun abCycleWithReturnMismatchFiles(): Map<VirtualPath, String> = mapOf(
        path("cycle/a.lua") to """
            local b = require("cycle.b")
            ---@return string
            local function label()
                return 1
            end
            local M = { tag = "a", peer = b, label = label }
            return M
        """.trimIndent(),
        path("cycle/b.lua") to """
            local a = require("cycle.a")
            ---@return number
            local function label()
                return "b"
            end
            local M = { tag = "b", peer = a, label = label }
            return M
        """.trimIndent(),
        path("app/main.lua") to """
            local a = require("cycle.a")
            return a.label()
        """.trimIndent()
    )

    private fun collectWorkspaceDiagnostics(files: Map<VirtualPath, String>): DiagnosticBundle {
        val result = LuaWorkspaceEngine().build(LuaWorkspaceInput(files = files))
        return diagnosticBundle(LuaWorkspaceQueryFacade(result.snapshot), files.keys)
    }

    private fun diagnosticBundle(
        queries: LuaWorkspaceQueryFacade,
        paths: Set<VirtualPath>
    ): DiagnosticBundle {
        val all = paths
            .sortedBy { it.value }
            .flatMap { path -> queries.diagnostics(path).map { path to it } }
        val fingerprints = all
            .map { (path, diagnostic) -> diagnosticFingerprint(path, diagnostic) }
            .sortedWith(fingerprintComparator)
        val codes = all.mapNotNull { it.second.code }.toSet()
        return DiagnosticBundle(
            codes = codes,
            fingerprints = fingerprints,
            totalCount = all.size
        )
    }

    private fun diagnosticFingerprint(path: VirtualPath, diagnostic: Diagnostic): List<Any?> {
        return listOf(
            diagnostic.code,
            diagnostic.message,
            diagnostic.severity.name,
            path.value,
            diagnostic.range?.start?.line,
            diagnostic.range?.start?.column,
            diagnostic.range?.end?.line,
            diagnostic.range?.end?.column
        )
    }

    private fun path(value: String): VirtualPath = VirtualPath.of(value)

    private data class DiagnosticBundle(
        val codes: Set<String>,
        val fingerprints: List<List<Any?>>,
        val totalCount: Int
    )

    private companion object {
        val fingerprintComparator: Comparator<List<Any?>> = Comparator { left, right ->
            val size = minOf(left.size, right.size)
            for (i in 0 until size) {
                val l = left[i]?.toString() ?: ""
                val r = right[i]?.toString() ?: ""
                val cmp = l.compareTo(r)
                if (cmp != 0) return@Comparator cmp
            }
            left.size.compareTo(right.size)
        }
    }
}
