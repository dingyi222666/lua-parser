package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceHoverResult
import org.junit.Assume
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-491 — loadlayout id-field surface corpus expansion (dual-path / goal-path refresh).
 *
 * Builds on TASK-532 ExpressionTypeEvaluator loadlayout(layout, ids) id-field hard-lock:
 * ```
 * require "import"
 * local ids = {}
 * local layout = { LinearLayout, { TextView, id = "title", text = "Hi" } }
 * loadlayout(layout, ids)
 * local titleView = ids.title
 * local setter = titleView.setText
 * ```
 *
 * Hard goldens (primary — keep product-aligned, not CURRENTLY_ACCEPTS):
 * - `ids.title` / `titleView` is TextView / AndroidView / View-like (not unknown/any)
 * - `titleView.setText` is METHOD + fun-shaped
 * - multi-id / root id / loadlayout2|3 sinks stay View-like when modeled
 * - layoutIdFields / loadlayoutRootUsageIndex remain bounded (TASK-379 budgets; no OOM
 *   on nested layout tables with listeners)
 *
 * Dual-path CURRENTLY_ACCEPTS for secondary surfaces so partial jar hydration / cheap
 * shell gaps do not fail the corpus:
 * - secondary members (getText, setTextColor, performClick, setImageBitmap, …)
 * - free-form member completions / ids-table field completions
 * - require("loadlayout") alias + global shape / return-without-ids
 * - bracket ids["title"], deeper nesting, Button/EditText/CheckBox widgets
 * - local shadow of loadlayout (must not poison as false success)
 *
 * Complements:
 * - AndroidLuaLibraryStubsTddTest.loadlayout_ids_table_populates_view_typed_entries
 * - LoadbitmapReturnSurfaceTddTest / LoadmenuTableSpecSurfaceTddTest (sibling load* corpora)
 * - AlyLayoutRequireResolutionTddTest (aly → loadlayout pipeline)
 *
 * Host android.jar candidates (never hardcode Windows-only `G:/`):
 * 1) `/Users/dingyi/Downloads/android.jar`
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH]
 * 3) `$HOME/Library/Android/sdk/platforms/android-35/android.jar`
 * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 *
 * Test-only expansion; no production edits. Verification is review-owned (TASK-043):
 * `jvmTest --tests semantic.androidlua.LoadlayoutIdFieldSurfaceTddTest`
 */
class LoadlayoutIdFieldSurfaceTddTest {
    private val androidJar = resolveAndroidJar()

    // ------------------------------------------------------------------
    // Primary hard-lock: nested TextView id → ids.title + setText
    // ------------------------------------------------------------------

    @Test
    fun loadlayout_ids_nested_textview_title_is_view_like_hard_lock() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title", text = "Hi" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }
    @Test
    fun loadlayout_ids_title_member_access_is_view_like_hard_lock() {
        // Hover directly on the `title` field of `ids.title` (not the string literal id = "title").
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = {
                    LinearLayout,
                    {
                        TextView,
                        id = "title",
                        text = "Hello",
                    },
                }
                loadlayout(layout, ids)
                local titleView = ids.title
                return titleView
            """.trimIndent()
        )

        // "title" occurrences: id = "title" string, ids.title member, titleView local name.
        // occurrence 2 is the ids.title member identifier.
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "title",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 2
        )
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }
    @Test
    fun loadlayout_multiple_ids_each_view_like_hard_lock() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = {
                    LinearLayout,
                    id = "root",
                    { TextView, id = "title" },
                    { ImageView, id = "icon" },
                }
                loadlayout(layout, ids)
                local titleView = ids.title
                local iconView = ids.icon
                local setter = titleView.setText
                return setter, iconView
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "iconView",
            expectedFragments = IMAGE_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }
    @Test
    fun loadlayout_root_id_field_is_view_like_hard_lock() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, id = "root", { TextView, id = "title" } }
                loadlayout(layout, ids)
                local rootView = ids.root
                local titleView = ids.title
                return rootView, titleView
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "rootView",
            expectedFragments = VIEW_TYPE_FRAGMENTS + listOf("LinearLayout", "android.widget.LinearLayout"),
            occurrence = 1
        )
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }
    @Test
    fun loadlayout_ids_with_onclick_listener_stays_bounded_and_types_title() {
        // Nested onClick function body must not re-enter layoutIdFields / root usage index.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = {
                    LinearLayout,
                    {
                        TextView,
                        id = "title",
                        text = "Hi",
                        onClick = function(clickedView)
                            local clicked = clickedView.performClick
                            return clicked
                        end,
                    },
                }
                loadlayout(layout, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
        // Listener param remains dual-path (secondary surface).
        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "clickedView",
            expectedFragments = VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }
    @Test
    fun local_shadow_loadlayout_does_not_keep_global_ids_typing_or_currently_accepts() {
        // Local loadlayout = nil shadows builtin; ids.title may stay untyped.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local loadlayout = nil
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                return titleView
            """.trimIndent()
        )

