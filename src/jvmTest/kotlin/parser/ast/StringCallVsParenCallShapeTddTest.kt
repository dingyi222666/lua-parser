package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
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
 * Corpus locking the AST shape split between:
 * - [StringCallExpression] for `f "arg"` / `f 'arg'` short string calls
 * - plain [CallExpression] for parenthesized `f("arg")` / multi-arg calls
 * - [TableCallExpression] for `f { ... }` short table calls
 *
 * Short calls are reified as the specialized node nested under an outer
 * [CallExpression] (arguments empty on that outer wrapper for pure short
 * forms). Parenthesized calls stay a single plain [CallExpression].
 */
class StringCallVsParenCallShapeTddTest {

    @Test
    fun stringCallAndParenthesizedCallAreDistinctNodeTypesAndShapes() {
        val stringCall = parseExpression("return print \"hello\"")
        val parenCall = parseExpression("return print(\"hello\")")

        val outerString = assertIs<CallExpression>(stringCall)
        val shortString = assertIs<StringCallExpression>(outerString.base)
        assertTrue(outerString.arguments.isEmpty(), "pure short string call wraps with empty outer args")
        assertEquals(1, shortString.arguments.size)
        assertEquals("hello", assertIs<ConstantNode>(shortString.arguments.single()).stringOf())
        assertEquals("print", assertIs<Identifier>(shortString.base).name)
        assertFalse(outerString is StringCallExpression)
        assertFalse(outerString is TableCallExpression)
        assertFalse(shortString is TableCallExpression)

        val plain = assertIs<CallExpression>(parenCall)
        assertFalse(plain is StringCallExpression, "parenthesized call must not be StringCallExpression")
        assertFalse(plain is TableCallExpression, "parenthesized call must not be TableCallExpression")
        assertFalse(plain.base is StringCallExpression)
        assertFalse(plain.base is TableCallExpression)
        assertEquals("print", assertIs<Identifier>(plain.base).name)
        assertEquals(1, plain.arguments.size)
        assertEquals("hello", assertIs<ConstantNode>(plain.arguments.single()).stringOf())

        assertEquals(
            "Call(StringCall(Id(print):Const(\"hello\")):)",
            renderShape(outerString)
        )
        assertEquals(
            "Call(Id(print):Const(\"hello\"))",
            renderShape(plain)
        )
        assertNotEquals(renderShape(outerString), renderShape(plain))
    }

    @Test
    fun singleQuotedStringCallMatchesDoubleQuotedShapeFamily() {
        val single = parseExpression("return require 'json'")
        val double = parseExpression("return require \"json\"")

        val singleOuter = assertIs<CallExpression>(single)
        val doubleOuter = assertIs<CallExpression>(double)
        assertIs<StringCallExpression>(singleOuter.base)
        assertIs<StringCallExpression>(doubleOuter.base)

        assertEquals(
            "Call(StringCall(Id(require):Const('json')):)",
            renderShape(singleOuter)
        )
        assertEquals(
            "Call(StringCall(Id(require):Const(\"json\")):)",
            renderShape(doubleOuter)
        )
    }

    @Test
    fun tableCallShapeIsRetainedAndDistinctFromStringAndParen() {
        val tableCall = parseExpression("return configure { enabled = true }")
        val stringCall = parseExpression("return configure \"enabled\"")
        val parenCall = parseExpression("return configure({ enabled = true })")

        val tableOuter = assertIs<CallExpression>(tableCall)
        val shortTable = assertIs<TableCallExpression>(tableOuter.base)
        assertTrue(tableOuter.arguments.isEmpty())
        assertEquals(1, shortTable.arguments.size)
        assertIs<TableConstructorExpression>(shortTable.arguments.single())
        assertEquals("configure", assertIs<Identifier>(shortTable.base).name)
        assertFalse(shortTable is StringCallExpression)
        assertFalse(tableOuter is TableCallExpression)
        assertFalse(tableOuter is StringCallExpression)

        val stringOuter = assertIs<CallExpression>(stringCall)
        assertIs<StringCallExpression>(stringOuter.base)

        val paren = assertIs<CallExpression>(parenCall)
        assertFalse(paren is TableCallExpression)
        assertFalse(paren.base is TableCallExpression)
        assertIs<TableConstructorExpression>(paren.arguments.single())

        assertEquals(
            "Call(TableCall(Id(configure):Table(TableKeyString(Id(enabled)=Const(true)))):)",
            renderShape(tableOuter)
        )
        assertEquals(
            "Call(StringCall(Id(configure):Const(\"enabled\")):)",
            renderShape(stringOuter)
        )
        assertEquals(
            "Call(Id(configure):Table(TableKeyString(Id(enabled)=Const(true))))",
            renderShape(paren)
        )
    }

