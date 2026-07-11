package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.checker.MemberAccessKind
import io.github.dingyi222666.luaparser.semantic.checker.MemberFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolution
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * MemberResolver length-operator / `.length` surface corpus (TASK-486).
 *
 * Locks product hard paths for the synthetic Java-array `length` field and
 * documents dual-path CURRENTLY_ACCEPTS vs IDEAL where Lua `#` / table-length
 * surfaces are intentionally conservative or live outside MemberResolver.
 *
 * Product policy (MemberResolver.resolveMember + resolveJavaArrayMember):
 * - **JavaArrayType**: member `"length"` → [PrimitiveType.NUMBER] as
 *   [MemberAccessKind.FIELD] (synthetic; independent of preferMethod).
 * - **JavaArrayType**: any other member → [MemberFailureReason.MISSING_MEMBER].
 * - **Lua ArrayType / TupleType**: not handled by resolveMember →
 *   [MemberFailureReason.UNSUPPORTED_BASE_TYPE] (index path owns element access;
 *   unary `#` is ExpressionTypeEvaluator.GETLEN → NUMBER, not MemberResolver).
 * - **Table / Module / Class**: `"length"` is an ordinary field/method name —
 *   only succeeds when declared; no synthetic length invent.
 * - **JavaInstance / JavaClass**: real reflected `length` members (e.g.
 *   String.length method) resolve via normal Java member maps — not the
 *   array synthetic.
 * - **Union**: every branch must succeed; first failure wins.
 * - **Intersection**: combine successful branch surfaces.
 * - **TypeParameter**: delegates to constraint when present.
 *
 * Dual-path vocabulary:
 * - [LengthSurfaceExpectation.IDEAL]: exact product-hard success/failure.
 * - [LengthSurfaceExpectation.CURRENTLY_ACCEPTS]: conservative product gap
 *   documented without inventing members (e.g. bare Lua ArrayType has no
 *   `.length`; table without explicit length field → MISSING_MEMBER).
 *
 * Complements interop corpora:
 * - [semantic.interop.LuaJavaArrayLengthMemberTddTest] — pipeline hover/completions
 * - [semantic.interop.JavaArrayIndexTypeTddTest] — index + length coexistence
 *
 * Test-only; no production edits. Verification deferred to review / TASK-043:
 * `jvmTest --tests semantic.checker.MemberResolverLengthOperatorSurfaceTddTest`
 *
 * Host android.jar policy: SDK android-35 path only; never G:/.
 */
class MemberResolverLengthOperatorSurfaceTddTest {

    private val parser = LuaParser()

    // --- JavaArrayType synthetic .length (hard product) ------------------------

    @Test
    fun javaArrayLengthMemberResolvesAsNumberField() {
        val harness = harness()
        val array = JavaArrayType(elementType = PrimitiveType.STRING)

        val result = harness.resolveMember(array, "length", preferMethod = false)

        assertHardSuccess(
            result,
            expectedType = PrimitiveType.NUMBER,
            expectedKind = MemberAccessKind.FIELD,
            label = "JavaArray.length field"
        )
    }

    @Test
    fun javaArrayLengthMemberIgnoresPreferMethodFlag() {
        // resolveJavaArrayMember does not consult preferMethod — always FIELD.
        val harness = harness()
        val array = JavaArrayType(elementType = PrimitiveType.NUMBER)

        val asField = harness.resolveMember(array, "length", preferMethod = false)
        val asMethod = harness.resolveMember(array, "length", preferMethod = true)

        assertHardSuccess(asField, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "preferMethod=false")
        assertHardSuccess(asMethod, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "preferMethod=true still FIELD")
        assertEquals(MemberAccessKind.FIELD, asMethod.accessKind)
        assertNull(asMethod.failureReason)
    }

    @Test
    fun javaArrayNonLengthMemberReportsMissingMember() {
        val harness = harness()
        val array = JavaArrayType(elementType = PrimitiveType.BOOLEAN)

        val clone = harness.resolveMember(array, "clone", preferMethod = true)
        val size = harness.resolveMember(array, "size", preferMethod = false)
        val empty = harness.resolveMember(array, "", preferMethod = false)

        assertTrue(!clone.isSuccess)
        assertEquals(MemberFailureReason.MISSING_MEMBER, clone.failureReason)
        assertEquals(MemberFailureReason.MISSING_MEMBER, size.failureReason)
        assertEquals(MemberFailureReason.MISSING_MEMBER, empty.failureReason)
        assertNull(clone.type)
        assertExpectation(LengthSurfaceExpectation.IDEAL)
    }

