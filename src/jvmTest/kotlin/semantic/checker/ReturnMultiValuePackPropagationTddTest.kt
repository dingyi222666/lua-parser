package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.ReturnChecker
import io.github.dingyi222666.luaparser.semantic.checker.ValueSequence
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-292: Return multi-value pack propagation corpus (test-only).
 *
 * Acceptance:
 * - `return a, b, c` pack length is stable under ValueSequence / ReturnChecker.
 * - Tail-call multi-return packs expand in final position and collapse safely
 *   in non-final / degraded positions (unknown / open-ended tails).
 *
 * Complements [ReturnMultiValueCorpusTddTest] by focusing on pack length
 * stability and tail-call degradation rather than broad slot-mismatch coverage.
 * Production defects surface as assertion failures.
 */
class ReturnMultiValuePackPropagationTddTest {

    private val parser = LuaParser()

    // --- fixed pack length stability: return a, b, c ----------------------------

    @Test
    fun threeLiteralPackLengthIsStableUnderValueSequence() {
        val pack = ValueSequence.fromExpressionResults(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        )

        assertEquals(3, pack.fixed.size, "return a,b,c pack length must stay 3")
        assertNull(pack.variadicTail)
        assertFalse(pack.isOpenEnded)
        assertEquals(PrimitiveType.STRING, pack.typeAt(0))
        assertEquals(PrimitiveType.NUMBER, pack.typeAt(1))
        assertEquals(PrimitiveType.BOOLEAN, pack.typeAt(2))
        // Past-end slots are nil (closed pack), not invented extras.
        assertEquals(PrimitiveType.NIL, pack.typeAt(3))
        assertFalse(pack.hasValueAt(3))
    }

