package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-205 corpus: BuiltinOverlay Lua 5.3 stdlib completeness audit.
 *
 * Scope is docs + this test only (no BuiltinOverlayLoader production edits).
 * Acceptance:
 * - Audit core Lua 5.3 stdlib modules/functions expected by overlay docs/resources.
 * - Missing surfaces listed as explicit failing assertions or documented gaps.
 *
 * Verification is review-owned (no Gradle here).
 *
 * Inventory reference (manual + std/lua53 star-dot-lua resources):
 * - basic library globals
 * - provider modules: coroutine, debug, io, math, os, package, string, table, utf8
 * - documented gaps: bit32 catalog-only, `module` catalog-only, package.seeall shim,
 *   moduleFieldNames seed subset, io `file` userdata methods
 *
 * See also: docs/semantic-compat.md section
 * "Lua 5.3 builtin overlay inventory (TASK-205)".
 */
class BuiltinOverlayLua53CompletenessTddTest {

    @Test
    fun lua53_provider_modules_match_core_stdlib_module_set() {
        val overlay = loadLua53()
        val moduleNames = overlay.providerModules.values.map { it.moduleName }.toSet()

        assertEquals(EXPECTED_PROVIDER_MODULES, moduleNames)
        EXPECTED_PROVIDER_MODULES.forEach { moduleName ->
            val path = overlay.providerModules.entries.single { it.value.moduleName == moduleName }.key
            assertEquals("__lua_std__/5.3/$moduleName.lua", path.value)
        }
    }

    @Test
    fun lua53_provider_export_surfaces_expose_full_resource_member_inventory() {
        val overlay = loadLua53()

        EXPECTED_MODULE_MEMBERS.forEach { (moduleName, expectedMembers) ->
            val provider = overlay.providerModules.values.single { it.moduleName == moduleName }
            val surface = assertNotNull(
                provider.file.moduleExportSurface,
                "Lua 5.3 provider '$moduleName' must expose a module export surface."
            )
            val actual = surface.members
                .map { it.name }
                .filter { it.isNotEmpty() }
                .toSet()

            val missing = expectedMembers - actual
            val unexpected = actual - expectedMembers - EXTRA_SURFACE_NAMES
            assertTrue(
                missing.isEmpty(),
                "Lua 5.3 module '$moduleName' missing expected members: $missing (actual=$actual)"
            )
            assertTrue(
                unexpected.isEmpty(),
                "Lua 5.3 module '$moduleName' has unexpected members beyond inventory/docs: $unexpected"
            )
            expectedMembers.forEach { member ->
                assertTrue(
                    member in surface.moduleType.fields || member in surface.moduleType.methods,
                    "Lua 5.3 module '$moduleName' must publish '$member' on moduleType fields/methods."
                )
            }
        }
    }

    @Test
    fun lua53_globals_catalog_contains_basic_library_and_module_globals() {
        val overlay = loadLua53()
        val globals = overlay.globals.globalNames

        EXPECTED_BASIC_GLOBALS.forEach { name ->
            assertTrue(name in globals, "Lua 5.3 basic global '$name' must be present in catalog globalNames.")
        }
        EXPECTED_MODULE_GLOBALS.forEach { name ->
            assertTrue(name in globals, "Lua 5.3 module global '$name' must be present in catalog globalNames.")
        }

        // Compatibility / legacy catalog entries that are not pure Lua 5.3 basic-library symbols.
        assertTrue("bit32" in globals, "Catalog still lists bit32 as a Lua 5.3 compatibility global.")
        assertTrue("module" in globals, "Catalog still lists legacy module() as a Lua 5.3 compatibility global.")
        assertFalse("warn" in globals, "Lua 5.3 overlay must not advertise Lua 5.4 warn().")
        assertFalse("loadstring" in globals, "Lua 5.3 overlay must not advertise removed loadstring alias as a catalog global.")
        assertFalse("getfenv" in globals, "Lua 5.3 overlay must not advertise 5.1 getfenv.")
        assertFalse("setfenv" in globals, "Lua 5.3 overlay must not advertise 5.1 setfenv.")
    }

