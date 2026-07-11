package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import java.io.File
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-410 — Java static field **completion surface refine** corpus.
 *
 * Complements [JavaStaticFieldCompletionTddTest] (core FIELD kind + instance exclusion)
 * and [JavaStaticFieldReadTddTest] (hover/read) by locking a dual-path refine surface:
 *
 * - **JDK path (always on host JVM classpath):** static field completions on
 *   `luajava.bindClass` receivers keep [CompletionItemKind.FIELD], exclude instance-only
 *   members, and stay isolated across bound classes.
 * - **android.jar path (host provider):** when a host `android.jar` is present, Android
 *   static fields complete as FIELD; when missing, tests skip with an explicit
 *   TASK-410 reason (never invent Android labels).
 *
 * Host android.jar resolution (never hardcodes Windows-only `G:/`):
 * 1) `/Users/dingyi/Downloads/android.jar`
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
 * 3) `$HOME/Library/Android/sdk/platforms/android-35/android.jar`
 * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 *
 * Test-only; no production edits. Workers must not run Gradle — verification is
 * review-owned / TASK-043 serial jvmTest.
 */
class JavaStaticFieldCompletionSurfaceTddTest {

    private val androidJar = resolveAndroidJar()

    // ------------------------------------------------------------------
    // Dual-path honesty: skip reason + host candidates (no G:/ hardcode)
    // ------------------------------------------------------------------

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-410"), "Skip reason must name TASK-410; got: $reason")
        assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
        assertTrue(reason.contains(missing.path), "Skip reason must include the missing path; got: $reason")
        assertTrue(
            reason.contains("ANDROID_HOME") ||
                reason.contains("ANDROID_SDK_ROOT") ||
                reason.contains("Install") ||
                reason.contains("Downloads"),
            "Skip reason must tell the host how to recover; got: $reason"
        )
        assertFalse(
            reason.contains("G:/") || reason.contains("G:\\"),
            "Skip reason must never hardcode Windows-only G:/ paths; got: $reason"
        )
    }

    @Test
    fun host_android_jar_candidates_include_macos_sdk_and_downloads_never_g_drive() {
        val candidates = hostAndroidJarCandidates().map { it.path.replace('\\', '/') }

        assertTrue(
            candidates.any { it.endsWith("/Downloads/android.jar") },
            "Candidate list must include macOS/user Downloads android.jar; got: $candidates"
        )
        assertTrue(
            candidates.any { it.contains("/Library/Android/sdk/platforms/android-35/android.jar") } ||
                candidates.any { it.contains("platforms/android-35/android.jar") },
            "Candidate list must include macOS SDK platforms/android-35/android.jar; got: $candidates"
        )
        assertFalse(
            candidates.any { it.startsWith("G:/") || it.startsWith("G:\\") || it.contains("/G:/") },
            "Host candidates must never hardcode Windows-only G:/Android paths; got: $candidates"
        )
    }

    @Test
    fun android_jar_present_or_skipped_with_explicit_reason() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
        assertTrue(androidJar.isFile)
        assertTrue(
            androidJar.length() > 0,
            "Expected non-empty Android platform jar at ${androidJar.path}."
        )
    }

    // ------------------------------------------------------------------
    // JDK refine surface: static FIELD completions + instance exclusion
    // ------------------------------------------------------------------

    @Test
    fun jdk_integer_static_field_completion_surface_is_field_kind() {
        val harness = jdkHarness(
            "main.lua" to """
                local Integer = luajava.bindClass("java.lang.Integer")
                local max = Integer.MAX_VALUE
                return max
            """.trimIndent()
        )

        val completions = completionsAt(harness, "MAX_VALUE")
        assertCompletion(completions, "MAX_VALUE", CompletionItemKind.FIELD)
        assertCompletion(completions, "MIN_VALUE", CompletionItemKind.FIELD)
        assertCompletion(completions, "SIZE", CompletionItemKind.FIELD)
        // Static methods may co-exist; instance-only wrappers must not.
        assertNoCompletion(completions, "intValue")
        assertNoCompletion(completions, "longValue")
        assertNoCompletion(completions, "byteValue")
    }

    @Test
    fun jdk_system_static_stream_fields_complete_as_field_without_local_shadow() {
        // Local name must not shadow System.out so positionOf("out") hits the member.
        val harness = jdkHarness(
            "main.lua" to """
                local System = luajava.bindClass("java.lang.System")
                local stream = System.out
                return stream
            """.trimIndent()
        )

        val completions = completionsAt(harness, "out")
        assertCompletion(completions, "out", CompletionItemKind.FIELD)
        assertCompletion(completions, "err", CompletionItemKind.FIELD)
        assertCompletion(completions, "in", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "getName")
    }

    @Test
    fun jdk_file_unique_static_field_needle_avoids_mid_token_sibling_traps() {
        // Refine of TASK-347 positionOf traps: IoFile + pathSeparatorChar.
        val harness = jdkHarness(
            "main.lua" to """
                local IoFile = luajava.bindClass("java.io.File")
                local sep = IoFile.pathSeparatorChar
                return sep
            """.trimIndent()
        )

        val completions = completionsAt(harness, "pathSeparatorChar")
        assertCompletion(completions, "pathSeparatorChar", CompletionItemKind.FIELD)
        assertCompletion(completions, "pathSeparator", CompletionItemKind.FIELD)
        assertCompletion(completions, "separator", CompletionItemKind.FIELD)
        assertCompletion(completions, "separatorChar", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "getName")
        assertNoCompletion(completions, "exists")
        assertNoCompletion(completions, "listFiles")
    }

    @Test
    fun jdk_static_field_completion_kind_matches_hover_field_symbol() {
        val harness = jdkHarness(
            "main.lua" to """
                local Integer = luajava.bindClass("java.lang.Integer")
                local max = Integer.MAX_VALUE
                return max
            """.trimIndent()
        )

        val pos = harness.positionOf("main.lua", "MAX_VALUE")
        val completions = harness.queries.completions(harness.path("main.lua"), pos)
        assertCompletion(completions, "MAX_VALUE", CompletionItemKind.FIELD)

        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), pos))
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
    }

    @Test
    fun jdk_bound_classes_static_field_completions_isolated() {
        val harness = jdkHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local Integer = luajava.bindClass("java.lang.Integer")
                local root = Locale.ROOT
                local max = Integer.MAX_VALUE
                return root, max
            """.trimIndent()
        )

        val localeCompletions = completionsAt(harness, "ROOT")
        assertCompletion(localeCompletions, "ROOT", CompletionItemKind.FIELD)
        assertNoCompletion(localeCompletions, "MAX_VALUE")

        val integerCompletions = completionsAt(harness, "MAX_VALUE")
        assertCompletion(integerCompletions, "MAX_VALUE", CompletionItemKind.FIELD)
        assertNoCompletion(integerCompletions, "ROOT")
        assertNoCompletion(integerCompletions, "ENGLISH")
    }

    @Test
    fun jdk_missing_static_field_label_is_not_invented() {
        val unknown = "definitelyNotAStaticField_refine_xyz"
        val harness = jdkHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local missing = Locale.$unknown
                return missing
            """.trimIndent()
        )

        val completions = completionsAt(harness, unknown)
        assertFalse(
            completions.any { it.label == unknown },
            "Missing static field must not be invented as a completion label; actual=${completions.map { it.label }}"
        )
        assertCompletion(completions, "ROOT", CompletionItemKind.FIELD)
    }

    @Test
    fun jdk_bind_class_alias_static_field_completion_surface_remains_field() {
        val harness = jdkHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local IoFile = bindClass("java.io.File")
                local sep = IoFile.pathSeparatorChar
                return sep
            """.trimIndent()
        )

        val completions = completionsAt(harness, "pathSeparatorChar")
        assertCompletion(completions, "pathSeparatorChar", CompletionItemKind.FIELD)
        assertCompletion(completions, "separator", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "getName")
    }

    @Test
    fun jdk_static_methods_remain_method_kind_alongside_static_fields() {
        val harness = jdkHarness(
            "main.lua" to """
                local Integer = luajava.bindClass("java.lang.Integer")
                local max = Integer.MAX_VALUE
                return max
            """.trimIndent()
        )

        val completions = completionsAt(harness, "MAX_VALUE")
        assertCompletion(completions, "MAX_VALUE", CompletionItemKind.FIELD)
        assertCompletion(completions, "parseInt", CompletionItemKind.METHOD)
        assertCompletion(completions, "valueOf", CompletionItemKind.METHOD)
    }

    @Test
    fun jdk_thread_priority_static_fields_complete_as_field_kind() {
        val harness = jdkHarness(
            "main.lua" to """
                local Thread = luajava.bindClass("java.lang.Thread")
                local max = Thread.MAX_PRIORITY
                return max
            """.trimIndent()
        )

        val completions = completionsAt(harness, "MAX_PRIORITY")
        assertCompletion(completions, "MAX_PRIORITY", CompletionItemKind.FIELD)
        assertCompletion(completions, "MIN_PRIORITY", CompletionItemKind.FIELD)
        assertCompletion(completions, "NORM_PRIORITY", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "getName")
        assertNoCompletion(completions, "start")
    }

    @Test
    fun jdk_double_numeric_static_fields_complete_as_field_kind() {
        val harness = jdkHarness(
            "main.lua" to """
                local Double = luajava.bindClass("java.lang.Double")
                local max = Double.MAX_VALUE
                return max
            """.trimIndent()
        )

        val completions = completionsAt(harness, "MAX_VALUE")
        assertCompletion(completions, "MAX_VALUE", CompletionItemKind.FIELD)
        assertCompletion(completions, "MIN_VALUE", CompletionItemKind.FIELD)
        assertCompletion(completions, "NaN", CompletionItemKind.FIELD)
        assertCompletion(completions, "POSITIVE_INFINITY", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "doubleValue")
    }

    @Test
    fun jdk_instance_receiver_still_offers_instance_fields_after_new_instance() {
        // Contrast path: constructor/instance surface keeps instance fields.
        val harness = jdkHarness(
            "main.lua" to """
                local point = luajava.newInstance("java.awt.Point")
                local x = point.x
                return x
            """.trimIndent()
        )

        val completions = completionsAt(harness, "x", occurrence = 2)
        assertCompletion(completions, "x", CompletionItemKind.FIELD)
        assertCompletion(completions, "y", CompletionItemKind.FIELD)
    }

    // ------------------------------------------------------------------
    // Android refine surface (skip if jar missing; dual-path honest)
    // ------------------------------------------------------------------

    @Test
    fun android_view_visibility_static_fields_complete_as_field_when_android_jar_available() {
        requireAndroidJarOrSkip()
        val harness = androidHarness(
            "main.lua" to """
                local View = luajava.bindClass("android.view.View")
                local visible = View.VISIBLE
                return visible
            """.trimIndent()
        )

        val completions = completionsAt(harness, "VISIBLE")
        assertCompletion(completions, "VISIBLE", CompletionItemKind.FIELD)
        assertCompletion(completions, "GONE", CompletionItemKind.FIELD)
        assertCompletion(completions, "INVISIBLE", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "setVisibility")
        assertNoCompletion(completions, "getVisibility")
        assertNoCompletion(completions, "findViewById")
    }

    @Test
    fun android_activity_result_static_fields_complete_as_field_when_android_jar_available() {
        requireAndroidJarOrSkip()
        val harness = androidHarness(
            "main.lua" to """
                local Activity = luajava.bindClass("android.app.Activity")
                local ok = Activity.RESULT_OK
                return ok
            """.trimIndent()
        )

        val completions = completionsAt(harness, "RESULT_OK")
        assertCompletion(completions, "RESULT_OK", CompletionItemKind.FIELD)
        assertCompletion(completions, "RESULT_CANCELED", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "setContentView")
        assertNoCompletion(completions, "findViewById")
        assertNoCompletion(completions, "getIntent")
    }

    @Test
    fun android_gravity_static_fields_complete_as_field_when_android_jar_available() {
        requireAndroidJarOrSkip()
        val harness = androidHarness(
            "main.lua" to """
                local Gravity = luajava.bindClass("android.view.Gravity")
                local center = Gravity.CENTER
                return center
            """.trimIndent()
        )

        val completions = completionsAt(harness, "CENTER")
        assertCompletion(completions, "CENTER", CompletionItemKind.FIELD)
        // Sibling gravity constants should also surface on the static receiver.
        assertTrue(
            completions.any { it.label == "LEFT" && it.kind == CompletionItemKind.FIELD } ||
                completions.any { it.label == "START" && it.kind == CompletionItemKind.FIELD } ||
                completions.any { it.label == "CENTER_HORIZONTAL" && it.kind == CompletionItemKind.FIELD },
            "Expected Gravity sibling static FIELD completions (LEFT/START/CENTER_HORIZONTAL); " +
                "actual=${completions.map { "${it.label}:${it.kind}" }}"
        )
    }

    @Test
    fun android_motion_event_action_static_fields_complete_as_field_when_android_jar_available() {
        requireAndroidJarOrSkip()
        val harness = androidHarness(
            "main.lua" to """
                local MotionEvent = luajava.bindClass("android.view.MotionEvent")
                local action = MotionEvent.ACTION_DOWN
                return action
            """.trimIndent()
        )

        val completions = completionsAt(harness, "ACTION_DOWN")
        assertCompletion(completions, "ACTION_DOWN", CompletionItemKind.FIELD)
        assertCompletion(completions, "ACTION_UP", CompletionItemKind.FIELD)
        assertCompletion(completions, "ACTION_MOVE", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "getAction")
        assertNoCompletion(completions, "getX")
        assertNoCompletion(completions, "getY")
    }

    @Test
    fun android_static_field_completion_kind_matches_hover_when_android_jar_available() {
        requireAndroidJarOrSkip()
        val harness = androidHarness(
            "main.lua" to """
                local View = luajava.bindClass("android.view.View")
                local visible = View.VISIBLE
                return visible
            """.trimIndent()
        )

        val pos = harness.positionOf("main.lua", "VISIBLE")
        val completions = harness.queries.completions(harness.path("main.lua"), pos)
        assertCompletion(completions, "VISIBLE", CompletionItemKind.FIELD)

        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), pos))
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
    }

    @Test
    fun android_and_jdk_static_field_completions_isolated_when_android_jar_available() {
        requireAndroidJarOrSkip()
        val harness = androidHarness(
            "main.lua" to """
                local View = luajava.bindClass("android.view.View")
                local Integer = luajava.bindClass("java.lang.Integer")
                local visible = View.VISIBLE
                local max = Integer.MAX_VALUE
                return visible, max
            """.trimIndent()
        )

        val viewCompletions = completionsAt(harness, "VISIBLE")
        assertCompletion(viewCompletions, "VISIBLE", CompletionItemKind.FIELD)
        assertNoCompletion(viewCompletions, "MAX_VALUE")

        val integerCompletions = completionsAt(harness, "MAX_VALUE")
        assertCompletion(integerCompletions, "MAX_VALUE", CompletionItemKind.FIELD)
        assertNoCompletion(integerCompletions, "VISIBLE")
        assertNoCompletion(integerCompletions, "GONE")
    }

    @Test
    fun android_missing_static_field_label_is_not_invented_when_android_jar_available() {
        requireAndroidJarOrSkip()
        val unknown = "definitelyNotAnAndroidStaticField_refine_xyz"
        val harness = androidHarness(
            "main.lua" to """
                local View = luajava.bindClass("android.view.View")
                local missing = View.$unknown
                return missing
            """.trimIndent()
        )

        val completions = completionsAt(harness, unknown)
        assertFalse(
            completions.any { it.label == unknown },
            "Missing Android static field must not be invented; actual=${completions.map { it.label }}"
        )
        // Known static fields still surface on the same receiver (dual-path present-jar arm).
        assertCompletion(completions, "VISIBLE", CompletionItemKind.FIELD)
    }

    /**
     * Dual-path honesty for hosts without android.jar: the refine corpus documents the
     * skip reason rather than inventing Android completion labels on a bare JDK engine.
     * When the jar **is** present this test is a no-op success (present arm covered above).
     */
    @Test
    fun android_static_field_completion_dual_path_skip_or_present_is_honest() {
        if (!androidJar.isFile) {
            val reason = missingAndroidJarSkipReason(androidJar)
            assertTrue(reason.contains("TASK-410"), "Missing-jar dual-path must name TASK-410; got: $reason")
            assertTrue(reason.contains("android.jar"), "Missing-jar dual-path must mention android.jar; got: $reason")
            // Honest degrade: do not claim Android static field completions without a jar.
            return
        }

        // Present arm: at least one Android static FIELD label surfaces under metadata.
        val harness = androidHarness(
            "main.lua" to """
                local View = luajava.bindClass("android.view.View")
                local visible = View.VISIBLE
                return visible
            """.trimIndent()
        )
        val completions = completionsAt(harness, "VISIBLE")
        assertCompletion(completions, "VISIBLE", CompletionItemKind.FIELD)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun jdkHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun androidHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private fun completionsAt(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ): List<CompletionItem> {
        return harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
    }

    private fun assertCompletion(completions: List<CompletionItem>, label: String, kind: CompletionItemKind) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    private fun assertNoCompletion(completions: List<CompletionItem>, label: String) {
        assertFalse(
            completions.any { it.label == label },
            "Did not expect completion label '$label' on static receiver; actual: ${completions.map { it.label }}."
        )
    }

    private companion object {
        /**
         * Host-resolution order for TASK-410 refine corpus:
         * 1) user-provided Downloads android.jar
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
         * 3) macOS well-known SDK path under $HOME
         * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
         *
         * Never hardcodes Windows-only G:/Android/Sdk paths.
         */
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

        internal fun missingAndroidJarSkipReason(androidJar: File): String {
            return "TASK-410 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35, place android.jar under Downloads, " +
                "or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar " +
                "before running Java static field completion surface refine corpus."
        }
    }
}
