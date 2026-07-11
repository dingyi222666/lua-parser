package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.firstStatement
import parser.parse
import parser.renderShape
import parser.returnExpression


/**
 * Corpus locking colon method-call AST shapes for Lua 5.3.
 *
 * Acceptance focus:
 * - Colon method calls retain a [MemberExpression] base with indexer `:` (self
 *   receiver is encoded by the colon member, not by inventing an extra AST arg).
 * - Nested colon/dot mixes stay left-nested and deterministic.
 * - Dot vs colon siblings remain distinct on the same receiver/name.
 *
 * Test-only (TASK-314). Production parser is out of scope unless review re-scopes.
 */
class MethodCallColonShapeTddTest {

    @Test
    fun simpleColonMethodCallRetainsSelfReceiverMemberShape() {
        val expression = parseExpression("return obj:method(1)")
        val call = assertIs<CallExpression>(expression)
        val member = assertIs<MemberExpression>(call.base)

        assertEquals(":", member.indexer)
        assertEquals("method", member.identifier.name)
        assertEquals("obj", assertIs<Identifier>(member.base).name)
        assertEquals(1, call.arguments.size)
        assertEquals("Const(1)", renderShape(call.arguments.single()))
        assertEquals("Call(Member(Id(obj):method):Const(1))", renderShape(call))

        // Self is not reified as an explicit argument on the CallExpression.
        assertFalse(call.arguments.any { it is Identifier && it.name == "self" })
        assertFalse(call is StringCallExpression)
        assertFalse(call is TableCallExpression)
    }

    @Test
    fun zeroArgColonMethodCallStillUsesColonMemberBase() {
        val call = assertIs<CallExpression>(parseExpression("return widget:ping()"))
        val member = assertIs<MemberExpression>(call.base)

        assertEquals(":", member.indexer)
        assertEquals("ping", member.identifier.name)
        assertTrue(call.arguments.isEmpty())
        assertEquals("Call(Member(Id(widget):ping):)", renderShape(call))
    }

    @Test
    fun multiArgColonMethodCallKeepsColonIndexerAndArgOrder() {
        val call = assertIs<CallExpression>(
            parseExpression("return object:method(1, \"two\", fn())")
        )
        val member = assertIs<MemberExpression>(call.base)

        assertEquals(":", member.indexer)
        assertEquals("method", member.identifier.name)
        assertEquals(
            listOf("Const(1)", "Const(\"two\")", "Call(Id(fn):)"),
            call.arguments.map(::renderShape)
        )
        assertEquals(
            "Call(Member(Id(object):method):Const(1),Const(\"two\"),Call(Id(fn):))",
            renderShape(call)
        )
    }

    @Test
    fun colonAndDotSiblingsOnSameReceiverStayDistinct() {
        val colon = assertIs<CallExpression>(parseExpression("return activity:setTitle(\"Ready\")"))
        val dot = assertIs<CallExpression>(parseExpression("return activity.setTitle(\"Ready\")"))

        assertEquals(":", assertIs<MemberExpression>(colon.base).indexer)
        assertEquals(".", assertIs<MemberExpression>(dot.base).indexer)
        assertEquals(
            "Call(Member(Id(activity):setTitle):Const(\"Ready\"))",
            renderShape(colon)
        )
        assertEquals(
            "Call(Member(Id(activity).setTitle):Const(\"Ready\"))",
            renderShape(dot)
        )
        assertNotEquals(renderShape(colon), renderShape(dot))
    }

    @Test
    fun nestedDotThenColonKeepsLeftNestedMemberShape() {
        val call = assertIs<CallExpression>(
            parseExpression("return root.child:run(value)")
        )
        val colonMember = assertIs<MemberExpression>(call.base)
        assertEquals(":", colonMember.indexer)
        assertEquals("run", colonMember.identifier.name)

        val dotMember = assertIs<MemberExpression>(colonMember.base)
        assertEquals(".", dotMember.indexer)
        assertEquals("child", dotMember.identifier.name)
        assertEquals("root", assertIs<Identifier>(dotMember.base).name)

        assertEquals(
            "Call(Member(Member(Id(root).child):run):Id(value))",
            renderShape(call)
        )
    }

