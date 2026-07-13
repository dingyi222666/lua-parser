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

}
