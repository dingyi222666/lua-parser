import com.google.gson.GsonBuilder
import io.github.dingyi222666.lua.optimization.OptimizationAnalyzer
import io.github.dingyi222666.lua.parser.LuaParser
import io.github.dingyi222666.lua.parser.ast.node.Position
import io.github.dingyi222666.lua.semantic.SemanticAnalyzer
import io.github.dingyi222666.lua.source.AST2Lua
import io.github.dingyi222666.lua.typesystem.TableType
import java.io.File

fun main(args: Array<String>) {
    /*  val parser = LuaParser()
      val source = File("src/main/resources/test.lua").bufferedReader()
      val rootNode = parser.parse(source)


      val globalScope = SemanticAnalyzer().analyze(rootNode)

      val localScope = globalScope.resolveScope(Position(1, 1))

      println(localScope.resolveSymbol("a"))
      println(localScope.resolveSymbol("b"))
      println(localScope.resolveSymbol("c"))
      println(localScope.resolveSymbol("d"))
      println(localScope.resolveSymbol("e"))

      println(
          GsonBuilder()
              //.setPrettyPrinting()
              .create()
              .toJson(rootNode)
      )*/


    val parser = LuaParser()
    val source = File("src/main/resources/main(3).lua").bufferedReader()
    val rootNode = parser.parse(source)

    val analyzer = OptimizationAnalyzer()
    analyzer.analyze(rootNode)

    println(
        AST2Lua().toLua(rootNode)
    )

    /*val analyzer = OptimizationAnalyzer()
    analyzer.analyze(rootNode)

    println(analyzer.genCode())*/


}