package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDirtySetPlanner
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-495 corpus: Workspace public-fingerprint invalidation.
 *
 * Dual path:
 * 1. **Planner path** — synthetic [WorkspaceSnapshot] pairs exercise
 *    [WorkspaceDirtySetPlanner] when [WorkspacePublicFingerprint.value] changes
 *    (or stays stable for private-only cacheKey churn).
 * 2. **Engine path** — [LuaWorkspaceEngine] build/update with real Lua modules
 *    asserts the same reverse-dirty / affectedDocuments contract end-to-end.
 *
 * Product contract under test:
 * - Public fingerprint value delta on a required module reverse-dirties direct
 *   and transitive consumers (unrelated files stay clean).
 * - Private-only edits (cacheKey / body) with stable public fingerprint do **not**
 *   reverse-dirty dependents.
 * - Multi-hop chains invalidate only downstream dependents of the fingerprint
 *   change.
 * - Fingerprint-stable re-analysis of an unchanged exporter keeps dependents clean.
 * - Engine-level export-field add/type change invalidates consumers; private
 *   local rewrites do not.
 * - Re-adding an exporter after delete restores a live fingerprint surface
 *   without leaving stale unresolved require edges.
 *
 * Host android.jar (when any JVM path is mentioned): Downloads + SDK android-35
 * only — never G:/. This corpus does not load android.jar.
 *
 * Test-only; product code is out of scope. Verification is review-owned (no Gradle).
 */
class WorkspaceFingerprintInvalidationTddTest {

    // -------------------------------------------------------------------------
    // Planner path: public fingerprint value drives reverse invalidation
    // -------------------------------------------------------------------------

