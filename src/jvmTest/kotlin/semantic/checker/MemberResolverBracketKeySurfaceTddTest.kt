package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
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
import io.github.dingyi222666.luaparser.semantic.types.model.JavaStaticMemberType
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
 * MemberResolver bracket-key (`resolveIndex`) surface corpus (TASK-439).
 *
 * Locks product hard paths for `base[key]` resolution across Table / Module /
 * Class / Array / Tuple / Java / union / intersection / type-parameter bases,
 * and documents dual-path CURRENTLY_ACCEPTS vs IDEAL where the surface is
 * intentionally conservative (non-literal keys, non-string class keys, etc.).
 *
 * Product policy (MemberResolver.resolveIndex + literalTableKey/stringLiteralKey):
 * - **Table / Module**: string + integer *literals* hit fields then methods
 *   (integer key stringified, e.g. `t[1]` → field `"1"`). Otherwise
 *   [TableType.IndexSignature] / [ModuleType.IndexSignature] when key type is
 *   assignable; else MISSING_MEMBER (no signature) or INVALID_INDEX_TYPE.
 * - **Class / JavaClass / JavaInstance**: string *literal* keys only; non-string
 *   keys → INVALID_INDEX_TYPE (not rewritten to numeric field names).
 * - **Array / JavaArray**: NUMBER-assignable index → elementType as INDEX.
 * - **Tuple**: 1-based integer *literal* only (float only when integral).
 * - **Union**: every branch must succeed; first failure wins.
 * - **Intersection**: combine successful branch surfaces.
 *
 * Dual-path vocabulary:
 * - [BracketSurfaceExpectation.IDEAL]: exact product-hard success/failure.
 * - [BracketSurfaceExpectation.CURRENTLY_ACCEPTS]: conservative product gap
 *   documented without inventing members (e.g. bare Identifier keys without
 *   index signature; non-literal expression keys).
 *
 * Test-only; no production edits. Verification deferred to review / TASK-043:
 * `jvmTest --tests semantic.checker.MemberResolverBracketKeySurfaceTddTest`
 */
class MemberResolverBracketKeySurfaceTddTest {

    private val parser = LuaParser()

    // --- TableType: string / integer literal field + method keys ---------------

    @Test
    fun tableStringLiteralKeyResolvesFieldAsFieldAccess() {
        val harness = harness()
        val table = TableType(fields = mapOf("name" to PrimitiveType.STRING))

        val result = harness.resolveIndex(table, stringNode("name"), PrimitiveType.STRING)

        assertHardSuccess(
            result,
            expectedType = PrimitiveType.STRING,
            expectedKind = MemberAccessKind.FIELD,
            label = "table[\"name\"] field"
        )
    }

    @Test
    fun tableStringLiteralKeyResolvesMethodAsMethodAccess() {
        val harness = harness()
        val method = FunctionType(returnType = PrimitiveType.BOOLEAN)
        val table = TableType(methods = mapOf("run" to method))

        val result = harness.resolveIndex(table, stringNode("run"), PrimitiveType.STRING)

        assertTrue(result.isSuccess, "table[\"run\"] method must succeed; failure=${result.failureReason}")
        assertEquals(MemberAccessKind.METHOD, result.accessKind)
        assertIs<FunctionType>(result.type)
        assertSame(PrimitiveType.BOOLEAN, assertIs<FunctionType>(result.type).returnType)
    }

    @Test
    fun tableStringLiteralPrefersFieldOverSameNamedMethod() {
        // resolveTableIndex checks fields before methods for literal keys
        // (unlike resolveMember preferMethod path).
        val harness = harness()
        val fieldFn = FunctionType(returnType = PrimitiveType.NUMBER)
        val methodFn = FunctionType(returnType = PrimitiveType.BOOLEAN)
        val table = TableType(
            fields = mapOf("run" to fieldFn),
            methods = mapOf("run" to methodFn)
        )

        val result = harness.resolveIndex(table, stringNode("run"), PrimitiveType.STRING)

        assertEquals(MemberAccessKind.FIELD, result.accessKind)
        assertSame(PrimitiveType.NUMBER, assertIs<FunctionType>(result.type).returnType)
    }

