package parser.lua53

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionOperator
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import parser.parse
import parser.renderShape
import parser.returnExpression

class Lua53ExpressionPrecedenceTddTest {

    @Test
    fun parsesResourceBackedComprehensiveExpressionPrecedenceFixture() {
        val source = loadExpressionFixture("comprehensive_expression_precedence.lua")
        val expectedShape = loadExpressionFixture("comprehensive_expression_precedence.shape.txt").trimEnd()
        val chunk = parse(LuaVersion.LUA_5_3, source)

        assertEquals(expectedShape, renderShape(chunk).trimEnd())
        assertEquals(9, chunk.body.returnStatement!!.arguments.size)
        assertEquals(
            "Binary(or,Binary(and,Binary(<,Binary(+,Id(a),Binary(*,Id(b),Id(c))),Binary(<<,Id(d),Const(2))),Binary(~=,Binary(|,Id(e),Binary(&,Id(f),Id(g))),Binary(..,Id(h),Binary(..,Id(i),Id(j))))),Id(k))",
            renderShape(chunk.body.returnStatement!!.arguments[0])
        )
        assertEquals(
            "Call(Member(Index(Call(Member(Id(root):method):Id(a),Id(b))[Binary(+,Id(i),Const(1))]).field):Binary(..,Const(\"x\"),Const(\"y\")))",
            renderShape(chunk.body.returnStatement!!.arguments[1])
        )
        assertEquals("Binary(^,Unary(-,Id(unary)),Binary(^,Id(power),Const(2)))", renderShape(chunk.body.returnStatement!!.arguments[2]))
        assertEquals("Binary(+,Binary(*,Binary(+,Id(a),Id(b)),Binary(-,Id(c),Id(d))),Id(e))", renderShape(chunk.body.returnStatement!!.arguments[3]))
        assertEquals("Unary(not,Binary(or,Id(a),Id(b)))", renderShape(chunk.body.returnStatement!!.arguments[4]))
        assertEquals("Binary(or,Binary(and,Id(a),Id(b)),Id(c))", renderShape(chunk.body.returnStatement!!.arguments[5]))
        assertEquals("Binary(..,Id(a),Binary(..,Binary(^,Id(b),Binary(^,Id(c),Id(d))),Id(e)))", renderShape(chunk.body.returnStatement!!.arguments[6]))
        assertEquals("Binary(>>,Binary(<<,Id(a),Id(b)),Id(c))", renderShape(chunk.body.returnStatement!!.arguments[7]))
        assertEquals("Binary(|,Binary(~,Binary(&,Id(a),Id(b)),Id(c)),Id(d))", renderShape(chunk.body.returnStatement!!.arguments[8]))
    }