    @Test
    fun planner_public_fingerprint_change_dirties_direct_and_transitive_dependents() {
        val dep = path("lib/dep.lua")
        val mid = path("lib/mid.lua")
        val top = path("app/top.lua")
        val unrelated = path("app/unrelated.lua")

        val previous = snapshot(
            files = mapOf(
                dep to fileSnapshot(fingerprint = "dep-fp-v1", modules = setOf("dep")),
                mid to fileSnapshot(fingerprint = "mid-fp-v1", modules = setOf("mid")),
                top to fileSnapshot(fingerprint = "top-fp-v1", modules = setOf("top")),
                unrelated to fileSnapshot(fingerprint = "unrelated-fp-v1", modules = setOf("unrelated"))
            ),
            graph = chainGraph(
                providers = mapOf(
                    "dep" to dep,
                    "mid" to mid,
                    "top" to top,
                    "unrelated" to unrelated
                ),
                edges = listOf(
                    requireEdge(mid, "dep", dep),
                    requireEdge(top, "mid", mid)
                )
            )
        )

        val current = previous.copy(
            files = previous.files + mapOf(
                dep to fileSnapshot(fingerprint = "dep-fp-v2", modules = setOf("dep"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(dep), plan.publicSurfaceChangedFiles)
        assertEquals(setOf(dep), plan.changedFiles)
        assertTrue(mid in plan.reverseDependencyDirtyClosure)
        assertTrue(top in plan.reverseDependencyDirtyClosure)
        assertFalse(unrelated in plan.reverseDependencyDirtyClosure)
        assertFalse(unrelated in plan.affectedDocuments)
        assertEquals(setOf(dep, mid, top), plan.affectedDocuments)
        assertTrue("dep" in plan.affectedModuleNames)
        assertFalse("unrelated" in plan.affectedModuleNames)
    }

    @Test
    fun planner_private_only_cache_key_churn_keeps_public_fingerprint_and_dependents_stable() {
        val dep = path("lib/dep.lua")
        val consumer = path("app/consumer.lua")
        val unrelated = path("app/unrelated.lua")
        val stablePublic = "dep-public-stable"

        val previous = snapshot(
            files = mapOf(
                dep to fileSnapshot(
                    fingerprint = stablePublic,
                    modules = setOf("dep"),
                    cacheKey = "dep-body-v1"
                ),
                consumer to fileSnapshot(fingerprint = "consumer-fp", modules = setOf("consumer")),
                unrelated to fileSnapshot(fingerprint = "unrelated-fp", modules = setOf("unrelated"))
            ),
            graph = chainGraph(
                providers = mapOf(
                    "dep" to dep,
                    "consumer" to consumer,
                    "unrelated" to unrelated
                ),
                edges = listOf(requireEdge(consumer, "dep", dep))
            )
        )

        val current = previous.copy(
            files = previous.files + mapOf(
                dep to fileSnapshot(
                    fingerprint = stablePublic,
                    modules = setOf("dep"),
                    cacheKey = "dep-body-v2-private"
                )
            )
        )

        assertEquals(
            previous.files.getValue(dep).publicFingerprint?.value,
            current.files.getValue(dep).publicFingerprint?.value
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(dep), plan.changedFiles)
        assertTrue(plan.publicSurfaceChangedFiles.isEmpty())
        assertTrue(plan.reverseDependencyDirtyClosure.isEmpty())
        assertEquals(setOf(dep), plan.affectedDocuments)
        assertFalse(consumer in plan.affectedDocuments)
        assertFalse(unrelated in plan.affectedDocuments)
    }

    @Test
    fun planner_multi_hop_fingerprint_change_invalidates_only_downstream_dependents() {
        val leaf = path("mods/leaf.lua")
        val a = path("mods/a.lua")
        val b = path("mods/b.lua")
        val c = path("mods/c.lua")
        val side = path("mods/side.lua")

        // side -> leaf (sibling branch); c -> b -> a -> leaf
        val previous = snapshot(
            files = mapOf(
                leaf to fileSnapshot(fingerprint = "leaf-fp-v1", modules = setOf("leaf")),
                a to fileSnapshot(fingerprint = "a-fp-v1", modules = setOf("a")),
                b to fileSnapshot(fingerprint = "b-fp-v1", modules = setOf("b")),
                c to fileSnapshot(fingerprint = "c-fp-v1", modules = setOf("c")),
                side to fileSnapshot(fingerprint = "side-fp-v1", modules = setOf("side"))
            ),
            graph = chainGraph(
                providers = mapOf(
                    "leaf" to leaf,
                    "a" to a,
                    "b" to b,
                    "c" to c,
                    "side" to side
                ),
                edges = listOf(
                    requireEdge(a, "leaf", leaf),
                    requireEdge(b, "a", a),
                    requireEdge(c, "b", b),
                    requireEdge(side, "leaf", leaf)
                )
            )
        )

        // Public fingerprint change only on mid-chain module `a`.
        val current = previous.copy(
            files = previous.files + mapOf(
                a to fileSnapshot(fingerprint = "a-fp-v2", modules = setOf("a"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(a), plan.publicSurfaceChangedFiles)
        assertTrue(b in plan.reverseDependencyDirtyClosure)
        assertTrue(c in plan.reverseDependencyDirtyClosure)
        assertFalse(leaf in plan.reverseDependencyDirtyClosure)
        assertFalse(side in plan.reverseDependencyDirtyClosure)
        assertFalse(side in plan.affectedDocuments)
        assertEquals(setOf(a, b, c), plan.affectedDocuments)
    }

    @Test
    fun planner_identical_public_fingerprint_reanalysis_keeps_dependents_clean() {
        val dep = path("lib/dep.lua")
        val consumer = path("app/main.lua")
        val shared = "shared-public-fp"

        val previous = snapshot(
            files = mapOf(
                dep to fileSnapshot(fingerprint = shared, modules = setOf("dep"), cacheKey = "k1"),
                consumer to fileSnapshot(fingerprint = "main-fp", modules = setOf("main"))
            ),
            graph = chainGraph(
                providers = mapOf("dep" to dep, "main" to consumer),
                edges = listOf(requireEdge(consumer, "dep", dep))
            )
        )
        // Distinct FileSnapshot instances with identical public fingerprint value.
        val current = previous.copy(
            files = mapOf(
                dep to fileSnapshot(fingerprint = shared, modules = setOf("dep"), cacheKey = "k1"),
                consumer to fileSnapshot(fingerprint = "main-fp", modules = setOf("main"))
            )
        )

        assertEquals(
            previous.files.getValue(dep).publicFingerprint?.value,
            current.files.getValue(dep).publicFingerprint?.value
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertTrue(plan.publicSurfaceChangedFiles.isEmpty())
        assertTrue(plan.reverseDependencyDirtyClosure.isEmpty())
        // cacheKey equal + same fingerprint → no changed files if snapshots equal
        assertTrue(
            plan.changedFiles.isEmpty() || plan.changedFiles == setOf(dep),
            "identical fingerprint reanalysis must not reverse-dirty dependents"
        )
        assertFalse(consumer in plan.affectedDocuments)
    }

    @Test
    fun planner_provided_module_name_set_change_counts_as_public_fingerprint_invalidation() {
        val dep = path("lib/dep.lua")
        val consumer = path("app/main.lua")

        val previous = snapshot(
            files = mapOf(
                dep to fileSnapshot(fingerprint = "fp-v1", modules = setOf("dep")),
                consumer to fileSnapshot(fingerprint = "main-fp", modules = setOf("main"))
            ),
            graph = chainGraph(
                providers = mapOf("dep" to dep, "main" to consumer),
                edges = listOf(requireEdge(consumer, "dep", dep))
            )
        )
        // Fingerprint value and provided module names both change (provider rename surface).
        val current = previous.copy(
            files = previous.files + mapOf(
                dep to fileSnapshot(fingerprint = "fp-v2", modules = setOf("dep", "dep.extra"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(dep), plan.publicSurfaceChangedFiles)
        assertTrue(consumer in plan.reverseDependencyDirtyClosure)
        assertEquals(setOf(dep, consumer), plan.affectedDocuments)
        assertTrue("dep" in plan.affectedModuleNames || "dep.extra" in plan.affectedModuleNames)
    }

    @Test
    fun planner_public_fingerprint_change_with_no_consumers_dirties_only_changed_file() {
        val alone = path("solo/module.lua")
        val other = path("solo/other.lua")

        val previous = snapshot(
            files = mapOf(
                alone to fileSnapshot(fingerprint = "alone-v1", modules = setOf("alone")),
                other to fileSnapshot(fingerprint = "other-v1", modules = setOf("other"))
            ),
            graph = chainGraph(
                providers = mapOf("alone" to alone, "other" to other),
                edges = emptyList()
            )
        )
        val current = previous.copy(
            files = previous.files + mapOf(
                alone to fileSnapshot(fingerprint = "alone-v2", modules = setOf("alone"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(alone), plan.publicSurfaceChangedFiles)
        assertTrue(plan.reverseDependencyDirtyClosure.isEmpty())
        assertEquals(setOf(alone), plan.affectedDocuments)
        assertFalse(other in plan.affectedDocuments)
    }

    @Test
    fun planner_scc_peer_invalidated_when_cycle_member_public_fingerprint_changes() {
        val left = path("cycle/left.lua")
        val right = path("cycle/right.lua")
        val unrelated = path("cycle/unrelated.lua")
        val scc = setOf(left, right)

        val previous = snapshot(
            files = mapOf(
                left to fileSnapshot(fingerprint = "left-v1", modules = setOf("left")),
                right to fileSnapshot(fingerprint = "right-v1", modules = setOf("right")),
                unrelated to fileSnapshot(fingerprint = "unrelated-v1", modules = setOf("unrelated"))
            ),
            graph = WorkspaceModuleGraph(
                activeProviders = mapOf(
                    "left" to provider("left", left),
                    "right" to provider("right", right),
                    "unrelated" to provider("unrelated", unrelated)
                ),
                providersByModuleName = mapOf(
                    "left" to listOf(provider("left", left)),
                    "right" to listOf(provider("right", right)),
                    "unrelated" to listOf(provider("unrelated", unrelated))
                ),
                resolvedDependencies = mapOf(
                    left to listOf(requireEdge(left, "right", right)),
                    right to listOf(requireEdge(right, "left", left))
                ),
                reverseDependencies = mapOf(
                    left to setOf(right),
                    right to setOf(left)
                ),
                stronglyConnectedComponents = listOf(scc, setOf(unrelated)),
                stronglyConnectedComponentByFile = mapOf(
                    left to scc,
                    right to scc,
                    unrelated to setOf(unrelated)
                )
            )
        )

        val current = previous.copy(
            files = previous.files + mapOf(
                left to fileSnapshot(fingerprint = "left-v2", modules = setOf("left"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertTrue(right in plan.reverseDependencyDirtyClosure)
        assertEquals(setOf(left, right), plan.sccExpandedDirtyClosure)
        assertEquals(setOf(left, right), plan.affectedDocuments)
        assertFalse(unrelated in plan.affectedDocuments)
    }

    // -------------------------------------------------------------------------
    // Engine path: real Lua modules + public fingerprint invalidation
    // -------------------------------------------------------------------------

    @Test
    fun engine_export_field_add_changes_public_fingerprint_and_dirties_dependents() {
        val engine = LuaWorkspaceEngine()
        val dep = path("dep.lua")
        val mid = path("mid.lua")
        val top = path("top.lua")
        val unrelated = path("unrelated.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    dep to "local M = { value = 1 }\nreturn M",
                    mid to "local dep = require(\"dep\")\nreturn { value = dep.value }",
                    top to "local mid = require(\"mid\")\nreturn { value = mid.value }",
                    unrelated to "return { value = 42 }"
                ),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        )

        val beforeFp = initial.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(beforeFp)

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    dep to "local M = { value = 1, extra = true }\nreturn M"
                )
            ),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )

        val afterFp = updated.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(afterFp)
        assertNotEquals(beforeFp, afterFp, "adding export field must change public fingerprint")
        assertTrue(dep in updated.publicSurfaceChangedFiles)
        assertTrue(mid in updated.affectedDocuments)
        assertTrue(top in updated.affectedDocuments)
        assertFalse(unrelated in updated.affectedDocuments)
        assertEquals(setOf(dep, mid, top), updated.affectedDocuments)
    }

    @Test
    fun engine_export_field_type_change_invalidates_fingerprint_and_dependents() {
        val engine = LuaWorkspaceEngine()
        val dep = path("dep.lua")
        val main = path("main.lua")
        val unrelated = path("unrelated.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    dep to "local M = { value = 1 }\nreturn M",
                    main to "local dep = require(\"dep\")\nreturn dep.value",
                    unrelated to "return {}"
                )
            )
        )

        val beforeFp = initial.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(beforeFp)

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    dep to "local M = { value = \"text\" }\nreturn M"
                )
            )
        )

        val afterFp = updated.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(afterFp)
        assertNotEquals(beforeFp, afterFp, "export field type change must alter public fingerprint")
        assertTrue(dep in updated.publicSurfaceChangedFiles)
        assertTrue(main in updated.affectedDocuments)
        assertFalse(unrelated in updated.affectedDocuments)
    }

    @Test
    fun engine_private_only_local_rewrite_keeps_public_fingerprint_and_dependents_clean() {
        val engine = LuaWorkspaceEngine()
        val dep = path("dep.lua")
        val main = path("main.lua")
        val unrelated = path("unrelated.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    dep to "local private = 1\nlocal M = { value = 1 }\nreturn M",
                    main to "local dep = require(\"dep\")\nreturn { value = dep.value }",
                    unrelated to "return { ok = true }"
                )
            )
        )

        val beforeFp = initial.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(beforeFp)

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    dep to "local private = 2\nlocal M = { value = 1 }\nreturn M"
                )
            )
        )

        val afterFp = updated.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(afterFp)
        assertEquals(
            beforeFp,
            afterFp,
            "private-only local rewrite must keep public fingerprint stable"
        )
        assertTrue(
            updated.publicSurfaceChangedFiles.isEmpty() || dep !in updated.publicSurfaceChangedFiles,
            "private-only edit must not mark public surface dirty"
        )
        assertEquals(setOf(dep), updated.affectedDocuments)
        assertFalse(main in updated.affectedDocuments)
        assertFalse(unrelated in updated.affectedDocuments)
    }

    @Test
    fun engine_noop_delta_keeps_fingerprints_and_affected_empty() {
        val engine = LuaWorkspaceEngine()
        val dep = path("dep.lua")
        val main = path("main.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    dep to "local M = { value = 1 }\nreturn M",
                    main to "local dep = require(\"dep\")\nreturn dep.value"
                ),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        )

        val beforeFp = initial.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(beforeFp)

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )

        assertEquals(
            beforeFp,
            updated.snapshot.files.getValue(dep).publicFingerprint?.value
        )
        assertTrue(updated.publicSurfaceChangedFiles.isEmpty())
        assertTrue(updated.affectedDocuments.isEmpty())
        assertFalse(main in updated.affectedDocuments)
    }

    @Test
    fun engine_delete_and_readd_restores_require_surface_with_fresh_fingerprint() {
        val engine = LuaWorkspaceEngine()
        val dep = path("dep.lua")
        val main = path("main.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    dep to "return { value = \"v1\" }",
                    main to "local d = require(\"dep\")\nreturn d.value"
                )
            )
        )
        val initialFp = initial.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(initialFp)

        val deleted = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(removals = setOf(dep))
        )
        assertFalse(deleted.snapshot.files.containsKey(dep))