    @Test
    fun memberAndMethodBasesPreserveShortCallSpecialization() {
        val memberString = parseExpression("return activity.setTitle \"Ready\"")
        val methodString = parseExpression("return activity:setTitle \"Ready\"")
        val memberParen = parseExpression("return activity.setTitle(\"Ready\")")
        val methodParen = parseExpression("return activity:setTitle(\"Ready\")")
        val memberTable = parseExpression("return layout.inflate { id = 1 }")
        val methodTable = parseExpression("return view:configure { id = 1 }")

        val memberStringOuter = assertIs<CallExpression>(memberString)
        val memberStringShort = assertIs<StringCallExpression>(memberStringOuter.base)
        assertIs<MemberExpression>(memberStringShort.base)
        assertEquals(".", assertIs<MemberExpression>(memberStringShort.base).indexer)
        assertEquals(
            "Call(StringCall(Member(Id(activity).setTitle):Const(\"Ready\")):)",
            renderShape(memberStringOuter)
        )

        val methodStringOuter = assertIs<CallExpression>(methodString)
        val methodStringShort = assertIs<StringCallExpression>(methodStringOuter.base)
        assertEquals(":", assertIs<MemberExpression>(methodStringShort.base).indexer)
        assertEquals(
            "Call(StringCall(Member(Id(activity):setTitle):Const(\"Ready\")):)",
            renderShape(methodStringOuter)
        )

        val memberParenCall = assertIs<CallExpression>(memberParen)
        assertFalse(memberParenCall is StringCallExpression)
        assertIs<MemberExpression>(memberParenCall.base)
        assertEquals(
            "Call(Member(Id(activity).setTitle):Const(\"Ready\"))",
            renderShape(memberParenCall)
        )

        val methodParenCall = assertIs<CallExpression>(methodParen)
        assertFalse(methodParenCall is StringCallExpression)
        assertEquals(":", assertIs<MemberExpression>(methodParenCall.base).indexer)
        assertEquals(
            "Call(Member(Id(activity):setTitle):Const(\"Ready\"))",
            renderShape(methodParenCall)
        )

        val memberTableOuter = assertIs<CallExpression>(memberTable)
        assertIs<TableCallExpression>(memberTableOuter.base)
        assertEquals(
            "Call(TableCall(Member(Id(layout).inflate):Table(TableKeyString(Id(id)=Const(1)))):)",
            renderShape(memberTableOuter)
        )

        val methodTableOuter = assertIs<CallExpression>(methodTable)
        assertIs<TableCallExpression>(methodTableOuter.base)
        assertEquals(
            "Call(TableCall(Member(Id(view):configure):Table(TableKeyString(Id(id)=Const(1)))):)",
            renderShape(methodTableOuter)
        )
    }

