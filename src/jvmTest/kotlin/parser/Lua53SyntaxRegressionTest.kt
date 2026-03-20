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

    @Test
    fun parsesTableConstructorsAndCallVsMemberShapes() {
        val tables = listOf(
            "return { name = value }",
            "return { [key] = value }",
            "return { name = value, [key] = other, third }"
        ).map { parse(LuaVersion.LUA_5_3, it).returnExpression() }

        val keyed = assertIs<TableConstructorExpression>(tables[0])
        assertEquals(1, keyed.fields.size)
        assertIs<TableKeyString>(keyed.fields.single())
        assertEquals("Table(TableKeyString(Id(name)=Id(value)))", renderShape(keyed))

        val indexed = assertIs<TableConstructorExpression>(tables[1])
        assertEquals(1, indexed.fields.size)
        assertIs<TableKey>(indexed.fields.single())
        assertEquals("Table(TableKey(Id(key)=Id(value)))", renderShape(indexed))

        val mixed = assertIs<TableConstructorExpression>(tables[2])
        assertEquals(3, mixed.fields.size)
        assertIs<TableKeyString>(mixed.fields[0])
        assertIs<TableKey>(mixed.fields[1])
        assertIs<TableKey>(mixed.fields[2])
        assertEquals("Id(third)", renderShape(mixed.fields[2].value))

        val memberCases = listOf(
            "return obj.method" to "Member(Id(obj).method)",
            "return obj:method(1)" to "Call(Member(Id(obj):method):Const(1))",
            "return obj.method(1)" to "Call(Member(Id(obj).method):Const(1))",
            "return root.child[1]:call('x')" to "Call(Member(Index(Member(Id(root).child)[Const(1)]):call):Const('x'))"
        )

        memberCases.forEach { (source, expectedShape) ->
            val expression = parse(LuaVersion.LUA_5_3, source).returnExpression()

            assertEquals(expectedShape, renderShape(expression))
        }

        val member = assertIs<MemberExpression>(parse(LuaVersion.LUA_5_3, "return obj.method").returnExpression())
        assertEquals("obj", assertIs<Identifier>(member.base).name)
        assertEquals("method", member.identifier.name)

        val methodCall = assertIs<CallExpression>(parse(LuaVersion.LUA_5_3, "return obj:method(1)").returnExpression())
        assertEquals(":", assertIs<MemberExpression>(methodCall.base).indexer)
    }

    @Test
    fun respectsLua53OperatorPrecedence() {
        val cases = listOf(
            "a | b & c" to "Binary(|,Id(a),Binary(&,Id(b),Id(c)))",
            "a << b + c" to "Binary(<<,Id(a),Binary(+,Id(b),Id(c)))",
            "7 // 3 % 2" to "Binary(%,Binary(//,Const(7),Const(3)),Const(2))",
            "a ^ b ^ c" to "Binary(^,Id(a),Binary(^,Id(b),Id(c)))",
            "a .. b .. c" to "Binary(..,Id(a),Binary(..,Id(b),Id(c)))",
            "not a and b or c" to "Binary(or,Binary(and,Unary(not,Id(a)),Id(b)),Id(c))"
        )

        cases.forEach { (expression, expectedShape) ->
            val actual = renderShape(parse(LuaVersion.LUA_5_3, "return $expression").returnExpression())

            assertEquals(expectedShape, actual, expression)
        }
    }

    @Test
    fun parsesCommentsAndLongStringsMixedWithCode() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            -- before
            local text = [[hello
            world]]
            -- after
            return text
            """.trimIndent()
        )

        val comments = chunk.comments()
        val localStatement = assertIs<LocalStatement>(chunk.body.statements[1])
        val longString = assertIs<ConstantNode>(localStatement.variables.single())

        assertEquals(2, comments.size)
        assertEquals(3, chunk.body.statements.size)
        assertContentEquals(listOf("-- before", "-- after"), comments.map { it.comment.trim() })
        comments.forEach { assertFalse(it.isDocComment) }
        assertEquals("Comment(line:-- before)", renderShape(comments[0]))
        assertEquals("Comment(line:-- after)", renderShape(comments[1]))
        assertTrue(longString.rawValue.toString().contains("[[hello\nworld]]"))
        assertEquals("Id(text)", renderShape(chunk.returnExpression()))
        assertTrue(renderShape(chunk).contains("Const([[hello\nworld]])"))
    }
}
