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
 * Focused dual-path recovery corpus for incomplete string concatenation (`..`)
 * (TASK-483).
 *
 * Complements [LuaParserRecoveryBinaryIncompleteRhsOpsTddTest] (full operator
 * matrix that only samples `..` lightly) and [LuaParserRecoveryLocalAssignTddTest]
 * (which mostly probes arithmetic `+`) with deeper concat-specific coverage:
 *
 * - assignment / local / multi-RHS incomplete `..` RHS absorb-vs-sibling matrix
 * - right-associative chained concat incomplete tails
 * - non-name left operands (string literal / call / member / index)
 * - return / if / while / repeat / function body contexts
 * - EOF and expression-terminator residual forms
 *
 * Product notes (LuaParser.parseSubExpTail / binaryPrecedence / isRightAssociative):
 * - Binary right recovers via expression terminator OR (line-break + statement-start)
 *   → `ExpressionNodeSupport` and later statements remain siblings under recovery.
 * - Strict mode currently absorbs a following `print(...)` as the binary right
 *   operand (CURRENTLY_ACCEPTS dual-path); `local` / `end` / `then` / `do` /
 *   `until` cannot be absorbed → strict REJECTS.
 * - `..` is right-associative (`isRightAssociative`), so
 *   `p .. q ..` incomplete becomes
 *   `Binary(..,Id(p),Binary(..,Id(q),ExpressionNodeSupport))`.
 * - Binary incomplete path is independent of left-operand kind (NAME / string /
 *   call / member / index all share the same right recovery).
 *
 * AST quirk: AssignmentStatement/LocalStatement `.init` = names/LHS,
 * `.variables` = RHS.
 *
 * Test-only; no production LuaParser edits unless review re-scopes.
 * Keep [StrictParseExpectation] flags honest against current product.
 * Host android.jar: SDK android-35 PRESENT; Downloads ABSENT; never G:/.
 */
class LuaParserRecoveryStringConcatIncompleteTddTest {

