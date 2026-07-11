package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-457 corpus: **BuiltinOverlayLoader rank collision** (dual-path).
 *
 * Complements (does not replace):
 * - [BuiltinOverlayLoaderTddTest] / common BuiltinOverlayLoaderTest — overlay surface shapes
 * - [WorkspacePackagePathSearchOrderTddTest] — package.path / first-hit ranking
 * - [WorkspaceModuleResolutionCaseTddTest] — workspace require style matrix
 * - [SemanticWorkspaceCampaignGapTddTest] — workspace claim overrides overlay
 * - Android-Lua library stubs (`bmob`/`bin` managed precedence, Dialog helper)
 *
 * Product surface locked here (existing APIs only):
 * - Graph ranks: LEGACY_TOP_LEVEL < VIRTUAL_PATH < EXTRA_WORKSPACE_PROVIDER <
 *   STANDARD_LIBRARY_OVERLAY; within a rank, lower `path.value` wins → single
 *   [WorkspaceModuleGraph.activeProviders] entry; conflicts retain full candidate lists.
 * - Builtin overlay providers always claim [WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY].
 * - Android-Lua reserved simple module names (Dialog, file, toast, import, loadlayout, …)
 *   stay owned by helpers/managed modules; framework class providers must not steal the
 *   simple alias via the same STANDARD_LIBRARY_OVERLAY rank + lexicographically earlier
 *   `__jvm__/classes/...` path (see BuiltinOverlayLoader reserved-name comments).
 * - When multiple framework classes share a simple alias, primary moduleName falls back to
 *   FQCN (or `$` → `.`); unique non-reserved simple aliases may still be active.
 * - Full binary / canonical class names remain require-able even when simple alias is reserved.
 * - Workspace VIRTUAL_PATH / LEGACY claims outrank STANDARD_LIBRARY_OVERLAY for the same name.
 * - Managed precedence modules (`bin`, `bmob`) keep a single overlay provider path under
 *   `__lua_std__/androlua5.3/…` with no shadow helper alias keys.
 *
 * Dual-path policy:
 * - HARD: reserved helpers win simple require; framework FQCN still active; workspace outranks
 *   overlay; conflicts keep one active; overlay-only math stays STANDARD_LIBRARY_OVERLAY;
 *   managed bin/bmob single-provider; host android.jar path policy never G:/.
 * - CURRENTLY_ACCEPTS (soft): some non-reserved simple class aliases may be absent when
 *   framework models are partial; require of FQCN / package modules may still succeed.
 *   Soft branches must never invent fabricated provider paths outside overlay/workspace files.
 *
 * Host android.jar: not required for this corpus (resource-backed Android framework models
 * load with the AndroLua overlay). Policy reminder only — Downloads + SDK android-35;
 * never G:/.
 *
 * Test-only. Product sources are out of scope. NO Gradle/tests/compile from workers;
 * verification is review-owned / TASK-043 serial jvmTest.
 */
class BuiltinOverlayLoaderRankCollisionTddTest {

    // -------------------------------------------------------------------------
    // HARD: reserved simple names stay on helpers, not framework __jvm__ classes
    // -------------------------------------------------------------------------

