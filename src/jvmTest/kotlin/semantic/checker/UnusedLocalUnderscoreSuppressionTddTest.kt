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
 * TASK-293 corpus: unused-local underscore suppression rules.
 *
 * Documents the product policy for unused-local diagnostics
 * (`checker.local.unused`) around intentionally discarded names:
 *
 * 1. **`_`** (single underscore) is always suppressed — never reported as unused.
 * 2. **`_name`** (any name with a leading underscore) is always suppressed.
 * 3. **Non-underscore** unused locals remain in scope for reporting **when**
 *    unused-local emission is enabled in the checker.
 *
 * Current product surface (ExpressionUsageChecker / CheckerPass as of TASK-202 /
 * TASK-293): unused-local diagnostics are **not** emitted at all. Therefore the
 * non-underscore positive path is dual-mode:
 * - empty set under current non-emission (green today)
 * - if/when emission lands, only non-underscore names may appear, with stable
 *   code `checker.local.unused`, message `Unused local '<name>'.`, WARNING.
 *
 * Complements [ExpressionUsageUnusedLocalTddTest] by focusing solely on the
 * underscore ignore contract rather than general unused-local presence/absence.
 *
 * Test-only; no production edits. Review-owned serial verification:
 * `jvmTest --tests semantic.checker.UnusedLocalUnderscoreSuppressionTddTest`.
 */
class UnusedLocalUnderscoreSuppressionTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // `_` single-underscore suppression
    // -------------------------------------------------------------------------

    @Test
    fun singleUnderscoreLocalIsNeverReportedAsUnused() {
        val model = analyze(
            """
            local _ = 1
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "_")
    }

    @Test
    fun singleUnderscoreAssignedFromCallIsNeverReportedAsUnused() {
        // Common discard pattern: local _ = f()
        val model = analyze(
            """
            local function f()
                return 1, 2
            end
            local _ = f()
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "_")
    }

    @Test
    fun singleUnderscoreInMultiNameLocalIsNeverReportedAsUnused() {
        val model = analyze(
            """
            local keep, _ = 1, 2
            return keep
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "_")
        // keep is used — must not appear either
        assertNoUnusedLocalNamed(model, "keep")
    }

    @Test
    fun forNumericLoopSingleUnderscoreControlIsNeverReportedAsUnused() {
        val model = analyze(
            """
            for _ = 1, 3 do
            end
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "_")
    }

    @Test
    fun forGenericLoopSingleUnderscoreNameIsNeverReportedAsUnused() {
        val model = analyze(
            """
            local t = { 1, 2 }
            for _, v in ipairs(t) do
                return v
            end
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "_")
    }

    // -------------------------------------------------------------------------
    // `_name` leading-underscore suppression
    // -------------------------------------------------------------------------

    @Test
    fun leadingUnderscoreNameIsNeverReportedAsUnused() {
        val model = analyze(
            """
            local _ignored = 1
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "_ignored")
    }

    @Test
    fun multipleLeadingUnderscoreNamesAreAllSuppressed() {
        val model = analyze(
            """
            local _a = 1
            local _b = 2
            local _also_unused = 3
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "_a")
        assertNoUnusedLocalNamed(model, "_b")
        assertNoUnusedLocalNamed(model, "_also_unused")
        assertTrue(
            unusedLocalDiagnostics(model).none { diagnostic ->
                mentionedName(diagnostic)?.startsWith("_") == true
            },
            "no leading-underscore name may appear in unused-local diagnostics; " +
                "got=${unusedLocalDiagnostics(model).map { it.message }}"
        )
    }

    @Test
    fun doubleLeadingUnderscoreNameIsSuppressed() {
        // `__dunder` still starts with `_` → suppressed under the leading-`_` rule
        val model = analyze(
            """
            local __dunder = 1
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "__dunder")
    }

    @Test
    fun leadingUnderscoreInMultiNameLocalIsSuppressed() {
        val model = analyze(
            """
            local keep, _drop, also = 1, 2, 3
            return keep
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "_drop")
    }

    @Test
    fun leadingUnderscoreParameterStyleLocalIsSuppressed() {
        // Function-local discard names (not formal params — those are out of scope
        // for unused-local reporting per ExpressionUsageUnusedLocalTddTest).
        val model = analyze(
            """
            local function work()
                local _scratch = 0
                return 1
            end
            return work()
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "_scratch")
    }

    // -------------------------------------------------------------------------
    // Mid-underscore / non-leading underscore is NOT a suppression prefix
    // -------------------------------------------------------------------------

    @Test
    fun midUnderscoreNameIsNotCoveredByLeadingUnderscoreSuppression() {
        // `mid_name` does not start with `_` → not suppressed by the `_` / `_name`
        // rule. Under current non-emission the set is empty; if emission is enabled
        // the name must be eligible for reporting (see non-underscore section).
        val model = analyze(
            """
            local mid_name = 2
            return 0
            """.trimIndent()
        )

        val unused = unusedLocalDiagnostics(model)
        assertTrue(
            unused.none { mentionedName(it) == "_" || mentionedName(it)?.startsWith("_") == true },
            "suppressed underscore forms must stay absent; got=${unused.map { it.message }}"
        )
        if (unused.isNotEmpty()) {
            assertTrue(
                unused.any { mentionedName(it) == "mid_name" },
                "when unused-local is enabled, mid_name (no leading _) must be eligible; " +
                    "got=${unused.map { it.message }}"
            )
            unused.filter { mentionedName(it) == "mid_name" }.forEach { assertUnusedLocalShape(it, "mid_name") }
        }
    }

    // -------------------------------------------------------------------------
    // Non-underscore unused still flagged when enabled (dual-mode)
    // -------------------------------------------------------------------------

    @Test
    fun nonUnderscoreUnusedIsEligibleWhenUnusedLocalEnabled() {
        val model = analyze(
            """
            local reportMe = 2
            return 0
            """.trimIndent()
        )

        val unused = unusedLocalDiagnostics(model)
        // Always: no false underscore hits on this program
        assertTrue(unused.none { mentionedName(it)?.let { n -> n == "_" || n.startsWith("_") } == true })

        if (unused.isEmpty()) {
            // Current product: unused-local emission disabled — empty is correct.
            assertEquals(emptyList(), unused)
        } else {
            // Future / enabled: plain unused local must be flagged with stable shape.
            assertTrue(
                unused.any { mentionedName(it) == "reportMe" },
                "when enabled, unused non-underscore local 'reportMe' must be flagged; " +
                    "got=${unused.map { it.message }}"
            )
            unused.filter { mentionedName(it) == "reportMe" }.forEach { assertUnusedLocalShape(it, "reportMe") }
        }
    }

    @Test
    fun mixedUnderscoreAndPlainUnused_onlyPlainMayBeFlaggedWhenEnabled() {
        val model = analyze(
            """
            local _skip = 1
            local reportMe = 2
            local _also = 3
            return 0
            """.trimIndent()
        )

        val unused = unusedLocalDiagnostics(model)

        // Hard contract (both current and future): underscore forms never reported.
        assertNoUnusedLocalNamed(model, "_skip")
        assertNoUnusedLocalNamed(model, "_also")
        assertTrue(
            unused.none { mentionedName(it)?.startsWith("_") == true },
            "leading-underscore names must stay suppressed even when plain unused exists; " +
                "got=${unused.map { it.message }}"
        )

        if (unused.isEmpty()) {
            // Current non-emission policy.
            assertEquals(emptyList(), unused)
        } else {
            // Enabled: only the non-underscore unused name may appear.
            assertTrue(
                unused.any { mentionedName(it) == "reportMe" },
                "when enabled, only non-underscore 'reportMe' should be flagged among " +
                    "{_skip, reportMe, _also}; got=${unused.map { it.message }}"
            )
            assertEquals(
                setOf("reportMe"),
                unused.mapNotNull { mentionedName(it) }.toSet(),
                "enabled emission must not invent extra unused names beyond plain unused locals"
            )
            unused.forEach { assertUnusedLocalShape(it, "reportMe") }
        }
    }

    @Test
    fun multiNamePartialUse_underscoreSuppressedAndPlainSiblingEligibleWhenEnabled() {
        val model = analyze(
            """
            local used, _drop, alsoDrop = 1, 2, 3
            return used
            """.trimIndent()
        )

        val unused = unusedLocalDiagnostics(model)

        assertNoUnusedLocalNamed(model, "used")
        assertNoUnusedLocalNamed(model, "_drop")

        if (unused.isEmpty()) {
            assertEquals(emptyList(), unused)
        } else {
            assertTrue(
                unused.any { mentionedName(it) == "alsoDrop" },
                "when enabled, unused non-underscore 'alsoDrop' must be flagged; " +
                    "got=${unused.map { it.message }}"
            )
            assertTrue(
                unused.none { mentionedName(it) == "_drop" || mentionedName(it) == "used" },
                "used and leading-underscore names must not appear; got=${unused.map { it.message }}"
            )
            unused.filter { mentionedName(it) == "alsoDrop" }.forEach {
                assertUnusedLocalShape(it, "alsoDrop")
            }
        }
    }

    @Test
    fun usedNonUnderscoreLocalIsNeverFlaggedEvenWhenEnabled() {
        val model = analyze(
            """
            local keep = 1
            local _ignored = 2
            return keep
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "keep")
        assertNoUnusedLocalNamed(model, "_ignored")
        // No unused-local diagnostics expected at all for this program under either policy.
        assertEquals(
            emptyList(),
            unusedLocalDiagnostics(model).filter {
                mentionedName(it) == "keep" || mentionedName(it) == "_ignored"
            }
        )
    }

    @Test
    fun forLoopPlainControlEligibleWhenEnabled_underscoreControlAlwaysSuppressed() {
        val model = analyze(
            """
            for i = 1, 3 do
            end
            for _ = 1, 3 do
            end
            return 0
            """.trimIndent()
        )

        assertNoUnusedLocalNamed(model, "_")

        val unused = unusedLocalDiagnostics(model)
        if (unused.isNotEmpty()) {
            // If loop controls enter unused-local reporting, `_` stays out; `i` may appear.
            assertTrue(unused.none { mentionedName(it) == "_" })
            unused.filter { mentionedName(it) == "i" }.forEach { assertUnusedLocalShape(it, "i") }
        }
    }

    // -------------------------------------------------------------------------
    // Stability / no fabricated underscore diagnostics
    // -------------------------------------------------------------------------

    @Test
    fun underscoreOnlyProgramNeverEmitsUnusedLocalCode() {
        val model = analyze(
            """
            local _ = 0
            local _tmp = 1
            local __x = 2
            return 0
            """.trimIndent()
        )

        assertEquals(emptyList(), unusedLocalDiagnostics(model))
        assertFalse(model.getDiagnostics().any { it.code == UNUSED_LOCAL_CODE })
    }

    @Test
    fun repeatedAnalysisKeepsUnderscoreSuppressionStable() {
        val source =
            """
            local _a = 1
            local plain = 2
            return 0
            """.trimIndent()

        val first = unusedLocalDiagnostics(analyze(source))
        val second = unusedLocalDiagnostics(analyze(source))

        assertTrue(first.none { mentionedName(it)?.startsWith("_") == true })
        assertTrue(second.none { mentionedName(it)?.startsWith("_") == true })
        assertEquals(
            first.map { diagnosticFingerprint(it) },
            second.map { diagnosticFingerprint(it) }
        )
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

    private fun assertNoUnusedLocalNamed(model: SemanticModel, name: String) {
        val hits = unusedLocalDiagnostics(model).filter { mentionedName(it) == name }
        assertTrue(
            hits.isEmpty(),
            "unused-local diagnostic for '$name' must be suppressed; " +
                "got=${hits.map { "${it.code}:${it.message}@${it.range}" }}"
        )
        assertFalse(
            model.getDiagnostics().any {
                it.code == UNUSED_LOCAL_CODE && it.message.contains("'$name'")
            },
            "message surface must not mention unused local '$name'"
        )
    }

    private fun assertUnusedLocalShape(diagnostic: Diagnostic, name: String) {
        assertEquals(UNUSED_LOCAL_CODE, diagnostic.code)
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertEquals(UNUSED_LOCAL_MESSAGE_PREFIX + "'$name'.", diagnostic.message)
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
        /** Stable code reserved for unused-local diagnostics. */
        const val UNUSED_LOCAL_CODE = "checker.local.unused"

        /** Documented message prefix when emission is enabled. */
        const val UNUSED_LOCAL_MESSAGE_PREFIX = "Unused local "

        private val UNUSED_LOCAL_NAME_REGEX = Regex("""Unused local '([^']+)'\.""")
    }
}
