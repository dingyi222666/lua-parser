package semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDirtySetPlanner
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-206 corpus: WorkspaceDirtySetPlanner reverse-dependency invalidation.
 *
 * Acceptance:
 * - Changing a required module marks dependents dirty.
 * - Unrelated files remain clean.
 *
 * Test-only; product code is out of scope. Verification is review-owned (no Gradle here).
 */
class WorkspaceDirtySetPlannerTddTest {

    @Test
    fun public_surface_change_on_required_module_dirties_direct_and_transitive_dependents() {
        val dep = path("lib/dep.lua")
        val mid = path("lib/mid.lua")
        val top = path("app/top.lua")
        val unrelated = path("app/unrelated.lua")

        val previous = snapshot(
            files = mapOf(
                dep to fileSnapshot(fingerprint = "dep-public-v1", modules = setOf("dep")),
                mid to fileSnapshot(fingerprint = "mid-public-v1", modules = setOf("mid")),
                top to fileSnapshot(fingerprint = "top-public-v1", modules = setOf("top")),
                unrelated to fileSnapshot(fingerprint = "unrelated-public-v1", modules = setOf("unrelated"))
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
                dep to fileSnapshot(fingerprint = "dep-public-v2", modules = setOf("dep"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(dep), plan.publicSurfaceChangedFiles)
        assertEquals(setOf(dep), plan.changedFiles)
        assertTrue(mid in plan.reverseDependencyDirtyClosure, "direct consumer of changed module must be reverse-dirty")
        assertTrue(top in plan.reverseDependencyDirtyClosure, "transitive consumer must be reverse-dirty")
        assertFalse(unrelated in plan.reverseDependencyDirtyClosure, "unrelated file must not enter reverse-dirty closure")
        assertFalse(unrelated in plan.affectedDocuments, "unrelated file must stay clean in affectedDocuments")
        assertEquals(setOf(dep, mid, top), plan.affectedDocuments)
        assertTrue("dep" in plan.affectedModuleNames)
        assertFalse("unrelated" in plan.affectedModuleNames)
    }

    @Test
    fun private_only_change_on_required_module_keeps_dependents_clean() {
        val dep = path("lib/dep.lua")
        val consumer = path("app/consumer.lua")
        val unrelated = path("app/unrelated.lua")

        val sharedPublic = "dep-public-stable"
        val previous = snapshot(
            files = mapOf(
                dep to fileSnapshot(
                    fingerprint = sharedPublic,
                    modules = setOf("dep"),
                    cacheKey = "dep-body-v1"
                ),
                consumer to fileSnapshot(fingerprint = "consumer-public-v1", modules = setOf("consumer")),
                unrelated to fileSnapshot(fingerprint = "unrelated-public-v1", modules = setOf("unrelated"))
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

        // Body/cache changes, but public fingerprint value is unchanged.
        val current = previous.copy(
            files = previous.files + mapOf(
                dep to fileSnapshot(
                    fingerprint = sharedPublic,
                    modules = setOf("dep"),
                    cacheKey = "dep-body-v2"
                )
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(dep), plan.changedFiles)
        assertTrue(plan.publicSurfaceChangedFiles.isEmpty(), "private-only edit must not mark public surface dirty")
        assertTrue(plan.reverseDependencyDirtyClosure.isEmpty(), "dependents must stay reverse-clean for private-only edits")
        assertEquals(setOf(dep), plan.affectedDocuments)
        assertFalse(consumer in plan.affectedDocuments)
        assertFalse(unrelated in plan.affectedDocuments)
    }

    @Test
    fun multi_hop_require_chain_invalidates_only_downstream_dependents() {
        val leaf = path("mods/leaf.lua")
        val a = path("mods/a.lua")
        val b = path("mods/b.lua")
        val c = path("mods/c.lua")
        val side = path("mods/side.lua")

        // side -> leaf (unrelated branch); c -> b -> a -> leaf
        val previous = snapshot(
            files = mapOf(
                leaf to fileSnapshot(fingerprint = "leaf-v1", modules = setOf("leaf")),
                a to fileSnapshot(fingerprint = "a-v1", modules = setOf("a")),
                b to fileSnapshot(fingerprint = "b-v1", modules = setOf("b")),
                c to fileSnapshot(fingerprint = "c-v1", modules = setOf("c")),
                side to fileSnapshot(fingerprint = "side-v1", modules = setOf("side"))
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

        // Public change only on module `a` (mid-chain). Downstream b/c dirty; leaf and side stay clean.
        val current = previous.copy(
            files = previous.files + mapOf(
                a to fileSnapshot(fingerprint = "a-v2", modules = setOf("a"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(a), plan.publicSurfaceChangedFiles)
        assertTrue(b in plan.reverseDependencyDirtyClosure)
        assertTrue(c in plan.reverseDependencyDirtyClosure)
        assertFalse(leaf in plan.reverseDependencyDirtyClosure, "provider itself is not a reverse dependent of its own surface change seed")
        assertFalse(side in plan.reverseDependencyDirtyClosure, "sibling consumer of an unchanged leaf must stay clean")
        assertFalse(side in plan.affectedDocuments)
        assertEquals(setOf(a, b, c), plan.affectedDocuments)
    }

    @Test
    fun public_surface_change_with_no_consumers_dirties_only_changed_file() {
        val alone = path("solo/module.lua")
        val other = path("solo/other.lua")

        val previous = snapshot(
            files = mapOf(
                alone to fileSnapshot(fingerprint = "alone-v1", modules = setOf("alone")),
                other to fileSnapshot(fingerprint = "other-v1", modules = setOf("other"))
            ),
            graph = chainGraph(
                providers = mapOf(
                    "alone" to alone,
                    "other" to other
                ),
                edges = emptyList()
            )
        )
        val current = previous.copy(
            files = previous.files + mapOf(
                alone to fileSnapshot(fingerprint = "alone-v2", modules = setOf("alone"))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertEquals(setOf(alone), plan.changedFiles)
        assertEquals(setOf(alone), plan.publicSurfaceChangedFiles)
        assertTrue(plan.reverseDependencyDirtyClosure.isEmpty())
        assertEquals(setOf(alone), plan.affectedDocuments)
        assertFalse(other in plan.affectedDocuments)
    }

    @Test
    fun scc_expansion_dirties_peer_in_cycle_when_public_surface_changes() {
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
                stronglyConnectedComponents = listOf(scc),
                stronglyConnectedComponentByFile = mapOf(
                    left to scc,
                    right to scc
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
        assertFalse(unrelated in plan.sccExpandedDirtyClosure)
    }

    @Test
    fun require_resolution_change_dirties_consumer_not_unrelated_files() {
        val providerPath = path("mods/dep.lua")
        val consumer = path("app/main.lua")
        val unrelated = path("app/other.lua")

        val previous = snapshot(
            files = mapOf(
                consumer to fileSnapshot(fingerprint = "main-v1", modules = setOf("main")),
                unrelated to fileSnapshot(fingerprint = "other-v1", modules = setOf("other"))
            ),
            graph = WorkspaceModuleGraph(
                activeProviders = mapOf(
                    "main" to provider("main", consumer),
                    "other" to provider("other", unrelated)
                ),
                providersByModuleName = mapOf(
                    "main" to listOf(provider("main", consumer)),
                    "other" to listOf(provider("other", unrelated))
                ),
                unresolvedStaticRequires = mapOf(
                    consumer to listOf(
                        WorkspaceModuleGraph.UnresolvedRequire(
                            consumerPath = consumer,
                            moduleName = "dep",
                            range = Range.EMPTY
                        )
                    )
                )
            )
        )

        val current = snapshot(
            files = mapOf(
                providerPath to fileSnapshot(fingerprint = "dep-v1", modules = setOf("dep")),
                consumer to fileSnapshot(fingerprint = "main-v1", modules = setOf("main")),
                unrelated to fileSnapshot(fingerprint = "other-v1", modules = setOf("other"))
            ),
            graph = chainGraph(
                providers = mapOf(
                    "dep" to providerPath,
                    "main" to consumer,
                    "other" to unrelated
                ),
                edges = listOf(requireEdge(consumer, "dep", providerPath))
            )
        )

        val plan = WorkspaceDirtySetPlanner.plan(previous, current)

        assertTrue(consumer in plan.filesWithRequireResolutionChanged)
        assertTrue(consumer in plan.reverseDependencyDirtyClosure)
        assertTrue(consumer in plan.affectedDocuments)
        assertTrue(providerPath in plan.affectedDocuments, "newly added provider file is changed and must be affected")
        assertFalse(unrelated in plan.filesWithRequireResolutionChanged)
        assertFalse(unrelated in plan.reverseDependencyDirtyClosure)
        assertFalse(unrelated in plan.affectedDocuments)
    }

    @Test
    fun engine_level_public_export_change_matches_planner_dependent_invalidation() {
        // Integration-style corpus via LuaWorkspaceEngine (same acceptance surface as engine tests).
        val engine = io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine()
        val initial = engine.build(
            io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput(
                files = mapOf(
                    path("dep.lua") to "local M = { value = 1 }\nreturn M",
                    path("mid.lua") to "local dep = require(\"dep\")\nreturn { value = dep.value }",
                    path("top.lua") to "local mid = require(\"mid\")\nreturn { value = mid.value }",
                    path("unrelated.lua") to "return { value = 42 }"
                )
            )
        )

        val updated = engine.update(
            previous = initial.snapshot,
            delta = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta(
                upserts = mapOf(
                    path("dep.lua") to "local M = { value = 1, extra = 2 }\nreturn M"
                )
            )
        )

        assertTrue(path("dep.lua") in updated.publicSurfaceChangedFiles)
        assertTrue(path("mid.lua") in updated.affectedDocuments)
        assertTrue(path("top.lua") in updated.affectedDocuments)
        assertFalse(path("unrelated.lua") in updated.affectedDocuments)
        assertEquals(
            setOf(path("dep.lua"), path("mid.lua"), path("top.lua")),
            updated.affectedDocuments
        )
    }

    @Test
    fun engine_level_private_only_change_keeps_unrelated_and_dependents_clean() {
        val engine = io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine()
        val initial = engine.build(
            io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput(
                files = mapOf(
                    path("dep.lua") to "local private = 1\nlocal M = { value = 1 }\nreturn M",
                    path("main.lua") to "local dep = require(\"dep\")\nreturn { value = dep.value }",
                    path("unrelated.lua") to "return { ok = true }"
                )
            )
        )

        val updated = engine.update(
            previous = initial.snapshot,
            delta = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta(
                upserts = mapOf(
                    path("dep.lua") to "local private = 2\nlocal M = { value = 1 }\nreturn M"
                )
            )
        )

        assertEquals(setOf(path("dep.lua")), updated.affectedDocuments)
        assertFalse(path("main.lua") in updated.affectedDocuments)
        assertFalse(path("unrelated.lua") in updated.affectedDocuments)
        assertTrue(updated.publicSurfaceChangedFiles.isEmpty() || path("main.lua") !in updated.affectedDocuments)
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
        return WorkspaceModuleGraph(
            activeProviders = active,
            providersByModuleName = byModule,
            resolvedDependencies = resolved,
            reverseDependencies = reverse.mapValues { (_, consumers) -> consumers.toSet() }
        )
    }
}
