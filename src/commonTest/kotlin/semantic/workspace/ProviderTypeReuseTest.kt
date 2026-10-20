package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.workspace.*
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderTypeReuseTest {
    private class Engine(val evaluated: MutableMap<ExpressionNode, Int>) : LuaWorkspaceEngine() {
        override val includeAndroidFramework = false
        override val checkDiagnostics = false
        internal override fun workspaceContext(input: LuaWorkspaceInput, path: VirtualPath, snapshot: WorkspaceSnapshot): SemanticWorkspaceContext =
            super.workspaceContext(input, path, snapshot).copy(lazyImportedSymbols = true,
                onExpressionEvaluation = if (path.value == "dep.lua") {
                    { node -> evaluated[node] = (evaluated[node] ?: 0) + 1 }
                } else null)
    }

    @Test
    fun different_exported_globals_share_completed_provider_types_and_edits_invalidate_them() {
        val dep = VirtualPath.of("dep.lua")
        val main = VirtualPath.of("main.lua")
        val counts = mutableMapOf<ExpressionNode, Int>()
        val engine = Engine(counts)
        val source = "local common = {value=1}\nalpha=common\nbeta=common\nreturn {}"
        val old = engine.build(LuaWorkspaceInput(mapOf(dep to source, main to "require 'dep'"))).snapshot
        counts.clear()
        val resolver = WorkspaceModuleResolver(old, deferDependencyGlobals = true)
        val filesUnderConstruction = old.files.toMutableMap()
        val buildingResolver = WorkspaceModuleResolver(old.copy(files = filesUnderConstruction), deferDependencyGlobals = true)
        assertIs<TableType>(resolver.importedSymbolFor(main, "alpha")!!.valueType)
        assertIs<TableType>(resolver.importedSymbolFor(main, "beta")!!.valueType)
        val common = (old.files.getValue(dep).semanticFile!!.chunk.body.statements.first() as LocalStatement)
            .variables.single()
        assertEquals(1, counts[common], "One provider revision evaluates its common value once across aliases.")
        buildingResolver.importedSymbolFor(main, "alpha")
        val newer = engine.update(old, WorkspaceDelta(upserts = mapOf(dep to source.replace("value=1", "value='changed'")))).snapshot
        val latest = WorkspaceModuleResolver(newer, deferDependencyGlobals = true)
        assertTrue(assertIs<TableType>(latest.importedSymbolFor(main, "beta")!!.valueType).fields.getValue("value")
            .displayName.contains("changed"))
        assertEquals("1", assertIs<TableType>(resolver.importedSymbolFor(main, "alpha")!!.valueType)
            .fields.getValue("value").displayName)
        filesUnderConstruction[dep] = newer.files.getValue(dep)
        assertTrue(assertIs<TableType>(buildingResolver.importedSymbolFor(main, "alpha")!!.valueType)
            .fields.getValue("value").displayName.contains("changed"),
            "The dependency-order builder may replace a binding under the same resolver.")
    }

    @Test
    fun recursive_entry_order_does_not_cache_provisional_types_as_completed_results() {
        val chunk = LuaParser().parse("first=second\nsecond={next=first}\nstable={value='ok'}\nreturn first,second,stable")
        val binder = SemanticPipeline().bindForGlobalFingerprint(chunk, BuiltinOverlaySnapshot.EMPTY.globals)
        val expressions = chunk.body.returnStatement!!.arguments
        fun evaluator() = ExpressionTypeEvaluator(binder, SemanticWorkspaceContext(overlayGlobals = BuiltinOverlaySnapshot.EMPTY.globals))
        val forward = evaluator()
        val backward = evaluator()
        expressions.forEach(forward::evaluate)
        expressions.asReversed().forEach(backward::evaluate)
        expressions.forEach { expression ->
            assertEquals(forward.evaluate(expression).displayName, backward.evaluate(expression).displayName)
        }
        assertEquals("\"ok\"", assertIs<TableType>(forward.evaluate(expressions.last())).fields.getValue("value").displayName)
    }
}
