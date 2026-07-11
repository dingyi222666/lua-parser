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
 * TASK-204 corpus: nested functions assigned to module table fields must appear on
 * export surfaces, while non-exported locals remain hidden.
 *
 * Kind convention matching ModuleExportCollector:
 * - colon-style `function M.a:b()` → METHOD
 * - dot-style `function M.a.b()` / assignment of function expression → FIELD with FunctionType
 *
 * Test-only. Verification is review-owned (no Gradle from workers).
 */
class ModuleExportNestedFunctionTddTest {

    @Test
    fun returned_m_table_exposes_nested_dot_function_on_export_surface() {
        val surface = collect(
            """
                local M = {}
                M.nested = {}
                function M.nested.run()
                  return 1
                end
                return M
            """.trimIndent()
        )

        assertMember(
            surface,
            exportPath = listOf("nested", "run"),
            expectedKind = SymbolKind.FIELD
        )
        assertFunctionOnModuleType(surface, listOf("nested", "run"))
        assertNoMemberNamed(surface, "helper")
        assertNoMemberNamed(surface, "M")
    }

    @Test
    fun returned_m_table_exposes_nested_colon_method_on_export_surface() {
        val surface = collect(
            """
                local M = {}
                M.api = {}
                function M.api:execute(arg)
                  return arg
                end
                return M
            """.trimIndent()
        )

        assertMember(
            surface,
            exportPath = listOf("api", "execute"),
            expectedKind = SymbolKind.METHOD
        )
        assertFunctionOnModuleType(surface, listOf("api", "execute"))
    }

    @Test
    fun nested_function_assignment_write_is_exported() {
        val surface = collect(
            """
                local M = {}
                M.tools = {}
                M.tools.compute = function(x)
                  return x + 1
                end
                return M
            """.trimIndent()
        )

        // Field assignment of a function expression is still a nested function export.
        assertMember(
            surface,
            exportPath = listOf("tools", "compute"),
            expectedKind = SymbolKind.FIELD
        )
        assertFunctionOnModuleType(surface, listOf("tools", "compute"))
    }

    @Test
    fun deep_nested_function_path_is_exported() {
        val surface = collect(
            """
                local M = {}
                function M.a.b.c.leaf()
                  return true
                end
                return M
            """.trimIndent()
        )

        assertMember(
            surface,
            exportPath = listOf("a", "b", "c", "leaf"),
            expectedKind = SymbolKind.FIELD
        )
        assertFunctionOnModuleType(surface, listOf("a", "b", "c", "leaf"))
        // Implicit parent tables are present as field members for navigation.
        assertMember(surface, listOf("a"), SymbolKind.FIELD)
        assertMember(surface, listOf("a", "b"), SymbolKind.FIELD)
        assertMember(surface, listOf("a", "b", "c"), SymbolKind.FIELD)
    }

    @Test
    fun direct_table_return_nested_function_literals_are_exported() {
        val surface = collect(
            """
                return {
                  nested = {
                    run = function()
                      return 2
                    end,
                    value = 1,
                  },
                }
            """.trimIndent()
        )

        assertMember(surface, listOf("nested"), SymbolKind.FIELD)
        assertMember(surface, listOf("nested", "run"), SymbolKind.FIELD)
        assertMember(surface, listOf("nested", "value"), SymbolKind.FIELD)
        assertFunctionOnModuleType(surface, listOf("nested", "run"))
    }

    @Test
    fun string_index_nested_function_write_is_exported() {
        val surface = collect(
            """
                local M = {}
                M["nested"] = {}
                M["nested"]["run"] = function()
                  return 0
                end
                return M
            """.trimIndent()
        )

        assertMember(surface, listOf("nested", "run"), SymbolKind.FIELD)
        assertFunctionOnModuleType(surface, listOf("nested", "run"))
    }

    @Test
    fun non_exported_locals_stay_hidden_on_export_surface() {
        val surface = collect(
            """
                local M = {}
                local helper = function()
                  return "secret"
                end
                local function privateRun()
                  return helper()
                end
                local scratch = {}
                scratch.inner = function() end
                M.publicApi = function()
                  return privateRun()
                end
                function M.nested.exposed()
                  return privateRun()
                end
                return M
            """.trimIndent()
        )

        assertMember(surface, listOf("publicApi"), SymbolKind.FIELD)
        assertMember(surface, listOf("nested", "exposed"), SymbolKind.FIELD)

        // Pure locals and locals nested under non-returned tables must not leak.
        assertNoMemberNamed(surface, "helper")
        assertNoMemberNamed(surface, "privateRun")
        assertNoMemberNamed(surface, "scratch")
        assertNoMemberNamed(surface, "inner")
        assertNull(surface.member(listOf("helper")))
        assertNull(surface.member(listOf("privateRun")))
        assertNull(surface.member(listOf("scratch")))
        assertNull(surface.member(listOf("scratch", "inner")))
        assertNull(surface.member(listOf("inner")))

        val exportNames = surface.members.map { it.name }.toSet()
        assertTrue("helper" !in exportNames)
        assertTrue("privateRun" !in exportNames)
        assertTrue("scratch" !in exportNames)
        assertTrue("inner" !in exportNames)
    }

