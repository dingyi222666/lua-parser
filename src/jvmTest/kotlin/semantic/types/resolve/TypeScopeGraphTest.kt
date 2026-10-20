package semantic.types.resolve

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypeScopeGraphTest {

    private val parser = LuaParser()

    @Test
    fun mirrorsBinderLexicalScopeHierarchyForTypeScopes() {
        val result = bind(
            """
            do
                do
                    local value = 1
                end
            end
            """.trimIndent()
        )
        val outerDo = result.scopeGraph.scopes.first { it.kind.name == "BLOCK" }
        val innerDo = result.scopeGraph.scopes.last { it.kind.name == "BLOCK" }
        val outerTypeScope = assertNotNull(result.typeScopeGraph.getLexicalScope(outerDo.id))
        val innerTypeScope = assertNotNull(result.typeScopeGraph.getLexicalScope(innerDo.id))

        assertEquals(result.typeScopeGraph.rootScope.id, outerTypeScope.parentId)
        assertEquals(outerTypeScope.id, innerTypeScope.parentId)
    }

    @Test
    fun keepsOnlyNamedTypeDeclarationsInLexicalTypeScopes() {
        val result = bind(
            """
            ---@alias Name string
            ---@class Widget<T>
            ---@field value integer
            ---@method Widget:render(): boolean
            local Widget = {}
            """.trimIndent()
        )
        val rootDeclarations = result.typeScopeGraph
            .getDeclarations(result.typeScopeGraph.rootScope.id)

        assertEquals(listOf(DeclarationKind.TYPE_ALIAS, DeclarationKind.CLASS), rootDeclarations.map { it.kind })
        assertTrue(rootDeclarations.none { it.name == "print" })
        assertTrue(rootDeclarations.none { it.kind == DeclarationKind.LOCAL })
        assertTrue(rootDeclarations.none { it.kind == DeclarationKind.FIELD || it.kind == DeclarationKind.METHOD })
    }

    @Test
    fun createsDeclarationTypeScopesForClassAndFunctionGenericsOnly() {
        val result = bind(
            """
            ---@class Widget<T>
            ---@field value integer
            ---@method Widget:render(): boolean

            ---@generic U
            local function build(value)
                return value
            end
            """.trimIndent()
        )
        val declarations = result.declarationIndex.declarations.filter { it.origin != DeclarationOrigin.BUILTIN }
        val classDeclaration = declarations.single { it.kind == DeclarationKind.CLASS }
        val functionDeclaration = declarations.single { it.kind == DeclarationKind.FUNCTION }
        val classScope = assertNotNull(result.typeScopeGraph.getDeclarationScope(classDeclaration.id))
        val functionScope = assertNotNull(result.typeScopeGraph.getDeclarationScope(functionDeclaration.id))

        assertEquals(listOf("T"), result.typeScopeGraph.getDeclarations(classScope.id).map { it.name })
        assertEquals(listOf("U"), result.typeScopeGraph.getDeclarations(functionScope.id).map { it.name })
        assertNull(result.typeScopeGraph.getDeclarationScope(declarations.single { it.kind == DeclarationKind.FIELD }.id))
        assertNull(result.typeScopeGraph.getDeclarationScope(declarations.single { it.kind == DeclarationKind.METHOD }.id))
        assertNull(result.typeScopeGraph.getDeclarationScope(declarations.single { it.kind == DeclarationKind.PARAMETER }.id))
    }

    @Test
    fun parentsFunctionGenericScopeToDeclarationVisibilityScopeInsteadOfBodyScope() {
        val source = """
            do
                ---@generic T
                local function build(value)
                    return value
                end
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val function = chunk.body.statements
            .filterIsInstance<DoStatement>()
            .single()
            .body
            .statements
            .filterIsInstance<FunctionDeclaration>()
            .single()
        val functionDeclaration = result.declarationIndex.declarations.single {
            it.kind == DeclarationKind.FUNCTION && it.origin != DeclarationOrigin.BUILTIN
        }
        val functionGenericScope = assertNotNull(result.typeScopeGraph.getDeclarationScope(functionDeclaration.id))
        val declarationLexicalScope = assertNotNull(result.typeScopeGraph.getParent(functionGenericScope.id))
        val functionDeclarationLexicalScope = assertNotNull(result.scopeGraph.getDeclarationScope(functionDeclaration.id))
        val functionBodyLexicalScope = assertNotNull(result.typeScopeGraph.getLexicalScope(assertNotNull(result.scopeGraph.getScope(function.body!!)).id))

        assertEquals(functionDeclarationLexicalScope.id, declarationLexicalScope.lexicalScopeId)
        assertTrue(functionBodyLexicalScope.id != declarationLexicalScope.id)
    }

    private fun bind(source: String) = parser.parse(source).let { chunk ->
        BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
    }
}
