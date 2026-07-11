package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.AnalysisProgress
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.ProgressReporter
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceUpdateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-238 corpus: AnalysisProgress cooperative cancel hooks.
 *
 * Acceptance:
 * - Progress reporter cooperative cancel is observable mid-analysis without corrupt snapshots.
 * - No cancel leaves a full snapshot.
 *
 * Test-only; product code is out of scope. Verification is review-owned (no Gradle here).
 *
 * Product contract encoded here:
 * - [ProgressReporter.report] is the cooperative cancel hook. A throwable raised from
 *   `report` aborts [LuaWorkspaceEngine.build] / [LuaWorkspaceEngine.update] mid-flight.
 * - The engine builds snapshots locally and only returns a [WorkspaceUpdateResult] on
 *   successful completion. Cancel therefore never publishes a half-built result: callers
 *   keep any previous snapshot reference unchanged (no corrupt published snapshot).
 * - Successful runs always end with [AnalysisProgress.Phase.COMPLETE] and a full
 *   file-keyed snapshot covering every input path.
 */
class AnalysisProgressCancelTddTest {

    private val engine = LuaWorkspaceEngine()

    /**
     * Throwable used by tests to request cooperative cancel from a progress reporter.
     * Any throwable from [ProgressReporter.report] is treated as cancel; this type makes
     * the intent explicit in assertions.
     */
    class CooperativeAnalysisCancelException(
        message: String,
        val observedProgress: List<AnalysisProgress>
    ) : RuntimeException(message)

    // -------------------------------------------------------------------------
    // No cancel → full snapshot
    // -------------------------------------------------------------------------

    @Test
    fun no_cancel_build_ends_with_complete_and_full_snapshot() {
        val progress = mutableListOf<AnalysisProgress>()
        val files = multiFileWorkspace()

        val result = engine.build(
            LuaWorkspaceInput(files = files),
            reporter = ProgressReporter { progress += it }
        )

        assertTrue(progress.isNotEmpty(), "progress must be reported during analysis")
        assertEquals(AnalysisProgress.Phase.COMPLETE, progress.last().phase)
        assertTrue(
            progress.any { it.phase == AnalysisProgress.Phase.PARSING },
            "successful analysis must report PARSING progress"
        )
        assertTrue(
            progress.any { it.phase == AnalysisProgress.Phase.BINDING },
            "successful analysis must report BINDING progress"
        )
        assertTrue(
            progress.zipWithNext().all { (left, right) -> left.completedFiles <= right.completedFiles },
            "completedFiles must be monotonic on a successful run"
        )

        assertEquals(files.keys, result.snapshot.files.keys)
        files.keys.forEach { path ->
            val fileSnapshot = assertNotNull(result.snapshot.files[path], "missing file snapshot for $path")
            assertNotNull(fileSnapshot.documentFacts, "full snapshot must attach document facts for $path")
            assertNotNull(fileSnapshot.semanticFile, "full snapshot must attach semantic file for $path")
            assertEquals(path, fileSnapshot.semanticFile?.path)
        }
        assertFalse(result.snapshot.files.values.any { it.documentFacts == null })
        assertEquals(files.keys, result.affectedDocuments)
    }