    @Test
    fun lua53_globals_surface_exposes_basic_library_callables_from_resource() {
        val overlay = loadLua53()
        val surface = assertNotNull(overlay.globals.file.moduleExportSurface)
        val memberNames = surface.members.map { it.name }.toSet()

        EXPECTED_BASIC_CALLABLE_GLOBALS.forEach { name ->
            assertTrue(
                name in memberNames,
                "Globals surface must expose basic callable '$name' from lua53/global.lua (actual=$memberNames)."
            )
        }
        assertTrue("_G" in memberNames || "_G" in overlay.globals.globalNames)
        assertTrue("_VERSION" in memberNames || "_VERSION" in overlay.globals.globalNames)

        // Module tables are catalogued as globals even though they are not declared as functions
        // in global.lua; provider modules own their export surfaces.
        EXPECTED_MODULE_GLOBALS.forEach { name ->
            assertTrue(
                name in overlay.globals.globalNames,
                "Module table global '$name' must remain catalogued."
            )
        }
    }

    /**
     * Documented gap: catalog lists bit32 + moduleFieldNames seed `band`, but there is no
     * bit32 provider resource/surface under `__lua_std__/5.3/bit32.lua`.
     * Full bit32 API is therefore not audit-complete for completion/hover via require("bit32").
     */
    @Test
    fun documented_gap_bit32_is_catalog_only_without_provider_surface() {
        val overlay = loadLua53()

        assertTrue("bit32" in overlay.globals.globalNames)
        assertEquals(setOf("band"), overlay.globals.moduleFieldNames["bit32"])
        assertFalse(
            overlay.providerModules.values.any { it.moduleName == "bit32" },
            "Documented gap: bit32 has catalog metadata but no provider module resource/surface."
        )

        // Explicit incomplete-surface assertion kept red-friendly if product later adds a provider:
        // until then, require("bit32") cannot resolve a std overlay path.
        val bit32Provider = overlay.providerModules.values.singleOrNull { it.moduleName == "bit32" }
        assertTrue(
            bit32Provider == null,
            "If this fails, update docs/semantic-compat.md bit32 gap (provider now exists)."
        )
        assertFalse(
            EXPECTED_BIT32_MEMBERS.all { member ->
                overlay.globals.moduleFieldNames["bit32"].orEmpty().contains(member)
            },
            "Documented gap: moduleFieldNames for bit32 is only the seed {band}, not full bit32 inventory."
        )
        EXPECTED_BIT32_MEMBERS.forEach { member ->
            if (member != "band") {
                assertFalse(
                    member in overlay.globals.moduleFieldNames["bit32"].orEmpty(),
                    "bit32 seed must not silently expand to full inventory ($member)."
                )
            }
        }
    }

    /**
     * Documented gap: catalog globalNames includes legacy `module`, but lua53/global.lua does not
     * declare a documented `function module(...)` surface member.
     */
    @Test
    fun documented_gap_module_global_is_catalog_only_without_resource_declaration() {
        val overlay = loadLua53()
        val surface = assertNotNull(overlay.globals.file.moduleExportSurface)
        val memberNames = surface.members.map { it.name }.toSet()

        assertTrue("module" in overlay.globals.globalNames)
        // Compatibility assignment may inject `module = module` into the globals source without docs.
        // The resource file itself does not provide a typed basic-library declaration.
        assertFalse(
            "module" in EXPECTED_BASIC_CALLABLE_GLOBALS,
            "module() is not part of the Lua 5.3 basic library resource inventory."
        )
        // Lock the gap: either absent from surface, or present only as untyped compatibility fill.
        if ("module" in memberNames) {
            val moduleMember = surface.members.single { it.name == "module" }
            assertTrue(
                moduleMember.type.displayName == "unknown" ||
                    moduleMember.type.displayName == "any" ||
                    moduleMember.type.displayName.contains("fun"),
                "If module becomes a fully documented basic global, refresh the semantic-compat inventory."
            )
        }
    }

    /**
     * Documented gap: package.seeall is injected via compatibilityGlobalsSource / moduleFieldNames
     * but is not a member of the package.lua resource inventory.
     */
    @Test
    fun documented_gap_package_seeall_is_compatibility_shim_not_package_resource_member() {
        val overlay = loadLua53()
        val packageProvider = overlay.providerModules.values.single { it.moduleName == "package" }
        val packageSurface = assertNotNull(packageProvider.file.moduleExportSurface)
        val packageMembers = packageSurface.members.map { it.name }.toSet()

        assertTrue("seeall" in overlay.globals.moduleFieldNames.getValue("package"))
        assertFalse(
            "seeall" in EXPECTED_MODULE_MEMBERS.getValue("package"),
            "package.seeall is not part of the Lua 5.3 package resource inventory."
        )
        assertFalse(
            "seeall" in packageMembers,
            "Documented gap: package provider surface lacks seeall; only compatibility metadata lists it."
        )
        assertFalse(
            "loaders" in packageMembers,
            "Lua 5.3 package.searchers replaced 5.1 package.loaders; loaders must not appear."
        )
    }

