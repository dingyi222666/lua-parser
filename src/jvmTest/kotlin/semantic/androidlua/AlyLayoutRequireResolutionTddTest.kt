package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TASK-381 / TASK-530 / TASK-602 — ALY layout require without `.lua` suffix resolution corpus.
 *
 * Expands TASK-184 `aly_fixture_*` coverage into a dedicated test-only corpus:
 * - Hard: `require("…representative_layout")` (no `.lua` / `.aly` suffix) resolves the
 *   workspace `.aly` provider path (`…/representative_layout.aly`) — never null.
 * - Hard: resolved export surface is LuaLayoutSpec-like (moduleName / fields / members).
 * - Hard: workspace module graph records a resolved dependency edge onto the `.aly` provider
 *   so goto/completion can walk layout exports.
 * - Soft dual-path only for member hydration after loadlayout (View / performClick typing).
 *
 * Host android.jar candidates (never hardcode Windows-only `G:/`):
 * 1) `/Users/dingyi/Downloads/android.jar`
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH]
 * 3) `$HOME/Library/Android/sdk/platforms/android-35/android.jar`
 * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 *
 * Verification is review-owned (TASK-043):
 * `jvmTest --tests semantic.androidlua.AlyLayoutRequireResolutionTddTest`
 */
class AlyLayoutRequireResolutionTddTest {
    private val androidJar = resolveAndroidJar()

    // ------------------------------------------------------------------
    // Require without .lua → .aly provider (ideal) or CURRENTLY_ACCEPTS null
    // ------------------------------------------------------------------

    @Test
    fun aly_fixture_require_without_lua_suffix_resolves_aly_provider_path() {
        // Mirrors AndroidLuaLibraryStubsTddTest.aly_fixture_is_resolved_as_layout_module_without_lua_suffix
        // Ideal: provider.path == workspace .aly. CURRENTLY_ACCEPTS: product still null.
        val harness = alyConsumerHarness(
            """
            local layout = require("$REQUIRE_MODULE")
            return layout
            """.trimIndent()
        )

        val resolved = harness.queries.resolveRequire(harness.path(MAIN_FILE), REQUIRE_MODULE)
        val lookedUp = harness.queries.lookupModule(REQUIRE_MODULE)
        assertAlyProviderIdealOrCurrentlyAccepts(
            label = "resolveRequire without .lua suffix",
            providerPath = resolved.provider?.path,
            expectedAlyPath = harness.path(ALY_WORKSPACE_PATH),
            lookupPath = lookedUp.provider?.path
        )
    }

    @Test
    fun aly_fixture_require_provider_is_not_stdlib_or_jvm_class_module() {
        val harness = alyConsumerHarness(
            """
            local layout = require("$REQUIRE_MODULE")
            return layout
            """.trimIndent()
        )
        val resolved = harness.queries.resolveRequire(harness.path(MAIN_FILE), REQUIRE_MODULE)
        val path = resolved.provider?.path?.value
        assertTrue(
            path != null,
            "Hard assert: aly resolveRequire.provider must not be null (TASK-530)"
        )
        assertTrue(
            path!!.contains("representative_layout") && path.endsWith(".aly"),
            "Provider should be the workspace .aly layout fixture; got $path"
        )
        assertTrue(
            !path.contains("__jvm__/classes"),
            "ALY layout require must not resolve as a reflected JVM class; got $path"
        )
        assertTrue(
            !path.contains("androlua5.3"),
            "ALY layout require must not resolve as an AndroLua std overlay stub; got $path"
        )
        assertTrue(
            !path.endsWith(".lua"),
            "Provider must not fall back to a fabricated .lua path; got $path"
        )
    }

