import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
       for i = 1,1 do
print(i)
end
    """.trimIndent()
    ).run {
        println(Gson()
            .toJson(this))
    }
}