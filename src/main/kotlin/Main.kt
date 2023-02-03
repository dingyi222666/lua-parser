import com.google.gson.Gson
import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
            b = { a=12,i = 14,["aaa"] = function()end}
    """.trimIndent()
    ).run {
        println(Gson()
            .toJson(this))
    }
}