package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraphBuilder
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceModuleGraphTest {
    @Test
    fun path_derived_provider_is_indexed() {
        val graph = buildGraph(
            "pkg/runtime.lua" to "return { value = 1 }"
        )

        assertEquals(
            VirtualPath.of("pkg/runtime.lua"),
            graph.activeProviders.getValue("pkg.runtime").path
        )
    }

    @Test
    fun top_level_legacy_provider_is_indexed() {
        val graph = buildGraph(
            "legacy.lua" to "module(\"x.y\")"
        )

        assertEquals(VirtualPath.of("legacy.lua"), graph.activeProviders.getValue("x.y").path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.LEGACY_TOP_LEVEL, graph.activeProviders.getValue("x.y").source)
    }

    @Test
    fun explicit_legacy_provider_beats_path_derived_provider_for_active_choice() {
        val graph = buildGraph(
            "pkg/runtime.lua" to "return { value = 1 }",
            "legacy.lua" to "module(\"pkg.runtime\")"
        )

        assertEquals(VirtualPath.of("legacy.lua"), graph.activeProviders.getValue("pkg.runtime").path)
    }

    @Test
    fun conflicts_are_recorded_and_resolved_deterministically() {
        val graph = buildGraph(
            "b.lua" to "module(\"shared\")",
            "a.lua" to "module(\"shared\")"
        )

        assertEquals(
            listOf(VirtualPath.of("a.lua"), VirtualPath.of("b.lua")),
            graph.providerConflicts.getValue("shared").map { it.path }
        )
        assertEquals(VirtualPath.of("a.lua"), graph.activeProviders.getValue("shared").path)
    }

    @Test
    fun unresolved_static_requires_are_recorded_when_no_provider_exists() {
        val graph = buildGraph(
            "main.lua" to "local dep = require(\"missing\")"
        )

        assertEquals(listOf("missing"), graph.unresolvedStaticRequires.getValue(VirtualPath.of("main.lua")).map { it.moduleName })
    }

    @Test
    fun dynamic_requires_are_recorded_and_do_not_create_resolved_edges() {
        val graph = buildGraph(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local dep = require(name)"
        )

        assertEquals(1, graph.dynamicRequireSites.getValue(VirtualPath.of("main.lua")).size)
        assertFalse(graph.resolvedDependencies.containsKey(VirtualPath.of("main.lua")))
    }

    @Test
    fun reverse_dependency_map_is_built_from_resolved_requires() {
        val graph = buildGraph(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local dep = require(\"dep\")"
        )

        assertEquals(
            setOf(VirtualPath.of("main.lua")),
            graph.reverseDependencies.getValue(VirtualPath.of("dep.lua"))
        )
    }

    @Test
    fun strongly_connected_components_collapse_cycles() {
        val graph = buildGraph(
            "a.lua" to "local b = require(\"b\")\nreturn { value = b.value }",
            "b.lua" to "local a = require(\"a\")\nreturn { value = a.value }"
        )

        assertEquals(
            setOf(VirtualPath.of("a.lua"), VirtualPath.of("b.lua")),
            graph.stronglyConnectedComponentByFile.getValue(VirtualPath.of("a.lua"))
        )
        assertTrue(graph.stronglyConnectedComponents.any { it == setOf(VirtualPath.of("a.lua"), VirtualPath.of("b.lua")) })
    }

    @Test
    fun standard_library_overlay_provider_is_indexed() {
        val graph = buildGraphWithOverlay(emptyMap())

        assertEquals(
            VirtualPath.of("__lua_std__/5.3/math.lua"),
            graph.activeProviders.getValue("math").path
        )
        assertEquals(
            WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY,
            graph.activeProviders.getValue("math").source
        )
    }

    @Test
    fun workspace_virtual_path_provider_beats_standard_library_overlay_provider() {
        val graph = buildGraphWithOverlay(
            mapOf("math.lua" to "return { custom = true }")
        )

        assertEquals(VirtualPath.of("math.lua"), graph.activeProviders.getValue("math").path)
        assertEquals(
            listOf(VirtualPath.of("math.lua"), VirtualPath.of("__lua_std__/5.3/math.lua")),
            graph.providerConflicts.getValue("math").map { it.path }
        )
    }

    private fun buildGraph(vararg files: Pair<String, String>): WorkspaceModuleGraph {
        return buildGraphWithOverlay(files.toMap())
    }

    private fun buildGraphWithOverlay(files: Map<String, String>): WorkspaceModuleGraph {
        val snapshots = files.associate { (path, source) ->
            val virtualPath = VirtualPath.of(path)
            virtualPath to WorkspaceSnapshot.FileSnapshot(
                documentFacts = DocumentFactsCollector.collect(virtualPath, LuaParser().parse(source))
            )
        }
        val overlay = BuiltinOverlayLoader.load(io.github.dingyi222666.luaparser.parser.LuaVersion.LUA_5_3) { path, source ->
            WorkspaceSnapshot.FileSnapshot(
                documentFacts = DocumentFactsCollector.collect(path, LuaParser().parse(source))
            )
        }
        return WorkspaceModuleGraphBuilder.build(snapshots, overlay)
    }
}
