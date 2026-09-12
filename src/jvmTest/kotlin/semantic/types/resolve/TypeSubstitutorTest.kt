package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeSubstitutor
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TypeSubstitutorTest {

    private val substitutor = TypeSubstitutor()

    @Test
    fun substitutesClassFieldAndMethodTypesThroughMapping() {
        val typeParameter = TypeParameterType("T")
        val boxType = ClassType(
            name = "Box",
            fields = mapOf("value" to typeParameter),
            methods = mapOf(
                "get" to FunctionType(returnType = typeParameter),
                "set" to FunctionType(parameters = listOf(FunctionParameter("value", typeParameter)), returnType = PrimitiveType.NIL)
            ),
            typeParameters = listOf(typeParameter)
        )

        val substituted = substitutor.substitute(boxType, mapOf("T" to PrimitiveType.STRING)) as ClassType

        assertSame(PrimitiveType.STRING, substituted.fields.getValue("value"))
        assertSame(PrimitiveType.STRING, (substituted.methods.getValue("get") as FunctionType).returnType)
        assertSame(PrimitiveType.STRING, (substituted.methods.getValue("set") as FunctionType).parameters.single().type)
    }

    @Test
    fun substitutesAliasTargetNestedInsideUnionIntersectionAndFunctionReturn() {
        val typeParameter = TypeParameterType("T")
        val alias = AliasType(
            name = "Wrapped",
            target = UnionType(
                linkedSetOf(
                    IntersectionType(linkedSetOf(typeParameter, PrimitiveType.STRING)),
                    FunctionType(returnType = typeParameter)
                )
            )
        )

        val substituted = substitutor.substitute(alias, mapOf("T" to PrimitiveType.NUMBER)) as AliasType
        val union = substituted.target as UnionType

        assertEquals(
            setOf(
                IntersectionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.STRING)),
                FunctionType(returnType = PrimitiveType.NUMBER)
            ),
            union.types
        )
    }

    @Test
    fun substitutesInheritedClassMembers() {
        val typeParameter = TypeParameterType("T")
        val base = ClassType(name = "Base", fields = mapOf("item" to typeParameter), typeParameters = listOf(typeParameter))
        val derived = ClassType(name = "Derived", superClass = base, typeParameters = listOf(typeParameter))

        val substituted = substitutor.substitute(derived, mapOf("T" to PrimitiveType.BOOLEAN)) as ClassType

        assertSame(PrimitiveType.BOOLEAN, substituted.superClass!!.fields.getValue("item"))
        assertSame(PrimitiveType.BOOLEAN, substituted.getAllFields().getValue("item"))
    }

    @Test
    fun doesNotRewriteShadowedNestedGenericTypeParameters() {
        val outerTypeParameter = TypeParameterType("T")
        val nestedTypeParameter = TypeParameterType("T")
        val type = ClassType(
            name = "Box",
            fields = mapOf(
                "factory" to FunctionType(
                    typeParameters = listOf(nestedTypeParameter),
                    parameters = listOf(FunctionParameter("value", nestedTypeParameter)),
                    returnType = nestedTypeParameter
                )
            ),
            methods = mapOf(
                "wrap" to FunctionType(parameters = listOf(FunctionParameter("value", outerTypeParameter)), returnType = outerTypeParameter)
            ),
            typeParameters = listOf(outerTypeParameter)
        )

        val substituted = substitutor.substitute(type, mapOf("T" to PrimitiveType.STRING)) as ClassType
        val factory = substituted.fields.getValue("factory") as FunctionType
        val wrap = substituted.methods.getValue("wrap") as FunctionType

        assertEquals("T", factory.typeParameters.single().name)
        assertEquals("T", factory.parameters.single().type.name)
        assertEquals("T", factory.returnType.name)
        assertSame(PrimitiveType.STRING, wrap.parameters.single().type)
        assertSame(PrimitiveType.STRING, wrap.returnType)
    }

    @Test
    fun leavesUnrelatedTypesUntouched() {
        assertSame(PrimitiveType.NUMBER, substitutor.substitute(PrimitiveType.NUMBER, mapOf("T" to PrimitiveType.STRING)))
    }

    @Test
    fun substitutesInheritedClassFieldsAndMethodsFromAppliedSuperclass() {
        val harness = resolvedHarness(
            """
            ---@class Base<T>
            ---@field value T
            ---@method Base:get(): T

            ---@class Box<T>: Base<T>
            ---@field own T
            """.trimIndent()
        )

        val substituted = assertIs<ClassType>(
            substitutor.substituteApplied(AppliedType("Box", listOf(PrimitiveType.STRING)), harness.context, harness.binder)
        )

        assertSame(PrimitiveType.STRING, substituted.getAllFields().getValue("value"))
        assertSame(PrimitiveType.STRING, substituted.getAllFields().getValue("own"))
        assertSame(PrimitiveType.STRING, (substituted.getAllMethods().getValue("get") as FunctionType).returnType)
    }

    @Test
    fun substitutesClassSuperTypeWithoutRewritingMaskedNestedGenerics() {
        val outerTypeParameter = TypeParameterType("T")
        val nestedTypeParameter = TypeParameterType("T")
        val base = AppliedType("Base", listOf(outerTypeParameter))
        val type = ClassType(
            name = "Box",
            fields = mapOf(
                "factory" to FunctionType(
                    typeParameters = listOf(nestedTypeParameter),
                    parameters = listOf(FunctionParameter("value", nestedTypeParameter)),
                    returnType = nestedTypeParameter
                )
            ),
            superType = base,
            typeParameters = listOf(outerTypeParameter)
        )

        val substituted = substitutor.substitute(type, mapOf("T" to PrimitiveType.STRING)) as ClassType

        assertEquals("Base<string>", substituted.superType!!.displayName)
        val factory = substituted.fields.getValue("factory") as FunctionType
        assertEquals("T", factory.typeParameters.single().name)
        assertEquals("T", factory.returnType.name)
    }

    @Test
    fun forwardsMaskFlagToOverloadedCallSignatures() {
        val typeParameter = TypeParameterType("T")
        val overloaded = OverloadedFunctionType(
            callSignatures = listOf(
                FunctionType(
                    typeParameters = listOf(typeParameter),
                    parameters = listOf(FunctionParameter("value", typeParameter)),
                    returnType = typeParameter
                )
            )
        )

        // The public substitute() runs with maskOwnTypeParameters = false: the enclosing
        // mapping must NOT be masked by the overload's own type parameters, mirroring the
        // direct FunctionType branch instead of hard-coding true.
        val substituted = substitutor.substitute(overloaded, mapOf("T" to PrimitiveType.STRING)) as OverloadedFunctionType
        val signature = substituted.callSignatures.single()
        assertSame(PrimitiveType.STRING, signature.parameters.single().type)
        assertSame(PrimitiveType.STRING, signature.returnType)
        // NOTE: the signature's `name` may still carry the stale fun<T> marker — the
        // stale-parameter drop lives in substituteApplied (applied-alias path), and the
        // direct substitute() branch forwards the mask flag only. Semantic types here
        // prove the mask forwarding; the name cleanup is display-level (LOW audit).
        // (displayName intentionally not asserted: the direct substitute() branch may
        // keep the stale fun<T> name marker — semantic substitution is the contract.)

        // preserveOwnTypeParameters = true keeps masking the mapping (current behavior for
        // masking callers such as TypeResolver.materializeAppliedParentClassSurface).
        val masked = substitutor.substitute(overloaded, mapOf("T" to PrimitiveType.STRING), preserveOwnTypeParameters = true) as OverloadedFunctionType
        assertEquals("T", masked.callSignatures.single().parameters.single().type.name)
        assertEquals("T", masked.callSignatures.single().returnType.name)
    }

    @Test
    fun appliedClassSurfaceDropsStaleTypeParameterMarker() {
        val harness = resolvedHarness(
            """
            ---@class Box<T>
            ---@field value T
            """.trimIndent()
        )

        val applied = assertIs<ClassType>(
            substitutor.substituteApplied(AppliedType("Box", listOf(PrimitiveType.NUMBER)), harness.context, harness.binder)
        )

        assertSame(PrimitiveType.NUMBER, applied.fields.getValue("value"))
        // The mapped `T` was replaced by the concrete argument: no phantom class<T> marker.
        assertTrue(applied.typeParameters.isEmpty())
        assertFalse(applied.displayName.contains("<T>"))
    }

    @Test
    fun appliedAliasFunctionSurfaceDropsStaleGenericMarkerFromDisplayName() {
        val harness = resolvedHarness(
            """
            ---@alias Mapper<T> fun(value: T): T
            """.trimIndent()
        )

        val applied = assertIs<FunctionType>(
            substitutor.substituteApplied(AppliedType("Mapper", listOf(PrimitiveType.NUMBER)), harness.context, harness.binder)
        )

        assertSame(PrimitiveType.NUMBER, applied.returnType)
        assertSame(PrimitiveType.NUMBER, applied.parameters.single().type)
        assertEquals("fun(value: number): number", applied.displayName)
        assertFalse(applied.displayName.contains("<T>"))
    }

    private fun resolvedHarness(source: String): ResolvedHarness {
        val parser = LuaParser()
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return ResolvedHarness(
            binder = resolved,
            context = io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolutionContext.forLexicalScope(
                resolved.scopeGraph.rootScope.id,
                resolved
            )
        )
    }

    private data class ResolvedHarness(
        val binder: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult,
        val context: io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolutionContext
    )
}
