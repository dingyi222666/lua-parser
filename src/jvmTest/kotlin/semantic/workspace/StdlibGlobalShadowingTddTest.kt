package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Lua standard library must survive the mounted Android framework catalog.
 *
 * android.jar resource classes reflect with lowercase simple names (android.R$string →
 * "string", R$layout → "layout") and used to claim those module names — outranking the
 * stdlib overlay provider — so the bare global `string` collapsed to a `__class`-only
 * shell: `string.` offered no members and `string.format` lost its signature. Same class
 * of conflict as the earlier android.app.Dialog vs require("Dialog") fix, now covering
 * the Lua std module names; bit32 additionally had no library surface at all.
 */
class StdlibGlobalShadowingTddTest {

    @Test
    fun androlua_overlay_has_a_single_string_module_and_it_is_the_stdlib() {
        val overlay = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
        val stringModules = overlay.providerModules.filterValues { it.moduleName == "string" }
        assertEquals(
            1,
            stringModules.size,
            "exactly one provider may claim module 'string'; got " +
                stringModules.entries.joinToString { (path, _) -> path.value }
        )
        assertEquals("__lua_std__/androlua5.3/string.lua", stringModules.keys.single().value)
    }

    @Test
    fun stdlib_module_names_are_never_claimed_by_framework_resources() {
        val overlay = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
        val stdNames = setOf("bit32", "coroutine", "debug", "io", "math", "os", "package", "string", "table", "utf8")
        overlay.providerModules.forEach { (path, provider) ->
            if (path.value.startsWith("__jvm__/")) {
                assertTrue(
                    provider.moduleName !in stdNames,
                    "framework resource ${path.value} must not claim std module name '${provider.moduleName}'"
                )
            }
        }
    }

    @Test
    fun bare_global_string_member_completion_and_hover_signature() {
        val harness = androluaHarness(
            """
            local s = string.format("%d", 1)
            local t = string.upper("a")
            return s, t
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        val afterDot = positionAfter(harness, "string.format", "string.")
        val labels = harness.queries.completions(main, afterDot).map { it.label }
        EXPECTED_STRING_MEMBERS.forEach { member ->
            assertTrue(member in labels, "string.$member must complete; got $labels")
        }
        val hover = assertNotNull(harness.queries.hover(main, afterDot))
        assertTrue(
            hover.typeInfo?.displayName.orEmpty() != "unknown",
            "string.format hover must carry a signature; got ${hover.typeInfo?.displayName}"
        )
    }

    @Test
    fun bit32_library_surface_completes_members() {
        val harness = androluaHarness(
            """
            local masked = bit32.band(1, 2)
            return masked
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        val afterDot = positionAfter(harness, "bit32.band", "bit32.")
        val labels = harness.queries.completions(main, afterDot).map { it.label }
        EXPECTED_BIT32_MEMBERS.forEach { member ->
            assertTrue(member in labels, "bit32.$member must complete; got $labels")
        }
    }

    // --- helpers -----------------------------------------------------------------

    private fun positionAfter(
        harness: WorkspaceSemanticHarness,
        needle: String,
        prefix: String
    ): Position {
        val base = harness.positionOf("main.lua", needle)
        return Position(base.line, base.column + prefix.length)
    }

    private fun androluaHarness(source: String) = WorkspaceSemanticHarness.build(
        "main.lua" to source,
        standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3,
        engine = JvmWorkspaceEngine(
            workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false) }
        )
    )

    private companion object {
        private val EXPECTED_STRING_MEMBERS = setOf(
            "byte", "char", "find", "format", "gmatch", "gsub", "len", "lower",
            "match", "rep", "reverse", "sub", "upper"
        )

        private val EXPECTED_BIT32_MEMBERS = setOf(
            "arshift", "band", "bnot", "bor", "btest", "bxor", "lshift", "rshift", "tobit", "tohex"
        )
    }
}
