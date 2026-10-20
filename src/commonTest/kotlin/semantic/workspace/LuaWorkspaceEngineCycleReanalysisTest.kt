package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LuaWorkspaceEngineCycleReanalysisTest {
    @Test
    fun cycle_reanalysis_pass_binds_provider_globals_between_cycle_members() {
        val engine = LuaWorkspaceEngine()
        val result = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    // a.lua ⇄ b.lua: the two files require each other, so dependency-order
                    // analysis always starts with b.lua while a.lua's semantic state is still
                    // empty on the first pass.
                    VirtualPath.of("a.lua") to """
                        local b = require("b")
                        SHARED_VALUE = 42
                        local M = {}
                        return M
                    """.trimIndent(),
                    VirtualPath.of("b.lua") to """
                        local a = require("a")
                        DERIVED_VALUE = SHARED_VALUE
                        local M = {}
                        return M
                    """.trimIndent(),
                    // Consumer reading a provider-global out of the cycle: DERIVED_VALUE is a
                    // global of b.lua whose value derives from a.lua's global, so its type is
                    // only correct once b.lua was re-analyzed against the completed a.lua.
                    VirtualPath.of("main.lua") to """
                        local b = require("b")
                        local x = DERIVED_VALUE
                        return x
                    """.trimIndent()
                ),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        )

        val graph = result.snapshot.graph
        val cycle = setOf(VirtualPath.of("a.lua"), VirtualPath.of("b.lua"))
        // Fixture guard: a.lua and b.lua really form one two-member SCC, so the cycle
        // re-analysis pass had to run for them.
        assertEquals(cycle, graph.stronglyConnectedComponentByFile.getValue(VirtualPath.of("a.lua")))
        assertEquals(cycle, graph.stronglyConnectedComponentByFile.getValue(VirtualPath.of("b.lua")))
        assertEquals(
            setOf(VirtualPath.of("main.lua")),
            graph.stronglyConnectedComponentByFile.getValue(VirtualPath.of("main.lua"))
        )

        // The consumer's imported symbol for the provider-global must carry a concrete type,
        // not UnknownType or a structural fallback table/module.
        val consumerSymbol = WorkspaceModuleResolver(result.snapshot)
            .importedSymbolsFor(VirtualPath.of("main.lua"))
            .getValue("DERIVED_VALUE")
        assertConcreteNumber(consumerSymbol.valueType, "provider-global")
    }

    @Test
    fun three_cycle_with_chained_derivations_converges_to_concrete_types() {
        val engine = LuaWorkspaceEngine()
        val result = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    // True 3-cycle a ⇄ b ⇄ c (a requires b, b requires c, c requires a) whose
                    // derivation chain runs GBASE(b) → GMC(a) → GZ(c) → GTOP(b): each derived
                    // global needs a peer that is only completed by re-analysis, so a single
                    // re-analysis sweep can leave the last hop (GTOP, read here by main.lua)
                    // binding against a half-finished peer and staying Unknown. The bounded
                    // fixpoint must keep sweeping until the chain fully resolves.
                    VirtualPath.of("a.lua") to """
                        local b = require("b")
                        GMC = GBASE + 1
                        local M = {}
                        return M
                    """.trimIndent(),
                    VirtualPath.of("b.lua") to """
                        local c = require("c")
                        GBASE = 42
                        GTOP = GZ + 1
                        local M = {}
                        return M
                    """.trimIndent(),
                    VirtualPath.of("c.lua") to """
                        local a = require("a")
                        GZ = GMC + 1
                        local M = {}
                        return M
                    """.trimIndent(),
                    // Consumer reading every hop of the chain out of the cycle, including the
                    // final hop GTOP.
                    VirtualPath.of("main.lua") to """
                        local a = require("a")
                        local b = require("b")
                        local c = require("c")
                        local x = GTOP
                        return x
                    """.trimIndent()
                ),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        )

        val graph = result.snapshot.graph
        val cycle = setOf(VirtualPath.of("a.lua"), VirtualPath.of("b.lua"), VirtualPath.of("c.lua"))
        // Fixture guard: a.lua, b.lua and c.lua really form one three-member SCC, so the cycle
        // re-analysis pass had to run for all of them.
        assertEquals(cycle, graph.stronglyConnectedComponentByFile.getValue(VirtualPath.of("a.lua")))
        assertEquals(cycle, graph.stronglyConnectedComponentByFile.getValue(VirtualPath.of("b.lua")))
        assertEquals(cycle, graph.stronglyConnectedComponentByFile.getValue(VirtualPath.of("c.lua")))
        assertEquals(
            setOf(VirtualPath.of("main.lua")),
            graph.stronglyConnectedComponentByFile.getValue(VirtualPath.of("main.lua"))
        )

        // Every hop of the chain must be concrete: the intermediate hops (GMC, GZ) as seen by
        // the consumer that requires their providers, and the final hop GTOP read by main.lua.
        val importedSymbols = WorkspaceModuleResolver(result.snapshot)
            .importedSymbolsFor(VirtualPath.of("main.lua"))
        assertConcreteNumber(importedSymbols.getValue("GMC").valueType, "GMC")
        assertConcreteNumber(importedSymbols.getValue("GZ").valueType, "GZ")
        assertConcreteNumber(importedSymbols.getValue("GTOP").valueType, "GTOP")
    }

    private fun assertConcreteNumber(valueType: Type, name: String) {
        assertFalse(
            valueType is UnknownType,
            "$name valueType must not be UnknownType; got ${valueType.displayName}"
        )
        val isConcreteNumber = valueType == PrimitiveType.NUMBER ||
            (valueType is LiteralType && valueType.baseType == PrimitiveType.NUMBER)
        assertTrue(
            isConcreteNumber,
            "$name valueType must be a concrete number; got ${valueType.displayName}"
        )
    }
}
