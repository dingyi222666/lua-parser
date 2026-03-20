package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LuaWorkspaceQueryFacadeTest {
    @Test
    fun diagnostics_module_lookup_require_hover_and_references_flow_through_workspace_semantics() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1, run = function() return true end }",
            "main.lua" to "---@return string\nlocal function render()\n  local dep = require(\"dep\")\n  local current = dep.value\n  return dep.value\nend"
        )

        val depPath = harness.path("dep.lua")
        val mainPath = harness.path("main.lua")
        val diagnostics = harness.queries.diagnostics(mainPath)
        val module = harness.queries.lookupModule("dep")
        val resolved = harness.queries.resolveRequire(mainPath, "dep")
        val completions = harness.queries.completions(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))
        val hover = harness.queries.hover(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))
        val definitions = harness.queries.gotoDefinition(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))
        val references = harness.queries.references(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))

        assertTrue(diagnostics.isNotEmpty())
        assertNotNull(module.provider)
        assertEquals(depPath, module.provider.path)
        assertEquals(depPath, resolved.provider?.path)
        assertTrue(resolved.exportSurface?.moduleType is ModuleType)
        assertTrue(completions.any { it.label == "value" })
        assertTrue(completions.any { it.label == "run" })
        assertEquals("1", hover?.typeInfo?.displayName)
        assertEquals(listOf(depPath), definitions.map { it.path })
        assertTrue(references.any { it.path == depPath })
        assertTrue(references.any { it.path == mainPath })
    }

    @Test
    fun builtin_overlay_modules_resolve_for_require_queries() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local math = require(\"math\")\nlocal current = math.abs",
            standardLibraryOverlayVersion = LuaVersion.LUA_5_4
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "math")
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "abs"))

        assertEquals(harness.path("__lua_std__/5.4/math.lua"), resolved.provider?.path)
        assertNotNull(hover?.symbol)
    }

    @Test
    fun unresolved_dynamic_and_shadowed_require_fall_back_cleanly() {
        val unresolved = WorkspaceSemanticHarness.build(
            "main.lua" to "local dep = require(name)\nlocal other = require(\"missing\")"
        )
        val shadowed = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local require = function() return { value = \"shadow\" } end\nlocal dep = require(\"dep\")\nlocal current = dep.value"
        )

        assertEquals(null, unresolved.queries.resolveRequire(unresolved.path("main.lua"), "missing").provider)
        assertEquals(null, unresolved.queries.resolveRequire(unresolved.path("main.lua"), unresolved.positionOf("main.lua", "missing"))?.provider)
        assertEquals(emptyList(), unresolved.queries.gotoDefinition(unresolved.path("main.lua"), unresolved.positionOf("main.lua", "missing")))

        val definitions = shadowed.queries.gotoDefinition(shadowed.path("main.lua"), shadowed.positionOf("main.lua", "value", occurrence = 2))
        assertEquals(null, shadowed.queries.resolveRequire(shadowed.path("main.lua"), shadowed.positionOf("main.lua", "dep", occurrence = 2))?.provider)
        assertFalse(definitions.any { it.path == shadowed.path("dep.lua") })
    }

    @Test
    fun resolve_require_only_reports_modules_for_real_builtin_call_sites() {
        val noRequire = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local current = 1"
        )
        val shadowed = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local require = function() return { value = \"shadow\" } end\nlocal dep = require(\"dep\")"
        )

        assertEquals(null, noRequire.queries.resolveRequire(noRequire.path("main.lua"), "dep").provider)
        assertEquals(null, shadowed.queries.resolveRequire(shadowed.path("main.lua"), shadowed.positionOf("main.lua", "dep", occurrence = 2))?.provider)
    }

    @Test
    fun export_sites_are_queryable_and_cycles_do_not_blow_up_member_queries() {
        val harness = WorkspaceSemanticHarness.build(
            "a.lua" to "local b = require(\"b\")\nlocal M = { value = b.value }\nreturn M",
            "b.lua" to "local a = require(\"a\")\nlocal M = { value = a.value }\nreturn M",
            "main.lua" to "local a = require(\"a\")\nlocal current = a.value"
        )

        val exportHover = harness.queries.hover(harness.path("a.lua"), harness.positionOf("a.lua", "value", occurrence = 1))
        val refs = harness.queries.references(harness.path("a.lua"), harness.positionOf("a.lua", "value", occurrence = 1))
        val def = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "value"))

        assertEquals(SymbolKind.FIELD, exportHover?.symbol?.kind)
        assertTrue(refs.count { it.path == harness.path("a.lua") } >= 1)
        assertEquals(harness.path("a.lua"), def.single().path)
    }
}
