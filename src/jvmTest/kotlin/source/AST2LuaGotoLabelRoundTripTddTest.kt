package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
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
 * AST2Lua round-trip corpus focused solely on Lua 5.3 goto/label forms (TASK-326).
 *
 * Complements [AST2LuaGotoAttributeRoundTripTddTest] with a goto/label-only inventory
 * (no attribute samples) emphasizing forward/backward labels and nested control-flow
 * shape stability.
 *
 * ## Shape-stable print policy
 *
 * 1. **Acceptance metric** — parse → print → reparse preserves `renderShape`.
 * 2. **Label form** — always `::ident::` (no spaces inside delimiters).
 * 3. **Goto form** — always `goto ident` (single space after keyword).
 * 4. **Forward/backward** — both label-before-goto and goto-before-label must round-trip.
 * 5. **IfStatement** — pure-if samples assert shape + fragments only (printer omits trailing `end`);
 *    reparse covered when wrapped in terminated blocks.
 * 6. Version: [LuaVersion.LUA_5_3]. Out of scope: jump legality, comment preservation.
 */
class AST2LuaGotoLabelRoundTripTddTest {

    private val printer = AST2Lua()
    private val version = LuaVersion.LUA_5_3

    @Test
    fun roundTripsSimpleForwardAndBackwardLabelGotoForms() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "::again:: goto again",
                    expectedShape = "Chunk(Block[Label(Id(again));Goto(Id(again))])",
                    printedFragments = listOf("::again::", "goto again")
                ),
                Sample(
                    source = "goto again ::again::",
                    expectedShape = "Chunk(Block[Goto(Id(again));Label(Id(again))])",
                    printedFragments = listOf("goto again", "::again::")
                ),
                Sample(
                    source = ";; ::again:: ;; goto again ;;",
                    expectedShape = "Chunk(Block[Label(Id(again));Goto(Id(again))])",
                    printedFragments = listOf("::again::", "goto again")
                ),
                Sample(
                    source = "::a:: ::b:: goto a goto b",
                    expectedShape = "Chunk(Block[Label(Id(a));Label(Id(b));Goto(Id(a));Goto(Id(b))])",
                    printedFragments = listOf("::a::", "::b::", "goto a", "goto b")
                ),
                Sample(
                    source = "goto finish local skipped = true ::finish::",
                    expectedShape =
                        "Chunk(Block[Goto(Id(finish));Local(Id(skipped)=Const(true));Label(Id(finish))])",
                    printedFragments = listOf("goto finish", "local skipped = true", "::finish::")
                )
            )
        )
    }

    @Test
    fun roundTripsLabelsInsideControlFlowBodies() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "do ::inner:: goto inner end",
                    expectedShape = "Chunk(Block[Do(Block[Label(Id(inner));Goto(Id(inner))])])",
                    printedFragments = listOf("do", "::inner::", "goto inner", "end")
                ),
                Sample(
                    source = "while keep do ::loop:: goto loop end",
                    expectedShape = "Chunk(Block[While(Id(keep):Block[Label(Id(loop));Goto(Id(loop))])])",
                    printedFragments = listOf("while keep do", "::loop::", "goto loop", "end")
                ),
                Sample(
                    source = "repeat ::retry:: goto retry until done",
                    expectedShape = "Chunk(Block[Repeat(Block[Label(Id(retry));Goto(Id(retry))]:Id(done))])",
                    printedFragments = listOf("repeat", "::retry::", "goto retry", "until done")
                ),
                Sample(
                    source = "for i = 1, 3 do ::step:: goto step end",
                    expectedShape =
                        "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[Label(Id(step));Goto(Id(step))])])",
                    printedFragments = listOf("for i = 1, 3 do", "::step::", "goto step", "end")
                ),
                Sample(
                    source = "for k, v in pairs(t) do ::each:: goto each end",
                    expectedShape =
                        "Chunk(Block[ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[Label(Id(each));Goto(Id(each))])])",
                    printedFragments = listOf("for k, v in pairs(t) do", "::each::", "goto each")
                )
            )
        )
    }

    @Test
    fun roundTripsBulkGotoLabelCorpusWithoutShapeDrift() {
        val samples = listOf(
            "::a:: goto a",
            "goto a ::a::",
            "::a:: ::b:: goto b goto a",
            "do ::x:: goto x end",
            "while keep do ::l:: goto l end",
            "repeat ::r:: goto r until done",
            "for i = 1, 2 do ::s:: goto s end",
            "for k, v in pairs(t) do ::e:: goto e end",
            """
            ::outer::
            do
              goto outer
            end
            """.trimIndent(),
            """
            goto finish
            local skipped = true
            ::finish::
            return skipped
            """.trimIndent(),
            """
            local function hop()
              ::start::
              goto start
            end
            """.trimIndent(),
            // terminated wrapper so pure-if end omission does not break reparse
            """
            do
              if ok then
                ::yes::
                goto yes
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

    // --- helpers ---

    private fun assertRoundTrips(samples: List<Sample>) {
        samples.forEach { sample ->
            val initial = LuaParser(luaVersion = version).parse(sample.source)
            if (sample.expectedShape != null) {
                assertEquals(sample.expectedShape, renderShape(initial), sample.source)
            }

            val printed = printer.asCode(initial)
            if (!sample.pureIf) {
                val reparsed = LuaParser(luaVersion = version).parse(printed)
                assertEquals(
                    renderShape(initial),
                    renderShape(reparsed),
                    "shape drift for ${sample.source}\nprinted:\n$printed"
                )
            }
            sample.printedFragments.forEach { fragment ->
                assertTrue(
                    printed.contains(fragment),
                    "Printed code for ${sample.source} did not contain <$fragment>:\n$printed"
                )
            }
        }
    }

    private fun collectLabels(node: BaseASTNode): List<LabelStatement> {
        val out = mutableListOf<LabelStatement>()
        walk(node) { current ->
            if (current is LabelStatement) out += current
        }
        return out
    }

    private fun collectGotos(node: BaseASTNode): List<GotoStatement> {
        val out = mutableListOf<GotoStatement>()
        walk(node) { current ->
            if (current is GotoStatement) out += current
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
                if (node !is ElseClause) walk(node.condition, visit)
                walk(node.body, visit)
            }
            is ForNumericStatement -> walk(node.body, visit)
            is ForGenericStatement -> walk(node.body, visit)
            is FunctionDeclaration -> node.body?.let { walk(it, visit) }
            else -> Unit
        }
    }

    private data class Sample(
        val source: String,
        val expectedShape: String? = null,
        val printedFragments: List<String> = emptyList(),
        val pureIf: Boolean = false
    )
}
