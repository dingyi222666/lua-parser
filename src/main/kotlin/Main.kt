import com.google.gson.Gson
import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
for a,z,x in pairs(1),a
  end
    """.trimIndent()
    ).run {
        println(Gson()
            .toJson(this))
    }
}