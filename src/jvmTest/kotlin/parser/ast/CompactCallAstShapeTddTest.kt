package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.compactCallArguments
import io.github.dingyi222666.luaparser.parser.compactCallBase
import io.github.dingyi222666.luaparser.parser.isCompactShortCall
import io.github.dingyi222666.luaparser.source.AST2Lua
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import parser.parse
import parser.renderShape
import parser.returnExpression

class CompactCallAstShapeTddTest {

    private val printer = AST2Lua()

    @Test
    fun compactStringCallUsesDocumentedOuterCallWrapperShape() {
        val call = parseCall("return luajava.loadLib \"java.util.Locale\", \"getDefault\"")
        val compactBase = assertIs<StringCallExpression>(call.base)

        assertTrue(call.isCompactShortCall())
        assertSame(compactBase.base, call.compactCallBase())
        assertEquals("Member(Id(luajava).loadLib)", renderShape(call.compactCallBase()))
        assertEquals(
            listOf("Const(\"java.util.Locale\")", "Const(\"getDefault\")"),
            call.compactCallArguments().map(::renderShape)
        )
        assertEquals(
            "Call(StringCall(Member(Id(luajava).loadLib):Const(\"java.util.Locale\")):Const(\"getDefault\"))",
            renderShape(call)
        )
    }

    @Test
    fun compactTableCallUsesSameQueryableWrapperShape() {
        val call = parseCall("return widgets[1] { text = \"Save\" }, options")
        val compactBase = assertIs<TableCallExpression>(call.base)

        assertTrue(call.isCompactShortCall())
        assertIs<IndexExpression>(call.compactCallBase())
        assertSame(compactBase.base, call.compactCallBase())
        assertEquals(
            listOf("Table(TableKeyString(Id(text)=Const(\"Save\")))", "Id(options)"),
            call.compactCallArguments().map(::renderShape)
        )
        assertEquals(
            "Call(TableCall(Index(Id(widgets)[Const(1)]):Table(TableKeyString(Id(text)=Const(\"Save\")))):Id(options))",
            renderShape(call)
        )
    }

    @Test
    fun compactHelpersLeavePlainCallsUnchanged() {
        val call = parseCall("return luajava.loadLib(\"java.util.Locale\", \"getDefault\")")

        assertEquals(false, call.isCompactShortCall())
        assertIs<MemberExpression>(call.compactCallBase())
        assertEquals(call.arguments, call.compactCallArguments())
        assertEquals(
            "Call(Member(Id(luajava).loadLib):Const(\"java.util.Locale\"),Const(\"getDefault\"))",
            renderShape(call)
        )
    }

    @Test
    fun ast2LuaPreservesCompactStringAndTableCallContinuations() {
        listOf(
            "return luajava.loadLib \"java.util.Locale\", \"getDefault\"",
            "return receiver:emit \"ready\", { code = 200 }",
            "return widgets[1] { text = \"Save\" }, options"
        ).forEach { source ->
            val initial = parse(LuaVersion.LUA_5_3, source)
            val printed = printer.asCode(initial)
            val reparsed = parse(LuaVersion.LUA_5_3, printed)

            assertEquals(renderShape(initial), renderShape(reparsed), source)
            assertEquals(renderShape(initial.returnExpression()), renderShape(reparsed.returnExpression()), printed)
        }
    }

    @Test
    fun compactCallArgumentsPreserveArgumentNodeTypesInOrder() {
        val call = parseCall("return factory \"name\", { id = 1 }, count")
        val arguments = call.compactCallArguments()

        assertIs<ConstantNode>(arguments[0])
        assertIs<TableConstructorExpression>(arguments[1])
        assertEquals("count", assertIs<Identifier>(arguments[2]).name)
    }

    private fun parseCall(source: String): CallExpression {
        return assertIs<CallExpression>(parse(LuaVersion.LUA_5_3, source).returnExpression())
    }
}
