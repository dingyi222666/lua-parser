package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticWorkspaceCampaignGapTddTest {
    @Test
    fun return_identifier_export_surface_keeps_written_fields_and_methods_queryable() {
        val harness = campaignHarness(
            "feature/profile.lua" to """
                local M = {}
                M.version = 1
                function M:render(user)
                  return user
                end
                return M
            """.trimIndent(),
            "main.lua" to """
                local profile = require("feature.profile")
                local version = profile.version
                local render = profile.render
                return render(version)
            """.trimIndent()
        )

        val surface = surface(harness, "feature/profile.lua")
        val versionHover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "version", occurrence = 2))
        val renderDefinition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "render", occurrence = 2))

        assertEquals(ModuleExportSurface.SourceForm.RETURN_IDENTIFIER, surface.sourceForm)
        assertEquals("feature.profile", surface.moduleType.moduleName)
        assertEquals(SymbolKind.FIELD, surface.members.single { it.exportPath == listOf("version") }.kind)
        assertEquals(SymbolKind.METHOD, surface.members.single { it.exportPath == listOf("render") }.kind)
        assertEquals("1", versionHover?.typeInfo?.displayName)
        assertEquals(listOf(harness.path("feature/profile.lua")), renderDefinition.map { it.path })
    }

    @Test
    fun return_table_literal_exports_nested_members_for_completion_and_definition() {
        val harness = campaignHarness(
            "feature/settings.lua" to """
                return {
                  flags = { enabled = true, label = "beta" },
                  apply = function() return true end
                }
            """.trimIndent(),
            "main.lua" to """
                local settings = require("feature.settings")
                local enabled = settings.flags.enabled
                local apply = settings.apply
                return enabled, apply
            """.trimIndent()
        )

        val surface = surface(harness, "feature/settings.lua")
        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "apply", occurrence = 2))

        assertEquals(ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL, surface.sourceForm)
        assertTrue(surface.members.any { it.exportPath == listOf("flags") })
        assertTrue(surface.members.any { it.exportPath == listOf("flags", "enabled") })
        assertTrue(surface.members.any { it.exportPath == listOf("apply") })
        assertEquals(listOf(harness.path("feature/settings.lua")), definition.map { it.path })
    }

    @Test
    fun alias_return_export_surface_preserves_nested_writes_through_chained_locals() {
        val harness = campaignHarness(
            "feature/router.lua" to """
                local Router = {}
                local alias = Router
                alias.routes = {}
                local routes = alias.routes
                routes.home = "home"
                function routes:open()
                  return routes.home
                end
                return alias
            """.trimIndent(),
            "main.lua" to """
                local router = require("feature.router")
                local home = router.routes.home
                local open = router.routes.open
                return home, open
            """.trimIndent()
        )

        val surface = surface(harness, "feature/router.lua")

        assertTrue(surface.members.any { it.exportPath == listOf("routes", "home") })
        assertTrue(surface.members.any { it.exportPath == listOf("routes", "open") && it.kind == SymbolKind.METHOD })
        assertTrue(surface.moduleType.fields.containsKey("routes"))
    }

    @Test
    fun legacy_module_export_surface_records_seeall_and_implicit_members() {
        val harness = campaignHarness(
            "legacy/runtime.lua" to """
                module("legacy.runtime", package.seeall)
                value = 2
                function _M:run()
                  return value
                end
            """.trimIndent(),
            "main.lua" to """
                local runtime = require("legacy.runtime")
                local value = runtime.value
                local run = runtime.run
                return run, value
            """.trimIndent()
        )

        val surface = surface(harness, "legacy/runtime.lua")
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "legacy.runtime")

        assertEquals(ModuleExportSurface.SourceForm.LEGACY_IMPLICIT, surface.sourceForm)
        assertTrue(surface.hasSeeAllFallback)
        assertTrue(surface.members.any { it.name == "value" && it.kind == SymbolKind.FIELD })
        assertTrue(surface.members.any { it.name == "run" && it.kind == SymbolKind.METHOD })
        assertEquals(harness.path("legacy/runtime.lua"), resolved.provider?.path)
    }

    @Test
    fun package_loaded_export_keeps_virtual_path_provider_and_return_surface() {
        val harness = campaignHarness(
            "feature/loaded.lua" to """
                local M = { status = "loaded" }
                package.loaded["feature.loaded"] = M
                return M
            """.trimIndent(),
            "main.lua" to """
                local loaded = require("feature.loaded")
                return loaded.status
            """.trimIndent()
        )

        val provider = harness.snapshot.graph.activeProviders.getValue("feature.loaded")
        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "status"))

        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, provider.source)
        assertEquals(harness.path("feature/loaded.lua"), provider.path)
        assertTrue(surface(harness, "feature/loaded.lua").members.any { it.name == "status" })
        assertEquals(listOf(harness.path("feature/loaded.lua")), definition.map { it.path })
    }

    @Test
    fun campaign_resource_fixture_exports_are_loaded_from_scoped_directory() {
        val harness = campaignFixtureHarness("fixtures/profile.lua", "fixtures/consumer.lua")

        val profileSurface = surface(harness, "fixtures/profile.lua")
        val consumerPath = harness.path("fixtures/consumer.lua")
        val summaryHover = harness.queries.hover(consumerPath, harness.positionOf("fixtures/consumer.lua", "summary", occurrence = 2))
        val titleDefinition = harness.queries.gotoDefinition(consumerPath, harness.positionOf("fixtures/consumer.lua", "title", occurrence = 2))

        assertEquals("fixtures.profile", profileSurface.moduleType.moduleName)
        assertTrue(profileSurface.members.any { it.name == "title" })
        assertTrue(profileSurface.members.any { it.name == "summary" && it.kind == SymbolKind.METHOD })
        assertEquals(SymbolKind.METHOD, summaryHover?.symbol?.kind)
        assertEquals(listOf(harness.path("fixtures/profile.lua")), titleDefinition.map { it.path })
    }

    @Test
    fun builtin_math_overlay_resolves_when_workspace_has_no_competing_provider() {
        val harness = campaignHarness(
            "main.lua" to """
                local math = require("math")
                local current = math.abs
                return current
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_4
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "math")
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "abs"))

        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, resolved.provider?.source)
        assertEquals(harness.path("__lua_std__/5.4/math.lua"), resolved.provider?.path)
        assertNotNull(hover?.symbol)
        val mathType = assertNotNull(resolved.exportSurface?.moduleType)
        assertTrue(mathType.fields.containsKey("abs") || mathType.methods.containsKey("abs"))
    }

    @Test
    fun workspace_module_provider_overrides_builtin_overlay_for_same_module_name() {
        val harness = campaignHarness(
            "math.lua" to "return { custom = true }",
            "main.lua" to """
                local math = require("math")
                local custom = math.custom
                return custom
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_4
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "math")
        val providers = harness.snapshot.graph.providersByModuleName.getValue("math")
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "custom"))

        assertEquals(harness.path("math.lua"), resolved.provider?.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, resolved.provider?.source)
        assertTrue(providers.any { it.source == WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY })
        assertTrue(harness.snapshot.graph.providerConflicts.containsKey("math"))
        assertEquals("true", hover?.typeInfo?.displayName)
    }

    @Test
    fun builtin_import_overlay_exposes_callable_symbol_through_require_callsite() {
        val harness = campaignHarness(
            "main.lua" to """
                local import = require("import")
                local value = import("java.lang.String")
                return import, value
            """.trimIndent()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), harness.positionOf("main.lua", "import", occurrence = 1))
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "import", occurrence = 3))

        assertEquals("import", resolved?.moduleName)
        assertEquals(harness.path("__lua_std__/androlua5.3/import.lua"), resolved?.provider?.path)
        assertEquals(SymbolKind.FUNCTION, hover?.symbol?.kind)
        assertEquals("fun(...: any...): any", hover?.typeInfo?.displayName)
    }

    @Test
    fun androlua_socket_url_overlay_keeps_dotted_module_resolution_and_parse_method() {
        val harness = campaignHarness(
            "main.lua" to """
                local url = require("socket.url")
                local parse = url.parse
                return parse
            """.trimIndent()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "socket.url")
        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "parse"))
        val surface = assertNotNull(resolved.exportSurface)

        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, resolved.provider?.source)
        assertEquals(harness.path("__lua_std__/androlua5.3/socket.url.lua"), resolved.provider?.path)
        assertEquals("socket.url", surface.moduleType.moduleName)
        assertTrue(surface.moduleType.methods.containsKey("parse"))
        assertEquals(listOf(harness.path("__lua_std__/androlua5.3/socket.url.lua")), definition.map { it.path })
    }

    @Test
    fun static_require_records_resolved_dependency_and_reverse_dependency() {
        val harness = campaignHarness(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to """
                local dep = require("dep")
                return dep.value
            """.trimIndent()
        )

        val graph = harness.snapshot.graph
        val dependency = graph.resolvedDependencies.getValue(harness.path("main.lua")).single()

        assertEquals("dep", dependency.moduleName)
        assertEquals(harness.path("dep.lua"), dependency.provider.path)
        assertTrue(graph.reverseDependencies.getValue(harness.path("dep.lua")).contains(harness.path("main.lua")))
        assertFalse(graph.unresolvedStaticRequires.containsKey(harness.path("main.lua")))
    }

    @Test
    fun unresolved_static_require_is_reported_without_fabricating_provider() {
        val harness = campaignHarness(
            "main.lua" to """
                local missing = require("missing.module")
                return missing
            """.trimIndent()
        )

        val graph = harness.snapshot.graph
        val unresolved = graph.unresolvedStaticRequires.getValue(harness.path("main.lua")).single()
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "missing.module")

        assertEquals("missing.module", unresolved.moduleName)
        assertEquals(harness.path("main.lua"), unresolved.consumerPath)
        assertEquals(null, resolved.provider)
        assertEquals(null, resolved.exportSurface)
    }

    @Test
    fun dynamic_require_site_is_tracked_without_static_dependency_resolution() {
        val harness = campaignHarness(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to """
                local name = "dep"
                local dep = require(name)
                return dep
            """.trimIndent()
        )

        val graph = harness.snapshot.graph
        val dynamicSites = graph.dynamicRequireSites.getValue(harness.path("main.lua"))

        assertEquals(1, dynamicSites.size)
        assertEquals(harness.path("main.lua"), dynamicSites.single().consumerPath)
        assertFalse(graph.resolvedDependencies.containsKey(harness.path("main.lua")))
        assertFalse(graph.unresolvedStaticRequires.containsKey(harness.path("main.lua")))
    }

    @Test
    fun cyclic_require_modules_share_strongly_connected_component_and_keep_exports_queryable() {
        val harness = campaignHarness(
            "cycle/a.lua" to """
                local b = require("cycle.b")
                local M = { value = b.value }
                return M
            """.trimIndent(),
            "cycle/b.lua" to """
                local a = require("cycle.a")
                local M = { value = a.value }
                return M
            """.trimIndent(),
            "main.lua" to """
                local a = require("cycle.a")
                return a.value
            """.trimIndent()
        )

        val component = harness.snapshot.graph.stronglyConnectedComponentByFile.getValue(harness.path("cycle/a.lua"))
        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "value"))

        assertEquals(setOf(harness.path("cycle/a.lua"), harness.path("cycle/b.lua")), component)
        assertTrue(harness.snapshot.graph.stronglyConnectedComponents.any { it == component })
        assertEquals(listOf(harness.path("cycle/a.lua")), definition.map { it.path })
    }

    @Test
    fun require_position_query_resolves_local_alias_back_to_provider_module() {
        val harness = campaignHarness(
            "feature/profile.lua" to "return { title = \"profile\" }",
            "main.lua" to """
                local profile = require("feature.profile")
                local title = profile.title
                return profile, title
            """.trimIndent()
        )

        val resolvedAtCall = harness.queries.resolveRequire(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "feature.profile")
        )
        val resolvedAtAlias = harness.queries.resolveRequire(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "profile", occurrence = 3)
        )
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "title", occurrence = 2))

        assertEquals("feature.profile", resolvedAtCall?.moduleName)
        assertEquals(harness.path("feature/profile.lua"), resolvedAtCall?.provider?.path)
        assertEquals("feature.profile", resolvedAtAlias?.moduleName)
        assertEquals(harness.path("feature/profile.lua"), resolvedAtAlias?.provider?.path)
        assertEquals("\"profile\"", hover?.typeInfo?.displayName)
    }

    @Test
    fun provider_conflicts_keep_legacy_top_level_provider_active_over_path_module() {
        val harness = campaignHarness(
            "shared.lua" to "return { value = 1 }",
            "legacy/shared.lua" to """
                module("shared")
                value = 2
            """.trimIndent(),
            "main.lua" to """
                local shared = require("shared")
                return shared.value
            """.trimIndent()
        )

        val graph = harness.snapshot.graph
        val providers = graph.providerConflicts.getValue("shared")
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "shared")
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "value"))

        assertEquals(
            listOf(harness.path("legacy/shared.lua"), harness.path("shared.lua")),
            providers.map { it.path }
        )
        assertEquals(WorkspaceModuleGraph.ProviderSource.LEGACY_TOP_LEVEL, resolved.provider?.source)
        assertEquals(harness.path("legacy/shared.lua"), resolved.provider?.path)
        assertEquals("2", hover?.typeInfo?.displayName)
    }

    private fun campaignHarness(
        vararg files: Pair<String, String>,
        standardLibraryOverlayVersion: LuaVersion = LuaVersion.ANDROLUA_5_3
    ): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            standardLibraryOverlayVersion = standardLibraryOverlayVersion
        )
    }

    private fun campaignFixtureHarness(vararg resourcePaths: String): WorkspaceSemanticHarness {
        val files = resourcePaths.map { resourcePath ->
            resourcePath to readCampaignFixture(resourcePath)
        }.toTypedArray()
        return campaignHarness(*files)
    }

    private fun readCampaignFixture(resourcePath: String): String {
        val url = assertNotNull(
            javaClass.classLoader.getResource("semantic/campaign-workspace/$resourcePath"),
            "Missing campaign workspace fixture: $resourcePath"
        )
        return url.readText()
    }

    private fun surface(harness: WorkspaceSemanticHarness, path: String): ModuleExportSurface {
        return assertNotNull(harness.snapshot.files.getValue(VirtualPath.of(path)).moduleExportSurface)
    }
}
