package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.workspace.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class AnalysisPassReuseTest {
    private class RecordingEngine : LuaWorkspaceEngine() {
        override val includeAndroidFramework = false
        override val checkDiagnostics = false
        val passes = mutableMapOf<Pair<String, VirtualPath>, Int>()

        override fun <T> analysisStage(stage: String, path: VirtualPath?, action: () -> T): T {
            if (path != null) passes[stage to path] = (passes[stage to path] ?: 0) + 1
            return action()
        }

        override fun extraProviders(input: LuaWorkspaceInput): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
            input.files.forEach { (path, source) -> documentFacts(path, source) }
            return emptyMap()
        }

        internal override fun workspaceContext(input: LuaWorkspaceInput, path: VirtualPath, snapshot: WorkspaceSnapshot): SemanticWorkspaceContext {
            documentFacts(path, input.files.getValue(path))
            return super.workspaceContext(input, path, snapshot)
        }
    }

    @Test
    fun fact_consumers_share_one_parse_and_one_facts_walk_per_document_revision() {
        val helper = VirtualPath.of("helper.lua")
        val main = VirtualPath.of("main.lua")
        val engine = RecordingEngine()
        val previous = engine.build(LuaWorkspaceInput(files = mapOf(
            helper to "return { member = 1 }",
            main to "local helper = require 'helper'\nprint(helper.member)"
        ))).snapshot
        for (path in listOf(helper, main)) {
            assertEquals(1, engine.passes["parse-file" to path])
            assertEquals(1, engine.passes["facts" to path])
            assertEquals(1, engine.passes["binding" to path])
        }
        engine.passes.clear()
        val current = engine.update(previous, WorkspaceDelta(upserts = mapOf(
            helper to "return { member = 'changed' }"
        ))).snapshot
        assertEquals(1, engine.passes["parse-file" to helper])
        assertEquals(1, engine.passes["facts" to helper])
        assertEquals(null, engine.passes["parse-file" to main])
        assertEquals(null, engine.passes["facts" to main])
        assertNotSame(previous.files.getValue(main).semanticFile!!.snapshot.binder,
            current.files.getValue(main).semanticFile!!.snapshot.binder)
        assertEquals(previous.files.getValue(main).semanticFile!!.chunk,
            current.files.getValue(main).semanticFile!!.chunk)
    }

    @Test
    fun dialect_changes_and_readded_files_receive_new_revisions() {
        val path = VirtualPath.of("main.lua")
        val source = "local value <close> = nil\nreturn value"
        val engine = RecordingEngine()
        val lua54 = engine.build(LuaWorkspaceInput(mapOf(path to source),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_4)).snapshot
        val lua53 = engine.build(LuaWorkspaceInput(mapOf(path to source),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3)).snapshot
        assertNotSame(lua54.files.getValue(path).semanticFile!!.chunk, lua53.files.getValue(path).semanticFile!!.chunk)
        assertEquals(2, engine.passes["parse-file" to path])
        assertEquals(2, engine.passes["facts" to path])
        val removed = engine.update(lua53, WorkspaceDelta(removals = setOf(path))).snapshot
        assertTrue(removed.files.isEmpty())
        val added = engine.update(removed, WorkspaceDelta(upserts = mapOf(path to source))).snapshot
        assertNotSame(lua53.files.getValue(path).semanticFile!!.chunk, added.files.getValue(path).semanticFile!!.chunk)
        assertEquals(3, engine.passes["parse-file" to path])
        assertEquals(3, engine.passes["facts" to path])
    }
}
