package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-363 corpus: freeform (unannotated / untyped) **call-site** arity silence.
 *
 * Product diagnostic surface today (CheckerPass / ExpressionUsageChecker / CallChecker /
 * FunctionSignatureChecker / ReturnChecker) — aligned with
 * [DiagnosticCodeStabilityTddTest.KNOWN_PIPELINE_CODES]:
 * - Emitted product codes are declaration-site / member / luajava families only:
 *   `checker.function.signature.*`, `checker.function.return.*`,
 *   `checker.member.missing`, `checker.luajava.target.unresolved`.
 * - [CallChecker] ranks signatures and returns structured [CallFailureReason]
 *   (including `NO_MATCHING_SIGNATURE` for closed-arity mismatches) for type
 *   evaluation and signature help — **not** as `checker.call.*` diagnostics.
 * - [ExpressionUsageChecker] walks call expressions but only emits
 *   `checker.luajava.target.unresolved` and `checker.member.missing` on
 *   Java-backed member/index surfaces. It does **not** emit call-site arity
 *   diagnostics for freeform Lua callables.
 * - [CheckerPass] therefore surfaces declaration-site signature / return codes
 *   via FunctionSignatureChecker and ReturnChecker, but **no**
 *   `checker.call.*` codes for freeform call argument counts.
 *
 * This corpus locks the **current** silence policy for freeform call arity so a
 * future call-site arity checker must update these assertions explicitly.
 * Reserved future call-site code names are frozen without inventing emission;
 * they are distinct from the product declaration-site / member / luajava catalog.
 *
 * Complements:
 * - [CallCheckerVarargCorpusTddTest] / [CallCheckerTest] (CallChecker ranking API)
 * - [FunctionSignatureArityTddTest] (declaration-site parameter contract)
 * - [DiagnosticCodeStabilityTddTest] (product code catalog)
 * - [GlobalWriteReadonlyDiagnosticTddTest] (silence + reserved codes pattern)
 *
 * Note: fixtures intentionally avoid multi-identifier returns such as
 * `return a, b` (parser currently rejects those with IllegalStateException near
 * eof). Matching-arity silence is locked with single-value returns and exact
 * 2-arg / 2-param freeform local calls.
 *
 * Test-only. Verification is review-owned (no Gradle from workers).
 */
class CallArityFreeformSilenceTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // Unannotated local callables — too few / too many / exact args
    // -------------------------------------------------------------------------

    @Test
    fun freeformLocalUnderArityCallDoesNotEmitCallArityCodes() {
        val model = analyze(
            """
            local function f(a, b)
                return a
            end
            return f(1)
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    @Test
    fun freeformLocalOverArityCallDoesNotEmitCallArityCodes() {
        val model = analyze(
            """
            local function f(a)
                return a
            end
            return f(1, 2, 3)
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    @Test
    fun freeformLocalMatchingArityCallRemainsSilentForCallCodes() {
        // Matching 2-arg call / 2-param freeform local.
        // Single-value return only: multi-identifier `return a, b` is a known
        // parser ISE surface (not call-arity). Silence is locked for call codes
        // and reserved future call-site names; product catalog codes stay empty
        // on this freeform surface.
        val model = analyze(
            """
            local function f(a, b)
                return a
            end
            local ok = f(1, 2)
            return ok
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
        val codes = codesOf(model)
        assertTrue(
            codes.none { it in RESERVED_CALL_ARITY_CODES },
            "matching-arity freeform call must stay free of reserved call-site codes; codes=$codes"
        )
        assertTrue(
            codes.none { it.startsWith("checker.call.") },
            "matching-arity freeform call must not invent checker.call.*; codes=$codes"
        )
        // Freeform matching call should not surface declaration-site product codes either.
        assertTrue(
            codes.none { it in PRODUCT_KNOWN_PIPELINE_CODES },
            "matching freeform local call must not emit product pipeline codes; codes=$codes"
        )
    }

    @Test
    fun freeformLocalZeroArityWithArgumentsDoesNotEmitCallArityCodes() {
        val model = analyze(
            """
            local function f()
                return 0
            end
            return f(1, 2)
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    @Test
    fun freeformLocalRequiredArgsOmittedEntirelyDoesNotEmitCallArityCodes() {
        val model = analyze(
            """
            local function f(a, b, c)
                return a
            end
            return f()
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    // -------------------------------------------------------------------------
    // Unannotated globals / methods / nested freeform surfaces
    // -------------------------------------------------------------------------

    @Test
    fun freeformGlobalUnderAndOverArityCallsDoNotEmitCallArityCodes() {
        val model = analyze(
            """
            function g(a, b)
                return a
            end
            g(1)
            g(1, 2, 3, 4)
            return g
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    @Test
    fun freeformTableMethodStyleCallsDoNotEmitCallArityCodes() {
        val model = analyze(
            """
            local t = {}
            function t:m(a, b)
                return a
            end
            t:m(1)
            t.m(t, 1, 2, 3)
            return t
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    @Test
    fun freeformNestedLocalCalledWithWrongArityDoesNotEmitCallArityCodes() {
        val model = analyze(
            """
            local function outer()
                local function inner(x, y)
                    return x
                end
                return inner(1)
            end
            return outer(9, 8, 7)
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    @Test
    fun freeformAssignedFunctionValueCallDoesNotEmitCallArityCodes() {
        val model = analyze(
            """
            local f = function(a, b)
                return a
            end
            local under = f(1)
            local over = f(1, 2, 3)
            return under
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    // -------------------------------------------------------------------------
    // Dynamic / unknown / builtin freeform call surfaces
    // -------------------------------------------------------------------------

    @Test
    fun undefinedGlobalCalleeWithAnyArityDoesNotEmitCallArityCodes() {
        // Complements DiagnosticCodeStability undefined-global silence: call-site
        // arity must also stay free of invented checker.call.* codes.
        val model = analyze(
            """
            return totallyUndefinedGlobal(1, 2, 3)
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
        assertFalse(codesOf(model).any { it.startsWith("checker.global.") })
    }

    @Test
    fun dynamicIndexCalleeWithWrongArityDoesNotEmitCallArityCodes() {
        val model = analyze(
            """
            local t = {}
            return t["missing"](1, 2)
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    @Test
    fun builtinPrintStyleVarargCallDoesNotEmitCallArityCodes() {
        val model = analyze(
            """
            print()
            print(1)
            print(1, 2, 3, "x")
            return true
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    @Test
    fun stringCallAndTableCallSugarDoNotEmitCallArityCodes() {
        val model = analyze(
            """
            local function f(a)
                return a
            end
            f "hello"
            f { 1, 2, 3 }
            return f
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    // -------------------------------------------------------------------------
    // Annotated declaration-site product codes must not invent call-site arity codes
    // -------------------------------------------------------------------------

    @Test
    fun annotatedCallableDeclarationSiteCodesDoNotImplyCallSiteArityCodes() {
        // Extra @param may emit product declaration-site signature family codes
        // (e.g. checker.function.signature.unknownParam), but freeform / mismatched
        // call argument counts still must not produce checker.call.* diagnostics.
        val model = analyze(
            """
            ---@param first string
            ---@param missing number
            local function documented(first)
                return first
            end
            return documented(1, 2, 3)
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
        // Product declaration-site codes (if present) are allowed; call-site codes are not.
        assertFalse(codesOf(model).any { it.startsWith("checker.call.") })
        val productCodes = codesOf(model).filter {
            it.startsWith("checker.function.signature.") ||
                it.startsWith("checker.function.return.") ||
                it.startsWith("checker.member.") ||
                it.startsWith("checker.luajava.")
        }
        // If product codes fire, they must stay in the known pipeline catalog.
        assertTrue(
            productCodes.all { it in PRODUCT_KNOWN_PIPELINE_CODES },
            "unexpected non-product codes: $productCodes; all=${codesOf(model)}; known=$PRODUCT_KNOWN_PIPELINE_CODES"
        )
        // Extra @param name is the product unknownParam path (declaration-site).
        val codes = codesOf(model)
        if (codes.isNotEmpty()) {
            assertTrue(
                PRODUCT_UNKNOWN_PARAM in codes || codes.all { it in PRODUCT_KNOWN_PIPELINE_CODES },
                "annotated extra @param surface must stay on product catalog; codes=$codes"
            )
        }
    }

    @Test
    fun fullyAnnotatedMatchingCallableCallStaysFreeOfCallArityCodes() {
        val model = analyze(
            """
            ---@param a number
            ---@param b string
            ---@return number
            local function addLabel(a, b)
                return a
            end
            local match = addLabel(1, "x")
            local under = addLabel(1)
            return match
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    // -------------------------------------------------------------------------
    // Reserved future call-site codes + product alignment + determinism
    // -------------------------------------------------------------------------

    @Test
    fun reservedCallArityCodeStringsAreLockedForFuturePolicy() {
        // Freeze intended stable call-site code strings so renames require explicit updates.
        // These are **not** emitted today; product call ranking uses CallFailureReason instead.
        assertEquals("checker.call.arity", CALL_ARITY_CODE)
        assertEquals("checker.call.argumentCount", CALL_ARGUMENT_COUNT_CODE)
        assertEquals("checker.call.tooManyArguments", CALL_TOO_MANY_ARGS_CODE)
        assertEquals("checker.call.tooFewArguments", CALL_TOO_FEW_ARGS_CODE)
        assertEquals("checker.call.noMatchingSignature", CALL_NO_MATCHING_SIGNATURE_CODE)
        // Mirror CallFailureReason.NO_MATCHING_SIGNATURE naming without inventing emission.
        assertTrue(CALL_NO_MATCHING_SIGNATURE_CODE.endsWith("noMatchingSignature"))
        assertTrue(RESERVED_CALL_ARITY_CODES.all { it.startsWith("checker.call.") })
        assertEquals(5, RESERVED_CALL_ARITY_CODES.size)
    }

    @Test
    fun productDeclarationSiteCodesRemainDistinctFromReservedCallSiteCodes() {
        // Align reserved future call-site names against the product catalog so a
        // future call-site emitter cannot silently reuse declaration-site strings.
        // Product catalog is the same set locked by DiagnosticCodeStabilityTddTest.
        assertEquals(
            setOf(
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
                "checker.local.unused",
                "checker.luajava.target.unresolved",
                "checker.member.missing"
            ),
            PRODUCT_KNOWN_PIPELINE_CODES
        )

        for (reserved in RESERVED_CALL_ARITY_CODES) {
            assertFalse(
                reserved in PRODUCT_KNOWN_PIPELINE_CODES,
                "reserved call-site code $reserved collides with product pipeline catalog"
            )
            assertFalse(
                reserved in PRODUCT_DECLARATION_SITE_CODES,
                "reserved call-site code $reserved collides with product declaration-site catalog"
            )
            assertTrue(
                reserved.startsWith("checker.call."),
                "reserved call-site code must stay under checker.call.*; got $reserved"
            )
            assertFalse(
                reserved.startsWith("checker.function."),
                "reserved call-site code must not reuse checker.function.*; got $reserved"
            )
        }

        // Explicit product constant alignment (declaration-site / member / luajava).
        assertEquals("checker.function.signature.parameterContractMismatch", PRODUCT_PARAMETER_CONTRACT_MISMATCH)
        assertEquals("checker.function.signature.unknownParam", PRODUCT_UNKNOWN_PARAM)
        assertEquals("checker.function.signature.missingParamName", PRODUCT_MISSING_PARAM_NAME)
        assertEquals("checker.function.signature.namedVararg", PRODUCT_NAMED_VARARG)
        assertEquals("checker.function.signature.multipleVararg", PRODUCT_MULTIPLE_VARARG)
        assertEquals("checker.function.signature.varargNotLast", PRODUCT_VARARG_NOT_LAST)
        assertEquals("checker.function.signature.optionalVararg", PRODUCT_OPTIONAL_VARARG)
        assertEquals("checker.function.signature.requiredAfterOptional", PRODUCT_REQUIRED_AFTER_OPTIONAL)
        assertEquals("checker.function.return.typeMismatch", PRODUCT_RETURN_TYPE_MISMATCH)
        assertEquals("checker.function.return.extraValues", PRODUCT_RETURN_EXTRA_VALUES)
        assertEquals("checker.member.missing", PRODUCT_MEMBER_MISSING)
        assertEquals("checker.luajava.target.unresolved", PRODUCT_LUAJAVA_UNRESOLVED)

        assertTrue(PRODUCT_DECLARATION_SITE_CODES.all { it in PRODUCT_KNOWN_PIPELINE_CODES })
        assertEquals(PRODUCT_KNOWN_PIPELINE_CODES, PRODUCT_DECLARATION_SITE_CODES)
    }

    @Test
    fun freeformAritySilenceIsDeterministicAcrossRepeatedAnalyze() {
        val source =
            """
            local function f(a, b)
                return a
            end
            local a = f(1)
            local b = f(1, 2, 3)
            local c = f()
            return a
            """.trimIndent()

        val first = codesOf(analyze(source)).sorted()
        val second = codesOf(analyze(source)).sorted()

        assertTrue(first == second)
        assertFalse(first.any { it.startsWith("checker.call.") })
        assertFalse(CALL_ARITY_CODE in first)
        assertFalse(CALL_ARGUMENT_COUNT_CODE in first)
        assertFalse(CALL_TOO_MANY_ARGS_CODE in first)
        assertFalse(CALL_TOO_FEW_ARGS_CODE in first)
        assertFalse(CALL_NO_MATCHING_SIGNATURE_CODE in first)
    }

    @Test
    fun freeformArityCorpusTableCoversUnderOverAndDynamicSilence() {
        data class Case(val name: String, val source: String)

        val cases = listOf(
            Case(
                name = "under-arity local",
                source = """
                    local function f(a, b) return a end
                    return f(1)
                """.trimIndent()
            ),
            Case(
                name = "over-arity local",
                source = """
                    local function f(a) return a end
                    return f(1, 2)
                """.trimIndent()
            ),
            Case(
                name = "zero-arity extras",
                source = """
                    local function f() end
                    f(1, 2, 3)
                """.trimIndent()
            ),
            Case(
                name = "global freeform",
                source = """
                    function g(a, b) return a end
                    g()
                    g(1, 2, 3)
                """.trimIndent()
            ),
            Case(
                name = "undefined callee",
                source = """
                    return missing(1, 2)
                """.trimIndent()
            ),
            Case(
                name = "anonymous assigned",
                source = """
                    local f = function(a, b) return a end
                    return f(1)
                """.trimIndent()
            ),
            Case(
                name = "matching arity local",
                source = """
                    local function f(a, b) return a end
                    local ok = f(1, 2)
                    return ok
                """.trimIndent()
            )
        )

        val failures = mutableListOf<String>()
        for (case in cases) {
            val model = analyze(case.source)
            val codes = codesOf(model)
            val callCodes = codes.filter { it.startsWith("checker.call.") }
            val reservedHits = RESERVED_CALL_ARITY_CODES.filter { it in codes }
            if (callCodes.isNotEmpty() || reservedHits.isNotEmpty()) {
                failures += "${case.name}: unexpected call-arity codes call=$callCodes reserved=$reservedHits all=$codes"
            }
            if (messagesLookLikeFreeformCallArity(model)) {
                failures += "${case.name}: free-form arity message invented; messages=${model.getDiagnostics().map { it.message }}"
            }
            val unknownProduct = codes.filter {
                it.startsWith("checker.") && it !in PRODUCT_KNOWN_PIPELINE_CODES && !it.startsWith("checker.call.")
            }
            if (unknownProduct.isNotEmpty()) {
                failures += "${case.name}: unknown non-catalog product codes=$unknownProduct all=$codes"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun analyze(source: String): SemanticModel =
        pipeline.analyze(parser.parse(source)).model

    private fun codesOf(model: SemanticModel): Set<String> =
        model.getDiagnostics().mapNotNull { it.code }.toSet()

    private fun assertNoCallAritySurface(model: SemanticModel) {
        val codes = codesOf(model)
        assertFalse(
            codes.any { it.startsWith("checker.call.") },
            "freeform call arity must not invent checker.call.* codes; codes=$codes messages=${model.getDiagnostics().map { it.message }}"
        )
        for (code in RESERVED_CALL_ARITY_CODES) {
            assertFalse(
                code in codes,
                "expected no reserved $code under freeform silence; codes=$codes"
            )
        }
        assertFalse(
            messagesLookLikeFreeformCallArity(model),
            "must not invent free-form call arity messages; messages=${model.getDiagnostics().map { it.message }}"
        )
        // Any codes that do appear must be known product pipeline codes only.
        assertTrue(
            codes.all { it in PRODUCT_KNOWN_PIPELINE_CODES },
            "freeform call surface may only emit known product pipeline codes; codes=$codes known=$PRODUCT_KNOWN_PIPELINE_CODES"
        )
    }

    private fun messagesLookLikeFreeformCallArity(model: SemanticModel): Boolean {
        return model.getDiagnostics().any { diagnostic ->
            val message = diagnostic.message.lowercase()
            val code = diagnostic.code.orEmpty()
            // Only treat as invented call-arity chatter when no known product code is set,
            // or when a reserved call-site code appears. Product declaration-site codes
            // (checker.function.signature.* / checker.function.return.* / member / luajava)
            // are allowed and aligned with DiagnosticCodeStabilityTddTest.
            val isProductCode = code in PRODUCT_KNOWN_PIPELINE_CODES ||
                code.startsWith("checker.function.") ||
                code.startsWith("checker.member.") ||
                code.startsWith("checker.luajava.")
            !isProductCode && (code.isEmpty() || code.startsWith("checker.call.")) && (
                message.contains("too many argument") ||
                    message.contains("too few argument") ||
                    message.contains("argument count") ||
                    message.contains("arity") ||
                    message.contains("expected") && message.contains("argument") &&
                    (message.contains("got") || message.contains("received"))
                )
        }
    }

    private companion object {
        // Reserved future call-site codes (not emitted by product today).
        // CallChecker uses CallFailureReason (NON_CALLABLE / NO_MATCHING_SIGNATURE /
        // AMBIGUOUS_MATCH) instead of checker.call.* diagnostic codes.
        const val CALL_ARITY_CODE = "checker.call.arity"
        const val CALL_ARGUMENT_COUNT_CODE = "checker.call.argumentCount"
        const val CALL_TOO_MANY_ARGS_CODE = "checker.call.tooManyArguments"
        const val CALL_TOO_FEW_ARGS_CODE = "checker.call.tooFewArguments"
        const val CALL_NO_MATCHING_SIGNATURE_CODE = "checker.call.noMatchingSignature"

        // Product diagnostic codes — keep in lockstep with
        // DiagnosticCodeStabilityTddTest.KNOWN_PIPELINE_CODES (declaration-site /
        // member / luajava). Not call-site arity.
        const val PRODUCT_PARAMETER_CONTRACT_MISMATCH =
            "checker.function.signature.parameterContractMismatch"
        const val PRODUCT_UNKNOWN_PARAM = "checker.function.signature.unknownParam"
        const val PRODUCT_MISSING_PARAM_NAME = "checker.function.signature.missingParamName"
        const val PRODUCT_NAMED_VARARG = "checker.function.signature.namedVararg"
        const val PRODUCT_MULTIPLE_VARARG = "checker.function.signature.multipleVararg"
        const val PRODUCT_VARARG_NOT_LAST = "checker.function.signature.varargNotLast"
        const val PRODUCT_OPTIONAL_VARARG = "checker.function.signature.optionalVararg"
        const val PRODUCT_REQUIRED_AFTER_OPTIONAL =
            "checker.function.signature.requiredAfterOptional"
        const val PRODUCT_RETURN_TYPE_MISMATCH = "checker.function.return.typeMismatch"
        const val PRODUCT_RETURN_EXTRA_VALUES = "checker.function.return.extraValues"
        const val PRODUCT_MEMBER_MISSING = "checker.member.missing"
        const val PRODUCT_LUAJAVA_UNRESOLVED = "checker.luajava.target.unresolved"

        val RESERVED_CALL_ARITY_CODES: Set<String> = setOf(
            CALL_ARITY_CODE,
            CALL_ARGUMENT_COUNT_CODE,
            CALL_TOO_MANY_ARGS_CODE,
            CALL_TOO_FEW_ARGS_CODE,
            CALL_NO_MATCHING_SIGNATURE_CODE
        )

        /**
         * Full product pipeline catalog (same membership as
         * DiagnosticCodeStabilityTddTest.KNOWN_PIPELINE_CODES).
         */
        val PRODUCT_KNOWN_PIPELINE_CODES: Set<String> = setOf(
            PRODUCT_RETURN_EXTRA_VALUES,
            PRODUCT_RETURN_TYPE_MISMATCH,
            PRODUCT_MISSING_PARAM_NAME,
            PRODUCT_MULTIPLE_VARARG,
            PRODUCT_NAMED_VARARG,
            PRODUCT_OPTIONAL_VARARG,
            PRODUCT_PARAMETER_CONTRACT_MISMATCH,
            PRODUCT_REQUIRED_AFTER_OPTIONAL,
            PRODUCT_UNKNOWN_PARAM,
            PRODUCT_VARARG_NOT_LAST,
            "checker.local.unused",
            PRODUCT_LUAJAVA_UNRESOLVED,
            PRODUCT_MEMBER_MISSING
        )

        /** Alias: declaration-site + member + luajava product codes (not call-site). */
        val PRODUCT_DECLARATION_SITE_CODES: Set<String> = PRODUCT_KNOWN_PIPELINE_CODES
    }
}
