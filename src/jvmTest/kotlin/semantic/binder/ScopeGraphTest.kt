package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScopeGraphTest {

    private val parser = LuaParser()

    @Test
    fun buildsNestedScopeHierarchyForBlockConditionalLoopAndFunctionBodies() {
        val chunk = parser.parse(
            """
            do
                if true then
                    while true do
                        local value = 1
                    end
                else
                    for i = 1, 3 do
                    end
                end
            end

            local function render()
                repeat
                until true
            end
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val scopeGraph = result.scopeGraph

        val doStatement = chunk.body.statements.filterIsInstance<DoStatement>().single()
        val ifStatement = doStatement.body.statements.filterIsInstance<IfStatement>().single()
        val whileStatement = ifStatement.causes.first().body.statements.filterIsInstance<WhileStatement>().single()
        val elseClause = ifStatement.causes.filterIsInstance<ElseClause>().single()
        val forStatement = elseClause.body.statements.filterIsInstance<ForNumericStatement>().single()
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val repeatStatement = function.body!!.statements.filterIsInstance<RepeatStatement>().single()

        val rootScope = scopeGraph.rootScope
        val doScope = assertNotNull(scopeGraph.getScope(doStatement.body))
        val ifScope = assertNotNull(scopeGraph.getScope(ifStatement.causes.first().body))
        val whileScope = assertNotNull(scopeGraph.getScope(whileStatement.body))
        val elseScope = assertNotNull(scopeGraph.getScope(elseClause.body))
        val forScope = assertNotNull(scopeGraph.getScope(forStatement.body))
        val functionScope = assertNotNull(scopeGraph.getScope(function.body!!))
        val repeatScope = assertNotNull(scopeGraph.getScope(repeatStatement.body))

        assertEquals(chunk.body, rootScope.ownerNode)
        assertEquals(ScopeKind.CHUNK, rootScope.kind)
        assertEquals(ScopeKind.BLOCK, doScope.kind)
        assertEquals(ScopeKind.CONDITIONAL, ifScope.kind)
        assertEquals(ScopeKind.LOOP, whileScope.kind)
        assertEquals(ScopeKind.CONDITIONAL, elseScope.kind)
        assertEquals(ScopeKind.LOOP, forScope.kind)
        assertEquals(ScopeKind.FUNCTION, functionScope.kind)
        assertEquals(ScopeKind.LOOP, repeatScope.kind)

        assertEquals(rootScope.id, doScope.parentId)
        assertEquals(doScope.id, ifScope.parentId)
        assertEquals(ifScope.id, whileScope.parentId)
        assertEquals(functionScope.id, repeatScope.parentId)
        assertTrue(scopeGraph.getChildren(rootScope.id).any { it.id == doScope.id })
        assertTrue(scopeGraph.getChildren(rootScope.id).any { it.id == functionScope.id })
    }
}
