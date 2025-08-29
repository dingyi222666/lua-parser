import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticAnalyzer
import io.github.dingyi222666.luaparser.semantic.types.FunctionType
import kotlin.test.Test

class JvmPlatformParserTest {

    @Test
    fun parse() {


        val code = """
            ---@type string
            local name = "test"
            
            ---@type number
            local age = 25
    
            ---@return number
            local function add(x, y)
                return x + y
            end
            
            local function concat(x, y)
                return x .. y
            end
            
            ---@param y number
            ---@param x number
            ---@return number
            local function multiply(x, y)
                return x * y
            end
            
            local function pow(x, y)
                return multiply(x, y)
            end
            
            local tab = {
                name = name,
                age = age
            }
    
            local result = add(tab.age, tab.age)
            
            ---@type string
            STRING = 123  -- 这里应该报错，因为类型不匹配
            
            GLOBAL_VAR = "hello"  -- 这是一个全局变量
        """.trimIndent()

        val parser = LuaParser()
        val analyzer = SemanticAnalyzer()

        val ast = parser.parse(code)
        val result = analyzer.analyze(ast)

        // 检查全局变量
        val stringType = result.globalSymbols["STRING"]?.type  // string
        val globalVarType = result.globalSymbols["GLOBAL_VAR"]?.type  // string
        val addType = result.symbolTable.resolveAtPosition("add", Position(20, 1))?.type
        val concatType = result.symbolTable.resolveAtPosition("concat", Position(4, 1))?.type
        val multiplyType = result.symbolTable.resolveAtPosition("multiply", Position(20, 1))?.type

        val pairs = result.globalSymbols["ipairs"]?.type


        println("Global STRING type: $stringType")
        println("add type: $addType")
        println("concat type: $concatType")
        println("Global GLOBAL_VAR type: $globalVarType")
        println("multiply type: $multiplyType")
        println("ipairs type: $pairs")
        println("all: ${result.symbolTable.toString()}")

        // 检查诊断信息
        result.diagnostics.forEach { diagnostic ->
            println("${diagnostic.severity}: ${diagnostic.message} at ${diagnostic.range}")
        }
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