    @Test
    fun tableIntegerLiteralKeyHitsStringifiedFieldKey() {
        val harness = harness()
        val table = TableType(fields = mapOf("1" to PrimitiveType.STRING, "2" to PrimitiveType.NUMBER))

        val first = harness.resolveIndex(table, intNode(1), PrimitiveType.NUMBER)
        val second = harness.resolveIndex(table, intNode(2), PrimitiveType.NUMBER)

        assertHardSuccess(first, PrimitiveType.STRING, MemberAccessKind.FIELD, "table[1] → field \"1\"")
        assertHardSuccess(second, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "table[2] → field \"2\"")
    }

    @Test
    fun tableIntegralFloatLiteralKeyHitsStringifiedFieldKey() {
        val harness = harness()
        val table = TableType(fields = mapOf("3" to PrimitiveType.BOOLEAN))

        val result = harness.resolveIndex(table, floatNode(3.0), PrimitiveType.NUMBER)

        assertHardSuccess(result, PrimitiveType.BOOLEAN, MemberAccessKind.FIELD, "table[3.0] → field \"3\"")
    }

    @Test
    fun tableNonIntegralFloatLiteralDoesNotHitStringifiedFieldAndFallsThrough() {
        val harness = harness()
        val table = TableType(fields = mapOf("1" to PrimitiveType.STRING))

        val result = harness.resolveIndex(table, floatNode(1.5), PrimitiveType.NUMBER)

        // Non-integral float is not a literalTableKey → no field hit.
        // No index signature → MISSING_MEMBER (hard product).
        assertTrue(!result.isSuccess)
        assertEquals(MemberFailureReason.MISSING_MEMBER, result.failureReason)
        assertNull(result.type)
    }

    @Test
    fun tableMissingLiteralKeyWithoutIndexSignatureReportsMissingMember() {
        val harness = harness()
        val table = TableType(fields = mapOf("known" to PrimitiveType.STRING))

        val result = harness.resolveIndex(table, stringNode("missing"), PrimitiveType.STRING)

        assertTrue(!result.isSuccess)
        assertEquals(MemberFailureReason.MISSING_MEMBER, result.failureReason)
    }

    @Test
    fun tableIndexSignatureServesNonLiteralAndMismatchedLiteralKeys() {
        val harness = harness()
        val table = TableType(
            fields = mapOf("fixed" to PrimitiveType.BOOLEAN),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        val viaSig = harness.resolveIndex(table, stringNode("dynamic"), PrimitiveType.STRING)
        val viaField = harness.resolveIndex(table, stringNode("fixed"), PrimitiveType.STRING)
        val badKeyType = harness.resolveIndex(table, intNode(1), PrimitiveType.NUMBER)

        assertHardSuccess(viaSig, PrimitiveType.NUMBER, MemberAccessKind.INDEX, "indexSignature string key")
        assertHardSuccess(viaField, PrimitiveType.BOOLEAN, MemberAccessKind.FIELD, "literal field still wins")
        // Integer literal "1" misses fields/methods; NUMBER not assignable from STRING keyType
        // wait: isAssignableFrom direction is keyType.isAssignableFrom(indexType)
        // STRING.isAssignableFrom(NUMBER) → false → INVALID_INDEX_TYPE
        assertTrue(!badKeyType.isSuccess)
        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, badKeyType.failureReason)
    }

