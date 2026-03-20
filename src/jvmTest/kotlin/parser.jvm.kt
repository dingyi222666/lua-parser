import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.AnalysisResult
import io.github.dingyi222666.luaparser.semantic.SemanticAnalyzer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmPlatformParserSmokeTest {

    @Test
    fun smokeAnalyzesAnnotatedFunctionsAndAssignmentDiagnostics() {
        val result = analyze(
            """
            ---@param x number
            ---@param y number
            ---@return number
            local function add(x, y)
                return x + y
            end

            local sum = add(1, 2)

            ---@type string
            GLOBAL_NAME = 123
            """.trimIndent()
        )

        assertEquals("number", result.symbolTable.resolve("sum")?.type?.name)
        assertEquals("string", result.globalSymbolTable.getGlobalSymbols()["GLOBAL_NAME"]?.type?.name)
        assertTrue(result.diagnostics.any { diagnostic ->
            diagnostic.message.contains("not assignable") && diagnostic.message.contains("string")
        })
    }

    @Test
    fun smokePreservesLegacyScopeVisibilityThroughWrapper() {
        val result = analyze(
            """
            local outer = 1
            do
                local outer = "x"
                local inner = outer
            end
            """.trimIndent()
        )

        val position = io.github.dingyi222666.luaparser.parser.ast.node.Position(4, 23)
        val scope = result.globalSymbolTable.findTableAtPosition(position)

        assertNotNull(scope)
        assertEquals(1, scope.getAllVisibleSymbols(position).count { it.name == "outer" })
        assertTrue(scope.getAllVisibleSymbols(position).any { it.name == "inner" })
    }

    @Suppress("DEPRECATION")
    private fun analyze(code: String): AnalysisResult {
        val parser = LuaParser()
        val analyzer = SemanticAnalyzer()
        return analyzer.analyze(parser.parse(code))
    }
}
