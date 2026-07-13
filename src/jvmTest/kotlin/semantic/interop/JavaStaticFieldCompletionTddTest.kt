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
