package semantic.types.resolve

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DocTypeResolutionTest {

    private val parser = LuaParser()

    @Test
    fun writesResolvedTypeTagBackToSingleLocal() {
        val result = bindAndResolve(
            """
            ---@class Foo
            ---@type Foo
            local value = {}
            """.trimIndent()
        )

        val local = result.declarationIndex.declarations.single { it.kind == DeclarationKind.LOCAL && it.name == "value" }
        val type = assertIs<ClassType>(local.declaredType)
        assertEquals("Foo", type.name)
        assertEquals("Foo", local.documentation?.resolvedInlineType?.name)
    }

    @Test
    fun composesFunctionTypesFromGenericParamAndReturnDocs() {
        val result = bindAndResolve(
            """
            ---@class Base

            ---@generic T: Base
            ---@param value T
            ---@return T, nil
            local function build(value)
                return value
            end
            """.trimIndent()
        )

        val declarations = result.declarationIndex.declarations.filter { it.origin != DeclarationOrigin.BUILTIN }
        val function = declarations.single { it.kind == DeclarationKind.FUNCTION }
        val parameters = declarations.filter { it.kind == DeclarationKind.PARAMETER }
        val valueParameter = parameters.single { it.name == "value" }
        val typeParameter = declarations.single { it.kind == DeclarationKind.TYPE_PARAMETER && it.name == "T" }

        val resolvedTypeParameter = assertIs<TypeParameterType>(typeParameter.declaredType)
        assertEquals("Base", resolvedTypeParameter.constraint?.name)
        assertEquals("T", valueParameter.declaredType?.name)

        val functionType = assertIs<FunctionType>(function.declaredType)
        val returnType = assertIs<MultiReturnType>(functionType.returnType)
        assertEquals(listOf("T", "nil"), returnType.types.map { it.name })
        assertEquals(setOf("value"), function.documentation?.resolvedParameterTypes?.keys)
        assertEquals(listOf("T", "nil"), function.documentation?.resolvedReturnTypes?.map { it.name })
    }

    @Test
    fun resolvesAstColonMethodDocsIntoDeclaredCallableSurface() {
        val result = bindAndResolve(
            """
            local box = {}
            ---@param self table
            ---@param value number
            ---@param label string
            ---@return string
            function box:render(value, label)
                return label
            end
            """.trimIndent()
        )

        val method = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.METHOD && it.name == "render"
        }
        val methodType = assertIs<FunctionType>(method.declaredType)

        assertEquals(listOf("self", "value", "label"), methodType.parameters.map { it.name })
        assertEquals(listOf("table", "number", "string"), methodType.parameters.map { it.type.displayName })
        assertEquals("string", methodType.returnType.displayName)
        assertEquals(setOf("self", "value", "label"), method.documentation?.resolvedParameterTypes?.keys)
    }

    @Test
    fun resolvesAliasClassFieldsAndMethodDocForms() {
        val result = bindAndResolve(
            """
            ---@alias Name string | number
            ---@class Base
            ---@field baseId integer

            ---@class Widget<T>: Base
            ---@field id integer
            ---@field label? string
            ---@method Widget.render fun(self: Widget<T>, value: string): boolean
            ---@method Widget:copy(value string): boolean

            ---@class AttachedOnly
            local value = {}
            """.trimIndent()
        )

        val alias = assertIs<AliasType>(result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.TYPE_ALIAS && it.name == "Name"
        }.declaredType)
        assertNotNull(alias.target)

        val widgetDeclaration = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.CLASS && it.name == "Widget"
        }
        val widget = assertIs<ClassType>(widgetDeclaration.declaredType)
        assertEquals("Base", widgetDeclaration.documentation?.resolvedParentType?.name)
        assertEquals("Base", widget.superClass?.name)
        assertEquals(setOf("id", "label"), widget.fields.keys)
        assertEquals(setOf("render", "copy"), widget.methods.keys)
        assertEquals(PrimitiveType.NUMBER, widget.fields.getValue("id"))
        assertEquals("string | nil", widget.fields.getValue("label").displayName)

        val copyMethod = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.METHOD && it.name == "copy"
        }
        assertIs<FunctionType>(copyMethod.declaredType)

        val localValue = result.declarationIndex.declarations.single { it.kind == DeclarationKind.LOCAL && it.name == "value" }
        assertNull(localValue.declaredType)
    }

    @Test
    fun storesResolvedOverloadDocsOnDeclarations() {
        val result = bindAndResolve(
            """
            ---@overload fun(value: string): string
            ---@param value number
            ---@return number
            local function normalize(value)
                return value
            end

            ---@class Widget
            ---@method Widget:pick(): number
            ---@overload fun(self: Widget, value: string): string
            """.trimIndent()
        )

        val function = result.declarationIndex.declarations.single { it.kind == DeclarationKind.FUNCTION && it.name == "normalize" }
        assertEquals(1, function.documentation?.resolvedOverloadTypes?.size)
        assertSame(PrimitiveType.STRING, function.documentation?.resolvedOverloadTypes?.single()?.returnType)

        val method = result.declarationIndex.declarations.single { it.kind == DeclarationKind.METHOD && it.name == "pick" }
        val methodType = assertIs<OverloadedFunctionType>(method.declaredType)
        assertEquals(2, methodType.callSignatures.size)
    }

    @Test
    fun duplicateSameScopeNamedTypesResolveToLastDeclaration() {
        val result = bindAndResolve(
            """
            ---@alias Name string
            ---@alias Name number
            ---@type Name
            local value = 1
            """.trimIndent()
        )

        val local = result.declarationIndex.declarations.single { it.kind == DeclarationKind.LOCAL }
        val resolvedAlias = assertIs<AliasType>(local.declaredType)
        assertSame(PrimitiveType.NUMBER, resolvedAlias.target)
    }

    @Test
    fun resolvesGenericSuperclassApplicationIntoClassSuperClass() {
        val result = bindAndResolve(
            """
            ---@class Base<T>
            ---@field value T

            ---@class Box<T>: Base<T>
            ---@field own T
            """.trimIndent()
        )

        val boxDeclaration = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.CLASS && it.name == "Box"
        }
        val box = assertIs<ClassType>(boxDeclaration.declaredType)

        assertEquals("Base<T>", assertIs<AppliedType>(box.superType).displayName)
        assertNotNull(box.superClass)
        assertEquals("T", box.superClass!!.fields.getValue("value").name)
    }

    @Test
    fun resolvesAliasWrappedSuperclassApplication() {
        val result = bindAndResolve(
            """
            ---@class Base<T>
            ---@field value T
            ---@alias StringBase Base<string>

            ---@class Child: StringBase
            """.trimIndent()
        )

        val childDeclaration = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.CLASS && it.name == "Child"
        }
        val child = assertIs<ClassType>(childDeclaration.declaredType)

        assertEquals("StringBase", childDeclaration.documentation?.resolvedParentType?.name)
        assertSame(PrimitiveType.STRING, child.superClass!!.fields.getValue("value"))
    }

    @Test
    fun storesResolvedRawParentTypeAlongsideMaterializedSuperClass() {
        val result = bindAndResolve(
            """
            ---@alias ParentRef string
            ---@class Child: ParentRef
            """.trimIndent()
        )

        val childDeclaration = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.CLASS && it.name == "Child"
        }
        val child = assertIs<ClassType>(childDeclaration.declaredType)

        assertIs<AliasType>(child.superType)
        assertNull(child.superClass)
        assertIs<AliasType>(childDeclaration.documentation?.resolvedParentType)
    }

    private fun bindAndResolve(source: String) = parser.parse(source).let { chunk ->
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        TypeResolver().resolve(binder)
    }
}
