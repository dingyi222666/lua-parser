import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.source.AST2Lua
import io.github.dingyi222666.luaparser.util.parseLuaString
import kotlin.test.Test

 class ParserTest {

    @Test
    fun parse() {
        println(parseLuaString("""[==[xxx]==]"""))
        println(parseLuaString(""" "xxxx" """))
        println(parseLuaString("""'xxxx'"""))
        println(parseLuaString("""'\u6578\x01\n\r\t'"""))
        println(parseLuaString("""[[\u6578]]"""))
        val parser = LuaParser()

        val root = parser.parse(source)

        println(AST2Lua().asCode(root))
    }

     @Test
     fun parseLua54() {
         val parser = LuaParser(LuaVersion.LUA_5_4)

         val root = parser.parse("""
             local apple <const>, carrot = 'fruit', 'vegetable'
         """.trimIndent())

         println(AST2Lua().asCode(root))
     }
}

const val source = """
local foo = 1 do foo = 2 end
do local foo = 1 end foo = 2
do local foo = 1 end do foo = 2 end
local foo do foo = 1 do foo = 2 end end
local function foo() end foo()
local a = { a }
local b = { b, b.a, b[a], b:a() }
local b = {} local a = { b, b.a, b[a], b:a() }
local c local a = { b[c] }
local a = function() end a()
local a, b = 1, a
local a, b = 1, function() b = 2 end
local a, b for i, a, b in c do end
local a, b, c for i, a, b in c do end
local a = {} function a:b() return self end self = nil
repeat local a = true until a
local a = function (b) end b = 0
-- hello
for a = 1, 5 do end a = 0
"""