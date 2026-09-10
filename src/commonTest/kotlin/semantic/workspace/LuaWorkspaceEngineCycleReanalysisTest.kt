package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
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
        val valueType = consumerSymbol.valueType
        assertFalse(
            valueType is UnknownType,
            "provider-global valueType must not be UnknownType; got ${valueType.displayName}"
        )
        val isConcreteNumber = valueType == PrimitiveType.NUMBER ||
            (valueType is LiteralType && valueType.baseType == PrimitiveType.NUMBER)
        assertTrue(
            isConcreteNumber,
            "provider-global valueType must be a concrete number; got ${valueType.displayName}"
        )
    }
}
