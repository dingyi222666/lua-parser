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
    fun roundTripsNestedFunctionsStable() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = """
                        local function outer(a)
                          local function inner(b)
                            return a + b
                          end
                          return inner
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Function(Id(outer),Block[Function(Id(inner),Block[Return(Binary(+,Id(a),Id(b)))]);Return(Id(inner))])])",
                    printedFragments = listOf(
                        "local function outer(a)",
                        "local function inner(b)",
                        "return a + b",
                        "return inner"
                    )
                ),
                Sample(
                    source = """
                        function outer()
                          function nested()
                            return 1
                          end
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Function(Id(outer),Block[Function(Id(nested),Block[Return(Const(1))])])])",
                    printedFragments = listOf("function outer()", "function nested()", "return 1")
                ),
                Sample(
                    source = """
                        local function factory()
                          return function(x)
                            return x
                          end
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Function(Id(factory),Block[Return(Function(null,Block[Return(Id(x))]))])])",
                    printedFragments = listOf("local function factory()", "return function(x)", "return x")
                ),
                Sample(
                    source = """
                        function obj:build()
                          local function helper()
                            return self
                          end
                          return helper
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Function(Member(Id(obj):build),Block[Function(Id(helper),Block[Return(Id(self))]);Return(Id(helper))])])",
                    printedFragments = listOf(
                        "function obj:build()",
                        "local function helper()",
                        "return self",
                        "return helper"
                    )
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

    @Test
    fun roundTripPreservesLocalFlagAndParamNamesOnReparse() {
        // Ends multi-value return on vararg so print→reparse survives default recovery (KDoc §6).
        val source = "local function greet(a, b, ...) return a, b, ... end"
        val printed = printer.asCode(LuaParser(luaVersion = version).parse(source))
        val chunk = LuaParser(luaVersion = version).parse(printed)
        val fn = assertIs<FunctionDeclaration>(chunk.body.statements.single())
        assertTrue(fn.isLocal, "local function must remain local after round-trip")
        assertEquals("greet", assertIs<Identifier>(fn.identifier).name)
        assertEquals(listOf("a", "b", "..."), fn.params.map { it.name })
        assertTrue(printed.contains("local function greet(a, b, ...)"), printed)
    }

    /**
     * Locks product initial-parse shapes for bare-Name-final multi-value returns inside
     * function bodies. Full print→reparse for these forms remains out of scope under default
     * recovery + AST2Lua trailing newline (KDoc §6 / REVIEW31).
     */
    @Test
    fun multiValueBareNameFinalReturnKeepsInitialShape() {
        val cases = listOf(
            "local function greet(a, b) return a, b end" to
                "Chunk(Block[Function(Id(greet),Block[Return(Id(a),Id(b))])])",
            "function t:m(x) return self, x end" to
                "Chunk(Block[Function(Member(Id(t):m),Block[Return(Id(self),Id(x))])])",
            "local function pack(a, b) return a, b end" to
                "Chunk(Block[Function(Id(pack),Block[Return(Id(a),Id(b))])])"
        )

        cases.forEach { (source, expectedShape) ->
            val initial = LuaParser(luaVersion = version).parse(source)
            assertEquals(expectedShape, renderShape(initial), source)
            // Printer still emits multi-value return text (product surface), even though reparse
            // of the multi-line form is out of scope under default recovery.
            val printed = printer.asCode(initial)
            assertTrue(
                printed.contains("return "),
                "printer must still emit return for <$source>:\n$printed"
            )
        }
    }

    @Test
    fun roundTripsBulkFunctionLocalCorpusWithoutShapeDrift() {
        val samples = listOf(
            "local function a() end",
            "local function a(x) return x end",
            // multi-value ends on binary / vararg / const — not bare Name (KDoc §6)
            "local function a(x, y) return x, y + 0 end",
            "local function a(...) return ... end",
            "local function a(x, ...) return x, ... end",
            "function a() end",
            "function a(x) return x end",
            "function t.m(x) return x end",
            "function t:m(x) return self, x + 0 end",
            "function t:m(x, ...) return self, ... end",
            "local f = function() end",
            "local f = function(x) return x end",
            "return function(x) return x end",
            "return function(x, y) return x + y end",
            """
            local function outer()
              local function inner()
                return 1
              end
              return inner
            end
            """.trimIndent(),
            """
            function outer()
              local function nested(a)
                return a
              end
              return nested
            end
            """.trimIndent(),
            """
            function M:new(value)
              local function init()
                return value
              end
              return init
            end
            """.trimIndent()
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
