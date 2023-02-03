import com.google.gson.Gson
import io.github.dingyi222666.lua.lexer.LuaLexer
import io.github.dingyi222666.lua.lexer.WrapperLuaLexer
import io.github.dingyi222666.lua.parser.LuaParser
import java.io.File
import java.io.StringReader

fun main(args: Array<String>) {
    val parser = LuaParser()
    val source = File("src/main/resources/test.lua").bufferedReader()
    parser.parse(source).run {
        println(Gson()
            .toJson(this))
    }
  /*  val test = "{Type='Eof'}"
    // Type=
    // Type=
    val lexer = WrapperLuaLexer(LuaLexer(StringReader(test)))
    val one = lexer.advance() // {
    lexer.advance() // Type
    val current = lexer.advance() // =
    println(current)
    lexer.yyback(2)
    println(lexer.advance()) // Type
    println(lexer.advance()) // =*/
}