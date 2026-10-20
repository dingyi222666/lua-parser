import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM platform end-to-end smoke: parse + analyze the public [SemanticPipeline]
 * facade over annotated source. The former legacy analyzer/symbol-table assertions
 * retired with the legacy compatibility surface; the fixtures stay so the JVM
 * platform path (parser -> pipeline -> model) keeps a standalone smoke.
 */
class JvmPlatformParserSmokeTest {

    @Test
    fun smokeAnalyzesAnnotatedFunctionsAndGlobals() {
        val analysis = analyze(SOURCE)

        // Local `sum` resolves at its declaration; the annotated global write resolves too.
        assertEquals("sum", analysis.model.getSymbolAt(Position(8, 7))?.name)
        assertEquals("GLOBAL_NAME", analysis.model.getSymbolAt(Position(11, 1))?.name)
        assertEquals(analysis.model.getDiagnostics().size, analysis.summary.diagnosticCount)
    }

    @Test
    fun smokePreservesScopeVisibilityThroughShadowing() {
        val analysis = analyze(
            """
            local outer = 1
            do
                local outer = "x"
                local inner = outer
            end
            """.trimIndent()
        )

        val position = Position(4, 23)
        val scope = assertNotNull(analysis.model.getScopeAt(position))

        // Innermost scope sees the shadowing `outer` exactly once plus `inner`.
        assertEquals(1, scope.symbols.count { it.name == "outer" })
        assertTrue(scope.symbols.any { it.name == "inner" })
    }

    private fun analyze(code: String) = SemanticPipeline().analyze(LuaParser().parse(code))

    private companion object {
        val SOURCE = """
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
    }
}