    @Test
    fun no_cancel_update_ends_with_complete_and_preserves_unrelated_full_files() {
        val initial = engine.build(LuaWorkspaceInput(files = multiFileWorkspace()))
        val progress = mutableListOf<AnalysisProgress>()

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    VirtualPath.of("lib/a.lua") to "local M = { value = 42 }\nreturn M"
                )
            ),
            reporter = ProgressReporter { progress += it }
        )

        assertTrue(progress.isNotEmpty())
        assertEquals(AnalysisProgress.Phase.COMPLETE, progress.last().phase)
        assertEquals(multiFileWorkspace().keys, updated.snapshot.files.keys)
        multiFileWorkspace().keys.forEach { path ->
            val fileSnapshot = assertNotNull(updated.snapshot.files[path])
            assertNotNull(fileSnapshot.documentFacts, "update without cancel must keep full facts for $path")
        }
        assertTrue(VirtualPath.of("lib/a.lua") in updated.affectedDocuments)
    }

    // -------------------------------------------------------------------------
    // Cooperative cancel mid-analysis
    // -------------------------------------------------------------------------

    @Test
    fun cooperative_cancel_during_parsing_is_observable_and_aborts_build() {
        val progress = mutableListOf<AnalysisProgress>()
        val files = multiFileWorkspace()

        val cancel = assertFailsWith<CooperativeAnalysisCancelException> {
            engine.build(
                LuaWorkspaceInput(files = files),
                reporter = cancellingReporter(
                    progress = progress,
                    cancelAfter = { observed ->
                        observed.count { it.phase == AnalysisProgress.Phase.PARSING } >= 1
                    },
                    message = "cancel after first PARSING report"
                )
            )
        }

        assertTrue(progress.isNotEmpty(), "cancel must be observable after progress was reported")
        assertTrue(
            progress.any { it.phase == AnalysisProgress.Phase.PARSING },
            "cancel must occur after mid-analysis PARSING progress"
        )
        assertFalse(
            progress.any { it.phase == AnalysisProgress.Phase.COMPLETE },
            "cancelled analysis must not report COMPLETE"
        )
        assertEquals(progress, cancel.observedProgress)
        assertTrue(cancel.message!!.contains("cancel after first PARSING report"))
    }

    @Test
    fun cooperative_cancel_during_binding_is_observable_mid_analysis() {
        val progress = mutableListOf<AnalysisProgress>()

        assertFailsWith<CooperativeAnalysisCancelException> {
            engine.build(
                LuaWorkspaceInput(files = multiFileWorkspace()),
                reporter = cancellingReporter(
                    progress = progress,
                    cancelAfter = { observed ->
                        observed.any { it.phase == AnalysisProgress.Phase.BINDING }
                    },
                    message = "cancel on first BINDING report"
                )
            )
        }

        assertTrue(progress.any { it.phase == AnalysisProgress.Phase.PARSING })
        assertTrue(progress.any { it.phase == AnalysisProgress.Phase.BINDING })
        assertFalse(progress.any { it.phase == AnalysisProgress.Phase.COMPLETE })
        // At least one file completed parsing progress before binding cancel.
        assertTrue(progress.any { it.phase == AnalysisProgress.Phase.PARSING && it.completedFiles > 0 })
    }

    @Test
    fun cooperative_cancel_does_not_publish_corrupt_or_partial_result() {
        // Holder models a caller that only swaps snapshots when build succeeds.
        var published: WorkspaceSnapshot? = null
        val previous = engine.build(LuaWorkspaceInput(files = multiFileWorkspace())).snapshot
        published = previous

        val progress = mutableListOf<AnalysisProgress>()
        val thrown = runCatching {
            val result = engine.build(
                LuaWorkspaceInput(files = multiFileWorkspace()),
                reporter = cancellingReporter(
                    progress = progress,
                    cancelAfter = { observed ->
                        observed.count { it.phase == AnalysisProgress.Phase.PARSING } >= 2
                    },
                    message = "cancel mid multi-file parse"
                )
            )
            // Successful path would publish; cancel must never reach here.
            published = result.snapshot
            result
        }

        assertTrue(thrown.isFailure, "cancel must fail the build call")
        assertTrue(thrown.exceptionOrNull() is CooperativeAnalysisCancelException)
        assertNull(
            (thrown.getOrNull() as WorkspaceUpdateResult?),
            "cancelled build must not yield a WorkspaceUpdateResult"
        )
        // Published reference remains the previous full snapshot — never a half-built map.
        assertNotNull(published)
        assertEquals(previous, published)
        assertEquals(multiFileWorkspace().keys, published!!.files.keys)
        assertFalse(progress.any { it.phase == AnalysisProgress.Phase.COMPLETE })
        assertTrue(progress.size >= 2, "mid-analysis cancel requires multiple progress observations")
    }

    @Test
    fun cooperative_cancel_on_update_leaves_previous_snapshot_intact() {
        val previousResult = engine.build(LuaWorkspaceInput(files = multiFileWorkspace()))
        val previous = previousResult.snapshot
        val progress = mutableListOf<AnalysisProgress>()

        val failure = runCatching {
            engine.update(
                previous = previous,
                delta = WorkspaceDelta(
                    upserts = mapOf(
                        VirtualPath.of("lib/a.lua") to "return { value = 99 }",
                        VirtualPath.of("lib/b.lua") to "return { value = 100 }"
                    )
                ),
                reporter = cancellingReporter(
                    progress = progress,
                    cancelAfter = { observed ->
                        observed.any { it.phase == AnalysisProgress.Phase.PARSING || it.phase == AnalysisProgress.Phase.BINDING }
                    },
                    message = "cancel mid update"
                )
            )
        }

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is CooperativeAnalysisCancelException)
        // Previous snapshot object is unchanged and still fully keyed.
        assertEquals(multiFileWorkspace().keys, previous.files.keys)
        multiFileWorkspace().keys.forEach { path ->
            assertNotNull(previous.files[path]?.documentFacts, "previous snapshot must stay non-corrupt for $path")
        }
        assertFalse(progress.any { it.phase == AnalysisProgress.Phase.COMPLETE })
        assertTrue(progress.isNotEmpty(), "update cancel must be observable via progress")
    }

    @Test
    fun cancel_after_partial_file_progress_never_reports_complete_counts() {
        val progress = mutableListOf<AnalysisProgress>()
        val files = multiFileWorkspace()

        assertFailsWith<CooperativeAnalysisCancelException> {
            engine.build(
                LuaWorkspaceInput(files = files),
                reporter = cancellingReporter(
                    progress = progress,
                    // Cancel once we have seen a non-terminal progress event with incomplete counts.
                    cancelAfter = { observed ->
                        observed.any {
                            it.phase != AnalysisProgress.Phase.COMPLETE &&
                                it.totalFiles > 0 &&
                                it.completedFiles < it.totalFiles
                        }
                    },
                    message = "cancel while completedFiles < totalFiles"
                )
            )
        }

        val last = progress.last()
        assertTrue(last.phase != AnalysisProgress.Phase.COMPLETE)
        assertTrue(last.totalFiles > 0)
        assertTrue(
            last.completedFiles < last.totalFiles || last.phase != AnalysisProgress.Phase.COMPLETE,
            "mid-analysis cancel must not look like a finished COMPLETE report"
        )
        assertFalse(progress.any { it.phase == AnalysisProgress.Phase.COMPLETE })
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun multiFileWorkspace(): Map<VirtualPath, String> = mapOf(
        VirtualPath.of("lib/a.lua") to "local M = { value = 1 }\nreturn M",
        VirtualPath.of("lib/b.lua") to "local M = { value = 2 }\nreturn M",
        VirtualPath.of("app/main.lua") to """
            local a = require("lib.a")
            local b = require("lib.b")
            return a.value + b.value
        """.trimIndent()
    )

    /**
     * Progress reporter that records every event, then cooperatively cancels once
     * [cancelAfter] becomes true for the accumulated observations (including the
     * current event). Cancel is signalled by throwing [CooperativeAnalysisCancelException].
     */
    private fun cancellingReporter(
        progress: MutableList<AnalysisProgress>,
        cancelAfter: (List<AnalysisProgress>) -> Boolean,
        message: String
    ): ProgressReporter = ProgressReporter { event ->
        progress += event
        if (cancelAfter(progress)) {
            throw CooperativeAnalysisCancelException(message, progress.toList())
        }
    }
}
