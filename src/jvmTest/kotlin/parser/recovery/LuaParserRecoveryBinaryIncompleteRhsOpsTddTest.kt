package parser.recovery

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AbsSwitchCause
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.BreakStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ContinueStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionOperator
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Full binary-operator incomplete-RHS recovery matrix (TASK-480).
 *
 * Complements [LuaParserRecoveryLocalAssignTddTest] and
 * [LuaParserRecoveryMultiRhsLineBreakTddTest], which only probe a few operators
 * (`+`, occasionally `==`). This corpus covers every Lua 5.3 binary operator
 * recognized by [LuaParser.binaryPrecedence] / [ExpressionOperator]:
 *
 * arithmetic: `+ - * / // % ^`
 * concat: `..`
 * relational: `== ~= < > <= >=`
 * logical: `and or`
 * bitwise: `& | ~ << >>`
 *
 * Product notes (LuaParser.parseSubExpTail):
 * - Binary right recovers via expression terminator OR (line-break + statement-start)
 *   → `ExpressionNodeSupport` and later statements remain siblings.
 * - Strict mode currently absorbs a following `print(...)` as the binary right
 *   operand for every operator (CURRENTLY_ACCEPTS), while `local` after a line
 *   break / `end` terminator cannot be absorbed → strict REJECTS.
 * - Multi-rhs second-slot incomplete binary (`a, b = x, value //\nprint(a)`) is the
 *   same dual-path footgun and is also CURRENTLY_ACCEPTS; honest print-sibling accept
 *   inventory is BINARY_OPS.size + 1 (not BINARY_OPS.size alone).
 * - Right-associative ops (`..`, `^`) share the same incomplete-RHS recovery path.
 * - Binary `~` (BIT_TILDE) is distinct from unary bitwise-not; after a left operand
 *   it is always binary XOR.
 *
 * AST quirk: AssignmentStatement/LocalStatement `.init` = names/LHS, `.variables` = RHS.
 * Inventory honesty under WINSLICE s010 (TASK-637): accept-flag hard-locks must match
 * product dual-path behavior end-to-end; do not hide multi-rhs by weakening counts.
 * Host android.jar: SDK android-35 PRESENT; Downloads ABSENT; never G:/.
 */
class LuaParserRecoveryBinaryIncompleteRhsOpsTddTest {

