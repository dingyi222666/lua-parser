package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.semantic.workspace.*
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmImportActivationReuseTest {
    @Test
    fun ordinary_edits_reuse_import_activation_but_changed_targets_replace_it() {
        val path = VirtualPath.of("main.lua")
        val engine = JvmWorkspaceEngine(configuration = JvmWorkspaceConfiguration(
            androluaImports = listOf("java.util.ArrayList")))
        var activations = 0
        engine.onImportActivation = { activations++ }
        val firstSource = "import 'java.lang.StringBuilder'\nlocal builder=StringBuilder()\nbuilder."
        val first = engine.build(LuaWorkspaceInput(mapOf(path to firstSource))).snapshot
        val initial = activations
        assertTrue("append" in LuaWorkspaceQueryFacade(first).completions(path, Position(3, 9)).map { it.label })
        val changed = engine.update(first, WorkspaceDelta(upserts = mapOf(path to firstSource.replace("builder=", "builder = ")))).snapshot
        assertEquals(initial, activations, "Source positions and whitespace are not import activation inputs.")
        assertTrue("append" in LuaWorkspaceQueryFacade(changed).completions(path, Position(3, 9)).map { it.label })
        val latest = engine.update(changed, WorkspaceDelta(upserts = mapOf(path to
            "import 'java.util.ArrayList'\nlocal list=ArrayList()\nlist."))).snapshot
        assertTrue(activations > initial)
        assertTrue("add" in LuaWorkspaceQueryFacade(latest).completions(path, Position(3, 6)).map { it.label })
    }

    @Test
    fun prefix_metadata_change_replaces_simple_name_activation() {
        val path = VirtualPath.of("main.lua")
        val engine = JvmWorkspaceEngine()
        val source = "local value=Date()\nvalue."
        var activations = 0
        engine.onImportActivation = { activations++ }
        val first = engine.build(LuaWorkspaceInput(mapOf(path to source), metadata = mapOf(
            JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "java.util"))).snapshot
        val initial = activations
        val next = engine.update(first, WorkspaceDelta(metadata = mapOf(
            JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "java.sql"))).snapshot
        assertTrue(activations > initial, "Metadata belongs to the activation identity.")
        assertTrue(first.metadata != next.metadata)
    }
}
