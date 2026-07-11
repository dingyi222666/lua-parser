package parser.androidlua

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import parser.assertParseFails
import parser.firstStatement
import parser.parse
import parser.renderShape
import parser.returnExpression

class ParserVersionPolicyTddTest {

    @Test
    fun noArgParserDefaultsToAndroLua53KeywordPolicy() {
        assertEquals(
            "Chunk(Block[While(Id(ready):Block[Continue])])",
            renderShape(LuaParser().parse("while ready do continue end"))
        )

        assertEquals(
            "Chunk(Block[Return(Lambda(Id(value):Id(value)))])",
            renderShape(LuaParser().parse(LuaLexer("return lambda value: value")))
        )
    }

    @Test
    fun explicitLua53DoesNotEnableAndroLuaKeywordSyntax() {
        val names = listOf("switch", "case", "default", "continue", "when", "lambda")
        val localSource = "local ${names.joinToString()} = ${names.joinToString()}"
        val local = assertIs<LocalStatement>(parse(LuaVersion.LUA_5_3, localSource).body.statements.single())

        assertContentEquals(names, local.init.map { it.name })
        assertContentEquals(names, local.variables.map { assertIs<Identifier>(it).name })
        assertEquals(
            "Chunk(Block[Return(Id(switch),Id(case),Id(default),Id(continue),Id(when),Id(lambda))])",
            renderShape(parse(LuaVersion.LUA_5_3, "return ${names.joinToString()}"))
        )

        assertParseFails(LuaVersion.LUA_5_3, "while ready do continue end")
        assertParseFails(LuaVersion.LUA_5_3, "when ready print(1) else print(2)")
        assertParseFails(LuaVersion.LUA_5_3, "return lambda value: value")
    }

    @Test
    fun explicitAndroLua53EnablesAndroLuaKeywordSyntax() {
        assertEquals(
            "Chunk(Block[While(Id(ready):Block[Continue])])",
            renderShape(parse(LuaVersion.ANDROLUA_5_3, "while ready do continue end"))
        )

        parse(LuaVersion.ANDROLUA_5_3, "when ready print(1) else print(2)").firstStatement<WhenStatement>()
        assertIs<LambdaDeclaration>(
            parse(LuaVersion.ANDROLUA_5_3, "return lambda value: value").returnExpression()
        )
    }
}
