package parser.lua53

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.parse
import parser.renderShape

/**
 * Focused Lua 5.3 forward-jump shape corpus (goto before later ::label::).
 *
 * Acceptance (TASK-280):
 * - Forward `::label::` after `goto` remains in the AST (strict parse shapes).
 * - Malformed labels recover without hang (errorRecovery path, bounded runtime).
 *
 * Test-only; production parser changes are out of scope unless review re-scopes.
 */
class Lua53GotoForwardJumpShapeTddTest {

    @Test
    fun forwardLabelAfterGotoRemainsInAstShape() {
        assertCaseShapes(
            "simple forward jump" to (
                "goto finish ::finish::" to
                    "Chunk(Block[Goto(Id(finish));Label(Id(finish))])"
            ),
            "forward jump with empty statements" to (
                "goto finish ;; ::finish:: ;;" to
                    "Chunk(Block[Goto(Id(finish));Label(Id(finish))])"
            ),
            "forward jump over local and call" to (
                """
                goto done
                local skipped = true
                print(skipped)
                ::done::
                """.trimIndent() to
                    "Chunk(Block[Goto(Id(done));Local(Id(skipped)=Const(true));CallStmt(Call(Id(print):Id(skipped)));Label(Id(done))])"
            ),
            "forward jump over assignment and return path" to (
                """
                goto end
                x = 1
                ::end::
                return x
                """.trimIndent() to
                    "Chunk(Block[Goto(Id(end));Assign(Id(x)=Const(1));Label(Id(end));Return(Id(x))])"
            ),
            "multiple forward targets stay ordered" to (
                "goto a goto b ::a:: ::b::" to
                    "Chunk(Block[Goto(Id(a));Goto(Id(b));Label(Id(a));Label(Id(b))])"
            )
        )
    }

