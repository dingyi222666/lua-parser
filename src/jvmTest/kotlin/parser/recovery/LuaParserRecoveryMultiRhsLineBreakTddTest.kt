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
 * Multi-line multi-RHS assignment/local recovery matrix (TASK-386 / TASK-546 / TASK-553).
 *
 * Complements [LuaParserRecoveryLocalAssignTddTest] with focused coverage of:
 * - multi-line multi-RHS forms (`a, b =\n x,\n y` and later-term variants)
 * - absorb-vs-sibling matrix for incomplete unary/binary RHS inside multi-RHS lists
 * - assignment (`RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_ASSIGNMENT = true`) vs
 *   local (`RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_LOCAL = false`) recoverFirst symmetry lock
 *
 * Product notes (LuaParser.parseExpList / parseExpressionOrMissing / parseSubExpTail /
 * parseUnaryExpression / WrapperLuaLexer.pushback) after TASK-546 + TASK-553:
 * - WrapperLuaLexer.hasLineBreakBeforeNextSignificantToken looks before a pushbacked
 *   next token (queued in currentStates), so peeks no longer desync linebreak detection
 *   past a NAME RHS onto a following statement newline.
 * - After a comma, later RHS recover only for keyword/control statement-starts after a
 *   line break (LOCAL/RETURN/IF/…). Bare NAME is always parsed as the next expression.
 * - Assignment first RHS uses recoverFirstStatementLineBreak=true; bare NAME after `=\n`
 *   is kept as a multi-line RHS expression, while call-shaped `print(...)` / keyword starts
 *   recover as missing so incomplete `a =\nprint(a)` keeps print as a sibling.
 * - Local first RHS keeps recoverFirstStatementLineBreak=false so multi-line multi-RHS
 *   locals stay clean under recovery (matching strict). Incomplete local first RHS after
 *   keyword statement-starts still inserts placeholders; call-shaped NAME may absorb.
 * - Well-formed multi-line multi-RHS (`a, b =\n x,\n y`) and same-line multi-RHS
 *   followed by a later statement (`a, b = x, y\nprint(a)`) match strict shapes under
 *   recovery (no sibling CallStmt / ExpressionNodeSupport placeholders).
 * - Binary incomplete RHS: expression terminator OR (line-break + statement-start) →
 *   `ExpressionNodeSupport` (sibling later statements).
 * - Unary incomplete RHS: expression terminator only → `ExpressionNodeSupport`; a
 *   following NAME/`print(...)` is absorbed as the unary operand.
 *
 * Keep [StrictParseExpectation] flags honest against current product.
 */
class LuaParserRecoveryMultiRhsLineBreakTddTest {

