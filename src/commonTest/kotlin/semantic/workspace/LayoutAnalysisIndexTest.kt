package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutAnalysisIndexTest {
    @Test
    fun layouts_beyond_old_node_and_depth_budgets_keep_every_id() {
        val rows = (0 until 700).joinToString(",\n") { "{ TextView, id='item$it' }" }
        val nested = "{ LinearLayout,".repeat(40) + "{ TextView,id='deep' }" + "}".repeat(40)
        val source = "loadlayout({ LinearLayout,$rows,$nested })"
        val globals = BuiltinOverlayLoader.standaloneGlobals(LuaVersion.ANDROLUA_5_3)
        val binder = SemanticPipeline().bindForGlobalFingerprint(LuaParser().parse(source), globals)
        val evaluator = ExpressionTypeEvaluator(binder, SemanticWorkspaceContext(overlayGlobals = globals))
        val ids = evaluator.loadlayoutInjectedGlobalNames()
        assertEquals(701, ids.size)
        assertTrue("item699" in ids && "deep" in ids)
        assertEquals(ids, evaluator.loadlayoutInjectedGlobalNames())
    }

    @Test
    fun late_layout_calls_are_discovered_during_binding_without_rescanning_the_document() {
        val padding = (0 until 4200).joinToString("\n") { "local padding$it = $it" }
        val source = "$padding\nloadlayout({ TextView,id='late' })"
        val globals = BuiltinOverlayLoader.standaloneGlobals(LuaVersion.ANDROLUA_5_3)
        val binder = SemanticPipeline().bindForGlobalFingerprint(LuaParser().parse(source), globals)
        assertEquals(1, binder.scopeGraph.calls.size)
        val evaluator = ExpressionTypeEvaluator(binder, SemanticWorkspaceContext(overlayGlobals = globals))
        assertTrue("late" in evaluator.loadlayoutInjectedGlobalNames())
    }

    @Test
    fun layout_call_index_includes_switch_bodies_and_compact_call_rows() {
        val source = """
            switch value do
            case 1
                loadlayout({ LinearLayout, CustomView {id='wrapped'} })
            default
                loadlayout({TextView,id='other'})
            end
        """.trimIndent()
        val globals = BuiltinOverlayLoader.standaloneGlobals(LuaVersion.ANDROLUA_5_3)
        val binder = SemanticPipeline().bindForGlobalFingerprint(LuaParser().parse(source), globals)
        val evaluator = ExpressionTypeEvaluator(binder, SemanticWorkspaceContext(overlayGlobals = globals))
        val ids = evaluator.loadlayoutInjectedGlobalNames()
        assertEquals(setOf("wrapped", "other"), ids)
    }
}
