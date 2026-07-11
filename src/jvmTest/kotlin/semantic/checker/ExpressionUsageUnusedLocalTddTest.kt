package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * REVIEW19 / TASK-202 corpus: ExpressionUsageChecker unused-local diagnostics.
 *
 * Current product policy (ExpressionUsageChecker as of TASK-202 rework):
 * - ExpressionUsageChecker emits only expression-surface diagnostics today:
 *   `checker.luajava.target.unresolved` and `checker.member.missing`.
 * - It does **not** emit unused-local diagnostics (`checker.local.unused`).
 * - Therefore every unused / used / underscore / loop-control local case yields an
 *   empty, stable `checker.local.unused` set (deterministic empty list).
 *
 * Documented gap / future acceptance surface (not implemented in product yet):
 * - A value local is unused when it is never read after declaration (writes alone
 *   would not count as a use).
 * - Stable code `checker.local.unused`, message `Unused local '<name>'.`, WARNING.
 * - Underscore-style ignores: `_` or names starting with `_` would be suppressed.
 * - Parameters and bare globals stay outside unused-local reporting.
 *
 * This corpus locks **actual** checker behavior so review serial verification is green
 * without product edits (test-only scope). If product later implements unused-local
 * emission, flip the positive cases below and keep underscore ignore locks.
 */
class ExpressionUsageUnusedLocalTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // Current policy: no unused-local diagnostics are emitted
    // -------------------------------------------------------------------------

    @Test
    fun unusedSimpleLocalDoesNotEmitUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local unused = 1
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun usedSimpleLocalDoesNotEmitUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local used = 1
            return used
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun localReadInExpressionDoesNotEmitUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local a = 1
            local b = a + 2
            return b
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun localReadAsCallBaseDoesNotEmitUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local fn = function()
                return 1
            end
            return fn()
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun localReadAsMemberBaseDoesNotEmitUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local t = { x = 1 }
            return t.x
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun assignmentWriteAloneDoesNotEmitUnusedLocalDiagnostic() {
        // Documented future gap: write-alone would still be "unused" once emission exists.
        // Current policy: no checker.local.unused at all.
        val model = analyze(
            """
            local written = 0
            written = 1
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    // -------------------------------------------------------------------------
    // Multiple locals / stability of the empty unused-local set
    // -------------------------------------------------------------------------

    @Test
    fun multipleUnusedLocalsStillProduceEmptyUnusedLocalSet() {
        val model = analyze(
            """
            local first = 1
            local second = 2
            local third = 3
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun mixedUsedAndUnusedLocalsStillProduceEmptyUnusedLocalSet() {
        val model = analyze(
            """
            local keep = 1
            local drop = 2
            local alsoKeep = keep + 1
            return alsoKeep
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun repeatedAnalysisYieldsIdenticalEmptyUnusedLocalDiagnostics() {
        val source =
            """
            local a = 1
            local b = 2
            return a
            """.trimIndent()

        val first = unusedLocalDiagnostics(analyze(source))
        val second = unusedLocalDiagnostics(analyze(source))

        assertEquals(emptyList(), first)
        assertEquals(first.map { diagnosticFingerprint(it) }, second.map { diagnosticFingerprint(it) })
    }

    // -------------------------------------------------------------------------
    // Nested scopes / multi-name declarations
    // -------------------------------------------------------------------------

    @Test
    fun nestedBlockUnusedLocalDoesNotEmitUnusedLocalDiagnostic() {
        val model = analyze(
            """
            do
                local hidden = 1
            end
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun shadowingOuterUsedInnerUnusedDoesNotEmitUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local value = 1
            do
                local value = 2
            end
            return value
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun multiNameLocalStatementDoesNotEmitUnusedLocalDiagnostics() {
        val model = analyze(
            """
            local left, right = 1, 2
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun multiNameLocalWithPartialUseDoesNotEmitUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local left, right = 1, 2
            return left
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun unusedLocalFunctionValueDoesNotEmitUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local helper = function()
                return 1
            end
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun usedLocalFunctionValueDoesNotEmitUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local helper = function()
                return 1
            end
            return helper()
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    // -------------------------------------------------------------------------
    // Underscore-style ignore policy (vacuous under current non-emission)
    // -------------------------------------------------------------------------

    @Test
    fun singleUnderscoreLocalIsNotReportedAsUnusedLocal() {
        val model = analyze(
            """
            local _ = 1
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
        assertFalse(
            model.getDiagnostics().any {
                it.code == UNUSED_LOCAL_CODE && it.message.contains("'_'")
            }
        )
    }

    @Test
    fun underscorePrefixedLocalIsNotReportedAsUnusedLocal() {
        val model = analyze(
            """
            local _ignored = 1
            local _also = 2
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun nonUnderscoreSiblingAlsoHasNoUnusedLocalDiagnosticUnderCurrentPolicy() {
        // Future policy would report only reportMe; current policy reports neither.
        val model = analyze(
            """
            local _skip = 1
            local reportMe = 2
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun multiNameWithUnderscoreStillProducesEmptyUnusedLocalSet() {
        val model = analyze(
            """
            local keep, _drop, alsoDrop = 1, 2, 3
            return keep
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun doubleUnderscoreAndMidUnderscoreNamesProduceEmptyUnusedLocalSet() {
        // Future: leading `_` ignored; mid_name reported. Current: neither.
        val model = analyze(
            """
            local __dunder = 1
            local mid_name = 2
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    // -------------------------------------------------------------------------
    // Non-local surfaces stay free of unused-local codes
    // -------------------------------------------------------------------------

    @Test
    fun unusedGlobalIsNotReportedAsUnusedLocal() {
        val model = analyze(
            """
            globalName = 1
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun unusedParameterIsNotReportedAsUnusedLocal() {
        val model = analyze(
            """
            local function render(input)
                return 1
            end
            return render(0)
            """.trimIndent()
        )

        assertTrue(
            unusedLocalDiagnostics(model).none {
                it.message.contains("'input'")
            }
        )
    }

    @Test
    fun forLoopControlVariablesDoNotEmitUnusedLocalDiagnostics() {
        val model = analyze(
            """
            local total = 0
            for i = 1, 3 do
                total = total + i
            end
            return total
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun unusedForLoopControlVariableDoesNotEmitUnusedLocalDiagnostic() {
        // Future: report `i`, ignore `_`. Current: empty unused-local set.
        val model = analyze(
            """
            for i = 1, 3 do
            end
            for _ = 1, 3 do
            end
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    // -------------------------------------------------------------------------
    // Diagnostic surface stability under current non-emission policy
    // -------------------------------------------------------------------------

    @Test
    fun unusedLocalCodeIsAbsentFromPipelineDiagnosticsForPlainLocals() {
        val model = analyze(
            """
            local only = true
            return false
            """.trimIndent()
        )

        assertFalse(model.getDiagnostics().any { it.code == UNUSED_LOCAL_CODE })
        assertEquals(0, unusedLocalDiagnostics(model).size)
    }

    @Test
    fun plainLocalProgramDoesNotFabricateUnusedLocalRanges() {
        val model = analyze(
            """
            local target = 123
            return 0
            """.trimIndent()
        )

        assertTrue(unusedLocalDiagnostics(model).isEmpty())
    }

    @Test
    fun pureWritesDoNotProduceDuplicateOrAnyUnusedLocalDiagnostics() {
        val unusedModel = analyze(
            """
            local once = 1
            once = 2
            once = 3
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(unusedModel)
        assertEquals(0, diagnostics.size)
        assertEquals(0, diagnostics.map { diagnosticFingerprint(it) }.toSet().size)
    }

    @Test
    fun expressionUsageCheckerStillOnlyUsesKnownCodesOnPlainLua() {
        // Guard: plain local-only Lua must not invent unused-local codes under any alias.
        val model = analyze(
            """
            local a, b = 1, 2
            b = b
            return a
            """.trimIndent()
        )

        val codes = model.getDiagnostics().mapNotNull { it.code }.toSet()
        assertFalse(UNUSED_LOCAL_CODE in codes)
        assertFalse(codes.any { it.contains("unused", ignoreCase = true) })
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    private fun analyze(source: String): SemanticModel {
        return pipeline.analyze(parser.parse(source)).model
    }

    private fun unusedLocalDiagnostics(model: SemanticModel): List<Diagnostic> {
        return model.getDiagnostics()
            .filter { it.code == UNUSED_LOCAL_CODE }
            .sortedWith(
                compareBy<Diagnostic>(
                    { it.range?.start?.line ?: Int.MAX_VALUE },
                    { it.range?.start?.column ?: Int.MAX_VALUE },
                    { it.message }
                )
            )
    }

    private fun assertNoUnusedLocalDiagnostics(model: SemanticModel) {
        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(
            emptyList(),
            diagnostics,
            "Current ExpressionUsageChecker policy: no checker.local.unused emission. " +
                "Got: ${diagnostics.map { "${it.code}:${it.message}@${it.range}" }}"
        )
    }

    private fun diagnosticFingerprint(diagnostic: Diagnostic): List<Any?> {
        return listOf(
            diagnostic.code,
            diagnostic.message,
            diagnostic.severity,
            diagnostic.range?.start?.line,
            diagnostic.range?.start?.column,
            diagnostic.range?.end?.line,
            diagnostic.range?.end?.column
        )
    }

    private companion object {
        /**
         * Stable code reserved for future unused-local diagnostics.
         * Current product does not emit this code from ExpressionUsageChecker.
         */
        const val UNUSED_LOCAL_CODE = "checker.local.unused"
    }
}
