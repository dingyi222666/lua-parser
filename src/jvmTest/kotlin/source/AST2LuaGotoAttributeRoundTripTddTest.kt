package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
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
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.source.AST2Lua
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AST2Lua round-trip corpus focused on Lua 5.3 goto/labels and Lua 5.4 local attributes.
 *
 * ## Shape-stable print policy (TASK-192)
 *
 * Round-trip acceptance is **AST-shape stable**, not byte-identical to the input source.
 *
 * 1. **Acceptance metric** — `parse(source)` then `AST2Lua.asCode` then reparse must yield the
 *    same `renderShape` as the original parse. Whitespace, indentation, and cosmetic spacing may
 *    change; structural nodes and identifiers must not.
 * 2. **Label form** — labels always print as `::ident::` (double-colon delimiters, no spaces
 *    inside the delimiters). Extra empty statements around labels may be dropped by the parser.
 * 3. **Goto form** — gotos always print as `goto ident` (single space after the keyword).
 * 4. **Attribute form** — local attributes always print as `name <attr>` with a single space
 *    before `<` and no space inside the brackets (e.g. `pinned <const>`, `handle <close>`).
 *    Input without that space (`name<attr>`) is accepted by the Lua 5.4 parser but normalized
 *    on print. Mixed lists keep unattributed names as bare identifiers in print (still modeled
 *    as [AttributeIdentifier] with a null attribute).
 * 5. **Block layout** — nested blocks are re-indented by `AST2Lua` (`indentSize`, default 4).
 *    Fragment assertions use substrings that survive indentation.
 * 6. **If / else layout** — `AST2Lua` prints if-clauses via `visitIfClause` / `visitElseClause` /
 *    `visitElseIfClause` and does **not** emit a trailing terminal `end` for [IfStatement]
 *    (unlike `do` / `while` / `for` / `function`). Fragment goldens for if samples therefore
 *    assert clause keywords (`if … then`, `else`) and body labels/gotos only — never a required
 *    trailing `end` for pure-if fragments. Shape stability is still enforced via reparse when the
 *    printer surface is accepted by the parser.
 * 7. **Version gating** — goto/label samples use [LuaVersion.LUA_5_3]; attribute samples use
 *    [LuaVersion.LUA_5_4]. Do not mix attribute syntax into 5.3 round-trips.
 * 8. **Out of scope** — jump legality, attribute semantic checks, and comment preservation are
 *    not asserted here; only parse → print → reparse shape and the stable print fragments above.
 */
class AST2LuaGotoAttributeRoundTripTddTest {

    private val printer = AST2Lua()

    // --- Goto / label corpus (Lua 5.3) ---

    @Test
    fun roundTripsSimpleLabelAndGotoForms() {
        assertRoundTrips(
            LuaVersion.LUA_5_3,
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
                )
            )
        )
    }

    // --- Local attribute corpus (Lua 5.4) ---

    @Test
    fun roundTripsSingleAttributedLocalsWithNormalizedSpacing() {
        assertRoundTrips(
            LuaVersion.LUA_5_4,
            listOf(
                Sample(
                    source = "local pinned <const> = 1",
                    expectedShape = "Chunk(Block[Local(AttrId(pinned<const>)=Const(1))])",
                    printedFragments = listOf("local pinned <const> = 1")
                ),
                Sample(
                    source = "local x<const> = 1",
                    expectedShape = "Chunk(Block[Local(AttrId(x<const>)=Const(1))])",
                    printedFragments = listOf("local x <const> = 1")
                ),
                Sample(
                    source = "local file <close> = open()",
                    expectedShape = "Chunk(Block[Local(AttrId(file<close>)=Call(Id(open):))])",
                    printedFragments = listOf("local file <close> = open()")
                ),
                Sample(
                    source = "local handle<close> = open()",
                    expectedShape = "Chunk(Block[Local(AttrId(handle<close>)=Call(Id(open):))])",
                    printedFragments = listOf("local handle <close> = open()")
                )
            )
        )
    }

    @Test
    fun roundTripsMixedAttributeListsAndUnattributedNames() {
        assertRoundTrips(
            LuaVersion.LUA_5_4,
            listOf(
                Sample(
                    source = "local pinned <const>, handle <close> = 1, open()",
                    expectedShape =
                        "Chunk(Block[Local(AttrId(pinned<const>),AttrId(handle<close>)=Const(1),Call(Id(open):))])",
                    printedFragments = listOf(
                        "local pinned <const>, handle <close> = 1, open()"
                    )
                ),
                Sample(
                    source = "local a<const>, b<close>, c = ...",
                    expectedShape =
                        "Chunk(Block[Local(AttrId(a<const>),AttrId(b<close>),AttrId(c)=Vararg)])",
                    printedFragments = listOf("local a <const>, b <close>, c = ...")
                ),
                Sample(
                    source = "local only, tagged <const> = 1, 2",
                    expectedShape =
                        "Chunk(Block[Local(AttrId(only),AttrId(tagged<const>)=Const(1),Const(2))])",
                    printedFragments = listOf("local only, tagged <const> = 1, 2")
                )
            )
        )
    }

    @Test
    fun roundTripsAttributedLocalsInsideBlocksAndLoops() {
        assertRoundTrips(
            LuaVersion.LUA_5_4,
            listOf(
                Sample(
                    source = "do local file <close> = open() end",
                    expectedShape =
                        "Chunk(Block[Do(Block[Local(AttrId(file<close>)=Call(Id(open):))])])",
                    printedFragments = listOf("local file <close> = open()")
                ),
                Sample(
                    source = "for i = 1, 2 do local limit <const> = i end",
                    expectedShape =
                        "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(2),null:Block[Local(AttrId(limit<const>)=Id(i))])])",
                    printedFragments = listOf("local limit <const> = i")
                ),
                Sample(
                    source = """
                        repeat
                            local handle<close> = open()
                        until done
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Repeat(Block[Local(AttrId(handle<close>)=Call(Id(open):))]:Id(done))])",
                    printedFragments = listOf("local handle <close> = open()", "until done")
                ),
                Sample(
                    source = """
                        for key, value in pairs(items) do
                            local current<close> = value
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[Local(AttrId(current<close>)=Id(value))])])",
                    printedFragments = listOf("local current <close> = value")
                ),
                Sample(
                    source = """
                        while ready do
                            local step <const> = 1
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[While(Id(ready):Block[Local(AttrId(step<const>)=Const(1))])])",
                    printedFragments = listOf("local step <const> = 1")
                )
            )
        )
    }

    // --- helpers ---

    private fun assertRoundTrips(version: LuaVersion, samples: List<Sample>) {
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

    private fun collectLabels(node: BaseASTNode): List<LabelStatement> {
        val out = mutableListOf<LabelStatement>()
        walk(node) { current ->
            if (current is LabelStatement) {
                out += current
            }
        }
        return out
    }

    private fun collectGotos(node: BaseASTNode): List<GotoStatement> {
        val out = mutableListOf<GotoStatement>()
        walk(node) { current ->
            if (current is GotoStatement) {
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
            else -> Unit
        }
    }

    private data class Sample(
        val source: String,
        val expectedShape: String? = null,
        val printedFragments: List<String> = emptyList()
    )
}
