package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-345 corpus: workspace builtin overlay table library surface.
 *
 * Acceptance:
 * - table.* members complete/hover from builtin overlay.
 * - Unknown table members degrade safely.
 * - Test-only. Verification is review-owned (no Gradle).
 */
class BuiltinOverlayTableLibraryTddTest {

    @Test
    fun lua53_table_provider_exposes_full_resource_member_inventory() {
        val overlay = loadLua53()
        val provider = overlay.providerModules.values.single { it.moduleName == "table" }
        val surface = assertNotNull(provider.file.moduleExportSurface)
        val actual = surface.members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        val missing = EXPECTED_TABLE_MEMBERS - actual
        assertTrue(missing.isEmpty(), "table missing $missing (actual=$actual)")
        EXPECTED_TABLE_MEMBERS.forEach { member ->
            assertTrue(
                member in surface.moduleType.fields || member in surface.moduleType.methods,
                "table must publish '$member'"
            )
        }
    }

    @Test
    fun lua53_table_provider_path_and_module_name_are_stable() {
        val overlay = loadLua53()
        val entry = overlay.providerModules.entries.single { it.value.moduleName == "table" }
        assertEquals("__lua_std__/5.3/table.lua", entry.key.value)
        assertEquals("table", entry.value.moduleName)
        assertEquals(LuaVersion.LUA_5_3, overlay.version)
    }

    @Test
    fun require_table_resolves_overlay_provider() {
        val harness = tableHarness(
            """
            local table = require("table")
            local value = table.insert
            return value
            """.trimIndent()
        )
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "table")
        assertEquals(harness.path("__lua_std__/5.3/table.lua"), resolved.provider?.path)
        assertNotNull(resolved.exportSurface)
        assertTrue(resolved.exportSurface!!.members.any { it.name == "insert" })
    }

    @Test
    fun known_table_members_surface_in_completion_and_hover() {
        val samples = listOf("insert", "remove", "concat", "sort", "pack", "unpack", "move")
        samples.forEach { name ->
            val harness = tableHarness(
                """
                local table = require("table")
                local value = table.$name
                return value
                """.trimIndent()
            )
            val main = harness.path("main.lua")
            val pos = harness.positionOf("main.lua", name)
            val completions = harness.queries.completions(main, pos)
            val item = assertNotNull(
                completions.singleOrNull { it.label == name },
                "Expected completion for table.$name; labels=${completions.map { it.label }}"
            )
            assertTrue(item.kind == CompletionItemKind.FIELD || item.kind == CompletionItemKind.METHOD)
            val hover = assertNotNull(harness.queries.hover(main, pos))
            assertNotNull(hover.symbol)
            assertTrue(hover.symbol?.kind == SymbolKind.FIELD || hover.symbol?.kind == SymbolKind.METHOD)
            val display = hover.typeInfo?.displayName.orEmpty()
            assertFalse(display.isBlank())
            assertFalse(display == "unknown", "table.$name must not be unknown")
        }
    }

    @Test
    fun unknown_table_member_degrades_safely() {
        val unknown = "definitelyNotATableMember_xyz"
        val harness = tableHarness(
            """
            local table = require("table")
            local weird = table.$unknown
            return weird
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        val pos = harness.positionOf("main.lua", unknown)
        assertFalse(harness.queries.completions(main, pos).any { it.label == unknown })
        val display = harness.queries.hover(main, pos)?.typeInfo?.displayName
        assertTrue(
            display == null || display.isBlank() || display == "unknown" || display == "any",
            "unknown table member should degrade; got '$display'"
        )
        harness.queries.diagnostics(main)
        val surface = assertNotNull(
            loadLua53().providerModules.values.single { it.moduleName == "table" }.file.moduleExportSurface
        )
        assertFalse(surface.members.any { it.name == unknown })
    }

    @Test
    fun androlua_overlay_still_exposes_table_provider_members() {
        val overlay = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
        val provider = assertNotNull(overlay.providerModules.values.singleOrNull { it.moduleName == "table" })
        val surface = assertNotNull(provider.file.moduleExportSurface)
        val actual = surface.members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        assertTrue((EXPECTED_TABLE_MEMBERS - actual).isEmpty(), "AndroLua table missing ${EXPECTED_TABLE_MEMBERS - actual}")
    }

    private fun tableHarness(source: String) = WorkspaceSemanticHarness.build(
        "main.lua" to source,
        standardLibraryOverlayVersion = LuaVersion.LUA_5_3,
        engine = JvmWorkspaceEngine(
            workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.LUA_5_3, errorRecovery = false) }
        )
    )

    private fun loadLua53() =
        BuiltinOverlayLoader.load(LuaVersion.LUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }

    companion object {
        private val EXPECTED_TABLE_MEMBERS = setOf(
            "concat", "insert", "move", "pack", "remove", "sort", "unpack"
        )
    }
}
