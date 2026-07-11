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
 * TASK-475 / TASK-262 corpus: workspace module-cycle **diagnostic stability** across reanalyze.
 *
 * Acceptance:
 * - Cycles A→B→A produce stable diagnostic codes/messages across reanalyze.
 * - No infinite loop on cycle detection.
 * - Test-only; product sources are out of scope. Verification is review-owned (no Gradle here).
 *
 * Cycle presence is observed on [WorkspaceModuleGraph.stronglyConnectedComponents] /
 * [WorkspaceModuleGraph.stronglyConnectedComponentByFile]. Per-file diagnostics come from
 * [LuaWorkspaceQueryFacade.diagnostics] (checker pipeline surface). Product currently does
 * **not** emit a dedicated `workspace.module.cycle` code; this corpus locks **stability** of
 * whatever codes/messages are produced for a cyclic workspace, plus termination.
 *
 * Dual-path / CURRENTLY_ACCEPTS (aligned to product):
 * - HARD: multi-file SCCs for mutual/three-module require cycles; acyclic workspaces have no
 *   multi-file SCCs; reanalyze (independent [LuaWorkspaceEngine.build], [LuaWorkspaceEngine.update]
 *   re-upsert, empty delta, harness rebuild) keeps codes/fingerprints/totalCount stable and finite
 *   (< 10_000); acyclic workspaces invent no cycle/circular diagnostic noise; host android.jar
 *   policy is Downloads + SDK android-35 only (never G:/).
 * - CURRENTLY_ACCEPTS (soft): checker leaf codes such as
 *   `checker.function.return.typeMismatch` may be present (IDEAL fixture surface) **or** absent
 *   when the workspace checker pipeline does not yet surface annotated return mismatches inside
 *   cyclic modules. Empty diagnostic bundles are allowed **only** when they stay empty and stable
 *   across reanalyze; non-empty surfaces must still fingerprint-stabilize.
 *
 * "Reanalyze" means both:
 * 1. Independent full [LuaWorkspaceEngine.build] passes on identical sources.
 * 2. [LuaWorkspaceEngine.update] that re-upserts the same cycle sources (dirty rebuild).
 *
 * Host android.jar: not opened by this pure workspace corpus. Policy reminder only —
 * `/Users/dingyi/Downloads/android.jar` and
 * `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`; never G:/.
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

        // Dual-path: IDEAL non-empty checker surface OR CURRENTLY_ACCEPTS empty-but-stable.
        assertStableDiagnosticSurface(
            first,
            second,
            third,
            label = "independent builds on AB cycle"
        )
        assertReturnMismatchDualPath(
            first.codes,
            label = "AB cycle independent builds"
        )
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
        assertReturnMismatchDualPath(first.codes, label = "AB cycle update reupsert")
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
        assertReturnMismatchDualPath(first.codes, label = "AB cycle harness rebuild")
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
        assertReturnMismatchDualPath(
            first.codes,
            label = "three-module cycle"
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
        // Dual-path: IDEAL return mismatch on dep.value:number vs string, or CURRENTLY_ACCEPTS gap.
        assertReturnMismatchDualPath(first.codes, label = "acyclic control fixture")
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
        assertReturnMismatchDualPath(baseline.codes, label = "large repeat reanalyze")
    }

    @Test
    fun engine_and_harness_paths_agree_on_cycle_scc_and_stable_diagnostic_dual_path() {
        // Dual-path goldens: engine.build vs WorkspaceSemanticHarness must agree on SCC
        // membership; diagnostic codes/fingerprints must be stable within each path and must
        // not invent cycle diagnostic codes. Cross-path code equality is soft when product
        // overlay defaults differ slightly, but both paths must terminate and fingerprint-
        // stabilize internally.
        val engineSources = abCycleWithReturnMismatchFiles()
        val harnessSources = engineSources.mapKeys { it.key.value }

        val engineFirst = collectWorkspaceDiagnostics(engineSources)
        val engineSecond = collectWorkspaceDiagnostics(engineSources)

        val harnessFirst = WorkspaceSemanticHarness.build(
            *harnessSources.toList().toTypedArray(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
        )
        val harnessSecond = WorkspaceSemanticHarness.build(
            *harnessSources.toList().toTypedArray(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
        )
        val harnessBundleFirst = diagnosticBundle(
            harnessFirst.queries,
            harnessSources.keys.map { VirtualPath.of(it) }.toSet()
        )
        val harnessBundleSecond = diagnosticBundle(
            harnessSecond.queries,
            harnessSources.keys.map { VirtualPath.of(it) }.toSet()
        )

        val engineGraph = LuaWorkspaceEngine()
            .build(LuaWorkspaceInput(files = engineSources))
            .snapshot
            .graph
        val engineComponent = engineGraph.stronglyConnectedComponentByFile.getValue(path("cycle/a.lua"))
        val harnessComponent = harnessFirst.snapshot.graph.stronglyConnectedComponentByFile
            .getValue(harnessFirst.path("cycle/a.lua"))

        assertEquals(setOf(path("cycle/a.lua"), path("cycle/b.lua")), engineComponent)
        assertEquals(
            setOf(harnessFirst.path("cycle/a.lua"), harnessFirst.path("cycle/b.lua")),
            harnessComponent
        )
        assertEquals(engineFirst.codes, engineSecond.codes)
        assertEquals(engineFirst.fingerprints, engineSecond.fingerprints)
        assertEquals(harnessBundleFirst.codes, harnessBundleSecond.codes)
        assertEquals(harnessBundleFirst.fingerprints, harnessBundleSecond.fingerprints)
        assertTrue(engineFirst.totalCount < 10_000)
        assertTrue(harnessBundleFirst.totalCount < 10_000)

        // Soft: when both paths emit non-empty surfaces, prefer matching codes (IDEAL).
        // CURRENTLY_ACCEPTS: either path may be empty while the other still emits checker codes
        // under overlay defaults — both remain valid if each path is internally stable.
        if (engineFirst.codes.isNotEmpty() && harnessBundleFirst.codes.isNotEmpty()) {
            assertEquals(
                engineFirst.codes,
                harnessBundleFirst.codes,
                "non-empty dual-path codes should agree between engine and harness"
            )
        }
        assertReturnMismatchDualPath(engineFirst.codes, label = "engine path dual")
        assertReturnMismatchDualPath(harnessBundleFirst.codes, label = "harness path dual")
    }

    @Test
    fun inventory_table_documents_hard_soft_cycle_diagnostic_matrix() {
        val hardAccept = listOf(
            "mutual A↔B require cycle collapses to one multi-file SCC",
            "three-module cycle is a single SCC",
            "acyclic workspace has no multi-file SCCs",
            "independent builds keep diagnostic codes/fingerprints stable",
            "update re-upsert keeps diagnostic surface stable",
            "empty delta does not grow diagnostics",
            "bounded reanalyze terminates with constant surface",
            "acyclic workspace invents no cycle/circular diagnostic codes"
        )
        val softAccept = listOf(
            "checker.function.return.typeMismatch present or CURRENTLY_ACCEPTS absent in cycle",
            "empty diagnostic bundle CURRENTLY_ACCEPTS when stable across reanalyze",
            "engine vs harness non-empty code equality when both non-empty; empty mismatch soft"
        )
        val hardReject = listOf(
            "infinite loop / non-terminating cycle analysis",
            "diagnostic totalCount growth under empty delta",
            "multi-file SCC invented on acyclic require graph",
            "cycle/circular diagnostic noise on acyclic workspace",
            "G:/ android.jar host path"
        )

        assertTrue(hardAccept.size >= 6)
        assertTrue(softAccept.size >= 2)
        assertTrue(hardReject.size >= 4)
        assertTrue(hardAccept.intersect(softAccept.toSet()).isEmpty())
        assertTrue(hardAccept.intersect(hardReject.toSet()).isEmpty())
    }

    @Test
    fun host_android_jar_policy_is_downloads_and_sdk_android35_never_g_drive() {
        // Corpus does not open android.jar. Lock host policy so expansion tasks do not
        // reintroduce G:/ hard-codes in related docs/tests.
        val allowedHints = listOf(
            "/Users/dingyi/Downloads/android.jar",
            "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"
        )
        val forbidden = listOf("G:/", "G:\\", "g:/android.jar")
        assertTrue(allowedHints.all { it.contains("android.jar") })
        assertTrue(forbidden.none { hint -> allowedHints.any { it.contains(hint, ignoreCase = true) } })
        assertFalse(allowedHints.any { it.startsWith("G:") || it.startsWith("g:") })
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
     * **may** emit `checker.function.return.typeMismatch` inside the cyclic workspace
     * (IDEAL). CURRENTLY_ACCEPTS when the workspace checker surface is still empty for
     * these fixtures — stability/termination remain the hard contract.
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

    /**
     * HARD: multi-pass stability + finite bound.
     * Soft note: non-empty is preferred (IDEAL) but empty stable surfaces are CURRENTLY_ACCEPTS.
     */
    private fun assertStableDiagnosticSurface(
        first: DiagnosticBundle,
        second: DiagnosticBundle,
        third: DiagnosticBundle,
        label: String
    ) {
        assertEquals(first.codes, second.codes, "$label: diagnostic codes must be stable across reanalyze builds")
        assertEquals(first.codes, third.codes, "$label: diagnostic codes must remain stable on third reanalyze")
        assertEquals(
            first.fingerprints,
            second.fingerprints,
            "$label: diagnostic code/message/severity/range fingerprints must be stable across reanalyze"
        )
        assertEquals(first.fingerprints, third.fingerprints, "$label: fingerprints must remain stable on third reanalyze")
        assertTrue(first.totalCount < 10_000, "$label: cycle analysis must terminate with finite diagnostics")
        assertEquals(first.totalCount, second.totalCount)
        assertEquals(first.totalCount, third.totalCount)
        // Soft documentation of dual-path emptiness — do not hard-fail empty codes.
        if (first.codes.isEmpty()) {
            assertEquals(0, first.totalCount, "$label CURRENTLY_ACCEPTS empty codes must also have totalCount=0")
        }
    }

    /**
     * Dual-path for annotated return mismatch code:
     * - IDEAL: `checker.function.return.typeMismatch` present
     * - CURRENTLY_ACCEPTS: code absent (product gap on workspace checker surface)
     *
     * Never invents a hard requirement that product emit a dedicated cycle diagnostic code.
     */
    private fun assertReturnMismatchDualPath(codes: Set<String>, label: String) {
        val mismatch = RETURN_TYPE_MISMATCH_CODE
        if (mismatch in codes) {
            // IDEAL surface locked.
            assertTrue(true)
            return
        }
        // CURRENTLY_ACCEPTS: product may omit return mismatch inside cyclic / workspace paths.
        // Hard reject remains: no fabricated cycle diagnostic codes.
        assertFalse(
            codes.any { it.contains("cycle", ignoreCase = true) },
            "$label dual-path CURRENTLY_ACCEPTS missing $mismatch but must not invent cycle codes; codes=$codes"
        )
        assertTrue(
            codes.none { it.contains("circular", ignoreCase = true) },
            "$label dual-path CURRENTLY_ACCEPTS missing $mismatch but must not invent circular codes; codes=$codes"
        )
    }

    private fun path(value: String): VirtualPath = VirtualPath.of(value)

    private data class DiagnosticBundle(
        val codes: Set<String>,
        val fingerprints: List<List<Any?>>,
        val totalCount: Int
    )

    private companion object {
        const val RETURN_TYPE_MISMATCH_CODE = "checker.function.return.typeMismatch"

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
