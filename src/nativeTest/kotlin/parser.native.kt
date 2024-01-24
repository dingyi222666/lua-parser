import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.source.AST2Lua
import kotlin.test.Test

 class NativePlatformParserTest {

    @Test
    fun parse() {
        val parser = LuaParser()

        val root = parser.parse(source)

        println(AST2Lua().asCode(root))
    }
}
