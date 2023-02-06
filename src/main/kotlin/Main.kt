import com.google.gson.GsonBuilder
import io.github.dingyi222666.lua.parser.LuaParser
import io.github.dingyi222666.lua.parser.ast.node.Position
import io.github.dingyi222666.lua.symbol.SemanticASTVisitor
import io.github.dingyi222666.lua.symbol.UnknownLikeTableSymbol
import java.io.File

fun main(args: Array<String>) {
    val parser = LuaParser()
    val source = File("src/main/resources/test.lua").bufferedReader()
    val rootNode = parser.parse(source)


    val globalScope = SemanticASTVisitor().analyze(rootNode)

    val localScope = globalScope.resolveScope(Position(1, 1))

    println(localScope.resolveSymbol("a"))
    println(localScope.resolveSymbol("b"))
    println(localScope.resolveSymbol("c"))
    println(localScope.resolveSymbol("s"))
    val funcScope = globalScope.resolveScope(Position(3,1))
    println(funcScope.resolveSymbol("t"))

    /* as UnknownLikeTableSymbol).getKeyValueLikeLua("a.c")*/

    println(
        GsonBuilder()
            //.setPrettyPrinting()
            .create()
            .toJson(rootNode)
    )

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