    @Test
    fun indexBaseShortCallsRetainStringAndTableSpecialization() {
        val stringIndexed = parseExpression("return handlers[1] \"event\"")
        val tableIndexed = parseExpression("return widgets[1] { text = \"Save\" }")
        val parenIndexed = parseExpression("return handlers[1](\"event\")")

        val stringOuter = assertIs<CallExpression>(stringIndexed)
        val stringShort = assertIs<StringCallExpression>(stringOuter.base)
        assertIs<IndexExpression>(stringShort.base)
        assertEquals(
            "Call(StringCall(Index(Id(handlers)[Const(1)]):Const(\"event\")):)",
            renderShape(stringOuter)
        )

        val tableOuter = assertIs<CallExpression>(tableIndexed)
        val tableShort = assertIs<TableCallExpression>(tableOuter.base)
        assertIs<IndexExpression>(tableShort.base)
        assertEquals(
            "Call(TableCall(Index(Id(widgets)[Const(1)]):Table(TableKeyString(Id(text)=Const(\"Save\")))):)",
            renderShape(tableOuter)
        )

        val paren = assertIs<CallExpression>(parenIndexed)
        assertFalse(paren is StringCallExpression)
        assertIs<IndexExpression>(paren.base)
        assertEquals(
            "Call(Index(Id(handlers)[Const(1)]):Const(\"event\"))",
            renderShape(paren)
        )
    }

    @Test
    fun multiArgParenthesizedCallsNeverCollapseToStringOrTableCallNodes() {
        val multi = parseExpression("return loadLib(\"java.util.Locale\", \"getDefault\")")
        val empty = parseExpression("return ready()")
        val tableArg = parseExpression("return open({ path = \"a\" }, mode)")

        listOf(multi, empty, tableArg).forEach { expression ->
            val call = assertIs<CallExpression>(expression)
            assertFalse(call is StringCallExpression, renderShape(call))
            assertFalse(call is TableCallExpression, renderShape(call))
            assertFalse(call.base is StringCallExpression, renderShape(call))
            assertFalse(call.base is TableCallExpression, renderShape(call))
        }

        assertEquals(
            "Call(Id(loadLib):Const(\"java.util.Locale\"),Const(\"getDefault\"))",
            renderShape(multi)
        )
        assertEquals("Call(Id(ready):)", renderShape(empty))
        assertEquals(
            "Call(Id(open):Table(TableKeyString(Id(path)=Const(\"a\"))),Id(mode))",
            renderShape(tableArg)
        )
    }

    @Test
    fun callStatementsMirrorExpressionShapeSplit() {
        val stringStmt = parse(LuaVersion.LUA_5_3, "print 'hi'").firstStatement<CallStatement>().expression
        val tableStmt = parse(LuaVersion.LUA_5_3, "configure { enabled = true }")
            .firstStatement<CallStatement>().expression
        val parenStmt = parse(LuaVersion.LUA_5_3, "print('hi')").firstStatement<CallStatement>().expression

        val stringOuter = assertIs<CallExpression>(stringStmt)
        assertIs<StringCallExpression>(stringOuter.base)
        assertEquals(
            "Call(StringCall(Id(print):Const('hi')):)",
            renderShape(stringOuter)
        )

        val tableOuter = assertIs<CallExpression>(tableStmt)
        assertIs<TableCallExpression>(tableOuter.base)
        assertEquals(
            "Call(TableCall(Id(configure):Table(TableKeyString(Id(enabled)=Const(true)))):)",
            renderShape(tableOuter)
        )

        val paren = assertIs<CallExpression>(parenStmt)
        assertFalse(paren is StringCallExpression)
        assertEquals("Call(Id(print):Const('hi'))", renderShape(paren))
    }

