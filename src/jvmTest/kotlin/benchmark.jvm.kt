import io.github.dingyi222666.luaparser.util.Character
import org.junit.Test
import java.io.File
import kotlin.time.measureTime

class JvmPlatformBenchmark {

    @Test
    fun benchmark() {
        val source = this::class.java.getResource("test.lua")
        val text = source.readText()

        val flexAdvancedTime = measureTime {
            val lexer = flex.LuaLexer(text)
            var len = 0

            var token: flex.LuaTokenTypes? = lexer.advance()

            while (token != null) {
                token = lexer.advance()

                len += lexer.yytext().length
            }



            lexer.yyclose()
        }.inWholeMilliseconds

        Character.initMap()
        val nativeAdvanceTime = measureTime {
            val lexer = io.github.dingyi222666.luaparser.lexer.LuaLexer(text)
            var len = 0

            var token = lexer.nextToken()

            while (token != io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.EOF) {
                token = lexer.nextToken()

                len += lexer.tokenText.length
            }

            println("source length $len")
        }.inWholeMilliseconds

        println("flex advanced time: $flexAdvancedTime ms, native advance time: $nativeAdvanceTime ms")
    }

    @Test
    fun forBenchmark() {
        for (i in 0..<10) {
            benchmark()
        }
    }
}