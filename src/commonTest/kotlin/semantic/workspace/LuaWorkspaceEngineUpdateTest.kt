package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import kotlin.test.Test
import kotlin.test.assertTrue

class LuaWorkspaceEngineUpdateTest {
    @Test
    fun metadata_only_update_without_extra_providers_preserves_documents_and_has_no_provider_changes() {
        val engine = LuaWorkspaceEngine()
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(VirtualPath.of("main.lua") to "local value = 1\nreturn value")
            )
        ).snapshot

        val updated = engine.update(
            previous = initial,
            delta = WorkspaceDelta(
                metadata = mapOf("workspace.mode" to "incremental")
            )
        )

        assertTrue(updated.snapshot.files.containsKey(VirtualPath.of("main.lua")))
        assertTrue(updated.activeProviderChangedModuleNames.isEmpty())
    }
}
