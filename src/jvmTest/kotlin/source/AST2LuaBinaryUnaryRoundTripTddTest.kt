package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionOperator
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.source.AST2Lua
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AST2Lua round-trip corpus focused on binary / unary expressions and parentheses
 * required for Lua 5.3 precedence (TASK-256).
 *
 * ## Shape-stable print policy
 *
 * Round-trip acceptance is **AST-shape stable**, not byte-identical to the input source.
 *
 * 1. **Acceptance metric** — `parse(source)` → `AST2Lua.asCode` → reparse must yield the same
 *    `renderShape` as the original parse. Whitespace may change; structural nodes must not.
 * 2. **Parentheses** — printer must re-emit parens that override precedence / associativity
 *    (e.g. `(a + b) * c`, `(a or b) and c`, left-grouped `^` / `..`, unary of low-prec
 *    operands). Cosmetic parens that do not change shape may be dropped.
 * 3. **Operator spacing** — binary ops print as `left op right` with single spaces around the
 *    operator token; `not` prints with a trailing space (`not x`); `#`, `-`, `~` are tight.
 * 4. **Empty / single-expression** — empty chunks and single-expression return statements must
 *    not crash the printer and must reparse to the same shape.
 * 5. **Version** — all samples use [LuaVersion.LUA_5_3] (includes floor-div / bitwise / power).
 * 6. **Out of scope** — comment preservation, semantic checks, and IfStatement trailing-`end`
 *    printer layout (IfStatement does not emit `end`; see TASK-192 policy). Pure-if samples
 *    only assert initial shape + condition fragments, not reparse.
 *
 * Documented Lua 5.3 precedence (high → low), relevant slice:
 *   power ^ (right-assoc)
 *   unary: not  #  -  ~
 *   multiplicative * / // %
 *   additive + -
 *   concat .. (right-assoc)
 *   shift << >> (left-assoc)
 *   bitwise and &
 *   bitwise xor ~
 *   bitwise or |
 *   comparisons < > <= >= ~= ==
 *   and
 *   or
 */
class AST2LuaBinaryUnaryRoundTripTddTest {

    private val printer = AST2Lua()
    private val version = LuaVersion.LUA_5_3

    // --- Empty chunk / single-expression safety ---

    @Test
    fun doesNotCrashOnEmptyChunkAndSingleExpressionReturns() {
        val empty = LuaParser(luaVersion = version).parse("")
        val emptyPrinted = printer.asCode(empty)
        val emptyReparsed = LuaParser(luaVersion = version).parse(emptyPrinted)
        assertEquals(renderShape(empty), renderShape(emptyReparsed), "empty chunk printed:\n$emptyPrinted")
        assertEquals("Chunk(Block[])", renderShape(empty))

        val onlySemicolons = LuaParser(luaVersion = version).parse(";;;")
        val onlySemicolonsPrinted = printer.asCode(onlySemicolons)
        val onlySemicolonsReparsed = LuaParser(luaVersion = version).parse(onlySemicolonsPrinted)
        assertEquals(
            renderShape(onlySemicolons),
            renderShape(onlySemicolonsReparsed),
            "semicolon-only chunk printed:\n$onlySemicolonsPrinted"
        )
        assertEquals("Chunk(Block[])", renderShape(onlySemicolons))

        listOf(
            "return 1",
            "return a",
            "return -a",
            "return not a",
            "return #t",
            "return ~flags",
            "return a + b",
            "return a and b or c"
        ).forEach { source ->
            val initial = LuaParser(luaVersion = version).parse(source)
            val printed = printer.asCode(initial)
            val reparsed = LuaParser(luaVersion = version).parse(printed)
            assertEquals(
                renderShape(initial),
                renderShape(reparsed),
                "single-expression return for <$source>\nprinted:\n$printed"
            )
            assertTrue(printed.contains("return"), "printed for <$source>:\n$printed")
        }
    }

    // --- Arithmetic + power / unary ---

