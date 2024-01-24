import com.google.gson.GsonBuilder
import io.github.dingyi222666.lua.lexer.LuaLexer
import io.github.dingyi222666.lua.lexer.TestLuaLexer
import io.github.dingyi222666.lua.parser.LuaParser
import io.github.dingyi222666.lua.parser.ast.node.Position
import io.github.dingyi222666.lua.semantic.SemanticAnalyzer
import java.io.File

fun main(args: Array<String>) {

    val lexer = TestLuaLexer(
        """
        local s = 0xff1.2
        s += 1
        local 你好 = a
    """.trimIndent()
    )


    for ((token, text) in lexer) {
        println("$token $text")
    }

    val parser = LuaParser()
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
    )


}
/*

fun main2() {
    val image = ImageIO.read(File("src/main/resources/img.jpg"))
    val width = image.width
    val height = image.height
    val blockSize = 6
    val asciiChars = arrayOf("@", "#", "S", "%", "?", "*", "+", ";", ":", ",", ".")
    val font = Font("Monospaced", Font.PLAIN, 12)
    val outputWidth = (width + blockSize - 1) / blockSize * 12
    val outputHeight = (height + blockSize - 1) / blockSize * 12
    val outputImage = BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = outputImage.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, outputImage.width, outputImage.height)
    graphics.font = font
    val executor = Executors.newFixedThreadPool(8)
    for (by in 0 until height step blockSize) {
        for (bx in 0 until width step blockSize) {
            executor.submit {
                var graySum = 0.0
                for (y in by until min(by + blockSize, height))
                    for (x in bx until min(bx + blockSize, width)) {
                        val color = Color(image.getRGB(x, y))
                        graySum += 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
                    }
                val gray = (graySum / (blockSize * blockSize)).toInt()
                val index = (gray * (asciiChars.size - 1) / 255).toInt()
                val char = asciiChars[index]
                graphics.color = Color(gray, gray, gray)
                graphics.drawString(char, (bx / blockSize) * 12, ((by + blockSize - 1) / blockSize + 1) * 12)
            }
        }
    }
    executor.shutdown()
    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    ImageIO.write(outputImage, "PNG", File("src/main/resources/output.png"))
}
*/
