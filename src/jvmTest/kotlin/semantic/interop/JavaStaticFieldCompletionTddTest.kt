package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-347 — Java static field completion corpus.
 *
 * Acceptance (test-only; review-owned verification):
 * - Static Java fields on `luajava.bindClass` receivers complete with
 *   [CompletionItemKind.FIELD].
 * - Instance-only members are excluded from static receivers (bound class module
 *   surface / JavaClassType static member surface).
 *
 * Complements [JavaStaticFieldReadTddTest] (hover/read) with a completion-focused
 * corpus. Cursor must land on the member identifier so member completions surface
 * (locals must not shadow the static-field token — see System.out / File fields).
 * Prefer a unique static-field needle (e.g. pathSeparatorChar) so positionOf cannot
 * land mid-token inside a longer sibling field name.
 * No Gradle from workers. Host android.jar via [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH].
 */
class JavaStaticFieldCompletionTddTest {

    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    // ------------------------------------------------------------------
    // Static fields complete as FIELD
    // ------------------------------------------------------------------

    @Test
    fun integer_static_fields_complete_as_field_kind() {
        val harness = jvmHarness(
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
    }

    @Test
    fun system_static_fields_out_and_err_complete_as_field_kind() {
        // Avoid local name shadowing so positionOf("out") lands on System.out.
        val harness = jvmHarness(
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
    }

    @Test
    fun math_static_fields_pi_and_e_complete_as_field_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local Math = luajava.bindClass("java.lang.Math")
                local pi = Math.PI
                return pi
            """.trimIndent()
        )

        val completions = completionsAt(harness, "PI")
        assertCompletion(completions, "PI", CompletionItemKind.FIELD)
        assertCompletion(completions, "E", CompletionItemKind.FIELD)
    }

    @Test
    fun file_static_separator_fields_complete_as_field_kind() {
        // Use IoFile receiver + unique pathSeparatorChar needle so positionOf cannot
        // hit a local, a bare File module alias, or a mid-token match inside a sibling
        // field (pathSeparator / separatorChar both contain the "separator" substring).
        val harness = jvmHarness(
            "main.lua" to """
                local IoFile = luajava.bindClass("java.io.File")
                local sep = IoFile.pathSeparatorChar
                return sep
            """.trimIndent()
        )

        val completions = completionsAt(harness, "pathSeparatorChar")
        assertCompletion(completions, "separator", CompletionItemKind.FIELD)
        assertCompletion(completions, "separatorChar", CompletionItemKind.FIELD)
        assertCompletion(completions, "pathSeparator", CompletionItemKind.FIELD)
        assertCompletion(completions, "pathSeparatorChar", CompletionItemKind.FIELD)
    }

    @Test
    fun locale_root_static_field_completes_as_field_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local root = Locale.ROOT
                return root
            """.trimIndent()
        )

        val completions = completionsAt(harness, "ROOT")
        assertCompletion(completions, "ROOT", CompletionItemKind.FIELD)
        assertCompletion(completions, "ENGLISH", CompletionItemKind.FIELD)
        assertCompletion(completions, "US", CompletionItemKind.FIELD)
    }

