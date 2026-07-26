package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.checker.CallChecker
import io.github.dingyi222666.luaparser.semantic.checker.CallFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.checker.ValueSequence
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CallCheckerTest {

    private val parser = LuaParser()

    @Test
    fun selectsPlainFunctionAndDocOverload() {
        val harness = harness(
            """
            ---@overload fun(value: string): string
            ---@param value number
            ---@return number
            local function normalize(value)
                return value
            end
            """.trimIndent()
        )
        val declaration = function(harness, "normalize")

        val numberCall = harness.checker.checkCall(declaration.declaredType!!, listOf(PrimitiveType.NUMBER), harness.scopeId, declaration)
        val stringCall = harness.checker.checkCall(declaration.declaredType!!, listOf(PrimitiveType.STRING), harness.scopeId, declaration)

        assertSame(PrimitiveType.NUMBER, numberCall.returnType)
        assertSame(PrimitiveType.STRING, stringCall.returnType)
    }

    @Test
    fun aliasToOverloadedCallableSelectsMatchingBranch() {
        val harness = harness("")
        val choice = AliasType(
            "Choice",
            OverloadedFunctionType(
                listOf(
                    FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)), returnType = PrimitiveType.STRING),
                    FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.NUMBER)), returnType = PrimitiveType.NUMBER)
                )
            )
        )

        val stringCall = harness.checker.checkCall(choice, listOf(PrimitiveType.STRING), harness.scopeId)
        val numberCall = harness.checker.checkCall(choice, listOf(PrimitiveType.NUMBER), harness.scopeId)

        assertSame(PrimitiveType.STRING, stringCall.returnType)
        assertSame(PrimitiveType.NUMBER, numberCall.returnType)
    }

    @Test
    fun rankingPrefersExactMatchOverWiderOrVarargMatch() {
        val callable = OverloadedFunctionType(
            listOf(
                FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)), returnType = PrimitiveType.STRING),
                FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.ANY)), returnType = PrimitiveType.NUMBER),
                FunctionType(parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)), returnType = PrimitiveType.BOOLEAN)
            )
        )
        val harness = harness("")

        val result = harness.checker.checkCall(callable, listOf(PrimitiveType.STRING), harness.scopeId)

        assertSame(PrimitiveType.STRING, result.returnType)
        assertFalse(result.ambiguous)
    }

    @Test
    fun colonStyleSelfArgumentAndAppliedTypesResolve() {
        val harness = harness(
            """
            ---@class Box<T>
            ---@field value T
            ---@method Box:get(): T
            ---@alias Mapper<T> fun(value: T): T
            """.trimIndent()
        )
        val appliedBox = AppliedType("Box", listOf(PrimitiveType.STRING))
        val methodType = harness.memberResolver.resolveMember(appliedBox, "get", true, harness.scopeId).type!!

        val methodCall = harness.checker.checkCall(methodType, listOf(appliedBox), harness.scopeId)
        val aliasCall = harness.checker.checkCall(AppliedType("Mapper", listOf(PrimitiveType.STRING)), listOf(PrimitiveType.STRING), harness.scopeId)

        assertSame(PrimitiveType.STRING, methodCall.returnType)
        assertSame(PrimitiveType.STRING, aliasCall.returnType)
    }

    @Test
    fun returnsStructuredFailuresAndDeterministicAmbiguity() {
        val harness = harness("")
        val nonCallable = harness.checker.checkCall(PrimitiveType.NUMBER, emptyList(), harness.scopeId)
        val arityMismatch = harness.checker.checkCall(
            FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.STRING))),
            emptyList(),
            harness.scopeId
        )
        val parameterMismatch = harness.checker.checkCall(
            FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.STRING))),
            listOf(PrimitiveType.NUMBER),
            harness.scopeId
        )
        val ambiguous = harness.checker.checkCall(
            OverloadedFunctionType(
                listOf(
                    FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)), returnType = PrimitiveType.STRING),
                    FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)), returnType = PrimitiveType.NUMBER)
                )
            ),
            listOf(PrimitiveType.STRING),
            harness.scopeId
        )

        assertEquals(CallFailureReason.NON_CALLABLE, nonCallable.failureReason)
        assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, arityMismatch.failureReason)
        assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, parameterMismatch.failureReason)
        assertTrue(ambiguous.ambiguous)
        assertEquals(CallFailureReason.AMBIGUOUS_MATCH, ambiguous.failureReason)
        assertSame(PrimitiveType.STRING, ambiguous.returnType)
    }

    @Test
    fun valueSequenceKeepsLastArgumentExpansionBehavior() {
        val harness = harness("")
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

        assertSame(PrimitiveType.BOOLEAN, result.returnType)
        assertTrue(result.isSuccess)
    }

    @Test
    fun appliedInheritedMethodCallUsesSubclassTypeArguments() {
        val harness = harness(
            """
            ---@class Base<T>
            ---@method Base:get(): T
            ---@class Box<T>: Base<T>
            """.trimIndent()
        )
        val appliedBox = AppliedType("Box", listOf(PrimitiveType.STRING))
        val methodType = harness.memberResolver.resolveMember(appliedBox, "get", true, harness.scopeId).type!!

        val result = harness.checker.checkCall(methodType, listOf(appliedBox), harness.scopeId)

        assertSame(PrimitiveType.STRING, result.returnType)
    }

    @Test
    fun aliasWrappedAppliedSubclassMethodCallUsesInheritedReturnType() {
        val harness = harness(
            """
            ---@class Base<T>
            ---@method Base:get(): T
            ---@class Box<T>: Base<T>
            ---@alias StringBox Box<string>
            """.trimIndent()
        )
        val stringBox = alias(harness, "StringBox")
        val methodType = harness.memberResolver.resolveMember(
            stringBox,
            "get",
            true,
            harness.scopeId
        ).type!!

        val result = harness.checker.checkCall(
            methodType,
            listOf(stringBox),
            harness.scopeId
        )

        assertSame(PrimitiveType.STRING, result.returnType)
    }

    @Test
    fun docOnlyOverloadedMethodSelectsMatchingOverload() {
        val harness = harness(
            """
            ---@class Widget
            ---@method Widget:pick(): number
            ---@overload fun(self: Widget, value: string): string
            """.trimIndent()
        )
        val widget = harness.declarations.declarationIndex.declarations.single {
            it.kind == DeclarationKind.CLASS && it.name == "Widget"
        }.declaredType!!
        val methodType = harness.memberResolver.resolveMember(widget, "pick", true, harness.scopeId).type!!

        val noArgResult = harness.checker.checkCall(methodType, listOf(widget), harness.scopeId)
        val stringArgResult = harness.checker.checkCall(methodType, listOf(widget, PrimitiveType.STRING), harness.scopeId)

        assertSame(PrimitiveType.NUMBER, noArgResult.returnType)
        assertSame(PrimitiveType.STRING, stringArgResult.returnType)
    }

    @Test
    fun genericJavaContainerOverloadInstantiatesItsTableReturn() {
        val harness = harness("")
        val element = TypeParameterType("T")
        val listClass = JavaClassType(
            javaName = JavaTypeName(packageName = "java.util", simpleNames = listOf("List")),
            typeParameters = listOf(TypeParameterType("E"))
        )
        val callable = OverloadedFunctionType(
            listOf(
                FunctionType(
                    parameters = listOf(FunctionParameter("object", PrimitiveType.ANY)),
                    returnType = PrimitiveType.TABLE
                ),
                FunctionType(
                    parameters = listOf(FunctionParameter("object", JavaInstanceType(listClass, listOf(element)))),
                    returnType = TableType(
                        indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, element)
                    ),
                    typeParameters = listOf(element)
                )
            )
        )

        val result = harness.checker.checkCall(
            callable,
            listOf(JavaInstanceType(listClass, listOf(PrimitiveType.STRING))),
            harness.scopeId
        )

        val table = assertIs<TableType>(result.returnType)
        assertSame(PrimitiveType.STRING, table.indexSignature?.valueType)
    }

    private fun harness(source: String): Harness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            checker = CallChecker(resolved),
            memberResolver = MemberResolver(resolved),
            declarations = resolved,
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private fun function(harness: Harness, name: String): BinderDeclaration {
        return harness.declarations.declarationIndex.declarations.single {
            it.kind == DeclarationKind.FUNCTION && it.name == name
        }
    }

    private fun alias(harness: Harness, name: String): AliasType {
        return harness.declarations.declarationIndex.declarations.single {
            it.kind == DeclarationKind.TYPE_ALIAS && it.name == name
        }.declaredType as AliasType
    }

    private data class Harness(
        val checker: CallChecker,
        val memberResolver: MemberResolver,
        val declarations: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult,
        val scopeId: io.github.dingyi222666.luaparser.semantic.binder.ScopeId
    )
}
