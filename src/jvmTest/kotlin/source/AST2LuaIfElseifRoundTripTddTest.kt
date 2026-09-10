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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AST2Lua round-trip corpus focused on `if` / `elseif` / `else` chains and nested if
 * printer stability (TASK-303, terminal-`end` fix from the adversarial printer audit).
 *
 * ## Shape-stable print policy
 *
 * Round-trip acceptance is **AST-shape stable**, not byte-identical to the input source.
 *
 * 1. **Acceptance metric** — `parse(source)` → `AST2Lua.asCode` → reparse must yield the same
 *    `renderShape` as the original parse **and** zero parser recovery diagnostics.
 *    Whitespace / indentation may change; structural nodes and identifiers must not.
 * 2. **If / elseif / else keywords** — clauses always print as:
 *    - `if <cond> then`
 *    - `elseif <cond> then`
 *    - `else`
 *    Keyword spacing is a single space after `if` / `elseif` and before `then`.
 * 3. **Exactly one trailing `end` per [IfStatement]** — [AST2Lua.visitIfStatement] prints the
 *    clause chain via `visitIfClause` / `visitElseIfClause` / `visitElseClause` and then emits
 *    a single terminating `end` (like `do` / `while` / `for` / `function` / `switch`). Clauses
 *    never emit their own `end`, so `if … elseif … else … end` prints one `end` total. The
 *    printer used to omit this terminator, which made every printed if-statement absorb the
 *    statements following it on reparse (and corrupted LSP document formatting).
 * 4. **Pure-if surfaces** — chunk-level pure if/elseif/else samples assert initial
 *    `renderShape`, stable print fragments (including the trailing `end`), a standalone
 *    `end` count, and full print→reparse shape stability.
 * 5. **Nested if inside terminated blocks** — when an if lives inside a construct that also
 *    prints `end`, the printed surface carries one `end` per block (inner if + outer block)
 *    and full parse→print→reparse shape stability is asserted.
 * 6. **Nested if + outer else/elseif sibling (REVIEW28 / TASK-676)** — a nested
 *    [IfStatement] now closes itself, so a bare nested `if … else … end` followed by an
 *    outer `else` / `elseif` is shape-stable without an explicit `do … end` wrapper: the
 *    outer clause can no longer be absorbed into the nested if on reparse.
 * 7. **Sequential if-statements** — consecutive if-statements (and trailing statements after
 *    an if) keep their statement count on reparse; the missing terminator used to fold them
 *    into the preceding if body.
 * 8. **Version** — all samples use [LuaVersion.LUA_5_3].
 * 9. **Out of scope** — jump legality, comment preservation, and semantic checks are not
 *    asserted here.
 */
class AST2LuaIfElseifRoundTripTddTest {

    private val printer = AST2Lua()
    private val version = LuaVersion.LUA_5_3

    // --- Core if / elseif / else chains (pure-if: shape + fragments + reparse) ---

