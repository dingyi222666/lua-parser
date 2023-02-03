import com.google.gson.Gson
import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
           a {1} (1)
    """.trimIndent()
    ).run {
        println(Gson()
            .toJson(this))
    }
}