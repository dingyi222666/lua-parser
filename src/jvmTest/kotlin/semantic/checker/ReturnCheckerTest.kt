package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.ReturnChecker
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReturnCheckerTest {

    private val parser = LuaParser()

    @Test
    fun acceptsMatchingSingleReturnType() {
        val harness = harness(
            """
            ---@return number
            local function normalize()
                return 123456
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun reportsMissingReturnValueAsNilAgainstDeclaredNonNil() {
        val harness = harness(
            """
            ---@return string
            local function normalize()
                return
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "nil")
    }

    @Test
    fun acceptsImplicitNilWhenDeclaredReturnIsNil() {
        val harness = harness(
            """
            ---@return nil
            local function normalize()
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun acceptsExactMultiReturnTuple() {
        val harness = harness(
            """
            ---@return string, number
            local function normalize()
                return "x", 1
            end
            """.trimIndent()
        )

        assertTrue(harness.checker.checkDeclaration(harness.function("normalize")).isEmpty())
    }

    @Test
    fun reportsSecondSlotMismatchInMultiReturn() {
        val harness = harness(
            """
            ---@return string, number
            local function normalize()
                return "x", "bad"
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertEquals(1, diagnostics.size)
        assertContains(diagnostics.single().message, "Return value 2")
    }

    @Test
    fun reportsExtraReturnedValuesAgainstSingleDeclaredReturn() {
        val harness = harness(
            """
            ---@return string
            local function normalize()
                return "x", 1
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.extraValues", diagnostics.single().code)
    }

    @Test
    fun treatsNonFinalReturnCallAsFirstValueOnly() {
        val harness = harness(
            """
            ---@return string, number
            local function pair()
                return "x", 2
            end

            ---@return string, number
            local function wrap()
                return pair(), 1
            end
            """.trimIndent()
        )

        assertTrue(harness.checker.checkDeclaration(harness.function("wrap")).isEmpty())
    }

    @Test
    fun expandsFinalReturnCallMultiReturn() {
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

        assertTrue(harness.checker.checkDeclaration(harness.function("wrap")).isEmpty())
    }

    @Test
    fun expandsFinalReturnVarargAsOpenTail() {
        val harness = harness(
            """
            local function passthrough(value)
                return value
            end
            """.trimIndent()
        )

        val declaration = harness.function("passthrough").copy(
            declaredType = FunctionType(
                parameters = listOf(FunctionParameter("value", VarargType(PrimitiveType.STRING), vararg = true)),
                returnType = VarargType(PrimitiveType.STRING)
            )
        )

        assertTrue(harness.checker.checkDeclaration(declaration).isEmpty())
    }

    @Test
    fun validatesExtraTailValuesAgainstDeclaredVarargReturn() {
        val harness = harness(
            """
            ---@return number, string...
            local function normalize()
                return 1, "x", 2
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertEquals(1, diagnostics.size)
        assertContains(diagnostics.single().message, "Return value 3")
    }

    @Test
    fun checksEachBranchReturnIndividually() {
        val harness = harness(
            """
            ---@param flag boolean
            ---@return string
            local function normalize(flag)
                if flag then
                    return "x"
                end
                return 1
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
    }

    @Test
    fun reportsImplicitNilOnMixedPathFallthrough() {
        val harness = harness(
            """
            ---@param flag boolean
            ---@return string
            local function normalize(flag)
                if flag then
                    return "x"
                end
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "nil")
    }

    @Test
    fun doesNotDescendIntoNestedFunctionReturns() {
        val harness = harness(
            """
            ---@return string
            local function normalize()
                local function nested()
                    return 1
                end
                return "x"
            end
            """.trimIndent()
        )

        assertTrue(harness.checker.checkDeclaration(harness.function("normalize")).isEmpty())
    }

    @Test
    fun usesResolvedGenericConstraintForReturnAssignability() {
        val harness = harness(
            """
            ---@generic T: string
            ---@param value T
            ---@return T
            local function normalize(value)
                return 1
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertEquals(1, diagnostics.size)
        assertContains(diagnostics.single().message, "expected T")
    }

    @Test
    fun acceptsInheritedGenericMethodReturnFromAppliedClass() {
        val harness = harness(
            """
            ---@class Base<T>
            ---@method Base:get(): T
            ---@class Box<T>: Base<T>

            ---@return string
            local function read()
                ---@type Box<string>
                local box = {}
                return box:get()
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("read"))

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun keepsPrimaryReturnCheckingWhenOverloadsArePresent() {
        val harness = harness(
            """
            ---@overload fun(): number
            ---@return string
            local function normalize()
                return 1
            end
            """.trimIndent()
        )

        val diagnostics = harness.checker.checkDeclaration(harness.function("normalize"))

        assertEquals(1, diagnostics.size)
        assertEquals("checker.function.return.typeMismatch", diagnostics.single().code)
        assertContains(diagnostics.single().message, "expected string")
    }

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
        val declarations: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
    ) {
        fun function(name: String): BinderDeclaration {
            return declarations.declarationIndex.declarations.single {
                (it.kind == DeclarationKind.FUNCTION || it.kind == DeclarationKind.GLOBAL) && it.name == name
            }
        }
    }
}
