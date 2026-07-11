package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-213 / TASK-246 corpus: LuaJava `newArray` helper typing.
 *
 * Acceptance focus:
 * - element type propagation from bound class / module first argument
 * - invalid or missing dimensions degrade to `unknown[]` (and/or diagnostics)
 * - valid `newArray(class, n)` keeps element type on array and index results
 *
 * TASK-246 product path: ExpressionTypeEvaluator validates newArray dimensions
 * (missing/zero/negative/non-numeric/nil/mixed) and degrades the result element
 * type to unknown when any dimension is invalid.
 */
class LuaJavaNewArrayTypingTddTest {
    @Test
    fun new_array_bound_class_propagates_element_type_to_result_and_index() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 2)
                local first = locales[1]
                return locales, first
            """.trimIndent()
        )

        assertHoverType(harness, "locales", "java.util.Locale[]", occurrence = 2)
        assertHoverType(harness, "first", "java.util.Locale", occurrence = 2)
    }

    @Test
    fun new_array_string_class_propagates_string_element_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local String = luajava.bindClass("java.lang.String")
                local values = luajava.newArray(String, 3)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertHoverType(harness, "values", "string[]", occurrence = 2)
        assertHoverType(harness, "first", "string", occurrence = 2)
    }

    @Test
    fun new_array_integer_class_propagates_number_element_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local Integer = luajava.bindClass("java.lang.Integer")
                local ids = luajava.newArray(Integer, 4)
                local first = ids[1]
                return ids, first
            """.trimIndent()
        )

        assertHoverType(harness, "ids", "number[]", occurrence = 2)
        assertHoverType(harness, "first", "number", occurrence = 2)
    }

    @Test
    fun new_array_result_exposes_java_array_length() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local files = luajava.newArray(File, 1)
                local count = files.length
                return count
            """.trimIndent()
        )

        assertHoverType(harness, "count", "number", occurrence = 2)
    }

    @Test
    fun new_array_alias_preserves_element_type_propagation() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local newArray = luajava.newArray
                local locales = newArray(Locale, 2)
                local first = locales[1]
                return locales, first
            """.trimIndent()
        )

        assertHoverType(harness, "locales", "java.util.Locale[]", occurrence = 2)
        assertHoverType(harness, "first", "java.util.Locale", occurrence = 2)
    }

    @Test
    fun chained_new_array_alias_preserves_element_type_propagation() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local newArray = luajava.newArray
                local make = newArray
                local again = make
                local locales = again(Locale, 2)
                local first = locales[1]
                return locales, first
            """.trimIndent()
        )

        assertHoverType(harness, "locales", "java.util.Locale[]", occurrence = 2)
        assertHoverType(harness, "first", "java.util.Locale", occurrence = 2)
    }

    @Test
    fun multi_dimension_new_array_still_propagates_component_element_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local matrix = luajava.newArray(Locale, 2, 3)
                local first = matrix[1]
                return matrix, first
            """.trimIndent()
        )

        // Element typing must remain rooted in the bound class even when more than
        // one dimension argument is supplied. Multi-rank array surface may still
        // collapse to a single Java array rank until rank modeling lands.
        assertHoverType(harness, "matrix", "java.util.Locale[]", occurrence = 2)
        assertHoverType(harness, "first", "java.util.Locale", occurrence = 2)
    }

    @Test
    fun unknown_class_value_degrades_to_unknown_array() {
        val harness = jvmHarness(
            "main.lua" to """
                local Missing = somethingMissing
                local values = luajava.newArray(Missing, 2)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertHoverType(harness, "values", "unknown[]", occurrence = 2)
        assertHoverType(harness, "first", "unknown", occurrence = 2)
    }

    @Test
    fun non_class_first_argument_degrades_to_unknown_array() {
        val harness = jvmHarness(
            "main.lua" to """
                local values = luajava.newArray("java.util.Locale", 2)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        // newArray requires a bound class userdata, not a class-name string
        // (string targets belong to createArray).
        assertHoverType(harness, "values", "unknown[]", occurrence = 2)
        assertHoverType(harness, "first", "unknown", occurrence = 2)
    }

    @Test
    fun missing_dimension_arguments_degrade_to_unknown_array() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local values = luajava.newArray(Locale)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertInvalidDimensionsDegrade(harness, "values", "first")
    }

    @Test
    fun zero_dimension_degrades_to_unknown_array() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local values = luajava.newArray(Locale, 0)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertInvalidDimensionsDegrade(harness, "values", "first")
    }

    @Test
    fun negative_dimension_degrades_to_unknown_array() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local values = luajava.newArray(Locale, -1)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertInvalidDimensionsDegrade(harness, "values", "first")
    }

    @Test
    fun non_numeric_dimension_degrades_to_unknown_array() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local values = luajava.newArray(Locale, "two")
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertInvalidDimensionsDegrade(harness, "values", "first")
    }

    @Test
    fun nil_dimension_degrades_to_unknown_array() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local values = luajava.newArray(Locale, nil)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertInvalidDimensionsDegrade(harness, "values", "first")
    }

    @Test
    fun mixed_valid_and_invalid_dimensions_degrade_to_unknown_array() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local values = luajava.newArray(Locale, 2, -3)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertInvalidDimensionsDegrade(harness, "values", "first")
    }

    @Test
    fun valid_new_array_does_not_require_unknown_dimension_diagnostics() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 2)
                return locales
            """.trimIndent()
        )

        assertHoverType(harness, "locales", "java.util.Locale[]", occurrence = 2)
        assertFalse(
            diagnostics(harness).any { it.looksLikeDimensionProblem() },
            "Valid newArray dimensions should not emit dimension diagnostics; actual: ${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun invalid_dimension_degrades_to_unknown_or_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local values = luajava.newArray(Locale, -1)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertInvalidDimensionsDegrade(harness, "values", "first")
    }

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
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, occurrence))
        assertEquals(expected, hover?.typeInfo?.displayName)
    }

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int
    ): String? {
        return harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )?.typeInfo?.displayName
    }

    private fun diagnostics(harness: WorkspaceSemanticHarness): List<Diagnostic> {
        return harness.queries.diagnostics(harness.path("main.lua"))
    }

    /**
     * TASK-246: invalid/missing newArray dimensions degrade array element typing
     * to unknown and/or emit a dimension diagnostic.
     */
    private fun assertInvalidDimensionsDegrade(
        harness: WorkspaceSemanticHarness,
        arrayNeedle: String,
        elementNeedle: String
    ) {
        val arrayDisplay = hoverDisplay(harness, arrayNeedle, occurrence = 2)
        val elementDisplay = hoverDisplay(harness, elementNeedle, occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeDimensionProblem() || it.looksLikeUnknownArray()
        }
        val idealUnknownArray =
            arrayDisplay == "unknown[]" || arrayDisplay == "unknown" || arrayDisplay.isNullOrBlank()
        val idealUnknownElement =
            elementDisplay == "unknown" || elementDisplay.isNullOrBlank()

        assertTrue(
            idealUnknownArray || diagnosticHit,
            "Invalid newArray dimensions must degrade array type to unknown or emit a diagnostic; " +
                "type=$arrayDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
        assertTrue(
            idealUnknownElement || diagnosticHit,
            "Invalid newArray dimensions must degrade element type to unknown or emit a diagnostic; " +
                "type=$elementDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
        assertFalse(
            arrayDisplay == "java.util.Locale[]" && !diagnosticHit,
            "Invalid newArray dimensions must not keep Locale[] without a dimension diagnostic; " +
                "type=$arrayDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    private fun Diagnostic.looksLikeDimensionProblem(): Boolean {
        val message = message.lowercase()
        return (message.contains("dimension") || message.contains("size") || message.contains("length") || message.contains("newarray")) &&
            (
                message.contains("invalid") ||
                    message.contains("unknown") ||
                    message.contains("negative") ||
                    message.contains("zero") ||
                    message.contains("missing") ||
                    message.contains("non-numeric") ||
                    message.contains("not a number") ||
                    message.contains("expected")
                )
    }

    private fun Diagnostic.looksLikeUnknownArray(): Boolean {
        val message = message.lowercase()
        return message.contains("array") && (
            message.contains("unknown") ||
                message.contains("invalid") ||
                message.contains("unresolved")
            )
    }
}
