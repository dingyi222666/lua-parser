import io.github.dingyi222666.parser.lua.ast.ASTGenerator

fun main(args: Array<String>) {
    val generator = ASTGenerator()
    generator.generate(
        """
        local d,a,c = 12,12,a
    """.trimIndent()
    ).run {
        println(this)
    }
}