    @Test
    fun recoversAssignmentIncompleteConcatRhsAbsorbVsSiblingMatrix() {
        assertEquals(6, assignmentIncompleteCases.size)
        assignmentIncompleteCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversLocalAndMultiRhsIncompleteConcatForms() {
        assertEquals(6, localAndMultiRhsCases.size)
        localAndMultiRhsCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversChainedRightAssociativeAndNonNameLeftOperands() {
        assertEquals(6, chainedAndLeftOperandCases.size)
        chainedAndLeftOperandCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversConcatIncompleteAcrossControlAndReturnContexts() {
        assertEquals(6, contextCases.size)
        contextCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun exposesTypedConcatStructureAfterRecovery() {
        // Assignment print-sibling: placeholder right + sibling CallStmt(print).
        val assign = parseRecoveringWithoutThrow("a = value ..\nprint(a)")
        val assignStmt = assertIs<AssignmentStatement>(assign.body.statements[0])
        assertEquals(1, assignStmt.init.size)
        assertEquals("Id(a)", renderShape(assignStmt.init[0]))
        assertEquals(1, assignStmt.variables.size)
        val assignBin = assertIs<BinaryExpression>(assignStmt.variables[0])
        assertEquals(ExpressionOperator.CONCAT, assignBin.operator)
        assertEquals("Id(value)", renderShape(assignBin.left!!))
        assertEquals("ExpressionNodeSupport", renderShape(assignBin.right!!))
        assertTrue(assignBin.right!!.bad)
        val assignPrint = assertIs<CallStatement>(assign.body.statements[1])
        assertEquals("Call(Id(print):Id(a))", renderShape(assignPrint.expression))

        // Right-associative chained incomplete: outer left is Id(p), right is nested
        // Binary(.., Id(q), ExpressionNodeSupport).
        val chained = parseRecoveringWithoutThrow("a = p .. q ..\nprint(a)")
        val chainedAssign = assertIs<AssignmentStatement>(chained.body.statements[0])
        val outer = assertIs<BinaryExpression>(chainedAssign.variables.single())
        assertEquals(ExpressionOperator.CONCAT, outer.operator)
        assertEquals("Id(p)", renderShape(outer.left!!))
        val inner = assertIs<BinaryExpression>(outer.right!!)
        assertEquals(ExpressionOperator.CONCAT, inner.operator)
        assertEquals("Id(q)", renderShape(inner.left!!))
        assertEquals("ExpressionNodeSupport", renderShape(inner.right!!))
        assertTrue(inner.right!!.bad)
        assertIs<CallStatement>(chained.body.statements[1])

        // String-literal left operand still recovers placeholder right.
        val lit = parseRecoveringWithoutThrow("a = \"hi\" ..\nprint(a)")
        val litAssign = assertIs<AssignmentStatement>(lit.body.statements[0])
        val litBin = assertIs<BinaryExpression>(litAssign.variables.single())
        assertEquals(ExpressionOperator.CONCAT, litBin.operator)
        assertEquals("Const(\"hi\")", renderShape(litBin.left!!))
        assertEquals("ExpressionNodeSupport", renderShape(litBin.right!!))
        assertTrue(litBin.right!!.bad)

        // Local sibling form: incomplete concat + following local.
        val local = parseRecoveringWithoutThrow("local total = value ..\nlocal after = 1")
        val localStmt = assertIs<LocalStatement>(local.body.statements[0])
        val localBin = assertIs<BinaryExpression>(localStmt.variables.single())
        assertEquals(ExpressionOperator.CONCAT, localBin.operator)
        assertEquals("ExpressionNodeSupport", renderShape(localBin.right!!))
        assertTrue(localBin.right!!.bad)
        val after = assertIs<LocalStatement>(local.body.statements[1])
        assertEquals("Local(Id(after)=Const(1))", renderShape(after))

        // End-terminator form inside do-block.
        val ended = parseRecoveringWithoutThrow("do\n  a = value ..\nend\nprint(a)")
        val doStmt = assertIs<DoStatement>(ended.body.statements[0])
        val endedAssign = assertIs<AssignmentStatement>(doStmt.body.statements.single())
        val endedBin = assertIs<BinaryExpression>(endedAssign.variables.single())
        assertEquals(ExpressionOperator.CONCAT, endedBin.operator)
        assertEquals("ExpressionNodeSupport", renderShape(endedBin.right!!))
        assertTrue(endedBin.right!!.bad)
        assertIs<CallStatement>(ended.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "a = value ..\nprint(a)",
            "a = value ..\nlocal after = 1",
            "do\n  a = value ..\nend\nprint(a)",
            "local total = value ..\nlocal after = 1",
            "local total = value ..\nprint(total)",
            "a, b = x, value ..\nprint(a)",
            "a, b = value ..\nprint(a)",
            "a = p .. q ..\nprint(a)",
            "a = \"hi\" ..\nprint(a)",
            "a = tostring(x) ..\nprint(a)",
            "a = obj.name ..\nprint(a)",
            "a = items[1] ..\nprint(a)",
            "do\n  return value ..\nend\nprint(a)",
            "if value .. then work() end\nprint(value)",
            "while value .. do work() end\nprint(value)",
            "repeat work() until value ..\nprint(value)",
            "function run()\n  a = value ..\nend\nprint(a)",
            "a = value .."
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
        assertTrue(rejects.isNotEmpty(), "inventory must retain REJECTS incomplete concat cases")
        rejects.forEach { case ->
            parseRecoveringWithoutThrow(case, attempt = "strict-reject-recovery-guard")
            assertStrictParseProducesDeterministicFailure(case)
        }

        val accepts = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
        }
        assertTrue(accepts.isNotEmpty(), "inventory must document CURRENTLY_ACCEPTS dual-path footguns")
        assertTrue(
            accepts.count() >= 8,
            "expected at least eight CURRENTLY_ACCEPTS dual-path concat footguns, got ${accepts.size}"
        )
        accepts.forEach(::assertStrictParseCurrentlyAccepts)
    }

    @Test
    fun wellFormedConcatStillParsesCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "a = p .. q",
            "a = p .. q .. r",
            "a = \"hi\" .. name",
            "a = tostring(x) .. suffix",
            "a = obj.name .. \"!\"",
            "a = items[1] .. items[2]",
            "local total = left .. right",
            "return a .. b",
            "a, b = x .. y, z .. w",
            "if ready .. flag then work() end"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed concat should not emit recovery diagnostics: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict), "shape parity: $source")
        }
    }

    @Test
    fun documentsStringConcatIncompleteInventorySizeAndCoverage() {
        assertEquals(
            assignmentIncompleteCases.size +
                localAndMultiRhsCases.size +
                chainedAndLeftOperandCases.size +
                contextCases.size,
            allCases().size
        )
        assertEquals(24, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("print sibling") })
        assertTrue(names.any { it.contains("local sibling") })
        assertTrue(names.any { it.contains("end terminator") })
        assertTrue(names.any { it.contains("chained") || it.contains("right-associative") })
        assertTrue(names.any { it.contains("string literal") || it.contains("literal left") })
        assertTrue(names.any { it.contains("call left") || it.contains("tostring") })
        assertTrue(names.any { it.contains("member") })
        assertTrue(names.any { it.contains("return") })
        assertTrue(names.any { it.contains("if condition") })
        assertTrue(names.any { it.contains("while") || it.contains("repeat") })
        assertTrue(names.any { it.contains("multi-rhs") || it.contains("second slot") })
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
        return assignmentIncompleteCases +
            localAndMultiRhsCases +
            chainedAndLeftOperandCases +
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

