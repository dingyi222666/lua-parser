package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.checker.CallChecker
import io.github.dingyi222666.luaparser.semantic.checker.CallFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.CallResolution
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
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
 * CallChecker colon-vs-dot implicit-self arity corpus.
 *
 * Encodes the call-site contract that higher layers (ExpressionTypeEvaluator /
 * MemberResolver) already materialize before ranking:
 *
 * - Colon (`obj:method(args)`) contributes the receiver as the first argument so
 *   signatures that declare `self` match with the remaining explicit args.
 * - Dot (`obj.method(args)`) does **not** invent a receiver argument; arity is
 *   checked against the explicit argument list only.
 * - Unknown callees fail as [CallFailureReason.NON_CALLABLE] without throwing.
 *
 * CallChecker itself is ranking-only (no indexer awareness). This corpus locks
 * the argument-list shapes that colon/dot must present. Test-only; production
 * CallChecker is out of scope (TASK-260).
 */
class CallCheckerColonDotSelfTddTest {

    private val parser = LuaParser()

    // --- colon: implicit self is part of the argument list -------------------

    @Test
    fun colonStyleArgsIncludeReceiverAndMatchSelfSignature() {
        val harness = harness()
        val widget = widgetType()
        val method = methodWithSelf(widget)

        // obj:method("x") → args = [self, "x"]
        val result = harness.check(method, listOf(widget, PrimitiveType.STRING))

        assertSuccess(result, PrimitiveType.NUMBER)
        assertEquals(2, result.selectedSignature!!.parameters.size)
        assertEquals("self", result.selectedSignature!!.parameters.first().name)
    }

    @Test
    fun colonStyleZeroExplicitArgsStillSuppliesSelf() {
        val harness = harness()
        val widget = widgetType()
        // ---@method Widget:ping(): boolean  → fun(self: Widget): boolean
        val method = FunctionType(
            parameters = listOf(FunctionParameter("self", widget)),
            returnType = PrimitiveType.BOOLEAN
        )

        // obj:ping() → args = [self]
        val result = harness.check(method, listOf(widget))

        assertSuccess(result, PrimitiveType.BOOLEAN)
    }

    @Test
    fun colonStyleMissingSelfAgainstSelfSignatureIsArityFailure() {
        // If the colon layer forgot to prepend the receiver, CallChecker must
        // reject rather than silently treating the first explicit arg as self.
        val harness = harness()
        val widget = widgetType()
        val method = methodWithSelf(widget)

        // Broken colon: only explicit "x", no receiver slot.
        val result = harness.check(method, listOf(PrimitiveType.STRING))

        assertNoMatch(result)
    }

    @Test
    fun colonStyleExtraArgBeyondSelfAndRequiredIsRejected() {
        val harness = harness()
        val widget = widgetType()
        val method = methodWithSelf(widget)

        // obj:method("x", 1) against fun(self, value: string) → closed arity fail
        val result = harness.check(
            method,
            listOf(widget, PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertNoMatch(result)
    }

    @Test
    fun colonStyleSelfPlusOptionalTailMayOmitOptional() {
        val harness = harness()
        val widget = widgetType()
        val method = FunctionType(
            parameters = listOf(
                FunctionParameter("self", widget),
                FunctionParameter("value", PrimitiveType.STRING),
                FunctionParameter("flag", PrimitiveType.BOOLEAN, optional = true)
            ),
            returnType = PrimitiveType.NIL
        )

        assertSuccess(
            harness.check(method, listOf(widget, PrimitiveType.STRING)),
            PrimitiveType.NIL
        )
        assertSuccess(
            harness.check(method, listOf(widget, PrimitiveType.STRING, PrimitiveType.BOOLEAN)),
            PrimitiveType.NIL
        )
        // colon without self still under-arity
        assertNoMatch(harness.check(method, listOf(PrimitiveType.STRING)))
    }

    // --- dot: do not invent self ---------------------------------------------

    @Test
    fun dotStyleArgsDoNotInventSelfAgainstPlainFieldFunction() {
        val harness = harness()
        // Field-style: fun(value: string) — no self parameter.
        val fieldFn = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
            returnType = PrimitiveType.BOOLEAN
        )

        // obj.method("x") → args = ["x"] only; CallChecker must not require self.
        val result = harness.check(fieldFn, listOf(PrimitiveType.STRING))

        assertSuccess(result, PrimitiveType.BOOLEAN)
    }

    @Test
    fun dotStyleInventedSelfAgainstPlainFieldFunctionIsRejected() {
        val harness = harness()
        val widget = widgetType()
        val fieldFn = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
            returnType = PrimitiveType.BOOLEAN
        )

        // Incorrectly prepending self for a dot call invents arity/type mismatch.
        val result = harness.check(fieldFn, listOf(widget, PrimitiveType.STRING))

        assertNoMatch(result)
    }

