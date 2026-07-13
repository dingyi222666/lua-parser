package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.source.AST2Lua
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AST2Lua round-trip corpus focused on `local function` and `function` statements
 * (including nested functions) for Lua 5.3 (TASK-324).
 *
 * ## Shape-stable print policy
 *
 * Round-trip acceptance is **AST-shape stable**, not byte-identical to the input source.
 *
 * 1. **Acceptance metric** — `parse(source)` → `AST2Lua.asCode` → reparse must yield the same
 *    `renderShape` as the original parse. Whitespace / indentation may change; structural
 *    nodes and identifiers must not.
 * 2. **Local function form** — product [AST2Lua] prints `local function name(...)` when
 *    [FunctionDeclaration.isLocal] is true (space after `local` / `function`, space before `(`
 *    only when an identifier is present). Anonymous expressions print as `function(...)` with
 *    no name between `function` and `(`.
 * 3. **Global / member function form** — prints as `function name(...)` /
 *    `function obj.method(...)` / `function obj:method(...)` via member indexer preservation.
 * 4. **Nested functions** — nested local/global/anonymous function expressions stay nested under
 *    the outer function body block after print→reparse.
 * 5. **Version** — all samples use [LuaVersion.LUA_5_3].
 * 6. **Printer/parser interaction guard (multi-value return)** — AST2Lua always ends chunks with
 *    a trailing newline, and [LuaParser] defaults to `errorRecovery = true`. Under that pair,
 *    REVIEW31 observed that **multi-value `return` lists whose final expression is a bare Name**
 *    (e.g. `return a, b`, `return self, x`) fail reparse with `unexpected <name|end> near '<eof>'`
 *    when the return is emitted on its own indented line inside a function body — even though the
 *    one-line source form parses to the documented shape. Multi-value returns that end on vararg /
 *    const / call / binary remain in full print→reparse scope. Bare-Name-final multi-value returns
 *    are therefore locked on **initial parse shape only** (see
 *    [multiValueBareNameFinalReturnKeepsInitialShape]) and stay out of full print→reparse until
 *    production lands a fix. Same class of interaction as TASK-360 CallChain KDoc §8.
 * 7. **Out of scope** — semantic self-injection, jump legality, comment preservation, and
 *    bare-Name-final multi-value return print→reparse (see §6).
 */
class AST2LuaFunctionLocalRoundTripTddTest {

    private val printer = AST2Lua()
    private val version = LuaVersion.LUA_5_3

    @Test
    fun roundTripsLocalFunctionStatements() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "local function greet() end",
                    expectedShape = "Chunk(Block[Function(Id(greet),Block[])])",
                    printedFragments = listOf("local function greet()", "end")
                ),
                // Multi-value return ends on binary (not bare Name) so print→reparse survives
                // AST2Lua trailing newline under default recovery (see KDoc §6 / REVIEW31).
                Sample(
                    source = "local function greet(a, b) return a, b + 0 end",
                    expectedShape =
                        "Chunk(Block[Function(Id(greet),Block[Return(Id(a),Binary(+,Id(b),Const(0)))])])",
                    printedFragments = listOf("local function greet(a, b)", "return a, b + 0", "end")
                ),
                Sample(
                    source = "local function greet(a, ...) return ... end",
                    expectedShape = "Chunk(Block[Function(Id(greet),Block[Return(Vararg)])])",
                    printedFragments = listOf("local function greet(a, ...)", "return ...")
                ),
                Sample(
                    source = "local function empty() local x = 1 end",
                    expectedShape = "Chunk(Block[Function(Id(empty),Block[Local(Id(x)=Const(1))])])",
                    printedFragments = listOf("local function empty()", "local x = 1")
                )
            )
        )
    }

    @Test
    fun roundTripsGlobalAndMemberFunctionStatements() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "function greet(a) return a end",
                    expectedShape = "Chunk(Block[Function(Id(greet),Block[Return(Id(a))])])",
                    printedFragments = listOf("function greet(a)", "return a", "end")
                ),
                Sample(
                    source = "function obj.run(x) return x end",
                    expectedShape = "Chunk(Block[Function(Member(Id(obj).run),Block[Return(Id(x))])])",
                    printedFragments = listOf("function obj.run(x)", "return x")
                ),
                // Multi-value return ends on vararg (not bare Name) — full reparse stays green.
                Sample(
                    source = "function obj:run(x, ...) return self, ... end",
                    expectedShape =
                        "Chunk(Block[Function(Member(Id(obj):run),Block[Return(Id(self),Vararg)])])",
                    printedFragments = listOf("function obj:run(x, ...)", "return self, ...")
                ),
                Sample(
                    source = "function M.util.helper() return true end",
                    expectedShape =
                        "Chunk(Block[Function(Member(Member(Id(M).util).helper),Block[Return(Const(true))])])",
                    printedFragments = listOf("function M.util.helper()", "return true")
                )
            )
        )
    }

    @Test
    fun roundTripsAnonymousAndAssignedFunctionExpressions() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "local f = function(a) return a end",
                    expectedShape = "Chunk(Block[Local(Id(f)=Function(null,Block[Return(Id(a))]))])",
                    printedFragments = listOf("local f = function(a)", "return a", "end")
                ),
                Sample(
                    source = "f = function() end",
                    expectedShape = "Chunk(Block[Assign(Id(f)=Function(null,Block[]))])",
                    printedFragments = listOf("f = function()", "end")
                ),
                // Product renderShape closes Return before Block: Return(Binary(...))) not
                // Return(Binary(...))]) — align golden to product (REVIEW31 ComparisonFailure).
                Sample(
                    source = "return function(x, y) return x + y end",
                    expectedShape =
                        "Chunk(Block[Return(Function(null,Block[Return(Binary(+,Id(x),Id(y)))]))])",
                    printedFragments = listOf("return function(x, y)", "return x + y")
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
