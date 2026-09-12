package parser

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionOperator
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Parser audit leftovers (FIXER-PARSER2 wave V), three fixes against HEAD e13ce40:
 *
 * 1. Compound assignment (`n += 2`) desugaring was reachable in EVERY dialect because the
 *    lexer emits `*_ASSIGN` tokens unconditionally. The parseExpStatement hook now gates on
 *    [LuaVersion.ANDROLUA_5_3] with the sibling lambda/switch/continue `assertVersion`
 *    contract: version gating is a hard error, not a recovery boundary, so plain LUA_5_3/5_4
 *    rejects `n += 2` again (pre-wave-A it failed with "'=' expected").
 *
 * 2. goto-target absorption: `goto\nprint(1)` absorbed the NAME across the line break as the
 *    label and the TASK-639 same-line drain then swallowed `(1)`, dropping the print call
 *    entirely. The target parse now applies the shared statement-start recovery
 *    (hasLineBreakBeforeNextSignificantToken + shouldRecoverStatementStartAsMissingExpression):
 *    call-shaped / keyword statement starts after the break recover as a missing-label
 *    diagnostic and stay unconsumed so the block parses them as siblings. A bare NAME after
 *    the break still parses as the label (well-formed `goto\nlabel` keeps working).
 *
 * 3. Unary absorption: `a = not\nprint(a)` absorbed the next statement as the operand. The
 *    operand parse now applies the exact parseSubExpTail binary-operand recovery
 *    (line break + statement start -> missingExpression, silently): the assignment keeps a
 *    bad unary operand and the print call survives as a sibling statement. Strict mode keeps
 *    parsing the (valid) cross-line operand form.
 *
 * Shapes use [renderShape]; ExpressionNodeSupport (missing expression placeholder) renders
 * as "ExpressionNodeSupport".
 */
class ParserAuditLeftoverRecoveryTddTest {

    // --- audit 1: compound assignment is an AndroLua-only dialect feature ---

    @Test
    fun plainLuaRejectsCompoundAssignmentInStrictAndRecoveryModes() {
        listOf(LuaVersion.LUA_5_3, LuaVersion.LUA_5_4).forEach { version ->
            listOf(false, true).forEach { recovery ->
                val failure = assertFails {
                    parse(version = version, source = "n += 2", recovery = recovery)
                }
                assertTrue(
                    failure.message.orEmpty()
                        .contains("compound assignment statement is only supported in androlua 5.3"),
                    "expected the version-gate failure for $version with recovery=$recovery " +
                        "but was: ${failure.message}"
                )
            }
        }
    }

    @Test
    fun androLuaCompoundAssignmentStillDesugarsRecoveryFree() {
        val result = recovering("local n = 1\nn += 2")

        assertEquals(
            emptyList(),
            result.recoveryDiagnostics.map { it.message },
            "AndroLua compound assignment must stay recovery-free"
        )
        assertEquals(
            "Chunk(Block[Local(Id(n)=Const(1));Assign(Id(n)=Binary(+,Id(n),Const(2)))])",
            renderShape(result.chunk)
        )
    }

    @Test
    fun androLuaStrictCompoundAssignmentStillParses() {
        val strict = LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false)
            .parse("t.x += 1")

