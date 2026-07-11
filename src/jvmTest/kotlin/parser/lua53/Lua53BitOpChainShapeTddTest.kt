package parser.lua53

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
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
 * Lua 5.3 bitwise operator *chain* shape corpus (TASK-313).
 *
 * Complements [Lua53BitwiseOpsCorpusTddTest] by pinning longer homogeneous and
 * mixed chains of `& | ~ << >>` plus unary bitwise-not interaction, so
 * left-associativity and the documented precedence ladder cannot regress without
 * a red shape assertion.
 *
 * Documented Lua 5.3 precedence (high → low), relevant slice:
 *   power ^ (right-assoc)
 *   unary operators: not  #  -  ~
 *   multiplicative * / // %
 *   additive + -
 *   concat .. (right-assoc)          ← higher than shifts
 *   shift << >> (left-assoc)
 *   bitwise and &  (left-assoc)
 *   bitwise xor ~  (left-assoc)
 *   bitwise or |   (left-assoc)
 *   comparisons / and / or
 *
 * Test-only; production defects surface as assertion failures.
 */
class Lua53BitOpChainShapeTddTest {

    private val printer = AST2Lua()

    @Test
    fun documentsHomogeneousBitwiseChainsAssociateLeft() {
        assertExpressionShapes(
            // & chains
            "two-term and is flat binary" to
                ("a & b" to "Binary(&,Id(a),Id(b))"),
            "three-term and associates left" to
                ("a & b & c" to "Binary(&,Binary(&,Id(a),Id(b)),Id(c))"),
            "four-term and associates left" to
                ("a & b & c & d" to "Binary(&,Binary(&,Binary(&,Id(a),Id(b)),Id(c)),Id(d))"),
            "five-term and associates left" to
                ("a & b & c & d & e" to
                    "Binary(&,Binary(&,Binary(&,Binary(&,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // binary ~ (xor) chains
            "two-term xor is flat binary" to
                ("a ~ b" to "Binary(~,Id(a),Id(b))"),
            "three-term xor associates left" to
                ("a ~ b ~ c" to "Binary(~,Binary(~,Id(a),Id(b)),Id(c))"),
            "four-term xor associates left" to
                ("a ~ b ~ c ~ d" to "Binary(~,Binary(~,Binary(~,Id(a),Id(b)),Id(c)),Id(d))"),
            "five-term xor associates left" to
                ("a ~ b ~ c ~ d ~ e" to
                    "Binary(~,Binary(~,Binary(~,Binary(~,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // | chains
            "two-term or is flat binary" to
                ("a | b" to "Binary(|,Id(a),Id(b))"),
            "three-term or associates left" to
                ("a | b | c" to "Binary(|,Binary(|,Id(a),Id(b)),Id(c))"),
            "four-term or associates left" to
                ("a | b | c | d" to "Binary(|,Binary(|,Binary(|,Id(a),Id(b)),Id(c)),Id(d))"),
            "five-term or associates left" to
                ("a | b | c | d | e" to
                    "Binary(|,Binary(|,Binary(|,Binary(|,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // << chains
            "two-term left shift is flat binary" to
                ("a << b" to "Binary(<<,Id(a),Id(b))"),
            "three-term left shift associates left" to
                ("a << b << c" to "Binary(<<,Binary(<<,Id(a),Id(b)),Id(c))"),
            "four-term left shift associates left" to
                ("a << b << c << d" to "Binary(<<,Binary(<<,Binary(<<,Id(a),Id(b)),Id(c)),Id(d))"),
            "five-term left shift associates left" to
                ("a << b << c << d << e" to
                    "Binary(<<,Binary(<<,Binary(<<,Binary(<<,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // >> chains
            "two-term right shift is flat binary" to
                ("a >> b" to "Binary(>>,Id(a),Id(b))"),
            "three-term right shift associates left" to
                ("a >> b >> c" to "Binary(>>,Binary(>>,Id(a),Id(b)),Id(c))"),
            "four-term right shift associates left" to
                ("a >> b >> c >> d" to "Binary(>>,Binary(>>,Binary(>>,Id(a),Id(b)),Id(c)),Id(d))"),
            "five-term right shift associates left" to
                ("a >> b >> c >> d >> e" to
                    "Binary(>>,Binary(>>,Binary(>>,Binary(>>,Id(a),Id(b)),Id(c)),Id(d)),Id(e))")
        )
    }

    @Test
    fun documentsMixedShiftChainsAssociateLeftAtSameBand() {
        assertExpressionShapes(
            "left then right shift associates left" to
                ("a << b >> c" to "Binary(>>,Binary(<<,Id(a),Id(b)),Id(c))"),
            "right then left shift associates left" to
                ("a >> b << c" to "Binary(<<,Binary(>>,Id(a),Id(b)),Id(c))"),
            "left right left shift chain" to
                ("a << b >> c << d" to "Binary(<<,Binary(>>,Binary(<<,Id(a),Id(b)),Id(c)),Id(d))"),
            "right left right shift chain" to
                ("a >> b << c >> d" to "Binary(>>,Binary(<<,Binary(>>,Id(a),Id(b)),Id(c)),Id(d))"),
            "long alternating shift chain" to
                ("a << b >> c << d >> e" to
                    "Binary(>>,Binary(<<,Binary(>>,Binary(<<,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),
            "shift chain with constant counts" to
                ("value << 1 >> 2 << 3" to
                    "Binary(<<,Binary(>>,Binary(<<,Id(value),Const(1)),Const(2)),Const(3))"),
            "shift chain with hex operands" to
                ("0xFF << 4 >> 2 << 1" to
                    "Binary(<<,Binary(>>,Binary(<<,Const(0xFF),Const(4)),Const(2)),Const(1))")
        )
    }

    @Test
    fun documentsBitwiseLadderPrecedenceAcrossChains() {
        assertExpressionShapes(
            // & above ~
            "and binds above xor in three-term chain" to
                ("a ~ b & c" to "Binary(~,Id(a),Binary(&,Id(b),Id(c)))"),
            "and binds above xor when and is on the left" to
                ("a & b ~ c" to "Binary(~,Binary(&,Id(a),Id(b)),Id(c))"),
            "and chain under outer xor" to
                ("a ~ b & c & d" to "Binary(~,Id(a),Binary(&,Binary(&,Id(b),Id(c)),Id(d)))"),
            "xor chain with and in the middle" to
                ("a ~ b & c ~ d" to "Binary(~,Binary(~,Id(a),Binary(&,Id(b),Id(c))),Id(d))"),

            // ~ above |
            "xor binds above or in three-term chain" to
                ("a | b ~ c" to "Binary(|,Id(a),Binary(~,Id(b),Id(c)))"),
            "xor binds above or when xor is on the left" to
                ("a ~ b | c" to "Binary(|,Binary(~,Id(a),Id(b)),Id(c))"),
            "xor chain under outer or" to
                ("a | b ~ c ~ d" to "Binary(|,Id(a),Binary(~,Binary(~,Id(b),Id(c)),Id(d)))"),
            "or chain with xor in the middle" to
                ("a | b ~ c | d" to "Binary(|,Binary(|,Id(a),Binary(~,Id(b),Id(c))),Id(d))"),

            // & above |
            "and binds above or in three-term chain" to
                ("a | b & c" to "Binary(|,Id(a),Binary(&,Id(b),Id(c)))"),
            "and binds above or when and is on the left" to
                ("a & b | c" to "Binary(|,Binary(&,Id(a),Id(b)),Id(c))"),
            "and chain under outer or" to
                ("a | b & c & d" to "Binary(|,Id(a),Binary(&,Binary(&,Id(b),Id(c)),Id(d)))"),
            "or chain with and in the middle" to
                ("a | b & c | d" to "Binary(|,Binary(|,Id(a),Binary(&,Id(b),Id(c))),Id(d))"),

            // shift above &
            "shift binds above and on the right" to
                ("a & b << c" to "Binary(&,Id(a),Binary(<<,Id(b),Id(c)))"),
            "shift binds above and on the left" to
                ("a << b & c" to "Binary(&,Binary(<<,Id(a),Id(b)),Id(c))"),
            "right shift binds above and" to
                ("a & b >> c" to "Binary(&,Id(a),Binary(>>,Id(b),Id(c)))"),
            "shift chain under outer and" to
                ("a & b << c >> d" to "Binary(&,Id(a),Binary(>>,Binary(<<,Id(b),Id(c)),Id(d)))"),
            "and chain with shift in the middle" to
                ("a & b << c & d" to "Binary(&,Binary(&,Id(a),Binary(<<,Id(b),Id(c))),Id(d))"),

            // shift above ~ and |
            "shift binds above xor" to
                ("a ~ b << c" to "Binary(~,Id(a),Binary(<<,Id(b),Id(c)))"),
            "shift binds above or" to
                ("a | b >> c" to "Binary(|,Id(a),Binary(>>,Id(b),Id(c)))"),
            "left-nested full ladder surface order high-to-low" to
                ("a << b & c ~ d | e" to
                    "Binary(|,Binary(~,Binary(&,Binary(<<,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),
            "right-nested full ladder surface order low-to-high" to
                ("a | b ~ c & d << e" to
                    "Binary(|,Id(a),Binary(~,Id(b),Binary(&,Id(c),Binary(<<,Id(d),Id(e)))))"),
            "long ladder with multi-term same-band chains" to
                ("a | b | c ~ d ~ e & f & g << h << i" to
                    "Binary(|,Binary(|,Id(a),Id(b)),Binary(~,Binary(~,Id(c),Id(d)),Binary(&,Binary(&,Id(e),Id(f)),Binary(<<,Binary(<<,Id(g),Id(h)),Id(i)))))"),
            "long ladder reverse multi-term chains" to
                ("a << b << c & d & e ~ f ~ g | h | i" to
                    "Binary(|,Binary(|,Binary(~,Binary(~,Binary(&,Binary(&,Binary(<<,Binary(<<,Id(a),Id(b)),Id(c)),Id(d)),Id(e)),Id(f)),Id(g)),Id(h)),Id(i))")
        )
    }

    @Test
    fun documentsUnaryBitwiseNotInChains() {
        assertExpressionShapes(
            // unary ~ binds above all binary bitwise / shifts
            "unary not as left of and" to
                ("~a & b" to "Binary(&,Unary(~,Id(a)),Id(b))"),
            "unary not as right of and" to
                ("a & ~b" to "Binary(&,Id(a),Unary(~,Id(b)))"),
            "unary not on both and operands" to
                ("~a & ~b" to "Binary(&,Unary(~,Id(a)),Unary(~,Id(b)))"),
            "unary not as left of xor" to
                ("~a ~ b" to "Binary(~,Unary(~,Id(a)),Id(b))"),
            "unary not as right of xor" to
                ("a ~ ~b" to "Binary(~,Id(a),Unary(~,Id(b)))"),
            "unary not as left of or" to
                ("~a | b" to "Binary(|,Unary(~,Id(a)),Id(b))"),
            "unary not as right of or" to
                ("a | ~b" to "Binary(|,Id(a),Unary(~,Id(b)))"),
            "unary not as left of left shift" to
                ("~a << b" to "Binary(<<,Unary(~,Id(a)),Id(b))"),
            "unary not as right of left shift" to
                ("a << ~b" to "Binary(<<,Id(a),Unary(~,Id(b)))"),
            "unary not as left of right shift" to
                ("~a >> b" to "Binary(>>,Unary(~,Id(a)),Id(b))"),
            "unary not as right of right shift" to
                ("a >> ~b" to "Binary(>>,Id(a),Unary(~,Id(b)))"),

            // nested unary chains
            "double unary not nests" to
                ("~~a" to "Unary(~,Unary(~,Id(a)))"),
            "triple unary not nests" to
                ("~~~a" to "Unary(~,Unary(~,Unary(~,Id(a))))"),
            "double unary not under and" to
                ("~~a & b" to "Binary(&,Unary(~,Unary(~,Id(a))),Id(b))"),
            "double unary not under or with and" to
                ("~~a & ~~b | ~~c" to
                    "Binary(|,Binary(&,Unary(~,Unary(~,Id(a))),Unary(~,Unary(~,Id(b)))),Unary(~,Unary(~,Id(c))))"),
            "unary not of each term in and chain" to
                ("~a & ~b & ~c" to
                    "Binary(&,Binary(&,Unary(~,Id(a)),Unary(~,Id(b))),Unary(~,Id(c)))"),
            "unary not of each term in or chain" to
                ("~a | ~b | ~c" to
                    "Binary(|,Binary(|,Unary(~,Id(a)),Unary(~,Id(b))),Unary(~,Id(c)))"),
            "unary not of each term in shift chain" to
                ("~a << ~b << ~c" to
                    "Binary(<<,Binary(<<,Unary(~,Id(a)),Unary(~,Id(b))),Unary(~,Id(c)))"),

            // unary ~ with other unaries
            "unary minus then bitwise not" to
                ("~-a" to "Unary(~,Unary(-,Id(a)))"),
            "unary bitwise not then unary minus" to
                ("-~a" to "Unary(-,Unary(~,Id(a)))"),
            "unary not keyword then bitwise or" to
                ("not a | b" to "Binary(|,Unary(not,Id(a)),Id(b))"),
            "unary not keyword of each or operand" to
                ("not a | not b" to "Binary(|,Unary(not,Id(a)),Unary(not,Id(b)))"),

            // unary ~ of power (power higher than unary)
            "unary not of power remains unary-of-power" to
                ("~a ^ b" to "Unary(~,Binary(^,Id(a),Id(b)))"),
            "unary not of power under and" to
                ("~a ^ b & c" to "Binary(&,Unary(~,Binary(^,Id(a),Id(b))),Id(c))"),
            "unary not of power under full ladder" to
                ("~base ^ exp << 1 & mask ~ toggle | flag" to
                    "Binary(|,Binary(~,Binary(&,Binary(<<,Unary(~,Binary(^,Id(base),Id(exp))),Const(1)),Id(mask)),Id(toggle)),Id(flag))"),

            // parentheses force unary over binary groups
            "parentheses wrap and under unary not" to
                ("~(a & b)" to "Unary(~,Binary(&,Id(a),Id(b)))"),
            "parentheses wrap or under unary not" to
                ("~(a | b)" to "Unary(~,Binary(|,Id(a),Id(b)))"),
            "parentheses wrap xor under unary not" to
                ("~(a ~ b)" to "Unary(~,Binary(~,Id(a),Id(b)))"),
            "parentheses wrap shift under unary not" to
                ("~(a << b)" to "Unary(~,Binary(<<,Id(a),Id(b)))"),
            "parentheses wrap full ladder under unary not" to
                ("~(a | b ~ c & d << e)" to
                    "Unary(~,Binary(|,Id(a),Binary(~,Id(b),Binary(&,Id(c),Binary(<<,Id(d),Id(e))))))"),
            "unary not of parenthesized chain then outer and" to
                ("~(a | b) & c" to "Binary(&,Unary(~,Binary(|,Id(a),Id(b))),Id(c))"),
            "unary not of parenthesized chain then outer or" to
                ("~(a & b) | c" to "Binary(|,Unary(~,Binary(&,Id(a),Id(b))),Id(c))"),
            "chain of unary-not groups under or" to
                ("~(a & b) | ~(c & d) | ~(e << f)" to
                    "Binary(|,Binary(|,Unary(~,Binary(&,Id(a),Id(b))),Unary(~,Binary(&,Id(c),Id(d)))),Unary(~,Binary(<<,Id(e),Id(f))))")
        )
    }

    @Test
    fun documentsParenthesesOverrideBitwiseChainShapes() {
        assertExpressionShapes(
            "parentheses force or before and" to
                ("(a | b) & c" to "Binary(&,Binary(|,Id(a),Id(b)),Id(c))"),
            "parentheses force or before xor" to
                ("(a | b) ~ c" to "Binary(~,Binary(|,Id(a),Id(b)),Id(c))"),
            "parentheses force xor before and" to
                ("(a ~ b) & c" to "Binary(&,Binary(~,Id(a),Id(b)),Id(c))"),
            "parentheses force and before shift" to
                ("(a & b) << c" to "Binary(<<,Binary(&,Id(a),Id(b)),Id(c))"),
            "parentheses force or before shift" to
                ("(a | b) >> c" to "Binary(>>,Binary(|,Id(a),Id(b)),Id(c))"),
            "parentheses force right-assoc-looking and chain" to
                ("a & (b & c)" to "Binary(&,Id(a),Binary(&,Id(b),Id(c)))"),
            "parentheses force right-assoc-looking or chain" to
                ("a | (b | c)" to "Binary(|,Id(a),Binary(|,Id(b),Id(c)))"),
            "parentheses force right-assoc-looking xor chain" to
                ("a ~ (b ~ c)" to "Binary(~,Id(a),Binary(~,Id(b),Id(c)))"),
            "parentheses force right-assoc-looking shift chain" to
                ("a << (b << c)" to "Binary(<<,Id(a),Binary(<<,Id(b),Id(c)))"),
            "nested parentheses flip full ladder" to
                ("((a | b) ~ c) & (d << e)" to
                    "Binary(&,Binary(~,Binary(|,Id(a),Id(b)),Id(c)),Binary(<<,Id(d),Id(e)))"),
            "multi-level parentheses on four-term and" to
                ("(a & (b & (c & d)))" to
                    "Binary(&,Id(a),Binary(&,Id(b),Binary(&,Id(c),Id(d))))"),
            "mixed paren override in long or-and-shift chain" to
                ("(a | b) & (c | d) << e" to
                    "Binary(&,Binary(|,Id(a),Id(b)),Binary(<<,Binary(|,Id(c),Id(d)),Id(e)))")
        )
    }

    @Test
    fun documentsBitwiseChainsAgainstArithmeticConcatComparisonsAndLogical() {
        assertExpressionShapes(
            // arithmetic higher than shifts
            "addition under left shift on right" to
                ("a << b + c" to "Binary(<<,Id(a),Binary(+,Id(b),Id(c)))"),
            "addition under left shift on left" to
                ("a + b << c" to "Binary(<<,Binary(+,Id(a),Id(b)),Id(c))"),
            "subtraction under right shift" to
                ("a - b >> c" to "Binary(>>,Binary(-,Id(a),Id(b)),Id(c))"),
            "multiplication under and" to
                ("a * b & c" to "Binary(&,Binary(*,Id(a),Id(b)),Id(c))"),
            "floor division under or" to
                ("a // b | c" to "Binary(|,Binary(//,Id(a),Id(b)),Id(c))"),
            "additive chain under shift chain" to
                ("a + b - c << d >> e" to
                    "Binary(>>,Binary(<<,Binary(-,Binary(+,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // concat higher than shifts (Lua 5.3: .. above << >>)
            "concat binds tighter than shift on the right" to
                ("a << b .. c" to "Binary(<<,Id(a),Binary(..,Id(b),Id(c)))"),
            "concat binds tighter than shift on the left" to
                ("a .. b >> c" to "Binary(>>,Binary(..,Id(a),Id(b)),Id(c))"),
            "concat chains under shift" to
                ("a .. b .. c << d" to
                    "Binary(<<,Binary(..,Id(a),Binary(..,Id(b),Id(c))),Id(d))"),
            "shift between concats groups as shift of concats" to
                ("prefix .. a << b .. suffix" to
                    "Binary(<<,Binary(..,Id(prefix),Id(a)),Binary(..,Id(b),Id(suffix)))"),

            // comparisons lower than bitwise or
            "comparison after or chain" to
                ("a | b | c < d" to "Binary(<,Binary(|,Binary(|,Id(a),Id(b)),Id(c)),Id(d))"),
            "equality after and chain" to
                ("a & b & c == d" to "Binary(==,Binary(&,Binary(&,Id(a),Id(b)),Id(c)),Id(d))"),
            "not-equal after xor chain" to
                ("a ~ b ~ c ~= d" to "Binary(~=,Binary(~,Binary(~,Id(a),Id(b)),Id(c)),Id(d))"),
            "less-equal after shift chain" to
                ("a << b << c <= d" to "Binary(<=,Binary(<<,Binary(<<,Id(a),Id(b)),Id(c)),Id(d))"),
            "greater-equal after mixed shift chain" to
                ("a >> b << c >= d" to "Binary(>=,Binary(<<,Binary(>>,Id(a),Id(b)),Id(c)),Id(d))"),

            // logical lower than bitwise
            "and after or chain" to
                ("a | b | c and d" to "Binary(and,Binary(|,Binary(|,Id(a),Id(b)),Id(c)),Id(d))"),
            "or after and chain" to
                ("a & b & c or d" to "Binary(or,Binary(&,Binary(&,Id(a),Id(b)),Id(c)),Id(d))"),
            "logical wrap of full bitwise ladder" to
                ("ready and a << b & c ~ d | e or fallback" to
                    "Binary(or,Binary(and,Id(ready),Binary(|,Binary(~,Binary(&,Binary(<<,Id(a),Id(b)),Id(c)),Id(d)),Id(e))),Id(fallback))")
        )
    }

    @Test
    fun documentsPrefixBasesInsideBitwiseChains() {
        assertExpressionShapes(
            "member fields in or chain" to
                ("bits.hi | bits.mid | bits.lo" to
                    "Binary(|,Binary(|,Member(Id(bits).hi),Member(Id(bits).mid)),Member(Id(bits).lo))"),
            "index expressions in and chain" to
                ("masks[i] & masks[j] & masks[k]" to
                    "Binary(&,Binary(&,Index(Id(masks)[Id(i)]),Index(Id(masks)[Id(j)])),Index(Id(masks)[Id(k)]))"),
            "method call base in and-or chain" to
                ("object:bits() & mask | 1" to
                    "Binary(|,Binary(&,Call(Member(Id(object):bits):),Id(mask)),Const(1))"),
            "call argument preserves long ladder" to
                ("pack(a | b ~ c & d << e << f)" to
                    "Call(Id(pack):Binary(|,Id(a),Binary(~,Id(b),Binary(&,Id(c),Binary(<<,Binary(<<,Id(d),Id(e)),Id(f))))))"),
            "table constructor fields with chains" to
                ("{ a & b & c, d | e | f, g << h << i }" to
                    "Table(TableKey(Const(1)=Binary(&,Binary(&,Id(a),Id(b)),Id(c))),TableKey(Const(2)=Binary(|,Binary(|,Id(d),Id(e)),Id(f))),TableKey(Const(3)=Binary(<<,Binary(<<,Id(g),Id(h)),Id(i))))"),
            "chained member under shift then or" to
                ("module.bits.hi << 8 | module.bits.lo" to
                    "Binary(|,Binary(<<,Member(Member(Id(module).bits).hi),Const(8)),Member(Member(Id(module).bits).lo))"),
            "index of call under and chain" to
                ("fetch()[1] & fetch()[2] & mask" to
                    "Binary(&,Binary(&,Index(Call(Id(fetch):)[Const(1)]),Index(Call(Id(fetch):)[Const(2)])),Id(mask))"),
            "no-space dense ladder still chains" to
                ("a~b|c&d<<e" to
                    "Binary(|,Binary(~,Id(a),Id(b)),Binary(&,Id(c),Binary(<<,Id(d),Id(e))))")
        )
    }

    @Test
    fun exposesBitwiseChainAstOperatorsAndLeftSpine() {
        val andChain = assertIs<BinaryExpression>(parseExpression("a & b & c & d"))
        assertEquals(ExpressionOperator.BIT_AND, andChain.operator)
        assertEquals("Id(d)", renderShape(andChain.right!!))
        val and3 = assertIs<BinaryExpression>(andChain.left)
        assertEquals(ExpressionOperator.BIT_AND, and3.operator)
        assertEquals("Id(c)", renderShape(and3.right!!))
        val and2 = assertIs<BinaryExpression>(and3.left)
        assertEquals(ExpressionOperator.BIT_AND, and2.operator)
        assertEquals("Id(a)", renderShape(and2.left!!))
        assertEquals("Id(b)", renderShape(and2.right!!))

        val orChain = assertIs<BinaryExpression>(parseExpression("a | b | c"))
        assertEquals(ExpressionOperator.BIT_OR, orChain.operator)
        val orLeft = assertIs<BinaryExpression>(orChain.left)
        assertEquals(ExpressionOperator.BIT_OR, orLeft.operator)
        assertEquals("Id(a)", renderShape(orLeft.left!!))
        assertEquals("Id(b)", renderShape(orLeft.right!!))
        assertEquals("Id(c)", renderShape(orChain.right!!))

        val xorChain = assertIs<BinaryExpression>(parseExpression("a ~ b ~ c"))
        assertEquals(ExpressionOperator.BIT_TILDE, xorChain.operator)
        val xorLeft = assertIs<BinaryExpression>(xorChain.left)
        assertEquals(ExpressionOperator.BIT_TILDE, xorLeft.operator)

        val shiftChain = assertIs<BinaryExpression>(parseExpression("a << b >> c << d"))
        assertEquals(ExpressionOperator.BIT_LT, shiftChain.operator)
        assertEquals("Id(d)", renderShape(shiftChain.right!!))
        val mid = assertIs<BinaryExpression>(shiftChain.left)
        assertEquals(ExpressionOperator.BIT_GT, mid.operator)
        assertEquals("Id(c)", renderShape(mid.right!!))
        val first = assertIs<BinaryExpression>(mid.left)
        assertEquals(ExpressionOperator.BIT_LT, first.operator)
        assertEquals("Id(a)", renderShape(first.left!!))
        assertEquals("Id(b)", renderShape(first.right!!))

        val ladder = assertIs<BinaryExpression>(parseExpression("a << b & c ~ d | e"))
        assertEquals(ExpressionOperator.BIT_OR, ladder.operator)
        assertEquals("Id(e)", renderShape(ladder.right!!))
        val xor = assertIs<BinaryExpression>(ladder.left)
        assertEquals(ExpressionOperator.BIT_TILDE, xor.operator)
        assertEquals("Id(d)", renderShape(xor.right!!))
        val and = assertIs<BinaryExpression>(xor.left)
        assertEquals(ExpressionOperator.BIT_AND, and.operator)
        assertEquals("Id(c)", renderShape(and.right!!))
        val shift = assertIs<BinaryExpression>(and.left)
        assertEquals(ExpressionOperator.BIT_LT, shift.operator)
        assertEquals("Id(a)", renderShape(shift.left!!))
        assertEquals("Id(b)", renderShape(shift.right!!))
    }

    @Test
    fun exposesUnaryBitwiseNotAstInChainContexts() {
        val unary = assertIs<UnaryExpression>(parseExpression("~flags"))
        assertEquals(ExpressionOperator.BIT_TILDE, unary.operator)
        assertIs<Identifier>(unary.arg)
        assertEquals("flags", (unary.arg as Identifier).name)

        val nested = assertIs<UnaryExpression>(parseExpression("~~~x"))
        assertEquals(ExpressionOperator.BIT_TILDE, nested.operator)
        val mid = assertIs<UnaryExpression>(nested.arg)
        assertEquals(ExpressionOperator.BIT_TILDE, mid.operator)
        val inner = assertIs<UnaryExpression>(mid.arg)
        assertEquals(ExpressionOperator.BIT_TILDE, inner.operator)
        assertEquals("Id(x)", renderShape(inner.arg))

        val unaryAnd = assertIs<BinaryExpression>(parseExpression("~a & ~b & ~c"))
        assertEquals(ExpressionOperator.BIT_AND, unaryAnd.operator)
        assertEquals("Unary(~,Id(c))", renderShape(unaryAnd.right!!))
        val leftAnd = assertIs<BinaryExpression>(unaryAnd.left)
        assertEquals("Unary(~,Id(a))", renderShape(leftAnd.left!!))
        assertEquals("Unary(~,Id(b))", renderShape(leftAnd.right!!))

        val parenUnary = assertIs<UnaryExpression>(parseExpression("~(a | b ~ c)"))
        assertEquals(ExpressionOperator.BIT_TILDE, parenUnary.operator)
        val group = assertIs<BinaryExpression>(parenUnary.arg)
        assertEquals(ExpressionOperator.BIT_OR, group.operator)
        assertEquals("Id(a)", renderShape(group.left!!))
        val xor = assertIs<BinaryExpression>(group.right)
        assertEquals(ExpressionOperator.BIT_TILDE, xor.operator)

        val unaryPower = assertIs<UnaryExpression>(parseExpression("~a ^ b"))
        assertEquals(ExpressionOperator.BIT_TILDE, unaryPower.operator)
        val power = assertIs<BinaryExpression>(unaryPower.arg)
        assertEquals(ExpressionOperator.BIT_EXP, power.operator)
    }

    @Test
    fun roundTripsBitwiseChainsWithStableShapes() {
        val samples = listOf(
            "return a & b & c & d",
            "return a | b | c | d | e",
            "return a ~ b ~ c ~ d",
            "return a << b << c << d",
            "return a >> b >> c >> d",
            "return a << b >> c << d >> e",
            "return a & b & c ~ d ~ e | f | g",
            "return a | b ~ c & d << e",
            "return a << b & c ~ d | e",
            "return a | b | c ~ d ~ e & f & g << h << i",
            "return a << b << c & d & e ~ f ~ g | h | i",
            "return ~a & ~b & ~c",
            "return ~a | ~b | ~c",
            "return ~a << ~b << ~c",
            "return ~~a & ~~b | ~~c",
            "return ~~~x",
            "return ~(a | b ~ c & d << e)",
            "return ~(a & b) | ~(c & d)",
            "return (a | b) & c",
            "return a & (b & c)",
            "return a << (b << c)",
            "return ((a | b) ~ c) & (d << e)",
            "return a + b - c << d >> e",
            "return prefix .. a << b .. suffix",
            "return a | b | c < d",
            "return ready and a << b & c ~ d | e or fallback",
            "return bits.hi | bits.mid | bits.lo",
            "return masks[i] & masks[j] & masks[k]",
            "return pack(a | b ~ c & d << e << f)",
            "return a~b|c&d<<e",
            "return ~base ^ exp << 1 & mask ~ toggle | flag",
            "return 0xFF << 4 >> 2 << 1",
            "return value << 1 >> 2 << 3"
        )

        val failures = samples.mapNotNull { source ->
            runCatching {
                val initial = LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(source)
                val printed = printer.asCode(initial)
                val reparsed = LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(printed)

                assertEquals(
                    renderShape(initial),
                    renderShape(reparsed),
                    "shape mismatch after print for <$source>\nprinted:\n$printed"
                )

                BITWISE_SURFACE_MARKERS.filter { marker -> source.contains(marker) }.forEach { marker ->
                    assertTrue(
                        printed.contains(marker),
                        "Printed code for <$source> lost operator marker <$marker>:\n$printed"
                    )
                }
            }.exceptionOrNull()?.let { failure ->
                "$source\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    @Test
    fun parsesBitwiseChainCorpusAsSuccessfulChunkBodies() {
        val sources = listOf(
            "local mask = a & b & c & d",
            "local flags = ~ready & mask | toggle | extra",
            "return a | b | c ~ d & e << f << g, a << b >> c << d",
            "if flags & MASK & EXTRA ~= 0 then return true end",
            "while ready and bits << 1 << 2 | 1 do break end",
            "for i = 1, limit do total = total | (1 << i << 0) end",
            "function pack(a, b, c) return (a & 0xFF) | (b << 8) | (c << 16) end",
            "local t = { a & b & c, d | e | f, ~g, h << i << j }",
            "repeat x = x >> 1 >> 0 until x & 1 & 1 == 0",
            "do local n = ~~seed | 1 | 2; return n & mask & 0xFF end",
            "local function unpack(v) return v & 0xFF, v >> 8 & 0xFF, v >> 16 & 0xFF end",
            "return (a | b | c) << (d + e) | f ~ g & h << i"
        )

        sources.forEach { source ->
            val chunk = parse(LuaVersion.LUA_5_3, source)
            assertTrue(
                chunk.body.statements.isNotEmpty() || chunk.body.returnStatement != null,
                "Expected non-empty chunk body for: $source"
            )
            assertTrue(renderShape(chunk).isNotBlank())
        }
    }

    @Test
    fun documentsBitwiseChainsInStatementContextsWithShapes() {
        val localAndChain = parse(LuaVersion.LUA_5_3, "local mask = a & b & c & d")
        assertEquals(
            "Chunk(Block[Local(Id(mask)=Binary(&,Binary(&,Binary(&,Id(a),Id(b)),Id(c)),Id(d)))])",
            renderShape(localAndChain)
        )

        val ifShiftChain = parse(LuaVersion.LUA_5_3, "if flags << 1 >> 2 & MASK ~= 0 then return true end")
        assertEquals(
            "Chunk(Block[If(Clause(Binary(~=,Binary(&,Binary(>>,Binary(<<,Id(flags),Const(1)),Const(2)),Id(MASK)),Const(0)):Block[Return(Const(true))]))])",
            renderShape(ifShiftChain)
        )

        val assignOrChain = parse(LuaVersion.LUA_5_3, "total = total | (1 << i) | (1 << j)")
        assertEquals(
            "Chunk(Block[Assign(Id(total)=Binary(|,Binary(|,Id(total),Binary(<<,Const(1),Id(i))),Binary(<<,Const(1),Id(j))))])",
            renderShape(assignOrChain)
        )

        val functionPack = parse(
            LuaVersion.LUA_5_3,
            "function pack(a, b, c) return (a & 0xFF) | (b << 8) | (c << 16) end"
        )
        assertEquals(
            "Chunk(Block[Function(Id(pack),Block[Return(Binary(|,Binary(|,Binary(&,Id(a),Const(0xFF)),Binary(<<,Id(b),Const(8))),Binary(<<,Id(c),Const(16))))])])",
            renderShape(functionPack)
        )

        val unaryLocal = parse(LuaVersion.LUA_5_3, "local n = ~~seed & mask | toggle")
        assertEquals(
            "Chunk(Block[Local(Id(n)=Binary(|,Binary(&,Unary(~,Unary(~,Id(seed))),Id(mask)),Id(toggle)))])",
            renderShape(unaryLocal)
        )
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
