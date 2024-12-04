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
    
            ---@type fun(x: number, y: number): number
            local function add(x, y)
                return x + y
            end
            
            ---
            --- If `object` does not have a metatable, returns **nil**. Otherwise, if the
            --- object's metatable has a `"__metatable"` field, returns the associated
            --- value. Otherwise, returns the metatable of the given object.
            ---@param object any
            ---@return any
            function getmetatable(object) end

            ---
            --- Returns three values (an iterator function, the table `t`, and 0) so that
            --- the construction
            --- > `for i,v in ipairs(t) do` *body* `end`
            --- will iterate over the key–value pairs (1,`t[1]`), (2,`t[2]`), ..., up to
            --- the first absent index.
            ---@generic V
            ---@param t table<number, V>|V[]
            ---@return fun(tbl: table<number, V>):number, V
            function ipairs(t) end

            ---
            --- Loads a chunk.
            --- If `chunk` is a string, the chunk is this string. If `chunk` is a function,
            --- `load` calls it repeatedly to get the chunk pieces. Each call to `chunk`
            --- must return a string that concatenates with previous results. A return of
            --- an empty string, **nil**, or no value signals the end of the chunk.
            ---
            --- If there are no syntactic errors, returns the compiled chunk as a function;
            --- otherwise, returns **nil** plus the error message.
            ---
            --- If the resulting function has upvalues, the first upvalue is set to the
            --- value of `env`, if that parameter is given, or to the value of the global
            --- environment. Other upvalues are initialized with **nil**. (When you load a
            --- main chunk, the resulting function will always have exactly one upvalue, the
            --- _ENV variable. However, when you load a binary chunk created from a
            --- function (see string.dump), the resulting function can have an arbitrary
            --- number of upvalues.) All upvalues are fresh, that is, they are not shared
            --- with any other function.
            ---
            --- `chunkname` is used as the name of the chunk for error messages and debug
            --- information. When absent, it defaults to `chunk`, if `chunk` is a string,
            --- or to "=(`load`)" otherwise.
            ---
            --- The string `mode` controls whether the chunk can be text or binary (that is,
            --- a precompiled chunk). It may be the string "b" (only binary chunks), "t"
            --- (only text chunks), or "bt" (both binary and text). The default is "bt".
            ---
            --- Lua does not check the consistency of binary chunks. Maliciously crafted
            --- binary chunks can crash the interpreter.
            ---@overload fun(chunk:fun():string):any
            ---@param chunk fun():string
            ---@param chunkname string
            ---@param mode string
            ---@param env any
            function load(chunk, chunkname, mode, env) end

            ---
            --- Similar to `load`, but gets the chunk from file `filename` or from the
            --- standard input, if no file name is given.
            ---@overload fun()
            ---@param filename string
            ---@param mode string
            ---@param env any
            function loadfile(filename, mode, env) end
            
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
        
        println("Global STRING type: $stringType")
        println("Global GLOBAL_VAR type: $globalVarType")

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
