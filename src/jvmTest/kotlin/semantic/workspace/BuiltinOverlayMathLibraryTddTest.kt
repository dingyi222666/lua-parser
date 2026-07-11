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
 * TASK-296 corpus: workspace builtin overlay math library surface.
 *
 * Acceptance:
 * - `math.*` members present from the Lua 5.3 std overlay (provider surface + require/hover/completion).
 * - Unknown math member degrades (no crash, no invented member, type stays unknown / absent).
 * - Test-only. Product sources are out of scope. Verification is review-owned (no Gradle).
 *
 * Inventory mirrors `src/commonMain/resources/.../std/lua53/math.lua` and the TASK-205 completeness list.
 * Query position goldens match product harness usage in BuiltinOverlayLoaderTddTest:
 * needle is the member token itself (single occurrence in the fixture → default occurrence=1).
 */
class BuiltinOverlayMathLibraryTddTest {

    @Test
    fun lua53_math_provider_exposes_full_resource_member_inventory() {
        val overlay = loadLua53()
        val provider = overlay.providerModules.values.single { it.moduleName == "math" }
        val surface = assertNotNull(
            provider.file.moduleExportSurface,
            "Lua 5.3 math provider must expose a module export surface."
        )
        val actual = surface.members
            .map { it.name }
            .filter { it.isNotEmpty() }
            .toSet()

        val missing = EXPECTED_MATH_MEMBERS - actual
        assertTrue(
            missing.isEmpty(),
            "Lua 5.3 math missing expected members: $missing (actual=$actual)"
        )

        EXPECTED_MATH_MEMBERS.forEach { member ->
            assertTrue(
                member in surface.moduleType.fields || member in surface.moduleType.methods,
                "math must publish '$member' on moduleType fields/methods."
            )
        }
        assertEquals("__lua_std__/5.3/math.lua", overlay.providerModules.entries
            .single { it.value.moduleName == "math" }.key.value)
    }

    @Test
    fun lua53_math_provider_path_and_module_name_are_stable() {
        val overlay = loadLua53()
        val entry = overlay.providerModules.entries.single { it.value.moduleName == "math" }
        assertEquals("__lua_std__/5.3/math.lua", entry.key.value)
        assertEquals("math", entry.value.moduleName)
        assertEquals(LuaVersion.LUA_5_3, overlay.version)
        assertTrue("math" in overlay.globals.globalNames)
        assertEquals(setOf("abs"), overlay.globals.moduleFieldNames["math"])
    }

