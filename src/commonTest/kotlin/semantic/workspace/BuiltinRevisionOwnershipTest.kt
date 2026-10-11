package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.*
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BuiltinRevisionOwnershipTest {
    private class Engine : LuaWorkspaceEngine() {
        override val includeAndroidFramework = false
        override val checkDiagnostics = false
    }

    @Test
    fun standalone_foreground_and_background_use_one_complete_resource_revision() {
        val core = BuiltinOverlayLoader.compiledRevision(LuaVersion.ANDROLUA_5_3, false)
        val complete = BuiltinOverlayLoader.compiledRevision(LuaVersion.ANDROLUA_5_3, true)
        assertSame(core.globals, BuiltinOverlayLoader.standaloneGlobals(LuaVersion.ANDROLUA_5_3))
        assertSame(core.globals, complete.globals)
        core.providerModules.forEach { (path, provider) ->
            assertSame(provider, complete.providerModules[path])
            val file = provider.file
            assertNotNull(file.documentFacts)
            assertNotNull(file.moduleExportSurface)
            val semantic = assertNotNull(file.semanticFile)
            assertNotNull(file.publicFingerprint)
            assertTrue(semantic.source.isNotEmpty())
            assertTrue(semantic.snapshot.binder.declarationIndex.declarations.isNotEmpty())
        }
        assertTrue(complete.providerModules.size > core.providerModules.size)
        assertNotSame(core, BuiltinOverlayLoader.compiledRevision(LuaVersion.LUA_5_4, false))
    }

    @Test
    fun two_workspace_engines_share_resources_and_keep_user_sources_separate() {
        val path = VirtualPath.of("main.lua")
        val first = Engine().build(LuaWorkspaceInput(mapOf(path to "local value = 1\nreturn value"))).snapshot
        val second = Engine().build(LuaWorkspaceInput(mapOf(path to "local value = 'other'\nreturn value"))).snapshot
        assertSame(first.builtinOverlay, second.builtinOverlay)
        assertNotSame(first.files.getValue(path).semanticFile!!.snapshot.binder,
            second.files.getValue(path).semanticFile!!.snapshot.binder)
        assertTrue(second.files.getValue(path).semanticFile!!.source.contains("other"))
    }
}
