package semantic.types.resolve

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class TypeResolverTest {

    private val parser = LuaParser()

    @Test
    fun resolvesPrimitiveNamesShadowingAndUnknownFallbacks() {
        val result = bindAndResolve(
            """
            ---@alias Name string
            ---@alias Name bool
            ---@alias Int integer
            ---@alias Void void
            ---@alias Mystery unknown
            ---@alias Stop never

            ---@generic T: Name
            ---@param value T
            local function build(value)
                return value
            end

            ---@alias MissingRef Missing.Type
            """.trimIndent()
        )

        assertSame(PrimitiveType.NUMBER, alias(result, "Int").target)
        assertSame(PrimitiveType.NIL, alias(result, "Void").target)
        assertSame(UnknownType, alias(result, "Mystery").target)
        assertSame(NeverType, alias(result, "Stop").target)
        assertIs<CustomType>(alias(result, "MissingRef").target)

        val function = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.FUNCTION && it.origin != DeclarationOrigin.BUILTIN
        }
        val typeParameter = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.TYPE_PARAMETER && it.name == "T"
        }
        val constraint = assertIs<TypeParameterType>(typeParameter.declaredType).constraint
        val constraintAlias = assertIs<AliasType>(constraint)
        assertSame(PrimitiveType.BOOLEAN, constraintAlias.target)

        val functionType = assertIs<FunctionType>(function.declaredType)
        assertEquals("T", functionType.parameters.single().type.name)
    }

    @Test
    fun resolvesForwardAliasesAndAliasCycles() {
        val result = bindAndResolve(
            """
            ---@alias Forward Later
            ---@alias Later string
            ---@alias A B
            ---@alias B A
            """.trimIndent()
        )

        val forward = alias(result, "Forward")
        assertEquals("Later", assertIs<AliasType>(forward.target).name)
        assertSame(PrimitiveType.STRING, alias(result, "Later").target)

        assertSame(ErrorType, alias(result, "A").target)
        assertSame(ErrorType, alias(result, "B").target)
    }

    @Test
    fun resolvesClassesGenericsAndCompositeSyntax() {
        val result = bindAndResolve(
            """
            ---@class Base
            ---@field baseId integer

            ---@class Widget<T>: Base
            ---@java-class java.util.ArrayList
            ---@field id integer
            ---@field value T
            ---@method Widget.render fun(self: Widget<T>, value: string): boolean

            ---@alias Indexed table<string, number>
            ---@alias Applied Result<string>
            ---@alias ArrayAlias string[]
            ---@alias TupleAlias [number, boolean]
            ---@alias ObjectAlias { id: integer, extra?: string, [string]: number, [integer]: boolean }
            ---@alias MultiAlias string, nil
            ---@alias FunctionAlias fun<T>(value: T...): T
            """.trimIndent()
        )

        val widget = assertIs<ClassType>(result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.CLASS && it.name == "Widget"
        }.declaredType)
        assertEquals("Base", widget.superClass?.name)
        assertSame(PrimitiveType.NUMBER, widget.fields.getValue("id"))
        assertEquals("T", widget.fields.getValue("value").name)
        assertEquals("render", widget.methods.keys.single())
        assertEquals("T", widget.typeParameters.single().name)
        assertEquals("java.util.ArrayList", widget.javaClassName)

        val indexed = assertIs<TableType>(alias(result, "Indexed").target)
        assertSame(PrimitiveType.STRING, indexed.indexSignature?.keyType)
        assertSame(PrimitiveType.NUMBER, indexed.indexSignature?.valueType)

        val applied = assertIs<AppliedType>(alias(result, "Applied").target)
        assertEquals("Result", applied.baseName)
        assertSame(PrimitiveType.STRING, applied.typeArguments.single())

        assertSame(PrimitiveType.STRING, assertIs<ArrayType>(alias(result, "ArrayAlias").target).elementType)
        assertEquals(
            listOf(PrimitiveType.NUMBER, PrimitiveType.BOOLEAN),
            assertIs<TupleType>(alias(result, "TupleAlias").target).elementTypes
        )

        val objectAlias = assertIs<IntersectionType>(alias(result, "ObjectAlias").target)
        assertEquals(2, objectAlias.types.size)

        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.NIL),
            assertIs<MultiReturnType>(alias(result, "MultiAlias").target).types
        )

        val functionAlias = assertIs<FunctionType>(alias(result, "FunctionAlias").target)
        assertEquals("T", functionAlias.typeParameters.single().name)
        assertIs<VarargType>(functionAlias.parameters.single().type)
    }

    @Test
    fun resolvesGenericAliasOwnedTypeParametersAndDocOnlyMethodOverloads() {
        val result = bindAndResolve(
            """
            ---@alias Mapper<T> fun(value: T): T
            ---@class Widget
            ---@method Widget:map(): string
            ---@overload fun(self: Widget, value: string): string
            """.trimIndent()
        )

        val mapper = alias(result, "Mapper")
        val mapperTarget = assertIs<FunctionType>(mapper.target)
        assertEquals("T", mapperTarget.typeParameters.single().name)
        assertEquals("T", mapperTarget.parameters.single().type.name)
        assertEquals("T", mapperTarget.returnType.name)

        val method = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.METHOD && it.name == "map"
        }
        val overloaded = assertIs<OverloadedFunctionType>(method.declaredType)
        assertEquals(2, overloaded.callSignatures.size)
        assertEquals(1, method.documentation?.resolvedOverloadTypes?.size)
    }

    @Test
    fun resolvesMultiLevelInheritedGenericMembers() {
        val result = bindAndResolve(
            """
            ---@class Base<T>
            ---@field value T
            ---@method Base:get(): T

            ---@class Mid<T>: Base<T>
            ---@field midValue T

            ---@class Box<T>: Mid<T>
            ---@field ownValue T
            """.trimIndent()
        )

        val box = assertIs<ClassType>(result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.CLASS && it.name == "Box"
        }.declaredType)

        assertEquals(setOf("value", "midValue", "ownValue"), box.getAllFields().keys)
        assertEquals("T", box.getAllFields().getValue("value").name)
        assertEquals("T", (box.getAllMethods().getValue("get") as FunctionType).returnType.name)
    }

    @Test
    fun classHeaderAndGenericConstraintProduceSingleTypeParameter() {
        val result = bindAndResolve(
            """
            ---@class Box<T>
            ---@generic T: string
            ---@field value T
            """.trimIndent()
        )

        val box = assertIs<ClassType>(result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.CLASS && it.name == "Box"
        }.declaredType)

        assertEquals(1, box.typeParameters.size)
        assertSame(PrimitiveType.STRING, box.typeParameters.single().constraint)
    }

    @Test
    fun childMembersOverrideInheritedMembers() {
        val result = bindAndResolve(
            """
            ---@class Base
            ---@field value string
            ---@method Base:get(): string

            ---@class Child: Base
            ---@field value number
            ---@method Child:get(): number
            """.trimIndent()
        )

        val child = assertIs<ClassType>(result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.CLASS && it.name == "Child"
        }.declaredType)

        assertSame(PrimitiveType.NUMBER, child.getAllFields().getValue("value"))
        assertSame(PrimitiveType.NUMBER, (child.getAllMethods().getValue("get") as FunctionType).returnType)
    }

    private fun bindAndResolve(source: String) = parser.parse(source).let { chunk ->
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        TypeResolver().resolve(binder)
    }

    private fun alias(result: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult, name: String): AliasType {
        return assertIs<AliasType>(result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.TYPE_ALIAS && it.name == name
        }.declaredType)
    }
}