    @Test
    fun roundTripsArithmeticRelationalLogicalAndUnaryWithRequiredParens() {
        assertRoundTrips(
            listOf(
                // arithmetic ladder
                Sample(
                    source = "return a + b * c",
                    expectedShape = "Chunk(Block[Return(Binary(+,Id(a),Binary(*,Id(b),Id(c))))])",
                    printedFragments = listOf("a + b * c")
                ),
                Sample(
                    source = "return (a + b) * c",
                    expectedShape = "Chunk(Block[Return(Binary(*,Binary(+,Id(a),Id(b)),Id(c)))])",
                    // parens required to preserve add-under-mul grouping
                    printedFragments = listOf("(a + b) * c")
                ),
                Sample(
                    source = "return a - b / c + d % e",
                    expectedShape =
                        "Chunk(Block[Return(Binary(+,Binary(-,Id(a),Binary(/,Id(b),Id(c))),Binary(%,Id(d),Id(e))))])",
                    printedFragments = listOf("a - b / c + d % e")
                ),
                Sample(
                    source = "return a + b // c - d",
                    expectedShape =
                        "Chunk(Block[Return(Binary(-,Binary(+,Id(a),Binary(//,Id(b),Id(c))),Id(d)))])",
                    printedFragments = listOf("a + b // c - d")
                ),
                Sample(
                    source = "return a * (b + c) / (d - e)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(/,Binary(*,Id(a),Binary(+,Id(b),Id(c))),Binary(-,Id(d),Id(e))))])",
                    printedFragments = listOf("a * (b + c) / (d - e)")
                ),

                // power (right-assoc) and unary-of-power
                Sample(
                    source = "return a ^ b ^ c",
                    expectedShape = "Chunk(Block[Return(Binary(^,Id(a),Binary(^,Id(b),Id(c))))])",
                    printedFragments = listOf("a ^ b ^ c")
                ),
                Sample(
                    source = "return (a ^ b) ^ c",
                    expectedShape = "Chunk(Block[Return(Binary(^,Binary(^,Id(a),Id(b)),Id(c)))])",
                    // left-grouped power needs parens under right-associative ^
                    printedFragments = listOf("(a ^ b) ^ c")
                ),
                Sample(
                    source = "return -a ^ b",
                    expectedShape = "Chunk(Block[Return(Unary(-,Binary(^,Id(a),Id(b))))])",
                    printedFragments = listOf("-a ^ b")
                ),
                Sample(
                    source = "return (-a) ^ b",
                    expectedShape = "Chunk(Block[Return(Binary(^,Unary(-,Id(a)),Id(b)))])",
                    printedFragments = listOf("(-a) ^ b")
                ),
                Sample(
                    source = "return a * b ^ c",
                    expectedShape = "Chunk(Block[Return(Binary(*,Id(a),Binary(^,Id(b),Id(c))))])",
                    printedFragments = listOf("a * b ^ c")
                ),

                // unary forms
                Sample(
                    source = "return -a * b",
                    expectedShape = "Chunk(Block[Return(Binary(*,Unary(-,Id(a)),Id(b)))])",
                    printedFragments = listOf("-a * b")
                ),
                Sample(
                    source = "return #a .. b",
                    expectedShape = "Chunk(Block[Return(Binary(..,Unary(#,Id(a)),Id(b)))])",
                    printedFragments = listOf("#a .. b")
                ),
                Sample(
                    source = "return not a and b",
                    expectedShape = "Chunk(Block[Return(Binary(and,Unary(not,Id(a)),Id(b)))])",
                    printedFragments = listOf("not a and b")
                ),
                Sample(
                    source = "return not (a or b)",
                    expectedShape = "Chunk(Block[Return(Unary(not,Binary(or,Id(a),Id(b))))])",
                    printedFragments = listOf("not (a or b)")
                ),
                Sample(
                    source = "return ~a & b | c",
                    expectedShape =
                        "Chunk(Block[Return(Binary(|,Binary(&,Unary(~,Id(a)),Id(b)),Id(c)))])",
                    printedFragments = listOf("~a & b | c")
                ),
                Sample(
                    source = "return ~(a | b)",
                    expectedShape = "Chunk(Block[Return(Unary(~,Binary(|,Id(a),Id(b))))])",
                    printedFragments = listOf("~(a | b)")
                ),
                Sample(
                    source = "return ~~flags",
                    expectedShape = "Chunk(Block[Return(Unary(~,Unary(~,Id(flags))))])",
                    printedFragments = listOf("~~flags")
                ),
                Sample(
                    source = "return -#t",
                    expectedShape = "Chunk(Block[Return(Unary(-,Unary(#,Id(t))))])",
                    printedFragments = listOf("-#t")
                ),

                // concat (right-assoc) vs arithmetic / shifts
                // Lua 5.3: .. binds tighter than << >>, so a << b .. c => a << (b .. c)
                Sample(
                    source = "return a .. b .. c",
                    expectedShape = "Chunk(Block[Return(Binary(..,Id(a),Binary(..,Id(b),Id(c))))])",
                    printedFragments = listOf("a .. b .. c")
                ),
                Sample(
                    source = "return (a .. b) .. c",
                    expectedShape = "Chunk(Block[Return(Binary(..,Binary(..,Id(a),Id(b)),Id(c)))])",
                    printedFragments = listOf("(a .. b) .. c")
                ),
                Sample(
                    source = "return a + b .. c * d",
                    expectedShape =
                        "Chunk(Block[Return(Binary(..,Binary(+,Id(a),Id(b)),Binary(*,Id(c),Id(d))))])",
                    printedFragments = listOf("a + b .. c * d")
                ),
                Sample(
                    source = "return a << b .. c",
                    expectedShape =
                        "Chunk(Block[Return(Binary(<<,Id(a),Binary(..,Id(b),Id(c))))])",
                    printedFragments = listOf("a << b .. c")
                ),
                Sample(
                    source = "return (a << b) .. c",
                    expectedShape =
                        "Chunk(Block[Return(Binary(..,Binary(<<,Id(a),Id(b)),Id(c)))])",
                    printedFragments = listOf("(a << b) .. c")
                ),

                // shifts / bitwise ladder
                Sample(
                    source = "return a << b << c",
                    expectedShape =
                        "Chunk(Block[Return(Binary(<<,Binary(<<,Id(a),Id(b)),Id(c)))])",
                    printedFragments = listOf("a << b << c")
                ),
                Sample(
                    source = "return a | b ~ c & d << e",
                    expectedShape =
                        "Chunk(Block[Return(Binary(|,Id(a),Binary(~,Id(b),Binary(&,Id(c),Binary(<<,Id(d),Id(e))))))])",
                    printedFragments = listOf("a | b ~ c & d << e")
                ),
                Sample(
                    source = "return (a | b) & c",
                    expectedShape =
                        "Chunk(Block[Return(Binary(&,Binary(|,Id(a),Id(b)),Id(c)))])",
                    printedFragments = listOf("(a | b) & c")
                ),
                Sample(
                    source = "return (a // b) << 2 | flags & mask ~ toggle",
                    expectedShape =
                        "Chunk(Block[Return(Binary(|,Binary(<<,Binary(//,Id(a),Id(b)),Const(2)),Binary(~,Binary(&,Id(flags),Id(mask)),Id(toggle))))])",
                    printedFragments = listOf("(a // b) << 2", "flags & mask ~ toggle")
                ),

                // relational + logical
                Sample(
                    source = "return a + b < c and d or e",
                    expectedShape =
                        "Chunk(Block[Return(Binary(or,Binary(and,Binary(<,Binary(+,Id(a),Id(b)),Id(c)),Id(d)),Id(e)))])",
                    printedFragments = listOf("a + b < c and d or e")
                ),
                Sample(
                    source = "return (a or b) and c",
                    expectedShape =
                        "Chunk(Block[Return(Binary(and,Binary(or,Id(a),Id(b)),Id(c)))])",
                    printedFragments = listOf("(a or b) and c")
                ),
                Sample(
                    source = "return a < b == c",
                    expectedShape =
                        "Chunk(Block[Return(Binary(==,Binary(<,Id(a),Id(b)),Id(c)))])",
                    printedFragments = listOf("a < b == c")
                ),
                Sample(
                    source = "return a ~= b and c >= d or e <= f",
                    expectedShape =
                        "Chunk(Block[Return(Binary(or,Binary(and,Binary(~=,Id(a),Id(b)),Binary(>=,Id(c),Id(d))),Binary(<=,Id(e),Id(f))))])",
                    printedFragments = listOf("a ~= b and c >= d or e <= f")
                ),
                Sample(
                    source = "return not a < b",
                    expectedShape =
                        "Chunk(Block[Return(Binary(<,Unary(not,Id(a)),Id(b)))])",
                    printedFragments = listOf("not a < b")
                ),
                Sample(
                    source = "return not (a < b)",
                    expectedShape =
                        "Chunk(Block[Return(Unary(not,Binary(<,Id(a),Id(b))))])",
                    printedFragments = listOf("not (a < b)")
                ),

                // mixed multi-return expressions
                Sample(
                    source = "return -a^b, a .. b .. c, not a and b",
                    expectedShape =
                        "Chunk(Block[Return(Unary(-,Binary(^,Id(a),Id(b))),Binary(..,Id(a),Binary(..,Id(b),Id(c))),Binary(and,Unary(not,Id(a)),Id(b)))])",
                    printedFragments = listOf("-a ^ b", "a .. b .. c", "not a and b")
                ),
                Sample(
                    source = "return (a + b) * c - d / (e + f)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(-,Binary(*,Binary(+,Id(a),Id(b)),Id(c)),Binary(/,Id(d),Binary(+,Id(e),Id(f)))))])",
                    printedFragments = listOf("(a + b) * c", "d / (e + f)")
                )
            )
        )
    }

