package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Corpus for TypeNormalizer nested-union flattening (TASK-287).
 *
 * Policy encoded here (must match TypeNormalizer / unionTypeOf / optionalTypeOf):
 * - Nested unions flatten to a single-level UnionType (or singleton/contagious collapse).
 * - Flatten order is deterministic: first-seen insertion order after recursive normalize
 *   of members (LinkedHashSet semantics).
 * - Nilability is preserved: legitimate `T | nil` shapes keep a single nil member;
 *   redundant nils collapse but nil is never stripped from optionals.
 * - never is dropped from unions; any/unknown remain contagious at the top level.
 * - Flattening is applied element-wise inside containers (function params/returns,
 *   tables, arrays, tuples, multi-return, vararg) without inventing outer unions.
 * - Test-only; production code is out of scope.
 */
class TypeNormalizerUnionFlattenTddTest {

    // -------------------------------------------------------------------------
    // Deterministic nested flatten
    // -------------------------------------------------------------------------

    @Test
    fun flattensTwoLevelNestedUnionInFirstSeenOrder() {
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
    fun flattensDeeplyNestedUnionsToSingleLevel() {
        // ((string | number) | (boolean | string)) | number
        val left = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        val right = UnionType(linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.STRING))
        val mid = UnionType(linkedSetOf(left, right))
        val input = UnionType(linkedSetOf(mid, PrimitiveType.NUMBER))

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
            normalized.types.toList()
        )
        assertEquals(3, normalized.types.size)
        assertTrue(normalized.types.none { it is UnionType })
    }

    @Test
    fun flattensRightNestedChainDeterministically() {
        // string | (number | (boolean | table))
        val deepest = UnionType(linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.TABLE))
        val mid = UnionType(linkedSetOf(PrimitiveType.NUMBER, deepest))
        val input = UnionType(linkedSetOf(PrimitiveType.STRING, mid))

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN,
                PrimitiveType.TABLE
            ),
            normalized.types.toList()
        )
    }

    @Test
    fun flattensLeftNestedChainDeterministically() {
        // ((string | number) | boolean) | table
        val innermost = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        val mid = UnionType(linkedSetOf(innermost, PrimitiveType.BOOLEAN))
        val input = UnionType(linkedSetOf(mid, PrimitiveType.TABLE))

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN,
                PrimitiveType.TABLE
            ),
            normalized.types.toList()
        )
    }

    @Test
    fun flattensDuplicateMembersAcrossNestedBranchesOnce() {
        val a = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        val b = UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN, PrimitiveType.STRING))
        val input = UnionType(linkedSetOf(a, b, PrimitiveType.BOOLEAN))

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
            normalized.types.toList()
        )
        assertEquals(1, normalized.types.count { it == PrimitiveType.STRING })
        assertEquals(1, normalized.types.count { it == PrimitiveType.NUMBER })
        assertEquals(1, normalized.types.count { it == PrimitiveType.BOOLEAN })
    }

    @Test
    fun nestedSingleMemberUnionsCollapseToMemberNotUnionShell() {
        val nested = UnionType(linkedSetOf(UnionType(linkedSetOf(PrimitiveType.STRING))))
        assertSame(PrimitiveType.STRING, TypeNormalizer.normalize(nested))
        assertSame(
            PrimitiveType.NUMBER,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(UnionType(linkedSetOf(PrimitiveType.NUMBER, NeverType))))
            )
        )
    }

    @Test
    fun dropsNeverWhileFlatteningNestedUnions() {
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
    }

    @Test
    fun neverOnlyNestedUnionCollapsesToNever() {
        val input = UnionType(
            linkedSetOf(
                NeverType,
                UnionType(linkedSetOf(NeverType)),
                UnionType(linkedSetOf(NeverType, NeverType))
            )
        )
        assertSame(NeverType, TypeNormalizer.normalize(input))
    }

    @Test
    fun unionTypeOfFactoryMatchesDirectNestedNormalize() {
        val nested = UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN))
        val direct = TypeNormalizer.normalize(
            UnionType(linkedSetOf(PrimitiveType.STRING, nested, PrimitiveType.STRING))
        )
        val viaFactory = unionTypeOf(PrimitiveType.STRING, nested, PrimitiveType.STRING)
        assertEquals(direct, viaFactory)
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
            assertIs<UnionType>(viaFactory).types.toList()
        )
    }

    // -------------------------------------------------------------------------
    // Nilability preserved under flatten
    // -------------------------------------------------------------------------

    @Test
    fun flattensNestedOptionalsPreservingSingleNil() {
        // (string | nil) | (number | nil) | nil -> string | nil | number
        val left = optionalTypeOf(PrimitiveType.STRING)
        val right = optionalTypeOf(PrimitiveType.NUMBER)
        val input = UnionType(linkedSetOf(left, right, PrimitiveType.NIL))

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            normalized.types.toList()
        )
        assertEquals(1, normalized.types.count { it == PrimitiveType.NIL })
    }

    @Test
    fun deeplyNestedOptionalUnionKeepsNilAtFlattenedTopLevel() {
        // ((string | nil) | number) | (boolean | (nil | table))
        val optString = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL))
        val left = UnionType(linkedSetOf(optString, PrimitiveType.NUMBER))
        val nilTable = UnionType(linkedSetOf(PrimitiveType.NIL, PrimitiveType.TABLE))
        val right = UnionType(linkedSetOf(PrimitiveType.BOOLEAN, nilTable))
        val input = UnionType(linkedSetOf(left, right))

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NIL,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN,
                PrimitiveType.TABLE
            ),
            normalized.types.toList()
        )
        assertEquals(1, normalized.types.count { it == PrimitiveType.NIL })
    }

    @Test
    fun optionalTypeOfAroundNestedUnionPreservesNilOnce() {
        // optional(string | number) -> string | number | nil (order: members then nil)
        val nested = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        val optional = optionalTypeOf(nested)

        val members = assertIs<UnionType>(optional).types.toList()
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NIL),
            members
        )
        assertEquals(optional, TypeNormalizer.normalize(optional))
    }

    @Test
    fun nestedOptionalOfOptionalDoesNotDuplicateNil() {
        val once = optionalTypeOf(PrimitiveType.BOOLEAN)
        val twice = optionalTypeOf(once)
        val thrice = unionTypeOf(twice, optionalTypeOf(PrimitiveType.BOOLEAN), PrimitiveType.NIL)

        val members = assertIs<UnionType>(thrice).types.toList()
        assertEquals(listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL), members)
        assertEquals(1, members.count { it == PrimitiveType.NIL })
    }

    @Test
    fun nilOnlyNestedBranchesCollapseToNilSingleton() {
        val input = UnionType(
            linkedSetOf(
                UnionType(linkedSetOf(PrimitiveType.NIL)),
                UnionType(linkedSetOf(PrimitiveType.NIL, NeverType)),
                AliasType("NilA", PrimitiveType.NIL)
            )
        )
        assertSame(PrimitiveType.NIL, TypeNormalizer.normalize(input))
    }

    @Test
    fun flattensAliasWrappedNestedOptionalsPreservingNil() {
        val maybeString = AliasType(
            "MaybeString",
            UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL))
        )
        val maybeNumber = AliasType(
            "MaybeNumber",
            AliasType(
                "InnerMaybeNumber",
                UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.NIL))
            )
        )
        val outer = UnionType(
            linkedSetOf(
                maybeString,
                UnionType(linkedSetOf(maybeNumber, PrimitiveType.BOOLEAN)),
                PrimitiveType.NIL
            )
        )

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(outer))
        assertEquals(
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NIL,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN
            ),
            normalized.types.toList()
        )
        assertEquals(1, normalized.types.count { it == PrimitiveType.NIL })
        assertTrue(normalized.types.none { it is AliasType })
        assertTrue(normalized.types.none { it is UnionType })
    }

    @Test
    fun preservesOptionalLiteralShapeWithoutInventingBase() {
        // ("x" | nil) | ("y" | nil) -> "x" | nil | "y"
        val left = UnionType(linkedSetOf(LiteralType("x", PrimitiveType.STRING), PrimitiveType.NIL))
        val right = UnionType(linkedSetOf(LiteralType("y", PrimitiveType.STRING), PrimitiveType.NIL))

        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(UnionType(linkedSetOf(left, right)))
        )
        assertEquals(
            listOf(
                LiteralType("x", PrimitiveType.STRING),
                PrimitiveType.NIL,
                LiteralType("y", PrimitiveType.STRING)
            ),
            normalized.types.toList()
        )
        assertTrue(normalized.types.none { it == PrimitiveType.STRING })
    }

    @Test
    fun absorbsLiteralsIntoBaseWhileKeepingNilDuringFlatten() {
        // (string | "x") | (nil | "y") | string -> string | nil
        val left = UnionType(
            linkedSetOf(PrimitiveType.STRING, LiteralType("x", PrimitiveType.STRING))
        )
        val right = UnionType(
            linkedSetOf(PrimitiveType.NIL, LiteralType("y", PrimitiveType.STRING))
        )
        val input = UnionType(linkedSetOf(left, right, PrimitiveType.STRING))

        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(input))
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NIL), normalized.types.toList())
    }

    // -------------------------------------------------------------------------
    // Contagious members after flatten
    // -------------------------------------------------------------------------

    @Test
    fun nestedAnyAbsorbsFlattenedUnionIncludingNil() {
        val nested = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL))
        assertSame(
            PrimitiveType.ANY,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(nested, PrimitiveType.NUMBER, PrimitiveType.ANY))
            )
        )
    }

    @Test
    fun nestedUnknownAbsorbsFlattenedUnionIncludingNil() {
        val nested = UnionType(linkedSetOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL))
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, nested, UnknownType))
            )
        )
    }

    @Test
    fun unknownViaNestedAliasStillAbsorbsAfterFlatten() {
        val mystery = AliasType(
            "MaybeMystery",
            UnionType(linkedSetOf(AliasType("U", UnknownType), PrimitiveType.NIL))
        )
        assertSame(
            UnknownType,
            TypeNormalizer.normalize(
                UnionType(linkedSetOf(PrimitiveType.STRING, mystery, PrimitiveType.NUMBER))
            )
        )
    }

    // -------------------------------------------------------------------------
    // Flatten inside containers (element-wise; no invented outer union)
    // -------------------------------------------------------------------------

    @Test
    fun flattensNestedUnionsInsideFunctionParameterAndReturn() {
        val paramNested = UnionType(
            linkedSetOf(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL)),
                PrimitiveType.NUMBER
            )
        )
        val returnNested = UnionType(
            linkedSetOf(
                optionalTypeOf(PrimitiveType.BOOLEAN),
                UnionType(linkedSetOf(PrimitiveType.TABLE, PrimitiveType.NIL))
            )
        )
        val fn = FunctionType(
            parameters = listOf(FunctionParameter("value", paramNested)),
            returnType = returnNested
        )

        val normalized = assertIs<FunctionType>(TypeNormalizer.normalize(fn))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            assertIs<UnionType>(normalized.parameters.single().type).types.toList()
        )
        assertEquals(
            listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL, PrimitiveType.TABLE),
            assertIs<UnionType>(normalized.returnType).types.toList()
        )
    }

    @Test
    fun flattensNestedUnionsInsideTableFieldsAndMethods() {
        val fieldUnion = UnionType(
            linkedSetOf(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER)),
                PrimitiveType.NIL
            )
        )
        val methodReturn = UnionType(
            linkedSetOf(
                optionalTypeOf(PrimitiveType.BOOLEAN),
                PrimitiveType.BOOLEAN
            )
        )
        val table = TableType(
            fields = mapOf("f" to fieldUnion),
            methods = mapOf("m" to FunctionType(returnType = methodReturn))
        )

        val normalized = assertIs<TableType>(TypeNormalizer.normalize(table))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.NIL),
            assertIs<UnionType>(normalized.fields.getValue("f")).types.toList()
        )
        assertEquals(
            listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL),
            assertIs<UnionType>(
                (normalized.methods.getValue("m") as FunctionType).returnType
            ).types.toList()
        )
    }

    @Test
    fun flattensNestedUnionsInsideArrayTupleMultiReturnVararg() {
        val nestedOptional = UnionType(
            linkedSetOf(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL)),
                PrimitiveType.NUMBER
            )
        )

        val array = assertIs<ArrayType>(TypeNormalizer.normalize(ArrayType(nestedOptional)))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            assertIs<UnionType>(array.elementType).types.toList()
        )

        val tuple = assertIs<TupleType>(
            TypeNormalizer.normalize(
                TupleType(
                    listOf(
                        nestedOptional,
                        UnionType(linkedSetOf(PrimitiveType.NIL, PrimitiveType.BOOLEAN))
                    )
                )
            )
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            assertIs<UnionType>(tuple.elementTypes[0]).types.toList()
        )
        assertEquals(
            listOf(PrimitiveType.NIL, PrimitiveType.BOOLEAN),
            assertIs<UnionType>(tuple.elementTypes[1]).types.toList()
        )

        val multi = assertIs<MultiReturnType>(
            TypeNormalizer.normalize(
                MultiReturnType(
                    listOf(
                        nestedOptional,
                        UnionType(linkedSetOf(PrimitiveType.NIL))
                    )
                )
            )
        )
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            assertIs<UnionType>(multi.types[0]).types.toList()
        )
        assertSame(PrimitiveType.NIL, multi.types[1])

        val vararg = assertIs<VarargType>(TypeNormalizer.normalize(VarargType(nestedOptional)))
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            assertIs<UnionType>(vararg.elementType).types.toList()
        )
    }

    @Test
    fun doesNotFlattenUnionNestedOnlyInsideIntersectionMemberShell() {
        // Intersection is not union-flattened away; each member still normalizes.
        val unionMember = UnionType(
            linkedSetOf(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER)),
                PrimitiveType.STRING
            )
        )
        val input = IntersectionType(linkedSetOf(CustomType("Named"), unionMember))

        val normalized = assertIs<IntersectionType>(TypeNormalizer.normalize(input))
        assertEquals(2, normalized.types.size)
        assertTrue(normalized.types.any { it == CustomType("Named") })
        val flattenedUnion = normalized.types.filterIsInstance<UnionType>().single()
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NUMBER),
            flattenedUnion.types.toList()
        )
    }

    // -------------------------------------------------------------------------
    // Display / stability smoke
    // -------------------------------------------------------------------------

    @Test
    fun flattenedOptionalDisplayMentionsMembersAndNil() {
        val nested = UnionType(
            linkedSetOf(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL)),
                PrimitiveType.NUMBER
            )
        )
        val normalized = assertIs<UnionType>(TypeNormalizer.normalize(nested))
        assertEquals("string | nil | number", normalized.displayName)
        assertEquals("string | nil | number", normalized.name)
    }

    @Test
    fun repeatedNormalizeIsIdempotentForFlattenedUnion() {
        val input = UnionType(
            linkedSetOf(
                UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL)),
                UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.NIL)),
                NeverType,
                LiteralType("x", PrimitiveType.STRING)
            )
        )
        val once = TypeNormalizer.normalize(input)
        val twice = TypeNormalizer.normalize(once)
        assertEquals(once, twice)
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL, PrimitiveType.NUMBER),
            assertIs<UnionType>(once).types.toList()
        )
    }

    @Test
    fun corpusUnionTypeOfNestedMatchesNormalizeAndPreservesNilCount() {
        val members: List<Type> = listOf(
            UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL)),
            AliasType("MaybeNum", UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.NIL))),
            UnionType(linkedSetOf(PrimitiveType.BOOLEAN)),
            NeverType,
            PrimitiveType.NIL
        )
        val viaFactory = unionTypeOf(members)
        val viaNormalize = TypeNormalizer.normalize(UnionType(members.toCollection(linkedSetOf())))
        assertEquals(viaNormalize, viaFactory)

        val union = assertIs<UnionType>(viaFactory)
        assertEquals(
            listOf(
                PrimitiveType.STRING,
                PrimitiveType.NIL,
                PrimitiveType.NUMBER,
                PrimitiveType.BOOLEAN
            ),
            union.types.toList()
        )
        assertEquals(1, union.types.count { it == PrimitiveType.NIL })
    }
}
