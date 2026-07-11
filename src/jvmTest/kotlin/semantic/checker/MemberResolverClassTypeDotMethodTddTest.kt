package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.checker.MemberAccessKind
import io.github.dingyi222666.luaparser.semantic.checker.MemberFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolution
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * MemberResolver ClassType preferMethod corpus for `.` vs `:` access (TASK-385).
 *
 * Locks the H2 preferMethod path used by Android-Lua `activity.getLuaDir`:
 * ClassType methods must resolve as [MemberAccessKind.METHOD] (→ [SymbolKind.METHOD])
 * with function-shaped types under **both** colon (`preferMethod = true`) and
 * dot (`preferMethod = false`). Also covers [ClassType.getAllMethods] inheritance
 * from [ClassType.superClass].
 *
 * Test-only; no production edits. Verification deferred to review / TASK-043:
 * `jvmTest --tests semantic.checker.MemberResolverClassTypeDotMethodTddTest`
 */
class MemberResolverClassTypeDotMethodTddTest {

    private val parser = LuaParser()

    // --- method-only ClassType: both . and : are METHOD + function-shaped ----

    @Test
    fun classMethodOnlyResolvesAsMethodUnderColonPreferMethod() {
        val harness = resolver()
        val getLuaDir = FunctionType(returnType = PrimitiveType.STRING)
        val activity = ClassType(
            name = "LuaActivity",
            methods = mapOf("getLuaDir" to getLuaDir)
        )

        val colon = harness.resolver.resolveMember(
            activity, "getLuaDir", preferMethod = true, lexicalScopeId = harness.scopeId
        )

        assertTrue(colon.isSuccess)
        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertEquals(SymbolKind.METHOD, colon.accessKind.toApiSymbolKind())
        assertFunctionShaped(colon.type)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(colon.type).returnType)
    }

    @Test
    fun classMethodOnlyResolvesAsMethodUnderDotPreferMethodFalse() {
        val harness = resolver()
        val getLuaDir = FunctionType(returnType = PrimitiveType.STRING)
        val activity = ClassType(
            name = "LuaActivity",
            methods = mapOf("getLuaDir" to getLuaDir)
        )

        // activity.getLuaDir — preferMethod=false must still hit methods map
        // when there is no same-named field (H2 lock for Android-Lua hover).
        val dot = harness.resolver.resolveMember(
            activity, "getLuaDir", preferMethod = false, lexicalScopeId = harness.scopeId
        )

        assertTrue(dot.isSuccess, "dot access must resolve ClassType method; failure=${dot.failureReason}")
        assertEquals(MemberAccessKind.METHOD, dot.accessKind)
        assertEquals(SymbolKind.METHOD, dot.accessKind.toApiSymbolKind())
        assertFunctionShaped(dot.type)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(dot.type).returnType)
    }

    @Test
    fun classMethodResolvesIdenticallyUnderDotAndColonAccessKinds() {
        val harness = resolver()
        val method = FunctionType(
            parameters = listOf(FunctionParameter("path", PrimitiveType.STRING, optional = true)),
            returnType = PrimitiveType.STRING
        )
        val activity = ClassType(name = "LuaActivity", methods = mapOf("getLuaDir" to method))

        val colon = harness.resolver.resolveMember(activity, "getLuaDir", preferMethod = true, harness.scopeId)
        val dot = harness.resolver.resolveMember(activity, "getLuaDir", preferMethod = false, harness.scopeId)

        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertEquals(MemberAccessKind.METHOD, dot.accessKind)
        assertEquals(SymbolKind.METHOD, colon.accessKind.toApiSymbolKind())
        assertEquals(SymbolKind.METHOD, dot.accessKind.toApiSymbolKind())
        assertFunctionShaped(colon.type)
        assertFunctionShaped(dot.type)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(colon.type).returnType)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(dot.type).returnType)
    }

    // --- field vs method preferMethod priority on ClassType -------------------

    @Test
    fun classPreferMethodTruePrefersMethodOverSameNamedField() {
        val harness = resolver()
        val fieldFn = FunctionType(returnType = PrimitiveType.NUMBER)
        val methodFn = FunctionType(returnType = PrimitiveType.BOOLEAN)
        val cls = ClassType(
            name = "Widget",
            fields = mapOf("run" to fieldFn),
            methods = mapOf("run" to methodFn)
        )

        val colon = harness.resolver.resolveMember(cls, "run", preferMethod = true, harness.scopeId)

        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertEquals(SymbolKind.METHOD, colon.accessKind.toApiSymbolKind())
        assertSame(PrimitiveType.BOOLEAN, assertIs<FunctionType>(colon.type).returnType)
    }

    @Test
    fun classPreferMethodFalsePrefersFieldOverSameNamedMethod() {
        val harness = resolver()
        val fieldFn = FunctionType(returnType = PrimitiveType.NUMBER)
        val methodFn = FunctionType(returnType = PrimitiveType.BOOLEAN)
        val cls = ClassType(
            name = "Widget",
            fields = mapOf("run" to fieldFn),
            methods = mapOf("run" to methodFn)
        )

        val dot = harness.resolver.resolveMember(cls, "run", preferMethod = false, harness.scopeId)

        assertEquals(MemberAccessKind.FIELD, dot.accessKind)
        assertEquals(SymbolKind.FIELD, dot.accessKind.toApiSymbolKind())
        assertSame(PrimitiveType.NUMBER, assertIs<FunctionType>(dot.type).returnType)
    }

    @Test
    fun classCallableFieldOnlyStaysFieldEvenUnderColon() {
        val harness = resolver()
        val callableField = FunctionType(returnType = PrimitiveType.STRING)
        val cls = ClassType(
            name = "Holder",
            fields = mapOf("callback" to callableField)
        )

        val colon = harness.resolver.resolveMember(cls, "callback", preferMethod = true, harness.scopeId)
        val dot = harness.resolver.resolveMember(cls, "callback", preferMethod = false, harness.scopeId)

        assertEquals(MemberAccessKind.FIELD, colon.accessKind)
        assertEquals(MemberAccessKind.FIELD, dot.accessKind)
        assertEquals(SymbolKind.FIELD, colon.accessKind.toApiSymbolKind())
        assertFunctionShaped(colon.type)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(colon.type).returnType)
    }

    // --- getAllMethods inheritance from superClass ----------------------------

    @Test
    fun getAllMethodsIncludesSuperClassMethods() {
        val baseMethod = FunctionType(returnType = PrimitiveType.STRING)
        val derivedMethod = FunctionType(returnType = PrimitiveType.NUMBER)
        val base = ClassType(name = "Base", methods = mapOf("getLuaDir" to baseMethod, "shared" to baseMethod))
        val derived = ClassType(
            name = "LuaActivity",
            methods = mapOf("newActivity" to derivedMethod),
            superClass = base
        )

        val all = derived.getAllMethods()

        assertTrue("getLuaDir" in all, "inherited getLuaDir missing: ${all.keys}")
        assertTrue("shared" in all, "inherited shared missing: ${all.keys}")
        assertTrue("newActivity" in all, "own newActivity missing: ${all.keys}")
        assertSame(baseMethod, all["getLuaDir"])
        assertSame(derivedMethod, all["newActivity"])
    }

    @Test
    fun getAllMethodsDerivedOverridesSuperClassSameName() {
        val baseMethod = FunctionType(returnType = PrimitiveType.STRING)
        val overrideMethod = FunctionType(returnType = PrimitiveType.BOOLEAN)
        val base = ClassType(name = "Base", methods = mapOf("getLuaDir" to baseMethod))
        val derived = ClassType(
            name = "LuaActivity",
            methods = mapOf("getLuaDir" to overrideMethod),
            superClass = base
        )

        val all = derived.getAllMethods()
        assertSame(overrideMethod, all["getLuaDir"])
        assertSame(PrimitiveType.BOOLEAN, assertIs<FunctionType>(all.getValue("getLuaDir")).returnType)
    }

    @Test
    fun inheritedSuperClassMethodResolvesUnderDotAndColon() {
        val harness = resolver()
        val inherited = FunctionType(returnType = PrimitiveType.STRING)
        val base = ClassType(name = "AndroidLuaContext", methods = mapOf("getLuaDir" to inherited))
        val activity = ClassType(
            name = "LuaActivity",
            methods = mapOf("newActivity" to FunctionType(returnType = PrimitiveType.NIL)),
            superClass = base
        )

        val colon = harness.resolver.resolveMember(activity, "getLuaDir", preferMethod = true, harness.scopeId)
        val dot = harness.resolver.resolveMember(activity, "getLuaDir", preferMethod = false, harness.scopeId)

        assertTrue(colon.isSuccess, "colon inherited: ${colon.failureReason}")
        assertTrue(dot.isSuccess, "dot inherited: ${dot.failureReason}")
        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertEquals(MemberAccessKind.METHOD, dot.accessKind)
        assertEquals(SymbolKind.METHOD, colon.accessKind.toApiSymbolKind())
        assertEquals(SymbolKind.METHOD, dot.accessKind.toApiSymbolKind())
        assertFunctionShaped(colon.type)
        assertFunctionShaped(dot.type)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(colon.type).returnType)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(dot.type).returnType)
    }

    @Test
    fun multiLevelSuperClassMethodChainResolvesUnderDot() {
        val harness = resolver()
        val grandMethod = FunctionType(returnType = PrimitiveType.BOOLEAN)
        val grand = ClassType(name = "Grand", methods = mapOf("isReady" to grandMethod))
        val parent = ClassType(name = "Parent", superClass = grand)
        val child = ClassType(name = "Child", superClass = parent)

        assertTrue("isReady" in child.getAllMethods())

        val dot = harness.resolver.resolveMember(child, "isReady", preferMethod = false, harness.scopeId)
        assertTrue(dot.isSuccess)
        assertEquals(MemberAccessKind.METHOD, dot.accessKind)
        assertEquals(SymbolKind.METHOD, dot.accessKind.toApiSymbolKind())
        assertFunctionShaped(dot.type)
        assertSame(PrimitiveType.BOOLEAN, assertIs<FunctionType>(dot.type).returnType)
    }

    // --- doc-comment ClassType surface (activity-like) ------------------------

    @Test
    fun docClassMethodResolvesUnderDotAndColonAsMethod() {
        val harness = resolver(
            """
            ---@class AndroidLuaContext
            ---@method AndroidLuaContext:getLuaDir(): string
            ---@class LuaActivity: AndroidLuaContext
            ---@method LuaActivity:newActivity(): nil
            """.trimIndent()
        )
        val activity = classType(harness, "LuaActivity")

        val colon = harness.resolver.resolveMember(activity, "getLuaDir", preferMethod = true, harness.scopeId)
        val dot = harness.resolver.resolveMember(activity, "getLuaDir", preferMethod = false, harness.scopeId)
        val ownDot = harness.resolver.resolveMember(activity, "newActivity", preferMethod = false, harness.scopeId)

        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertEquals(MemberAccessKind.METHOD, dot.accessKind)
        assertEquals(MemberAccessKind.METHOD, ownDot.accessKind)
        assertEquals(SymbolKind.METHOD, colon.accessKind.toApiSymbolKind())
        assertEquals(SymbolKind.METHOD, dot.accessKind.toApiSymbolKind())
        assertFunctionShaped(colon.type)
        assertFunctionShaped(dot.type)
        assertFunctionShaped(ownDot.type)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(colon.type).returnType)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(dot.type).returnType)
    }

    @Test
    fun docClassOwnAndInheritedMethodsPresentInGetAllMethods() {
        val harness = resolver(
            """
            ---@class BaseCtx
            ---@method BaseCtx:getLuaDir(): string
            ---@class LuaActivity: BaseCtx
            ---@method LuaActivity:setContentView(): nil
            """.trimIndent()
        )
        val activity = classType(harness, "LuaActivity")
        val all = activity.getAllMethods()

        assertTrue("getLuaDir" in all, all.keys.toString())
        assertTrue("setContentView" in all, all.keys.toString())
        assertIs<FunctionType>(all.getValue("getLuaDir"))
        assertIs<FunctionType>(all.getValue("setContentView"))
    }

    // --- overloaded / multi-signature method surfaces -------------------------

    @Test
    fun overloadedClassMethodRemainsMethodUnderDotAndColon() {
        val harness = resolver()
        val overloaded = OverloadedFunctionType(
            callSignatures = listOf(
                FunctionType(returnType = PrimitiveType.STRING),
                FunctionType(
                    parameters = listOf(FunctionParameter("name", PrimitiveType.STRING)),
                    returnType = PrimitiveType.STRING
                )
            )
        )
        val cls = ClassType(name = "LuaActivity", methods = mapOf("getLuaDir" to overloaded))

        val colon = harness.resolver.resolveMember(cls, "getLuaDir", preferMethod = true, harness.scopeId)
        val dot = harness.resolver.resolveMember(cls, "getLuaDir", preferMethod = false, harness.scopeId)

        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertEquals(MemberAccessKind.METHOD, dot.accessKind)
        assertEquals(SymbolKind.METHOD, colon.accessKind.toApiSymbolKind())
        assertFunctionShaped(colon.type)
        assertFunctionShaped(dot.type)
        assertEquals(2, assertIs<OverloadedFunctionType>(colon.type).callSignatures.size)
        assertEquals(2, assertIs<OverloadedFunctionType>(dot.type).callSignatures.size)
    }

    // --- missing / non-method ClassType surfaces ------------------------------

    @Test
    fun missingClassMethodReportsMissingMemberUnderDotAndColon() {
        val harness = resolver()
        val cls = ClassType(name = "LuaActivity", methods = mapOf("getLuaDir" to FunctionType(returnType = PrimitiveType.STRING)))

        val colon = harness.resolver.resolveMember(cls, "missing", preferMethod = true, harness.scopeId)
        val dot = harness.resolver.resolveMember(cls, "missing", preferMethod = false, harness.scopeId)

        assertEquals(MemberFailureReason.MISSING_MEMBER, colon.failureReason)
        assertEquals(MemberFailureReason.MISSING_MEMBER, dot.failureReason)
        assertNull(colon.type)
        assertNull(dot.type)
        assertNull(colon.accessKind)
        assertNull(dot.accessKind)
    }

    @Test
    fun classFieldOnlyIsFieldNotMethodUnderDot() {
        val harness = resolver()
        val cls = ClassType(
            name = "LuaActivity",
            fields = mapOf("title" to PrimitiveType.STRING)
        )

        val dot = harness.resolver.resolveMember(cls, "title", preferMethod = false, harness.scopeId)

        assertEquals(MemberAccessKind.FIELD, dot.accessKind)
        assertEquals(SymbolKind.FIELD, dot.accessKind.toApiSymbolKind())
        assertSame(PrimitiveType.STRING, dot.type)
    }

    // --- receiver self-binding on ClassType methods ---------------------------

    @Test
    fun classColonMethodBindsSelfReceiverWhenAbsent() {
        val harness = resolver()
        val method = FunctionType(parameters = emptyList(), returnType = PrimitiveType.NIL)
        val activity = ClassType(name = "LuaActivity", methods = mapOf("touch" to method))

        val colon = harness.resolver.resolveMember(activity, "touch", preferMethod = true, harness.scopeId)
        val bound = assertIs<FunctionType>(colon.type)

        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertTrue(bound.parameters.isNotEmpty(), "colon method should bind self")
        assertEquals("self", bound.parameters.first().name)
        assertSame(activity, bound.parameters.first().type)
    }

    @Test
    fun classDotMethodAlsoBindsSelfWhenSignatureLacksCompatibleReceiver() {
        val harness = resolver()
        val method = FunctionType(parameters = emptyList(), returnType = PrimitiveType.STRING)
        val activity = ClassType(name = "LuaActivity", methods = mapOf("getLuaDir" to method))

        val dot = harness.resolver.resolveMember(activity, "getLuaDir", preferMethod = false, harness.scopeId)
        val bound = assertIs<FunctionType>(dot.type)

        assertEquals(MemberAccessKind.METHOD, dot.accessKind)
        assertEquals(SymbolKind.METHOD, dot.accessKind.toApiSymbolKind())
        // bindMethodReceiver runs for METHOD regardless of preferMethod flag.
        assertTrue(bound.parameters.isNotEmpty())
        assertEquals("self", bound.parameters.first().name)
    }

    // --- helpers --------------------------------------------------------------

    private fun assertFunctionShaped(type: Type?) {
        assertNotNull(type, "expected function-shaped type")
        assertTrue(
            type is CallableType || type is FunctionType || type is OverloadedFunctionType,
            "expected CallableType/FunctionType, got ${type::class.simpleName}: $type"
        )
        val display = type.displayName
        assertTrue(
            display.startsWith("fun") || display.contains("fun(") || type is OverloadedFunctionType,
            "function-shaped displayName expected, got '$display'"
        )
    }

    /**
     * Mirrors [io.github.dingyi222666.luaparser.semantic.model.ApiAdapters.syntheticMemberSymbol]
     * mapping: METHOD → SymbolKind.METHOD, FIELD/INDEX → SymbolKind.FIELD.
     */
    private fun MemberAccessKind?.toApiSymbolKind(): SymbolKind = when (this) {
        MemberAccessKind.METHOD -> SymbolKind.METHOD
        MemberAccessKind.FIELD, MemberAccessKind.INDEX -> SymbolKind.FIELD
        null -> error("accessKind is null; cannot map to SymbolKind")
    }

    private fun resolver(source: String = ""): Harness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            resolver = MemberResolver(resolved),
            declarations = resolved,
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private fun classType(harness: Harness, name: String): ClassType = assertIs(
        harness.declarations.declarationIndex.declarations
            .single { it.kind == DeclarationKind.CLASS && it.name == name }
            .declaredType
    )

    private data class Harness(
        val resolver: MemberResolver,
        val declarations: BinderPassResult,
        val scopeId: ScopeId
    )
}
