package parser.lua53

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.BreakStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.firstStatement
import parser.parse
import parser.renderShape

class Lua53StatementGrammarTddTest {

    @Test
    fun parsesResourceBackedComprehensiveStatementBlock() {
        val source = loadStatementFixture("comprehensive_statement_block.lua")
        val expectedShape = loadStatementFixture("comprehensive_statement_block.shape.txt").trimEnd()
        val chunk = parse(LuaVersion.LUA_5_3, source)

        assertEquals(expectedShape, renderShape(chunk).trimEnd())
        assertEquals(11, chunk.body.statements.size)
        assertIs<LabelStatement>(chunk.body.statements[0])
        assertIs<LocalStatement>(chunk.body.statements[1])
        assertIs<DoStatement>(chunk.body.statements[2])
        assertIs<WhileStatement>(chunk.body.statements[3])
        assertIs<RepeatStatement>(chunk.body.statements[4])
        assertIs<ForNumericStatement>(chunk.body.statements[5])
        assertIs<ForGenericStatement>(chunk.body.statements[6])
        assertIs<FunctionDeclaration>(chunk.body.statements[7])
        assertIs<FunctionDeclaration>(chunk.body.statements[8])
        assertIs<CallStatement>(chunk.body.statements[9])
        assertIs<GotoStatement>(chunk.body.statements[10])
        assertIs<ReturnStatement>(chunk.body.returnStatement)
    }

    @Test
    fun parsesEmptyStatementsAndSimpleControlTransfer() {
        assertCaseShapes(
            "empty block with only semicolons" to (
                " ; ; ; " to "Chunk(Block[])"
            ),
            "label and goto round trip" to (
                "::again:: goto again" to "Chunk(Block[Label(Id(again));Goto(Id(again))])"
            ),
            "label separated by empty statements" to (
                ";; ::again:: ;; goto again ;;" to "Chunk(Block[Label(Id(again));Goto(Id(again))])"
            ),
            "break in while body" to (
                "while keepGoing do break end" to "Chunk(Block[While(Id(keepGoing):Block[Break])])"
            ),
            "return with no values" to (
                "return" to "Chunk(Block[Return()])"
            ),
            "return with trailing semicolon" to (
                "return value;" to "Chunk(Block[Return(Id(value))])"
            ),
            "return remains separate from preceding regular statements" to (
                "local reachable = true return reachable" to "Chunk(Block[Local(Id(reachable)=Const(true));Return(Id(reachable))])"
            )
        )
    }

    @Test
    fun parsesDoWhileRepeatAndIfBlocks() {
        assertCaseShapes(
            "do block containing local and call" to (
                "do local value = 1 print(value) end" to
                    "Chunk(Block[Do(Block[Local(Id(value)=Const(1));CallStmt(Call(Id(print):Id(value)))])])"
            ),
            "nested do block preserves child statement order" to (
                "do do local inner = true end local outer = false end" to
                    "Chunk(Block[Do(Block[Do(Block[Local(Id(inner)=Const(true))]);Local(Id(outer)=Const(false))])])"
            ),
            "while block with assignment" to (
                "while ready do count = count + 1 end" to
                    "Chunk(Block[While(Id(ready):Block[Assign(Id(count)=Binary(+,Id(count),Const(1)))])])"
            ),
            "while block with nested break" to (
                "while ready do if done then break end end" to
                    "Chunk(Block[While(Id(ready):Block[If(Clause(Id(done):Block[Break]))])])"
            ),
            "repeat until condition after assignment body" to (
                "repeat count = count - 1 until count == 0" to
                    "Chunk(Block[Repeat(Block[Assign(Id(count)=Binary(-,Id(count),Const(1)))]:Binary(==,Id(count),Const(0)))])"
            ),
            "if then only" to (
                "if ready then start() end" to
                    "Chunk(Block[If(Clause(Id(ready):Block[CallStmt(Call(Id(start):))]))])"
            ),
            "if elseif else chain" to (
                "if a then x = 1 elseif b then x = 2 else x = 3 end" to
                    "Chunk(Block[If(Clause(Id(a):Block[Assign(Id(x)=Const(1))]),ElseIf(Id(b):Block[Assign(Id(x)=Const(2))]),Else(Block[Assign(Id(x)=Const(3))]))])"
            ),
            "if body keeps return separate from regular statements" to (
                "if ok then local value = 1 return value end" to
                    "Chunk(Block[If(Clause(Id(ok):Block[Local(Id(value)=Const(1));Return(Id(value))]))])"
            ),
            "else body can contain nested do" to (
                "if ok then pass() else do fail() end end" to
                    "Chunk(Block[If(Clause(Id(ok):Block[CallStmt(Call(Id(pass):))]),Else(Block[Do(Block[CallStmt(Call(Id(fail):))])]))])"
            )
        )
    }

