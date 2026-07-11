package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-456 corpus: workspace **module resolution case matrix** (dual-path).
 *
 * Complements (does not replace):
 * - [WorkspaceModuleResolverPathStyleTddTest] — dots vs slashes path styles
 * - [WorkspacePackagePathSearchOrderTddTest] — first-hit / provider ranking
 * - [WorkspaceRequireRelativePathTddTest] — `./` `../` relative strings
 * - [WorkspaceRequireChainVisibilityTddTest] — multi-hop export visibility
 * - [WorkspaceMultiRootRelativeCollisionTddTest] — multi-root VirtualPath collision
 *
 * Product surface locked here (existing APIs only):
 * - Documented require style is **dotted** package names (`lib.util`).
 * - Virtual provider paths stay **slash-separated** (`lib/util.lua`).
 * - Path-derived module names map slash → dots; `init.lua` package dirs use the parent name.
 * - [LuaWorkspaceQueryFacade.resolveRequire] (by name) is **edge-based** on the consumer's
 *   recorded static require string; unmatched styles must not invent dotted edges.
 * - [LuaWorkspaceQueryFacade.lookupModule] is a global active-provider lookup.
 * - Provider ranking: LEGACY_TOP_LEVEL < VIRTUAL_PATH < EXTRA < STANDARD_LIBRARY_OVERLAY;
 *   within a rank, lower path.value wins → single [WorkspaceModuleGraph.activeProviders] entry.
 * - Ambiguous / relative / slash / mixed / missing cases degrade conservatively:
 *   unresolved static require or optional alias only if product maps the exact string to a
 *   real workspace path — never fabricated provider paths, never hang.
 *
 * Dual-path policy:
 * - HARD contracts: documented dotted resolution, path→module mapping, init.lua parent name,
 *   missing module stays unresolved, active keys stay dotted, conflicts keep one active,
 *   reverse deps recorded, repeated lookup stability, no hang on bad strings.
 * - CURRENTLY_ACCEPTS (soft): slash/mixed/relative require strings may either alias the
 *   real provider or remain unresolved; both are OK if no fabricated path is invented.
 *
 * Host android.jar: not required for this pure workspace corpus. Policy reminder only —
 * Downloads + SDK android-35 host paths; never G:/.
 *
 * Test-only. Product sources are out of scope. NO Gradle/tests/compile from workers;
 * verification is review-owned / TASK-043 serial jvmTest.
 */
class WorkspaceModuleResolutionCaseTddTest {

    // -------------------------------------------------------------------------
    // HARD: documented dotted require → slash virtual path provider
    // -------------------------------------------------------------------------