        assertEquals(
            "Chunk(Block[Assign(Member(Id(t).x)=Binary(+,Member(Id(t).x),Const(1)))])",
            renderShape(strict)
        )
    }

    @Test
    fun plainLua53PlainPlusAssignmentIsUntouchedByTheGate() {
        // The gate lives on the *_ASSIGN hook only; a plain `+` assignment under
        // strict LUA_5_3 keeps parsing so the hook cannot poison regular expressions.
        assertEquals(
            "Chunk(Block[Assign(Id(n)=Binary(+,Id(n),Const(2)))])",
            renderShape(parse(LuaVersion.LUA_5_3, "n = n + 2"))
        )
    }

    // --- audit 2: goto must not absorb a call statement across the line break ---

    @Test
    fun recoveringGotoKeepsCallStatementAsSiblingAcrossLineBreak() {
        val result = recovering("goto\nprint(1)")

        assertEquals(
            "Chunk(Block[Goto(Id());CallStmt(Call(Id(print):Const(1)))])",
            renderShape(result.chunk),
            "goto must recover with a missing label and print(1) must survive as a sibling"
        )

        val goto = assertIs<GotoStatement>(result.chunk.body.statements.first())
        assertTrue(goto.identifier.bad, "cross-line call-shaped target must recover as a bad label")
        assertEquals("", goto.identifier.name)
        assertTrue(goto.identifier.parent === goto, "missing label parent must be the goto statement")

        assertTrue(
            result.recoveryDiagnostics.any { it.message.contains("<name> expected") },
            "expected a missing goto-label diagnostic; got ${result.recoveryDiagnostics.map { it.message }}"
        )
    }

    @Test
    fun strictGotoStillRejectsTargetAbsorbedAcrossLineBreak() {
        // TASK-639 contract: strict mode leaves the residual tokens so `goto\nprint(1)`
        // still rejects (the leftover `(1)` cannot form a call statement).
        assertParseFails(LuaVersion.LUA_5_3, "goto\nprint(1)", recovery = false)
    }

    @Test
    fun gotoBareNameTargetAfterLineBreakStillParsesAsLabel() {
        // Only call-shaped / keyword statement starts recover; a bare NAME after the
        // line break is still the label, so well-formed `goto\nlabel` keeps working.
        val result = recovering("goto\nloop1")

        assertEquals("Chunk(Block[Goto(Id(loop1))])", renderShape(result.chunk))
        assertEquals(
            emptyList(),
            result.recoveryDiagnostics.map { it.message },
            "bare cross-line goto target must stay recovery-free"
        )
    }

    @Test
    fun gotoSameLineCallShapedTargetKeepsTask639DrainShape() {
        // Without a line break the guard must not fire: the NAME is absorbed as the
        // label and the same-line leftover `(1)` is drained (TASK-639).
        val result = recovering("goto print(1)")

        assertEquals("Chunk(Block[Goto(Id(print))])", renderShape(result.chunk))
        assertEquals(
            emptyList(),
            result.recoveryDiagnostics.map { it.message },
            "same-line goto drain stays silent"
        )
    }

    // --- audit 3: unary must not absorb a statement across the line break ---

    @Test
    fun recoveringUnaryNotKeepsPrintCallAsSiblingAcrossLineBreak() {
        val result = recovering("a = not\nprint(a)")

        assertEquals(
            "Chunk(Block[Assign(Id(a)=Unary(not,ExpressionNodeSupport));CallStmt(Call(Id(print):Id(a)))])",
            renderShape(result.chunk),
            "assignment must keep a bad unary operand and print(a) must survive as a sibling"
        )

        val assignment = assertIs<AssignmentStatement>(result.chunk.body.statements.first())
        assertEquals("a", (assignment.init.single() as Identifier).name)
        val unary = assertIs<UnaryExpression>(assignment.variables.single())
        assertEquals(ExpressionOperator.NOT, unary.operator)
        assertTrue(unary.arg.bad, "cross-line operand must recover as a missing (bad) operand")
        assertTrue(unary.arg.parent === unary, "missing operand parent must be the unary expression")

        // Statement-start operand recovery inserts the placeholder silently, matching
        // the parseSubExpTail binary-operand recovery this mirrors.
        assertEquals(
            emptyList(),
            result.recoveryDiagnostics.map { it.message },
            "unary statement-start recovery stays silent like the binary tail"
        )
    }

    @Test
    fun strictUnaryStillParsesCrossLineOperand() {
        // `a = not\nprint(a)` is valid Lua (`not` applied to the call); strict mode keeps
        // parsing the cross-line operand because the recovery guard is recovery-only.
        assertEquals(
            "Chunk(Block[Assign(Id(a)=Unary(not,Call(Id(print):Id(a))))])",
            renderShape(parse(LuaVersion.LUA_5_3, "a = not\nprint(a)", recovery = false))
        )
    }

    // --- helpers ---

    private fun recovering(source: String): LuaParseResult {
        // AndroLua dialect: compound assignment is version-gated to it, and the goto /
        // unary recovery shapes under test are AndroLua-corpus shapes.
        return LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = true)
            .parseWithDiagnostics(source)
    }
}
