package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.LegacyModuleEnvironmentPass
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportCollector
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-297 corpus: table-literal / module-table field exports.
 *
 * Acceptance:
 * - `return { f = function() end }` exports `f`.
 * - Nested export fields are visible to `require` consumers.
 *
 * Kind convention matching ModuleExportCollector:
 * - table-literal function values and `function M.f()` / assignment of function expr → FIELD + FunctionType
 * - colon-style `function M:f()` → METHOD
 *
 * Mixes a lightweight collector-only path (direct surface assertions) with a small
 * WorkspaceSemanticHarness require graph for consumer visibility. Test-only.
 * Verification is review-owned (no Gradle from workers).
 */
class ModuleExportTableFieldTddTest {

    private val parser = LuaParser()

    @Test
    fun direct_table_return_exports_function_field_f() {
        // Acceptance: return { f = function() end } exports f.
        val surface = collect(
            """
                return {
                  f = function()
                    return 1
                  end,
                }
            """.trimIndent(),
            path = "provider.lua"
        )

        assertEquals(ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL, surface.sourceForm)
        val member = assertMember(surface, listOf("f"), SymbolKind.FIELD)
        assertEquals("f", member.name)
        assertFunctionOnModuleType(surface, listOf("f"))
        assertIs<FunctionType>(surface.moduleType.fields.getValue("f"))
    }

    @Test
    fun direct_table_return_exports_scalar_and_function_fields() {
        val surface = collect(
            """
                return {
                  name = "mod",
                  count = 2,
                  f = function(x)
                    return x
                  end,
                }
            """.trimIndent()
        )

        assertMember(surface, listOf("name"), SymbolKind.FIELD)
        assertMember(surface, listOf("count"), SymbolKind.FIELD)
        assertMember(surface, listOf("f"), SymbolKind.FIELD)
        assertFunctionOnModuleType(surface, listOf("f"))
        assertTrue(surface.moduleType.fields.containsKey("name"))
        assertTrue(surface.moduleType.fields.containsKey("count"))
        assertIs<FunctionType>(surface.moduleType.fields.getValue("f"))
    }

    @Test
    fun returned_m_table_field_writes_export_function_and_scalar() {
        val surface = collect(
            """
                local M = {}
                M.f = function()
                  return true
                end
                M.tag = "public"
                return M
            """.trimIndent()
        )

        assertEquals(ModuleExportSurface.SourceForm.RETURN_IDENTIFIER, surface.sourceForm)
        assertMember(surface, listOf("f"), SymbolKind.FIELD)
        assertMember(surface, listOf("tag"), SymbolKind.FIELD)
        assertFunctionOnModuleType(surface, listOf("f"))
    }

    @Test
    fun returned_m_table_dot_function_declaration_exports_field_f() {
        val surface = collect(
            """
                local M = {}
                function M.f()
                  return 0
                end
                return M
            """.trimIndent()
        )

        assertMember(surface, listOf("f"), SymbolKind.FIELD)
        assertFunctionOnModuleType(surface, listOf("f"))
        assertIs<FunctionType>(surface.moduleType.fields.getValue("f"))
    }

    @Test
    fun returned_m_table_colon_method_exports_method_f() {
        val surface = collect(
            """
                local M = {}
                function M:f()
                  return self
                end
                return M
            """.trimIndent()
        )

        assertMember(surface, listOf("f"), SymbolKind.METHOD)
        assertFunctionOnModuleType(surface, listOf("f"))
        assertIs<FunctionType>(surface.moduleType.methods.getValue("f"))
    }

    @Test
    fun nested_table_literal_export_fields_are_collected() {
        val surface = collect(
            """
                return {
                  outer = {
                    f = function()
                      return 2
                    end,
                    value = 1,
                  },
                }
            """.trimIndent()
        )

        assertMember(surface, listOf("outer"), SymbolKind.FIELD)
        assertMember(surface, listOf("outer", "f"), SymbolKind.FIELD)
        assertMember(surface, listOf("outer", "value"), SymbolKind.FIELD)
        assertFunctionOnModuleType(surface, listOf("outer", "f"))

        val outer = assertIs<TableType>(surface.moduleType.fields.getValue("outer"))
        assertIs<FunctionType>(outer.fields.getValue("f"))
        assertTrue(outer.fields.containsKey("value"))
    }