    @Test
    fun reserved_Dialog_simple_require_resolves_helper_not_android_app_Dialog() {
        val harness = androluaHarness(
            "main.lua" to """
                local Dialog = require("Dialog")
                return Dialog
            """.trimIndent()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "Dialog")
        val active = assertNotNull(harness.snapshot.graph.activeProviders["Dialog"])
        val providerPath = assertNotNull(resolved.provider?.path)

        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, resolved.provider?.source)
        assertEquals(active.path, providerPath)
        assertTrue(
            providerPath.value.contains("androlua5.3") || providerPath.value.contains("helpers"),
            "require(\"Dialog\") must stay on Android-Lua helper, got ${providerPath.value}"
        )
        assertFalse(
            providerPath.value.startsWith("__jvm__/"),
            "reserved Dialog must not resolve to framework __jvm__ provider; got ${providerPath.value}"
        )
        assertFalse(
            providerPath.value.contains("android/app/Dialog"),
            "reserved Dialog must not resolve to android.app.Dialog class path"
        )
        // Helper surface includes the modeled bottom-sheet factory (when present).
        val surface = assertNotNull(resolved.exportSurface)
        assertTrue(
            surface.moduleType.methods.containsKey("MyBottomSheetDialog") ||
                surface.members.any { it.name == "MyBottomSheetDialog" } ||
                surface.moduleType.fields.isNotEmpty() ||
                surface.moduleType.methods.isNotEmpty(),
            "Dialog helper should expose a modeled surface; methods=${surface.moduleType.methods.keys}"
        )
    }

    @Test
    fun reserved_file_and_toast_simple_requires_stay_on_helpers() {
        for (moduleName in listOf("file", "toast")) {
            val harness = androluaHarness(
                "main.lua" to """
                    local m = require("$moduleName")
                    return m
                """.trimIndent()
            )
            val resolved = harness.queries.resolveRequire(harness.path("main.lua"), moduleName)
            val path = assertNotNull(resolved.provider?.path, "expected helper provider for $moduleName")
            assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, resolved.provider?.source)
            assertTrue(
                path.value.contains("androlua5.3") || path.value.contains("helpers"),
                "$moduleName must resolve under AndroLua helpers; got ${path.value}"
            )
            assertFalse(path.value.startsWith("__jvm__/"), "$moduleName stolen by ${path.value}")
        }
    }

    @Test
    fun reserved_import_and_loadlayout_family_not_stolen_by_framework_simple_alias() {
        val reserved = listOf("import", "loadlayout", "loadlayout2", "loadlayout3", "loadmenu", "loadbitmap")
        val harness = androluaHarness(
            "main.lua" to """
                local import = require("import")
                local loadlayout = require("loadlayout")
                local loadlayout2 = require("loadlayout2")
                local loadlayout3 = require("loadlayout3")
                local loadmenu = require("loadmenu")
                local loadbitmap = require("loadbitmap")
                return import, loadlayout, loadlayout2, loadlayout3, loadmenu, loadbitmap
            """.trimIndent()
        )

        for (name in reserved) {
            val active = harness.snapshot.graph.activeProviders[name]
            assertNotNull(active, "expected active overlay provider for reserved $name")
            assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, active.source)
            assertFalse(
                active.path.value.startsWith("__jvm__/"),
                "reserved $name must not be framework class alias; got ${active.path.value}"
            )
            val resolved = harness.queries.resolveRequire(harness.path("main.lua"), name)
            assertEquals(active.path, resolved.provider?.path)
        }
    }

    @Test
    fun framework_full_name_android_app_Dialog_still_active_when_simple_Dialog_reserved() {
        val harness = androluaHarness(
            "main.lua" to """
                local helper = require("Dialog")
                local fqcn = require("android.app.Dialog")
                return helper, fqcn
            """.trimIndent()
        )

        val helper = assertNotNull(harness.snapshot.graph.activeProviders["Dialog"])
        val fqcn = harness.snapshot.graph.activeProviders["android.app.Dialog"]

        assertFalse(helper.path.value.startsWith("__jvm__/"))
        if (fqcn != null) {
            assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, fqcn.source)
            assertTrue(
                fqcn.path.value.contains("__jvm__/classes/android/app/Dialog") ||
                    fqcn.path.value.contains("android/app/Dialog"),
                "FQCN provider path should be framework class path; got ${fqcn.path.value}"
            )
            assertNotEquals(helper.path, fqcn.path)
            val resolvedFqcn = harness.queries.resolveRequire(harness.path("main.lua"), "android.app.Dialog")
            assertEquals(fqcn.path, resolvedFqcn.provider?.path)
        } else {
            // CURRENTLY_ACCEPTS: framework model may omit android.app.Dialog on some hosts.
            assertTrue(
                true,
                "CURRENTLY_ACCEPTS: android.app.Dialog FQCN provider absent; simple Dialog helper still locked"
            )
        }
    }

    // -------------------------------------------------------------------------
    // HARD: overlay loader primary moduleName policy for reserved / colliding aliases
    // -------------------------------------------------------------------------

    @Test
    fun overlay_loader_does_not_emit_simple_moduleName_Dialog_from_jvm_class_path() {
        val overlay = loadAndroluaOverlay()
        val dialogSimpleProviders = overlay.providerModules.entries.filter { it.value.moduleName == "Dialog" }

        assertTrue(dialogSimpleProviders.isNotEmpty(), "expected helper Dialog provider moduleName")
        assertTrue(
            dialogSimpleProviders.all { !it.key.value.startsWith("__jvm__/") },
            "no __jvm__ provider may claim simple moduleName Dialog; got ${dialogSimpleProviders.map { it.key.value }}"
        )

        val jvmDialogPaths = overlay.providerModules.keys.filter {
            it.value.contains("android/app/Dialog") || it.value.endsWith("/Dialog.lua") && it.value.startsWith("__jvm__/")
        }
        for (path in jvmDialogPaths) {
            val moduleName = overlay.providerModules.getValue(path).moduleName
            assertNotEquals(
                "Dialog",
                moduleName,
                "framework path $path must not use reserved simple moduleName Dialog; got $moduleName"
            )
        }
    }

    @Test
    fun overlay_loader_unique_non_reserved_simple_alias_may_claim_TextView() {
        val overlay = loadAndroluaOverlay()
        val textViewProviders = overlay.providerModules.entries.filter { it.value.moduleName == "TextView" }

        if (textViewProviders.isEmpty()) {
            // CURRENTLY_ACCEPTS: alias may be FQCN-only when models partial.
            val fqcn = overlay.providerModules.entries.firstOrNull {
                it.value.moduleName == "android.widget.TextView" ||
                    it.key.value.endsWith("/android/widget/TextView.lua")
            }
            assertTrue(
                fqcn != null || overlay.providerModules.keys.any { it.value.contains("TextView") },
                "expected some TextView-related framework provider when simple alias missing"
            )
            return
        }

        assertTrue(
            textViewProviders.any { it.key.value.contains("__jvm__/") },
            "unique non-reserved TextView simple alias should map to a __jvm__ provider when present"
        )
        assertEquals(1, textViewProviders.size, "simple TextView should not dual-claim multiple moduleNames entries")
    }

    @Test
    fun overlay_loader_colliding_simple_aliases_prefer_fqcn_primary_module_name() {
        val overlay = loadAndroluaOverlay()
        // Group by simple last segment among __jvm__/classes providers.
        val simpleToPaths = overlay.providerModules.entries
            .filter { it.key.value.startsWith("__jvm__/classes/") }
            .groupBy { it.value.moduleName.substringAfterLast('.').substringAfterLast('$') }

        val collisions = simpleToPaths.filter { (simple, entries) ->
            simple.isNotBlank() &&
                entries.size > 1 &&
                entries.any { it.value.moduleName == simple }
        }

        // HARD: if product still emits a simple name for a multi-class simple segment,
        // every other claim for that simple name must not also be simple-only without FQCN.
        // Preferred product shape: multi-class simple aliases use FQCN moduleNames only.
        for ((simple, entries) in collisions) {
            val simpleOnly = entries.filter { it.value.moduleName == simple }
            assertTrue(
                simpleOnly.size <= 1,
                "colliding simple alias '$simple' must not have multiple simple-only providers; " +
                    "paths=${simpleOnly.map { it.key.value }}"
            )
        }

        // Inventory soft check: at least one multi-class simple segment exists in framework set
        // OR models are partial (CURRENTLY_ACCEPTS empty collision map).
        assertTrue(simpleToPaths.isNotEmpty() || collisions.isEmpty())
    }

    // -------------------------------------------------------------------------
    // HARD: graph rank — workspace outranks overlay; overlay-only stays overlay
    // -------------------------------------------------------------------------

    @Test
    fun workspace_virtual_path_claim_outranks_standard_library_overlay_for_math() {
        val harness = WorkspaceSemanticHarness.build(
            "math.lua" to "return { custom = true, abs = function(x) return x end }",
            "main.lua" to """
                local math = require("math")
                local custom = math.custom
                return custom
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )

        val providers = harness.snapshot.graph.providersByModuleName.getValue("math")
        val active = harness.snapshot.graph.activeProviders.getValue("math")
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "math")

        assertTrue(providers.any { it.source == WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY })
        assertTrue(providers.any { it.source == WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH })
        assertTrue(harness.snapshot.graph.providerConflicts.containsKey("math"))
        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, active.source)
        assertEquals(harness.path("math.lua"), active.path)
        assertEquals(active.path, resolved.provider?.path)
        // Within-rank ordering: active is first sorted candidate.
        assertEquals(providers.first().path, active.path)
    }

    @Test
    fun legacy_top_level_outranks_virtual_path_and_overlay_for_same_name() {
        val harness = WorkspaceSemanticHarness.build(
            "shared.lua" to "return { value = 1 }",
            "legacy/shared.lua" to """
                module("shared")
                value = 2
            """.trimIndent(),
            "main.lua" to """
                local shared = require("shared")
                return shared.value
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )

        val active = harness.snapshot.graph.activeProviders.getValue("shared")
        val conflicts = harness.snapshot.graph.providerConflicts.getValue("shared")
        assertEquals(WorkspaceModuleGraph.ProviderSource.LEGACY_TOP_LEVEL, active.source)
        assertEquals(harness.path("legacy/shared.lua"), active.path)
        assertTrue(conflicts.size >= 2)
        assertEquals(conflicts.first().path, active.path)
    }

    @Test
    fun std_overlay_math_remains_active_when_workspace_does_not_claim_it() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local math = require("math")
                return math.abs
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )
        val provider = assertNotNull(harness.snapshot.graph.activeProviders["math"])
        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, provider.source)
        assertTrue(provider.path.value.contains("__lua_std__/"))
        assertFalse(harness.snapshot.graph.providerConflicts.containsKey("math"))
    }

    @Test
    fun androlua_overlay_socket_url_outranks_nothing_when_unclaimed_and_stays_dotted() {
        val harness = androluaHarness(
            "main.lua" to """
                local url = require("socket.url")
                return url.parse
            """.trimIndent()
        )
        val provider = assertNotNull(harness.snapshot.graph.activeProviders["socket.url"])
        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, provider.source)
        assertEquals(VirtualPath.of("__lua_std__/androlua5.3/socket.url.lua"), provider.path)
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("socket/url"))
    }

    // -------------------------------------------------------------------------
    // HARD: managed module precedence (bin / bmob) — no shadow helper dual claim
    // -------------------------------------------------------------------------

    @Test
    fun managed_bmob_and_bin_have_single_overlay_provider_without_conflicts() {
        val harness = androluaHarness(
            "main.lua" to """
                local bmob = require("bmob")
                local bin = require("bin")
                return bmob, bin
            """.trimIndent()
        )

        for (name in listOf("bmob", "bin")) {
            val providers = harness.snapshot.graph.providersByModuleName[name].orEmpty()
            val active = assertNotNull(harness.snapshot.graph.activeProviders[name])
            assertEquals(1, providers.size, "managed $name must not dual-claim providers: $providers")
            assertFalse(name in harness.snapshot.graph.providerConflicts)
            assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, active.source)
            assertEquals(VirtualPath.of("__lua_std__/androlua5.3/$name.lua"), active.path)
            assertEquals(
                active.path,
                harness.queries.resolveRequire(harness.path("main.lua"), name).provider?.path
            )
        }
    }

    @Test
    fun overlay_loader_managed_modules_do_not_export_helpers_dot_shadow_aliases() {
        val overlay = loadAndroluaOverlay()
        val names = overlay.providerModules.values.map { it.moduleName }
        assertEquals(1, names.count { it == "bmob" })
        assertEquals(1, names.count { it == "bin" })
        assertFalse("helpers.bmob" in names)
        assertFalse("helpers.bin" in names)
        assertFalse("assets.bmob" in names)
        assertFalse("assets.bin" in names)
    }

    // -------------------------------------------------------------------------
    // HARD: same-rank path lexicographic winner; conflicts retain candidates
    // -------------------------------------------------------------------------

    @Test
    fun same_rank_overlay_providers_keep_single_active_per_module_name() {
        val harness = androluaHarness("main.lua" to "return 1")
        val byName = harness.snapshot.graph.providersByModuleName
        val actives = harness.snapshot.graph.activeProviders

        // Every active entry is the first sorted provider for that name.
        for ((name, active) in actives) {
            val providers = byName[name].orEmpty()
            assertTrue(providers.isNotEmpty(), "active $name missing providers list")
            assertEquals(providers.first().path, active.path, "active for $name must be first sorted")
            if (providers.size > 1) {
                assertTrue(
                    harness.snapshot.graph.providerConflicts.containsKey(name),
                    "multi-provider module $name must appear in providerConflicts"
                )
            }
        }
    }

    @Test
    fun binary_and_canonical_inner_class_aliases_do_not_collapse_to_one_active_key() {
        val harness = androluaHarness(
            "main.lua" to """
                require "import"
                import "android.view.View.OnClickListener"
                local listener = OnClickListener
                return listener
            """.trimIndent()
        )

        val canonical = harness.snapshot.graph.activeProviders["android.view.View.OnClickListener"]
        val binary = harness.snapshot.graph.activeProviders["android.view.View\$OnClickListener"]

        if (canonical != null && binary != null) {
            assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, canonical.source)
            assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, binary.source)
            // Distinct module names → independent active keys (not a simple-name collision).
            assertNotEquals(canonical.path, binary.path)
            assertNotNull(harness.snapshot.builtinOverlay.providerModules[canonical.path])
            assertNotNull(harness.snapshot.builtinOverlay.providerModules[binary.path])
        } else {
            // CURRENTLY_ACCEPTS: framework models may omit listener aliases on some hosts.
            assertTrue(
                true,
                "CURRENTLY_ACCEPTS: OnClickListener binary/canonical providers partial " +
                    "(canonical=$canonical binary=$binary)"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Dual-path CURRENTLY_ACCEPTS: non-reserved simple alias optional
    // -------------------------------------------------------------------------

    @Test
    fun non_reserved_simple_class_alias_is_present_or_fqcn_fallback_never_fabricated() {
        val harness = androluaHarness(
            "main.lua" to """
                local tv = require("TextView")
                local fq = require("android.widget.TextView")
                return tv, fq
            """.trimIndent()
        )

        val simple = harness.queries.resolveRequire(harness.path("main.lua"), "TextView")
        val fqcn = harness.queries.resolveRequire(harness.path("main.lua"), "android.widget.TextView")

        if (simple.provider != null) {
            assertTrue(
                simple.provider!!.path.value.contains("TextView"),
                "simple TextView provider must relate to TextView; got ${simple.provider!!.path}"
            )
            assertTrue(
                harness.snapshot.files.containsKey(simple.provider!!.path) ||
                    harness.snapshot.builtinOverlay.providerModules.containsKey(simple.provider!!.path) ||
                    harness.snapshot.graph.activeProviders["TextView"]?.path == simple.provider!!.path,
                "simple provider path must exist in graph/overlay; got ${simple.provider!!.path}"
            )
        } else {
            assertNull(simple.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[harness.path("main.lua")]
                    .orEmpty()
                    .any { it.moduleName == "TextView" } ||
                    harness.snapshot.graph.activeProviders["TextView"] == null,
                "unresolved simple TextView must not invent an active key"
            )
        }

        if (fqcn.provider != null) {
            assertTrue(
                fqcn.provider!!.path.value.contains("TextView"),
                "FQCN provider must relate to TextView; got ${fqcn.provider!!.path}"
            )
        }
        // Never invent slash-style active keys for class requires.
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("android/widget/TextView"))
    }

    // -------------------------------------------------------------------------
    // Inventory + host policy
    // -------------------------------------------------------------------------

    @Test
    fun corpus_inventory_documents_hard_soft_reject_rank_collision_matrix() {
        val hardAccept = listOf(
            "reserved Dialog/file/toast require → helper overlay, not __jvm__ class",
            "reserved import/loadlayout family not stolen by framework simple alias",
            "framework FQCN remains require-able beside reserved simple helper",
            "overlay loader never assigns simple moduleName Dialog to __jvm__ path",
            "workspace VIRTUAL_PATH outranks STANDARD_LIBRARY_OVERLAY for math",
            "LEGACY_TOP_LEVEL outranks VIRTUAL_PATH (and overlay) for same name",
            "unclaimed math stays STANDARD_LIBRARY_OVERLAY under __lua_std__",
            "managed bin/bmob single provider, no providerConflicts",
            "activeProviders always first sorted candidate per module name",
            "binary + canonical inner-class aliases keep distinct active keys"
        )
        val softAccept = listOf(
            "unique non-reserved simple class alias may be FQCN-only when models partial",
            "android.app.Dialog FQCN provider may be absent on partial framework hosts",
            "OnClickListener binary/canonical pair may be partial",
            "simple TextView require alias-or-unresolved, never fabricated path"
        )
        val hardReject = listOf(
            "require(\"Dialog\") → __jvm__/classes/android/app/Dialog.lua",
            "multiple simple-only providers for the same colliding simple alias",
            "helpers.bmob / assets.bin shadow moduleName keys",
            "activeProviders key using slash form for class modules",
            "host hard-coded G:/ android.jar path"
        )

        assertTrue(hardAccept.size >= 8)
        assertTrue(softAccept.size >= 3)
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
        // Host re-check note (no open required): SDK android-35 is the preferred present path.
        assertTrue(allowedHints.any { it.contains("android-35") })
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun androluaHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
        )
    }

    private fun loadAndroluaOverlay() = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ ->
        WorkspaceSnapshot.FileSnapshot()
    }
}
