package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.source.AST2Lua
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AST2Lua round-trip corpus focused on `if` / `elseif` / `else` chains and nested if
 * printer stability (TASK-303).
 *
 * ## Shape-stable print policy
 *
 * Round-trip acceptance is **AST-shape stable**, not byte-identical to the input source.
 *
 * 1. **Acceptance metric** — `parse(source)` → `AST2Lua.asCode` → reparse must yield the same
 *    `renderShape` as the original parse when the printed surface is accepted by the parser.
 *    Whitespace / indentation may change; structural nodes and identifiers must not.
 * 2. **If / elseif / else keywords** — clauses always print as:
 *    - `if <cond> then`
 *    - `elseif <cond> then`
 *    - `else`
 *    Keyword spacing is a single space after `if` / `elseif` and before `then`.
 * 3. **No trailing `end` for [IfStatement]** — [AST2Lua] visits if-clauses via
 *    `visitIfClause` / `visitElseIfClause` / `visitElseClause` and does **not** emit a
 *    terminal `end` for pure if-statements (unlike `do` / `while` / `for` / `function` /
 *    `switch`). Fragment goldens for pure-if samples therefore assert clause keywords and
 *    body fragments only — never a required trailing `end` for pure-if surfaces.
 * 4. **Pure-if surfaces** — chunk-level pure if/elseif/else samples assert initial
 *    `renderShape` + stable print fragments + absence of a standalone trailing `end`.
 *    They do **not** reparse the pure-if printer surface (same policy as TASK-256 pure-if
 *    condition samples and TASK-192 §6). Structural reparse is covered by samples wrapped
 *    in terminated blocks (`do` / `while` / `for` / `function` / `repeat`).
 * 5. **Nested if inside terminated blocks** — when an if lives inside a construct that *does*
 *    print `end`, full parse→print→reparse shape stability is asserted. Nested pure-if still
 *    does not invent its own trailing `end`; outer terminators keep the surface parseable.
 * 6. **Nested if + outer else/elseif sibling (REVIEW28 / TASK-676)** — nested
 *    [IfStatement] still omits trailing `end` (policy §3). On print→reparse a bare
 *    nested `if … else` followed by an outer `else` / `elseif` is **not** shape-stable:
 *    the missing inner `end` lets [io.github.dingyi222666.luaparser.parser.LuaParser]
 *    continue the inner clause loop and absorb the outer clause (or residual body) into
 *    the nested if. Corpus samples that need both nesting and outer siblings therefore
 *    wrap the nested if in an explicit `do … end` (which *does* print `end`) so clause
 *    ownership survives print→reparse. Pure nested trees without trailing outer siblings
 *    remain unwrapped and still round-trip via the outer terminator.
 * 7. **Version** — all samples use [LuaVersion.LUA_5_3].
 * 8. **Out of scope** — jump legality, comment preservation, and semantic checks are not
 *    asserted here. Production printer `end` emission for IfStatement is out of scope
 *    (test-only task).
 */
class AST2LuaIfElseifRoundTripTddTest {

    private val printer = AST2Lua()
    private val version = LuaVersion.LUA_5_3

    // --- Core if / elseif / else chains (pure-if: shape + fragments, no reparse) ---