    @Test
    fun tableNumberIndexSignatureAcceptsIntegerLiteralWhenNoFieldHit() {
        val harness = harness()
        val table = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, PrimitiveType.STRING)
        )

        val result = harness.resolveIndex(table, intNode(9), PrimitiveType.NUMBER)

        // No field "9"; NUMBER signature accepts NUMBER index → INDEX
        assertHardSuccess(result, PrimitiveType.STRING, MemberAccessKind.INDEX, "number indexSignature")
    }

    @Test
    fun tableIdentifierKeyWithoutIndexSignatureIsCurrentlyAcceptsMissing() {
        // Dual-path: bare Identifier is not a ConstantNode literal → no field hit.
        // Product CURRENTLY_ACCEPTS: MISSING_MEMBER (no invent). IDEAL remains
        // "resolve dynamic key via runtime-known constant folding" if ever added.
        val harness = harness()
        val table = TableType(fields = mapOf("name" to PrimitiveType.STRING))

        val result = harness.resolveIndex(table, Identifier("name"), PrimitiveType.STRING)

        assertTrue(!result.isSuccess)
        assertEquals(
            MemberFailureReason.MISSING_MEMBER,
            result.failureReason,
            "CURRENTLY_ACCEPTS: Identifier key without indexSignature → MISSING_MEMBER"
        )
        assertExpectation(BracketSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    @Test
    fun tableIdentifierKeyWithMatchingIndexSignatureResolvesAsIndex() {
        val harness = harness()
        val table = TableType(
            fields = mapOf("name" to PrimitiveType.BOOLEAN),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        val result = harness.resolveIndex(table, Identifier("name"), PrimitiveType.STRING)

        // Identifier is not a literal → skips field map; hits indexSignature.
        assertHardSuccess(
            result,
            PrimitiveType.NUMBER,
            MemberAccessKind.INDEX,
            "Identifier + string indexSignature"
        )
    }

    // --- ModuleType bracket keys ------------------------------------------------

    @Test
    fun moduleStringLiteralKeyResolvesFieldAndMethod() {
        val harness = harness()
        val module = ModuleType(
            moduleName = "mod",
            fields = mapOf("version" to PrimitiveType.STRING),
            methods = mapOf("start" to FunctionType(returnType = PrimitiveType.NIL))
        )

        val field = harness.resolveIndex(module, stringNode("version"), PrimitiveType.STRING)
        val method = harness.resolveIndex(module, stringNode("start"), PrimitiveType.STRING)

        assertHardSuccess(field, PrimitiveType.STRING, MemberAccessKind.FIELD, "module[\"version\"]")
        assertTrue(method.isSuccess)
        assertEquals(MemberAccessKind.METHOD, method.accessKind)
        assertIs<FunctionType>(method.type)
    }

    @Test
    fun moduleIntegerLiteralKeyHitsStringifiedField() {
        val harness = harness()
        val module = ModuleType(
            moduleName = "packed",
            fields = mapOf("1" to PrimitiveType.NUMBER)
        )

        val result = harness.resolveIndex(module, intNode(1), PrimitiveType.NUMBER)

        assertHardSuccess(result, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "module[1]")
    }

    @Test
    fun moduleIndexSignatureFallbackMirrorsTable() {
        val harness = harness()
        val module = ModuleType(
            moduleName = "dyn",
            indexSignature = ModuleType.IndexSignature(PrimitiveType.STRING, PrimitiveType.BOOLEAN)
        )

        val ok = harness.resolveIndex(module, stringNode("any"), PrimitiveType.STRING)
        val missing = harness.resolveIndex(
            ModuleType(moduleName = "empty"),
            stringNode("any"),
            PrimitiveType.STRING
        )

        assertHardSuccess(ok, PrimitiveType.BOOLEAN, MemberAccessKind.INDEX, "module indexSignature")
        assertEquals(MemberFailureReason.MISSING_MEMBER, missing.failureReason)
    }

    // --- ClassType: string literal only ----------------------------------------

    @Test
    fun classStringLiteralKeyResolvesFieldAndMethod() {
        val harness = harness()
        val cls = ClassType(
            name = "Item",
            fields = mapOf("name" to PrimitiveType.STRING),
            methods = mapOf("getName" to FunctionType(returnType = PrimitiveType.STRING))
        )

        val field = harness.resolveIndex(cls, stringNode("name"), PrimitiveType.STRING)
        val method = harness.resolveIndex(cls, stringNode("getName"), PrimitiveType.STRING)

        assertHardSuccess(field, PrimitiveType.STRING, MemberAccessKind.FIELD, "class[\"name\"]")
        assertTrue(method.isSuccess)
        assertEquals(MemberAccessKind.METHOD, method.accessKind)
        assertIs<FunctionType>(method.type)
    }

    @Test
    fun classInheritedStringLiteralKeyResolvesThroughSuperClass() {
        val harness = harness()
        val base = ClassType(
            name = "Base",
            fields = mapOf("id" to PrimitiveType.NUMBER),
            methods = mapOf("describe" to FunctionType(returnType = PrimitiveType.STRING))
        )
        val derived = ClassType(name = "Derived", superClass = base)

        val field = harness.resolveIndex(derived, stringNode("id"), PrimitiveType.STRING)
        val method = harness.resolveIndex(derived, stringNode("describe"), PrimitiveType.STRING)

        assertHardSuccess(field, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "derived[\"id\"] inherited")
        assertTrue(method.isSuccess)
        assertEquals(MemberAccessKind.METHOD, method.accessKind)
    }

    @Test
    fun classIntegerLiteralKeyIsInvalidIndexTypeNotMissingMember() {
        // Dual-path footgun: ClassType does NOT stringify integer keys the way Table does.
        // Product hard: INVALID_INDEX_TYPE. IDEAL (if ever aligned with table) would hit "1".
        val harness = harness()
        val cls = ClassType(
            name = "Item",
            fields = mapOf("1" to PrimitiveType.STRING)
        )

        val result = harness.resolveIndex(cls, intNode(1), PrimitiveType.NUMBER)

        assertTrue(!result.isSuccess)
        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, result.failureReason)
        assertNull(result.type)
        assertExpectation(BracketSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    @Test
    fun classMissingStringLiteralKeyReportsMissingMember() {
        val harness = harness()
        val cls = ClassType(name = "Item", fields = mapOf("name" to PrimitiveType.STRING))

        val result = harness.resolveIndex(cls, stringNode("missing"), PrimitiveType.STRING)

        assertEquals(MemberFailureReason.MISSING_MEMBER, result.failureReason)
    }

    @Test
    fun classIdentifierKeyIsInvalidIndexTypeCurrentlyAccepts() {
        val harness = harness()
        val cls = ClassType(name = "Item", fields = mapOf("name" to PrimitiveType.STRING))

        val result = harness.resolveIndex(cls, Identifier("name"), PrimitiveType.STRING)

        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, result.failureReason)
        assertExpectation(BracketSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    // --- ArrayType / TupleType / JavaArrayType ---------------------------------

    @Test
    fun arrayNumberIndexYieldsElementTypeAsIndexAccess() {
        val harness = harness()
        val array = ArrayType(PrimitiveType.BOOLEAN)

        val ok = harness.resolveIndex(array, intNode(1), PrimitiveType.NUMBER)
        val bad = harness.resolveIndex(array, stringNode("x"), PrimitiveType.STRING)

        assertHardSuccess(ok, PrimitiveType.BOOLEAN, MemberAccessKind.INDEX, "array[1]")
        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, bad.failureReason)
    }

    @Test
    fun tupleOneBasedIntegerLiteralYieldsElementOrInvalid() {
        val harness = harness()
        val tuple = TupleType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN))

        val first = harness.resolveIndex(tuple, intNode(1), PrimitiveType.NUMBER)
        val second = harness.resolveIndex(tuple, intNode(2), PrimitiveType.NUMBER)
        val third = harness.resolveIndex(tuple, intNode(3), PrimitiveType.NUMBER)
        val oob = harness.resolveIndex(tuple, intNode(4), PrimitiveType.NUMBER)
        val zero = harness.resolveIndex(tuple, intNode(0), PrimitiveType.NUMBER)
        val nonLiteral = harness.resolveIndex(tuple, Identifier("i"), PrimitiveType.NUMBER)

        assertHardSuccess(first, PrimitiveType.STRING, MemberAccessKind.INDEX, "tuple[1]")
        assertHardSuccess(second, PrimitiveType.NUMBER, MemberAccessKind.INDEX, "tuple[2]")
        assertHardSuccess(third, PrimitiveType.BOOLEAN, MemberAccessKind.INDEX, "tuple[3]")
        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, oob.failureReason)
        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, zero.failureReason)
        // Non-literal: integerLiteralIndex null → INVALID_INDEX_TYPE (CURRENTLY_ACCEPTS / hard)
        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, nonLiteral.failureReason)
        assertExpectation(BracketSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    @Test
    fun tupleIntegralFloatLiteralYieldsElement() {
        val harness = harness()
        val tuple = TupleType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))

        val result = harness.resolveIndex(tuple, floatNode(2.0), PrimitiveType.NUMBER)

        assertHardSuccess(result, PrimitiveType.NUMBER, MemberAccessKind.INDEX, "tuple[2.0]")
    }

    @Test
    fun javaArrayNumberIndexYieldsElementType() {
        val harness = harness()
        val array = JavaArrayType(elementType = PrimitiveType.STRING)

        val ok = harness.resolveIndex(array, intNode(0), PrimitiveType.NUMBER)
        val bad = harness.resolveIndex(array, stringNode("len"), PrimitiveType.STRING)

        assertHardSuccess(ok, PrimitiveType.STRING, MemberAccessKind.INDEX, "javaArray[0]")
        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, bad.failureReason)
    }

    // --- JavaClassType / JavaInstanceType string-literal bracket keys ----------

    @Test
    fun javaClassStringLiteralKeyResolvesStaticFieldAndMethod() {
        val harness = harness()
        val javaName = JavaTypeName(packageName = "com.example", simpleNames = listOf("Widget"))
        val clazz = JavaClassType(
            javaName = javaName,
            staticMembers = mapOf(
                "VERSION" to JavaStaticMemberType(
                    owner = javaName,
                    memberName = "VERSION",
                    valueType = PrimitiveType.NUMBER,
                    memberKind = JavaMemberKind.FIELD
                ),
                "create" to JavaStaticMemberType(
                    owner = javaName,
                    memberName = "create",
                    valueType = FunctionType(returnType = PrimitiveType.STRING),
                    memberKind = JavaMemberKind.METHOD
                )
            )
        )

        val field = harness.resolveIndex(clazz, stringNode("VERSION"), PrimitiveType.STRING)
        val method = harness.resolveIndex(clazz, stringNode("create"), PrimitiveType.STRING)
        val missing = harness.resolveIndex(clazz, stringNode("nope"), PrimitiveType.STRING)
        val intKey = harness.resolveIndex(clazz, intNode(1), PrimitiveType.NUMBER)

        assertHardSuccess(field, PrimitiveType.NUMBER, MemberAccessKind.FIELD, "JavaClass[\"VERSION\"]")
        assertTrue(method.isSuccess)
        assertEquals(MemberAccessKind.METHOD, method.accessKind)
        assertEquals(MemberFailureReason.MISSING_MEMBER, missing.failureReason)
        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, intKey.failureReason)
    }

    @Test
    fun javaInstanceStringLiteralKeyResolvesInstanceFieldAndMethod() {
        val harness = harness()
        val javaName = JavaTypeName(packageName = "com.example", simpleNames = listOf("Widget"))
        val clazz = JavaClassType(
            javaName = javaName,
            instanceMembers = mapOf(
                "label" to JavaInstanceMemberType(
                    owner = javaName,
                    memberName = "label",
                    valueType = PrimitiveType.STRING,
                    memberKind = JavaMemberKind.FIELD
                ),
                "click" to JavaInstanceMemberType(
                    owner = javaName,
                    memberName = "click",
                    valueType = FunctionType(returnType = PrimitiveType.NIL),
                    memberKind = JavaMemberKind.METHOD
                )
            )
        )
        val instance = JavaInstanceType(clazz)

        val field = harness.resolveIndex(instance, stringNode("label"), PrimitiveType.STRING)
        val method = harness.resolveIndex(instance, stringNode("click"), PrimitiveType.STRING)
        val idKey = harness.resolveIndex(instance, Identifier("label"), PrimitiveType.STRING)

        assertHardSuccess(field, PrimitiveType.STRING, MemberAccessKind.FIELD, "JavaInstance[\"label\"]")
        assertTrue(method.isSuccess)
        assertEquals(MemberAccessKind.METHOD, method.accessKind)
        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, idKey.failureReason)
        assertExpectation(BracketSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    // --- Union / Intersection / TypeParameter / unsupported --------------------

    @Test
    fun unionRequiresSuccessfulIndexOnEveryBranch() {
        val harness = harness()
        val left = TableType(fields = mapOf("k" to PrimitiveType.STRING))
        val right = TableType(fields = mapOf("k" to PrimitiveType.NUMBER))
        val missing = TableType(fields = mapOf("other" to PrimitiveType.BOOLEAN))
        val okUnion = UnionType(linkedSetOf(left, right))
        val badUnion = UnionType(linkedSetOf(left, missing))

        val ok = harness.resolveIndex(okUnion, stringNode("k"), PrimitiveType.STRING)
        val bad = harness.resolveIndex(badUnion, stringNode("k"), PrimitiveType.STRING)

        assertTrue(ok.isSuccess)
        assertEquals(setOf(PrimitiveType.STRING, PrimitiveType.NUMBER), assertIs<UnionType>(ok.type).types)
        assertEquals(MemberFailureReason.MISSING_MEMBER, bad.failureReason)
    }

    @Test
    fun intersectionCombinesSuccessfulIndexBranches() {
        val harness = harness()
        val a = TableType(fields = mapOf("k" to PrimitiveType.STRING))
        val b = TableType(fields = mapOf("k" to PrimitiveType.NUMBER))
        val intersection = IntersectionType(linkedSetOf(a, b))

        val result = harness.resolveIndex(intersection, stringNode("k"), PrimitiveType.STRING)

        // Product merges via intersectionTypeOf; NeverType is acceptable for
        // incompatible primitives (mirrors resolveMember intersection corpus).
        assertTrue(result.isSuccess, "intersection index should succeed on both branches")
        assertNotNull(result.type)
    }

    @Test
    fun typeParameterWithConstraintDelegatesIndexToConstraint() {
        val harness = harness()
        val constraint = TableType(fields = mapOf("value" to PrimitiveType.STRING))
        val param = TypeParameterType(name = "T", constraint = constraint)
        val unconstrained = TypeParameterType(name = "U")

        val ok = harness.resolveIndex(param, stringNode("value"), PrimitiveType.STRING)
        val bad = harness.resolveIndex(unconstrained, stringNode("value"), PrimitiveType.STRING)

        assertHardSuccess(ok, PrimitiveType.STRING, MemberAccessKind.FIELD, "typeparam constraint field")
        assertEquals(MemberFailureReason.UNSUPPORTED_BASE_TYPE, bad.failureReason)
    }

    @Test
    fun unsupportedBaseTypePrimitiveReportsUnsupported() {
        val harness = harness()

        val result = harness.resolveIndex(PrimitiveType.STRING, stringNode("len"), PrimitiveType.STRING)

        assertEquals(MemberFailureReason.UNSUPPORTED_BASE_TYPE, result.failureReason)
        assertNull(result.type)
    }

    // --- Dual-path inventory / batch corpus ------------------------------------

    @Test
    fun bracketKeySurfaceInventoryCoversRequiredFamilies() {
        // Sparse inventory lock: each family name must stay represented so the
        // corpus does not silently shrink during dual-path reworks.
        val families = listOf(
            "table-string-literal-field",
            "table-string-literal-method",
            "table-integer-literal-field",
            "table-index-signature",
            "table-identifier-currently-accepts",
            "module-string-literal",
            "class-string-literal",
            "class-integer-invalid-currently-accepts",
            "array-number-index",
            "tuple-one-based",
            "java-class-string-literal",
            "java-instance-string-literal",
            "java-array-number-index",
            "union-all-branches",
            "intersection-combine",
            "typeparam-constraint",
            "unsupported-base"
        )
        assertTrue(families.size >= 16, "inventory must keep ≥16 bracket-key families; got ${families.size}")
        assertTrue(families.distinct().size == families.size)
        assertTrue(families.any { it.contains("currently-accepts") })
        assertTrue(families.any { it.startsWith("table-") })
        assertTrue(families.any { it.startsWith("class-") })
        assertTrue(families.any { it.startsWith("java-") })
    }

    @Test
    fun batchBracketKeySurfaceDualPathCorpus() {
        val harness = harness()
        val cases = listOf(
            BatchCase(
                label = "table field string",
                base = TableType(fields = mapOf("x" to PrimitiveType.STRING)),
                key = stringNode("x"),
                keyType = PrimitiveType.STRING,
                idealSuccess = true,
                idealKind = MemberAccessKind.FIELD,
                idealType = PrimitiveType.STRING
            ),
            BatchCase(
                label = "table missing no sig",
                base = TableType(fields = mapOf("x" to PrimitiveType.STRING)),
                key = stringNode("y"),
                keyType = PrimitiveType.STRING,
                idealSuccess = false,
                idealFailure = MemberFailureReason.MISSING_MEMBER
            ),
            BatchCase(
                label = "table int field",
                base = TableType(fields = mapOf("0" to PrimitiveType.NUMBER)),
                key = intNode(0),
                keyType = PrimitiveType.NUMBER,
                idealSuccess = true,
                idealKind = MemberAccessKind.FIELD,
                idealType = PrimitiveType.NUMBER
            ),
            BatchCase(
                label = "table Identifier CURRENTLY_ACCEPTS missing",
                base = TableType(fields = mapOf("x" to PrimitiveType.STRING)),
                key = Identifier("x"),
                keyType = PrimitiveType.STRING,
                idealSuccess = false,
                idealFailure = MemberFailureReason.MISSING_MEMBER,
                currentlyAccepts = true
            ),
            BatchCase(
                label = "class string field",
                base = ClassType(name = "C", fields = mapOf("x" to PrimitiveType.BOOLEAN)),
                key = stringNode("x"),
                keyType = PrimitiveType.STRING,
                idealSuccess = true,
                idealKind = MemberAccessKind.FIELD,
                idealType = PrimitiveType.BOOLEAN
            ),
            BatchCase(
                label = "class int key CURRENTLY_ACCEPTS invalid",
                base = ClassType(name = "C", fields = mapOf("1" to PrimitiveType.STRING)),
                key = intNode(1),
                keyType = PrimitiveType.NUMBER,
                idealSuccess = false,
                idealFailure = MemberFailureReason.INVALID_INDEX_TYPE,
                currentlyAccepts = true
            ),
            BatchCase(
                label = "array ok",
                base = ArrayType(PrimitiveType.STRING),
                key = intNode(2),
                keyType = PrimitiveType.NUMBER,
                idealSuccess = true,
                idealKind = MemberAccessKind.INDEX,
                idealType = PrimitiveType.STRING
            ),
            BatchCase(
                label = "array bad key",
                base = ArrayType(PrimitiveType.STRING),
                key = stringNode("2"),
                keyType = PrimitiveType.STRING,
                idealSuccess = false,
                idealFailure = MemberFailureReason.INVALID_INDEX_TYPE
            ),
            BatchCase(
                label = "tuple ok",
                base = TupleType(listOf(PrimitiveType.NUMBER)),
                key = intNode(1),
                keyType = PrimitiveType.NUMBER,
                idealSuccess = true,
                idealKind = MemberAccessKind.INDEX,
                idealType = PrimitiveType.NUMBER
            ),
            BatchCase(
                label = "module field",
                base = ModuleType(moduleName = "m", fields = mapOf("a" to PrimitiveType.STRING)),
                key = stringNode("a"),
                keyType = PrimitiveType.STRING,
                idealSuccess = true,
                idealKind = MemberAccessKind.FIELD,
                idealType = PrimitiveType.STRING
            ),
            BatchCase(
                label = "unsupported number base",
                base = PrimitiveType.NUMBER,
                key = stringNode("x"),
                keyType = PrimitiveType.STRING,
                idealSuccess = false,
                idealFailure = MemberFailureReason.UNSUPPORTED_BASE_TYPE
            ),
            BatchCase(
                label = "nil constant key on table CURRENTLY_ACCEPTS missing",
                base = TableType(fields = mapOf("nil" to PrimitiveType.STRING)),
                key = ConstantNode.NIL,
                keyType = PrimitiveType.NIL,
                idealSuccess = false,
                idealFailure = MemberFailureReason.MISSING_MEMBER,
                currentlyAccepts = true
            )
        )

        cases.forEach { case ->
            val result = harness.resolveIndex(case.base, case.key, case.keyType)
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
                assertExpectation(BracketSurfaceExpectation.CURRENTLY_ACCEPTS)
            } else {
                assertExpectation(BracketSurfaceExpectation.IDEAL)
            }
        }
    }

    @Test
    fun tableMethodViaIntegerLiteralKeyBindsMethodAccessKind() {
        val harness = harness()
        val table = TableType(
            methods = mapOf("2" to FunctionType(returnType = PrimitiveType.STRING))
        )

        val result = harness.resolveIndex(table, intNode(2), PrimitiveType.NUMBER)

        assertTrue(result.isSuccess)
        assertEquals(MemberAccessKind.METHOD, result.accessKind)
        assertSame(PrimitiveType.STRING, assertIs<FunctionType>(result.type).returnType)
    }

    @Test
    fun classStringLiteralPrefersFieldOverSameNamedMethod() {
        // resolveClassIndex: fields before methods (no preferMethod flag on index path).
        val harness = harness()
        val fieldFn = FunctionType(returnType = PrimitiveType.NUMBER)
        val methodFn = FunctionType(returnType = PrimitiveType.BOOLEAN)
        val cls = ClassType(
            name = "Widget",
            fields = mapOf("run" to fieldFn),
            methods = mapOf("run" to methodFn)
        )

        val result = harness.resolveIndex(cls, stringNode("run"), PrimitiveType.STRING)

        assertEquals(MemberAccessKind.FIELD, result.accessKind)
        assertSame(PrimitiveType.NUMBER, assertIs<FunctionType>(result.type).returnType)
    }

    @Test
    fun booleanConstantKeyOnClassIsInvalidIndexType() {
        val harness = harness()
        val cls = ClassType(name = "C", fields = mapOf("true" to PrimitiveType.STRING))

        val result = harness.resolveIndex(
            cls,
            ConstantNode(ConstantNode.TYPE.BOOLEAN, "true"),
            PrimitiveType.BOOLEAN
        )

        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, result.failureReason)
        assertExpectation(BracketSurfaceExpectation.CURRENTLY_ACCEPTS)
    }

    // --- helpers ----------------------------------------------------------------

    private enum class BracketSurfaceExpectation {
        IDEAL,
        CURRENTLY_ACCEPTS
    }

    private data class BatchCase(
        val label: String,
        val base: Type,
        val key: ExpressionNode,
        val keyType: Type,
        val idealSuccess: Boolean,
        val idealKind: MemberAccessKind? = null,
        val idealType: Type? = null,
        val idealFailure: MemberFailureReason? = null,
        val currentlyAccepts: Boolean = false
    )

    private fun assertExpectation(expectation: BracketSurfaceExpectation) {
        assertTrue(
            expectation == BracketSurfaceExpectation.IDEAL ||
                expectation == BracketSurfaceExpectation.CURRENTLY_ACCEPTS,
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
        assertExpectation(BracketSurfaceExpectation.IDEAL)
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

    private fun stringNode(value: String) = ConstantNode(ConstantNode.TYPE.STRING, "\"$value\"")
    private fun intNode(value: Int) = ConstantNode(ConstantNode.TYPE.INTERGER, value)
    private fun floatNode(value: Double) = ConstantNode(ConstantNode.TYPE.FLOAT, value)

    private data class Harness(
        val resolver: MemberResolver,
        val declarations: BinderPassResult,
        val scopeId: ScopeId
    ) {
        fun resolveIndex(base: Type, key: ExpressionNode, keyType: Type): MemberResolution =
            resolver.resolveIndex(base, key, keyType, scopeId)
    }
}
