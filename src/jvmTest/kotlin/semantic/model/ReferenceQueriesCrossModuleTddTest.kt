package semantic.model

import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-module reference/definition corpus for require-backed module exports.
 *
 * Encodes acceptance for TASK-223:
 * - Two-file fixtures resolve definitions and references across require/module exports.
 * - Missing exports produce empty navigation results.
 *
 * Uses [WorkspaceSemanticHarness] / [io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade]
 * (the public surface that drives [io.github.dingyi222666.luaparser.semantic.model.ReferenceQueries]
 * through workspace-aware member resolution).
 */
class ReferenceQueriesCrossModuleTddTest {

    @Test
    fun two_file_exported_field_definition_resolves_to_provider() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to """
                local M = {}
                M.value = 42
                return M
            """.trimIndent(),
            "main.lua" to """
                local dep = require("dep")
                local current = dep.value
                return current
            """.trimIndent()
        )

        val depPath = harness.path("dep.lua")
        val mainPath = harness.path("main.lua")
        val usage = harness.positionOf("main.lua", "value")

        val definitions = harness.queries.gotoDefinition(mainPath, usage)

        assertEquals(listOf(depPath), definitions.map { it.path })
        assertEquals(harness.positionOf("dep.lua", "value"), definitions.single().range.start)
    }

    @Test
    fun two_file_exported_field_references_include_provider_and_consumer_usages() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to """
                local M = {}
                M.value = 42
                return M
            """.trimIndent(),
            "main.lua" to """
                local dep = require("dep")
                local first = dep.value
                local second = dep.value
                return second
            """.trimIndent()
        )

        val depPath = harness.path("dep.lua")
        val mainPath = harness.path("main.lua")
        val usage = harness.positionOf("main.lua", "value", occurrence = 1)

        val references = harness.queries.references(mainPath, usage)

        assertTrue(
            references.any { it.path == depPath && it.range.start == harness.positionOf("dep.lua", "value") },
            "Expected provider definition among references; got ${references.map { it.path.value to it.range.start }}"
        )
        assertEquals(2, references.count { it.path == mainPath })
        assertTrue(references.any { it.path == mainPath && it.range.start == harness.positionOf("main.lua", "value", occurrence = 1) })
        assertTrue(references.any { it.path == mainPath && it.range.start == harness.positionOf("main.lua", "value", occurrence = 2) })
    }

    @Test
    fun two_file_exported_function_definition_and_references_resolve_across_modules() {
        val harness = WorkspaceSemanticHarness.build(
            "lib.lua" to """
                local M = {}
                function M.run()
                  return true
                end
                return M
            """.trimIndent(),
            "app.lua" to """
                local lib = require("lib")
                local once = lib.run
                local twice = lib.run
                return twice
            """.trimIndent()
        )

        val libPath = harness.path("lib.lua")
        val appPath = harness.path("app.lua")
        val usage = harness.positionOf("app.lua", "run", occurrence = 1)

        val definitions = harness.queries.gotoDefinition(appPath, usage)
        val references = harness.queries.references(appPath, usage)

        assertEquals(listOf(libPath), definitions.map { it.path })
        assertEquals(harness.positionOf("lib.lua", "run"), definitions.single().range.start)
        assertTrue(references.any { it.path == libPath })
        assertEquals(2, references.count { it.path == appPath })
    }

    @Test
    fun two_file_require_local_definition_resolves_to_provider_module() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to """
                return { value = 1 }
            """.trimIndent(),
            "main.lua" to """
                local dep = require("dep")
                return dep
            """.trimIndent()
        )

        val depPath = harness.path("dep.lua")
        val mainPath = harness.path("main.lua")

        // Local binding site (occurrence 1). require("dep") string is occurrence 2; usage is 3.
        val atBinding = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "dep", occurrence = 1)
        )
        // Read usage
        val atUsage = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "dep", occurrence = 3)
        )

        assertEquals(listOf(depPath), atBinding.map { it.path })
        assertEquals(listOf(depPath), atUsage.map { it.path })
    }

    @Test
    fun two_file_inline_table_export_field_resolves_definition_and_references() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to """
                return { value = 7, label = "ok" }
            """.trimIndent(),
            "main.lua" to """
                local dep = require("dep")
                local a = dep.value
                local b = dep.value
                return a + b
            """.trimIndent()
        )

        val depPath = harness.path("dep.lua")
        val mainPath = harness.path("main.lua")
        val usage = harness.positionOf("main.lua", "value", occurrence = 1)

        val definitions = harness.queries.gotoDefinition(mainPath, usage)
        val references = harness.queries.references(mainPath, usage)

        assertEquals(listOf(depPath), definitions.map { it.path })
        assertEquals(harness.positionOf("dep.lua", "value"), definitions.single().range.start)
        assertTrue(references.any { it.path == depPath })
        assertEquals(2, references.count { it.path == mainPath })
    }

    @Test
    fun missing_export_member_definition_stays_empty() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to """
                local M = {}
                M.value = 1
                return M
            """.trimIndent(),
            "main.lua" to """
                local dep = require("dep")
                local missing = dep.missing
                return missing
            """.trimIndent()
        )

        val mainPath = harness.path("main.lua")
        val missingPos = harness.positionOf("main.lua", "missing", occurrence = 2)

        val definitions = harness.queries.gotoDefinition(mainPath, missingPos)

        assertEquals(
            emptyList(),
            definitions,
            "Missing module export should not resolve a definition; got ${definitions.map { it.path.value }}"
        )
    }

    @Test
    fun missing_export_member_references_stay_empty() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to """
                local M = {}
                M.value = 1
                return M
            """.trimIndent(),
            "main.lua" to """
                local dep = require("dep")
                local first = dep.missing
                local second = dep.missing
                return first or second
            """.trimIndent()
        )

        val mainPath = harness.path("main.lua")
        val missingPos = harness.positionOf("main.lua", "missing", occurrence = 2)

        val references = harness.queries.references(mainPath, missingPos)

        assertEquals(
            emptyList(),
            references,
            "Missing module export should not collect cross-module references; got ${references.map { it.path.value to it.range.start }}"
        )
    }

    @Test
    fun missing_module_require_definition_stays_empty() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local other = require("missing")
                return other
            """.trimIndent()
        )

        val mainPath = harness.path("main.lua")

        val atModuleName = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "missing")
        )
        val atBinding = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "other", occurrence = 1)
        )
        val atUsage = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "other", occurrence = 2)
        )

        assertEquals(emptyList(), atModuleName)
        // Binding/usage of an unresolved require should not invent a provider location.
        assertTrue(
            atBinding.none { it.path.value.contains("missing") },
            "Unresolved require binding must not invent a missing provider path; got ${atBinding.map { it.path.value }}"
        )
        assertTrue(
            atUsage.none { it.path.value.contains("missing") },
            "Unresolved require usage must not invent a missing provider path; got ${atUsage.map { it.path.value }}"
        )
    }

    @Test
    fun known_export_still_resolves_when_sibling_missing_export_present() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to """
                local M = {}
                M.value = 9
                return M
            """.trimIndent(),
            "main.lua" to """
                local dep = require("dep")
                local ok = dep.value
                local bad = dep.missing
                return ok
            """.trimIndent()
        )

        val depPath = harness.path("dep.lua")
        val mainPath = harness.path("main.lua")

        val knownDef = harness.queries.gotoDefinition(mainPath, harness.positionOf("main.lua", "value"))
        val knownRefs = harness.queries.references(mainPath, harness.positionOf("main.lua", "value"))
        // "missing" appears only once (member access); local is named bad.
        val missingDef = harness.queries.gotoDefinition(mainPath, harness.positionOf("main.lua", "missing", occurrence = 1))
        val missingRefs = harness.queries.references(mainPath, harness.positionOf("main.lua", "missing", occurrence = 1))

        assertEquals(listOf(depPath), knownDef.map { it.path })
        assertTrue(knownRefs.any { it.path == depPath })
        assertTrue(knownRefs.any { it.path == mainPath })
        assertEquals(emptyList(), missingDef)
        assertEquals(emptyList(), missingRefs)
    }

    @Test
    fun provider_side_query_includes_consumer_references_for_exported_field() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to """
                local M = {}
                M.value = 3
                return M
            """.trimIndent(),
            "main.lua" to """
                local dep = require("dep")
                local current = dep.value
                return dep.value
            """.trimIndent()
        )

        val depPath = harness.path("dep.lua")
        val mainPath = harness.path("main.lua")
        val providerPos = harness.positionOf("dep.lua", "value")

        val definitions = harness.queries.gotoDefinition(depPath, providerPos)
        val references = harness.queries.references(depPath, providerPos)

        assertEquals(listOf(depPath), definitions.map { it.path })
        assertTrue(references.any { it.path == depPath && it.range.start == providerPos })
        assertEquals(2, references.count { it.path == mainPath })
    }

    @Test
    fun two_file_require_string_definition_resolves_to_provider_file() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to """
                return { ok = true }
            """.trimIndent(),
            "main.lua" to """
                local dep = require("dep")
                return dep
            """.trimIndent()
        )

        val depPath = harness.path("dep.lua")
        val mainPath = harness.path("main.lua")
        // Needle "dep" occurrence 2 is the require("dep") module name string.
        val atRequireArg = harness.queries.gotoDefinition(
            mainPath,
            harness.positionOf("main.lua", "dep", occurrence = 2)
        )

        assertEquals(listOf(depPath), atRequireArg.map { it.path })
    }
}
