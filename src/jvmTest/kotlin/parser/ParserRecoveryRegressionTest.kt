package parser

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParserRecoveryRegressionTest {

    @Test
    fun recoversResourceBackedFixture() {
        val path = "/parser/regressions/recovery/doc_member_if_repeat.lua"
        val source = loadParserRegressionResource(path)

        assertRecoveryPair(LuaVersion.LUA_5_3, source) { recovered ->
            assertEquals(loadParserRegressionShape(path).trimEnd(), renderShape(recovered).trimEnd())

            val statements = recovered.body.statements
            assertTrue(assertIs<CommentStatement>(statements[0]).isDocComment)
            assertIs<CallStatement>(statements[1])
            assertIs<IfStatement>(statements[2])
            assertTrue(assertIs<RepeatStatement>(statements[3]).condition.bad)
        }
    }

    @Test
    fun recoversIncompleteLocalAttributeSyntax() {
        assertRecoveryPair(LuaVersion.LUA_5_4, "local value<const = 1") { recovered ->
            val statement = recovered.firstStatement<LocalStatement>()
            val localName = assertIs<AttributeIdentifier>(statement.init.single())

            assertEquals(1, statement.init.size)
            assertEquals(1, statement.variables.size)
            assertEquals("value", localName.name)
            assertEquals("const", localName.attributeName)
            assertEquals("Const(1)", renderShape(statement.variables.single()))
            assertEquals("Local(AttrId(value<const>)=Const(1))", renderShape(statement))
        }
    }

    @Test
    fun recoversMalformedWhenBranches() {
        assertRecoveryPair(LuaVersion.ANDROLUA_5_3, "when ready print(1) else fallback") { recovered ->
            val statement = recovered.firstStatement<WhenStatement>()

            assertEquals("Id(ready)", renderShape(statement.condition))
            assertIs<CallStatement>(statement.ifCause)
            assertIs<CallStatement>(statement.elseCause)
            assertEquals(
                "Chunk(Block[When(Id(ready)?CallStmt(Call(Id(print):Const(1))):CallStmt(Call(Id(fallback):)))])",
                renderShape(recovered)
            )
        }
    }

    @Test
    fun recoversMalformedSwitchBodies() {
        assertRecoveryPair(LuaVersion.ANDROLUA_5_3, "switch value do case 1 fallback end") { recovered ->
            val statement = recovered.firstStatement<SwitchStatement>()
            val cause = assertIs<CaseCause>(statement.causes.single())

            assertEquals("Id(value)", renderShape(statement.condition))
            assertEquals("Const(1)", renderShape(cause.conditions.single()))
            assertEquals(1, cause.body.statements.size)
            assertIs<CallStatement>(cause.body.statements.single())
            assertEquals(
                "Chunk(Block[Switch(Id(value):Case(Const(1):Block[CallStmt(Call(Id(fallback):))]))])",
                renderShape(recovered)
            )
        }
    }

    @Test
    fun recoversIncompleteLambdaArrowOrBody() {
        listOf(
            "return lambda value - value" to "Id(value)",
            "return lambda value ->" to "ExpressionNodeSupport"
        ).forEach { (source, expectedBodyShape) ->
            assertRecoveryPair(LuaVersion.ANDROLUA_5_3, source) { recovered ->
                val lambda = assertIs<LambdaDeclaration>(recovered.returnExpression())

                assertContentEquals(listOf("value"), lambda.params.map { it.name })
                assertEquals(expectedBodyShape, renderShape(lambda.expression))
            }
        }
    }

    @Test
    fun recoversBrokenMemberAndCallExpressionsNearDocComments() {
        listOf(
            "---@type fun()\nobj.\nprint(1)" to "CallStmt(Call(Member(Id(obj).):))",
            "---@type fun()\nobj:\nprint(1)" to "CallStmt(Call(Member(Id(obj):):))"
        ).forEach { (source, brokenShape) ->
            assertRecoveryPair(LuaVersion.LUA_5_3, source) { recovered ->
                val statements = recovered.body.statements
                val comment = assertIs<CommentStatement>(statements[0])

                assertTrue(comment.isDocComment)
                assertEquals(3, statements.size)
                assertEquals("Comment(doc:---@type fun())", renderShape(comment))
                assertEquals(brokenShape, renderShape(statements[1]))
                assertEquals("CallStmt(Call(Id(print):Const(1)))", renderShape(statements[2]))
            }
        }
    }

    @Test
    fun recoversMissingThenDoEndAndUntil() {
        assertRecoveryPair(LuaVersion.LUA_5_3, "if ready local value = 1 end") { recovered ->
            val statement = recovered.firstStatement<IfStatement>()
            val clause = statement.causes.single()

            assertEquals("Id(ready)", renderShape(clause.condition))
            assertEquals(1, clause.body.statements.size)
            assertIs<LocalStatement>(clause.body.statements.single())
        }

        assertRecoveryPair(LuaVersion.LUA_5_3, "while ready local value = 1 end") { recovered ->
            val statement = recovered.firstStatement<WhileStatement>()

            assertEquals("Id(ready)", renderShape(statement.condition))
            assertEquals(1, statement.body.statements.size)
            assertIs<LocalStatement>(statement.body.statements.single())
        }

        assertRecoveryPair(LuaVersion.LUA_5_3, "do local value = 1") { recovered ->
            val statement = recovered.firstStatement<DoStatement>()

            assertEquals(1, statement.body.statements.size)
            assertIs<LocalStatement>(statement.body.statements.single())
        }

        assertRecoveryPair(LuaVersion.LUA_5_3, "repeat local value = 1") { recovered ->
            val statement = recovered.firstStatement<RepeatStatement>()

            assertEquals(1, statement.body.statements.size)
            assertIs<LocalStatement>(statement.body.statements.single())
            assertTrue(statement.condition.bad)
            assertEquals("ExpressionNodeSupport", renderShape(statement.condition))
        }
    }

    private fun assertRecoveryPair(
        version: LuaVersion,
        source: String,
        expectedStrictMessage: String? = null,
        assertRecovered: (ChunkNode) -> Unit,
    ) {
        val recovered = parseRecovering(version, source)
        assertRecovered(recovered)

        val first = assertParseFails(version, source, recovery = false)
        val second = assertParseFails(version, source, recovery = false)

        assertEquals(first::class, second::class)
        assertEquals(first.message, second.message)
        expectedStrictMessage?.let { assertEquals(it, first.message) }
    }
}