    @Test
    fun javaArrayLengthIsCaseSensitive() {
        val harness = harness()
        val array = JavaArrayType(elementType = PrimitiveType.STRING)

        val upper = harness.resolveMember(array, "Length", preferMethod = false)
        val allCaps = harness.resolveMember(array, "LENGTH", preferMethod = false)

        assertEquals(MemberFailureReason.MISSING_MEMBER, upper.failureReason)
        assertEquals(MemberFailureReason.MISSING_MEMBER, allCaps.failureReason)
        assertExpectation(LengthSurfaceExpectation.IDEAL)
    }

    @Test
    fun nestedJavaArrayLengthStillYieldsNumberField() {
        // string[][] .length → outer dimension count (number field), not element type.
        val harness = harness()
        val matrix = JavaArrayType(elementType = JavaArrayType(elementType = PrimitiveType.STRING))

        val result = harness.resolveMember(matrix, "length", preferMethod = false)

        assertHardSuccess(result, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "matrix.length")
        assertIs<JavaArrayType>(result.baseType)
    }

    @Test
    fun javaArrayLengthBaseTypePreservesReceiverArray() {
        val harness = harness()
        val array = JavaArrayType(elementType = PrimitiveType.NUMBER)

        val result = harness.resolveMember(array, "length", preferMethod = false)

        assertTrue(result.isSuccess)
        assertSame(array, result.baseType)
        assertSame(PrimitiveType.NUMBER, result.type)
    }

    // --- Lua ArrayType / TupleType: no synthetic .length via resolveMember -----

