package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.LegacyModuleEnvironmentPass
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportCollector
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModuleExportCollectorTest {
    @Test
    fun exported_functions_distinguish_zero_values_from_explicit_nil_before_semantic_analysis() {
        val surface = collect("returns.lua", """
            return {
                idle = function() end,
                stop = function() return end,
                explicit = function() return nil end,
                nested = function() if flag then return end end,
                outer = function() local child = function() return nil end end,
            }
        """.trimIndent())
        for (name in listOf("idle", "stop", "nested", "outer")) {
            assertEquals(PrimitiveType.VOID,
                assertIs<FunctionType>(surface.moduleType.fields.getValue(name)).returnType, name)
        }
        val nilType = assertIs<LiteralType>(
            assertIs<FunctionType>(surface.moduleType.fields.getValue("explicit")).returnType)
        assertEquals(PrimitiveType.NIL, nilType.baseType)
    }

    @Test
    fun layout_exports_keep_real_fields_and_module_identity() {
        val surface = collect("ui/main.aly", "return {title = 'hello'}")
        assertEquals("ui.main", surface.moduleType.moduleName)
        assertEquals("LuaLayoutSpec", surface.moduleType.displayName)
        assertEquals(setOf("title"), surface.moduleType.fields.keys)
        assertEquals(listOf("title"), surface.members.map { it.name })
    }

    @Test
    fun legacy_module_mode_exports_bare_assignment_and_function() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = """
                module("pkg.runtime")
                value = 1
                function run() end
            """.trimIndent()
        )

        assertEquals(ModuleExportSurface.SourceForm.LEGACY_IMPLICIT, surface.sourceForm)
        assertEquals(1, (surface.moduleType.fields.getValue("value") as LiteralType).value)
        assertIs<FunctionType>(surface.moduleType.fields.getValue("run"))
    }

    @Test
    fun local__m_table_return_is_collected() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = """
                local _M = {}
                _M.value = 1
                function _M.run() end
                return _M
            """.trimIndent()
        )

        assertEquals(1, (surface.moduleType.fields.getValue("value") as LiteralType).value)
        assertIs<FunctionType>(surface.moduleType.fields.getValue("run"))
    }

    @Test
    fun local_m_table_return_is_collected() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = """
                local M = {}
                M.value = 1
                return M
            """.trimIndent()
        )

        assertEquals(1, (surface.moduleType.fields.getValue("value") as LiteralType).value)
    }

    @Test
    fun local_m_table_literal_return_keeps_initializer_fields() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = """
                local M = { value = 1 }
                return M
            """.trimIndent()
        )

        assertEquals(1, (surface.moduleType.fields.getValue("value") as LiteralType).value)
    }

    @Test
    fun aliased_return_keeps_nested_initializer_fields() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = """
                local M = { nested = { enabled = true } }
                local exports = M
                return exports
            """.trimIndent()
        )

        val nested = assertIs<TableType>(surface.moduleType.fields.getValue("nested"))
        assertEquals(true, (nested.fields.getValue("enabled") as LiteralType).value)
    }

    @Test
    fun return_identifier_merges_initializer_and_later_writes() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = """
                local M = { value = 1 }
                M.extra = 2
                return M
            """.trimIndent()
        )

        assertEquals(1, (surface.moduleType.fields.getValue("value") as LiteralType).value)
        assertEquals(2, (surface.moduleType.fields.getValue("extra") as LiteralType).value)
    }

    @Test
    fun direct_return_table_literal_is_collected() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = "return { value = 1, nested = { enabled = true }, run = function() end }"
        )

        assertEquals(1, (surface.moduleType.fields.getValue("value") as LiteralType).value)
        val nested = assertIs<TableType>(surface.moduleType.fields.getValue("nested"))
        assertEquals(true, (nested.fields.getValue("enabled") as LiteralType).value)
        // Direct-return table fields keep FunctionType values on moduleType.fields and surface as
        // SymbolKind.FIELD (colon methods only use METHOD). Member name "run" must be present.
        assertIs<FunctionType>(surface.moduleType.fields.getValue("run"))
        assertNotNull(surface.members.firstOrNull { it.name == "value" }?.range)
        val runMember = assertNotNull(surface.members.firstOrNull { it.name == "run" })
        assertEquals(io.github.dingyi222666.luaparser.semantic.api.SymbolKind.FIELD, runMember.kind)
        assertIs<FunctionType>(runMember.type)
    }

    @Test
    fun forwarding_alias_is_collected() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = """
                local M = {}
                local exports = M
                exports.value = 1
                return exports
            """.trimIndent()
        )

        assertEquals(1, (surface.moduleType.fields.getValue("value") as LiteralType).value)
    }

    @Test
    fun nested_path_shaping_builds_nested_table_fields() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = """
                local M = {}
                M.nested.value = 1
                return M
            """.trimIndent()
        )

        val nested = assertIs<TableType>(surface.moduleType.fields.getValue("nested"))
        assertEquals(1, (nested.fields.getValue("value") as LiteralType).value)
    }

    @Test
    fun string_index_write_becomes_named_field() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = """
                local _M = {}
                _M["name"] = 2
                return _M
            """.trimIndent()
        )

        assertEquals(2, (surface.moduleType.fields.getValue("name") as LiteralType).value)
    }

    @Test
    fun seeall_metadata_is_preserved_on_legacy_export_surface() {
        val surface = collect(
            path = "pkg/runtime.lua",
            source = """
                module("pkg.runtime", package.seeall)
                value = 1
            """.trimIndent()
        )

        assertTrue(surface.hasSeeAllFallback)
    }

    private fun collect(path: String, source: String): ModuleExportSurface {
        val chunk = LuaParser().parse(source)
        val virtualPath = VirtualPath.of(path)
        val facts = DocumentFactsCollector.collect(virtualPath, chunk)
        val environment = LegacyModuleEnvironmentPass.analyze(virtualPath, facts)
        return assertNotNull(ModuleExportCollector.collect(chunk, facts, environment))
    }
}
