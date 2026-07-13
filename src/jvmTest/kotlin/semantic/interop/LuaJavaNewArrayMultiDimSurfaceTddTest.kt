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
 * TASK-415 corpus: LuaJava `newArray` **multi-dimensional surface**.
 *
 * Complements [LuaJavaNewArrayTypingTddTest] (1D element typing + one multi-dim smoke),
 * [JavaArrayIndexTypeTddTest] (index → component), and [LuaJavaArrayLengthMemberTddTest]
 * (`.length`) by locking the multi-rank surface:
 *
 * - Multi-dim `newArray(Class, d1, d2[, …])` keeps the bound component class root.
 * - Rank modeling (TASK-592 product lock): multi-rank display (`T[][]`) and nested index
 *   peeling (`T[]` after one index on a 2-d array) via nested JavaArrayType from
 *   ExpressionTypeEvaluator. Flat collapse is no longer accepted for valid multi-dim calls.
 * - `.length` remains available on multi-dim results.
 * - Invalid multi-dim dimensions degrade to unknown[] (TASK-246/TASK-525) and/or diagnose.
 * - Aliases, breadth table, colon / local-shadow negatives.
 *
 * Verification is review-owned serial jvmTest (TASK-043); workers must not run Gradle.
 */
class LuaJavaNewArrayMultiDimSurfaceTddTest {

    // -------------------------------------------------------------------------
    // Positive multi-dim: component root + dual-path rank
    // -------------------------------------------------------------------------

