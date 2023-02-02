import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
        function test.c:a(a,b)
          print("支持中文，简洁的parser")
        end  
        :: test ::
        goto test
    """.trimIndent()
    ).run {
        println(Gson()
            .toJson(this))
    }
}