    @Test
    fun coversLua53PrecedenceWithNamedShapeAssertions() {
        assertExpressionShapes(
            "multiplication binds before addition" to ("a + b * c" to "Binary(+,Id(a),Binary(*,Id(b),Id(c)))"),
            "division binds before subtraction" to ("a - b / c" to "Binary(-,Id(a),Binary(/,Id(b),Id(c)))"),
            "floor division binds before addition" to ("a + b // c" to "Binary(+,Id(a),Binary(//,Id(b),Id(c)))"),
            "modulo binds before subtraction" to ("a - b % c" to "Binary(-,Id(a),Binary(%,Id(b),Id(c)))"),
            "multiplicative operators associate left" to ("a * b / c % d" to "Binary(%,Binary(/,Binary(*,Id(a),Id(b)),Id(c)),Id(d))"),
            "additive operators associate left" to ("a + b - c + d" to "Binary(+,Binary(-,Binary(+,Id(a),Id(b)),Id(c)),Id(d))"),
            "shift binds lower than addition on right" to ("a << b + c" to "Binary(<<,Id(a),Binary(+,Id(b),Id(c)))"),
            "shift binds lower than addition on left" to ("a + b << c" to "Binary(<<,Binary(+,Id(a),Id(b)),Id(c))"),
            "left shift associates left" to ("a << b << c" to "Binary(<<,Binary(<<,Id(a),Id(b)),Id(c))"),
            "right shift associates left" to ("a >> b >> c" to "Binary(>>,Binary(>>,Id(a),Id(b)),Id(c))"),
            "bitwise and binds above bitwise xor" to ("a ~ b & c" to "Binary(~,Id(a),Binary(&,Id(b),Id(c)))"),
            "bitwise xor binds above bitwise or" to ("a | b ~ c" to "Binary(|,Id(a),Binary(~,Id(b),Id(c)))"),
            "bitwise and binds above bitwise or" to ("a | b & c" to "Binary(|,Id(a),Binary(&,Id(b),Id(c)))"),
            "shift binds above bitwise and" to ("a & b << c" to "Binary(&,Id(a),Binary(<<,Id(b),Id(c)))"),
            "concat binds below shifts" to ("a << b .. c" to "Binary(..,Binary(<<,Id(a),Id(b)),Id(c))"),
            "concat binds above addition on right" to ("a .. b + c" to "Binary(..,Id(a),Binary(+,Id(b),Id(c)))"),
            "concat is right associative" to ("a .. b .. c" to "Binary(..,Id(a),Binary(..,Id(b),Id(c)))"),
            "concat chains after arithmetic groups right" to ("a + b .. c * d .. e" to "Binary(..,Binary(+,Id(a),Id(b)),Binary(..,Binary(*,Id(c),Id(d)),Id(e)))"),
            "comparisons bind below bitwise or" to ("a | b < c" to "Binary(<,Binary(|,Id(a),Id(b)),Id(c))"),
            "comparison operators associate left" to ("a < b == c" to "Binary(==,Binary(<,Id(a),Id(b)),Id(c))"),
            "not equal comparison after concat" to ("a .. b ~= c" to "Binary(~=,Binary(..,Id(a),Id(b)),Id(c))"),
            "less equal comparison after addition" to ("a + b <= c" to "Binary(<=,Binary(+,Id(a),Id(b)),Id(c))"),
            "greater equal comparison after floor division" to ("a // b >= c" to "Binary(>=,Binary(//,Id(a),Id(b)),Id(c))"),
            "and binds below comparisons" to ("a < b and c" to "Binary(and,Binary(<,Id(a),Id(b)),Id(c))"),
            "or binds below and" to ("a or b and c" to "Binary(or,Id(a),Binary(and,Id(b),Id(c)))"),
            "and associates left" to ("a and b and c" to "Binary(and,Binary(and,Id(a),Id(b)),Id(c))"),
            "or associates left" to ("a or b or c" to "Binary(or,Binary(or,Id(a),Id(b)),Id(c))"),
            "mixed logical comparison arithmetic" to ("a + b < c and d or e" to "Binary(or,Binary(and,Binary(<,Binary(+,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),
            "power is right associative" to ("a ^ b ^ c" to "Binary(^,Id(a),Binary(^,Id(b),Id(c)))"),
            "power binds above multiplication" to ("a * b ^ c" to "Binary(*,Id(a),Binary(^,Id(b),Id(c)))"),
            "power binds inside unary operand" to ("-a ^ b" to "Unary(-,Binary(^,Id(a),Id(b)))"),
            "unary binds above multiplication" to ("-a * b" to "Binary(*,Unary(-,Id(a)),Id(b))"),
            "unary length binds above concat operand" to ("#a .. b" to "Binary(..,Unary(#,Id(a)),Id(b))"),
            "unary not accepts comparison operand" to ("not a < b" to "Binary(<,Unary(not,Id(a)),Id(b))"),
            "unary bitwise not binds above bitwise and" to ("~a & b" to "Binary(&,Unary(~,Id(a)),Id(b))"),
            "parentheses override arithmetic precedence" to ("(a + b) * c" to "Binary(*,Binary(+,Id(a),Id(b)),Id(c))"),
            "parentheses override logical precedence" to ("(a or b) and c" to "Binary(and,Binary(or,Id(a),Id(b)),Id(c))"),
            "nested parentheses preserve expression grouping" to ("((a)) + (b)" to "Binary(+,Id(a),Id(b))"),
            "call binds tighter than multiplication" to ("fn(a) * b" to "Binary(*,Call(Id(fn):Id(a)),Id(b))"),
            "call arguments preserve expression precedence" to ("fn(a + b * c)" to "Call(Id(fn):Binary(+,Id(a),Binary(*,Id(b),Id(c))))"),
            "index binds tighter than addition" to ("items[i] + value" to "Binary(+,Index(Id(items)[Id(i)]),Id(value))"),
            "index expression preserves inner addition" to ("items[i + 1]" to "Index(Id(items)[Binary(+,Id(i),Const(1))])"),
            "member access binds tighter than concat" to ("object.name .. suffix" to "Binary(..,Member(Id(object).name),Id(suffix))"),
            "method call binds tighter than and" to ("object:method(a, b) and ok" to "Binary(and,Call(Member(Id(object):method):Id(a),Id(b)),Id(ok))"),
            "chained calls indexes and members stay left nested" to ("root:make(a)[i].field(b)" to "Call(Member(Index(Call(Member(Id(root):make):Id(a))[Id(i)]).field):Id(b))"),
            "string call binds as prefix expression" to ("printer 'ready' or fallback" to "Binary(or,StringCall(Id(printer):Const('ready')),Id(fallback))"),
            "table call binds as prefix expression" to ("configure { enabled = true } and ok" to "Binary(and,TableCall(Id(configure):Table(TableKeyString(Id(enabled)=Const(true)))),Id(ok))"),
            "method call can be indexed before arithmetic" to ("object:method()[i] + 1" to "Binary(+,Index(Call(Member(Id(object):method):)[Id(i)]),Const(1))"),
            "member chain can be called before comparison" to ("module.child.run() == result" to "Binary(==,Call(Member(Member(Id(module).child).run):),Id(result))"),
            "nested prefix expression participates in power" to ("object.value ^ powers[i]" to "Binary(^,Member(Id(object).value),Index(Id(powers)[Id(i)]))")
        )
    }

    @Test
    fun exposesRepresentativeAstOperatorsAndAssociativity() {
        val expression = parseExpression("a .. b .. c ^ d ^ e")
        val concat = assertIs<BinaryExpression>(expression)
        assertEquals(ExpressionOperator.CONCAT, concat.operator)
        assertEquals("Id(a)", renderShape(concat.left!!))

        val rightConcat = assertIs<BinaryExpression>(concat.right)
        assertEquals(ExpressionOperator.CONCAT, rightConcat.operator)
        assertEquals("Id(b)", renderShape(rightConcat.left!!))

        val power = assertIs<BinaryExpression>(rightConcat.right)
        assertEquals(ExpressionOperator.BIT_EXP, power.operator)
        assertEquals("Id(c)", renderShape(power.left!!))
        assertEquals("Binary(^,Id(d),Id(e))", renderShape(power.right!!))
    }

    @Test
    fun exposesPrefixAstForCallsIndexesMembersAndMethodCalls() {
        val call = assertIs<CallExpression>(parseExpression("root:make(a + b)[i].field('x', { y = z })"))
        assertEquals("Member(Index(Call(Member(Id(root):make):Binary(+,Id(a),Id(b)))[Id(i)]).field)", renderShape(call.base))
        assertEquals(2, call.arguments.size)
        assertEquals("Const('x')", renderShape(call.arguments[0]))
        assertEquals("Table(TableKeyString(Id(y)=Id(z)))", renderShape(call.arguments[1]))

        val field = assertIs<MemberExpression>(call.base)
        assertEquals(".", field.indexer)
        assertEquals("field", field.identifier.name)
        val index = assertIs<IndexExpression>(field.base)
        assertEquals("Id(i)", renderShape(index.index))
        val methodCall = assertIs<CallExpression>(index.base)
        val method = assertIs<MemberExpression>(methodCall.base)
        assertEquals(":", method.indexer)
        assertEquals("make", method.identifier.name)
    }

    @Test
    fun exposesSourceRangesForRepresentativeExpressionNodes() {
        val returnStatement = parse(LuaVersion.LUA_5_3, "return a + b * c, root:method(value)[i]\n").body.returnStatement!!
        assertRange(returnStatement, startLine = 1, startColumn = 1, endLine = 1, endColumn = 38)

        val arithmetic = assertIs<BinaryExpression>(returnStatement.arguments[0])
        assertRange(arithmetic, startLine = 1, startColumn = 8, endLine = 1, endColumn = 17)
        assertRange(assertIs<Identifier>(arithmetic.left), startLine = 1, startColumn = 8, endLine = 1, endColumn = 9)

        val multiply = assertIs<BinaryExpression>(arithmetic.right)
        assertEquals(ExpressionOperator.MULT, multiply.operator)
        assertRange(multiply, startLine = 1, startColumn = 12, endLine = 1, endColumn = 17)
        assertRange(assertIs<Identifier>(multiply.right), startLine = 1, startColumn = 16, endLine = 1, endColumn = 17)

        val index = assertIs<IndexExpression>(returnStatement.arguments[1])
        assertRange(index, startLine = 1, startColumn = 19, endLine = 1, endColumn = 38)
        val call = assertIs<CallExpression>(index.base)
        assertRange(call, startLine = 1, startColumn = 19, endLine = 1, endColumn = 35)
        assertRange(assertIs<MemberExpression>(call.base), startLine = 1, startColumn = 19, endLine = 1, endColumn = 30)
        assertRange(assertIs<Identifier>(call.arguments.single()), startLine = 1, startColumn = 31, endLine = 1, endColumn = 36)
    }

    @Test
    fun exposesRangesForRightAssociativeExpressionsAcrossLines() {
        val expression = parseExpression(
            """
            left
                .. middle
                .. right ^ tail
            """.trimIndent()
        )

        val concat = assertIs<BinaryExpression>(expression)
        assertEquals(ExpressionOperator.CONCAT, concat.operator)
        assertRange(concat, startLine = 1, startColumn = 1, endLine = 3, endColumn = 20)
        assertRange(assertIs<Identifier>(concat.left), startLine = 1, startColumn = 1, endLine = 1, endColumn = 5)

        val rightConcat = assertIs<BinaryExpression>(concat.right)
        assertEquals(ExpressionOperator.CONCAT, rightConcat.operator)
        assertRange(rightConcat, startLine = 2, startColumn = 8, endLine = 3, endColumn = 20)

        val power = assertIs<BinaryExpression>(rightConcat.right)
        assertEquals(ExpressionOperator.BIT_EXP, power.operator)
        assertRange(power, startLine = 3, startColumn = 8, endLine = 3, endColumn = 20)
    }

    private fun assertExpressionShapes(vararg cases: Pair<String, Pair<String, String>>) {
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

    private fun parseExpression(source: String): ExpressionNode {
        return parse(LuaVersion.LUA_5_3, "return $source").returnExpression()
    }

    private fun assertRange(
        node: io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int
    ) {
        assertEquals(Range(Position(startLine, startColumn), Position(endLine, endColumn)), node.range)
    }

    private fun loadExpressionFixture(name: String): String {
        val path = "/parser/tdd/lua53/expressions/$name"
        return checkNotNull(javaClass.getResourceAsStream(path)) {
            "Missing expression precedence fixture: $path"
        }.bufferedReader().use { it.readText() }
    }
}
