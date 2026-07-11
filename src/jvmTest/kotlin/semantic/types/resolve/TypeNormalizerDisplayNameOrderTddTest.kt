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
 * TASK-393 corpus: normalizeUnion first-seen order is reflected in displayName;
 * set equality still holds across permutations.
 *
 * Policy (TypeNormalizer.normalizeUnion / UnionType.displayName):
 * - UnionType.displayName (via name) joins members with " | " in LinkedHashSet
 *   first-seen order after normalize — not sorted alphabetically.
 * - Different input permutations of the same member set yield equal set
 *   fingerprints but distinct ordered displayName strings.
 * - Nested flatten, duplicate collapse, never drop, literal absorption, and
 *   nil first-seen position all surface through displayName in the same order
 *   as the normalized member list.
 * - Contagious any/unknown collapse to a singleton displayName regardless of
 *   input position.
 * - Element-wise normalize inside containers preserves nested union displayName
 *   order for those nested unions.
 * - Test-only sibling to TASK-364 (union order). Production is out of scope.
 *   Verification is review-owned (no Gradle from workers).
 */
class TypeNormalizerDisplayNameOrderTddTest {

    // -------------------------------------------------------------------------
    // Core AC: first-seen order in displayName + set equality
    // -------------------------------------------------------------------------

    @Test
    fun displayNameReflectsFirstSeenOrderForTwoPrimitivesWhileSetEqualityHolds() {
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
        assertNotEquals(sn.displayName, ns.displayName)

        // Ordered lists differ; order-insensitive set fingerprint is identical.
        assertNotEquals(sn.types.toList(), ns.types.toList())
        assertEquals(memberSetFingerprint(sn), memberSetFingerprint(ns))
        assertEquals(displayNameSetFingerprint(sn), displayNameSetFingerprint(ns))
    }

    @Test
    fun threeWayPermutationDisplayNamesFollowInputOrderWithOneMemberSet() {
        val a = PrimitiveType.STRING
        val b = PrimitiveType.NUMBER
        val c = PrimitiveType.BOOLEAN
        val cases = listOf(
            listOf(a, b, c) to "string | number | boolean",
            listOf(c, a, b) to "boolean | string | number",
            listOf(b, c, a) to "number | boolean | string"
        )
        val setFingerprints = mutableSetOf<String>()
        cases.forEach { (order, expectedDisplay) ->
            val normalized = assertIs<UnionType>(
                TypeNormalizer.normalize(UnionType(order.toCollection(linkedSetOf())))
            )
            assertEquals(expectedDisplay, normalized.displayName, "input=$order")
            assertEquals(order.map { it.displayName }.joinToString(" | "), normalized.displayName)
            setFingerprints += memberSetFingerprint(normalized)
        }
        assertEquals(1, setFingerprints.size, "member set must be order-insensitive: $setFingerprints")
    }

    @Test
    fun sixPermutationCorpusLocksDisplayNameStabilityAndSetEquality() {
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
        val displayNames = mutableSetOf<String>()
        perms.forEach { idx ->
            val order = idx.map { members[it] }
            val normalized = assertIs<UnionType>(
                TypeNormalizer.normalize(UnionType(order.toCollection(linkedSetOf())))
            )
            val expectedDisplay = order.joinToString(" | ") { it.displayName }
            assertEquals(expectedDisplay, normalized.displayName, "perm=$order")
            // displayName is exactly the ordered join of member displayNames.
            assertEquals(
                normalized.types.joinToString(" | ") { it.displayName },
                normalized.displayName
            )
            setFingerprints += memberSetFingerprint(normalized)
            displayNames += normalized.displayName
        }
        assertEquals(1, setFingerprints.size)
        // All six ordered display names are distinct (no accidental sort).
        assertEquals(6, displayNames.size, "displayNames=$displayNames")
    }

    // -------------------------------------------------------------------------
    // Nested flatten / duplicates / never / absorption surface via displayName
    // -------------------------------------------------------------------------

