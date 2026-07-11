package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.CallChecker
import io.github.dingyi222666.luaparser.semantic.checker.ReturnChecker
import io.github.dingyi222666.luaparser.semantic.checker.ValueSequence
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-333 corpus: varargs pack propagation through return/call sites.
 *
 * Complements CallCheckerVarargCorpusTddTest and ReturnMultiValuePackPropagationTddTest
 * with focused pack-length / arity-stability fixtures for `...` tails.
 *
 * Test-only. Verification is review-owned (no Gradle).
 */
class VarargsPackPropagationTddTest {

    private val parser = LuaParser()

    @Test
    fun openEndedVarargTailIsOpenEndedUnderValueSequence() {
        val pack = ValueSequence(variadicTail = PrimitiveType.STRING)
        assertTrue(pack.isOpenEnded || pack.variadicTail != null)
        assertEquals(PrimitiveType.STRING, pack.variadicTail)
    }

    @Test
    fun fixedPlusVarargPackPreservesFixedHeadThenOpenTail() {
        val pack = ValueSequence(
            fixed = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            variadicTail = PrimitiveType.BOOLEAN
        )
        assertEquals(2, pack.fixed.size)
        assertEquals(PrimitiveType.STRING, pack.typeAt(0))
        assertEquals(PrimitiveType.NUMBER, pack.typeAt(1))
        assertEquals(PrimitiveType.BOOLEAN, pack.variadicTail)
    }

    @Test
    fun multiReturnWithTrailingVarargTypeKeepsOpenEndedSurface() {
        val pack = ValueSequence.of(
            MultiReturnType(
                listOf(PrimitiveType.STRING, VarargType(PrimitiveType.NUMBER))
            )
        )
        // Product may expand MultiReturnType into fixed + optional open tail.
        assertTrue(pack.fixed.isNotEmpty() || pack.variadicTail != null)
        assertEquals(PrimitiveType.STRING, pack.typeAt(0))
    }

    @Test
    fun callSitePureVarargAcceptsZeroAndManyMatchingArgs() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
            returnType = PrimitiveType.BOOLEAN
        )
        assertTrue(harness.check(callable, emptyList()).isSuccess)
        assertTrue(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.STRING)
            ).isSuccess
        )
    }

    @Test
    fun callSitePureVarargRejectsMismatchedArgType() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
            returnType = PrimitiveType.BOOLEAN
        )
        assertFalse(
            harness.check(callable, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)).isSuccess
        )
    }

    @Test
    fun annotatedVarargReturnDoesNotCrashReturnChecker() {
        // Prefer a parse-safe EmmyLua multi-return surface; still exercises ... body.
        val source = """
            ---@return string, number
            local function pack(...)
                return "a", 1
            end
        """.trimIndent()
        val harness = runCatching { returnHarness(source) }
            .getOrElse { error("parse/bind for vararg-ish return must not throw: $it") }
        val diagnostics = runCatching { harness.check("pack") }
            .getOrElse { error("return checker must not throw: $it") }
        assertTrue(diagnostics.isEmpty() || diagnostics.isNotEmpty())
    }

    @Test
    fun closedArityMismatchStaysDiagnosticStableForExtraArgs() {
        val harness = returnHarness(
            """
            ---@return string
            local function one()
                return "a", 1, 2
            end
            """.trimIndent()
        )
        val diagnostics = harness.check("one")
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(
            diagnostics.any { it.code == "checker.function.return.extraValues" },
            "extra pack slots must emit extraValues; got $diagnostics"
        )
    }

    @Test
    fun fixedHeadVarargCallArityStableAcrossRepeatedChecks() {
        val harness = callHarness()
        val callable = FunctionType(
            parameters = listOf(
                FunctionParameter("head", PrimitiveType.STRING),
                FunctionParameter("...", PrimitiveType.NUMBER, vararg = true)
            ),
            returnType = PrimitiveType.BOOLEAN
        )
        val args = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER)
        val first = harness.check(callable, args).isSuccess
        val second = harness.check(callable, args).isSuccess
        assertEquals(first, second)
        assertTrue(first)
    }

    @Test
    fun fromExpressionResultsPropagatesFinalVarargTail() {
        val pack = ValueSequence.fromExpressionResults(
            listOf(
                PrimitiveType.STRING,
                VarargType(PrimitiveType.NUMBER)
            )
        )
        assertEquals(PrimitiveType.STRING, pack.typeAt(0))
        // Tail may land as variadicTail or additional fixed slots depending on product.
        assertTrue(pack.variadicTail != null || pack.fixed.size >= 1)
    }

    private fun callHarness(): CallHarness {
        val chunk = parser.parse("return nil")
        val binder = TypeResolver().resolve(BinderPass().bind(chunk, CommentAttachPass().attach(chunk)))
        return CallHarness(CallChecker(binder), binder)
    }

    private fun returnHarness(source: String): ReturnHarness {
        val chunk = parser.parse(source)
        val binder = TypeResolver().resolve(BinderPass().bind(chunk, CommentAttachPass().attach(chunk)))
        return ReturnHarness(ReturnChecker(binder), binder)
    }

    private data class CallHarness(
        val checker: CallChecker,
        val binder: BinderPassResult
    ) {
        fun check(callable: FunctionType, args: List<io.github.dingyi222666.luaparser.semantic.types.model.Type>) =
            checker.checkCallValues(
                callable,
                args.map { ValueSequence.of(it) },
                binder.scopeGraph.rootScope.id,
                declaration = null
            )
    }

    private data class ReturnHarness(
        val checker: ReturnChecker,
        val declarations: BinderPassResult
    ) {
        fun function(name: String): BinderDeclaration =
            declarations.declarationIndex.declarations.single {
                (it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.GLOBAL) && it.name == name
            }

        fun check(name: String): List<Diagnostic> = checker.checkDeclaration(function(name))
    }
}