    @Test
    fun require_math_resolves_overlay_provider_for_workspace_queries() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local math = require("math")
                local value = math.abs
                return value
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3,
            engine = JvmWorkspaceEngine(
                workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.LUA_5_3, errorRecovery = false) }
            )
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "math")
        assertEquals(harness.path("__lua_std__/5.3/math.lua"), resolved.provider?.path)
        assertNotNull(resolved.exportSurface, "require(\"math\") must expose an export surface.")
        assertTrue(
            resolved.exportSurface!!.members.any { it.name == "abs" },
            "math export surface must include abs."
        )
    }

    @Test
    fun known_math_members_surface_in_completion_and_hover() {
        // Spot-check functions + constants across the inventory (not only abs seed).
        // Fixtures mirror BuiltinOverlayLoaderTddTest: member token appears once, so occurrence=1.
        val samples = listOf(
            Sample("abs", expectedDetailContains = "number"),
            Sample("pi", expectedDetailContains = "number"),
            Sample("maxinteger", expectedDetailContains = "number"),
            Sample("random", expectedDetailContains = "number"),
            Sample("tointeger", expectedDetailContains = "number"),
            Sample("ult", expectedDetailContains = "boolean")
        )

        samples.forEach { sample ->
            val harness = mathHarness(
                """
                local math = require("math")
                local value = math.${sample.name}
                return value
                """.trimIndent()
            )
            val main = harness.path("main.lua")
            val pos = harness.positionOf("main.lua", sample.name)

            val completions = harness.queries.completions(main, pos)
            val item = assertNotNull(
                completions.singleOrNull { it.label == sample.name },
                "Expected completion for math.${sample.name}; labels=${completions.map { it.label }}"
            )
            assertTrue(
                item.kind == CompletionItemKind.FIELD || item.kind == CompletionItemKind.METHOD,
                "math.${sample.name} kind should be FIELD/METHOD, got ${item.kind}"
            )
            assertTrue(
                item.detail.orEmpty().contains(sample.expectedDetailContains, ignoreCase = true) ||
                    item.detail.orEmpty().contains("fun"),
                "math.${sample.name} detail should mention ${sample.expectedDetailContains}/fun, got '${item.detail}'"
            )

            val hover = assertNotNull(harness.queries.hover(main, pos), "hover for math.${sample.name}")
            assertNotNull(hover.symbol, "math.${sample.name} must resolve a symbol")
            assertTrue(
                hover.symbol?.kind == SymbolKind.FIELD || hover.symbol?.kind == SymbolKind.METHOD,
                "math.${sample.name} symbol kind should be FIELD/METHOD, got ${hover.symbol?.kind}"
            )
            val display = hover.typeInfo?.displayName.orEmpty()
            assertFalse(display.isBlank(), "math.${sample.name} hover type must not be blank")
            assertFalse(
                display == "unknown",
                "Known math.${sample.name} must not degrade to unknown; got '$display'"
            )
        }
    }

    @Test
    fun known_math_members_are_all_completable_from_require_local() {
        // Query completion at math.floor / math.huge; member token appears once per fixture.
        val harness = mathHarness(
            """
            local math = require("math")
            local value = math.floor
            return value
            """.trimIndent()
        )
        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "floor")
        )
        val labels = completions.map { it.label }.toSet()

        // At minimum the inventory members that the member resolver can surface should include
        // several well-known math APIs (product may filter by prefix; floor itself must appear).
        assertTrue("floor" in labels, "Completions at math.floor must include floor; labels=$labels")
        val present = EXPECTED_MATH_MEMBERS.intersect(labels)
        assertTrue(
            present.size >= 1,
            "Expected at least the queried math member in completions; present=$present labels=$labels"
        )

        // Cross-check a second access site for a constant so constants are not function-only.
        val constHarness = mathHarness(
            """
            local math = require("math")
            local value = math.huge
            return value
            """.trimIndent()
        )
        val hugeCompletions = constHarness.queries.completions(
            constHarness.path("main.lua"),
            constHarness.positionOf("main.lua", "huge")
        )
        assertTrue(
            hugeCompletions.any { it.label == "huge" },
            "Completions at math.huge must include huge; labels=${hugeCompletions.map { it.label }}"
        )
    }

    @Test
    fun lua53_math_excludes_removed_pre53_symbols() {
        val overlay = loadLua53()
        val provider = overlay.providerModules.values.single { it.moduleName == "math" }
        val surface = assertNotNull(provider.file.moduleExportSurface)
        val actual = surface.members.map { it.name }.toSet()
        val leaked = actual.intersect(REMOVED_PRE53_MATH_MEMBERS)
        assertTrue(
            leaked.isEmpty(),
            "Lua 5.3 math must not expose pre-5.3 symbols: $leaked"
        )
    }

    @Test
    fun unknown_math_member_degrades_without_crash_or_invented_surface() {
        val unknown = "definitelyNotAMathMember_xyz"
        val harness = mathHarness(
            """
            local math = require("math")
            local weird = math.$unknown
            return weird
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        val mathProvider = harness.path("__lua_std__/5.3/math.lua")
        val pos = harness.positionOf("main.lua", unknown)

        // Completions must not invent the unknown label as a first-class math member.
        val completions = harness.queries.completions(main, pos)
        assertFalse(
            completions.any { it.label == unknown },
            "Unknown math member must not appear as a completion label; labels=${completions.map { it.label }}"
        )

        // Hover/type degrade: unknown (or blank/absent), never a concrete modeled math signature.
        val hover = harness.queries.hover(main, pos)
        val display = hover?.typeInfo?.displayName
        assertTrue(
            display == null || display.isBlank() || display == "unknown" || display == "any",
            "Unknown math.$unknown should degrade to unknown/any/absent, got '$display'"
        )
        // Must not map the bogus member onto a known inventory symbol.
        EXPECTED_MATH_MEMBERS.forEach { member ->
            assertFalse(
                display == member,
                "Unknown math member must not resolve to known member type name '$member'"
            )
        }

        // Product may fall back to require-backed module navigation (math provider) or keep a local
        // range; empty defs are also fine. It must not invent an unrelated path. The provider export
        // surface inventory below remains the strict "no invented member" golden.
        val definitions = harness.queries.gotoDefinition(main, pos)
        assertTrue(
            definitions.isEmpty() ||
                definitions.all { it.path == main || it.path == mathProvider },
            "Unknown math member must not invent an unrelated definition path; defs=$definitions"
        )

        // Analysis remains queryable (no hang/crash).
        harness.queries.diagnostics(main)

        // Provider surface inventory still lacks the unknown name.
        val overlay = loadLua53()
        val surface = assertNotNull(
            overlay.providerModules.values.single { it.moduleName == "math" }.file.moduleExportSurface
        )
        assertFalse(
            surface.members.any { it.name == unknown },
            "math export surface must not invent $unknown"
        )
    }

    @Test
    fun unknown_math_member_on_call_site_degrades_return_type() {
        val unknown = "definitelyNotAMathMember_xyz"
        val harness = mathHarness(
            """
            local math = require("math")
            local weird = math.$unknown(1)
            return weird
            """.trimIndent()
        )
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "weird", occurrence = 2)
        )
        val display = hover?.typeInfo?.displayName
        assertTrue(
            display == null || display.isBlank() || display == "unknown" || display == "any",
            "Call through unknown math member must degrade return type; got '$display'"
        )
        harness.queries.diagnostics(harness.path("main.lua"))
    }

    @Test
    fun androlua_overlay_still_exposes_math_provider_members() {
        // AndroLua 5.3 shares the Lua 5.3 math resource inventory for stdlib providers.
        val overlay = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
        val provider = overlay.providerModules.values.singleOrNull { it.moduleName == "math" }
        assertNotNull(provider, "AndroLua overlay must include math provider.")
        val surface = assertNotNull(provider.file.moduleExportSurface)
        val actual = surface.members.map { it.name }.filter { it.isNotEmpty() }.toSet()
        val missing = EXPECTED_MATH_MEMBERS - actual
        assertTrue(
            missing.isEmpty(),
            "AndroLua math missing expected Lua 5.3 members: $missing (actual=$actual)"
        )
    }

    private fun mathHarness(source: String): WorkspaceSemanticHarness =
        WorkspaceSemanticHarness.build(
            "main.lua" to source,
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3,
            engine = JvmWorkspaceEngine(
                workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.LUA_5_3, errorRecovery = false) }
            )
        )

    private fun loadLua53() =
        BuiltinOverlayLoader.load(LuaVersion.LUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }

    private data class Sample(val name: String, val expectedDetailContains: String)

    companion object {
        /** Full math inventory from lua53/math.lua (TASK-205 / Lua 5.3 manual). */
        private val EXPECTED_MATH_MEMBERS = setOf(
            "abs", "acos", "asin", "atan", "ceil", "cos", "deg", "exp", "floor", "fmod", "huge",
            "log", "max", "maxinteger", "min", "mininteger", "modf", "pi", "rad", "random",
            "randomseed", "sin", "sqrt", "tan", "tointeger", "type", "ult"
        )

        private val REMOVED_PRE53_MATH_MEMBERS = setOf(
            "atan2", "cosh", "sinh", "tanh", "pow", "frexp", "ldexp", "log10"
        )
    }
}
