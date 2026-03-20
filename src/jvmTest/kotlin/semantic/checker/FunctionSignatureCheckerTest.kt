package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.FunctionSignatureChecker
import io.github.dingyi222666.luaparser.semantic.checker.resolveOwningFunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FunctionSignatureCheckerTest {

    private val parser = LuaParser()

    @Test
    fun reportsUnknownParamTagName() {
        val harness = harness(
            """
            ---@param missing string
            local function normalize(value)
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.signature.unknownParam", diagnostics.single().code)
        assertContains(diagnostics.single().message, "missing")
    }

    @Test
    fun acceptsParamTagsThatMatchDeclaredParameters() {
        val harness = harness(
            """
            ---@param value string
            ---@param count? number
            local function normalize(value, count)
            end
            """.trimIndent()
        )


        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun reportsVarargTagOnNonVarargParameter() {
        val harness = harness("local function normalize(value) end")
        val declaration = harness.function("normalize").copy(
            declaredType = FunctionType(
                parameters = listOf(FunctionParameter("value", PrimitiveType.STRING, vararg = true)),
                returnType = PrimitiveType.NIL
            )
        )

        val diagnostics = harness.checker.checkDeclaration(declaration)

        assertEquals(listOf("checker.function.signature.namedVararg"), diagnostics.mapNotNull { it.code })
    }

    @Test
    fun reportsVarargTagWhenFunctionHasNoTrailingDots() {
        val harness = harness(
            """
            ---@param ... string
            local function normalize(value)
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertEquals(listOf("checker.function.signature.unknownParam"), diagnostics.mapNotNull { it.code })
    }

    @Test
    fun reportsRequiredParameterAfterOptionalParameter() {
        val harness = harness("local function normalize(first, second) end")
        val declaration = harness.function("normalize").copy(
            declaredType = FunctionType(
                parameters = listOf(
                    FunctionParameter("first", PrimitiveType.STRING, optional = true),
                    FunctionParameter("second", PrimitiveType.NUMBER)
                ),
                returnType = PrimitiveType.NIL
            )
        )

        val diagnostics = harness.checker.checkDeclaration(declaration)

        assertEquals(listOf("checker.function.signature.requiredAfterOptional"), diagnostics.mapNotNull { it.code })
    }

    @Test
    fun acceptsOptionalTailParameters() {
        val harness = harness("local function normalize(first, second) end")
        val declaration = harness.function("normalize").copy(
            declaredType = FunctionType(
                parameters = listOf(
                    FunctionParameter("first", PrimitiveType.STRING),
                    FunctionParameter("second", PrimitiveType.NUMBER, optional = true)
                ),
                returnType = PrimitiveType.NIL
            )
        )

        val diagnostics = harness.checker.checkDeclaration(declaration)

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun rejectsOptionalAndVarargOnSameParameter() {
        val harness = harness("local function normalize(value) end")
        val declaration = harness.function("normalize").copy(
            declaredType = FunctionType(
                parameters = listOf(FunctionParameter("value", PrimitiveType.STRING, optional = true, vararg = true)),
                returnType = PrimitiveType.NIL
            )
        )

        val diagnostics = harness.checker.checkDeclaration(declaration)

        assertEquals(
            listOf("checker.function.signature.namedVararg", "checker.function.signature.optionalVararg"),
            diagnostics.mapNotNull { it.code }
        )
    }

    @Test
    fun ignoresFunctionsWithoutRelevantDocs() {
        val harness = harness("local function normalize(value) return value end")

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun resolvesOwningFunctionDeclarationFromBinderDeclaration() {
        val harness = harness("local function normalize(value) return value end")

        val function = resolveOwningFunctionDeclaration(harness.declarations, harness.function("normalize"))

        assertNotNull(function)
        assertEquals("normalize", (function.identifier as io.github.dingyi222666.luaparser.parser.ast.node.Identifier).name)
    }

    @Test
    fun validatesMalformedOverloadShapesWithoutAstContractMismatch() {
        val harness = harness(
            """
            ---@overload fun(value: string..., extra: number): nil
            ---@overload fun(first?: string, second: number): nil
            ---@param value string
            local function normalize(value)
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))
        val codes = diagnostics.mapNotNull { it.code }

        assertContains(codes, "checker.function.signature.namedVararg")
        assertContains(codes, "checker.function.signature.varargNotLast")
        assertContains(codes, "checker.function.signature.requiredAfterOptional")
        assertTrue("checker.function.signature.parameterContractMismatch" !in codes)
    }

    @Test
    fun acceptsValidOverloadArityDifferences() {
        val harness = harness(
            """
            ---@overload fun(): nil
            ---@overload fun(value: string, count?: number): nil
            local function normalize(value)
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertTrue(diagnostics.none { it.code == "checker.function.signature.parameterContractMismatch" })
    }

    @Test
    fun validatesMalformedOverloadShapesOnDocOnlyMethodDeclarations() {
        val harness = harness(
            """
            ---@class Widget
            ---@method Widget:pick(): string
            ---@overload fun(self: Widget, value: string..., extra: number): string
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.method("pick"))
        val codes = diagnostics.mapNotNull { it.code }

        assertContains(codes, "checker.function.signature.namedVararg")
        assertContains(codes, "checker.function.signature.varargNotLast")
    }

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
        val declarations: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
    ) {
        fun function(name: String): BinderDeclaration {
            return declarations.declarationIndex.declarations.single {
                (it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.GLOBAL) && it.name == name
            }
        }

        fun method(name: String): BinderDeclaration {
            return declarations.declarationIndex.declarations.single {
                it.kind == DeclarationKind.METHOD && it.name == name
            }
        }
    }
}
