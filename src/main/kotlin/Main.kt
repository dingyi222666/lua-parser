import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
        local function s(a)
           local f = not true
        end   
    """.trimIndent()
    ).run {
        println(this)
    }
}