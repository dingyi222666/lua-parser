package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.checker.CallChecker
import io.github.dingyi222666.luaparser.semantic.checker.CallFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.CallResolution
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-585 / TASK-300 — Java method overload pick-by-arity product ranking.
 *
 * Encodes the product contract for selecting among multi-arity Java method
 * overloads ([JavaOverloadType] / reflected callables) via [CallChecker]:
 * - Distinct argument counts select the matching closed overload stably.
 * - Zero/one/two/three-arg siblings do not bleed into each other.
 * - Exact closed arity is preferred over vararg/spread siblings when both match
 *   ([CallChecker] requiredPenalty primary key; vararg/spread base penalty).
 * - Same-arity ties degrade safely: [CallFailureReason.AMBIGUOUS_MATCH] with a
 *   deterministic first-signature selection (still returns a type).
 * - Impossible arity fails with [CallFailureReason.NO_MATCHING_SIGNATURE]
 *   rather than inventing a signature.
 * - Unknown arguments stay soft (assignable) without inventing missing arities.
 *
 * Distinct from constructor overload ranking (TASK-523 / TASK-586).
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class JavaMethodOverloadArityPickTddTest {

    private val parser = LuaParser()
    private val provider = JvmClassModuleProvider()

    // --- distinct closed arities ------------------------------------------------

    @Test
    fun zeroArgOverloadSelectedWhenNoArguments() {
        val harness = harness()
        val callable = javaOverloads(
            zeroArg(returnType = PrimitiveType.BOOLEAN),
            oneArg(PrimitiveType.STRING, returnType = PrimitiveType.STRING),
            twoArg(PrimitiveType.STRING, PrimitiveType.NUMBER, returnType = PrimitiveType.NUMBER)
        )

        val result = harness.check(callable, emptyList())

        assertSuccess(result, PrimitiveType.BOOLEAN)
        assertEquals(0, result.selectedSignature!!.parameters.size)
        assertFalse(result.ambiguous)
    }
    @Test
    fun oneArgOverloadSelectedWhenSingleArgument() {
        val harness = harness()
        val callable = javaOverloads(
            zeroArg(returnType = PrimitiveType.BOOLEAN),
            oneArg(PrimitiveType.STRING, returnType = PrimitiveType.STRING),
            twoArg(PrimitiveType.STRING, PrimitiveType.NUMBER, returnType = PrimitiveType.NUMBER)
        )

        val result = harness.check(callable, listOf(PrimitiveType.STRING))

        assertSuccess(result, PrimitiveType.STRING)
        assertEquals(1, result.selectedSignature!!.parameters.size)
        assertFalse(result.ambiguous)
    }
    @Test
    fun twoArgOverloadSelectedWhenTwoArguments() {
        val harness = harness()
        val callable = javaOverloads(
            zeroArg(returnType = PrimitiveType.BOOLEAN),
            oneArg(PrimitiveType.STRING, returnType = PrimitiveType.STRING),
            twoArg(PrimitiveType.STRING, PrimitiveType.NUMBER, returnType = PrimitiveType.NUMBER)
        )

        val result = harness.check(
            callable,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertSuccess(result, PrimitiveType.NUMBER)
        assertEquals(2, result.selectedSignature!!.parameters.size)
        assertFalse(result.ambiguous)
    }
    @Test
    fun closedArityRejectsTooManyArgumentsEvenWithSiblingOverloads() {
        val harness = harness()
        val callable = javaOverloads(
            oneArg(PrimitiveType.STRING, returnType = PrimitiveType.STRING),
            twoArg(PrimitiveType.STRING, PrimitiveType.NUMBER, returnType = PrimitiveType.NUMBER)
        )

        // three args match neither closed 1-arg nor closed 2-arg
        assertNoMatch(
            harness.check(
                callable,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
            )
        )
    }
    @Test
    fun fixedArityPreferredOverVarargSiblingWhenCountMatches() {
        val harness = harness()
        val callable = javaOverloads(
            oneArg(PrimitiveType.STRING, returnType = PrimitiveType.STRING),
            FunctionType(
                parameters = listOf(
                    FunctionParameter("...", PrimitiveType.STRING, vararg = true)
                ),
                returnType = PrimitiveType.BOOLEAN
            )
        )

        val result = harness.check(callable, listOf(PrimitiveType.STRING))

        assertSuccess(result, PrimitiveType.STRING)
        assertFalse(result.selectedSignature!!.parameters.any { it.vararg })
        assertFalse(result.ambiguous)
    }
    @Test
    fun sameArityTypeCompatibleTieIsAmbiguousButStillSelectsDeterministicFirst() {
        val harness = harness()
        // Two unary overloads both accept string (any is wider; both remain candidates
        // only when both parameters assign from the arg). Use identical parameter
        // types so scores tie fully — first signature wins, AMBIGUOUS_MATCH set.
        val first = oneArg(PrimitiveType.STRING, returnType = PrimitiveType.STRING)
        val second = oneArg(PrimitiveType.STRING, returnType = PrimitiveType.NUMBER)
        val callable = javaOverloads(first, second)

        val result = harness.check(callable, listOf(PrimitiveType.STRING))

        assertTrue(result.isSuccess, "ambiguous arity must still degrade with a return type")
        assertTrue(result.ambiguous)
        assertEquals(CallFailureReason.AMBIGUOUS_MATCH, result.failureReason)
        assertSame(PrimitiveType.STRING, result.returnType)
        assertSame(first, result.selectedSignature)
    }
    @Test
    fun reflectedStringSubstringArityPickSelectsOneAndTwoArgOverloads() {
        val method = reflectedInstanceCallable("java.lang.String", "substring")
        assertTrue(method.callSignatures.size >= 2, "substring must expose multi-arity overloads")
        val arities = method.callSignatures.map { it.parameters.size }.toSet()
        assertTrue(1 in arities && 2 in arities, "substring arities=$arities")

        val harness = harness()
        val one = harness.check(method, listOf(PrimitiveType.NUMBER))
        val two = harness.check(method, listOf(PrimitiveType.NUMBER, PrimitiveType.NUMBER))

        assertTrue(one.isSuccess, "1-arg substring should match; reason=${one.failureReason}")
        assertEquals(1, one.selectedSignature!!.parameters.size)
        assertFalse(one.ambiguous)

        assertTrue(two.isSuccess, "2-arg substring should match; reason=${two.failureReason}")
        assertEquals(2, two.selectedSignature!!.parameters.size)
        assertFalse(two.ambiguous)
    }
    @Test
    fun javaMethodOverloadArityPickCorpusTable() {
        data class Case(
            val name: String,
            val signatures: List<FunctionType>,
            val arguments: List<Type>,
            val expectSuccess: Boolean,
            val expectArity: Int? = null,
            val expectReturn: Type? = null,
            val expectAmbiguous: Boolean = false
        )

        val cases = listOf(
            Case(
                name = "pick 0-arg",
                signatures = listOf(
                    zeroArg(PrimitiveType.BOOLEAN),
                    oneArg(PrimitiveType.STRING, PrimitiveType.STRING)
                ),
                arguments = emptyList(),
                expectSuccess = true,
                expectArity = 0,
                expectReturn = PrimitiveType.BOOLEAN
            ),
            Case(
                name = "pick 1-arg",
                signatures = listOf(
                    zeroArg(PrimitiveType.BOOLEAN),
                    oneArg(PrimitiveType.STRING, PrimitiveType.STRING),
                    twoArg(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectArity = 1,
                expectReturn = PrimitiveType.STRING
            ),
            Case(
                name = "pick 2-arg",
                signatures = listOf(
                    oneArg(PrimitiveType.STRING, PrimitiveType.STRING),
                    twoArg(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectSuccess = true,
                expectArity = 2,
                expectReturn = PrimitiveType.NUMBER
            ),
            Case(
                name = "too many args fail",
                signatures = listOf(
                    oneArg(PrimitiveType.STRING, PrimitiveType.STRING)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectSuccess = false
            ),
            Case(
                name = "too few args fail",
                signatures = listOf(
                    twoArg(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = false
            ),
            Case(
                name = "unknown arg friendly unary",
                signatures = listOf(
                    oneArg(PrimitiveType.STRING, PrimitiveType.STRING),
                    twoArg(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NUMBER)
                ),
                arguments = listOf(UnknownType),
                expectSuccess = true,
                expectArity = 1,
                expectReturn = PrimitiveType.STRING
            ),
            Case(
                name = "same-arity ambiguous degrades",
                signatures = listOf(
                    oneArg(PrimitiveType.STRING, PrimitiveType.STRING),
                    oneArg(PrimitiveType.STRING, PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectArity = 1,
                expectReturn = PrimitiveType.STRING,
                expectAmbiguous = true
            ),
            Case(
                name = "fixed beats vararg",
                signatures = listOf(
                    oneArg(PrimitiveType.STRING, PrimitiveType.STRING),
                    FunctionType(
                        parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                        returnType = PrimitiveType.BOOLEAN
                    )
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectArity = 1,
                expectReturn = PrimitiveType.STRING
            ),
            Case(
                name = "vararg when fixed misses",
                signatures = listOf(
                    oneArg(PrimitiveType.STRING, PrimitiveType.STRING),
                    FunctionType(
                        parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                        returnType = PrimitiveType.BOOLEAN
                    )
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.STRING),
                expectSuccess = true,
                expectReturn = PrimitiveType.BOOLEAN
            ),
            Case(
                name = "exact closed beats vararg even when vararg listed first",
                signatures = listOf(
                    FunctionType(
                        parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                        returnType = PrimitiveType.BOOLEAN
                    ),
                    oneArg(PrimitiveType.STRING, PrimitiveType.STRING)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectArity = 1,
                expectReturn = PrimitiveType.STRING
            ),
            Case(
                name = "zero-arg exact beats pure vararg",
                signatures = listOf(
                    FunctionType(
                        parameters = listOf(FunctionParameter("...", PrimitiveType.ANY, vararg = true)),
                        returnType = PrimitiveType.STRING
                    ),
                    zeroArg(PrimitiveType.BOOLEAN)
                ),
                arguments = emptyList(),
                expectSuccess = true,
                expectArity = 0,
                expectReturn = PrimitiveType.BOOLEAN
            ),
            Case(
                name = "unknown soft no invent arity",
                signatures = listOf(
                    oneArg(PrimitiveType.STRING, PrimitiveType.STRING)
                ),
                arguments = listOf(UnknownType, UnknownType),
                expectSuccess = false
            )
        )

        val harness = harness()
        val failures = mutableListOf<String>()
        for (case in cases) {
            val callable = javaOverloads(*case.signatures.toTypedArray())
            val result = harness.check(callable, case.arguments)
            if (result.isSuccess != case.expectSuccess) {
                failures += "${case.name}: expected success=${case.expectSuccess}, " +
                    "got success=${result.isSuccess} reason=${result.failureReason}"
                continue
            }
            if (case.expectSuccess) {
                if (case.expectReturn != null && result.returnType !== case.expectReturn) {
                    failures += "${case.name}: expected return ${case.expectReturn}, got ${result.returnType}"
                }
                if (case.expectArity != null && result.selectedSignature?.parameters?.size != case.expectArity) {
                    failures += "${case.name}: expected arity ${case.expectArity}, " +
                        "got ${result.selectedSignature?.parameters?.size}"
                }
                if (result.ambiguous != case.expectAmbiguous) {
                    failures += "${case.name}: expected ambiguous=${case.expectAmbiguous}, got ${result.ambiguous}"
                }
                if (case.expectAmbiguous && result.failureReason != CallFailureReason.AMBIGUOUS_MATCH) {
                    failures += "${case.name}: expected AMBIGUOUS_MATCH, got ${result.failureReason}"
                }
                if (!case.expectAmbiguous && result.failureReason != null) {
                    failures += "${case.name}: expected no failureReason, got ${result.failureReason}"
                }
            } else if (result.failureReason != CallFailureReason.NO_MATCHING_SIGNATURE) {
                failures += "${case.name}: expected NO_MATCHING_SIGNATURE, got ${result.failureReason}"
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    // --- helpers ----------------------------------------------------------------

    private fun harness(): Harness {
        val chunk = parser.parse("")
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            checker = CallChecker(resolved),
            declarations = resolved,
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private fun javaOverloads(vararg signatures: FunctionType): JavaOverloadType {
        return JavaOverloadType(
            javaName = JavaTypeName(packageName = "semantic.interop", simpleNames = listOf("ArityPicker")),
            overloadName = "pick",
            callSignatures = signatures.toList()
        )
    }

    private fun zeroArg(returnType: Type): FunctionType =
        FunctionType(parameters = emptyList(), returnType = returnType)

    private fun oneArg(param: Type, returnType: Type): FunctionType =
        FunctionType(
            parameters = listOf(FunctionParameter("arg1", param)),
            returnType = returnType
        )

    private fun twoArg(first: Type, second: Type, returnType: Type): FunctionType =
        FunctionType(
            parameters = listOf(
                FunctionParameter("arg1", first),
                FunctionParameter("arg2", second)
            ),
            returnType = returnType
        )

    private fun threeArg(first: Type, second: Type, third: Type, returnType: Type): FunctionType =
        FunctionType(
            parameters = listOf(
                FunctionParameter("arg1", first),
                FunctionParameter("arg2", second),
                FunctionParameter("arg3", third)
            ),
            returnType = returnType
        )

    private fun reflectedStaticCallable(className: String, methodName: String): CallableType {
        val module = reflectedModule(className)
        val type = module.methods[methodName]
            ?: fail("Missing static method $className.$methodName; available=${module.methods.keys.sorted()}")
        return assertIs(type)
    }

    private fun reflectedInstanceCallable(className: String, methodName: String): CallableType {
        val module = reflectedModule(className)
        val classType = module.fields["__class"]
            ?: fail("Missing __class for $className")
        // JavaInstanceType / JavaClassType expose instance members via allInstanceMembers when available.
        val instanceMembers = when (classType) {
            is io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType ->
                classType.allInstanceMembers()
            is io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType ->
                classType.allInstanceMembers()
            else -> fail("Unexpected __class type ${classType::class.simpleName}")
        }
        val member = instanceMembers[methodName]
            ?: fail("Missing instance method $className.$methodName; available=${instanceMembers.keys.sorted()}")
        return assertIs(member.valueType)
    }

    private fun reflectedModule(className: String): ModuleType {
        val path = VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")
        val snapshot = provider.providersFor(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to className)
        )[path] ?: fail("Missing provider for $className at ${path.value}")
        val surface = snapshot.moduleExportSurface
            ?: fail("Missing export surface for $className")
        return surface.moduleType
    }

    private fun assertSuccess(result: CallResolution, expectedReturn: Type) {
        assertTrue(result.isSuccess, "expected success, got reason=${result.failureReason}")
        assertNull(result.failureReason)
        assertSame(expectedReturn, result.returnType)
        assertNotNull(result.selectedSignature)
    }

    private fun assertNoMatch(result: CallResolution) {
        assertFalse(result.isSuccess, "expected NO_MATCHING_SIGNATURE failure")
        assertEquals(CallFailureReason.NO_MATCHING_SIGNATURE, result.failureReason)
        assertNull(result.returnType)
        assertNull(result.selectedSignature)
    }

    private data class Harness(
        val checker: CallChecker,
        val declarations: BinderPassResult,
        val scopeId: ScopeId
    ) {
        fun check(callable: Type, arguments: List<Type>): CallResolution {
            return checker.checkCall(callable, arguments, scopeId)
        }
    }
}