    @Test
    fun dotStyleAgainstSelfSignatureRequiresExplicitReceiver() {
        val harness = harness()
        val widget = widgetType()
        val method = methodWithSelf(widget)

        // obj.method("x") without explicit self → under-arity
        assertNoMatch(harness.check(method, listOf(PrimitiveType.STRING)))

        // obj.method(obj, "x") — explicit self is legal for a self-bearing signature
        assertSuccess(
            harness.check(method, listOf(widget, PrimitiveType.STRING)),
            PrimitiveType.NUMBER
        )
    }

    @Test
    fun dotStyleZeroArgsAgainstSelfOnlySignatureIsArityFailure() {
        val harness = harness()
        val widget = widgetType()
        val method = FunctionType(
            parameters = listOf(FunctionParameter("self", widget)),
            returnType = PrimitiveType.BOOLEAN
        )

        // obj.method() — no invented self
        assertNoMatch(harness.check(method, emptyList()))
        // obj.method(obj) — explicit receiver succeeds
        assertSuccess(harness.check(method, listOf(widget)), PrimitiveType.BOOLEAN)
    }

    // --- MemberResolver preferMethod binds self for colon, not for plain field

    @Test
    fun memberResolverColonPreferMethodBindsSelfThenColonArgsMatch() {
        val harness = docHarness(
            """
            ---@class Widget
            ---@method Widget:greet(name: string): string
            """.trimIndent()
        )
        val widget = harness.classType("Widget")
        val bound = harness.memberResolver.resolveMember(
            baseType = widget,
            memberName = "greet",
            preferMethod = true,
            lexicalScopeId = harness.scopeId
        )
        assertTrue(bound.isSuccess, "colon preferMethod must resolve greet")
        val methodType = bound.type!!
        // Bound signature should accept receiver as first argument.
        val first = (methodType as? FunctionType)?.parameters?.firstOrNull()
            ?: (methodType as? OverloadedFunctionType)?.callSignatures?.firstOrNull()?.parameters?.firstOrNull()
        assertNotNull(first)
        assertEquals("self", first.name)

        // obj:greet("hi") → [self, "hi"]
        val colon = harness.checker.checkCall(
            methodType,
            listOf(widget, PrimitiveType.STRING),
            harness.scopeId
        )
        assertSuccess(colon, PrimitiveType.STRING)

        // Forgotten self under colon binding still fails arity.
        assertNoMatch(
            harness.checker.checkCall(methodType, listOf(PrimitiveType.STRING), harness.scopeId)
        )
    }

