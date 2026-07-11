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
 * TASK-346 corpus: workspace builtin overlay `io` / `os` library surface.
 *
 * Acceptance:
 * - `io.*` and `os.*` library members surface from the Lua 5.3 std overlay.
 * - Missing members degrade without throw (no invented completion labels).
 * - Test-only. Verification is review-owned (no Gradle).
 *
 * Inventory mirrors `src/commonMain/resources/.../std/lua53/io.lua` and `os.lua`.
 */
class BuiltinOverlayIoOsLibraryTddTest {

    // ------------------------------------------------------------------
    // io provider inventory / path stability
    // ------------------------------------------------------------------

    @Test
    fun lua53_io_provider_exposes_full_resource_member_inventory() {
        val overlay = loadLua53()
        val provider = overlay.providerModules.values.single { it.moduleName == "io" }
        val surface = assertNotNull(provider.file.moduleExportSurface)
        val actual = surface.members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        val missing = EXPECTED_IO_MEMBERS - actual
        assertTrue(missing.isEmpty(), "io missing $missing (actual=$actual)")
        EXPECTED_IO_MEMBERS.forEach { member ->
            assertTrue(
                member in surface.moduleType.fields || member in surface.moduleType.methods,
                "io must publish '$member'"
            )
        }
    }

    @Test
    fun lua53_io_provider_path_and_module_name_are_stable() {
        val overlay = loadLua53()
        val entry = overlay.providerModules.entries.single { it.value.moduleName == "io" }
        assertEquals("__lua_std__/5.3/io.lua", entry.key.value)
        assertEquals("io", entry.value.moduleName)
        assertEquals(LuaVersion.LUA_5_3, overlay.version)
    }

