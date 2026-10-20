package semantic.types.resolve

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolutionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeResolutionContextTest {

    private val parser = LuaParser()

    @Test
    fun classScopeSeesOwnTypeParametersAndOuterNamedTypes() {
        val result = bind(
            """
            ---@alias Name string
            ---@class Widget<T>
            """.trimIndent()
        )
        val classDeclaration = result.declarationIndex.declarations.single { it.kind == DeclarationKind.CLASS }
        val context = TypeResolutionContext.forDeclaration(classDeclaration.id, result)

        assertEquals(listOf("T"), context.lookupTypeParameter("T").map { it.name })
        assertEquals(listOf("Name"), context.lookupNamedType("Name").map { it.name })
        assertEquals(listOf("Widget"), context.lookupNamedType("Widget").map { it.name })
    }

    @Test
    fun functionGenericScopeShadowsOuterNamedTypes() {
        val result = bind(
            """
            ---@alias T string

            ---@generic T
            local function build(value)
                return value
            end
            """.trimIndent()
        )
        val functionDeclaration = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.FUNCTION && it.origin != DeclarationOrigin.BUILTIN
        }
        val context = TypeResolutionContext.forDeclaration(functionDeclaration.id, result)

        assertEquals(
            listOf(DeclarationKind.TYPE_PARAMETER, DeclarationKind.TYPE_ALIAS),
            context.lookup("T").map { it.kind }
        )
    }

    @Test
    fun lexicalScopeLookupReturnsOnlyClassesAndAliases() {
        val source = """
            ---@alias Widget string
            local Widget = {}

            local function build(value)
                local T = value
                return T
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val lexicalContext = TypeResolutionContext.forLexicalScope(
            scopeId = result.scopeGraph.getScope(function.body!!)?.id!!,
            binder = result
        )

        assertEquals(listOf(DeclarationKind.TYPE_ALIAS), lexicalContext.lookup("Widget").map { it.kind })
        assertTrue(lexicalContext.lookup("T").isEmpty())
        assertTrue(lexicalContext.lookup("print").isEmpty())
    }

    @Test
    fun duplicateNamedTypesInSameScopeArePreserved() {
        val result = bind(
            """
            ---@alias Name string
            ---@alias Name number
            ---@class Name
            """.trimIndent()
        )
        val context = TypeResolutionContext.forLexicalScope(result.scopeGraph.rootScope.id, result)

        assertEquals(
            listOf(DeclarationKind.TYPE_ALIAS, DeclarationKind.TYPE_ALIAS, DeclarationKind.CLASS),
            context.lookup("Name").map { it.kind }
        )
    }

    @Test
    fun memberAndAttachedFunctionContextsResolveAgainstOwningDeclarationScope() {
        val result = bind(
            """
            ---@class Widget<T>
            ---@field value T
            ---@method Widget:render(arg T): T

            ---@generic U
            local function build(value)
                local U = value
                return U
            end
            """.trimIndent()
        )
        val declarations = result.declarationIndex.declarations.filter { it.origin != DeclarationOrigin.BUILTIN }
        val fieldDeclaration = declarations.single { it.kind == DeclarationKind.FIELD }
        val methodDeclaration = declarations.single { it.kind == DeclarationKind.METHOD }
        val functionDeclaration = declarations.single { it.kind == DeclarationKind.FUNCTION }

        val fieldContext = TypeResolutionContext.forDeclaration(fieldDeclaration.id, result)
        val methodContext = TypeResolutionContext.forDeclaration(methodDeclaration.id, result)
        val functionContext = TypeResolutionContext.forDeclaration(functionDeclaration.id, result)

        assertEquals(listOf(DeclarationKind.TYPE_PARAMETER), fieldContext.lookup("T").map { it.kind })
        assertEquals(listOf(DeclarationKind.TYPE_PARAMETER), methodContext.lookup("T").map { it.kind })
        assertEquals(listOf(DeclarationKind.TYPE_PARAMETER), functionContext.lookup("U").map { it.kind })
    }

    @Test
    fun nonGenericOwnedDeclarationsFallBackToOwnerVisibilityScope() {
        val result = bind(
            """
            ---@alias Name string
            ---@class Widget
            ---@field value Name
            """.trimIndent()
        )
        val fieldDeclaration = result.declarationIndex.declarations.single { it.kind == DeclarationKind.FIELD }
        val fieldContext = TypeResolutionContext.forDeclaration(fieldDeclaration.id, result)

        assertEquals(listOf(DeclarationKind.TYPE_ALIAS), fieldContext.lookupNamedType("Name").map { it.kind })
    }

    private fun bind(source: String) = parser.parse(source).let { chunk ->
        BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
    }
}
