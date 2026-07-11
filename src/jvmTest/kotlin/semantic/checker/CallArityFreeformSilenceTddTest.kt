package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-363 corpus: freeform (unannotated / untyped) **call-site** arity silence.
 *
 * Product snapshot (CheckerPass / ExpressionUsageChecker / CallChecker):
 * - [CallChecker] ranks signatures and returns structured [CallFailureReason]
 *   (including `NO_MATCHING_SIGNATURE` for closed-arity mismatches) for type
 *   evaluation and signature help.
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
 * Reserved stable code names are frozen without inventing emission.
 *
 * Complements:
 * - [CallCheckerVarargCorpusTddTest] / [CallCheckerTest] (CallChecker ranking API)
 * - [FunctionSignatureArityTddTest] (declaration-site parameter contract)
 * - [GlobalWriteReadonlyDiagnosticTddTest] (silence + reserved codes pattern)
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
        val model = analyze(
            """
            local function f(a, b)
                return a, b
            end
            return f(1, 2)
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
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
            return f(1), f(1, 2, 3)
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
    // Annotated declaration-site codes must not invent call-site arity codes
    // -------------------------------------------------------------------------

    @Test
    fun annotatedCallableDeclarationSiteCodesDoNotImplyCallSiteArityCodes() {
        // Extra @param may emit declaration-site signature family codes, but freeform
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
        // Do not invent call arity codes even when other signature codes may exist.
        assertFalse(codesOf(model).any { it.startsWith("checker.call.") })
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
            return addLabel(1, "x"), addLabel(1)
            """.trimIndent()
        )

        assertNoCallAritySurface(model)
    }

    // -------------------------------------------------------------------------
    // Reserved future codes + determinism
    // -------------------------------------------------------------------------

    @Test
    fun reservedCallArityCodeStringsAreLockedForFuturePolicy() {
        // Freeze intended stable code strings so renames require explicit updates.
        assertTrue(CALL_ARITY_CODE == "checker.call.arity")
        assertTrue(CALL_ARGUMENT_COUNT_CODE == "checker.call.argumentCount")
        assertTrue(CALL_TOO_MANY_ARGS_CODE == "checker.call.tooManyArguments")
        assertTrue(CALL_TOO_FEW_ARGS_CODE == "checker.call.tooFewArguments")
        assertTrue(CALL_NO_MATCHING_SIGNATURE_CODE == "checker.call.noMatchingSignature")
    }

    @Test
    fun freeformAritySilenceIsDeterministicAcrossRepeatedAnalyze() {
        val source =
            """
            local function f(a, b)
                return a
            end
            return f(1), f(1, 2, 3), f()
            """.trimIndent()

        val first = codesOf(analyze(source)).sorted()
        val second = codesOf(analyze(source)).sorted()

        assertTrue(first == second)
        assertFalse(first.any { it.startsWith("checker.call.") })
        assertFalse(CALL_ARITY_CODE in first)
        assertFalse(CALL_ARGUMENT_COUNT_CODE in first)
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
    }

    private fun messagesLookLikeFreeformCallArity(model: SemanticModel): Boolean {
        return model.getDiagnostics().any { diagnostic ->
            val message = diagnostic.message.lowercase()
            val code = diagnostic.code.orEmpty()
            // Only treat as invented call-arity chatter when no known product code is set,
            // or when a reserved call-site code appears.
            (code.isEmpty() || code.startsWith("checker.call.")) && (
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
        const val CALL_ARITY_CODE = "checker.call.arity"
        const val CALL_ARGUMENT_COUNT_CODE = "checker.call.argumentCount"
        const val CALL_TOO_MANY_ARGS_CODE = "checker.call.tooManyArguments"
        const val CALL_TOO_FEW_ARGS_CODE = "checker.call.tooFewArguments"
        const val CALL_NO_MATCHING_SIGNATURE_CODE = "checker.call.noMatchingSignature"

        val RESERVED_CALL_ARITY_CODES: Set<String> = setOf(
            CALL_ARITY_CODE,
            CALL_ARGUMENT_COUNT_CODE,
            CALL_TOO_MANY_ARGS_CODE,
            CALL_TOO_FEW_ARGS_CODE,
            CALL_NO_MATCHING_SIGNATURE_CODE
        )
    }
}
