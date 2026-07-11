package semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModuleExportCollectorNestedRangeTddTest {
    @Test
    fun direct_table_return_preserves_nested_member_ranges_for_provider_navigation_and_references() {
        val harness = WorkspaceSemanticHarness.build(
            "provider.lua" to """
                return {
                  nested = {
                    value = 1,
                    run = function()
                      return 2
                    end,
                  },
                }
            """.trimIndent(),
            "main.lua" to """
                local provider = require("provider")
                local first = provider.nested.value
                return provider.nested.run
            """.trimIndent()
        )

        assertNestedExport(
            harness = harness,
            exportPath = listOf("nested"),
            providerNeedle = "nested",
            expectedKind = SymbolKind.FIELD
        )
        assertNestedExport(
            harness = harness,
            exportPath = listOf("nested", "value"),
            providerNeedle = "value",
            expectedKind = SymbolKind.FIELD
        )
        assertNestedExport(
            harness = harness,
            exportPath = listOf("nested", "run"),
            providerNeedle = "run",
            expectedKind = SymbolKind.FIELD
        )
    }

    @Test
    fun returned_identifier_preserves_nested_write_ranges_for_provider_navigation_and_references() {
        val harness = WorkspaceSemanticHarness.build(
            "provider.lua" to """
                local M = {}
                M.nested = {}
                M.nested.value = 1
                function M.nested:run()
                  return self.value
                end
                return M
            """.trimIndent(),
            "main.lua" to """
                local provider = require("provider")
                local first = provider.nested.value
                return provider.nested.run
            """.trimIndent()
        )

        assertNestedExport(
            harness = harness,
            exportPath = listOf("nested", "value"),
            providerNeedle = "value",
            expectedKind = SymbolKind.FIELD
        )
        assertNestedExport(
            harness = harness,
            exportPath = listOf("nested", "run"),
            providerNeedle = "run",
            expectedKind = SymbolKind.METHOD
        )
    }

    @Test
    fun returned_identifier_preserves_implicit_parent_write_range_for_provider_navigation_and_references() {
        val harness = WorkspaceSemanticHarness.build(
            "provider.lua" to """
                local M = {}
                M.nested.value = 1
                return M
            """.trimIndent(),
            "main.lua" to """
                local provider = require("provider")
                local branch = provider.nested
                return provider.nested.value
            """.trimIndent()
        )
        val providerPath = harness.path("provider.lua")
        val mainPath = harness.path("main.lua")
        val surface = assertNotNull(
            harness.queries.lookupModule("provider").exportSurface,
            "Expected provider export surface."
        )
        val parent = assertNotNull(surface.member(listOf("nested")), "Expected implicit parent export.")
        val leaf = assertNotNull(surface.member(listOf("nested", "value")), "Expected leaf export.")
        val parentRange = harness.rangeOf("provider.lua", "nested")
        val leafRange = harness.rangeOf("provider.lua", "value")

        assertEquals(parentRange.start, parent.range?.start)
        assertEquals(parentRange.end, parent.range?.end)
        assertEquals(leafRange.start, leaf.range?.start)
        assertEquals(leafRange.end, leaf.range?.end)

        val consumerRange = harness.rangeOf("main.lua", "nested")
        val definition = harness.queries.gotoDefinition(mainPath, consumerRange.start)
        val references = harness.queries.references(mainPath, consumerRange.start)

        assertEquals(listOf(providerPath), definition.map { it.path })
        assertEquals(parentRange.start, definition.single().range.start)
        assertTrue(references.any { it.path == providerPath && it.range.start == parentRange.start })
        assertTrue(references.any { it.path == mainPath && it.range.start == consumerRange.start })
        assertTrue(references.none { it.path == providerPath && it.range.start == leafRange.start })
    }

    private fun assertNestedExport(
        harness: WorkspaceSemanticHarness,
        exportPath: List<String>,
        providerNeedle: String,
        expectedKind: SymbolKind
    ) {
        val providerPath = harness.path("provider.lua")
        val surface = assertNotNull(
            harness.queries.lookupModule("provider").exportSurface,
            "Expected provider export surface."
        )
        val member = assertNotNull(
            surface.member(exportPath),
            "Expected exported member ${exportPath.joinToString(".")}."
        )
        val expectedRange = harness.rangeOf("provider.lua", providerNeedle)

        assertEquals(expectedKind, member.kind)
        assertEquals(expectedRange.start, member.range?.start)
        assertEquals(expectedRange.end, member.range?.end)

        val providerDefinition = harness.queries.gotoDefinition(providerPath, expectedRange.start)
        val providerReferences = harness.queries.references(providerPath, expectedRange.start)

        assertEquals(listOf(providerPath), providerDefinition.map { it.path })
        assertEquals(expectedRange.start, providerDefinition.single().range.start)
        assertTrue(providerReferences.any { it.path == providerPath && it.range.start == expectedRange.start })
    }

    private fun ModuleExportSurface.member(exportPath: List<String>): ModuleExportSurface.MemberExport? =
        members.singleOrNull { it.exportPath == exportPath }

    private fun WorkspaceSemanticHarness.rangeOf(path: String, needle: String, occurrence: Int = 1): Range {
        val position = positionOf(path, needle, occurrence)
        return Range(position, position.copy(column = position.column + needle.length))
    }
}
