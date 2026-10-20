package parser.lua53

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionOperator
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.source.AST2Lua
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.parse
import parser.renderShape
import parser.returnExpression

/**
 * Focused Lua 5.3 bitwise-operator corpus (TASK-185).
 *
 * Documented Lua 5.3 precedence (high → low), relevant slice:
 *   power ^ (right-assoc)
 *   unary operators: not  #  -  ~
 *   multiplicative * / // %
 *   additive + -
 *   concat .. (right-assoc)          ← higher than shifts
 *   shift << >> (left-assoc)
 *   bitwise and &
 *   bitwise xor ~
 *   bitwise or |
 *   comparisons < > <= >= ~= ==
 *   and / or
 *
 * Corpus asserts parse success plus AST shape and roundtrip-stable print.
 * Production parser defects must surface as assertion failures (no production edits here).
 */
class Lua53BitwiseOpsCorpusTddTest {

    private val printer = AST2Lua()

    @Test
    fun documentsBitwiseOperatorPrecedenceWithNamedShapes() {
        assertExpressionShapes(
            // shifts vs arithmetic / concat
            // concat binds tighter than shifts (Lua 5.3: .. above << >>)
            "left shift binds below addition on the right" to
                ("a << b + c" to "Binary(<<,Id(a),Binary(+,Id(b),Id(c)))"),
            "left shift binds below addition on the left" to
                ("a + b << c" to "Binary(<<,Binary(+,Id(a),Id(b)),Id(c))"),
            "right shift binds below floor division" to
                ("a // b >> c" to "Binary(>>,Binary(//,Id(a),Id(b)),Id(c))"),
            "left shift binds below subtraction" to
                ("a - b << c" to "Binary(<<,Binary(-,Id(a),Id(b)),Id(c))"),
            "right shift binds below multiplication" to
                ("a * b >> c" to "Binary(>>,Binary(*,Id(a),Id(b)),Id(c))"),
            "concat binds tighter than shift on the right" to
                ("a << b .. c" to "Binary(<<,Id(a),Binary(..,Id(b),Id(c)))"),
            "concat binds tighter than shift on the left" to
                ("a .. b >> c" to "Binary(>>,Binary(..,Id(a),Id(b)),Id(c))"),
            "left shift associates left" to
                ("a << b << c" to "Binary(<<,Binary(<<,Id(a),Id(b)),Id(c))"),
            "right shift associates left" to
                ("a >> b >> c" to "Binary(>>,Binary(>>,Id(a),Id(b)),Id(c))"),
            "mixed shifts associate left" to
                ("a << b >> c << d" to "Binary(<<,Binary(>>,Binary(<<,Id(a),Id(b)),Id(c)),Id(d))"),

            // & ~ | ladder
            "bitwise and binds above bitwise xor" to
                ("a ~ b & c" to "Binary(~,Id(a),Binary(&,Id(b),Id(c)))"),
            "bitwise xor binds above bitwise or" to
                ("a | b ~ c" to "Binary(|,Id(a),Binary(~,Id(b),Id(c)))"),
            "bitwise and binds above bitwise or" to
                ("a | b & c" to "Binary(|,Id(a),Binary(&,Id(b),Id(c)))"),
            "shift binds above bitwise and" to
                ("a & b << c" to "Binary(&,Id(a),Binary(<<,Id(b),Id(c)))"),
            "shift binds above bitwise xor" to
                ("a ~ b >> c" to "Binary(~,Id(a),Binary(>>,Id(b),Id(c)))"),
            "shift binds above bitwise or" to
                ("a | b << c" to "Binary(|,Id(a),Binary(<<,Id(b),Id(c)))"),
            "bitwise and associates left" to
                ("a & b & c" to "Binary(&,Binary(&,Id(a),Id(b)),Id(c))"),
            "bitwise xor associates left" to
                ("a ~ b ~ c" to "Binary(~,Binary(~,Id(a),Id(b)),Id(c))"),
            "bitwise or associates left" to
                ("a | b | c" to "Binary(|,Binary(|,Id(a),Id(b)),Id(c))"),
            "full bitwise ladder left-to-right nesting" to
                ("a | b ~ c & d << e" to "Binary(|,Id(a),Binary(~,Id(b),Binary(&,Id(c),Binary(<<,Id(d),Id(e)))))"),
            "full bitwise ladder reverse surface order" to
                ("a << b & c ~ d | e" to "Binary(|,Binary(~,Binary(&,Binary(<<,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // unary bitwise not
            "unary bitwise not binds above bitwise and" to
                ("~a & b" to "Binary(&,Unary(~,Id(a)),Id(b))"),
            "unary bitwise not binds above shift" to
                ("~a << b" to "Binary(<<,Unary(~,Id(a)),Id(b))"),
            "unary bitwise not binds above bitwise or" to
                ("~a | b" to "Binary(|,Unary(~,Id(a)),Id(b))"),
            "unary bitwise not chains as nested unaries" to
                ("~~a" to "Unary(~,Unary(~,Id(a)))"),
            "unary minus then bitwise not" to
                ("~-a" to "Unary(~,Unary(-,Id(a)))"),
            "unary bitwise not then unary minus" to
                ("-~a" to "Unary(-,Unary(~,Id(a)))"),
            "unary bitwise not of power remains unary-of-power" to
                ("~a ^ b" to "Unary(~,Binary(^,Id(a),Id(b)))"),
            "unary not keyword binds above bitwise or" to
                ("not a | b" to "Binary(|,Unary(not,Id(a)),Id(b))"),

            // comparisons / logical relative to bitwise
            "comparisons bind below bitwise or" to
                ("a | b < c" to "Binary(<,Binary(|,Id(a),Id(b)),Id(c))"),
            "equality after bitwise and" to
                ("a & b == c" to "Binary(==,Binary(&,Id(a),Id(b)),Id(c))"),
            "not-equal after bitwise xor" to
                ("a ~ b ~= c" to "Binary(~=,Binary(~,Id(a),Id(b)),Id(c))"),
            "less-equal after left shift" to
                ("a << b <= c" to "Binary(<=,Binary(<<,Id(a),Id(b)),Id(c))"),
            "greater-equal after right shift" to
                ("a >> b >= c" to "Binary(>=,Binary(>>,Id(a),Id(b)),Id(c))"),
            "and binds below bitwise or" to
                ("a | b and c" to "Binary(and,Binary(|,Id(a),Id(b)),Id(c))"),
            "or binds below bitwise or" to
                ("a | b or c" to "Binary(or,Binary(|,Id(a),Id(b)),Id(c))"),
            "or of and of bitwise comparison" to
                ("a & b == c and d or e" to "Binary(or,Binary(and,Binary(==,Binary(&,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // parentheses override
            "parentheses force or before and" to
                ("(a | b) & c" to "Binary(&,Binary(|,Id(a),Id(b)),Id(c))"),
            "parentheses force xor before and" to
                ("(a ~ b) & c" to "Binary(&,Binary(~,Id(a),Id(b)),Id(c))"),
            "parentheses force shift after and" to
                ("(a & b) << c" to "Binary(<<,Binary(&,Id(a),Id(b)),Id(c))"),
            "parentheses wrap unary operand of or" to
                ("~(a | b)" to "Unary(~,Binary(|,Id(a),Id(b)))"),
            "parentheses force concat under shift grouping" to
                ("(a << b) .. c" to "Binary(..,Binary(<<,Id(a),Id(b)),Id(c))"),
            "parentheses force shift under concat grouping" to
                ("a .. (b >> c)" to "Binary(..,Id(a),Binary(>>,Id(b),Id(c)))")
        )
    }

    @Test
    fun parsesNestedBitwiseMixesWithStableShapes() {
        assertExpressionShapes(
            "nested mix: floor-div shift and xor or" to (
                "(a // b) << 2 | flags & mask ~ toggle" to
                    "Binary(|,Binary(<<,Binary(//,Id(a),Id(b)),Const(2)),Binary(~,Binary(&,Id(flags),Id(mask)),Id(toggle)))"
                ),
            "nested mix: unary not around and then or" to (
                "~flags & mask | toggle" to
                    "Binary(|,Binary(&,Unary(~,Id(flags)),Id(mask)),Id(toggle))"
                ),
            "nested mix: hex constants through ladder" to (
                "0xFF << 4 | 0x0F & 0x33 ~ 0x01" to
                    "Binary(|,Binary(<<,Const(0xFF),Const(4)),Binary(~,Binary(&,Const(0x0F),Const(0x33)),Const(0x01)))"
                ),
            "nested mix: comparison of shifted masks" to (
                "flags & mask << offset == expected" to
                    "Binary(==,Binary(&,Id(flags),Binary(<<,Id(mask),Id(offset))),Id(expected))"
                ),
            "nested mix: logical guard around bitwise or" to (
                "ready and flags | mask or fallback" to
                    "Binary(or,Binary(and,Id(ready),Binary(|,Id(flags),Id(mask))),Id(fallback))"
                ),
            // .. higher than <<; right-assoc ..; so: (prefix .. a) << (b .. suffix)
            "nested mix: concat of shifted values" to (
                "prefix .. a << b .. suffix" to
                    "Binary(<<,Binary(..,Id(prefix),Id(a)),Binary(..,Id(b),Id(suffix)))"
                ),
            "nested mix: method-call base in bitwise and" to (
                "object:bits() & mask | 1" to
                    "Binary(|,Binary(&,Call(Member(Id(object):bits):),Id(mask)),Const(1))"
                ),
            "nested mix: index expression as shift count" to (
                "value << offsets[i] & mask" to
                    "Binary(&,Binary(<<,Id(value),Index(Id(offsets)[Id(i)])),Id(mask))"
                ),
            "nested mix: multi-level parentheses preserve grouping" to (
                "((a | b) & c) << (d + e)" to
                    "Binary(<<,Binary(&,Binary(|,Id(a),Id(b)),Id(c)),Binary(+,Id(d),Id(e)))"
                ),
            "nested mix: power inside unary not then and" to (
                "~base ^ exp & mask" to
                    "Binary(&,Unary(~,Binary(^,Id(base),Id(exp))),Id(mask))"
                ),
            "nested mix: member field as shift left operand" to (
                "bits.hi << 8 | bits.lo" to
                    "Binary(|,Binary(<<,Member(Id(bits).hi),Const(8)),Member(Id(bits).lo))"
                ),
            "nested mix: call argument preserves bitwise ladder" to (
                "pack(a | b & c << d)" to
                    "Call(Id(pack):Binary(|,Id(a),Binary(&,Id(b),Binary(<<,Id(c),Id(d)))))"
                ),
            "nested mix: table constructor field with bitwise or" to (
                "{ a & 0xFF, b << 1 | c }" to
                    "Table(TableKey(Const(1)=Binary(&,Id(a),Const(0xFF))),TableKey(Const(2)=Binary(|,Binary(<<,Id(b),Const(1)),Id(c))))"
                ),
            "nested mix: binary xor without spaces" to (
                "a~b|c" to
                    "Binary(|,Binary(~,Id(a),Id(b)),Id(c))"
                ),
            "nested mix: chained unary not with and/or ladder" to (
                "~~a & ~~b | ~~c" to
                    "Binary(|,Binary(&,Unary(~,Unary(~,Id(a))),Unary(~,Unary(~,Id(b)))),Unary(~,Unary(~,Id(c))))"
                )
        )
    }

    @Test
    fun exposesBitwiseAstOperatorsAndAssociativity() {
        val ladder = assertIs<BinaryExpression>(parseExpression("a | b ~ c & d << e"))
        assertEquals(ExpressionOperator.BIT_OR, ladder.operator)
        assertEquals("Id(a)", renderShape(ladder.left!!))

        val xor = assertIs<BinaryExpression>(ladder.right)
        assertEquals(ExpressionOperator.BIT_TILDE, xor.operator)
        assertEquals("Id(b)", renderShape(xor.left!!))

        val and = assertIs<BinaryExpression>(xor.right)
        assertEquals(ExpressionOperator.BIT_AND, and.operator)
        assertEquals("Id(c)", renderShape(and.left!!))

        val shift = assertIs<BinaryExpression>(and.right)
        assertEquals(ExpressionOperator.BIT_LT, shift.operator)
        assertEquals("Id(d)", renderShape(shift.left!!))
        assertEquals("Id(e)", renderShape(shift.right!!))

        val rightShiftChain = assertIs<BinaryExpression>(parseExpression("a >> b >> c"))
        assertEquals(ExpressionOperator.BIT_GT, rightShiftChain.operator)
        val leftShiftOfChain = assertIs<BinaryExpression>(rightShiftChain.left)
        assertEquals(ExpressionOperator.BIT_GT, leftShiftOfChain.operator)
        assertEquals("Id(a)", renderShape(leftShiftOfChain.left!!))
        assertEquals("Id(b)", renderShape(leftShiftOfChain.right!!))
        assertEquals("Id(c)", renderShape(rightShiftChain.right!!))

        val leftShiftChain = assertIs<BinaryExpression>(parseExpression("a << b << c"))
        assertEquals(ExpressionOperator.BIT_LT, leftShiftChain.operator)
        val leftShiftInner = assertIs<BinaryExpression>(leftShiftChain.left)
        assertEquals(ExpressionOperator.BIT_LT, leftShiftInner.operator)
        assertEquals("Id(a)", renderShape(leftShiftInner.left!!))
        assertEquals("Id(b)", renderShape(leftShiftInner.right!!))
        assertEquals("Id(c)", renderShape(leftShiftChain.right!!))

        val unaryNot = assertIs<UnaryExpression>(parseExpression("~flags"))
        assertEquals(ExpressionOperator.BIT_TILDE, unaryNot.operator)
        assertIs<Identifier>(unaryNot.arg)
        assertEquals("flags", (unaryNot.arg as Identifier).name)

        val nestedUnary = assertIs<UnaryExpression>(parseExpression("~~x"))
        assertEquals(ExpressionOperator.BIT_TILDE, nestedUnary.operator)
        val innerUnary = assertIs<UnaryExpression>(nestedUnary.arg)
        assertEquals(ExpressionOperator.BIT_TILDE, innerUnary.operator)
        assertEquals("Id(x)", renderShape(innerUnary.arg))
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

    private companion object {
        val BITWISE_SURFACE_MARKERS = listOf("&", "|", "<<", ">>", "~")
    }
}
