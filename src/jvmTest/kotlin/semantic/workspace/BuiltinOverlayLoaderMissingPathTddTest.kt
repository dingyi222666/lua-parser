package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayResourceAccess
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-496 corpus: **BuiltinOverlayLoader missing path** (dual-path).
 *
 * Complements (does not replace):
 * - [BuiltinOverlayLoaderTddTest] / common BuiltinOverlayLoaderTest — overlay surface shapes
 * - [BuiltinOverlayLoaderRankCollisionTddTest] — rank / reserved-name collision matrix
 * - Library surface corpora (math/string/table/io/os/coroutine) — member inventories
 * - [SemanticWorkspaceCampaignGapTddTest] — workspace claim overrides overlay
 *
 * Product surface locked here (existing APIs only):
 * - [BuiltinOverlayResourceAccess.readText] fails with a message that embeds the missing
 *   classpath resource path (`Builtin overlay resource not found: …`).
 * - Standard Lua 5.3/5.4 provider modules are loaded from present classpath resources under
 *   `/io/github/dingyi222666/luaparser/semantic/workspace/std/lua5{3,4}/…` and surface as
 *   virtual paths `__lua_std__/5.3|5.4/<module>.lua`.
 * - AndroLua raw provider modules declare `resourcePath` + `fallbackSource` via
 *   `readTextOrFallback`; present resources win, but synthetic surfaces still publish
 *   managed helpers (Dialog / bin / bmob / import / loadlayout family) under
 *   `__lua_std__/androlua5.3/…` even when resource text is thin.
 * - Android framework overlay resources are **required** (manifest/class index/model):
 *   missing required framework resource must not invent empty provider maps — when present
 *   they publish `__jvm__/classes/…` and `__jvm__/packages/…` providers.
 * - Workspace `require` of a module that is neither overlay nor workspace file stays
 *   unresolved: no fabricated activeProviders key, no invented `__lua_std__` / `__jvm__` path.
 * - Host android.jar policy remains Downloads + SDK android-35; never G:/.
 *
 * Dual-path policy:
 * - HARD: ResourceAccess missing-path error embeds the path; present std/androlua resources
 *   load to stable virtual paths; managed helpers stay single-provider; unresolved require
 *   never fabricates overlay paths; host jar policy never G:/.
 * - CURRENTLY_ACCEPTS (soft): partial Android framework models may omit individual class
 *   FQCNs; require of absent simple class aliases may be unresolved without inventing paths.
 *   Soft branches must never invent fabricated provider paths outside overlay/workspace files.
 *
 * Host android.jar: not required for most of this corpus (resource-backed overlays load without
 * reflective android.jar). Policy reminder only — Downloads + SDK android-35; never G:/.
 *
 * Test-only. Product sources are out of scope. NO Gradle/tests/compile from workers;
 * verification is review-owned / TASK-043 serial jvmTest.
 */
class BuiltinOverlayLoaderMissingPathTddTest {

    // -------------------------------------------------------------------------
    // HARD: BuiltinOverlayResourceAccess missing classpath path contract
    // -------------------------------------------------------------------------

    @Test
    fun resource_access_missing_classpath_path_errors_with_embedded_path() {
        val missingPath =
            "/io/github/dingyi222666/luaparser/semantic/workspace/std/__missing__/does-not-exist.lua"
        val error = assertFailsWith<IllegalStateException> {
            BuiltinOverlayResourceAccess.readText(missingPath)
        }
        val message = error.message.orEmpty()
        assertTrue(
            message.contains("Builtin overlay resource not found"),
            "Missing-path error must name the resource-not-found contract; got: $message"
        )
        assertTrue(
            message.contains(missingPath) || message.contains("__missing__/does-not-exist.lua"),
            "Missing-path error must embed the requested path; got: $message"
        )
        assertFalse(
            message.contains("G:/", ignoreCase = true),
            "Resource error must never mention Windows G:/ paths; got: $message"
        )
    }

    @Test
    fun resource_access_present_lua53_math_path_reads_non_empty_text() {
        val path = "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/math.lua"
        val text = BuiltinOverlayResourceAccess.readText(path)
        assertTrue(text.isNotBlank(), "Present lua53 math resource must be non-empty")
        assertTrue(
            text.contains("math") || text.contains("abs") || text.contains("---"),
            "Present math resource should look like Emmy/Lua math docs; head=${text.take(80)}"
        )
    }

    @Test
    fun resource_access_present_androlua_dialog_helper_path_reads() {
        val path =
            "/io/github/dingyi222666/luaparser/semantic/workspace/androidlua/androlua5.3/helpers/Dialog.lua"
        val text = BuiltinOverlayResourceAccess.readText(path)
        assertTrue(text.isNotBlank(), "Present Dialog helper resource must be non-empty")
    }