    @Test
    fun boolean_true_false_static_fields_complete_as_field_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local Boolean = luajava.bindClass("java.lang.Boolean")
                local yes = Boolean.TRUE
                return yes
            """.trimIndent()
        )

        val completions = completionsAt(harness, "TRUE")
        assertCompletion(completions, "TRUE", CompletionItemKind.FIELD)
        assertCompletion(completions, "FALSE", CompletionItemKind.FIELD)
        assertCompletion(completions, "TYPE", CompletionItemKind.FIELD)
    }

    @Test
    fun double_and_long_numeric_static_fields_complete_as_field_kind() {
        val doubleHarness = jvmHarness(
            "main.lua" to """
                local Double = luajava.bindClass("java.lang.Double")
                local max = Double.MAX_VALUE
                return max
            """.trimIndent()
        )
        val doubleCompletions = completionsAt(doubleHarness, "MAX_VALUE")
        assertCompletion(doubleCompletions, "MAX_VALUE", CompletionItemKind.FIELD)
        assertCompletion(doubleCompletions, "MIN_VALUE", CompletionItemKind.FIELD)
        assertCompletion(doubleCompletions, "NaN", CompletionItemKind.FIELD)
        assertCompletion(doubleCompletions, "POSITIVE_INFINITY", CompletionItemKind.FIELD)

        val longHarness = jvmHarness(
            "main.lua" to """
                local Long = luajava.bindClass("java.lang.Long")
                local min = Long.MIN_VALUE
                return min
            """.trimIndent()
        )
        val longCompletions = completionsAt(longHarness, "MIN_VALUE")
        assertCompletion(longCompletions, "MIN_VALUE", CompletionItemKind.FIELD)
        assertCompletion(longCompletions, "MAX_VALUE", CompletionItemKind.FIELD)
        assertCompletion(longCompletions, "SIZE", CompletionItemKind.FIELD)
    }

    @Test
    fun thread_priority_static_fields_complete_as_field_kind() {
        val harness = jvmHarness(
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
    }

    @Test
    fun static_field_completion_kind_matches_hover_field_symbol() {
        val harness = jvmHarness(
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

    // ------------------------------------------------------------------
    // Static methods remain available; fields keep FIELD kind
    // ------------------------------------------------------------------

    @Test
    fun static_receiver_still_surfaces_static_methods_alongside_fields() {
        val harness = jvmHarness(
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
    fun system_static_methods_remain_method_kind_next_to_static_fields() {
        val harness = jvmHarness(
            "main.lua" to """
                local System = luajava.bindClass("java.lang.System")
                local stream = System.out
                return stream
            """.trimIndent()
        )

        val completions = completionsAt(harness, "out")
        assertCompletion(completions, "out", CompletionItemKind.FIELD)
        assertCompletion(completions, "currentTimeMillis", CompletionItemKind.METHOD)
        assertCompletion(completions, "getProperty", CompletionItemKind.METHOD)
    }

    // ------------------------------------------------------------------
    // Instance-only members excluded from static receiver
    // ------------------------------------------------------------------

    @Test
    fun file_static_receiver_excludes_instance_only_methods() {
        // Cursor on unique static field pathSeparatorChar (member surface).
        val harness = jvmHarness(
            "main.lua" to """
                local IoFile = luajava.bindClass("java.io.File")
                local sep = IoFile.pathSeparatorChar
                return sep
            """.trimIndent()
        )

        val completions = completionsAt(harness, "pathSeparatorChar")
        assertCompletion(completions, "pathSeparatorChar", CompletionItemKind.FIELD)
        assertCompletion(completions, "separator", CompletionItemKind.FIELD)

        // Instance-only File members must not leak onto the static bindClass receiver.
        assertNoCompletion(completions, "getName")
        assertNoCompletion(completions, "exists")
        assertNoCompletion(completions, "isDirectory")
        assertNoCompletion(completions, "getParentFile")
        assertNoCompletion(completions, "listFiles")
        assertNoCompletion(completions, "createNewFile")
    }

    @Test
    fun string_builder_static_receiver_excludes_instance_only_append() {
        val harness = jvmHarness(
            "main.lua" to """
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local candidate = StringBuilder.append
                return candidate
            """.trimIndent()
        )

        val completions = completionsAt(harness, "append")
        assertNoCompletion(completions, "append")
        assertNoCompletion(completions, "toString")
        assertNoCompletion(completions, "reverse")
        assertNoCompletion(completions, "length")
        assertNoCompletion(completions, "charAt")
    }

    @Test
    fun integer_static_receiver_excludes_instance_only_int_value() {
        val harness = jvmHarness(
            "main.lua" to """
                local Integer = luajava.bindClass("java.lang.Integer")
                local max = Integer.MAX_VALUE
                return max
            """.trimIndent()
        )

        val completions = completionsAt(harness, "MAX_VALUE")
        assertCompletion(completions, "MAX_VALUE", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "intValue")
        assertNoCompletion(completions, "longValue")
        assertNoCompletion(completions, "byteValue")
        assertNoCompletion(completions, "shortValue")
        assertNoCompletion(completions, "doubleValue")
    }

    @Test
    fun point_static_receiver_excludes_instance_fields_x_and_y() {
        // Point.x / Point.y are instance fields; bindClass static surface must not offer them.
        val harness = jvmHarness(
            "main.lua" to """
                local Point = luajava.bindClass("java.awt.Point")
                local candidate = Point.x
                return candidate
            """.trimIndent()
        )

        val completions = completionsAt(harness, "x")
        assertNoCompletion(completions, "x")
        assertNoCompletion(completions, "y")
        assertNoCompletion(completions, "getX")
        assertNoCompletion(completions, "getY")
        assertNoCompletion(completions, "translate")
        assertNoCompletion(completions, "move")
    }

    @Test
    fun thread_static_receiver_excludes_instance_get_name() {
        val harness = jvmHarness(
            "main.lua" to """
                local Thread = luajava.bindClass("java.lang.Thread")
                local max = Thread.MAX_PRIORITY
                return max
            """.trimIndent()
        )

        val completions = completionsAt(harness, "MAX_PRIORITY")
        assertCompletion(completions, "MAX_PRIORITY", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "getName")
        assertNoCompletion(completions, "setName")
        assertNoCompletion(completions, "start")
        assertNoCompletion(completions, "join")
        assertNoCompletion(completions, "interrupt")
    }

    @Test
    fun instance_receiver_still_offers_instance_members_after_constructor() {
        // Contrast: constructor/instance surface must keep instance members.
        val harness = jvmHarness(
            "main.lua" to """
                local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                local builder = StringBuilder()
                local append = builder.append
                return append
            """.trimIndent()
        )

        val completions = completionsAt(harness, "append", occurrence = 2)
        assertCompletion(completions, "append", CompletionItemKind.METHOD)
        assertCompletion(completions, "toString", CompletionItemKind.METHOD)
    }

    @Test
    fun instance_receiver_from_new_instance_offers_instance_fields() {
        val harness = jvmHarness(
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
    // Alias / multi-class / degrade paths
    // ------------------------------------------------------------------

    @Test
    fun bind_class_alias_static_field_completions_remain_field_kind() {
        // Alias bindClass + IoFile receiver + unique pathSeparatorChar member needle.
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local IoFile = bindClass("java.io.File")
                local sep = IoFile.pathSeparatorChar
                return sep
            """.trimIndent()
        )

        val completions = completionsAt(harness, "pathSeparatorChar")
        assertCompletion(completions, "separator", CompletionItemKind.FIELD)
        assertCompletion(completions, "pathSeparator", CompletionItemKind.FIELD)
        assertCompletion(completions, "pathSeparatorChar", CompletionItemKind.FIELD)
        assertNoCompletion(completions, "getName")
    }

    @Test
    fun two_bound_classes_static_field_completions_isolated() {
        val harness = jvmHarness(
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
        assertNoCompletion(localeCompletions, "MIN_VALUE")

        val integerCompletions = completionsAt(harness, "MAX_VALUE")
        assertCompletion(integerCompletions, "MAX_VALUE", CompletionItemKind.FIELD)
        assertNoCompletion(integerCompletions, "ROOT")
        assertNoCompletion(integerCompletions, "ENGLISH")
    }

    @Test
    fun missing_static_field_label_is_not_invented_in_completions() {
        val unknown = "definitelyNotAStaticField_xyz"
        val harness = jvmHarness(
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
        // Known static fields still surface on the same receiver.
        assertCompletion(completions, "ROOT", CompletionItemKind.FIELD)
    }

    @Test
    fun empty_member_prefix_on_static_receiver_still_lists_static_fields() {
        // Completing at an existing static field token should still include the broader field set.
        val harness = jvmHarness(
            "main.lua" to """
                local System = luajava.bindClass("java.lang.System")
                local stream = System.out
                return stream
            """.trimIndent()
        )

        val labels = completionsAt(harness, "out").map { it.label }.toSet()
        assertTrue("out" in labels && "err" in labels, "Expected System.out/err among $labels")
        assertTrue(labels.none { it == "getName" }, "Instance-only getName must not appear on System static surface")
    }

    // ------------------------------------------------------------------
    // Android static fields (when host android.jar is available)
    // ------------------------------------------------------------------

    @Test
    fun android_view_visible_static_field_completes_as_field_when_android_jar_available() {
        if (!androidJar.isFile) return

        val harness = jvmHarness(
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
        // Instance-only View surface must stay off the static receiver.
        assertNoCompletion(completions, "setVisibility")
        assertNoCompletion(completions, "getVisibility")
        assertNoCompletion(completions, "findViewById")
    }

    @Test
    fun android_activity_result_static_fields_complete_as_field_when_android_jar_available() {
        if (!androidJar.isFile) return

        val harness = jvmHarness(
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
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
}