    @Test
    fun recoversAssignmentBinaryIncompleteRhsPrintSiblingForAllOperators() {
        assertEquals(BINARY_OPS.size, assignmentPrintSiblingCases.size)
        assignmentPrintSiblingCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversAssignmentBinaryIncompleteRhsEndTerminatorForAllOperators() {
        assertEquals(BINARY_OPS.size, assignmentEndTerminatorCases.size)
        assignmentEndTerminatorCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversLocalBinaryIncompleteRhsLocalSiblingForAllOperators() {
        assertEquals(BINARY_OPS.size, localLocalSiblingCases.size)
        localLocalSiblingCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversReturnAndIfConditionIncompleteRhsAcrossOperatorFamilies() {
        assertEquals(contextCases.size, contextCases.size)
        assertEquals(12, contextCases.size)
        contextCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun exposesTypedBinaryStructureAfterRecoveryAcrossOperatorFamilies() {
        // Arithmetic `+` print-sibling: placeholder right + sibling CallStmt(print).
        val add = parseRecoveringWithoutThrow("a = value +\nprint(a)")
        val addAssign = assertIs<AssignmentStatement>(add.body.statements[0])
        assertEquals(1, addAssign.init.size)
        assertEquals("Id(a)", renderShape(addAssign.init[0]))
        assertEquals(1, addAssign.variables.size)
        val addBin = assertIs<BinaryExpression>(addAssign.variables[0])
        assertEquals(ExpressionOperator.ADD, addBin.operator)
        assertEquals("Id(value)", renderShape(addBin.left!!))
        assertEquals("ExpressionNodeSupport", renderShape(addBin.right!!))
        assertTrue(addBin.right!!.bad)
        val addPrint = assertIs<CallStatement>(add.body.statements[1])
        assertEquals("Call(Id(print):Id(a))", renderShape(addPrint.expression))

        // Relational `==` end-terminator form.
        val eq = parseRecoveringWithoutThrow("do\n  a = value ==\nend\nprint(a)")
        val eqDo = assertIs<DoStatement>(eq.body.statements[0])
        val eqAssign = assertIs<AssignmentStatement>(eqDo.body.statements.single())
        val eqBin = assertIs<BinaryExpression>(eqAssign.variables.single())
        assertEquals(ExpressionOperator.EQ, eqBin.operator)
        assertEquals("ExpressionNodeSupport", renderShape(eqBin.right!!))
        assertTrue(eqBin.right!!.bad)

        // Logical `and` local-sibling form.
        val andLocal = parseRecoveringWithoutThrow("local total = value and\nlocal after = 1")
        val andStmt = assertIs<LocalStatement>(andLocal.body.statements[0])
        val andBin = assertIs<BinaryExpression>(andStmt.variables.single())
        assertEquals(ExpressionOperator.AND, andBin.operator)
        assertEquals("ExpressionNodeSupport", renderShape(andBin.right!!))
        assertTrue(andBin.right!!.bad)
        val after = assertIs<LocalStatement>(andLocal.body.statements[1])
        assertEquals("Local(Id(after)=Const(1))", renderShape(after))

        // Bitwise `<<` print-sibling.
        val shl = parseRecoveringWithoutThrow("a = value <<\nprint(a)")
        val shlAssign = assertIs<AssignmentStatement>(shl.body.statements[0])
        val shlBin = assertIs<BinaryExpression>(shlAssign.variables.single())
        assertEquals(ExpressionOperator.BIT_LT, shlBin.operator)
        assertEquals("ExpressionNodeSupport", renderShape(shlBin.right!!))

        // Right-associative `^` end-terminator.
        val pow = parseRecoveringWithoutThrow("do\n  a = value ^\nend\nprint(a)")
        val powDo = assertIs<DoStatement>(pow.body.statements[0])
        val powAssign = assertIs<AssignmentStatement>(powDo.body.statements.single())
        val powBin = assertIs<BinaryExpression>(powAssign.variables.single())
        assertEquals(ExpressionOperator.BIT_EXP, powBin.operator)
        assertEquals("ExpressionNodeSupport", renderShape(powBin.right!!))

        // Concat `..` print-sibling.
        val concat = parseRecoveringWithoutThrow("a = value ..\nprint(a)")
        val concatAssign = assertIs<AssignmentStatement>(concat.body.statements[0])
        val concatBin = assertIs<BinaryExpression>(concatAssign.variables.single())
        assertEquals(ExpressionOperator.CONCAT, concatBin.operator)
        assertEquals("ExpressionNodeSupport", renderShape(concatBin.right!!))

        // Binary bitwise XOR `~` (not unary).
        val xor = parseRecoveringWithoutThrow("a = value ~\nprint(a)")
        val xorAssign = assertIs<AssignmentStatement>(xor.body.statements[0])
        val xorBin = assertIs<BinaryExpression>(xorAssign.variables.single())
        assertEquals(ExpressionOperator.BIT_TILDE, xorBin.operator)
        assertEquals("ExpressionNodeSupport", renderShape(xorBin.right!!))
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "a = value +\nprint(a)",
            "a = value -\nprint(a)",
            "a = value *\nprint(a)",
            "a = value /\nprint(a)",
            "a = value //\nprint(a)",
            "a = value %\nprint(a)",
            "a = value ^\nprint(a)",
            "a = value ..\nprint(a)",
            "a = value ==\nprint(a)",
            "a = value ~=\nprint(a)",
            "a = value <\nprint(a)",
            "a = value >\nprint(a)",
            "a = value <=\nprint(a)",
            "a = value >=\nprint(a)",
            "a = value and\nprint(a)",
            "a = value or\nprint(a)",
            "a = value &\nprint(a)",
            "a = value |\nprint(a)",
            "a = value ~\nprint(a)",
            "a = value <<\nprint(a)",
            "a = value >>\nprint(a)",
            "do\n  a = value +\nend\nprint(a)",
            "local total = value +\nlocal after = 1",
            "return value +\n",
            "if value + then work() end"
        )

        sources.forEach { source ->
            val first = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            val second = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)

            assertEquals(renderShape(first.chunk), renderShape(second.chunk), "shape deterministic: $source")
            assertEquals(first.warnings, second.warnings, "warnings deterministic: $source")
        }
    }

    @Test
    fun strictParseRejectsTrueGapsWhileRecordingHonestAcceptFlags() {
        val rejects = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty())
        rejects.forEach { case ->
            parseRecoveringWithoutThrow(case, attempt = "strict-reject-recovery-guard")
            assertStrictParseProducesDeterministicFailure(case)
        }

        val accepts = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
        }
        assertTrue(accepts.isNotEmpty())
        // Honest CURRENTLY_ACCEPTS print-sibling inventory (strict absorbs next-line print as
        // a valid call expression RHS; recovery still inserts ExpressionNodeSupport + sibling):
        // - all 21 assignment operator-matrix print-sibling cases
        // - multi-rhs second-slot incomplete `//` print-sibling sample
        // Do not collapse this to BINARY_OPS.size alone — that silently drifts past the
        // multi-rhs dual-path footgun (Windows s010: expected 21 but was 22).
        val printSiblingAccepts = accepts.filter { it.name.contains("print sibling") }
        assertEquals(
            BINARY_OPS.size + 1,
            printSiblingAccepts.size,
            "print-sibling CURRENTLY_ACCEPTS must be 21 ops + multi-rhs sample; names=${printSiblingAccepts.map { it.name }}"
        )
        assertEquals(
            BINARY_OPS.size,
            printSiblingAccepts.count {
                it.name.contains("assignment binary incomplete rhs op")
            },
            "operator-matrix assignment print-sibling accepts must cover every binary op"
        )
        assertEquals(
            1,
            printSiblingAccepts.count { it.name.contains("multi-rhs") },
            "multi-rhs second-slot print-sibling must remain CURRENTLY_ACCEPTS"
        )
        accepts.forEach(::assertStrictParseCurrentlyAccepts)
    }

