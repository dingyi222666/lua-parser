package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import kotlin.test.Test
import kotlin.test.assertTrue

class LuaWorkspaceEngineUpdateJvmTest {
    @Test
    fun metadata_only_update_rebuilds_extra_providers_and_affected_modules() {
        val engine = JvmWorkspaceEngine()
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(VirtualPath.of("main.lua") to "local current = 1")
            )
        ).snapshot

        val updated = engine.update(
            previous = initial,
            delta = WorkspaceDelta(
                metadata = mapOf(JvmClassModuleProvider.IMPORTS_METADATA_KEY to "String")
            )
        )

        assertTrue("String" in updated.activeProviderChangedModuleNames)
        assertTrue(updated.snapshot.graph.activeProviders.containsKey("String"))
        assertTrue(updated.affectedModuleNames.contains("String"))
    }

    @Test
    fun metadata_only_update_preserves_workspace_documents() {
        val engine = JvmWorkspaceEngine()
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(VirtualPath.of("main.lua") to "local value = 1\nreturn value")
            )
        ).snapshot

        val updated = engine.update(
            previous = initial,
            delta = WorkspaceDelta(
                metadata = mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Locale")
            )
        )

        assertTrue(updated.snapshot.files.containsKey(VirtualPath.of("main.lua")))
        assertTrue(updated.snapshot.graph.activeProviders.containsKey("Locale"))
    }
}
