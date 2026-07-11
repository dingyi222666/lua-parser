package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.FunctionSignatureChecker
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * FunctionSignatureChecker arity / parameter-contract corpus.
 *
 * Encodes that annotated callables whose resolved primary [FunctionType] arity
 * does not match the AST parameter list produce
 * `checker.function.signature.parameterContractMismatch` diagnostics for both
 * over-arity and under-arity mismatches. Overload-only arity differences remain
 * exempt from AST contract matching. Test-only; red is acceptable until review.
 */
class FunctionSignatureArityTddTest {

    private val parser = LuaParser()

    // --- primary signature under-arity (resolved fewer than AST) -------------

    @Test
    fun annotatedCallableUnderArityReportsParameterContractMismatch() {
        val harness = harness(
            """
            ---@param first string
            ---@param second number
            local function normalize(first, second)
            end
            """.trimIndent()
        )
        val declaration = harness.function("normalize").withDeclaredType(
            FunctionType(
                parameters = listOf(
                    FunctionParameter("first", PrimitiveType.STRING)
                ),
                returnType = PrimitiveType.NIL
            )
        )

        val diagnostics = harness.checker.checkDeclaration(declaration)
        val codes = diagnostics.mapNotNull { it.code }

        assertContains(codes, PARAMETER_CONTRACT_MISMATCH)
        assertTrue(
            diagnostics.any {
                it.code == PARAMETER_CONTRACT_MISMATCH &&
                    it.message.contains("does not align with the declaration parameter list")
            },
            "under-arity must mention parameter list alignment; got: ${diagnostics.map { it.message }}"
        )
    }

    @Test
    fun annotatedCallableEmptySignatureAgainstMultiParamAstReportsUnderArity() {
        val harness = harness(
            """
            ---@param value string
            ---@param count number
            local function normalize(value, count)
            end
            """.trimIndent()
        )
        val declaration = harness.function("normalize").withDeclaredType(
            FunctionType(
                parameters = emptyList(),
                returnType = PrimitiveType.NIL
            )
        )

        val codes = harness.checker.checkDeclaration(declaration).mapNotNull { it.code }

        assertContains(codes, PARAMETER_CONTRACT_MISMATCH)
    }

    // --- primary signature over-arity (resolved more than AST) ---------------

    @Test
    fun annotatedCallableOverArityReportsParameterContractMismatch() {
        val harness = harness(
            """
            ---@param value string
            local function normalize(value)
            end
            """.trimIndent()
        )
        val declaration = harness.function("normalize").withDeclaredType(
            FunctionType(
                parameters = listOf(
                    FunctionParameter("value", PrimitiveType.STRING),
                    FunctionParameter("count", PrimitiveType.NUMBER),
                    FunctionParameter("flag", PrimitiveType.BOOLEAN)
                ),
                returnType = PrimitiveType.NIL
            )
        )

        val diagnostics = harness.checker.checkDeclaration(declaration)
        val codes = diagnostics.mapNotNull { it.code }

        assertContains(codes, PARAMETER_CONTRACT_MISMATCH)
        assertTrue(
            diagnostics.any {
                it.code == PARAMETER_CONTRACT_MISMATCH &&
                    it.message.contains("does not align with the declaration parameter list")
            },
            "over-arity must mention parameter list alignment; got: ${diagnostics.map { it.message }}"
        )
    }

    @Test
    fun annotatedCallableSingleParamAstAgainstZeroAstParamsReportsOverArity() {
        val harness = harness(
            """
            ---@return nil
            local function normalize()
            end
            """.trimIndent()
        )
        val declaration = harness.function("normalize").withDeclaredType(
            FunctionType(
                parameters = listOf(
                    FunctionParameter("value", PrimitiveType.STRING)
                ),
                returnType = PrimitiveType.NIL
            )
        )

        val codes = harness.checker.checkDeclaration(declaration).mapNotNull { it.code }

        assertContains(codes, PARAMETER_CONTRACT_MISMATCH)
    }

    // --- matching arity (positive control) -----------------------------------

