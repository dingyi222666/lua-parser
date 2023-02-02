import com.google.gson.Gson
import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
print "6" (11)
    """.trimIndent()
    ).run {
        println(Gson()
            .toJson(this))
    }
}