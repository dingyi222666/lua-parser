package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-368 — Java enum constant surface corpus.
 *
 * Acceptance (test-only; review-owned verification):
 * - Public Java enum constants on `luajava.bindClass` receivers resolve as static
 *   fields ([SymbolKind.FIELD] / [CompletionItemKind.FIELD]) with the enum type.
 * - Nested enum types (binary `$` names) expose their constants the same way.
 * - Enum static helpers (`values` / `valueOf`) remain METHOD kind.
 * - Missing constants stay unknown without crashing the pipeline.
 *
 * Product surface is the existing static-field reflection path (enum constants are
 * public static final fields of the enum class). No invented enum-only APIs.
 * Complements [JavaStaticFieldReadTddTest] / [JavaStaticFieldCompletionTddTest]
 * and nested-class provider coverage in interop.jvm.JvmClassProviderInnerClassTddTest.
 * No Gradle from workers.
 */
class JavaEnumConstantSurfaceTddTest {

    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    // ------------------------------------------------------------------
    // Top-level JDK enum constants: hover / type
    // ------------------------------------------------------------------

    @Test
    fun time_unit_seconds_enum_constant_hover_reports_time_unit_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local unit = TimeUnit.SECONDS
                return unit
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "SECONDS"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("java.util.concurrent.TimeUnit", hover.typeInfo?.displayName)
        assertNotUnknown(hover.typeInfo?.displayName)
    }
    @Test
    fun time_unit_enum_constants_complete_as_field_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local unit = TimeUnit.SECONDS
                return unit
            """.trimIndent()
        )

        val completions = completionsAt(harness, "SECONDS")
        assertCompletion(completions, "SECONDS", CompletionItemKind.FIELD)
        assertCompletion(completions, "MILLISECONDS", CompletionItemKind.FIELD)
        assertCompletion(completions, "NANOSECONDS", CompletionItemKind.FIELD)
        assertCompletion(completions, "DAYS", CompletionItemKind.FIELD)
        assertCompletion(completions, "HOURS", CompletionItemKind.FIELD)
        assertCompletion(completions, "MINUTES", CompletionItemKind.FIELD)
        assertCompletion(completions, "MICROSECONDS", CompletionItemKind.FIELD)
    }
    @Test
    fun time_unit_static_values_and_value_of_remain_method_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local unit = TimeUnit.SECONDS
                return unit
            """.trimIndent()
        )

        val completions = completionsAt(harness, "SECONDS")
        assertCompletion(completions, "SECONDS", CompletionItemKind.FIELD)
        assertCompletion(completions, "values", CompletionItemKind.METHOD)
        assertCompletion(completions, "valueOf", CompletionItemKind.METHOD)
    }
    @Test
    fun enum_constant_definition_points_to_enum_class_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local unit = TimeUnit.SECONDS
                return unit
            """.trimIndent()
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "SECONDS")
        )
        assertEquals(
            listOf(harness.path("__jvm__/classes/java/util/concurrent/TimeUnit.lua")),
            definitions.map { it.path }
        )
    }
    @Test
    fun missing_enum_constant_on_bound_enum_is_unknown_without_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local missing = TimeUnit.definitelyNotAnEnumConstant
                return missing
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "definitelyNotAnEnumConstant")
        )
        val display = hover?.typeInfo?.displayName
        assertTrue(
            display == null || display == "unknown",
            "Missing enum constant should be unknown/untyped, got '$display'."
        )

        val localHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "missing", occurrence = 2)
        )
        val localDisplay = localHover?.typeInfo?.displayName
        assertTrue(
            localDisplay == null || localDisplay == "unknown",
            "Local bound from missing enum constant should be unknown, got '$localDisplay'."
        )

        assertNotNull(harness.queries.diagnostics(harness.path("main.lua")))
    }
    @Test
    fun android_text_view_buffer_type_enum_constants_surface_when_android_jar_available() {
        if (!androidJar.isFile) return

        val harness = jvmHarness(
            "main.lua" to """
                local BufferType = luajava.bindClass("android.widget.TextView${'$'}BufferType")
                local normal = BufferType.NORMAL
                return normal
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "NORMAL"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        // Canonical display may use '.' for nested type names.
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display == "android.widget.TextView.BufferType" ||
                display == "android.widget.TextView\$BufferType",
            "Expected TextView.BufferType for BufferType.NORMAL, got '$display'."
        )
        assertNotUnknown(display)

        val completions = completionsAt(harness, "NORMAL")
        assertCompletion(completions, "NORMAL", CompletionItemKind.FIELD)
        assertCompletion(completions, "SPANNABLE", CompletionItemKind.FIELD)
        assertCompletion(completions, "EDITABLE", CompletionItemKind.FIELD)
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
            "Did not expect completion label '$label' on enum static surface; actual: ${completions.map { it.label }}."
        )
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java enum constant type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java enum constant type, got unknown.")
    }

    private fun List<Diagnostic>.containsInvalidMember(member: String): Boolean {
        return any { diagnostic ->
            diagnostic.message.contains(member) &&
                (diagnostic.message.contains("member", ignoreCase = true) ||
                    diagnostic.message.contains("method", ignoreCase = true) ||
                    diagnostic.message.contains("field", ignoreCase = true) ||
                    diagnostic.message.contains("unknown", ignoreCase = true) ||
                    diagnostic.message.contains("unresolved", ignoreCase = true) ||
                    diagnostic.message.contains("not found", ignoreCase = true))
        }
    }
}