    @Test
    fun nestedColonThenDotAfterCallStaysLeftNested() {
        // root:make(a)[i].field(b) — colon method call, then index, then dotted call.
        val call = assertIs<CallExpression>(
            parseExpression("return root:make(a)[i].field(b)")
        )
        assertEquals(
            "Call(Member(Index(Call(Member(Id(root):make):Id(a))[Id(i)]).field):Id(b))",
            renderShape(call)
        )

        val outerMember = assertIs<MemberExpression>(call.base)
        assertEquals(".", outerMember.indexer)
        assertEquals("field", outerMember.identifier.name)

        val index = assertIs<IndexExpression>(outerMember.base)
        val innerCall = assertIs<CallExpression>(index.base)
        val colonMember = assertIs<MemberExpression>(innerCall.base)
        assertEquals(":", colonMember.indexer)
        assertEquals("make", colonMember.identifier.name)
        assertEquals("root", assertIs<Identifier>(colonMember.base).name)
    }

    @Test
    fun indexBaseColonMethodCallRetainsColonMember() {
        val call = assertIs<CallExpression>(
            parseExpression("return root.child[1]:call('x')")
        )
        val member = assertIs<MemberExpression>(call.base)
        assertEquals(":", member.indexer)
        assertEquals("call", member.identifier.name)
        assertIs<IndexExpression>(member.base)
        assertEquals(
            "Call(Member(Index(Member(Id(root).child)[Const(1)]):call):Const('x'))",
            renderShape(call)
        )
    }

    @Test
    fun callResultBaseThenColonMethodKeepsDeterministicNesting() {
        val call = assertIs<CallExpression>(
            parseExpression("return factory():create(name)")
        )
        val member = assertIs<MemberExpression>(call.base)
        assertEquals(":", member.indexer)
        assertEquals("create", member.identifier.name)
        assertIs<CallExpression>(member.base)
        assertEquals(
            "Call(Member(Call(Id(factory):):create):Id(name))",
            renderShape(call)
        )
    }

    @Test
    fun shortStringAndTableColonMethodCallsKeepColonIndexerOnSpecializedBase() {
        val stringOuter = assertIs<CallExpression>(
            parseExpression("return activity:setTitle \"Ready\"")
        )
        val stringShort = assertIs<StringCallExpression>(stringOuter.base)
        assertEquals(":", assertIs<MemberExpression>(stringShort.base).indexer)
        assertEquals(
            "Call(StringCall(Member(Id(activity):setTitle):Const(\"Ready\")):)",
            renderShape(stringOuter)
        )

        val tableOuter = assertIs<CallExpression>(
            parseExpression("return view:configure { id = 1 }")
        )
        val tableShort = assertIs<TableCallExpression>(tableOuter.base)
        assertEquals(":", assertIs<MemberExpression>(tableShort.base).indexer)
        assertEquals(
            "Call(TableCall(Member(Id(view):configure):Table(TableKeyString(Id(id)=Const(1)))):)",
            renderShape(tableOuter)
        )
    }

    @Test
    fun callStatementColonMethodMirrorsExpressionShape() {
        val stmt = parse(LuaVersion.LUA_5_3, "object:method(1, 2)")
            .firstStatement<CallStatement>()
            .expression
        val call = assertIs<CallExpression>(stmt)
        val member = assertIs<MemberExpression>(call.base)

        assertEquals(":", member.indexer)
        assertEquals(
            "Call(Member(Id(object):method):Const(1),Const(2))",
            renderShape(call)
        )
    }

    @Test
    fun chainedColonMethodsRemainLeftNestedAndDistinctFromDots() {
        // a:b():c() — second colon attaches to the call result of the first.
        val chained = assertIs<CallExpression>(parseExpression("return a:b():c()"))
        assertEquals(
            "Call(Member(Call(Member(Id(a):b):):c):)",
            renderShape(chained)
        )
        val outer = assertIs<MemberExpression>(chained.base)
        assertEquals(":", outer.indexer)
        assertEquals("c", outer.identifier.name)
        val innerCall = assertIs<CallExpression>(outer.base)
        val inner = assertIs<MemberExpression>(innerCall.base)
        assertEquals(":", inner.indexer)
        assertEquals("b", inner.identifier.name)

        val dotted = assertIs<CallExpression>(parseExpression("return a.b().c()"))
        assertEquals(
            "Call(Member(Call(Member(Id(a).b):).c):)",
            renderShape(dotted)
        )
        assertNotEquals(renderShape(chained), renderShape(dotted))
    }