    @Test
    fun memberResolverDotPreferFieldDoesNotForceSelfSlotOnFieldFunction() {
        // Documented as a field function (no method table entry): fun(name: string)
        val harness = docHarness(
            """
            ---@class Widget
            ---@field greet fun(name: string): string
            """.trimIndent()
        )
        val widget = harness.classType("Widget")
        val field = harness.memberResolver.resolveMember(
            baseType = widget,
            memberName = "greet",
            preferMethod = false,
            lexicalScopeId = harness.scopeId
        )
        assertTrue(field.isSuccess, "dot prefer field must resolve greet")
        val fieldType = field.type!!

        // obj.greet("hi") → ["hi"] only — no invented self
        val dot = harness.checker.checkCall(
            fieldType,
            listOf(PrimitiveType.STRING),
            harness.scopeId
        )
        assertSuccess(dot, PrimitiveType.STRING)

        // Inventing self for a plain field function is rejected.
        assertNoMatch(
            harness.checker.checkCall(
                fieldType,
                listOf(widget, PrimitiveType.STRING),
                harness.scopeId
            )
        )
    }

    @Test
    fun overloadedMethodSelectsBranchAfterColonSelfIsSupplied() {
        val harness = docHarness(
            """
            ---@class Widget
            ---@method Widget:pick(): number
            ---@overload fun(self: Widget, value: string): string
            """.trimIndent()
        )
        val widget = harness.classType("Widget")
        val methodType = harness.memberResolver.resolveMember(
            widget,
            "pick",
            preferMethod = true,
            harness.scopeId
        ).type!!

        val noArg = harness.checker.checkCall(methodType, listOf(widget), harness.scopeId)
        val stringArg = harness.checker.checkCall(
            methodType,
            listOf(widget, PrimitiveType.STRING),
            harness.scopeId
        )

        assertSuccess(noArg, PrimitiveType.NUMBER)
        assertSuccess(stringArg, PrimitiveType.STRING)

        // Dot without self: neither overload matches
        assertNoMatch(
            harness.checker.checkCall(methodType, emptyList(), harness.scopeId)
        )
        assertNoMatch(
            harness.checker.checkCall(methodType, listOf(PrimitiveType.STRING), harness.scopeId)
        )
    }

    // --- unknown callee: no crash -------------------------------------------

    @Test
    fun unknownCalleeIsNonCallableWithoutCrashForEmptyArgs() {
        val harness = harness()
        val result = harness.check(UnknownType, emptyList())

        assertFalse(result.isSuccess)
        assertEquals(CallFailureReason.NON_CALLABLE, result.failureReason)
        assertNull(result.selectedSignature)
        assertNull(result.returnType)
    }