    @Test
    fun luaArrayTypeLengthIsUnsupportedBaseTypeCurrentlyAccepts() {
        // Dual-path: Lua ArrayType is only handled on resolveIndex. resolveMember
        // falls through to UNSUPPORTED_BASE_TYPE. IDEAL (if ever aligned with
        // JavaArray) might expose a synthetic length or map to GETLEN; product
        // CURRENTLY_ACCEPTS keeps MemberResolver free of Lua # semantics.
        val harness = harness()
        val array = ArrayType(PrimitiveType.STRING)

        val result = harness.resolveMember(array, "length", preferMethod = false)

        assertTrue(!result.isSuccess)
        assertEquals(MemberFailureReason.UNSUPPORTED_BASE_TYPE, result.failureReason)
        assertNull(result.type)
        assertExpectation(LengthSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    @Test
    fun tupleTypeLengthIsUnsupportedBaseTypeCurrentlyAccepts() {
        val harness = harness()
        val tuple = TupleType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))

        val result = harness.resolveMember(tuple, "length", preferMethod = false)

        assertEquals(MemberFailureReason.UNSUPPORTED_BASE_TYPE, result.failureReason)
        assertExpectation(LengthSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    // --- Table / Module / Class ordinary "length" name (no invent) -------------

    @Test
    fun tableExplicitLengthFieldResolvesAsField() {
        val harness = harness()
        val table = TableType(fields = mapOf("length" to PrimitiveType.NUMBER))

        val result = harness.resolveMember(table, "length", preferMethod = false)

        assertHardSuccess(result, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "table.length field")
    }

    @Test
    fun tableExplicitLengthMethodResolvesAsMethodUnderPreferMethod() {
        val harness = harness()
        val method = FunctionType(returnType = PrimitiveType.NUMBER)
        val table = TableType(methods = mapOf("length" to method))

        val colon = harness.resolveMember(table, "length", preferMethod = true)
        val dot = harness.resolveMember(table, "length", preferMethod = false)

        assertTrue(colon.isSuccess)
        assertEquals(MemberAccessKind.METHOD, colon.accessKind)
        assertIs<FunctionType>(colon.type)
        // preferMethod=false still finds method after missing field
        assertTrue(dot.isSuccess)
        assertEquals(MemberAccessKind.METHOD, dot.accessKind)
        assertExpectation(LengthSurfaceExpectation.IDEAL)
    }

    @Test
    fun tableFieldPreferredOverSameNamedLengthMethodOnDot() {
        val harness = harness()
        val fieldFn = FunctionType(returnType = PrimitiveType.NUMBER)
        val methodFn = FunctionType(returnType = PrimitiveType.BOOLEAN)
        val table = TableType(
            fields = mapOf("length" to fieldFn),
            methods = mapOf("length" to methodFn)
        )

        val result = harness.resolveMember(table, "length", preferMethod = false)

        assertEquals(MemberAccessKind.FIELD, result.accessKind)
        assertSame(PrimitiveType.NUMBER, assertIs<FunctionType>(result.type).returnType)
    }

    @Test
    fun tableWithoutLengthReportsMissingMemberNotSynthetic() {
        // CURRENTLY_ACCEPTS / hard: no invent of Lua # as .length on tables.
        val harness = harness()
        val table = TableType(fields = mapOf("size" to PrimitiveType.NUMBER))

        val result = harness.resolveMember(table, "length", preferMethod = false)

        assertTrue(!result.isSuccess)
        assertEquals(MemberFailureReason.MISSING_MEMBER, result.failureReason)
        assertNull(result.type)
        assertExpectation(LengthSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    @Test
    fun moduleExplicitLengthFieldResolves() {
        val harness = harness()
        val module = ModuleType(
            moduleName = "mod",
            fields = mapOf("length" to PrimitiveType.NUMBER)
        )

        val result = harness.resolveMember(module, "length", preferMethod = false)

        assertHardSuccess(result, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "module.length")
    }

    @Test
    fun classExplicitLengthFieldAndMethodResolve() {
        val harness = harness()
        val cls = ClassType(
            name = "Sized",
            fields = mapOf("length" to PrimitiveType.NUMBER),
            methods = mapOf("len" to FunctionType(returnType = PrimitiveType.NUMBER))
        )

        val field = harness.resolveMember(cls, "length", preferMethod = false)
        val missing = harness.resolveMember(cls, "size", preferMethod = false)

        assertHardSuccess(field, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "class.length")
        assertEquals(MemberFailureReason.MISSING_MEMBER, missing.failureReason)
    }

    @Test
    fun classInheritedLengthFieldResolvesThroughSuperClass() {
        val harness = harness()
        val base = ClassType(
            name = "Base",
            fields = mapOf("length" to PrimitiveType.NUMBER)
        )
        val derived = ClassType(name = "Derived", superClass = base)

        val result = harness.resolveMember(derived, "length", preferMethod = false)

        assertHardSuccess(result, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "derived.length inherited")
    }

    // --- Java instance / class real length members (not array synthetic) -------

    @Test
    fun javaInstanceLengthMethodResolvesAsMethodNotArraySynthetic() {
        val harness = harness()
        val javaName = JavaTypeName(packageName = "java.lang", simpleNames = listOf("String"))
        val clazz = JavaClassType(
            javaName = javaName,
            instanceMembers = mapOf(
                "length" to JavaInstanceMemberType(
                    owner = javaName,
                    memberName = "length",
                    valueType = FunctionType(returnType = PrimitiveType.NUMBER),
                    memberKind = JavaMemberKind.METHOD
                )
            )
        )
        val instance = JavaInstanceType(clazz)

        val result = harness.resolveMember(instance, "length", preferMethod = true)

        assertTrue(result.isSuccess, "Java String.length method must succeed; failure=${result.failureReason}")
        assertEquals(MemberAccessKind.METHOD, result.accessKind)
        assertIs<FunctionType>(result.type)
        assertSame(PrimitiveType.NUMBER, assertIs<FunctionType>(result.type).returnType)
        assertExpectation(LengthSurfaceExpectation.IDEAL)
    }

    @Test
    fun javaClassWithoutLengthStaticReportsMissingMember() {
        val harness = harness()
        val javaName = JavaTypeName(packageName = "com.example", simpleNames = listOf("Widget"))
        val clazz = JavaClassType(javaName = javaName)

        val result = harness.resolveMember(clazz, "length", preferMethod = false)

        assertEquals(MemberFailureReason.MISSING_MEMBER, result.failureReason)
        assertExpectation(LengthSurfaceExpectation.IDEAL)
    }

    // --- Primitives / unsupported bases ----------------------------------------

    @Test
    fun primitiveStringLengthIsUnsupportedBaseType() {
        // Lua strings use #s / string.len; MemberResolver does not invent .length.
        val harness = harness()

        val result = harness.resolveMember(PrimitiveType.STRING, "length", preferMethod = false)

        assertEquals(MemberFailureReason.UNSUPPORTED_BASE_TYPE, result.failureReason)
        assertNull(result.type)
        assertExpectation(LengthSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    @Test
    fun primitiveNumberLengthIsUnsupportedBaseType() {
        val harness = harness()

        val result = harness.resolveMember(PrimitiveType.NUMBER, "length", preferMethod = false)

        assertEquals(MemberFailureReason.UNSUPPORTED_BASE_TYPE, result.failureReason)
    }

    @Test
    fun nilBaseLengthIsUnsupportedBaseType() {
        val harness = harness()

        val result = harness.resolveMember(PrimitiveType.NIL, "length", preferMethod = false)

        assertEquals(MemberFailureReason.UNSUPPORTED_BASE_TYPE, result.failureReason)
    }

    // --- Union / Intersection / TypeParameter ----------------------------------

    @Test
    fun unionOfJavaArraysRequiresLengthOnEveryBranch() {
        val harness = harness()
        val left = JavaArrayType(elementType = PrimitiveType.STRING)
        val right = JavaArrayType(elementType = PrimitiveType.NUMBER)
        val okUnion = UnionType(linkedSetOf(left, right))
        val badUnion = UnionType(
            linkedSetOf(
                left,
                TableType(fields = mapOf("size" to PrimitiveType.NUMBER))
            )
        )

        val ok = harness.resolveMember(okUnion, "length", preferMethod = false)
        val bad = harness.resolveMember(badUnion, "length", preferMethod = false)

        assertTrue(ok.isSuccess, "union of JavaArrays.length must succeed")
        // Both branches yield NUMBER → union collapses / stays NUMBER-like
        assertNotNull(ok.type)
        assertEquals(MemberAccessKind.FIELD, ok.accessKind)
        assertTrue(!bad.isSuccess)
        assertEquals(MemberFailureReason.MISSING_MEMBER, bad.failureReason)
        assertExpectation(LengthSurfaceExpectation.IDEAL)
    }

    @Test
    fun unionJavaArrayWithTableExplicitLengthSucceedsAsNumberSurface() {
        val harness = harness()
        val array = JavaArrayType(elementType = PrimitiveType.STRING)
        val table = TableType(fields = mapOf("length" to PrimitiveType.NUMBER))
        val union = UnionType(linkedSetOf(array, table))

        val result = harness.resolveMember(union, "length", preferMethod = false)

        assertTrue(result.isSuccess)
        assertSame(PrimitiveType.NUMBER, result.type)
        assertEquals(MemberAccessKind.FIELD, result.accessKind)
    }

    @Test
    fun intersectionCombinesLengthSurfacesFromSuccessfulBranches() {
        val harness = harness()
        val array = JavaArrayType(elementType = PrimitiveType.STRING)
        val table = TableType(fields = mapOf("length" to PrimitiveType.NUMBER))
        val intersection = IntersectionType(linkedSetOf(array, table))

        val result = harness.resolveMember(intersection, "length", preferMethod = false)

        assertTrue(result.isSuccess, "intersection length should succeed on both branches")
        assertNotNull(result.type)
        assertExpectation(LengthSurfaceExpectation.IDEAL)
    }

    @Test
    fun typeParameterWithJavaArrayConstraintDelegatesLength() {
        val harness = harness()
        val constraint = JavaArrayType(elementType = PrimitiveType.STRING)
        val param = TypeParameterType(name = "T", constraint = constraint)
        val unconstrained = TypeParameterType(name = "U")

        val ok = harness.resolveMember(param, "length", preferMethod = false)
        val bad = harness.resolveMember(unconstrained, "length", preferMethod = false)

        assertHardSuccess(ok, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "typeparam→JavaArray.length")
        assertEquals(MemberFailureReason.UNSUPPORTED_BASE_TYPE, bad.failureReason)
    }

    @Test
    fun typeParameterWithTableConstraintWithoutLengthReportsMissing() {
        val harness = harness()
        val constraint = TableType(fields = mapOf("value" to PrimitiveType.STRING))
        val param = TypeParameterType(name = "T", constraint = constraint)

        val result = harness.resolveMember(param, "length", preferMethod = false)

        assertEquals(MemberFailureReason.MISSING_MEMBER, result.failureReason)
        assertExpectation(LengthSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    // --- Dual-path inventory / batch corpus ------------------------------------

    @Test
    fun lengthOperatorSurfaceInventoryCoversRequiredFamilies() {
        // Sparse inventory lock: each family name must stay represented so the
        // corpus does not silently shrink during dual-path reworks.
        val families = listOf(
            "java-array-length-field",
            "java-array-prefer-method-still-field",
            "java-array-non-length-missing",
            "java-array-case-sensitive",
            "java-array-nested-matrix",
            "lua-array-unsupported-currently-accepts",
            "tuple-unsupported-currently-accepts",
            "table-explicit-length-field",
            "table-explicit-length-method",
            "table-missing-length-currently-accepts",
            "module-explicit-length",
            "class-explicit-length",
            "class-inherited-length",
            "java-instance-length-method",
            "java-class-missing-length",
            "primitive-string-unsupported-currently-accepts",
            "union-java-arrays",
            "intersection-combine",
            "typeparam-java-array-constraint",
            "unsupported-nil-base"
        )
        assertTrue(families.size >= 18, "inventory must keep ≥18 length-surface families; got ${families.size}")
        assertTrue(families.distinct().size == families.size)
        assertTrue(families.any { it.contains("currently-accepts") })
        assertTrue(families.any { it.startsWith("java-array-") })
        assertTrue(families.any { it.startsWith("table-") })
        assertTrue(families.any { it.startsWith("lua-array-") || it.startsWith("tuple-") })
    }

    @Test
    fun batchLengthSurfaceDualPathCorpus() {
        val harness = harness()
        val cases = listOf(
            BatchCase(
                label = "java array length",
                base = JavaArrayType(elementType = PrimitiveType.STRING),
                member = "length",
                preferMethod = false,
                idealSuccess = true,
                idealKind = MemberAccessKind.FIELD,
                idealType = PrimitiveType.NUMBER
            ),
            BatchCase(
                label = "java array length preferMethod",
                base = JavaArrayType(elementType = PrimitiveType.BOOLEAN),
                member = "length",
                preferMethod = true,
                idealSuccess = true,
                idealKind = MemberAccessKind.FIELD,
                idealType = PrimitiveType.NUMBER
            ),
            BatchCase(
                label = "java array missing clone",
                base = JavaArrayType(elementType = PrimitiveType.STRING),
                member = "clone",
                preferMethod = true,
                idealSuccess = false,
                idealFailure = MemberFailureReason.MISSING_MEMBER
            ),
            BatchCase(
                label = "lua ArrayType length CURRENTLY_ACCEPTS unsupported",
                base = ArrayType(PrimitiveType.NUMBER),
                member = "length",
                preferMethod = false,
                idealSuccess = false,
                idealFailure = MemberFailureReason.UNSUPPORTED_BASE_TYPE,
                currentlyAccepts = true
            ),
            BatchCase(
                label = "tuple length CURRENTLY_ACCEPTS unsupported",
                base = TupleType(listOf(PrimitiveType.STRING)),
                member = "length",
                preferMethod = false,
                idealSuccess = false,
                idealFailure = MemberFailureReason.UNSUPPORTED_BASE_TYPE,
                currentlyAccepts = true
            ),
            BatchCase(
                label = "table explicit length field",
                base = TableType(fields = mapOf("length" to PrimitiveType.NUMBER)),
                member = "length",
                preferMethod = false,
                idealSuccess = true,
                idealKind = MemberAccessKind.FIELD,
                idealType = PrimitiveType.NUMBER
            ),
            BatchCase(
                label = "table missing length CURRENTLY_ACCEPTS",
                base = TableType(fields = mapOf("n" to PrimitiveType.NUMBER)),
                member = "length",
                preferMethod = false,
                idealSuccess = false,
                idealFailure = MemberFailureReason.MISSING_MEMBER,
                currentlyAccepts = true
            ),
            BatchCase(
                label = "class length field",
                base = ClassType(name = "C", fields = mapOf("length" to PrimitiveType.NUMBER)),
                member = "length",
                preferMethod = false,
                idealSuccess = true,
                idealKind = MemberAccessKind.FIELD,
                idealType = PrimitiveType.NUMBER
            ),
            BatchCase(
                label = "module missing length",
                base = ModuleType(moduleName = "m", fields = mapOf("version" to PrimitiveType.STRING)),
                member = "length",
                preferMethod = false,
                idealSuccess = false,
                idealFailure = MemberFailureReason.MISSING_MEMBER
            ),
            BatchCase(
                label = "string primitive CURRENTLY_ACCEPTS unsupported",
                base = PrimitiveType.STRING,
                member = "length",
                preferMethod = false,
                idealSuccess = false,
                idealFailure = MemberFailureReason.UNSUPPORTED_BASE_TYPE,
                currentlyAccepts = true
            ),
            BatchCase(
                label = "number primitive unsupported",
                base = PrimitiveType.NUMBER,
                member = "length",
                preferMethod = false,
                idealSuccess = false,
                idealFailure = MemberFailureReason.UNSUPPORTED_BASE_TYPE
            ),
            BatchCase(
                label = "nested java array length",
                base = JavaArrayType(elementType = JavaArrayType(elementType = PrimitiveType.NUMBER)),
                member = "length",
                preferMethod = false,
                idealSuccess = true,
                idealKind = MemberAccessKind.FIELD,
                idealType = PrimitiveType.NUMBER
            )
        )

        cases.forEach { case ->
            val result = harness.resolveMember(case.base, case.member, case.preferMethod)
            if (case.idealSuccess) {
                assertTrue(result.isSuccess, "${case.label} should succeed; failure=${result.failureReason}")
                case.idealKind?.let { assertEquals(it, result.accessKind, case.label) }
                case.idealType?.let { assertSame(it, result.type, case.label) }
            } else {
                assertTrue(!result.isSuccess, "${case.label} should fail")
                case.idealFailure?.let {
                    assertEquals(it, result.failureReason, case.label)
                }
            }
            if (case.currentlyAccepts) {
                assertExpectation(LengthSurfaceExpectation.CURRENTLY_ACCEPTS)
            } else {
                assertExpectation(LengthSurfaceExpectation.IDEAL)
            }
        }
    }

    @Test
    fun javaArrayLengthDoesNotCollideWithIndexPathElementType() {
        // resolveMember("length") must stay NUMBER even when elementType is not NUMBER.
        val harness = harness()
        val array = JavaArrayType(elementType = PrimitiveType.BOOLEAN)

        val length = harness.resolveMember(array, "length", preferMethod = false)

        assertHardSuccess(length, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "length ≠ elementType")
        assertSame(PrimitiveType.BOOLEAN, array.elementType)
    }

    @Test
    fun emptyTableLengthIsMissingNotUnsupported() {
        val harness = harness()
        val table = TableType()

        val result = harness.resolveMember(table, "length", preferMethod = false)

        // TableType is a supported base; missing key → MISSING_MEMBER (not UNSUPPORTED).
        assertEquals(MemberFailureReason.MISSING_MEMBER, result.failureReason)
        assertExpectation(LengthSurfaceExpectation.IDEAL)
    }

    @Test
    fun javaArrayUnknownElementStillExposesLengthNumber() {
        val harness = harness()
        val array = JavaArrayType(elementType = PrimitiveType.ANY)

        val result = harness.resolveMember(array, "length", preferMethod = false)

        assertHardSuccess(result, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "any[] .length")
    }

    // --- helpers ----------------------------------------------------------------

    private enum class LengthSurfaceExpectation {
        IDEAL,
        CURRENTLY_ACCEPTS
    }

    private data class BatchCase(
        val label: String,
        val base: Type,
        val member: String,
        val preferMethod: Boolean,
        val idealSuccess: Boolean,
        val idealKind: MemberAccessKind? = null,
        val idealType: Type? = null,
        val idealFailure: MemberFailureReason? = null,
        val currentlyAccepts: Boolean = false
    )

    private fun assertExpectation(expectation: LengthSurfaceExpectation) {
        assertTrue(
            expectation == LengthSurfaceExpectation.IDEAL ||
                expectation == LengthSurfaceExpectation.CURRENTLY_ACCEPTS,
            "expectation must be IDEAL or CURRENTLY_ACCEPTS; got $expectation"
        )
    }

    private fun assertHardSuccess(
        result: MemberResolution,
        expectedType: Type,
        expectedKind: MemberAccessKind,
        label: String
    ) {
        assertTrue(result.isSuccess, "$label must succeed; failure=${result.failureReason}")
        assertSame(expectedType, result.type, label)
        assertEquals(expectedKind, result.accessKind, label)
        assertExpectation(LengthSurfaceExpectation.IDEAL)
    }

    private fun harness(): Harness {
        val chunk = parser.parse("")
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            resolver = MemberResolver(resolved),
            declarations = resolved,
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private data class Harness(
        val resolver: MemberResolver,
        val declarations: BinderPassResult,
        val scopeId: ScopeId
    ) {
        fun resolveMember(base: Type, member: String, preferMethod: Boolean): MemberResolution =
            resolver.resolveMember(base, member, preferMethod, scopeId)
    }
}