    @Test
    fun nested_export_fields_visible_to_require_consumer() {
        // Acceptance: nested export fields visible to require.
        val harness = WorkspaceSemanticHarness.build(
            "provider.lua" to """
                return {
                  outer = {
                    f = function()
                      return 2
                    end,
                    value = 1,
                  },
                  f = function()
                    return 0
                  end,
                }
            """.trimIndent(),
            "main.lua" to """
                local provider = require("provider")
                local first = provider.f
                local nested = provider.outer.f
                local value = provider.outer.value
                return first, nested, value
            """.trimIndent()
        )

        val providerPath = harness.path("provider.lua")
        val mainPath = harness.path("main.lua")

        val resolved = harness.queries.resolveRequire(mainPath, "provider")
        assertEquals(providerPath, resolved.provider?.path)
        val surface = assertNotNull(resolved.exportSurface, "Expected provider export surface via require.")

        assertTrue(surface.members.any { it.exportPath == listOf("f") })
        assertTrue(surface.members.any { it.exportPath == listOf("outer") })
        assertTrue(surface.members.any { it.exportPath == listOf("outer", "f") })
        assertTrue(surface.members.any { it.exportPath == listOf("outer", "value") })

        val lookup = harness.queries.lookupModule("provider")
        assertEquals(providerPath, lookup.provider?.path)
        assertNotNull(lookup.exportSurface)

        // Consumer member sites (2nd "f" is nested outer.f usage; 1st after require is provider.f).
        val topFDefinition = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "f", occurrence = 1)
        )
        val nestedFDefinition = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "f", occurrence = 2)
        )
        val valueDefinition = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "value", occurrence = 2)
        )

        assertEquals(listOf(providerPath), topFDefinition.map { it.path })
        assertEquals(listOf(providerPath), nestedFDefinition.map { it.path })
        assertEquals(listOf(providerPath), valueDefinition.map { it.path })

        val completionsAtProviderDot = harness.queries.completions(
            mainPath,
            harness.positionOf("main.lua", "f", occurrence = 1)
        )
        assertTrue(
            completionsAtProviderDot.any { it.label == "f" },
            "Completions on require(\"provider\") must include top-level export field f; got=${completionsAtProviderDot.map { it.label }}"
        )
        assertTrue(
            completionsAtProviderDot.any { it.label == "outer" },
            "Completions on require(\"provider\") must include nested table field outer; got=${completionsAtProviderDot.map { it.label }}"
        )

        val completionsAtOuterDot = harness.queries.completions(
            mainPath,
            harness.positionOf("main.lua", "f", occurrence = 2)
        )
        assertTrue(
            completionsAtOuterDot.any { it.label == "f" },
            "Completions on provider.outer must include nested export field f; got=${completionsAtOuterDot.map { it.label }}"
        )
        assertTrue(
            completionsAtOuterDot.any { it.label == "value" },
            "Completions on provider.outer must include nested export field value; got=${completionsAtOuterDot.map { it.label }}"
        )
    }

    @Test
    fun nested_m_table_field_writes_visible_to_require_consumer() {
        val harness = WorkspaceSemanticHarness.build(
            "provider.lua" to """
                local M = {}
                M.outer = {}
                M.outer.f = function()
                  return 1
                end
                M.outer.value = 2
                function M.f()
                  return 0
                end
                return M
            """.trimIndent(),
            "main.lua" to """
                local provider = require("provider")
                local top = provider.f
                local nested = provider.outer.f
                local value = provider.outer.value
                return top, nested, value
            """.trimIndent()
        )

        val providerPath = harness.path("provider.lua")
        val mainPath = harness.path("main.lua")
        val surface = assertNotNull(
            harness.queries.lookupModule("provider").exportSurface,
            "Expected returned-M export surface."
        )

        assertTrue(surface.members.any { it.exportPath == listOf("f") && it.kind == SymbolKind.FIELD })
        assertTrue(surface.members.any { it.exportPath == listOf("outer", "f") })
        assertTrue(surface.members.any { it.exportPath == listOf("outer", "value") })

        assertEquals(
            listOf(providerPath),
            harness.queries.gotoDefinition(mainPath, harness.positionOf("main.lua", "f", occurrence = 1)).map { it.path }
        )
        assertEquals(
            listOf(providerPath),
            harness.queries.gotoDefinition(mainPath, harness.positionOf("main.lua", "f", occurrence = 2)).map { it.path }
        )
        assertEquals(
            listOf(providerPath),
            harness.queries.gotoDefinition(mainPath, harness.positionOf("main.lua", "value", occurrence = 2)).map { it.path }
        )
    }

    @Test
    fun non_exported_local_table_fields_stay_hidden() {
        val surface = collect(
            """
                local hidden = {
                  f = function() end,
                  value = 1,
                }
                local M = {}
                M.f = function() end
                return M
            """.trimIndent()
        )

        assertMember(surface, listOf("f"), SymbolKind.FIELD)
        assertNull(surface.member(listOf("value")))
        assertTrue(surface.members.none { it.name == "hidden" })
        assertTrue(surface.members.none { it.exportPath == listOf("hidden", "f") })
        // Only the exported M.f should appear as top-level f.
        assertEquals(1, surface.members.count { it.exportPath == listOf("f") })
    }

    private fun collect(source: String, path: String = "pkg/table_field.lua"): ModuleExportSurface {
        val chunk = parser.parse(source)
        val virtualPath = VirtualPath.of(path)
        val facts = DocumentFactsCollector.collect(virtualPath, chunk)
        val environment = LegacyModuleEnvironmentPass.analyze(virtualPath, facts)
        return assertNotNull(
            ModuleExportCollector.collect(chunk, facts, environment),
            "Expected ModuleExportCollector to produce a surface for:\n$source"
        )
    }

    private fun assertMember(
        surface: ModuleExportSurface,
        exportPath: List<String>,
        expectedKind: SymbolKind
    ): ModuleExportSurface.MemberExport {
        val member = assertNotNull(
            surface.member(exportPath),
            "Expected exported member ${exportPath.joinToString(".")}; members=${
                surface.members.map { it.exportPath.joinToString(".") + ":" + it.kind }
            }"
        )
        assertEquals(expectedKind, member.kind, "Unexpected kind for ${exportPath.joinToString(".")}")
        assertEquals(exportPath.last(), member.name)
        assertEquals(exportPath, member.exportPath)
        return member
    }

    private fun assertFunctionOnModuleType(surface: ModuleExportSurface, exportPath: List<String>) {
        require(exportPath.isNotEmpty())
        var currentFields = surface.moduleType.fields
        var currentMethods = surface.moduleType.methods
        exportPath.dropLast(1).forEach { segment ->
            val nested = assertIs<TableType>(
                currentFields[segment],
                "Expected nested table field '$segment' along ${exportPath.joinToString(".")}"
            )
            currentFields = nested.fields
            currentMethods = nested.methods
        }
        val leaf = exportPath.last()
        val leafType = currentMethods[leaf] ?: currentFields[leaf]
        assertNotNull(leafType, "Missing leaf type for ${exportPath.joinToString(".")}")
        assertIs<FunctionType>(
            leafType,
            "Expected FunctionType at ${exportPath.joinToString(".")}, got $leafType"
        )
    }

    private fun ModuleExportSurface.member(exportPath: List<String>): ModuleExportSurface.MemberExport? =
        members.singleOrNull { it.exportPath == exportPath }
}