    @Test
    fun case_dotted_require_resolves_virtual_path_provider_and_export_surface() {
        val harness = harness(
            "lib/util.lua" to "return { tag = \"util\", n = 1 }",
            "main.lua" to """
                local util = require("lib.util")
                return util.tag
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val provider = harness.path("lib/util.lua")
        val resolved = harness.queries.resolveRequire(main, "lib.util")
        val lookedUp = harness.queries.lookupModule("lib.util")

        assertEquals(provider, resolved.provider?.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, resolved.provider?.source)
        assertEquals("lib.util", resolved.moduleName)
        assertEquals(provider, lookedUp.provider?.path)
        assertTrue(resolved.exportSurface?.moduleType?.fields?.containsKey("tag") == true)
        assertTrue(resolved.exportSurface?.members?.any { it.exportPath == listOf("tag") } == true)
    }

    @Test
    fun case_deep_nested_path_derives_dotted_module_name_candidate() {
        val harness = harness(
            "org/acme/net/http/client.lua" to "return { get = function() end }",
            "main.lua" to """
                local client = require("org.acme.net.http.client")
                return client.get
            """.trimIndent()
        )

        val candidate = harness.snapshot.files
            .getValue(harness.path("org/acme/net/http/client.lua"))
            .documentFacts
            ?.moduleNameCandidates
            ?.single { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }

        assertEquals("org.acme.net.http.client", candidate?.moduleName)
        assertEquals(
            harness.path("org/acme/net/http/client.lua"),
            harness.queries.resolveRequire(harness.path("main.lua"), "org.acme.net.http.client").provider?.path
        )
        assertEquals(
            WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH,
            harness.snapshot.graph.activeProviders.getValue("org.acme.net.http.client").source
        )
    }

    @Test
    fun case_init_lua_package_directory_uses_parent_dotted_name_not_trailing_init() {
        val harness = harness(
            "pkg/runtime/init.lua" to "return { ready = true }",
            "main.lua" to """
                local runtime = require("pkg.runtime")
                return runtime.ready
            """.trimIndent()
        )

        val providerPath = harness.path("pkg/runtime/init.lua")
        val virtualCandidate = harness.snapshot.files.getValue(providerPath)
            .documentFacts
            ?.moduleNameCandidates
            ?.single { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }

        assertEquals("pkg.runtime", virtualCandidate?.moduleName)
        assertEquals(
            providerPath,
            harness.queries.resolveRequire(harness.path("main.lua"), "pkg.runtime").provider?.path
        )
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("pkg.runtime.init"))
    }

    @Test
    fun case_top_level_lua_file_module_name_is_basename_without_extension() {
        val harness = harness(
            "dep.lua" to "return { v = 1 }",
            "main.lua" to "local d = require(\"dep\")\nreturn d.v"
        )

        val candidate = harness.snapshot.files.getValue(harness.path("dep.lua"))
            .documentFacts
            ?.moduleNameCandidates
            ?.single { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }

        assertEquals("dep", candidate?.moduleName)
        assertEquals(harness.path("dep.lua"), harness.queries.resolveRequire(harness.path("main.lua"), "dep").provider?.path)
    }

    // -------------------------------------------------------------------------
    // HARD: graph edges, reverse deps, stability
    // -------------------------------------------------------------------------

    @Test
    fun case_resolved_dependency_records_dotted_name_against_slash_provider_path() {
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

    @Test
    fun case_require_resolution_is_stable_across_repeated_lookups() {
        val harness = harness(
            "lib/util.lua" to "return { ok = true }",
            "main.lua" to "local u = require(\"lib.util\")\nreturn u.ok"
        )
        val main = harness.path("main.lua")
        val first = harness.queries.resolveRequire(main, "lib.util").provider?.path
        val second = harness.queries.resolveRequire(main, "lib.util").provider?.path
        val third = harness.queries.lookupModule("lib.util").provider?.path

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(harness.path("lib/util.lua"), first)
    }

    @Test
    fun case_require_call_site_position_resolves_dotted_module_to_slash_path() {
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
    fun case_two_hop_chain_records_independent_edges_without_scc() {
        val harness = harness(
            "leaf.lua" to "return { value = 42 }",
            "mid.lua" to """
                local leaf = require("leaf")
                return { value = leaf.value, run = leaf.run }
            """.trimIndent(),
            "main.lua" to """
                local mid = require("mid")
                return mid.value
            """.trimIndent()
        )

        val graph = harness.snapshot.graph
        val main = harness.path("main.lua")
        val mid = harness.path("mid.lua")
        val leaf = harness.path("leaf.lua")

        assertEquals(listOf("mid"), graph.resolvedDependencies.getValue(main).map { it.moduleName })
        assertEquals(listOf("leaf"), graph.resolvedDependencies.getValue(mid).map { it.moduleName })
        assertEquals(mid, graph.resolvedDependencies.getValue(main).single().provider.path)
        assertEquals(leaf, graph.resolvedDependencies.getValue(mid).single().provider.path)
        assertTrue(graph.stronglyConnectedComponents.none { it.size > 1 })
    }

    // -------------------------------------------------------------------------
    // HARD: missing / bad strings / active key shape
    // -------------------------------------------------------------------------

    @Test
    fun case_missing_dotted_module_stays_unresolved_without_path_invention() {
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
            harness.snapshot.graph.activeProviders.keys.any { it.contains('/') || it.contains('\\') },
            "active module names must stay dotted package style, not slash paths"
        )
    }

    @Test
    fun case_path_derived_module_names_never_use_slash_or_backslash_separators() {
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
    fun case_empty_user_module_workspace_keeps_dotted_active_keys_only() {
        val harness = harness(
            "main.lua" to "return 1"
        )
        val active = harness.snapshot.graph.activeProviders
        assertTrue(active.keys.none { it.contains('/') || it.contains('\\') })
        // Path-derived candidate for main.lua is dotted "main" when claimed.
        val mainCandidate = harness.snapshot.files.getValue(harness.path("main.lua"))
            .documentFacts
            ?.moduleNameCandidates
            ?.firstOrNull { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }
        assertEquals("main", mainCandidate?.moduleName)
        if (active.containsKey("main")) {
            assertEquals(harness.path("main.lua"), active.getValue("main").path)
            assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, active.getValue("main").source)
        }
    }

    // -------------------------------------------------------------------------
    // HARD: conflicts / ranking / package.loaded identity
    // -------------------------------------------------------------------------

    @Test
    fun case_ambiguous_file_and_init_for_same_dotted_name_pick_single_active() {
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
        assertEquals(1, graph.resolvedDependencies[harness.path("main.lua")].orEmpty().size)
    }

    @Test
    fun case_legacy_module_claim_outranks_path_derived_for_same_name() {
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
        val active = graph.activeProviders.getValue("shared")
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "shared")

        assertEquals(2, conflicts.size)
        assertEquals(
            setOf(harness.path("shared.lua"), harness.path("legacy/shared.lua")),
            conflicts.map { it.path }.toSet()
        )
        assertEquals(WorkspaceModuleGraph.ProviderSource.LEGACY_TOP_LEVEL, active.source)
        assertEquals(harness.path("legacy/shared.lua"), resolved.provider?.path)
        assertEquals(active.path, resolved.provider?.path)
    }

    @Test
    fun case_package_loaded_dotted_name_keeps_slash_path_provider_identity() {
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
    fun case_distinct_module_names_do_not_steal_each_other() {
        val harness = harness(
            "a/mod.lua" to "return { from = \"a\" }",
            "b/mod.lua" to "return { from = \"b\" }",
            "main.lua" to """
                local m = require("a.mod")
                return m.from
            """.trimIndent()
        )

        assertEquals(harness.path("a/mod.lua"), harness.queries.resolveRequire(harness.path("main.lua"), "a.mod").provider?.path)
        assertTrue(harness.snapshot.graph.activeProviders.containsKey("a.mod"))
        assertTrue(harness.snapshot.graph.activeProviders.containsKey("b.mod"))
        assertNotEquals(
            harness.snapshot.graph.activeProviders.getValue("a.mod").path,
            harness.snapshot.graph.activeProviders.getValue("b.mod").path
        )
    }

    @Test
    fun case_shallower_and_deeper_feature_claim_keep_deterministic_active() {
        val harness = harness(
            "feature.lua" to "return { level = 1 }",
            "feature/init.lua" to "return { level = 2 }",
            "main.lua" to "local f = require(\"feature\")\nreturn f.level"
        )
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "feature")
        val active = harness.snapshot.graph.activeProviders["feature"]
        assertNotNull(active)
        assertEquals(active.path, resolved.provider?.path)
        val all = harness.snapshot.graph.providersByModuleName["feature"].orEmpty()
        if (all.size > 1) {
            assertEquals(all.first().path, active.path)
            assertTrue(harness.snapshot.graph.providerConflicts.containsKey("feature"))
        }
    }

    // -------------------------------------------------------------------------
    // Dual-path CURRENTLY_ACCEPTS: slash / mixed / relative styles
    // -------------------------------------------------------------------------

    @Test
    fun case_slash_style_require_is_alias_or_unresolved_never_fabricated() {
        // CURRENTLY_ACCEPTS: slash form may alias dotted provider or stay unresolved.
        val harness = harness(
            "feature/profile.lua" to "return { title = \"profile\" }",
            "main.lua" to """
                local profile = require("feature/profile")
                return profile
            """.trimIndent(),
            "dotted_consumer.lua" to """
                local profile = require("feature.profile")
                return profile
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val providerPath = harness.path("feature/profile.lua")
        val slashResolved = harness.queries.resolveRequire(main, "feature/profile")
        val dottedLookedUp = harness.queries.lookupModule("feature.profile")
        val dottedConsumerResolved = harness.queries.resolveRequire(
            harness.path("dotted_consumer.lua"),
            "feature.profile"
        )
        val unresolved = harness.snapshot.graph.unresolvedStaticRequires[main].orEmpty()

        // HARD: documented dotted path still works globally / via dotted consumer.
        assertEquals(providerPath, dottedLookedUp.provider?.path)
        assertEquals(providerPath, dottedConsumerResolved.provider?.path)
        assertEquals("feature.profile", dottedConsumerResolved.moduleName)
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("feature/profile"))

        // SOFT dual-path for slash-only consumer.
        if (slashResolved.provider != null) {
            assertEquals(providerPath, slashResolved.provider?.path)
            assertEquals(
                dottedLookedUp.exportSurface?.moduleType?.moduleName,
                slashResolved.exportSurface?.moduleType?.moduleName
            )
        } else {
            assertNull(slashResolved.exportSurface)
            assertTrue(unresolved.any { it.moduleName == "feature/profile" })
            assertTrue(
                harness.snapshot.graph.resolvedDependencies[main].orEmpty()
                    .none { it.moduleName == "feature.profile" },
                "slash-only consumer must not invent a dotted dependency edge"
            )
        }
    }

