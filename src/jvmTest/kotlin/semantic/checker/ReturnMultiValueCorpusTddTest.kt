package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.ReturnChecker
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
import kotlin.test.assertTrue

/**
 * Focused ReturnChecker multi-value return-shape corpus.
 *
 * Documents annotated multi-return slot checking, open-ended / vararg tails,
 * final-call multi-return expansion, and unannotated unknown-friendly behaviour.
 * Production defects surface as assertion failures (test-only; no product edits).
 */
class ReturnMultiValueCorpusTddTest {

    private val parser = LuaParser()

    // --- annotated multi-return: exact shape ---------------------------------

    @Test
    fun annotatedTwoSlotExactMatchProducesNoDiagnostics() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return "ok", 42
            end
            """.trimIndent()
        )

        assertTrue(harness.check("pair").isEmpty())
    }

    @Test
    fun annotatedThreeSlotExactMatchProducesNoDiagnostics() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function triple()
                return "x", 1, true
            end
            """.trimIndent()
        )

        assertTrue(harness.check("triple").isEmpty())
    }

    @Test
    fun annotatedSingleSlotExactMatchProducesNoDiagnostics() {
        val harness = harness(
            """
            ---@return string
            local function one()
                return "solo"
            end
            """.trimIndent()
        )

        assertTrue(harness.check("one").isEmpty())
    }

    // --- annotated multi-return: slot mismatches ----------------------------

    @Test
    fun annotatedFirstSlotTypeMismatchReportsReturnValue1() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return 1, 2
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("pair")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 1")
        assertContains(diagnostics.single().message, "expected string")
    }

    @Test
    fun annotatedSecondSlotTypeMismatchReportsReturnValue2() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return "x", "bad"
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("pair")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 2")
        assertContains(diagnostics.single().message, "expected number")
    }

    @Test
    fun annotatedThirdSlotTypeMismatchReportsReturnValue3() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function triple()
                return "x", 1, "nope"
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("triple")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 3")
        assertContains(diagnostics.single().message, "expected boolean")
    }

    @Test
    fun annotatedMultipleSlotMismatchesReportEachSlot() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return true, "bad"
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("pair")
        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.all { it.code == "checker.function.return.typeMismatch" })
        assertTrue(diagnostics.any { "Return value 1" in it.message })
        assertTrue(diagnostics.any { "Return value 2" in it.message })
    }

    // --- missing / extra values ---------------------------------------------

    @Test
    fun missingSecondSlotFillsNilAndReportsMismatchAgainstNonNil() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return "only-first"
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("pair")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 2")
        assertContains(diagnostics.single().message, "nil")
        assertContains(diagnostics.single().message, "expected number")
    }

    @Test
    fun emptyReturnAgainstMultiAnnotatedReportsNilSlots() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("pair")
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.all { it.code == "checker.function.return.typeMismatch" })
        assertTrue(diagnostics.any { "nil" in it.message })
        assertTrue(diagnostics.any { "Return value 1" in it.message })
    }

    @Test
    fun extraValuesAgainstClosedAnnotatedReturnReportExtraValues() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return "x", 1, true
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("pair")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.extraValues", diagnostics.single().code)
        assertContains(diagnostics.single().message, "extra values")
    }

    @Test
    fun extraValuesAgainstSingleAnnotatedReturnReportExtraValues() {
        val harness = harness(
            """
            ---@return string
            local function one()
                return "x", 1
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("one")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.extraValues", diagnostics.single().code)
    }

    @Test
    fun missingSlotsAllowedWhenDeclaredNil() {
        val harness = harness(
            """
            ---@return string, nil
            local function pair()
                return "x"
            end
            """.trimIndent()
        )

        assertTrue(harness.check("pair").isEmpty())
    }

    // --- final-call multi-return expansion ----------------------------------

    @Test
    fun finalCallExpandsAnnotatedMultiReturnIntoDeclaredSlots() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return "x", 2
            end

            ---@return string, number
            local function wrap()
                return pair()
            end
            """.trimIndent()
        )

        assertTrue(harness.check("wrap").isEmpty())
    }

    @Test
    fun finalCallExpansionSurfacesSlotMismatchFromCalleeShape() {
        val harness = harness(
            """
            ---@return number, string
            local function swapped()
                return 1, "x"
            end

            ---@return string, number
            local function wrap()
                return swapped()
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("wrap")
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.any { it.code == "checker.function.return.typeMismatch" })
        assertTrue(diagnostics.any { "Return value 1" in it.message || "Return value 2" in it.message })
    }

    @Test
    fun nonFinalCallCollapsesToFirstValueOnlyBeforeTrailingLiteral() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return "x", 2
            end

            ---@return string, number
            local function wrap()
                return pair(), 9
            end
            """.trimIndent()
        )

        // Non-final multi-return contributes only first slot ("x"); second is literal 9.
        assertTrue(harness.check("wrap").isEmpty())
    }

    @Test
    fun nonFinalCallThenMismatchedTrailingLiteralReportsSecondSlot() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return "x", 2
            end

            ---@return string, number
            local function wrap()
                return pair(), "bad"
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("wrap")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 2")
    }

    @Test
    fun finalCallWithTooFewDeclaredSlotsReportsExtraFromExpandedCallee() {
        val harness = harness(
            """
            ---@return string, number, boolean
            local function triple()
                return "x", 1, true
            end

            ---@return string
            local function wrap()
                return triple()
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

    // --- vararg / open-ended returns ----------------------------------------

    @Test
    fun annotatedVarargTailAcceptsMatchingExtraSlots() {
        val harness = harness(
            """
            ---@return number, string...
            local function normalize()
                return 1, "a", "b"
            end
            """.trimIndent()
        )

        assertTrue(harness.check("normalize").isEmpty())
    }

    @Test
    fun annotatedVarargTailRejectsMismatchedExtraSlot() {
        val harness = harness(
            """
            ---@return number, string...
            local function normalize()
                return 1, "a", 99
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("normalize")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 3")
    }

    @Test
    fun syntheticVarargReturnAcceptsPassthroughOfVarargParameter() {
        val harness = harness(
            """
            local function passthrough(...)
                return ...
            end
            """.trimIndent()
        )

        val declaration = harness.function("passthrough").copy(
            declaredType = FunctionType(
                parameters = listOf(FunctionParameter("...", VarargType(PrimitiveType.STRING), vararg = true)),
                returnType = VarargType(PrimitiveType.STRING)
            )
        )

        assertTrue(harness.checker.checkDeclaration(declaration).isEmpty())
    }

    // --- branch paths -------------------------------------------------------

    @Test
    fun eachBranchReturnCheckedIndependentlyForMultiShape() {
        val harness = harness(
            """
            ---@param flag boolean
            ---@return string, number
            local function pair(flag)
                if flag then
                    return "ok", 1
                end
                return "ok", "bad"
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("pair")
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 2")
    }

    @Test
    fun fallthroughPathAgainstMultiAnnotatedReportsNilMismatch() {
        val harness = harness(
            """
            ---@param flag boolean
            ---@return string, number
            local function pair(flag)
                if flag then
                    return "ok", 1
                end
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("pair")
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.any { it.code == "checker.function.return.typeMismatch" })
        assertTrue(diagnostics.any { "nil" in it.message })
    }

    @Test
    fun nestedFunctionReturnsDoNotPolluteOuterMultiShape() {
        val harness = harness(
            """
            ---@return string, number
            local function outer()
                local function nested()
                    return 1, true, "noise"
                end
                return "x", 2
            end
            """.trimIndent()
        )

        assertTrue(harness.check("outer").isEmpty())
    }

    // --- unannotated: unknown-friendly --------------------------------------

    @Test
    fun unannotatedFunctionDeclaredReturnIsUnknown() {
        val harness = harness(
            """
            local function freeform()
                return "x", 1, true
            end
            """.trimIndent()
        )

        val declaration = harness.function("freeform")
        val functionType = declaration.declaredType as FunctionType
        assertEquals(UnknownType, functionType.returnType)
    }

    @Test
    fun unannotatedMultiValueReturnProducesNoDiagnostics() {
        // Unknown expected slots skip assignability checks (unknown-friendly).
        val harness = harness(
            """
            local function freeform()
                return "x", 1, true
            end
            """.trimIndent()
        )

        assertTrue(harness.check("freeform").isEmpty())
    }

    @Test
    fun unannotatedEmptyBodyProducesNoDiagnostics() {
        val harness = harness(
            """
            local function freeform()
            end
            """.trimIndent()
        )

        assertTrue(harness.check("freeform").isEmpty())
    }

    @Test
    fun unannotatedHeterogeneousBranchesStayUnknownFriendly() {
        val harness = harness(
            """
            local function freeform(flag)
                if flag then
                    return "x", 1
                end
                return true
            end
            """.trimIndent()
        )

        assertTrue(harness.check("freeform").isEmpty())
    }

    @Test
    fun syntheticUnknownMultiReturnStaysUnknownFriendly() {
        val harness = harness(
            """
            local function freeform()
                return "x", 1
            end
            """.trimIndent()
        )

        val declaration = harness.function("freeform").copy(
            declaredType = FunctionType(
                parameters = emptyList(),
                returnType = MultiReturnType(listOf(UnknownType, UnknownType))
            )
        )

        assertTrue(harness.checker.checkDeclaration(declaration).isEmpty())
    }

    @Test
    fun syntheticPartialUnknownSecondSlotSkipsOnlyUnknownSlot() {
        val harness = harness(
            """
            local function freeform()
                return 1, "bad"
            end
            """.trimIndent()
        )

        val declaration = harness.function("freeform").copy(
            declaredType = FunctionType(
                parameters = emptyList(),
                returnType = MultiReturnType(listOf(PrimitiveType.NUMBER, UnknownType))
            )
        )

        // Slot 1 matches number; slot 2 is unknown so mismatch is suppressed.
        assertTrue(harness.checker.checkDeclaration(declaration).isEmpty())
    }

    @Test
    fun syntheticPartialUnknownStillReportsKnownSlotMismatch() {
        val harness = harness(
            """
            local function freeform()
                return "bad", 2
            end
            """.trimIndent()
        )

        val declaration = harness.function("freeform").copy(
            declaredType = FunctionType(
                parameters = emptyList(),
                returnType = MultiReturnType(listOf(PrimitiveType.NUMBER, UnknownType))
            )
        )

        val diagnostics = harness.checker.checkDeclaration(declaration)
        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "Return value 1")
        assertContains(diagnostics.single().message, "expected number")
    }

    // --- documented return shape via ---@type on assigned function ----------

    @Test
    fun globalAnnotatedMultiReturnMismatchReportsDiagnostics() {
        val harness = harness(
            """
            ---@return boolean, string
            function exportPair()
                return "no", 1
            end
            """.trimIndent()
        )

        val diagnostics = harness.check("exportPair")
        assertTrue(diagnostics.size >= 2 || diagnostics.isNotEmpty())
        assertTrue(diagnostics.any { it.code == "checker.function.return.typeMismatch" })
        assertTrue(diagnostics.any { "Return value 1" in it.message })
    }

    @Test
    fun globalAnnotatedMultiReturnExactMatchIsClean() {
        val harness = harness(
            """
            ---@return boolean, string
            function exportPair()
                return true, "ok"
            end
            """.trimIndent()
        )

        assertTrue(harness.check("exportPair").isEmpty())
    }

    // --- harness ------------------------------------------------------------

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
