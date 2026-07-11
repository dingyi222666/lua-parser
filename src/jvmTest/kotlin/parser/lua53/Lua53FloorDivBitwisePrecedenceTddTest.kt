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
 * Focused Lua 5.3 floor-division (//) and bitwise precedence corpus (TASK-253).
 *
 * Documented Lua 5.3 operator precedence (high → low), relevant slice:
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
 * This corpus pins AST shapes for mixed // with arithmetic, shifts, bitwise,
 * relational and logical operators. Test-only; production defects surface as
 * assertion failures (no production edits here).
 */
class Lua53FloorDivBitwisePrecedenceTddTest {

    private val printer = AST2Lua()

    @Test
    fun documentsFloorDivisionRelativeToArithmeticAndBitwise() {
        assertExpressionShapes(
            // // is multiplicative: same band as * / %
            "floor division binds before addition on the right" to
                ("a + b // c" to "Binary(+,Id(a),Binary(//,Id(b),Id(c)))"),
            "floor division binds before addition on the left" to
                ("a // b + c" to "Binary(+,Binary(//,Id(a),Id(b)),Id(c))"),
            "floor division binds before subtraction on the right" to
                ("a - b // c" to "Binary(-,Id(a),Binary(//,Id(b),Id(c)))"),
            "floor division binds before subtraction on the left" to
                ("a // b - c" to "Binary(-,Binary(//,Id(a),Id(b)),Id(c))"),
            "floor division associates left with multiplication" to
                ("a * b // c" to "Binary(//,Binary(*,Id(a),Id(b)),Id(c))"),
            "floor division associates left with division" to
                ("a // b / c" to "Binary(/,Binary(//,Id(a),Id(b)),Id(c))"),
            "floor division associates left with modulo" to
                ("a // b % c" to "Binary(%,Binary(//,Id(a),Id(b)),Id(c))"),
            "multiplicative chain with floor division associates left" to
                ("a * b // c / d % e" to "Binary(%,Binary(/,Binary(//,Binary(*,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),
            "floor division binds below power" to
                ("a // b ^ c" to "Binary(//,Id(a),Binary(^,Id(b),Id(c)))"),
            "power then floor division left-associates at mul level" to
                ("a ^ b // c" to "Binary(//,Binary(^,Id(a),Id(b)),Id(c))"),
            "unary minus then floor division" to
                ("-a // b" to "Binary(//,Unary(-,Id(a)),Id(b))"),
            "unary length then floor division" to
                ("#a // b" to "Binary(//,Unary(#,Id(a)),Id(b))"),

            // // relative to shifts (mul > shift)
            "left shift binds below floor division on the right" to
                ("a << b // c" to "Binary(<<,Id(a),Binary(//,Id(b),Id(c)))"),
            "left shift binds below floor division on the left" to
                ("a // b << c" to "Binary(<<,Binary(//,Id(a),Id(b)),Id(c))"),
            "right shift binds below floor division on the right" to
                ("a >> b // c" to "Binary(>>,Id(a),Binary(//,Id(b),Id(c)))"),
            "right shift binds below floor division on the left" to
                ("a // b >> c" to "Binary(>>,Binary(//,Id(a),Id(b)),Id(c))"),
            "mixed shifts around floor division nest left" to
                ("a // b << c >> d" to "Binary(>>,Binary(<<,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"),

            // // relative to bitwise & ~ |
            "bitwise and binds below floor division on the right" to
                ("a & b // c" to "Binary(&,Id(a),Binary(//,Id(b),Id(c)))"),
            "bitwise and binds below floor division on the left" to
                ("a // b & c" to "Binary(&,Binary(//,Id(a),Id(b)),Id(c))"),
            "bitwise xor binds below floor division on the right" to
                ("a ~ b // c" to "Binary(~,Id(a),Binary(//,Id(b),Id(c)))"),
            "bitwise xor binds below floor division on the left" to
                ("a // b ~ c" to "Binary(~,Binary(//,Id(a),Id(b)),Id(c))"),
            "bitwise or binds below floor division on the right" to
                ("a | b // c" to "Binary(|,Id(a),Binary(//,Id(b),Id(c)))"),
            "bitwise or binds below floor division on the left" to
                ("a // b | c" to "Binary(|,Binary(//,Id(a),Id(b)),Id(c))"),
            "full ladder: floor-div then shift then and then xor then or" to
                ("a // b << c & d ~ e | f" to
                    "Binary(|,Binary(~,Binary(&,Binary(<<,Binary(//,Id(a),Id(b)),Id(c)),Id(d)),Id(e)),Id(f))"),
            "full ladder reverse surface order" to
                ("a | b ~ c & d << e // f" to
                    "Binary(|,Id(a),Binary(~,Id(b),Binary(&,Id(c),Binary(<<,Id(d),Binary(//,Id(e),Id(f))))))"),

            // // relative to concat (mul > concat > shift)
            "concat binds below floor division on the right" to
                ("a .. b // c" to "Binary(..,Id(a),Binary(//,Id(b),Id(c)))"),
            "concat binds below floor division on the left" to
                ("a // b .. c" to "Binary(..,Binary(//,Id(a),Id(b)),Id(c))"),
            // .. higher than <<, so: (a // b) << (c .. d)
            "floor-div shift with concat on right groups as shift of floor-div and concat" to
                ("a // b << c .. d" to "Binary(<<,Binary(//,Id(a),Id(b)),Binary(..,Id(c),Id(d)))"),
            "concat of floor-div then shift groups as shift of concat" to
                ("a // b .. c << d" to "Binary(<<,Binary(..,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"),

            // // relative to comparisons / logical
            "comparison binds below floor division" to
                ("a // b < c" to "Binary(<,Binary(//,Id(a),Id(b)),Id(c))"),
            "greater-equal after floor division" to
                ("a // b >= c" to "Binary(>=,Binary(//,Id(a),Id(b)),Id(c))"),
            "equality after floor-div and bitwise and" to
                ("a // b & c == d" to "Binary(==,Binary(&,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"),
            "not-equal after floor-div xor" to
                ("a // b ~ c ~= d" to "Binary(~=,Binary(~,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"),
            "and binds below floor-div comparison" to
                ("a // b < c and d" to "Binary(and,Binary(<,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"),
            "or binds below and of floor-div bitwise" to
                ("a // b | c and d or e" to
                    "Binary(or,Binary(and,Binary(|,Binary(//,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // unary bitwise not around floor division
            "unary bitwise not binds above floor division" to
                ("~a // b" to "Binary(//,Unary(~,Id(a)),Id(b))"),
            "unary bitwise not of floor-div group" to
                ("~(a // b)" to "Unary(~,Binary(//,Id(a),Id(b)))"),
            "unary not keyword then floor division comparison" to
                ("not a // b < c" to "Binary(<,Binary(//,Unary(not,Id(a)),Id(b)),Id(c))"),

            // parentheses override
            "parentheses force floor-div after addition" to
                ("(a + b) // c" to "Binary(//,Binary(+,Id(a),Id(b)),Id(c))"),
            "parentheses force floor-div after bitwise or" to
                ("(a | b) // c" to "Binary(//,Binary(|,Id(a),Id(b)),Id(c))"),
            "parentheses force shift before floor division" to
                ("a // (b << c)" to "Binary(//,Id(a),Binary(<<,Id(b),Id(c)))"),
            "parentheses force bitwise and before floor division" to
                ("a // (b & c)" to "Binary(//,Id(a),Binary(&,Id(b),Id(c)))")
        )
    }

    @Test
    fun parsesMixedFloorDivBitwiseShapesWithStableNesting() {
        assertExpressionShapes(
            "mixed: floor-div of sum then left shift then or" to (
                "(a + b) // 2 << 3 | mask" to
                    "Binary(|,Binary(<<,Binary(//,Binary(+,Id(a),Id(b)),Const(2)),Const(3)),Id(mask))"
                ),
            "mixed: floor-div then and then comparison" to (
                "width // cell & mask ~= 0" to
                    "Binary(~=,Binary(&,Binary(//,Id(width),Id(cell)),Id(mask)),Const(0))"
                ),
            "mixed: unary not floor-div then xor then or" to (
                "~n // 2 ~ flag | 1" to
                    "Binary(|,Binary(~,Binary(//,Unary(~,Id(n)),Const(2)),Id(flag)),Const(1))"
                ),
            "mixed: hex floor-div through full bitwise ladder" to (
                "0xFF // 0x10 << 2 & 0x0F ~ 0x01 | 0x02" to
                    "Binary(|,Binary(~,Binary(&,Binary(<<,Binary(//,Const(0xFF),Const(0x10)),Const(2)),Const(0x0F)),Const(0x01)),Const(0x02))"
                ),
            "mixed: index as floor-div right operand under shift" to (
                "value // sizes[i] << offset" to
                    "Binary(<<,Binary(//,Id(value),Index(Id(sizes)[Id(i)])),Id(offset))"
                ),
            "mixed: member field floor-div under bitwise and" to (
                "cfg.step // 4 & mask" to
                    "Binary(&,Binary(//,Member(Id(cfg).step),Const(4)),Id(mask))"
                ),
            "mixed: method call floor-div then or" to (
                "object:count() // 2 | 1" to
                    "Binary(|,Binary(//,Call(Member(Id(object):count):),Const(2)),Const(1))"
                ),
            "mixed: call argument preserves floor-div bitwise ladder" to (
                "pack(a // b << c & d | e)" to
                    "Call(Id(pack):Binary(|,Binary(&,Binary(<<,Binary(//,Id(a),Id(b)),Id(c)),Id(d)),Id(e)))"
                ),
            "mixed: table field with floor-div and shift" to (
                "{ a // b, c << 1 | d // e }" to
                    "Table(TableKey(Const(1)=Binary(//,Id(a),Id(b))),TableKey(Const(2)=Binary(|,Binary(<<,Id(c),Const(1)),Binary(//,Id(d),Id(e)))))"
                ),
            "mixed: multi-level parentheses preserve grouping" to (
                "((a // b) | c) << (d // e)" to
                    "Binary(<<,Binary(|,Binary(//,Id(a),Id(b)),Id(c)),Binary(//,Id(d),Id(e)))"
                ),
            "mixed: power inside floor-div then bitwise and" to (
                "base ^ exp // scale & mask" to
                    "Binary(&,Binary(//,Binary(^,Id(base),Id(exp)),Id(scale)),Id(mask))"
                ),
            "mixed: concat of floor-div groups under shift" to (
                "prefix // 2 .. mid // 3 << shift" to
                    "Binary(<<,Binary(..,Binary(//,Id(prefix),Const(2)),Binary(//,Id(mid),Const(3))),Id(shift))"
                ),
            "mixed: logical guard around floor-div bitwise or" to (
                "ready and n // 2 | flag or fallback" to
                    "Binary(or,Binary(and,Id(ready),Binary(|,Binary(//,Id(n),Const(2)),Id(flag))),Id(fallback))"
                ),
            "mixed: floor-div without spaces then bitwise or" to (
                "a//b|c" to
                    "Binary(|,Binary(//,Id(a),Id(b)),Id(c))"
                ),
            "mixed: chained floor-div left-assoc under and" to (
                "a // b // c & d" to
                    "Binary(&,Binary(//,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"
                )
        )
    }

    @Test
    fun exposesFloorDivAndBitwiseAstOperators() {
        val expression = assertIs<BinaryExpression>(parseExpression("a // b << c & d ~ e | f"))
        assertEquals(ExpressionOperator.BIT_OR, expression.operator)
        assertEquals("Id(f)", renderShape(expression.right!!))

        val xor = assertIs<BinaryExpression>(expression.left)
        assertEquals(ExpressionOperator.BIT_TILDE, xor.operator)
        assertEquals("Id(e)", renderShape(xor.right!!))

        val and = assertIs<BinaryExpression>(xor.left)
        assertEquals(ExpressionOperator.BIT_AND, and.operator)
        assertEquals("Id(d)", renderShape(and.right!!))

        val shift = assertIs<BinaryExpression>(and.left)
        assertEquals(ExpressionOperator.BIT_LT, shift.operator)
        assertEquals("Id(c)", renderShape(shift.right!!))

        val floorDiv = assertIs<BinaryExpression>(shift.left)
        assertEquals(ExpressionOperator.DOUBLE_DIV, floorDiv.operator)
        assertEquals("Id(a)", renderShape(floorDiv.left!!))
        assertEquals("Id(b)", renderShape(floorDiv.right!!))

        val leftAssoc = assertIs<BinaryExpression>(parseExpression("a // b // c"))
        assertEquals(ExpressionOperator.DOUBLE_DIV, leftAssoc.operator)
        val inner = assertIs<BinaryExpression>(leftAssoc.left)
        assertEquals(ExpressionOperator.DOUBLE_DIV, inner.operator)
        assertEquals("Id(a)", renderShape(inner.left!!))
        assertEquals("Id(b)", renderShape(inner.right!!))
        assertEquals("Id(c)", renderShape(leftAssoc.right!!))

        val unaryThenFloor = assertIs<BinaryExpression>(parseExpression("~a // 2"))
        assertEquals(ExpressionOperator.DOUBLE_DIV, unaryThenFloor.operator)
        val unary = assertIs<UnaryExpression>(unaryThenFloor.left)
        assertEquals(ExpressionOperator.BIT_TILDE, unary.operator)
        assertEquals("Id(a)", renderShape(unary.arg))
        assertEquals("Const(2)", renderShape(unaryThenFloor.right!!))
    }

    @Test
    fun exposesLiteralOperandsOnFloorDivBitwiseExpressions() {
        val expression = assertIs<BinaryExpression>(parseExpression("0xFF // 16 << 2 | 1"))
        assertEquals(ExpressionOperator.BIT_OR, expression.operator)

        val shift = assertIs<BinaryExpression>(expression.left)
        assertEquals(ExpressionOperator.BIT_LT, shift.operator)
        val floorDiv = assertIs<BinaryExpression>(shift.left)
        assertEquals(ExpressionOperator.DOUBLE_DIV, floorDiv.operator)
        assertEquals("0xFF", assertIs<ConstantNode>(floorDiv.left).rawValue.toString())
        assertEquals("16", assertIs<ConstantNode>(floorDiv.right).rawValue.toString())
        assertEquals("2", assertIs<ConstantNode>(shift.right).rawValue.toString())
        assertEquals("1", assertIs<ConstantNode>(expression.right).rawValue.toString())

        val comparison = assertIs<BinaryExpression>(parseExpression("n // 2 & 0x0F == 0"))
        assertEquals(ExpressionOperator.EQ, comparison.operator)
        val and = assertIs<BinaryExpression>(comparison.left)
        assertEquals(ExpressionOperator.BIT_AND, and.operator)
        val nFloor = assertIs<BinaryExpression>(and.left)
        assertEquals(ExpressionOperator.DOUBLE_DIV, nFloor.operator)
        assertIs<Identifier>(nFloor.left)
        assertEquals("n", (nFloor.left as Identifier).name)
    }

    @Test
    fun roundTripsFloorDivBitwiseMixesWithStableShapes() {
        val samples = listOf(
            "return a // b",
            "return a // b // c",
            "return a + b // c",
            "return a // b + c",
            "return a // b << c",
            "return a << b // c",
            "return a // b >> c",
            "return a // b & c",
            "return a // b ~ c",
            "return a // b | c",
            "return a // b << c & d ~ e | f",
            "return a | b ~ c & d << e // f",
            "return ~a // b",
            "return ~(a // b)",
            "return (a + b) // c",
            "return (a | b) // c",
            "return a // (b << c)",
            "return a // b < c",
            "return a // b & c == d",
            "return a // b | c and d or e",
            "return (a + b) // 2 << 3 | mask",
            "return width // cell & mask ~= 0",
            "return 0xFF // 0x10 << 2 & 0x0F ~ 0x01 | 0x02",
            "return value // sizes[i] << offset",
            "return cfg.step // 4 & mask",
            "return object:count() // 2 | 1",
            "return pack(a // b << c & d | e)",
            "return ((a // b) | c) << (d // e)",
            "return base ^ exp // scale & mask",
            "return ready and n // 2 | flag or fallback",
            "return a//b|c",
            "return a // b // c & d",
            "return a // b .. c << d",
            "return a // b << c .. d"
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

                FLOOR_DIV_BITWISE_MARKERS.filter { marker -> source.contains(marker) }.forEach { marker ->
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
    fun parsesFloorDivBitwiseCorpusAsSuccessfulChunkBodies() {
        val sources = listOf(
            "local q = n // 2",
            "local packed = (a // b) << 4 | (c // d)",
            "return a // b << c & d ~ e | f, a // b // c",
            "if n // step & MASK ~= 0 then return true end",
            "while ready and n // 2 | 1 do break end",
            "for i = 1, limit // step do total = total | (1 << i) end",
            "function scale(a, b) return (a // b) | (b << 8) end",
            "local t = { a // b, c | d // e, ~f // g, h << i // j }",
            "repeat x = x // 2 until x & 1 == 0",
            "do local n = ~~seed // 2 | 1; return n & mask end",
            "local function unpack(v) return v // 256, v & 0xFF end",
            "return (a // b) << (c + d) | e ~ f & g // h"
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
    fun documentsFloorDivBitwiseInStatementContextsWithShapes() {
        val localFloor = parse(LuaVersion.LUA_5_3, "local q = n // 2")
        assertEquals(
            "Chunk(Block[Local(Id(q)=Binary(//,Id(n),Const(2)))])",
            renderShape(localFloor)
        )

        val ifFloorBitwise = parse(LuaVersion.LUA_5_3, "if n // step & MASK ~= 0 then return true end")
        assertEquals(
            "Chunk(Block[If(Clause(Binary(~=,Binary(&,Binary(//,Id(n),Id(step)),Id(MASK)),Const(0)):Block[Return(Const(true))]))])",
            renderShape(ifFloorBitwise)
        )

        val assignMix = parse(LuaVersion.LUA_5_3, "total = total | (n // 2 << i)")
        assertEquals(
            "Chunk(Block[Assign(Id(total)=Binary(|,Id(total),Binary(<<,Binary(//,Id(n),Const(2)),Id(i))))])",
            renderShape(assignMix)
        )

        val functionScale = parse(
            LuaVersion.LUA_5_3,
            "function scale(a, b) return (a // b) | (b << 8) end"
        )
        assertEquals(
            "Chunk(Block[Function(Id(scale),Block[Return(Binary(|,Binary(//,Id(a),Id(b)),Binary(<<,Id(b),Const(8))))])])",
            renderShape(functionScale)
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
        val FLOOR_DIV_BITWISE_MARKERS = listOf("//", "&", "|", "<<", ">>", "~")
    }
}