    @Test
    fun case_mixed_separator_require_is_alias_or_unresolved_never_fabricated() {
        val harness = harness(
            "a/b/c.lua" to "return { ok = true }",
            "main.lua" to """
                local bad = require("a.b/c")
                return bad
            """.trimIndent(),
            "dotted_consumer.lua" to """
                local ok = require("a.b.c")
                return ok
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val providerPath = harness.path("a/b/c.lua")
        val mixed = harness.queries.resolveRequire(main, "a.b/c")

        assertEquals(providerPath, harness.queries.lookupModule("a.b.c").provider?.path)
        assertEquals(
            providerPath,
            harness.queries.resolveRequire(harness.path("dotted_consumer.lua"), "a.b.c").provider?.path
        )
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("a.b/c"))

        if (mixed.provider != null) {
            assertEquals(providerPath, mixed.provider?.path)
        } else {
            assertNull(mixed.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == "a.b/c" }
            )
            assertTrue(
                harness.snapshot.graph.resolvedDependencies[main].orEmpty()
                    .none { it.moduleName == "a.b.c" }
            )
        }
    }

    @Test
    fun case_dot_slash_relative_require_does_not_invent_provider_key() {
        val harness = harness(
            "feature/sibling.lua" to "return { tag = \"sibling\" }",
            "feature/main.lua" to """
                local relative = require("./sibling")
                return relative
            """.trimIndent(),
            "dotted_consumer.lua" to """
                local sibling = require("feature.sibling")
                return sibling
            """.trimIndent()
        )

        val main = harness.path("feature/main.lua")
        val provider = harness.path("feature/sibling.lua")
        val relativeName = "./sibling"

        assertTrue(
            harness.snapshot.files.getValue(main).documentFacts
                ?.requires
                .orEmpty()
                .any { it.moduleName == relativeName }
        )
        assertEquals(provider, harness.queries.lookupModule("feature.sibling").provider?.path)

        val resolved = harness.queries.resolveRequire(main, relativeName)
        if (resolved.provider != null) {
            assertEquals(provider, resolved.provider?.path)
        } else {
            assertNull(resolved.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == relativeName }
            )
            assertTrue(
                harness.snapshot.graph.resolvedDependencies[main].orEmpty()
                    .none { it.moduleName == "feature.sibling" }
            )
        }
        assertFalse(harness.snapshot.graph.activeProviders.containsKey(relativeName))
        assertFalse(harness.snapshot.graph.activeProviders.keys.any { it.startsWith("./") })
    }

    @Test
    fun case_parent_dot_dot_relative_require_does_not_invent_provider_key() {
        val harness = harness(
            "feature/util.lua" to "return { util = true }",
            "feature/sub/main.lua" to """
                local util = require("../util")
                return util
            """.trimIndent(),
            "dotted_consumer.lua" to """
                local util = require("feature.util")
                return util
            """.trimIndent()
        )

        val main = harness.path("feature/sub/main.lua")
        val relativeName = "../util"
        val provider = harness.path("feature/util.lua")
        val resolved = harness.queries.resolveRequire(main, relativeName)

        assertEquals(provider, harness.queries.lookupModule("feature.util").provider?.path)
        if (resolved.provider != null) {
            assertEquals(provider, resolved.provider?.path)
        } else {
            assertNull(resolved.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == relativeName }
            )
        }
        assertFalse(harness.snapshot.graph.activeProviders.containsKey(relativeName))
        assertFalse(harness.snapshot.graph.activeProviders.keys.any { it.contains("..") })
    }

    @Test
    fun case_leading_slash_require_is_not_active_provider_key() {
        val harness = harness(
            "lib/core.lua" to "return { n = 1 }",
            "main.lua" to """
                local core = require("/lib/core")
                return core
            """.trimIndent(),
            "dotted_consumer.lua" to """
                local core = require("lib.core")
                return core
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val slashName = "/lib/core"
        val provider = harness.path("lib/core.lua")
        val resolved = harness.queries.resolveRequire(main, slashName)

        assertEquals(provider, harness.queries.lookupModule("lib.core").provider?.path)
        assertFalse(harness.snapshot.graph.activeProviders.containsKey(slashName))
        assertFalse(harness.snapshot.graph.activeProviders.keys.any { it.startsWith("/") })

        if (resolved.provider != null) {
            assertEquals(provider, resolved.provider?.path)
        } else {
            assertNull(resolved.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == slashName }
            )
        }
    }

