package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import kotlin.test.Test
import kotlin.test.assertSame
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

    @Test
    fun identical_metadata_delta_is_a_no_op_that_returns_the_previous_snapshot() {
        val engine = LuaWorkspaceEngine()
        val metadata = mapOf("workspace.mode" to "incremental")
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(VirtualPath.of("main.lua") to "local value = 1\nreturn value"),
                metadata = metadata
            )
        ).snapshot

        val updated = engine.update(
            previous = initial,
            // Same metadata CONTENT on a fresh map instance (the LSP reparses settings into a
            // new map per didChangeConfiguration), no upserts/removals: the update must be
            // treated as a no-op and hand back the previous snapshot instance without
            // re-running extraProviders discovery or re-analyzing any document.
            delta = WorkspaceDelta(metadata = metadata)
        )

        assertTrue(initial.metadata.isNotEmpty(), "fixture guard: the previous snapshot carries metadata")
        assertSame(initial, updated.snapshot, "identical metadata delta must return the previous snapshot instance")
        assertTrue(updated.changedFiles.isEmpty())
        assertTrue(updated.publicSurfaceChangedFiles.isEmpty())
        assertTrue(updated.activeProviderChangedModuleNames.isEmpty())
        assertTrue(updated.affectedDocuments.isEmpty())
    }
}
