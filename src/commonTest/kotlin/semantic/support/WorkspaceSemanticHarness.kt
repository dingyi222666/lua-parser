package semantic.support

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot

class WorkspaceSemanticHarness private constructor(
    val files: Map<VirtualPath, String>,
    val snapshot: WorkspaceSnapshot,
    val queries: LuaWorkspaceQueryFacade
) {
    fun path(value: String): VirtualPath = VirtualPath.of(value)

    fun positionOf(path: String, needle: String, occurrence: Int = 1): Position {
        val source = files.getValue(VirtualPath.of(path))
        var index = -1
        repeat(occurrence) {
            index = source.indexOf(needle, index + 1)
            check(index >= 0) { "Missing occurrence $occurrence of '$needle' in $path." }
        }

        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return Position(line, column)
    }

    companion object {
        fun build(
            vararg files: Pair<String, String>,
            standardLibraryOverlayVersion: LuaVersion = LuaVersion.LUA_5_3,
            metadata: Map<String, String> = emptyMap(),
            engine: LuaWorkspaceEngine = LuaWorkspaceEngine()
        ): WorkspaceSemanticHarness {
            val mapped = files.associate { (path, source) -> VirtualPath.of(path) to source }
            val snapshot = engine.build(
                LuaWorkspaceInput(
                    files = mapped,
                    metadata = metadata,
                    standardLibraryOverlayVersion = standardLibraryOverlayVersion
                )
            ).snapshot
            return WorkspaceSemanticHarness(mapped, snapshot, LuaWorkspaceQueryFacade(snapshot))
        }
    }
}