    @Test
    fun unknownCalleeIsNonCallableWithoutCrashForColonShapedArgs() {
        // Colon layer may still build [receiver, ...]; CallChecker must not throw.
        val harness = harness()
        val widget = widgetType()
        val result = harness.check(
            UnknownType,
            listOf(widget, PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertFalse(result.isSuccess)
        assertEquals(CallFailureReason.NON_CALLABLE, result.failureReason)
        assertNull(result.selectedSignature)
    }

    @Test
    fun unknownCalleeIsNonCallableWithoutCrashForDotShapedArgs() {
        val harness = harness()
        val result = harness.check(
            UnknownType,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertFalse(result.isSuccess)
        assertEquals(CallFailureReason.NON_CALLABLE, result.failureReason)
    }

    @Test
    fun nonCallablePrimitiveCalleeDoesNotCrashUnderColonOrDotArgShapes() {
        val harness = harness()
        val widget = widgetType()

        val colonShaped = harness.check(PrimitiveType.NUMBER, listOf(widget))
        val dotShaped = harness.check(PrimitiveType.NUMBER, listOf(PrimitiveType.STRING))

        assertEquals(CallFailureReason.NON_CALLABLE, colonShaped.failureReason)
        assertEquals(CallFailureReason.NON_CALLABLE, dotShaped.failureReason)
    }

    // --- corpus table: colon vs dot argument shapes --------------------------

    @Test
    fun colonVsDotSelfArityCorpusTable() {
        data class Case(
            val name: String,
            val parameters: List<FunctionParameter>,
            val arguments: List<Type>,
            val expectSuccess: Boolean,
            val note: String
        )

        val widget = widgetType()
        val cases = listOf(
            Case(
                name = "colon self+value",
                parameters = listOf(
                    FunctionParameter("self", widget),
                    FunctionParameter("value", PrimitiveType.STRING)
                ),
                arguments = listOf(widget, PrimitiveType.STRING),
                expectSuccess = true,
                note = "obj:method(x)"
            ),
            Case(
                name = "colon self only",
                parameters = listOf(FunctionParameter("self", widget)),
                arguments = listOf(widget),
                expectSuccess = true,
                note = "obj:ping()"
            ),
            Case(
                name = "colon forgot self",
                parameters = listOf(
                    FunctionParameter("self", widget),
                    FunctionParameter("value", PrimitiveType.STRING)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = false,
                note = "broken colon without receiver slot"
            ),
            Case(
                name = "dot plain field no self",
                parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                note = "obj.method(x) field function"
            ),
            Case(
                name = "dot invents self on field fn",
                parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
                arguments = listOf(widget, PrimitiveType.STRING),
                expectSuccess = false,
                note = "dot must not invent self"
            ),
            Case(
                name = "dot against self-sig without receiver",
                parameters = listOf(
                    FunctionParameter("self", widget),
                    FunctionParameter("value", PrimitiveType.STRING)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = false,
                note = "obj.method(x) missing explicit self"
            ),
            Case(
                name = "dot explicit self against self-sig",
                parameters = listOf(
                    FunctionParameter("self", widget),
                    FunctionParameter("value", PrimitiveType.STRING)
                ),
                arguments = listOf(widget, PrimitiveType.STRING),
                expectSuccess = true,
                note = "obj.method(obj, x)"
            ),
            Case(
                name = "colon extra beyond closed self+value",
                parameters = listOf(
                    FunctionParameter("self", widget),
                    FunctionParameter("value", PrimitiveType.STRING)
                ),
                arguments = listOf(widget, PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectSuccess = false,
                note = "obj:method(x, extra)"
            ),
            Case(
                name = "colon self + optional omit",
                parameters = listOf(
                    FunctionParameter("self", widget),
                    FunctionParameter("flag", PrimitiveType.BOOLEAN, optional = true)
                ),
                arguments = listOf(widget),
                expectSuccess = true,
                note = "obj:toggle() optional tail"
            ),
            Case(
                name = "dot empty against self-only",
                parameters = listOf(FunctionParameter("self", widget)),
                arguments = emptyList(),
                expectSuccess = false,
                note = "obj.method() does not invent self"
            )
        )

        val harness = harness()
        val failures = mutableListOf<String>()
        for (case in cases) {
            val callable = FunctionType(parameters = case.parameters, returnType = PrimitiveType.NIL)
            val result = harness.check(callable, case.arguments)
            if (result.isSuccess != case.expectSuccess) {
                failures += "${case.name} (${case.note}): expected success=${case.expectSuccess}, " +
                    "got success=${result.isSuccess} reason=${result.failureReason}"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    // --- harness -------------------------------------------------------------

    private fun harness(): Harness {
        val chunk = parser.parse("")
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            checker = CallChecker(resolved),
            memberResolver = MemberResolver(resolved),
            declarations = resolved,
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private fun docHarness(source: String): Harness {
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

    private fun widgetType(): Type {
        // Stand-in receiver type; assignability only needs identity for self checks.
        return PrimitiveType.TABLE
    }

    private fun methodWithSelf(selfType: Type): FunctionType = FunctionType(
        parameters = listOf(
            FunctionParameter("self", selfType),
            FunctionParameter("value", PrimitiveType.STRING)
        ),
        returnType = PrimitiveType.NUMBER
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
        val memberResolver: MemberResolver,
        val declarations: BinderPassResult,
        val scopeId: ScopeId
    ) {
        fun check(callable: Type, arguments: List<Type>): CallResolution {
            return checker.checkCall(callable, arguments, scopeId)
        }

        fun classType(name: String): Type {
            return declarations.declarationIndex.declarations.single {
                it.kind == DeclarationKind.CLASS && it.name == name
            }.declaredType!!
        }
    }
}