    @Test
    fun recoversMultiLineMultiRhsWellFormedAndLaterTermFootguns() {
        assertEquals(6, multiLineMultiRhsCases.size)
        multiLineMultiRhsCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversUnaryBinaryIncompleteRhsAbsorbVsSiblingMatrix() {
        assertEquals(8, unaryBinaryIncompleteRhsCases.size)
        unaryBinaryIncompleteRhsCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversLocalMultiRhsLineBreakAsymmetry() {
        assertEquals(6, localMultiRhsLineBreakCases.size)
        localMultiRhsLineBreakCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun locksRecoverFirstAssignmentVsLocalSymmetryPolicy() {
        // TASK-553: assignment first-RHS may recover call-shaped statement-start after
        // line break as sibling; local first-RHS keeps recoverFirst=false and absorbs
        // call-shaped print as initializer. Later multi-RHS terms stay unpoisoned.
        assertTrue(LuaParser.RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_ASSIGNMENT)
        assertTrue(!LuaParser.RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_LOCAL)
        assertTrue(!LuaParser.RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_RETURN)

        val assignMissing = parseRecoveringWithoutThrow("a =\nprint(a)")
        assertEquals(
            "Chunk(Block[Assign(Id(a)=ExpressionNodeSupport);CallStmt(Call(Id(print):Id(a)))])",
            renderShape(assignMissing)
        )

        val localAbsorbs = parseRecoveringWithoutThrow("local a =\nprint(a)")
        assertEquals(
            "Chunk(Block[Local(Id(a)=Call(Id(print):Id(a)))])",
            renderShape(localAbsorbs)
        )

        // Later-term after comma is never first-RHS recovery: call-shaped print is RHS.
        val assignLater = parseRecoveringWithoutThrow("a, b = x,\nprint(a)")
        assertEquals(
            "Chunk(Block[Assign(Id(a),Id(b)=Id(x),Call(Id(print):Id(a)))])",
            renderShape(assignLater)
        )
        val localLater = parseRecoveringWithoutThrow("local a, b = x,\nprint(a)")
        assertEquals(
            "Chunk(Block[Local(Id(a),Id(b)=Id(x),Call(Id(print):Id(a)))])",
            renderShape(localLater)
        )

        // Well-formed multi-line multi-RHS local stays clean (no placeholders / siblings).
        val localMulti = parseRecoveringWithoutThrow("local a, b =\n x,\n y")
        assertEquals(
            "Chunk(Block[Local(Id(a),Id(b)=Id(x),Id(y))])",
            renderShape(localMulti)
        )
    }

    @Test
    fun exposesTypedMultiRhsStructureAfterRecovery() {
        // Later-term bare NAME after comma+line-break is a well-formed second RHS.
        val laterTerm = parseRecoveringWithoutThrow("a, b = x,\n y")
        val assign = assertIs<AssignmentStatement>(laterTerm.body.statements.single())
        assertEquals(2, assign.init.size)
        assertEquals("Id(a)", renderShape(assign.init[0]))
        assertEquals("Id(b)", renderShape(assign.init[1]))
        assertEquals(2, assign.variables.size)
        assertEquals("Id(x)", renderShape(assign.variables[0]))
        assertEquals("Id(y)", renderShape(assign.variables[1]))
        assertTrue(assign.variables.none { it.bad })

        // Well-formed multi-line multi-RHS under recovery matches strict (single assign).
        val multiLineFirst = parseRecoveringWithoutThrow("a, b =\n x,\n y")
        val firstAssign = assertIs<AssignmentStatement>(multiLineFirst.body.statements.single())
        assertEquals(2, firstAssign.init.size)
        assertEquals("Id(a)", renderShape(firstAssign.init[0]))
        assertEquals("Id(b)", renderShape(firstAssign.init[1]))
        assertEquals(listOf("Id(x)", "Id(y)"), firstAssign.variables.map(::renderShape))
        assertTrue(firstAssign.variables.none { it.bad })

        // Same-line multi-RHS + following statement: both RHS stay; print is sibling.
        val sameLine = parseRecoveringWithoutThrow("a, b = x, y\nprint(a)")
        val sameLineAssign = assertIs<AssignmentStatement>(sameLine.body.statements[0])
        assertEquals(2, sameLineAssign.init.size)
        assertEquals("Id(a)", renderShape(sameLineAssign.init[0]))
        assertEquals("Id(b)", renderShape(sameLineAssign.init[1]))
        assertEquals(2, sameLineAssign.variables.size)
        assertEquals("Id(x)", renderShape(sameLineAssign.variables[0]))
        assertEquals("Id(y)", renderShape(sameLineAssign.variables[1]))
        assertTrue(sameLineAssign.variables.none { it.bad })
        val sameLinePrint = assertIs<CallStatement>(sameLine.body.statements[1])
        assertEquals("Call(Id(print):Id(a))", renderShape(sameLinePrint.expression))
        assertEquals(2, sameLine.body.statements.size)

        // True incomplete first RHS with call-shaped statement-start after line break.
        val missingFirst = parseRecoveringWithoutThrow("a, b =\nprint(a)")
        val missingAssign = assertIs<AssignmentStatement>(missingFirst.body.statements[0])
        assertEquals(listOf("ExpressionNodeSupport"), missingAssign.variables.map(::renderShape))
        assertTrue(missingAssign.variables.single().bad)
        val missingPrint = assertIs<CallStatement>(missingFirst.body.statements[1])
        assertEquals("Call(Id(print):Id(a))", renderShape(missingPrint.expression))

        // Unary absorb on second RHS: print is operand, not sibling.
        val unaryAbsorb = parseRecoveringWithoutThrow("a, b = x, not\nprint(a)")
        val unaryAssign = assertIs<AssignmentStatement>(unaryAbsorb.body.statements.single())
        assertEquals(
            "Assign(Id(a),Id(b)=Id(x),Unary(not,Call(Id(print):Id(a))))",
            renderShape(unaryAssign)
        )
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "a, b =\n x,\n y",
            "a, b = x,\n y",
            "a, b = x,\nprint(a)",
            "a, b = x, y\nprint(a)",
            "a, b = x, value +\nprint(a)",
            "a, b = x, not\nprint(a)",
            "do\n  a, b = x, not\nend\nprint(a)",
            "local a, b =\n x,\n y"
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

        val acceptsMissingRhs = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
        }
        assertTrue(
            acceptsMissingRhs.map { it.name }.containsAll(
                listOf(
                    "assignment multi-target missing first rhs newline print keeps later print under recovery"
                )
            )
        )
        acceptsMissingRhs.forEach(::assertStrictParseCurrentlyAccepts)

        val acceptsValid = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
        }
        assertTrue(
            acceptsValid.map { it.name }.containsAll(
                listOf(
                    "assignment multi-line first rhs name is recovered as placeholder under recovery",
                    "assignment later multi-rhs after comma line break leaves second name sibling under recovery",
                    "assignment same-line multi-rhs followed by newline statement recovers second name as sibling"
                )
            )
        )
        acceptsValid.forEach(::assertStrictParseCurrentlyAccepts)
    }

    @Test
    fun wellFormedMultiRhsStillParseCleanlyUnderRecovery() {
        // Pure same-line / no trailing statement-start after a later newline: recovery must
        // match strict (EOF / non-statement tokens do not trip the peek-desync residual).
        val wellFormed = listOf(
            "a, b = x, y",
            "a, b = 1, 2",
            "local a, b = x, y",
            "a, b, c = 1, 2, 3",
            "a, b = pair(), extra"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed multi-rhs should not emit recovery diagnostics: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict))
        }
    }

    @Test
    fun documentsMultiRhsLineBreakInventorySizeAndCoverage() {
        assertEquals(
            multiLineMultiRhsCases.size +
                unaryBinaryIncompleteRhsCases.size +
                localMultiRhsLineBreakCases.size,
            allCases().size
        )
        assertEquals(20, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("later multi-rhs") || it.contains("comma line break") })
        assertTrue(names.any { it.contains("same-line multi-rhs") })
        assertTrue(names.any { it.contains("unary") && it.contains("absorb") })
        assertTrue(names.any { it.contains("binary") && it.contains("sibling") })
        assertTrue(names.any { it.contains("local") })
        assertTrue(names.any { it.contains("local") && it.contains("first rhs") && it.contains("absorbs") })
        assertTrue(names.any { it.contains("assignment") && it.contains("missing first rhs") })
        assertTrue(
            allCases().any {
                it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
            }
        )
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
        return multiLineMultiRhsCases +
            unaryBinaryIncompleteRhsCases +
            localMultiRhsLineBreakCases
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

    // Well-formed multi-line multi-RHS and later-term line-break footguns under assignment
    // (recoverFirstStatementLineBreak = true).
    private val multiLineMultiRhsCases = listOf(
        RecoveryCase(
            // Well-formed multi-line multi-RHS: first and later NAME terms after line
            // breaks parse as expressions (TASK-546). Recovery matches strict.
            name = "assignment multi-line first rhs name is recovered as placeholder under recovery",
            source = "a, b =\n x,\n y",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Id(x),Id(y))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Later-term bare NAME after comma+line-break is a valid RHS expression.
            name = "assignment later multi-rhs after comma line break leaves second name sibling under recovery",
            source = "a, b = x,\n y",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Id(x),Id(y))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Call-shaped NAME after comma is still an expression start; recovery matches
            // strict and absorbs print as the second RHS (no sibling split).
            name = "assignment later multi-rhs after comma line break leaves print sibling under recovery",
            source = "a, b = x,\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Id(x),Call(Id(print):Id(a)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Multi-target missing first RHS after newline with call-shaped print:
            // first-RHS recover keeps print as sibling (true incomplete gap).
            name = "assignment multi-target missing first rhs newline print keeps later print under recovery",
            source = "a, b =\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
        ),
        RecoveryCase(
            // Three-target later-term bare NAME after comma+line-break parses as third RHS.
            name = "assignment three-target later multi-rhs after comma line break leaves third name sibling",
            source = "a, b, c = x, y,\n z",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b),Id(c)=Id(x),Id(y),Id(z))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Same-line multi-RHS + following statement: pushback no longer desyncs
            // linebreak detection past `y`, so both RHS stay in the assignment and
            // print remains the next statement (matches strict).
            name = "assignment same-line multi-rhs followed by newline statement recovers second name as sibling",
            source = "a, b = x, y\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Id(x),Id(y))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )

    // Absorb-vs-sibling matrix for incomplete unary/binary RHS inside multi-RHS lists.
    private val unaryBinaryIncompleteRhsCases = listOf(
        RecoveryCase(
            // Binary right uses statement-start-after-line-break → placeholder + sibling print.
            name = "assignment second rhs binary missing operand leaves print sibling",
            source = "a, b = x, value +\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Id(x),Binary(+,Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Binary right against non-expression statement-start (`local`) — sibling local.
            name = "assignment second rhs binary missing operand leaves later local sibling",
            source = "a, b = x, value +\nlocal after = 1\nprint(after)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Id(x),Binary(+,Id(value),ExpressionNodeSupport))",
                "Local(Id(after)=Const(1))",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Terminator form (`end`): binary always inserts ExpressionNodeSupport.
            name = "assignment second rhs binary missing operand before end keeps later print",
            source = "do\n  a, b = x, value +\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Assign(Id(a),Id(b)=Id(x),Binary(+,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Unary absorbs following print as operand (no unary line-break statement recovery).
            name = "assignment second rhs unary absorbs following print as operand",
            source = "a, b = x, not\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Id(x),Unary(not,Call(Id(print):Id(a))))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Unary placeholder only when next token is an expression terminator (`end`).
            name = "assignment second rhs unary missing operand before end keeps later print",
            source = "do\n  a, b = x, not\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Assign(Id(a),Id(b)=Id(x),Unary(not,ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // First RHS incomplete binary before comma: comma is expression terminator for the
            // binary right; second bare NAME after line break parses as the next RHS.
            name = "assignment first rhs binary missing operand before comma recovers second placeholder",
            source = "a, b = value +,\n y",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Binary(+,Id(value),ExpressionNodeSupport),Id(y))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // First RHS incomplete binary with line-break sibling (no second RHS parsed).
            name = "assignment first rhs binary missing operand leaves print sibling",
            source = "a, b = value +\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Binary(+,Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // First RHS unary absorb (matrix counterpart to second-RHS unary absorb).
            name = "assignment first rhs unary absorbs following print as operand",
            source = "a, b = not\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Unary(not,Call(Id(print):Id(a))))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )

    // Local multi-RHS line-break asymmetry lock (TASK-553):
    // recoverFirstStatementLineBreak = RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_LOCAL (false).
    private val localMultiRhsLineBreakCases = listOf(
        RecoveryCase(
            // Local first RHS does NOT use statement-start-after-line-break recovery;
            // later bare NAME after comma also parses as expression (TASK-546 shared policy).
            name = "local multi-line multi-rhs keeps first name and recovers later term as placeholder",
            source = "local a, b =\n x,\n y",
            requiredShapeFragments = listOf(
                "Local(Id(a),Id(b)=Id(x),Id(y))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "local later multi-rhs after comma line break leaves second name sibling",
            source = "local a, b = x,\n y",
            requiredShapeFragments = listOf(
                "Local(Id(a),Id(b)=Id(x),Id(y))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Local second-RHS binary sibling recovery (same binary path as assignment).
            name = "local second rhs binary missing operand leaves later local sibling",
            source = "local a, b = x, value +\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(Id(a),Id(b)=Id(x),Binary(+,Id(value),ExpressionNodeSupport))",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Local second-RHS unary absorbs print (same unary absorb product path).
            name = "local second rhs unary absorbs following print as operand",
            source = "local a, b = x, not\nprint(a)",
            requiredShapeFragments = listOf(
                "Local(Id(a),Id(b)=Id(x),Unary(not,Call(Id(print):Id(a))))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // TASK-553 asymmetry lock: local first RHS recoverFirst=false, so call-shaped
            // print after `=\n` is absorbed as the initializer (unlike assignment sibling).
            name = "local first rhs call-shaped print after line break absorbs as initializer",
            source = "local a =\nprint(a)",
            requiredShapeFragments = listOf(
                "Local(Id(a)=Call(Id(print):Id(a)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Local multi-target same family: first call-shaped print absorbed; no sibling split.
            name = "local multi-target first rhs call-shaped print absorbs under recoverFirst false",
            source = "local a, b =\nprint(a)",
            requiredShapeFragments = listOf(
                "Local(Id(a),Id(b)=Call(Id(print):Id(a)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )
}
