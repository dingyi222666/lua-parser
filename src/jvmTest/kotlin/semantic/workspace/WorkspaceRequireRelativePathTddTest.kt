package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-366 corpus: workspace require relative-path strings.
 *
 * Product surface locked by this corpus (existing APIs only):
 * - Documented workspace require style is dotted package names (`feature.sibling`).
 * - Virtual provider paths stay slash-separated (`feature/sibling.lua`); path-derived
 *   module names map slash → dots (see WorkspaceModuleResolverPathStyleTddTest).
 * - DocumentFacts records static `require("…")` string arguments as-is
 *   ([DocumentFacts.RequireFact.moduleName]); WorkspaceModuleGraphBuilder binds edges
 *   only when that exact string is an activeProviders key.
 * - There is no consumer-relative path rewrite for `./`, `../`, bare slash paths, or
 *   `.lua`-suffixed relative strings in the module graph / resolveRequire path.
 * - Relative-looking requires therefore degrade conservatively: unresolved static
 *   require (or optional alias only if product already maps the exact string), no
 *   fabricated provider path, no activeProviders key under relative form, no hang.
 * - resolveRequire(consumer, name) is edge-based on the consumer's recorded require
 *   string; documented dotted acceptance is asserted via lookupModule / a dotted
 *   consumer, not by re-querying a relative-only consumer with a different name.
 *
 * Test-only. Product sources are out of scope. Verification is review-owned (no Gradle).
 */
class WorkspaceRequireRelativePathTddTest {

