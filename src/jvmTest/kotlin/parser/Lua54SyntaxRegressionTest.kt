package parser

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Lua54SyntaxRegressionTest {

    @Test
    fun parsesResourceBackedLua54Fixture() {
        val chunk = assertResourceShape(
            LuaVersion.LUA_5_4,
            "/parser/regressions/lua54/attributes_in_blocks.lua"
        )

        assertEquals(3, chunk.body.statements.size)

        val doStatement = assertIs<DoStatement>(chunk.body.statements[0])
        assertEquals(
            listOf("const", "close"),
            doStatement.body.statements.map {
                assertIs<AttributeIdentifier>(assertIs<LocalStatement>(it).init.single()).attributeName
            }
        )

        val numericStatement = assertIs<ForNumericStatement>(chunk.body.statements[1])
        assertEquals("const", assertIs<AttributeIdentifier>(assertIs<LocalStatement>(numericStatement.body.statements.single()).init.single()).attributeName)

        val repeatStatement = assertIs<RepeatStatement>(chunk.body.statements[2])
        assertEquals("close", assertIs<AttributeIdentifier>(assertIs<LocalStatement>(repeatStatement.body.statements.single()).init.single()).attributeName)
    }

    @Test
    fun parsesSingleAttributedLocals() {
        val constChunk = parse(LuaVersion.LUA_5_4, "local x<const> = 1")
        val constLocal = constChunk.firstStatement<LocalStatement>()
        val constIdentifier = assertIs<AttributeIdentifier>(constLocal.init.single())

        assertEquals("x", constIdentifier.name)
        assertEquals("const", constIdentifier.attributeName)
        assertTrue(constIdentifier.isLocal)
        assertEquals(1, constLocal.variables.size)
        assertEquals("Local(AttrId(x<const>)=Const(1))", renderShape(constLocal))

        val closeChunk = parse(LuaVersion.LUA_5_4, "local file<close> = open()")
        val closeLocal = closeChunk.firstStatement<LocalStatement>()
        val closeIdentifier = assertIs<AttributeIdentifier>(closeLocal.init.single())

        assertEquals("file", closeIdentifier.name)
        assertEquals("close", closeIdentifier.attributeName)
        assertTrue(closeIdentifier.isLocal)
        assertEquals(1, closeLocal.variables.size)
        assertEquals("Local(AttrId(file<close>)=Call(Id(open):))", renderShape(closeLocal))
    }

    @Test
    fun parsesMixedAttributeLists() {
        val chunk = parse(LuaVersion.LUA_5_4, "local a<const>, b<close>, c = ...")
        val localStatement = chunk.firstStatement<LocalStatement>()

        assertEquals(3, localStatement.init.size)
        localStatement.init.forEach { assertIs<AttributeIdentifier>(it) }
        assertContentEquals(listOf("a", "b", "c"), localStatement.init.map { (it as AttributeIdentifier).name })
        assertContentEquals(listOf("const", "close", null), localStatement.init.map { (it as AttributeIdentifier).attributeName })
        assertEquals(1, localStatement.variables.size)
        assertIs<VarargLiteral>(localStatement.variables.single())
        assertEquals(
            "Local(AttrId(a<const>),AttrId(b<close>),AttrId(c)=Vararg)",
            renderShape(localStatement)
        )
    }

    @Test
    fun parsesAttributedLocalsInsideBlocksAndLoops() {
        val doChunk = parse(
            LuaVersion.LUA_5_4,
            """
            do
                local value<const> = 1
            end
            """.trimIndent()
        )
        val doStatement = doChunk.firstStatement<DoStatement>()
        val doLocal = assertIs<LocalStatement>(doStatement.body.statements.single())
        val doIdentifier = assertIs<AttributeIdentifier>(doLocal.init.single())
        assertEquals("const", doIdentifier.attributeName)
        assertEquals("Chunk(Block[Do(Block[Local(AttrId(value<const>)=Const(1))])])", renderShape(doChunk))

        val repeatChunk = parse(
            LuaVersion.LUA_5_4,
            """
            repeat
                local handle<close> = open()
            until done
            """.trimIndent()
        )
        val repeatStatement = repeatChunk.firstStatement<RepeatStatement>()
        val repeatLocal = assertIs<LocalStatement>(repeatStatement.body.statements.single())
        val repeatIdentifier = assertIs<AttributeIdentifier>(repeatLocal.init.single())
        assertEquals("close", repeatIdentifier.attributeName)
        assertEquals(
            "Chunk(Block[Repeat(Block[Local(AttrId(handle<close>)=Call(Id(open):))]:Id(done))])",
            renderShape(repeatChunk)
        )

        val numericChunk = parse(
            LuaVersion.LUA_5_4,
            """
            for i = 1, 3 do
                local step<const> = i
            end
            """.trimIndent()
        )
        val numericStatement = numericChunk.firstStatement<ForNumericStatement>()
        val numericLocal = assertIs<LocalStatement>(numericStatement.body.statements.single())
        val numericIdentifier = assertIs<AttributeIdentifier>(numericLocal.init.single())
        assertEquals("const", numericIdentifier.attributeName)
        assertEquals(
            "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[Local(AttrId(step<const>)=Id(i))])])",
            renderShape(numericChunk)
        )

        val genericChunk = parse(
            LuaVersion.LUA_5_4,
            """
            for key, value in pairs(items) do
                local current<close> = value
            end
            """.trimIndent()
        )
        val genericStatement = genericChunk.firstStatement<ForGenericStatement>()
        val genericLocal = assertIs<LocalStatement>(genericStatement.body.statements.single())
        val genericIdentifier = assertIs<AttributeIdentifier>(genericLocal.init.single())
        assertEquals("close", genericIdentifier.attributeName)
        assertEquals(
            "Chunk(Block[ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[Local(AttrId(current<close>)=Id(value))])])",
            renderShape(genericChunk)
        )
    }

    @Test
    fun gatesLocalAttributesByLuaVersion() {
        val successCases = listOf(
            "local x<const> = 1" to "Chunk(Block[Local(AttrId(x<const>)=Const(1))])",
            "local file<close> = open()" to "Chunk(Block[Local(AttrId(file<close>)=Call(Id(open):))])",
            "local a<const>, b<close>, c = ..." to "Chunk(Block[Local(AttrId(a<const>),AttrId(b<close>),AttrId(c)=Vararg)])",
            "do local value<const> = 1 end" to "Chunk(Block[Do(Block[Local(AttrId(value<const>)=Const(1))])])"
        )

        successCases.forEach { (source, expectedShape) ->
            assertEquals(expectedShape, renderShape(parse(LuaVersion.LUA_5_4, source)), source)
        }

        listOf(
            "local x<const> = 1",
            "local file<close> = open()",
            "local a<const>, b<close>, c = ...",
            "do local value<const> = 1 end"
        ).forEach { source ->
            assertParseFails(LuaVersion.LUA_5_3, source)
        }
    }
}
