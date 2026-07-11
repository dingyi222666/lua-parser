package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-556 / TASK-202 corpus: ExpressionUsageChecker unused-local diagnostics.
 *
 * Product policy (ExpressionUsageChecker):
 * - Value locals that are never *read* after declaration emit WARNING
 *   `checker.local.unused` with message `Unused local '<name>'.`
 * - Writes alone (assignment LHS) do **not** count as a use.
 * - Underscore-style ignores: `_` or names starting with `_` are suppressed.
 * - Parameters and bare globals stay outside unused-local reporting.
 * - Loop-control names stay outside unused-local reporting.
 * - Table field names / member selectors are not reads of value locals.
 * - Existing Java member / luajava unresolved diagnostics remain distinct.
 */
class ExpressionUsageUnusedLocalTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // Positive unused emission
    // -------------------------------------------------------------------------

    @Test
    fun unusedSimpleLocalEmitsUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local unused = 1
            return 0
            """.trimIndent()
        )

        assertUnusedLocals(model, "unused")
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
    fun assignmentWriteAloneStillEmitsUnusedLocalDiagnostic() {
        // Writes alone do not count as a use.
        val model = analyze(
            """
            local written = 0
            written = 1
            return 0
            """.trimIndent()
        )

        assertUnusedLocals(model, "written")
    }

    // -------------------------------------------------------------------------
    // Multiple locals / partial use
    // -------------------------------------------------------------------------

    @Test
    fun multipleUnusedLocalsEmitOneDiagnosticEach() {
        val model = analyze(
            """
            local first = 1
            local second = 2
            local third = 3
            return 0
            """.trimIndent()
        )

        assertUnusedLocals(model, "first", "second", "third")
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

        assertUnusedLocals(model, "drop")
    }

    @Test
    fun repeatedAnalysisYieldsIdenticalUnusedLocalDiagnostics() {
        val source =
            """
            local a = 1
            local b = 2
            return a
            """.trimIndent()

        val first = unusedLocalDiagnostics(analyze(source))
        val second = unusedLocalDiagnostics(analyze(source))

        assertEquals(listOf("b"), first.mapNotNull { mentionedName(it) })
        assertEquals(first.map { diagnosticFingerprint(it) }, second.map { diagnosticFingerprint(it) })
    }

    // -------------------------------------------------------------------------
    // Nested scopes / multi-name declarations
    // -------------------------------------------------------------------------

    @Test
    fun nestedBlockUnusedLocalEmitsUnusedLocalDiagnostic() {
        val model = analyze(
            """
            do
                local hidden = 1
            end
            return 0
            """.trimIndent()
        )

        assertUnusedLocals(model, "hidden")
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

        val unused = unusedLocalDiagnostics(model)
        assertEquals(1, unused.size)
        assertEquals("value", mentionedName(unused.single()))
        assertUnusedLocalShape(unused.single(), "value")
    }

    @Test
    fun multiNameLocalStatementReportsAllUnusedNames() {
        val model = analyze(
            """
            local left, right = 1, 2
            return 0
            """.trimIndent()
        )

        assertUnusedLocals(model, "left", "right")
    }

    @Test
    fun multiNameLocalWithPartialUseReportsOnlyUnusedSibling() {
        val model = analyze(
            """
            local left, right = 1, 2
            return left
            """.trimIndent()
        )

        assertUnusedLocals(model, "right")
    }

    @Test
    fun unusedLocalFunctionValueEmitsUnusedLocalDiagnostic() {
        val model = analyze(
            """
            local helper = function()
                return 1
            end
            return 0
            """.trimIndent()
        )

        assertUnusedLocals(model, "helper")
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
    // Table field / member selector surfaces (not value reads)
    // -------------------------------------------------------------------------

    @Test
    fun tableFieldNameDoesNotCountAsLocalRead() {
        val model = analyze(
            """
            local name = 1
            local t = { name = 2 }
            return t
            """.trimIndent()
        )

        assertUnusedLocals(model, "name")
    }

    @Test
    fun tableFieldValueReadCountsAsLocalUse() {
        val model = analyze(
            """
            local name = 1
            local t = { field = name }
            return t
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    @Test
    fun memberSelectorOnAssignmentLhsDoesNotCountAsLocalReadOfFieldName() {
        val model = analyze(
            """
            local x = 1
            local t = {}
            t.x = 2
            return t
            """.trimIndent()
        )

        assertUnusedLocals(model, "x")
    }

    // -------------------------------------------------------------------------
    // Underscore-style ignore policy
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
    fun nonUnderscoreSiblingIsReportedWhileUnderscoreIsSuppressed() {
        val model = analyze(
            """
            local _skip = 1
            local reportMe = 2
            return 0
            """.trimIndent()
        )

        assertUnusedLocals(model, "reportMe")
        assertTrue(unusedLocalDiagnostics(model).none { mentionedName(it)?.startsWith("_") == true })
    }

    @Test
    fun multiNameWithUnderscoreReportsOnlyNonUnderscoreUnused() {
        val model = analyze(
            """
            local keep, _drop, alsoDrop = 1, 2, 3
            return keep
            """.trimIndent()
        )

        assertUnusedLocals(model, "alsoDrop")
    }

    @Test
    fun doubleUnderscoreSuppressedAndMidUnderscoreReported() {
        // Leading `_` ignored; mid_name reported.
        val model = analyze(
            """
            local __dunder = 1
            local mid_name = 2
            return 0
            """.trimIndent()
        )

        assertUnusedLocals(model, "mid_name")
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
        assertNoUnusedLocalDiagnostics(model)
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

    @Test
    fun unusedForGenericLoopControlVariablesDoNotEmitUnusedLocalDiagnostics() {
        val model = analyze(
            """
            local t = { 1, 2 }
            for k, v in pairs(t) do
            end
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
    }

    // -------------------------------------------------------------------------
    // Diagnostic surface shape / stability
    // -------------------------------------------------------------------------

    @Test
    fun unusedLocalDiagnosticHasStableCodeMessageAndSeverity() {
        val model = analyze(
            """
            local only = true
            return false
            """.trimIndent()
        )

        val diagnostics = unusedLocalDiagnostics(model)
        assertEquals(1, diagnostics.size)
        assertUnusedLocalShape(diagnostics.single(), "only")
    }

    @Test
    fun plainLocalProgramEmitsRangeOnDeclarationName() {
        val model = analyze(
            """
            local target = 123
            return 0
            """.trimIndent()
        )

        val diagnostic = unusedLocalDiagnostics(model).single()
        assertUnusedLocalShape(diagnostic, "target")
        assertTrue(diagnostic.range != null, "unused-local diagnostic must carry a declaration range")
    }

    @Test
    fun pureWritesProduceSingleUnusedLocalDiagnosticWithoutDuplicates() {
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
        assertUnusedLocalShape(diagnostics.single(), "once")
    }

    @Test
    fun usedLocalsWithRhsWriteDoNotEmitUnusedLocal() {
        // `b = b` reads b on the RHS; `return a` reads a.
        val model = analyze(
            """
            local a, b = 1, 2
            b = b
            return a
            """.trimIndent()
        )

        assertNoUnusedLocalDiagnostics(model)
        val codes = model.getDiagnostics().mapNotNull { it.code }.toSet()
        assertFalse(
            codes.any { it != UNUSED_LOCAL_CODE && it.contains("unused", ignoreCase = true) },
            "no alternate unused-local aliases; codes=$codes"
        )
    }

    @Test
    fun memberAndLuajavaCodesRemainDistinctFromUnusedLocal() {
        // Plain Lua fixture: no Java surface → no member/luajava codes; unused local still emits.
        val model = analyze(
            """
            local alone = 1
            return 0
            """.trimIndent()
        )

        val codes = model.getDiagnostics().mapNotNull { it.code }.toSet()
        assertTrue(UNUSED_LOCAL_CODE in codes)
        assertFalse(MEMBER_MISSING_CODE in codes)
        assertFalse(LUAJAVA_TARGET_UNRESOLVED_CODE in codes)
        assertEquals(setOf(UNUSED_LOCAL_CODE), codes.filter { it.startsWith("checker.local.") }.toSet())
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
            "expected no checker.local.unused emission; " +
                "got: ${diagnostics.map { "${it.code}:${it.message}@${it.range}" }}"
        )
    }

    private fun assertUnusedLocals(model: SemanticModel, vararg names: String) {
        val diagnostics = unusedLocalDiagnostics(model)
        val actualNames = diagnostics.mapNotNull { mentionedName(it) }
        assertEquals(
            names.toList(),
            actualNames,
            "unused-local names mismatch; diagnostics=${diagnostics.map { it.message }}"
        )
        diagnostics.forEachIndexed { index, diagnostic ->
            assertUnusedLocalShape(diagnostic, names[index])
        }
    }

    private fun assertUnusedLocalShape(diagnostic: Diagnostic, name: String) {
        assertEquals(UNUSED_LOCAL_CODE, diagnostic.code)
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertEquals("Unused local '$name'.", diagnostic.message)
    }

    private fun mentionedName(diagnostic: Diagnostic): String? {
        val match = UNUSED_LOCAL_NAME_REGEX.find(diagnostic.message) ?: return null
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
        const val MEMBER_MISSING_CODE = "checker.member.missing"
        const val LUAJAVA_TARGET_UNRESOLVED_CODE = "checker.luajava.target.unresolved"
        private val UNUSED_LOCAL_NAME_REGEX = Regex("""Unused local '([^']+)'\.""")
    }
}
