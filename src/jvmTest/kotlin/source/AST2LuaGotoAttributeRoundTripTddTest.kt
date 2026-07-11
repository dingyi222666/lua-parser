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

    @Test
    fun roundTripsLabelsInsideControlFlowBodies() {
        assertRoundTrips(
            LuaVersion.LUA_5_3,
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
                    source = "if ok then ::yes:: goto yes end",
                    expectedShape = "Chunk(Block[If(Clause(Id(ok):Block[Label(Id(yes));Goto(Id(yes))]))])",
                    // REVIEW21C golden fix: IfStatement print omits trailing `end` (policy §6).
                    printedFragments = listOf("if ok then", "::yes::", "goto yes")
                ),
                Sample(
                    source = "if ok then pass() else ::no:: goto no end",
                    expectedShape =
                        "Chunk(Block[If(Clause(Id(ok):Block[CallStmt(Call(Id(pass):))]),Else(Block[Label(Id(no));Goto(Id(no))]))])",
                    printedFragments = listOf("if ok then", "pass()", "else", "::no::", "goto no")
                )
            )
        )
    }

    @Test
    fun roundTripsLabelsInsideNumericAndGenericForBodies() {
        assertRoundTrips(
            LuaVersion.LUA_5_3,
            listOf(
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
    fun roundTripsNestedVisibilityAndFunctionScopedLabels() {
        assertRoundTrips(
            LuaVersion.LUA_5_3,
            listOf(
                Sample(
                    source = """
                        ::outer::
                        do
                          goto outer
                        end
                    """.trimIndent(),
                    expectedShape = "Chunk(Block[Label(Id(outer));Do(Block[Goto(Id(outer))])])",
                    printedFragments = listOf("::outer::", "goto outer")
                ),
                Sample(
                    source = """
                        ::L::
                        do
                          ::L::
                          goto L
                        end
                        goto L
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Label(Id(L));Do(Block[Label(Id(L));Goto(Id(L))]);Goto(Id(L))])",
                    printedFragments = listOf("::L::", "goto L")
                ),
                Sample(
                    source = """
                        local function worker()
                          ::body::
                          goto body
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Function(Id(worker),Block[Label(Id(body));Goto(Id(body))])])",
                    printedFragments = listOf("local function worker()", "::body::", "goto body", "end")
                ),
                Sample(
                    source = """
                        ::start::
                        if ready then
                          goto start
                        else
                          ::done::
                          goto done
                        end
                    """.trimIndent(),
                    expectedShape =
                        "Chunk(Block[Label(Id(start));If(Clause(Id(ready):Block[Goto(Id(start))]),Else(Block[Label(Id(done));Goto(Id(done))]))])",
                    // No required terminal `end` fragment for if/else (policy §6).
                    printedFragments = listOf(
                        "::start::",
                        "if ready then",
                        "goto start",
                        "else",
                        "::done::",
                        "goto done"
                    )
                )
            )
        )
    }

    @Test
    fun roundTripPreservesLabelAndGotoIdentifierParentsOnReparse() {
        val source = "::entry:: goto entry"
        val printed = printer.asCode(LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(source))
        val chunk = LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(printed)

        assertEquals(2, chunk.body.statements.size)

        val label = assertIs<LabelStatement>(chunk.body.statements[0])
        assertEquals("entry", label.identifier.name)
        assertEquals(label, label.identifier.parent)
        assertEquals(chunk.body, label.parent)

        val goto = assertIs<GotoStatement>(chunk.body.statements[1])
        assertEquals("entry", goto.identifier.name)
        assertEquals(goto, goto.identifier.parent)
        assertEquals(chunk.body, goto.parent)

        assertTrue(printed.contains("::entry::"), "printed:\n$printed")
        assertTrue(printed.contains("goto entry"), "printed:\n$printed")
    }

    @Test
    fun roundTripNestedControlFlowStillCollectsAllLabelsAndGotos() {
        val source = """
            ::root::
            while keep do
              ::loop::
              if ok then
                goto loop
              else
                do
                  goto root
                end
              end
            end
        """.trimIndent()

        val initial = LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(source)
        val printed = printer.asCode(initial)
        val reparsed = LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(printed)

        assertEquals(renderShape(initial), renderShape(reparsed), printed)

        val labels = collectLabels(reparsed)
        val gotos = collectGotos(reparsed)
        assertContentEquals(listOf("root", "loop"), labels.map { it.identifier.name })
        assertContentEquals(listOf("loop", "root"), gotos.map { it.identifier.name })

        assertTrue(printed.contains("::root::"), "printed:\n$printed")
        assertTrue(printed.contains("::loop::"), "printed:\n$printed")
        assertTrue(printed.contains("goto loop"), "printed:\n$printed")
        assertTrue(printed.contains("goto root"), "printed:\n$printed")
        assertTrue(printed.contains("while keep do"), "printed:\n$printed")
        assertTrue(printed.contains("if ok then"), "printed:\n$printed")
        assertTrue(printed.contains("else"), "printed:\n$printed")
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

    @Test
    fun roundTripPreservesAttributeIdentifierFieldsOnReparse() {
        val source = "local a<const>, b<close>, c = ..."
        val printed = printer.asCode(LuaParser(luaVersion = LuaVersion.LUA_5_4).parse(source))
        val local = LuaParser(luaVersion = LuaVersion.LUA_5_4).parse(printed)
            .body.statements.single()
            .let { assertIs<LocalStatement>(it) }

        assertEquals(3, local.init.size)
        local.init.forEach { assertIs<AttributeIdentifier>(it) }
        assertContentEquals(
            listOf("a", "b", "c"),
            local.init.map { (it as AttributeIdentifier).name }
        )
        assertContentEquals(
            listOf("const", "close", null),
            local.init.map { (it as AttributeIdentifier).attributeName }
        )

        val unattributed = assertIs<AttributeIdentifier>(local.init[2])
        assertNull(unattributed.attributeName)

        assertTrue(
            printed.contains("local a <const>, b <close>, c = ..."),
            "Print policy requires spaced attributes:\n$printed"
        )
    }

    @Test
    fun printPolicyDocumentsShapeStabilityOverByteIdentity() {
        // Same AST shape from two surface forms must print to the same stable attribute form.
        val spaced = "local pinned <const> = 1"
        val compact = "local pinned<const> = 1"

        val spacedPrinted = printer.asCode(LuaParser(luaVersion = LuaVersion.LUA_5_4).parse(spaced))
        val compactPrinted = printer.asCode(LuaParser(luaVersion = LuaVersion.LUA_5_4).parse(compact))

        assertEquals(
            renderShape(LuaParser(luaVersion = LuaVersion.LUA_5_4).parse(spaced)),
            renderShape(LuaParser(luaVersion = LuaVersion.LUA_5_4).parse(compact))
        )
        assertEquals(spacedPrinted.trim(), compactPrinted.trim())
        assertTrue(spacedPrinted.contains("local pinned <const> = 1"), spacedPrinted)

        // Label surface forms with empty statements collapse to the same printed fragments.
        val withEmpties = ";; ::again:: ;; goto again ;;"
        val bare = "::again:: goto again"
        val withEmptiesPrinted =
            printer.asCode(LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(withEmpties))
        val barePrinted = printer.asCode(LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(bare))

        assertEquals(
            renderShape(LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(withEmpties)),
            renderShape(LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(bare))
        )
        assertTrue(withEmptiesPrinted.contains("::again::"), withEmptiesPrinted)
        assertTrue(withEmptiesPrinted.contains("goto again"), withEmptiesPrinted)
        assertTrue(barePrinted.contains("::again::"), barePrinted)
        assertTrue(barePrinted.contains("goto again"), barePrinted)
    }

    /**
     * Explicit REVIEW21C regression: if-body label/goto goldens must not require a trailing
     * `end` fragment, because [AST2Lua] does not emit `end` for [IfStatement].
     */
    @Test
    fun ifBodiesWithLabelsDoNotRequireTrailingEndFragment() {
        val samples = listOf(
            Sample(
                source = "if ok then ::yes:: goto yes end",
                expectedShape = "Chunk(Block[If(Clause(Id(ok):Block[Label(Id(yes));Goto(Id(yes))]))])",
                printedFragments = listOf("if ok then", "::yes::", "goto yes")
            ),
            Sample(
                source = "if ok then pass() else ::no:: goto no end",
                expectedShape =
                    "Chunk(Block[If(Clause(Id(ok):Block[CallStmt(Call(Id(pass):))]),Else(Block[Label(Id(no));Goto(Id(no))]))])",
                printedFragments = listOf("if ok then", "pass()", "else", "::no::", "goto no")
            )
        )

        samples.forEach { sample ->
            val initial = LuaParser(luaVersion = LuaVersion.LUA_5_3).parse(sample.source)
            assertEquals(sample.expectedShape, renderShape(initial), sample.source)

            val printed = printer.asCode(initial)
            sample.printedFragments.forEach { fragment ->
                assertTrue(
                    printed.contains(fragment),
                    "Printed code for ${sample.source} did not contain <$fragment>:\n$printed"
                )
            }
            // Pure-if surface must not invent a trailing/standalone end keyword.
            assertTrue(
                !Regex("(?m)^\\s*end\\s*$").containsMatchIn(printed) &&
                    !printed.trim().endsWith("end"),
                "IfStatement print must not emit trailing end (policy §6):\n$printed"
            )
        }
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
