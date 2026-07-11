package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.LegacyModuleEnvironment
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleEnvironmentMode
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDirtySetPlanner
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * TASK-428 corpus: WorkspaceDirtySetPlanner behavior when builtinOverlay globals
 * [BuiltinOverlaySnapshot.GlobalsSnapshot.metadataFingerprint] changes.
 *
 * Product contract ([WorkspaceDirtySetPlanner]):
 * - When previous/current globals metadataFingerprint differ, every current file whose
 *   [WorkspaceSnapshot.FileSnapshot.legacyModuleEnvironment] contains a segment with
 *   [LegacyModuleEnvironment.Segment.hasSeeAllFallback] == true is treated as a
 *   see-all fallback document.
 * - Those documents enter reverseDependencyDirtyClosure seeds and affectedDocuments.
 * - Files without see-all fallback stay clean for overlay-only fingerprint churn
 *   (no public surface / require resolution change).
 * - Stable globals fingerprint yields no overlay-driven dirtiness.
 *
 * Test-only; product code is out of scope. Verification is review-owned (no Gradle here).
 */
class WorkspaceDirtySetOverlayFingerprintTddTest {

    @Test
    fun overlay_globals_fingerprint_change_dirties_only_seeall_fallback_documents() {
        val seeAll = path("legacy/seeall.lua")
        val plainLegacy = path("legacy/plain.lua")
        val plainChunk = path("app/chunk.lua")

        val previous = snapshot(
            files = mapOf(
                seeAll to fileSnapshot(
                    fingerprint = "seeall-stable",
                    modules = setOf("legacy.seeall"),
                    legacy = legacyEnv(
                        moduleName = "legacy.seeall",
                        mode = ModuleEnvironmentMode.LEGACY_MODULE_SEEALL,
                        hasSeeAllFallback = true
                    )
                ),
                plainLegacy to fileSnapshot(
                    fingerprint = "plain-stable",
                    modules = setOf("legacy.plain"),
                    legacy = legacyEnv(
                        moduleName = "legacy.plain",
                        mode = ModuleEnvironmentMode.LEGACY_MODULE,
                        hasSeeAllFallback = false
                    )
                ),
                plainChunk to fileSnapshot(
                    fingerprint = "chunk-stable",
                    modules = setOf("app.chunk")
                )
            ),
            overlay = overlay(fingerprint = "globals-v1", globalNames = setOf("print", "bit32"))
        )

        val current = previous.copy(
            builtinOverlay = overlay(fingerprint = "globals-v2", globalNames = setOf("print"))
        )

        assertNotEquals(
            previous.builtinOverlay.globals.metadataFingerprint,
            current.builtinOverlay.globals.metadataFingerprint
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertTrue(plan.changedFiles.isEmpty(), "overlay-only churn must not mark user files changed")
        assertTrue(plan.publicSurfaceChangedFiles.isEmpty())
        assertTrue(plan.filesWithRequireResolutionChanged.isEmpty())
        assertTrue(
            seeAll in plan.reverseDependencyDirtyClosure,
            "seeall document must seed reverse-dirty when globals fingerprint changes"
        )
        assertTrue(seeAll in plan.affectedDocuments)
        assertFalse(
            plainLegacy in plan.affectedDocuments,
            "legacy module without seeall fallback must stay clean"
        )
        assertFalse(
            plainChunk in plan.affectedDocuments,
            "chunk file without legacy seeall must stay clean"
        )
        assertEquals(setOf(seeAll), plan.affectedDocuments)
    }

    @Test
    fun stable_overlay_globals_fingerprint_keeps_seeall_documents_clean() {
        val seeAll = path("legacy/seeall.lua")
        val other = path("app/other.lua")
        val stableOverlay = overlay(fingerprint = "globals-stable", globalNames = setOf("print", "type"))

        val previous = snapshot(
            files = mapOf(
                seeAll to fileSnapshot(
                    fingerprint = "seeall-stable",
                    modules = setOf("legacy.seeall"),
                    legacy = legacyEnv(
                        moduleName = "legacy.seeall",
                        mode = ModuleEnvironmentMode.LEGACY_MODULE_SEEALL,
                        hasSeeAllFallback = true
                    )
                ),
                other to fileSnapshot(fingerprint = "other-stable", modules = setOf("app.other"))
            ),
            overlay = stableOverlay
        )
        // Same fingerprint payload; distinct GlobalsSnapshot instance is fine.
        val current = previous.copy(
            builtinOverlay = overlay(fingerprint = "globals-stable", globalNames = setOf("print", "type"))
        )

        assertEquals(
            previous.builtinOverlay.globals.metadataFingerprint,
            current.builtinOverlay.globals.metadataFingerprint
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertTrue(plan.changedFiles.isEmpty())
        assertTrue(plan.reverseDependencyDirtyClosure.isEmpty())
        assertTrue(plan.affectedDocuments.isEmpty())
        assertFalse(seeAll in plan.affectedDocuments)
        assertFalse(other in plan.affectedDocuments)
    }

    @Test
    fun overlay_fingerprint_change_with_no_seeall_files_yields_empty_dirty_set() {
        val a = path("a.lua")
        val b = path("b.lua")

        val previous = snapshot(
            files = mapOf(
                a to fileSnapshot(fingerprint = "a-stable", modules = setOf("a")),
                b to fileSnapshot(
                    fingerprint = "b-stable",
                    modules = setOf("b"),
                    legacy = legacyEnv(
                        moduleName = "b",
                        mode = ModuleEnvironmentMode.LEGACY_MODULE,
                        hasSeeAllFallback = false
                    )
                )
            ),
            overlay = overlay(fingerprint = "g1", globalNames = setOf("print"))
        )
        val current = previous.copy(
            builtinOverlay = overlay(fingerprint = "g2", globalNames = setOf("print", "assert"))
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertNotEquals(
            previous.builtinOverlay.globals.metadataFingerprint,
            current.builtinOverlay.globals.metadataFingerprint
        )
        assertTrue(plan.affectedDocuments.isEmpty())
        assertTrue(plan.reverseDependencyDirtyClosure.isEmpty())
    }

    @Test
    fun multiple_seeall_documents_all_dirty_on_overlay_globals_fingerprint_change() {
        val seeAllA = path("mods/a.lua")
        val seeAllB = path("mods/b.lua")
        val clean = path("mods/clean.lua")

        val previous = snapshot(
            files = mapOf(
                seeAllA to fileSnapshot(
                    fingerprint = "a",
                    modules = setOf("mods.a"),
                    legacy = legacyEnv("mods.a", ModuleEnvironmentMode.LEGACY_MODULE_SEEALL, true)
                ),
                seeAllB to fileSnapshot(
                    fingerprint = "b",
                    modules = setOf("mods.b"),
                    legacy = legacyEnv("mods.b", ModuleEnvironmentMode.LEGACY_MODULE_SEEALL, true)
                ),
                clean to fileSnapshot(fingerprint = "clean", modules = setOf("mods.clean"))
            ),
            overlay = overlay(fingerprint = "before", globalNames = setOf("print", "bit32"))
        )
        val current = previous.copy(
            builtinOverlay = overlay(fingerprint = "after", globalNames = setOf("print"))
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(seeAllA, seeAllB), plan.affectedDocuments)
        assertTrue(seeAllA in plan.reverseDependencyDirtyClosure)
        assertTrue(seeAllB in plan.reverseDependencyDirtyClosure)
        assertFalse(clean in plan.affectedDocuments)
    }

    @Test
    fun only_segments_with_seeall_flag_count_mixed_legacy_environment() {
        val mixed = path("mixed.lua")
        val pureSeeAll = path("seeall.lua")

        // Mixed file: one non-seeall segment then a seeall segment → qualifies.
        val mixedEnv = LegacyModuleEnvironment(
            segments = listOf(
                segment("mixed.plain", ModuleEnvironmentMode.LEGACY_MODULE, hasSeeAllFallback = false),
                segment("mixed.seeall", ModuleEnvironmentMode.LEGACY_MODULE_SEEALL, hasSeeAllFallback = true)
            )
        )
        val pureEnv = legacyEnv("pure", ModuleEnvironmentMode.LEGACY_MODULE_SEEALL, true)

        val previous = snapshot(
            files = mapOf(
                mixed to fileSnapshot(fingerprint = "mixed", modules = setOf("mixed"), legacy = mixedEnv),
                pureSeeAll to fileSnapshot(fingerprint = "pure", modules = setOf("pure"), legacy = pureEnv)
            ),
            overlay = overlay(fingerprint = "v1", globalNames = setOf("print"))
        )
        val current = previous.copy(
            builtinOverlay = overlay(fingerprint = "v2", globalNames = setOf("print", "type"))
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertTrue(mixed in plan.affectedDocuments)
        assertTrue(pureSeeAll in plan.affectedDocuments)
        assertEquals(setOf(mixed, pureSeeAll), plan.affectedDocuments)
    }

    @Test
    fun seeall_flag_false_even_with_legacy_module_seeall_mode_label_is_not_dirty_seed() {
        // Planner keys off hasSeeAllFallback, not mode enum alone.
        val mislabeled = path("mislabeled.lua")
        val previous = snapshot(
            files = mapOf(
                mislabeled to fileSnapshot(
                    fingerprint = "m",
                    modules = setOf("m"),
                    legacy = legacyEnv(
                        moduleName = "m",
                        mode = ModuleEnvironmentMode.LEGACY_MODULE_SEEALL,
                        hasSeeAllFallback = false
                    )
                )
            ),
            overlay = overlay(fingerprint = "old", globalNames = setOf("print"))
        )
        val current = previous.copy(
            builtinOverlay = overlay(fingerprint = "new", globalNames = setOf("print", "error"))
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertTrue(plan.affectedDocuments.isEmpty())
        assertFalse(mislabeled in plan.reverseDependencyDirtyClosure)
    }

    @Test
    fun overlay_fingerprint_change_plus_public_edit_unions_affected_sets() {
        val seeAll = path("legacy.lua")
        val exporter = path("exporter.lua")
        val importer = path("importer.lua")
        val unrelated = path("unrelated.lua")

        val previous = snapshot(
            files = mapOf(
                seeAll to fileSnapshot(
                    fingerprint = "legacy-stable",
                    modules = setOf("legacy"),
                    legacy = legacyEnv("legacy", ModuleEnvironmentMode.LEGACY_MODULE_SEEALL, true)
                ),
                exporter to fileSnapshot(fingerprint = "exporter-v1", modules = setOf("exporter")),
                importer to fileSnapshot(fingerprint = "importer-v1", modules = setOf("importer")),
                unrelated to fileSnapshot(fingerprint = "unrelated-v1", modules = setOf("unrelated"))
            ),
            graph = requireGraph(
                providers = mapOf(
                    "legacy" to seeAll,
                    "exporter" to exporter,
                    "importer" to importer,
                    "unrelated" to unrelated
                ),
                edges = listOf(requireEdge(importer, "exporter", exporter))
            ),
            overlay = overlay(fingerprint = "g-old", globalNames = setOf("print", "bit32"))
        )

        val current = previous.copy(
            files = previous.files + mapOf(
                exporter to fileSnapshot(fingerprint = "exporter-v2", modules = setOf("exporter"))
            ),
            builtinOverlay = overlay(fingerprint = "g-new", globalNames = setOf("print"))
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(exporter), plan.publicSurfaceChangedFiles)
        assertTrue(importer in plan.reverseDependencyDirtyClosure)
        assertTrue(seeAll in plan.affectedDocuments, "seeall must dirty from overlay fingerprint")
        assertTrue(exporter in plan.affectedDocuments)
        assertTrue(importer in plan.affectedDocuments)
        assertFalse(unrelated in plan.affectedDocuments)
        assertEquals(setOf(seeAll, exporter, importer), plan.affectedDocuments)
    }

    @Test
    fun removed_seeall_file_is_not_in_current_affected_set_on_overlay_change() {
        val kept = path("kept.lua")
        val removed = path("removed.lua")

        val previous = snapshot(
            files = mapOf(
                kept to fileSnapshot(
                    fingerprint = "kept",
                    modules = setOf("kept"),
                    legacy = legacyEnv("kept", ModuleEnvironmentMode.LEGACY_MODULE_SEEALL, true)
                ),
                removed to fileSnapshot(
                    fingerprint = "removed",
                    modules = setOf("removed"),
                    legacy = legacyEnv("removed", ModuleEnvironmentMode.LEGACY_MODULE_SEEALL, true)
                )
            ),
            overlay = overlay(fingerprint = "old", globalNames = setOf("print"))
        )
        val current = previous.copy(
            files = mapOf(
                kept to previous.files.getValue(kept)
            ),
            builtinOverlay = overlay(fingerprint = "new", globalNames = setOf("print", "assert"))
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        // removed is a changedFiles path (deleted) but not present in current.files,
        // so overlay seeall selection only considers kept.
        assertTrue(kept in plan.affectedDocuments)
        assertFalse(removed in plan.affectedDocuments)
        assertTrue(removed in plan.changedFiles)
    }

    @Test
    fun empty_globals_to_nonempty_fingerprint_change_dirties_seeall() {
        val seeAll = path("seeall.lua")
        val previous = snapshot(
            files = mapOf(
                seeAll to fileSnapshot(
                    fingerprint = "s",
                    modules = setOf("s"),
                    legacy = legacyEnv("s", ModuleEnvironmentMode.LEGACY_MODULE_SEEALL, true)
                )
            ),
            overlay = BuiltinOverlaySnapshot.EMPTY
        )
        val current = previous.copy(
            builtinOverlay = overlay(fingerprint = "populated", globalNames = setOf("print", "type"))
        )

        assertNotEquals(
            previous.builtinOverlay.globals.metadataFingerprint,
            current.builtinOverlay.globals.metadataFingerprint
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)
        assertEquals(setOf(seeAll), plan.affectedDocuments)
    }

    @Test
    fun module_field_names_only_overlay_fingerprint_change_still_dirties_seeall() {
        // GlobalsSnapshot.create hashes globalNames AND moduleFieldNames.
        val seeAll = path("seeall.lua")
        val plain = path("plain.lua")
        val previous = snapshot(
            files = mapOf(
                seeAll to fileSnapshot(
                    fingerprint = "s",
                    modules = setOf("s"),
                    legacy = legacyEnv("s", ModuleEnvironmentMode.LEGACY_MODULE_SEEALL, true)
                ),
                plain to fileSnapshot(fingerprint = "p", modules = setOf("p"))
            ),
            overlay = overlay(
                fingerprint = "unused-marker",
                globalNames = setOf("print"),
                moduleFieldNames = mapOf("math" to setOf("sin", "cos"))
            )
        )
        val current = previous.copy(
            builtinOverlay = overlay(
                fingerprint = "unused-marker",
                globalNames = setOf("print"),
                moduleFieldNames = mapOf("math" to setOf("sin", "cos", "tan"))
            )
        )

        assertNotEquals(
            previous.builtinOverlay.globals.metadataFingerprint,
            current.builtinOverlay.globals.metadataFingerprint,
            "moduleFieldNames churn must alter metadataFingerprint"
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)
        assertEquals(setOf(seeAll), plan.affectedDocuments)
        assertFalse(plain in plan.affectedDocuments)
    }

    @Test
    fun engine_level_lua53_to_lua54_dirties_seeall_not_plain_or_unrelated() {
        val engine = LuaWorkspaceEngine()
        val seeAll = path("legacy.lua")
        val plain = path("plain.lua")
        val unrelated = path("unrelated.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    seeAll to "module(\"legacy\", package.seeall)\nreturn legacy",
                    plain to "module(\"plain\")\nreturn plain",
                    unrelated to "return { ok = true }"
                ),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        )

        assertTrue(
            initial.snapshot.files.getValue(seeAll)
                .legacyModuleEnvironment
                ?.segments
                .orEmpty()
                .any { it.hasSeeAllFallback },
            "fixture must establish seeall fallback on legacy.lua"
        )

        val previousFp = initial.snapshot.builtinOverlay.globals.metadataFingerprint

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_4
        )

        assertNotEquals(
            previousFp,
            updated.snapshot.builtinOverlay.globals.metadataFingerprint,
            "Lua 5.3 → 5.4 must change overlay globals fingerprint (e.g. bit32)"
        )
        assertTrue(seeAll in updated.affectedDocuments)
        assertFalse(plain in updated.affectedDocuments, "plain module() without seeall stays clean")
        assertFalse(unrelated in updated.affectedDocuments)
        assertEquals(setOf(seeAll), updated.affectedDocuments)
    }

    @Test
    fun engine_level_same_overlay_version_noop_keeps_seeall_clean() {
        val engine = LuaWorkspaceEngine()
        val seeAll = path("legacy.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    seeAll to "module(\"legacy\", package.seeall)\nreturn legacy"
                ),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        )

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )

        assertEquals(
            initial.snapshot.builtinOverlay.globals.metadataFingerprint,
            updated.snapshot.builtinOverlay.globals.metadataFingerprint
        )
        assertTrue(updated.affectedDocuments.isEmpty())
        assertFalse(seeAll in updated.affectedDocuments)
    }

    @Test
    fun engine_level_overlay_switch_with_public_exporter_edit_unions_dirty_sets() {
        val engine = LuaWorkspaceEngine()
        val seeAll = path("legacy.lua")
        val exporter = path("exporter.lua")
        val importer = path("importer.lua")
        val unrelated = path("unrelated.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    seeAll to "module(\"legacy\", package.seeall)\nreturn legacy",
                    exporter to "local M = { value = 1 }\nreturn M",
                    importer to "local exp = require(\"exporter\")\nreturn { value = exp.value }",
                    unrelated to "return {}"
                ),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        )

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    exporter to "local M = { value = 1, extra = true }\nreturn M"
                )
            ),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_4
        )

        assertTrue(seeAll in updated.affectedDocuments)
        assertTrue(exporter in updated.affectedDocuments)
        assertTrue(importer in updated.affectedDocuments)
        assertFalse(unrelated in updated.affectedDocuments)
        assertEquals(setOf(seeAll, exporter, importer), updated.affectedDocuments)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun path(value: String): VirtualPath = VirtualPath.of(value)

    private fun fileSnapshot(
        fingerprint: String,
        modules: Set<String>,
        cacheKey: String = fingerprint,
        legacy: LegacyModuleEnvironment? = null
    ): WorkspaceSnapshot.FileSnapshot = WorkspaceSnapshot.FileSnapshot(
        cacheKey = cacheKey,
        legacyModuleEnvironment = legacy,
        publicFingerprint = WorkspacePublicFingerprint(
            providedModuleNames = modules,
            value = fingerprint
        )
    )

    private fun legacyEnv(
        moduleName: String,
        mode: ModuleEnvironmentMode,
        hasSeeAllFallback: Boolean
    ): LegacyModuleEnvironment = LegacyModuleEnvironment(
        segments = listOf(segment(moduleName, mode, hasSeeAllFallback))
    )

    private fun segment(
        moduleName: String,
        mode: ModuleEnvironmentMode,
        hasSeeAllFallback: Boolean
    ): LegacyModuleEnvironment.Segment = LegacyModuleEnvironment.Segment(
        moduleName = moduleName,
        mode = mode,
        hasSeeAllFallback = hasSeeAllFallback,
        range = Range(Position(1, 1), Position(100, 1)),
        triggerRange = Range(Position(1, 1), Position(1, 8)),
        bindings = mapOf("_M" to ModuleType(moduleName = moduleName))
    )

    /**
     * Build a synthetic overlay. [fingerprint] is folded into globalNames so that
     * [BuiltinOverlaySnapshot.GlobalsSnapshot.create] produces a distinct
     * metadataFingerprint without depending on product stdlib catalogs.
     */
    private fun overlay(
        fingerprint: String,
        globalNames: Set<String>,
        moduleFieldNames: Map<String, Set<String>> = emptyMap()
    ): BuiltinOverlaySnapshot {
        val names = linkedSetOf<String>()
        names += globalNames
        // Marker global forces fingerprint uniqueness when caller wants a labeled delta
        // beyond the provided name set (create() hashes names, not a free-form tag).
        names += "__overlay_fp__$fingerprint"
        return BuiltinOverlaySnapshot(
            version = LuaVersion.LUA_5_3,
            providerModules = emptyMap(),
            globals = BuiltinOverlaySnapshot.GlobalsSnapshot.create(
                path = VirtualPath.of("__lua_std__/test/_G.lua"),
                file = WorkspaceSnapshot.FileSnapshot(cacheKey = "overlay-$fingerprint"),
                globalNames = names,
                moduleFieldNames = moduleFieldNames
            )
        )
    }

    private fun snapshot(
        files: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>,
        graph: WorkspaceModuleGraph = emptyGraph(files.keys),
        overlay: BuiltinOverlaySnapshot
    ): WorkspaceSnapshot = WorkspaceSnapshot(
        files = files,
        graph = graph,
        builtinOverlay = overlay
    )

    private fun emptyGraph(paths: Set<VirtualPath>): WorkspaceModuleGraph {
        val components = paths.map { setOf(it) }
        val byFile = paths.associateWith { setOf(it) }
        return WorkspaceModuleGraph(
            stronglyConnectedComponents = components,
            stronglyConnectedComponentByFile = byFile
        )
    }

    private fun provider(
        moduleName: String,
        path: VirtualPath
    ): WorkspaceModuleGraph.ModuleProvider = WorkspaceModuleGraph.ModuleProvider(
        moduleName = moduleName,
        path = path,
        source = WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH
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

    private fun requireGraph(
        providers: Map<String, VirtualPath>,
        edges: List<WorkspaceModuleGraph.ResolvedDependency>
    ): WorkspaceModuleGraph {
        val active = providers.mapValues { (name, path) -> provider(name, path) }
        val byModule = active.mapValues { (_, p) -> listOf(p) }
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