    @Test
    fun annotatedCallableMatchingArityProducesNoParameterContractMismatch() {
        val harness = harness(
            """
            ---@param first string
            ---@param second number
            local function normalize(first, second)
            end
            """.trimIndent()
        )
        val declaration = harness.function("normalize").withDeclaredType(
            FunctionType(
                parameters = listOf(
                    FunctionParameter("first", PrimitiveType.STRING),
                    FunctionParameter("second", PrimitiveType.NUMBER)
                ),
                returnType = PrimitiveType.NIL
            )
        )

        val codes = harness.checker.checkDeclaration(declaration).mapNotNull { it.code }

        assertTrue(
            PARAMETER_CONTRACT_MISMATCH !in codes,
            "matching arity must not report parameterContractMismatch; codes=$codes"
        )
    }

    @Test
    fun annotatedCallableMatchingArityWithOptionalTailProducesNoContractMismatch() {
        val harness = harness(
            """
            ---@param first string
            ---@param second? number
            local function normalize(first, second)
            end
            """.trimIndent()
        )
        val declaration = harness.function("normalize").withDeclaredType(
            FunctionType(
                parameters = listOf(
                    FunctionParameter("first", PrimitiveType.STRING),
                    FunctionParameter("second", PrimitiveType.NUMBER, optional = true)
                ),
                returnType = PrimitiveType.NIL
            )
        )

        val diagnostics = harness.checker.checkDeclaration(declaration)

        assertTrue(
            diagnostics.none { it.code == PARAMETER_CONTRACT_MISMATCH },
            "optional tail with matching count is not an arity mismatch; got=$diagnostics"
        )
    }

    // --- name/order mismatch at same arity still contracts -------------------

    @Test
    fun annotatedCallableSameArityButReorderedNamesReportsContractMismatch() {
        val harness = harness(
            """
            ---@param first string
            ---@param second number
            local function normalize(first, second)
            end
            """.trimIndent()
        )
        val declaration = harness.function("normalize").withDeclaredType(
            FunctionType(
                parameters = listOf(
                    FunctionParameter("second", PrimitiveType.NUMBER),
                    FunctionParameter("first", PrimitiveType.STRING)
                ),
                returnType = PrimitiveType.NIL
            )
        )

        val diagnostics = harness.checker.checkDeclaration(declaration)
        val codes = diagnostics.mapNotNull { it.code }

        assertContains(codes, PARAMETER_CONTRACT_MISMATCH)
        assertTrue(
            diagnostics.any {
                it.code == PARAMETER_CONTRACT_MISMATCH &&
                    it.message.contains("parameter order")
            },
            "reordered names should report order alignment; got: ${diagnostics.map { it.message }}"
        )
    }

    // --- overloads: arity may differ without primary AST contract ------------

    @Test
    fun overloadOnlyUnderAndOverArityDoesNotReportParameterContractMismatch() {
        val harness = harness(
            """
            ---@overload fun(): nil
            ---@overload fun(value: string, count: number, flag: boolean): nil
            ---@param value string
            local function normalize(value)
            end
            """.trimIndent()
        )

        val codes = harness.checker.checkDeclaration(harness.function("normalize")).mapNotNull { it.code }

        assertTrue(
            PARAMETER_CONTRACT_MISMATCH !in codes,
            "overload-only arity differences must not use AST contract matching; codes=$codes"
        )
    }

    @Test
    fun primaryOverArityStillReportsEvenWhenOverloadsAlsoDiffer() {
        val harness = harness(
            """
            ---@overload fun(): nil
            ---@param value string
            local function normalize(value)
            end
            """.trimIndent()
        )
        val base = harness.function("normalize")
        val declaration = base.withDeclaredType(
            FunctionType(
                parameters = listOf(
                    FunctionParameter("value", PrimitiveType.STRING),
                    FunctionParameter("extra", PrimitiveType.NUMBER)
                ),
                returnType = PrimitiveType.NIL
            )
        )

        val codes = harness.checker.checkDeclaration(declaration).mapNotNull { it.code }

        assertTrue(
            PARAMETER_CONTRACT_MISMATCH in codes,
            "primary over-arity must still report even if overload arity differs; codes=$codes"
        )
    }

    // --- global annotated callable surface -----------------------------------