    @Test
    fun parsesNumericAndGenericForLoops() {
        assertCaseShapes(
            "numeric for without explicit step" to (
                "for i = 1, 3 do total = total + i end" to
                    "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[Assign(Id(total)=Binary(+,Id(total),Id(i)))])])"
            ),
            "numeric for with explicit step" to (
                "for i = 10, 1, -1 do total = total + i end" to
                    "Chunk(Block[ForNumeric(Id(i)=Const(10),Const(1),Unary(-,Const(1)):Block[Assign(Id(total)=Binary(+,Id(total),Id(i)))])])"
            ),
            "numeric for bounds can be expressions" to (
                "for i = start + 1, finish - 1, step do tick(i) end" to
                    "Chunk(Block[ForNumeric(Id(i)=Binary(+,Id(start),Const(1)),Binary(-,Id(finish),Const(1)),Id(step):Block[CallStmt(Call(Id(tick):Id(i)))])])"
            ),
            "generic for with one variable" to (
                "for item in iter() do use(item) end" to
                    "Chunk(Block[ForGeneric(Id(item) in Call(Id(iter):):Block[CallStmt(Call(Id(use):Id(item)))])])"
            ),
            "generic for with two variables" to (
                "for key, value in pairs(items) do seen[key] = value end" to
                    "Chunk(Block[ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[Assign(Index(Id(seen)[Id(key)])=Id(value))])])"
            ),
            "generic for with multiple iterators" to (
                "for key, value in pairs(items), next do take(key, value) end" to
                    "Chunk(Block[ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)),Id(next):Block[CallStmt(Call(Id(take):Id(key),Id(value)))])])"
            ),
            "for body can return" to (
                "for i = 1, 1 do return i end" to
                    "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(1),null:Block[Return(Id(i))])])"
            )
        )
    }

    @Test
    fun parsesFunctionDeclarationsAndLocalDeclarations() {
        assertCaseShapes(
            "global function declaration" to (
                "function greet(name) return name end" to
                    "Chunk(Block[Function(Id(greet),Block[Return(Id(name))])])"
            ),
            "global dotted function declaration" to (
                "function module.greet(name) return name end" to
                    "Chunk(Block[Function(Member(Id(module).greet),Block[Return(Id(name))])])"
            ),
            "global method declaration uses colon member" to (
                "function module:greet(name) return self, name end" to
                    "Chunk(Block[Function(Member(Id(module):greet),Block[Return(Id(self),Id(name))])])"
            ),
            "local function declaration" to (
                "local function make(value) return value end" to
                    "Chunk(Block[Function(Id(make),Block[Return(Id(value))])])"
            ),
            "function declaration with vararg" to (
                "function collect(first, ...) return first, ... end" to
                    "Chunk(Block[Function(Id(collect),Block[Return(Id(first),Vararg)])])"
            ),
            "local declaration without initializer" to (
                "local a, b" to "Chunk(Block[Local(Id(a),Id(b)=)])"
            ),
            "local declaration with initializer list" to (
                "local a, b = 1, 2" to "Chunk(Block[Local(Id(a),Id(b)=Const(1),Const(2))])"
            ),
            "local declaration with nil and boolean initializers" to (
                "local missing, enabled = nil, true" to
                    "Chunk(Block[Local(Id(missing),Id(enabled)=Const(nil),Const(true))])"
            ),
            "local declaration initialized by function expression" to (
                "local handler = function(value) return value end" to
                    "Chunk(Block[Local(Id(handler)=Function(null,Block[Return(Id(value))]))])"
            ),
            "local declaration initialized by table literal" to (
                "local config = { name = label, count }" to
                    "Chunk(Block[Local(Id(config)=Table(TableKeyString(Id(name)=Id(label)),TableKey(Const(1)=Id(count))))])"
            )
        )
    }

    @Test
    fun parsesAssignmentsCallsAndReturnStatements() {
        assertCaseShapes(
            "single assignment" to (
                "value = 1" to "Chunk(Block[Assign(Id(value)=Const(1))])"
            ),
            "multi assignment" to (
                "a, b = b, a" to "Chunk(Block[Assign(Id(a),Id(b)=Id(b),Id(a))])"
            ),
            "member assignment" to (
                "object.name = value" to "Chunk(Block[Assign(Member(Id(object).name)=Id(value))])"
            ),
            "index assignment" to (
                "items[i] = value" to "Chunk(Block[Assign(Index(Id(items)[Id(i)])=Id(value))])"
            ),
            "nested member index assignment" to (
                "root.child[i].name = value" to
                    "Chunk(Block[Assign(Member(Index(Member(Id(root).child)[Id(i)]).name)=Id(value))])"
            ),
            "plain function call statement" to (
                "print(value)" to "Chunk(Block[CallStmt(Call(Id(print):Id(value)))])"
            ),
            "method call statement" to (
                "object:method(1, 2)" to
                    "Chunk(Block[CallStmt(Call(Member(Id(object):method):Const(1),Const(2)))])"
            ),
            "string call statement" to (
                "print 'hello'" to "Chunk(Block[CallStmt(Call(StringCall(Id(print):Const('hello')):))])"
            ),
            "table call statement" to (
                "configure { enabled = true }" to
                    "Chunk(Block[CallStmt(Call(TableCall(Id(configure):Table(TableKeyString(Id(enabled)=Const(true)))):))])"
            ),
            "return one expression" to (
                "return value" to "Chunk(Block[Return(Id(value))])"
            ),
            "return multiple expressions" to (
                "return a, b + c, fn()" to
                    "Chunk(Block[Return(Id(a),Binary(+,Id(b),Id(c)),Call(Id(fn):))])"
            ),
            "return vararg" to (
                "return ..." to "Chunk(Block[Return(Vararg)])"
            )
        )
    }

