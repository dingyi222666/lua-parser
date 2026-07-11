package parser.lua53

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.parse
import parser.renderShape

/**
 * Focused Lua 5.3 goto/label corpus at AST level.
 *
 * Parser accepts goto/label syntax without resolving jump legality
 * (that is a later semantic concern). Corpus therefore:
 * - asserts parse success + shape for valid forms and nested visibility
 * - includes one illegal cross-scope jump that still must parse without throw
 */
class Lua53GotoLabelCorpusTddTest {

    @Test
    fun parsesSimpleLabelAndGotoRoundTrip() {
        assertCaseShapes(
            "label then goto" to (
                "::again:: goto again" to
                    "Chunk(Block[Label(Id(again));Goto(Id(again))])"
            ),
            "goto then label" to (
                "goto again ::again::" to
                    "Chunk(Block[Goto(Id(again));Label(Id(again))])"
            ),
            "empty statements around label and goto" to (
                ";; ::again:: ;; goto again ;;" to
                    "Chunk(Block[Label(Id(again));Goto(Id(again))])"
            ),
            "multiple independent labels" to (
                "::a:: ::b:: goto a goto b" to
                    "Chunk(Block[Label(Id(a));Label(Id(b));Goto(Id(a));Goto(Id(b))])"
            )
        )
    }

    @Test
    fun exposesIdentifierNamesAndParentsForLabelGoto() {
        val chunk = parse(LuaVersion.LUA_5_3, "::entry:: goto entry")
        assertEquals(2, chunk.body.statements.size)

        val label = assertIs<LabelStatement>(chunk.body.statements[0])
        assertEquals("entry", label.identifier.name)
        assertEquals(label, label.identifier.parent)
        assertEquals(chunk.body, label.parent)

        val goto = assertIs<GotoStatement>(chunk.body.statements[1])
        assertEquals("entry", goto.identifier.name)
        assertEquals(goto, goto.identifier.parent)
        assertEquals(chunk.body, goto.parent)
    }

    @Test
    fun parsesLabelsInsideDoWhileRepeatIfBodies() {
        assertCaseShapes(
            "label and goto inside do block" to (
                "do ::inner:: goto inner end" to
                    "Chunk(Block[Do(Block[Label(Id(inner));Goto(Id(inner))])])"
            ),
            "label and goto inside while body" to (
                "while keep do ::loop:: goto loop end" to
                    "Chunk(Block[While(Id(keep):Block[Label(Id(loop));Goto(Id(loop))])])"
            ),
            "label and goto inside repeat body" to (
                "repeat ::retry:: goto retry until done" to
                    "Chunk(Block[Repeat(Block[Label(Id(retry));Goto(Id(retry))]:Id(done))])"
            ),
            "label and goto inside if then" to (
                "if ok then ::yes:: goto yes end" to
                    "Chunk(Block[If(Clause(Id(ok):Block[Label(Id(yes));Goto(Id(yes))]))])"
            ),
            "label and goto inside else" to (
                "if ok then pass() else ::no:: goto no end" to
                    "Chunk(Block[If(Clause(Id(ok):Block[CallStmt(Call(Id(pass):))]),Else(Block[Label(Id(no));Goto(Id(no))]))])"
            )
        )
    }

    @Test
    fun parsesLabelsInsideNumericAndGenericForBodies() {
        assertCaseShapes(
            "label and goto inside numeric for" to (
                "for i = 1, 3 do ::step:: goto step end" to
                    "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[Label(Id(step));Goto(Id(step))])])"
            ),
            "label and goto inside generic for" to (
                "for k, v in pairs(t) do ::each:: goto each end" to
                    "Chunk(Block[ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[Label(Id(each));Goto(Id(each))])])"
            )
        )
    }

