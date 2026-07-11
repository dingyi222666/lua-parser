package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
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
 * Corpus for TypeNormalizer nilability + alias collapse policy (TASK-201).
 *
 * Policy encoded here (must match TypeNormalizer / unionTypeOf / optionalTypeOf):
 * - Redundant nils in unions collapse (dedupe / flatten), but legitimate
 *   optional shapes `T | nil` are preserved (nil is not stripped from optionals).
 * - Alias chains unwrap before/during normalization; alias-to-nil and
 *   alias-to-optional collapse into the same union members as bare forms.
 * - Unknown is contagious in unions: presence of unknown yields UnknownType
 *   and must not invent a more precise concrete type.
 * - Any is also contagious in unions; never is dropped from unions.
 * - Test-only; production code is out of scope.
 */
class TypeNormalizerNilabilityTddTest {

    // -------------------------------------------------------------------------
    // Redundant nil collapse
    // -------------------------------------------------------------------------

    @Test
    fun collapsesDuplicateNilMembersToSingleNil() {
        val input = UnionType(
            linkedSetOf(
                PrimitiveType.NIL,
                PrimitiveType.NIL,
                PrimitiveType("nil", PrimitiveType.Kind.NIL)
            )
        )

        assertSame(PrimitiveType.NIL, TypeNormalizer.normalize(input))
        assertSame(PrimitiveType.NIL, unionTypeOf(PrimitiveType.NIL, PrimitiveType.NIL))
    }

    @Test
    fun collapsesNestedNilOnlyUnionsToNil() {
        val nested = UnionType(
            linkedSetOf(
                UnionType(linkedSetOf(PrimitiveType.NIL)),
                UnionType(linkedSetOf(PrimitiveType.NIL, NeverType)),
                PrimitiveType.NIL
            )
        )

        assertSame(PrimitiveType.NIL, TypeNormalizer.normalize(nested))
    }