    /**
     * Documented gap: globals.moduleFieldNames is a compact seed map (one/few fields per module),
     * not the full stdlib member inventory. Full inventory lives on provider export surfaces.
     */
    @Test
    fun documented_gap_moduleFieldNames_is_seed_subset_not_full_inventory() {
        val overlay = loadLua53()
        val seeds = overlay.globals.moduleFieldNames

        assertEquals(
            setOf(
                "bit32", "coroutine", "debug", "io", "math", "os", "package", "string", "table", "utf8"
            ),
            seeds.keys
        )

        // Seed expectations currently shipped by lua53Catalog().
        assertEquals(setOf("band"), seeds.getValue("bit32"))
        assertEquals(setOf("create"), seeds.getValue("coroutine"))
        assertEquals(setOf("traceback"), seeds.getValue("debug"))
        assertEquals(setOf("open"), seeds.getValue("io"))
        assertEquals(setOf("abs"), seeds.getValue("math"))
        assertEquals(setOf("clock"), seeds.getValue("os"))
        assertEquals(setOf("loaded", "searchpath", "seeall"), seeds.getValue("package"))
        assertEquals(setOf("format"), seeds.getValue("string"))
        assertEquals(setOf("insert"), seeds.getValue("table"))
        assertEquals(setOf("len"), seeds.getValue("utf8"))

        EXPECTED_MODULE_MEMBERS.forEach { (moduleName, fullMembers) ->
            val seed = seeds.getValue(moduleName)
            assertTrue(
                seed.size < fullMembers.size,
                "Documented gap: moduleFieldNames['$moduleName'] must remain a seed subset of the full inventory."
            )
            assertTrue(
                fullMembers.containsAll(seed - setOf("seeall")),
                "Seed fields for '$moduleName' (except seeall shim) must be subset of resource inventory."
            )
        }
    }

    /**
     * Documented gap: Lua 5.3 file userdata methods are declared on a `file` class in io.lua,
     * not as `io.*` provider members. They are intentionally outside the io module inventory.
     */
    @Test
    fun documented_gap_io_file_userdata_methods_are_not_io_module_members() {
        val overlay = loadLua53()
        val ioProvider = overlay.providerModules.values.single { it.moduleName == "io" }
        val ioSurface = assertNotNull(ioProvider.file.moduleExportSurface)
        val ioMembers = ioSurface.members.map { it.name }.toSet()

        EXPECTED_FILE_USERDATA_METHODS.forEach { method ->
            assertFalse(
                method in ioMembers && method !in EXPECTED_MODULE_MEMBERS.getValue("io"),
                "file userdata method '$method' must not be mis-attributed unless modeled as io member."
            )
        }
        // None of the colon-style file methods appear as top-level io members in the inventory.
        // Note: close/flush/lines/read/write also exist as io.* helpers; those are not file-only.
        val fileOnlyMethods = setOf("seek", "setvbuf")
        assertTrue(ioMembers.intersect(fileOnlyMethods).isEmpty())
        assertTrue(ioMembers.intersect(EXPECTED_FILE_USERDATA_METHODS - EXPECTED_MODULE_MEMBERS.getValue("io")).isEmpty())
    }

    @Test
    fun lua53_overlay_version_and_globals_path_are_stable() {
        val overlay = loadLua53()
        assertEquals(LuaVersion.LUA_5_3, overlay.version)
        assertEquals("__lua_std__/5.3/_G.lua", overlay.globals.path.value)
    }

