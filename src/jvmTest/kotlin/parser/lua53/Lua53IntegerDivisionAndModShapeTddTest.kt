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
 * Focused Lua 5.3 integer-division (`//`) and modulo (`%`) shape corpus (TASK-312).
 *
 * Complements [Lua53FloorDivBitwisePrecedenceTddTest] and
 * [Lua53ExpressionPrecedenceTddTest] by pinning deterministic AST shapes for the
 * multiplicative pair `//` / `%` with each other, with `*` `/`, and with nearby
 * unary/binary neighbors (power, unary, additive, concat, shifts, comparisons,
 * logical). Test-only; production defects surface as assertion failures.
 *
 * Documented Lua 5.3 precedence (high → low), relevant slice:
 *   power ^ (right-assoc)
 *   unary operators: not  #  -  ~
 *   multiplicative * / // %  (left-assoc)
 *   additive + -
 *   concat .. / shifts / bitwise / comparisons / and / or
 */
class Lua53IntegerDivisionAndModShapeTddTest {

    private val printer = AST2Lua()

    @Test
    fun documentsIntegerDivisionAndModHomogeneousChainsAssociateLeft() {
        assertExpressionShapes(
            // // chains
            "two-term floor division is flat binary" to
                ("a // b" to "Binary(//,Id(a),Id(b))"),
            "three-term floor division associates left" to
                ("a // b // c" to "Binary(//,Binary(//,Id(a),Id(b)),Id(c))"),
            "four-term floor division associates left" to
                ("a // b // c // d" to "Binary(//,Binary(//,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"),
            "five-term floor division associates left" to
                ("a // b // c // d // e" to
                    "Binary(//,Binary(//,Binary(//,Binary(//,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // % chains
            "two-term modulo is flat binary" to
                ("a % b" to "Binary(%,Id(a),Id(b))"),
            "three-term modulo associates left" to
                ("a % b % c" to "Binary(%,Binary(%,Id(a),Id(b)),Id(c))"),
            "four-term modulo associates left" to
                ("a % b % c % d" to "Binary(%,Binary(%,Binary(%,Id(a),Id(b)),Id(c)),Id(d))"),
            "five-term modulo associates left" to
                ("a % b % c % d % e" to
                    "Binary(%,Binary(%,Binary(%,Binary(%,Id(a),Id(b)),Id(c)),Id(d)),Id(e))")
        )
    }

    @Test
    fun documentsIntegerDivisionAndModMixWithMultiplicativeBand() {
        assertExpressionShapes(
            // // with %
            "floor division then modulo associates left" to
                ("a // b % c" to "Binary(%,Binary(//,Id(a),Id(b)),Id(c))"),
            "modulo then floor division associates left" to
                ("a % b // c" to "Binary(//,Binary(%,Id(a),Id(b)),Id(c))"),
            "alternating floor-div mod chain associates left" to
                ("a // b % c // d" to "Binary(//,Binary(%,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"),
            "alternating mod floor-div chain associates left" to
                ("a % b // c % d" to "Binary(%,Binary(//,Binary(%,Id(a),Id(b)),Id(c)),Id(d))"),
            "long mixed floor-div mod chain" to
                ("a // b % c // d % e" to
                    "Binary(%,Binary(//,Binary(%,Binary(//,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // with * /
            "multiply then floor division associates left" to
                ("a * b // c" to "Binary(//,Binary(*,Id(a),Id(b)),Id(c))"),
            "floor division then multiply associates left" to
                ("a // b * c" to "Binary(*,Binary(//,Id(a),Id(b)),Id(c))"),
            "division then floor division associates left" to
                ("a / b // c" to "Binary(//,Binary(/,Id(a),Id(b)),Id(c))"),
            "floor division then division associates left" to
                ("a // b / c" to "Binary(/,Binary(//,Id(a),Id(b)),Id(c))"),
            "multiply then modulo associates left" to
                ("a * b % c" to "Binary(%,Binary(*,Id(a),Id(b)),Id(c))"),
            "modulo then multiply associates left" to
                ("a % b * c" to "Binary(*,Binary(%,Id(a),Id(b)),Id(c))"),
            "division then modulo associates left" to
                ("a / b % c" to "Binary(%,Binary(/,Id(a),Id(b)),Id(c))"),
            "modulo then division associates left" to
                ("a % b / c" to "Binary(/,Binary(%,Id(a),Id(b)),Id(c))"),
            "full multiplicative mix associates left" to
                ("a * b // c / d % e" to
                    "Binary(%,Binary(/,Binary(//,Binary(*,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),
            "full multiplicative mix reverse surface order" to
                ("a % b / c // d * e" to
                    "Binary(*,Binary(//,Binary(/,Binary(%,Id(a),Id(b)),Id(c)),Id(d)),Id(e))"),

            // constants / hex
            "floor division of integers" to
                ("10 // 3" to "Binary(//,Const(10),Const(3))"),
            "modulo of integers" to
                ("10 % 3" to "Binary(%,Const(10),Const(3))"),
            "hex floor division then modulo" to
                ("0xFF // 0x10 % 0x0F" to "Binary(%,Binary(//,Const(0xFF),Const(0x10)),Const(0x0F))"),
            "no-space floor division and modulo" to
                ("a//b%c" to "Binary(%,Binary(//,Id(a),Id(b)),Id(c))")
        )
    }

    @Test
    fun documentsIntegerDivisionAndModRelativeToUnaryAndPowerNeighbors() {
        assertExpressionShapes(
            // power (higher, right-assoc)
            "floor division binds below power on the right" to
                ("a // b ^ c" to "Binary(//,Id(a),Binary(^,Id(b),Id(c)))"),
            "floor division binds below power on the left" to
                ("a ^ b // c" to "Binary(//,Binary(^,Id(a),Id(b)),Id(c))"),
            "modulo binds below power on the right" to
                ("a % b ^ c" to "Binary(%,Id(a),Binary(^,Id(b),Id(c)))"),
            "modulo binds below power on the left" to
                ("a ^ b % c" to "Binary(%,Binary(^,Id(a),Id(b)),Id(c))"),
            "right-assoc power under floor division" to
                ("a // b ^ c ^ d" to "Binary(//,Id(a),Binary(^,Id(b),Binary(^,Id(c),Id(d))))"),
            "power of floor-div group requires parentheses" to
                ("(a // b) ^ c" to "Binary(^,Binary(//,Id(a),Id(b)),Id(c))"),
            "power of modulo group requires parentheses" to
                ("(a % b) ^ c" to "Binary(^,Binary(%,Id(a),Id(b)),Id(c))"),

            // unary (higher than multiplicative)
            "unary minus then floor division" to
                ("-a // b" to "Binary(//,Unary(-,Id(a)),Id(b))"),
            "unary minus then modulo" to
                ("-a % b" to "Binary(%,Unary(-,Id(a)),Id(b))"),
            "unary length then floor division" to
                ("#a // b" to "Binary(//,Unary(#,Id(a)),Id(b))"),
            "unary length then modulo" to
                ("#a % b" to "Binary(%,Unary(#,Id(a)),Id(b))"),
            "unary bitwise not then floor division" to
                ("~a // b" to "Binary(//,Unary(~,Id(a)),Id(b))"),
            "unary bitwise not then modulo" to
                ("~a % b" to "Binary(%,Unary(~,Id(a)),Id(b))"),
            "unary not keyword then floor division" to
                ("not a // b" to "Binary(//,Unary(not,Id(a)),Id(b))"),
            "unary not keyword then modulo" to
                ("not a % b" to "Binary(%,Unary(not,Id(a)),Id(b))"),
            "unary of floor-div group" to
                ("-(a // b)" to "Unary(-,Binary(//,Id(a),Id(b)))"),
            "unary of modulo group" to
                ("-(a % b)" to "Unary(-,Binary(%,Id(a),Id(b)))"),
            "unary bitwise not of floor-div group" to
                ("~(a // b)" to "Unary(~,Binary(//,Id(a),Id(b)))"),
            "unary bitwise not of modulo group" to
                ("~(a % b)" to "Unary(~,Binary(%,Id(a),Id(b)))"),
            "double unary then floor division" to
                ("- -a // b" to "Binary(//,Unary(-,Unary(-,Id(a))),Id(b))"),
            "unary length of floor-div remains unary-of-group" to
                ("#(a // b)" to "Unary(#,Binary(//,Id(a),Id(b)))"),
            "power inside unary then floor division" to
                ("-a ^ b // c" to "Binary(//,Unary(-,Binary(^,Id(a),Id(b))),Id(c))"),
            "power inside unary then modulo" to
                ("-a ^ b % c" to "Binary(%,Unary(-,Binary(^,Id(a),Id(b))),Id(c))")
        )
    }

    @Test
    fun documentsIntegerDivisionAndModRelativeToBinaryNeighbors() {
        assertExpressionShapes(
            // additive (lower)
            "floor division binds before addition on the right" to
                ("a + b // c" to "Binary(+,Id(a),Binary(//,Id(b),Id(c)))"),
            "floor division binds before addition on the left" to
                ("a // b + c" to "Binary(+,Binary(//,Id(a),Id(b)),Id(c))"),
            "modulo binds before addition on the right" to
                ("a + b % c" to "Binary(+,Id(a),Binary(%,Id(b),Id(c)))"),
            "modulo binds before addition on the left" to
                ("a % b + c" to "Binary(+,Binary(%,Id(a),Id(b)),Id(c))"),
            "floor division binds before subtraction on the right" to
                ("a - b // c" to "Binary(-,Id(a),Binary(//,Id(b),Id(c)))"),
            "floor division binds before subtraction on the left" to
                ("a // b - c" to "Binary(-,Binary(//,Id(a),Id(b)),Id(c))"),
            "modulo binds before subtraction on the right" to
                ("a - b % c" to "Binary(-,Id(a),Binary(%,Id(b),Id(c)))"),
            "modulo binds before subtraction on the left" to
                ("a % b - c" to "Binary(-,Binary(%,Id(a),Id(b)),Id(c))"),
            "mixed additive around floor-div and mod" to
                ("a // b + c % d - e" to
                    "Binary(-,Binary(+,Binary(//,Id(a),Id(b)),Binary(%,Id(c),Id(d))),Id(e))"),

            // concat (lower than mul)
            "concat binds below floor division on the right" to
                ("a .. b // c" to "Binary(..,Id(a),Binary(//,Id(b),Id(c)))"),
            "concat binds below floor division on the left" to
                ("a // b .. c" to "Binary(..,Binary(//,Id(a),Id(b)),Id(c))"),
            "concat binds below modulo on the right" to
                ("a .. b % c" to "Binary(..,Id(a),Binary(%,Id(b),Id(c)))"),
            "concat binds below modulo on the left" to
                ("a % b .. c" to "Binary(..,Binary(%,Id(a),Id(b)),Id(c))"),
            "concat of floor-div and mod groups right" to
                ("a // b .. c % d" to "Binary(..,Binary(//,Id(a),Id(b)),Binary(%,Id(c),Id(d)))"),

            // shifts (lower than mul)
            "left shift binds below floor division on the right" to
                ("a << b // c" to "Binary(<<,Id(a),Binary(//,Id(b),Id(c)))"),
            "left shift binds below floor division on the left" to
                ("a // b << c" to "Binary(<<,Binary(//,Id(a),Id(b)),Id(c))"),
            "right shift binds below modulo on the right" to
                ("a >> b % c" to "Binary(>>,Id(a),Binary(%,Id(b),Id(c)))"),
            "right shift binds below modulo on the left" to
                ("a % b >> c" to "Binary(>>,Binary(%,Id(a),Id(b)),Id(c))"),
            "floor-div then shift then mod via parentheses" to
                ("(a // b) << (c % d)" to "Binary(<<,Binary(//,Id(a),Id(b)),Binary(%,Id(c),Id(d)))"),

            // comparisons / logical
            "comparison after floor division" to
                ("a // b < c" to "Binary(<,Binary(//,Id(a),Id(b)),Id(c))"),
            "comparison after modulo" to
                ("a % b >= c" to "Binary(>=,Binary(%,Id(a),Id(b)),Id(c))"),
            "equality after floor-div and mod mix" to
                ("a // b % c == d" to "Binary(==,Binary(%,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"),
            "not-equal after modulo then floor division" to
                ("a % b // c ~= 0" to "Binary(~=,Binary(//,Binary(%,Id(a),Id(b)),Id(c)),Const(0))"),
            "and binds below floor-div comparison" to
                ("a // b < c and d" to "Binary(and,Binary(<,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"),
            "or binds below and of modulo" to
                ("a % b == 0 and ready or fallback" to
                    "Binary(or,Binary(and,Binary(==,Binary(%,Id(a),Id(b)),Const(0)),Id(ready)),Id(fallback))"),

            // parentheses override
            "parentheses force floor-div after addition" to
                ("(a + b) // c" to "Binary(//,Binary(+,Id(a),Id(b)),Id(c))"),
            "parentheses force modulo after addition" to
                ("(a + b) % c" to "Binary(%,Binary(+,Id(a),Id(b)),Id(c))"),
            "parentheses force floor-div of sum then modulo" to
                ("(a + b) // c % d" to "Binary(%,Binary(//,Binary(+,Id(a),Id(b)),Id(c)),Id(d))"),
            "parentheses force modulo before floor division" to
                ("a // (b % c)" to "Binary(//,Id(a),Binary(%,Id(b),Id(c)))"),
            "parentheses force floor division before modulo" to
                ("a % (b // c)" to "Binary(%,Id(a),Binary(//,Id(b),Id(c)))")
        )
    }

    @Test
    fun parsesMixedIntegerDivisionAndModShapesWithStableNesting() {
        assertExpressionShapes(
            "mixed: floor-div of sum then modulo" to (
                "(a + b) // 2 % stride" to
                    "Binary(%,Binary(//,Binary(+,Id(a),Id(b)),Const(2)),Id(stride))"
                ),
            "mixed: modulo of floor-div under comparison" to (
                "n // step % limit ~= 0" to
                    "Binary(~=,Binary(%,Binary(//,Id(n),Id(step)),Id(limit)),Const(0))"
                ),
            "mixed: unary minus floor-div then add modulo" to (
                "-n // 2 + m % 3" to
                    "Binary(+,Binary(//,Unary(-,Id(n)),Const(2)),Binary(%,Id(m),Const(3)))"
                ),
            "mixed: hex floor-div mod ladder" to (
                "0xFF // 0x10 % 0x0F * 2" to
                    "Binary(*,Binary(%,Binary(//,Const(0xFF),Const(0x10)),Const(0x0F)),Const(2))"
                ),
            "mixed: index as floor-div right operand under modulo" to (
                "value // sizes[i] % stride" to
                    "Binary(%,Binary(//,Id(value),Index(Id(sizes)[Id(i)])),Id(stride))"
                ),
            "mixed: member field floor-div then modulo" to (
                "cfg.step // 4 % mask" to
                    "Binary(%,Binary(//,Member(Id(cfg).step),Const(4)),Id(mask))"
                ),
            "mixed: method call floor-div then modulo" to (
                "object:count() // 2 % 3" to
                    "Binary(%,Binary(//,Call(Member(Id(object):count):),Const(2)),Const(3))"
                ),
            "mixed: call argument preserves floor-div mod ladder" to (
                "pack(a // b % c * d)" to
                    "Call(Id(pack):Binary(*,Binary(%,Binary(//,Id(a),Id(b)),Id(c)),Id(d)))"
                ),
            "mixed: table field with floor-div and modulo" to (
                "{ a // b, c % d // e }" to
                    "Table(TableKey(Const(1)=Binary(//,Id(a),Id(b))),TableKey(Const(2)=Binary(//,Binary(%,Id(c),Id(d)),Id(e))))"
                ),
            "mixed: multi-level parentheses preserve grouping" to (
                "((a // b) % c) + (d % e // f)" to
                    "Binary(+,Binary(%,Binary(//,Id(a),Id(b)),Id(c)),Binary(//,Binary(%,Id(d),Id(e)),Id(f)))"
                ),
            "mixed: power inside floor-div then modulo" to (
                "base ^ exp // scale % mask" to
                    "Binary(%,Binary(//,Binary(^,Id(base),Id(exp)),Id(scale)),Id(mask))"
                ),
            "mixed: concat of floor-div and mod groups" to (
                "prefix // 2 .. mid % 3" to
                    "Binary(..,Binary(//,Id(prefix),Const(2)),Binary(%,Id(mid),Const(3)))"
                ),
            "mixed: logical guard around floor-div modulo" to (
                "ready and n // 2 % 3 == 0 or fallback" to
                    "Binary(or,Binary(and,Id(ready),Binary(==,Binary(%,Binary(//,Id(n),Const(2)),Const(3)),Const(0))),Id(fallback))"
                ),
            "mixed: no-space floor-div mod under or" to (
                "a//b%c or d" to
                    "Binary(or,Binary(%,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"
                ),
            "mixed: chained floor-div left-assoc under modulo" to (
                "a // b // c % d" to
                    "Binary(%,Binary(//,Binary(//,Id(a),Id(b)),Id(c)),Id(d))"
                ),
            "mixed: chained modulo left-assoc under floor division" to (
                "a % b % c // d" to
                    "Binary(//,Binary(%,Binary(%,Id(a),Id(b)),Id(c)),Id(d))"
                ),
            "mixed: shift of floor-div and modulo groups" to (
                "a // b << c % d" to
                    "Binary(<<,Binary(//,Id(a),Id(b)),Binary(%,Id(c),Id(d)))"
                ),
            "mixed: bitwise and of floor-div under comparison" to (
                "n // 2 & 1 == 0" to
                    "Binary(==,Binary(&,Binary(//,Id(n),Const(2)),Const(1)),Const(0))"
                )
        )
    }

    @Test
    fun exposesIntegerDivisionAndModAstOperators() {
        val floorThenMod = assertIs<BinaryExpression>(parseExpression("a // b % c"))
        assertEquals(ExpressionOperator.MOD, floorThenMod.operator)
        assertEquals("Id(c)", renderShape(floorThenMod.right!!))

        val floor = assertIs<BinaryExpression>(floorThenMod.left)
        assertEquals(ExpressionOperator.DOUBLE_DIV, floor.operator)
        assertEquals("Id(a)", renderShape(floor.left!!))
        assertEquals("Id(b)", renderShape(floor.right!!))

        val modThenFloor = assertIs<BinaryExpression>(parseExpression("a % b // c"))
        assertEquals(ExpressionOperator.DOUBLE_DIV, modThenFloor.operator)
        val mod = assertIs<BinaryExpression>(modThenFloor.left)
        assertEquals(ExpressionOperator.MOD, mod.operator)
        assertEquals("Id(a)", renderShape(mod.left!!))
        assertEquals("Id(b)", renderShape(mod.right!!))
        assertEquals("Id(c)", renderShape(modThenFloor.right!!))

        val leftAssocFloor = assertIs<BinaryExpression>(parseExpression("a // b // c"))
        assertEquals(ExpressionOperator.DOUBLE_DIV, leftAssocFloor.operator)
        val innerFloor = assertIs<BinaryExpression>(leftAssocFloor.left)
        assertEquals(ExpressionOperator.DOUBLE_DIV, innerFloor.operator)
        assertEquals("Id(a)", renderShape(innerFloor.left!!))
        assertEquals("Id(b)", renderShape(innerFloor.right!!))
        assertEquals("Id(c)", renderShape(leftAssocFloor.right!!))

        val leftAssocMod = assertIs<BinaryExpression>(parseExpression("a % b % c"))
        assertEquals(ExpressionOperator.MOD, leftAssocMod.operator)
        val innerMod = assertIs<BinaryExpression>(leftAssocMod.left)
        assertEquals(ExpressionOperator.MOD, innerMod.operator)
        assertEquals("Id(a)", renderShape(innerMod.left!!))
        assertEquals("Id(b)", renderShape(innerMod.right!!))
        assertEquals("Id(c)", renderShape(leftAssocMod.right!!))

        val unaryThenFloor = assertIs<BinaryExpression>(parseExpression("-a // 2"))
        assertEquals(ExpressionOperator.DOUBLE_DIV, unaryThenFloor.operator)
        val unary = assertIs<UnaryExpression>(unaryThenFloor.left)
        assertEquals(ExpressionOperator.MINUS, unary.operator)
        assertEquals("Id(a)", renderShape(unary.arg))
        assertEquals("Const(2)", renderShape(unaryThenFloor.right!!))

        val unaryThenMod = assertIs<BinaryExpression>(parseExpression("~a % 3"))
        assertEquals(ExpressionOperator.MOD, unaryThenMod.operator)
        val unaryBit = assertIs<UnaryExpression>(unaryThenMod.left)
        assertEquals(ExpressionOperator.BIT_TILDE, unaryBit.operator)
        assertEquals("Id(a)", renderShape(unaryBit.arg))
        assertEquals("Const(3)", renderShape(unaryThenMod.right!!))
    }

    @Test
    fun exposesLiteralOperandsOnIntegerDivisionAndModExpressions() {
        val expression = assertIs<BinaryExpression>(parseExpression("0xFF // 16 % 3"))
        assertEquals(ExpressionOperator.MOD, expression.operator)
        assertEquals("3", assertIs<ConstantNode>(expression.right).rawValue.toString())

        val floorDiv = assertIs<BinaryExpression>(expression.left)
        assertEquals(ExpressionOperator.DOUBLE_DIV, floorDiv.operator)
        assertEquals("0xFF", assertIs<ConstantNode>(floorDiv.left).rawValue.toString())
        assertEquals("16", assertIs<ConstantNode>(floorDiv.right).rawValue.toString())

        val comparison = assertIs<BinaryExpression>(parseExpression("n // 2 % 5 == 0"))
        assertEquals(ExpressionOperator.EQ, comparison.operator)
        val mod = assertIs<BinaryExpression>(comparison.left)
        assertEquals(ExpressionOperator.MOD, mod.operator)
        val nFloor = assertIs<BinaryExpression>(mod.left)
        assertEquals(ExpressionOperator.DOUBLE_DIV, nFloor.operator)
        assertIs<Identifier>(nFloor.left)
        assertEquals("n", (nFloor.left as Identifier).name)
        assertEquals("2", assertIs<ConstantNode>(nFloor.right).rawValue.toString())
        assertEquals("5", assertIs<ConstantNode>(mod.right).rawValue.toString())
        assertEquals("0", assertIs<ConstantNode>(comparison.right).rawValue.toString())
    }

    @Test
    fun roundTripsIntegerDivisionAndModMixesWithStableShapes() {
        val samples = listOf(
            "return a // b",
            "return a % b",
            "return a // b // c",
            "return a % b % c",
            "return a // b % c",
            "return a % b // c",
            "return a * b // c / d % e",
            "return a % b / c // d * e",
            "return a + b // c",
            "return a // b + c",
            "return a - b % c",
            "return a % b - c",
            "return a // b ^ c",
            "return a ^ b % c",
            "return -a // b",
            "return -a % b",
            "return #a // b",
            "return ~a // b",
            "return ~a % b",
            "return not a // b",
            "return -(a // b)",
            "return ~(a % b)",
            "return (a + b) // c",
            "return (a + b) % c",
            "return a // (b % c)",
            "return a % (b // c)",
            "return a // b .. c",
            "return a % b .. c",
            "return a // b << c",
            "return a % b >> c",
            "return a // b < c",
            "return a % b >= c",
            "return a // b % c == d",
            "return a % b // c ~= 0",
            "return a // b < c and d",
            "return a % b == 0 and ready or fallback",
            "return (a + b) // 2 % stride",
            "return n // step % limit ~= 0",
            "return -n // 2 + m % 3",
            "return 0xFF // 0x10 % 0x0F * 2",
            "return value // sizes[i] % stride",
            "return cfg.step // 4 % mask",
            "return object:count() // 2 % 3",
            "return pack(a // b % c * d)",
            "return ((a // b) % c) + (d % e // f)",
            "return base ^ exp // scale % mask",
            "return ready and n // 2 % 3 == 0 or fallback",
            "return a//b%c",
            "return a // b // c % d",
            "return a % b % c // d",
            "return a // b << c % d"
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

                INTEGER_DIV_MOD_MARKERS.filter { marker -> source.contains(marker) }.forEach { marker ->
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
    fun parsesIntegerDivisionAndModCorpusAsSuccessfulChunkBodies() {
        val sources = listOf(
            "local q = n // 2",
            "local r = n % 2",
            "local mixed = (a // b) % (c // d)",
            "return a // b % c, a % b // c",
            "if n // step % limit ~= 0 then return true end",
            "while ready and n // 2 % 3 == 0 do break end",
            "for i = 1, limit // step do total = total + i % 2 end",
            "function scale(a, b) return (a // b) % (b + 1) end",
            "local t = { a // b, c % d, -e // f, g % h // i }",
            "repeat x = x // 2 until x % 2 == 0",
            "do local n = -seed // 2 % 3; return n end",
            "local function unpack(v) return v // 256, v % 256 end",
            "return (a // b) + (c % d) * (e // f % g)"
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
    fun documentsIntegerDivisionAndModInStatementContextsWithShapes() {
        val localFloor = parse(LuaVersion.LUA_5_3, "local q = n // 2")
        assertEquals(
            "Chunk(Block[Local(Id(q)=Binary(//,Id(n),Const(2)))])",
            renderShape(localFloor)
        )

        val localMod = parse(LuaVersion.LUA_5_3, "local r = n % 2")
        assertEquals(
            "Chunk(Block[Local(Id(r)=Binary(%,Id(n),Const(2)))])",
            renderShape(localMod)
        )

        val ifFloorMod = parse(LuaVersion.LUA_5_3, "if n // step % limit ~= 0 then return true end")
        assertEquals(
            "Chunk(Block[If(Clause(Binary(~=,Binary(%,Binary(//,Id(n),Id(step)),Id(limit)),Const(0)):Block[Return(Const(true))]))])",
            renderShape(ifFloorMod)
        )

        val assignMix = parse(LuaVersion.LUA_5_3, "total = total + (n // 2 % i)")
        assertEquals(
            "Chunk(Block[Assign(Id(total)=Binary(+,Id(total),Binary(%,Binary(//,Id(n),Const(2)),Id(i))))])",
            renderShape(assignMix)
        )

        val functionScale = parse(
            LuaVersion.LUA_5_3,
            "function scale(a, b) return (a // b) % (b + 1) end"
        )
        assertEquals(
            "Chunk(Block[Function(Id(scale),Block[Return(Binary(%,Binary(//,Id(a),Id(b)),Binary(+,Id(b),Const(1))))])])",
            renderShape(functionScale)
        )
    }

    /**
     * TASK-549: NUMBER ConstantNode typing must follow Lua 5.3 rules, not
     * merely `contains('.')`. Hex integers keep INTERGER (even when digits look
     * like decimal exponents); hex binary-exponent / fraction forms and decimal
     * scientific forms are FLOAT. Raw lexeme is preserved; intOf/floatOf never
     * throw ClassCastException for oversized values.
     */
    @Test
    fun typesNumberLiteralsByLua53RulesNotJustDotPresence() {
        fun numberAt(source: String): ConstantNode {
            val expression = parseExpression(source)
            return assertIs<ConstantNode>(expression)
        }

        // Decimal integer
        numberAt("42").let {
            assertEquals(ConstantNode.TYPE.INTERGER, it.constantType)
            assertEquals("42", it.rawValue.toString())
            assertEquals(42, it.intOf())
        }

        // Hex integer (no '.', no p) — including hex digit 'e' which is NOT a decimal exponent
        numberAt("0xFF").let {
            assertEquals(ConstantNode.TYPE.INTERGER, it.constantType)
            assertEquals("0xFF", it.rawValue.toString())
            assertEquals(255, it.intOf())
        }
        numberAt("0x1e").let {
            assertEquals(ConstantNode.TYPE.INTERGER, it.constantType)
            assertEquals("0x1e", it.rawValue.toString())
            assertEquals(0x1e, it.intOf())
        }
        numberAt("0x10").let {
            assertEquals(ConstantNode.TYPE.INTERGER, it.constantType)
            assertEquals(16, it.intOf())
        }

        // Decimal scientific is float even without '.'
        numberAt("1e3").let {
            assertEquals(ConstantNode.TYPE.FLOAT, it.constantType)
            assertEquals("1e3", it.rawValue.toString())
            assertEquals(1000f, it.floatOf())
        }
        numberAt("2E-1").let {
            assertEquals(ConstantNode.TYPE.FLOAT, it.constantType)
            assertEquals("2E-1", it.rawValue.toString())
        }

        // Decimal with fraction
        numberAt("3.14").let {
            assertEquals(ConstantNode.TYPE.FLOAT, it.constantType)
            assertEquals("3.14", it.rawValue.toString())
        }

        // Hex float forms (fraction and/or binary exponent) — must NOT stay INTERGER
        numberAt("0x1.8p1").let {
            assertEquals(ConstantNode.TYPE.FLOAT, it.constantType)
            assertEquals("0x1.8p1", it.rawValue.toString())
            assertEquals(3f, it.floatOf()) // 1.5 * 2^1
        }
        numberAt("0x1p10").let {
            assertEquals(ConstantNode.TYPE.FLOAT, it.constantType)
            assertEquals("0x1p10", it.rawValue.toString())
            assertEquals(1024f, it.floatOf())
        }
        numberAt("0x1.f").let {
            assertEquals(ConstantNode.TYPE.FLOAT, it.constantType)
            assertEquals("0x1.f", it.rawValue.toString())
        }

        // Out-of-Int integer lexeme: keep raw, intOf must not throw
        numberAt("9223372036854775807").let {
            assertEquals(ConstantNode.TYPE.INTERGER, it.constantType)
            assertEquals("9223372036854775807", it.rawValue.toString())
            // Long-safe path: coerced into Int range rather than ClassCastException
            val safe = runCatching { it.intOf() }
            assertTrue(safe.isSuccess, "intOf must not throw for long integer lexeme: ${safe.exceptionOrNull()}")
        }

        // Floor-div / mod shapes with hex operands still use raw lexeme text
        val expression = assertIs<BinaryExpression>(parseExpression("0xFF // 0x10 % 0x0F"))
        assertEquals(ExpressionOperator.MOD, expression.operator)
        assertEquals("0x0F", assertIs<ConstantNode>(expression.right).rawValue.toString())
        assertEquals(ConstantNode.TYPE.INTERGER, assertIs<ConstantNode>(expression.right).constantType)
        val floorDiv = assertIs<BinaryExpression>(expression.left)
        assertEquals(ConstantNode.TYPE.INTERGER, assertIs<ConstantNode>(floorDiv.left).constantType)
        assertEquals(ConstantNode.TYPE.INTERGER, assertIs<ConstantNode>(floorDiv.right).constantType)
    }

    @Test
    fun classifiesNumberLexemesWithoutParserRoundTrip() {
        val cases = listOf(
            "42" to ConstantNode.TYPE.INTERGER,
            "0" to ConstantNode.TYPE.INTERGER,
            "0xFF" to ConstantNode.TYPE.INTERGER,
            "0X2a" to ConstantNode.TYPE.INTERGER,
            "0x1e" to ConstantNode.TYPE.INTERGER, // hex digit e, not scientific
            "1e3" to ConstantNode.TYPE.FLOAT,
            "1E+10" to ConstantNode.TYPE.FLOAT,
            "3.14" to ConstantNode.TYPE.FLOAT,
            ".5" to ConstantNode.TYPE.FLOAT,
            "0x1.8p1" to ConstantNode.TYPE.FLOAT,
            "0x1p10" to ConstantNode.TYPE.FLOAT,
            "0X1.FP+0" to ConstantNode.TYPE.FLOAT,
            "0x1.f" to ConstantNode.TYPE.FLOAT,
            "0x.8p-1" to ConstantNode.TYPE.FLOAT
        )

        val failures = cases.mapNotNull { (lexeme, expected) ->
            val actual = ConstantNode.typeForNumberLexeme(lexeme)
            if (actual != expected) {
                "lexeme=$lexeme expected=$expected actual=$actual"
            } else {
                null
            }
        }
        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n"))
        }

        // fromNumberLexeme keeps raw text and does not throw on large ints
        val huge = ConstantNode.fromNumberLexeme("999999999999999999999999999")
        assertEquals(ConstantNode.TYPE.INTERGER, huge.constantType)
        assertEquals("999999999999999999999999999", huge.rawValue.toString())
        assertTrue(runCatching { huge.intOf() }.isSuccess)

        val hexFloat = ConstantNode.fromNumberLexeme("0x1.8p1")
        assertEquals(ConstantNode.TYPE.FLOAT, hexFloat.constantType)
        assertTrue(runCatching { hexFloat.floatOf() }.isSuccess)
        assertEquals(3f, hexFloat.floatOf())
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
        val INTEGER_DIV_MOD_MARKERS = listOf("//", "%")
    }
}
