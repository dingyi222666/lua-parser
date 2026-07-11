package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.checker.CallChecker
import io.github.dingyi222666.luaparser.semantic.checker.CallFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.CallResolution
import io.github.dingyi222666.luaparser.semantic.checker.ValueSequence
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TASK-440 corpus: CallChecker **vararg arity** surface (dual-path).
 *
 * Complements:
 * - [CallCheckerVarargCorpusTddTest] — broader vararg consumption / trailing-nil /
 *   unknown-friendly ranking detail
 * - [CallArityMismatchSurfaceTddTest] — closed under/over arity + pipeline silence
 * - [CallArityFreeformSilenceTddTest] — freeform call-site silence policy
 * - [VarargsPackPropagationTddTest] — pack length / ValueSequence open tails
 * - [FunctionSignatureArityTddTest] — declaration-site parameter contract
 *
 * Product snapshot (CallChecker.rankSignature):
 * - Required count excludes optional and vararg parameters.
 * - Closed signatures reject argumentTypes.size > parameters.size.
 * - Open signatures (hasVararg) accept any count ≥ required; extras map onto the
 *   last vararg parameter and must assign to its element/type surface.
 * - Missing required head fails as [CallFailureReason.NO_MATCHING_SIGNATURE]
 *   even when a vararg tail is present.
 * - Pipeline still does **not** emit `checker.call.*` for freeform call-site
 *   arity (CURRENTLY_ACCEPTS silence); reserved future codes stay distinct.
 *
 * Dual-path vocabulary:
 * - **IDEAL** — structured CallChecker success/failure for open vs closed arity.
 * - **CURRENTLY_ACCEPTS** — pipeline freeform silence for vararg call sites
 *   (no invented call-site arity diagnostics today).
 *
 * Test-only. Workers must not run Gradle; verification is review-owned serial
 * jvmTest (`semantic.checker.CallCheckerVarargArityTddTest`).
 * Host android.jar: Downloads + SDK android-35 only (this corpus does not load
 * android.jar; never hardcode G:/).
 */
class CallCheckerVarargArityTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // Pure vararg (...) arity bounds
    // -------------------------------------------------------------------------

    @Test
    fun pureVarargArityZeroIsSuccess() {
        val harness = callHarness()
        val callable = pureVararg(PrimitiveType.STRING, PrimitiveType.BOOLEAN)

        assertAritySuccess(harness.check(callable, emptyList()), PrimitiveType.BOOLEAN)
        assertSelectedIsVararg(harness.check(callable, emptyList()))
    }

    @Test
    fun pureVarargArityOneMatchingIsSuccess() {
        val harness = callHarness()
        val callable = pureVararg(PrimitiveType.NUMBER, PrimitiveType.NIL)

        assertAritySuccess(
            harness.check(callable, listOf(PrimitiveType.NUMBER)),
            PrimitiveType.NIL
        )
    }

    @Test
    fun pureVarargArityManyMatchingIsSuccess() {
        val harness = callHarness()
        val callable = pureVararg(PrimitiveType.STRING, PrimitiveType.BOOLEAN)

        assertAritySuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.STRING, PrimitiveType.STRING)
            ),
            PrimitiveType.BOOLEAN
        )
    }

    @Test
    fun pureVarargArityManyWithTypeMismatchIsNoMatchNotArityInvent() {
        // Arity itself is open; failure is type/rank NO_MATCHING_SIGNATURE.
        val harness = callHarness()
        val callable = pureVararg(PrimitiveType.STRING, PrimitiveType.BOOLEAN)

        assertArityMismatch(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        )
    }

    // -------------------------------------------------------------------------
    // Fixed head + vararg tail — under / exact / over arity
    // -------------------------------------------------------------------------

    @Test
    fun fixedPlusVarargUnderRequiredArityIsMismatch() {
        val harness = callHarness()
        val callable = fixedTwoPlusVararg()

        // 0 and 1 args miss required head of 2.
        assertArityMismatch(harness.check(callable, emptyList()))
        assertArityMismatch(harness.check(callable, listOf(PrimitiveType.STRING)))
    }

    @Test
    fun fixedPlusVarargExactFixedArityWithoutExtrasIsSuccess() {
        val harness = callHarness()
        val callable = fixedTwoPlusVararg()

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        assertAritySuccess(result, PrimitiveType.BOOLEAN)
        assertSelectedIsVararg(result)
        assertEquals(3, result.selectedSignature!!.parameters.size)
    }

    @Test
    fun fixedPlusVarargOverArityMatchingExtrasIsSuccess() {
        val harness = callHarness()
        val callable = fixedTwoPlusVararg()

        assertAritySuccess(
            harness.check(
                callable,
                listOf(
                    PrimitiveType.STRING,
                    PrimitiveType.NUMBER,
                    PrimitiveType.BOOLEAN,
                    PrimitiveType.BOOLEAN,
                    PrimitiveType.BOOLEAN
                )
            ),
            PrimitiveType.BOOLEAN
        )
    }

    @Test
    fun fixedPlusVarargOverArityMismatchedExtraIsNoMatch() {
        val harness = callHarness()
        val callable = fixedTwoPlusVararg()

        assertArityMismatch(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.STRING)
            )
        )
    }

    @Test
    fun fixedOnePlusVarargUnderZeroIsMismatchExactOneIsSuccess() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
            ),
            returnType = PrimitiveType.NIL
        )

        assertArityMismatch(harness.check(callable, emptyList()))
        assertAritySuccess(
            harness.check(callable, listOf(PrimitiveType.STRING)),
            PrimitiveType.NIL
        )
        assertAritySuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER)
            ),
            PrimitiveType.NIL
        )
    }

    // -------------------------------------------------------------------------
    // Closed arity contrast (no vararg) — over-arity fails
    // -------------------------------------------------------------------------

    @Test
    fun closedTwoArgOverArityIsMismatchWhileVarargSiblingWouldSucceed() {
        val harness = callHarness()
        val closed = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER)
            ),
            returnType = PrimitiveType.STRING
        )
        val open = fixedTwoPlusVararg()

        val args = listOf(
            PrimitiveType.STRING,
            PrimitiveType.NUMBER,
            PrimitiveType.BOOLEAN
        )
        assertArityMismatch(harness.check(closed, args))
        assertAritySuccess(harness.check(open, args), PrimitiveType.BOOLEAN)
    }

    @Test
    fun closedZeroArityRejectsAnyArgWhilePureVarargAccepts() {
        val harness = callHarness()
        val closed = FunctionType(parameters = emptyList(), returnType = PrimitiveType.NIL)
        val open = pureVararg(PrimitiveType.STRING, PrimitiveType.NIL)

        assertArityMismatch(harness.check(closed, listOf(PrimitiveType.STRING)))
        assertAritySuccess(harness.check(open, listOf(PrimitiveType.STRING)), PrimitiveType.NIL)
        assertAritySuccess(harness.check(closed, emptyList()), PrimitiveType.NIL)
        assertAritySuccess(harness.check(open, emptyList()), PrimitiveType.NIL)
    }

    // -------------------------------------------------------------------------
    // Optional + vararg arity mapping
    // -------------------------------------------------------------------------

    @Test
    fun optionalThenVarargAcceptsRequiredOnlyAndRequiredPlusOptional() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER, optional = true),
                FunctionParameter("...", PrimitiveType.BOOLEAN, vararg = true)
            ),
            returnType = PrimitiveType.NIL
        )

        assertAritySuccess(harness.check(callable, listOf(PrimitiveType.STRING)), PrimitiveType.NIL)
        assertAritySuccess(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)),
            PrimitiveType.NIL
        )
        assertAritySuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
            ),
            PrimitiveType.NIL
        )
    }

    @Test
    fun optionalThenVarargPositionalSkipMapsOntoOptionalNotVararg() {
        // Document product positional mapping: after required, next arg fills
        // optional NUMBER — a BOOLEAN cannot skip optional into vararg.
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER, optional = true),
                FunctionParameter("...", PrimitiveType.BOOLEAN, vararg = true)
            ),
            returnType = PrimitiveType.NIL
        )

        assertArityMismatch(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.BOOLEAN))
        )
    }

    // -------------------------------------------------------------------------
    // Overload ranking by arity: fixed preferred; vararg fallback; closed reject
    // -------------------------------------------------------------------------

    @Test
    fun overloadPrefersExactFixedArityOverVarargFallback() {
        val harness = callHarness()
        val callable = OverloadedFunctionType(
            listOf(
                FunctionType(
                    parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
                    returnType = PrimitiveType.STRING
                ),
                FunctionType(
                    parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                    returnType = PrimitiveType.BOOLEAN
                )
            )
        )

        val result = harness.check(callable, listOf(PrimitiveType.STRING))
        assertAritySuccess(result, PrimitiveType.STRING)
        assertFalse(result.ambiguous)
        assertFalse(result.selectedSignature!!.parameters.any { it.vararg })
    }

    @Test
    fun overloadSelectsVarargWhenFixedArityDoesNotCoverExtras() {
        val harness = callHarness()
        val callable = OverloadedFunctionType(
            listOf(
                FunctionType(
                    parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
                    returnType = PrimitiveType.STRING
                ),
                FunctionType(
                    parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                    returnType = PrimitiveType.BOOLEAN
                )
            )
        )

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.STRING, PrimitiveType.STRING)
        )
        assertAritySuccess(result, PrimitiveType.BOOLEAN)
        assertSelectedIsVararg(result)
    }

    @Test
    fun overloadClosedAndTypedVarargBothRejectIncompatibleArityOrTypes() {
        val harness = callHarness()
        val callable = OverloadedFunctionType(
            listOf(
                FunctionType(
                    parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
                    returnType = PrimitiveType.STRING
                ),
                FunctionType(
                    parameters = listOf(FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)),
                    returnType = PrimitiveType.BOOLEAN
                )
            )
        )

        // two strings: closed rejects arity; number-vararg rejects type
        assertArityMismatch(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.STRING))
        )
        // one number: closed rejects type; pure number-vararg succeeds
        assertAritySuccess(
            harness.check(callable, listOf(PrimitiveType.NUMBER)),
            PrimitiveType.BOOLEAN
        )
    }

    // -------------------------------------------------------------------------
    // Multi-return / ValueSequence expansion into vararg arity
    // -------------------------------------------------------------------------

    @Test
    fun lastMultiReturnExpandsToSatisfyFixedPlusVarargArity() {
        val harness = callHarness()
        val callable = fixedTwoPlusVararg()

        val result = harness.checker.checkCallValues(
            callable,
            listOf(
                ValueSequence.of(
                    MultiReturnType(
                        listOf(
                            PrimitiveType.STRING,
                            PrimitiveType.NUMBER,
                            PrimitiveType.BOOLEAN,
                            PrimitiveType.BOOLEAN
                        )
                    )
                )
            ),
            harness.scopeId
        )

        assertAritySuccess(result, PrimitiveType.BOOLEAN)
    }

    @Test
    fun lastMultiReturnTooShortStillUnderArityForFixedHead() {
        val harness = callHarness()
        val callable = fixedTwoPlusVararg()

        val result = harness.checker.checkCallValues(
            callable,
            listOf(
                ValueSequence.of(MultiReturnType(listOf(PrimitiveType.STRING)))
            ),
            harness.scopeId
        )

        assertArityMismatch(result)
    }

    @Test
    fun openEndedVariadicTailArgumentAppendsSingleSlotForArity() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        // ValueSequence.variadicTail contributes one call arg slot (not infinite).
        val result = harness.checker.checkCallValues(
            callable,
            listOf(
                ValueSequence.of(PrimitiveType.STRING),
                ValueSequence(variadicTail = PrimitiveType.NUMBER)
            ),
            harness.scopeId
        )

        assertAritySuccess(result, PrimitiveType.BOOLEAN)
    }

    @Test
    fun pureVarargAcceptsValueSequenceOpenTailAsSingleMatchingArg() {
        val harness = callHarness()
        val callable = pureVararg(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)

        val result = harness.checker.checkCallValues(
            callable,
            listOf(ValueSequence(variadicTail = PrimitiveType.NUMBER)),
            harness.scopeId
        )

        assertAritySuccess(result, PrimitiveType.BOOLEAN)
    }

    // -------------------------------------------------------------------------
    // Unknown-friendly open arity
    // -------------------------------------------------------------------------

    @Test
    fun unknownExtrasAgainstTypedVarargPreserveOpenAritySuccess() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        assertAritySuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, UnknownType, UnknownType)
            ),
            PrimitiveType.BOOLEAN
        )
    }

    @Test
    fun unknownVarargParameterAcceptsHeterogeneousArityMany() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("...", UnknownType, vararg = true)),
            returnType = PrimitiveType.ANY
        )

        assertAritySuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
            ),
            PrimitiveType.ANY
        )
        assertAritySuccess(harness.check(callable, emptyList()), PrimitiveType.ANY)
    }

    // -------------------------------------------------------------------------
    // JavaOverloadType open vs closed arity
    // -------------------------------------------------------------------------

    @Test
    fun javaOverloadVarargTailAcceptsExtraArity() {
        val harness = callHarness()
        val callable = JavaOverloadType(
            javaName = JavaTypeName(
                packageName = "semantic.checker",
                simpleNames = listOf("VarargAritySurface")
            ),
            overloadName = "openCall",
            callSignatures = listOf(
                FunctionType(
                    parameters = listOf(
                        FunctionParameter("head", PrimitiveType.STRING),
                        FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
                    ),
                    returnType = PrimitiveType.BOOLEAN
                )
            )
        )

        assertAritySuccess(
            harness.check(callable, listOf(PrimitiveType.STRING)),
            PrimitiveType.BOOLEAN
        )
        assertAritySuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER)
            ),
            PrimitiveType.BOOLEAN
        )
        assertArityMismatch(harness.check(callable, emptyList()))
    }

    @Test
    fun javaOverloadClosedRejectsOverArityWhileSiblingVarargAccepts() {
        val harness = callHarness()
        val closed = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
            returnType = PrimitiveType.STRING
        )
        val open = FunctionType(
            parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
            returnType = PrimitiveType.BOOLEAN
        )
        val callable = JavaOverloadType(
            javaName = JavaTypeName(
                packageName = "semantic.checker",
                simpleNames = listOf("VarargAritySurface")
            ),
            overloadName = "pick",
            callSignatures = listOf(closed, open)
        )

        val one = harness.check(callable, listOf(PrimitiveType.STRING))
        assertAritySuccess(one, PrimitiveType.STRING)

        val two = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.STRING)
        )
        assertAritySuccess(two, PrimitiveType.BOOLEAN)
        assertSelectedIsVararg(two)
    }

    // -------------------------------------------------------------------------
    // Dual-path pipeline silence for freeform vararg call sites
    // -------------------------------------------------------------------------

    @Test
    fun pipelineFreeformVarargUnderAndOverArityStaysSilentDualPath() {
        // CURRENTLY_ACCEPTS: freeform local with `...` does not emit checker.call.*
        // for any call-site argument count. IDEAL CallChecker ranking is locked
        // separately via API tests above.
        val modelUnder = analyze(
            """
            local function f(...)
                return ...
            end
            return f()
            """.trimIndent()
        )
        val modelOver = analyze(
            """
            local function f(a, ...)
                return a
            end
            return f(1, 2, 3, 4)
            """.trimIndent()
        )
        val modelExact = analyze(
            """
            local function f(a, ...)
                return a
            end
            return f(1)
            """.trimIndent()
        )

        assertPipelineCallAritySilence(modelUnder, "freeform pure-vararg zero-arg")
        assertPipelineCallAritySilence(modelOver, "freeform fixed+vararg multi-arg")
        assertPipelineCallAritySilence(modelExact, "freeform fixed+vararg exact-head")
    }

    @Test
    fun pipelineAnnotatedVarargCallSiteAlsoSilentForCallCodesDualPath() {
        // Even with Emmy ---@param ..., call-site arity is not a pipeline diagnostic
        // family today (declaration-site contract is separate).
        val model = analyze(
            """
            ---@param first string
            ---@param ... number
            ---@return boolean
            local function f(first, ...)
                return true
            end
            local ok = f("x", 1, 2, 3)
            return ok
            """.trimIndent()
        )

        assertPipelineCallAritySilence(model, "annotated vararg multi-arg call")
        val codes = codesOf(model)
        assertTrue(
            codes.none { it in RESERVED_CALL_ARITY_CODES },
            "annotated vararg call must not invent reserved call-site codes; codes=$codes"
        )
    }

    @Test
    fun pipelineSilenceIsDeterministicAcrossRepeatedAnalyzeForVarargCalls() {
        val source =
            """
            local function pack(head, ...)
                return head
            end
            return pack("a", 1, 2)
            """.trimIndent()

        val first = codesOf(analyze(source))
        val second = codesOf(analyze(source))
        assertEquals(first, second, "vararg call-site pipeline codes must be deterministic")
        assertTrue(
            first.none { it.startsWith("checker.call.") },
            "repeated analyze must not invent checker.call.*; codes=$first"
        )
    }

    // -------------------------------------------------------------------------
    // Corpus table — open vs closed arity matrix
    // -------------------------------------------------------------------------

    @Test
    fun varargArityCorpusTable() {
        data class Case(
            val name: String,
            val parameters: List<FunctionParameter>,
            val arguments: List<Type>,
            val expectSuccess: Boolean
        )

        val cases = listOf(
            Case(
                name = "pure-vararg arity 0",
                parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                arguments = emptyList(),
                expectSuccess = true
            ),
            Case(
                name = "pure-vararg arity 3 match",
                parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.STRING, PrimitiveType.STRING),
                expectSuccess = true
            ),
            Case(
                name = "fixed2+vararg under 1",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER),
                    FunctionParameter("...", PrimitiveType.BOOLEAN, vararg = true)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = false
            ),
            Case(
                name = "fixed2+vararg exact 2",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER),
                    FunctionParameter("...", PrimitiveType.BOOLEAN, vararg = true)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectSuccess = true
            ),
            Case(
                name = "fixed2+vararg over 4 match",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER),
                    FunctionParameter("...", PrimitiveType.BOOLEAN, vararg = true)
                ),
                arguments = listOf(
                    PrimitiveType.STRING,
                    PrimitiveType.NUMBER,
                    PrimitiveType.BOOLEAN,
                    PrimitiveType.BOOLEAN
                ),
                expectSuccess = true
            ),
            Case(
                name = "closed2 over 3 reject",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
                expectSuccess = false
            ),
            Case(
                name = "closed0 over 1 reject",
                parameters = emptyList(),
                arguments = listOf(PrimitiveType.NIL),
                expectSuccess = false
            ),
            Case(
                name = "optional+vararg required only",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER, optional = true),
                    FunctionParameter("...", PrimitiveType.BOOLEAN, vararg = true)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true
            ),
            Case(
                name = "VarargType-wrapped param prefers element type for call args",
                // Documented surface: FunctionParameter.type = VarargType does not
                // element-wise assign NUMBER; element type on parameter is preferred.
                // CURRENTLY_ACCEPTS: non-element VarargType vs NUMBER may fail.
                parameters = listOf(
                    FunctionParameter("...", VarargType(PrimitiveType.NUMBER), vararg = true)
                ),
                arguments = listOf(PrimitiveType.NUMBER),
                expectSuccess = false
            ),
            Case(
                name = "element-typed vararg accepts NUMBER",
                parameters = listOf(
                    FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
                ),
                arguments = listOf(PrimitiveType.NUMBER),
                expectSuccess = true
            )
        )

        val harness = callHarness()
        val failures = mutableListOf<String>()
        for (case in cases) {
            val callable = FunctionType(parameters = case.parameters, returnType = PrimitiveType.NIL)
            val result = harness.check(callable, case.arguments)
            if (result.isSuccess != case.expectSuccess) {
                failures += "${case.name}: expected success=${case.expectSuccess}, " +
                    "got success=${result.isSuccess} reason=${result.failureReason}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    // -------------------------------------------------------------------------
    // Harness / assertions
    // -------------------------------------------------------------------------

    private fun callHarness(): Harness {
        val chunk = parser.parse("")
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            checker = CallChecker(resolved),
            declarations = resolved,
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private fun analyze(source: String): SemanticModel =
        pipeline.analyze(parser.parse(source)).model

    private fun codesOf(model: SemanticModel): List<String> =
        model.getDiagnostics().mapNotNull { it.code }.sorted()

    private fun assertPipelineCallAritySilence(model: SemanticModel, label: String) {
        val codes = codesOf(model)
        assertTrue(
            codes.none { it.startsWith("checker.call.") },
            "$label CURRENTLY_ACCEPTS dual-path: no checker.call.* today; codes=$codes"
        )
        assertTrue(
            codes.none { it in RESERVED_CALL_ARITY_CODES },
            "$label must not emit reserved future call-site arity codes; codes=$codes"
        )
    }

    private fun pureVararg(element: Type, returnType: Type): FunctionType =
        FunctionType(
            parameters = listOf(FunctionParameter("...", element, vararg = true)),
            returnType = returnType
        )

    private fun fixedTwoPlusVararg(): FunctionType =
        FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER),
                FunctionParameter("...", PrimitiveType.BOOLEAN, vararg = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

    private fun assertAritySuccess(result: CallResolution, expectedReturn: Type) {
        assertTrue(result.isSuccess, "expected arity success, got reason=${result.failureReason}")
        assertNull(result.failureReason)
        assertSame(expectedReturn, result.returnType)
        assertNotNull(result.selectedSignature)
        assertFalse(result.ambiguous)
    }

    private fun assertArityMismatch(result: CallResolution) {
        assertFalse(result.isSuccess, "expected NO_MATCHING_SIGNATURE")
        assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, result.failureReason)
        assertNull(result.returnType)
        assertNull(result.selectedSignature)
        assertFalse(result.ambiguous)
    }

    private fun assertSelectedIsVararg(result: CallResolution) {
        val selected = result.selectedSignature
        assertNotNull(selected, "expected selected signature with vararg")
        assertTrue(
            selected.parameters.any { it.vararg },
            "selected signature should retain vararg parameter; params=${selected.parameters}"
        )
    }

    private data class Harness(
        val checker: CallChecker,
        val declarations: BinderPassResult,
        val scopeId: ScopeId
    ) {
        fun check(callable: Type, arguments: List<Type>): CallResolution =
            checker.checkCall(callable, arguments, scopeId)
    }

    private companion object {
        // Reserved future call-site diagnostic codes (not emitted today).
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
