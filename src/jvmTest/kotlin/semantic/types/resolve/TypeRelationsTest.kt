package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeRelations
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaPrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaSignatureMetadata
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeNormalizer
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom
import io.github.dingyi222666.luaparser.semantic.types.resolve.overloadedFunctionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

        // Function params are contravariant for signature-vs-signature assignability:
        // a fun(value: "x") handler cannot serve fun(value: string) (it crashes on any
        // other string), while fun(value: string) CAN serve fun(value: "x").
        assertFalse(target.isAssignableFrom(compatible))
        assertTrue(compatible.isAssignableFrom(target))
        assertFalse(target.isAssignableFrom(incompatible))
        assertTrue(overloadedTarget.isAssignableFrom(target))
        assertFalse(overloadedTarget.isAssignableFrom(uncoveredSource))
        assertTrue(PrimitiveType.FUNCTION.isAssignableFrom(overloadedTarget))

        // Arity containment: a source accepting MORE arguments (extra optional) can serve
        // a target with fewer required parameters; the reverse cannot.
        val fewerRequired = FunctionType(
            parameters = listOf(FunctionParameter("a", PrimitiveType.NUMBER)),
            returnType = PrimitiveType.NIL
        )
        val extraOptional = FunctionType(
            parameters = listOf(
                FunctionParameter("a", PrimitiveType.NUMBER),
                FunctionParameter("b", PrimitiveType.BOOLEAN, optional = true)
            ),
            returnType = PrimitiveType.NIL
        )
        assertTrue(fewerRequired.isAssignableFrom(extraOptional))
        assertFalse(extraOptional.isAssignableFrom(fewerRequired))
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

    @Test
    fun rejectsUnsafeJavaCharNumericWidenings() {
        // char is unsigned 16-bit: byte -> char and char -> short reinterpret bits.
        assertFalse(JavaPrimitiveType.CHAR.isAssignableFrom(JavaPrimitiveType.BYTE))
        assertFalse(JavaPrimitiveType.SHORT.isAssignableFrom(JavaPrimitiveType.CHAR))
        // Guard: ordinary integral widening still holds.
        assertTrue(JavaPrimitiveType.INT.isAssignableFrom(JavaPrimitiveType.SHORT))
    }

    @Test
    fun normalizationKeepsJavaSignatureMetadata() {
        val metadata = JavaSignatureMetadata(isVarArgs = true)
        val overload = JavaOverloadType(
            javaName = JavaTypeName(simpleNames = listOf("Util")),
            overloadName = "format",
            callSignatures = listOf(FunctionType(returnType = PrimitiveType.STRING)),
            signatureMetadata = listOf(metadata)
        )

        val normalized = assertIs<JavaOverloadType>(TypeNormalizer.normalize(overload))
        assertEquals(listOf(metadata), normalized.signatureMetadata)
    }

    @Test
    fun normalizerCollapsesExactDuplicateOverloadSignatures() {
        val normalized = TypeNormalizer.normalize(
            OverloadedFunctionType(
                listOf(
                    FunctionType(
                        parameters = listOf(FunctionParameter("value", PrimitiveType.NUMBER)),
                        returnType = PrimitiveType.STRING
                    ),
                    FunctionType(
                        parameters = listOf(FunctionParameter("value", PrimitiveType.NUMBER)),
                        returnType = PrimitiveType.STRING
                    )
                )
            )
        )

        val function = assertIs<FunctionType>(normalized)
        assertEquals(PrimitiveType.STRING, function.returnType)
    }

    @Test
    fun normalizerCollapsesOverloadSignaturesSubsumedByEarlierKeptOnes() {
        val general = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.ANY)),
            returnType = PrimitiveType.STRING
        )
        val specific = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.NUMBER)),
            returnType = PrimitiveType.STRING
        )

        val normalized = TypeNormalizer.normalize(OverloadedFunctionType(listOf(general, specific)))

        // fun(value: any) is declared first and already accepts every call that
        // fun(value: number) could handle, so the later signature is dropped.
        val function = assertIs<FunctionType>(normalized)
        assertEquals(listOf(FunctionParameter("value", PrimitiveType.ANY)), function.parameters)
    }

    @Test
    fun customTypeAndClassTypeInteroperateByName() {
        assertTrue(CustomType("UserId").isAssignableFrom(ClassType("UserId")))
        assertTrue(ClassType("UserId").isAssignableFrom(CustomType("UserId")))
        assertFalse(ClassType("UserId").isAssignableFrom(CustomType("Other")))
        // Generic class targets still require structural type-parameter checks.
        assertFalse(
            ClassType("UserId", typeParameters = listOf(TypeParameterType("T")))
                .isAssignableFrom(CustomType("UserId"))
        )
    }

    @Test
    fun nonVarargSourceServesVarargTargetByDroppingTrailingArguments() {
        val varargTarget = listOf(
            FunctionParameter("first", PrimitiveType.STRING),
            FunctionParameter("rest", PrimitiveType.NUMBER, vararg = true)
        )
        val varargSource = listOf(
            FunctionParameter("first", PrimitiveType.STRING),
            FunctionParameter("rest", PrimitiveType.NUMBER, vararg = true)
        )
        // Regression: a plain non-vararg handler CAN serve a vararg target — Lua simply
        // drops the extra arguments at the call site.
        val plainSource = listOf(FunctionParameter("first", PrimitiveType.STRING))
        val requiredTrailingTarget = listOf(
            FunctionParameter("first", PrimitiveType.STRING),
            FunctionParameter("second", PrimitiveType.NUMBER)
        )
        val optionalTrailingTarget = listOf(
            FunctionParameter("first", PrimitiveType.STRING),
            FunctionParameter("second", PrimitiveType.NUMBER, optional = true)
        )

        assertTrue(TypeRelations.parameterListsCompatible(varargTarget, varargSource))
        assertTrue(TypeRelations.parameterListsCompatible(varargTarget, plainSource))
        // A REQUIRED (non-optional, non-vararg) trailing target slot cannot be dropped.
        assertFalse(TypeRelations.parameterListsCompatible(requiredTrailingTarget, plainSource))
        // Arity containment still holds without a target vararg: a target callable with
        // two arguments needs a source that can take both, optional trailing or not.
        assertFalse(TypeRelations.parameterListsCompatible(optionalTrailingTarget, plainSource))
        // ...but past a target vararg the optional/vararg slots are droppable.
        val optionalAndVarargTarget = listOf(
            FunctionParameter("first", PrimitiveType.STRING),
            FunctionParameter("second", PrimitiveType.NUMBER, optional = true),
            FunctionParameter("rest", PrimitiveType.NUMBER, vararg = true)
        )
        assertTrue(TypeRelations.parameterListsCompatible(optionalAndVarargTarget, plainSource))
        val requiredInsideVarargTarget = listOf(
            FunctionParameter("first", PrimitiveType.STRING),
            FunctionParameter("second", PrimitiveType.NUMBER),
            FunctionParameter("rest", PrimitiveType.NUMBER, vararg = true)
        )
        assertFalse(TypeRelations.parameterListsCompatible(requiredInsideVarargTarget, plainSource))
    }

    @Test
    fun javaObjectParametersAreBivariantForHandlerAssignability() {
        val view = JavaClassType(
            javaName = JavaTypeName(packageName = "android.view", simpleNames = listOf("View"))
        )
        val button = JavaClassType(
            javaName = JavaTypeName(packageName = "android.widget", simpleNames = listOf("Button")),
            superClass = view
        )
        // Android listener slot: interface declares fun onClick(v: View); the Lua handler
        // annotates the concrete widget it manipulates: fun(v: Button).
        val listenerParams = listOf(FunctionParameter("v", JavaInstanceType(view)))
        val handlerParams = listOf(FunctionParameter("v", JavaInstanceType(button)))

        assertTrue(
            TypeRelations.parameterListsCompatible(listenerParams, handlerParams),
            "handler param `Button` must serve listener param `View`"
        )
        // Bivariance: the reverse parameter direction also stays acceptable.
        assertTrue(
            TypeRelations.parameterListsCompatible(handlerParams, listenerParams),
            "Java object parameters must accept either direction"
        )
    }

    @Test
    fun primitiveAndLiteralParametersKeepStrictContravariance() {
        // Handler param "x" (narrower literal) cannot serve a string-parameter target.
        val literalTarget = listOf(FunctionParameter("value", PrimitiveType.STRING))
        val literalSource = listOf(FunctionParameter("value", LiteralType("x", PrimitiveType.STRING)))
        assertFalse(TypeRelations.parameterListsCompatible(literalTarget, literalSource))
        assertTrue(TypeRelations.parameterListsCompatible(literalSource, literalTarget))

        // Java primitives keep the widening-only contravariant direction; no bivariance.
        // isAssignable(long, int) holds, so a fun(v: long) source can serve a fun(v: int)
        // target, while fun(v: int) cannot serve fun(v: long).
        val intParam = listOf(FunctionParameter("v", JavaPrimitiveType.INT))
        val longParam = listOf(FunctionParameter("v", JavaPrimitiveType.LONG))
        assertTrue(TypeRelations.parameterListsCompatible(intParam, longParam))
        assertFalse(TypeRelations.parameterListsCompatible(longParam, intParam))

        // Lua-side primitives/literals stay strictly directional as well.
        val numberParam = listOf(FunctionParameter("v", PrimitiveType.NUMBER))
        val numberLiteralParam = listOf(FunctionParameter("v", LiteralType(1, PrimitiveType.NUMBER)))
        assertTrue(TypeRelations.parameterListsCompatible(numberLiteralParam, numberParam))
        assertFalse(TypeRelations.parameterListsCompatible(numberParam, numberLiteralParam))
    }

    @Test
    fun luaClassParametersAreBivariantForHandlerAssignability() {
        val animalParam = listOf(FunctionParameter("value", ClassType("Animal")))
        val dogParam = listOf(FunctionParameter("value", ClassType("Dog", superClass = ClassType("Animal"))))

        assertTrue(TypeRelations.parameterListsCompatible(animalParam, dogParam))
        // Bivariance accepts the runtime-loose direction too (handler narrows the target).
        assertTrue(TypeRelations.parameterListsCompatible(dogParam, animalParam))
        // Unrelated classes still fail both directions.
        val unrelatedParam = listOf(FunctionParameter("value", ClassType("Stranger")))
        assertFalse(TypeRelations.parameterListsCompatible(animalParam, unrelatedParam))
    }

    @Test
    fun classDeclarationIdentityGatesSameNameMatching() {
        // Same name from DISTINCT binder declarations: identity matching rejects.
        assertFalse(ClassType("Foo", declarationId = 1).isAssignableFrom(ClassType("Foo", declarationId = 2)))
        // Both sides synthetic/bridged (null id): name-only matching still holds.
        assertTrue(ClassType("Foo").isAssignableFrom(ClassType("Foo")))
        // One side carries no id (overlay/legacy path): identity matching disabled.
        assertTrue(ClassType("Foo", declarationId = 1).isAssignableFrom(ClassType("Foo")))
        assertTrue(ClassType("Foo").isAssignableFrom(ClassType("Foo", declarationId = 2)))
        // Same declaration on both sides: assignable.
        assertTrue(ClassType("Foo", declarationId = 1).isAssignableFrom(ClassType("Foo", declarationId = 1)))
    }

    @Test
    fun superTypeFallbackReachesAncestorWhenSuperClassMissing() {
        // Regression: TypeResolver materializes superClass only for a real class parent;
        // when it stays null the raw superType edge must still be walked.
        val base = ClassType("Base")
        val child = ClassType("Child", superType = base)

        assertTrue(base.isAssignableFrom(child), "superType fallback must reach Base from Child")
        assertFalse(base.isAssignableFrom(ClassType("Child")))
    }

    @Test
    fun superTypeCycleTerminates() {
        // A superType pointing back at the declaring name (CustomType placeholder loop)
        // must terminate instead of walking forever.
        val child = ClassType("Child", superType = CustomType("Child"))
        assertFalse(ClassType("Base").isAssignableFrom(child))

        // Genuine ClassType cycle: the visited set blocks the self-referential superType edge.
        val looping = ClassType("Loop", superType = ClassType("Loop"))
        assertFalse(ClassType("Base").isAssignableFrom(looping))
        // Self-match still succeeds despite the cycle (matched before revisiting).
        assertTrue(ClassType("Loop").isAssignableFrom(looping))
    }
}