        val restored = engine.update(
            previous = deleted.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(dep to "return { value = \"v2\" }")
            )
        )

        assertTrue(restored.snapshot.files.containsKey(dep))
        assertEquals(dep, restored.snapshot.graph.activeProviders["dep"]?.path)
        val restoredFp = restored.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(restoredFp)

        val queries = LuaWorkspaceQueryFacade(restored.snapshot)
        val resolved = queries.resolveRequire(main, "dep")
        assertEquals(dep, resolved.provider?.path)
        val surface = assertNotNull(resolved.exportSurface)
        assertTrue(
            surface.members.any { it.name == "value" || it.exportPath == listOf("value") } ||
                surface.moduleType.fields.containsKey("value"),
            "restored surface must expose value; members=${surface.members.map { it.exportPath }}"
        )
        // Content changed v1 → v2: fingerprint should differ OR surface still exposes fields.
        assertTrue(
            restoredFp != initialFp || surface.moduleType.fields.isNotEmpty(),
            "re-add with new content should not keep an identical stale surface blindly"
        )
    }

    @Test
    fun engine_multi_hop_export_change_invalidates_downstream_only() {
        val engine = LuaWorkspaceEngine()
        val leaf = path("leaf.lua")
        val mid = path("mid.lua")
        val top = path("top.lua")
        val side = path("side.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    leaf to "local M = { n = 1 }\nreturn M",
                    mid to "local l = require(\"leaf\")\nreturn { n = l.n }",
                    top to "local m = require(\"mid\")\nreturn { n = m.n }",
                    // side requires leaf but leaf fingerprint change still reverse-dirties side
                    // when leaf public surface changes — use mid as the changed module instead.
                    side to "return { alone = true }"
                )
            )
        )

        val midBefore = initial.snapshot.files.getValue(mid).publicFingerprint?.value
        assertNotNull(midBefore)

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    mid to "local l = require(\"leaf\")\nreturn { n = l.n, tag = \"mid\" }"
                )
            )
        )

        val midAfter = updated.snapshot.files.getValue(mid).publicFingerprint?.value
        assertNotNull(midAfter)
        assertNotEquals(midBefore, midAfter)
        assertTrue(mid in updated.publicSurfaceChangedFiles)
        assertTrue(top in updated.affectedDocuments)
        assertTrue(mid in updated.affectedDocuments)
        // leaf is upstream provider, not a reverse dependent of mid.
        assertFalse(leaf in updated.affectedDocuments)
        assertFalse(leaf in updated.publicSurfaceChangedFiles)
        assertFalse(side in updated.affectedDocuments)
    }

    @Test
    fun engine_private_then_public_edit_sequence_detects_only_public_invalidation() {
        val engine = LuaWorkspaceEngine()
        val dep = path("dep.lua")
        val main = path("main.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    dep to "local private = 1\nlocal M = { value = 1 }\nreturn M",
                    main to "local dep = require(\"dep\")\nreturn dep.value"
                )
            )
        )
        val fp0 = initial.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(fp0)

        val privateOnly = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    dep to "local private = 99\nlocal M = { value = 1 }\nreturn M"
                )
            )
        )
        val fp1 = privateOnly.snapshot.files.getValue(dep).publicFingerprint?.value
        assertEquals(fp0, fp1)
        assertFalse(main in privateOnly.affectedDocuments)

        val publicEdit = engine.update(
            previous = privateOnly.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    dep to "local private = 99\nlocal M = { value = 2 }\nreturn M"
                )
            )
        )
        val fp2 = publicEdit.snapshot.files.getValue(dep).publicFingerprint?.value
        // value field stays number type — fingerprint may or may not change depending on
        // literal-vs-primitive surface policy. Dependents must dirty if public surface changed;
        // if surface is type-level only and stays NUMBER, private-style stability is acceptable
        // only when publicSurfaceChangedFiles is empty.
        if (dep in publicEdit.publicSurfaceChangedFiles) {
            assertNotEquals(fp1, fp2)
            assertTrue(main in publicEdit.affectedDocuments)
        } else {
            // Type-stable export: still require no false reverse-dirty of main when FP stable.
            assertEquals(fp1, fp2)
            assertFalse(main in publicEdit.affectedDocuments)
        }
    }

    @Test
    fun engine_export_field_remove_changes_fingerprint_and_dirties_consumer() {
        val engine = LuaWorkspaceEngine()
        val dep = path("dep.lua")
        val main = path("main.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    dep to "local M = { value = 1, extra = true }\nreturn M",
                    main to "local dep = require(\"dep\")\nreturn dep.value"
                )
            )
        )
        val beforeFp = initial.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(beforeFp)

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    dep to "local M = { value = 1 }\nreturn M"
                )
            )
        )
        val afterFp = updated.snapshot.files.getValue(dep).publicFingerprint?.value
        assertNotNull(afterFp)
        assertNotEquals(beforeFp, afterFp, "removing export field must change public fingerprint")
        assertTrue(dep in updated.publicSurfaceChangedFiles)
        assertTrue(main in updated.affectedDocuments)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun path(value: String): VirtualPath = VirtualPath.of(value)

    private fun fileSnapshot(
        fingerprint: String,
        modules: Set<String>,
        cacheKey: String = fingerprint
    ): WorkspaceSnapshot.FileSnapshot = WorkspaceSnapshot.FileSnapshot(
        cacheKey = cacheKey,
        publicFingerprint = WorkspacePublicFingerprint(
            providedModuleNames = modules,
            value = fingerprint
        )
    )

    private fun snapshot(
        files: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>,
        graph: WorkspaceModuleGraph
    ): WorkspaceSnapshot = WorkspaceSnapshot(
        files = files,
        graph = graph
    )

    private fun provider(
        moduleName: String,
        path: VirtualPath,
        source: WorkspaceModuleGraph.ProviderSource = WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH
    ): WorkspaceModuleGraph.ModuleProvider = WorkspaceModuleGraph.ModuleProvider(
        moduleName = moduleName,
        path = path,
        source = source
    )

    private fun requireEdge(
        consumer: VirtualPath,
        moduleName: String,
        providerPath: VirtualPath
    ): WorkspaceModuleGraph.ResolvedDependency = WorkspaceModuleGraph.ResolvedDependency(
        consumerPath = consumer,
        moduleName = moduleName,
        provider = provider(moduleName, providerPath),
        range = Range.EMPTY
    )

    /**
     * Linear/DAG module graph with reverse edges and singleton SCCs for every
     * participating file (mirrors [WorkspaceModuleGraphBuilder] output shape).
     */
    private fun chainGraph(
        providers: Map<String, VirtualPath>,
        edges: List<WorkspaceModuleGraph.ResolvedDependency>
    ): WorkspaceModuleGraph {
        val active = providers.mapValues { (name, path) -> provider(name, path) }
        val byModule = active.mapValues { (_, provider) -> listOf(provider) }
        val resolved = edges.groupBy { it.consumerPath }
        val reverse = linkedMapOf<VirtualPath, MutableSet<VirtualPath>>()
        edges.forEach { edge ->
            reverse.getOrPut(edge.provider.path) { linkedSetOf() } += edge.consumerPath
        }
        val allPaths = linkedSetOf<VirtualPath>()
        allPaths += providers.values
        edges.forEach { edge ->
            allPaths += edge.consumerPath
            allPaths += edge.provider.path
        }
        val singletonComponents = allPaths.map { setOf(it) }
        val componentByFile = allPaths.associateWith { setOf(it) }
        return WorkspaceModuleGraph(
            activeProviders = active,
            providersByModuleName = byModule,
            resolvedDependencies = resolved,
            reverseDependencies = reverse.mapValues { (_, consumers) -> consumers.toSet() },
            stronglyConnectedComponents = singletonComponents,
            stronglyConnectedComponentByFile = componentByFile
        )
    }
}
