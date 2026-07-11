package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Corpus for TypeRelations table/structural assignability (TASK-288).
 *
 * Policy encoded here (must match TypeRelations.isTableAssignable /
 * isMemberBearingShapeAssignable / intersection table merge / absorbants):
 * - Structural width: source may have extra fields/methods; missing required
 *   target members fail.
 * - Member depth: each required field/method type is checked via isAssignable.
 * - Index signatures: when target declares one, source must supply a compatible
 *   index (key and value both source-subtypes of target).
 * - Table-like sources: ModuleType, ClassType (no target index), ArrayType
 *   (index-only number→element targets).
 * - Intersection sources of table shapes merge members before structural check;
 *   conflicting same-name member types yield never and reject.
 * - Unknown / error (and PrimitiveType.UNKNOWN/ERROR after normalize) absorb as
 *   source or target without throwing. PrimitiveType.ANY absorbs as *target*
 *   only (isPrimitiveAssignable); any-as-source is not a structural wildcard.
 * - PrimitiveType.TABLE accepts any table-like shape.
 * - Test-only; production code is out of scope.
 *
 * Complements TypeRelationsTest.checksStructuralTableAssignability with a
 * denser table-focused matrix.
 */
class TypeRelationsTableAssignTddTest {

    // -------------------------------------------------------------------------
    // Structural width / depth (table ← table)
    // -------------------------------------------------------------------------

