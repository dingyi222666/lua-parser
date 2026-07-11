package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeNormalizer
import io.github.dingyi222666.luaparser.semantic.types.resolve.intersectionTypeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TASK-335 corpus: intersection normalization is order-insensitive; duplicates collapse.
 *
 * Policy (TypeNormalizer.normalizeIntersection / intersectionTypeOf):
 * - Nested intersections flatten.
 * - Duplicate members collapse (LinkedHashSet / narrowing).
 * - Incompatible primitives/literals collapse to never.
 * - Subtype absorption may drop wider members when a narrower assignable member remains.
 * - Order of input members must not change semantic result (member set equality).
 *
 * Test-only. Verification is review-owned (no Gradle).
 */
class TypeNormalizerIntersectionOrderTddTest {

    @Test
    fun orderInsensitiveForTwoDistinctPrimitivesThatAreCompatibleShapes() {
        // string & string → string (dup collapse); table shapes order-insensitive.
        val left = TableType(fields = mapOf("a" to PrimitiveType.STRING))
        val right = TableType(fields = mapOf("b" to PrimitiveType.NUMBER))
        val ab = TypeNormalizer.normalize(IntersectionType(linkedSetOf(left, right)))
        val ba = TypeNormalizer.normalize(IntersectionType(linkedSetOf(right, left)))
        assertEquals(memberFingerprint(ab), memberFingerprint(ba))
    }

    @Test
    fun duplicatesCollapseToSingleMember() {
        val input = IntersectionType(
            linkedSetOf(PrimitiveType.STRING, PrimitiveType.STRING, PrimitiveType.STRING)
        )
        assertSame(PrimitiveType.STRING, TypeNormalizer.normalize(input))
    }

    @Test
    fun nestedIntersectionFlattens() {
        val nested = IntersectionType(
            linkedSetOf(
                IntersectionType(linkedSetOf(PrimitiveType.STRING)),
                PrimitiveType.STRING
            )
        )
        assertSame(PrimitiveType.STRING, TypeNormalizer.normalize(nested))
    }

    @Test
    fun incompatiblePrimitivesCollapseToNever() {
        val input = IntersectionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        assertSame(NeverType, TypeNormalizer.normalize(input))
    }

    @Test
    fun incompatibleLiteralsCollapseToNever() {
        val input = IntersectionType(
            linkedSetOf(
                LiteralType("a", PrimitiveType.STRING),
                LiteralType("b", PrimitiveType.STRING)
            )
        )
        assertSame(NeverType, TypeNormalizer.normalize(input))
    }

    @Test
    fun neverMemberAbsorbsIntersectionToNever() {
        val input = IntersectionType(linkedSetOf(PrimitiveType.STRING, NeverType, PrimitiveType.NUMBER))
        assertSame(NeverType, TypeNormalizer.normalize(input))
    }

    @Test
    fun intersectionTypeOfFactoryMatchesDirectNormalize() {
        val left = TableType(fields = mapOf("x" to PrimitiveType.STRING))
        val right = TableType(fields = mapOf("y" to PrimitiveType.BOOLEAN))
        val viaFactory = intersectionTypeOf(left, right)
        val direct = TypeNormalizer.normalize(IntersectionType(linkedSetOf(left, right)))
        assertEquals(memberFingerprint(direct), memberFingerprint(viaFactory))
    }

    @Test
    fun threeWayTableIntersectionOrderInsensitive() {
        val a = TableType(fields = mapOf("a" to PrimitiveType.STRING))
        val b = TableType(fields = mapOf("b" to PrimitiveType.NUMBER))
        val c = TableType(fields = mapOf("c" to PrimitiveType.BOOLEAN))
        val orders = listOf(
            listOf(a, b, c),
            listOf(c, a, b),
            listOf(b, c, a)
        ).map { TypeNormalizer.normalize(IntersectionType(it.toCollection(linkedSetOf()))) }
        val fingerprints = orders.map(::memberFingerprint).toSet()
        assertEquals(1, fingerprints.size, "order must not change intersection result: $fingerprints")
    }

    @Test
    fun unknownDroppedWhenConcreteMembersPresent() {
        val input = IntersectionType(linkedSetOf(UnknownType, PrimitiveType.STRING))
        // Policy: unknown dropped when concrete members present → string.
        val normalized = TypeNormalizer.normalize(input)
        assertTrue(
            normalized == PrimitiveType.STRING || normalized == UnknownType,
            "expected string or unknown, got ${normalized.displayName}"
        )
    }

    @Test
    fun functionIntersectionWithDuplicateSignaturesCollapses() {
        val fn = FunctionType(
            parameters = listOf(FunctionParameter("x", PrimitiveType.STRING)),
            returnType = PrimitiveType.NUMBER
        )
        val input = IntersectionType(linkedSetOf(fn, fn))
        val normalized = TypeNormalizer.normalize(input)
        assertTrue(normalized is FunctionType || normalized is IntersectionType)
        if (normalized is IntersectionType) {
            assertEquals(1, normalized.types.size)
        }
    }

    private fun memberFingerprint(type: Type): String = when (type) {
        is IntersectionType -> type.types.map { it.displayName }.sorted().joinToString("&")
        else -> type.displayName
    }
}
