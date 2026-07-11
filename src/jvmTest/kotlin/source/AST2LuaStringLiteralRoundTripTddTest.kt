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
    fun roundTripsUnicodeHexAndDecimalEscapesWithoutRewrite() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return \"\\u{41}\"",
                    expectedShape = "Chunk(Block[Return(Const(\"\\u{41}\"))])",
                    printedFragments = listOf("\"\\u{41}\"")
                ),
                Sample(
                    source = "return \"\\u{1F600}\"",
                    expectedShape = "Chunk(Block[Return(Const(\"\\u{1F600}\"))])",
                    printedFragments = listOf("\"\\u{1F600}\"")
                ),
                Sample(
                    source = "return \"\\x41\\x42\"",
                    expectedShape = "Chunk(Block[Return(Const(\"\\x41\\x42\"))])",
                    printedFragments = listOf("\"\\x41\\x42\"")
                ),
                Sample(
                    source = "return \"\\065\\066\"",
                    expectedShape = "Chunk(Block[Return(Const(\"\\065\\066\"))])",
                    printedFragments = listOf("\"\\065\\066\"")
                ),
                Sample(
                    source = "return \"A\\u{42}C\\x44\"",
                    expectedShape = "Chunk(Block[Return(Const(\"A\\u{42}C\\x44\"))])",
                    printedFragments = listOf("\"A\\u{42}C\\x44\"")
                ),
                Sample(
                    source = "return '\\u{41}\\x42\\066'",
                    expectedShape = "Chunk(Block[Return(Const('\\u{41}\\x42\\066'))])",
                    printedFragments = listOf("'\\u{41}\\x42\\066'")
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

    @Test
    fun doesNotInventUnsupportedEscapeRewrites() {
        // Printer must keep raw escape text rather than decoding to glyphs / alternate forms.
        val cases = listOf(
            "return \"\\u{41}\"" to "\\u{41}",
            "return \"\\x41\"" to "\\x41",
            "return \"\\065\"" to "\\065",
            "return \"\\n\\t\\r\"" to "\\n\\t\\r",
            "return \"\\a\\b\\f\\v\"" to "\\a\\b\\f\\v",
            "return [[raw \\u{41} text]]" to "[[raw \\u{41} text]]",
            "return [=[raw \\x41]=]" to "[=[raw \\x41]=]"
        )

        cases.forEach { (source, fragment) ->
            val initial = LuaParser(luaVersion = version).parse(source)
            val printed = printer.asCode(initial)
            val reparsed = LuaParser(luaVersion = version).parse(printed)
            assertEquals(renderShape(initial), renderShape(reparsed), printed)
            assertTrue(
                printed.contains(fragment),
                "Printer rewrote escape form; expected raw fragment <$fragment> in:\n$printed"
            )
            // Explicit non-invention: decoded single-letter 'A' alone is not an acceptable rewrite
            // of \\u{41} when the AST still holds the escape lexeme.
            if (fragment.contains("\\u{41}")) {
                val ret = assertIs<ReturnStatement>(reparsed.body.returnStatement)
                val const = assertIs<ConstantNode>(ret.arguments.single())
                assertTrue(
                    const.rawValue.toString().contains("\\u{41}") ||
                        const.rawValue.toString().contains("[[raw \\u{41} text]]"),
                    "rawValue must keep escape text: ${const.rawValue}"
                )
            }
        }
    }

    @Test
    fun roundTripsBulkStringLiteralCorpusWithoutShapeDrift() {
        val samples = listOf(
            "return \"\"",
            "return ''",
            "return \"abc\"",
            "return 'abc'",
            "return \"a\\nb\"",
            "return \"\\t\\r\\n\"",
            "return \"\\\\\"",
            "return \"\\\"\"",
            "return '\\''",
            "return \"\\u{0}\"",
            "return \"\\u{10FFFF}\"",
            "return \"\\x00\\xFF\"",
            "return \"\\000\\255\"",
            "return \"\\a\\b\\f\\v\"",
            "return [[]]",
            "return [[hello world]]",
            "return [=[a [[b]] c]=]",
            "return [==[x]=]y]==]",
            "return [===[keep ]==] end]===]",
            "local s = \"hi\"",
            "local s = 'hi\\n'",
            "print('x')",
            "print(\"y\")",
            "return a .. \"b\" .. 'c'",
            "return a .. \"b\" .. 'c' .. [[d]]",
            "local t = { \"a\", 'b', [[c]] }",
            "local t = { name = \"widget\", ['k'] = 'v' }",
            "local t = { label = 'plain', [\"k\"] = \"v\" }",
            "return f(\"a\", 'b', [[c]])",
            "return [[raw \\n \\u{41}]]",
            "return \"A\\u{42}C\\x44\\065\""
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
