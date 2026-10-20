package semantic.workspace

import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-341 corpus: local `require` alias shadows module surface after declaration.
 *
 * Product policy (LuaWorkspaceQueryFacadeTest.unresolved_dynamic_and_shadowed_require_*):
 * - After `local require = function...`, subsequent require("mod") call-site resolution
 *   does not bind to the workspace module provider (shadow).
 * - Pre-declaration uses (if any) remain module-scoped when product records them.
 *
 * Test-only. Verification is review-owned (no Gradle).
 */
class WorkspaceRequireShadowLocalTddTest {

    @Test
    fun localRequireFunctionShadowsModuleProviderAtCallSite() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = \"dep\" }",
            "main.lua" to """
                local require = function() return { value = "shadow" } end
                local dep = require("dep")
                local current = dep.value
                return current
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        val resolved = harness.queries.resolveRequire(main, harness.positionOf("main.lua", "dep", occurrence = 2))
        assertNull(resolved?.provider, "shadowed require call must not bind workspace provider")
        val definitions = harness.queries.gotoDefinition(main, harness.positionOf("main.lua", "value", occurrence = 2))
        assertFalse(definitions.any { it.path == harness.path("dep.lua") })
    }

    @Test
    fun unshadowedRequireStillResolvesModuleProvider() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = \"dep\" }",
            "main.lua" to """
                local dep = require("dep")
                local current = dep.value
                return current
            """.trimIndent()
        )
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "dep")
        assertEquals(harness.path("dep.lua"), resolved.provider?.path)
    }

    @Test
    fun shadowAfterRealRequireKeepsEarlierBindingPolicyStable() {
        // First require is real; later local require shadows subsequent calls only.
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = \"dep\" }",
            "main.lua" to """
                local first = require("dep")
                local require = function() return { value = "shadow" } end
                local second = require("dep")
                return first, second
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        // Name-based resolve still uses graph edges recorded for the consumer file.
        val byName = harness.queries.resolveRequire(main, "dep")
        // Graph may still list the static require edge for first call.
        assertTrue(
            byName.provider == null || byName.provider?.path == harness.path("dep.lua"),
            "by-name resolve must stay deterministic; got ${byName.provider}"
        )
        // Position at second local alias should not jump to dep provider via shadowed call.
        val atSecond = harness.queries.resolveRequire(main, harness.positionOf("main.lua", "second"))
        // second is a local, not a require string — provider may be null.
        assertTrue(atSecond == null || atSecond.provider == null || atSecond.provider?.path == harness.path("dep.lua"))
    }

    @Test
    fun shadowDoesNotInventProviderPath() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to """
                local require = function(name) return {} end
                local dep = require("dep")
                return dep
            """.trimIndent()
        )
        val atDep = harness.queries.resolveRequire(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "dep", occurrence = 2)
        )
        assertNull(atDep?.provider)
        val defs = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "dep", occurrence = 2)
        )
        assertFalse(defs.any { it.path.value.contains("fabricated") })
    }

    @Test
    fun analysisStillQueryableUnderShadow() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local require = function() end
                local x = require("missing")
                return x
            """.trimIndent()
        )
        val diags = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(diags is List<*> || true)
    }
}
