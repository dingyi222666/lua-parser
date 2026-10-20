package parser

import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionOperator
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.source.AST2Lua
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AndroLua C-style compound assignment desugaring (`n += 1` parses as `n = n + 1`).
 *
 * Covers the acceptance matrix for the lexer's dedicated `+=` `-=` `*=` `/=` `//=`
 * tokens (the lexer has no MOD_ASSIGN, so `%=` is out of scope):
 *
 * 1. `local n = 1` + `n += 2` parses with zero recovery diagnostics; statement 2 is an
 *    [AssignmentStatement] whose assigned target is the Identifier `n` and whose value is
 *    `BinaryExpression(ADD, Identifier n, ConstantNode 2)`.
 * 2. The desugared statement prints `n = n + 2` and the printed source reparses
 *    recovery-free.
 * 3. `+=` `-=` `*=` `/=` `//=` map to ADD MINUS MULT DIV DOUBLE_DIV.
 * 4. Member (`t.x += 1`) and index (`t[1] -= 2`) targets desugar with a clone of only the
 *    target in the RHS; every cloned node carries a parent pointer.
 * 5. errorRecovery=false parses compound assignments without exceptions.
 *
 * Field-name note: this codebase's [AssignmentStatement] lists are printer-reversed.
 * AST2Lua prints `init` first, then `=`, then `variables`, so the *target* lives in
 * `init` and the desugared *value* lives in `variables` — the exact shape the plain
 * assignment production builds.
 *
 * Node-type checks use `is` checks plus `as` casts into local vals (no `assertIs`).
 */
class CompoundAssignmentTddTest {

    private val printer = AST2Lua()

    // --- acceptance 1: recovery-free parse + desugared statement shape ---

    @Test
    fun plusAssignDesugarsToPlainAssignmentWithoutRecoveryDiagnostics() {
        val result = recovering("local n = 1\nn += 2")

        assertNoDiagnostics(result)

        val statement = assignmentStatementAt(result, index = 1)
        assertFalse(statement.bad, "desugared statement must not be marked bad")

        val target = statement.init.single() as Identifier
        assertEquals("n", target.name)
        assertTrue(target.parent === statement, "target parent must be the desugared statement")

        val binary = statement.variables.single() as BinaryExpression
        assertEquals(ExpressionOperator.ADD, binary.operator)
        assertTrue(binary.parent === statement, "desugared value parent must be the statement")

        val left = assertNotNull(binary.left)
        assertTrue(left is Identifier, "left operand must be an Identifier but was ${left::class.simpleName}")
        val leftIdentifier = left as Identifier
        assertEquals("n", leftIdentifier.name)
        assertTrue(leftIdentifier.parent === binary, "cloned left operand parent must be the binary expression")
        assertFalse(left === target, "RHS operand must be a distinct clone of the target, not the same node")

        val right = assertNotNull(binary.right)
        assertTrue(right is ConstantNode, "right operand must be a ConstantNode but was ${right::class.simpleName}")
        val rightConstant = right as ConstantNode
        assertEquals(ConstantNode.TYPE.INTERGER, rightConstant.constantType)
        assertEquals("2", rightConstant.rawValue.toString())
        assertTrue(rightConstant.parent === binary, "RHS constant parent must be the binary expression")
    }

    // --- acceptance 2: AST2Lua round-trip ---

    @Test
    fun desugaredStatementPrintsPlainAssignmentAndPrintedSourceReparsesRecoveryFree() {
        val result = recovering("local n = 1\nn += 2")
        val printed = printer.asCode(result.chunk)

        assertEquals("local n = 1\nn = n + 2", printed.trim(), "printed:\n$printed")

        val reparse = recovering(printed)
        assertNoDiagnostics(reparse, printed)

        val statement = assignmentStatementAt(reparse, index = 1)
        val binary = statement.variables.single() as BinaryExpression
        assertEquals(ExpressionOperator.ADD, binary.operator)
        assertEquals("n", (statement.init.single() as Identifier).name)
    }

    // --- acceptance 3: operator mapping ---

    @Test
    fun allCompoundOperatorsDesugarToTheirBinaryOperator() {
        val cases = listOf(
            "+=" to ExpressionOperator.ADD,
            "-=" to ExpressionOperator.MINUS,
            "*=" to ExpressionOperator.MULT,
            "/=" to ExpressionOperator.DIV,
            "//=" to ExpressionOperator.DOUBLE_DIV
        )

        cases.forEach { (operatorText, expectedOperator) ->
            val source = "n = 1\nn $operatorText 2"
            val result = recovering(source)
            assertNoDiagnostics(result, source)

            val statement = assignmentStatementAt(result, index = 1)
            val binary = statement.variables.single() as BinaryExpression
            assertEquals(expectedOperator, binary.operator, "operator for <$source>")

            val printed = printer.asCode(result.chunk).trim()
            assertEquals("n = 1\nn = n ${expectedOperator.value} 2", printed, "printed for <$source>")

            val reparse = recovering(printed)
            assertNoDiagnostics(reparse, printed)
        }
    }

    // --- acceptance 4: member / index targets with fully parented clones ---

