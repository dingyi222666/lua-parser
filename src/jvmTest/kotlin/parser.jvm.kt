import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticAnalyzer
import io.github.dingyi222666.luaparser.source.AST2Lua
import kotlin.test.Test

class JvmPlatformParserTest {

    @Test
    fun parse() {
        val parser = LuaParser()

        val root = parser.parse(testSource)

        val analyzer = SemanticAnalyzer()

        val scope = analyzer.analyze(root)

        println(scope.resolveSymbol("c").toString())
        println(scope.resolveScope(Position(15, 1)).resolveSymbol("c").toString())
        println(scope.resolveSymbol("d").toString())
        println(scope.resolveSymbol("sb").toString())
        println(scope.resolveSymbol("x").toString())
        println(scope.resolveSymbol("print").toString())
    }
}

val testSource = """
    local a  = 12
    local b = { s = a } 
    
    function b:a() 
      return { a = b.s }
    end
    
    local function ss() return 1,"" end
    
    sb,x = ss()
    
    d = b
    
    local s = b:a()
    
    local c = 12
    
    do
        local c = ""
        d.c = 1 + ""
    end
    
    function pairs1(t)
       return 1, ""
    end
    
    for i = 1, 10 do
        print(i)
        print(""..2)
        print(ss)
        print(b)
       
    end
""".trimIndent()