    @Test
    fun dedupesNilInsideOptionalUnionWithoutStrippingNil() {
        val input = UnionType(
            linkedSetOf(
                PrimitiveType.STRING,
                PrimitiveType.NIL,
                PrimitiveType.NIL,
                UnionType(linkedSetOf(PrimitiveType.NIL, PrimitiveType.STRING))
            )
        )

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NIL), normalized.types.toList())
        assertEquals(2, normalized.types.size)
    }

    @Test
    fun flattensNestedOptionalsSharingNilIntoSingleUnion() {
        // (string | nil) | (number | nil) -> string | number | nil
        val left = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL))
        val right = UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.NIL))
        val input = UnionType(linkedSetOf(left, right))

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            normalized.types.toList()
        )
        assertEquals(1, normalized.types.count { it == PrimitiveType.NIL })
    }

    @Test
    fun dropsNeverFromOptionalUnionLeavingTOrNil() {
        val input = UnionType(
            linkedSetOf(
                PrimitiveType.BOOLEAN,
                NeverType,
                PrimitiveType.NIL,
                NeverType
            )
        )

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL), normalized.types.toList())
    }

    @Test
    fun optionalTypeOfProducesTOrNilAndIsIdempotentUnderNormalize() {
        val optional = optionalTypeOf(PrimitiveType.STRING)
        val asUnion = assertIs<UnionType>(optional)
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NIL), asUnion.types.toList())

        assertEquals(optional, TypeNormalizer.normalize(optional))
        assertEquals(
            optional,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(optional, PrimitiveType.NIL, PrimitiveType.STRING))
            )
        )
    }

    @Test
    fun optionalOfNilCollapsesToNil() {
        // nil | nil is redundant; optionalTypeOf(nil) must not invent a wider type.
        assertSame(PrimitiveType.NIL, optionalTypeOf(PrimitiveType.NIL))
        assertSame(
            PrimitiveType.NIL,
            TypeNormalizer.normalize(UnionType(linkedSetOf(PrimitiveType.NIL, PrimitiveType.NIL)))
        )
    }

    @Test
    fun preservesDistinctNonNilMembersAlongsideSingleNil() {
        val input = UnionType(
            linkedSetOf(
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN,
                PrimitiveType.NIL,
                PrimitiveType.NIL
            )
        )

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN,
                PrimitiveType.NIL
            ),
            normalized.types.toList()
        )
    }

    // -------------------------------------------------------------------------
    // Alias collapse with nil / optionals
    // -------------------------------------------------------------------------

    @Test
    fun unwrapsAliasToNilInsideUnion() {
        val nilAlias = AliasType("NilAlias", PrimitiveType.NIL)
        val nestedNilAlias = AliasType("OuterNil", AliasType("InnerNil", PrimitiveType.NIL))

        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, nilAlias, nestedNilAlias))
            )
        )

        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NIL), normalized.types.toList())
        assertTrue(normalized.types.none { it is AliasType })
    }

    @Test
    fun collapsesAliasChainWhoseTargetIsOptionalUnion() {
        val optionalString = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL))
        val maybeString = AliasType("MaybeString", optionalString)
        val outer = AliasType("OuterMaybe", maybeString)

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(outer))
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NIL), normalized.types.toList())
    }

    @Test
    fun mergesAliasWrappedOptionalsWithoutDuplicatingNil() {
        val maybeString = AliasType(
            "MaybeString",
            UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL))
        )
        val maybeNumber = AliasType(
            "MaybeNumber",
            UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.NIL))
        )

        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(UnionType(linkedSetOf(maybeString, maybeNumber, PrimitiveType.NIL)))
        )

        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            normalized.types.toList()
        )
        assertEquals(1, normalized.types.count { it == PrimitiveType.NIL })
        assertTrue(normalized.types.none { it is AliasType })
    }

    @Test
    fun unwrapsAliasesInsideFunctionAndTableOptionalShapes() {
        val stringAlias = AliasType("S", PrimitiveType.STRING)
        val nilAlias = AliasType("N", PrimitiveType.NIL)
        val optionalAlias = AliasType(
            "MaybeS",
            UnionType(linkedSetOf(stringAlias, nilAlias))
        )

        val fn = FunctionType(
            parameters = listOf(FunctionParameter("value", optionalAlias)),
            returnType = optionalAlias
        )
        val table = TableType(
            fields = mapOf(
                "opt" to optionalAlias,
                "nilField" to nilAlias
            )
        )

        val normalizedFn = assertIs<FunctionType>(TypeNormalizer.normalize(fn))
        val paramUnion = assertIs<UnionType>(normalizedFn.parameters.single().type)
        val returnUnion = assertIs<UnionType>(normalizedFn.returnType)
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NIL), paramUnion.types.toList())
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NIL), returnUnion.types.toList())

        val normalizedTable = assertIs<TableType>(TypeNormalizer.normalize(table))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL),
            assertIs<UnionType>(normalizedTable.fields.getValue("opt")).types.toList()
        )
        assertSame(PrimitiveType.NIL, normalizedTable.fields.getValue("nilField"))
    }

    @Test
    fun unwrapsAliasesInContainerTypesHoldingNil() {
        val nilAlias = AliasType("NilAlias", PrimitiveType.NIL)
        val stringAlias = AliasType("StringAlias", PrimitiveType.STRING)

        assertEquals(
            PrimitiveType.NIL,
            (TypeNormalizer.normalize(ArrayType(nilAlias)) as ArrayType).elementType
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL),
            (TypeNormalizer.normalize(TupleType(listOf(stringAlias, nilAlias))) as TupleType).elementTypes
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL),
            (TypeNormalizer.normalize(MultiReturnType(listOf(stringAlias, nilAlias))) as MultiReturnType).types
        )
        assertEquals(
            PrimitiveType.NIL,
            (TypeNormalizer.normalize(VarargType(nilAlias)) as VarargType).elementType
        )
    }

    @Test
    fun aliasOnlyNilChainCollapsesToNilSingleton() {
        val chain = AliasType(
            "A",
            AliasType(
                "B",
                AliasType("C", PrimitiveType.NIL)
            )
        )
        assertSame(PrimitiveType.NIL, TypeNormalizer.normalize(chain))
    }

    // -------------------------------------------------------------------------
    // Does not invent precision for unknown
    // -------------------------------------------------------------------------

    @Test
    fun unknownInUnionAbsorbsConcreteMembersIncludingNil() {
        // string | unknown -> unknown (must not stay as string or string|unknown)
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(UnionType(linkedSetOf(PrimitiveType.STRING, UnknownType)))
        )
        // unknown | nil -> unknown (must not invent optional-string or nil-only)
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(UnionType(linkedSetOf(UnknownType, PrimitiveType.NIL)))
        )
        // string | number | nil | unknown -> unknown
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        PrimitiveType.STRING,
                        PrimitiveType.NUMBER,
                        PrimitiveType.NIL,
                        UnknownType
                    )
                )
            )
        )
    }

    @Test
    fun unknownViaAliasStillAbsorbsUnionWithoutInventingPrecision() {
        val unknownAlias = AliasType("Mystery", UnknownType)
        val maybeUnknown = AliasType(
            "MaybeMystery",
            UnionType(linkedSetOf(unknownAlias, PrimitiveType.NIL))
        )

        assertSame(UnknownType, TypeNormalizer.normalize(unknownAlias))
        assertSame(UnknownType, TypeNormalizer.normalize(maybeUnknown))
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, maybeUnknown, PrimitiveType.NUMBER))
            )
        )
    }

    @Test
    fun primitiveUnknownKindCanonicalizesToUnknownTypeNotConcrete() {
        val kindUnknown = PrimitiveType("u", PrimitiveType.Kind.UNKNOWN)
        assertSame(UnknownType, TypeNormalizer.normalize(kindUnknown))
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(kindUnknown, PrimitiveType.STRING, PrimitiveType.NIL))
            )
        )
    }

    @Test
    fun bareUnknownAndNilAloneDoNotGainPrecision() {
        assertSame(UnknownType, TypeNormalizer.normalize(UnknownType))
        assertSame(PrimitiveType.NIL, TypeNormalizer.normalize(PrimitiveType.NIL))
        // unknown alone is not upgraded to any/string/nil
        assertNotEquals(PrimitiveType.ANY, TypeNormalizer.normalize(UnknownType))
        assertNotEquals(PrimitiveType.STRING, TypeNormalizer.normalize(UnknownType))
        assertNotEquals(PrimitiveType.NIL, TypeNormalizer.normalize(UnknownType))
    }

    @Test
    fun anyInUnionAbsorbsOptionalsWithoutInventingUnknownOrNilOnly() {
        assertSame(
            PrimitiveType.ANY,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.ANY))
            )
        )
        // any is not rewritten to unknown
        assertNotEquals(
            UnknownType,
            TypeNormalizer.normalize(UnionType(linkedSetOf(PrimitiveType.ANY, PrimitiveType.NIL)))
        )
    }

    @Test
    fun unknownDoesNotCollapseToNeverOrEmptyUnion() {
        val normalized = TypeNormalizer.normalize(
            UnionType(linkedSetOf(UnknownType, NeverType))
        )
        assertSame(UnknownType, normalized)
        assertNotEquals(NeverType, normalized)
    }

    // -------------------------------------------------------------------------
    // Intersection / literal edge interactions with nil & unknown
    // -------------------------------------------------------------------------

    @Test
    fun intersectionOfNilAndConcretePrimitiveIsNever() {
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL))
            )
        )
    }

    @Test
    fun intersectionDropsUnknownWhenConcretePresentLeavingNilOrConcrete() {
        assertSame(
            PrimitiveType.NIL,
            TypeNormalizer.normalize(
                IntersectionType(linkedSetOf(PrimitiveType.NIL, UnknownType))
            )
        )
        assertSame(
            PrimitiveType.STRING,
            TypeNormalizer.normalize(
                IntersectionType(linkedSetOf(PrimitiveType.STRING, UnknownType))
            )
        )
    }

    @Test
    fun literalOfBaseIsAbsorbedByBaseInsideOptionalUnion() {
        // string | "x" | nil -> string | nil (literal absorbed; nil kept)
        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        PrimitiveType.STRING,
                        LiteralType("x", PrimitiveType.STRING),
                        PrimitiveType.NIL,
                        LiteralType("y", PrimitiveType.STRING)
                    )
                )
            )
        )
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NIL), normalized.types.toList())
    }

    @Test
    fun optionalLiteralWithoutBaseStaysLiteralOrNil() {
        // "x" | nil is a legitimate optional literal; do not invent string
        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        LiteralType("x", PrimitiveType.STRING),
                        PrimitiveType.NIL
                    )
                )
            )
        )
        assertEquals(
            listOf(LiteralType("x", PrimitiveType.STRING), PrimitiveType.NIL),
            normalized.types.toList()
        )
        assertTrue(normalized.types.none { it == PrimitiveType.STRING })
    }

    @Test
    fun nestedOptionalFactoriesComposeWithoutRedundantNil() {
        val once = optionalTypeOf(PrimitiveType.NUMBER)
        val twice = optionalTypeOf(once)
        val thrice = unionTypeOf(twice, PrimitiveType.NIL, optionalTypeOf(PrimitiveType.NUMBER))

        val members = assertIs<UnionType>(thrice).types.toList()
        assertEquals(listOf(PrimitiveType.NUMBER, PrimitiveType.NIL), members)
        assertEquals(1, members.count { it == PrimitiveType.NIL })
    }

    @Test
    fun multiReturnOptionalShapesNormalizeElementwise() {
        val maybeString = AliasType(
            "MaybeString",
            UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL))
        )
        val multi = MultiReturnType(
            listOf(
                maybeString,
                UnionType(linkedSetOf(PrimitiveType.NIL, PrimitiveType.NIL)),
                AliasType("U", UnknownType)
            )
        )

        val normalized = assertIs<MultiReturnType>(TypeNormalizer.normalize(multi))
        assertEquals(3, normalized.types.size)
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL),
            assertIs<UnionType>(normalized.types[0]).types.toList()
        )
        assertSame(PrimitiveType.NIL, normalized.types[1])
        assertSame(UnknownType, normalized.types[2])
    }

    // -------------------------------------------------------------------------
    // Display / shape smoke for corpus stability
    // -------------------------------------------------------------------------

    @Test
    fun optionalUnionDisplayNameMentionsNilAndBase() {
        val optional = assertIs<UnionType>(optionalTypeOf(PrimitiveType.STRING))
        assertEquals("string | nil", optional.displayName)
        assertEquals("string | nil", TypeNormalizer.normalize(optional).displayName)
    }

    @Test
    fun corpusUnionTypeOfMatchesDirectNormalize() {
        val members: List<Type> = listOf(
            AliasType("S", PrimitiveType.STRING),
            AliasType("N", PrimitiveType.NIL),
            UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL)),
            NeverType
        )
        val viaFactory = unionTypeOf(members)
        val viaNormalize = TypeNormalizer.normalize(UnionType(members.toCollection(linkedSetOf())))
        assertEquals(viaNormalize, viaFactory)
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL),
            assertIs<UnionType>(viaFactory).types.toList()
        )
    }
}
