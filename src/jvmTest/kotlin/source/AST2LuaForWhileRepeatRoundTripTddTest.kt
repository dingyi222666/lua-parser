package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.source.AST2Lua
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AST2Lua round-trip corpus for `for` / `while` / `repeat` printers (TASK-325).
 *
 * ## Shape-stable print policy
 *
 * 1. **Acceptance metric** — parse → print → reparse must preserve `renderShape`.
 * 2. **while** — `while <cond> do` … `end`
 * 3. **repeat** — `repeat` … `until <cond>`
 * 4. **for numeric** — `for i = start, end[, step] do` … `end`
 * 5. **for generic** — `for names in iterators do` … `end`
 * 6. **Empty bodies** must remain stable (`Block[]` shapes).
 * 7. Version: [LuaVersion.LUA_5_3]. Out of scope: comment preservation, jump legality.
 */
class AST2LuaForWhileRepeatRoundTripTddTest {

    private val printer = AST2Lua()
    private val version = LuaVersion.LUA_5_3

    @Test
    fun roundTripsWhileFormsIncludingEmptyBodies() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "while ready do end",
                    expectedShape = "Chunk(Block[While(Id(ready):Block[])])",
                    printedFragments = listOf("while ready do", "end")
                ),
                Sample(
                    source = "while ready do work() end",
                    expectedShape = "Chunk(Block[While(Id(ready):Block[CallStmt(Call(Id(work):))])])",
                    printedFragments = listOf("while ready do", "work()", "end")
                ),
                Sample(
                    source = "while a and b or c do break end",
                    expectedShape =
                        "Chunk(Block[While(Binary(or,Binary(and,Id(a),Id(b)),Id(c)):Block[Break])])",
                    printedFragments = listOf("while a and b or c do", "break")
                ),
                Sample(
                    source = "while x < 10 do x = x + 1 end",
                    expectedShape =
                        "Chunk(Block[While(Binary(<,Id(x),Const(10)):Block[Assign(Id(x)=Binary(+,Id(x),Const(1)))])])",
                    printedFragments = listOf("while x < 10 do", "x = x + 1")
                )
            )
        )
    }

    @Test
    fun roundTripsNumericAndGenericForIncludingEmptyBodies() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "for i = 1, 3 do end",
                    expectedShape =
                        "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[])])",
                    printedFragments = listOf("for i = 1, 3 do", "end")
                ),
                Sample(
                    source = "for i = 1, limit, 2 do total = total + i end",
                    expectedShape =
                        "Chunk(Block[ForNumeric(Id(i)=Const(1),Id(limit),Const(2):Block[Assign(Id(total)=Binary(+,Id(total),Id(i)))])])",
                    printedFragments = listOf("for i = 1, limit, 2 do", "total = total + i")
                ),
                Sample(
                    source = "for k, v in pairs(t) do end",
                    expectedShape =
                        "Chunk(Block[ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[])])",
                    printedFragments = listOf("for k, v in pairs(t) do", "end")
                ),
                Sample(
                    source = "for key, value in pairs(source) do target[key] = value end",
                    expectedShape =
                        "Chunk(Block[ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(source)):Block[Assign(Index(Id(target)[Id(key)])=Id(value))])])",
                    printedFragments = listOf(
                        "for key, value in pairs(source) do",
                        "target[key] = value"
                    )
                ),
                Sample(
                    source = "for i in iter() do use(i) end",
                    expectedShape =
                        "Chunk(Block[ForGeneric(Id(i) in Call(Id(iter):):Block[CallStmt(Call(Id(use):Id(i)))])])",
                    printedFragments = listOf("for i in iter() do", "use(i)")
                )
            )
        )
    }

    @Test
    fun roundTripsNestedLoopFormsStable() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = """
                        while outer do
                          for i = 1, 2 do
                            repeat
                              work(i)
                            until done
                          end
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[While(Id(outer):Block[ForNumeric(Id(i)=Const(1),Const(2),null:Block[Repeat(Block[CallStmt(Call(Id(work):Id(i)))]:Id(done))])])])",
                    printedFragments = listOf(
                        "while outer do",
                        "for i = 1, 2 do",
                        "repeat",
                        "work(i)",
                        "until done"
                    )
                ),
                Sample(
                    source = """
                        for k, v in pairs(t) do
                          while v do
                            break
                          end
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[While(Id(v):Block[Break])])])",
                    printedFragments = listOf("for k, v in pairs(t) do", "while v do", "break")
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