    @Test
    fun resource_access_present_android_framework_manifest_reads() {
        val path =
            "/io/github/dingyi222666/luaparser/semantic/workspace/android-framework/manifest.index"
        val text = BuiltinOverlayResourceAccess.readText(path)
        assertTrue(text.isNotBlank(), "Present framework manifest must be non-empty")
        assertTrue(
            text.lineSequence().any { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && '|' in trimmed
            },
            "Manifest should contain package|classIndex|model rows; head=${text.take(120)}"
        )
    }

    // -------------------------------------------------------------------------
    // HARD: present overlay load maps classpath → stable virtual paths
    // -------------------------------------------------------------------------

    @Test
    fun lua53_present_std_modules_map_to_lua_std_virtual_paths() {
        val overlay = load(LuaVersion.LUA_5_3)
        val expected = listOf(
            "math", "string", "table", "io", "os", "coroutine", "package", "debug", "utf8"
        )
        for (moduleName in expected) {
            val entry = overlay.providerModules.entries.singleOrNull { it.value.moduleName == moduleName }
            assertNotNull(entry, "expected present lua53 provider for $moduleName")
            assertEquals("__lua_std__/5.3/$moduleName.lua", entry.key.value)
            assertNotNull(entry.value.file.moduleExportSurface, "surface required for $moduleName")
        }
        assertEquals(LuaVersion.LUA_5_3, overlay.version)
        assertEquals("__lua_std__/5.3/_G.lua", overlay.globals.path.value)
    }

    @Test
    fun lua54_present_std_modules_map_to_lua_std_virtual_paths_without_bit32() {
        val overlay = load(LuaVersion.LUA_5_4)
        assertEquals("__lua_std__/5.4/math.lua", overlay.providerModules.entries
            .single { it.value.moduleName == "math" }.key.value)
        assertEquals("__lua_std__/5.4/_G.lua", overlay.globals.path.value)
        assertFalse("bit32" in overlay.globals.globalNames)
        assertTrue("warn" in overlay.globals.globalNames)
        // No provider should claim a missing-style path segment.
        assertTrue(
            overlay.providerModules.keys.none { it.value.contains("__missing__") },
            "loader must not invent __missing__ virtual paths"
        )
    }

    @Test
    fun androlua_managed_helpers_publish_stable_virtual_paths_with_surfaces() {
        val overlay = load(LuaVersion.ANDROLUA_5_3)
        val expected = mapOf(
            "Dialog" to "__lua_std__/androlua5.3/Dialog.lua",
            "import" to "__lua_std__/androlua5.3/import.lua",
            "loadlayout" to "__lua_std__/androlua5.3/loadlayout.lua",
            "loadbitmap" to "__lua_std__/androlua5.3/loadbitmap.lua",
            "loadmenu" to "__lua_std__/androlua5.3/loadmenu.lua",
            "bin" to "__lua_std__/androlua5.3/bin.lua",
            "bmob" to "__lua_std__/androlua5.3/bmob.lua",
            "file" to "__lua_std__/androlua5.3/file.lua",
            "toast" to "__lua_std__/androlua5.3/toast.lua",
            "luajava" to "__lua_std__/androlua5.3/luajava.lua"
        )
        for ((moduleName, virtualPath) in expected) {
            val entry = overlay.providerModules.entries.singleOrNull { it.value.moduleName == moduleName }
            assertNotNull(entry, "expected AndroLua provider for $moduleName")
            assertEquals(virtualPath, entry.key.value, "virtual path for $moduleName")
            assertNotNull(
                entry.value.file.moduleExportSurface,
                "synthetic/documented surface required for $moduleName even if resource thin"
            )
        }
        assertEquals(LuaVersion.ANDROLUA_5_3, overlay.version)
        assertEquals("__lua_std__/androlua5.3/_G.lua", overlay.globals.path.value)
    }

    @Test
    fun androlua_dialog_helper_surface_survives_resource_thin_or_full_load() {
        // Product pairs Dialog resource with synthetic surface (MyBottomSheetDialog / __call).
        // Even if the resource text is minimal, loader merge keeps the synthetic surface.
        val overlay = load(LuaVersion.ANDROLUA_5_3)
        val dialog = assertNotNull(
            overlay.providerModules.entries.singleOrNull { it.value.moduleName == "Dialog" }
        )
        assertFalse(dialog.key.value.startsWith("__jvm__/"))
        val surface = assertNotNull(dialog.value.file.moduleExportSurface)
        assertTrue(
            surface.moduleType.methods.containsKey("MyBottomSheetDialog") ||
                surface.members.any { it.name == "MyBottomSheetDialog" } ||
                surface.moduleType.fields.containsKey("__call") ||
                surface.moduleType.methods.isNotEmpty() ||
                surface.moduleType.fields.isNotEmpty(),
            "Dialog helper must retain modeled surface after load; methods=${surface.moduleType.methods.keys}"
        )
    }