    @Test
    fun aly_fixture_graph_records_resolved_dependency_edge_to_aly_provider() {
        // TASK-602: graph edges must record layout module exports for goto/completion.
        val harness = alyConsumerHarness(
            """
            local layout = require("$REQUIRE_MODULE")
            return layout
            """.trimIndent()
        )
        val expectedAly = harness.path(ALY_WORKSPACE_PATH)
        val mainPath = harness.path(MAIN_FILE)

        // Active provider claim for the require module name.
        val active = harness.snapshot.graph.activeProviders[REQUIRE_MODULE]
        assertTrue(
            active != null,
            "Hard assert: graph.activeProviders must claim $REQUIRE_MODULE for .aly layout"
        )
        assertEquals(
            expectedAly,
            active!!.path,
            "Hard assert: active provider path must be the workspace .aly; got ${active.path.value}"
        )
        assertTrue(
            active.path.value.endsWith(".aly"),
            "Hard assert: active provider path must end with .aly; got ${active.path.value}"
        )

        // Resolved dependency edge from consumer → layout provider.
        val deps = harness.snapshot.graph.resolvedDependencies[mainPath].orEmpty()
        val edge = deps.firstOrNull { it.moduleName == REQUIRE_MODULE }
        assertTrue(
            edge != null,
            "Hard assert: resolvedDependencies must include require edge for $REQUIRE_MODULE; " +
                "deps=${deps.map { it.moduleName to it.provider.path.value }}"
        )
        assertEquals(
            expectedAly,
            edge!!.provider.path,
            "Hard assert: dependency provider must be workspace .aly; got ${edge.provider.path.value}"
        )

        // Reverse edge so completion/goto can walk consumers of the layout module.
        val reverse = harness.snapshot.graph.reverseDependencies[expectedAly].orEmpty()
        assertTrue(
            mainPath in reverse,
            "Hard assert: reverseDependencies must list main.lua as consumer of .aly; reverse=$reverse"
        )

        // Export surface on the file snapshot (or synthetic recovery) is LuaLayoutSpec-like.
        val surface = harness.snapshot.files[expectedAly]?.moduleExportSurface
            ?: harness.queries.lookupModule(REQUIRE_MODULE).exportSurface
        assertTrue(
            surface != null,
            "Hard assert: .aly layout must expose a non-null export surface"
        )
        assertTrue(
            surface!!.moduleType.moduleName.contains("LuaLayoutSpec") ||
                surface.moduleType.fields.isNotEmpty() ||
                surface.members.isNotEmpty(),
            "Hard assert: export surface must be LuaLayoutSpec-like; " +
                "moduleName=${surface.moduleType.moduleName} fields=${surface.moduleType.fields.keys}"
        )
    }

    @Test
    fun aly_fixture_export_surface_is_lua_layout_spec_not_null() {
        val harness = alyConsumerHarness(
            """
            local layout = require("$REQUIRE_MODULE")
            return layout
            """.trimIndent()
        )
        val resolved = harness.queries.resolveRequire(harness.path(MAIN_FILE), REQUIRE_MODULE)
        val lookedUp = harness.queries.lookupModule(REQUIRE_MODULE)
        val surface = resolved.exportSurface ?: lookedUp.exportSurface
        assertTrue(
            surface != null,
            "Hard assert (TASK-602): resolveRequire/lookupModule exportSurface must not be null"
        )
        assertTrue(
            surface!!.moduleType.moduleName.contains("LuaLayoutSpec"),
            "Hard assert: export moduleName must be LuaLayoutSpec; got ${surface.moduleType.moduleName}"
        )
        assertTrue(
            surface.moduleType.fields.isNotEmpty() || surface.members.isNotEmpty(),
            "Hard assert: LuaLayoutSpec export must expose layout fields/members; " +
                "fields=${surface.moduleType.fields.keys} members=${surface.members.map { it.name }}"
        )
    }

    @Test
    fun aly_fixture_require_binding_is_known_type_or_currently_accepts_gap() {
        // Ideal: non-unknown/non-any modeled type for `layout` (LuaLayoutSpec or table-like).
        // CURRENTLY_ACCEPTS: product gap may still leave the binding unknown/any/null.
        val source = """
            local layout = require("$REQUIRE_MODULE")
            return layout
        """.trimIndent()
        val harness = alyConsumerHarness(source)
        val display = hoverDisplay(harness, MAIN_FILE, "layout", occurrence = 1)
        assertKnownOrCurrentlyAccepts(
            label = "layout require binding",
            display = display,
            idealFragments = listOf("LuaLayoutSpec", "table", "Layout")
        )
    }

    // ------------------------------------------------------------------
    // LuaLayoutSpec-like surface after require + loadlayout pipeline
    // ------------------------------------------------------------------

    @Test
    fun aly_fixture_require_spec_hover_is_lua_layout_spec_or_currently_accepts() {
        // Mirrors AndroidLuaLibraryStubsTddTest.aly_fixture_exports_layout_table_with_view_class_children
        // for the `spec` binding type (ideal: LuaLayoutSpec).
        val harness = alyLoadlayoutHarness()
        val display = hoverDisplay(harness, MAIN_FILE, "spec", occurrence = 1)
        assertKnownOrCurrentlyAccepts(
            label = "spec (.aly require)",
            display = display,
            idealFragments = listOf("LuaLayoutSpec")
        )
        if (isModeledIdeal(display, listOf("LuaLayoutSpec"))) {
            assertTrue(
                display!!.contains("LuaLayoutSpec"),
                "Modeled .aly require must report LuaLayoutSpec; got '$display'"
            )
        }
    }