    @Test
    fun lua53_resource_inventory_excludes_removed_pre53_stdlib_symbols() {
        val overlay = loadLua53()

        // Symbols removed or replaced before/at Lua 5.3 must not appear on provider surfaces.
        val forbiddenByModule = mapOf(
            "math" to setOf("atan2", "cosh", "sinh", "tanh", "pow", "frexp", "ldexp", "log10"),
            "table" to setOf("maxn", "foreach", "foreachi", "getn", "setn"),
            "package" to setOf("loaders", "seeall"),
            "string" to setOf("gfind"),
            "debug" to setOf("getfenv", "setfenv")
        )
        forbiddenByModule.forEach { (moduleName, forbidden) ->
            val provider = overlay.providerModules.values.single { it.moduleName == moduleName }
            val surface = assertNotNull(provider.file.moduleExportSurface)
            val actual = surface.members.map { it.name }.toSet()
            val leaked = actual.intersect(forbidden)
            assertTrue(
                leaked.isEmpty(),
                "Lua 5.3 module '$moduleName' unexpectedly exposes pre-5.3 symbols: $leaked"
            )
        }
    }

    private fun loadLua53() =
        BuiltinOverlayLoader.load(LuaVersion.LUA_5_3) { _, _ -> WorkspaceSnapshot.FileSnapshot() }

    companion object {
        private val EXTRA_SURFACE_NAMES: Set<String> = emptySet()

        private val EXPECTED_PROVIDER_MODULES = setOf(
            "coroutine", "debug", "io", "math", "os", "package", "string", "table", "utf8"
        )

        private val EXPECTED_BASIC_CALLABLE_GLOBALS = setOf(
            "assert", "collectgarbage", "dofile", "error", "getmetatable", "ipairs", "load", "loadfile",
            "next", "pairs", "pcall", "print", "rawequal", "rawget", "rawlen", "rawset", "require",
            "select", "setmetatable", "tonumber", "tostring", "type", "xpcall"
        )

        private val EXPECTED_BASIC_GLOBALS = EXPECTED_BASIC_CALLABLE_GLOBALS + setOf("_G", "_VERSION")

        private val EXPECTED_MODULE_GLOBALS = setOf(
            "coroutine", "debug", "io", "math", "os", "package", "string", "table", "utf8"
        )

        /**
         * Full member inventory mirrored from
         * `src/commonMain/resources/.../std/lua53/{module}.lua` and docs/semantic-compat.md.
         */
        private val EXPECTED_MODULE_MEMBERS: Map<String, Set<String>> = linkedMapOf(
            "coroutine" to setOf(
                "create", "isyieldable", "resume", "running", "status", "wrap", "yield"
            ),
            "debug" to setOf(
                "debug", "gethook", "getinfo", "getlocal", "getmetatable", "getregistry", "getupvalue",
                "getuservalue", "sethook", "setlocal", "setmetatable", "setupvalue", "setuservalue",
                "traceback", "upvalueid", "upvaluejoin"
            ),
            "io" to setOf(
                "close", "flush", "input", "lines", "open", "output", "popen", "read", "stderr",
                "stdin", "stdout", "tmpfile", "type", "write"
            ),
            "math" to setOf(
                "abs", "acos", "asin", "atan", "ceil", "cos", "deg", "exp", "floor", "fmod", "huge",
                "log", "max", "maxinteger", "min", "mininteger", "modf", "pi", "rad", "random",
                "randomseed", "sin", "sqrt", "tan", "tointeger", "type", "ult"
            ),
            "os" to setOf(
                "clock", "date", "difftime", "execute", "exit", "getenv", "remove", "rename",
                "setlocale", "time", "tmpname"
            ),
            "package" to setOf(
                "config", "cpath", "loaded", "loadlib", "path", "preload", "searchers", "searchpath"
            ),
            "string" to setOf(
                "byte", "char", "dump", "find", "format", "gmatch", "gsub", "len", "lower", "match",
                "pack", "packsize", "rep", "reverse", "sub", "unpack", "upper"
            ),
            "table" to setOf(
                "concat", "insert", "move", "pack", "remove", "sort", "unpack"
            ),
            "utf8" to setOf(
                "char", "charpattern", "codepoint", "codes", "len", "offset"
            )
        )

        private val EXPECTED_BIT32_MEMBERS = setOf(
            "arshift", "band", "bnot", "bor", "btest", "bxor", "extract", "lrotate", "lshift",
            "replace", "rrotate", "rshift"
        )

        private val EXPECTED_FILE_USERDATA_METHODS = setOf(
            "close", "flush", "lines", "read", "seek", "setvbuf", "write"
        )
    }
}
