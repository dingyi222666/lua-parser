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
    fun twoArgConstructorSelectedWhenTwoArguments() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            zeroArgCtor(),
            oneArgCtor(PrimitiveType.STRING),
            twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        val result = harness.check(
            clazz,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertSuccessInstance(result, clazz)
        assertEquals(2, result.selectedSignature!!.parameters.size)
        assertFalse(result.ambiguous)
    }

    @Test
    fun threeArgConstructorSelectedWhenThreeArguments() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            oneArgCtor(PrimitiveType.STRING),
            twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER),
            threeArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        )

        val result = harness.check(
            clazz,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        )

        assertSuccessInstance(result, clazz)
        assertEquals(3, result.selectedSignature!!.parameters.size)
        assertFalse(result.ambiguous)
    }

    @Test
    fun fourArgConstructorSelectedWhenFourArguments() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER),
            threeArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
            fourArgCtor(
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN,
                PrimitiveType.ANY
            )
        )

        val result = harness.check(
            clazz,
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN,
                PrimitiveType.ANY
            )
        )

        assertSuccessInstance(result, clazz)
        assertEquals(4, result.selectedSignature!!.parameters.size)
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
    fun closedArityRejectsTooFewConstructorArgumentsEvenWithSiblingOverloads() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER),
            threeArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        )

        assertNoMatch(harness.check(clazz, listOf(PrimitiveType.STRING)))
    }

    @Test
    fun multiArityClosedSetDoesNotBleedAcrossNeighboringArgCounts() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            zeroArgCtor(),
            oneArgCtor(PrimitiveType.STRING),
            twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER),
            threeArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        )

        val zero = harness.check(clazz, emptyList())
        val one = harness.check(clazz, listOf(PrimitiveType.STRING))
        val two = harness.check(clazz, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        val three = harness.check(
            clazz,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        )

        assertEquals(0, zero.selectedSignature!!.parameters.size)
        assertEquals(1, one.selectedSignature!!.parameters.size)
        assertEquals(2, two.selectedSignature!!.parameters.size)
        assertEquals(3, three.selectedSignature!!.parameters.size)
        assertTrue(listOf(zero, one, two, three).all { it.isSuccess && !it.ambiguous })
        // Four args still fail closed set without inventing a signature.
        assertNoMatch(
            harness.check(
                clazz,
                listOf(
                    PrimitiveType.STRING,
                    PrimitiveType.NUMBER,
                    PrimitiveType.BOOLEAN,
                    PrimitiveType.ANY
                )
            )
        )
    }

    // --- ranking stability: fixed vs vararg / optional --------------------------

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
    fun varargConstructorSiblingSelectedWhenFixedArityDoesNotMatch() {
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

        val result = harness.check(
            clazz,
            listOf(PrimitiveType.STRING, PrimitiveType.STRING)
        )

        assertSuccessInstance(result, clazz)
        assertTrue(result.selectedSignature!!.parameters.any { it.vararg })
    }

    @Test
    fun exactRequiredConstructorArityPreferredOverOptionalTailSibling() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            oneArgCtor(PrimitiveType.STRING),
            FunctionType(
                parameters = listOf(
                    FunctionParameter("value", PrimitiveType.STRING),
                    FunctionParameter("radix", PrimitiveType.NUMBER, optional = true)
                ),
                returnType = UnknownType
            )
        )

        val result = harness.check(clazz, listOf(PrimitiveType.STRING))

        // Both match, but optional-tail carries fallbackPenalty so unary wins.
        assertSuccessInstance(result, clazz)
        assertEquals(1, result.selectedSignature!!.parameters.size)
        assertFalse(result.ambiguous)
    }

    @Test
    fun twoArgCallSelectsOptionalTailConstructorSiblingOverUnary() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            oneArgCtor(PrimitiveType.STRING),
            FunctionType(
                parameters = listOf(
                    FunctionParameter("value", PrimitiveType.STRING),
                    FunctionParameter("radix", PrimitiveType.NUMBER, optional = true)
                ),
                returnType = UnknownType
            )
        )

        val result = harness.check(
            clazz,
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertSuccessInstance(result, clazz)
        assertEquals(2, result.selectedSignature!!.parameters.size)
    }

    @Test
    fun zeroArgFixedPreferredOverOptionalOnlyVarargWhenNoArguments() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            zeroArgCtor(),
            FunctionType(
                parameters = listOf(
                    FunctionParameter("...", PrimitiveType.ANY, vararg = true)
                ),
                returnType = UnknownType
            )
        )

        val result = harness.check(clazz, emptyList())

        assertSuccessInstance(result, clazz)
        assertEquals(0, result.selectedSignature!!.parameters.size)
        assertFalse(result.selectedSignature!!.parameters.any { it.vararg })
        assertFalse(result.ambiguous)
    }

    // --- ambiguous arity degrades safely ----------------------------------------

    @Test
    fun sameArityTypeCompatibleConstructorTieIsAmbiguousButStillSelectsDeterministicFirst() {
        val harness = harness()
        // Distinct parameter names keep rewritten callSignatures distinct after
        // JavaClassType.withReturnType + CallChecker.signatures.distinct().
        val first = oneArgCtor(PrimitiveType.STRING, name = "left")
        val second = oneArgCtor(PrimitiveType.STRING, name = "right")
        val clazz = javaClassWithConstructors(first, second)

        val result = harness.check(clazz, listOf(PrimitiveType.STRING))

        assertTrue(result.isSuccess, "ambiguous constructor arity must still degrade with an instance type")
        assertTrue(result.ambiguous)
        assertEquals(CallFailureReason.AMBIGUOUS_MATCH, result.failureReason)
        assertIsInstanceOfClass(result.returnType, clazz)
        // Selected signature is the rewritten call surface (instance return); first wins by index.
        assertEquals(1, result.selectedSignature!!.parameters.size)
        assertEquals("left", result.selectedSignature!!.parameters.single().name)
        assertEquals(listOf(PrimitiveType.STRING), result.selectedSignature!!.parameters.map { it.type })
    }

    @Test
    fun sameArityConstructorAmbiguityIsStableAcrossRepeatedChecks() {
        val harness = harness()
        val first = oneArgCtor(PrimitiveType.STRING, name = "left")
        val second = oneArgCtor(PrimitiveType.STRING, name = "right")
        val clazz = javaClassWithConstructors(first, second)

        val results = (1..5).map {
            harness.check(clazz, listOf(PrimitiveType.STRING))
        }

        assertTrue(results.all { it.ambiguous })
        assertTrue(results.all { it.failureReason == CallFailureReason.AMBIGUOUS_MATCH })
        assertTrue(results.all { it.isSuccess })
        val firstSelected = results.first().selectedSignature
        assertNotNull(firstSelected)
        assertTrue(
            results.all {
                it.selectedSignature?.parameters?.map { p -> p.type } ==
                    firstSelected.parameters.map { p -> p.type } &&
                    it.selectedSignature?.parameters?.size == firstSelected.parameters.size &&
                    it.selectedSignature?.parameters?.single()?.name == firstSelected.parameters.single().name
            }
        )
    }

    @Test
    fun sameArityWiderAnyDoesNotTieWhenExactConstructorMatchExists() {
        val harness = harness()
        val exact = oneArgCtor(PrimitiveType.STRING)
        val wider = oneArgCtor(PrimitiveType.ANY)
        val clazz = javaClassWithConstructors(exact, wider)

        val result = harness.check(clazz, listOf(PrimitiveType.STRING))

        // exactMismatchCount ranks exact over any → not ambiguous
        assertSuccessInstance(result, clazz)
        assertFalse(result.ambiguous)
        assertEquals(listOf(PrimitiveType.STRING), result.selectedSignature!!.parameters.map { it.type })
    }

    @Test
    fun twoArgSameArityConstructorTieStillReturnsInstanceWithoutThrow() {
        val harness = harness()
        val first = twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER, firstName = "a1", secondName = "a2")
        val second = twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER, firstName = "b1", secondName = "b2")
        val clazz = javaClassWithConstructors(first, second)

        val result = harness.check(clazz, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))

        assertTrue(result.isSuccess)
        assertTrue(result.ambiguous)
        assertEquals(CallFailureReason.AMBIGUOUS_MATCH, result.failureReason)
        assertIsInstanceOfClass(result.returnType, clazz)
        assertEquals(2, result.selectedSignature!!.parameters.size)
        assertEquals("a1", result.selectedSignature!!.parameters.first().name)
    }

    // --- empty constructor set / no-match degrade -------------------------------

    @Test
    fun emptyConstructorSetExposesSyntheticZeroArgDefaultSurface() {
        // Product: JavaClassType.callSignatures falls back to a zero-arg instance
        // constructor when the overload set is empty (no throw).
        val harness = harness()
        val clazz = JavaClassType(
            javaName = JavaTypeName(packageName = "semantic.interop", simpleNames = listOf("NoCtors")),
            constructors = JavaOverloadSet()
        )

        val zero = harness.check(clazz, emptyList())
        assertSuccessInstance(zero, clazz)
        assertEquals(0, zero.selectedSignature!!.parameters.size)

        // Still rejects non-zero arity rather than inventing parameters.
        assertNoMatch(harness.check(clazz, listOf(PrimitiveType.STRING)))
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
    fun noMatchDegradesWithoutThrowForManyImpossibleArities() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        val attempts = listOf(
            emptyList(),
            listOf(PrimitiveType.STRING),
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN, PrimitiveType.ANY)
        )

        attempts.forEach { args ->
            val result = runCatching { harness.check(clazz, args) }
                .getOrElse { fail("constructor no-match must not throw for arity=${args.size}: $it") }
            assertNoMatch(result)
        }
    }

    @Test
    fun unknownArgumentRemainsArityFriendlyButDoesNotInventMissingArity() {
        val harness = harness()
        val clazz = javaClassWithConstructors(
            oneArgCtor(PrimitiveType.STRING),
            twoArgCtor(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        val unaryUnknown = harness.check(clazz, listOf(UnknownType))
        assertSuccessInstance(unaryUnknown, clazz)
        assertEquals(1, unaryUnknown.selectedSignature!!.parameters.size)

        // Three Unknown args still cannot invent a third closed constructor.
        assertNoMatch(
            harness.check(clazz, listOf(UnknownType, UnknownType, UnknownType))
        )
    }

    // --- reflected Java surfaces (StringBuilder / File / Object / Integer / UUID) -

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
    fun reflectedFileConstructorRejectsZeroArgAndPicksUnaryString() {
        val clazz = reflectedCallableClass("java.io.File")
        val arities = clazz.callSignatures.map { it.parameters.size }.toSet()
        assertTrue(1 in arities, "File must expose at least one 1-arg constructor; arities=$arities")
        assertFalse(0 in arities, "File must not expose a public zero-arg constructor; arities=$arities")

        val harness = harness()
        assertNoMatch(harness.check(clazz, emptyList()))

        val one = harness.check(clazz, listOf(PrimitiveType.STRING))
        assertTrue(one.isSuccess, "File(String) should match; reason=${one.failureReason}")
        assertEquals(1, one.selectedSignature!!.parameters.size)
        assertReflectedInstance(one.returnType, "java.io.File")
    }

    @Test
    fun reflectedObjectConstructorAcceptsZeroArgAndRejectsExtraArg() {
        val clazz = reflectedCallableClass("java.lang.Object")
        val harness = harness()

        val zero = harness.check(clazz, emptyList())
        assertTrue(zero.isSuccess, "Object() should match; reason=${zero.failureReason}")
        assertEquals(0, zero.selectedSignature!!.parameters.size)
        assertReflectedInstance(zero.returnType, "java.lang.Object")

        assertNoMatch(harness.check(clazz, listOf(PrimitiveType.STRING)))
    }

    @Test
    fun reflectedIntegerConstructorUnaryPickIsStableAcrossRepeats() {
        val clazz = reflectedCallableClass("java.lang.Integer")
        assertTrue(clazz.callSignatures.any { it.parameters.size == 1 })

        val harness = harness()
        val results = (1..4).map {
            harness.check(clazz, listOf(PrimitiveType.STRING))
        }

        assertTrue(
            results.all { it.isSuccess },
            "Integer(String) should match: ${results.map { it.failureReason }}"
        )
        assertTrue(results.all { it.selectedSignature!!.parameters.size == 1 })
        val firstSelected = results.first().selectedSignature
        assertTrue(
            results.all {
                it.selectedSignature === firstSelected ||
                    it.selectedSignature == firstSelected ||
                    (
                        it.selectedSignature?.parameters?.size == firstSelected?.parameters?.size &&
                            it.selectedSignature?.parameters?.map { p -> p.type } ==
                            firstSelected?.parameters?.map { p -> p.type }
                        )
            }
        )
        assertEquals(
            (results.first().returnType as? JavaInstanceType)?.javaName?.canonicalName,
            (results.last().returnType as? JavaInstanceType)?.javaName?.canonicalName
        )
    }

    @Test
    fun reflectedUuidRejectsUnaryConstructorArityWithoutThrow() {
        // java.util.UUID public constructor is (long, long) — one arg is wrong arity.
        val clazz = reflectedCallableClass("java.util.UUID")
        val harness = harness()

        assertNoMatch(harness.check(clazz, listOf(PrimitiveType.NUMBER)))
        val two = harness.check(clazz, listOf(PrimitiveType.NUMBER, PrimitiveType.NUMBER))
        assertTrue(two.isSuccess, "UUID(long,long) should match; reason=${two.failureReason}")
        assertEquals(2, two.selectedSignature!!.parameters.size)
        assertReflectedInstance(two.returnType, "java.util.UUID")
    }

    @Test
    fun reflectedStringBuilderRejectsImpossibleArityWithoutThrow() {
        val clazz = reflectedCallableClass("java.lang.StringBuilder")
        val harness = harness()

        // Four string args is not a public StringBuilder constructor shape.
        val result = runCatching {
            harness.check(
                clazz,
                listOf(
                    PrimitiveType.STRING,
                    PrimitiveType.STRING,
                    PrimitiveType.STRING,
                    PrimitiveType.STRING
                )
            )
        }.getOrElse { fail("reflected no-match must not throw: $it") }

        assertNoMatch(result)
    }

    @Test
    fun reflectedArrayListZeroArgConstructorPickIsStable() {
        val clazz = reflectedCallableClass("java.util.ArrayList")
        val arities = clazz.callSignatures.map { it.parameters.size }.toSet()
        assertTrue(0 in arities, "ArrayList must expose zero-arg ctor; arities=$arities")

        val harness = harness()
        val results = (1..3).map { harness.check(clazz, emptyList()) }

        assertTrue(results.all { it.isSuccess }, "ArrayList() should match: ${results.map { it.failureReason }}")
        assertTrue(results.all { it.selectedSignature!!.parameters.size == 0 })
        assertTrue(results.all { !it.ambiguous })
        assertEquals(
            (results.first().returnType as? JavaInstanceType)?.javaName?.canonicalName,
            "java.util.ArrayList"
        )
    }

    @Test
    fun reflectedModuleCallSurfaceResolvesThroughModule__call() {
        // ModuleType with __call = JavaClassType must resolve as the constructor surface.
        val module = reflectedModule("java.lang.StringBuilder")
        assertNotNull(module.fields["__call"], "expected __call constructor surface on StringBuilder module")
        val harness = harness()

        val result = harness.check(module, emptyList())
        assertTrue(result.isSuccess, "module __call 0-arg should match; reason=${result.failureReason}")
        assertEquals(0, result.selectedSignature!!.parameters.size)
        assertReflectedInstance(result.returnType, "java.lang.StringBuilder")
    }

    @Test
    fun reflectedModuleCallSurfaceRejectsImpossibleArityWithoutThrow() {
        val module = reflectedModule("java.lang.Object")
        val harness = harness()

        val result = runCatching {
            harness.check(module, listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        }.getOrElse { fail("module __call no-match must not throw: $it") }

        assertNoMatch(result)
    }

    // --- corpus table -----------------------------------------------------------

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
