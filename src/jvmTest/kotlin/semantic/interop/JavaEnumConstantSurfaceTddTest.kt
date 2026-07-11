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
    fun time_unit_days_enum_constant_hover_reports_time_unit_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local unit = TimeUnit.DAYS
                return unit
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "DAYS"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("java.util.concurrent.TimeUnit", hover.typeInfo?.displayName)
    }

    @Test
    fun element_type_method_enum_constant_hover_reports_element_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local ElementType = luajava.bindClass("java.lang.annotation.ElementType")
                local kind = ElementType.METHOD
                return kind
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "METHOD"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("java.lang.annotation.ElementType", hover.typeInfo?.displayName)
        assertNotUnknown(hover.typeInfo?.displayName)
    }

    @Test
    fun retention_policy_runtime_enum_constant_hover_reports_retention_policy() {
        val harness = jvmHarness(
            "main.lua" to """
                local RetentionPolicy = luajava.bindClass("java.lang.annotation.RetentionPolicy")
                local policy = RetentionPolicy.RUNTIME
                return policy
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "RUNTIME"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("java.lang.annotation.RetentionPolicy", hover.typeInfo?.displayName)
    }

    @Test
    fun standard_copy_option_replace_existing_hover_reports_enum_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local StandardCopyOption = luajava.bindClass("java.nio.file.StandardCopyOption")
                local option = StandardCopyOption.REPLACE_EXISTING
                return option
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "REPLACE_EXISTING"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("java.nio.file.StandardCopyOption", hover.typeInfo?.displayName)
    }

    @Test
    fun enum_constant_assigned_local_retains_enum_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local unit = TimeUnit.MILLISECONDS
                return unit
            """.trimIndent()
        )

        // Occurrence 2 is the return-site use of `unit`.
        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "unit", occurrence = 2))
        )
        assertEquals("java.util.concurrent.TimeUnit", hover.typeInfo?.displayName)
        assertNotUnknown(hover.typeInfo?.displayName)
    }

    // ------------------------------------------------------------------
    // Nested enum (binary $ name) constants
    // ------------------------------------------------------------------

    @Test
    fun thread_state_runnable_enum_constant_hover_reports_thread_state() {
        // Nested enums bind via binary name, matching Map$Entry corpus style.
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local state = State.RUNNABLE
                return state
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "RUNNABLE"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("java.lang.Thread.State", hover.typeInfo?.displayName)
        assertNotUnknown(hover.typeInfo?.displayName)
    }

    @Test
    fun thread_state_terminated_enum_constant_hover_reports_thread_state() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local state = State.TERMINATED
                return state
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "TERMINATED"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("java.lang.Thread.State", hover.typeInfo?.displayName)
    }

    @Test
    fun thread_state_new_and_blocked_constants_resolve_in_same_document() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local fresh = State.NEW
                local blocked = State.BLOCKED
                return fresh, blocked
            """.trimIndent()
        )

        val newHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "NEW"))
        )
        assertEquals(SymbolKind.FIELD, newHover.symbol?.kind)
        assertEquals("java.lang.Thread.State", newHover.typeInfo?.displayName)

        val blockedHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "BLOCKED"))
        )
        assertEquals(SymbolKind.FIELD, blockedHover.symbol?.kind)
        assertEquals("java.lang.Thread.State", blockedHover.typeInfo?.displayName)
    }

    // ------------------------------------------------------------------
    // Completions: enum constants as FIELD
    // ------------------------------------------------------------------

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
    fun thread_state_enum_constants_complete_as_field_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local state = State.RUNNABLE
                return state
            """.trimIndent()
        )

        val completions = completionsAt(harness, "RUNNABLE")
        assertCompletion(completions, "RUNNABLE", CompletionItemKind.FIELD)
        assertCompletion(completions, "NEW", CompletionItemKind.FIELD)
        assertCompletion(completions, "BLOCKED", CompletionItemKind.FIELD)
        assertCompletion(completions, "WAITING", CompletionItemKind.FIELD)
        assertCompletion(completions, "TIMED_WAITING", CompletionItemKind.FIELD)
        assertCompletion(completions, "TERMINATED", CompletionItemKind.FIELD)
    }

    @Test
    fun element_type_enum_constants_complete_as_field_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local ElementType = luajava.bindClass("java.lang.annotation.ElementType")
                local kind = ElementType.TYPE
                return kind
            """.trimIndent()
        )

        val completions = completionsAt(harness, "TYPE")
        assertCompletion(completions, "TYPE", CompletionItemKind.FIELD)
        assertCompletion(completions, "FIELD", CompletionItemKind.FIELD)
        assertCompletion(completions, "METHOD", CompletionItemKind.FIELD)
        assertCompletion(completions, "PARAMETER", CompletionItemKind.FIELD)
        assertCompletion(completions, "CONSTRUCTOR", CompletionItemKind.FIELD)
        assertCompletion(completions, "ANNOTATION_TYPE", CompletionItemKind.FIELD)
    }

    @Test
    fun retention_policy_enum_constants_complete_as_field_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local RetentionPolicy = luajava.bindClass("java.lang.annotation.RetentionPolicy")
                local policy = RetentionPolicy.SOURCE
                return policy
            """.trimIndent()
        )

        val completions = completionsAt(harness, "SOURCE")
        assertCompletion(completions, "SOURCE", CompletionItemKind.FIELD)
        assertCompletion(completions, "CLASS", CompletionItemKind.FIELD)
        assertCompletion(completions, "RUNTIME", CompletionItemKind.FIELD)
    }

    @Test
    fun standard_copy_option_enum_constants_complete_as_field_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local StandardCopyOption = luajava.bindClass("java.nio.file.StandardCopyOption")
                local option = StandardCopyOption.ATOMIC_MOVE
                return option
            """.trimIndent()
        )

        val completions = completionsAt(harness, "ATOMIC_MOVE")
        assertCompletion(completions, "ATOMIC_MOVE", CompletionItemKind.FIELD)
        assertCompletion(completions, "REPLACE_EXISTING", CompletionItemKind.FIELD)
        assertCompletion(completions, "COPY_ATTRIBUTES", CompletionItemKind.FIELD)
    }

    @Test
    fun enum_constant_completion_kind_matches_hover_field_symbol() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local unit = TimeUnit.SECONDS
                return unit
            """.trimIndent()
        )

        val pos = harness.positionOf("main.lua", "SECONDS")
        val completions = harness.queries.completions(harness.path("main.lua"), pos)
        assertCompletion(completions, "SECONDS", CompletionItemKind.FIELD)

        val hover = assertNotNull(harness.queries.hover(harness.path("main.lua"), pos))
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
    }

    // ------------------------------------------------------------------
    // Enum static methods remain METHOD; constants stay FIELD
    // ------------------------------------------------------------------

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
    fun thread_state_static_values_and_value_of_remain_method_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local state = State.RUNNABLE
                return state
            """.trimIndent()
        )

        val completions = completionsAt(harness, "RUNNABLE")
        assertCompletion(completions, "RUNNABLE", CompletionItemKind.FIELD)
        assertCompletion(completions, "values", CompletionItemKind.METHOD)
        assertCompletion(completions, "valueOf", CompletionItemKind.METHOD)
    }

    @Test
    fun time_unit_static_convert_method_remains_method_beside_constants() {
        // TimeUnit exposes instance convert overloads and static of(...); values/valueOf are static.
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local unit = TimeUnit.SECONDS
                return unit
            """.trimIndent()
        )

        val completions = completionsAt(harness, "SECONDS")
        assertCompletion(completions, "SECONDS", CompletionItemKind.FIELD)
        // Static helper from Enum / TimeUnit surface.
        assertCompletion(completions, "valueOf", CompletionItemKind.METHOD)
        assertCompletion(completions, "values", CompletionItemKind.METHOD)
    }

    // ------------------------------------------------------------------
    // Navigation / definition
    // ------------------------------------------------------------------

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
    fun nested_enum_constant_definition_points_to_binary_name_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local state = State.RUNNABLE
                return state
            """.trimIndent()
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "RUNNABLE")
        )
        assertEquals(
            listOf(harness.path("__jvm__/classes/java/lang/Thread\$State.lua")),
            definitions.map { it.path }
        )
    }

    // ------------------------------------------------------------------
    // Multi-enum isolation + alias
    // ------------------------------------------------------------------

    @Test
    fun two_enum_constant_reads_resolve_in_same_document() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local ElementType = luajava.bindClass("java.lang.annotation.ElementType")
                local unit = TimeUnit.SECONDS
                local kind = ElementType.FIELD
                return unit, kind
            """.trimIndent()
        )

        val unitHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "SECONDS"))
        )
        assertEquals("java.util.concurrent.TimeUnit", unitHover.typeInfo?.displayName)

        val kindHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "FIELD"))
        )
        assertEquals("java.lang.annotation.ElementType", kindHover.typeInfo?.displayName)
    }

    @Test
    fun two_bound_enums_constant_completions_isolated() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local RetentionPolicy = luajava.bindClass("java.lang.annotation.RetentionPolicy")
                local unit = TimeUnit.SECONDS
                local policy = RetentionPolicy.RUNTIME
                return unit, policy
            """.trimIndent()
        )

        val timeUnitCompletions = completionsAt(harness, "SECONDS")
        assertCompletion(timeUnitCompletions, "SECONDS", CompletionItemKind.FIELD)
        assertNoCompletion(timeUnitCompletions, "RUNTIME")
        assertNoCompletion(timeUnitCompletions, "SOURCE")

        val retentionCompletions = completionsAt(harness, "RUNTIME")
        assertCompletion(retentionCompletions, "RUNTIME", CompletionItemKind.FIELD)
        assertNoCompletion(retentionCompletions, "SECONDS")
        assertNoCompletion(retentionCompletions, "DAYS")
    }

    @Test
    fun bind_class_alias_still_resolves_enum_constant() {
        val harness = jvmHarness(
            "main.lua" to """
                local bindClass = luajava.bindClass
                local TimeUnit = bindClass("java.util.concurrent.TimeUnit")
                local unit = TimeUnit.MINUTES
                return unit
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "MINUTES"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("java.util.concurrent.TimeUnit", hover.typeInfo?.displayName)

        val completions = completionsAt(harness, "MINUTES")
        assertCompletion(completions, "MINUTES", CompletionItemKind.FIELD)
        assertCompletion(completions, "HOURS", CompletionItemKind.FIELD)
    }

    // ------------------------------------------------------------------
    // Missing enum constants: unknown without crash
    // ------------------------------------------------------------------

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
    fun missing_enum_constant_may_report_invalid_member_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local ElementType = luajava.bindClass("java.lang.annotation.ElementType")
                local value = ElementType.noSuchElementTypeConstant
                return value
            """.trimIndent()
        )

        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "noSuchElementTypeConstant")
        )
        val display = hover?.typeInfo?.displayName
        assertTrue(
            display == null || display == "unknown" || diagnostics.containsInvalidMember("noSuchElementTypeConstant"),
            "Missing enum constant must stay unknown and/or report a diagnostic; " +
                "hover='$display', diagnostics=${diagnostics.map { it.message }}."
        )
    }

    @Test
    fun missing_enum_constant_after_known_constant_does_not_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local unit = TimeUnit.SECONDS
                local missing = TimeUnit.notARealTimeUnit
                return unit, missing
            """.trimIndent()
        )

        val unitHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "SECONDS"))
        )
        assertEquals("java.util.concurrent.TimeUnit", unitHover.typeInfo?.displayName)

        val missingHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "notARealTimeUnit")
        )
        val display = missingHover?.typeInfo?.displayName
        assertTrue(
            display == null || display == "unknown",
            "Missing enum constant should be unknown, got '$display'."
        )
        harness.queries.diagnostics(harness.path("main.lua"))
    }

    @Test
    fun missing_enum_constant_label_is_not_invented_in_completions() {
        val unknown = "definitelyNotAnEnumConstant_xyz"
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local missing = TimeUnit.$unknown
                return missing
            """.trimIndent()
        )

        val completions = completionsAt(harness, unknown)
        assertFalse(
            completions.any { it.label == unknown },
            "Missing enum constant must not be invented as a completion label; actual=${completions.map { it.label }}"
        )
        assertCompletion(completions, "SECONDS", CompletionItemKind.FIELD)
        assertCompletion(completions, "DAYS", CompletionItemKind.FIELD)
    }

    @Test
    fun missing_constant_on_second_enum_isolated_from_first() {
        val harness = jvmHarness(
            "main.lua" to """
                local TimeUnit = luajava.bindClass("java.util.concurrent.TimeUnit")
                local State = luajava.bindClass("java.lang.Thread${'$'}State")
                local unit = TimeUnit.SECONDS
                local bad = State.noSuchThreadState
                return unit, bad
            """.trimIndent()
        )

        val unitHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "SECONDS"))
        )
        assertEquals("java.util.concurrent.TimeUnit", unitHover.typeInfo?.displayName)

        val badHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "noSuchThreadState")
        )
        val display = badHover?.typeInfo?.displayName
        assertTrue(
            display == null || display == "unknown",
            "Missing Thread.State constant should be unknown, got '$display'."
        )
        harness.queries.diagnostics(harness.path("main.lua"))
    }

    // ------------------------------------------------------------------
    // Android nested enums (when host android.jar is available)
    // ------------------------------------------------------------------

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

    @Test
    fun android_porter_duff_mode_enum_constants_surface_when_android_jar_available() {
        if (!androidJar.isFile) return

        val harness = jvmHarness(
            "main.lua" to """
                local Mode = luajava.bindClass("android.graphics.PorterDuff${'$'}Mode")
                local src = Mode.SRC_OVER
                return src
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "SRC_OVER"))
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display == "android.graphics.PorterDuff.Mode" ||
                display == "android.graphics.PorterDuff\$Mode",
            "Expected PorterDuff.Mode for Mode.SRC_OVER, got '$display'."
        )

        val completions = completionsAt(harness, "SRC_OVER")
        assertCompletion(completions, "SRC_OVER", CompletionItemKind.FIELD)
        assertCompletion(completions, "CLEAR", CompletionItemKind.FIELD)
        assertCompletion(completions, "SRC", CompletionItemKind.FIELD)
        assertCompletion(completions, "DST", CompletionItemKind.FIELD)
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