    @Test
    fun mixedColonDotIndexCorpusStaysDeterministic() {
        assertCaseShapes(
            "simple colon" to (
                "return obj:method(1)" to "Call(Member(Id(obj):method):Const(1))"
            ),
            "simple dot sibling" to (
                "return obj.method(1)" to "Call(Member(Id(obj).method):Const(1))"
            ),
            "zero-arg colon" to (
                "return widget:ping()" to "Call(Member(Id(widget):ping):)"
            ),
            "multi-arg colon" to (
                "return object:method(1, 2)" to
                    "Call(Member(Id(object):method):Const(1),Const(2))"
            ),
            "dot then colon" to (
                "return root.child:run(value)" to
                    "Call(Member(Member(Id(root).child):run):Id(value))"
            ),
            "colon then index then dot call" to (
                "return root:make(a)[i].field(b)" to
                    "Call(Member(Index(Call(Member(Id(root):make):Id(a))[Id(i)]).field):Id(b))"
            ),
            "index base colon" to (
                "return root.child[1]:call('x')" to
                    "Call(Member(Index(Member(Id(root).child)[Const(1)]):call):Const('x'))"
            ),
            "call base colon" to (
                "return factory():create(name)" to
                    "Call(Member(Call(Id(factory):):create):Id(name))"
            ),
            "chained colon methods" to (
                "return a:b():c()" to "Call(Member(Call(Member(Id(a):b):):c):)"
            ),
            "chained mixed colon then dot" to (
                "return a:b().c()" to "Call(Member(Call(Member(Id(a):b):).c):)"
            ),
            "chained mixed dot then colon" to (
                "return a.b():c()" to "Call(Member(Call(Member(Id(a).b):):c):)"
            ),
            "deep mixed chain" to (
                "return root.child:make(x)[i]:finish(y)" to
                    "Call(Member(Index(Call(Member(Member(Id(root).child):make):Id(x))[Id(i)]):finish):Id(y))"
            ),
            "colon method string short call" to (
                "return m:n \"v\"" to
                    "Call(StringCall(Member(Id(m):n):Const(\"v\")):)"
            ),
            "colon method table short call" to (
                "return m:n { k = 1 }" to
                    "Call(TableCall(Member(Id(m):n):Table(TableKeyString(Id(k)=Const(1)))):)"
            ),
            "android-style colon setTitle" to (
                "return activity:setTitle(\"Ready\")" to
                    "Call(Member(Id(activity):setTitle):Const(\"Ready\"))"
            ),
            "android-style colon after require chain" to (
                "return require(\"pkg\").api:run()" to
                    "Call(Member(Member(Call(Id(require):Const(\"pkg\")).api):run):)"
            )
        )
    }

    @Test
    fun colonMemberWithoutCallIsStillColonMemberShape() {
        // Bare colon member is rare at expression root without a call, but
        // function-name forms and recovery paths rely on the same indexer field.
        // Here we lock the method-declaration identifier shape used by modules.
        val chunk = parse(
            LuaVersion.LUA_5_3,
            "function module:create(first)\n  return self, first\nend"
        )
        val decl = assertIs<FunctionDeclaration>(chunk.body.statements.single())
        val identifier = assertIs<MemberExpression>(decl.identifier)
        assertEquals(":", identifier.indexer)
        assertEquals("create", identifier.identifier.name)
        assertEquals("module", assertIs<Identifier>(identifier.base).name)
        assertEquals("Member(Id(module):create)", renderShape(identifier))
    }

    private fun parseExpression(source: String): ExpressionNode {
        return parse(LuaVersion.LUA_5_3, source).returnExpression()
    }

    private fun assertCaseShapes(vararg cases: Pair<String, Pair<String, String>>) {
        val failures = cases.mapNotNull { (name, case) ->
            val (source, expectedShape) = case
            runCatching {
                assertEquals(expectedShape, renderShape(parseExpression(source)), name)
            }.exceptionOrNull()?.let { failure ->
                "$name\nsource: $source\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }
}
