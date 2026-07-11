package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeNormalizer
import io.github.dingyi222666.luaparser.semantic.types.resolve.optionalTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TASK-364 corpus: union normalization member order is deterministic first-seen.
 *
 * Policy (TypeNormalizer.normalizeUnion / unionTypeOf / optionalTypeOf):
 * - Nested unions flatten; insertion order is first-seen after recursive normalize
 *   of members (LinkedHashSet semantics).
 * - Duplicate members collapse; the first occurrence keeps its position.
 * - never is dropped wherever it appears; empty after drop → never.
 * - any / unknown remain contagious regardless of input position.
 * - Literals whose base primitive kind is also present are absorbed (dropped);
 *   the base keeps its first-seen position.
 * - Distinct non-absorbed members keep first-seen order: different input
 *   permutations yield the same member *set* but ordered lists follow that
 *   permutation's first-seen sequence (not sorted by name).
 * - Nilability is preserved: legitimate `T | nil` keeps a single nil member
 *   at its first-seen position.
 * - Test-only; production code is out of scope. Verification is review-owned
 *   (no Gradle from workers).
 */
class TypeNormalizerUnionOrderTddTest {

    // -------------------------------------------------------------------------
    // First-seen order for flat unions
    // -------------------------------------------------------------------------

    @Test
    fun preservesFirstSeenOrderForTwoDistinctPrimitives() {
        val sn = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
            )
        )
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER), sn.types.toList())

        val ns = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.STRING))
            )
        )
        assertEquals(listOf(PrimitiveType.NUMBER, PrimitiveType.STRING), ns.types.toList())
        assertEquals(memberSetFingerprint(sn), memberSetFingerprint(ns))
        // Ordered sequences differ even though the member set is equal.
        assertNotEquals(sn.types.toList(), ns.types.toList())
        assertNotEquals(sn.displayName, ns.displayName)
    }

    @Test
    fun threeWayPermutationKeepsFirstSeenSequenceAndSameMemberSet() {
        val a = PrimitiveType.STRING
        val b = PrimitiveType.NUMBER
        val c = PrimitiveType.BOOLEAN
        val expectedByOrder = mapOf(
            listOf(a, b, c) to listOf(a, b, c),
            listOf(c, a, b) to listOf(c, a, b),
            listOf(b, c, a) to listOf(b, c, a)
        )
        val fingerprints = mutableSetOf<String>()
        expectedByOrder.forEach { (inputOrder, expectedOrder) ->
            val normalized = assertIs<UnionType>(
                TypeNormalizer.normalize(UnionType(inputOrder.toCollection(linkedSetOf())))
            )
            assertEquals(expectedOrder, normalized.types.toList(), "input=$inputOrder")
            fingerprints += memberSetFingerprint(normalized)
        }
        assertEquals(1, fingerprints.size, "member set must be order-insensitive: $fingerprints")
    }

    @Test
    fun duplicatesCollapseKeepingFirstOccurrencePosition() {
        // Build with List → LinkedHashSet so first-seen order is explicit before normalize.
        val input = UnionType(
            listOf(
                PrimitiveType.NUMBER,
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.STRING,
                PrimitiveType.BOOLEAN,
                PrimitiveType.NUMBER
            ).toCollection(linkedSetOf())
        )
        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(PrimitiveType.NUMBER, PrimitiveType.STRING, PrimitiveType.BOOLEAN),
            normalized.types.toList()
        )
        assertEquals(1, normalized.types.count { it == PrimitiveType.NUMBER })
        assertEquals(1, normalized.types.count { it == PrimitiveType.STRING })
    }

    @Test
    fun singletonAfterDedupeIsNotUnionShell() {
        assertSame(
            PrimitiveType.STRING,
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        PrimitiveType.STRING,
                        PrimitiveType.STRING,
                        PrimitiveType("text", PrimitiveType.Kind.STRING)
                    )
                )
            )
        )
    }

    // -------------------------------------------------------------------------
    // Nested flatten order
    // -------------------------------------------------------------------------

    @Test
    fun nestedUnionFlattenFollowsOuterThenInnerFirstSeen() {
        // string | (number | boolean) | string -> string | number | boolean
        val nested = UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN))
        val input = UnionType(linkedSetOf(PrimitiveType.STRING, nested, PrimitiveType.STRING))
        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
            normalized.types.toList()
        )
        assertTrue(normalized.types.none { it is UnionType })
    }

    @Test
    fun nestedUnionWithSwappedOuterOrderChangesTopLevelSequence() {
        // (boolean | string) | number  vs  number | (boolean | string)
        val nested = UnionType(linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.STRING))
        val leftOuter = assertIs<UnionType>(
            TypeNormalizer.normalize(UnionType(linkedSetOf(nested, PrimitiveType.NUMBER)))
        )
        val rightOuter = assertIs<UnionType>(
            TypeNormalizer.normalize(UnionType(linkedSetOf(PrimitiveType.NUMBER, nested)))
        )
        assertEquals(
            listOf(PrimitiveType.BOOLEAN, PrimitiveType.STRING, PrimitiveType.NUMBER),
            leftOuter.types.toList()
        )
        assertEquals(
            listOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN, PrimitiveType.STRING),
            rightOuter.types.toList()
        )
        assertEquals(memberSetFingerprint(leftOuter), memberSetFingerprint(rightOuter))
        assertNotEquals(leftOuter.types.toList(), rightOuter.types.toList())
    }

    @Test
    fun deeplyNestedLeftAndRightChainsAreDeterministic() {
        // ((string | number) | boolean) | table
        val leftInnermost = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        val leftMid = UnionType(linkedSetOf(leftInnermost, PrimitiveType.BOOLEAN))
        val left = assertIs<UnionType>(
            TypeNormalizer.normalize(UnionType(linkedSetOf(leftMid, PrimitiveType.TABLE)))
        )
        assertEquals(
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN,
                PrimitiveType.TABLE
            ),
            left.types.toList()
        )

        // string | (number | (boolean | table))
        val rightDeepest = UnionType(linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.TABLE))
        val rightMid = UnionType(linkedSetOf(PrimitiveType.NUMBER, rightDeepest))
        val right = assertIs<UnionType>(
            TypeNormalizer.normalize(UnionType(linkedSetOf(PrimitiveType.STRING, rightMid)))
        )
        assertEquals(
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN,
                PrimitiveType.TABLE
            ),
            right.types.toList()
        )
        assertEquals(memberSetFingerprint(left), memberSetFingerprint(right))
    }

    // -------------------------------------------------------------------------
    // never / any / unknown position-insensitive absorption
    // -------------------------------------------------------------------------

    @Test
    fun neverDroppedWhereverItAppearsPreservingSiblingOrder() {
        val leading = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(NeverType, PrimitiveType.STRING, PrimitiveType.NUMBER))
            )
        )
        val middle = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, NeverType, PrimitiveType.NUMBER))
            )
        )
        val trailing = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER, NeverType))
            )
        )
        val expected = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        assertEquals(expected, leading.types.toList())
        assertEquals(expected, middle.types.toList())
        assertEquals(expected, trailing.types.toList())
    }

    @Test
    fun neverOnlyUnionCollapsesToNeverRegardlessOfNestingOrder() {
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(NeverType, UnionType(linkedSetOf(NeverType))))
            )
        )
    }

    @Test
    fun anyAbsorbsUnionRegardlessOfPosition() {
        assertSame(
            PrimitiveType.ANY,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.ANY, PrimitiveType.STRING, PrimitiveType.NUMBER))
            )
        )
        assertSame(
            PrimitiveType.ANY,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.ANY, PrimitiveType.NUMBER))
            )
        )
        assertSame(
            PrimitiveType.ANY,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.ANY))
            )
        )
    }

    @Test
    fun unknownAbsorbsUnionRegardlessOfPosition() {
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(UnknownType, PrimitiveType.BOOLEAN, PrimitiveType.NIL))
            )
        )
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.BOOLEAN, UnknownType, PrimitiveType.NIL))
            )
        )
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL, UnknownType))
            )
        )
    }

    // -------------------------------------------------------------------------
    // Literal absorption vs order
    // -------------------------------------------------------------------------

    @Test
    fun literalAbsorbedWhenBasePresentKeepsBaseFirstSeenPosition() {
        // "x" | string | "y" -> string (literals dropped; base at second then sole)
        assertSame(
            PrimitiveType.STRING,
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        LiteralType("x", PrimitiveType.STRING),
                        PrimitiveType.STRING,
                        LiteralType("y", PrimitiveType.STRING)
                    )
                )
            )
        )

        // string | number | "x" -> string | number
        val withTrailingLiteral = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        PrimitiveType.STRING,
                        PrimitiveType.NUMBER,
                        LiteralType("x", PrimitiveType.STRING)
                    )
                )
            )
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            withTrailingLiteral.types.toList()
        )

        // "x" | number | string -> number | string (literal dropped; base after number)
        val literalLeading = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        LiteralType("x", PrimitiveType.STRING),
                        PrimitiveType.NUMBER,
                        PrimitiveType.STRING
                    )
                )
            )
        )
        assertEquals(
            listOf(PrimitiveType.NUMBER, PrimitiveType.STRING),
            literalLeading.types.toList()
        )
    }

    @Test
    fun distinctLiteralsWithoutBaseKeepFirstSeenOrder() {
        val xy = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        LiteralType("x", PrimitiveType.STRING),
                        LiteralType("y", PrimitiveType.STRING)
                    )
                )
            )
        )
        assertEquals(
            listOf(
                LiteralType("x", PrimitiveType.STRING),
                LiteralType("y", PrimitiveType.STRING)
            ),
            xy.types.toList()
        )

        val yx = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        LiteralType("y", PrimitiveType.STRING),
                        LiteralType("x", PrimitiveType.STRING)
                    )
                )
            )
        )
        assertEquals(
            listOf(
                LiteralType("y", PrimitiveType.STRING),
                LiteralType("x", PrimitiveType.STRING)
            ),
            yx.types.toList()
        )
        assertEquals(memberSetFingerprint(xy), memberSetFingerprint(yx))
        assertNotEquals(xy.types.toList(), yx.types.toList())
    }

    // -------------------------------------------------------------------------
    // Nilability + first-seen nil position
    // -------------------------------------------------------------------------

    @Test
    fun optionalNilKeepsFirstSeenPositionAmongDistinctMembers() {
        val nilFirst = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.NIL, PrimitiveType.STRING, PrimitiveType.NUMBER))
            )
        )
        assertEquals(
            listOf(PrimitiveType.NIL, PrimitiveType.STRING, PrimitiveType.NUMBER),
            nilFirst.types.toList()
        )

        val nilMiddle = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER))
            )
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            nilMiddle.types.toList()
        )

        val nilLast = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(optionalTypeOf(PrimitiveType.STRING), PrimitiveType.NUMBER))
            )
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            nilLast.types.toList()
        )
        assertEquals(1, nilFirst.types.count { it == PrimitiveType.NIL })
        assertEquals(1, nilMiddle.types.count { it == PrimitiveType.NIL })
        assertEquals(1, nilLast.types.count { it == PrimitiveType.NIL })
        assertEquals(memberSetFingerprint(nilFirst), memberSetFingerprint(nilMiddle))
        assertEquals(memberSetFingerprint(nilMiddle), memberSetFingerprint(nilLast))
    }

    @Test
    fun redundantNilsCollapseToSingleNilAtFirstSeenSlot() {
        val input = UnionType(
            linkedSetOf(
                PrimitiveType.BOOLEAN,
                PrimitiveType.NIL,
                PrimitiveType.NUMBER,
                PrimitiveType.NIL,
                UnionType(linkedSetOf(PrimitiveType.NIL, PrimitiveType.BOOLEAN))
            )
        )
        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL, PrimitiveType.NUMBER),
            normalized.types.toList()
        )
        assertEquals(1, normalized.types.count { it == PrimitiveType.NIL })
    }

    @Test
    fun optionalTypeOfAppendsNilAfterBasePreservingBaseOrder() {
        // optional(string) -> string | nil; optional(number | boolean) -> number | boolean | nil
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL),
            assertIs<UnionType>(optionalTypeOf(PrimitiveType.STRING)).types.toList()
        )
        val nested = UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN))
        assertEquals(
            listOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN, PrimitiveType.NIL),
            assertIs<UnionType>(optionalTypeOf(nested)).types.toList()
        )
        // optional of already-optional does not reorder or duplicate nil
        val once = optionalTypeOf(PrimitiveType.TABLE)
        assertEquals(
            listOf(PrimitiveType.TABLE, PrimitiveType.NIL),
            assertIs<UnionType>(optionalTypeOf(once)).types.toList()
        )
    }

    // -------------------------------------------------------------------------
    // Factories, aliases, containers, stability
    // -------------------------------------------------------------------------

    @Test
    fun unionTypeOfFactoryMatchesDirectNormalizeAndFirstSeenOrder() {
        val nested = UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN))
        val members = listOf(PrimitiveType.STRING, nested, PrimitiveType.STRING)
        val direct = TypeNormalizer.normalize(UnionType(members.toCollection(linkedSetOf())))
        val viaFactory = unionTypeOf(members)
        assertEquals(direct, viaFactory)
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
            assertIs<UnionType>(viaFactory).types.toList()
        )
    }

    @Test
    fun aliasUnwrapDoesNotDisturbFirstSeenOrderOfUnderlyingMembers() {
        val aliasString = AliasType("S", PrimitiveType.STRING)
        val aliasNumber = AliasType("N", AliasType("InnerN", PrimitiveType.NUMBER))
        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(aliasNumber, aliasString, PrimitiveType.BOOLEAN))
            )
        )
        assertEquals(
            listOf(PrimitiveType.NUMBER, PrimitiveType.STRING, PrimitiveType.BOOLEAN),
            normalized.types.toList()
        )
        assertTrue(normalized.types.none { it is AliasType })
    }

    @Test
    fun elementWiseUnionOrderInsideFunctionParamAndTableField() {
        val paramUnion = UnionType(
            linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        val fieldUnion = UnionType(
            linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL, PrimitiveType.BOOLEAN)
        )
        val fn = FunctionType(
            parameters = listOf(FunctionParameter("v", paramUnion)),
            returnType = fieldUnion
        )
        val table = TableType(fields = mapOf("f" to fieldUnion))

        val normalizedFn = assertIs<FunctionType>(TypeNormalizer.normalize(fn))
        assertEquals(
            listOf(PrimitiveType.NUMBER, PrimitiveType.STRING),
            assertIs<UnionType>(normalizedFn.parameters.single().type).types.toList()
        )
        assertEquals(
            listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL),
            assertIs<UnionType>(normalizedFn.returnType).types.toList()
        )

        val normalizedTable = assertIs<TableType>(TypeNormalizer.normalize(table))
        assertEquals(
            listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL),
            assertIs<UnionType>(normalizedTable.fields.getValue("f")).types.toList()
        )

        val array = assertIs<ArrayType>(
            TypeNormalizer.normalize(
                ArrayType(
                    UnionType(linkedSetOf(PrimitiveType.TABLE, PrimitiveType.STRING, PrimitiveType.TABLE))
                )
            )
        )
        assertEquals(
            listOf(PrimitiveType.TABLE, PrimitiveType.STRING),
            assertIs<UnionType>(array.elementType).types.toList()
        )
    }

    @Test
    fun elementWiseUnionOrderInsideTupleMultiReturnAndVararg() {
        val nested = UnionType(
            linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.STRING)
        )
        val swapped = UnionType(
            linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL)
        )

        val tuple = assertIs<TupleType>(
            TypeNormalizer.normalize(TupleType(listOf(nested, swapped)))
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            assertIs<UnionType>(tuple.elementTypes[0]).types.toList()
        )
        assertEquals(
            listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL),
            assertIs<UnionType>(tuple.elementTypes[1]).types.toList()
        )

        val multi = assertIs<MultiReturnType>(
            TypeNormalizer.normalize(MultiReturnType(listOf(swapped, nested)))
        )
        assertEquals(
            listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL),
            assertIs<UnionType>(multi.types[0]).types.toList()
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            assertIs<UnionType>(multi.types[1]).types.toList()
        )

        val vararg = assertIs<VarargType>(TypeNormalizer.normalize(VarargType(nested)))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            assertIs<UnionType>(vararg.elementType).types.toList()
        )
    }

    @Test
    fun displayNameFollowsFirstSeenMemberOrder() {
        val sn = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
            )
        )
        val ns = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.STRING))
            )
        )
        assertEquals("string | number", sn.displayName)
        assertEquals("number | string", ns.displayName)
    }

    @Test
    fun repeatedNormalizeIsIdempotentForOrderedUnion() {
        val input = UnionType(
            linkedSetOf(
                UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.STRING)),
                NeverType,
                LiteralType("x", PrimitiveType.STRING),
                PrimitiveType.STRING,
                PrimitiveType.NIL
            )
        )
        val once = TypeNormalizer.normalize(input)
        val twice = TypeNormalizer.normalize(once)
        assertEquals(once, twice)
        assertEquals(
            listOf(PrimitiveType.NUMBER, PrimitiveType.STRING, PrimitiveType.NIL),
            assertIs<UnionType>(once).types.toList()
        )
    }

    @Test
    fun corpusMemberSetEqualityAcrossSixPermutationsOfThreePrimitives() {
        val members = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        val perms = listOf(
            listOf(0, 1, 2),
            listOf(0, 2, 1),
            listOf(1, 0, 2),
            listOf(1, 2, 0),
            listOf(2, 0, 1),
            listOf(2, 1, 0)
        )
        val setFingerprints = mutableSetOf<String>()
        perms.forEach { idx ->
            val order = idx.map { members[it] }
            val normalized = assertIs<UnionType>(
                TypeNormalizer.normalize(UnionType(order.toCollection(linkedSetOf())))
            )
            assertEquals(order, normalized.types.toList(), "perm=$order")
            setFingerprints += memberSetFingerprint(normalized)
        }
        assertEquals(1, setFingerprints.size)
    }

    /** Sorted display-name join — order-insensitive member-set fingerprint. */
    private fun memberSetFingerprint(type: Type): String = when (type) {
        is UnionType -> type.types.map { it.displayName }.sorted().joinToString("|")
        else -> type.displayName
    }
}
