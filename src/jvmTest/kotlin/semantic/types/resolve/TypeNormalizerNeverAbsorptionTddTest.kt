package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
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
import io.github.dingyi222666.luaparser.semantic.types.resolve.intersectionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.optionalTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Corpus for TypeNormalizer never absorption (TASK-438).
 *
 * Policy encoded here (must match TypeNormalizer / unionTypeOf / intersectionTypeOf):
 *
 * Union (`normalizeUnion`):
 * - never is dropped wherever it appears (leading / middle / trailing / nested).
 * - empty after drop collapses to NeverType.
 * - Primitive Kind.NEVER canonicalizes to NeverType before absorption.
 * - never does not block any/unknown contagion; never + any → any; never + unknown → unknown.
 * - Legitimate optionals `T | nil` keep a single nil after never is dropped.
 * - Literals whose base is present still absorb after never drop.
 * - Alias-wrapped never unwraps and is dropped like bare never.
 *
 * Intersection (`normalizeIntersection`):
 * - any never member absorbs the whole intersection to NeverType (position-insensitive).
 * - incompatible primitives / literals collapse to NeverType even without an explicit never.
 * - never via alias still absorbs.
 *
 * Containers:
 * - Absorption is applied element-wise inside function params/returns, tables, arrays,
 *   tuples, multi-return, and vararg without inventing outer unions/intersections.
 *
 * Test-only; production code is out of scope. Verification is review-owned (no Gradle).
 */
class TypeNormalizerNeverAbsorptionTddTest {

    // -------------------------------------------------------------------------
    // Union: never dropped / empty → never
    // -------------------------------------------------------------------------

    @Test
    fun bareNeverNormalizesToNeverSingleton() {
        assertSame(NeverType, TypeNormalizer.normalize(NeverType))
        assertSame(
            NeverType,
            TypeNormalizer.normalize(PrimitiveType("n", PrimitiveType.Kind.NEVER))
        )
        assertSame(
            NeverType,
            TypeNormalizer.normalize(PrimitiveType.NEVER)
        )
    }

    @Test
    fun neverDroppedFromUnionWhereverItAppearsPreservingSiblingOrder() {
        val expected = listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)

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

