package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-299 corpus: Java array `.length` member surface for LuaJava `newArray` results.
 *
 * Acceptance (test-only; review-owned serial verification):
 * - Java array `.length` surfaces on `luajava.newArray` results as a numeric field.
 * - Non-array receivers do not inherit the synthetic array `length` field; access degrades
 *   to unknown / missing-member without crashing the semantic pipeline.
 *
 * Complements [LuaJavaNewArrayTypingTddTest] (element typing + one length smoke) and
 * [LuaJavaArrayHelpersTddTest] (createArray helpers). This file owns the dedicated
 * length-member positive/negative corpus.
 */
class LuaJavaArrayLengthMemberTddTest {

    // ------------------------------------------------------------------
    // Positive: newArray results expose .length as number field
    // ------------------------------------------------------------------

    @Test
    fun new_array_bound_class_result_exposes_length_as_number() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 2)
                local count = locales.length
                return count
            """.trimIndent()
        )

        assertHoverType(harness, "count", "number", occurrence = 2)
        assertHoverType(harness, "length", "number")
    }

    @Test
    fun new_array_string_class_result_exposes_length_as_number() {
        val harness = jvmHarness(
            "main.lua" to """
                local String = luajava.bindClass("java.lang.String")
                local values = luajava.newArray(String, 3)
                local count = values.length
                return count
            """.trimIndent()
        )

        assertHoverType(harness, "values", "string[]", occurrence = 2)
        assertHoverType(harness, "count", "number", occurrence = 2)
        assertHoverType(harness, "length", "number")
    }

    @Test
    fun new_array_integer_class_result_exposes_length_as_number() {
        val harness = jvmHarness(
            "main.lua" to """
                local Integer = luajava.bindClass("java.lang.Integer")
                local ids = luajava.newArray(Integer, 4)
                local count = ids.length
                return count
            """.trimIndent()
        )

        assertHoverType(harness, "ids", "number[]", occurrence = 2)
        assertHoverType(harness, "count", "number", occurrence = 2)
    }

    @Test
    fun new_array_file_result_length_hover_is_field_kind() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local files = luajava.newArray(File, 1)
                local count = files.length
                return count
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "length"))
        )
        assertEquals("number", hover.typeInfo?.displayName)
        // Synthetic Java array length is a field surface (not a method) when a symbol is attached.
        hover.symbol?.let { symbol ->
            assertEquals(SymbolKind.FIELD, symbol.kind)
        }
    }

    @Test
    fun new_array_member_completions_include_length_field() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 2)
                local count = locales.length
                return count
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "length")
        )
        assertCompletion(completions, "length", CompletionItemKind.FIELD)
    }

    @Test
    fun new_array_alias_preserves_length_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local newArray = luajava.newArray
                local locales = newArray(Locale, 2)
                local count = locales.length
                return count
            """.trimIndent()
        )

        assertHoverType(harness, "locales", "java.util.Locale[]", occurrence = 2)
        assertHoverType(harness, "count", "number", occurrence = 2)
        assertHoverType(harness, "length", "number")
    }

    @Test
    fun multi_dimension_new_array_still_exposes_length() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local matrix = luajava.newArray(Locale, 2, 3)
                local count = matrix.length
                return count
            """.trimIndent()
        )

        // Rank modeling may collapse multi-dim to a single Java array surface, but
        // `.length` must still resolve as the array field when the result is a Java array.
        assertHoverType(harness, "count", "number", occurrence = 2)
        assertHoverType(harness, "length", "number")
    }

    @Test
    fun new_array_length_and_index_element_coexist() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 2)
                local first = locales[1]
                local count = locales.length
                return first, count
            """.trimIndent()
        )

        assertHoverType(harness, "first", "java.util.Locale", occurrence = 2)
        assertHoverType(harness, "count", "number", occurrence = 2)
        assertHoverType(harness, "length", "number")
    }

    @Test
    fun create_array_result_also_exposes_length_as_number() {
        // Adjacent helper surface: createArray produces Java arrays and should share
        // the same synthetic length field as newArray (not the primary AC, but guards
        // regression of the shared JavaArrayType member surface).
        val harness = jvmHarness(
            "main.lua" to """
                local values = luajava.createArray("java.lang.String", {})
                local count = values.length
                return count
            """.trimIndent()
        )

        assertHoverType(harness, "count", "number", occurrence = 2)
        assertHoverType(harness, "length", "number")
    }

    // ------------------------------------------------------------------
    // Negative: non-array receivers must not get synthetic array length
    // ------------------------------------------------------------------

    @Test
    fun non_array_java_instance_without_length_degrades() {
        // Locale has no `length` field/method; synthetic array length must not apply.
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local root = Locale.ROOT
                local count = root.length
                return count
            """.trimIndent()
        )

        assertDegradedLength(harness, memberNeedle = "length", localNeedle = "count")
    }

    @Test
    fun non_array_bound_class_static_length_degrades() {
        // Bound class (static surface) is not a Java array.
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local count = Locale.length
                return count
            """.trimIndent()
        )

        assertDegradedLength(harness, memberNeedle = "length", localNeedle = "count")
    }

    @Test
    fun non_array_plain_lua_table_length_degrades() {
        val harness = jvmHarness(
            "main.lua" to """
                local values = { "a", "b" }
                local count = values.length
                return count
            """.trimIndent()
        )

        assertDegradedLength(harness, memberNeedle = "length", localNeedle = "count")
    }

    @Test
    fun non_array_number_receiver_length_degrades() {
        val harness = jvmHarness(
            "main.lua" to """
                local n = 42
                local count = n.length
                return count
            """.trimIndent()
        )

        assertDegradedLength(harness, memberNeedle = "length", localNeedle = "count")
    }

    @Test
    fun non_array_unknown_receiver_length_degrades_without_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local missing = somethingMissing
                local count = missing.length
                return count
            """.trimIndent()
        )

        assertDegradedLength(harness, memberNeedle = "length", localNeedle = "count")
    }

    @Test
    fun non_array_nil_receiver_length_degrades_without_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local missing = nil
                local count = missing.length
                return count
            """.trimIndent()
        )

        assertDegradedLength(harness, memberNeedle = "length", localNeedle = "count")
    }

    @Test
    fun non_array_completions_do_not_require_synthetic_array_length() {
        // Plain table member completion must not invent Java array `.length`.
        val harness = jvmHarness(
            "main.lua" to """
                local values = { name = "x" }
                local count = values.length
                return count
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "length")
        )
        assertFalse(
            completions.any { it.label == "length" && it.kind == CompletionItemKind.FIELD },
            "Non-array table must not surface synthetic Java array length FIELD; actual: ${completions.map { "${it.label}:${it.kind}" }}"
        )
        // Pipeline still completes diagnostics without throwing.
        assertNotNull(harness.queries.diagnostics(harness.path("main.lua")))
    }

    @Test
    fun array_length_isolated_from_non_array_in_same_document() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 2)
                local root = Locale.ROOT
                local arrayCount = locales.length
                local badCount = root.length
                return arrayCount, badCount
            """.trimIndent()
        )

        assertHoverType(harness, "arrayCount", "number", occurrence = 2)

        // First `length` is locales.length (array); second is root.length (non-array).
        assertHoverType(harness, "length", "number", occurrence = 1)
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        val badMemberHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "length", occurrence = 2)
        )
        val badLocalHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "badCount", occurrence = 2)
        )
        assertDegradedDisplay(
            display = badMemberHover?.typeInfo?.displayName,
            label = "root.length member",
            diagnostics = diagnostics,
            member = "length"
        )
        assertDegradedDisplay(
            display = badLocalHover?.typeInfo?.displayName,
            label = "badCount local",
            diagnostics = diagnostics,
            member = "badCount"
        )
        assertNotNull(diagnostics)
    }

    @Test
    fun invalid_new_array_unknown_array_may_still_expose_or_degrade_length() {
        // When dimensions are invalid the result degrades to unknown[]; length may still
        // surface if the result remains a JavaArrayType(unknown), or fully degrade.
        // Either is acceptable; the pipeline must not crash.
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local values = luajava.newArray(Locale, -1)
                local count = values.length
                return count
            """.trimIndent()
        )

        val lengthHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "length")
        )
        val countHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "count", occurrence = 2)
        )
        val lengthDisplay = lengthHover?.typeInfo?.displayName
        val countDisplay = countHover?.typeInfo?.displayName
        val ok =
            lengthDisplay == "number" ||
                lengthDisplay == null ||
                lengthDisplay == "unknown" ||
                lengthDisplay.isBlank() ||
                countDisplay == "number" ||
                countDisplay == null ||
                countDisplay == "unknown" ||
                countDisplay.isNullOrBlank()
        assertTrue(
            ok,
            "Invalid newArray length access must resolve to number (if still JavaArrayType) or degrade; " +
                "length='$lengthDisplay' count='$countDisplay'"
        )
        assertNotNull(harness.queries.diagnostics(harness.path("main.lua")))
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

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
        assertEquals(expected, hover?.typeInfo?.displayName)
    }

    private fun assertCompletion(completions: List<CompletionItem>, label: String, kind: CompletionItemKind) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    private fun assertDegradedLength(
        harness: WorkspaceSemanticHarness,
        memberNeedle: String,
        localNeedle: String
    ) {
        val memberHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", memberNeedle)
        )
        val localHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", localNeedle, occurrence = 2)
        )
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))

        assertDegradedDisplay(
            display = memberHover?.typeInfo?.displayName,
            label = "member $memberNeedle",
            diagnostics = diagnostics,
            member = memberNeedle
        )
        assertDegradedDisplay(
            display = localHover?.typeInfo?.displayName,
            label = "local $localNeedle",
            diagnostics = diagnostics,
            member = localNeedle
        )
        assertNotNull(diagnostics)
    }

    private fun assertDegradedDisplay(
        display: String?,
        label: String,
        diagnostics: List<Diagnostic> = emptyList(),
        member: String = ""
    ) {
        val degraded =
            display == null ||
                display == "unknown" ||
                display.isBlank() ||
                display == "nil"
        val numberWithDiagnostic =
            display == "number" &&
                member.isNotEmpty() &&
                diagnostics.containsInvalidMember(member)
        assertTrue(
            degraded || numberWithDiagnostic,
            "Non-array length access ($label) must degrade (unknown/nil/untyped) " +
                "or pair number with an invalid-member diagnostic; got '$display' " +
                "diagnostics=${diagnostics.map { it.message }}"
        )
    }

    private fun List<Diagnostic>.containsInvalidMember(member: String): Boolean {
        return any { diagnostic ->
            diagnostic.message.contains(member) &&
                (
                    diagnostic.message.contains("member", ignoreCase = true) ||
                        diagnostic.message.contains("method", ignoreCase = true) ||
                        diagnostic.message.contains("field", ignoreCase = true) ||
                        diagnostic.message.contains("unknown", ignoreCase = true) ||
                        diagnostic.message.contains("unresolved", ignoreCase = true) ||
                        diagnostic.message.contains("not found", ignoreCase = true)
                    )
        }
    }
}