    @Test
    fun roundTripsSimpleIfThenForms() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "if ready then start() end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ready):Block[CallStmt(Call(Id(start):))]))])",
                    printedFragments = listOf("if ready then", "start()", "end"),
                    expectedEndCount = 1
                ),
                Sample(
                    source = "if ready then local value = 1 return value end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ready):Block[Local(Id(value)=Const(1));Return(Id(value))]))])",
                    printedFragments = listOf("if ready then", "local value = 1", "return value", "end"),
                    expectedEndCount = 1
                ),
                Sample(
                    source = "if a + b < c and d or e then work() end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Binary(or,Binary(and,Binary(<,Binary(+,Id(a),Id(b)),Id(c)),Id(d)),Id(e)):Block[CallStmt(Call(Id(work):))]))])",
                    printedFragments = listOf("if a + b < c and d or e then", "work()", "end"),
                    expectedEndCount = 1
                ),
                Sample(
                    source = "if flags & MASK ~= 0 then return true end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Binary(~=,Binary(&,Id(flags),Id(MASK)),Const(0)):Block[Return(Const(true))]))])",
                    printedFragments = listOf("if flags & MASK ~= 0 then", "return true", "end"),
                    expectedEndCount = 1
                ),
                Sample(
                    source = "if ready then end",
                    expectedShape = "Chunk(Block[If(Clause(Id(ready):Block[]))])",
                    printedFragments = listOf("if ready then", "end"),
                    expectedEndCount = 1
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
                        "x = 3",
                        "end"
                    ),
                    expectedEndCount = 1
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
                        "four()",
                        "end"
                    ),
                    expectedEndCount = 1
                ),
                Sample(
                    source = "if a then one() elseif b then two() end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[CallStmt(Call(Id(one):))]),ElseIf(Id(b):Block[CallStmt(Call(Id(two):))]))])",
                    printedFragments = listOf("if a then", "one()", "elseif b then", "two()", "end"),
                    expectedEndCount = 1
                ),
                Sample(
                    source = "if ok then pass() else fail() end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ok):Block[CallStmt(Call(Id(pass):))]),Else(Block[CallStmt(Call(Id(fail):))]))])",
                    printedFragments = listOf("if ok then", "pass()", "else", "fail()", "end"),
                    expectedEndCount = 1
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
                        "local c = 3",
                        "end"
                    ),
                    expectedEndCount = 1
                )
            )
        )
    }

    // --- Nested if printer stability (one `end` per block) ---

    @Test
    fun roundTripsNestedIfInsideTerminatedBlocks() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "do if ready then start() end end",
                    expectedShape =
                        "Chunk(Block[Do(Block[If(Clause(Id(ready):Block[CallStmt(Call(Id(start):))]))])])",
                    printedFragments = listOf("do", "if ready then", "start()", "end"),
                    expectedEndCount = 2
                ),
                Sample(
                    source = "while keep do if done then break end end",
                    expectedShape =
                        "Chunk(Block[While(Id(keep):Block[If(Clause(Id(done):Block[Break]))])])",
                    printedFragments = listOf("while keep do", "if done then", "break", "end"),
                    expectedEndCount = 2
                ),
                Sample(
                    source = "repeat if ok then work() end until done",
                    expectedShape =
                        "Chunk(Block[Repeat(Block[If(Clause(Id(ok):Block[CallStmt(Call(Id(work):))]))]:Id(done))])",
                    printedFragments = listOf("repeat", "if ok then", "work()", "end", "until done"),
                    expectedEndCount = 1
                ),
                Sample(
                    source = "for i = 1, 3 do if i == 2 then break end end",
                    expectedShape =
                        "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[If(Clause(Binary(==,Id(i),Const(2)):Block[Break]))])])",
                    printedFragments = listOf("for i = 1, 3 do", "if i == 2 then", "break", "end"),
                    expectedEndCount = 2
                ),
                Sample(
                    source = "for k, v in pairs(t) do if v then use(k, v) end end",
                    expectedShape =
                        "Chunk(Block[ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[If(Clause(Id(v):Block[CallStmt(Call(Id(use):Id(k),Id(v)))]))])])",
                    printedFragments = listOf("for k, v in pairs(t) do", "if v then", "use(k, v)", "end"),
                    expectedEndCount = 2
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
                    ),
                    expectedEndCount = 2
                ),
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
                    ),
                    expectedEndCount = 2
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
                        "four()",
                        "end"
                    ),
                    expectedEndCount = 2
                )
            )
        )
    }

    // --- Nested if with outer else / elseif siblings (policy §6) ---

    @Test
    fun roundTripsNestedIfFollowedByOuterElseOrElseifWithoutDoWrapper() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "if a then if b then x = 1 else x = 2 end else x = 3 end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[If(Clause(Id(b):Block[Assign(Id(x)=Const(1))]),Else(Block[Assign(Id(x)=Const(2))]))]),Else(Block[Assign(Id(x)=Const(3))]))])",
                    printedFragments = listOf("if a then", "if b then", "x = 1", "else", "x = 2", "x = 3", "end"),
                    expectedEndCount = 2
                ),
                Sample(
                    source = "if a then if b then x = 1 end elseif c then x = 2 end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[If(Clause(Id(b):Block[Assign(Id(x)=Const(1))]))]),ElseIf(Id(c):Block[Assign(Id(x)=Const(2))]))])",
                    printedFragments = listOf("if a then", "if b then", "x = 1", "elseif c then", "x = 2", "end"),
                    expectedEndCount = 2
                ),
                Sample(
                    source = "if a then if b then x = 1 elseif c then x = 2 end after() else other() end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[If(Clause(Id(b):Block[Assign(Id(x)=Const(1))]),ElseIf(Id(c):Block[Assign(Id(x)=Const(2))]));CallStmt(Call(Id(after):))]),Else(Block[CallStmt(Call(Id(other):))]))])",
                    printedFragments = listOf("if a then", "if b then", "elseif c then", "after()", "else", "other()", "end"),
                    expectedEndCount = 2
                )
            )
        )
    }

    // --- Sequential if-statements keep their statement count (policy §7) ---

    @Test
    fun roundTripsSequentialIfStatementsWithoutAbsorbingFollowingStatements() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "if a then x() end if b then y() end z()",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[CallStmt(Call(Id(x):))]));If(Clause(Id(b):Block[CallStmt(Call(Id(y):))]));CallStmt(Call(Id(z):))])",
                    printedFragments = listOf("if a then", "x()", "if b then", "y()", "z()", "end"),
                    expectedEndCount = 2
                ),
                Sample(
                    source = "if a then x() else y() end local after = 1 return after",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[CallStmt(Call(Id(x):))]),Else(Block[CallStmt(Call(Id(y):))]));Local(Id(after)=Const(1));Return(Id(after))])",
                    printedFragments = listOf("if a then", "x()", "else", "y()", "end", "local after = 1", "return after"),
                    expectedEndCount = 1
                ),
                Sample(
                    source = "local function run() if a then x() end tail() end",
                    expectedShape =
                        "Chunk(Block[Function(Id(run),Block[If(Clause(Id(a):Block[CallStmt(Call(Id(x):))]));CallStmt(Call(Id(tail):))])])",
                    printedFragments = listOf("local function run()", "if a then", "x()", "end", "tail()"),
                    expectedEndCount = 2
                )
            )
        )
    }

    // --- Terminal `end` pin (policy §3) ---

    @Test
    fun pureIfSurfacesEmitExactlyOneTerminalEnd() {
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
            assertEquals(
                1,
                countStandaloneEndLines(printed),
                "IfStatement print must emit exactly one terminal end (policy §3):\n$printed"
            )
            assertTrue(
                printed.trim().endsWith("end"),
                "IfStatement print must terminate with end (policy §3):\n$printed"
            )

            val reparsed = LuaParser(luaVersion = version).parseWithDiagnostics(printed)
            assertTrue(
                reparsed.recoveryDiagnostics.isEmpty(),
                "printed if-statement must reparse cleanly for <$source>; " +
                    "diagnostics=${reparsed.recoveryDiagnostics}\nprinted:\n$printed"
            )
            assertEquals(renderShape(initial), renderShape(reparsed.chunk), "shape drift for <$source>\nprinted:\n$printed")
        }
    }

    @Test
    fun roundTripsIfElseifElseChainWithZeroRecoveryDiagnosticsAndStableStatementCount() {
        val source = "if a then\n  print(1)\nelseif b then\n  print(2)\nelse\n  print(3)\nend"

        val initial = LuaParser(luaVersion = version).parseWithDiagnostics(source)
        assertTrue(
            initial.recoveryDiagnostics.isEmpty(),
            "source must parse cleanly; diagnostics=${initial.recoveryDiagnostics}"
        )
        assertEquals(1, initial.chunk.body.statements.size, "source has one top-level if-statement")

        val printed = printer.asCode(initial.chunk)
        assertTrue(printed.contains("if a then"), printed)
        assertTrue(printed.contains("elseif b then"), printed)
        assertTrue(printed.contains("else"), printed)
        assertEquals(1, countStandaloneEndLines(printed), "exactly one end for the whole chain:\n$printed")

        val reparsed = LuaParser(luaVersion = version).parseWithDiagnostics(printed)
        assertTrue(
            reparsed.recoveryDiagnostics.isEmpty(),
            "printed chain must reparse without recovery diagnostics; " +
                "diagnostics=${reparsed.recoveryDiagnostics}\nprinted:\n$printed"
        )
        assertEquals(
            initial.chunk.body.statements.size,
            reparsed.chunk.body.statements.size,
            "statement count must survive print→reparse\nprinted:\n$printed"
        )
        assertEquals(
            collectIfStatements(initial.chunk).size,
            collectIfStatements(reparsed.chunk).size,
            "if-statement count must survive print→reparse\nprinted:\n$printed"
        )
        assertEquals(renderShape(initial.chunk), renderShape(reparsed.chunk), "shape drift\nprinted:\n$printed")
    }

    @Test
    fun clausesDoNotEmitTheirOwnEnd() {
        // Three clauses, one statement: exactly one `end`, never one per clause.
        val source = "if a then x = 1 elseif b then x = 2 else x = 3 end"
        val printed = printer.asCode(LuaParser(luaVersion = version).parse(source))
        assertEquals(1, countStandaloneEndLines(printed), printed)
        assertEquals(1, Regex("\\bend\\b").findAll(printed).count(), "no per-clause end tokens:\n$printed")
    }

    // --- helpers ---

    private fun assertRoundTrips(samples: List<Sample>) {
        samples.forEach { sample ->
            val initial = LuaParser(luaVersion = version).parseWithDiagnostics(sample.source)
            assertTrue(
                initial.recoveryDiagnostics.isEmpty(),
                "sample must parse cleanly: ${sample.source}; diagnostics=${initial.recoveryDiagnostics}"
            )
            if (sample.expectedShape != null) {
                assertEquals(sample.expectedShape, renderShape(initial.chunk), sample.source)
            }

            val printed = printer.asCode(initial.chunk)
            sample.printedFragments.forEach { fragment ->
                assertTrue(
                    printed.contains(fragment),
                    "Printed code for ${sample.source} did not contain <$fragment>:\n$printed"
                )
            }
            sample.expectedEndCount?.let { expected ->
                assertEquals(
                    expected,
                    countStandaloneEndLines(printed),
                    "standalone end count for ${sample.source}\nprinted:\n$printed"
                )
            }

            val reparsed = LuaParser(luaVersion = version).parseWithDiagnostics(printed)
            assertTrue(
                reparsed.recoveryDiagnostics.isEmpty(),
                "printed surface must reparse cleanly for ${sample.source}; " +
                    "diagnostics=${reparsed.recoveryDiagnostics}\nprinted:\n$printed"
            )
            assertEquals(
                initial.chunk.body.statements.size,
                reparsed.chunk.body.statements.size,
                "top-level statement count drift for ${sample.source}\nprinted:\n$printed"
            )
            assertEquals(
                collectIfStatements(initial.chunk).size,
                collectIfStatements(reparsed.chunk).size,
                "if-statement count drift for ${sample.source}\nprinted:\n$printed"
            )
            assertEquals(
                renderShape(initial.chunk),
                renderShape(reparsed.chunk),
                "shape drift for ${sample.source}\nprinted:\n$printed"
            )
        }
    }

    /** Lines that consist of nothing but an `end` keyword (block terminators on their own line). */
    private fun countStandaloneEndLines(printed: String): Int {
        return Regex("(?m)^[ \\t]*end[ \\t]*$").findAll(printed).count()
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
        /** When set, the printed surface must contain exactly this many standalone `end` lines. */
        val expectedEndCount: Int? = null
    )
}
