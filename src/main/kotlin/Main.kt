import com.google.gson.Gson
import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """ if a>1 print("f") elseif a>2 print("5") else a=a+1 end
    """.trimIndent()
    ).run {
        println(Gson()
            .toJson(this))
    }
}