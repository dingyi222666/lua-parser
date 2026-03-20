package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuiltinOverlayLoaderTest {
    @Test
    fun lua53_overlay_contains_emmylua_math_module_resource() {
        val lua53 = load(LuaVersion.LUA_5_3)
        val mathPath = lua53.providerModules.entries.first { it.value.moduleName == "math" }.key
        val sources = mutableMapOf<String, String>()
        BuiltinOverlayLoader.load(LuaVersion.LUA_5_3) { path, source ->
            sources[path.value] = source
            WorkspaceSnapshot.FileSnapshot()
        }

        assertContains(sources.getValue(mathPath.value), "function math.abs(...)")
    }

    @Test
    fun lua53_overlay_contains_bit32_and_lua54_does_not() {
        val lua53 = load(LuaVersion.LUA_5_3)
        val lua54 = load(LuaVersion.LUA_5_4)

        assertTrue("bit32" in lua53.globals.globalNames)
        assertFalse("bit32" in lua54.globals.globalNames)
        assertFalse(lua53.providerModules.values.any { it.moduleName == "bit32" })
        assertFalse(lua54.providerModules.values.any { it.moduleName == "bit32" })
    }

    @Test
    fun lua54_globals_contain_warn_and_lua53_does_not() {
        val lua53 = load(LuaVersion.LUA_5_3)
        val lua54 = load(LuaVersion.LUA_5_4)

        assertFalse("warn" in lua53.globals.globalNames)
        assertTrue("warn" in lua54.globals.globalNames)
    }

    @Test
    fun package_metadata_exposes_seeall_for_legacy_fallback() {
        val lua53 = load(LuaVersion.ANDROLUA_5_3)
        val lua54 = load(LuaVersion.LUA_5_4)

        assertTrue("seeall" in lua53.globals.moduleFieldNames.getValue("package"))
        assertTrue("seeall" in lua54.globals.moduleFieldNames.getValue("package"))
    }

    private fun load(version: LuaVersion) = BuiltinOverlayLoader.load(version) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
}
