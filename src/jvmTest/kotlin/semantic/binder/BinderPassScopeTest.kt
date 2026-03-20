package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinderPassScopeTest {

    private val parser = LuaParser()

    @Test
    fun bindsChunkAndBlockLocalsIntoTheirOwningScopes() {
        val chunk = parser.parse(
            """
            local top = 1
            do
                local inner = 2
            end
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val declarations = result.declarationIndex.declarations.filter { it.origin != DeclarationOrigin.BUILTIN }
        val rootScope = result.scopeGraph.rootScope
        val doStatement = chunk.body.statements.filterIsInstance<DoStatement>().single()
        val blockScope = assertNotNull(result.scopeGraph.getScope(doStatement.body))

        val topDeclaration = declarations.single { it.name == "top" }
        val innerDeclaration = declarations.single { it.name == "inner" }

        assertEquals(DeclarationOwner.Lexical(chunk.body), topDeclaration.owner)
        assertEquals(DeclarationOwner.Lexical(doStatement.body), innerDeclaration.owner)
        assertTrue(result.scopeGraph.getDeclarations(rootScope.id).contains(topDeclaration.id))
        assertTrue(result.scopeGraph.getDeclarations(blockScope.id).contains(innerDeclaration.id))
    }

    @Test
    fun bindsLoopVariablesAndParametersIntoLoopAndFunctionScopes() {
        val chunk = parser.parse(
            """
            for i = 1, 3 do
            end

            for key, value in pairs(items) do
            end

            local function render(input)
            end
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val index = result.declarationIndex

        val numericFor = chunk.body.statements.filterIsInstance<ForNumericStatement>().single()
        val genericFor = chunk.body.statements.filterIsInstance<ForGenericStatement>().single()
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()

        val numericScope = assertNotNull(result.scopeGraph.getScope(numericFor.body))
        val genericScope = assertNotNull(result.scopeGraph.getScope(genericFor.body))
        val functionScope = assertNotNull(result.scopeGraph.getScope(function.body!!))

        val iDeclaration = index.declarations.single { it.name == "i" && it.kind == DeclarationKind.LOCAL }
        val keyDeclaration = index.declarations.single { it.name == "key" && it.kind == DeclarationKind.LOCAL }
        val valueDeclaration = index.declarations.single { it.name == "value" && it.kind == DeclarationKind.LOCAL }
        val parameterDeclaration = index.declarations.single { it.name == "input" && it.kind == DeclarationKind.PARAMETER }
        val functionDeclaration = index.declarations.single { it.name == "render" && it.kind == DeclarationKind.FUNCTION }

        assertEquals(DeclarationOwner.Lexical(numericFor.body), iDeclaration.owner)
        assertEquals(DeclarationOwner.Lexical(genericFor.body), keyDeclaration.owner)
        assertEquals(DeclarationOwner.Declaration(functionDeclaration.id), parameterDeclaration.owner)
        assertTrue(result.scopeGraph.getDeclarations(numericScope.id).contains(iDeclaration.id))
        assertTrue(result.scopeGraph.getDeclarations(genericScope.id).contains(keyDeclaration.id))
        assertTrue(result.scopeGraph.getDeclarations(genericScope.id).contains(valueDeclaration.id))
        assertTrue(result.scopeGraph.getDeclarations(functionScope.id).contains(parameterDeclaration.id))
        assertEquals(ScopeKind.FUNCTION, functionScope.kind)
    }

    @Test
    fun createsAnonymousFunctionScopeWithoutNamedFunctionDeclaration() {
        val chunk = parser.parse(
            """
            local callback = function(value)
                return value
            end
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val anonymousFunction = local.variables.filterIsInstance<FunctionDeclaration>().single()
        val functionScope = assertNotNull(result.scopeGraph.getScope(anonymousFunction.body!!))

        assertEquals(ScopeKind.FUNCTION, functionScope.kind)
        assertNull(result.scopeGraph.getScope(anonymousFunction))
        assertEquals(0, result.declarationIndex.declarations.count {
            it.origin != DeclarationOrigin.BUILTIN &&
                it.kind in setOf(DeclarationKind.FUNCTION, DeclarationKind.GLOBAL)
        })
        assertEquals(1, result.declarationIndex.declarations.count { it.name == "value" && it.kind == DeclarationKind.PARAMETER })
    }

    @Test
    fun createsConditionalScopeForElseIfBodies() {
        val chunk = parser.parse(
            """
            if true then
                local first = 1
            elseif false then
                local second = 2
            end
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val ifStatement = chunk.body.statements.filterIsInstance<IfStatement>().single()
        val elseIfClause = ifStatement.causes.filterIsInstance<ElseIfClause>().single()
        val elseIfScope = assertNotNull(result.scopeGraph.getScope(elseIfClause.body))
        val elseIfDeclaration = result.declarationIndex.declarations.single { it.name == "second" && it.kind == DeclarationKind.LOCAL }

        assertEquals(ScopeKind.CONDITIONAL, elseIfScope.kind)
        assertEquals(DeclarationOwner.Lexical(elseIfClause.body), elseIfDeclaration.owner)
        assertTrue(result.scopeGraph.getDeclarations(elseIfScope.id).contains(elseIfDeclaration.id))
    }
}
