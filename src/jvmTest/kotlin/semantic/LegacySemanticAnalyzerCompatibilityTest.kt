package semantic

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticAnalyzer
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
class LegacySemanticAnalyzerCompatibilityTest {

    @Test
    fun analyzer_wrapper_is_backed_by_pipeline_diagnostics() {
        val chunk = parse(
            """
            ---@param first string
            ---@param missing number
            ---@return string
            local function render(first)
                return 1, 2
            end
            """.trimIndent()
        )

        val pipeline = SemanticPipeline().analyze(chunk)
        val legacy = SemanticAnalyzer().analyze(chunk)

        assertEquals(pipeline.model.getDiagnostics().map { it.message }, legacy.diagnostics.take(3).map { it.message })
    }

    @Test
    fun analyzer_wrapper_preserves_legacy_scope_visibility() {
        val chunk = parse(
            """
            local outer = 1
            do
                local outer = "x"
                local inner = outer
            end
            """.trimIndent()
        )

        val pipeline = SemanticPipeline().analyze(chunk)
        val legacy = SemanticAnalyzer().analyze(chunk)
        val position = Position(4, 23)
        val legacyScope = assertNotNull(legacy.globalSymbolTable.findTableAtPosition(position))
        val pipelineScope = assertNotNull(pipeline.model.getScopeAt(position))
        val legacyVisible = legacyScope.getAllVisibleSymbols(position)

        assertEquals(1, legacyVisible.count { it.name == "outer" })
        assertTrue(legacyVisible.any { it.name == "inner" })
        val pipelineNames = pipelineScope.symbols.map { it.name }.toSet()
        assertTrue("outer" in pipelineNames)
        assertTrue("inner" in pipelineNames)
    }

    private fun parse(source: String) = LuaParser().parse(source)
}
