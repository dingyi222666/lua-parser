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

    @Test
    fun commentsHelperKeepsDocAndLineCommentsDistinct() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            ---@type string
            -- note
            return value
            """.trimIndent()
        )

        val comments = chunk.comments()

        assertEquals(2, comments.size)
        assertIs<CommentStatement>(comments[0])
        assertEquals("Comment(doc:---@type string)", renderShape(comments[0]))
        assertEquals("Comment(line:-- note)", renderShape(comments[1]))
    }

    @Test
    fun renderShapeCoversExtendedStatementNodes() {
        assertEquals(
            "Chunk(Block[Label(Id(again));Goto(Id(again))])",
            renderShape(parse(LuaVersion.LUA_5_3, "::again:: goto again"))
        )
        assertEquals(
            "Chunk(Block[While(Id(ready):Block[Continue])])",
            renderShape(parse(LuaVersion.ANDROLUA_5_3, "while ready do continue end"))
        )
        assertEquals(
            "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(10),Const(2):Block[])])",
            renderShape(parse(LuaVersion.LUA_5_3, "for i = 1, 10, 2 do end"))
        )
        assertEquals(
            "Chunk(Block[ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[])])",
            renderShape(parse(LuaVersion.LUA_5_3, "for key, value in pairs(items) do end"))
        )
        assertEquals(
            "Chunk(Block[Repeat(Block[]:Id(ready))])",
            renderShape(parse(LuaVersion.LUA_5_3, "repeat until ready"))
        )
        assertEquals(
            "Chunk(Block[Do(Block[])])",
            renderShape(parse(LuaVersion.LUA_5_3, "do end"))
        )
        assertEquals(
            "Chunk(Block[When(Id(ready)?CallStmt(Call(Id(print):Const(1))):CallStmt(Call(Id(print):Const(2))))])",
            renderShape(parse(LuaVersion.ANDROLUA_5_3, "when ready print(1) else print(2)"))
        )
        assertEquals(
            "Chunk(Block[Switch(Id(value):Case(Const(1),Const(2):Block[CallStmt(Call(Id(print):Const(1)))]),Default(Block[CallStmt(Call(Id(print):Const(2)))]))])",
            renderShape(parse(LuaVersion.ANDROLUA_5_3, "switch value do case 1, 2 then print(1) default print(2) end"))
        )
    }
}
