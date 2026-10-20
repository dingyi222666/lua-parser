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
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaConstructorType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadSet
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-348 — Java constructor overload pick-by-arity corpus.
 *
 * Encodes the product contract for selecting among multi-arity Java constructor
 * overloads exposed on [JavaClassType] / reflected class `__call` surfaces via
 * [CallChecker]:
 * - Distinct argument counts select the matching closed constructor stably.
 * - Zero/one/two/three/four-arg siblings do not bleed into each other.
 * - Fixed-arity matches beat vararg fallback when both are compatible.
 * - Same-arity ties degrade safely: [CallFailureReason.AMBIGUOUS_MATCH] with a
 *   deterministic first-signature selection (still returns the instance type).
 * - Impossible arity fails with [CallFailureReason.NO_MATCHING_SIGNATURE]
 *   rather than inventing a constructor or throwing.
 *
 * Complements [LuaJavaNewInstanceArityTddTest] (luajava.newInstance string path /
 * product gap) by asserting CallChecker constructor overload ranking directly.
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class JavaConstructorOverloadPickTddTest {

    private val parser = LuaParser()
    private val provider = JvmClassModuleProvider()

    // --- distinct closed arities ------------------------------------------------

    @Test
    fun zeroArgConstructorSelectedWhenNoArguments() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            zeroArgCtor(),
            oneArgCtor(PrimitiveType.STRING),
            twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        val result = harness.check(clazz, emptyList())

        assertSuccessInstance(result, clazz)
        assertEquals(0, result.selectedSignature!!.parameters.size)
        assertFalse(result.ambiguous)
    }
    @Test
    fun oneArgConstructorSelectedWhenSingleArgument() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            zeroArgCtor(),
            oneArgCtor(PrimitiveType.STRING),
            twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        val result = harness.check(clazz, listOf(PrimitiveType.STRING))

        assertSuccessInstance(result, clazz)
        assertEquals(1, result.selectedSignature!!.parameters.size)
        assertFalse(result.ambiguous)
    }
    @Test
    fun closedArityRejectsTooManyConstructorArgumentsEvenWithSiblingOverloads() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            oneArgCtor(PrimitiveType.STRING),
            twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertNoMatch(
            harness.check(
                clazz,
                listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
            )
        )
    }
    @Test
    fun fixedArityConstructorPreferredOverVarargSiblingWhenCountMatches() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            oneArgCtor(PrimitiveType.STRING),
            FunctionType(
                parameters = listOf(
                    FunctionParameter("...", PrimitiveType.STRING, vararg = true)
                ),
                returnType = UnknownType
            )
        )

        val result = harness.check(clazz, listOf(PrimitiveType.STRING))

        assertSuccessInstance(result, clazz)
        assertFalse(result.selectedSignature!!.parameters.any { it.vararg })
        assertFalse(result.ambiguous)
    }
    @Test
    fun noMatchingConstructorArityDegradesWithoutThrowAndWithoutInventedSignature() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            oneArgCtor(PrimitiveType.STRING)
        )

        val missing = harness.check(clazz, emptyList())
        assertNoMatch(missing)
        assertNull(missing.returnType)
        assertNull(missing.selectedSignature)

        val tooMany = harness.check(clazz, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        assertNoMatch(tooMany)
    }
    @Test
    fun reflectedStringBuilderConstructorArityPickSelectsZeroAndOneArgOverloads() {
        val clazz = reflectedCallableClass("java.lang.StringBuilder")
        val arities = clazz.callSignatures.map { it.parameters.size }.toSet()
        assertTrue(
            0 in arities && 1 in arities,
            "StringBuilder must expose 0- and 1-arg constructors; arities=$arities"
        )

        val harness = harness()
        val zero = harness.check(clazz, emptyList())
        val one = harness.check(clazz, listOf(PrimitiveType.STRING))

        assertTrue(zero.isSuccess, "0-arg StringBuilder ctor should match; reason=${zero.failureReason}")
        assertEquals(0, zero.selectedSignature!!.parameters.size)
        assertFalse(zero.ambiguous)
        assertReflectedInstance(zero.returnType, "java.lang.StringBuilder")

        assertTrue(one.isSuccess, "1-arg StringBuilder ctor should match; reason=${one.failureReason}")
        assertEquals(1, one.selectedSignature!!.parameters.size)
        assertFalse(one.ambiguous)
        assertReflectedInstance(one.returnType, "java.lang.StringBuilder")
    }
    @Test
    fun javaConstructorOverloadArityPickCorpusTable() {
        data class Case(
            val name: String,
            val signatures: List<FunctionType>,
            val arguments: List<Type>,
            val expectSuccess: Boolean,
            val expectArity: Int? = null,
            val expectAmbiguous: Boolean = false,
            val expectVararg: Boolean? = null
        )

        val cases = listOf(
            Case(
                name = "pick 0-arg ctor",
                signatures = listOf(
                    zeroArgCtor(),
                    oneArgCtor(PrimitiveType.STRING)
                ),
                arguments = emptyList(),
                expectSuccess = true,
                expectArity = 0
            ),
            Case(
                name = "pick 1-arg ctor",
                signatures = listOf(
                    zeroArgCtor(),
                    oneArgCtor(PrimitiveType.STRING),
                    twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectArity = 1
            ),
            Case(
                name = "pick 2-arg ctor",
                signatures = listOf(
                    oneArgCtor(PrimitiveType.STRING),
                    twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectSuccess = true,
                expectArity = 2
            ),
            Case(
                name = "pick 3-arg ctor",
                signatures = listOf(
                    oneArgCtor(PrimitiveType.STRING),
                    twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER),
                    threeArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
                expectSuccess = true,
                expectArity = 3
            ),
            Case(
                name = "pick 4-arg ctor",
                signatures = listOf(
                    threeArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
                    fourArgCtor(
                        PrimitiveType.STRING,
                        PrimitiveType.NUMBER,
                        PrimitiveType.BOOLEAN,
                        PrimitiveType.ANY
                    )
                ),
                arguments = listOf(
                    PrimitiveType.STRING,
                    PrimitiveType.NUMBER,
                    PrimitiveType.BOOLEAN,
                    PrimitiveType.ANY
                ),
                expectSuccess = true,
                expectArity = 4
            ),
            Case(
                name = "too many ctor args fail",
                signatures = listOf(
                    oneArgCtor(PrimitiveType.STRING)
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectSuccess = false
            ),
            Case(
                name = "too few ctor args fail",
                signatures = listOf(
                    twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = false
            ),
            Case(
                name = "unknown arg friendly unary ctor",
                signatures = listOf(
                    oneArgCtor(PrimitiveType.STRING),
                    twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER)
                ),
                arguments = listOf(UnknownType),
                expectSuccess = true,
                expectArity = 1
            ),
            Case(
                name = "same-arity ambiguous ctor degrades",
                signatures = listOf(
                    oneArgCtor(PrimitiveType.STRING, name = "left"),
                    oneArgCtor(PrimitiveType.STRING, name = "right")
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectArity = 1,
                expectAmbiguous = true
            ),
            Case(
                name = "exact beats wider any ctor",
                signatures = listOf(
                    oneArgCtor(PrimitiveType.STRING),
                    oneArgCtor(PrimitiveType.ANY)
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectArity = 1,
                expectAmbiguous = false
            ),
            Case(
                name = "fixed beats vararg ctor",
                signatures = listOf(
                    oneArgCtor(PrimitiveType.STRING),
                    FunctionType(
                        parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                        returnType = UnknownType
                    )
                ),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = true,
                expectArity = 1,
                expectVararg = false
            ),
            Case(
                name = "vararg when fixed ctor misses",
                signatures = listOf(
                    oneArgCtor(PrimitiveType.STRING),
                    FunctionType(
                        parameters = listOf(FunctionParameter("...", PrimitiveType.STRING, vararg = true)),
                        returnType = UnknownType
                    )
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.STRING),
                expectSuccess = true,
                expectArity = 1, // vararg signature itself has one parameter entry
                expectVararg = true
            ),
            Case(
                name = "optional tail used only when arity requires it",
                signatures = listOf(
                    oneArgCtor(PrimitiveType.STRING),
                    FunctionType(
                        parameters = listOf(
                            FunctionParameter("value", PrimitiveType.STRING),
                            FunctionParameter("radix", PrimitiveType.NUMBER, optional = true)
                        ),
                        returnType = UnknownType
                    )
                ),
                arguments = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
                expectSuccess = true,
                expectArity = 2
            ),
            Case(
                name = "zero-only set rejects unary",
                signatures = listOf(zeroArgCtor()),
                arguments = listOf(PrimitiveType.STRING),
                expectSuccess = false
            )
        )

        val harness = harness()
        val failures = mutableListOf<String>()
        for (case in cases) {
            val outcome = runCatching {
                val clazz = javaClassWithConstructors(*case.signatures.toTypedArray())
                harness.check(clazz, case.arguments)
            }
            if (outcome.isFailure) {
                failures += "${case.name}: threw unexpectedly: ${outcome.exceptionOrNull()}"
                continue
            }
            val result = outcome.getOrThrow()
            if (result.isSuccess != case.expectSuccess) {
                failures += "${case.name}: expected success=${case.expectSuccess}, " +
                    "got success=${result.isSuccess} reason=${result.failureReason}"
                continue
            }
            if (case.expectSuccess) {
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
                if (result.returnType !is JavaInstanceType) {
                    failures += "${case.name}: expected JavaInstanceType return, got ${result.returnType}"
                }
                if (case.expectVararg != null) {
                    val isVararg = result.selectedSignature?.parameters?.any { it.vararg } == true
                    if (isVararg != case.expectVararg) {
                        failures += "${case.name}: expected vararg=${case.expectVararg}, got $isVararg"
                    }
                }
            } else if (result.failureReason != CallFailureReason.NO_MATCHING_SIGNATURE) {
                failures += "${case.name}: expected NO_MATCHING_SIGNATURE, got ${result.failureReason}"
            } else if (result.returnType != null || result.selectedSignature != null) {
                failures += "${case.name}: no-match must not invent return/signature"
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

    private fun javaClassWithConstructors(vararg signatures: FunctionType): JavaClassType {
        val javaName = JavaTypeName(packageName = "semantic.interop", simpleNames = listOf("CtorPicker"))
        return JavaClassType(
            javaName = javaName,
            constructors = JavaOverloadSet(
                signatures.map { signature ->
                    JavaConstructorType(
                        owner = javaName,
                        signature = signature
                    )
                }
            )
        )
    }

    private fun zeroArgCtor(): FunctionType =
        FunctionType(parameters = emptyList(), returnType = UnknownType)

    private fun oneArgCtor(param: Type, name: String = "arg1"): FunctionType =
        FunctionType(
            parameters = listOf(FunctionParameter(name, param)),
            returnType = UnknownType
        )

    private fun twoArgCtor(
        first: Type,
        second: Type,
        firstName: String = "arg1",
        secondName: String = "arg2"
    ): FunctionType =
        FunctionType(
            parameters = listOf(
                FunctionParameter(firstName, first),
                FunctionParameter(secondName, second)
            ),
            returnType = UnknownType
        )

    private fun threeArgCtor(first: Type, second: Type, third: Type): FunctionType =
        FunctionType(
            parameters = listOf(
                FunctionParameter("arg1", first),
                FunctionParameter("arg2", second),
                FunctionParameter("arg3", third)
            ),
            returnType = UnknownType
        )

    private fun fourArgCtor(first: Type, second: Type, third: Type, fourth: Type): FunctionType =
        FunctionType(
            parameters = listOf(
                FunctionParameter("arg1", first),
                FunctionParameter("arg2", second),
                FunctionParameter("arg3", third),
                FunctionParameter("arg4", fourth)
            ),
            returnType = UnknownType
        )

    private fun reflectedCallableClass(className: String): JavaClassType {
        val module = reflectedModule(className)
        val call = module.fields["__call"]
            ?: fail("Missing __call constructor surface for $className; fields=${module.fields.keys.sorted()}")
        return assertIs(call)
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

    private fun assertSuccessInstance(result: CallResolution, clazz: JavaClassType) {
        assertTrue(result.isSuccess, "expected success, got reason=${result.failureReason}")
        assertNull(result.failureReason)
        assertNotNull(result.selectedSignature)
        assertIsInstanceOfClass(result.returnType, clazz)
    }

    private fun assertIsInstanceOfClass(type: Type?, clazz: JavaClassType) {
        val instance = assertIs<JavaInstanceType>(type)
        // callSignatures rewrites returns onto JavaInstanceType(this); identity may differ
        // after withReturnType, so compare canonical names.
        assertEquals(clazz.javaName.canonicalName, instance.javaName.canonicalName)
    }

    private fun assertReflectedInstance(type: Type?, canonicalName: String) {
        val instance = assertIs<JavaInstanceType>(type)
        assertEquals(canonicalName, instance.javaName.canonicalName)
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
