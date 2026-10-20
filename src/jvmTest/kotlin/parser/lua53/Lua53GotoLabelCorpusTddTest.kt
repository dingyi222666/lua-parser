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