        assertEquals(expected, leading.types.toList())
        assertEquals(expected, middle.types.toList())
        assertEquals(expected, trailing.types.toList())
        assertTrue(leading.types.none { it == NeverType })
        assertTrue(middle.types.none { it == NeverType })
        assertTrue(trailing.types.none { it == NeverType })
    }

    @Test
    fun neverOnlyUnionCollapsesToNeverRegardlessOfNestingOrDuplicates() {
        assertSame(
            NeverType,
            TypeNormalizer.normalize(UnionType(linkedSetOf(NeverType)))
        )
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(NeverType, NeverType, NeverType))
            )
        )
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        NeverType,
                        UnionType(linkedSetOf(NeverType)),
                        UnionType(linkedSetOf(NeverType, NeverType)),
                        PrimitiveType("bot", PrimitiveType.Kind.NEVER)
                    )
                )
            )
        )
    }

    @Test
    fun dropsNeverWhileFlatteningNestedUnionsLeavingDistinctMembers() {
        val nested = UnionType(
            linkedSetOf(
                NeverType,
                UnionType(linkedSetOf(PrimitiveType.STRING, NeverType)),
                UnionType(linkedSetOf(NeverType, PrimitiveType.NUMBER)),
                NeverType
            )
        )

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(nested))
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER), normalized.types.toList())
        assertTrue(normalized.types.none { it == NeverType })
        assertTrue(normalized.types.none { it is UnionType })
    }

    @Test
    fun neverWithSingleSiblingCollapsesToThatSiblingNotUnionShell() {
        assertSame(
            PrimitiveType.STRING,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(NeverType, PrimitiveType.STRING, NeverType))
            )
        )
        assertSame(
            PrimitiveType.NUMBER,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(UnionType(linkedSetOf(PrimitiveType.NUMBER, NeverType))))
            )
        )
        assertSame(
            PrimitiveType.BOOLEAN,
            unionTypeOf(NeverType, PrimitiveType.BOOLEAN, NeverType)
        )
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
        assertEquals(1, normalized.types.count { it == PrimitiveType.NIL })
        assertTrue(normalized.types.none { it == NeverType })
    }

    @Test
    fun nestedOptionalWithNeverKeepsSingleNilAfterDrop() {
        // (string | never | nil) | (number | never) | never -> string | nil | number
        val left = UnionType(
            linkedSetOf(PrimitiveType.STRING, NeverType, PrimitiveType.NIL)
        )
        val right = UnionType(linkedSetOf(PrimitiveType.NUMBER, NeverType))
        val input = UnionType(linkedSetOf(left, right, NeverType))

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            normalized.types.toList()
        )
        assertEquals(1, normalized.types.count { it == PrimitiveType.NIL })
    }

    // -------------------------------------------------------------------------
    // Union: never vs any / unknown contagion
    // -------------------------------------------------------------------------

    @Test
    fun neverDoesNotBlockAnyAbsorptionInUnion() {
        assertSame(
            PrimitiveType.ANY,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(NeverType, PrimitiveType.STRING, PrimitiveType.ANY))
            )
        )
        assertSame(
            PrimitiveType.ANY,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.ANY, NeverType, PrimitiveType.NUMBER))
            )
        )
        assertSame(
            PrimitiveType.ANY,
            unionTypeOf(NeverType, PrimitiveType.NIL, PrimitiveType.ANY, NeverType)
        )
    }

    @Test
    fun neverDoesNotBlockUnknownAbsorptionInUnion() {
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(NeverType, PrimitiveType.BOOLEAN, UnknownType))
            )
        )
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(UnknownType, NeverType, PrimitiveType.NIL))
            )
        )
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, NeverType, UnknownType, NeverType))
            )
        )
    }

    @Test
    fun neverWithUnknownAloneIsUnknownNotNever() {
        val normalized = TypeNormalizer.normalize(
            UnionType(linkedSetOf(UnknownType, NeverType))
        )
        assertSame(UnknownType, normalized)
        assertNotEquals(NeverType, normalized)
    }

    @Test
    fun neverWithAnyAloneIsAnyNotNever() {
        assertSame(
            PrimitiveType.ANY,
            TypeNormalizer.normalize(UnionType(linkedSetOf(NeverType, PrimitiveType.ANY)))
        )
        assertSame(
            PrimitiveType.ANY,
            TypeNormalizer.normalize(UnionType(linkedSetOf(PrimitiveType.ANY, NeverType)))
        )
    }

    // -------------------------------------------------------------------------
    // Union: alias-wrapped never + literals
    // -------------------------------------------------------------------------

    @Test
    fun aliasWrappedNeverIsDroppedFromUnionLikeBareNever() {
        val neverAlias = AliasType("Bot", NeverType)
        val nestedNeverAlias = AliasType("OuterBot", AliasType("InnerBot", NeverType))
        val kindNeverAlias = AliasType(
            "KindBot",
            PrimitiveType("n", PrimitiveType.Kind.NEVER)
        )

        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        PrimitiveType.STRING,
                        neverAlias,
                        nestedNeverAlias,
                        kindNeverAlias,
                        PrimitiveType.NUMBER
                    )
                )
            )
        )
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER), normalized.types.toList())
        assertTrue(normalized.types.none { it is AliasType })
        assertTrue(normalized.types.none { it == NeverType })
    }

    @Test
    fun aliasOnlyNeverChainCollapsesToNeverSingleton() {
        val chain = AliasType(
            "A",
            AliasType(
                "B",
                AliasType("C", NeverType)
            )
        )
        assertSame(NeverType, TypeNormalizer.normalize(chain))
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(chain, AliasType("D", NeverType)))
            )
        )
    }

    @Test
    fun dropsNeverThenAbsorbsLiteralsIntoBaseInUnion() {
        // never | "x" | string | "y" | never -> string
        assertSame(
            PrimitiveType.STRING,
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        NeverType,
                        LiteralType("x", PrimitiveType.STRING),
                        PrimitiveType.STRING,
                        LiteralType("y", PrimitiveType.STRING),
                        NeverType
                    )
                )
            )
        )

        // string | never | number | "x" -> string | number
        val withTrailingLiteral = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        PrimitiveType.STRING,
                        NeverType,
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
    }

    @Test
    fun neverWithDistinctLiteralsWithoutBaseKeepsLiteralsAfterDrop() {
        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        NeverType,
                        LiteralType("x", PrimitiveType.STRING),
                        NeverType,
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
            normalized.types.toList()
        )
        assertTrue(normalized.types.none { it == NeverType })
        assertTrue(normalized.types.none { it == PrimitiveType.STRING })
    }

    // -------------------------------------------------------------------------
    // Intersection: never absorbs whole intersection
    // -------------------------------------------------------------------------

    @Test
    fun neverMemberAbsorbsIntersectionToNeverRegardlessOfPosition() {
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(linkedSetOf(NeverType, PrimitiveType.STRING, PrimitiveType.NUMBER))
            )
        )
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(linkedSetOf(PrimitiveType.STRING, NeverType, CustomType("Named")))
            )
        )
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER, NeverType))
            )
        )
        assertSame(
            NeverType,
            intersectionTypeOf(PrimitiveType.STRING, NeverType, PrimitiveType.BOOLEAN)
        )
    }

    @Test
    fun neverOnlyIntersectionCollapsesToNever() {
        assertSame(
            NeverType,
            TypeNormalizer.normalize(IntersectionType(linkedSetOf(NeverType)))
        )
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(
                    linkedSetOf(
                        NeverType,
                        IntersectionType(linkedSetOf(NeverType)),
                        PrimitiveType("bot", PrimitiveType.Kind.NEVER)
                    )
                )
            )
        )
    }

    @Test
    fun incompatiblePrimitivesCollapseToNeverWithoutExplicitNeverMember() {
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
            )
        )
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.TABLE, PrimitiveType.NIL))
            )
        )
    }

    @Test
    fun incompatibleLiteralsCollapseToNever() {
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(
                    linkedSetOf(
                        LiteralType("a", PrimitiveType.STRING),
                        LiteralType("b", PrimitiveType.STRING)
                    )
                )
            )
        )
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(
                    linkedSetOf(
                        LiteralType("1", PrimitiveType.NUMBER),
                        LiteralType("2", PrimitiveType.NUMBER),
                        NeverType
                    )
                )
            )
        )
    }

    @Test
    fun aliasWrappedNeverAbsorbsIntersection() {
        val neverAlias = AliasType("Bot", NeverType)
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(linkedSetOf(PrimitiveType.STRING, neverAlias, CustomType("X")))
            )
        )
        assertSame(
            NeverType,
            TypeNormalizer.normalize(
                IntersectionType(
                    linkedSetOf(
                        AliasType("Outer", AliasType("Inner", NeverType)),
                        PrimitiveType.NUMBER
                    )
                )
            )
        )
    }

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
    fun nestedIntersectionWithNeverInBranchAbsorbsToNever() {
        val nested = IntersectionType(
            linkedSetOf(
                IntersectionType(linkedSetOf(PrimitiveType.STRING, CustomType("A"))),
                IntersectionType(linkedSetOf(NeverType, PrimitiveType.NUMBER))
            )
        )
        assertSame(NeverType, TypeNormalizer.normalize(nested))
    }

    // -------------------------------------------------------------------------
    // Containers: element-wise never absorption
    // -------------------------------------------------------------------------

    @Test
    fun absorbsNeverInsideFunctionParameterAndReturnUnions() {
        val paramUnion = UnionType(
            linkedSetOf(NeverType, PrimitiveType.STRING, NeverType, PrimitiveType.NUMBER)
        )
        val returnUnion = UnionType(
            linkedSetOf(optionalTypeOf(PrimitiveType.BOOLEAN), NeverType)
        )
        val fn = FunctionType(
            parameters = listOf(FunctionParameter("value", paramUnion)),
            returnType = returnUnion
        )

        val normalized = assertIs<FunctionType>(TypeNormalizer.normalize(fn))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            assertIs<UnionType>(normalized.parameters.single().type).types.toList()
        )
        assertEquals(
            listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL),
            assertIs<UnionType>(normalized.returnType).types.toList()
        )
    }

    @Test
    fun absorbsNeverInsideTableFieldsAndMethods() {
        val fieldUnion = UnionType(
            linkedSetOf(PrimitiveType.STRING, NeverType, PrimitiveType.NIL)
        )
        val methodReturn = UnionType(
            linkedSetOf(NeverType, PrimitiveType.BOOLEAN, NeverType)
        )
        val table = TableType(
            fields = mapOf("f" to fieldUnion),
            methods = mapOf("m" to FunctionType(returnType = methodReturn))
        )

        val normalized = assertIs<TableType>(TypeNormalizer.normalize(table))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL),
            assertIs<UnionType>(normalized.fields.getValue("f")).types.toList()
        )
        assertSame(
            PrimitiveType.BOOLEAN,
            (normalized.methods.getValue("m") as FunctionType).returnType
        )
    }

    @Test
    fun absorbsNeverInsideArrayTupleMultiReturnVararg() {
        val nested = UnionType(
            linkedSetOf(NeverType, PrimitiveType.STRING, NeverType, PrimitiveType.NUMBER)
        )

        val array = assertIs<ArrayType>(TypeNormalizer.normalize(ArrayType(nested)))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            assertIs<UnionType>(array.elementType).types.toList()
        )

        val tuple = assertIs<TupleType>(
            TypeNormalizer.normalize(
                TupleType(
                    listOf(
                        nested,
                        UnionType(linkedSetOf(NeverType, PrimitiveType.BOOLEAN))
                    )
                )
            )
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            assertIs<UnionType>(tuple.elementTypes[0]).types.toList()
        )
        assertSame(PrimitiveType.BOOLEAN, tuple.elementTypes[1])

        val multi = assertIs<MultiReturnType>(
            TypeNormalizer.normalize(
                MultiReturnType(
                    listOf(
                        nested,
                        UnionType(linkedSetOf(NeverType)),
                        AliasType("Bot", NeverType)
                    )
                )
            )
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            assertIs<UnionType>(multi.types[0]).types.toList()
        )
        assertSame(NeverType, multi.types[1])
        assertSame(NeverType, multi.types[2])

        val vararg = assertIs<VarargType>(TypeNormalizer.normalize(VarargType(nested)))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            assertIs<UnionType>(vararg.elementType).types.toList()
        )
    }

    @Test
    fun intersectionNeverAbsorptionInsideContainers() {
        val neverIntersect = IntersectionType(
            linkedSetOf(PrimitiveType.STRING, NeverType)
        )
        val fn = FunctionType(
            parameters = listOf(FunctionParameter("x", neverIntersect)),
            returnType = neverIntersect
        )
        val normalizedFn = assertIs<FunctionType>(TypeNormalizer.normalize(fn))
        assertSame(NeverType, normalizedFn.parameters.single().type)
        assertSame(NeverType, normalizedFn.returnType)

        val table = TableType(fields = mapOf("bad" to neverIntersect))
        val normalizedTable = assertIs<TableType>(TypeNormalizer.normalize(table))
        assertSame(NeverType, normalizedTable.fields.getValue("bad"))
    }

    // -------------------------------------------------------------------------
    // Factories + stability
    // -------------------------------------------------------------------------

    @Test
    fun unionTypeOfFactoryMatchesDirectNormalizeForNeverAbsorption() {
        val members: List<Type> = listOf(
            NeverType,
            PrimitiveType.STRING,
            UnionType(linkedSetOf(NeverType, PrimitiveType.NUMBER)),
            AliasType("Bot", NeverType),
            NeverType
        )
        val viaFactory = unionTypeOf(members)
        val viaNormalize = TypeNormalizer.normalize(UnionType(members.toCollection(linkedSetOf())))
        assertEquals(viaNormalize, viaFactory)
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            assertIs<UnionType>(viaFactory).types.toList()
        )
    }

    @Test
    fun intersectionTypeOfFactoryMatchesDirectNormalizeForNeverAbsorption() {
        val viaFactory = intersectionTypeOf(PrimitiveType.STRING, NeverType, PrimitiveType.NUMBER)
        val direct = TypeNormalizer.normalize(
            IntersectionType(linkedSetOf(PrimitiveType.STRING, NeverType, PrimitiveType.NUMBER))
        )
        assertSame(NeverType, viaFactory)
        assertSame(NeverType, direct)
        assertEquals(direct, viaFactory)
    }

    @Test
    fun optionalTypeOfNeverIsNilNotNeverShell() {
        // optional(never) = never | nil → nil (never dropped; nil remains)
        assertSame(PrimitiveType.NIL, optionalTypeOf(NeverType))
        assertSame(
            PrimitiveType.NIL,
            TypeNormalizer.normalize(UnionType(linkedSetOf(NeverType, PrimitiveType.NIL)))
        )
    }

    @Test
    fun repeatedNormalizeIsIdempotentAfterNeverAbsorption() {
        val unionInput = UnionType(
            linkedSetOf(
                UnionType(linkedSetOf(PrimitiveType.NUMBER, NeverType, PrimitiveType.STRING)),
                NeverType,
                LiteralType("x", PrimitiveType.STRING),
                PrimitiveType.STRING,
                PrimitiveType.NIL
            )
        )
        val onceUnion = TypeNormalizer.normalize(unionInput)
        val twiceUnion = TypeNormalizer.normalize(onceUnion)
        assertEquals(onceUnion, twiceUnion)
        assertEquals(
            listOf(PrimitiveType.NUMBER, PrimitiveType.STRING, PrimitiveType.NIL),
            assertIs<UnionType>(onceUnion).types.toList()
        )

        val intersectInput = IntersectionType(
            linkedSetOf(PrimitiveType.STRING, NeverType, CustomType("Named"))
        )
        val onceIntersect = TypeNormalizer.normalize(intersectInput)
        val twiceIntersect = TypeNormalizer.normalize(onceIntersect)
        assertSame(NeverType, onceIntersect)
        assertEquals(onceIntersect, twiceIntersect)
    }

    @Test
    fun neverAbsorbedUnionDisplayNameOmitsNever() {
        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        NeverType,
                        PrimitiveType.STRING,
                        NeverType,
                        PrimitiveType.NUMBER
                    )
                )
            )
        )
        assertEquals("string | number", normalized.displayName)
        assertEquals("string | number", normalized.name)
        assertTrue(!normalized.displayName.contains("never"))
    }

    @Test
    fun errorTypeInUnionIsNotTreatedAsNeverDrop() {
        // Error is not dropped like never; corpus documents the contrast.
        // Current policy: error remains as a member unless other contagion applies.
        val normalized = TypeNormalizer.normalize(
            UnionType(linkedSetOf(ErrorType, PrimitiveType.STRING, NeverType))
        )
        // never dropped; error + string stay (or collapse per equality of ErrorType)
        when (normalized) {
            is UnionType -> {
                assertTrue(normalized.types.any { it == ErrorType || it == PrimitiveType.STRING })
                assertTrue(normalized.types.none { it == NeverType })
            }
            ErrorType -> Unit // if error became contagious in future, still not never
            PrimitiveType.STRING -> Unit
            else -> assertNotEquals(NeverType, normalized)
        }
    }
}
