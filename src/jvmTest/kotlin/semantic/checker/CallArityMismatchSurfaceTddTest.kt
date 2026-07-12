package semantic.checker

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.checker.CallChecker
import io.github.dingyi222666.luaparser.semantic.checker.CallFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.CallResolution
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-409 corpus: call **arity mismatch diagnostic surface** stability for Lua
 * and Java-backed callables.
 *
 * Product snapshot:
 * - [CallChecker] ranks closed / open signatures and returns structured
 *   [CallFailureReason.NO_MATCHING_SIGNATURE] for closed under-arity and
 *   over-arity mismatches (and type-incompatible extras). Matching closed
 *   arity, optional tails, and vararg tails succeed without inventing
 *   signatures.
 * - [CallChecker] failures are **not** currently emitted as pipeline
 *   `checker.call.*` diagnostics. [SemanticPipeline] / CheckerPass surfaces
 *   declaration-site signature / return / member / luajava codes only (see
 *   [DiagnosticCodeStabilityTddTest] / [CallArityFreeformSilenceTddTest]).
 * - Java-backed callables ([JavaOverloadType], reflected instance/static
 *   methods) share the same CallChecker ranking surface: impossible arity
 *   fails with `NO_MATCHING_SIGNATURE`; matching overload arity selects
 *   stably.
 *
 * This corpus locks:
 * 1. Lua FunctionType / OverloadedFunctionType under / over / match arity.
 * 2. JavaOverloadType + reflected JDK method impossible-arity failures.
 * 3. Structured failure surface invariants (null return / null selected
 *    signature / non-success / stable reason enum).
 * 4. Pipeline silence for freeform call-site arity (no invented
 *    `checker.call.*` codes) while reserved future call-site code strings
 *    remain distinct from the product catalog.
 *
 * Complements:
 * - [CallArityFreeformSilenceTddTest] (pipeline freeform silence + reserved codes)
 * - [CallCheckerVarargCorpusTddTest] / [CallCheckerTest] (ranking API detail)
 * - [FunctionSignatureArityTddTest] (declaration-site parameter contract)
 * - [JavaMethodOverloadArityPickTddTest] / [JavaConstructorOverloadPickTddTest]
 *   (interop overload pick focus)
 * - [DiagnosticCodeStabilityTddTest] (product code catalog)
 *
 * Test-only. Workers must not run Gradle; verification is review-owned serial
 * jvmTest (`semantic.checker.CallArityMismatchSurfaceTddTest`).
 */
class CallArityMismatchSurfaceTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()
    private val jvmProvider = JvmClassModuleProvider()

    // -------------------------------------------------------------------------
    // Lua closed FunctionType — under / over / match
    // -------------------------------------------------------------------------

    @Test
    fun luaClosedUnderArityReportsNoMatchingSignature() {
        val harness = callHarness()
        val callable = closedTwoArg(returnType = PrimitiveType.BOOLEAN)

        val result = harness.check(callable, listOf(PrimitiveType.STRING))

        assertArityMismatch(result)
    }

    @Test
    fun luaClosedOverArityReportsNoMatchingSignature() {
        val harness = callHarness()
        val callable = closedOneArg(returnType = PrimitiveType.NUMBER)

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        )

        assertArityMismatch(result)
    }

    @Test
    fun luaClosedZeroArityWithArgumentsReportsNoMatchingSignature() {
        val harness = callHarness()
        val callable = FunctionType(parameters = emptyList(), returnType = PrimitiveType.NIL)

        assertArityMismatch(harness.check(callable, listOf(PrimitiveType.STRING)))
        assertArityMismatch(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        )
    }

    @Test
    fun luaClosedRequiredArgsOmittedEntirelyReportsNoMatchingSignature() {
        val harness = callHarness()
        val callable = closedTwoArg(returnType = PrimitiveType.STRING)

        assertArityMismatch(harness.check(callable, emptyList()))
    }

    @Test
    fun luaClosedMatchingAritySucceedsWithoutFailureReason() {
        val harness = callHarness()
        val callable = closedTwoArg(returnType = PrimitiveType.BOOLEAN)

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertArityMatch(result, PrimitiveType.BOOLEAN)
        assertEquals(2, result.selectedSignature!!.parameters.size)
    }

    @Test
    fun luaClosedMatchingZeroAritySucceeds() {
        val harness = callHarness()
        val callable = FunctionType(parameters = emptyList(), returnType = PrimitiveType.NIL)

        assertArityMatch(harness.check(callable, emptyList()), PrimitiveType.NIL)
    }

    // -------------------------------------------------------------------------
    // Optional / vararg — must not false-fail as closed arity mismatch
    // -------------------------------------------------------------------------

    @Test
    fun luaOptionalTailOmittedIsNotArityMismatch() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER, optional = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        assertArityMatch(
            harness.check(callable, listOf(PrimitiveType.STRING)),
            PrimitiveType.BOOLEAN
        )
    }

    @Test
    fun luaVarargTailAcceptsExtraArgsWithoutArityMismatch() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        assertArityMatch(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER)
            ),
            PrimitiveType.BOOLEAN
        )
    }

    @Test
    fun luaPureVarargZeroArgsIsNotArityMismatch() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
            returnType = PrimitiveType.NUMBER
        )

        assertArityMatch(harness.check(callable, emptyList()), PrimitiveType.NUMBER)
    }

    // -------------------------------------------------------------------------
    // Lua OverloadedFunctionType — sibling arities + impossible arity
    // -------------------------------------------------------------------------

    @Test
    fun luaOverloadPicksMatchingArityAndRejectsImpossibleArity() {
        val harness = callHarness()
        val callable = OverloadedFunctionType(
            listOf(
                closedOneArg(returnType = PrimitiveType.STRING),
                closedTwoArg(returnType = PrimitiveType.NUMBER)
            )
        )

        assertArityMatch(
            harness.check(callable, listOf(PrimitiveType.STRING)),
            PrimitiveType.STRING
        )
        assertArityMatch(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)),
            PrimitiveType.NUMBER
        )
        // three args match neither closed 1-arg nor closed 2-arg
        assertArityMismatch(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
            )
        )
        // zero args match neither
        assertArityMismatch(harness.check(callable, emptyList()))
    }

    @Test
    fun luaOverloadFixedPreferredOverVarargWhenCountMatches() {
        val harness = callHarness()
        val fixed = closedOneArg(returnType = PrimitiveType.STRING)
        val vararg = FunctionType(
            parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
            returnType = PrimitiveType.BOOLEAN
        )
        val callable = OverloadedFunctionType(listOf(fixed, vararg))

        val match = harness.check(callable, listOf(PrimitiveType.STRING))
        assertArityMatch(match, PrimitiveType.STRING)
        assertFalse(match.selectedSignature!!.parameters.any { it.vararg })

        val extras = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.STRING)
        )
        assertArityMatch(extras, PrimitiveType.BOOLEAN)
        assertTrue(extras.selectedSignature!!.parameters.any { it.vararg })
    }

    // -------------------------------------------------------------------------
    // JavaOverloadType — same CallChecker surface as Lua overloads
    // -------------------------------------------------------------------------

    @Test
    fun javaOverloadClosedUnderArityReportsNoMatchingSignature() {
        val harness = callHarness()
        val callable = javaOverloads(
            closedTwoArg(returnType = PrimitiveType.NUMBER),
            FunctionType(
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER),
                    FunctionParameter("c", PrimitiveType.BOOLEAN)
                ),
                returnType = PrimitiveType.BOOLEAN
            )
        )

        assertArityMismatch(harness.check(callable, listOf(PrimitiveType.STRING)))
    }

    @Test
    fun javaOverloadClosedOverArityReportsNoMatchingSignature() {
        val harness = callHarness()
        val callable = javaOverloads(
            closedOneArg(returnType = PrimitiveType.STRING),
            closedTwoArg(returnType = PrimitiveType.NUMBER)
        )

        assertArityMismatch(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
            )
        )
    }

    @Test
    fun javaOverloadMatchingAritySelectsStableSignature() {
        val harness = callHarness()
        val callable = javaOverloads(
            FunctionType(parameters = emptyList(), returnType = PrimitiveType.BOOLEAN),
            closedOneArg(returnType = PrimitiveType.STRING),
            closedTwoArg(returnType = PrimitiveType.NUMBER)
        )

        val zero = harness.check(callable, emptyList())
        assertArityMatch(zero, PrimitiveType.BOOLEAN)
        assertEquals(0, zero.selectedSignature!!.parameters.size)

        val one = harness.check(callable, listOf(PrimitiveType.STRING))
        assertArityMatch(one, PrimitiveType.STRING)
        assertEquals(1, one.selectedSignature!!.parameters.size)

        val two = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        assertArityMatch(two, PrimitiveType.NUMBER)
        assertEquals(2, two.selectedSignature!!.parameters.size)
    }

    @Test
    fun javaOverloadImpossibleArityIsDeterministicAcrossRepeats() {
        val harness = callHarness()
        val callable = javaOverloads(closedOneArg(returnType = PrimitiveType.STRING))
        val args = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)

        val results = (1..5).map { harness.check(callable, args) }

        assertTrue(results.all { !it.isSuccess })
        assertTrue(results.all { it.failureReason == CallFailureReason.NO_MATCHING_SIGNATURE })
        assertTrue(results.all { it.returnType == null })
        assertTrue(results.all { it.selectedSignature == null })
        assertTrue(results.all { !it.ambiguous })
    }

    // -------------------------------------------------------------------------
    // Reflected Java methods — real JDK multi-arity surfaces
    // -------------------------------------------------------------------------

    @Test
    fun reflectedStringSubstringUnderArityReportsNoMatchingSignature() {
        val method = reflectedInstanceCallable("java.lang.String", "substring")
        val harness = callHarness()

        // substring requires at least beginIndex
        assertArityMismatch(harness.check(method, emptyList()))
    }

    @Test
    fun reflectedStringSubstringMatchingAritiesSucceed() {
        val method = reflectedInstanceCallable("java.lang.String", "substring")
        val arities = method.callSignatures.map { it.parameters.size }.toSet()
        assertTrue(1 in arities && 2 in arities, "substring arities=$arities")

        val harness = callHarness()
        val one = harness.check(method, listOf(PrimitiveType.NUMBER))
        val two = harness.check(method, listOf(PrimitiveType.NUMBER, PrimitiveType.NUMBER))

        assertTrue(one.isSuccess, "1-arg substring should match; reason=${one.failureReason}")
        assertNull(one.failureReason)
        assertEquals(1, one.selectedSignature!!.parameters.size)

        assertTrue(two.isSuccess, "2-arg substring should match; reason=${two.failureReason}")
        assertNull(two.failureReason)
        assertEquals(2, two.selectedSignature!!.parameters.size)
    }

    @Test
    fun reflectedStringSubstringOverArityReportsNoMatchingSignature() {
        val method = reflectedInstanceCallable("java.lang.String", "substring")
        val harness = callHarness()

        // substring only exposes 1-arg and 2-arg closed shapes
        assertArityMismatch(
            harness.check(
                method,
                listOf(PrimitiveType.NUMBER, PrimitiveType.NUMBER, PrimitiveType.NUMBER)
            )
        )
    }

    @Test
    fun reflectedIntegerParseIntClosedArityMismatchSurface() {
        val method = reflectedStaticCallable("java.lang.Integer", "parseInt")
        val harness = callHarness()
        val arities = method.callSignatures.map { it.parameters.size }.toSet()
        assertTrue(arities.isNotEmpty(), "parseInt must expose at least one overload")

        // Zero-arg is never a valid parseInt arity.
        assertArityMismatch(harness.check(method, emptyList()))

        // Matching unary string form succeeds when present.
        if (1 in arities) {
            val unary = harness.check(method, listOf(PrimitiveType.STRING))
            assertTrue(
                unary.isSuccess,
                "unary parseInt(string) should match; reason=${unary.failureReason}"
            )
            assertNull(unary.failureReason)
        }
    }

    // -------------------------------------------------------------------------
    // Structured failure surface invariants
    // -------------------------------------------------------------------------

    @Test
    fun arityMismatchSurfaceInvariantsAreStable() {
        val harness = callHarness()
        val callable = closedTwoArg(returnType = PrimitiveType.BOOLEAN)

        val under = harness.check(callable, listOf(PrimitiveType.STRING))
        val over = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        )
        val match = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        for (mismatch in listOf(under, over)) {
            assertFalse(mismatch.isSuccess)
            assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, mismatch.failureReason)
            assertNull(mismatch.returnType)
            assertNull(mismatch.selectedSignature)
            assertFalse(mismatch.ambiguous)
            // Callable resolution itself still succeeds — only signature ranking fails.
            assertNotNull(mismatch.callableResolution)
            assertTrue(mismatch.callableResolution!!.isSuccess)
            assertNull(mismatch.callableResolution!!.failureReason)
        }

        assertArityMatch(match, PrimitiveType.BOOLEAN)
        assertNotNull(match.callableResolution)
        assertTrue(match.callableResolution!!.isSuccess)
    }

    @Test
    fun nonCallableIsDistinctFromArityMismatchSurface() {
        val harness = callHarness()

        val nonCallable = harness.check(PrimitiveType.NUMBER, listOf(PrimitiveType.STRING))
        val arity = harness.check(
            closedOneArg(returnType = PrimitiveType.STRING),
            emptyList()
        )

        assertEquals(CallFailureReason.NON_CALLABLE, nonCallable.failureReason)
        assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, arity.failureReason)
        assertFalse(nonCallable.isSuccess)
        assertFalse(arity.isSuccess)
        // NON_CALLABLE must not be reclassified as arity mismatch.
        assertTrue(nonCallable.failureReason != CallFailureReason.NO_MATCHING_SIGNATURE)
    }

    @Test
    fun typeMismatchAtMatchingArityStillReportsNoMatchingSignatureNotAmbiguous() {
        // Same arity but incompatible parameter type shares the NO_MATCHING_SIGNATURE
        // reason enum (CallChecker does not emit a separate arity-only code). Document
        // that shared surface so future split requires an explicit corpus update.
        val harness = callHarness()
        val callable = closedOneArg(returnType = PrimitiveType.STRING)

        val result = harness.check(callable, listOf(PrimitiveType.NUMBER))

        assertFalse(result.isSuccess)
        assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, result.failureReason)
        assertNull(result.returnType)
        assertNull(result.selectedSignature)
        assertFalse(result.ambiguous)
    }

    @Test
    fun unknownArgumentIsArityFriendlyWhenCountMatches() {
        val harness = callHarness()
        val callable = closedOneArg(returnType = PrimitiveType.STRING)

        assertArityMatch(harness.check(callable, listOf(UnknownType)), PrimitiveType.STRING)
        // Extra unknown still fails closed arity.
        assertArityMismatch(
            harness.check(callable, listOf(UnknownType, UnknownType))
        )
    }

    // -------------------------------------------------------------------------
    // Pipeline diagnostic silence (freeform call-site) vs structured API
    // -------------------------------------------------------------------------

    @Test
    fun freeformLuaCallSiteArityMismatchDoesNotEmitCallArityDiagnosticCodes() {
        // Complements CallArityFreeformSilenceTddTest: pipeline remains silent for
        // freeform under/over arity while CallChecker (above) reports structured
        // NO_MATCHING_SIGNATURE on typed FunctionType surfaces.
        val under = analyze(
            """
            local function f(a, b)
                return a
            end
            return f(1)
            """.trimIndent()
        )
        val over = analyze(
            """
            local function f(a)
                return a
            end
            return f(1, 2, 3)
            """.trimIndent()
        )

        assertNoCallArityDiagnosticSurface(under)
        assertNoCallArityDiagnosticSurface(over)
    }

    @Test
    fun annotatedLuaCallSiteArityMismatchStillDoesNotInventCallArityCodes() {
        // Declaration-site product codes may appear; call-site arity codes must not.
        val model = analyze(
            """
            ---@param a number
            ---@param b string
            ---@return number
            local function addLabel(a, b)
                return a
            end
            local under = addLabel(1)
            local over = addLabel(1, "x", true)
            return under
            """.trimIndent()
        )

        assertNoCallArityDiagnosticSurface(model)
        assertFalse(codesOf(model).any { it.startsWith("checker.call.") })
        val productOnly = codesOf(model).filter {
            it.startsWith("checker.") && it !in PRODUCT_KNOWN_PIPELINE_CODES
        }
        assertTrue(
            productOnly.isEmpty(),
            "annotated call-site must not invent non-catalog codes; got=$productOnly all=${codesOf(model)}"
        )
    }

    @Test
    fun pipelineAritySilenceIsDeterministicAcrossRepeatedAnalyze() {
        val source =
            """
            local function f(a, b)
                return a
            end
            local a = f(1)
            local b = f(1, 2, 3)
            return a
            """.trimIndent()

        val first = codesOf(analyze(source)).sorted()
        val second = codesOf(analyze(source)).sorted()

        assertEquals(first, second)
        assertFalse(first.any { it.startsWith("checker.call.") })
        for (code in RESERVED_CALL_ARITY_CODES) {
            assertFalse(code in first, "reserved $code must stay unemitted; codes=$first")
        }
    }

    // -------------------------------------------------------------------------
    // Reserved future call-site codes + product catalog alignment
    // -------------------------------------------------------------------------

    @Test
    fun reservedCallArityCodeStringsRemainLockedAndDistinctFromProductCatalog() {
        assertEquals("checker.call.arity", CALL_ARITY_CODE)
        assertEquals("checker.call.argumentCount", CALL_ARGUMENT_COUNT_CODE)
        assertEquals("checker.call.tooManyArguments", CALL_TOO_MANY_ARGS_CODE)
        assertEquals("checker.call.tooFewArguments", CALL_TOO_FEW_ARGS_CODE)
        assertEquals("checker.call.noMatchingSignature", CALL_NO_MATCHING_SIGNATURE_CODE)
        // Mirror CallFailureReason.NO_MATCHING_SIGNATURE naming without inventing emission.
        assertTrue(CALL_NO_MATCHING_SIGNATURE_CODE.endsWith("noMatchingSignature"))
        assertEquals(5, RESERVED_CALL_ARITY_CODES.size)
        assertTrue(RESERVED_CALL_ARITY_CODES.all { it.startsWith("checker.call.") })

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
                "checker.luajava.target.unresolved",
                "checker.local.unused",
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
                reserved.startsWith("checker.function."),
                "reserved call-site code must not reuse checker.function.*; got $reserved"
            )
        }

        // Failure-reason enum surface remains the product call-arity channel today.
        assertEquals(
            setOf(
                CallFailureReason.NON_CALLABLE,
                CallFailureReason.NO_MATCHING_SIGNATURE,
                CallFailureReason.AMBIGUOUS_MATCH
            ),
            CallFailureReason.entries.toSet()
        )
    }

    // -------------------------------------------------------------------------
    // Unified corpus table — Lua + Java under / over / match
    // -------------------------------------------------------------------------

    @Test
    fun callArityMismatchSurfaceCorpusTableCoversLuaAndJava() {
        data class Case(
            val name: String,
            val callable: Type,
            val arguments: List<Type>,
            val expectMatch: Boolean,
            val expectReturn: Type? = null,
            val expectArity: Int? = null
        )

        val luaOne = closedOneArg(returnType = PrimitiveType.STRING)
        val luaTwo = closedTwoArg(returnType = PrimitiveType.NUMBER)
        val luaOverload = OverloadedFunctionType(listOf(luaOne, luaTwo))
        val javaOverload = javaOverloads(
            FunctionType(parameters = emptyList(), returnType = PrimitiveType.BOOLEAN),
            closedOneArg(returnType = PrimitiveType.STRING),
            closedTwoArg(returnType = PrimitiveType.NUMBER)
        )
        val optionalTail = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER, optional = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )
        val varargTail = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        val cases = listOf(
            Case(
                name = "lua under-arity",
                callable = luaTwo,
                arguments = listOf(PrimitiveType.STRING),
                expectMatch = false
            ),
            Case(
                name = "lua over-arity",
                callable = luaOne,
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectMatch = false
            ),
            Case(
                name = "lua match two",
                callable = luaTwo,
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectMatch = true,
                expectReturn = PrimitiveType.NUMBER,
                expectArity = 2
            ),
            Case(
                name = "lua overload pick one",
                callable = luaOverload,
                arguments = listOf(PrimitiveType.STRING),
                expectMatch = true,
                expectReturn = PrimitiveType.STRING,
                expectArity = 1
            ),
            Case(
                name = "lua overload impossible three",
                callable = luaOverload,
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
                expectMatch = false
            ),
            Case(
                name = "lua optional omit",
                callable = optionalTail,
                arguments = listOf(PrimitiveType.STRING),
                expectMatch = true,
                expectReturn = PrimitiveType.BOOLEAN
            ),
            Case(
                name = "lua vararg extras",
                callable = varargTail,
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER),
                expectMatch = true,
                expectReturn = PrimitiveType.BOOLEAN
            ),
            Case(
                name = "java under-arity",
                callable = javaOverload,
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
                expectMatch = false
            ),
            Case(
                name = "java match zero",
                callable = javaOverload,
                arguments = emptyList(),
                expectMatch = true,
                expectReturn = PrimitiveType.BOOLEAN,
                expectArity = 0
            ),
            Case(
                name = "java match one",
                callable = javaOverload,
                arguments = listOf(PrimitiveType.STRING),
                expectMatch = true,
                expectReturn = PrimitiveType.STRING,
                expectArity = 1
            ),
            Case(
                name = "java match two",
                callable = javaOverload,
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectMatch = true,
                expectReturn = PrimitiveType.NUMBER,
                expectArity = 2
            ),
            Case(
                name = "unknown arg matching count",
                callable = luaOne,
                arguments = listOf(UnknownType),
                expectMatch = true,
                expectReturn = PrimitiveType.STRING,
                expectArity = 1
            ),
            Case(
                name = "unknown arg over-arity",
                callable = luaOne,
                arguments = listOf(UnknownType, UnknownType),
                expectMatch = false
            )
        )

        val harness = callHarness()
        val failures = mutableListOf<String>()
        for (case in cases) {
            val result = harness.check(case.callable, case.arguments)
            if (result.isSuccess != case.expectMatch) {
                failures += "${case.name}: expected match=${case.expectMatch}, " +
                    "got success=${result.isSuccess} reason=${result.failureReason}"
                continue
            }
            if (case.expectMatch) {
                if (result.failureReason != null) {
                    failures += "${case.name}: expected no failureReason, got ${result.failureReason}"
                }
                if (case.expectReturn != null && result.returnType !== case.expectReturn) {
                    failures += "${case.name}: expected return ${case.expectReturn}, got ${result.returnType}"
                }
                if (case.expectArity != null &&
                    result.selectedSignature?.parameters?.size != case.expectArity
                ) {
                    failures += "${case.name}: expected arity ${case.expectArity}, " +
                        "got ${result.selectedSignature?.parameters?.size}"
                }
                if (result.ambiguous) {
                    failures += "${case.name}: unexpected ambiguous match"
                }
            } else {
                if (result.failureReason != CallFailureReason.NO_MATCHING_SIGNATURE) {
                    failures += "${case.name}: expected NO_MATCHING_SIGNATURE, got ${result.failureReason}"
                }
                if (result.returnType != null) {
                    failures += "${case.name}: mismatch must not invent returnType=${result.returnType}"
                }
                if (result.selectedSignature != null) {
                    failures += "${case.name}: mismatch must not invent selectedSignature"
                }
                if (result.ambiguous) {
                    failures += "${case.name}: mismatch must not be marked ambiguous"
                }
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

    private fun assertNoCallArityDiagnosticSurface(model: SemanticModel) {
        val codes = codesOf(model)
        assertFalse(
            codes.any { it.startsWith("checker.call.") },
            "call arity mismatch must not invent checker.call.* codes; codes=$codes " +
                "messages=${model.getDiagnostics().map { it.message }}"
        )
        for (code in RESERVED_CALL_ARITY_CODES) {
            assertFalse(code in codes, "expected no reserved $code; codes=$codes")
        }
        assertTrue(
            codes.all { it in PRODUCT_KNOWN_PIPELINE_CODES },
            "call-site surface may only emit known product pipeline codes; codes=$codes"
        )
        assertFalse(
            messagesLookLikeInventedCallArity(model),
            "must not invent free-form call arity messages; " +
                "messages=${model.getDiagnostics().map { it.message }}"
        )
    }

    private fun messagesLookLikeInventedCallArity(model: SemanticModel): Boolean {
        return model.getDiagnostics().any { diagnostic ->
            val message = diagnostic.message.lowercase()
            val code = diagnostic.code.orEmpty()
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

    private fun closedOneArg(returnType: Type): FunctionType =
        FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
            returnType = returnType
        )

    private fun closedTwoArg(returnType: Type): FunctionType =
        FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER)
            ),
            returnType = returnType
        )

    private fun javaOverloads(vararg signatures: FunctionType): JavaOverloadType =
        JavaOverloadType(
            javaName = JavaTypeName(
                packageName = "semantic.checker",
                simpleNames = listOf("ArityMismatchSurface")
            ),
            overloadName = "call",
            callSignatures = signatures.toList()
        )

    private fun reflectedStaticCallable(className: String, methodName: String): CallableType {
        val module = reflectedModule(className)
        val type = module.methods[methodName]
            ?: fail(
                "Missing static method $className.$methodName; " +
                    "available=${module.methods.keys.sorted()}"
            )
        return assertIs(type)
    }

    private fun reflectedInstanceCallable(className: String, methodName: String): CallableType {
        val module = reflectedModule(className)
        val classType = module.fields["__class"]
            ?: fail("Missing __class for $className")
        val instanceMembers = when (classType) {
            is io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType ->
                classType.allInstanceMembers()
            is io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType ->
                classType.allInstanceMembers()
            else -> fail("Unexpected __class type ${classType::class.simpleName}")
        }
        val member = instanceMembers[methodName]
            ?: fail(
                "Missing instance method $className.$methodName; " +
                    "available=${instanceMembers.keys.sorted()}"
            )
        return assertIs(member.valueType)
    }

    private fun reflectedModule(className: String): ModuleType {
        val path = VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")
        val snapshot = jvmProvider.providersFor(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to className)
        )[path] ?: fail("Missing provider for $className at ${path.value}")
        val surface = snapshot.moduleExportSurface
            ?: fail("Missing export surface for $className")
        return surface.moduleType
    }

    private fun assertArityMatch(result: CallResolution, expectedReturn: Type) {
        assertTrue(result.isSuccess, "expected arity match, got reason=${result.failureReason}")
        assertNull(result.failureReason)
        assertSame(expectedReturn, result.returnType)
        assertNotNull(result.selectedSignature)
        assertFalse(result.ambiguous)
    }

    private fun assertArityMismatch(result: CallResolution) {
        assertFalse(result.isSuccess, "expected NO_MATCHING_SIGNATURE arity mismatch")
        assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, result.failureReason)
        assertNull(result.returnType)
        assertNull(result.selectedSignature)
        assertFalse(result.ambiguous)
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
        // Product call ranking uses CallFailureReason instead of checker.call.*.
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

        // Keep in lockstep with DiagnosticCodeStabilityTddTest.KNOWN_PIPELINE_CODES.
        val PRODUCT_KNOWN_PIPELINE_CODES: Set<String> = setOf(
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
