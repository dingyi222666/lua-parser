package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.source.AST2Lua
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AST2Lua round-trip corpus for short/long string literals and stable escape printing (TASK-327).
 *
 * ## Shape-stable print policy
 *
 * Round-trip acceptance is **AST-shape stable**, not byte-identical to the input source.
 *
 * 1. **Acceptance metric** — parse → print → reparse preserves `renderShape`.
 * 2. **Short strings** — printer re-emits [ConstantNode.rawValue] (source lexeme text), so
 *    escapes such as `\\n`, `\\t`, `\\u{...}`, `\\xHH`, and `\\ddd` are preserved as written
 *    rather than inventing alternate escape rewrites. Quote style (`"` vs `'`) is part of the
 *    raw lexeme and must survive print→reparse.
 * 3. **Long strings** — `[[...]]` / `[=[...]=]` / deeper long-bracket forms keep long-bracket
 *    raw values (including interior newlines and nested lower-level brackets).
 * 4. **Does not invent unsupported escape rewrites** — e.g. does not normalize `\\u{41}` to a
 *    literal `A`, rewrite `\\x41` to `\\065`, or change quote styles unless the AST raw value
 *    already differs.
 * 5. **Contexts** — string literals appear in returns, locals, call args, table record/bracket
 *    fields, and `..` chains. Concatenation is **right-associative** in Lua 5.3, so
 *    `a .. "x" .. [[y]]` shapes as `Binary(..,Id(a),Binary(..,Const("x"),Const([[y]])))`.
 * 6. **Bracket string keys** — `['k']` keeps the key's own quote style in shape
 *    (`Const('k')` for single-quoted keys, `Const("k")` for double-quoted keys).
 * 7. Version: [LuaVersion.LUA_5_3].
 * 8. **Out of scope** — comment preservation, recovery of broken string tokens, and production
 *    printer changes (test-only corpus).
 */
class AST2LuaStringLiteralRoundTripTddTest {

    private val printer = AST2Lua()
    private val version = LuaVersion.LUA_5_3