    @Test
    fun exposesAstDetailsForRepresentativeStatements() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            ::again::
            local total, label = 0, "start"
            while total < 3 do
                total = total + 1
                if total == 2 then break end
            end
            for key, value in pairs(items), next do
                consume(key, value)
            end
            function module:run(arg, ...)
                return self, arg, ...
            end
            print(label)
            goto again
            return total, label
            """.trimIndent()
        )

        assertEquals(7, chunk.body.statements.size)
        assertEquals("again", chunk.firstStatement<LabelStatement>().identifier.name)

        val local = assertIs<LocalStatement>(chunk.body.statements[1])
        assertContentEquals(listOf("total", "label"), local.init.map { it.name })
        assertEquals(2, local.variables.size)

        val whileStatement = assertIs<WhileStatement>(chunk.body.statements[2])
        assertEquals(2, whileStatement.body.statements.size)
        assertIs<AssignmentStatement>(whileStatement.body.statements[0])
        val ifStatement = assertIs<IfStatement>(whileStatement.body.statements[1])
        assertIs<BreakStatement>(ifStatement.causes.single().body.statements.single())

        val genericFor = assertIs<ForGenericStatement>(chunk.body.statements[3])
        assertContentEquals(listOf("key", "value"), genericFor.variables.map { it.name })
        assertEquals(2, genericFor.iterators.size)
        assertIs<CallExpression>(genericFor.iterators.first())
        assertIs<CallStatement>(genericFor.body.statements.single())

        val declaration = assertIs<FunctionDeclaration>(chunk.body.statements[4])
        assertEquals(false, declaration.isLocal)
        val methodName = assertIs<MemberExpression>(declaration.identifier)
        assertEquals(":", methodName.indexer)
        assertEquals("module", assertIs<Identifier>(methodName.base).name)
        assertEquals("run", methodName.identifier.name)
        assertContentEquals(listOf("arg", "..."), declaration.params.map { it.name })
        assertEquals(3, declaration.body!!.returnStatement!!.arguments.size)

        val call = assertIs<CallStatement>(chunk.body.statements[5])
        assertEquals("print", assertIs<Identifier>(call.expression.base).name)

        assertEquals("again", assertIs<GotoStatement>(chunk.body.statements[6]).identifier.name)
        assertContentEquals(
            listOf("Id(total)", "Id(label)"),
            chunk.body.returnStatement!!.arguments.map(::renderShape)
        )
    }

    @Test
    fun exposesAstDetailsForIfClauseKindsAndFunctionLocality() {
        val ifStatement = parse(
            LuaVersion.LUA_5_3,
            "if first then one() elseif second then two() else three() end"
        ).firstStatement<IfStatement>()

        assertEquals(3, ifStatement.causes.size)
        assertIs<IfClause>(ifStatement.causes[0])
        assertIs<ElseIfClause>(ifStatement.causes[1])
        assertIs<ElseClause>(ifStatement.causes[2])
        assertEquals("Id(first)", renderShape(ifStatement.causes[0].condition))
        assertEquals("Id(second)", renderShape(ifStatement.causes[1].condition))
        assertContentEquals(
            listOf("CallStmt(Call(Id(one):))", "CallStmt(Call(Id(two):))", "CallStmt(Call(Id(three):))"),
            ifStatement.causes.map { renderShape(it.body.statements.single()) }
        )

        val localFunction = parse(
            LuaVersion.LUA_5_3,
            "local function finish(value) return value end"
        ).firstStatement<FunctionDeclaration>()

        assertTrue(localFunction.isLocal)
        assertEquals("finish", assertIs<Identifier>(localFunction.identifier).name)
        assertContentEquals(listOf("value"), localFunction.params.map { it.name })
        assertEquals("Return(Id(value))", renderShape(localFunction.body!!.returnStatement!!))
    }

    private fun assertCaseShapes(vararg cases: Pair<String, Pair<String, String>>) {
        val failures = cases.mapNotNull { (name, case) ->
            val (source, expectedShape) = case
            runCatching {
                assertEquals(expectedShape, renderShape(parse(LuaVersion.LUA_5_3, source)), name)
            }.exceptionOrNull()?.let { failure ->
                "$name\nsource: $source\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    private fun loadStatementFixture(name: String): String {
        val path = "/parser/tdd/lua53/statements/$name"
        return checkNotNull(javaClass.getResourceAsStream(path)) {
            "Missing statement grammar fixture: $path"
        }.bufferedReader().use { it.readText() }
    }
}
