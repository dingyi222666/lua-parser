import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
        local f = - 35 + 12 * 7 >> 1 % 3
    """.trimIndent()
    ).run {
        println(Gson()
            .toJson(this))
    }
}