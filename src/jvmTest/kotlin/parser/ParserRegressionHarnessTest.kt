package parser

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ParserRegressionHarnessTest {

    @Test
    fun parseHelpersUseExplicitVersioningAndTypedAccessors() {
        val lua54Chunk = parse(LuaVersion.LUA_5_4, "local value<const> = 1")

        assertEquals(
            "Local(AttrId(value<const>)=Const(1))",
            renderShape(lua54Chunk.firstStatement<LocalStatement>())
        )
        assertParseFails(LuaVersion.LUA_5_3, "local value<const> = 1")
    }

    @Test
    fun parseIsStrictByDefaultAndRecoveryIsExplicit() {
        val malformedSource = "while ready print(1) end"

        assertParseFails(LuaVersion.LUA_5_3, malformedSource)

        val recovered = parseRecovering(LuaVersion.LUA_5_3, malformedSource)

        assertEquals(
            "Chunk(Block[While(Id(ready):Block[CallStmt(Call(Id(print):Const(1)))])])",
            renderShape(recovered)
        )
    }

    @Test
    fun returnExpressionAndCallShapesCoverExtendedExpressionNodes() {
        val lambdaChunk = parse(LuaVersion.ANDROLUA_5_3, "return lambda value: value")
        val stringCallChunk = parse(LuaVersion.LUA_5_3, "print 'hi'")
        val tableCallChunk = parse(LuaVersion.LUA_5_3, "print { value = 1 }")

        assertEquals("Lambda(Id(value):Id(value))", renderShape(lambdaChunk.returnExpression()))
        assertEquals(
            "CallStmt(Call(StringCall(Id(print):Const('hi')):))",
            renderShape(stringCallChunk.firstStatement<CallStatement>())
        )
        assertEquals(
            "CallStmt(Call(TableCall(Id(print):Table(TableKeyString(Id(value)=Const(1)))):))",
            renderShape(tableCallChunk.firstStatement<CallStatement>())
        )
    }

}