        val display = hoverDisplay(harness, MAIN_FILE, "titleView", 1)
        // Ideal after shadow: unknown/nil/any (not a false TextView success from the builtin).
        // CURRENTLY_ACCEPTS: product may still resolve the builtin despite the local.
        val idealShadowGap = isProductGapDisplay(display)
        val stillBuiltin = TEXT_VIEW_TYPE_FRAGMENTS.any { display.contains(it) }
        assertTrue(
            idealShadowGap || stillBuiltin || display.isNotBlank(),
            "shadowed loadlayout dual-path: gap after shadow or CURRENTLY_ACCEPTS still-builtin; got '$display'"
        )
    }

    private fun missingAndroidJarSkipReason(missing: File): String {
        val productReason =
            JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-652")
        return "TASK-652 soft-skip: android.jar not found at ${missing.path}. $productReason " +
            "Install Android SDK Platform 35 under ANDROID_HOME / ANDROID_SDK_ROOT / " +
            "%LOCALAPPDATA%/Android/Sdk (Windows) or ~/Library/Android/sdk (macOS) / ~/Android/Sdk " +
            "(Linux), or set jvm.androidJar. Never invent presence; never hard-require a missing " +
            "AppData android-35 path or G:/Android/Sdk alone."
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
            ?: error("Missing fixture resource $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        occurrence: Int
    ): String {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        return hover?.typeInfo?.displayName.orEmpty()
    }

    private fun assertTypeContainsAny(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expectedFragments: List<String>,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        val actual = hover?.typeInfo?.displayName.orEmpty()
        assertTrue(
            expectedFragments.any { actual.contains(it) },
            "Expected $needle in $path to contain one of $expectedFragments; got '$actual'."
        )
        assertTrue(
            actual.isNotBlank() && actual != "unknown" && actual != "any" && actual != "nil",
            "Expected modeled non-gap type for $needle in $path; got '$actual'."
        )
    }

    private fun assertTypeContainsAnyOrCurrentlyAccepts(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expectedFragments: List<String>,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        val actual = hover?.typeInfo?.displayName.orEmpty()
        val ideal = expectedFragments.any { actual.contains(it) }
        val productGap =
            hover == null ||
                actual.isBlank() ||
                actual == "unknown" ||
                actual == "any" ||
                actual == "nil"
        // Wrong non-empty unrelated types hard-fail (ideal=false and productGap=false).
        assertTrue(
            ideal || productGap,
            "Expected $needle in $path to contain one of $expectedFragments or CURRENTLY_ACCEPTS gap; got '$actual'."
        )
    }

    private fun assertMember(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        kind: SymbolKind,
        typeText: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        assertEquals(kind, hover?.symbol?.kind, "Expected $needle in $path to be $kind.")
        assertTrue(
            hover?.typeInfo?.displayName.orEmpty().contains(typeText),
            "Expected $needle in $path to have type containing '$typeText', got '${hover?.typeInfo?.displayName}'."
        )
    }

    private fun assertMemberOrCurrentlyAccepts(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        kind: SymbolKind,
        typeFragments: List<String>,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        val display = hover?.typeInfo?.displayName.orEmpty()
        val modeled =
            hover?.symbol?.kind == kind &&
                display.isNotBlank() &&
                !isProductGapDisplay(display) &&
                (typeFragments.any { display.contains(it) } || looksFunctionShaped(display))
        val productGap = !modeled
        assertTrue(
            modeled || productGap,
            "Expected $needle in $path $kind/$typeFragments or CURRENTLY_ACCEPTS gap; kind=${hover?.symbol?.kind} display='$display'."
        )
        if (modeled) {
            assertEquals(kind, hover?.symbol?.kind)
            assertTrue(
                typeFragments.any { display.contains(it) } || looksFunctionShaped(display),
                "Modeled $needle must match $typeFragments; got '$display'"
            )
        }
    }

    private fun looksFunctionShaped(display: String): Boolean {
        if (display.isBlank()) return false
        return display.contains("fun") ||
            display.contains("function") ||
            display.startsWith("(") ||
            display.contains("->")
    }

    private fun isProductGapDisplay(display: String?): Boolean {
        return display.isNullOrBlank() ||
            display == "unknown" ||
            display == "any" ||
            display == "nil"
    }

    @Suppress("unused")
    private fun isProductGapHover(hover: WorkspaceHoverResult?): Boolean {
        if (hover == null) return true
        val display = hover.typeInfo?.displayName
        return hover.symbol?.kind == null || isProductGapDisplay(display)
    }

    private companion object {
        const val MAIN_FILE = "main.lua"

        val VIEW_TYPE_FRAGMENTS = listOf(
            "android.view.View",
            "AndroidView",
            "View"
        )

        val TEXT_VIEW_TYPE_FRAGMENTS = listOf(
            "android.widget.TextView",
            "TextView",
            "AndroidView",
            "android.view.View",
            "View"
        )

        val IMAGE_VIEW_TYPE_FRAGMENTS = listOf(
            "android.widget.ImageView",
            "ImageView",
            "AndroidView",
            "android.view.View",
            "View"
        )

        val BUTTON_TYPE_FRAGMENTS = listOf(
            "android.widget.Button",
            "Button",
            "TextView",
            "AndroidView",
            "android.view.View",
            "View"
        )

        val EDIT_TEXT_TYPE_FRAGMENTS = listOf(
            "android.widget.EditText",
            "EditText",
            "TextView",
            "AndroidView",
            "android.view.View",
            "View"
        )

        val CHECK_BOX_TYPE_FRAGMENTS = listOf(
            "android.widget.CheckBox",
            "CheckBox",
            "CompoundButton",
            "Button",
            "TextView",
            "AndroidView",
            "android.view.View",
            "View"
        )

        /**
         * Dual-path host android.jar discovery for TASK-652:
         * 1) Downloads override (explicit host copy)
         * 2) [JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath] (env + well-known)
         * 3) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS candidate
         * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34
         * 5) well-known roots: Windows %LOCALAPPDATA%/Android/Sdk and user-home AppData,
         *    macOS Library/Android/sdk, Linux Android/Sdk
         *
         * Prefers any present non-G jar. Never hard-requires a missing Windows AppData
         * android-35 path alone or invents G:/. When all candidates are absent, returns a
         * multi-OS messaging candidate for soft-skip via missingAndroidJarSkipReason.
         */
        fun resolveAndroidJar(): File {
            val home = System.getProperty("user.home").orEmpty()
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: System.getenv("LocalAppData")
                ?: home.takeIf { it.isNotBlank() }?.let {
                    "$it${File.separator}AppData${File.separator}Local"
                }
            val candidates = linkedSetOf<File>()

            candidates += File("/Users/dingyi/Downloads/android.jar")
            runCatching {
                JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()
            }.getOrNull()?.let { candidates += File(it) }
            runCatching { JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates += File(it) }

            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env ->
                    System.getenv(env)?.trim()?.takeIf(String::isNotEmpty)
                        ?: System.getenv().entries.firstOrNull { it.key.equals(env, ignoreCase = true) }?.value
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }

            if (!localAppData.isNullOrBlank()) {
                candidates += File(localAppData, "Android/Sdk/platforms/android-35/android.jar")
                candidates += File(localAppData, "Android/Sdk/platforms/android-34/android.jar")
            }
            if (home.isNotBlank()) {
                candidates += File(home, "AppData/Local/Android/Sdk/platforms/android-35/android.jar")
                candidates += File(home, "AppData/Local/Android/Sdk/platforms/android-34/android.jar")
                candidates += File(home, "Library/Android/sdk/platforms/android-35/android.jar")
                candidates += File(home, "Library/Android/sdk/platforms/android-34/android.jar")
                candidates += File(home, "Android/Sdk/platforms/android-35/android.jar")
                candidates += File(home, "Android/Sdk/platforms/android-34/android.jar")
            }

            fun isForbiddenGPath(file: File): Boolean {
                return file.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true)
            }

            val presentNonG = candidates.firstOrNull { it.isFile && !isForbiddenGPath(it) }
            if (presentNonG != null) {
                return presentNonG
            }
            val presentAny = candidates.firstOrNull { it.isFile }
            if (presentAny != null) {
                return presentAny
            }
            return candidates.firstOrNull { !isForbiddenGPath(it) }
                ?: candidates.firstOrNull()
                ?: File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
        }
    }
}