    @Test
    fun threeSlotAnnotatedReturnKeepsExactPackLengthWithoutDiagnostics() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function triple()
                return "a", 2, true
            end
            """.trimIndent()
        )

        assertTrue(harness.check("triple").isEmpty())
        val expected = ValueSequence.of(
            MultiReturnType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN))
        )
        assertEquals(3, expected.fixed.size)
        assertFalse(expected.isOpenEnded)
    }

    @Test
    fun threeSlotPackLengthStableAcrossRepeatedReturnSites() {
        val harness = harness(
            """
            ---@param flag boolean
            ---@return string, number, boolean
            local function triple(flag)
                if flag then
                    return "a", 1, true
                end
                return "b", 2, false
            end
            """.trimIndent()
        )

        assertTrue(harness.check("triple").isEmpty())
    }

    @Test
    fun closedThreeSlotPackRejectsFourthValueAsExtra() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function triple()
                return "a", 1, true, "noise"
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("triple")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.extraValues", diagnostics.single().code)
        assertContains(diagnostics.single().message, "extra values")
    }

    @Test
    fun closedThreeSlotPackMissingThirdFillsNilAndReportsMismatch() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function triple()
                return "a", 1
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("triple")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 3")
        assertContains(diagnostics.single().message, "nil")
        assertContains(diagnostics.single().message, "expected boolean")
    }

    @Test
    fun multiReturnTypeOfPreservesThreeSlotPackLength() {
        val pack = ValueSequence.of(
            MultiReturnType(
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
            )
        )
        assertEquals(3, pack.fixed.size)
        assertNull(pack.variadicTail)
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
            pack.fixed
        )
    }

    @Test
    fun bareUnknownReturnIsFlaggedAsUnconstrainedFreeformShape() {
        val bare = ValueSequence.of(UnknownType)
        assertTrue(bare.isBareUnknownReturn)
        assertEquals(1, bare.fixed.size)
        assertFalse(bare.isOpenEnded)
    }

    @Test
    fun multiUnknownPackIsNotBareUnknownReturn() {
        val multi = ValueSequence.of(MultiReturnType(listOf(UnknownType, UnknownType)))
        assertFalse(multi.isBareUnknownReturn)
        assertEquals(2, multi.fixed.size)
    }

    @Test
    fun freeformMultiValueReturnAgainstBareUnknownIsSilent() {
        // Product policy (TASK-555): unannotated freeform multi-return must not
        // emit extraValues solely because bare Unknown is a single closed slot.
        val harness = harness(
            """
            local function freeform()
                return "a", 1, true
            end
            """.trimIndent()
        )

        assertTrue(harness.check("freeform").isEmpty())
    }

    // --- final-position tail-call pack propagation ------------------------------

    @Test
    fun finalTailCallPropagatesThreeSlotPackIntact() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function source()
                return "a", 1, true
            end

            ---@return string, number, boolean
            local function wrap()
                return source()
            end
            """.trimIndent()
        )

        assertTrue(harness.check("wrap").isEmpty())
    }

    @Test
    fun finalTailCallThreeSlotIntoTwoSlotReportsExtraFromExpandedPack() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function source()
                return "a", 1, true
            end

            ---@return string, number
            local function wrap()
                return source()
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("wrap")
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(
            diagnostics.any { it.code == "checker.function.return.extraValues" } ||
                diagnostics.any { it.code == "checker.function.return.typeMismatch" },
            "expanded 3-slot pack into 2-slot return must surface extra/mismatch; got $diagnostics"
        )
    }

    @Test
    fun finalTailCallTwoSlotIntoThreeSlotFillsThirdWithNilMismatch() {
        val harness = harness(
            """
            ---@return string, number
            local function source()
                return "a", 1
            end

            ---@return string, number, boolean
            local function wrap()
                return source()
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("wrap")
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.any { it.code == "checker.function.return.typeMismatch" })
        assertTrue(
            diagnostics.any { "Return value 3" in it.message || "nil" in it.message },
            "undersized tail pack should degrade with nil/third-slot mismatch; got $diagnostics"
        )
    }

    @Test
    fun chainedFinalTailCallKeepsThreeSlotPackLengthStable() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function source()
                return "a", 1, true
            end

            ---@return string, number, boolean
            local function mid()
                return source()
            end

            ---@return string, number, boolean
            local function wrap()
                return mid()
            end
            """.trimIndent()
        )

        assertTrue(harness.check("mid").isEmpty())
        assertTrue(harness.check("wrap").isEmpty())
    }

    // --- non-final / degraded tail-call packs -----------------------------------

    @Test
    fun nonFinalTailCallCollapsesPackToFirstSlotOnly() {
        // Lua multi-value rule: only the final expression in a list keeps its pack.
        val collapsed = ValueSequence.fromExpressionResults(
            listOf(
                MultiReturnType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)),
                PrimitiveType.NUMBER
            )
        )

        assertEquals(2, collapsed.fixed.size, "non-final pack must collapse to first + trailing")
        assertEquals(PrimitiveType.STRING, collapsed.fixed[0])
        assertEquals(PrimitiveType.NUMBER, collapsed.fixed[1])
        assertNull(collapsed.variadicTail)
    }

    @Test
    fun nonFinalCallThenTrailingLiteralKeepsDeclaredTwoSlotPackClean() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function source()
                return "a", 1, true
            end

            ---@return string, number
            local function wrap()
                return source(), 9
            end
            """.trimIndent()
        )

        // source() is non-final → only "a"; second slot is literal 9.
        assertTrue(harness.check("wrap").isEmpty())
    }

    @Test
    fun nonFinalCallThenMismatchedTrailingReportsSecondSlotOnly() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function source()
                return "a", 1, true
            end

            ---@return string, number
            local function wrap()
                return source(), "bad"
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("wrap")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 2")
        // Collapsed pack must not invent a third-slot diagnostic from source's pack.
        assertFalse(diagnostics.any { "Return value 3" in it.message })
    }

    @Test
    fun leadingLiteralThenFinalTailCallExpandsPackAfterFirstSlot() {
        val harness = harness(
            """
            ---@return number, boolean
            local function source()
                return 1, true
            end

            ---@return string, number, boolean
            local function wrap()
                return "head", source()
            end
            """.trimIndent()
        )

        // "head" + expanded (number, boolean) → three declared slots.
        assertTrue(harness.check("wrap").isEmpty())
    }

    @Test
    fun leadingLiteralThenFinalTailCallExtraSlotReportsExtraValues() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function source()
                return "a", 1, true
            end

            ---@return string, number
            local function wrap()
                return "head", source()
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("wrap")
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(
            diagnostics.any { it.code == "checker.function.return.extraValues" } ||
                diagnostics.any { it.code == "checker.function.return.typeMismatch" }
        )
    }

    // --- open-ended / unknown tail degradation ----------------------------------

    @Test
    fun openEndedVarargTailPackAcceptsMatchingExtrasWithoutInventingLength() {
        val harness = harness(
            """
            ---@return number, string...
            local function open()
                return 1, "a", "b", "c"
            end
            """.trimIndent()
        )

        assertTrue(harness.check("open").isEmpty())
        val expected = ValueSequence.of(
            MultiReturnType(listOf(PrimitiveType.NUMBER, VarargType(PrimitiveType.STRING)))
        )
        assertEquals(1, expected.fixed.size)
        assertTrue(expected.isOpenEnded)
        assertEquals(PrimitiveType.STRING, expected.variadicTail)
    }

    @Test
    fun openEndedVarargTailPackRejectsMismatchedExtraSafely() {
        val harness = harness(
            """
            ---@return number, string...
            local function open()
                return 1, "a", 99
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("open")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 3")
    }

    @Test
    fun unknownFinalCallPackDegradesWithoutHardCrashOrSpuriousSlotDiagnostics() {
        // Unannotated callee return is Unknown (single closed slot). Wrapping it as
        // a final call against a multi-return declaration must degrade safely:
        // Unknown slots skip assignability; no exception; at most conservative extras.
        val harness = harness(
            """
            local function freeform()
                return "a", 1, true
            end

            ---@return string, number, boolean
            local function wrap()
                return freeform()
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("wrap")
        // Must not throw; Unknown-friendly: either clean or only extraValues/typeMismatch codes.
        assertTrue(
            diagnostics.all {
                it.code == "checker.function.return.extraValues" ||
                    it.code == "checker.function.return.typeMismatch"
            },
            "unknown tail pack must degrade with known return codes only; got $diagnostics"
        )
    }

    @Test
    fun syntheticUnknownMultiReturnPackAcceptsThreeSlotBody() {
        val harness = harness(
            """
            local function freeform()
                return "a", 1, true
            end
            """.trimIndent()
        )

        val declaration = harness.function("freeform").copy(
            declaredType = FunctionType(
                parameters = emptyList(),
                returnType = MultiReturnType(
                    listOf(UnknownType, UnknownType, UnknownType)
                )
            )
        )

        assertTrue(harness.checker.checkDeclaration(declaration).isEmpty())
    }

    @Test
    fun syntheticOpenVarargReturnAcceptsPassthroughPackDegradation() {
        val harness = harness(
            """
            local function passthrough(...)
                return ...
            end
            """.trimIndent()
        )

        val declaration = harness.function("passthrough").copy(
            declaredType = FunctionType(
                parameters = listOf(
                    FunctionParameter("...", VarargType(PrimitiveType.STRING), vararg = true)
                ),
                returnType = VarargType(PrimitiveType.STRING)
            )
        )

        assertTrue(harness.checker.checkDeclaration(declaration).isEmpty())
    }

    @Test
    fun collapseToSingleDegradesMultiPackToFirstSlotOnly() {
        val pack = ValueSequence.of(
            MultiReturnType(
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
            )
        )
        val collapsed = pack.collapseToSingle()

        assertEquals(1, collapsed.fixed.size)
        assertEquals(PrimitiveType.STRING, collapsed.typeAt(0))
        assertNull(collapsed.variadicTail)
        assertFalse(collapsed.hasValueAt(1))
        assertEquals(PrimitiveType.NIL, collapsed.typeAt(1))
    }

    // --- assignment-side pack propagation (same ValueSequence rules) ------------

    @Test
    fun resolveAssignedPackUsesFinalExpressionExpansionRule() {
        // Mirrors ExpressionTypeEvaluator.resolveAssignedValueType / fromExpressionResults:
        // non-final multi packs collapse; final multi packs expand by slot index.
        val finalExpanded = ValueSequence.of(
            MultiReturnType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN))
        )
        assertEquals(PrimitiveType.STRING, finalExpanded.typeAt(0))
        assertEquals(PrimitiveType.NUMBER, finalExpanded.typeAt(1))
        assertEquals(PrimitiveType.BOOLEAN, finalExpanded.typeAt(2))

        val nonFinal = ValueSequence.fromExpressionResults(
            listOf(
                MultiReturnType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)),
                PrimitiveType.NIL
            )
        )
        assertEquals(2, nonFinal.fixed.size)
        assertEquals(PrimitiveType.STRING, nonFinal.typeAt(0))
        assertEquals(PrimitiveType.NIL, nonFinal.typeAt(1))
    }

    @Test
    fun emptyReturnPackIsSingleNilNotZeroLength() {
        val empty = ValueSequence.fromExpressionResults(emptyList())
        assertEquals(1, empty.fixed.size)
        assertEquals(PrimitiveType.NIL, empty.typeAt(0))
        assertFalse(empty.isOpenEnded)
    }

    // --- harness ----------------------------------------------------------------

    private fun harness(source: String): Harness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            checker = ReturnChecker(resolved),
            declarations = resolved
        )
    }

    private data class Harness(
        val checker: ReturnChecker,
        val declarations: BinderPassResult
    ) {
        fun function(name: String): BinderDeclaration {
            return declarations.declarationIndex.declarations.single {
                (it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.GLOBAL) && it.name == name
            }
        }

        fun check(name: String): List<Diagnostic> = checker.checkDeclaration(function(name))
    }
}
