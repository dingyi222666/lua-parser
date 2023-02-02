import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.dingyi222666.lua.parser.LuaParser

fun main(args: Array<String>) {
    val parser = LuaParser()
    parser.parse(
        """
        while true 
          a.c()
          local f = a.c:a(1,2,a)
        end
        local f = function(a,b)
           print("call f")
        end
    """.trimIndent()
    ).run {
        println(Gson()
            .toJson(this))
    }
}