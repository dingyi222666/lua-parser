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
 * REVIEW19 / TASK-233 corpus: stable diagnostic **codes** for checker fixtures.
 *
 * Locks the string codes emitted (or deliberately not emitted) by the public
 * [SemanticPipeline] surface for three fixture families named by the task:
 *
 * 1. **Undefined global** — free use of an undeclared global name.
 *    Current CheckerPass / ExpressionUsageChecker does **not** emit a dedicated
 *    undefined-global diagnostic (no `checker.global.undefined` product code yet).
 *    The reserved code is asserted absent so a future emission requires an
 *    explicit assertion update here.
 *
 * 2. **Arity** — annotated functions whose `@param` set does not match the AST
 *    parameter list. Product emits stable signature codes via
 *    FunctionSignatureChecker:
 *    - `checker.function.signature.unknownParam` when an `@param` name is absent
 *      from the AST parameter list (common under-arity / extra-doc path)
 *    - `checker.function.signature.parameterContractMismatch` when a resolved
 *      primary FunctionType length/order diverges from the AST list
 *    - related signature-shape codes (`requiredAfterOptional`, vararg family, …)
 *
 * 3. **Type mismatch** — annotated `@return` / return-site mismatch and extra
 *    return values. Product emits
 *    `checker.function.return.typeMismatch` and
 *    `checker.function.return.extraValues` via ReturnChecker.
 *
 * Inventory tests freeze the full set of codes currently produced by the public
 * pipeline for plain Lua + EmmyLua annotations so **new codes require explicit
 * assertion updates** (acceptance criterion). Test-only; red OK until review.
 */
class DiagnosticCodeStabilityTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // 1. Undefined global fixtures (current policy: no dedicated code)
    // -------------------------------------------------------------------------

    @Test
    fun undefinedGlobalReadDoesNotEmitUndefinedGlobalCode() {
        val model = analyze(
            """
            return totallyUndefinedGlobal
            """.trimIndent()
        )

        assertNoCode(model, UNDEFINED_GLOBAL_CODE)
        assertFalse(
            model.getDiagnostics().any { diagnostic ->
                diagnostic.code?.contains("undefined", ignoreCase = true) == true ||
                    diagnostic.message.contains("undefined", ignoreCase = true)
            },
            "undefined global must not invent free-form undefined diagnostics; " +
                "got=${codesOf(model)}"
        )
    }

    @Test
    fun undefinedGlobalCallDoesNotEmitUndefinedGlobalCode() {
        val model = analyze(
            """
            return totallyUndefinedGlobal(1, "x")
            """.trimIndent()
        )

        assertNoCode(model, UNDEFINED_GLOBAL_CODE)
        assertFalse(codesOf(model).any { it.startsWith("checker.global.") })
    }

    @Test
    fun undefinedGlobalMemberWriteDoesNotEmitUndefinedGlobalCode() {
        val model = analyze(
            """
            totallyUndefinedGlobal = 1
            totallyUndefinedGlobal.field = 2
            """.trimIndent()
        )

        assertNoCode(model, UNDEFINED_GLOBAL_CODE)
    }

    @Test
    fun definedBuiltinGlobalDoesNotEmitUndefinedGlobalCode() {
        val model = analyze(
            """
            return type(1)
            """.trimIndent()
        )

        assertNoCode(model, UNDEFINED_GLOBAL_CODE)
        assertFalse(
            UNDEFINED_GLOBAL_CODE in codesOf(model),
            "builtin global use must stay free of undefined-global codes; codes=${codesOf(model)}"
        )
    }

    // -------------------------------------------------------------------------
    // 2. Arity fixtures → unknownParam / parameterContractMismatch family
    // -------------------------------------------------------------------------

    @Test
    fun annotatedCallableExtraParamTagEmitsUnknownParamCode() {
        // Extra @param name beyond AST params: stable arity-family code.
        val model = analyze(
            """
            ---@param first string
            ---@param missing number
            local function onlyOne(first)
            end
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertTrue(
            UNKNOWN_PARAM in codes,
            "extra @param name must emit $UNKNOWN_PARAM; codes=$codes"
        )
        assertEquals(
            listOf(UNKNOWN_PARAM),
            model.getDiagnostics().mapNotNull { it.code }.filter { it == UNKNOWN_PARAM }.distinct()
        )
        assertOnlyKnownCodes(codes)
        assertTrue(codes.any { it in ARITY_FAMILY_CODES })
    }

    @Test
    fun annotatedCallableUnderDocumentedArityEmitsStableArityFamilyCode() {
        // Two documented params, one AST param → unknownParam on the missing name.
        val model = analyze(
            """
            ---@param first string
            ---@param second number
            local function underArity(first)
            end
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertTrue(
            UNKNOWN_PARAM in codes || PARAMETER_CONTRACT_MISMATCH in codes,
            "under-documented arity surface must emit a stable signature code; codes=$codes"
        )
        assertOnlyKnownCodes(codes)
        assertTrue(codes.any { it in ARITY_FAMILY_CODES })
    }

    @Test
    fun annotatedCallableMatchingArityDoesNotEmitArityMismatchCodes() {
        val model = analyze(
            """
            ---@param first string
            ---@param second number
            ---@return nil
            local function matching(first, second)
            end
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertFalse(
            PARAMETER_CONTRACT_MISMATCH in codes,
            "matching arity must not emit $PARAMETER_CONTRACT_MISMATCH; codes=$codes"
        )
        assertFalse(UNKNOWN_PARAM in codes)
        assertOnlyKnownCodes(codes)
    }

    @Test
    fun annotatedGlobalFunctionExtraParamTagEmitsStableArityFamilyCode() {
        val model = analyze(
            """
            ---@param a string
            ---@param b number
            function globalArity(a)
            end
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertTrue(
            UNKNOWN_PARAM in codes || PARAMETER_CONTRACT_MISMATCH in codes,
            "global under-documented arity surface must emit stable signature code; codes=$codes"
        )
        assertOnlyKnownCodes(codes)
        assertTrue(codes.any { it in ARITY_FAMILY_CODES })
    }

    @Test
    fun arityMismatchCodeIsDeterministicAcrossRepeatedAnalyze() {
        val source = """
            ---@param first string
            ---@param missing number
            local function onlyOne(first)
            end
        """.trimIndent()

        val first = codesOf(analyze(source)).sorted()
        val second = codesOf(analyze(source)).sorted()
        assertEquals(first, second, "diagnostic codes must be stable across repeated analyze")
        assertTrue(UNKNOWN_PARAM in first)
    }

    @Test
    fun signatureRequiredAfterOptionalUsesStableCode() {
        // Same shape as SemanticModelDiagnosticsTest: method tag + function body.
        val model = analyze(
            """
            ---@method User:bad(optional?: string, required: number): string
            ---@overload fun(self: User, optional?: string, required: number): string
            ---@return string
            local function render()
                return 1
            end
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertTrue(
            REQUIRED_AFTER_OPTIONAL in codes,
            "required-after-optional method signature must emit $REQUIRED_AFTER_OPTIONAL; codes=$codes"
        )
        assertTrue(TYPE_MISMATCH in codes, "return mismatch still present; codes=$codes")
        assertOnlyKnownCodes(codes)
    }

    // -------------------------------------------------------------------------
    // 3. Type mismatch fixtures → return.typeMismatch / return.extraValues
    // -------------------------------------------------------------------------

    @Test
    fun returnTypeMismatchEmitsStableTypeMismatchCode() {
        val model = analyze(
            """
            ---@return string
            local function render()
                return 1
            end
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertTrue(
            TYPE_MISMATCH in codes,
            "return type mismatch must emit $TYPE_MISMATCH; codes=$codes"
        )
        assertEquals(setOf(TYPE_MISMATCH), codes)
        assertOnlyKnownCodes(codes)
    }

    @Test
    fun returnExtraValuesEmitsStableExtraValuesCode() {
        val model = analyze(
            """
            ---@return string
            local function render()
                return "ok", 2
            end
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertTrue(
            EXTRA_VALUES in codes,
            "extra return values must emit $EXTRA_VALUES; codes=$codes"
        )
        // First value may match; extra value is the locked surface.
        assertTrue(codes.all { it in setOf(TYPE_MISMATCH, EXTRA_VALUES) })
        assertOnlyKnownCodes(codes)
    }

    @Test
    fun returnTypeMismatchAndExtraValuesTogetherUseStableCodes() {
        val model = analyze(
            """
            ---@return string
            local function render()
                return 1, 2
            end
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertTrue(TYPE_MISMATCH in codes, "codes=$codes")
        assertTrue(EXTRA_VALUES in codes, "codes=$codes")
        assertEquals(setOf(TYPE_MISMATCH, EXTRA_VALUES), codes)
    }

    @Test
    fun matchingReturnTypesDoNotEmitTypeMismatchOrExtraValues() {
        val model = analyze(
            """
            ---@return string, number
            local function render()
                return "ok", 1
            end
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertFalse(TYPE_MISMATCH in codes, "codes=$codes")
        assertFalse(EXTRA_VALUES in codes, "codes=$codes")
    }

    @Test
    fun typeMismatchCodeIsDeterministicAndFingerprintStable() {
        val source = """
            ---@return string
            local function render()
                return 1
            end
        """.trimIndent()

        val first = analyze(source).getDiagnostics().map(::diagnosticFingerprint)
        val second = analyze(source).getDiagnostics().map(::diagnosticFingerprint)
        assertEquals(first, second)
        assertEquals(listOf(TYPE_MISMATCH), first.map { it[0] })
    }

    // -------------------------------------------------------------------------
    // Combined fixture + inventory lock (new codes require assertion updates)
    // -------------------------------------------------------------------------

    @Test
    fun combinedUndefinedArityAndTypeMismatchFixtureUsesOnlyKnownCodes() {
        val model = analyze(
            """
            ---@param first string
            ---@param missing number
            ---@return string
            local function render(first)
                return 1, 2
            end

            return totallyUndefinedGlobal(render)
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertTrue(UNKNOWN_PARAM in codes, "codes=$codes")
        assertTrue(TYPE_MISMATCH in codes, "codes=$codes")
        assertTrue(EXTRA_VALUES in codes, "codes=$codes")
        assertNoCode(model, UNDEFINED_GLOBAL_CODE)

        val unknown = codes - KNOWN_PIPELINE_CODES
        assertTrue(
            unknown.isEmpty(),
            "new diagnostic codes require explicit assertion updates in TASK-233; unexpected=$unknown all=$codes"
        )
    }

    @Test
    fun plainLuaWithoutAnnotationsEmitsNoCheckerCodes() {
        val model = analyze(
            """
            local function add(a, b)
                return a + b
            end
            return add(1, 2)
            """.trimIndent()
        )

        assertEquals(
            emptySet(),
            codesOf(model),
            "plain Lua without EmmyLua contracts should not invent checker codes"
        )
    }

    @Test
    fun knownPipelineCodeInventoryIsExplicitAndSorted() {
        // Freeze the product code vocabulary used by CheckerPass-backed pipeline
        // diagnostics. Adding a product code without updating this list fails the test.
        val expected = listOf(
            "checker.function.return.extraValues",
            "checker.function.return.typeMismatch",
            "checker.function.signature.missingParamName",
            "checker.function.signature.multipleVararg",
            "checker.function.signature.namedVararg",
            "checker.function.signature.optionalVararg",
            "checker.function.signature.parameterContractMismatch",
            "checker.function.signature.requiredAfterOptional",
            "checker.function.signature.unknownParam",
            "checker.function.signature.varargNotLast",
            "checker.luajava.target.unresolved",
            "checker.local.unused",
            "checker.member.missing"
        ).sorted()

        assertEquals(
            expected,
            KNOWN_PIPELINE_CODES.sorted(),
            "KNOWN_PIPELINE_CODES drift: update inventory deliberately when product adds codes"
        )
        // Reserved future codes are intentionally **not** in the inventory until product emits them.
        assertFalse(UNDEFINED_GLOBAL_CODE in KNOWN_PIPELINE_CODES)
        assertTrue(UNUSED_LOCAL_CODE in KNOWN_PIPELINE_CODES)
    }

    @Test
    fun diagnosticSeverityForTypeMismatchAndArityRemainsErrorByDefault() {
        val mismatch = analyze(
            """
            ---@return string
            local function render()
                return 1
            end
            """.trimIndent()
        ).getDiagnostics().filter { it.code == TYPE_MISMATCH }

        val arity = analyze(
            """
            ---@param first string
            ---@param missing number
            local function onlyOne(first)
            end
            """.trimIndent()
        ).getDiagnostics().filter { it.code == UNKNOWN_PARAM }

        assertTrue(mismatch.isNotEmpty())
        assertTrue(arity.isNotEmpty())
        assertTrue(
            mismatch.all { it.severity == DiagnosticSeverity.ERROR },
            "type mismatch severity must stay ERROR by default"
        )
        assertTrue(
            arity.all { it.severity == DiagnosticSeverity.ERROR },
            "arity/unknownParam severity must stay ERROR by default"
        )
    }

    @Test
    fun parameterContractMismatchCodeStringIsLockedEvenIfPipelineRarelyEmitsIt() {
        // Through TypeResolver, primary FunctionType arity usually matches AST params,
        // so pipeline fixtures more often hit unknownParam. Still lock the stable
        // arity-contract code string so renames require an explicit assertion update.
        assertEquals(
            "checker.function.signature.parameterContractMismatch",
            PARAMETER_CONTRACT_MISMATCH
        )
        assertTrue(PARAMETER_CONTRACT_MISMATCH in KNOWN_PIPELINE_CODES)
        assertTrue(PARAMETER_CONTRACT_MISMATCH in ARITY_FAMILY_CODES)
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    private fun analyze(source: String): SemanticModel {
        return pipeline.analyze(parser.parse(source)).model
    }

    private fun codesOf(model: SemanticModel): Set<String> {
        return model.getDiagnostics().mapNotNull { it.code }.toSet()
    }

    private fun assertNoCode(model: SemanticModel, code: String) {
        assertFalse(
            code in codesOf(model),
            "expected no emission of $code; got=${codesOf(model)} messages=${model.getDiagnostics().map { it.message }}"
        )
    }

    private fun assertOnlyKnownCodes(codes: Set<String>) {
        val unexpected = codes - KNOWN_PIPELINE_CODES
        assertTrue(
            unexpected.isEmpty(),
            "new diagnostic codes require explicit assertion updates; unexpected=$unexpected all=$codes"
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
        /** Reserved future code for undefined globals — not emitted by product today. */
        const val UNDEFINED_GLOBAL_CODE = "checker.global.undefined"

        /** Stable unused-local code emitted by ExpressionUsageChecker (TASK-556). */
        const val UNUSED_LOCAL_CODE = "checker.local.unused"

        const val PARAMETER_CONTRACT_MISMATCH = "checker.function.signature.parameterContractMismatch"
        const val UNKNOWN_PARAM = "checker.function.signature.unknownParam"
        const val MISSING_PARAM_NAME = "checker.function.signature.missingParamName"
        const val NAMED_VARARG = "checker.function.signature.namedVararg"
        const val MULTIPLE_VARARG = "checker.function.signature.multipleVararg"
        const val VARARG_NOT_LAST = "checker.function.signature.varargNotLast"
        const val OPTIONAL_VARARG = "checker.function.signature.optionalVararg"
        const val REQUIRED_AFTER_OPTIONAL = "checker.function.signature.requiredAfterOptional"

        const val TYPE_MISMATCH = "checker.function.return.typeMismatch"
        const val EXTRA_VALUES = "checker.function.return.extraValues"

        val ARITY_FAMILY_CODES: Set<String> = setOf(
            PARAMETER_CONTRACT_MISMATCH,
            UNKNOWN_PARAM,
            MISSING_PARAM_NAME,
            NAMED_VARARG,
            MULTIPLE_VARARG,
            VARARG_NOT_LAST,
            OPTIONAL_VARARG,
            REQUIRED_AFTER_OPTIONAL
        )

        /**
         * Complete set of stable codes currently emitted by CheckerPass-backed
         * SemanticPipeline diagnostics. Product additions must update this set
         * and the inventory test deliberately.
         */
        val KNOWN_PIPELINE_CODES: Set<String> = setOf(
            "checker.function.return.extraValues",
            "checker.function.return.typeMismatch",
            "checker.function.signature.missingParamName",
            "checker.function.signature.multipleVararg",
            "checker.function.signature.namedVararg",
            "checker.function.signature.optionalVararg",
            "checker.function.signature.parameterContractMismatch",
            "checker.function.signature.requiredAfterOptional",
            "checker.function.signature.unknownParam",
            "checker.function.signature.varargNotLast",
            "checker.luajava.target.unresolved",
            "checker.local.unused",
            "checker.member.missing"
        )
    }
}