    @Test
    fun nestedFlattenDisplayNameUsesOuterThenInnerFirstSeen() {
        // string | (number | boolean) | string -> string | number | boolean
        val nested = UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN))
        val input = UnionType(linkedSetOf(PrimitiveType.STRING, nested, PrimitiveType.STRING))
        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals("string | number | boolean", normalized.displayName)
        assertTrue(normalized.types.none { it is UnionType })
    }

    @Test
    fun nestedSwappedOuterOrderChangesDisplayNameButNotMemberSet() {
        val nested = UnionType(linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.STRING))
        val leftOuter = assertIs<UnionType>(
            TypeNormalizer.normalize(UnionType(linkedSetOf(nested, PrimitiveType.NUMBER)))
        )
        val rightOuter = assertIs<UnionType>(
            TypeNormalizer.normalize(UnionType(linkedSetOf(PrimitiveType.NUMBER, nested)))
        )
        assertEquals("boolean | string | number", leftOuter.displayName)
        assertEquals("number | boolean | string", rightOuter.displayName)
        assertNotEquals(leftOuter.displayName, rightOuter.displayName)
        assertEquals(memberSetFingerprint(leftOuter), memberSetFingerprint(rightOuter))
    }

    @Test
    fun duplicateCollapseKeepsFirstSeenSlotInDisplayName() {
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
        assertEquals("number | string | boolean", normalized.displayName)
    }

    @Test
    fun neverDroppedDoesNotAppearInDisplayNameSiblingOrderPreserved() {
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
        assertEquals("string | number", leading.displayName)
        assertEquals("string | number", middle.displayName)
        assertEquals("string | number", trailing.displayName)
        assertTrue("never" !in leading.displayName)
    }

    @Test
    fun contagiousAnyAndUnknownCollapseDisplayNameRegardlessOfPosition() {
        assertEquals(
            PrimitiveType.ANY.displayName,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.ANY, PrimitiveType.NUMBER))
            ).displayName
        )
        assertEquals(
            PrimitiveType.ANY.displayName,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.ANY, PrimitiveType.STRING, PrimitiveType.NUMBER))
            ).displayName
        )
        assertEquals(
            UnknownType.displayName,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.BOOLEAN, UnknownType, PrimitiveType.NIL))
            ).displayName
        )
        assertEquals(
            UnknownType.displayName,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(UnknownType, PrimitiveType.BOOLEAN, PrimitiveType.NIL))
            ).displayName
        )
    }

    @Test
    fun literalAbsorptionDisplayNameKeepsBaseFirstSeenPosition() {
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
        assertEquals("string | number", withTrailingLiteral.displayName)
        assertTrue("\"x\"" !in withTrailingLiteral.displayName)

        // "x" | number | string -> number | string
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
        assertEquals("number | string", literalLeading.displayName)
        assertEquals(memberSetFingerprint(withTrailingLiteral), memberSetFingerprint(literalLeading))
    }

    @Test
    fun distinctLiteralsWithoutBaseKeepFirstSeenOrderInDisplayName() {
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
        assertEquals("\"x\" | \"y\"", xy.displayName)
        assertEquals("\"y\" | \"x\"", yx.displayName)
        assertNotEquals(xy.displayName, yx.displayName)
        assertEquals(memberSetFingerprint(xy), memberSetFingerprint(yx))
    }

    // -------------------------------------------------------------------------
    // Nilability + factories + containers + stability
    // -------------------------------------------------------------------------

    @Test
    fun nilFirstSeenPositionIsVisibleInDisplayNameAndSetEqualityHolds() {
        val nilFirst = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.NIL, PrimitiveType.STRING, PrimitiveType.NUMBER))
            )
        )
        val nilMiddle = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER))
            )
        )
        val nilLast = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(optionalTypeOf(PrimitiveType.STRING), PrimitiveType.NUMBER))
            )
        )
        assertEquals("nil | string | number", nilFirst.displayName)
        assertEquals("string | nil | number", nilMiddle.displayName)
        assertEquals("string | nil | number", nilLast.displayName)
        assertNotEquals(nilFirst.displayName, nilMiddle.displayName)
        assertEquals(memberSetFingerprint(nilFirst), memberSetFingerprint(nilMiddle))
        assertEquals(memberSetFingerprint(nilMiddle), memberSetFingerprint(nilLast))
    }

    @Test
    fun optionalTypeOfDisplayNameAppendsNilAfterBase() {
        assertEquals(
            "string | nil",
            assertIs<UnionType>(optionalTypeOf(PrimitiveType.STRING)).displayName
        )
        val nested = UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN))
        assertEquals(
            "number | boolean | nil",
            assertIs<UnionType>(optionalTypeOf(nested)).displayName
        )
        val once = optionalTypeOf(PrimitiveType.TABLE)
        assertEquals(
            "table | nil",
            assertIs<UnionType>(optionalTypeOf(once)).displayName
        )
    }

    @Test
    fun unionTypeOfFactoryDisplayNameMatchesDirectNormalize() {
        val nested = UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN))
        val members = listOf(PrimitiveType.STRING, nested, PrimitiveType.STRING)
        val direct = TypeNormalizer.normalize(UnionType(members.toCollection(linkedSetOf())))
        val viaFactory = unionTypeOf(members)
        assertEquals(direct.displayName, viaFactory.displayName)
        assertEquals("string | number | boolean", viaFactory.displayName)
        assertEquals(direct, viaFactory)
    }

    @Test
    fun aliasUnwrapDisplayNameUsesUnderlyingMemberOrder() {
        val aliasString = AliasType("S", PrimitiveType.STRING)
        val aliasNumber = AliasType("N", AliasType("InnerN", PrimitiveType.NUMBER))
        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(aliasNumber, aliasString, PrimitiveType.BOOLEAN))
            )
        )
        assertEquals("number | string | boolean", normalized.displayName)
        assertTrue(normalized.types.none { it is AliasType })
        // Alias names must not leak into normalized displayName.
        assertTrue("S" !in normalized.displayName)
        assertTrue("N" !in normalized.displayName)
        assertTrue("InnerN" !in normalized.displayName)
    }

    @Test
    fun nestedUnionDisplayNameOrderInsideContainers() {
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
        val normalizedFn = assertIs<FunctionType>(TypeNormalizer.normalize(fn))
        assertEquals(
            "number | string",
            assertIs<UnionType>(normalizedFn.parameters.single().type).displayName
        )
        assertEquals(
            "boolean | nil",
            assertIs<UnionType>(normalizedFn.returnType).displayName
        )

        val table = assertIs<TableType>(
            TypeNormalizer.normalize(TableType(fields = mapOf("f" to fieldUnion)))
        )
        assertEquals(
            "boolean | nil",
            assertIs<UnionType>(table.fields.getValue("f")).displayName
        )

        val array = assertIs<ArrayType>(
            TypeNormalizer.normalize(
                ArrayType(
                    UnionType(linkedSetOf(PrimitiveType.TABLE, PrimitiveType.STRING, PrimitiveType.TABLE))
                )
            )
        )
        assertEquals(
            "table | string",
            assertIs<UnionType>(array.elementType).displayName
        )

        val nested = UnionType(
            linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.STRING)
        )
        val swapped = UnionType(linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL))
        val tuple = assertIs<TupleType>(
            TypeNormalizer.normalize(TupleType(listOf(nested, swapped)))
        )
        assertEquals("string | number", assertIs<UnionType>(tuple.elementTypes[0]).displayName)
        assertEquals("boolean | nil", assertIs<UnionType>(tuple.elementTypes[1]).displayName)

        val multi = assertIs<MultiReturnType>(
            TypeNormalizer.normalize(MultiReturnType(listOf(swapped, nested)))
        )
        assertEquals("boolean | nil", assertIs<UnionType>(multi.types[0]).displayName)
        assertEquals("string | number", assertIs<UnionType>(multi.types[1]).displayName)

        val vararg = assertIs<VarargType>(TypeNormalizer.normalize(VarargType(nested)))
        assertEquals("string | number", assertIs<UnionType>(vararg.elementType).displayName)
    }

    @Test
    fun displayNameIsStableAcrossIdempotentRenormalize() {
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
        assertEquals(once.displayName, twice.displayName)
        assertEquals("number | string | nil", once.displayName)
        // Re-normalize does not sort or reshuffle displayName.
        assertSame(
            PrimitiveType.STRING,
            assertIs<UnionType>(once).types.toList()[1]
        )
    }

    @Test
    fun singletonCollapseDisplayNameIsNotUnionJoin() {
        assertEquals(
            "string",
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        PrimitiveType.STRING,
                        PrimitiveType.STRING,
                        PrimitiveType("text", PrimitiveType.Kind.STRING)
                    )
                )
            ).displayName
        )
        assertEquals(
            "never",
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(NeverType, UnionType(linkedSetOf(NeverType))))
            ).displayName
        )
    }

    @Test
    fun displayNameEqualsOrderedMemberJoinContract() {
        // Explicit contract: UnionType.displayName == members joined by " | " in set iteration order.
        val orders = listOf(
            listOf(PrimitiveType.TABLE, PrimitiveType.FUNCTION, PrimitiveType.THREAD),
            listOf(PrimitiveType.USERDATA, PrimitiveType.NIL, PrimitiveType.BOOLEAN)
        )
        orders.forEach { order ->
            val normalized = assertIs<UnionType>(
                TypeNormalizer.normalize(UnionType(order.toCollection(linkedSetOf())))
            )
            val joined = normalized.types.joinToString(" | ") { it.displayName }
            assertEquals(joined, normalized.displayName)
            assertEquals(order.joinToString(" | ") { it.displayName }, normalized.displayName)
        }
    }

    /** Sorted display-name join — order-insensitive member-set fingerprint. */
    private fun memberSetFingerprint(type: Type): String = when (type) {
        is UnionType -> type.types.map { it.displayName }.sorted().joinToString("|")
        else -> type.displayName
    }

    /** Sorted tokens of a displayName string — order-insensitive display set fingerprint. */
    private fun displayNameSetFingerprint(type: Type): String =
        type.displayName.split(" | ").map { it.trim() }.sorted().joinToString("|")
}
