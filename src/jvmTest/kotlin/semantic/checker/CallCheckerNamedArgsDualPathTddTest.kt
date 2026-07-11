package semantic.checker

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
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TASK-487 — CallChecker **named-args dual-path** corpus.
 *
 * Complements:
 * - [CallCheckerTest] — overload ranking / structured failures
 * - [CallCheckerVarargArityTddTest] / [CallCheckerVarargCorpusTddTest] — open arity
 * - [CallArityMismatchSurfaceTddTest] — closed under/over arity surface
 * - [CallCheckerColonDotSelfTddTest] — colon self prepend shapes
 * - [CallArityFreeformSilenceTddTest] — freeform call-site diagnostic silence
 * - [FunctionSignatureArityTddTest] — declaration-site parameter contract
 *
 * Product snapshot ([CallChecker.rankSignature] / [CallChecker.checkCall]):
 * - Arguments are ranked **positionally** only. [FunctionParameter.name] is retained
 *   on selected signatures (display / signature-help / inlay consumers) but does
 *   **not** reorder or match call arguments by name today.
 * - There is no CallChecker API that accepts a name→type map or rewrites a single
 *   table constructor into multi-formal slots. A table call is a single argument
 *   of [TableType] (or unknown) and must assign to the formal at that position.
 * - Optional / vararg / overload ranking remains positional and name-agnostic.
 * - Pipeline freeform call sites still do **not** emit `checker.call.*` diagnostics
 *   for table-style or multi-arg calls (CURRENTLY_ACCEPTS silence).
 *
 * Dual-path vocabulary:
 * - **IDEAL** — named formals retained; positional success/failure stable; table
 *   formals accept matching [TableType] fields; future name-based reordering would
 *   be an explicit product change (out of this corpus).
 * - **CURRENTLY_ACCEPTS** — no name-based argument reordering; multi-param formals
 *   reject a single options-table argument as closed arity/type mismatch; freeform
 *   pipeline remains silent for call-site arity/named-arg style.
 *
 * Test-only. Workers must not run Gradle; verification is review-owned serial
 * jvmTest (`semantic.checker.CallCheckerNamedArgsDualPathTddTest`).
 * Host android.jar: Downloads + SDK android-35 only (this corpus does not load
 * android.jar; never hardcode G:/).
 */
class CallCheckerNamedArgsDualPathTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // Named formals preserved through positional success
    // -------------------------------------------------------------------------

    @Test
    fun positionalMatchPreservesDistinctFormalNamesOnSelectedSignature() {
        val harness = callHarness()
        val callable = namedTwoArg(
            first = "path",
            firstType = PrimitiveType.STRING,
            second = "mode",
            secondType = PrimitiveType.STRING,
            returnType = PrimitiveType.BOOLEAN
        )

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.STRING)
        )

        assertAritySuccess(result, PrimitiveType.BOOLEAN)
        val selected = assertNotNull(result.selectedSignature)
        assertEquals(listOf("path", "mode"), selected.parameters.map { it.name })
        assertEquals(PrimitiveType.STRING, selected.parameters[0].type)
        assertEquals(PrimitiveType.STRING, selected.parameters[1].type)
    }

    @Test
    fun positionalMatchWithSelfPlusNamedFormalsPreservesNames() {
        val harness = callHarness()
        val widget = TableType(fields = mapOf("id" to PrimitiveType.STRING))
        val method = FunctionType(
            parameters = listOf(
                FunctionParameter("self", widget),
                FunctionParameter("label", PrimitiveType.STRING),
                FunctionParameter("alpha", PrimitiveType.NUMBER, optional = true)
            ),
            returnType = PrimitiveType.NIL
        )

        // Colon-style presentation: receiver already prepended.
        val result = harness.check(
            method,
            listOf(widget, PrimitiveType.STRING)
        )

        assertAritySuccess(result, PrimitiveType.NIL)
        val selected = assertNotNull(result.selectedSignature)
        assertEquals(listOf("self", "label", "alpha"), selected.parameters.map { it.name })
        assertTrue(selected.parameters[2].optional)
    }

    @Test
    fun zeroArgCallableWithEmptyNamedFormalsStillSucceeds() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = emptyList(),
            returnType = PrimitiveType.NUMBER
        )

        val result = harness.check(callable, emptyList())

        assertAritySuccess(result, PrimitiveType.NUMBER)
        assertTrue(assertNotNull(result.selectedSignature).parameters.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Names do not reorder arguments (positional-only ranking)
    // -------------------------------------------------------------------------

    @Test
    fun swappedPositionalTypesFailEvenWhenFormalNamesSuggestReordering() {
        // fun(path: string, count: number) called as (number, string).
        // IDEAL future name-based call sites could remap; product ranks positionally.
        val harness = callHarness()
        val callable = namedTwoArg(
            first = "path",
            firstType = PrimitiveType.STRING,
            second = "count",
            secondType = PrimitiveType.NUMBER,
            returnType = PrimitiveType.BOOLEAN
        )

        val result = harness.check(
            callable,
            listOf(PrimitiveType.NUMBER, PrimitiveType.STRING)
        )

        assertNoMatchingSignature(result)
    }

    @Test
    fun orderSensitiveOverloadStillPicksByPositionalTypesNotNames() {
        val harness = callHarness()
        val callable = OverloadedFunctionType(
            listOf(
                FunctionType(
                    parameters = listOf(
                        FunctionParameter("path", PrimitiveType.STRING),
                        FunctionParameter("mode", PrimitiveType.STRING)
                    ),
                    returnType = PrimitiveType.STRING
                ),
                FunctionType(
                    parameters = listOf(
                        FunctionParameter("count", PrimitiveType.NUMBER),
                        FunctionParameter("flag", PrimitiveType.BOOLEAN)
                    ),
                    returnType = PrimitiveType.NUMBER
                )
            )
        )

        val stringPath = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.STRING)
        )
        val numberPath = harness.check(
            callable,
            listOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        )

        assertAritySuccess(stringPath, PrimitiveType.STRING)
        assertEquals(listOf("path", "mode"), stringPath.selectedSignature!!.parameters.map { it.name })

        assertAritySuccess(numberPath, PrimitiveType.NUMBER)
        assertEquals(listOf("count", "flag"), numberPath.selectedSignature!!.parameters.map { it.name })
    }

    @Test
    fun identicalNamesAcrossOverloadsStillDisambiguateByTypePositionally() {
        val harness = callHarness()
        val callable = OverloadedFunctionType(
            listOf(
                FunctionType(
                    parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
                    returnType = PrimitiveType.STRING
                ),
                FunctionType(
                    parameters = listOf(FunctionParameter("value", PrimitiveType.NUMBER)),
                    returnType = PrimitiveType.NUMBER
                )
            )
        )

        assertAritySuccess(
            harness.check(callable, listOf(PrimitiveType.STRING)),
            PrimitiveType.STRING
        )
        assertAritySuccess(
            harness.check(callable, listOf(PrimitiveType.NUMBER)),
            PrimitiveType.NUMBER
        )
    }

    // -------------------------------------------------------------------------
    // Table-style "named args" as a single options table (dual-path)
    // -------------------------------------------------------------------------

    @Test
    fun multiParamFormalsRejectSingleOptionsTableAsClosedArityMismatch() {
        // Lua idiom: open({ path = "...", mode = "..." }) against
        // fun(path: string, mode: string) — product treats the table as ONE arg.
        val harness = callHarness()
        val callable = namedTwoArg(
            first = "path",
            firstType = PrimitiveType.STRING,
            second = "mode",
            secondType = PrimitiveType.STRING,
            returnType = PrimitiveType.BOOLEAN
        )
        val options = TableType(
            fields = mapOf(
                "path" to PrimitiveType.STRING,
                "mode" to PrimitiveType.STRING
            )
        )

        val result = harness.check(callable, listOf(options))

        // CURRENTLY_ACCEPTS: no name-based table unpack into formals.
        assertNoMatchingSignature(result)
    }

    @Test
    fun optionsTableFormalAcceptsMatchingTableTypeArgument() {
        // IDEAL path for Emmy options-table style: single formal is the table.
        val harness = callHarness()
        val optionsShape = TableType(
            fields = mapOf(
                "path" to PrimitiveType.STRING,
                "mode" to PrimitiveType.STRING
            )
        )
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("opts", optionsShape)),
            returnType = PrimitiveType.BOOLEAN
        )
        val argument = TableType(
            fields = mapOf(
                "path" to PrimitiveType.STRING,
                "mode" to PrimitiveType.STRING
            )
        )

        val result = harness.check(callable, listOf(argument))

        // Structural table assignability may succeed (IDEAL) or still fail if
        // table field assign is partial — dual-path soft only on structural gap.
        if (result.isSuccess) {
            assertAritySuccess(result, PrimitiveType.BOOLEAN)
            assertEquals("opts", result.selectedSignature!!.parameters.single().name)
        } else {
            // CURRENTLY_ACCEPTS structural gap: still structured failure, not crash.
            assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, result.failureReason)
            assertNull(result.returnType)
            assertNull(result.selectedSignature)
        }
    }

    @Test
    fun optionsTableFormalRejectsPrimitiveInsteadOfTable() {
        val harness = callHarness()
        val optionsShape = TableType(
            fields = mapOf("path" to PrimitiveType.STRING)
        )
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("opts", optionsShape)),
            returnType = PrimitiveType.NIL
        )

        val result = harness.check(callable, listOf(PrimitiveType.STRING))

        assertNoMatchingSignature(result)
    }

    @Test
    fun emptyTableAgainstMultiNamedFormalsIsArityFailureNotNameUnpack() {
        val harness = callHarness()
        val callable = namedTwoArg(
            first = "x",
            firstType = PrimitiveType.NUMBER,
            second = "y",
            secondType = PrimitiveType.NUMBER,
            returnType = PrimitiveType.NUMBER
        )

        val result = harness.check(callable, listOf(TableType()))

        assertNoMatchingSignature(result)
    }

    // -------------------------------------------------------------------------
    // Optional / vararg named formals (positional dual-path)
    // -------------------------------------------------------------------------

    @Test
    fun optionalNamedTailMayBeOmittedPositionally() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("path", PrimitiveType.STRING),
                FunctionParameter("mode", PrimitiveType.STRING, optional = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        val omitOptional = harness.check(callable, listOf(PrimitiveType.STRING))
        val supplyOptional = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.STRING)
        )

        assertAritySuccess(omitOptional, PrimitiveType.BOOLEAN)
        assertEquals(listOf("path", "mode"), omitOptional.selectedSignature!!.parameters.map { it.name })
        assertAritySuccess(supplyOptional, PrimitiveType.BOOLEAN)
    }

    @Test
    fun namedHeadPlusVarargTailConsumesExtrasPositionally() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("head", PrimitiveType.STRING),
                FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )

        val noExtras = harness.check(callable, listOf(PrimitiveType.STRING))
        val withExtras = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER)
        )
        val badExtra = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.BOOLEAN)
        )

        assertAritySuccess(noExtras, PrimitiveType.BOOLEAN)
        assertAritySuccess(withExtras, PrimitiveType.BOOLEAN)
        assertNoMatchingSignature(badExtra)
        assertEquals("head", noExtras.selectedSignature!!.parameters.first().name)
        assertTrue(withExtras.selectedSignature!!.parameters.any { it.vararg })
    }

    @Test
    fun missingRequiredNamedHeadFailsEvenWithNamedOptionalTailPresent() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("required", PrimitiveType.STRING),
                FunctionParameter("optional", PrimitiveType.NUMBER, optional = true)
            ),
            returnType = PrimitiveType.NIL
        )

        val result = harness.check(callable, emptyList())

        assertNoMatchingSignature(result)
    }

    // -------------------------------------------------------------------------
    // Unknown / any / non-callable surfaces with named formals
    // -------------------------------------------------------------------------

    @Test
    fun unknownCalleeIsNonCallableEvenWithNamedFormalDocs() {
        val harness = callHarness()

        val result = harness.check(UnknownType, listOf(PrimitiveType.STRING))

        assertFalse(result.isSuccess)
        assertEquals(CallFailureReason.NON_CALLABLE, result.failureReason)
        assertNull(result.selectedSignature)
        assertNull(result.returnType)
    }

    @Test
    fun namedFormalOfAnyAcceptsStringArgumentPositionally() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.ANY)),
            returnType = PrimitiveType.STRING
        )

        val result = harness.check(callable, listOf(PrimitiveType.STRING))

        assertAritySuccess(result, PrimitiveType.STRING)
        assertEquals("value", result.selectedSignature!!.parameters.single().name)
    }

    @Test
    fun namedFormalOfUnknownAcceptsOrSoftRejectsArgumentDualPath() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("value", UnknownType)),
            returnType = PrimitiveType.BOOLEAN
        )

        val result = harness.check(callable, listOf(PrimitiveType.NUMBER))

        // Unknown assignability is product-surface dependent; either structured
        // success with name retained or structured no-match is acceptable.
        if (result.isSuccess) {
            assertAritySuccess(result, PrimitiveType.BOOLEAN)
            assertEquals("value", result.selectedSignature!!.parameters.single().name)
        } else {
            assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, result.failureReason)
        }
    }

    // -------------------------------------------------------------------------
    // Ambiguity with identically named formals
    // -------------------------------------------------------------------------

    @Test
    fun ambiguousOverloadsWithSameNamedFormalReportAmbiguousMatch() {
        val harness = callHarness()
        val callable = OverloadedFunctionType(
            listOf(
                FunctionType(
                    parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
                    returnType = PrimitiveType.STRING
                ),
                FunctionType(
                    parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
                    returnType = PrimitiveType.NUMBER
                )
            )
        )

        val result = harness.check(callable, listOf(PrimitiveType.STRING))

        assertTrue(result.isSuccess, "ambiguous still returns a selected branch")
        assertTrue(result.ambiguous)
        assertEquals(CallFailureReason.AMBIGUOUS_MATCH, result.failureReason)
        assertEquals("value", result.selectedSignature!!.parameters.single().name)
        // Deterministic first-tie return type (STRING branch index 0).
        assertSame(PrimitiveType.STRING, result.returnType)
    }

    // -------------------------------------------------------------------------
    // Batch inventory: named formal shapes
    // -------------------------------------------------------------------------

    @Test
    fun namedArgsPositionalInventoryBatch() {
        data class Case(
            val name: String,
            val parameters: List<FunctionParameter>,
            val arguments: List<Type>,
            val expectSuccess: Boolean,
            val expectedNames: List<String>? = null
        )

        val cases = listOf(
            Case(
                name = "single named string",
                parameters = listOf(FunctionParameter("path", PrimitiveType.STRING)),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectedNames = listOf("path")
            ),
            Case(
                name = "two named match",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectSuccess = true,
                expectedNames = listOf("a", "b")
            ),
            Case(
                name = "two named type swap fail",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.NUMBER, PrimitiveType.STRING),
                expectSuccess = false
            ),
            Case(
                name = "optional named omitted",
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER, optional = true)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectedNames = listOf("a", "b")
            ),
            Case(
                name = "closed over-arity with names",
                parameters = listOf(FunctionParameter("only", PrimitiveType.STRING)),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectSuccess = false
            ),
            Case(
                name = "options table vs multi formals fail",
                parameters = listOf(
                    FunctionParameter("path", PrimitiveType.STRING),
                    FunctionParameter("mode", PrimitiveType.STRING)
                ),
                arguments = listOf(
                    TableType(
                        fields = mapOf(
                            "path" to PrimitiveType.STRING,
                            "mode" to PrimitiveType.STRING
                        )
                    )
                ),
                expectSuccess = false
            ),
            Case(
                name = "named head + vararg zero extras",
                parameters = listOf(
                    FunctionParameter("head", PrimitiveType.STRING),
                    FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectedNames = listOf("head", "...")
            ),
            Case(
                name = "named head missing against vararg",
                parameters = listOf(
                    FunctionParameter("head", PrimitiveType.STRING),
                    FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
                ),
                arguments = emptyList(),
                expectSuccess = false
            )
        )

        val harness = callHarness()
        val failures = mutableListOf<String>()
        for (case in cases) {
            val callable = FunctionType(
                parameters = case.parameters,
                returnType = PrimitiveType.NIL
            )
            val result = harness.check(callable, case.arguments)
            if (result.isSuccess != case.expectSuccess) {
                failures += "${case.name}: expected success=${case.expectSuccess}, " +
                    "got success=${result.isSuccess} reason=${result.failureReason}"
                continue
            }
            if (case.expectSuccess && case.expectedNames != null) {
                val names = result.selectedSignature?.parameters?.map { it.name }
                if (names != case.expectedNames) {
                    failures += "${case.name}: expected names=${case.expectedNames}, got=$names"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    // -------------------------------------------------------------------------
    // Pipeline freeform silence for table / multi-arg call sites
    // -------------------------------------------------------------------------

    @Test
    fun freeformTableStyleCallDoesNotEmitCallNamedArgDiagnostics() {
        // open{ path = "a", mode = "r" } against freeform multi-param local.
        val model = analyze(
            """
            local function open(path, mode)
                return path
            end
            return open{ path = "a", mode = "r" }
            """.trimIndent()
        )

        assertPipelineCallNamedArgSilence(model, "freeform table-style multi-formal call")
    }

    @Test
    fun freeformPositionalNamedStyleCallDoesNotEmitCallNamedArgDiagnostics() {
        val model = analyze(
            """
            local function paint(color, alpha)
                return color
            end
            return paint("red", 1)
            """.trimIndent()
        )

        assertPipelineCallNamedArgSilence(model, "freeform positional multi-arg call")
    }

    @Test
    fun freeformOptionsTableSingleParamDoesNotEmitCallNamedArgDiagnostics() {
        val model = analyze(
            """
            local function configure(opts)
                return opts
            end
            return configure({ path = "a", mode = "r" })
            """.trimIndent()
        )

        assertPipelineCallNamedArgSilence(model, "freeform single options-table param")
    }

    @Test
    fun annotatedLocalCallStillSilentForCallSiteNamedArgCodes() {
        // Declaration-site codes may appear; call-site named-arg codes must not.
        val model = analyze(
            """
            ---@param path string
            ---@param mode string
            ---@return boolean
            local function open(path, mode)
                return true
            end
            return open("a", "r")
            """.trimIndent()
        )

        val codes = codesOf(model)
        assertTrue(
            codes.none { it.startsWith("checker.call.") },
            "annotated call CURRENTLY_ACCEPTS: no checker.call.* today; codes=$codes"
        )
        assertTrue(
            codes.none { it in RESERVED_CALL_NAMED_ARG_CODES },
            "must not emit reserved future named-arg call-site codes; codes=$codes"
        )
    }

    // -------------------------------------------------------------------------
    // Host android.jar policy (documentation-only probe)
    // -------------------------------------------------------------------------

    @Test
    fun hostAndroidJarPolicyDocumentsAllowedPathsOnly() {
        // This corpus does not load android.jar. Lock the host policy for
        // multi-agent workers: Downloads + SDK android-35 only; never G:/.
        val allowed = listOf(
            "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar",
            "/Users/dingyi/Downloads/android.jar"
        )
        val forbiddenFragments = listOf("G:/", "G:\\", "g:/")

        assertTrue(allowed.all { it.contains("android") && it.endsWith("android.jar") })
        assertTrue(forbiddenFragments.none { fragment ->
            allowed.any { it.contains(fragment) }
        })
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

    private fun assertPipelineCallNamedArgSilence(model: SemanticModel, label: String) {
        val codes = codesOf(model)
        assertTrue(
            codes.none { it.startsWith("checker.call.") },
            "$label CURRENTLY_ACCEPTS dual-path: no checker.call.* today; codes=$codes"
        )
        assertTrue(
            codes.none { it in RESERVED_CALL_NAMED_ARG_CODES },
            "$label must not emit reserved future named-arg call-site codes; codes=$codes"
        )
        assertTrue(
            codes.none { it in RESERVED_CALL_ARITY_CODES },
            "$label must not emit reserved future call-site arity codes; codes=$codes"
        )
    }

    private fun namedTwoArg(
        first: String,
        firstType: Type,
        second: String,
        secondType: Type,
        returnType: Type
    ): FunctionType = FunctionType(
        parameters = listOf(
            FunctionParameter(first, firstType),
            FunctionParameter(second, secondType)
        ),
        returnType = returnType
    )

    private fun assertAritySuccess(result: CallResolution, expectedReturn: Type) {
        assertTrue(result.isSuccess, "expected success, got reason=${result.failureReason}")
        assertNull(result.failureReason)
        assertSame(expectedReturn, result.returnType)
        assertNotNull(result.selectedSignature)
        assertFalse(result.ambiguous)
    }

    private fun assertNoMatchingSignature(result: CallResolution) {
        assertFalse(result.isSuccess, "expected NO_MATCHING_SIGNATURE")
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
        const val CALL_ARITY_CODE = "checker.call.arity"
        const val CALL_ARGUMENT_COUNT_CODE = "checker.call.argumentCount"
        const val CALL_TOO_MANY_ARGS_CODE = "checker.call.tooManyArguments"
        const val CALL_TOO_FEW_ARGS_CODE = "checker.call.tooFewArguments"
        const val CALL_NO_MATCHING_SIGNATURE_CODE = "checker.call.noMatchingSignature"
        const val CALL_NAMED_ARG_CODE = "checker.call.namedArgument"
        const val CALL_UNKNOWN_NAMED_ARG_CODE = "checker.call.unknownNamedArgument"
        const val CALL_DUPLICATE_NAMED_ARG_CODE = "checker.call.duplicateNamedArgument"
        const val CALL_NAMED_ARG_TYPE_CODE = "checker.call.namedArgumentType"

        val RESERVED_CALL_ARITY_CODES: Set<String> = setOf(
            CALL_ARITY_CODE,
            CALL_ARGUMENT_COUNT_CODE,
            CALL_TOO_MANY_ARGS_CODE,
            CALL_TOO_FEW_ARGS_CODE,
            CALL_NO_MATCHING_SIGNATURE_CODE
        )

        val RESERVED_CALL_NAMED_ARG_CODES: Set<String> = setOf(
            CALL_NAMED_ARG_CODE,
            CALL_UNKNOWN_NAMED_ARG_CODE,
            CALL_DUPLICATE_NAMED_ARG_CODE,
            CALL_NAMED_ARG_TYPE_CODE
        )
    }
}