    @Test
    fun parsesNestedVisibilityOfLabelsAcrossBlocks() {
        // Visible outer label from nested block (syntactically valid; jump is legal in Lua).
        val outerVisible = parse(
            LuaVersion.LUA_5_3,
            """
            ::outer::
            do
              goto outer
            end
            """.trimIndent()
        )
        assertEquals(
            "Chunk(Block[Label(Id(outer));Do(Block[Goto(Id(outer))])])",
            renderShape(outerVisible)
        )
        val outerLabel = assertIs<LabelStatement>(outerVisible.body.statements[0])
        assertEquals("outer", outerLabel.identifier.name)
        val outerDo = assertIs<DoStatement>(outerVisible.body.statements[1])
        val outerGoto = assertIs<GotoStatement>(outerDo.body.statements.single())
        assertEquals("outer", outerGoto.identifier.name)

        // Nested same-name labels remain distinct AST nodes under different parents.
        val nestedSameName = parse(
            LuaVersion.LUA_5_3,
            """
            ::L::
            do
              ::L::
              goto L
            end
            goto L
            """.trimIndent()
        )
        assertEquals(
            "Chunk(Block[Label(Id(L));Do(Block[Label(Id(L));Goto(Id(L))]);Goto(Id(L))])",
            renderShape(nestedSameName)
        )
        val topLabel = assertIs<LabelStatement>(nestedSameName.body.statements[0])
        val nestedDo = assertIs<DoStatement>(nestedSameName.body.statements[1])
        val nestedLabel = assertIs<LabelStatement>(nestedDo.body.statements[0])
        assertEquals("L", topLabel.identifier.name)
        assertEquals("L", nestedLabel.identifier.name)
        assertTrue(topLabel !== nestedLabel)
        assertEquals(nestedSameName.body, topLabel.parent)
        assertEquals(nestedDo.body, nestedLabel.parent)

        // Forward jump into nested block body is represented at AST level.
        val forwardIntoNested = parse(
            LuaVersion.LUA_5_3,
            """
            goto inner
            do
              ::inner::
              local x = 1
            end
            """.trimIndent()
        )
        assertEquals(
            "Chunk(Block[Goto(Id(inner));Do(Block[Label(Id(inner));Local(Id(x)=Const(1))])])",
            renderShape(forwardIntoNested)
        )
        assertEquals("inner", assertIs<GotoStatement>(forwardIntoNested.body.statements[0]).identifier.name)
        val nested = assertIs<DoStatement>(forwardIntoNested.body.statements[1])
        assertEquals("inner", assertIs<LabelStatement>(nested.body.statements[0]).identifier.name)
        assertIs<LocalStatement>(nested.body.statements[1])
    }

