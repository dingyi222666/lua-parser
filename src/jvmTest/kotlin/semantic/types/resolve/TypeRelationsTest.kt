package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
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
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom
import io.github.dingyi222666.luaparser.semantic.types.resolve.overloadedFunctionOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeRelationsTest {

    @Test
    fun handlesPrimitiveAndLiteralAssignability() {
        assertTrue(PrimitiveType.STRING.isAssignableFrom(PrimitiveType.STRING))
        assertTrue(PrimitiveType.STRING.isAssignableFrom(LiteralType("x", PrimitiveType.STRING)))
        assertFalse(LiteralType("x", PrimitiveType.STRING).isAssignableFrom(PrimitiveType.STRING))
        assertTrue(LiteralType("x", PrimitiveType.STRING).isAssignableFrom(LiteralType("x", PrimitiveType.STRING)))
        assertFalse(LiteralType("x", PrimitiveType.STRING).isAssignableFrom(LiteralType("y", PrimitiveType.STRING)))
    }

    @Test
    fun appliesAnyUnknownErrorAndNeverShortCircuits() {
        assertTrue(PrimitiveType.STRING.isAssignableFrom(UnknownType))
        assertTrue(PrimitiveType.STRING.isAssignableFrom(ErrorType))
        assertTrue(PrimitiveType.STRING.isAssignableFrom(NeverType))
        assertTrue(NeverType.isAssignableFrom(NeverType))
        assertFalse(NeverType.isAssignableFrom(PrimitiveType.STRING))
        assertTrue(UnknownType.isAssignableFrom(PrimitiveType.STRING))
    }

    @Test
    fun handlesUnionAndIntersectionTargetsAndSources() {
        val unionTarget = UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        val unionSource = UnionType(linkedSetOf(LiteralType("x", PrimitiveType.STRING), LiteralType("y", PrimitiveType.STRING)))
        val badUnionSource = UnionType(linkedSetOf(LiteralType("x", PrimitiveType.STRING), PrimitiveType.BOOLEAN))
        val intersectionTarget = IntersectionType(linkedSetOf(PrimitiveType.STRING, LiteralType("x", PrimitiveType.STRING)))
        val intersectionSource = IntersectionType(linkedSetOf(LiteralType("x", PrimitiveType.STRING), PrimitiveType.NUMBER))

        assertTrue(unionTarget.isAssignableFrom(PrimitiveType.STRING))
        assertTrue(PrimitiveType.STRING.isAssignableFrom(unionSource))
        assertFalse(PrimitiveType.STRING.isAssignableFrom(badUnionSource))
        assertTrue(intersectionTarget.isAssignableFrom(LiteralType("x", PrimitiveType.STRING)))
        assertTrue(PrimitiveType.STRING.isAssignableFrom(intersectionSource))
    }

    @Test
    fun comparesAliasesThroughUnwrappedTargets() {
        val alias = AliasType("Text", AliasType("InnerText", PrimitiveType.STRING))

        assertTrue(alias.isAssignableFrom(LiteralType("ok", PrimitiveType.STRING)))
        assertTrue(PrimitiveType.STRING.isAssignableFrom(alias))
    }

    @Test
    fun checksFunctionAndOverloadAssignability() {
        val target = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
            returnType = PrimitiveType.NUMBER
        )
        val compatible = FunctionType(
            parameters = listOf(FunctionParameter("value", LiteralType("x", PrimitiveType.STRING))),
            returnType = PrimitiveType.NUMBER
        )
        val incompatible = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.NUMBER)),
            returnType = PrimitiveType.NUMBER
        )
        val overloadedTarget = overloadedFunctionOf(
            target,
            FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.NUMBER)), returnType = PrimitiveType.STRING)
        )
        val uncoveredSource = overloadedFunctionOf(
            compatible,
            FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.BOOLEAN)), returnType = PrimitiveType.STRING)
        )

        assertTrue(target.isAssignableFrom(compatible))
        assertFalse(target.isAssignableFrom(incompatible))
        assertTrue(overloadedTarget.isAssignableFrom(target))
        assertFalse(overloadedTarget.isAssignableFrom(uncoveredSource))
        assertTrue(PrimitiveType.FUNCTION.isAssignableFrom(overloadedTarget))
    }

    @Test
    fun aliasWrappedOverloadedCallableRemainsAssignable() {
        val target = AliasType(
            "Fn",
            overloadedFunctionOf(
                FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)), returnType = PrimitiveType.STRING),
                FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.NUMBER)), returnType = PrimitiveType.NUMBER)
            )
        )

        assertTrue(target.isAssignableFrom(FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)), returnType = PrimitiveType.STRING)))
        assertTrue(PrimitiveType.FUNCTION.isAssignableFrom(target))
    }

    @Test
    fun treatsAlphaEquivalentGenericFunctionsAsAssignable() {
        val target = FunctionType(
            parameters = listOf(FunctionParameter("value", TypeParameterType("T", constraint = PrimitiveType.STRING))),
            returnType = TypeParameterType("T", constraint = PrimitiveType.STRING),
            typeParameters = listOf(TypeParameterType("T", constraint = PrimitiveType.STRING))
        )
        val source = FunctionType(
            parameters = listOf(FunctionParameter("value", TypeParameterType("U", constraint = PrimitiveType.STRING))),
            returnType = TypeParameterType("U", constraint = PrimitiveType.STRING),
            typeParameters = listOf(TypeParameterType("U", constraint = PrimitiveType.STRING))
        )

        assertTrue(target.isAssignableFrom(source))
    }

    @Test
    fun checksStructuralTableAssignability() {
        val method = FunctionType(returnType = PrimitiveType.STRING)
        val target = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER),
            methods = mapOf("render" to method),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        val source = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER, "name" to PrimitiveType.STRING),
            methods = mapOf("render" to method),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, LiteralType(1, PrimitiveType.NUMBER))
        )
        val missingField = TableType(methods = mapOf("render" to method))

        assertTrue(target.isAssignableFrom(source))
        assertFalse(target.isAssignableFrom(missingField))
    }

    @Test
    fun supportsClassInheritanceAndTableLikeSources() {
        val base = ClassType("Base", fields = mapOf("id" to PrimitiveType.NUMBER))
        val child = ClassType(
            "Child",
            fields = mapOf("name" to PrimitiveType.STRING),
            methods = mapOf("render" to FunctionType(returnType = PrimitiveType.STRING)),
            superClass = base
        )
        val tableTarget = TableType(
            fields = mapOf("id" to PrimitiveType.NUMBER),
            methods = mapOf("render" to FunctionType(returnType = PrimitiveType.STRING))
        )
        val arrayTableTarget = TableType(indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, PrimitiveType.STRING))

        assertTrue(base.isAssignableFrom(child))
        assertTrue(tableTarget.isAssignableFrom(child))
        assertTrue(arrayTableTarget.isAssignableFrom(ArrayType(PrimitiveType.STRING)))
        assertTrue(PrimitiveType.TABLE.isAssignableFrom(child))
        assertTrue(PrimitiveType.TABLE.isAssignableFrom(ArrayType(PrimitiveType.STRING)))
    }

    @Test
    fun treatsAlphaEquivalentGenericClassesAsAssignable() {
        val target = ClassType("Box", typeParameters = listOf(TypeParameterType("T", constraint = PrimitiveType.STRING)))
        val source = ClassType("Box", typeParameters = listOf(TypeParameterType("U", constraint = PrimitiveType.STRING)))

        assertTrue(target.isAssignableFrom(source))
    }

    @Test
    fun combinesIntersectionMembersForStructuralTargets() {
        val target = TableType(
            fields = mapOf(
                "id" to PrimitiveType.NUMBER,
                "name" to PrimitiveType.STRING
            )
        )
        val source = IntersectionType(
            linkedSetOf(
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                TableType(fields = mapOf("name" to PrimitiveType.STRING))
            )
        )

        assertTrue(target.isAssignableFrom(source))
    }

    @Test
    fun rejectsImpossibleMergedIntersectionMembersForStructuralTargets() {
        val target = TableType(fields = mapOf("id" to PrimitiveType.NUMBER))
        val source = IntersectionType(
            linkedSetOf(
                TableType(fields = mapOf("id" to PrimitiveType.NUMBER)),
                TableType(fields = mapOf("id" to PrimitiveType.STRING))
            )
        )

        assertFalse(target.isAssignableFrom(source))
    }

    @Test
    fun checksArrayTupleMultiReturnAndVarargShapes() {
        assertTrue(ArrayType(PrimitiveType.STRING).isAssignableFrom(ArrayType(LiteralType("x", PrimitiveType.STRING))))
        assertFalse(ArrayType(PrimitiveType.STRING).isAssignableFrom(ArrayType(PrimitiveType.NUMBER)))

        assertTrue(TupleType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)).isAssignableFrom(
            TupleType(listOf(LiteralType("x", PrimitiveType.STRING), LiteralType(1, PrimitiveType.NUMBER)))
        ))
        assertFalse(TupleType(listOf(PrimitiveType.STRING)).isAssignableFrom(TupleType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))))

        assertTrue(MultiReturnType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)).isAssignableFrom(
            MultiReturnType(listOf(LiteralType("x", PrimitiveType.STRING), LiteralType(1, PrimitiveType.NUMBER)))
        ))
        assertFalse(MultiReturnType(listOf(PrimitiveType.STRING)).isAssignableFrom(MultiReturnType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))))

        assertTrue(VarargType(PrimitiveType.STRING).isAssignableFrom(VarargType(LiteralType("x", PrimitiveType.STRING))))
        assertFalse(VarargType(PrimitiveType.STRING).isAssignableFrom(VarargType(PrimitiveType.NUMBER)))
    }

    @Test
    fun checksAppliedCustomAndTypeParameterRules() {
        assertTrue(AppliedType("Box", listOf(PrimitiveType.STRING)).isAssignableFrom(AppliedType("Box", listOf(LiteralType("x", PrimitiveType.STRING)))))
        assertFalse(AppliedType("Box", listOf(PrimitiveType.STRING)).isAssignableFrom(AppliedType("Result", listOf(PrimitiveType.STRING))))

        assertTrue(CustomType("UserId").isAssignableFrom(CustomType("UserId")))
        assertFalse(CustomType("UserId").isAssignableFrom(CustomType("SessionId")))

        val constrained = TypeParameterType("T", constraint = PrimitiveType.STRING)
        assertTrue(constrained.isAssignableFrom(LiteralType("x", PrimitiveType.STRING)))
        assertFalse(constrained.isAssignableFrom(PrimitiveType.NUMBER))
    }
}