    @Test
    fun nested_local_inside_exported_function_body_is_not_exported() {
        val surface = collect(
            """
                local M = {}
                function M.factory()
                  local function nestedLocal()
                    return 1
                  end
                  local nestedTable = {}
                  nestedTable.fn = function() end
                  return nestedLocal
                end
                return M
            """.trimIndent()
        )

        assertMember(surface, listOf("factory"), SymbolKind.FIELD)
        assertNoMemberNamed(surface, "nestedLocal")
        assertNoMemberNamed(surface, "nestedTable")
        assertNoMemberNamed(surface, "fn")
        assertNull(surface.member(listOf("nestedLocal")))
        assertNull(surface.member(listOf("factory", "nestedLocal")))
        assertNull(surface.member(listOf("fn")))
    }

    @Test
    fun aliased_export_root_keeps_nested_function_writes() {
        val surface = collect(
            """
                local M = {}
                local exports = M
                exports.nested = {}
                function exports.nested.invoke()
                  return true
                end
                return exports
            """.trimIndent()
        )

        assertMember(surface, listOf("nested", "invoke"), SymbolKind.FIELD)
        assertFunctionOnModuleType(surface, listOf("nested", "invoke"))
        assertNoMemberNamed(surface, "exports")
    }

    @Test
    fun workspace_lookup_surfaces_nested_function_exports_and_hides_locals() {
        val harness = WorkspaceSemanticHarness.build(
            "provider.lua" to """
                local M = {}
                local secret = function()
                  return "hidden"
                end
                local function privateHelper()
                  return secret()
                end
                M.nested = {}
                function M.nested.run()
                  return privateHelper()
                end
                M.nested.compute = function(x)
                  return x
                end
                function M.nested:methodRun()
                  return privateHelper()
                end
                return M
            """.trimIndent(),
            "main.lua" to """
                local provider = require("provider")
                return provider.nested.run
            """.trimIndent()
        )

        val surface = assertNotNull(
            harness.queries.lookupModule("provider").exportSurface,
            "Expected provider export surface via workspace lookup."
        )

        assertMember(surface, listOf("nested", "run"), SymbolKind.FIELD)
        assertMember(surface, listOf("nested", "compute"), SymbolKind.FIELD)
        assertMember(surface, listOf("nested", "methodRun"), SymbolKind.METHOD)
        assertFunctionOnModuleType(surface, listOf("nested", "run"))
        assertFunctionOnModuleType(surface, listOf("nested", "compute"))
        assertFunctionOnModuleType(surface, listOf("nested", "methodRun"))

        assertNoMemberNamed(surface, "secret")
        assertNoMemberNamed(surface, "privateHelper")
        assertNull(surface.member(listOf("secret")))
        assertNull(surface.member(listOf("privateHelper")))

        // Consumer can resolve the nested exported function path as a field chain.
        val nestedType = assertIs<TableType>(surface.moduleType.fields.getValue("nested"))
        assertTrue(
            nestedType.methods.containsKey("methodRun") || nestedType.fields["run"] is FunctionType,
            "Expected nested function exports on the module type."
        )
        assertIs<FunctionType>(nestedType.fields.getValue("run"))
        assertIs<FunctionType>(nestedType.fields.getValue("compute"))
        assertIs<FunctionType>(nestedType.methods.getValue("methodRun"))
    }

    @Test
    fun mixed_nested_function_forms_all_appear_on_single_surface() {
        val surface = collect(
            """
                local M = {
                  fromLiteral = {
                    early = function()
                      return 0
                    end,
                  },
                }
                M.fromLiteral.late = function()
                  return 1
                end
                function M.fromLiteral.declared()
                  return 2
                end
                function M.fromLiteral:methodStyle()
                  return 3
                end
                local ignored = function() end
                return M
            """.trimIndent()
        )

        assertMember(surface, listOf("fromLiteral", "early"), SymbolKind.FIELD)
        assertMember(surface, listOf("fromLiteral", "late"), SymbolKind.FIELD)
        assertMember(surface, listOf("fromLiteral", "declared"), SymbolKind.FIELD)
        assertMember(surface, listOf("fromLiteral", "methodStyle"), SymbolKind.METHOD)

        assertFunctionOnModuleType(surface, listOf("fromLiteral", "early"))
        assertFunctionOnModuleType(surface, listOf("fromLiteral", "late"))
        assertFunctionOnModuleType(surface, listOf("fromLiteral", "declared"))
        assertFunctionOnModuleType(surface, listOf("fromLiteral", "methodStyle"))

        assertNoMemberNamed(surface, "ignored")
    }

    private fun collect(source: String, path: String = "pkg/nested_fn.lua"): ModuleExportSurface {
        val chunk = LuaParser().parse(source)
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

    private fun assertNoMemberNamed(surface: ModuleExportSurface, name: String) {
        assertTrue(
            surface.members.none { it.name == name },
            "Non-exported local '$name' must stay hidden; found ${
                surface.members.filter { it.name == name }.map { it.exportPath.joinToString(".") }
            }"
        )
    }

    private fun ModuleExportSurface.member(exportPath: List<String>): ModuleExportSurface.MemberExport? =
        members.singleOrNull { it.exportPath == exportPath }
}
