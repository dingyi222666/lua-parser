package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.source.AST2Lua
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertEquals

class AST2LuaRoundTripTest {

    private val parser = LuaParser()
    private val printer = AST2Lua()

    @Test
    fun roundTripsPrecedenceSensitiveAndStructuredForms() {
        val samples = listOf(
            "return (a + b) * c - d / (e + f)",
            "return -a^b, a .. b .. c, not a and b",
            "return root.child[1 + offset]:call('x', { nested = values[2], flag = true })",
            "local value = { total = (a + b) * c, { one = 1 }, items[index], ['name'] = other.name }"
        )

        samples.forEach { sample ->
            val initial = parser.parse(sample)
            val reparsed = parser.parse(printer.asCode(initial))

            assertEquals(renderShape(initial), renderShape(reparsed), sample)
        }
    }
}
