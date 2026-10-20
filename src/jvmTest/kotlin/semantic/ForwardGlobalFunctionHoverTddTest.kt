package semantic

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForwardGlobalFunctionHoverTddTest {
    @Test
    fun laterChunkGlobalFunctionIsTypedInsideAnEarlierFunctionBody() {
        val source = """
            FileUtil = {}

            FileUtil.saveBitmap = function(bitmap, path)
                use(bitmap, function(out)
                    out.close()
                end)
            end

            function use(input, func)
                func(input)
            end
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        val symbol = assertNotNull(model.getSymbolAt(positionOf(source, "use")))
        assertTrue(
            symbol.type?.displayName?.startsWith("fun(") == true,
            "Forward global function hover must expose its callable type; actual=${symbol.type}"
        )
    }

    @Test
    fun laterLocalFunctionDoesNotBecomeVisibleBeforeItsDeclaration() {
        val source = """
            local function caller()
                laterLocal()
            end

            local function laterLocal()
            end
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        assertNull(model.getSymbolAt(positionOf(source, "laterLocal")))
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var fromIndex = 0
        var matchIndex = -1
        repeat(occurrence) {
            matchIndex = source.indexOf(needle, fromIndex)
            require(matchIndex >= 0) { "Needle '$needle' occurrence $occurrence not found" }
            fromIndex = matchIndex + needle.length
        }
        val lineStart = source.lastIndexOf('\n', startIndex = matchIndex).let { if (it < 0) 0 else it + 1 }
        val line = source.substring(0, matchIndex).count { it == '\n' } + 1
        return Position(line, matchIndex - lineStart + 1)
    }
}
