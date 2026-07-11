package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-344 corpus: workspace builtin overlay string library surface.
 *
 * Acceptance:
 * - string.* members complete/hover from builtin overlay.
 * - Unknown string members degrade safely.
 * - Test-only. Verification is review-owned (no Gradle).
 */
class BuiltinOverlayStringLibraryTddTest {

    @Test
    fun lua53_string_provider_exposes_full_resource_member_inventory() {
        val overlay = loadLua53()
        val provider = overlay.providerModules.values.single { it.moduleName == "string" }
        val surface = assertNotNull(provider.file.moduleExportSurface)
        val actual = surface.members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        val missing = EXPECTED_STRING_MEMBERS - actual
        assertTrue(missing.isEmpty(), "string missing $missing (actual=$actual)")
        EXPECTED_STRING_MEMBERS.forEach { member ->
            assertTrue(
                member in surface.moduleType.fields || member in surface.moduleType.methods,
                "string must publish '$member'"
            )
        }
    }

    @Test
    fun lua53_string_provider_path_and_module_name_are_stable() {
        val overlay = loadLua53()
        val entry = overlay.providerModules.entries.single { it.value.moduleName == "string" }
        assertEquals("__lua_std__/5.3/string.lua", entry.key.value)
        assertEquals("string", entry.value.moduleName)
        assertEquals(LuaVersion.LUA_5_3, overlay.version)
    }

    @Test
    fun require_string_resolves_overlay_provider() {
        val harness = stringHarness(
            """
            local string = require("string")
            local value = string.len
            return value
            """.trimIndent()
        )
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "string")
        assertEquals(harness.path("__lua_std__/5.3/string.lua"), resolved.provider?.path)
        assertNotNull(resolved.exportSurface)
        assertTrue(resolved.exportSurface!!.members.any { it.name == "len" })
    }

    @Test
    fun known_string_members_surface_in_completion_and_hover() {
        val samples = listOf("sub", "format", "find", "gsub", "upper", "pack")
        samples.forEach { name ->
            val harness = stringHarness(
                """
                local string = require("string")
                local value = string.$name
                return value
                """.trimIndent()
            )
            val main = harness.path("main.lua")
            val pos = harness.positionOf("main.lua", name)
            val completions = harness.queries.completions(main, pos)
            val item = assertNotNull(
                completions.singleOrNull { it.label == name },
                "Expected completion for string.$name; labels=${completions.map { it.label }}"
            )
            assertTrue(
                item.kind == CompletionItemKind.FIELD || item.kind == CompletionItemKind.METHOD,
                "string.$name kind=${item.kind}"
            )
            val hover = assertNotNull(harness.queries.hover(main, pos))
            assertNotNull(hover.symbol)
            assertTrue(
                hover.symbol?.kind == SymbolKind.FIELD || hover.symbol?.kind == SymbolKind.METHOD
            )
            val display = hover.typeInfo?.displayName.orEmpty()
            assertFalse(display.isBlank())
            assertFalse(display == "unknown", "string.$name must not be unknown")
        }
    }

    @Test
    fun unknown_string_member_degrades_safely() {
        val unknown = "definitelyNotAStringMember_xyz"
        val harness = stringHarness(
            """
            local string = require("string")
            local weird = string.$unknown
            return weird
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        val pos = harness.positionOf("main.lua", unknown)
        val completions = harness.queries.completions(main, pos)
        assertFalse(completions.any { it.label == unknown })
        val hover = harness.queries.hover(main, pos)
        val display = hover?.typeInfo?.displayName
        assertTrue(
            display == null || display.isBlank() || display == "unknown" || display == "any",
            "unknown string member should degrade; got '$display'"
        )
        harness.queries.diagnostics(main)
        val surface = assertNotNull(
            loadLua53().providerModules.values.single { it.moduleName == "string" }.file.moduleExportSurface
        )
        assertFalse(surface.members.any { it.name == unknown })
    }

    @Test
    fun androlua_overlay_still_exposes_string_provider_members() {
        // AndroLua inherits Lua 5.3 stdlib string at __lua_std__/androlua5.3/string.lua, but the
        // Android framework overlay also registers alias providers named "string"
        // (e.g. android.R$string). Select the stdlib provider by path, not singleOrNull(moduleName).
        val overlay = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
        val stdPath = VirtualPath.of("__lua_std__/androlua5.3/string.lua")
        val provider = assertNotNull(
            overlay.providerModules[stdPath],
            "AndroLua overlay must include stdlib string provider at ${stdPath.value}; " +
                "string-named modules=${overlay.providerModules.filterValues { it.moduleName == "string" }.keys.map { it.value }}"
        )
        assertEquals("string", provider.moduleName)
        assertEquals(LuaVersion.ANDROLUA_5_3, overlay.version)
        val surface = assertNotNull(provider.file.moduleExportSurface)
        val actual = surface.members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        val missing = EXPECTED_STRING_MEMBERS - actual
        assertTrue(missing.isEmpty(), "AndroLua string missing $missing (actual=$actual)")
    }

    private fun stringHarness(source: String) = WorkspaceSemanticHarness.build(
        "main.lua" to source,
        standardLibraryOverlayVersion = LuaVersion.LUA_5_3,
        engine = JvmWorkspaceEngine(
            workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.LUA_5_3, errorRecovery = false) }
        )
    )

    private fun loadLua53() =
        BuiltinOverlayLoader.load(LuaVersion.LUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }

    companion object {
        private val EXPECTED_STRING_MEMBERS = setOf(
            "byte", "char", "dump", "find", "format", "gmatch", "gsub", "len", "lower",
            "match", "pack", "packsize", "rep", "reverse", "sub", "unpack", "upper"
        )
    }
}