    @Test
    fun parsesLabelsAndGotosInsideFunctionBodies() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            function hop()
              ::start::
              if ready then
                goto start
              end
              ::done::
            end
            goto done
            """.trimIndent()
        )

        assertEquals(
            "Chunk(Block[Function(Id(hop),Block[Label(Id(start));If(Clause(Id(ready):Block[Goto(Id(start))]));Label(Id(done))]);Goto(Id(done))])",
            renderShape(chunk)
        )

        val fn = assertIs<FunctionDeclaration>(chunk.body.statements[0])
        val body = assertNotNull(fn.body)
        assertIs<LabelStatement>(body.statements[0])
        val ifStmt = assertIs<IfStatement>(body.statements[1])
        assertEquals(
            "start",
            assertIs<GotoStatement>(ifStmt.causes.single().body.statements.single()).identifier.name
        )
        assertEquals("done", assertIs<LabelStatement>(body.statements[2]).identifier.name)
        assertEquals("done", assertIs<GotoStatement>(chunk.body.statements[1]).identifier.name)
    }

    @Test
    fun parsesBackwardAndForwardJumpsAcrossSiblings() {
        assertCaseShapes(
            "backward jump over statements" to (
                """
                ::top::
                local n = 0
                n = n + 1
                goto top
                """.trimIndent() to
                    "Chunk(Block[Label(Id(top));Local(Id(n)=Const(0));Assign(Id(n)=Binary(+,Id(n),Const(1)));Goto(Id(top))])"
            ),
            "forward jump over statements" to (
                """
                goto finish
                local skipped = true
                ::finish::
                return skipped
                """.trimIndent() to
                    "Chunk(Block[Goto(Id(finish));Local(Id(skipped)=Const(true));Label(Id(finish));Return(Id(skipped))])"
            )
        )
    }

    @Test
    fun parsesIllegalCrossScopeJumpWithoutThrow() {
        // Lua forbids jumping into the scope of a local variable.
        // The parser must still accept the syntax and produce AST nodes without throwing.
        val source = """
            goto target
            do
              local hidden = 1
              ::target::
              use(hidden)
            end
        """.trimIndent()

        val chunk = parseWithoutThrow(source)

        assertEquals(
            "Chunk(Block[Goto(Id(target));Do(Block[Local(Id(hidden)=Const(1));Label(Id(target));CallStmt(Call(Id(use):Id(hidden)))])])",
            renderShape(chunk)
        )

        val goto = assertIs<GotoStatement>(chunk.body.statements[0])
        assertEquals("target", goto.identifier.name)

        val doBlock = assertIs<DoStatement>(chunk.body.statements[1])
        assertEquals(3, doBlock.body.statements.size)
        assertIs<LocalStatement>(doBlock.body.statements[0])
        assertEquals("target", assertIs<LabelStatement>(doBlock.body.statements[1]).identifier.name)
        assertIs<CallStatement>(doBlock.body.statements[2])

        // Cross-scope relationship is visible in the tree: goto parent is chunk body,
        // label parent is the nested do body that introduces the local.
        assertEquals(chunk.body, goto.parent)
        assertEquals(doBlock.body, assertIs<LabelStatement>(doBlock.body.statements[1]).parent)
    }

    @Test
    fun collectsAllGotoAndLabelNodesFromNestedControlFlow() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            ::root::
            while true do
              for i = 1, 2 do
                ::inner::
                if i == 1 then
                  goto inner
                else
                  goto root
                end
              end
              goto root
            end
            """.trimIndent()
        )

        val labels = collectStatements<LabelStatement>(chunk)
        val gotos = collectStatements<GotoStatement>(chunk)

        // DFS pre-order: labels outer→nested; gotos nested if/else then while-body tail.
        assertEquals(listOf("root", "inner"), labels.map { it.identifier.name })
        assertEquals(listOf("inner", "root", "root"), gotos.map { it.identifier.name })

        val whileStmt = assertIs<WhileStatement>(chunk.body.statements[1])
        val numericFor = assertIs<ForNumericStatement>(whileStmt.body.statements[0])
        assertEquals("inner", assertIs<LabelStatement>(numericFor.body.statements[0]).identifier.name)
        val ifStmt = assertIs<IfStatement>(numericFor.body.statements[1])
        assertEquals(2, ifStmt.causes.size)
        assertEquals(
            "inner",
            assertIs<GotoStatement>(ifStmt.causes[0].body.statements.single()).identifier.name
        )
        assertEquals(
            "root",
            assertIs<GotoStatement>(ifStmt.causes[1].body.statements.single()).identifier.name
        )
        assertEquals(
            "root",
            assertIs<GotoStatement>(whileStmt.body.statements[1]).identifier.name
        )
    }

    private fun parseWithoutThrow(source: String): ChunkNode {
        return runCatching {
            parse(LuaVersion.LUA_5_3, source)
        }.getOrElse { failure ->
            fail("illegal cross-scope goto must parse without throw; source:\n$source\n${failure.message}")
        }
    }

    private fun assertCaseShapes(vararg cases: Pair<String, Pair<String, String>>) {
        val failures = cases.mapNotNull { (name, case) ->
            val (source, expectedShape) = case
            runCatching {
                assertEquals(expectedShape, renderShape(parse(LuaVersion.LUA_5_3, source)), name)
            }.exceptionOrNull()?.let { failure ->
                "$name\nsource: $source\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    /**
     * Collect statements of type [T] in depth-first pre-order (source nesting
     * order). Nested control-flow bodies are visited immediately so gotos inside
     * for/if appear before sibling gotos in outer blocks.
     */
    private fun <T : StatementNode> collectStatements(chunk: ChunkNode, predicate: (StatementNode) -> Boolean): List<T> {
        val out = mutableListOf<T>()
        fun walkBlock(block: BlockNode) {
            val statements = buildList {
                addAll(block.statements)
                block.returnStatement?.let { add(it) }
            }
            for (statement in statements) {
                if (predicate(statement)) {
                    @Suppress("UNCHECKED_CAST")
                    out += statement as T
                }
                when (statement) {
                    is DoStatement -> walkBlock(statement.body)
                    is WhileStatement -> walkBlock(statement.body)
                    is RepeatStatement -> walkBlock(statement.body)
                    is ForNumericStatement -> walkBlock(statement.body)
                    is ForGenericStatement -> walkBlock(statement.body)
                    is IfStatement -> statement.causes.forEach { walkBlock(it.body) }
                    is FunctionDeclaration -> statement.body?.let(::walkBlock)
                    else -> Unit
                }
            }
        }
        walkBlock(chunk.body)
        return out
    }

    private inline fun <reified T : StatementNode> collectStatements(chunk: ChunkNode): List<T> =
        collectStatements(chunk) { it is T }
}
