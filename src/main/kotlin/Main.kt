import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
        local s = ...
    """.trimIndent()
    ).run {
        println(this)
    }
}