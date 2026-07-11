package semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDirtySetPlanner
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-294 corpus: Workspace dirty set propagation along require edges.
 *
 * Acceptance:
 * - Editing an exporter dirties importers via require edges.
 * - Unrelated files stay clean.
 *
 * Test-only; product code is out of scope. Verification is review-owned (no Gradle here).
 *
 * Synthetic graphs populate [WorkspaceModuleGraph.stronglyConnectedComponentByFile]
 * with at least singleton components so [WorkspaceDirtySetPlanner.Result.affectedDocuments]
 * expansion matches [WorkspaceModuleGraphBuilder] output shape.
 */
class WorkspaceDirtySetRequireEdgeTddTest {

    @Test
    fun exporter_public_edit_dirties_direct_importer_via_require_edge() {
        val exporter = path("lib/exporter.lua")
        val importer = path("app/importer.lua")
        val unrelated = path("app/unrelated.lua")

        val previous = snapshot(
            files = mapOf(
                exporter to fileSnapshot(fingerprint = "exporter-public-v1", modules = setOf("exporter")),
                importer to fileSnapshot(fingerprint = "importer-public-v1", modules = setOf("importer")),
                unrelated to fileSnapshot(fingerprint = "unrelated-public-v1", modules = setOf("unrelated"))
            ),
            graph = requireGraph(
                providers = mapOf(
                    "exporter" to exporter,
                    "importer" to importer,
                    "unrelated" to unrelated
                ),
                edges = listOf(
                    requireEdge(importer, "exporter", exporter)
                )
            )
        )

        val current = previous.copy(
            files = previous.files + mapOf(
                exporter to fileSnapshot(fingerprint = "exporter-public-v2", modules = setOf("exporter"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(exporter), plan.publicSurfaceChangedFiles)
        assertEquals(setOf(exporter), plan.changedFiles)
        assertTrue(
            importer in plan.reverseDependencyDirtyClosure,
            "Direct require importer must be reverse-dirty when exporter public surface changes"
        )
        assertTrue(
            importer in plan.affectedDocuments,
            "Direct require importer must be in affectedDocuments"
        )
        assertFalse(unrelated in plan.reverseDependencyDirtyClosure)
        assertFalse(unrelated in plan.affectedDocuments)
        assertEquals(setOf(exporter, importer), plan.affectedDocuments)
        assertTrue("exporter" in plan.affectedModuleNames)
        assertFalse("unrelated" in plan.affectedModuleNames)
    }

    @Test
    fun exporter_public_edit_dirties_multi_hop_importers_along_require_edges() {
        // top requires mid; mid requires exporter (leaf).
        val exporter = path("mods/exporter.lua")
        val mid = path("mods/mid.lua")
        val top = path("mods/top.lua")
        val sibling = path("mods/sibling.lua")

        val previous = snapshot(
            files = mapOf(
                exporter to fileSnapshot(fingerprint = "exporter-v1", modules = setOf("exporter")),
                mid to fileSnapshot(fingerprint = "mid-v1", modules = setOf("mid")),
                top to fileSnapshot(fingerprint = "top-v1", modules = setOf("top")),
                sibling to fileSnapshot(fingerprint = "sibling-v1", modules = setOf("sibling"))
            ),
            graph = requireGraph(
                providers = mapOf(
                    "exporter" to exporter,
                    "mid" to mid,
                    "top" to top,
                    "sibling" to sibling
                ),
                edges = listOf(
                    requireEdge(mid, "exporter", exporter),
                    requireEdge(top, "mid", mid)
                    // sibling has no require edge to exporter
                )
            )
        )

        val current = previous.copy(
            files = previous.files + mapOf(
                exporter to fileSnapshot(fingerprint = "exporter-v2", modules = setOf("exporter"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(exporter), plan.publicSurfaceChangedFiles)
        assertTrue(mid in plan.reverseDependencyDirtyClosure, "Direct importer mid must dirty")
        assertTrue(top in plan.reverseDependencyDirtyClosure, "Transitive importer top must dirty via require chain")
        assertFalse(sibling in plan.reverseDependencyDirtyClosure, "Sibling without require edge must stay clean")
        assertFalse(sibling in plan.affectedDocuments)
        assertEquals(setOf(exporter, mid, top), plan.affectedDocuments)
    }

    @Test
    fun multiple_importers_of_same_exporter_all_dirty_via_require_edges() {
        val exporter = path("shared/api.lua")
        val importerA = path("clients/a.lua")
        val importerB = path("clients/b.lua")
        val unrelated = path("clients/other.lua")

        val previous = snapshot(
            files = mapOf(
                exporter to fileSnapshot(fingerprint = "api-v1", modules = setOf("api")),
                importerA to fileSnapshot(fingerprint = "a-v1", modules = setOf("a")),
                importerB to fileSnapshot(fingerprint = "b-v1", modules = setOf("b")),
                unrelated to fileSnapshot(fingerprint = "other-v1", modules = setOf("other"))
            ),
            graph = requireGraph(
                providers = mapOf(
                    "api" to exporter,
                    "a" to importerA,
                    "b" to importerB,
                    "other" to unrelated
                ),
                edges = listOf(
                    requireEdge(importerA, "api", exporter),
                    requireEdge(importerB, "api", exporter)
                )
            )
        )

        val current = previous.copy(
            files = previous.files + mapOf(
                exporter to fileSnapshot(fingerprint = "api-v2", modules = setOf("api"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertTrue(importerA in plan.reverseDependencyDirtyClosure)
        assertTrue(importerB in plan.reverseDependencyDirtyClosure)
        assertTrue(importerA in plan.affectedDocuments)
        assertTrue(importerB in plan.affectedDocuments)
        assertFalse(unrelated in plan.reverseDependencyDirtyClosure)
        assertFalse(unrelated in plan.affectedDocuments)
        assertEquals(setOf(exporter, importerA, importerB), plan.affectedDocuments)
    }

    @Test
    fun private_only_exporter_edit_keeps_require_importers_clean() {
        val exporter = path("lib/exporter.lua")
        val importer = path("app/main.lua")
        val unrelated = path("app/other.lua")
        val stablePublic = "exporter-public-stable"

        val previous = snapshot(
            files = mapOf(
                exporter to fileSnapshot(
                    fingerprint = stablePublic,
                    modules = setOf("exporter"),
                    cacheKey = "exporter-body-v1"
                ),
                importer to fileSnapshot(fingerprint = "main-v1", modules = setOf("main")),
                unrelated to fileSnapshot(fingerprint = "other-v1", modules = setOf("other"))
            ),
            graph = requireGraph(
                providers = mapOf(
                    "exporter" to exporter,
                    "main" to importer,
                    "other" to unrelated
                ),
                edges = listOf(requireEdge(importer, "exporter", exporter))
            )
        )

        val current = previous.copy(
            files = previous.files + mapOf(
                exporter to fileSnapshot(
                    fingerprint = stablePublic,
                    modules = setOf("exporter"),
                    cacheKey = "exporter-body-v2"
                )
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(exporter), plan.changedFiles)
        assertTrue(plan.publicSurfaceChangedFiles.isEmpty())
        assertTrue(plan.reverseDependencyDirtyClosure.isEmpty())
        assertEquals(setOf(exporter), plan.affectedDocuments)
        assertFalse(importer in plan.affectedDocuments)
        assertFalse(unrelated in plan.affectedDocuments)
    }

    @Test
    fun require_edge_absent_leaves_non_consumer_clean_when_exporter_changes() {
        val exporter = path("pkg/exporter.lua")
        val nonConsumer = path("pkg/non_consumer.lua")

        val previous = snapshot(
            files = mapOf(
                exporter to fileSnapshot(fingerprint = "exporter-v1", modules = setOf("exporter")),
                nonConsumer to fileSnapshot(fingerprint = "non-v1", modules = setOf("non_consumer"))
            ),
            graph = requireGraph(
                providers = mapOf(
                    "exporter" to exporter,
                    "non_consumer" to nonConsumer
                ),
                edges = emptyList()
            )
        )

        val current = previous.copy(
            files = previous.files + mapOf(
                exporter to fileSnapshot(fingerprint = "exporter-v2", modules = setOf("exporter"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(exporter), plan.changedFiles)
        assertEquals(setOf(exporter), plan.publicSurfaceChangedFiles)
        assertTrue(plan.reverseDependencyDirtyClosure.isEmpty())
        assertEquals(setOf(exporter), plan.affectedDocuments)
        assertFalse(nonConsumer in plan.affectedDocuments)
    }

    @Test
    fun engine_level_exporter_edit_dirties_importers_via_require_edges() {
        val engine = LuaWorkspaceEngine()
        val exporter = path("exporter.lua")
        val mid = path("mid.lua")
        val top = path("top.lua")
        val unrelated = path("unrelated.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    exporter to "local M = { value = 1 }\nreturn M",
                    mid to "local exp = require(\"exporter\")\nreturn { value = exp.value }",
                    top to "local mid = require(\"mid\")\nreturn { value = mid.value }",
                    unrelated to "return { ok = true }"
                )
            )
        )

        // Sanity: require edges exist exporter <- mid <- top
        val graph = initial.snapshot.graph
        assertEquals(setOf(mid), graph.reverseDependencies[exporter])
        assertEquals(setOf(top), graph.reverseDependencies[mid])
        assertFalse(unrelated in graph.reverseDependencies[exporter].orEmpty())
        assertFalse(unrelated in graph.reverseDependencies[mid].orEmpty())

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    exporter to "local M = { value = 1, extra = true }\nreturn M"
                )
            )
        )

        assertTrue(exporter in updated.publicSurfaceChangedFiles)
        assertTrue(mid in updated.affectedDocuments, "Direct require importer mid must dirty")
        assertTrue(top in updated.affectedDocuments, "Transitive require importer top must dirty")
        assertFalse(unrelated in updated.affectedDocuments, "Unrelated file must stay clean")
        assertEquals(setOf(exporter, mid, top), updated.affectedDocuments)
    }

    @Test
    fun engine_level_private_only_exporter_edit_keeps_importers_and_unrelated_clean() {
        val engine = LuaWorkspaceEngine()
        val exporter = path("exporter.lua")
        val importer = path("importer.lua")
        val unrelated = path("unrelated.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    exporter to "local private = 1\nlocal M = { value = 1 }\nreturn M",
                    importer to "local exp = require(\"exporter\")\nreturn { value = exp.value }",
                    unrelated to "return { ok = true }"
                )
            )
        )

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    exporter to "local private = 99\nlocal M = { value = 1 }\nreturn M"
                )
            )
        )

        assertEquals(setOf(exporter), updated.affectedDocuments)
        assertFalse(importer in updated.affectedDocuments)
        assertFalse(unrelated in updated.affectedDocuments)
        assertTrue(
            updated.publicSurfaceChangedFiles.isEmpty() ||
                importer !in updated.affectedDocuments
        )
    }

    @Test
    fun engine_level_two_importers_share_exporter_require_edge_dirty_set() {
        val engine = LuaWorkspaceEngine()
        val exporter = path("api.lua")
        val a = path("a.lua")
        val b = path("b.lua")
        val unrelated = path("unrelated.lua")

        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    exporter to "local M = { flag = false }\nreturn M",
                    a to "local api = require(\"api\")\nreturn { flag = api.flag }",
                    b to "local api = require(\"api\")\nreturn { flag = api.flag }",
                    unrelated to "return {}"
                )
            )
        )

        val reverse = initial.snapshot.graph.reverseDependencies[exporter].orEmpty()
        assertTrue(a in reverse)
        assertTrue(b in reverse)
        assertFalse(unrelated in reverse)

        val updated = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(
                    exporter to "local M = { flag = true }\nreturn M"
                )
            )
        )

        assertTrue(a in updated.affectedDocuments)
        assertTrue(b in updated.affectedDocuments)
        assertTrue(exporter in updated.affectedDocuments)
        assertFalse(unrelated in updated.affectedDocuments)
        assertEquals(setOf(exporter, a, b), updated.affectedDocuments)
    }

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
     * Build a require-edge module graph with reverse edges and singleton SCCs for every
     * participating file (mirrors WorkspaceModuleGraphBuilder output shape).
     */
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
