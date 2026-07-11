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
 * 6. **Nested if + outer else/elseif sibling (REVIEW28)** — because nested [IfStatement]
 *    also omits `end`, a bare nested `if … else … end` followed by an outer `else` /
 *    `elseif` is not shape-stable under reparse: the outer clause is absorbed into the
 *    inner if. Corpus samples that need both nesting and outer siblings therefore wrap
 *    the nested if in an explicit `do … end` (which *does* print `end`) so clause
 *    boundaries survive print→reparse. Pure nested trees without trailing outer siblings
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
    fun roundTripsIfElseChains() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "if ok then pass() else fail() end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ok):Block[CallStmt(Call(Id(pass):))]),Else(Block[CallStmt(Call(Id(fail):))]))])",
                    printedFragments = listOf("if ok then", "pass()", "else", "fail()"),
                    pureIf = true
                ),
                Sample(
                    source = "if ok then pass() else do fail() end end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ok):Block[CallStmt(Call(Id(pass):))]),Else(Block[Do(Block[CallStmt(Call(Id(fail):))])]))])",
                    printedFragments = listOf("if ok then", "pass()", "else", "do", "fail()", "end"),
                    pureIf = true
                ),
                Sample(
                    source = "if ready then x = 1 else x = 0 end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ready):Block[Assign(Id(x)=Const(1))]),Else(Block[Assign(Id(x)=Const(0))]))])",
                    printedFragments = listOf("if ready then", "x = 1", "else", "x = 0"),
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

    @Test
    fun roundTripsNestedIfInsideIfBranches() {
        // Nested pure-if trees: shape + fragments only (no pure-if reparse).
        assertRoundTrips(
            listOf(
                Sample(
                    source = """
                        if outer then
                          if inner then
                            work()
                          else
                            skip()
                          end
                        else
                          fallback()
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(outer):Block[If(Clause(Id(inner):Block[CallStmt(Call(Id(work):))]),Else(Block[CallStmt(Call(Id(skip):))]))]),Else(Block[CallStmt(Call(Id(fallback):))]))])",
                    printedFragments = listOf(
                        "if outer then",
                        "if inner then",
                        "work()",
                        "else",
                        "skip()",
                        "fallback()"
                    ),
                    pureIf = true
                ),
                Sample(
                    source = """
                        if a then
                          if b then
                            if c then
                              deep()
                            end
                          end
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[If(Clause(Id(b):Block[If(Clause(Id(c):Block[CallStmt(Call(Id(deep):))]))]))]))])",
                    printedFragments = listOf(
                        "if a then",
                        "if b then",
                        "if c then",
                        "deep()"
                    ),
                    pureIf = true
                ),
                Sample(
                    source = """
                        if a then
                          one()
                        elseif b then
                          if nested then
                            two()
                          end
                        else
                          three()
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[CallStmt(Call(Id(one):))]),ElseIf(Id(b):Block[If(Clause(Id(nested):Block[CallStmt(Call(Id(two):))]))]),Else(Block[CallStmt(Call(Id(three):))]))])",
                    printedFragments = listOf(
                        "if a then",
                        "one()",
                        "elseif b then",
                        "if nested then",
                        "two()",
                        "else",
                        "three()"
                    ),
                    pureIf = true
                )
            )
        )
    }

    @Test
    fun nestedIfInsideDoRoundTripsStructurally() {
        // Nested trees inside `do`. Nested ifs that are followed by an outer else/elseif
        // sibling are wrapped in an inner `do … end` so the no-if-end printer cannot absorb
        // the outer clause into the nested if (REVIEW28 / policy §6).
        assertRoundTrips(
            listOf(
                Sample(
                    source = """
                        do
                          if outer then
                            do
                              if inner then
                                work()
                              else
                                skip()
                              end
                            end
                          else
                            fallback()
                          end
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Do(Block[If(Clause(Id(outer):Block[Do(Block[If(Clause(Id(inner):Block[CallStmt(Call(Id(work):))]),Else(Block[CallStmt(Call(Id(skip):))]))])]),Else(Block[CallStmt(Call(Id(fallback):))]))])])",
                    printedFragments = listOf(
                        "do",
                        "if outer then",
                        "if inner then",
                        "work()",
                        "else",
                        "skip()",
                        "fallback()",
                        "end"
                    )
                ),
                Sample(
                    // Nested pure-if chain with no trailing outer sibling after the innermost
                    // if: outer `do` terminator alone is enough for shape-stable reparse.
                    source = """
                        do
                          if a then
                            if b then
                              if c then
                                deep()
                              end
                            end
                          end
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Do(Block[If(Clause(Id(a):Block[If(Clause(Id(b):Block[If(Clause(Id(c):Block[CallStmt(Call(Id(deep):))]))]))]))])])",
                    printedFragments = listOf("if a then", "if b then", "if c then", "deep()")
                ),
                Sample(
                    source = """
                        do
                          if a then
                            one()
                          elseif b then
                            do
                              if nested then
                                two()
                              end
                            end
                          else
                            three()
                          end
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Do(Block[If(Clause(Id(a):Block[CallStmt(Call(Id(one):))]),ElseIf(Id(b):Block[Do(Block[If(Clause(Id(nested):Block[CallStmt(Call(Id(two):))]))])]),Else(Block[CallStmt(Call(Id(three):))]))])])",
                    printedFragments = listOf(
                        "if a then",
                        "one()",
                        "elseif b then",
                        "if nested then",
                        "two()",
                        "else",
                        "three()"
                    )
                )
            )
        )
    }

    @Test
    fun nestedIfPrinterStableAcrossControlFlowSurfaces() {
        // Nested `if deep` sits inside an inner `do` so the outer elseif/else siblings of
        // the parent if are not absorbed under the no-if-end policy (policy §6).
        val source = """
            ::root::
            while keep do
              ::loop::
              if ok then
                goto loop
              elseif other then
                do
                  if deep then
                    goto root
                  end
                end
              else
                break
              end
            end
        """.trimIndent()

        val initial = LuaParser(luaVersion = version).parse(source)
        val expectedShape =
            "Chunk(Block[Label(Id(root));While(Id(keep):Block[Label(Id(loop));If(Clause(Id(ok):Block[Goto(Id(loop))]),ElseIf(Id(other):Block[Do(Block[If(Clause(Id(deep):Block[Goto(Id(root))]))])]),Else(Block[Break]))])])"
        assertEquals(expectedShape, renderShape(initial), source)

        val printed = printer.asCode(initial)
        val reparsed = LuaParser(luaVersion = version).parse(printed)
        assertEquals(renderShape(initial), renderShape(reparsed), "printed:\n$printed")

        assertTrue(printed.contains("if ok then"), "printed:\n$printed")
        assertTrue(printed.contains("elseif other then"), "printed:\n$printed")
        assertTrue(printed.contains("if deep then"), "printed:\n$printed")
        assertTrue(printed.contains("else"), "printed:\n$printed")
        assertTrue(printed.contains("goto loop"), "printed:\n$printed")
        assertTrue(printed.contains("goto root"), "printed:\n$printed")
        assertTrue(printed.contains("break"), "printed:\n$printed")
    }

    // --- Structural clause inspection after print ---

    @Test
    fun reparsePreservesIfElseifElseClauseTypesAndOrder() {
        // Outer `do` provides the terminator so reparse is shape-stable.
        val source = "do if a then one() elseif b then two() elseif c then three() else four() end end"
        val printed = printer.asCode(LuaParser(luaVersion = version).parse(source))
        val chunk = LuaParser(luaVersion = version).parse(printed)
        val doStatement = assertIs<DoStatement>(chunk.body.statements.single())
        val ifStatement = assertIs<IfStatement>(doStatement.body.statements.single())

        assertEquals(4, ifStatement.causes.size)
        val first = assertIs<IfClause>(ifStatement.causes[0])
        assertTrue(first !is ElseIfClause && first !is ElseClause)
        assertIs<ElseIfClause>(ifStatement.causes[1])
        assertIs<ElseIfClause>(ifStatement.causes[2])
        assertIs<ElseClause>(ifStatement.causes[3])

        assertContentEquals(
            listOf("a", "b", "c"),
            listOf(
                first.condition,
                (ifStatement.causes[1] as ElseIfClause).condition,
                (ifStatement.causes[2] as ElseIfClause).condition
            ).map { cond ->
                renderShape(cond).removePrefix("Id(").removeSuffix(")")
            }
        )

        assertTrue(printed.contains("if a then"), "printed:\n$printed")
        assertTrue(printed.contains("elseif b then"), "printed:\n$printed")
        assertTrue(printed.contains("elseif c then"), "printed:\n$printed")
        assertTrue(printed.contains("else"), "printed:\n$printed")
    }

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

    @Test
    fun emptyAndMultiStatementBranchBodiesRoundTripStructurally() {
        // Pure-if empty/multi bodies: shape + fragments only.
        assertRoundTrips(
            listOf(
                Sample(
                    source = "if ready then end",
                    expectedShape = "Chunk(Block[If(Clause(Id(ready):Block[]))])",
                    printedFragments = listOf("if ready then"),
                    pureIf = true
                ),
                Sample(
                    source = "if ready then else end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ready):Block[]),Else(Block[]))])",
                    printedFragments = listOf("if ready then", "else"),
                    pureIf = true
                ),
                Sample(
                    source = "if a then elseif b then else end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(a):Block[]),ElseIf(Id(b):Block[]),Else(Block[]))])",
                    printedFragments = listOf("if a then", "elseif b then", "else"),
                    pureIf = true
                ),
                Sample(
                    source = """
                        if ready then
                          local a = 1
                          local b = 2
                          use(a, b)
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ready):Block[Local(Id(a)=Const(1));Local(Id(b)=Const(2));CallStmt(Call(Id(use):Id(a),Id(b)))]))])",
                    printedFragments = listOf(
                        "if ready then",
                        "local a = 1",
                        "local b = 2",
                        "use(a, b)"
                    ),
                    pureIf = true
                )
            )
        )

        // Same empty/multi bodies inside `do` for full structural reparse.
        assertRoundTrips(
            listOf(
                Sample(
                    source = "do if ready then end end",
                    expectedShape = "Chunk(Block[Do(Block[If(Clause(Id(ready):Block[]))])])",
                    printedFragments = listOf("do", "if ready then", "end")
                ),
                Sample(
                    source = "do if ready then else end end",
                    expectedShape =
                        "Chunk(Block[Do(Block[If(Clause(Id(ready):Block[]),Else(Block[]))])])",
                    printedFragments = listOf("if ready then", "else")
                ),
                Sample(
                    source = "do if a then elseif b then else end end",
                    expectedShape =
                        "Chunk(Block[Do(Block[If(Clause(Id(a):Block[]),ElseIf(Id(b):Block[]),Else(Block[]))])])",
                    printedFragments = listOf("if a then", "elseif b then", "else")
                ),
                Sample(
                    source = """
                        do
                          if ready then
                            local a = 1
                            local b = 2
                            use(a, b)
                          end
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Do(Block[If(Clause(Id(ready):Block[Local(Id(a)=Const(1));Local(Id(b)=Const(2));CallStmt(Call(Id(use):Id(a),Id(b)))]))])])",
                    printedFragments = listOf(
                        "if ready then",
                        "local a = 1",
                        "local b = 2",
                        "use(a, b)"
                    )
                )
            )
        )
    }

    @Test
    fun bulkIfElseifElseCorpusWithoutShapeDrift() {
        // Only terminated-block samples so reparse is meaningful under the no-if-end policy.
        // Nested ifs followed by outer else/elseif siblings use an inner `do` terminator
        // (policy §6 / REVIEW28).
        val samples = listOf(
            "do if ready then start() end end",
            "do if ready then local value = 1 return value end end",
            "do if ok then pass() else fail() end end",
            "do if ok then pass() else do fail() end end end",
            "do if a then x = 1 elseif b then x = 2 else x = 3 end end",
            "do if a then one() elseif b then two() end end",
            "do if a then one() elseif b then two() elseif c then three() else four() end end",
            "do if ready then end end",
            "do if ready then else end end",
            "do if a then elseif b then else end end",
            "do if a + b < c and d or e then work() end end",
            "do if flags & MASK ~= 0 then return true end end",
            "while keep do if done then break end end",
            "repeat if ok then work() end until done",
            "for i = 1, 3 do if i == 2 then break end end",
            "for k, v in pairs(t) do if v then use(k, v) end end",
            "local function choose(a, b) if a then return a elseif b then return b else return nil end end",
            """
                do
                  if outer then
                    do
                      if inner then
                        work()
                      else
                        skip()
                      end
                    end
                  else
                    fallback()
                  end
                end
            """.trimIndent(),
            """
                do
                  if a then
                    if b then
                      if c then
                        deep()
                      end
                    end
                  end
                end
            """.trimIndent(),
            """
                do
                  if a then
                    one()
                  elseif b then
                    do
                      if nested then
                        two()
                      end
                    end
                  else
                    three()
                  end
                end
            """.trimIndent(),
            """
                do
                  if first then
                    local a = 1
                  elseif second then
                    local b = 2
                    return b
                  else
                    local c = 3
                  end
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

    @Test
    fun collectNestedIfStatementsAfterRoundTrip() {
        // Nested if/elseif/else is the sole statement of the outer if branch (no outer
        // sibling after the nested if), so bare nesting remains shape-stable.
        val source = """
            do
              if a then
                if b then
                  work()
                elseif c then
                  other()
                else
                  skip()
                end
              end
            end
        """.trimIndent()

        val initial = LuaParser(luaVersion = version).parse(source)
        val printed = printer.asCode(initial)
        val reparsed = LuaParser(luaVersion = version).parse(printed)

        assertEquals(renderShape(initial), renderShape(reparsed), "printed:\n$printed")

        val ifs = collectIfStatements(reparsed)
        assertEquals(2, ifs.size)
        assertEquals(1, ifs[0].causes.size) // outer if only
        assertEquals(3, ifs[1].causes.size) // inner if / elseif / else
        assertIs<IfClause>(ifs[1].causes[0])
        assertIs<ElseIfClause>(ifs[1].causes[1])
        assertIs<ElseClause>(ifs[1].causes[2])

        assertTrue(printed.contains("if a then"), "printed:\n$printed")
        assertTrue(printed.contains("if b then"), "printed:\n$printed")
        assertTrue(printed.contains("elseif c then"), "printed:\n$printed")
        assertTrue(printed.contains("else"), "printed:\n$printed")
    }

    @Test
    fun nestedIfWithOuterSiblingRequiresDoTerminatorForShapeStableReparse() {
        // Regression for REVIEW28: bare nested if+else then outer else drifts under
        // no-if-end print; wrapping nested if in do keeps clause ownership.
        val bareNested = """
            do
              if outer then
                if inner then
                  work()
                else
                  skip()
                end
              else
                fallback()
              end
            end
        """.trimIndent()
        val bareInitial = LuaParser(luaVersion = version).parse(bareNested)
        val barePrinted = printer.asCode(bareInitial)
        val bareReparsed = LuaParser(luaVersion = version).parse(barePrinted)
        assertTrue(
            renderShape(bareInitial) != renderShape(bareReparsed),
            "Expected bare nested if+outer-else to drift under no-if-end policy:\n$barePrinted"
        )

        val isolated = """
            do
              if outer then
                do
                  if inner then
                    work()
                  else
                    skip()
                  end
                end
              else
                fallback()
              end
            end
        """.trimIndent()
        val initial = LuaParser(luaVersion = version).parse(isolated)
        val printed = printer.asCode(initial)
        val reparsed = LuaParser(luaVersion = version).parse(printed)
        assertEquals(renderShape(initial), renderShape(reparsed), "printed:\n$printed")
        assertTrue(printed.contains("do"), "printed:\n$printed")
        assertTrue(printed.contains("if outer then"), "printed:\n$printed")
        assertTrue(printed.contains("if inner then"), "printed:\n$printed")
        assertTrue(printed.contains("fallback()"), "printed:\n$printed")
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