    @Test
    fun memberTargetDesugarsCloningOnlyTheTargetWithParentsAssigned() {
        val result = recovering("t.x += 1")
        assertNoDiagnostics(result)

        val statement = assignmentStatementAt(result, index = 0)
        val target = statement.init.single() as MemberExpression
        assertEquals(".", target.indexer)
        assertEquals("t", (target.base as Identifier).name)
        assertEquals("x", target.identifier.name)
        assertTrue(target.parent === statement)

        val binary = statement.variables.single() as BinaryExpression
        assertEquals(ExpressionOperator.ADD, binary.operator)

        val left = assertNotNull(binary.left)
        assertFalse(left === target, "RHS operand must be a clone, not the parsed target node")
        assertTrue(left is MemberExpression, "clone must keep the MemberExpression shape")
        val clone = left as MemberExpression
        assertTrue(clone.parent === binary, "clone parent must be the binary expression")
        assertTrue(clone.base.parent === clone, "clone base parent must be the clone")
        assertTrue(clone.identifier.parent === clone, "clone identifier parent must be the clone")
        assertEquals("t", (clone.base as Identifier).name)
        assertEquals("x", clone.identifier.name)

        val right = assertNotNull(binary.right) as ConstantNode
        assertEquals("1", right.rawValue.toString())
        assertTrue(right.parent === binary)

        val printed = printer.asCode(result.chunk).trim()
        assertEquals("t.x = t.x + 1", printed, "printed:\n$printed")
        assertNoDiagnostics(recovering(printed), printed)
    }

    @Test
    fun indexTargetDesugarsCloningOnlyTheTargetWithParentsAssigned() {
        val result = recovering("t[1] -= 2")
        assertNoDiagnostics(result)

        val statement = assignmentStatementAt(result, index = 0)
        val target = statement.init.single() as IndexExpression
        assertEquals("t", (target.base as Identifier).name)
        assertEquals("1", (target.index as ConstantNode).rawValue.toString())
        assertTrue(target.parent === statement)

        val binary = statement.variables.single() as BinaryExpression
        assertEquals(ExpressionOperator.MINUS, binary.operator)

        val left = assertNotNull(binary.left)
        assertFalse(left === target, "RHS operand must be a clone, not the parsed target node")
        assertTrue(left is IndexExpression, "clone must keep the IndexExpression shape")
        val clone = left as IndexExpression
        assertTrue(clone.parent === binary, "clone parent must be the binary expression")
        assertTrue(clone.base.parent === clone, "clone base parent must be the clone")
        assertTrue(clone.index.parent === clone, "clone index parent must be the clone")

        val right = assertNotNull(binary.right) as ConstantNode
        assertEquals("2", right.rawValue.toString())
        assertTrue(right.parent === binary)

        val printed = printer.asCode(result.chunk).trim()
        assertEquals("t[1] = t[1] - 2", printed, "printed:\n$printed")
        assertNoDiagnostics(recovering(printed), printed)
    }

    // --- acceptance 5: strict mode ---

    @Test
    fun strictModeParsesCompoundAssignmentsWithoutExceptions() {
        listOf("+=", "-=", "*=", "/=", "//=").forEach { operatorText ->
            val expectedOperator = when (operatorText) {
                "+=" -> ExpressionOperator.ADD
                "-=" -> ExpressionOperator.MINUS
                "*=" -> ExpressionOperator.MULT
                "/=" -> ExpressionOperator.DIV
                else -> ExpressionOperator.DOUBLE_DIV
            }

            val chunk = strict("local n = 1\nn $operatorText 2")
            val statements = chunk.body.statements
            assertTrue(statements.size == 2, "expected two statements for <$operatorText>")
            val statement = statements[1]
            assertTrue(
                statement is AssignmentStatement,
                "statement must be an AssignmentStatement for <$operatorText>"
            )
            val assignment = statement as AssignmentStatement
            assertEquals("n", (assignment.init.single() as Identifier).name)
            val binary = assignment.variables.single() as BinaryExpression
            assertEquals(expectedOperator, binary.operator, "operator for <$operatorText>")
        }
    }

    @Test
    fun strictModeStillRejectsIncompleteCompoundAssignmentRhs() {
        assertFails { strict("n +=") }
    }

    // --- regression guards: plain statements must be untouched ---

    @Test
    fun plainAssignmentsAndCallStatementsKeepTheirExistingShape() {
        val assignment = recovering("n = n + 2")
        assertNoDiagnostics(assignment)
        val statement = assignmentStatementAt(assignment, index = 0)
        assertEquals(1, statement.init.size)
        assertEquals(1, statement.variables.size)
        val binary = statement.variables.single() as BinaryExpression
        assertEquals(ExpressionOperator.ADD, binary.operator)
        assertEquals("n", (statement.init.single() as Identifier).name)

        val call = recovering("print('x')")
        assertNoDiagnostics(call)
        val callStatement = call.chunk.body.statements.single()
        assertTrue(
            callStatement is CallStatement,
            "call statement must stay a CallStatement but was ${callStatement::class.simpleName}"
        )
    }

    // --- helpers ---

    private fun recovering(source: String): LuaParseResult {
        return LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = true)
            .parseWithDiagnostics(source)
    }

    private fun strict(source: String): ChunkNode {
        return LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false)
            .parse(source)
    }

    private fun assertNoDiagnostics(result: LuaParseResult, context: String = "") {
        val suffix = if (context.isEmpty()) "" else " for <$context>"
        assertEquals(
            emptyList(),
            result.recoveryDiagnostics.map { it.message },
            "expected a recovery-free parse$suffix"
        )
    }

    private fun assignmentStatementAt(result: LuaParseResult, index: Int): AssignmentStatement {
        val statements = result.chunk.body.statements
        assertTrue(index < statements.size, "expected a statement at index $index")
        val statement = statements[index]
        assertTrue(
            statement is AssignmentStatement,
            "statement at index $index must be an AssignmentStatement but was ${statement::class.simpleName}"
        )
        return statement as AssignmentStatement
    }
}
