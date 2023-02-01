import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
        local function s(a)
           local f = 1
        end   
    """.trimIndent()
    ).run {
        println(this)
    }
}