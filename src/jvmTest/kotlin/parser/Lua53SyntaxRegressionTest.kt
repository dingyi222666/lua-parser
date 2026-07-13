package parser

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Lua53SyntaxRegressionTest {

    @Test
    fun parsesResourceBackedLua53Fixture() {
        val chunk = assertResourceShape(
            LuaVersion.LUA_5_3,
            "/parser/regressions/lua53/labels_loops_tables.lua"
        )

        assertEquals(4, chunk.body.statements.size)
        assertEquals("again", chunk.firstStatement<LabelStatement>().identifier.name)
        assertEquals("again", assertIs<GotoStatement>(chunk.body.statements.last()).identifier.name)

        val numericStatement = assertIs<ForNumericStatement>(chunk.body.statements[1])
        assertEquals("i", numericStatement.variable.name)
        assertIs<LocalStatement>(numericStatement.body.statements.single())

        val genericStatement = assertIs<ForGenericStatement>(chunk.body.statements[2])
        assertContentEquals(listOf("key", "value"), genericStatement.variables.map { it.name })
    }

    @Test
    fun parsesLabelsGotoAndStructuredBlocks() {
        val cases = listOf<(String) -> Unit>(
            { source ->
                val chunk = parse(LuaVersion.LUA_5_3, source)

                assertEquals(2, chunk.body.statements.size)
                assertEquals("start", chunk.firstStatement<LabelStatement>().identifier.name)
                assertEquals("start", assertIs<GotoStatement>(chunk.body.statements[1]).identifier.name)
                assertEquals("Chunk(Block[Label(Id(start));Goto(Id(start))])", renderShape(chunk))
            },
            { source ->
                val chunk = parse(LuaVersion.LUA_5_3, source)
                val statement = chunk.firstStatement<DoStatement>()

                assertEquals(1, chunk.body.statements.size)
                assertEquals(1, statement.body.statements.size)
                assertIs<LocalStatement>(statement.body.statements.single())
                assertEquals("Chunk(Block[Do(Block[Local(Id(value)=Const(1))])])", renderShape(chunk))
            },
            { source ->
                val chunk = parse(LuaVersion.LUA_5_3, source)
                val statement = chunk.firstStatement<RepeatStatement>()

                assertEquals(1, chunk.body.statements.size)
                assertEquals(1, statement.body.statements.size)
                assertIs<LocalStatement>(statement.body.statements.single())
                assertEquals("Chunk(Block[Repeat(Block[Local(Id(value)=Const(1))]:Id(ready))])", renderShape(chunk))
            },
            { source ->
                val chunk = parse(LuaVersion.LUA_5_3, source)
                val statement = chunk.firstStatement<ForNumericStatement>()

                assertEquals("i", statement.variable.name)
                assertEquals(1, statement.body.statements.size)
                assertIs<LocalStatement>(statement.body.statements.single())
                assertEquals(
                    "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(10),Const(2):Block[Local(Id(step)=Id(i))])])",
                    renderShape(chunk)
                )
            },
            { source ->
                val chunk = parse(LuaVersion.LUA_5_3, source)
                val statement = chunk.firstStatement<ForGenericStatement>()

                assertContentEquals(listOf("key", "value"), statement.variables.map { it.name })
                assertEquals(1, statement.body.statements.size)
                assertIs<LocalStatement>(statement.body.statements.single())
                assertEquals(
                    "Chunk(Block[ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[Local(Id(current)=Id(value))])])",
                    renderShape(chunk)
                )
            }
        )

        val sources = listOf(
            "::start:: goto start",
            "do local value = 1 end",
            "repeat local value = 1 until ready",
            "for i = 1, 10, 2 do local step = i end",
            "for key, value in pairs(items) do local current = value end"
        )

        sources.zip(cases).forEach { (source, assertion) -> assertion(source) }
    }

    @Test
    fun parsesFunctionDeclarationFormsAndVarargs() {
        val statementCases = listOf(
            Triple(
                "function greet(a, ...) return ... end",
                false,
                "Id(greet)"
            ),
            Triple(
                "local function greet(a, ...) return ... end",
                true,
                "Id(greet)"
            ),
            Triple(
                "function obj:run(x, ...) return self, ... end",
                false,
                "Member(Id(obj):run)"
            )
        )

        statementCases.forEach { (source, isLocal, identifierShape) ->
            val chunk = parse(LuaVersion.LUA_5_3, source)
            val declaration = chunk.firstStatement<FunctionDeclaration>()
            val returnStatement = declaration.body!!.returnStatement!!

            assertEquals(isLocal, declaration.isLocal)
            assertEquals(identifierShape, renderShape(declaration.identifier!!))
            assertTrue(declaration.params.isNotEmpty())
            assertEquals("...", declaration.params.last().name)
            assertTrue(returnStatement.arguments.last() is VarargLiteral)
        }

        val globalDeclaration = parse(LuaVersion.LUA_5_3, "function greet(a, ...) return ... end")
            .firstStatement<FunctionDeclaration>()
        assertContentEquals(listOf("a", "..."), globalDeclaration.params.map { it.name })
        assertEquals("Vararg", renderShape(globalDeclaration.body!!.returnStatement!!.arguments.single()))

        val localDeclaration = parse(LuaVersion.LUA_5_3, "local function greet(a, ...) return ... end")
            .firstStatement<FunctionDeclaration>()
        assertContentEquals(listOf("a", "..."), localDeclaration.params.map { it.name })
        assertEquals("Function(Id(greet),Block[Return(Vararg)])", renderShape(localDeclaration))

        val methodDeclaration = parse(LuaVersion.LUA_5_3, "function obj:run(x, ...) return self, ... end")
            .firstStatement<FunctionDeclaration>()
        assertContentEquals(listOf("x", "..."), methodDeclaration.params.map { it.name })
        assertEquals("Id(self)", renderShape(methodDeclaration.body!!.returnStatement!!.arguments.first()))
        assertEquals("Vararg", renderShape(methodDeclaration.body!!.returnStatement!!.arguments[1]))

        val anonymous = assertIs<FunctionDeclaration>(
            parse(LuaVersion.LUA_5_3, "return function(...) return ... end").returnExpression()
        )
        assertEquals(null, anonymous.identifier)
        assertContentEquals(listOf("..."), anonymous.params.map { it.name })
        assertEquals("Function(null,Block[Return(Vararg)])", renderShape(anonymous))
    }

}
