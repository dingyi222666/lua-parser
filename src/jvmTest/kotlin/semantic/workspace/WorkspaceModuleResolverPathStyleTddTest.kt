package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-209 corpus: WorkspaceModuleResolver package path style (dots vs slashes).
 *
 * Documented styles in this workspace model:
 * - Virtual file paths use slash separators (`feature/profile.lua`).
 * - Module / require names use Lua package dots (`feature.profile`).
 * - Path-derived providers map slash paths → dotted module names (including `init.lua`
 *   package directories).
 *
 * Acceptance:
 * - Resolver accepts documented dots vs slashes path styles (require dots resolve to
 *   slash-path providers; path-derived module names stay dotted).
 * - Ambiguous paths degrade conservatively (conflicts recorded, single active provider,
 *   no fabricated resolution for unmatched styles).
 *
 * Test-only. Verification is review-owned (no Gradle from workers).
 */
class WorkspaceModuleResolverPathStyleTddTest {

    @Test
    fun dotted_require_resolves_to_slash_separated_virtual_path_provider() {
        val harness = harness(
            "feature/profile.lua" to "return { title = \"profile\" }",
            "main.lua" to """
                local profile = require("feature.profile")
                return profile.title
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val providerPath = harness.path("feature/profile.lua")
        val resolved = harness.queries.resolveRequire(main, "feature.profile")
        val lookedUp = harness.queries.lookupModule("feature.profile")

        assertEquals(providerPath, resolved.provider?.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, resolved.provider?.source)
        assertEquals("feature.profile", resolved.moduleName)
        assertEquals(providerPath, lookedUp.provider?.path)
        assertTrue(resolved.exportSurface?.moduleType?.fields?.containsKey("title") == true)
    }

    @Test
    fun deep_nested_slash_path_maps_to_dotted_module_name_for_require() {
        val harness = harness(
            "lib/net/http/client.lua" to "return { get = function() end }",
            "main.lua" to """
                local client = require("lib.net.http.client")
                return client.get
            """.trimIndent()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "lib.net.http.client")
        val candidate = harness.snapshot.files
            .getValue(harness.path("lib/net/http/client.lua"))
            .documentFacts
            ?.moduleNameCandidates
            ?.single { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }

        assertEquals("lib.net.http.client", candidate?.moduleName)
        assertEquals(harness.path("lib/net/http/client.lua"), resolved.provider?.path)
        assertEquals(
            WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH,
            harness.snapshot.graph.activeProviders.getValue("lib.net.http.client").source
        )
    }

    @Test
    fun init_lua_package_directory_uses_parent_dotted_module_name() {
        val harness = harness(
            "pkg/runtime/init.lua" to "return { ready = true }",
            "main.lua" to """
                local runtime = require("pkg.runtime")
                return runtime.ready
            """.trimIndent()
        )

        val providerPath = harness.path("pkg/runtime/init.lua")
        val facts = harness.snapshot.files.getValue(providerPath).documentFacts
        val virtualCandidate = facts?.moduleNameCandidates
            ?.single { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }

        assertEquals("pkg.runtime", virtualCandidate?.moduleName)
        assertEquals(
            providerPath,
            harness.queries.resolveRequire(harness.path("main.lua"), "pkg.runtime").provider?.path
        )
        // init.lua must not also claim a trailing ".init" module from the path style.
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("pkg.runtime.init"))
    }

    @Test
    fun package_loaded_dotted_name_keeps_slash_path_provider_identity() {
        val harness = harness(
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
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "feature.loaded")

        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, provider.source)
        assertEquals(harness.path("feature/loaded.lua"), provider.path)
        assertEquals(provider.path, resolved.provider?.path)
        assertEquals("feature.loaded", resolved.moduleName)
    }

    @Test
    fun require_call_site_position_resolves_dotted_module_to_slash_path() {
        val harness = harness(
            "feature/profile.lua" to "return { title = \"profile\" }",
            "main.lua" to """
                local profile = require("feature.profile")
                return profile
            """.trimIndent()
        )

        val atLiteral = harness.queries.resolveRequire(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "feature.profile")
        )
        val atAlias = harness.queries.resolveRequire(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "profile", occurrence = 2)
        )

        assertEquals("feature.profile", atLiteral?.moduleName)
        assertEquals(harness.path("feature/profile.lua"), atLiteral?.provider?.path)
        assertEquals("feature.profile", atAlias?.moduleName)
        assertEquals(harness.path("feature/profile.lua"), atAlias?.provider?.path)
    }

    @Test
    fun slash_style_require_string_is_not_silently_rewritten_when_unmatched() {
        // Documented require style uses dots. A slash-form module string that does not
        // match any registered provider must degrade conservatively (no invented provider).
        val harness = harness(
            "feature/profile.lua" to "return { title = \"profile\" }",
            "main.lua" to """
                local profile = require("feature/profile")
                return profile
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val slashResolved = harness.queries.resolveRequire(main, "feature/profile")
        val dottedResolved = harness.queries.resolveRequire(main, "feature.profile")
        val unresolved = harness.snapshot.graph.unresolvedStaticRequires[main].orEmpty()

        // Path-derived dotted name still works as the documented module style.
        assertEquals(harness.path("feature/profile.lua"), dottedResolved.provider?.path)

        // Slash-form require is either accepted as an alias of the dotted module, or left
        // unresolved — never bound to an unrelated fabricated path.
        if (slashResolved.provider != null) {
            assertEquals(harness.path("feature/profile.lua"), slashResolved.provider?.path)
            assertEquals(
                dottedResolved.exportSurface?.moduleType?.moduleName,
                slashResolved.exportSurface?.moduleType?.moduleName
            )
        } else {
            assertNull(slashResolved.exportSurface)
            assertTrue(unresolved.any { it.moduleName == "feature/profile" })
            assertFalse(harness.snapshot.graph.activeProviders.containsKey("feature/profile"))
        }
    }

    @Test
    fun mixed_separator_require_does_not_fabricate_provider() {
        val harness = harness(
            "a/b/c.lua" to "return { ok = true }",
            "main.lua" to """
                local bad = require("a.b/c")
                return bad
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val mixed = harness.queries.resolveRequire(main, "a.b/c")
        val dotted = harness.queries.resolveRequire(main, "a.b.c")

        assertEquals(harness.path("a/b/c.lua"), dotted.provider?.path)
        // Mixed separators are ambiguous: resolve only if product explicitly aliases them
        // to the unique provider; otherwise stay unresolved without inventing a path.
        if (mixed.provider != null) {
            assertEquals(harness.path("a/b/c.lua"), mixed.provider?.path)
        } else {
            assertNull(mixed.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == "a.b/c" }
            )
        }
    }

    @Test
    fun ambiguous_same_module_name_from_slash_path_and_legacy_degrades_to_single_active_provider() {
        val harness = harness(
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
        val conflicts = graph.providerConflicts.getValue("shared")
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "shared")
        val active = graph.activeProviders.getValue("shared")

        assertEquals(2, conflicts.size)
        assertEquals(
            setOf(harness.path("shared.lua"), harness.path("legacy/shared.lua")),
            conflicts.map { it.path }.toSet()
        )
        // Conservative ranking: legacy top-level wins over path-derived module.
        assertEquals(WorkspaceModuleGraph.ProviderSource.LEGACY_TOP_LEVEL, active.source)
        assertEquals(harness.path("legacy/shared.lua"), resolved.provider?.path)
        assertEquals(active.path, resolved.provider?.path)
        // Only one active provider is exposed for require resolution.
        assertEquals(1, listOfNotNull(resolved.provider).size)
    }

    @Test
    fun ambiguous_path_derived_providers_for_same_dotted_name_pick_stable_active_without_duplicate_require_hits() {
        // Two slash paths that would both claim the same dotted module name are not possible
        // via pure path derivation (`a/b.lua` → `a.b`, `a/b/init.lua` → `a.b`). When both
        // exist, the graph must record a conflict and keep exactly one active provider.
        val harness = harness(
            "a/b.lua" to "return { from = \"file\" }",
            "a/b/init.lua" to "return { from = \"init\" }",
            "main.lua" to """
                local mod = require("a.b")
                return mod.from
            """.trimIndent()
        )

        val graph = harness.snapshot.graph
        val providers = graph.providersByModuleName["a.b"].orEmpty()
        val active = graph.activeProviders["a.b"]
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "a.b")

        assertTrue(providers.size >= 2, "expected both path-derived providers for a.b")
        assertNotNull(active)
        assertEquals(active.path, resolved.provider?.path)
        assertTrue(graph.providerConflicts.containsKey("a.b"))
        assertEquals(
            providers.map { it.path }.toSet(),
            graph.providerConflicts.getValue("a.b").map { it.path }.toSet()
        )
        // Require resolves through the single active provider only.
        assertEquals(1, graph.resolvedDependencies[harness.path("main.lua")].orEmpty().size)
    }

    @Test
    fun missing_dotted_module_stays_unresolved_without_slash_path_invention() {
        val harness = harness(
            "main.lua" to """
                local missing = require("no.such.module")
                return missing
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val resolved = harness.queries.resolveRequire(main, "no.such.module")
        val unresolved = harness.snapshot.graph.unresolvedStaticRequires.getValue(main).single()

        assertNull(resolved.provider)
        assertNull(resolved.exportSurface)
        assertEquals("no.such.module", unresolved.moduleName)
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("no.such.module"))
        assertFalse(
            harness.snapshot.graph.activeProviders.keys.any { it.contains('/') },
            "active module names must stay in dotted package style, not slash paths"
        )
    }

    @Test
    fun path_derived_module_names_never_use_slash_separators() {
        val harness = harness(
            "feature/sub/util.lua" to "return {}",
            "feature/sub/init.lua" to "return {}",
            "top.lua" to "return {}"
        )

        val pathCandidates = harness.snapshot.files.values
            .flatMap { it.documentFacts?.moduleNameCandidates.orEmpty() }
            .filter { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }
            .map { it.moduleName }

        assertTrue(pathCandidates.isNotEmpty())
        assertTrue(pathCandidates.all { !it.contains('/') && !it.contains('\\') })
        assertTrue(pathCandidates.contains("feature.sub.util"))
        assertTrue(pathCandidates.contains("feature.sub"))
        assertTrue(pathCandidates.contains("top"))
    }

    @Test
    fun resolved_dependency_records_dotted_module_name_against_slash_provider_path() {
        val harness = harness(
            "lib/core.lua" to "return { n = 1 }",
            "main.lua" to """
                local core = require("lib.core")
                return core.n
            """.trimIndent()
        )

        val dependency = harness.snapshot.graph.resolvedDependencies
            .getValue(harness.path("main.lua"))
            .single()

        assertEquals("lib.core", dependency.moduleName)
        assertEquals(harness.path("lib/core.lua"), dependency.provider.path)
        assertTrue(dependency.provider.path.value.contains('/'))
        assertFalse(dependency.moduleName.contains('/'))
        assertTrue(
            harness.snapshot.graph.reverseDependencies
                .getValue(harness.path("lib/core.lua"))
                .contains(harness.path("main.lua"))
        )
    }

    private fun harness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(*files)
    }
}
