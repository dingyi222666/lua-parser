package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel

import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-334 corpus: writes to readonly/builtin globals diagnostic policy.
 *
 * Product snapshot (DiagnosticCodeStabilityTddTest / CheckerPass):
 * - No dedicated `checker.global.readonly` / `checker.global.undefined` codes yet.
 * - Plain writes to builtins (e.g. `math = 1`, `_G = {}`) must remain silent
 *   under current policy (no invented free-form diagnostics).
 * - When a future readonly policy lands, codes must be stable strings; this corpus
 *   locks the *current* silence + reserved code names.
 *
 * Test-only. Verification is review-owned (no Gradle).
 */
class GlobalWriteReadonlyDiagnosticTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    @Test
    fun writeToBuiltinMathGlobalDoesNotEmitReadonlyCodeToday() {
        val model = analyze("math = 1")
        assertNoCode(model, READONLY_GLOBAL_CODE)
        assertFalse(codesOf(model).any { it.startsWith("checker.global.") })
    }

    @Test
    fun writeToBuiltinTypeGlobalDoesNotEmitReadonlyCodeToday() {
        val model = analyze("type = function() end")
        assertNoCode(model, READONLY_GLOBAL_CODE)
    }

    @Test
    fun writeToUnderscoreGDoesNotEmitReadonlyCodeToday() {
        val model = analyze("_G = {}")
        assertNoCode(model, READONLY_GLOBAL_CODE)
    }

    @Test
    fun writeToUserGlobalRemainsSilentWithoutPolicy() {
        val model = analyze("myGlobal = 42")
        assertNoCode(model, READONLY_GLOBAL_CODE)
        assertNoCode(model, UNDEFINED_GLOBAL_CODE)
        assertFalse(codesOf(model).any { it.startsWith("checker.global.") })
    }

    @Test
    fun memberWriteOnBuiltinTableDoesNotInventReadonlyDiagnostic() {
        val model = analyze("math.abs = 1")
        assertNoCode(model, READONLY_GLOBAL_CODE)
        // May emit member.missing depending on analysis surface; must not invent global.readonly.
        assertFalse(READONLY_GLOBAL_CODE in codesOf(model))
    }

    @Test
    fun allowedLocalShadowOfBuiltinRemainsSilent() {
        val model = analyze(
            """
            local math = { abs = function(x) return x end }
            math.abs = function(x) return -x end
            return math.abs(1)
            """.trimIndent()
        )
        assertNoCode(model, READONLY_GLOBAL_CODE)
    }

    @Test
    fun reservedReadonlyCodeStringIsLockedForFuturePolicy() {
        // Freeze the intended stable code string so renames require explicit updates.
        assertTrue(READONLY_GLOBAL_CODE == "checker.global.readonly")
        assertTrue(UNDEFINED_GLOBAL_CODE == "checker.global.undefined")
    }

    @Test
    fun repeatedAnalyzeOfBuiltinWriteIsDiagnosticStable() {
        val source = "print = nil"
        val first = codesOf(analyze(source)).sorted()
        val second = codesOf(analyze(source)).sorted()
        assertTrue(first == second)
        assertFalse(READONLY_GLOBAL_CODE in first)
    }

    private fun analyze(source: String): SemanticModel =
        pipeline.analyze(parser.parse(source)).model

    private fun codesOf(model: SemanticModel): Set<String> =
        model.getDiagnostics().mapNotNull { it.code }.toSet()

    private fun assertNoCode(model: SemanticModel, code: String) {
        assertFalse(
            code in codesOf(model),
            "expected no $code; got=${codesOf(model)} messages=${model.getDiagnostics().map { it.message }}"
        )
    }

    private companion object {
        const val READONLY_GLOBAL_CODE = "checker.global.readonly"
        const val UNDEFINED_GLOBAL_CODE = "checker.global.undefined"
    }
}
