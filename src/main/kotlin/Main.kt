import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
        a.c,b,c = 12,function() end,114514
    """.trimIndent()
    ).run {
        println(Gson()
            .toJson(this))
    }
}