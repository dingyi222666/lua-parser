package parser

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ContinueStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroLuaSyntaxRegressionTest {

    @Test
    fun parsesResourceBackedAndroLuaFixture() {
        val chunk = assertResourceShape(
            LuaVersion.ANDROLUA_5_3,
            "/parser/regressions/androlua/control_flow_lambda_array.lua"
        )

        assertEquals(3, chunk.body.statements.size)
        assertIs<WhileStatement>(chunk.body.statements[0])
        assertEquals(
            "Lambda(Id(value):Id(value))",
            renderShape(assertIs<LocalStatement>(chunk.body.statements[1]).variables.single())
        )
        assertIs<WhenStatement>(chunk.body.statements[2])

        val returnedArray = assertIs<ArrayConstructorExpression>(chunk.returnExpression())
        assertEquals(3, returnedArray.values.size)
        assertEquals("Array(Const(3),Const(4))", renderShape(returnedArray.values[2]))
    }

    @Test
    fun parsesAndroLuaControlFlowForms() {
        val whileChunk = parse(LuaVersion.ANDROLUA_5_3, "while ready do continue end")
        val whileStatement = whileChunk.firstStatement<WhileStatement>()

        assertEquals(1, whileStatement.body.statements.size)
        assertIs<ContinueStatement>(whileStatement.body.statements.single())
        assertEquals("Chunk(Block[While(Id(ready):Block[Continue])])", renderShape(whileChunk))

        val whenAssignmentChunk = parse(LuaVersion.ANDROLUA_5_3, "when cond a = 1 else b = 2")
        val whenAssignment = whenAssignmentChunk.firstStatement<WhenStatement>()

        assertIs<AssignmentStatement>(whenAssignment.ifCause)
        assertIs<AssignmentStatement>(whenAssignment.elseCause)
        assertEquals(
            "Chunk(Block[When(Id(cond)?Assign(Id(a)=Const(1)):Assign(Id(b)=Const(2)))])",
            renderShape(whenAssignmentChunk)
        )

        val whenCallChunk = parse(LuaVersion.ANDROLUA_5_3, "when cond call() else other()")
        val whenCall = whenCallChunk.firstStatement<WhenStatement>()

        assertIs<CallStatement>(whenCall.ifCause)
        assertIs<CallStatement>(whenCall.elseCause)
        assertEquals(
            "Chunk(Block[When(Id(cond)?CallStmt(Call(Id(call):)):CallStmt(Call(Id(other):)))])",
            renderShape(whenCallChunk)
        )

        val switchChunk = parse(
            LuaVersion.ANDROLUA_5_3,
            "switch expr do case 1, 2 then call() default other() end"
        )
        val switchStatement = switchChunk.firstStatement<SwitchStatement>()
        val caseCause = assertIs<CaseCause>(switchStatement.causes[0])
        val defaultCause = assertIs<DefaultCause>(switchStatement.causes[1])

        assertEquals(2, switchStatement.causes.size)
        assertEquals(1, caseCause.body.statements.size)
        assertEquals(1, defaultCause.body.statements.size)
        assertIs<CallStatement>(caseCause.body.statements.single())
        assertIs<CallStatement>(defaultCause.body.statements.single())
        assertEquals(
            "Chunk(Block[Switch(Id(expr):Case(Const(1),Const(2):Block[CallStmt(Call(Id(call):))]),Default(Block[CallStmt(Call(Id(other):))]))])",
            renderShape(switchChunk)
        )
    }

    @Test
    fun parsesAndroLuaDollarPrefixedLocals() {
        val chunk = parse(LuaVersion.ANDROLUA_5_3, "local ${'$'}value = 1")
        val statement = chunk.firstStatement<LocalStatement>()
        val localName = assertIs<Identifier>(statement.init.single())

        assertEquals("value", localName.name)
        assertTrue(localName.isLocal)
        assertEquals("Chunk(Block[Local(Id(value)=Const(1))])", renderShape(chunk))
    }

    @Test
    fun parsesAndroLuaLambdaFormsAndArrayConstructors() {
        val lambdaColon = assertIs<LambdaDeclaration>(
            parse(LuaVersion.ANDROLUA_5_3, "return lambda x: x + 1").returnExpression()
        )
        assertContentEquals(listOf("x"), lambdaColon.params.map { it.name })
        assertIs<BinaryExpression>(lambdaColon.expression)
        assertEquals("Lambda(Id(x):Binary(+,Id(x),Const(1)))", renderShape(lambdaColon))

        val lambdaFatArrow = assertIs<LambdaDeclaration>(
            parse(LuaVersion.ANDROLUA_5_3, "return lambda(x, y) => x + y").returnExpression()
        )
        assertContentEquals(listOf("x", "y"), lambdaFatArrow.params.map { it.name })
        assertEquals("Binary(+,Id(x),Id(y))", renderShape(lambdaFatArrow.expression))
        assertEquals("Lambda(Id(x),Id(y):Binary(+,Id(x),Id(y)))", renderShape(lambdaFatArrow))

        val lambdaArrow = assertIs<LambdaDeclaration>(
            parse(LuaVersion.ANDROLUA_5_3, "return lambda value -> value").returnExpression()
        )
        assertContentEquals(listOf("value"), lambdaArrow.params.map { it.name })
        assertIs<Identifier>(lambdaArrow.expression)
        assertEquals("Lambda(Id(value):Id(value))", renderShape(lambdaArrow))

        val array = assertIs<ArrayConstructorExpression>(
            parse(LuaVersion.ANDROLUA_5_3, "return [1, 2, foo()]").returnExpression()
        )
        assertEquals(3, array.values.size)
        assertIs<CallExpression>(array.values.last())
        assertEquals("Array(Const(1),Const(2),Call(Id(foo):))", renderShape(array))
    }

    @Test
    fun gatesAndroLuaOnlySyntaxInPlainLuaModes() {
        listOf(
            "while ready do continue end",
            "when cond a = 1 else b = 2",
            "when cond call() else other()",
            "switch expr do case 1, 2 then call() default other() end",
            "local ${'$'}value = 1",
            "return lambda x: x + 1",
            "return lambda(x, y) => x + y",
            "return lambda value -> value",
            "return [1, 2, foo()]"
        ).forEach { source ->
            parse(LuaVersion.ANDROLUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_4, source)
        }
    }
}