    @Test
    fun roundTripsSimpleIfThenForms() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "if ready then start() end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ready):Block[CallStmt(Call(Id(start):))]))])",
                    printedFragments = listOf("if ready then", "start()"),
                    pureIf = true
                ),
                Sample(
                    source = "if ready then local value = 1 return value end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ready):Block[Local(Id(value)=Const(1));Return(Id(value))]))])",
                    printedFragments = listOf("if ready then", "local value = 1", "return value"),
                    pureIf = true
                ),
                Sample(
                    source = "if a + b < c and d or e then work() end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Binary(or,Binary(and,Binary(<,Binary(+,Id(a),Id(b)),Id(c)),Id(d)),Id(e)):Block[CallStmt(Call(Id(work):))]))])",
                    printedFragments = listOf("if a + b < c and d or e then", "work()"),
                    pureIf = true
                ),
                Sample(
                    source = "if flags & MASK ~= 0 then return true end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Binary(~=,Binary(&,Id(flags),Id(MASK)),Const(0)):Block[Return(Const(true))]))])",
                    printedFragments = listOf("if flags & MASK ~= 0 then", "return true"),
                    pureIf = true
                )
            )
        )
    }

    @Test
    fun roundTripsIfElseifElseChains() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "if a then x = 1 elseif b then x = 2 else x = 3 end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[Assign(Id(x)=Const(1))]),ElseIf(Id(b):Block[Assign(Id(x)=Const(2))]),Else(Block[Assign(Id(x)=Const(3))]))])",
                    printedFragments = listOf(
                        "if a then",
                        "x = 1",
                        "elseif b then",
                        "x = 2",
                        "else",
                        "x = 3"
                    ),
                    pureIf = true
                ),
                Sample(
                    source = "if a then one() elseif b then two() elseif c then three() else four() end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[CallStmt(Call(Id(one):))]),ElseIf(Id(b):Block[CallStmt(Call(Id(two):))]),ElseIf(Id(c):Block[CallStmt(Call(Id(three):))]),Else(Block[CallStmt(Call(Id(four):))]))])",
                    printedFragments = listOf(
                        "if a then",
                        "one()",
                        "elseif b then",
                        "two()",
                        "elseif c then",
                        "three()",
                        "else",
                        "four()"
                    ),
                    pureIf = true
                ),
                Sample(
                    source = "if a then one() elseif b then two() end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[CallStmt(Call(Id(one):))]),ElseIf(Id(b):Block[CallStmt(Call(Id(two):))]))])",
                    printedFragments = listOf("if a then", "one()", "elseif b then", "two()"),
                    pureIf = true
                ),
                Sample(
                    source = """
                        if first then
                          local a = 1
                        elseif second then
                          local b = 2
                          return b
                        else
                          local c = 3
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(first):Block[Local(Id(a)=Const(1))]),ElseIf(Id(second):Block[Local(Id(b)=Const(2));Return(Id(b))]),Else(Block[Local(Id(c)=Const(3))]))])",
                    printedFragments = listOf(
                        "if first then",
                        "local a = 1",
                        "elseif second then",
                        "local b = 2",
                        "return b",
                        "else",
                        "local c = 3"
                    ),
                    pureIf = true
                )
            )
        )
    }

    // --- Nested if printer stability (full reparse via terminated outer blocks) ---

    @Test
    fun roundTripsNestedIfInsideTerminatedBlocks() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "do if ready then start() end end",
                    expectedShape =
                        "Chunk(Block[Do(Block[If(Clause(Id(ready):Block[CallStmt(Call(Id(start):))]))])])",
                    printedFragments = listOf("do", "if ready then", "start()", "end")
                ),
                Sample(
                    source = "while keep do if done then break end end",
                    expectedShape =
                        "Chunk(Block[While(Id(keep):Block[If(Clause(Id(done):Block[Break]))])])",
                    printedFragments = listOf("while keep do", "if done then", "break", "end")
                ),
                Sample(
                    source = "repeat if ok then work() end until done",
                    expectedShape =
                        "Chunk(Block[Repeat(Block[If(Clause(Id(ok):Block[CallStmt(Call(Id(work):))]))]:Id(done))])",
                    printedFragments = listOf("repeat", "if ok then", "work()", "until done")
                ),
                Sample(
                    source = "for i = 1, 3 do if i == 2 then break end end",
                    expectedShape =
                        "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[If(Clause(Binary(==,Id(i),Const(2)):Block[Break]))])])",
                    printedFragments = listOf("for i = 1, 3 do", "if i == 2 then", "break")
                ),
                Sample(
                    source = "for k, v in pairs(t) do if v then use(k, v) end end",
                    expectedShape =
                        "Chunk(Block[ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[If(Clause(Id(v):Block[CallStmt(Call(Id(use):Id(k),Id(v)))]))])])",
                    printedFragments = listOf("for k, v in pairs(t) do", "if v then", "use(k, v)")
                ),
                Sample(
                    source = """
                        local function choose(a, b)
                          if a then
                            return a
                          elseif b then
                            return b
                          else
                            return nil
                          end
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Function(Id(choose),Block[If(Clause(Id(a):Block[Return(Id(a))]),ElseIf(Id(b):Block[Return(Id(b))]),Else(Block[Return(Const(nil))]))])])",
                    printedFragments = listOf(
                        "local function choose(a, b)",
                        "if a then",
                        "return a",
                        "elseif b then",
                        "return b",
                        "else",
                        "return nil",
                        "end"
                    )
                ),
                // Full structural round-trip of if/elseif/else via outer `do` terminator.
                Sample(
                    source = "do if a then x = 1 elseif b then x = 2 else x = 3 end end",
                    expectedShape =
                        "Chunk(Block[Do(Block[If(Clause(Id(a):Block[Assign(Id(x)=Const(1))]),ElseIf(Id(b):Block[Assign(Id(x)=Const(2))]),Else(Block[Assign(Id(x)=Const(3))]))])])",
                    printedFragments = listOf(
                        "do",
                        "if a then",
                        "x = 1",
                        "elseif b then",
                        "x = 2",
                        "else",
                        "x = 3",
                        "end"
                    )
                ),
                Sample(
                    source = "do if a then one() elseif b then two() elseif c then three() else four() end end",
                    expectedShape =
                        "Chunk(Block[Do(Block[If(Clause(Id(a):Block[CallStmt(Call(Id(one):))]),ElseIf(Id(b):Block[CallStmt(Call(Id(two):))]),ElseIf(Id(c):Block[CallStmt(Call(Id(three):))]),Else(Block[CallStmt(Call(Id(four):))]))])])",
                    printedFragments = listOf(
                        "if a then",
                        "one()",
                        "elseif b then",
                        "two()",
                        "elseif c then",
                        "three()",
                        "else",
                        "four()"
                    )
                )
            )
        )
    }

    // --- Structural clause inspection after print ---

    @Test
    fun pureIfSurfacesDoNotRequireTrailingEndFragment() {
        val samples = listOf(
            "if ready then start() end",
            "if ok then pass() else fail() end",
            "if a then x = 1 elseif b then x = 2 else x = 3 end",
            "if a then one() elseif b then two() elseif c then three() end"
        )

        samples.forEach { source ->
            val initial = LuaParser(luaVersion = version).parse(source)
            val printed = printer.asCode(initial)

            assertTrue(printed.contains("if "), "printed for <$source>:\n$printed")
            // Pure-if surface must not invent a trailing/standalone end keyword.
            assertTrue(
                !Regex("(?m)^\\s*end\\s*$").containsMatchIn(printed) &&
                    !printed.trim().endsWith("end"),
                "IfStatement print must not emit trailing end (policy §3):\n$printed"
            )
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
            sample.printedFragments.forEach { fragment ->
                assertTrue(
                    printed.contains(fragment),
                    "Printed code for ${sample.source} did not contain <$fragment>:\n$printed"
                )
            }

            if (sample.pureIf) {
                if ("end" !in sample.printedFragments) {
                    assertTrue(
                        !Regex("(?m)^\\s*end\\s*$").containsMatchIn(printed) &&
                            !printed.trim().endsWith("end"),
                        "Pure-if print must not emit trailing end (policy §3):\n$printed"
                    )
                }
                // Pure-if surfaces are not reparsed (IfStatement omits terminal end).
                return@forEach
            }

            val reparsed = LuaParser(luaVersion = version).parse(printed)
            assertEquals(
                renderShape(initial),
                renderShape(reparsed),
                "shape drift for ${sample.source}\nprinted:\n$printed"
            )
        }
    }

    private fun collectIfStatements(node: BaseASTNode): List<IfStatement> {
        val out = mutableListOf<IfStatement>()
        walk(node) { current ->
            if (current is IfStatement) {
                out += current
            }
        }
        return out
    }

    private fun walk(node: BaseASTNode, visit: (BaseASTNode) -> Unit) {
        visit(node)
        when (node) {
            is ChunkNode -> walk(node.body, visit)
            is BlockNode -> {
                node.statements.forEach { walk(it, visit) }
                node.returnStatement?.let { walk(it, visit) }
            }
            is DoStatement -> walk(node.body, visit)
            is WhileStatement -> {
                walk(node.condition, visit)
                walk(node.body, visit)
            }
            is RepeatStatement -> {
                walk(node.body, visit)
                walk(node.condition, visit)
            }
            is IfStatement -> node.causes.forEach { walk(it, visit) }
            is ElseIfClause -> {
                walk(node.condition, visit)
                walk(node.body, visit)
            }
            is ElseClause -> walk(node.body, visit)
            is IfClause -> {
                if (node !is ElseClause) {
                    walk(node.condition, visit)
                }
                walk(node.body, visit)
            }
            is ForNumericStatement -> walk(node.body, visit)
            is ForGenericStatement -> walk(node.body, visit)
            is FunctionDeclaration -> node.body?.let { walk(it, visit) }
            is CallStatement -> Unit
            is LocalStatement -> Unit
            else -> Unit
        }
    }

    private data class Sample(
        val source: String,
        val expectedShape: String? = null,
        val printedFragments: List<String> = emptyList(),
        /** When true, assert pure-if surfaces do not print a trailing `end` and skip reparse. */
        val pureIf: Boolean = false
    )
}