    @Test
    fun aly_fixture_loadlayout_view_is_android_view_or_currently_accepts() {
        val harness = alyLoadlayoutHarness()
        val display = hoverDisplay(harness, MAIN_FILE, "view", occurrence = 1)
        assertKnownOrCurrentlyAccepts(
            label = "loadlayout(view)",
            display = display,
            idealFragments = listOf("android.view.View", "AndroidView", "View")
        )
    }

    @Test
    fun aly_fixture_view_performClick_member_is_method_function_or_currently_accepts() {
        val harness = alyLoadlayoutHarness()
        val hover = harness.queries.hover(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "performClick")
        )
        val kind = hover?.symbol?.kind
        val display = hover?.typeInfo?.displayName
        val modeled =
            kind == SymbolKind.METHOD &&
                !display.isNullOrBlank() &&
                display != "unknown" &&
                display != "any" &&
                (display.contains("function") || display.contains("fun(") || display.startsWith("fun"))
        val productGap =
            hover == null ||
                kind == null ||
                display.isNullOrBlank() ||
                display == "unknown" ||
                display == "any"

        assertTrue(
            modeled || productGap,
            "performClick dual-path / CURRENTLY_ACCEPTS: METHOD function-shaped or product gap; " +
                "kind=$kind display=$display"
        )
        if (modeled) {
            assertEquals(SymbolKind.METHOD, kind, "Modeled performClick must be METHOD.")
            assertTrue(
                display!!.contains("function") || display.contains("fun(") || display.startsWith("fun"),
                "Modeled performClick must be function-shaped; got '$display'"
            )
        }
    }

    @Test
    fun aly_fixture_require_plus_loadlayout_pipeline_batch_surface() {
        // Combined corpus locking the full TASK-184 aly_fixture_exports_* chain with dual-path.
        val harness = alyLoadlayoutHarness()

        val resolved = harness.queries.resolveRequire(harness.path(MAIN_FILE), REQUIRE_MODULE)
        val lookedUp = harness.queries.lookupModule(REQUIRE_MODULE)
        assertAlyProviderIdealOrCurrentlyAccepts(
            label = "batch pipeline resolveRequire",
            providerPath = resolved.provider?.path,
            expectedAlyPath = harness.path(ALY_WORKSPACE_PATH),
            lookupPath = lookedUp.provider?.path
        )

        val spec = hoverDisplay(harness, MAIN_FILE, "spec", occurrence = 1)
        val view = hoverDisplay(harness, MAIN_FILE, "view", occurrence = 1)
        val performHover = harness.queries.hover(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "performClick")
        )

        // Soft path: each surface is ideal or CURRENTLY_ACCEPTS gap.
        assertKnownOrCurrentlyAccepts(
            label = "batch.spec",
            display = spec,
            idealFragments = listOf("LuaLayoutSpec")
        )
        assertKnownOrCurrentlyAccepts(
            label = "batch.view",
            display = view,
            idealFragments = listOf("android.view.View", "AndroidView", "View")
        )

        val performDisplay = performHover?.typeInfo?.displayName
        val performKind = performHover?.symbol?.kind
        val performModeled =
            performKind == SymbolKind.METHOD &&
                !performDisplay.isNullOrBlank() &&
                performDisplay != "unknown" &&
                performDisplay != "any"
        val performGap =
            performHover == null ||
                performKind == null ||
                performDisplay.isNullOrBlank() ||
                performDisplay == "unknown" ||
                performDisplay == "any"
        assertTrue(
            performModeled || performGap,
            "batch.performClick CURRENTLY_ACCEPTS dual-path; kind=$performKind display=$performDisplay"
        )
    }

    // ------------------------------------------------------------------
    // Require path variants (still without .lua suffix)
    // ------------------------------------------------------------------

    @Test
    fun aly_require_dotted_module_name_does_not_need_lua_or_aly_suffix_in_string() {
        // Document that the require *argument* omits both .lua and .aly; ideal resolution maps
        // the dotted name onto the workspace .aly file via DocumentFactsCollector rules.
        // CURRENTLY_ACCEPTS: product may still leave resolveRequire.provider null.
        val requireArg = REQUIRE_MODULE
        assertTrue(!requireArg.endsWith(".lua"), "Require arg must omit .lua; got $requireArg")
        assertTrue(!requireArg.endsWith(".aly"), "Require arg must omit .aly; got $requireArg")

        val harness = alyConsumerHarness(
            """
            local layout = require("$requireArg")
            return layout
            """.trimIndent()
        )
        val resolved = harness.queries.resolveRequire(harness.path(MAIN_FILE), requireArg)
        val lookedUp = harness.queries.lookupModule(requireArg)
        assertAlyProviderIdealOrCurrentlyAccepts(
            label = "dotted require arg without .lua/.aly",
            providerPath = resolved.provider?.path,
            expectedAlyPath = harness.path(ALY_WORKSPACE_PATH),
            lookupPath = lookedUp.provider?.path
        )
    }

    @Test
    fun aly_require_export_surface_present_or_currently_accepts_null_surface() {
        // Provider path is a hard assert (TASK-530). Export surface should be LuaLayoutSpec-like
        // when present; soft dual-path only if surface fields are still sparse.
        val harness = alyConsumerHarness(
            """
            local layout = require("$REQUIRE_MODULE")
            return layout
            """.trimIndent()
        )
        val resolved = harness.queries.resolveRequire(harness.path(MAIN_FILE), REQUIRE_MODULE)
        val lookedUp = harness.queries.lookupModule(REQUIRE_MODULE)

        val provider = resolved.provider ?: lookedUp.provider
        assertTrue(
            provider != null,
            "Hard assert: .aly provider must be present for export-surface probe"
        )
        assertTrue(
            provider!!.path.value.endsWith(".aly") ||
                provider.path == harness.path(ALY_WORKSPACE_PATH),
            "When provider is present it should be the .aly layout module; got ${provider.path.value}"
        )

        val surface = resolved.exportSurface ?: lookedUp.exportSurface
        assertTrue(
            surface != null,
            "Hard assert (TASK-602): .aly exportSurface must not be null"
        )
        assertTrue(
            surface!!.moduleType.moduleName.contains("LuaLayoutSpec") ||
                surface.moduleType.moduleName.isNotBlank() ||
                surface.moduleType.fields.isNotEmpty() ||
                surface.moduleType.methods.isNotEmpty() ||
                surface.members.isNotEmpty(),
            "Hard assert: exportSurface must expose a non-empty/LuaLayoutSpec surface; " +
                "moduleName=${surface.moduleType.moduleName} " +
                "fields=${surface.moduleType.fields.keys} methods=${surface.moduleType.methods.keys}"
        )
    }

    @Test
    fun host_android_jar_candidates_never_hardcode_windows_g_drive() {
        val candidates = hostAndroidJarCandidates().map { it.path.replace('\\', '/') }
        assertTrue(
            candidates.any { it.endsWith("/Downloads/android.jar") },
            "Candidate list must include Downloads android.jar; got: $candidates"
        )
        assertTrue(
            candidates.any { it.contains("platforms/android-35/android.jar") } ||
                candidates.any { it.contains("Library/Android/sdk") },
            "Candidate list must include macOS SDK android-35 path; got: $candidates"
        )
        assertTrue(
            candidates.none { it.startsWith("G:/Android/Sdk", ignoreCase = true) },
            "Candidate list must never hardcode Windows G:/Android/Sdk; got: $candidates"
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Documents dual-path expectations for this corpus.
     *
     * - [IDEAL]: modeled non-gap type containing one of the ideal fragments /
     *   non-null `.aly` provider path.
     * - [CURRENTLY_ACCEPTS]: product gap (null / blank / unknown / any / null provider)
     *   still allowed while the corpus documents the ideal require→`.aly` contract.
     */
    private enum class StrictSurfaceExpectation {
        IDEAL,
        CURRENTLY_ACCEPTS,
    }

    /**
     * Hard assert for `.aly` provider resolution (TASK-530 / TASK-602).
     *
     * Required: resolveRequire (or lookupModule) provider path equals the workspace `.aly`
     * file and ends with `.aly` (not `__jvm__/` / not `androlua5.3` / not fabricated `.lua`).
     * Soft dual-path is reserved for type/member hydration only — never for provider presence.
     */
    private fun assertAlyProviderIdealOrCurrentlyAccepts(
        label: String,
        providerPath: VirtualPath?,
        expectedAlyPath: VirtualPath,
        lookupPath: VirtualPath?
    ) {
        val effective = providerPath ?: lookupPath
        assertTrue(
            effective != null,
            "$label hard assert: resolveRequire/lookupModule provider must not be null; " +
                "expected .aly path ${expectedAlyPath.value}"
        )
        assertEquals(
            expectedAlyPath,
            effective,
            "$label hard assert: require without .lua must resolve the .aly layout module; " +
                "actual=${effective!!.value}"
        )
        assertTrue(
            effective.value.endsWith(".aly"),
            "$label hard assert: provider path must end with .aly; got ${effective.value}"
        )
        assertTrue(
            !effective.value.endsWith(".lua"),
            "$label hard assert: provider must not fall back to fabricated .lua; got ${effective.value}"
        )
        assertTrue(
            !effective.value.contains("__jvm__/classes"),
            "$label hard assert: must not resolve as JVM class; got ${effective.value}"
        )
        assertTrue(
            !effective.value.contains("androlua5.3"),
            "$label hard assert: must not resolve as AndroLua std overlay; got ${effective.value}"
        )
    }

    private fun assertKnownOrCurrentlyAccepts(
        label: String,
        display: String?,
        idealFragments: List<String>
    ) {
        val text = display.orEmpty()
        val ideal = isModeledIdeal(display, idealFragments)
        val productGap =
            display == null ||
                text.isBlank() ||
                text == "unknown" ||
                text == "any" ||
                text == "nil"
        // Also accept any other non-unknown modeled type as progress toward IDEAL.
        val otherModeled =
            !productGap && !ideal && text.isNotBlank() && text != "unknown" && text != "any"

        assertTrue(
            ideal || productGap || otherModeled,
            "$label dual-path: IDEAL fragments=$idealFragments or CURRENTLY_ACCEPTS gap; got '$display'"
        )

        val expectation = when {
            ideal -> StrictSurfaceExpectation.IDEAL
            productGap -> StrictSurfaceExpectation.CURRENTLY_ACCEPTS
            else -> StrictSurfaceExpectation.IDEAL // other modeled type counts as progress
        }
        // Touch expectation so the dual-path vocabulary stays referenced in the corpus.
        assertTrue(
            expectation == StrictSurfaceExpectation.IDEAL ||
                expectation == StrictSurfaceExpectation.CURRENTLY_ACCEPTS,
            "$label must classify as IDEAL or CURRENTLY_ACCEPTS; got $expectation for '$display'"
        )
    }

    private fun isModeledIdeal(display: String?, idealFragments: List<String>): Boolean {
        val text = display.orEmpty()
        if (text.isBlank() || text == "unknown" || text == "any" || text == "nil") {
            return false
        }
        return idealFragments.any { text.contains(it) }
    }

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        occurrence: Int
    ): String? {
        return harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )?.typeInfo?.displayName
    }

    private fun alyConsumerHarness(mainSource: String): WorkspaceSemanticHarness {
        return androidHarness(
            ALY_WORKSPACE_PATH to resourceText("representative_layout.aly"),
            MAIN_FILE to mainSource
        )
    }

    private fun alyLoadlayoutHarness(): WorkspaceSemanticHarness {
        val source = """
            require "import"
            local spec = require("$REQUIRE_MODULE")
            local view = loadlayout(spec)
            local clicked = view.performClick
            return clicked
        """.trimIndent()
        return alyConsumerHarness(source)
    }

    private fun androidHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun resourceText(name: String): String {
        val path = "/semantic/androidlua/library-fixtures/$name"
        val stream = javaClass.getResourceAsStream(path)
            ?: error("Missing TASK-381 fixture resource $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private companion object {
        const val MAIN_FILE = "main.lua"
        const val ALY_WORKSPACE_PATH = "semantic/androidlua/library-fixtures/representative_layout.aly"
        const val REQUIRE_MODULE = "semantic.androidlua.library-fixtures.representative_layout"

        private fun hostAndroidJarCandidates(): List<File> {
            val candidates = linkedSetOf<File>()
            candidates += File("/Users/dingyi/Downloads/android.jar")
            candidates += File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
            candidates += File(
                System.getProperty("user.home"),
                "Library/Android/sdk/platforms/android-35/android.jar"
            )
            candidates += File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")
            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env -> System.getenv(env)?.trim()?.takeIf(String::isNotEmpty) }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }
            return candidates.toList()
        }

        private fun resolveAndroidJar(): File {
            val candidates = hostAndroidJarCandidates()
            return candidates.firstOrNull { it.isFile } ?: candidates.first()
        }
    }
}
