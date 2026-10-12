package semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.workspace.*
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedGlobalRevisionTest {
    @Test
    fun model_and_workspace_queries_agree_on_member_boundaries_and_receiver_positions() {
        val source = "local api={outerOnly=1,nested={leaf=1,run=function() end}}\napi.nested."
        val path = VirtualPath.of("main.lua")
        val snapshot = Engine().build(LuaWorkspaceInput(mapOf(path to source))).snapshot
        val model = snapshot.files.getValue(path).semanticFile!!.model
        val queries = LuaWorkspaceQueryFacade(snapshot)
        val caret = Position(2, "api.nested.".length + 1)
        val direct = model.getCompletionsAt(caret).map { it.label }.toSet()
        val workspace = queries.completions(path, caret).map { it.label }.toSet()
        kotlin.test.assertEquals(direct, workspace)
        assertTrue("leaf" in direct && "run" in direct, direct.toString())
        kotlin.test.assertFalse("outerOnly" in direct, direct.toString())
        val receiver = model.getCompletionsAt(Position(2, 2)).map { it.label }
        assertTrue("api" in receiver, receiver.toString())
        kotlin.test.assertFalse("leaf" in receiver, receiver.toString())
    }

    @Test
    fun local_member_query_does_not_hydrate_unrelated_workspace_globals() {
        var unrelatedResolutions = 0
        val engine = object : LuaWorkspaceEngine() {
            override val includeAndroidFramework = false
            override val checkDiagnostics = false
            internal override fun workspaceContext(input: LuaWorkspaceInput, path: VirtualPath,
                snapshot: WorkspaceSnapshot): io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext {
                val resolve: (String) -> io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol? = { name ->
                    if (name == "fixture.Expensive") unrelatedResolutions++
                    null
                }
                return super.workspaceContext(input, path, snapshot).copy(
                    resolveImportTarget = resolve, baseResolveImportTarget = resolve)
            }
        }
        val main = VirtualPath.of("main.lua")
        val snapshot = engine.build(LuaWorkspaceInput(mapOf(
            main to "local receiver={refresh=function() end}\nreceiver.",
            VirtualPath.of("provider.lua") to "Unrelated={object=luajava.bindClass('fixture.Expensive')}"
        ))).snapshot
        unrelatedResolutions = 0
        // Caret inside the declared name: the local is visible here (start-inclusive
        // identifier range) for BOTH the model and facade visibility predicates, so this
        // query is deterministic in any fork/test ordering. The former Position(1, 1)
        // preceded the declaration and only passed via unspecified cross-test state.
        val selective = LuaWorkspaceQueryFacade(snapshot).completionsMatching(main, Position(1, 8)) {
            it.startsWith("rec")
        }.map { it.label }
        assertTrue("receiver" in selective, selective.toString())
        kotlin.test.assertFalse("Unrelated" in selective, selective.toString())
        kotlin.test.assertEquals(0, unrelatedResolutions, "Name filtering must happen before global type resolution")
        val labels = LuaWorkspaceQueryFacade(snapshot).completions(main, Position(2, 10)).map { it.label }
        assertTrue("refresh" in labels, labels.toString())
        kotlin.test.assertEquals(0, unrelatedResolutions, "Single receiver lookup must not enumerate other globals")
        val lexical = LuaWorkspaceQueryFacade(snapshot).completions(main, Position(1, 1)).map { it.label }
        assertTrue("Unrelated" in lexical, lexical.toString())
        assertTrue(unrelatedResolutions > 0, "Full lexical completion must still derive real shared globals")
    }

    private class Engine : LuaWorkspaceEngine() {
        override val includeAndroidFramework = false
        override val checkDiagnostics = false
    }

    @Test
    fun shared_globals_without_require_edges_invalidate_warmed_consumers_and_keep_old_snapshots() {
        val provider = VirtualPath.of("provider.lua")
        val consumer = VirtualPath.of("main.lua")
        val unrelated = VirtualPath.of("other.lua")
        val engine = Engine()
        val old = engine.build(LuaWorkspaceInput(mapOf(
            provider to "Shared={first=1}", consumer to "Shared.", unrelated to "local value=1\nreturn value"
        ))).snapshot
        fun labels(snapshot: WorkspaceSnapshot) = LuaWorkspaceQueryFacade(snapshot).completions(consumer, Position(1, 8)).map { it.label }
        assertTrue("first" in labels(old))
        val oldModel = old.files.getValue(consumer).semanticFile!!.model
        val independentModel = old.files.getValue(unrelated).semanticFile!!.model
        val updated = engine.update(old, WorkspaceDelta(upserts = mapOf(provider to "Shared={second='new'}")))
        assertTrue(consumer in updated.affectedDocuments)
        assertTrue("second" in labels(updated.snapshot) && "first" !in labels(updated.snapshot))
        assertNotSame(oldModel, updated.snapshot.files.getValue(consumer).semanticFile!!.model)
        assertSame(independentModel, updated.snapshot.files.getValue(unrelated).semanticFile!!.model)
        assertTrue("first" in labels(old) && "second" !in labels(old), "Old published revisions stay immutable.")
        val removed = engine.update(updated.snapshot, WorkspaceDelta(removals = setOf(provider)))
        assertTrue(consumer in removed.affectedDocuments)
        assertTrue("second" !in labels(removed.snapshot))
    }

    @Test
    fun a_new_global_provider_refreshes_a_previously_unknown_name() {
        val provider = VirtualPath.of("provider.lua")
        val consumer = VirtualPath.of("main.lua")
        val engine = Engine()
        val old = engine.build(LuaWorkspaceInput(mapOf(consumer to "Shared."))).snapshot
        LuaWorkspaceQueryFacade(old).completions(consumer, Position(1, 8))
        val added = engine.update(old, WorkspaceDelta(upserts = mapOf(provider to "Shared={available=1}")))
        assertTrue(consumer in added.affectedDocuments)
        val labels = LuaWorkspaceQueryFacade(added.snapshot).completions(consumer, Position(1, 8)).map { it.label }
        assertTrue("available" in labels, labels.toString())
    }

    @Test
    fun annotation_only_edits_and_transitive_shared_global_inputs_invalidate_readers() {
        val provider = VirtualPath.of("a.lua")
        val bridge = VirtualPath.of("b.lua")
        val consumer = VirtualPath.of("main.lua")
        val engine = Engine()
        val source = "---@class Surface\n---@field oldMember number\n---@type Surface\nShared=Shared"
        val old = engine.build(LuaWorkspaceInput(mapOf(provider to source,
            bridge to "Forward=Shared", consumer to "Forward."))).snapshot
        val changed = engine.update(old, WorkspaceDelta(upserts = mapOf(
            provider to source.replace("oldMember number", "newMember string"))))
        assertTrue(bridge in changed.affectedDocuments && consumer in changed.affectedDocuments,
            "Annotation changes flow through shared global edges, not only require edges.")
        assertNotSame(old.files.getValue(consumer).semanticFile!!.model,
            changed.snapshot.files.getValue(consumer).semanticFile!!.model)
    }

    @Test
    fun static_table_keys_are_not_global_reads_but_dynamic_index_keys_are() {
        val provider = VirtualPath.of("provider.lua")
        val literal = VirtualPath.of("literal.lua")
        val dynamic = VirtualPath.of("dynamic.lua")
        val engine = Engine()
        val old = engine.build(LuaWorkspaceInput(mapOf(provider to "value=1",
            literal to "return { value=1 }", dynamic to "return { [value]=1 }"))).snapshot
        val changed = engine.update(old, WorkspaceDelta(upserts = mapOf(provider to "value='new'")))
        assertTrue(literal !in changed.affectedDocuments)
        assertTrue(dynamic in changed.affectedDocuments)
    }
}