    @Test
    fun require_io_resolves_overlay_provider() {
        val harness = overlayHarness(
            """
            local io = require("io")
            local value = io.open
            return value
            """.trimIndent()
        )
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "io")
        assertEquals(harness.path("__lua_std__/5.3/io.lua"), resolved.provider?.path)
        assertNotNull(resolved.exportSurface)
        assertTrue(resolved.exportSurface!!.members.any { it.name == "open" })
    }

    @Test
    fun known_io_members_surface_in_completion_and_hover() {
        val samples = listOf("open", "close", "read", "write", "lines", "stdin", "stdout", "stderr", "tmpfile", "type")
        samples.forEach { name ->
            val harness = overlayHarness(
                """
                local io = require("io")
                local value = io.$name
                return value
                """.trimIndent()
            )
            val main = harness.path("main.lua")
            val pos = harness.positionOf("main.lua", name)
            val completions = harness.queries.completions(main, pos)
            val item = assertNotNull(
                completions.singleOrNull { it.label == name },
                "Expected completion for io.$name; labels=${completions.map { it.label }}"
            )
            assertTrue(
                item.kind == CompletionItemKind.FIELD || item.kind == CompletionItemKind.METHOD,
                "io.$name kind=${item.kind}"
            )
            val hover = assertNotNull(harness.queries.hover(main, pos))
            assertNotNull(hover.symbol)
            assertTrue(
                hover.symbol?.kind == SymbolKind.FIELD || hover.symbol?.kind == SymbolKind.METHOD
            )
            val display = hover.typeInfo?.displayName.orEmpty()
            assertFalse(display.isBlank())
            assertFalse(display == "unknown", "io.$name must not be unknown")
        }
    }

    @Test
    fun unknown_io_member_degrades_safely() {
        val unknown = "definitelyNotAnIoMember_xyz"
        val harness = overlayHarness(
            """
            local io = require("io")
            local weird = io.$unknown
            return weird
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        val pos = harness.positionOf("main.lua", unknown)
        assertFalse(harness.queries.completions(main, pos).any { it.label == unknown })
        val display = harness.queries.hover(main, pos)?.typeInfo?.displayName
        assertTrue(
            display == null || display.isBlank() || display == "unknown" || display == "any",
            "unknown io member should degrade; got '$display'"
        )
        harness.queries.diagnostics(main)
        val surface = assertNotNull(
            loadLua53().providerModules.values.single { it.moduleName == "io" }.file.moduleExportSurface
        )
        assertFalse(surface.members.any { it.name == unknown })
    }

    // ------------------------------------------------------------------
    // os provider inventory / path stability
    // ------------------------------------------------------------------

    @Test
    fun lua53_os_provider_exposes_full_resource_member_inventory() {
        val overlay = loadLua53()
        val provider = overlay.providerModules.values.single { it.moduleName == "os" }
        val surface = assertNotNull(provider.file.moduleExportSurface)
        val actual = surface.members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        val missing = EXPECTED_OS_MEMBERS - actual
        assertTrue(missing.isEmpty(), "os missing $missing (actual=$actual)")
        EXPECTED_OS_MEMBERS.forEach { member ->
            assertTrue(
                member in surface.moduleType.fields || member in surface.moduleType.methods,
                "os must publish '$member'"
            )
        }
    }

    @Test
    fun lua53_os_provider_path_and_module_name_are_stable() {
        val overlay = loadLua53()
        val entry = overlay.providerModules.entries.single { it.value.moduleName == "os" }
        assertEquals("__lua_std__/5.3/os.lua", entry.key.value)
        assertEquals("os", entry.value.moduleName)
        assertEquals(LuaVersion.LUA_5_3, overlay.version)
    }

    @Test
    fun require_os_resolves_overlay_provider() {
        val harness = overlayHarness(
            """
            local os = require("os")
            local value = os.time
            return value
            """.trimIndent()
        )
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "os")
        assertEquals(harness.path("__lua_std__/5.3/os.lua"), resolved.provider?.path)
        assertNotNull(resolved.exportSurface)
        assertTrue(resolved.exportSurface!!.members.any { it.name == "time" })
    }

    @Test
    fun known_os_members_surface_in_completion_and_hover() {
        val samples = listOf("clock", "date", "difftime", "execute", "exit", "getenv", "remove", "rename", "setlocale", "time", "tmpname")
        samples.forEach { name ->
            val harness = overlayHarness(
                """
                local os = require("os")
                local value = os.$name
                return value
                """.trimIndent()
            )
            val main = harness.path("main.lua")
            val pos = harness.positionOf("main.lua", name)
            val completions = harness.queries.completions(main, pos)
            val item = assertNotNull(
                completions.singleOrNull { it.label == name },
                "Expected completion for os.$name; labels=${completions.map { it.label }}"
            )
            assertTrue(
                item.kind == CompletionItemKind.FIELD || item.kind == CompletionItemKind.METHOD,
                "os.$name kind=${item.kind}"
            )
            val hover = assertNotNull(harness.queries.hover(main, pos))
            assertNotNull(hover.symbol)
            assertTrue(
                hover.symbol?.kind == SymbolKind.FIELD || hover.symbol?.kind == SymbolKind.METHOD
            )
            val display = hover.typeInfo?.displayName.orEmpty()
            assertFalse(display.isBlank())
            assertFalse(display == "unknown", "os.$name must not be unknown")
        }
    }

    @Test
    fun unknown_os_member_degrades_safely() {
        val unknown = "definitelyNotAnOsMember_xyz"
        val harness = overlayHarness(
            """
            local os = require("os")
            local weird = os.$unknown
            return weird
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        val pos = harness.positionOf("main.lua", unknown)
        assertFalse(harness.queries.completions(main, pos).any { it.label == unknown })
        val display = harness.queries.hover(main, pos)?.typeInfo?.displayName
        assertTrue(
            display == null || display.isBlank() || display == "unknown" || display == "any",
            "unknown os member should degrade; got '$display'"
        )
        harness.queries.diagnostics(main)
        val surface = assertNotNull(
            loadLua53().providerModules.values.single { it.moduleName == "os" }.file.moduleExportSurface
        )
        assertFalse(surface.members.any { it.name == unknown })
    }

    // ------------------------------------------------------------------
    // Combined + AndroLua
    // ------------------------------------------------------------------

    @Test
    fun io_and_os_providers_coexist_in_same_overlay() {
        val overlay = loadLua53()
        val names = overlay.providerModules.values.map { it.moduleName }.toSet()
        assertTrue("io" in names, "overlay must include io")
        assertTrue("os" in names, "overlay must include os")
    }

    @Test
    fun androlua_overlay_still_exposes_io_and_os_provider_members() {
        val overlay = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
        val ioProvider = assertNotNull(overlay.providerModules.values.singleOrNull { it.moduleName == "io" })
        val osProvider = assertNotNull(overlay.providerModules.values.singleOrNull { it.moduleName == "os" })
        val ioActual = assertNotNull(ioProvider.file.moduleExportSurface)
            .members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        val osActual = assertNotNull(osProvider.file.moduleExportSurface)
            .members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        assertTrue((EXPECTED_IO_MEMBERS - ioActual).isEmpty(), "AndroLua io missing ${EXPECTED_IO_MEMBERS - ioActual}")
        assertTrue((EXPECTED_OS_MEMBERS - osActual).isEmpty(), "AndroLua os missing ${EXPECTED_OS_MEMBERS - osActual}")
    }

    @Test
    fun mixed_io_os_require_document_surfaces_known_members() {
        val harness = overlayHarness(
            """
            local io = require("io")
            local os = require("os")
            local handle = io.open
            local now = os.time
            return handle, now
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        val openHover = assertNotNull(harness.queries.hover(main, harness.positionOf("main.lua", "open")))
        val timeHover = assertNotNull(harness.queries.hover(main, harness.positionOf("main.lua", "time")))
        assertFalse(openHover.typeInfo?.displayName.isNullOrBlank())
        assertFalse(openHover.typeInfo?.displayName == "unknown")
        assertFalse(timeHover.typeInfo?.displayName.isNullOrBlank())
        assertFalse(timeHover.typeInfo?.displayName == "unknown")
        harness.queries.diagnostics(main)
    }

    private fun overlayHarness(source: String) = WorkspaceSemanticHarness.build(
        "main.lua" to source,
        standardLibraryOverlayVersion = LuaVersion.LUA_5_3,
        engine = JvmWorkspaceEngine(
            workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.LUA_5_3, errorRecovery = false) }
        )
    )

    private fun loadLua53() =
        BuiltinOverlayLoader.load(LuaVersion.LUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }

    companion object {
        /** Top-level `io` members from lua53/io.lua (file methods live on the file class, not module). */
        private val EXPECTED_IO_MEMBERS = setOf(
            "close", "flush", "input", "lines", "open", "output", "popen",
            "read", "tmpfile", "type", "write", "stderr", "stdin", "stdout"
        )

        /** Full `os` inventory from lua53/os.lua. */
        private val EXPECTED_OS_MEMBERS = setOf(
            "clock", "date", "difftime", "execute", "exit", "getenv",
            "remove", "rename", "setlocale", "time", "tmpname"
        )
    }
}
