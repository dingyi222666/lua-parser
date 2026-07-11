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
 * TASK-367 corpus: workspace builtin overlay coroutine library surface.
 *
 * Acceptance:
 * - coroutine.* members complete/hover from builtin overlay.
 * - Unknown coroutine members degrade safely.
 * - Test-only. Verification is review-owned (no Gradle).
 *
 * Inventory mirrors `src/commonMain/resources/.../std/lua53/coroutine.lua`
 * and the TASK-205 / BuiltinOverlayLua53CompletenessTddTest list.
 */
class BuiltinOverlayCoroutineLibraryTddTest {

    @Test
    fun lua53_coroutine_provider_exposes_full_resource_member_inventory() {
        val overlay = loadLua53()
        val provider = overlay.providerModules.values.single { it.moduleName == "coroutine" }
        val surface = assertNotNull(provider.file.moduleExportSurface)
        val actual = surface.members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        val missing = EXPECTED_COROUTINE_MEMBERS - actual
        assertTrue(missing.isEmpty(), "coroutine missing $missing (actual=$actual)")
        EXPECTED_COROUTINE_MEMBERS.forEach { member ->
            assertTrue(
                member in surface.moduleType.fields || member in surface.moduleType.methods,
                "coroutine must publish '$member'"
            )
        }
    }

    @Test
    fun lua53_coroutine_provider_path_and_module_name_are_stable() {
        val overlay = loadLua53()
        val entry = overlay.providerModules.entries.single { it.value.moduleName == "coroutine" }
        assertEquals("__lua_std__/5.3/coroutine.lua", entry.key.value)
        assertEquals("coroutine", entry.value.moduleName)
        assertEquals(LuaVersion.LUA_5_3, overlay.version)
        assertTrue("coroutine" in overlay.globals.globalNames)
        assertEquals(setOf("create"), overlay.globals.moduleFieldNames["coroutine"])
    }

    @Test
    fun require_coroutine_resolves_overlay_provider() {
        val harness = coroutineHarness(
            """
            local coroutine = require("coroutine")
            local value = coroutine.create
            return value
            """.trimIndent()
        )
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "coroutine")
        assertEquals(harness.path("__lua_std__/5.3/coroutine.lua"), resolved.provider?.path)
        assertNotNull(resolved.exportSurface)
        assertTrue(resolved.exportSurface!!.members.any { it.name == "create" })
    }

    @Test
    fun known_coroutine_members_surface_in_completion_and_hover() {
        val samples = listOf("create", "resume", "yield", "wrap", "status", "running", "isyieldable")
        samples.forEach { name ->
            val harness = coroutineHarness(
                """
                local coroutine = require("coroutine")
                local value = coroutine.$name
                return value
                """.trimIndent()
            )
            val main = harness.path("main.lua")
            val pos = harness.positionOf("main.lua", name)
            val completions = harness.queries.completions(main, pos)
            val item = assertNotNull(
                completions.singleOrNull { it.label == name },
                "Expected completion for coroutine.$name; labels=${completions.map { it.label }}"
            )
            assertTrue(
                item.kind == CompletionItemKind.FIELD || item.kind == CompletionItemKind.METHOD,
                "coroutine.$name kind=${item.kind}"
            )
            val hover = assertNotNull(harness.queries.hover(main, pos))
            assertNotNull(hover.symbol)
            assertTrue(
                hover.symbol?.kind == SymbolKind.FIELD || hover.symbol?.kind == SymbolKind.METHOD
            )
            val display = hover.typeInfo?.displayName.orEmpty()
            assertFalse(display.isBlank())
            assertFalse(display == "unknown", "coroutine.$name must not be unknown")
        }
    }

    @Test
    fun known_coroutine_members_are_completable_from_require_local() {
        val harness = coroutineHarness(
            """
            local coroutine = require("coroutine")
            local value = coroutine.yield
            return value
            """.trimIndent()
        )
        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "yield")
        )
        val labels = completions.map { it.label }.toSet()
        assertTrue("yield" in labels, "Completions at coroutine.yield must include yield; labels=$labels")
        val present = EXPECTED_COROUTINE_MEMBERS.intersect(labels)
        assertTrue(
            present.size >= 1,
            "Expected at least the queried coroutine member in completions; present=$present labels=$labels"
        )
    }

    @Test
    fun unknown_coroutine_member_degrades_safely() {
        val unknown = "definitelyNotACoroutineMember_xyz"
        val harness = coroutineHarness(
            """
            local coroutine = require("coroutine")
            local weird = coroutine.$unknown
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
            "unknown coroutine member should degrade; got '$display'"
        )
        harness.queries.diagnostics(main)
        val surface = assertNotNull(
            loadLua53().providerModules.values.single { it.moduleName == "coroutine" }.file.moduleExportSurface
        )
        assertFalse(surface.members.any { it.name == unknown })
    }

    @Test
    fun androlua_overlay_still_exposes_coroutine_provider_members() {
        val overlay = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
        val provider = assertNotNull(overlay.providerModules.values.singleOrNull { it.moduleName == "coroutine" })
        val surface = assertNotNull(provider.file.moduleExportSurface)
        val actual = surface.members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        assertTrue(
            (EXPECTED_COROUTINE_MEMBERS - actual).isEmpty(),
            "AndroLua coroutine missing ${EXPECTED_COROUTINE_MEMBERS - actual}"
        )
    }

    private fun coroutineHarness(source: String) = WorkspaceSemanticHarness.build(
        "main.lua" to source,
        standardLibraryOverlayVersion = LuaVersion.LUA_5_3,
        engine = JvmWorkspaceEngine(
            workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.LUA_5_3, errorRecovery = false) }
        )
    )

    private fun loadLua53() =
        BuiltinOverlayLoader.load(LuaVersion.LUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }

    companion object {
        /** Full coroutine inventory from lua53/coroutine.lua (TASK-205 / Lua 5.3 manual). */
        private val EXPECTED_COROUTINE_MEMBERS = setOf(
            "create", "isyieldable", "resume", "running", "status", "wrap", "yield"
        )
    }
}