    @Test
    fun roundTripsShortStringLiteralsWithCommonEscapes() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return \"hello\"",
                    expectedShape = "Chunk(Block[Return(Const(\"hello\"))])",
                    printedFragments = listOf("\"hello\"")
                ),
                Sample(
                    source = "return 'hello'",
                    expectedShape = "Chunk(Block[Return(Const('hello'))])",
                    printedFragments = listOf("'hello'")
                ),
                Sample(
                    source = "return \"a\\nb\\tc\"",
                    expectedShape = "Chunk(Block[Return(Const(\"a\\nb\\tc\"))])",
                    printedFragments = listOf("\"a\\nb\\tc\"")
                ),
                Sample(
                    source = "return \"quote\\\"inside\"",
                    expectedShape = "Chunk(Block[Return(Const(\"quote\\\"inside\"))])",
                    printedFragments = listOf("\"quote\\\"inside\"")
                ),
                Sample(
                    source = "return 'quote\\'inside'",
                    expectedShape = "Chunk(Block[Return(Const('quote\\'inside'))])",
                    printedFragments = listOf("'quote\\'inside'")
                ),
                Sample(
                    source = "return \"\\\\path\"",
                    expectedShape = "Chunk(Block[Return(Const(\"\\\\path\"))])",
                    printedFragments = listOf("\"\\\\path\"")
                ),
                Sample(
                    source = "return \"\\a\\b\\f\\v\"",
                    expectedShape = "Chunk(Block[Return(Const(\"\\a\\b\\f\\v\"))])",
                    printedFragments = listOf("\"\\a\\b\\f\\v\"")
                ),
                Sample(
                    source = "return \"line\\z\n  continued\"",
                    expectedShape = "Chunk(Block[Return(Const(\"line\\z\n  continued\"))])",
                    printedFragments = listOf("\"line\\z")
                )
            )
        )
    }

    @Test
    fun roundTripsLongStringLiteralsStable() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return [[hello]]",
                    expectedShape = "Chunk(Block[Return(Const([[hello]]))])",
                    printedFragments = listOf("[[hello]]")
                ),
                Sample(
                    source = "return [=[hello [[nested]] still]=]",
                    expectedShape = "Chunk(Block[Return(Const([=[hello [[nested]] still]=]))])",
                    printedFragments = listOf("[=[hello [[nested]] still]=]")
                ),
                Sample(
                    source = "return [[line1\nline2]]",
                    expectedShape = "Chunk(Block[Return(Const([[line1\nline2]]))])",
                    printedFragments = listOf("[[line1\nline2]]")
                ),
                Sample(
                    source = "return [==[keep ]=] end]==]",
                    expectedShape = "Chunk(Block[Return(Const([==[keep ]=] end]==]))])",
                    printedFragments = listOf("[==[keep ]=] end]==]")
                ),
                Sample(
                    source = "return [===[triple [[and]] [=[nested]=] ok]===]",
                    expectedShape =
                        "Chunk(Block[Return(Const([===[triple [[and]] [=[nested]=] ok]===]))])",
                    printedFragments = listOf("[===[triple [[and]] [=[nested]=] ok]===]")
                ),
                Sample(
                    source = "return [[raw \\n \\u{41} stays raw]]",
                    expectedShape =
                        "Chunk(Block[Return(Const([[raw \\n \\u{41} stays raw]]))])",
                    printedFragments = listOf("[[raw \\n \\u{41} stays raw]]")
                )
            )
        )
    }

    @Test
    fun roundTripsStringsInAssignmentsCallsAndTables() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "local msg = \"hi\\n\"",
                    expectedShape = "Chunk(Block[Local(Id(msg)=Const(\"hi\\n\"))])",
                    printedFragments = listOf("local msg = \"hi\\n\"")
                ),
                Sample(
                    source = "local msg = 'hi\\t'",
                    expectedShape = "Chunk(Block[Local(Id(msg)=Const('hi\\t'))])",
                    printedFragments = listOf("local msg = 'hi\\t'")
                ),
                Sample(
                    source = "print(\"ok\")",
                    expectedShape = "Chunk(Block[CallStmt(Call(Id(print):Const(\"ok\")))])",
                    printedFragments = listOf("print(\"ok\")")
                ),
                Sample(
                    source = "print('ok')",
                    expectedShape = "Chunk(Block[CallStmt(Call(Id(print):Const('ok')))])",
                    printedFragments = listOf("print('ok')")
                ),
                Sample(
                    source = "local t = { name = \"widget\", ['k'] = 'v' }",
                    expectedShape =
                        "Chunk(Block[Local(Id(t)=Table(TableKeyString(Id(name)=Const(\"widget\")),TableKey(Const('k')=Const('v'))))])",
                    printedFragments = listOf("name = \"widget\"", "['k'] = 'v'")
                ),
                Sample(
                    source = "local t = { label = 'plain', [\"k\"] = \"v\" }",
                    expectedShape =
                        "Chunk(Block[Local(Id(t)=Table(TableKeyString(Id(label)=Const('plain')),TableKey(Const(\"k\")=Const(\"v\"))))])",
                    printedFragments = listOf("label = 'plain'", "[\"k\"] = \"v\"")
                ),
                // `..` is right-associative (Lua 5.3): a .. "x" .. [[y]]
                Sample(
                    source = "return a .. \"x\" .. [[y]]",
                    expectedShape =
                        "Chunk(Block[Return(Binary(..,Id(a),Binary(..,Const(\"x\"),Const([[y]]))))])",
                    printedFragments = listOf("\"x\"", "[[y]]")
                ),
                Sample(
                    source = "return a .. 'b' .. \"c\" .. [=[d]=]",
                    expectedShape =
                        "Chunk(Block[Return(Binary(..,Id(a),Binary(..,Const('b'),Binary(..,Const(\"c\"),Const([=[d]=])))))])",
                    printedFragments = listOf("'b'", "\"c\"", "[=[d]=]")
                ),
                Sample(
                    source = "return f(\"a\", 'b', [[c]])",
                    expectedShape =
                        "Chunk(Block[Return(Call(Id(f):Const(\"a\"),Const('b'),Const([[c]])))])",
                    printedFragments = listOf("f(\"a\", 'b', [[c]])")
                )
            )
        )
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
