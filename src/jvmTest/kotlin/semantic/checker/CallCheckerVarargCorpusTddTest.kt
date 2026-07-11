package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.checker.CallChecker
import io.github.dingyi222666.luaparser.semantic.checker.CallFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.CallResolution
import io.github.dingyi222666.luaparser.semantic.checker.ValueSequence
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CallChecker vararg consumption, extra-arg, and trailing-nil corpus.
 *
 * Encodes ranking rules for open-ended (...) tails, closed arity rejection of
 * extras, optional trailing parameters, explicit nil vs omitted optionals, and
 * unknown-friendly assignability for dynamic argument / parameter surfaces.
 * Test-only; red is acceptable until review-owned verification.
 */
class CallCheckerVarargCorpusTddTest {

    private val parser = LuaParser()

    // --- pure vararg (...) consumption ----------------------------------------

    @Test
    fun pureVarargAcceptsZeroArguments() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
            returnType = PrimitiveType.BOOLEAN
        )

        val result = harness.check(callable, emptyList())

        assertSuccess(result, PrimitiveType.BOOLEAN)
    }

    @Test
    fun pureVarargConsumesMatchingExtraArguments() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
            returnType = PrimitiveType.NUMBER
        )

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.STRING, PrimitiveType.STRING)
        )

        assertSuccess(result, PrimitiveType.NUMBER)
    }

    @Test
    fun pureVarargRejectsMismatchedExtraArgument() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
            returnType = PrimitiveType.NUMBER
        )

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertNoMatch(result)
    }

    @Test
    fun pureVarargWrappedInVarargTypeStillConsumesElementType() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("...", VarargType(PrimitiveType.NUMBER), vararg = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        // Assignability of parameter.type (VarargType) vs argument (NUMBER) is
        // not element-wise; document current surface. Prefer element type on
        // FunctionParameter.type for call checking (see pureVarargConsumes...).
        val withElementType = FunctionType(
            parameters = listOf(FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)),
            returnType = PrimitiveType.BOOLEAN
        )
        assertSuccess(
            harness.check(withElementType, listOf(PrimitiveType.NUMBER, PrimitiveType.NUMBER)),
            PrimitiveType.BOOLEAN
        )

        // When parameter.type remains VarargType, only another VarargType assigns.
        val openArg = harness.check(callable, listOf(VarargType(PrimitiveType.NUMBER)))
        assertSuccess(openArg, PrimitiveType.BOOLEAN)
    }

    // --- fixed head + vararg tail ---------------------------------------------

    @Test
    fun fixedPlusVarargRequiresFixedHead() {
        val harness = harness()
        val callable = fixedPlusVararg()

        assertNoMatch(harness.check(callable, emptyList()))
        assertNoMatch(harness.check(callable, listOf(PrimitiveType.NUMBER)))
    }

    @Test
    fun fixedPlusVarargAcceptsHeadWithoutExtras() {
        val harness = harness()
        val callable = fixedPlusVararg()

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertSuccess(result, PrimitiveType.BOOLEAN)
    }

    @Test
    fun fixedPlusVarargConsumesMatchingExtraArgs() {
        val harness = harness()
        val callable = fixedPlusVararg()

        val result = harness.check(
            callable,
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN,
                PrimitiveType.BOOLEAN
            )
        )

        assertSuccess(result, PrimitiveType.BOOLEAN)
    }

    @Test
    fun fixedPlusVarargRejectsMismatchedExtraArg() {
        val harness = harness()
        val callable = fixedPlusVararg()

        val result = harness.check(
            callable,
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.STRING
            )
        )

        assertNoMatch(result)
    }

    @Test
    fun fixedPlusVarargRejectsMismatchedFixedHeadEvenWithValidExtras() {
        val harness = harness()
        val callable = fixedPlusVararg()

        val result = harness.check(
            callable,
            listOf(
                PrimitiveType.NUMBER,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN
            )
        )

        assertNoMatch(result)
    }

    // --- closed arity: extra args rejected ------------------------------------

    @Test
    fun closedSignatureRejectsOneExtraArgument() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        )

        assertNoMatch(result)
    }

    @Test
    fun closedSignatureRejectsManyExtraArguments() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
            returnType = PrimitiveType.NIL
        )

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN, PrimitiveType.NIL)
        )

        assertNoMatch(result)
    }

    @Test
    fun closedZeroArityRejectsAnyArgument() {
        val harness = harness()
        val callable = FunctionType(parameters = emptyList(), returnType = PrimitiveType.NIL)

        assertNoMatch(harness.check(callable, listOf(PrimitiveType.NIL)))
        assertNoMatch(harness.check(callable, listOf(PrimitiveType.STRING)))
        assertSuccess(harness.check(callable, emptyList()), PrimitiveType.NIL)
    }

    // --- optional trailing parameters / trailing nil --------------------------

    @Test
    fun optionalTrailingMayBeOmittedWithoutNilFill() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER, optional = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        assertSuccess(
            harness.check(callable, listOf(PrimitiveType.STRING)),
            PrimitiveType.BOOLEAN
        )
        assertSuccess(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)),
            PrimitiveType.BOOLEAN
        )
    }

    @Test
    fun explicitTrailingNilAgainstOptionalNonNilTypeIsRejected() {
        // Omitted optionals succeed; explicit nil is a real argument and must assign.
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER, optional = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NIL)
        )

        assertNoMatch(result)
    }

    @Test
    fun explicitTrailingNilAgainstOptionalNilSlotSucceeds() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NIL, optional = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        assertSuccess(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.NIL)),
            PrimitiveType.BOOLEAN
        )
    }

    @Test
    fun requiredTrailingSlotCannotBeSatisfiedByOmissionEvenWithNilSemantics() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        // Missing required is arity failure; CallChecker does not invent trailing nil.
        assertNoMatch(harness.check(callable, listOf(PrimitiveType.STRING)))
        assertNoMatch(harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.NIL)))
    }

    @Test
    fun multiOptionalTrailingMayOmitAnySuffix() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("a", PrimitiveType.STRING),
                FunctionParameter("b", PrimitiveType.NUMBER, optional = true),
                FunctionParameter("c", PrimitiveType.BOOLEAN, optional = true)
            ),
            returnType = PrimitiveType.NIL
        )

        assertSuccess(harness.check(callable, listOf(PrimitiveType.STRING)), PrimitiveType.NIL)
        assertSuccess(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)),
            PrimitiveType.NIL
        )
        assertSuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
            ),
            PrimitiveType.NIL
        )
        // Extra beyond closed optionals still rejected (no vararg).
        assertNoMatch(
            harness.check(
                callable,
                listOf(
                    PrimitiveType.STRING,
                    PrimitiveType.NUMBER,
                    PrimitiveType.BOOLEAN,
                    PrimitiveType.STRING
                )
            )
        )
    }

    @Test
    fun optionalThenVarargAcceptsOmittedOptionalAndVarargExtras() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER, optional = true),
                FunctionParameter("...", PrimitiveType.BOOLEAN, vararg = true)
            ),
            returnType = PrimitiveType.NIL
        )

        // only required
        assertSuccess(harness.check(callable, listOf(PrimitiveType.STRING)), PrimitiveType.NIL)
        // required + optional
        assertSuccess(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)),
            PrimitiveType.NIL
        )
        // required + optional + vararg extras
        assertSuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN, PrimitiveType.BOOLEAN)
            ),
            PrimitiveType.NIL
        )
        // required then direct vararg without optional: third arg is number-typed optional slot
        // so boolean lands on optional number and fails; document positional mapping.
        assertNoMatch(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.BOOLEAN))
        )
    }

    // --- multi-return / ValueSequence expansion into vararg -------------------

    @Test
    fun lastArgumentMultiReturnExpandsIntoFixedParameters() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        val result = harness.checker.checkCallValues(
            callable,
            listOf(ValueSequence.of(MultiReturnType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)))),
            harness.scopeId
        )

        assertSuccess(result, PrimitiveType.BOOLEAN)
    }

    @Test
    fun lastArgumentMultiReturnExpandsIntoFixedPlusVararg() {
        val harness = harness()
        val callable = fixedPlusVararg()

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

        assertSuccess(result, PrimitiveType.BOOLEAN)
    }

    @Test
    fun nonFinalMultiReturnCollapsesToFirstBeforeTrailingLiteral() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("second", PrimitiveType.NUMBER)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        // Non-final multi-return contributes only its first value via fromExpressionResults.
        val sequences = listOf(
            ValueSequence.fromExpressionResults(
                listOf(
                    MultiReturnType(listOf(PrimitiveType.STRING, PrimitiveType.BOOLEAN)),
                    PrimitiveType.NUMBER
                )
            )
        )
        // fromExpressionResults already flattened; pass as single sequence via checkCallValues
        // by wrapping each fixed slot as its own ValueSequence.of.
        val flat = sequences.single()
        val asArgs = flat.fixed.map { ValueSequence.of(it) }
        val result = harness.checker.checkCallValues(callable, asArgs, harness.scopeId)

        assertSuccess(result, PrimitiveType.BOOLEAN)
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER), flat.fixed)
    }

    @Test
    fun openEndedVarargTailArgumentAppendsSingleSlotNotInfinite() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        // ValueSequence with variadicTail appends one element for call args.
        val result = harness.checker.checkCallValues(
            callable,
            listOf(
                ValueSequence.of(PrimitiveType.STRING),
                ValueSequence(variadicTail = PrimitiveType.NUMBER)
            ),
            harness.scopeId
        )

        assertSuccess(result, PrimitiveType.BOOLEAN)
    }

    // --- unknown-friendly dynamic surfaces ------------------------------------

    @Test
    fun unknownArgumentAgainstTypedParameterSucceeds() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
            returnType = PrimitiveType.NUMBER
        )

        assertSuccess(
            harness.check(callable, listOf(UnknownType)),
            PrimitiveType.NUMBER
        )
    }

    @Test
    fun unknownVarargExtrasAgainstTypedVarargSucceed() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("first", PrimitiveType.STRING),
                FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        assertSuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, UnknownType, UnknownType)
            ),
            PrimitiveType.BOOLEAN
        )
    }

    @Test
    fun unknownParameterTypeAcceptsAnyConcreteArgument() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("value", UnknownType)),
            returnType = PrimitiveType.NIL
        )

        assertSuccess(harness.check(callable, listOf(PrimitiveType.STRING)), PrimitiveType.NIL)
        assertSuccess(harness.check(callable, listOf(PrimitiveType.NUMBER)), PrimitiveType.NIL)
        assertSuccess(harness.check(callable, listOf(PrimitiveType.NIL)), PrimitiveType.NIL)
    }

    @Test
    fun unknownVarargParameterConsumesHeterogeneousExtras() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("...", UnknownType, vararg = true)),
            returnType = PrimitiveType.ANY
        )

        assertSuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN, PrimitiveType.NIL)
            ),
            PrimitiveType.ANY
        )
    }

    @Test
    fun unknownCalleeTypeIsNonCallableNotArityMatch() {
        // Dynamic unknown callees do not invent signatures; resolve fails as NON_CALLABLE.
        val harness = harness()
        val result = harness.check(UnknownType, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))

        assertFalse(result.isSuccess)
        assertEquals(CallFailureReason.NON_CALLABLE, result.failureReason)
        assertNull(result.selectedSignature)
    }

    @Test
    fun anyParameterIsUnknownFriendlyForAllArguments() {
        val harness = harness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("value", PrimitiveType.ANY),
                FunctionParameter("...", PrimitiveType.ANY, vararg = true)
            ),
            returnType = PrimitiveType.ANY
        )

        assertSuccess(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, UnknownType, PrimitiveType.NIL)
            ),
            PrimitiveType.ANY
        )
    }

    // --- overload ranking with vararg fallback --------------------------------

    @Test
    fun exactFixedSignaturePreferredOverVarargFallback() {
        val harness = harness()
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

        assertSuccess(result, PrimitiveType.STRING)
        assertFalse(result.ambiguous)
    }

    @Test
    fun varargOverloadSelectedWhenFixedArityDoesNotMatch() {
        val harness = harness()
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
            listOf(PrimitiveType.STRING, PrimitiveType.STRING)
        )

        assertSuccess(result, PrimitiveType.BOOLEAN)
    }

    @Test
    fun closedOverloadRejectsExtrasEvenWhenSiblingHasVarargMismatch() {
        val harness = harness()
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

        // two strings: closed rejects arity; vararg rejects type → no match
        assertNoMatch(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.STRING))
        )
    }

    // --- corpus table ---------------------------------------------------------

    @Test
    fun varargAndTrailingNilCorpusTable() {
        data class Case(
            val name: String,
            val parameters: List<FunctionParameter>,
            val arguments: List<Type>,
            val expectSuccess: Boolean
        )

        val cases = listOf(
            Case(
                name = "vararg empty",
                parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                arguments = emptyList(),
                expectSuccess = true
            ),
            Case(
                name = "vararg many match",
                parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.STRING),
                expectSuccess = true
            ),
            Case(
                name = "vararg mismatch",
                parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                arguments = listOf(PrimitiveType.NUMBER),
                expectSuccess = false
            ),
            Case(
                name = "closed extra rejected",
                parameters = listOf(FunctionParameter("a", PrimitiveType.STRING)),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.STRING),
                expectSuccess = false
            ),
            Case(
                name = "optional omit",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER, optional = true)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true
            ),
            Case(
                name = "optional explicit nil rejected",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER, optional = true)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NIL),
                expectSuccess = false
            ),
            Case(
                name = "required missing rejected",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = false
            ),
            Case(
                name = "unknown arg friendly",
                parameters = listOf(FunctionParameter("a", PrimitiveType.STRING)),
                arguments = listOf(UnknownType),
                expectSuccess = true
            ),
            Case(
                name = "fixed+vararg extras",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER),
                expectSuccess = true
            ),
            Case(
                name = "fixed+vararg extra mismatch",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.STRING),
                expectSuccess = false
            )
        )

        val harness = harness()
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

    // --- harness --------------------------------------------------------------

    private fun harness(): Harness {
        val chunk = parser.parse("")
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            checker = CallChecker(resolved),
            declarations = resolved,
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private fun fixedPlusVararg(): FunctionType = FunctionType(
        parameters = listOf(
            FunctionParameter("first", PrimitiveType.STRING),
            FunctionParameter("second", PrimitiveType.NUMBER),
            FunctionParameter("...", PrimitiveType.BOOLEAN, vararg = true)
        ),
        returnType = PrimitiveType.BOOLEAN
    )

    private fun assertSuccess(result: CallResolution, expectedReturn: Type) {
        assertTrue(result.isSuccess, "expected success, got reason=${result.failureReason}")
        assertNull(result.failureReason)
        assertSame(expectedReturn, result.returnType)
    }

    private fun assertNoMatch(result: CallResolution) {
        assertFalse(result.isSuccess, "expected NO_MATCHING_SIGNATURE failure")
        assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, result.failureReason)
        assertNull(result.returnType)
    }

    private data class Harness(
        val checker: CallChecker,
        val declarations: BinderPassResult,
        val scopeId: ScopeId
    ) {
        fun check(callable: Type, arguments: List<Type>): CallResolution {
            return checker.checkCall(callable, arguments, scopeId)
        }
    }
}
