package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.LegacyModuleEnvironmentPass
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleEnvironmentMode
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LegacyModuleEnvironmentPassTest {
    @Test
    fun module_call_produces_legacy_segment_bindings() {
        val environment = analyze(
            path = "pkg/runtime.lua",
            source = "module(\"pkg.runtime\")"
        )

        val segment = environment.segments.single()
        assertEquals("pkg.runtime", (segment.bindings.getValue("_NAME") as LiteralType).value)
        assertEquals("pkg.", (segment.bindings.getValue("_PACKAGE") as LiteralType).value)
        assertEquals("pkg.runtime", (segment.bindings.getValue("...") as LiteralType).value)
        assertIs<ModuleType>(segment.bindings.getValue("_M"))
    }

    @Test
    fun module_call_with_package_seeall_marks_metadata_only() {
        val environment = analyze(
            path = "pkg/runtime.lua",
            source = "module(\"pkg.runtime\", package.seeall)"
        )

        val segment = environment.segments.single()
        assertEquals(ModuleEnvironmentMode.LEGACY_MODULE_SEEALL, segment.mode)
        assertTrue(segment.hasSeeAllFallback)
        assertFalse(segment.bindings.containsKey("print"))
    }

    @Test
    fun multiple_top_level_module_calls_produce_distinct_segment_environments() {
        val environment = analyze(
            path = "pkg/runtime.lua",
            source = """
                module("pkg.first")
                local a = 1
                module("pkg.second")
                local b = 2
            """.trimIndent()
        )

        assertEquals(listOf("pkg.first", "pkg.second"), environment.segments.map { it.moduleName })
    }

    @Test
    fun non_top_level_module_calls_do_not_switch_chunk_environment() {
        val environment = analyze(
            path = "pkg/runtime.lua",
            source = """
                local function configure()
                    module("pkg.inner")
                end
            """.trimIndent()
        )

        assertTrue(environment.segments.isEmpty())
    }

    private fun analyze(path: String, source: String) = LegacyModuleEnvironmentPass.analyze(
        path = VirtualPath.of(path),
        facts = DocumentFactsCollector.collect(VirtualPath.of(path), LuaParser().parse(source))
    )
}