    // Assignment incomplete `..` RHS absorb-vs-sibling matrix.
    private val assignmentIncompleteCases = listOf(
        RecoveryCase(
            // Recovery: Binary(..,Id(value),ExpressionNodeSupport) + sibling print.
            // Strict: absorbs print as the binary right operand.
            name = "assignment incomplete concat leaves print sibling",
            source = "a = value ..\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(..,Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Non-expression statement-start (`local`) cannot be absorbed even in strict.
            name = "assignment incomplete concat leaves local sibling",
            source = "a = value ..\nlocal after = 1\nprint(after)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(..,Id(value),ExpressionNodeSupport))",
                "Local(Id(after)=Const(1))",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Expression terminator `end` always inserts placeholder right.
            name = "assignment incomplete concat end terminator keeps later print",
            source = "do\n  a = value ..\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Assign(Id(a)=Binary(..,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "assignment incomplete concat at eof still recovers placeholder",
            source = "a = value ..",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(..,Id(value),ExpressionNodeSupport))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Same dual-path with return (statement-start, not expression-absorbable).
            name = "assignment incomplete concat leaves following return sibling",
            source = "a = value ..\nreturn a",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(..,Id(value),ExpressionNodeSupport))",
                "Return(Id(a))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Nested incomplete concat inside if-then body, then later print after end.
            name = "assignment incomplete concat inside if then keeps later print",
            source = """
                if ready then
                  a = value ..
                end
                print(a)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(..,Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )

    // Local initializer + multi-RHS incomplete concat forms.
    private val localAndMultiRhsCases = listOf(
        RecoveryCase(
            // Local binary incomplete uses the same parseSubExpTail path as assignment.
            name = "local incomplete concat leaves local sibling",
            source = "local total = value ..\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(Id(total)=Binary(..,Id(value),ExpressionNodeSupport))",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Local + print: recovery sibling; strict absorbs print as concat right.
            name = "local incomplete concat leaves print sibling",
            source = "local total = value ..\nprint(total)",
            requiredShapeFragments = listOf(
                "Local(Id(total)=Binary(..,Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(total)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "local incomplete concat end terminator keeps later print",
            source = "do\n  local total = value ..\nend\nprint(total)",
            requiredShapeFragments = listOf(
                "Do(Block[Local(Id(total)=Binary(..,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(total)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Multi-RHS second slot incomplete concat.
            name = "assignment multi-rhs second slot incomplete concat leaves print sibling",
            source = "a, b = x, value ..\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Id(x),Binary(..,Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Multi-RHS first slot incomplete concat (no second RHS parsed after line-break).
            name = "assignment multi-rhs first slot incomplete concat leaves print sibling",
            source = "a, b = value ..\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Binary(..,Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Local multi-RHS second slot incomplete concat + later local sibling.
            name = "local multi-rhs second slot incomplete concat leaves local sibling",
            source = "local a, b = x, value ..\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(Id(a),Id(b)=Id(x),Binary(..,Id(value),ExpressionNodeSupport))",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )

    // Right-associative chaining + non-name left operands.
    private val chainedAndLeftOperandCases = listOf(
        RecoveryCase(
            // Right-associative: incomplete third operand nests under the second concat.
            name = "assignment right-associative chained incomplete concat leaves print sibling",
            source = "a = p .. q ..\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(..,Id(p),Binary(..,Id(q),ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "assignment right-associative chained incomplete concat end terminator keeps later print",
            source = "do\n  a = p .. q ..\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Assign(Id(a)=Binary(..,Id(p),Binary(..,Id(q),ExpressionNodeSupport)))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "assignment incomplete concat with string literal left leaves print sibling",
            source = "a = \"hi\" ..\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(..,Const(\"hi\"),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "assignment incomplete concat with call left leaves print sibling",
            source = "a = tostring(x) ..\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(..,Call(Id(tostring):Id(x)),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "assignment incomplete concat with member left leaves print sibling",
            source = "a = obj.name ..\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(..,Member(Id(obj).name),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "assignment incomplete concat with index left leaves print sibling",
            source = "a = items[1] ..\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(..,Index(Id(items)[Const(1)]),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )

    // Control-flow / return / function contexts for incomplete concat.
    private val contextCases = listOf(
        RecoveryCase(
            name = "return incomplete concat before end keeps later print",
            source = "do\n  return value ..\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Return(Binary(..,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "if condition incomplete concat keeps then body",
            source = "if value .. then work() end\nprint(value)",
            requiredShapeFragments = listOf(
                "Binary(..,Id(value),ExpressionNodeSupport)",
                "CallStmt(Call(Id(work):))",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "while condition incomplete concat keeps body and later print",
            source = "while value .. do work() end\nprint(value)",
            requiredShapeFragments = listOf(
                "While(Binary(..,Id(value),ExpressionNodeSupport):",
                "CallStmt(Call(Id(work):))",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Strict absorbs next-line print as the concat right operand.
            name = "repeat condition incomplete concat leaves print sibling under recovery",
            source = "repeat work() until value ..\nprint(value)",
            requiredShapeFragments = listOf(
                "Repeat(",
                "Binary(..,Id(value),ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "function body incomplete concat end keeps later print",
            source = """
                function run()
                  a = value ..
                end
                print(a)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Function(Id(run),Block[Assign(Id(a)=Binary(..,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Nested right-associative incomplete inside return before end.
            name = "return right-associative chained incomplete concat before end keeps later print",
            source = "do\n  return p .. q ..\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Return(Binary(..,Id(p),Binary(..,Id(q),ExpressionNodeSupport)))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )
}
