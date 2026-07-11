package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REVIEW19 / TASK-202 corpus: ExpressionUsageChecker unused-local diagnostics.
 *
 * Policy encoded here (stable acceptance surface for review-owned product work):
 * - A value local is unused when it is never read after declaration (writes alone do not count).
 * - Diagnostics use stable code `checker.local.unused` and message `Unused local '<name>'.`.
 * - Severity is WARNING (unused is not a hard error).
 * - Diagnostics are deterministic: sorted by declaration position, one per unused local.
 * - Underscore-style ignores: name `_` or any name starting with `_` is never reported.
 * - Parameters and globals are out of this corpus (value locals / loop control locals only).
 *
 * Test-only; product edits are out of worker scope. Verification is review-owned.
 */
class ExpressionUsageUnusedLocalTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // Basic unused / used locals
    // -------------------------------------------------------------------------

    @Test
    fun unusedSimpleLocalProducesStableDiagnostic() {
        val model = analyze(
            """
            local unused = 1
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics.single()
        assertStableUnusedLocal(diagnostic, "unused")
        assertEquals(Position(1, 7), diagnostic.range?.start)
    }

    @Test
    fun usedSimpleLocalProducesNoUnusedDiagnostic() {
        val model = analyze(
            """
            local used = 1
            return used
            """.trimIndent()
        )

        assertTrue(unusedLocalDiagnostics(model).isEmpty())
    }

    @Test
    fun localReadInExpressionCountsAsUse() {
        val model = analyze(
            """
            local a = 1
            local b = a + 2
            return b
            """.trimIndent()
        )

        assertTrue(unusedLocalDiagnostics(model).isEmpty())
    }

    @Test
    fun localReadAsCallBaseCountsAsUse() {
        val model = analyze(
            """
            local fn = function()
                return 1
            end
            return fn()
            """.trimIndent()
        )

        assertTrue(unusedLocalDiagnostics(model).isEmpty())
    }

    @Test
    fun localReadAsMemberBaseCountsAsUse() {
        val model = analyze(
            """
            local t = { x = 1 }
            return t.x
            """.trimIndent()
        )

        assertTrue(unusedLocalDiagnostics(model).isEmpty())
    }

    @Test
    fun assignmentWriteAloneDoesNotCountAsUse() {
        val model = analyze(
            """
            local written = 0
            written = 1
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(1, diagnostics.size)
        assertStableUnusedLocal(diagnostics.single(), "written")
    }

    // -------------------------------------------------------------------------
    // Multiple locals — stability / sorting
    // -------------------------------------------------------------------------

    @Test
    fun multipleUnusedLocalsReportEachWithStableOrder() {
        val model = analyze(
            """
            local first = 1
            local second = 2
            local third = 3
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(listOf("first", "second", "third"), diagnostics.map { unusedLocalName(it) })
        assertEquals(
            listOf("checker.local.unused", "checker.local.unused", "checker.local.unused"),
            diagnostics.map { it.code }
        )
        // Sorted by declaration start position (line, then column).
        assertTrue(diagnostics.zipWithNext().all { (a, b) ->
            val as_ = a.range?.start
            val bs = b.range?.start
            as_ != null && bs != null &&
                (as_.line < bs.line || (as_.line == bs.line && as_.column <= bs.column))
        })
    }

    @Test
    fun mixedUsedAndUnusedLocalsReportOnlyUnused() {
        val model = analyze(
            """
            local keep = 1
            local drop = 2
            local alsoKeep = keep + 1
            return alsoKeep
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(listOf("drop"), diagnostics.map { unusedLocalName(it) })
        assertStableUnusedLocal(diagnostics.single(), "drop")
    }

    @Test
    fun repeatedAnalysisYieldsIdenticalUnusedDiagnostics() {
        val source =
            """
            local a = 1
            local b = 2
            return a
            """.trimIndent()

        val first = unusedLocalDiagnostics(analyze(source))
        val second = unusedLocalDiagnostics(analyze(source))

        assertEquals(1, first.size)
        assertEquals(first.map { diagnosticFingerprint(it) }, second.map { diagnosticFingerprint(it) })
        assertStableUnusedLocal(first.single(), "b")
    }

    // -------------------------------------------------------------------------
    // Nested scopes / multi-name declarations
    // -------------------------------------------------------------------------

    @Test
    fun nestedBlockUnusedLocalIsReported() {
        val model = analyze(
            """
            do
                local hidden = 1
            end
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(1, diagnostics.size)
        assertStableUnusedLocal(diagnostics.single(), "hidden")
    }

    @Test
    fun shadowingOuterUsedInnerUnusedReportsOnlyInner() {
        val model = analyze(
            """
            local value = 1
            do
                local value = 2
            end
            return value
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(1, diagnostics.size)
        assertStableUnusedLocal(diagnostics.single(), "value")
        // Inner declaration is on line 3.
        assertEquals(3, diagnostics.single().range?.start?.line)
    }

    @Test
    fun multiNameLocalStatementReportsEachUnusedName() {
        val model = analyze(
            """
            local left, right = 1, 2
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(listOf("left", "right"), diagnostics.map { unusedLocalName(it) })
        diagnostics.forEach { assertStableUnusedLocal(it, unusedLocalName(it)) }
    }

    @Test
    fun multiNameLocalWithPartialUseReportsOnlyUnreadNames() {
        val model = analyze(
            """
            local left, right = 1, 2
            return left
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(listOf("right"), diagnostics.map { unusedLocalName(it) })
        assertStableUnusedLocal(diagnostics.single(), "right")
    }

    @Test
    fun unusedLocalFunctionValueIsReported() {
        // Prefer `local helper = function ...` so the binder surface is DeclarationKind.LOCAL.
        val model = analyze(
            """
            local helper = function()
                return 1
            end
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(1, diagnostics.size)
        assertStableUnusedLocal(diagnostics.single(), "helper")
    }

    @Test
    fun usedLocalFunctionValueIsNotReported() {
        val model = analyze(
            """
            local helper = function()
                return 1
            end
            return helper()
            """.trimIndent()
        )

        assertTrue(unusedLocalDiagnostics(model).isEmpty())
    }

    // -------------------------------------------------------------------------
    // Underscore-style ignore policy
    // -------------------------------------------------------------------------

    @Test
    fun singleUnderscoreLocalIsIgnored() {
        val model = analyze(
            """
            local _ = 1
            return 0
            """.trimIndent()
        )

        assertTrue(unusedLocalDiagnostics(model).isEmpty())
        assertFalse(model.getDiagnostics().any { it.message.contains("'_'") && it.code == UNUSED_LOCAL_CODE })
    }

    @Test
    fun underscorePrefixedLocalIsIgnored() {
        val model = analyze(
            """
            local _ignored = 1
            local _also = 2
            return 0
            """.trimIndent()
        )

        assertTrue(unusedLocalDiagnostics(model).isEmpty())
    }

    @Test
    fun underscoreIgnoreDoesNotSuppressNonUnderscoreSiblings() {
        val model = analyze(
            """
            local _skip = 1
            local reportMe = 2
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(listOf("reportMe"), diagnostics.map { unusedLocalName(it) })
        assertStableUnusedLocal(diagnostics.single(), "reportMe")
    }

    @Test
    fun multiNameWithUnderscoreIgnoresOnlyUnderscoreNames() {
        val model = analyze(
            """
            local keep, _drop, alsoDrop = 1, 2, 3
            return keep
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(listOf("alsoDrop"), diagnostics.map { unusedLocalName(it) })
        assertStableUnusedLocal(diagnostics.single(), "alsoDrop")
    }

    @Test
    fun doubleUnderscoreAndMidUnderscoreNamesFollowPrefixPolicyOnly() {
        // Leading `_` => ignored. Name with internal `_` but no leading `_` => still reported.
        val model = analyze(
            """
            local __dunder = 1
            local mid_name = 2
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(listOf("mid_name"), diagnostics.map { unusedLocalName(it) })
        assertStableUnusedLocal(diagnostics.single(), "mid_name")
    }

    // -------------------------------------------------------------------------
    // Non-local surfaces stay out of this corpus
    // -------------------------------------------------------------------------

    @Test
    fun unusedGlobalIsNotReportedAsUnusedLocal() {
        val model = analyze(
            """
            globalName = 1
            return 0
            """.trimIndent()
        )

        assertTrue(unusedLocalDiagnostics(model).isEmpty())
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

        assertTrue(unusedLocalDiagnostics(model).none { unusedLocalName(it) == "input" })
    }

    @Test
    fun forLoopControlVariableUseSuppressesUnused() {
        val model = analyze(
            """
            local total = 0
            for i = 1, 3 do
                total = total + i
            end
            return total
            """.trimIndent()
        )

        assertTrue(unusedLocalDiagnostics(model).none { unusedLocalName(it) == "i" })
        assertTrue(unusedLocalDiagnostics(model).none { unusedLocalName(it) == "total" })
    }

    @Test
    fun unusedForLoopControlVariableIsReportedUnlessUnderscore() {
        val model = analyze(
            """
            for i = 1, 3 do
            end
            for _ = 1, 3 do
            end
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(listOf("i"), diagnostics.map { unusedLocalName(it) })
        assertStableUnusedLocal(diagnostics.single(), "i")
    }

    // -------------------------------------------------------------------------
    // Diagnostic surface stability
    // -------------------------------------------------------------------------

    @Test
    fun unusedLocalDiagnosticUsesWarningSeverity() {
        val model = analyze(
            """
            local only = true
            return false
            """.trimIndent()
        )

        val diagnostic = unusedLocalDiagnostics(model).single()
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
    }

    @Test
    fun unusedLocalDiagnosticRangeCoversDeclarationName() {
        val model = analyze(
            """
            local target = 123
            return 0
            """.trimIndent()
        )

        val diagnostic = unusedLocalDiagnostics(model).single()
        val range = assertNotNull(diagnostic.range)
        // `local target = 123` — name starts at column 7 (1-based).
        assertEquals(Position(1, 7), range.start)
        // End is exclusive of the identifier span (column after last character).
        assertEquals(Position(1, 7 + "target".length), range.end)
    }

    @Test
    fun noDuplicateUnusedDiagnosticsForSameLocal() {
        // Pure writes only: read-on-RHS of self-assignment would count as a use.
        val unusedModel = analyze(
            """
            local once = 1
            once = 2
            once = 3
            return 0
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(unusedModel)
        assertEquals(1, diagnostics.size)
        assertEquals(1, diagnostics.map { diagnosticFingerprint(it) }.toSet().size)
        assertStableUnusedLocal(diagnostics.single(), "once")
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

    private fun assertStableUnusedLocal(diagnostic: Diagnostic, name: String) {
        assertEquals(UNUSED_LOCAL_CODE, diagnostic.code)
        assertEquals(UNUSED_LOCAL_MESSAGE.format(name), diagnostic.message)
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertNotNull(diagnostic.range)
        assertNotNull(diagnostic.range?.start)
        assertNotNull(diagnostic.range?.end)
    }

    private fun unusedLocalName(diagnostic: Diagnostic): String {
        val match = UNUSED_LOCAL_NAME_REGEX.matchEntire(diagnostic.message)
        assertNotNull(
            match,
            "Unused-local message must match '${UNUSED_LOCAL_MESSAGE.format("<name>")}'; got: ${diagnostic.message}"
        )
        return match.groupValues[1]
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
        const val UNUSED_LOCAL_CODE = "checker.local.unused"
        const val UNUSED_LOCAL_MESSAGE = "Unused local '%s'."
        val UNUSED_LOCAL_NAME_REGEX = Regex("^Unused local '([^']+)'\\.$")
    }
}
