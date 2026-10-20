package semantic

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticTypeConsistencyTest {
    @Test
    fun type_at_never_turns_rejected_named_calls_into_successful_return_types() {
        val source = """
            ---@param count number
            ---@return string
            local function convert(count) return 'ok' end
            local receiver = {}
            ---@param count number
            ---@return string
            function receiver:convert(count) return 'ok' end
            local rejected = convert(false)
            local rejectedMember = receiver:convert(false)
            local accepted = convert(1)
        """.trimIndent()
        val chunk = LuaParser().parse(source)
        val snapshot = SemanticPipeline().analyzeSnapshot(chunk,
            SemanticWorkspaceContext(overlayGlobals = BuiltinOverlaySnapshot.EMPTY.globals))
        val declarations = chunk.body.statements.filterIsInstance<LocalStatement>()
        val rejected = declarations.single { it.init.single().name == "rejected" }.variables.single()
        val rejectedMember = declarations.single { it.init.single().name == "rejectedMember" }.variables.single()
        assertEquals("unknown", snapshot.model.getTypeAt(rejected)?.displayName)
        assertEquals("unknown", snapshot.model.getTypeAt(rejectedMember)?.displayName)
        val accepted = declarations.single { it.init.single().name == "accepted" }.variables.single()
        assertEquals("string", snapshot.model.getTypeAt(accepted)?.displayName)
        assertTrue(snapshot.model.getDiagnostics().isNotEmpty())
    }

    @Test
    fun inferred_multi_return_local_uses_the_same_value_slot_as_expression_typing() {
        val source = "local function values() return 1,'second' end\nlocal first,second=values()\nreturn first,second"
        val chunk = LuaParser().parse(source)
        val snapshot = SemanticPipeline().analyzeSnapshot(chunk,
            SemanticWorkspaceContext(overlayGlobals = BuiltinOverlaySnapshot.EMPTY.globals))
        val declaration = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        declaration.init.forEachIndexed { index, identifier ->
            val symbol = snapshot.model.getSymbolAt(identifier.range.start)!!
            val inferred = snapshot.model.getInferredType(symbol)!!.displayName
            val usage = chunk.body.returnStatement!!.arguments[index]
            val evaluator = ExpressionTypeEvaluator(snapshot.binder, snapshot.workspaceContext)
            assertEquals(evaluator.evaluate(usage).displayName, inferred)
        }
    }
}