    @Test
    fun luajavaStyleStringCallVsParenthesizedImportShapes() {
        val shortImport = parseExpression("return import \"android.widget.TextView\"")
        val parenImport = parseExpression("return import(\"android.widget.TextView\")")
        val shortLoadLib = parseExpression(
            "return luajava.loadLib \"java.util.Locale\", \"getDefault\""
        )
        val parenLoadLib = parseExpression(
            "return luajava.loadLib(\"java.util.Locale\", \"getDefault\")"
        )

        val shortImportOuter = assertIs<CallExpression>(shortImport)
        assertIs<StringCallExpression>(shortImportOuter.base)
        assertEquals(
            "Call(StringCall(Id(import):Const(\"android.widget.TextView\")):)",
            renderShape(shortImportOuter)
        )

        val parenImportCall = assertIs<CallExpression>(parenImport)
        assertFalse(parenImportCall is StringCallExpression)
        assertEquals(
            "Call(Id(import):Const(\"android.widget.TextView\"))",
            renderShape(parenImportCall)
        )

        // Compact continuation: outer Call wraps StringCall base + remaining args.
        val compact = assertIs<CallExpression>(shortLoadLib)
        assertIs<StringCallExpression>(compact.base)
        assertEquals(1, compact.arguments.size)
        assertEquals(
            "Call(StringCall(Member(Id(luajava).loadLib):Const(\"java.util.Locale\")):Const(\"getDefault\"))",
            renderShape(compact)
        )

        val parenLoad = assertIs<CallExpression>(parenLoadLib)
        assertFalse(parenLoad.base is StringCallExpression)
        assertEquals(
            "Call(Member(Id(luajava).loadLib):Const(\"java.util.Locale\"),Const(\"getDefault\"))",
            renderShape(parenLoad)
        )
    }

    @Test
    fun corpusPairsKeepStringTableAndParenFamiliesStable() {
        assertCaseShapes(
            "id string call" to (
                "return f \"x\"" to "Call(StringCall(Id(f):Const(\"x\")):)"
            ),
            "id single-quote string call" to (
                "return f 'x'" to "Call(StringCall(Id(f):Const('x')):)"
            ),
            "id paren string arg" to (
                "return f(\"x\")" to "Call(Id(f):Const(\"x\"))"
            ),
            "id table call" to (
                "return f { a = 1 }" to "Call(TableCall(Id(f):Table(TableKeyString(Id(a)=Const(1)))):)"
            ),
            "id paren table arg" to (
                "return f({ a = 1 })" to "Call(Id(f):Table(TableKeyString(Id(a)=Const(1))))"
            ),
            "member string call" to (
                "return m.n \"v\"" to "Call(StringCall(Member(Id(m).n):Const(\"v\")):)"
            ),
            "member paren call" to (
                "return m.n(\"v\")" to "Call(Member(Id(m).n):Const(\"v\"))"
            ),
            "method string call" to (
                "return m:n \"v\"" to "Call(StringCall(Member(Id(m):n):Const(\"v\")):)"
            ),
            "method paren call" to (
                "return m:n(\"v\")" to "Call(Member(Id(m):n):Const(\"v\"))"
            ),
            "index table call" to (
                "return t[1] { k = 2 }" to
                    "Call(TableCall(Index(Id(t)[Const(1)]):Table(TableKeyString(Id(k)=Const(2)))):)"
            ),
            "index paren call" to (
                "return t[1]({ k = 2 })" to
                    "Call(Index(Id(t)[Const(1)]):Table(TableKeyString(Id(k)=Const(2))))"
            ),
            "empty paren call" to (
                "return go()" to "Call(Id(go):)"
            ),
            "multi paren call" to (
                "return go(1, 2)" to "Call(Id(go):Const(1),Const(2))"
            ),
            "nested paren around callee still plain call" to (
                "return (go)(1)" to "Call(Id(go):Const(1))"
            )
        )
    }

    @Test
    fun specializedCallNodesExposeExpectedRuntimeClassNames() {
        val stringOuter = assertIs<CallExpression>(parseExpression("return print \"x\""))
        val tableOuter = assertIs<CallExpression>(parseExpression("return print { x = 1 }"))
        val paren = assertIs<CallExpression>(parseExpression("return print(\"x\")"))

        assertEquals("StringCallExpression", stringOuter.base::class.simpleName)
        assertEquals("TableCallExpression", tableOuter.base::class.simpleName)
        assertEquals("CallExpression", paren::class.simpleName)
        assertEquals("Identifier", paren.base::class.simpleName)
        assertNotEquals(stringOuter.base::class, paren::class)
        assertNotEquals(tableOuter.base::class, paren::class)
        assertNotEquals(stringOuter.base::class, tableOuter.base::class)
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
