package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.syntax.IndexTableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinderPassDocDeclarationTest {

    private val parser = LuaParser()

    @Test
    fun bindsOrphanAliasDeclarationWithParsedTypeSyntax() {
        val chunk = parser.parse(
            """
            ---@alias Name string
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val alias = result.declarationIndex.declarations.single { it.kind == DeclarationKind.TYPE_ALIAS }

        assertEquals("Name", alias.name)
        assertEquals(DeclarationOwner.Root, alias.owner)
        assertEquals(DeclarationOrigin.DOC_COMMENT, alias.origin)
        assertIs<NamedTypeSyntax>(alias.declaredTypeSyntax)
        assertNotNull(alias.range)
    }

    @Test
    fun bindsGenericAliasOwnedTypeParametersWithoutLeaking() {
        val chunk = parser.parse(
            """
            ---@alias Mapper<T> fun(value: T): T
            ---@alias Pair<L, R> { left: L, right: R }
            ---@class Box<U>
            ---@field value U
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val mapper = result.declarationIndex.declarations.single { it.kind == DeclarationKind.TYPE_ALIAS && it.name == "Mapper" }
        val pair = result.declarationIndex.declarations.single { it.kind == DeclarationKind.TYPE_ALIAS && it.name == "Pair" }
        val box = result.declarationIndex.declarations.single { it.kind == DeclarationKind.CLASS && it.name == "Box" }

        assertEquals(listOf("T"), ownedNames(result, mapper.id, DeclarationKind.TYPE_PARAMETER))
        assertEquals(listOf("L", "R"), ownedNames(result, pair.id, DeclarationKind.TYPE_PARAMETER))
        assertEquals(listOf("U"), ownedNames(result, box.id, DeclarationKind.TYPE_PARAMETER))
    }

    @Test
    fun bindsOrphanClassFieldMethodAndTypeParameterDeclarations() {
        val chunk = parser.parse(
            """
            ---@class Widget<T>: Base
            ---@field id integer
            ---@method Widget:render(value string): boolean
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val declarations = result.declarationIndex.declarations.filter { it.origin != DeclarationOrigin.BUILTIN }

        val klass = declarations.single { it.kind == DeclarationKind.CLASS }
        val typeParameter = declarations.single { it.kind == DeclarationKind.TYPE_PARAMETER }
        val field = declarations.single { it.kind == DeclarationKind.FIELD }
        val method = declarations.single { it.kind == DeclarationKind.METHOD }

        assertEquals(DeclarationOwner.Root, klass.owner)
        assertEquals(DeclarationOwner.Declaration(klass.id), typeParameter.owner)
        assertEquals(DeclarationOwner.Declaration(klass.id), field.owner)
        assertEquals(DeclarationOwner.Declaration(klass.id), method.owner)
        assertNotNull(typeParameter.range)
        assertNotNull(field.range)
        assertNotNull(method.range)
        assertIs<NamedTypeSyntax>(field.declaredTypeSyntax)
        assertNull(method.declaredTypeSyntax)
    }

    @Test
    fun bindsFieldMethodAndGenericTagsToNearestClassOnly() {
        val chunk = parser.parse(
            """
            ---@class First<T>
            ---@field firstValue T
            ---@class Second<U>
            ---@generic U: string
            ---@field secondValue U
            ---@method Second:get(): U
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val declarations = result.declarationIndex.declarations.filter { it.origin != DeclarationOrigin.BUILTIN }
        val first = declarations.single { it.kind == DeclarationKind.CLASS && it.name == "First" }
        val second = declarations.single { it.kind == DeclarationKind.CLASS && it.name == "Second" }

        assertEquals(listOf("T"), ownedNames(result, first.id, DeclarationKind.TYPE_PARAMETER))
        assertEquals(listOf("firstValue"), ownedNames(result, first.id, DeclarationKind.FIELD))
        assertEquals(emptyList(), ownedNames(result, first.id, DeclarationKind.METHOD))

        assertEquals(listOf("U"), ownedNames(result, second.id, DeclarationKind.TYPE_PARAMETER))
        assertEquals(listOf("secondValue"), ownedNames(result, second.id, DeclarationKind.FIELD))
        assertEquals(listOf("get"), ownedNames(result, second.id, DeclarationKind.METHOD))
    }

    @Test
    fun mergesClassHeaderTypeParameterWithGenericConstraint() {
        val chunk = parser.parse(
            """
            ---@class Box<T>
            ---@generic T: string
            ---@field value T
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val box = result.declarationIndex.declarations.single { it.kind == DeclarationKind.CLASS && it.name == "Box" }
        val typeParameters = result.declarationIndex.getOwnedDeclarations(DeclarationOwner.Declaration(box.id))
            .filter { it.kind == DeclarationKind.TYPE_PARAMETER }

        assertEquals(1, typeParameters.size)
        assertEquals("T", typeParameters.single().name)
        assertIs<NamedTypeSyntax>(typeParameters.single().declaredTypeSyntax)
        assertEquals("string", (typeParameters.single().declaredTypeSyntax as NamedTypeSyntax).name)
    }

    @Test
    fun supportsMultipleClassBlocksWithoutDuplicatingMembers() {
        val chunk = parser.parse(
            """
            ---@class Alpha
            ---@field id string
            ---@class Beta
            ---@field value number
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val alpha = result.declarationIndex.declarations.single { it.kind == DeclarationKind.CLASS && it.name == "Alpha" }
        val beta = result.declarationIndex.declarations.single { it.kind == DeclarationKind.CLASS && it.name == "Beta" }

        assertEquals(listOf("id"), ownedNames(result, alpha.id, DeclarationKind.FIELD))
        assertEquals(listOf("value"), ownedNames(result, beta.id, DeclarationKind.FIELD))
        assertEquals(2, result.declarationIndex.declarations.count { it.kind == DeclarationKind.FIELD })
    }

    @Test
    fun classMethodOverloadsAttachOnlyToNearestMethod() {
        val chunk = parser.parse(
            """
            ---@class Widget
            ---@method Widget:first(): string
            ---@overload fun(self: Widget, value: string): string
            ---@method Widget:second(): number
            ---@overload fun(self: Widget, value: number): number
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val first = result.declarationIndex.declarations.single { it.kind == DeclarationKind.METHOD && it.name == "first" }
        val second = result.declarationIndex.declarations.single { it.kind == DeclarationKind.METHOD && it.name == "second" }

        assertEquals(2, first.documentation?.docComment?.tags?.size)
        assertEquals(2, second.documentation?.docComment?.tags?.size)
        assertEquals("method", first.documentation?.docComment?.tags?.first()?.tagName)
        assertEquals("overload", first.documentation?.docComment?.tags?.last()?.tagName)
        assertEquals("method", second.documentation?.docComment?.tags?.first()?.tagName)
        assertEquals("overload", second.documentation?.docComment?.tags?.last()?.tagName)
    }

    @Test
    fun bindsFunctionDocsAndGenericTypeParametersWithoutSyntheticParamTags() {
        val chunk = parser.parse(
            """
            --- function docs
            ---@generic T
            ---@param value T
            ---@return T
            local function render(value)
                return value
            end
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val declarations = result.declarationIndex.declarations.filter { it.origin != DeclarationOrigin.BUILTIN }
        val function = declarations.single { it.kind == DeclarationKind.FUNCTION }

        assertEquals("function docs", function.documentation?.docComment?.description)
        val typeParameter = declarations.single {
            it.kind == DeclarationKind.TYPE_PARAMETER && it.owner == DeclarationOwner.Declaration(function.id)
        }
        assertNotNull(typeParameter.range)
        assertEquals(1, declarations.count { it.kind == DeclarationKind.PARAMETER && it.owner == DeclarationOwner.Declaration(function.id) })
    }

    @Test
    fun bindsAttachedClassAlongsideAstValueDeclaration() {
        val chunk = parser.parse(
            """
            ---@class Widget
            local value = {}
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val declarations = result.declarationIndex.declarations.filter { it.origin != DeclarationOrigin.BUILTIN }

        assertEquals(1, declarations.count { it.kind == DeclarationKind.LOCAL && it.name == "value" })
        assertEquals(1, declarations.count { it.kind == DeclarationKind.CLASS && it.name == "Widget" && it.owner == DeclarationOwner.Root })
    }

    @Test
    fun leavesMultiLocalTypeMappingUnresolvedWithoutCrashing() {
        val chunk = parser.parse(
            """
            ---@type string, number
            local first, second = "a", 1
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val locals = result.declarationIndex.declarations.filter { it.kind == DeclarationKind.LOCAL }

        assertEquals(2, locals.size)
        assertTrue(locals.all { it.declaredTypeSyntax == null })
        assertTrue(locals.all { it.documentation != null })
    }

    @Test
    fun preservesSingleLocalTypeSyntaxAndUsesLocalStatementInitNames() {
        val chunk = parser.parse(
            """
            ---@type table<string, number>
            local value = {}

            repeat
                local callback = function(item)
                    return item
                end
            until true
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val valueDeclaration = result.declarationIndex.declarations.single { it.kind == DeclarationKind.LOCAL && it.name == "value" }
        val callbackDeclaration = result.declarationIndex.declarations.single { it.kind == DeclarationKind.LOCAL && it.name == "callback" }

        assertIs<IndexTableTypeSyntax>(valueDeclaration.declaredTypeSyntax)
        assertNull(result.declarationIndex.declarations.find { it.kind == DeclarationKind.LOCAL && it.name == "{}" })
        assertNotNull(callbackDeclaration)
        assertTrue(result.scopeGraph.scopes.any { it.kind.name == "LOOP" })
    }
}

private fun ownedNames(
    result: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult,
    ownerId: io.github.dingyi222666.luaparser.semantic.binder.DeclarationId,
    kind: DeclarationKind
): List<String> {
    return result.declarationIndex.getOwnedDeclarations(DeclarationOwner.Declaration(ownerId))
        .filter { it.kind == kind }
        .map { it.name }
}