    @Test
    fun wellFormedBinaryOpsStillParseCleanlyUnderRecovery() {
        val wellFormed = BINARY_OPS.map { op ->
            "a = value ${op.token} other"
        } + listOf(
            "a = 1 + 2 * 3",
            "a = b and c or d",
            "a = x << 2 | y",
            "a = p .. q .. r",
            "a = base ^ exp ^ tail",
            "local total = left == right",
            "return a ~= b"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed binary should not emit recovery diagnostics: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict), "shape parity: $source")
        }
    }

    @Test
    fun documentsBinaryIncompleteRhsOperatorMatrixInventory() {
        assertEquals(
            assignmentPrintSiblingCases.size +
                assignmentEndTerminatorCases.size +
                localLocalSiblingCases.size +
                contextCases.size,
            allCases().size
        )
        // 21 ops × 3 primary contexts + 12 multi-context family samples = 75
        assertEquals(75, allCases().size)
        assertEquals(BINARY_OPS.size, 21)

        val tokens = BINARY_OPS.map { it.token }.toSet()
        assertTrue(tokens.containsAll(listOf("+", "-", "*", "/", "//", "%", "^", "..")))
        assertTrue(tokens.containsAll(listOf("==", "~=", "<", ">", "<=", ">=")))
        assertTrue(tokens.containsAll(listOf("and", "or")))
        assertTrue(tokens.containsAll(listOf("&", "|", "~", "<<", ">>")))

        val names = allCases().map { it.name }
        BINARY_OPS.forEach { op ->
            assertTrue(
                names.any { it.contains("op `${op.token}`") && it.contains("print sibling") },
                "missing print-sibling case for ${op.token}"
            )
            assertTrue(
                names.any { it.contains("op `${op.token}`") && it.contains("end terminator") },
                "missing end-terminator case for ${op.token}"
            )
            assertTrue(
                names.any { it.contains("op `${op.token}`") && it.contains("local sibling") },
                "missing local-sibling case for ${op.token}"
            )
        }

        assertTrue(
            allCases().any {
                it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
            }
        )
        assertTrue(
            allCases().any {
                it.strictParseExpectation == StrictParseExpectation.REJECTS
            }
        )
        assertTrue(names.any { it.contains("return") })
        assertTrue(names.any { it.contains("if condition") })
        assertTrue(names.any { it.contains("while condition") })
        assertTrue(names.any { it.contains("repeat condition") })
    }

    // --- helpers -----------------------------------------------------------------

    private fun assertSupportedRecoveryCase(case: RecoveryCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)
        when (case.strictParseExpectation) {
            StrictParseExpectation.REJECTS -> assertStrictParseProducesDeterministicFailure(case)
            StrictParseExpectation.CURRENTLY_ACCEPTS,
            StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS -> assertStrictParseCurrentlyAccepts(case)
        }
    }

    private fun recoverTwiceAndAssertDeterministic(case: RecoveryCase): RecoveryRun {
        val first = parseRecoveringWithoutThrow(case, attempt = "first")
        val second = parseRecoveringWithoutThrow(case, attempt = "second")

        assertEquals(renderShape(first.chunk), renderShape(second.chunk), "${case.name} recovered shape")
        assertEquals(first.warnings, second.warnings, "${case.name} warning stream")

        return first
    }

    private fun parseRecoveringWithoutThrow(case: RecoveryCase, attempt: String): RecoveryRun {
        try {
            return parseRecoveringWithWarnings(case.version, case.source)
        } catch (failure: Throwable) {
            throw AssertionError("${case.name} should recover without throwing during $attempt parse", failure)
        }
    }

    private fun parseRecoveringWithoutThrow(
        source: String,
        version: LuaVersion = LuaVersion.LUA_5_3,
    ): ChunkNode {
        return try {
            LuaParser(luaVersion = version, errorRecovery = true).parse(source)
        } catch (failure: Throwable) {
            throw AssertionError("recovery parse must not throw for: $source", failure)
        }
    }

    private fun parseRecoveringWithWarnings(version: LuaVersion, source: String): RecoveryRun {
        val result = LuaParser(luaVersion = version, errorRecovery = true)
            .parseWithDiagnostics(source)

        return RecoveryRun(
            chunk = result.chunk,
            warnings = result.recoveryDiagnostics.map { it.message }
        )
    }

    private fun assertStrictParseProducesDeterministicFailure(case: RecoveryCase) {
        val first = assertParseFails(case.version, case.source, recovery = false)
        val second = assertParseFails(case.version, case.source, recovery = false)

        assertEquals(first::class, second::class, "${case.name} strict failure type")
        assertEquals(first.message, second.message, "${case.name} strict failure message")
    }

    private fun assertStrictParseCurrentlyAccepts(case: RecoveryCase) {
        val first = parse(case.version, case.source, recovery = false)
        val second = parse(case.version, case.source, recovery = false)

        assertEquals(renderShape(first), renderShape(second), "${case.name} strict accepted shape")
    }

    private fun assertShapeFragments(case: RecoveryCase, chunk: ChunkNode) {
        val shape = renderShape(chunk)
        case.requiredShapeFragments.forEach { expected ->
            assertTrue(
                shape.contains(expected),
                "${case.name} should keep '$expected' reachable in recovered shape:\n$shape"
            )
        }
    }

    private fun assertBadShapeFragments(case: RecoveryCase, chunk: ChunkNode) {
        if (case.badShapeFragments.isEmpty()) {
            return
        }
        val badShapes = collectBadNodes(chunk).map(::renderShape)
        case.badShapeFragments.forEach { expected ->
            assertTrue(
                badShapes.any { it.contains(expected) },
                "${case.name} should mark a recovered node containing '$expected' as bad; actual bad nodes: $badShapes"
            )
        }
    }

    private fun assertWarningFragments(case: RecoveryCase, warnings: List<String>) {
        case.warningFragments.forEach { expected ->
            assertTrue(
                warnings.any { it.contains(expected) },
                "${case.name} should emit warning containing '$expected'; actual warnings: $warnings"
            )
        }
    }

    private fun collectBadNodes(root: BaseASTNode): List<BaseASTNode> {
        val result = mutableListOf<BaseASTNode>()

        fun visit(node: BaseASTNode) {
            if (node.bad) {
                result += node
            }
            childrenOf(node).forEach(::visit)
        }

        visit(root)
        return result
    }

    private fun childrenOf(node: BaseASTNode): List<BaseASTNode> {
        return when (node) {
            is ChunkNode -> listOf(node.body)
            is BlockNode -> node.statements + listOfNotNull(node.returnStatement)
            is ReturnStatement -> node.arguments
            is LocalStatement -> node.init + node.variables
            is AssignmentStatement -> node.init + node.variables
            is CallStatement -> listOf(node.expression)
            is WhileStatement -> listOf(node.condition, node.body)
            is RepeatStatement -> listOf(node.body, node.condition)
            is DoStatement -> listOf(node.body)
            is BreakStatement -> emptyList()
            is ContinueStatement -> emptyList()
            is LabelStatement -> listOf(node.identifier)
            is GotoStatement -> listOf(node.identifier)
            is ForNumericStatement -> listOfNotNull(node.variable, node.start, node.end, node.step, node.body)
            is ForGenericStatement -> node.variables + node.iterators + listOf(node.body)
            is WhenStatement -> listOfNotNull(node.condition, node.ifCause, node.elseCause)
            is SwitchStatement -> listOf(node.condition) + node.causes
            is CaseCause -> node.conditions + listOf(node.body)
            is DefaultCause -> listOf(node.body)
            is AbsSwitchCause -> emptyList()
            is IfStatement -> node.causes
            is IfClause -> listOf(node.condition, node.body)
            is FunctionDeclaration -> listOfNotNull(node.identifier, node.body) + node.params
            is LambdaDeclaration -> node.params + listOf(node.expression)
            is BinaryExpression -> listOfNotNull(node.left, node.right)
            is UnaryExpression -> listOf(node.arg)
            is CallExpression -> listOf(node.base) + node.arguments
            is MemberExpression -> listOf(node.base, node.identifier)
            is IndexExpression -> listOf(node.base, node.index)
            is TableConstructorExpression -> node.fields
            is TableKeyString -> listOf(node.key, node.value)
            is TableKey -> listOf(node.key, node.value)
            is ArrayConstructorExpression -> node.values
            is Identifier -> emptyList()
            is VarargLiteral -> emptyList()
            is CommentStatement -> emptyList()
            is ExpressionNode -> emptyList()
            is StatementNode -> emptyList()
            else -> emptyList()
        }
    }

    private fun allCases(): List<RecoveryCase> {
        return assignmentPrintSiblingCases +
            assignmentEndTerminatorCases +
            localLocalSiblingCases +
            contextCases
    }

    private enum class StrictParseExpectation {
        REJECTS,
        CURRENTLY_ACCEPTS,
        CURRENTLY_ACCEPTS_MISSING_RHS,
    }

    private data class RecoveryCase(
        val name: String,
        val source: String,
        val version: LuaVersion = LuaVersion.LUA_5_3,
        val requiredShapeFragments: List<String>,
        val badShapeFragments: List<String> = emptyList(),
        val warningFragments: List<String> = emptyList(),
        val strictParseExpectation: StrictParseExpectation = StrictParseExpectation.REJECTS,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )

    private data class BinaryOp(
        val token: String,
        val shapeToken: String = token,
    )

    /**
     * Every binary operator with a non-zero [LuaParser] binaryPrecedence entry.
     * Shape token matches [ExpressionOperator.toString] / renderShape.
     */
    private companion object {
        val BINARY_OPS = listOf(
            // arithmetic
            BinaryOp("+"),
            BinaryOp("-"),
            BinaryOp("*"),
            BinaryOp("/"),
            BinaryOp("//"),
            BinaryOp("%"),
            BinaryOp("^"),
            // concat (right-associative)
            BinaryOp(".."),
            // relational
            BinaryOp("=="),
            BinaryOp("~="),
            BinaryOp("<"),
            BinaryOp(">"),
            BinaryOp("<="),
            BinaryOp(">="),
            // logical
            BinaryOp("and"),
            BinaryOp("or"),
            // bitwise
            BinaryOp("&"),
            BinaryOp("|"),
            BinaryOp("~"),
            BinaryOp("<<"),
            BinaryOp(">>"),
        )
    }

    // Assignment: incomplete binary RHS with line-break + statement-start print.
    // Recovery: Binary(op,Id(value),ExpressionNodeSupport) + sibling CallStmt(print).
    // Strict: currently absorbs print as the binary right → CURRENTLY_ACCEPTS.
    // These 21 cases are the operator-matrix half of the print-sibling accept inventory;
    // multi-rhs second-slot incomplete `//` below is the additional +1 dual-path sample.
    private val assignmentPrintSiblingCases: List<RecoveryCase> = BINARY_OPS.map { op ->
        RecoveryCase(
            name = "assignment binary incomplete rhs op `${op.token}` leaves print sibling",
            source = "a = value ${op.token}\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(${op.shapeToken},Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    }

    // Assignment: incomplete binary RHS before expression terminator `end`.
    // Recovery: placeholder right inside do-block; later print reachable.
    // Strict: REJECTS (no absorbable operand).
    private val assignmentEndTerminatorCases: List<RecoveryCase> = BINARY_OPS.map { op ->
        RecoveryCase(
            name = "assignment binary incomplete rhs op `${op.token}` end terminator keeps later print",
            source = "do\n  a = value ${op.token}\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Assign(Id(a)=Binary(${op.shapeToken},Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    }

    // Local initializer: incomplete binary RHS with following local statement.
    // Recovery: placeholder right + sibling Local; strict REJECTS.
    private val localLocalSiblingCases: List<RecoveryCase> = BINARY_OPS.map { op ->
        RecoveryCase(
            name = "local binary incomplete rhs op `${op.token}` leaves local sibling",
            source = "local total = value ${op.token}\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(Id(total)=Binary(${op.shapeToken},Id(value),ExpressionNodeSupport))",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    }

    // Multi-context samples across operator families (return / if / while / repeat / multi-RHS).
    private val contextCases = listOf(
        RecoveryCase(
            name = "return arithmetic incomplete rhs op `+` before end keeps later print",
            source = "do\n  return value +\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Return(Binary(+,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "return relational incomplete rhs op `==` before end keeps later print",
            source = "do\n  return value ==\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Return(Binary(==,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "return logical incomplete rhs op `and` before end keeps later print",
            source = "do\n  return value and\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Return(Binary(and,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "return bitwise incomplete rhs op `|` before end keeps later print",
            source = "do\n  return value |\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Return(Binary(|,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "return concat incomplete rhs op `..` before end keeps later print",
            source = "do\n  return value ..\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Return(Binary(..,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "return pow incomplete rhs op `^` before end keeps later print",
            source = "do\n  return value ^\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Return(Binary(^,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "if condition incomplete rhs op `+` keeps then body",
            source = "if value + then work() end\nprint(value)",
            requiredShapeFragments = listOf(
                "Binary(+,Id(value),ExpressionNodeSupport)",
                "CallStmt(Call(Id(work):))",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "if condition incomplete rhs op `==` keeps then body",
            source = "if value == then work() end\nprint(value)",
            requiredShapeFragments = listOf(
                "Binary(==,Id(value),ExpressionNodeSupport)",
                "CallStmt(Call(Id(work):))",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "if condition incomplete rhs op `and` keeps then body",
            source = "if value and then work() end\nprint(value)",
            requiredShapeFragments = listOf(
                "Binary(and,Id(value),ExpressionNodeSupport)",
                "CallStmt(Call(Id(work):))",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "while condition incomplete rhs op `<` keeps body and later print",
            source = "while value < do work() end\nprint(value)",
            requiredShapeFragments = listOf(
                "While(Binary(<,Id(value),ExpressionNodeSupport):",
                "CallStmt(Call(Id(work):))",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "repeat condition incomplete rhs op `~=` keeps body and later print",
            source = "repeat work() until value ~=\nprint(value)",
            requiredShapeFragments = listOf(
                "Repeat(",
                "Binary(~=,Id(value),ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            // Strict absorbs next-line print as the relational right operand.
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "assignment multi-rhs second slot incomplete op `//` leaves print sibling",
            source = "a, b = x, value //\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Id(x),Binary(//,Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )
}
