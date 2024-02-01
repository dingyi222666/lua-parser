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

         println(root)
       // val analyzer = SemanticAnalyzer()

      //  val scope = analyzer.analyze(root)

      //  println(scope.resolveSymbol("s").toString())

    }
}

val testSource = """
    local a  = 12
    local b = { s = a } 
    
    function b:a() 
      return { a = b.s }
    end
    
    
    local s = b:a()
    
    s.
""".trimIndent()
