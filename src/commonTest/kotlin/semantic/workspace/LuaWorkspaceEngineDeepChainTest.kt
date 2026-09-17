package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals

class LuaWorkspaceEngineDeepChainTest {
    @Test
    fun deep_require_chain_builds_without_stack_overflow_and_yields_single_member_sccs() {
        // 5000 files f1..f5000 where f_i requires f_{i+1}: both the analysis-order DFS and the
        // Tarjan SCC pass used to recurse once per chain link, deep enough to overflow the
        // call stack. Both are iterative frames now, so one engine.build must complete.
        val chainLength = 5000
        val files = linkedMapOf<VirtualPath, String>()
        for (index in 1..chainLength) {
            files[VirtualPath.of("f$index.lua")] = if (index == chainLength) {
                "return { index = $index }"
            } else {
                "local nextModule = require(\"f${index + 1}\")\nreturn { index = $index }"
            }
        }

        val result = LuaWorkspaceEngine().build(
            LuaWorkspaceInput(
                files = files,
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        )

        assertEquals(chainLength, result.snapshot.files.size)
        val graph = result.snapshot.graph
        val chainPaths = (1..chainLength).map { index -> VirtualPath.of("f$index.lua") }
        // A linear require chain must not collapse anything into a multi-member SCC: every
        // chain file is its own strongly connected component (and the iterative Tarjan still
        // emitted a component per node).
        chainPaths.forEach { path ->
            assertEquals(setOf(path), graph.stronglyConnectedComponentByFile.getValue(path))
        }
        // The require edges themselves survived the graph build: f1..f4999 each resolve f_{i+1}.
        assertEquals(
            chainLength - 1,
            chainPaths.count { path -> graph.resolvedDependencies[path].orEmpty().isNotEmpty() }
        )
    }
}