    @Test
    fun roundTripsLeftVsRightAssociativityParenForms() {
        assertRoundTrips(
            listOf(
                // left-associative ops with forced right grouping
                Sample(
                    source = "return a + (b + c)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(+,Id(a),Binary(+,Id(b),Id(c))))])",
                    printedFragments = listOf("a + (b + c)")
                ),
                Sample(
                    source = "return a - (b - c)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(-,Id(a),Binary(-,Id(b),Id(c))))])",
                    printedFragments = listOf("a - (b - c)")
                ),
                Sample(
                    source = "return a * (b * c)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(*,Id(a),Binary(*,Id(b),Id(c))))])",
                    printedFragments = listOf("a * (b * c)")
                ),
                Sample(
                    source = "return a / (b / c)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(/,Id(a),Binary(/,Id(b),Id(c))))])",
                    printedFragments = listOf("a / (b / c)")
                ),
                Sample(
                    source = "return a << (b << c)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(<<,Id(a),Binary(<<,Id(b),Id(c))))])",
                    printedFragments = listOf("a << (b << c)")
                ),
                Sample(
                    source = "return a & (b & c)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(&,Id(a),Binary(&,Id(b),Id(c))))])",
                    printedFragments = listOf("a & (b & c)")
                ),
                Sample(
                    source = "return a | (b | c)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(|,Id(a),Binary(|,Id(b),Id(c))))])",
                    printedFragments = listOf("a | (b | c)")
                ),
                Sample(
                    source = "return a and (b and c)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(and,Id(a),Binary(and,Id(b),Id(c))))])",
                    printedFragments = listOf("a and (b and c)")
                ),
                Sample(
                    source = "return a or (b or c)",
                    expectedShape =
                        "Chunk(Block[Return(Binary(or,Id(a),Binary(or,Id(b),Id(c))))])",
                    printedFragments = listOf("a or (b or c)")
                ),

                // right-associative ^ / .. natural forms + mixed with lower/higher neighbors
                Sample(
                    source = "return a ^ b * c",
                    expectedShape =
                        "Chunk(Block[Return(Binary(*,Binary(^,Id(a),Id(b)),Id(c)))])",
                    printedFragments = listOf("a ^ b * c")
                ),
                Sample(
                    source = "return a .. b + c",
                    expectedShape =
                        "Chunk(Block[Return(Binary(..,Id(a),Binary(+,Id(b),Id(c))))])",
                    printedFragments = listOf("a .. b + c")
                ),
                Sample(
                    source = "return a + b .. c + d",
                    expectedShape =
                        "Chunk(Block[Return(Binary(..,Binary(+,Id(a),Id(b)),Binary(+,Id(c),Id(d))))])",
                    printedFragments = listOf("a + b .. c + d")
                )
            )
        )
    }