    @Test
    fun three_dim_file_new_array_surfaces_component_root_with_rank_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local cube = luajava.newArray(File, 2, 3, 4)
                local plane = cube[1]
                return cube, plane
            """.trimIndent()
        )

        assertMultiDimArraySurface(
            harness = harness,
            arrayNeedle = "cube",
            componentFqcn = "java.io.File",
            rank = 3
        )
        assertMultiDimIndexSurface(
            harness = harness,
            indexNeedle = "plane",
            componentFqcn = "java.io.File",
            remainingRankAfterOneIndex = 2
        )
    }
    @Test
    fun two_dim_string_new_array_surfaces_string_component_root() {
        val harness = jvmHarness(
            "main.lua" to """
                local String = luajava.bindClass("java.lang.String")
                local grid = luajava.newArray(String, 2, 2)
                local row = grid[1]
                return grid, row
            """.trimIndent()
        )

        assertMultiDimArraySurface(
            harness = harness,
            arrayNeedle = "grid",
            componentFqcn = "string",
            rank = 2,
            alternateComponentNames = listOf("java.lang.String", "String")
        )
        assertMultiDimIndexSurface(
            harness = harness,
            indexNeedle = "row",
            componentFqcn = "string",
            remainingRankAfterOneIndex = 1,
            alternateComponentNames = listOf("java.lang.String", "String")
        )
    }
    @Test
    fun multi_dim_index_and_length_coexist() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local matrix = luajava.newArray(Locale, 2, 3)
                local row = matrix[1]
                local count = matrix.length
                return row, count
            """.trimIndent()
        )

        assertMultiDimIndexSurface(
            harness = harness,
            indexNeedle = "row",
            componentFqcn = "java.util.Locale",
            remainingRankAfterOneIndex = 1
        )
        assertHoverType(harness, "count", "number", occurrence = 2)
        assertHoverType(harness, "length", "number")
    }
    @Test
    fun multi_dim_mixed_valid_and_invalid_third_dimension_degrades() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local values = luajava.newArray(Locale, 2, 3, -1)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertInvalidDimensionsDegrade(harness, "values", "first")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className from prior bindClass; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
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

    /**
     * Multi-dim array local (TASK-592 product lock):
     * - Hard-require nested multi-rank display `component[]…[]` with exact [rank] brackets
     *   (nested JavaArrayType from ExpressionTypeEvaluator).
     * - Flat single-rank collapse is rejected for valid multi-dim allocations.
     */
    private fun assertMultiDimArraySurface(
        harness: WorkspaceSemanticHarness,
        arrayNeedle: String,
        componentFqcn: String,
        rank: Int,
        alternateComponentNames: List<String> = emptyList()
    ) {
        val display = hoverDisplay(harness, arrayNeedle, occurrence = 2)
        assertNotNull(display, "Expected hover type on multi-dim newArray local '$arrayNeedle'.")
        assertFalse(display.isBlank(), "Multi-dim newArray local must not be blank.")

        val componentRoots = (listOf(componentFqcn) + alternateComponentNames).distinct()
        val idealMultiRank = componentRoots.map { root -> root + "[]".repeat(rank) }

        assertTrue(
            display in idealMultiRank,
            "Multi-dim newArray must surface nested rank ${idealMultiRank.joinToString("/")}; got '$display' for rank=$rank"
        )

        // Must not invent wrong component classes.
        assertFalse(
            display.contains("java.lang.Runnable") || display.contains("android."),
            "Multi-dim newArray must not invent unrelated component types; got '$display'"
        )
    }

    /**
     * One numeric index into multi-dim array (TASK-592 product lock):
     * - Remaining array `component[]…` with exact [remainingRankAfterOneIndex] brackets
     *   (or bare component when remaining rank is 0).
     * - Flat collapse to bare component is rejected when remaining rank > 0.
     */
    private fun assertMultiDimIndexSurface(
        harness: WorkspaceSemanticHarness,
        indexNeedle: String,
        componentFqcn: String,
        remainingRankAfterOneIndex: Int,
        alternateComponentNames: List<String> = emptyList()
    ) {
        val display = hoverDisplay(harness, indexNeedle, occurrence = 2)
        assertNotNull(display, "Expected hover type on multi-dim index local '$indexNeedle'.")
        assertFalse(display.isBlank(), "Multi-dim index local must not be blank.")

        val componentRoots = (listOf(componentFqcn) + alternateComponentNames).distinct()
        val idealRemainingArray =
            if (remainingRankAfterOneIndex > 0) {
                componentRoots.map { root -> root + "[]".repeat(remainingRankAfterOneIndex) }
            } else {
                componentRoots
            }

        assertTrue(
            display in idealRemainingArray,
            "Multi-dim index must surface remaining rank ${idealRemainingArray.joinToString("/")}; got '$display'"
        )
    }

    /**
     * Nested `matrix[i][j]` cell (TASK-592 product lock):
     * Two indexes into a 2-d array must yield the bare component type.
     */
    private fun assertNestedCellSurface(
        harness: WorkspaceSemanticHarness,
        cellNeedle: String,
        componentFqcn: String,
        alternateComponentNames: List<String> = emptyList()
    ) {
        val display = hoverDisplay(harness, cellNeedle, occurrence = 2)
        val componentRoots = (listOf(componentFqcn) + alternateComponentNames).distinct()
        assertNotNull(display, "Expected hover type on nested multi-dim cell '$cellNeedle'.")
        assertTrue(
            display in componentRoots,
            "Nested multi-dim index cell must surface component ${componentRoots.joinToString("/")}; got '$display'"
        )
    }

    private fun looksLikeJavaArrayOf(display: String, componentRoots: List<String>): Boolean {
        return componentRoots.any { root ->
            display == "$root[]" ||
                display == "$root[][]" ||
                display == "$root[][][]" ||
                (display.startsWith(root) && display.removePrefix(root).matches(Regex("""(\[\])+""")))
        }
    }

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
            "Invalid multi-dim newArray dimensions must degrade array type to unknown or emit a diagnostic; " +
                "type=$arrayDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
        assertTrue(
            idealUnknownElement || diagnosticHit,
            "Invalid multi-dim newArray dimensions must degrade element type to unknown or emit a diagnostic; " +
                "type=$elementDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
        assertFalse(
            (arrayDisplay == "java.util.Locale[]" || arrayDisplay == "java.util.Locale[][]") && !diagnosticHit,
            "Invalid multi-dim newArray dimensions must not keep Locale array without a dimension diagnostic; " +
                "type=$arrayDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    private fun assertHoverTypeIsTableLikeNotJavaArray(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ) {
        val display = hoverDisplay(harness, needle, occurrence).orEmpty()
        assertTrue(
            display == "table" ||
                display.startsWith("{") ||
                display.contains("value") ||
                display.contains("d1"),
            "Expected table-like shadowed newArray result, got '$display'."
        )
        assertHoverTypeIsNotJavaArray(harness, needle, occurrence)
    }

    private fun assertHoverTypeIsNotJavaArray(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ) {
        val display = hoverDisplay(harness, needle, occurrence).orEmpty()
        assertFalse(
            display.endsWith("[]") || display.contains("[][]"),
            "Shadowed / non-helper newArray must not expose Java array type, got '$display'."
        )
        assertFalse(
            display.startsWith("java.") && display.endsWith("[]"),
            "Shadowed / non-helper newArray must not expose JVM array type, got '$display'."
        )
    }

    private fun assertHoverTypeIsNotJavaComponent(
        harness: WorkspaceSemanticHarness,
        needle: String,
        forbiddenComponent: String,
        occurrence: Int = 1
    ) {
        val display = hoverDisplay(harness, needle, occurrence)
        assertFalse(
            display == forbiddenComponent || display == "$forbiddenComponent[]",
            "Shadowed / non-helper newArray index must not surface $forbiddenComponent; got '$display'"
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
}