    @Test
    fun annotatedGlobalFunctionUnderAndOverArityReportContractMismatch() {
        val underHarness = harness(
            """
            ---@param a string
            ---@param b number
            function globalNorm(a, b)
            end
            """.trimIndent()
        )
        val under = underHarness.function("globalNorm").withDeclaredType(
            FunctionType(
                parameters = listOf(FunctionParameter("a", PrimitiveType.STRING)),
                returnType = PrimitiveType.NIL
            )
        )
        val underCodes = underHarness.checker.checkDeclaration(under).mapNotNull { it.code }

        val overHarness = harness(
            """
            ---@param a string
            function globalOnly(a)
            end
            """.trimIndent()
        )
        val over = overHarness.function("globalOnly").withDeclaredType(
            FunctionType(
                parameters = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER)
                ),
                returnType = PrimitiveType.NIL
            )
        )
        val overCodes = overHarness.checker.checkDeclaration(over).mapNotNull { it.code }

        assertContains(underCodes, PARAMETER_CONTRACT_MISMATCH)
        assertContains(overCodes, PARAMETER_CONTRACT_MISMATCH)
    }

    // --- corpus table: under / over / match ----------------------------------

    @Test
    fun arityMismatchCorpusCoversUnderOverAndMatchCases() {
        data class Case(
            val name: String,
            val source: String,
            val functionName: String,
            val resolvedParams: List<FunctionParameter>,
            val expectContractMismatch: Boolean
        )

        val cases = listOf(
            Case(
                name = "under by one",
                source = """
                    ---@param x string
                    ---@param y number
                    local function f(x, y) end
                """.trimIndent(),
                functionName = "f",
                resolvedParams = listOf(FunctionParameter("x", PrimitiveType.STRING)),
                expectContractMismatch = true
            ),
            Case(
                name = "over by one",
                source = """
                    ---@param x string
                    local function f(x) end
                """.trimIndent(),
                functionName = "f",
                resolvedParams = listOf(
                    FunctionParameter("x", PrimitiveType.STRING),
                    FunctionParameter("y", PrimitiveType.NUMBER)
                ),
                expectContractMismatch = true
            ),
            Case(
                name = "under by many",
                source = """
                    ---@param a string
                    ---@param b number
                    ---@param c boolean
                    local function f(a, b, c) end
                """.trimIndent(),
                functionName = "f",
                resolvedParams = emptyList(),
                expectContractMismatch = true
            ),
            Case(
                name = "over by many",
                source = """
                    ---@return nil
                    local function f() end
                """.trimIndent(),
                functionName = "f",
                resolvedParams = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER),
                    FunctionParameter("c", PrimitiveType.BOOLEAN)
                ),
                expectContractMismatch = true
            ),
            Case(
                name = "exact match zero",
                source = """
                    ---@return nil
                    local function f() end
                """.trimIndent(),
                functionName = "f",
                resolvedParams = emptyList(),
                expectContractMismatch = false
            ),
            Case(
                name = "exact match two",
                source = """
                    ---@param a string
                    ---@param b number
                    local function f(a, b) end
                """.trimIndent(),
                functionName = "f",
                resolvedParams = listOf(
                    FunctionParameter("a", PrimitiveType.STRING),
                    FunctionParameter("b", PrimitiveType.NUMBER)
                ),
                expectContractMismatch = false
            )
        )

        val failures = mutableListOf<String>()
        for (case in cases) {
            val harness = harness(case.source)
            val declaration = harness.function(case.functionName).withDeclaredType(
                FunctionType(parameters = case.resolvedParams, returnType = PrimitiveType.NIL)
            )
            val codes = harness.checker.checkDeclaration(declaration).mapNotNull { it.code }
            val reported = PARAMETER_CONTRACT_MISMATCH in codes
            if (reported != case.expectContractMismatch) {
                failures += "${case.name}: expected mismatch=${case.expectContractMismatch}, codes=$codes"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    // --- helpers -------------------------------------------------------------

    private fun harness(source: String): Harness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            checker = FunctionSignatureChecker(resolved),
            declarations = resolved
        )
    }

    private data class Harness(
        val checker: FunctionSignatureChecker,
        val declarations: BinderPassResult
    ) {
        fun function(name: String): BinderDeclaration {
            return declarations.declarationIndex.declarations.single {
                (it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.GLOBAL) && it.name == name
            }
        }
    }

    private fun BinderDeclaration.withDeclaredType(type: FunctionType): BinderDeclaration {
        return copy(declaredType = type)
    }

    private companion object {
        const val PARAMETER_CONTRACT_MISMATCH = "checker.function.signature.parameterContractMismatch"
    }
}
