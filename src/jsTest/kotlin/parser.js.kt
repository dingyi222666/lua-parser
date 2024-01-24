import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.source.AST2Lua
import kotlin.test.Test

 class JsPlatformParserTest {

    @Test
    fun parse() {
        val parser = LuaParser()

        val root = parser.parse(source)

        console.log(AST2Lua().asCode(root))
    }
}