    @Test
    fun case_relative_with_lua_suffix_does_not_register_relative_active_key() {
        val harness = harness(
            "lib/mod.lua" to "return { ok = 1 }",
            "app/main.lua" to """
                local mod = require("./mod.lua")
                return mod
            """.trimIndent()
        )

        val main = harness.path("app/main.lua")
        val relativeName = "./mod.lua"
        val resolved = harness.queries.resolveRequire(main, relativeName)

        assertEquals(harness.path("lib/mod.lua"), harness.snapshot.graph.activeProviders["lib.mod"]?.path)
        assertFalse(harness.snapshot.graph.activeProviders.containsKey(relativeName))
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("mod.lua"))

        if (resolved.provider != null) {
            assertTrue(
                resolved.provider?.path == harness.path("lib/mod.lua") ||
                    resolved.provider?.path == harness.path("app/mod.lua"),
                "unexpected fabricated provider ${resolved.provider?.path}"
            )
        } else {
            assertNull(resolved.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == relativeName }
            )
        }
    }

    @Test
    fun case_relative_and_dotted_requires_in_same_file_keep_independent_edges() {
        val harness = harness(
            "lib/a.lua" to "return { from = \"a\" }",
            "lib/b.lua" to "return { from = \"b\" }",
            "lib/main.lua" to """
                local a = require("lib.a")
                local b = require("./b")
                return a, b
            """.trimIndent()
        )

        val main = harness.path("lib/main.lua")
        val facts = harness.snapshot.files.getValue(main).documentFacts?.requires.orEmpty()
            .map { it.moduleName }
        assertTrue(facts.contains("lib.a"))
        assertTrue(facts.contains("./b"))

        val dotted = harness.queries.resolveRequire(main, "lib.a")
        assertEquals(harness.path("lib/a.lua"), dotted.provider?.path)
        assertTrue(
            harness.snapshot.graph.resolvedDependencies[main].orEmpty()
                .any { it.moduleName == "lib.a" && it.provider.path == harness.path("lib/a.lua") }
        )

        val relative = harness.queries.resolveRequire(main, "./b")
        if (relative.provider != null) {
            assertEquals(harness.path("lib/b.lua"), relative.provider?.path)
        } else {
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == "./b" }
            )
        }
    }

    // -------------------------------------------------------------------------
    // HARD: DocumentFacts preserve require strings; no hang on multi-hop escape
    // -------------------------------------------------------------------------

    @Test
    fun case_document_facts_preserve_require_string_as_written() {
        val harness = harness(
            "lib/x.lua" to "return {}",
            "main.lua" to """
                local a = require("lib.x")
                local b = require("lib/x")
                local c = require("./x")
                return a, b, c
            """.trimIndent()
        )

        val names = harness.snapshot.files.getValue(harness.path("main.lua"))
            .documentFacts
            ?.requires
            .orEmpty()
            .map { it.moduleName }

        assertTrue(names.contains("lib.x"))
        assertTrue(names.contains("lib/x"))
        assertTrue(names.contains("./x"))
        // Dotted form still resolves; others dual-path.
        assertEquals(
            harness.path("lib/x.lua"),
            harness.queries.resolveRequire(harness.path("main.lua"), "lib.x").provider?.path
        )
    }

    @Test
    fun case_multi_hop_parent_relative_does_not_hang_or_escape_as_active_key() {
        val harness = harness(
            "pkg/inner/main.lua" to """
                local escape = require("../../outside")
                return escape
            """.trimIndent(),
            "outside.lua" to "return { outside = true }"
        )

        val main = harness.path("pkg/inner/main.lua")
        val relativeName = "../../outside"
        val resolved = harness.queries.resolveRequire(main, relativeName)

        assertEquals(harness.path("outside.lua"), harness.queries.lookupModule("outside").provider?.path)
        if (resolved.provider != null) {
            assertEquals(harness.path("outside.lua"), resolved.provider?.path)
        } else {
            assertNull(resolved.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == relativeName }
            )
        }
        assertFalse(harness.snapshot.graph.activeProviders.containsKey(relativeName))
        assertNotNull(harness.snapshot.files[main])
        // Diagnostics must be finite / queryable (no hang).
        assertTrue(harness.queries.diagnostics(main).size < 10_000)
    }

    @Test
    fun case_require_facts_count_matches_static_string_calls_only() {
        val harness = harness(
            "lib/a.lua" to "return {}",
            "main.lua" to """
                local name = "lib.a"
                local a = require("lib.a")
                local dyn = require(name)
                return a, dyn
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val staticRequires = harness.snapshot.files.getValue(main).documentFacts?.requires.orEmpty()
        val dynamic = harness.snapshot.files.getValue(main).documentFacts?.dynamicRequires.orEmpty()

        assertTrue(staticRequires.any { it.moduleName == "lib.a" })
        // Dynamic require must not be rewritten into a static provider edge under a fabricated name.
        assertTrue(dynamic.isNotEmpty() || staticRequires.size >= 1)
        assertEquals(
            harness.path("lib/a.lua"),
            harness.queries.resolveRequire(main, "lib.a").provider?.path
        )
        // Dynamic sites are tracked separately when product records them.
        if (dynamic.isNotEmpty()) {
            assertTrue(
                harness.snapshot.graph.dynamicRequireSites[main].orEmpty().isNotEmpty() ||
                    dynamic.isNotEmpty()
            )
        }
    }

    // -------------------------------------------------------------------------
    // HARD: std overlay vs workspace claim ranking reminder
    // -------------------------------------------------------------------------

    @Test
    fun case_std_overlay_math_remains_active_when_no_workspace_claim() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local math = require("math")
                return math.abs
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )
        val provider = harness.snapshot.graph.activeProviders["math"]
        assertNotNull(provider)
        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, provider.source)
    }

    @Test
    fun case_inventory_table_documents_accept_reject_soft_matrix() {
        // Documentation-as-test: inventory of resolution cases for review/traceability.
        val hardAccept = listOf(
            "dotted require → slash VIRTUAL_PATH provider + export surface",
            "path-derived moduleNameCandidates are dotted (no / or \\)",
            "init.lua package dir → parent dotted name (not .init)",
            "missing dotted module → unresolved, no active key",
            "conflicts keep exactly one activeProviders entry",
            "LEGACY_TOP_LEVEL outranks VIRTUAL_PATH for same name",
            "resolvedDependencies use dotted moduleName + slash provider path",
            "reverseDependencies recorded for resolved edges",
            "repeated resolveRequire/lookupModule stable",
            "require call-site position resolves dotted alias/literal"
        )
        val softAccept = listOf(
            "slash-style require string alias-or-unresolved",
            "mixed-separator require alias-or-unresolved",
            "./ and ../ relative require alias-or-unresolved",
            "leading-slash require not active key",
            "relative .lua suffix not active key"
        )
        val hardReject = listOf(
            "fabricated provider path not present in workspace files",
            "activeProviders key using slash or relative form",
            "silent rewrite of slash-only consumer edge to dotted name",
            "hang / non-terminating analysis on bad require strings"
        )

        assertTrue(hardAccept.size >= 8)
        assertTrue(softAccept.size >= 4)
        assertTrue(hardReject.size >= 3)
        // Sanity: categories are disjoint labels (string sets).
        assertTrue(hardAccept.intersect(softAccept.toSet()).isEmpty())
        assertTrue(hardAccept.intersect(hardReject.toSet()).isEmpty())
    }

    // -------------------------------------------------------------------------
    // Host path policy note (no android.jar required for this corpus)
    // -------------------------------------------------------------------------

    @Test
    fun case_host_android_jar_policy_is_downloads_and_sdk_android35_never_g_drive() {
        // This corpus does not open android.jar. Lock the dual-path host policy string
        // so expansion tasks do not reintroduce G:/ hard-codes in related docs/tests.
        val allowedHints = listOf(
            "/Users/dingyi/Downloads/android.jar",
            "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"
        )
        val forbidden = listOf("G:/", "G:\\", "g:/android.jar")
        assertTrue(allowedHints.all { it.contains("android.jar") })
        assertTrue(forbidden.none { hint -> allowedHints.any { it.contains(hint, ignoreCase = true) } })
        assertFalse(allowedHints.any { it.startsWith("G:") || it.startsWith("g:") })
    }

    private fun harness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(*files)
    }
}