    @Test
    fun forwardLabelAfterGotoKeepsIdentifierParents() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            goto entry
            local prep = 0
            ::entry::
            use(prep)
            """.trimIndent()
        )

        assertEquals(
            "Chunk(Block[Goto(Id(entry));Local(Id(prep)=Const(0));Label(Id(entry));CallStmt(Call(Id(use):Id(prep)))])",
            renderShape(chunk)
        )

        val goto = assertIs<GotoStatement>(chunk.body.statements[0])
        assertEquals("entry", goto.identifier.name)
        assertEquals(goto, goto.identifier.parent)
        assertEquals(chunk.body, goto.parent)

        assertIs<LocalStatement>(chunk.body.statements[1])

        val label = assertIs<LabelStatement>(chunk.body.statements[2])
        assertEquals("entry", label.identifier.name)
        assertEquals(label, label.identifier.parent)
        assertEquals(chunk.body, label.parent)

        assertIs<CallStatement>(chunk.body.statements[3])
    }

    @Test
    fun forwardJumpsIntoNestedControlFlowBodiesRemainInAst() {
        assertCaseShapes(
            "forward into do body" to (
                """
                goto inner
                do
                  ::inner::
                  local x = 1
                end
                """.trimIndent() to
                    "Chunk(Block[Goto(Id(inner));Do(Block[Label(Id(inner));Local(Id(x)=Const(1))])])"
            ),
            "forward into while body" to (
                """
                goto loop
                while keep do
                  ::loop::
                  break
                end
                """.trimIndent() to
                    "Chunk(Block[Goto(Id(loop));While(Id(keep):Block[Label(Id(loop));Break])])"
            ),
            "forward into repeat body" to (
                """
                goto retry
                repeat
                  ::retry::
                  work()
                until done
                """.trimIndent() to
                    "Chunk(Block[Goto(Id(retry));Repeat(Block[Label(Id(retry));CallStmt(Call(Id(work):))]:Id(done))])"
            ),
            "forward into if then" to (
                """
                goto yes
                if ok then
                  ::yes::
                  pass()
                end
                """.trimIndent() to
                    "Chunk(Block[Goto(Id(yes));If(Clause(Id(ok):Block[Label(Id(yes));CallStmt(Call(Id(pass):))]))])"
            ),
            "forward into else branch" to (
                """
                goto no
                if ok then
                  pass()
                else
                  ::no::
                  fail()
                end
                """.trimIndent() to
                    "Chunk(Block[Goto(Id(no));If(Clause(Id(ok):Block[CallStmt(Call(Id(pass):))]),Else(Block[Label(Id(no));CallStmt(Call(Id(fail):))]))])"
            ),
            "forward into numeric for body" to (
                """
                goto step
                for i = 1, 3 do
                  ::step::
                  use(i)
                end
                """.trimIndent() to
                    "Chunk(Block[Goto(Id(step));ForNumeric(Id(i)=Const(1),Const(3),null:Block[Label(Id(step));CallStmt(Call(Id(use):Id(i)))])])"
            ),
            "forward into generic for body" to (
                """
                goto each
                for k, v in pairs(t) do
                  ::each::
                  use(k, v)
                end
                """.trimIndent() to
                    "Chunk(Block[Goto(Id(each));ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[Label(Id(each));CallStmt(Call(Id(use):Id(k),Id(v)))])])"
            )
        )
    }

    @Test
    fun forwardJumpsAcrossFunctionAndSiblingScopesRemainInAst() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            goto outer
            function hop()
              goto done
              ::start::
              work()
              ::done::
            end
            ::outer::
            """.trimIndent()
        )

        assertEquals(
            "Chunk(Block[Goto(Id(outer));Function(Id(hop),Block[Goto(Id(done));Label(Id(start));CallStmt(Call(Id(work):));Label(Id(done))]);Label(Id(outer))])",
            renderShape(chunk)
        )

        val outerGoto = assertIs<GotoStatement>(chunk.body.statements[0])
        assertEquals("outer", outerGoto.identifier.name)

        val fn = assertIs<FunctionDeclaration>(chunk.body.statements[1])
        val body = requireNotNull(fn.body)
        assertEquals("done", assertIs<GotoStatement>(body.statements[0]).identifier.name)
        assertEquals("start", assertIs<LabelStatement>(body.statements[1]).identifier.name)
        assertIs<CallStatement>(body.statements[2])
        assertEquals("done", assertIs<LabelStatement>(body.statements[3]).identifier.name)

        assertEquals("outer", assertIs<LabelStatement>(chunk.body.statements[2]).identifier.name)
    }

    @Test
    fun forwardJumpOverStatementsPreservesIntermediateNodes() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            goto finish
            local skipped = true
            skipped = not skipped
            ::finish::
            return skipped
            """.trimIndent()
        )

        assertEquals(
            "Chunk(Block[Goto(Id(finish));Local(Id(skipped)=Const(true));Assign(Id(skipped)=Unary(not,Id(skipped)));Label(Id(finish));Return(Id(skipped))])",
            renderShape(chunk)
        )

        assertIs<GotoStatement>(chunk.body.statements[0])
        assertIs<LocalStatement>(chunk.body.statements[1])
        assertEquals("finish", assertIs<LabelStatement>(chunk.body.statements[3]).identifier.name)
        assertIs<ReturnStatement>(chunk.body.returnStatement)
    }

    @Test
    fun malformedLabelsRecoverWithoutHangAndKeepLaterStatements() {
        val cases = listOf(
            MalformedLabelCase(
                name = "missing trailing double colon after label name",
                source = """
                    goto finish
                    ::finish
                    print("after")
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Goto(Id(finish))",
                    "Label(Id(finish))",
                    "CallStmt(Call(Id(print):Const(\"after\")))"
                ),
                warningFragments = listOf("'::' expected")
            ),
            MalformedLabelCase(
                name = "missing label name between double colons",
                source = """
                    goto target
                    ::
                    ::
                    print("after")
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Goto(Id(target))",
                    "Label(Id())",
                    "CallStmt(Call(Id(print):Const(\"after\")))"
                ),
                warningFragments = listOf("<name> expected")
            ),
            MalformedLabelCase(
                name = "single opening double colon then statement",
                source = """
                    goto later
                    ::
                    local after = 1
                    ::later::
                    return after
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Goto(Id(later))",
                    "Label(Id())",
                    "Local(Id(after)=Const(1))",
                    "Label(Id(later))",
                    "Return(Id(after))"
                ),
                warningFragments = listOf("<name> expected", "'::' expected")
            ),
            MalformedLabelCase(
                name = "label name then later statement without closing double colon",
                source = """
                    goto end
                    ::end
                    return 1
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Goto(Id(end))",
                    "Label(Id(end))",
                    "Return(Const(1))"
                ),
                warningFragments = listOf("'::' expected")
            ),
            MalformedLabelCase(
                name = "forward goto with incomplete label inside do still reaches later call",
                source = """
                    goto inner
                    do
                      ::inner
                      local x = 1
                    end
                    print(x)
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Goto(Id(inner))",
                    "Do(Block[",
                    "Label(Id(inner))",
                    "Local(Id(x)=Const(1))",
                    "CallStmt(Call(Id(print):Id(x)))"
                ),
                warningFragments = listOf("'::' expected")
            ),
            MalformedLabelCase(
                name = "goto missing name before forward label still recovers",
                source = """
                    goto
                    ::finish::
                    print("done")
                """.trimIndent(),
                requiredShapeFragments = listOf(
                    "Goto(Id())",
                    "Label(Id(finish))",
                    "CallStmt(Call(Id(print):Const(\"done\")))"
                ),
                warningFragments = listOf("<name> expected")
            )
        )

        assertEquals(6, cases.size)

        val failures = cases.mapNotNull { case ->
            runCatching {
                assertMalformedLabelRecoversWithoutHang(case)
            }.exceptionOrNull()?.let { failure ->
                "${case.name}\nsource:\n${case.source}\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    @Test
    fun nestedForwardJumpShapesExposeControlFlowParents() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            goto outer
            while true do
              goto inner
              for i = 1, 2 do
                ::inner::
                if i == 1 then
                  break
                end
              end
              ::outer::
            end
            """.trimIndent()
        )

        assertEquals(
            "Chunk(Block[Goto(Id(outer));While(Const(true):Block[Goto(Id(inner));ForNumeric(Id(i)=Const(1),Const(2),null:Block[Label(Id(inner));If(Clause(Binary(==,Id(i),Const(1)):Block[Break]))]);Label(Id(outer))])])",
            renderShape(chunk)
        )

        val whileStmt = assertIs<WhileStatement>(chunk.body.statements[1])
        val numericFor = assertIs<ForNumericStatement>(whileStmt.body.statements[1])
        assertEquals("inner", assertIs<LabelStatement>(numericFor.body.statements[0]).identifier.name)
        assertIs<IfStatement>(numericFor.body.statements[1])
        assertEquals("outer", assertIs<LabelStatement>(whileStmt.body.statements[2]).identifier.name)
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

    private fun assertMalformedLabelRecoversWithoutHang(case: MalformedLabelCase) {
        val first = parseRecoveringBounded(case.source, attempt = "first")
        val second = parseRecoveringBounded(case.source, attempt = "second")

        assertEquals(
            renderShape(first.chunk),
            renderShape(second.chunk),
            "${case.name} recovered shape should be deterministic"
        )
        assertEquals(
            first.warnings,
            second.warnings,
            "${case.name} warning stream should be deterministic"
        )

        val shape = renderShape(first.chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' in recovered shape:\n$shape"
            )
        }
        case.warningFragments.forEach { expected ->
            assertTrue(
                first.warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: ${first.warnings}"
            )
        }
    }

    /**
     * Recovery parse with a hard wall-clock budget so a hung recovery loop fails the test
     * instead of stalling review-owned Gradle verification.
     */
    private fun parseRecoveringBounded(source: String, attempt: String): RecoveryRun {
        val startedAt = System.nanoTime()
        val result = runCatching {
            LuaParser(luaVersion = LuaVersion.LUA_5_3, errorRecovery = true)
                .parseWithDiagnostics(source)
        }.getOrElse { failure ->
            fail(
                "malformed label recovery must complete without throw ($attempt); " +
                    "source:\n$source\n${failure.message}"
            )
        }
        val elapsedNanos = System.nanoTime() - startedAt
        assertTrue(
            elapsedNanos < MAX_RECOVERY_NANOS,
            "malformed label recovery appears to hang on $attempt parse: " +
                "elapsed=${elapsedNanos / 1_000_000}ms budget=${MAX_RECOVERY_NANOS / 1_000_000}ms " +
                "source length=${source.length}"
        )
        return RecoveryRun(
            chunk = result.chunk,
            warnings = result.recoveryDiagnostics.map { it.message }
        )
    }

    private data class MalformedLabelCase(
        val name: String,
        val source: String,
        val requiredShapeFragments: List<String>,
        val warningFragments: List<String> = emptyList(),
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )

    private companion object {
        // Short sources; 2s is far above normal recovery time and catches infinite loops.
        private const val MAX_RECOVERY_NANOS = 2_000_000_000L
    }
}