    @Test
    fun roundTripsBinaryUnaryInsideStatementContexts() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "local total = (a + b) * c - d",
                    expectedShape =
                        "Chunk(Block[Local(Id(total)=Binary(-,Binary(*,Binary(+,Id(a),Id(b)),Id(c)),Id(d)))])",
                    printedFragments = listOf("local total = (a + b) * c - d")
                ),
                Sample(
                    source = "total = total | (1 << i)",
                    expectedShape =
                        "Chunk(Block[Assign(Id(total)=Binary(|,Id(total),Binary(<<,Const(1),Id(i))))])",
                    printedFragments = listOf("total | (1 << i)")
                ),
                Sample(
                    source = "while ready and bits << 1 | 1 do break end",
                    expectedShape =
                        "Chunk(Block[While(Binary(and,Id(ready),Binary(|,Binary(<<,Id(bits),Const(1)),Const(1))):Block[Break])])",
                    printedFragments = listOf("ready and bits << 1 | 1")
                ),
                Sample(
                    source = "function pack(a, b) return (a & 0xFF) | (b << 8) end",
                    expectedShape =
                        "Chunk(Block[Function(Id(pack),Block[Return(Binary(|,Binary(&,Id(a),Const(0xFF)),Binary(<<,Id(b),Const(8))))])])",
                    printedFragments = listOf("(a & 0xFF) | (b << 8)")
                ),
                Sample(
                    source = "local t = { a + b * c, not ready, #items }",
                    expectedShape =
                        "Chunk(Block[Local(Id(t)=Table(TableKey(Const(1)=Binary(+,Id(a),Binary(*,Id(b),Id(c)))),TableKey(Const(2)=Unary(not,Id(ready))),TableKey(Const(3)=Unary(#,Id(items)))))])",
                    printedFragments = listOf("a + b * c", "not ready", "#items")
                ),
                Sample(
                    source = "return pack(a | b & c << d)",
                    expectedShape =
                        "Chunk(Block[Return(Call(Id(pack):Binary(|,Id(a),Binary(&,Id(b),Binary(<<,Id(c),Id(d))))))])",
                    printedFragments = listOf("pack(a | b & c << d)")
                ),
                Sample(
                    source = "return object:bits() & mask | 1",
                    expectedShape =
                        "Chunk(Block[Return(Binary(|,Binary(&,Call(Member(Id(object):bits):),Id(mask)),Const(1)))])",
                    printedFragments = listOf("object:bits() & mask | 1")
                ),
                Sample(
                    source = "return value << offsets[i] & mask",
                    expectedShape =
                        "Chunk(Block[Return(Binary(&,Binary(<<,Id(value),Index(Id(offsets)[Id(i)])),Id(mask)))])",
                    printedFragments = listOf("value << offsets[i] & mask")
                ),
                Sample(
                    source = "return bits.hi << 8 | bits.lo",
                    expectedShape =
                        "Chunk(Block[Return(Binary(|,Binary(<<,Member(Id(bits).hi),Const(8)),Member(Id(bits).lo)))])",
                    printedFragments = listOf("bits.hi << 8 | bits.lo")
                ),
                Sample(
                    source = "repeat x = x >> 1 until x & 1 == 0",
                    expectedShape =
                        "Chunk(Block[Repeat(Block[Assign(Id(x)=Binary(>>,Id(x),Const(1)))]:Binary(==,Binary(&,Id(x),Const(1)),Const(0)))])",
                    printedFragments = listOf("x >> 1", "x & 1 == 0")
                )
            )
        )
    }

    @Test
    fun printerEmitsRequiredParensForPrecedenceOverrides() {
        // Explicit fragment checks: when the AST groups a lower-prec op under a higher-prec
        // parent, the printed surface must include parentheses so reparse keeps the shape.
        val cases = listOf(
            "return (a + b) * c" to listOf("(a + b)"),
            "return (a or b) and c" to listOf("(a or b)"),
            "return (a ^ b) ^ c" to listOf("(a ^ b)"),
            "return (a .. b) .. c" to listOf("(a .. b)"),
            "return a + (b + c)" to listOf("(b + c)"),
            "return a - (b - c)" to listOf("(b - c)"),
            "return not (a or b)" to listOf("not (a or b)"),
            "return ~(a | b)" to listOf("~(a | b)"),
            "return (-a) ^ b" to listOf("(-a)"),
            "return (a | b) & c" to listOf("(a | b)"),
            "return (a // b) << 2" to listOf("(a // b)"),
            "return (a << b) .. c" to listOf("(a << b)"),
            "return a * (b + c) / (d - e)" to listOf("(b + c)", "(d - e)")
        )

        cases.forEach { (source, fragments) ->
            val initial = LuaParser(luaVersion = version).parse(source)
            val printed = printer.asCode(initial)
            val reparsed = LuaParser(luaVersion = version).parse(printed)

            assertEquals(
                renderShape(initial),
                renderShape(reparsed),
                "shape drift for <$source>\nprinted:\n$printed"
            )
            fragments.forEach { fragment ->
                assertTrue(
                    printed.contains(fragment),
                    "Printed code for <$source> dropped required parens fragment <$fragment>:\n$printed"
                )
            }
        }
    }

    @Test
    fun ifConditionBinaryOpsPreserveShapeAndPrintFragments() {
        // IfStatement printer omits trailing `end` (TASK-192 policy); do not reparse pure-if
        // surfaces. Still assert parse shape of binary condition and stable print fragments.
        val source = "if flags & MASK ~= 0 then return true end"
        val expectedShape =
            "Chunk(Block[If(Clause(Binary(~=,Binary(&,Id(flags),Id(MASK)),Const(0)):Block[Return(Const(true))]))])"

        val initial = LuaParser(luaVersion = version).parse(source)
        assertEquals(expectedShape, renderShape(initial), source)

        val printed = printer.asCode(initial)
        assertTrue(printed.contains("flags & MASK ~= 0"), "printed:\n$printed")
        assertTrue(printed.contains("if "), "printed:\n$printed")
        assertTrue(printed.contains("return true"), "printed:\n$printed")
    }

    @Test
    fun exposesBinaryAndUnaryAstOperatorsAfterRoundTrip() {
        val printed = printer.asCode(
            LuaParser(luaVersion = version).parse("return -a ^ b + c * d, not (x or y), a << b & c | d")
        )
        val chunk = LuaParser(luaVersion = version).parse(printed)
        val ret = assertIs<ReturnStatement>(chunk.body.returnStatement)
        assertEquals(3, ret.arguments.size)

        val add = assertIs<BinaryExpression>(ret.arguments[0])
        assertEquals(ExpressionOperator.ADD, add.operator)
        val unaryPower = assertIs<UnaryExpression>(add.left)
        assertEquals(ExpressionOperator.MINUS, unaryPower.operator)
        val power = assertIs<BinaryExpression>(unaryPower.arg)
        assertEquals(ExpressionOperator.BIT_EXP, power.operator)
        val mul = assertIs<BinaryExpression>(add.right)
        assertEquals(ExpressionOperator.MULT, mul.operator)

        val notExpr = assertIs<UnaryExpression>(ret.arguments[1])
        assertEquals(ExpressionOperator.NOT, notExpr.operator)
        val orExpr = assertIs<BinaryExpression>(notExpr.arg)
        assertEquals(ExpressionOperator.OR, orExpr.operator)

        val bitOr = assertIs<BinaryExpression>(ret.arguments[2])
        assertEquals(ExpressionOperator.BIT_OR, bitOr.operator)
        val bitAnd = assertIs<BinaryExpression>(bitOr.left)
        assertEquals(ExpressionOperator.BIT_AND, bitAnd.operator)
        val shift = assertIs<BinaryExpression>(bitAnd.left)
        assertEquals(ExpressionOperator.BIT_LT, shift.operator)

        assertTrue(printed.contains("-a ^ b + c * d"), "printed:\n$printed")
        assertTrue(printed.contains("not (x or y)"), "printed:\n$printed")
        assertTrue(printed.contains("a << b & c | d"), "printed:\n$printed")
    }

    @Test
    fun roundTripsBulkBinaryUnaryCorpusWithoutShapeDrift() {
        val samples = listOf(
            // arithmetic
            "return a + b",
            "return a - b",
            "return a * b",
            "return a / b",
            "return a // b",
            "return a % b",
            "return a + b - c",
            "return a * b / c % d",
            "return a + b * c - d / e",
            "return (a + b) * (c - d)",
            "return a * (b + c) - d",
            // power / unary
            "return a ^ b",
            "return a ^ b ^ c",
            "return (a ^ b) ^ c",
            "return -a",
            "return -a ^ b",
            "return (-a) ^ b",
            "return #t",
            "return #a .. #b",
            "return not a",
            "return not not a",
            "return not a and not b",
            "return not (a and b)",
            "return ~a",
            "return ~~a",
            "return ~-a",
            "return -~a",
            "return ~a ^ b",
            // concat
            "return a .. b",
            "return a .. b .. c",
            "return (a .. b) .. c",
            "return a + b .. c * d .. e",
            // shifts / bitwise
            "return a << b",
            "return a >> b",
            "return a << b << c",
            "return a >> b >> c",
            "return a << b >> c << d",
            "return a & b",
            "return a ~ b",
            "return a | b",
            "return a & b & c",
            "return a ~ b ~ c",
            "return a | b | c",
            "return a | b ~ c & d << e",
            "return a << b & c ~ d | e",
            "return (a | b) & c",
            "return ~(a | b)",
            "return (a // b) << 2 | flags & mask ~ toggle",
            "return 0xFF << 4 | 0x0F & 0x33 ~ 0x01",
            // relational / logical
            "return a < b",
            "return a > b",
            "return a <= b",
            "return a >= b",
            "return a == b",
            "return a ~= b",
            "return a < b == c",
            "return a + b < c",
            "return a | b < c",
            "return a < b and c",
            "return a or b and c",
            "return a and b and c",
            "return a or b or c",
            "return a + b < c and d or e",
            "return (a or b) and c",
            "return not a < b",
            "return not (a < b)",
            "return ready and flags | mask or fallback",
            // mixed surfaces
            "return -a^b, a .. b .. c, not a and b",
            "return (a + b) * c - d / (e + f)",
            "return a + (b + c)",
            "return a - (b - c)",
            "return a * (b * c)",
            "return a / (b / c)",
            "return a << (b << c)",
            "return a and (b and c)",
            "return a or (b or c)",
            "return pack(a | b & c << d)",
            "return object:bits() & mask | 1",
            "return value << offsets[i] & mask",
            "return bits.hi << 8 | bits.lo",
            "return prefix .. a << b .. suffix",
            "return a << b .. c",
            "return a .. b >> c",
            "return (a << b) .. c",
            "return a .. (b >> c)",
            "return ~~a & ~~b | ~~c",
            "return not a | b & c",
            "local total = (a + b) * c - d",
            "total = total | (1 << i)",
            "function pack(a, b) return (a & 0xFF) | (b << 8) end",
            "while ready and bits << 1 | 1 do break end",
            "repeat x = x >> 1 until x & 1 == 0"
        )

        val failures = samples.mapNotNull { source ->
            runCatching {
                val initial = LuaParser(luaVersion = version).parse(source)
                val printed = printer.asCode(initial)
                val reparsed = LuaParser(luaVersion = version).parse(printed)
                assertEquals(
                    renderShape(initial),
                    renderShape(reparsed),
                    "shape mismatch after print for <$source>\nprinted:\n$printed"
                )
            }.exceptionOrNull()?.let { failure ->
                "$source\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    // --- helpers ---

    private fun assertRoundTrips(samples: List<Sample>) {
        samples.forEach { sample ->
            val initial = LuaParser(luaVersion = version).parse(sample.source)
            if (sample.expectedShape != null) {
                assertEquals(sample.expectedShape, renderShape(initial), sample.source)
            }

            val printed = printer.asCode(initial)
            val reparsed = LuaParser(luaVersion = version).parse(printed)

            assertEquals(
                renderShape(initial),
                renderShape(reparsed),
                "shape drift for ${sample.source}\nprinted:\n$printed"
            )
            sample.printedFragments.forEach { fragment ->
                assertTrue(
                    printed.contains(fragment),
                    "Printed code for ${sample.source} did not contain <$fragment>:\n$printed"
                )
            }
        }
    }

    private data class Sample(
        val source: String,
        val expectedShape: String? = null,
        val printedFragments: List<String> = emptyList()
    )
}
