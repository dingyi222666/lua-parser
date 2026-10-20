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
 * TASK-349 corpus: Java array index expressions type as the array component type
 * when the receiver is a modeled Java array (`luajava.newArray` / `createArray`).
 *
 * Acceptance (test-only; review-owned serial verification):
 * - Index expressions on modeled Java arrays surface the component/element type.
 * - `.length` remains available on the same receivers (does not regress TASK-299).
 * - Non-array / unknown receivers degrade without crashing the semantic pipeline.
 *
 * Complements:
 * - [LuaJavaNewArrayTypingTddTest] — newArray result + one-index smoke
 * - [LuaJavaArrayHelpersTddTest] — createArray helpers
 * - [LuaJavaArrayLengthMemberTddTest] — dedicated `.length` positive/negative corpus
 *
 * This file owns the dedicated index-component-type positive/negative corpus and the
 * co-existence of index + length on the same Java array value.
 */
class JavaArrayIndexTypeTddTest {

    // ------------------------------------------------------------------
    // Positive: newArray index → component type
    // ------------------------------------------------------------------

    @Test
    fun new_array_locale_index_reports_locale_component_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 2)
                local first = locales[1]
                return first
            """.trimIndent()
        )

        assertHoverType(harness, "locales", "java.util.Locale[]", occurrence = 2)
        assertHoverType(harness, "first", "java.util.Locale", occurrence = 2)
    }
    @Test
    fun new_array_string_index_reports_string_component_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local String = luajava.bindClass("java.lang.String")
                local values = luajava.newArray(String, 3)
                local first = values[1]
                return first
            """.trimIndent()
        )

        assertHoverType(harness, "values", "string[]", occurrence = 2)
        assertHoverType(harness, "first", "string", occurrence = 2)
    }
    @Test
    fun new_array_zero_based_and_one_based_numeric_indexes_share_component_type() {
        // Lua 1-based convention is common, but modeled Java arrays accept number indexes.
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 3)
                local zero = locales[0]
                local one = locales[1]
                return zero, one
            """.trimIndent()
        )

        assertHoverType(harness, "zero", "java.util.Locale", occurrence = 2)
        assertHoverType(harness, "one", "java.util.Locale", occurrence = 2)
    }
    @Test
    fun multi_dimension_new_array_index_still_reports_component_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local matrix = luajava.newArray(Locale, 2, 3)
                local first = matrix[1]
                return first
            """.trimIndent()
        )

        // Nested JavaArrayType rank from newArray(Locale, 2, 3) is Locale[][]; one numeric
        // index peels a single rank to intermediate Locale[] (not bare Locale / not [[]]).
        assertHoverType(harness, "matrix", "java.util.Locale[][]", occurrence = 2)
        assertHoverType(harness, "first", "java.util.Locale[]", occurrence = 2)
    }
    @Test
    fun create_array_string_index_reports_string_component_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local values = luajava.createArray("java.lang.String", { "a", "b" })
                local first = values[1]
                return first
            """.trimIndent()
        )

        assertHoverType(harness, "values", "string[]", occurrence = 2)
        assertHoverType(harness, "first", "string", occurrence = 2)
    }
    @Test
    fun new_array_index_and_length_coexist_on_same_receiver() {
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
    fun non_array_java_instance_index_degrades_without_crash() {
        // Locale instance is not a Java array; numeric index must not invent component typing.
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local root = Locale.ROOT
                local first = root[1]
                return first
            """.trimIndent()
        )

        assertDegradedIndex(harness, localNeedle = "first")
    }
    @Test
    fun plain_lua_table_index_is_not_java_array_component_typing() {
        // Plain Lua tables keep their own index surface; this is not a Java array component.
        val harness = jvmHarness(
            "main.lua" to """
                local values = { "a", "b" }
                local first = values[1]
                return first
            """.trimIndent()
        )

        val firstDisplay = hoverDisplay(harness, "first", occurrence = 2)
        // Table index may be string/unknown/nil depending on inference; must not claim Java Locale.
        assertFalse(
            firstDisplay == "java.util.Locale" || firstDisplay == "java.util.Locale[]",
            "Plain Lua table index must not surface Java array component typing; got '$firstDisplay'"
        )
        assertNotNull(harness.queries.diagnostics(harness.path("main.lua")))
    }
    @Test
    fun array_index_isolated_from_non_array_in_same_document() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 2)
                local root = Locale.ROOT
                local first = locales[1]
                local bad = root[1]
                local count = locales.length
                return first, bad, count
            """.trimIndent()
        )

        assertHoverType(harness, "first", "java.util.Locale", occurrence = 2)
        assertHoverType(harness, "count", "number", occurrence = 2)
        assertHoverType(harness, "length", "number")

        val badDisplay = hoverDisplay(harness, "bad", occurrence = 2)
        val diagnostics = diagnostics(harness)
        val degraded =
            badDisplay == null ||
                badDisplay == "unknown" ||
                badDisplay.isBlank() ||
                badDisplay == "nil"
        val diagnosticHit = diagnostics.any { it.looksLikeInvalidIndex() || it.looksLikeMissingMember("bad") }
        assertTrue(
            degraded || diagnosticHit,
            "Non-array index local must degrade or diagnose; type='$badDisplay' diagnostics=${diagnostics.map { it.message }}"
        )
        assertFalse(
            badDisplay == "java.util.Locale" && !diagnosticHit,
            "Non-array root[1] must not silently type as Locale; type='$badDisplay'"
        )
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

    private fun assertCompletion(completions: List<CompletionItem>, label: String, kind: CompletionItemKind) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    private fun assertDegradedIndex(
        harness: WorkspaceSemanticHarness,
        localNeedle: String
    ) {
        val localHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", localNeedle, occurrence = 2)
        )
        val diagnostics = diagnostics(harness)
        assertDegradedDisplay(
            display = localHover?.typeInfo?.displayName,
            label = "local $localNeedle",
            diagnostics = diagnostics
        )
        assertNotNull(diagnostics)
    }

    private fun assertDegradedDisplay(
        display: String?,
        label: String,
        diagnostics: List<Diagnostic> = emptyList()
    ) {
        val degraded =
            display == null ||
                display == "unknown" ||
                display.isBlank() ||
                display == "nil"
        val diagnosticHit = diagnostics.any { it.looksLikeInvalidIndex() || it.looksLikeMissingMember(label) }
        assertTrue(
            degraded || diagnosticHit,
            "Non-array / invalid index access ($label) must degrade (unknown/nil/untyped) " +
                "or emit an index/member diagnostic; got '$display' diagnostics=${diagnostics.map { it.message }}"
        )
    }

    private fun assertInvalidArrayIndexDegrades(
        harness: WorkspaceSemanticHarness,
        arrayNeedle: String,
        elementNeedle: String
    ) {
        val arrayDisplay = hoverDisplay(harness, arrayNeedle, occurrence = 2)
        val elementDisplay = hoverDisplay(harness, elementNeedle, occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeDimensionProblem() || it.looksLikeUnknownArray() || it.looksLikeInvalidIndex()
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
            "Invalid newArray dimensions must degrade index component to unknown or emit a diagnostic; " +
                "type=$elementDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
        assertFalse(
            arrayDisplay == "java.util.Locale[]" && elementDisplay == "java.util.Locale" && !diagnosticHit,
            "Invalid newArray dimensions must not keep Locale[]/Locale without a diagnostic; " +
                "array=$arrayDisplay element=$elementDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    private fun Diagnostic.looksLikeInvalidIndex(): Boolean {
        val message = message.lowercase()
        return (message.contains("index") || message.contains("subscript") || message.contains("key")) &&
            (
                message.contains("invalid") ||
                    message.contains("unknown") ||
                    message.contains("unsupported") ||
                    message.contains("not a number") ||
                    message.contains("non-numeric") ||
                    message.contains("expected") ||
                    message.contains("unresolved")
                )
    }

    private fun Diagnostic.looksLikeMissingMember(member: String): Boolean {
        return message.contains(member) &&
            (
                message.contains("member", ignoreCase = true) ||
                    message.contains("method", ignoreCase = true) ||
                    message.contains("field", ignoreCase = true) ||
                    message.contains("unknown", ignoreCase = true) ||
                    message.contains("unresolved", ignoreCase = true) ||
                    message.contains("not found", ignoreCase = true)
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