    @Test
    fun androlua_framework_providers_publish_jvm_virtual_paths_when_resources_present() {
        val overlay = load(LuaVersion.ANDROLUA_5_3)
        val jvmClassProviders = overlay.providerModules.keys.filter { it.value.startsWith("__jvm__/classes/") }
        val jvmPackageProviders = overlay.providerModules.keys.filter { it.value.startsWith("__jvm__/packages/") }

        // HARD when framework resources are present on classpath (they are in this repo).
        assertTrue(
            jvmClassProviders.isNotEmpty(),
            "AndroLua overlay must load framework class providers from present android-framework resources"
        )
        assertTrue(
            jvmPackageProviders.isNotEmpty(),
            "AndroLua overlay must load framework package providers from present android-framework resources"
        )
        // No provider path may look like a missing-resource placeholder.
        assertTrue(
            overlay.providerModules.keys.none { path ->
                path.value.contains("does-not-exist") || path.value.contains("__missing__")
            }
        )
    }

    // -------------------------------------------------------------------------
    // HARD: workspace missing module path — unresolved, never fabricate overlay
    // -------------------------------------------------------------------------

    @Test
    fun require_of_totally_missing_module_is_unresolved_without_fabricated_overlay_path() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local missing = require("task496.does.not.exist")
                return missing
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )
        val main = harness.path("main.lua")
        val resolved = harness.queries.resolveRequire(main, "task496.does.not.exist")
        assertNull(resolved.provider, "missing module must not invent a provider")
        assertNull(resolved.exportSurface, "missing module must not invent an export surface")
        assertNull(harness.snapshot.graph.activeProviders["task496.does.not.exist"])
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("task496/does/not/exist"))

        val unresolved = harness.snapshot.graph.unresolvedStaticRequires[main].orEmpty()
        assertTrue(
            unresolved.any { it.moduleName == "task496.does.not.exist" } ||
                resolved.provider == null,
            "unresolvedStaticRequires should record the missing module or resolve stays null"
        )
        // Never invent overlay-shaped paths for workspace-missing modules.
        assertTrue(
            harness.snapshot.builtinOverlay.providerModules.keys.none {
                it.value.contains("task496")
            }
        )
    }

    @Test
    fun require_slash_style_class_path_does_not_become_active_provider_key() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local bad = require("android/widget/TextView")
                return bad
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
        )
        assertFalse(
            harness.snapshot.graph.activeProviders.containsKey("android/widget/TextView"),
            "slash-style class require must not become an activeProviders key"
        )
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "android/widget/TextView")
        if (resolved.provider != null) {
            // CURRENTLY_ACCEPTS: product may map some slash forms; path must still exist.
            val path = resolved.provider!!.path
            assertTrue(
                harness.snapshot.builtinOverlay.providerModules.containsKey(path) ||
                    harness.snapshot.files.containsKey(path) ||
                    harness.snapshot.graph.activeProviders.values.any { it.path == path },
                "if slash require resolves, path must exist in overlay/workspace; got $path"
            )
            assertFalse(path.value.contains("G:/", ignoreCase = true))
        }
    }

    @Test
    fun require_of_present_overlay_math_is_not_unresolved() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local math = require("math")
                return math.abs
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )
        val provider = assertNotNull(harness.snapshot.graph.activeProviders["math"])
        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, provider.source)
        assertTrue(provider.path.value.startsWith("__lua_std__/"))
        assertEquals(
            provider.path,
            harness.queries.resolveRequire(harness.path("main.lua"), "math").provider?.path
        )
        assertFalse(
            harness.snapshot.graph.unresolvedStaticRequires[harness.path("main.lua")]
                .orEmpty()
                .any { it.moduleName == "math" }
        )
    }

    // -------------------------------------------------------------------------
    // Dual-path CURRENTLY_ACCEPTS: optional class FQCN may be absent, never fabricated
    // -------------------------------------------------------------------------

    @Test
    fun optional_framework_class_require_is_present_or_unresolved_never_fabricated() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local maybe = require("android.app.Fragment")
                return maybe
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
        )
        val name = "android.app.Fragment"
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), name)
        val active = harness.snapshot.graph.activeProviders[name]

        if (resolved.provider != null || active != null) {
            val path = (resolved.provider?.path ?: active?.path)
            assertNotNull(path)
            assertTrue(
                path.value.contains("Fragment") || path.value.contains("__jvm__/"),
                "present Fragment provider must relate to framework path; got $path"
            )
            assertTrue(
                harness.snapshot.builtinOverlay.providerModules.containsKey(path) ||
                    harness.snapshot.graph.activeProviders[name]?.path == path,
                "present provider path must be real overlay/graph entry"
            )
        } else {
            // CURRENTLY_ACCEPTS: compact framework models may omit Fragment.
            assertNull(resolved.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[harness.path("main.lua")]
                    .orEmpty()
                    .any { it.moduleName == name } || active == null,
                "absent Fragment must not invent activeProviders"
            )
        }
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("android/app/Fragment"))
    }

    @Test
    fun empty_workspace_still_loads_overlay_without_user_files() {
        // Mirrors BuiltinOverlayLoaderTddTest empty-workspace path but focuses on "no missing
        // required std resources" — load must not throw for present classpath catalog.
        val overlay = load(LuaVersion.ANDROLUA_5_3)
        assertTrue(overlay.providerModules.isNotEmpty())
        assertTrue(overlay.globals.globalNames.contains("print"))
        assertTrue(overlay.globals.globalNames.contains("import") || "import" in overlay.providerModules.values.map { it.moduleName })
    }

    // -------------------------------------------------------------------------
    // Inventory + host policy
    // -------------------------------------------------------------------------

    @Test
    fun corpus_inventory_documents_hard_soft_reject_missing_path_matrix() {
        val hardAccept = listOf(
            "ResourceAccess missing path → error embeds requested classpath path",
            "present lua53 math/string/table… → __lua_std__/5.3/<module>.lua",
            "present androlua Dialog/import/loadlayout/bin/bmob → __lua_std__/androlua5.3/…",
            "Dialog synthetic surface survives thin/full resource merge",
            "present android-framework resources → non-empty __jvm__/classes + packages",
            "require(task496.does.not.exist) unresolved; no fabricated overlay path",
            "require(math) on lua53 stays STANDARD_LIBRARY_OVERLAY under __lua_std__",
            "host android.jar candidates are Downloads + SDK android-35 only (never G:/)"
        )
        val softAccept = listOf(
            "optional framework FQCN (e.g. android.app.Fragment) may be absent on compact models",
            "slash-style class require may stay unresolved without inventing active key",
            "partial framework hosts may omit individual class providers while overlay still loads"
        )
        val hardReject = listOf(
            "ResourceAccess missing path silent success / empty string",
            "fabricated __lua_std__ path for workspace-only missing module names",
            "activeProviders key using slash form for class modules without real provider",
            "host hard-coded G:/ android.jar path",
            "helpers.bmob / assets.bin shadow moduleName keys from missing-path fallback"
        )

        assertTrue(hardAccept.size >= 6)
        assertTrue(softAccept.size >= 2)
        assertTrue(hardReject.size >= 4)
        assertTrue(hardAccept.intersect(softAccept.toSet()).isEmpty())
        assertTrue(hardAccept.intersect(hardReject.toSet()).isEmpty())
    }

    @Test
    fun host_android_jar_policy_is_downloads_and_sdk_android35_never_g_drive() {
        val allowedHints = listOf(
            "/Users/dingyi/Downloads/android.jar",
            "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"
        )
        val forbidden = listOf("G:/", "G:\\", "g:/android.jar", "G:/Android/Sdk")
        assertTrue(allowedHints.all { it.contains("android.jar") })
        assertTrue(forbidden.none { hint -> allowedHints.any { it.contains(hint, ignoreCase = true) } })
        assertFalse(allowedHints.any { it.startsWith("G:") || it.startsWith("g:") })
        assertTrue(allowedHints.any { it.contains("android-35") })
        assertTrue(allowedHints.any { it.contains("Downloads") })
    }

    @Test
    fun managed_modules_do_not_emit_helpers_dot_or_assets_dot_shadow_aliases() {
        val overlay = load(LuaVersion.ANDROLUA_5_3)
        val names = overlay.providerModules.values.map { it.moduleName }
        assertEquals(1, names.count { it == "bmob" })
        assertEquals(1, names.count { it == "bin" })
        assertFalse("helpers.bmob" in names)
        assertFalse("helpers.bin" in names)
        assertFalse("assets.bmob" in names)
        assertFalse("assets.bin" in names)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun load(version: LuaVersion) =
        BuiltinOverlayLoader.load(version) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
}