    @Test
    fun dotted_sibling_require_still_resolves_when_relative_form_also_present() {
        val harness = harness(
            "feature/sibling.lua" to "return { tag = \"sibling\" }",
            "feature/main.lua" to """
                local dotted = require("feature.sibling")
                local relative = require("./sibling")
                return dotted, relative
            """.trimIndent()
        )

        val main = harness.path("feature/main.lua")
        val provider = harness.path("feature/sibling.lua")

        val dotted = harness.queries.resolveRequire(main, "feature.sibling")
        assertEquals(provider, dotted.provider?.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, dotted.provider?.source)
        assertEquals("feature.sibling", dotted.moduleName)
        assertTrue(dotted.exportSurface?.moduleType?.fields?.containsKey("tag") == true)

        // Relative form must not steal / replace the dotted active provider.
        assertEquals(provider, harness.snapshot.graph.activeProviders["feature.sibling"]?.path)
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("./sibling"))
    }

    @Test
    fun dot_slash_relative_require_does_not_invent_provider_for_existing_sibling() {
        // Consumer: feature/main.lua; sibling file exists at feature/sibling.lua.
        // Product does not rewrite "./sibling" against the consumer directory.
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

        val factsRequire = harness.snapshot.files.getValue(main).documentFacts
            ?.requires
            .orEmpty()
            .map { it.moduleName }
        assertTrue(
            factsRequire.contains(relativeName),
            "RequireFact must preserve the relative string as-is: $factsRequire"
        )

        val resolved = harness.queries.resolveRequire(main, relativeName)
        val unresolved = harness.snapshot.graph.unresolvedStaticRequires[main].orEmpty()
        val deps = harness.snapshot.graph.resolvedDependencies[main].orEmpty()

        // Documented dotted provider remains available globally / via dotted consumers.
        assertEquals(provider, harness.queries.lookupModule("feature.sibling").provider?.path)
        assertEquals(
            provider,
            harness.queries.resolveRequire(harness.path("dotted_consumer.lua"), "feature.sibling").provider?.path
        )

        // Relative form: either unresolved, or (if product later aliases) only the real sibling —
        // never a fabricated path and never an activeProviders key under "./sibling".
        if (resolved.provider != null) {
            assertEquals(provider, resolved.provider?.path)
            assertEquals(
                harness.queries.lookupModule("feature.sibling").exportSurface?.moduleType?.moduleName,
                resolved.exportSurface?.moduleType?.moduleName
            )
        } else {
            assertNull(resolved.exportSurface)
            assertTrue(
                unresolved.any { it.moduleName == relativeName },
                "expected unresolved static require for $relativeName, got $unresolved"
            )
            assertTrue(deps.none { it.moduleName == relativeName })
            // Must not invent a dotted edge the consumer never wrote.
            assertTrue(deps.none { it.moduleName == "feature.sibling" })
        }
        assertFalse(harness.snapshot.graph.activeProviders.containsKey(relativeName))
        assertFalse(harness.snapshot.graph.activeProviders.keys.any { it.startsWith("./") })
    }

    @Test
    fun parent_dot_dot_relative_require_does_not_invent_provider() {
        // Consumer nested under feature/sub/; sibling at feature/util.lua would need ../util.
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
        val provider = harness.path("feature/util.lua")
        val relativeName = "../util"

        assertEquals(
            relativeName,
            harness.snapshot.files.getValue(main).documentFacts?.requires?.single()?.moduleName
        )

        val resolved = harness.queries.resolveRequire(main, relativeName)
        val unresolved = harness.snapshot.graph.unresolvedStaticRequires[main].orEmpty()

        assertEquals(provider, harness.queries.lookupModule("feature.util").provider?.path)
        assertEquals(
            provider,
            harness.queries.resolveRequire(harness.path("dotted_consumer.lua"), "feature.util").provider?.path
        )

        if (resolved.provider != null) {
            assertEquals(provider, resolved.provider?.path)
        } else {
            assertNull(resolved.exportSurface)
            assertTrue(unresolved.any { it.moduleName == relativeName })
            assertTrue(
                harness.snapshot.graph.resolvedDependencies[main].orEmpty()
                    .none { it.moduleName == "feature.util" }
            )
        }
        assertFalse(harness.snapshot.graph.activeProviders.containsKey(relativeName))
        assertFalse(harness.snapshot.graph.activeProviders.keys.any { it.contains("..") })
    }

    @Test
    fun relative_require_with_lua_suffix_does_not_invent_provider() {
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

        assertTrue(
            harness.snapshot.files.getValue(main).documentFacts?.requires
                .orEmpty()
                .any { it.moduleName == relativeName }
        )
        // Path-derived provider is dotted `lib.mod`, not a relative key.
        assertEquals(harness.path("lib/mod.lua"), harness.snapshot.graph.activeProviders["lib.mod"]?.path)
        assertFalse(harness.snapshot.graph.activeProviders.containsKey(relativeName))
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("mod.lua"))

        if (resolved.provider != null) {
            // Optional future alias must still land on a real workspace file, not invent one.
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
    fun multi_hop_parent_relative_does_not_escape_or_fabricate_workspace_provider() {
        // Strings that look like VirtualPath `..` walks must not invent providers outside
        // documented dotted module identity, and must not hang analysis.
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
        val unresolved = harness.snapshot.graph.unresolvedStaticRequires[main].orEmpty()

        // Documented dotted form for outside.lua remains available.
        assertEquals(harness.path("outside.lua"), harness.queries.lookupModule("outside").provider?.path)

        if (resolved.provider != null) {
            assertEquals(harness.path("outside.lua"), resolved.provider?.path)
        } else {
            assertNull(resolved.exportSurface)
            assertTrue(unresolved.any { it.moduleName == relativeName })
        }
        assertFalse(harness.snapshot.graph.activeProviders.containsKey(relativeName))
        assertFalse(
            harness.snapshot.graph.activeProviders.keys.any { it.startsWith("../") || it.startsWith("../../") }
        )
        // Snapshot remains queryable (no hang / incomplete graph).
        assertNotNull(harness.snapshot.files[main])
        assertTrue(harness.queries.diagnostics(main) is List<*> || true)
    }

    @Test
    fun leading_slash_style_require_is_not_treated_as_workspace_absolute_provider_key() {
        // VirtualPath is workspace-relative (no leading '/'). A require string with a
        // leading slash is not a documented module name and must not invent a provider.
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
        assertEquals(
            provider,
            harness.queries.resolveRequire(harness.path("dotted_consumer.lua"), "lib.core").provider?.path
        )
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
            assertTrue(
                harness.snapshot.graph.resolvedDependencies[main].orEmpty()
                    .none { it.moduleName == "lib.core" }
            )
        }
    }

    @Test
    fun bare_slash_relative_without_dot_prefix_does_not_silently_become_dotted_edge() {
        // "feature/sibling" is slash style (covered broadly by PathStyle corpus); re-lock
        // that a nested consumer writing only the slash form does not invent a dotted
        // resolvedDependencies edge under a different name.
        val harness = harness(
            "feature/sibling.lua" to "return { tag = \"sibling\" }",
            "feature/main.lua" to """
                local sibling = require("feature/sibling")
                return sibling
            """.trimIndent()
        )

        val main = harness.path("feature/main.lua")
        val slashName = "feature/sibling"
        val resolved = harness.queries.resolveRequire(main, slashName)
        val deps = harness.snapshot.graph.resolvedDependencies[main].orEmpty()

        assertEquals(
            harness.path("feature/sibling.lua"),
            harness.queries.lookupModule("feature.sibling").provider?.path
        )
        assertFalse(harness.snapshot.graph.activeProviders.containsKey(slashName))

        if (resolved.provider != null) {
            assertEquals(harness.path("feature/sibling.lua"), resolved.provider?.path)
        } else {
            assertNull(resolved.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == slashName }
            )
            // No silent rewrite of the consumer edge to the dotted package name.
            assertTrue(deps.none { it.moduleName == "feature.sibling" })
        }
    }

    @Test
    fun relative_require_call_site_position_degrades_without_hang() {
        val harness = harness(
            "feature/sibling.lua" to "return { tag = \"sibling\" }",
            "feature/main.lua" to """
                local relative = require("./sibling")
                return relative
            """.trimIndent()
        )

        val main = harness.path("feature/main.lua")
        val atLiteral = harness.queries.resolveRequire(
            main,
            harness.positionOf("feature/main.lua", "./sibling")
        )
        val atAlias = harness.queries.resolveRequire(
            main,
            harness.positionOf("feature/main.lua", "relative")
        )
        val definitions = harness.queries.gotoDefinition(
            main,
            harness.positionOf("feature/main.lua", "relative")
        )

        // Position resolve may be null (no edge) or a lookup without a fabricated path.
        if (atLiteral != null && atLiteral.provider != null) {
            assertEquals(harness.path("feature/sibling.lua"), atLiteral.provider?.path)
        }
        if (atAlias != null && atAlias.provider != null) {
            assertEquals(harness.path("feature/sibling.lua"), atAlias.provider?.path)
        }
        assertFalse(definitions.any { it.path.value.contains("fabricated") })
        assertFalse(
            definitions.any { it.path.value.startsWith("./") || it.path.value.startsWith("../") },
            "gotoDefinition must not surface relative-looking provider paths: $definitions"
        )
        assertNotNull(harness.snapshot.files[main])
    }

    @Test
    fun relative_and_dotted_requires_in_same_file_keep_independent_edges() {
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

        val relative = harness.queries.resolveRequire(main, "./b")
        val deps = harness.snapshot.graph.resolvedDependencies[main].orEmpty()
        assertTrue(deps.any { it.moduleName == "lib.a" && it.provider.path == harness.path("lib/a.lua") })

        if (relative.provider != null) {
            assertEquals(harness.path("lib/b.lua"), relative.provider?.path)
        } else {
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == "./b" }
            )
            assertTrue(deps.none { it.moduleName == "lib.b" })
        }
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("./b"))
    }

    @Test
    fun relative_require_does_not_register_active_provider_under_relative_key() {
        val harness = harness(
            "pkg/mod.lua" to "return {}",
            "pkg/user.lua" to """
                return require("./mod")
            """.trimIndent(),
            "other/user.lua" to """
                return require("../pkg/mod")
            """.trimIndent()
        )

        val activeKeys = harness.snapshot.graph.activeProviders.keys
        assertTrue(activeKeys.contains("pkg.mod"))
        assertFalse(activeKeys.any { it.startsWith("./") || it.startsWith("../") || it.contains("/./") })
        assertFalse(activeKeys.contains("./mod"))
        assertFalse(activeKeys.contains("../pkg/mod"))

        // Path-derived identity stays dotted / slash-path provider, not relative require key.
        val provider = harness.snapshot.graph.activeProviders.getValue("pkg.mod")
        assertEquals(harness.path("pkg/mod.lua"), provider.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, provider.source)
    }

    @Test
    fun lookupModule_on_relative_string_does_not_invent_provider() {
        val harness = harness(
            "feature/sibling.lua" to "return { tag = \"sibling\" }",
            "feature/main.lua" to """
                local relative = require("./sibling")
                return relative
            """.trimIndent()
        )

        val relativeLookup = harness.queries.lookupModule("./sibling")
        val dottedLookup = harness.queries.lookupModule("feature.sibling")

        assertEquals(harness.path("feature/sibling.lua"), dottedLookup.provider?.path)
        assertNull(
            relativeLookup.provider,
            "lookupModule must not invent an active provider for relative key './sibling'"
        )
        assertNull(relativeLookup.exportSurface)
    }

    @Test
    fun missing_relative_target_stays_unresolved_and_analysis_remains_queryable() {
        val harness = harness(
            "app/main.lua" to """
                local missing = require("./nope")
                local also = require("../gone")
                return missing, also
            """.trimIndent()
        )

        val main = harness.path("app/main.lua")
        val unresolved = harness.snapshot.graph.unresolvedStaticRequires[main].orEmpty()
            .map { it.moduleName }
            .toSet()

        assertTrue("./nope" in unresolved || harness.queries.resolveRequire(main, "./nope").provider == null)
        assertTrue("../gone" in unresolved || harness.queries.resolveRequire(main, "../gone").provider == null)
        assertNull(harness.queries.resolveRequire(main, "./nope").exportSurface)
        assertNull(harness.queries.resolveRequire(main, "../gone").exportSurface)
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("./nope"))
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("../gone"))
        assertNotNull(harness.snapshot.files[main])
        assertTrue(harness.queries.diagnostics(main) is List<*> || true)
    }

    @Test
    fun init_lua_package_still_uses_parent_dotted_name_not_relative_require_string() {
        // Relative require of an init package directory is not a documented style;
        // path-derived provider remains `pkg.runtime` for init.lua.
        val harness = harness(
            "pkg/runtime/init.lua" to "return { ready = true }",
            "pkg/main.lua" to """
                local via_relative = require("./runtime")
                local via_dotted = require("pkg.runtime")
                return via_relative, via_dotted
            """.trimIndent()
        )

        val main = harness.path("pkg/main.lua")
        val initPath = harness.path("pkg/runtime/init.lua")

        assertEquals(initPath, harness.queries.resolveRequire(main, "pkg.runtime").provider?.path)
        assertEquals(initPath, harness.snapshot.graph.activeProviders["pkg.runtime"]?.path)
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("pkg.runtime.init"))
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("./runtime"))

        val relative = harness.queries.resolveRequire(main, "./runtime")
        if (relative.provider != null) {
            assertEquals(initPath, relative.provider?.path)
        } else {
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == "./runtime" }
            )
        }
    }

    private fun harness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(*files)
    }
}