    @Test
    fun emptyTableIsAssignableFromAnyTableShape() {
        val empty = TableType()
        assertTrue(empty.isAssignableFrom(TableType()))
        assertTrue(empty.isAssignableFrom(TableType(fields = mapOf("x" to PrimitiveType.NUMBER))))
        assertTrue(
            empty.isAssignableFrom(
                TableType(
                    methods = mapOf("m" to FunctionType(returnType = PrimitiveType.NIL)),
                    indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.ANY)
                )
            )
        )
    }

    @Test
    fun sourceWithExtraFieldsAndMethodsSatisfiesNarrowerTarget() {
        val target = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER),
            methods = mapOf("draw" to FunctionType(returnType = PrimitiveType.NIL))
        )
        val source = TableType(
            fields = mapOf(
                "id" to PrimitiveType.NUMBER,
                "name" to PrimitiveType.STRING,
                "extra" to PrimitiveType.BOOLEAN
            ),
            methods = mapOf(
                "draw" to FunctionType(returnType = PrimitiveType.NIL),
                "hide" to FunctionType(returnType = PrimitiveType.NIL)
            )
        )

        assertTrue(target.isAssignableFrom(source))
        assertFalse(source.isAssignableFrom(target))
    }

    @Test
    fun missingRequiredFieldOrMethodRejects() {
        val target = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER, "label" to PrimitiveType.STRING),
            methods = mapOf("render" to FunctionType(returnType = PrimitiveType.STRING))
        )

        assertFalse(
            target.isAssignableFrom(
                TableType(
                    fields = mapOf("id" to PrimitiveType.NUMBER),
                    methods = mapOf("render" to FunctionType(returnType = PrimitiveType.STRING))
                )
            )
        )
        assertFalse(
            target.isAssignableFrom(
                TableType(
                    fields = mapOf(
                        "id" to PrimitiveType.NUMBER,
                        "label" to PrimitiveType.STRING
                    )
                )
            )
        )
        assertFalse(target.isAssignableFrom(TableType()))
    }

    @Test
    fun fieldTypeDepthUsesIsAssignableNotEquality() {
        val target = TableType(
            fields = mapOf(
                "tag" to PrimitiveType.STRING,
                "count" to PrimitiveType.NUMBER
            )
        )
        val ok = TableType(
            fields = mapOf(
                "tag" to LiteralType("ok", PrimitiveType.STRING),
                "count" to LiteralType(1, PrimitiveType.NUMBER)
            )
        )
        val bad = TableType(
            fields = mapOf(
                "tag" to PrimitiveType.STRING,
                "count" to PrimitiveType.STRING
            )
        )

        assertTrue(target.isAssignableFrom(ok))
        assertFalse(target.isAssignableFrom(bad))
        // Literal field target is narrower than primitive source.
        assertFalse(
            TableType(fields = mapOf("tag" to LiteralType("ok", PrimitiveType.STRING)))
                .isAssignableFrom(TableType(fields = mapOf("tag" to PrimitiveType.STRING)))
        )
    }

    @Test
    fun methodTypeDepthRequiresCallableCompatibility() {
        val targetMethod = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
            returnType = PrimitiveType.NUMBER
        )
        val target = TableType(methods = mapOf("run" to targetMethod))

        val compatible = FunctionType(
            parameters = listOf(FunctionParameter("value", LiteralType("x", PrimitiveType.STRING))),
            returnType = PrimitiveType.NUMBER
        )
        val badParam = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.NUMBER)),
            returnType = PrimitiveType.NUMBER
        )
        val badReturn = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
            returnType = PrimitiveType.STRING
        )

        assertTrue(target.isAssignableFrom(TableType(methods = mapOf("run" to compatible))))
        assertFalse(target.isAssignableFrom(TableType(methods = mapOf("run" to badParam))))
        assertFalse(target.isAssignableFrom(TableType(methods = mapOf("run" to badReturn))))
        assertFalse(target.isAssignableFrom(TableType(fields = mapOf("run" to PrimitiveType.STRING))))
    }

    // -------------------------------------------------------------------------
    // Index signatures
    // -------------------------------------------------------------------------

    @Test
    fun indexSignatureRequiredWhenTargetDeclaresOne() {
        val target = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        val withIndex = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        val withoutIndex = TableType(fields = mapOf("a" to PrimitiveType.NUMBER))

        assertTrue(target.isAssignableFrom(withIndex))
        assertFalse(target.isAssignableFrom(withoutIndex))
        // Target without index does not require one on source.
        assertTrue(TableType().isAssignableFrom(withIndex))
        assertTrue(TableType(fields = mapOf("a" to PrimitiveType.NUMBER)).isAssignableFrom(withoutIndex))
    }

    @Test
    fun indexKeyAndValueAreCovariantUnderIsAssignable() {
        val target = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        // Source value literal number <: number; same string key.
        val okValue = TableType(
            indexSignature = TableType.IndexSignature(
                PrimitiveType.STRING,
                LiteralType(1, PrimitiveType.NUMBER)
            )
        )
        val badValue = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.STRING)
        )
        val badKey = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, PrimitiveType.NUMBER)
        )

        assertTrue(target.isAssignableFrom(okValue))
        assertFalse(target.isAssignableFrom(badValue))
        assertFalse(target.isAssignableFrom(badKey))
    }

    @Test
    fun fieldsPlusIndexMustBothHold() {
        val target = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.ANY)
        )
        val ok = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER, "name" to PrimitiveType.STRING),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.STRING)
        )
        val missingField = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.STRING)
        )
        val missingIndex = TableType(fields = mapOf("id" to PrimitiveType.NUMBER))

        assertTrue(target.isAssignableFrom(ok))
        assertFalse(target.isAssignableFrom(missingField))
        assertFalse(target.isAssignableFrom(missingIndex))
    }

    // -------------------------------------------------------------------------
    // Table-like non-Table sources
    // -------------------------------------------------------------------------

    @Test
    fun moduleSourceSatisfiesStructuralTableTarget() {
        val target = TableType(
            fields = mapOf("version" to PrimitiveType.STRING),
            methods = mapOf("start" to FunctionType(returnType = PrimitiveType.NIL)),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        val module = ModuleType(
            moduleName = "app",
            fields = mapOf(
                "version" to LiteralType("1.0", PrimitiveType.STRING),
                "build" to PrimitiveType.NUMBER
            ),
            methods = mapOf("start" to FunctionType(returnType = PrimitiveType.NIL)),
            indexSignature = ModuleType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        val incomplete = ModuleType(
            moduleName = "app",
            fields = mapOf("version" to PrimitiveType.STRING)
        )

        assertTrue(target.isAssignableFrom(module))
        assertFalse(target.isAssignableFrom(incomplete))
    }

    @Test
    fun classSourceSatisfiesFieldMethodTargetsButNotIndexedTargets() {
        val base = ClassType("Base", fields = mapOf("id" to PrimitiveType.NUMBER))
        val child = ClassType(
            "Child",
            fields = mapOf("name" to PrimitiveType.STRING),
            methods = mapOf("render" to FunctionType(returnType = PrimitiveType.STRING)),
            superClass = base
        )
        val fieldMethodTarget = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER, "name" to PrimitiveType.STRING),
            methods = mapOf("render" to FunctionType(returnType = PrimitiveType.STRING))
        )
        val indexedTarget = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.ANY)
        )

        assertTrue(fieldMethodTarget.isAssignableFrom(child))
        // Class path requires target.indexSignature == null.
        assertFalse(indexedTarget.isAssignableFrom(child))
        assertFalse(
            TableType(fields = mapOf("missing" to PrimitiveType.BOOLEAN)).isAssignableFrom(child)
        )
    }

    @Test
    fun arraySourceOnlyFitsIndexOnlyNumberKeyTargets() {
        val array = ArrayType(PrimitiveType.STRING)
        val arrayTable = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, PrimitiveType.STRING)
        )
        val widerValue = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, PrimitiveType.ANY)
        )
        val withFields = TableType(
            fields = mapOf("len" to PrimitiveType.NUMBER),
            indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, PrimitiveType.STRING)
        )
        val stringKeyIndex = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.STRING)
        )

        assertTrue(arrayTable.isAssignableFrom(array))
        assertTrue(widerValue.isAssignableFrom(array))
        assertFalse(withFields.isAssignableFrom(array))
        assertFalse(stringKeyIndex.isAssignableFrom(array))
        assertFalse(
            TableType(methods = mapOf("m" to FunctionType(returnType = PrimitiveType.NIL)))
                .isAssignableFrom(array)
        )
    }

    @Test
    fun primitiveTableAcceptsAllTableLikeShapes() {
        val shapes: List<Type> = listOf(
            TableType(fields = mapOf("x" to PrimitiveType.NUMBER)),
            ModuleType("m", fields = mapOf("y" to PrimitiveType.STRING)),
            ClassType("C", fields = mapOf("z" to PrimitiveType.BOOLEAN)),
            ArrayType(PrimitiveType.NUMBER),
            TableType()
        )
        shapes.forEach { shape ->
            assertTrue(PrimitiveType.TABLE.isAssignableFrom(shape), "expected TABLE ← ${shape.name}")
        }
        assertFalse(PrimitiveType.TABLE.isAssignableFrom(PrimitiveType.STRING))
        assertFalse(PrimitiveType.TABLE.isAssignableFrom(FunctionType(returnType = PrimitiveType.NIL)))
    }

    // -------------------------------------------------------------------------
    // Intersection merge of table shapes
    // -------------------------------------------------------------------------

    @Test
    fun intersectionOfTablesMergesMembersForStructuralTarget() {
        val target = TableType(
            fields = mapOf(
                "id" to PrimitiveType.NUMBER,
                "name" to PrimitiveType.STRING
            ),
            methods = mapOf("draw" to FunctionType(returnType = PrimitiveType.NIL))
        )
        val source = IntersectionType(
            linkedSetOf(
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                TableType(
                    fields = mapOf("name" to PrimitiveType.STRING),
                    methods = mapOf("draw" to FunctionType(returnType = PrimitiveType.NIL))
                )
            )
        )

        assertTrue(target.isAssignableFrom(source))
    }

    @Test
    fun intersectionMergeRejectsConflictingMemberTypes() {
        val target = TableType(fields = mapOf("id" to PrimitiveType.NUMBER))
        val conflict = IntersectionType(
            linkedSetOf(
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                TableType(fields = mapOf("id" to PrimitiveType.STRING))
            )
        )

        assertFalse(target.isAssignableFrom(conflict))
    }

    @Test
    fun intersectionMergesIndexSignaturesWhenCompatible() {
        val target = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        val source = IntersectionType(
            linkedSetOf(
                TableType(
                    fields = mapOf("id" to PrimitiveType.NUMBER),
                    indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
                ),
                TableType(
                    fields = mapOf("name" to PrimitiveType.STRING)
                )
            )
        )
        val indexConflict = IntersectionType(
            linkedSetOf(
                TableType(indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)),
                TableType(indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.STRING))
            )
        )

        assertTrue(target.isAssignableFrom(source))
        assertFalse(
            TableType(indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER))
                .isAssignableFrom(indexConflict)
        )
    }

    @Test
    fun intersectionCanMixModuleAndTableShapes() {
        val target = TableType(
            fields = mapOf("a" to PrimitiveType.NUMBER, "b" to PrimitiveType.STRING)
        )
        val source = IntersectionType(
            linkedSetOf(
                ModuleType("m", fields = mapOf("a" to PrimitiveType.NUMBER)),
                TableType(fields = mapOf("b" to PrimitiveType.STRING))
            )
        )

        assertTrue(target.isAssignableFrom(source))
    }

    // -------------------------------------------------------------------------
    // Alias unwrap + unions around tables
    // -------------------------------------------------------------------------

    @Test
    fun aliasWrappedTablesCompareThroughUnwrappedShape() {
        val shape = TableType(fields = mapOf("id" to PrimitiveType.NUMBER))
        val aliasTarget = AliasType("IdTable", AliasType("Inner", shape))
        val aliasSource = AliasType(
            "Wide",
            TableType(
                fields = mapOf(
                    "id" to LiteralType(7, PrimitiveType.NUMBER),
                    "extra" to PrimitiveType.STRING
                )
            )
        )

        assertTrue(aliasTarget.isAssignableFrom(aliasSource))
        assertTrue(shape.isAssignableFrom(aliasSource))
        assertTrue(aliasTarget.isAssignableFrom(shape))
    }

    @Test
    fun unionTargetsAndSourcesAroundTables() {
        val tString = TableType(fields = mapOf("v" to PrimitiveType.STRING))
        val tNumber = TableType(fields = mapOf("v" to PrimitiveType.NUMBER))
        val unionTarget = UnionType(linkedSetOf(tString, tNumber))
        val sourceString = TableType(fields = mapOf("v" to LiteralType("x", PrimitiveType.STRING)))
        val sourceBoth = UnionType(
            linkedSetOf(
                TableType(fields = mapOf("v" to PrimitiveType.STRING)),
                TableType(fields = mapOf("v" to PrimitiveType.NUMBER))
            )
        )
        val sourceBad = UnionType(
            linkedSetOf(
                TableType(fields = mapOf("v" to PrimitiveType.STRING)),
                TableType(fields = mapOf("v" to PrimitiveType.BOOLEAN))
            )
        )

        assertTrue(unionTarget.isAssignableFrom(sourceString))
        assertTrue(unionTarget.isAssignableFrom(sourceBoth))
        assertFalse(unionTarget.isAssignableFrom(sourceBad))
        // Source union → single target requires all arms assignable.
        assertTrue(tString.isAssignableFrom(UnionType(linkedSetOf(sourceString, tString))))
        assertFalse(tString.isAssignableFrom(sourceBoth))
    }

    // -------------------------------------------------------------------------
    // Unknown / any / error absorb without throw
    // -------------------------------------------------------------------------

    @Test
    fun unknownAndErrorAbsorbAsSourceOrTargetWithoutThrow() {
        val structural = TableType(
            fields = mapOf(
                "id" to PrimitiveType.NUMBER,
                "name" to PrimitiveType.STRING
            ),
            methods = mapOf("run" to FunctionType(returnType = PrimitiveType.NIL)),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        // UnknownType / ErrorType (and PrimitiveType.UNKNOWN/ERROR after normalize)
        // short-circuit isAssignable in both directions.
        val bidirectionalAbsorbants: List<Type> = listOf(
            UnknownType,
            ErrorType,
            PrimitiveType.UNKNOWN,
            PrimitiveType.ERROR
        )

        bidirectionalAbsorbants.forEach { abs ->
            assertTrue(structural.isAssignableFrom(abs), "table ← ${abs.name}")
            assertTrue(abs.isAssignableFrom(structural), "${abs.name} ← table")
            assertTrue(TableType().isAssignableFrom(abs))
            assertTrue(abs.isAssignableFrom(TableType()))
        }
    }

    @Test
    fun anyAbsorbsAsTargetOnlyAndDoesNotThrow() {
        val structural = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER),
            methods = mapOf("run" to FunctionType(returnType = PrimitiveType.NIL))
        )
        // ANY as target: isPrimitiveAssignable short-circuits true for any source.
        assertTrue(PrimitiveType.ANY.isAssignableFrom(structural))
        assertTrue(PrimitiveType.ANY.isAssignableFrom(TableType()))
        assertTrue(PrimitiveType.ANY.isAssignableFrom(ArrayType(PrimitiveType.STRING)))
        assertTrue(PrimitiveType.ANY.isAssignableFrom(ModuleType("m")))
        assertTrue(PrimitiveType.ANY.isAssignableFrom(ClassType("C")))
        assertTrue(PrimitiveType.ANY.isAssignableFrom(UnknownType))
        assertTrue(PrimitiveType.ANY.isAssignableFrom(NeverType))

        // ANY as source is *not* a structural wildcard for table targets.
        assertFalse(structural.isAssignableFrom(PrimitiveType.ANY))
        assertFalse(TableType().isAssignableFrom(PrimitiveType.ANY))
        // Nested ANY field/index still absorbs via member-level isAssignable.
        assertTrue(
            TableType(fields = mapOf("x" to PrimitiveType.ANY))
                .isAssignableFrom(TableType(fields = mapOf("x" to PrimitiveType.STRING)))
        )
        assertTrue(
            TableType(indexSignature = TableType.IndexSignature(PrimitiveType.ANY, PrimitiveType.ANY))
                .isAssignableFrom(
                    TableType(indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER))
                )
        )
    }

    @Test
    fun nestedUnknownOrAnyInFieldOrIndexAbsorbs() {
        val targetWithUnknownField = TableType(fields = mapOf("x" to UnknownType))
        val targetWithAnyField = TableType(fields = mapOf("x" to PrimitiveType.ANY))
        val concrete = TableType(fields = mapOf("x" to PrimitiveType.STRING))
        val sourceUnknownField = TableType(fields = mapOf("x" to UnknownType))

        assertTrue(targetWithUnknownField.isAssignableFrom(concrete))
        assertTrue(targetWithAnyField.isAssignableFrom(concrete))
        assertTrue(concrete.isAssignableFrom(sourceUnknownField))
        assertTrue(
            TableType(indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER))
                .isAssignableFrom(
                    TableType(indexSignature = TableType.IndexSignature(UnknownType, UnknownType))
                )
        )
        assertTrue(
            TableType(indexSignature = TableType.IndexSignature(PrimitiveType.ANY, PrimitiveType.ANY))
                .isAssignableFrom(
                    TableType(indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER))
                )
        )
    }

    @Test
    fun neverSourceAssignsToTablesButNeverTargetDoesNotAcceptTables() {
        val table = TableType(fields = mapOf("k" to PrimitiveType.BOOLEAN))
        assertTrue(table.isAssignableFrom(NeverType))
        assertTrue(PrimitiveType.TABLE.isAssignableFrom(NeverType))
        assertFalse(NeverType.isAssignableFrom(table))
        assertTrue(NeverType.isAssignableFrom(NeverType))
    }

    @Test
    fun nonTableNonAbsorbantSourcesRejectWithoutThrow() {
        val target = TableType(fields = mapOf("id" to PrimitiveType.NUMBER))
        val rejects: List<Type> = listOf(
            PrimitiveType.NUMBER,
            PrimitiveType.STRING,
            PrimitiveType.NIL,
            PrimitiveType.BOOLEAN,
            PrimitiveType.FUNCTION,
            PrimitiveType.ANY,
            LiteralType("nope", PrimitiveType.STRING),
            FunctionType(returnType = PrimitiveType.NUMBER)
        )
        rejects.forEach { source ->
            assertFalse(target.isAssignableFrom(source), "table must reject ${source.name}")
        }
    }

    // -------------------------------------------------------------------------
    // Stability matrix: identity, symmetry edges, nested tables
    // -------------------------------------------------------------------------

    @Test
    fun identicalTablesAreAssignableBothWays() {
        val a = TableType(
            fields = mapOf("a" to PrimitiveType.STRING, "b" to PrimitiveType.NUMBER),
            methods = mapOf("m" to FunctionType(returnType = PrimitiveType.BOOLEAN)),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.ANY)
        )
        val b = TableType(
            fields = mapOf("a" to PrimitiveType.STRING, "b" to PrimitiveType.NUMBER),
            methods = mapOf("m" to FunctionType(returnType = PrimitiveType.BOOLEAN)),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.ANY)
        )
        assertTrue(a.isAssignableFrom(b))
        assertTrue(b.isAssignableFrom(a))
        assertTrue(a.isAssignableFrom(a))
    }

    @Test
    fun nestedTableFieldsCheckStructurally() {
        val innerTarget = TableType(fields = mapOf("x" to PrimitiveType.NUMBER))
        val target = TableType(fields = mapOf("inner" to innerTarget))
        val ok = TableType(
            fields = mapOf(
                "inner" to TableType(
                    fields = mapOf(
                        "x" to LiteralType(1, PrimitiveType.NUMBER),
                        "y" to PrimitiveType.STRING
                    )
                )
            )
        )
        val bad = TableType(
            fields = mapOf(
                "inner" to TableType(fields = mapOf("x" to PrimitiveType.STRING))
            )
        )
        val missingInnerField = TableType(
            fields = mapOf("inner" to TableType())
        )

        assertTrue(target.isAssignableFrom(ok))
        assertFalse(target.isAssignableFrom(bad))
        assertFalse(target.isAssignableFrom(missingInnerField))
    }

    @Test
    fun optionalNilUnionFieldDepthRemainsStable() {
        val optionalString = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NIL))
        val target = TableType(fields = mapOf("name" to optionalString))
        val stringOnly = TableType(fields = mapOf("name" to PrimitiveType.STRING))
        val nilOnly = TableType(fields = mapOf("name" to PrimitiveType.NIL))
        val both = TableType(fields = mapOf("name" to optionalString))
        val number = TableType(fields = mapOf("name" to PrimitiveType.NUMBER))

        assertTrue(target.isAssignableFrom(stringOnly))
        assertTrue(target.isAssignableFrom(nilOnly))
        assertTrue(target.isAssignableFrom(both))
        assertFalse(target.isAssignableFrom(number))
        // Narrower target does not accept optional source.
        assertFalse(stringOnly.isAssignableFrom(both))
    }

    @Test
    fun denseCorpusMatrixStaysDeterministic() {
        // Compact multi-assert matrix used as a regression snapshot for review serial runs.
        val rows = listOf(
            // target, source, expected
            Triple(
                TableType(fields = mapOf("a" to PrimitiveType.NUMBER)),
                TableType(fields = mapOf("a" to PrimitiveType.NUMBER, "b" to PrimitiveType.STRING)),
                true
            ),
            Triple(
                TableType(fields = mapOf("a" to PrimitiveType.NUMBER, "b" to PrimitiveType.STRING)),
                TableType(fields = mapOf("a" to PrimitiveType.NUMBER)),
                false
            ),
            Triple(
                TableType(methods = mapOf("f" to FunctionType(returnType = PrimitiveType.STRING))),
                TableType(methods = mapOf("f" to FunctionType(returnType = PrimitiveType.STRING))),
                true
            ),
            Triple(
                TableType(indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, PrimitiveType.STRING)),
                ArrayType(LiteralType("x", PrimitiveType.STRING)),
                true
            ),
            Triple(
                TableType(indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, PrimitiveType.STRING)),
                ArrayType(PrimitiveType.NUMBER),
                false
            ),
            Triple(
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                ClassType("C", fields = mapOf("id" to PrimitiveType.NUMBER)),
                true
            ),
            Triple(
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                ModuleType("m", fields = mapOf("id" to PrimitiveType.NUMBER)),
                true
            ),
            Triple(
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                UnknownType,
                true
            ),
            Triple(
                UnknownType,
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                true
            ),
            Triple(
                PrimitiveType.ANY,
                TableType(),
                true
            ),
            Triple(
                // ANY as source is not a structural wildcard for tables.
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                PrimitiveType.ANY,
                false
            ),
            Triple(
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                ErrorType,
                true
            ),
            Triple(
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                IntersectionType(
                    linkedSetOf(
                        TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                        TableType(fields = mapOf("name" to PrimitiveType.STRING))
                    )
                ),
                true
            ),
            Triple(
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                IntersectionType(
                    linkedSetOf(
                        TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                        TableType(fields = mapOf("id" to PrimitiveType.STRING))
                    )
                ),
                false
            ),
            Triple(
                PrimitiveType.TABLE,
                ArrayType(PrimitiveType.BOOLEAN),
                true
            ),
            Triple(
                TableType(fields = mapOf("x" to PrimitiveType.STRING)),
                PrimitiveType.TABLE,
                false
            )
        )

        rows.forEachIndexed { index, (target, source, expected) ->
            val actual = target.isAssignableFrom(source)
            if (expected) {
                assertTrue(actual, "row $index expected assignable: ${target.name} ← ${source.name}")
            } else {
                assertFalse(actual, "row $index expected reject: ${target.name} ← ${source.name}")
            }
        }
    }
}
