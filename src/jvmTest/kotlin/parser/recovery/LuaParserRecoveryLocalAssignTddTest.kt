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
 * Focused recovery corpus for incomplete `local` declarations and assignments
 * (TASK-320 / TASK-546 / TASK-553).
 *
 * Complements the 7-case local/assignment family in [LuaParserRecoveryTddTest]
 * with deeper coverage of missing names, missing `=`, missing RHS (including
 * multi-target / trailing-comma forms), incomplete indexed/member targets, and
 * later-statement reachability.
 *
 * Test-only; no production LuaParser edits unless review re-scopes.
 *
 * Product goldens (aligned with existing recovery inventory + LuaParser):
 * - missing local names recover as empty bad `Id()` and may mark the Local bad;
 * - missing local/assign RHS inserts bad `ExpressionNodeSupport`;
 * - assignment missing `=` marks the Assign bad and may wrap leftover names as
 *   bad call statements (`a, b\nprint(a)` → Assign(Id(a),ExpressionNodeSupport=)
 *   plus Call(Id(b):));
 * - assignment first RHS uses RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_ASSIGNMENT
 *   (true) so `a =\nprint(a)` keeps print as a sibling CallStmt (strict mode
 *   currently accepts several newline-after-`=` RHS gaps by absorbing the next
 *   expression — CURRENTLY_ACCEPTS_MISSING_RHS inventory stays honest for those);
 * - binary RHS recovery also uses statement-start-after-line-break / expression
 *   terminators, so `a = value +\nprint(a)` and `value + end` insert a bad
 *   ExpressionNodeSupport and keep later statements as siblings (strict mode
 *   currently absorbs a following NAME call as the binary right operand);
 * - assignment/local unary missing operands only insert placeholders when the next
 *   token is an expression terminator (`end` / `)` / `,` / …); a following
 *   NAME/`print(...)` is absorbed as the unary operand (product has no unary
 *   statement-start-after-line-break path — same family as local unary);
 * - local initializer explist uses RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_LOCAL
 *   (false), so a following expression-start statement after `local x =\nprint(...)`
 *   can be absorbed as the initializer; statement-start tokens that are not
 *   expression starts (`local` / `return` / `end` / …) still force a placeholder
 *   and leave the later statement reachable (binary tails still recover via the
 *   binary statement-start path once an expression has begun). Do not flip local
 *   recoverFirst to true (TASK-553 / REVIEW16 local attribute footguns).
 * - later multi-RHS terms after comma share keyword-only recovery (TASK-546) and
 *   are not poisoned by assignment first-RHS recoverFirst=true.
 */
class LuaParserRecoveryLocalAssignTddTest {

    @Test
    fun recoversLocalDeclarationsMissingNamesEqualsOrRhs() {
        assertEquals(8, localMissingPiecesCases.size)
        localMissingPiecesCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversAssignmentsMissingNamesEqualsOrRhs() {
        assertEquals(9, assignmentMissingPiecesCases.size)
        assignmentMissingPiecesCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIndexedMemberAndMultiTargetAssignForms() {
        assertEquals(7, indexedMemberAndMultiTargetCases.size)
        indexedMemberAndMultiTargetCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteRhsExpressionsAndKeepsLaterStatements() {
        assertEquals(9, incompleteRhsExpressionCases.size)
        incompleteRhsExpressionCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversNestedLocalAndAssignInsideBlocks() {
        assertEquals(4, nestedBlockCases.size)
        nestedBlockCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun exposesTypedLocalAndAssignStructureAfterRecovery() {
        val localChunk = parseRecoveringWithoutThrow(
            "local =\nlocal after = 1\nreturn after"
        )
        val badLocal = assertIs<LocalStatement>(localChunk.body.statements[0])
        assertTrue(badLocal.bad)
        assertEquals(1, badLocal.init.size)
        assertEquals("Id()", renderShape(badLocal.init.single()))
        assertTrue(badLocal.init.single().bad)
        assertEquals(1, badLocal.variables.size)
        assertEquals("ExpressionNodeSupport", renderShape(badLocal.variables.single()))
        assertIs<LocalStatement>(localChunk.body.statements[1])
        assertIs<ReturnStatement>(localChunk.body.returnStatement)

        val assignChunk = parseRecoveringWithoutThrow("a, = 1\nprint(a)")
        val assign = assertIs<AssignmentStatement>(assignChunk.body.statements[0])
        assertEquals(2, assign.init.size)
        assertEquals("Id(a)", renderShape(assign.init[0]))
        assertEquals("ExpressionNodeSupport", renderShape(assign.init[1]))
        assertTrue(assign.init[1].bad)
        assertEquals(1, assign.variables.size)
        assertEquals("Const(1)", renderShape(assign.variables.single()))
        assertIs<CallStatement>(assignChunk.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "local =\nlocal after = 1",
            "a, b\nprint(a)",
            "a =\nprint(a)",
            "a, b = 1,\nprint(a)",
            "items[] = value\nprint(value)",
            "local a, =\nlocal after = 1"
        )

        sources.forEach { source ->
            val first = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            val second = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)

            assertEquals(renderShape(first.chunk), renderShape(second.chunk), "shape deterministic: $source")
            assertEquals(first.warnings, second.warnings, "warnings deterministic: $source")
        }
    }

    @Test
    fun strictParseRejectsMostIncompleteLocalsAndAssignsWhileRecordingRhsGaps() {
        val rejects = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty())
        rejects.forEach { case ->
            parseRecoveringWithoutThrow(case, attempt = "strict-reject-recovery-guard")
            assertStrictParseProducesDeterministicFailure(case)
        }

        // TASK-553: CURRENTLY_ACCEPTS_MISSING_RHS inventory remains honest for true
        // missing-RHS gaps that strict mode currently absorbs (assignment first RHS
        // after `=\n`). Do not reclassify well-formed multi-line multi-RHS as missing.
        val acceptsMissingRhs = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
        }
        assertTrue(
            acceptsMissingRhs.map { it.name }.containsAll(
                listOf(
                    "assignment missing rhs expression should keep later print",
                    "assignment multi-target missing first rhs keeps later print",
                    "member assignment missing rhs keeps later print",
                    "index assignment missing rhs keeps later print"
                )
            )
        )
        // No local cases in the missing-RHS strict-accept inventory: local recoverFirst
        // is false, so incomplete local RHS after keyword starts still REJECT under strict
        // when the next token is not an expression (local/return).
        assertTrue(acceptsMissingRhs.none { it.name.startsWith("local ") })
        acceptsMissingRhs.forEach(::assertStrictParseCurrentlyAccepts)

        val acceptsValid = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
        }
        assertTrue(acceptsValid.map { it.name }.contains("local bare namelist without equals keeps following print"))
        acceptsValid.forEach(::assertStrictParseCurrentlyAccepts)
    }

    @Test
    fun wellFormedLocalsAndAssignsStillParseCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "local value = 1",
            "local a, b = 1, 2",
            "a = 1",
            "a, b = 1, 2",
            "items[key] = value",
            "obj.field = value",
            "local after = 1\nprint(after)",
            "a = b + c\nprint(a)"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed local/assign should not emit recovery diagnostics: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict))
        }
    }

    @Test
    fun documentsLocalAssignInventorySizeAndCoverage() {
        assertEquals(
            localMissingPiecesCases.size +
                assignmentMissingPiecesCases.size +
                indexedMemberAndMultiTargetCases.size +
                incompleteRhsExpressionCases.size +
                nestedBlockCases.size,
            allCases().size
        )
        assertEquals(37, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("local") && it.contains("missing name") })
        assertTrue(names.any { it.contains("assignment") && it.contains("missing equals") })
        assertTrue(names.any { it.contains("missing rhs") || it.contains("trailing rhs") })
        assertTrue(names.any { it.contains("index") || it.contains("member") })
        assertTrue(
            allCases().any {
                it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
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
        return localMissingPiecesCases +
            assignmentMissingPiecesCases +
            indexedMemberAndMultiTargetCases +
            incompleteRhsExpressionCases +
            nestedBlockCases
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

    // Local namelist / optional `=` explist recovery.
    private val localMissingPiecesCases = listOf(
        RecoveryCase(
            name = "local declaration missing name should keep later local",
            source = "local =\nlocal after = 1\nreturn after",
            requiredShapeFragments = listOf(
                "Local(Id()=ExpressionNodeSupport)",
                "Local(Id(after)=Const(1))",
                "Return(Id(after))"
            ),
            badShapeFragments = listOf("Local(Id()=ExpressionNodeSupport)"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            // Following `print` is an expression-start NAME; local RHS recovery does not
            // special-case first-statement line breaks, so a following `local`/`return`
            // is the reliable sibling form. Use return here to keep later statements
            // reachable without absorption into the broken local's initializer.
            name = "local declaration missing name keeps following return",
            source = "local =\nreturn after",
            requiredShapeFragments = listOf(
                "Local(Id()=ExpressionNodeSupport)",
                "Return(Id(after))"
            ),
            badShapeFragments = listOf("Local(Id()=ExpressionNodeSupport)"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            name = "local multi-name missing second name keeps later local",
            source = "local a, =\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(Id(a),Id()=ExpressionNodeSupport)",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("Local(Id(a),Id()=ExpressionNodeSupport)"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            name = "local declaration missing initializer expression should keep later local",
            source = "local value =\nlocal after = 1\nreturn after",
            requiredShapeFragments = listOf(
                "Local(Id(value)=ExpressionNodeSupport)",
                "Local(Id(after)=Const(1))",
                "Return(Id(after))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "local declaration missing initializer keeps following return",
            source = "local value =\nreturn value",
            requiredShapeFragments = listOf(
                "Local(Id(value)=ExpressionNodeSupport)",
                "Return(Id(value))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "local multi-name missing trailing initializer keeps later local",
            source = "local a, b = 1,\nlocal after = 2",
            requiredShapeFragments = listOf(
                "Local(Id(a),Id(b)=Const(1),ExpressionNodeSupport)",
                "Local(Id(after)=Const(2))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Valid Lua omits `=` when there is no initializer; recovery must still
            // leave later statements reachable and emit no missing-`=` warning.
            name = "local bare namelist without equals keeps following print",
            source = "local value\nprint(value)",
            requiredShapeFragments = listOf(
                "Local(Id(value)=)",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "local missing name at eof still recovers empty binding",
            source = "local",
            requiredShapeFragments = listOf("Local(Id()=)"),
            badShapeFragments = listOf("Local(Id()=)"),
            warningFragments = listOf("<name> expected")
        )
    )

    // Assignment varlist `=` explist recovery, including documented strict RHS gaps.
    private val assignmentMissingPiecesCases = listOf(
        RecoveryCase(
            name = "assignment missing name after comma should keep print",
            source = "a, = 1\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),ExpressionNodeSupport=Const(1))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Missing `=` recovery marks the Assign bad. Depending on resync, the second
            // name may remain inside the Assign or become a following bad call; either way
            // later print/local must stay reachable (see docs shape notes + core inventory).
            name = "assignment missing equals after varlist should keep print",
            source = "a, b\nprint(a)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("Assign("),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            // Single identifier without `=` / `,` is recovered as a bad compact call
            // statement (not an Assign), then later print remains a sibling.
            name = "assignment missing equals single target keeps following print",
            source = "a\nprint(a)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(a):))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("Call(Id(a):)")
        ),
        RecoveryCase(
            name = "assignment missing rhs expression should keep later print",
            source = "a =\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
        ),
        RecoveryCase(
            // After comma, call-shaped print is a valid expression start and becomes the
            // second RHS under recovery (matches strict; TASK-546 later-term policy).
            name = "assignment missing trailing rhs after comma should keep later print",
            source = "a, b = 1,\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Const(1),Call(Id(print):Id(a)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "assignment missing rhs at eof still recovers placeholder",
            source = "a =",
            requiredShapeFragments = listOf("Assign(Id(a)=ExpressionNodeSupport)"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Strict mode currently absorbs next-line print as the assignment RHS
            // (same family as the documented single-target missing-RHS gap).
            name = "assignment multi-target missing first rhs keeps later print",
            source = "a, b =\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
        ),
        RecoveryCase(
            name = "assignment missing name after comma before equals keeps later local",
            source = "a, = 1\nlocal after = 2",
            requiredShapeFragments = listOf(
                "Assign(Id(a),ExpressionNodeSupport=Const(1))",
                "Local(Id(after)=Const(2))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "assignment missing equals keeps following local",
            source = "a, b\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("Assign("),
            warningFragments = listOf("'=' expected")
        )
    )

    // Indexed / member targets and multi-target assign residuals.
    private val indexedMemberAndMultiTargetCases = listOf(
        RecoveryCase(
            name = "assignment with empty index should keep later statement",
            source = "items[] = value\nprint(value)",
            requiredShapeFragments = listOf(
                "Assign(Index(Id(items)[ExpressionNodeSupport])=Id(value))",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("Index(Id(items)[ExpressionNodeSupport])")
        ),
        RecoveryCase(
            name = "assignment with empty index missing rhs keeps later print",
            source = "items[] =\nprint(items)",
            requiredShapeFragments = listOf(
                "Assign(Index(Id(items)[ExpressionNodeSupport])=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(items)))"
            ),
            badShapeFragments = listOf(
                "Index(Id(items)[ExpressionNodeSupport])",
                "ExpressionNodeSupport"
            )
        ),
        RecoveryCase(
            name = "member assignment missing rhs keeps later print",
            source = "obj.field =\nprint(obj)",
            requiredShapeFragments = listOf(
                "Assign(Member(Id(obj).field)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(obj)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
        ),
        RecoveryCase(
            name = "index assignment missing rhs keeps later print",
            source = "items[key] =\nprint(items)",
            requiredShapeFragments = listOf(
                "Assign(Index(Id(items)[Id(key)])=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(items)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
        ),
        RecoveryCase(
            name = "mixed multi-target assign missing trailing rhs keeps later print",
            source = "a, items[key] = 1,\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Index(Id(items)[Id(key)])=Const(1),Call(Id(print):Id(a)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "multi-target assign missing middle name keeps later print",
            source = "a, , c = 1, 2, 3\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),ExpressionNodeSupport,Id(c)=Const(1),Const(2),Const(3))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "member assign missing equals keeps following print",
            source = "obj.field\nprint(obj)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Member(Id(obj).field):))",
                "CallStmt(Call(Id(print):Id(obj)))"
            ),
            badShapeFragments = listOf("Call(Member(Id(obj).field):)")
        )
    )

    // Incomplete RHS expressions still leave later statements reachable when possible.
    // Goldens track product recovery in LuaParser:
    // - binary right: expression terminator OR (line-break + statement-start) → ExpressionNodeSupport
    // - unary arg: expression terminator only → ExpressionNodeSupport; NAME/call is absorbed
    // - parenthesized missing `)`: recoverToken emits `')' expected`; primary recovers as Id(value)
    // - unclosed call `factory(seed\nprint(a)`: product inserts ExpressionNodeSupport for the
    //   incomplete call arg list (does not keep `seed` as Call arg), leaves `seed` as a sibling
    //   bad CallStmt(Call(Id(seed):)), then keeps later print as another sibling
    private val incompleteRhsExpressionCases = listOf(
        RecoveryCase(
            // Binary right-hand recovery uses statement-start-after-line-break, so
            // next-line print stays a sibling CallStmt under recovery. Strict mode
            // currently absorbs the next-line call as the binary right operand.
            name = "assignment binary rhs missing operand keeps later print",
            source = "a = value +\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(+,Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Same binary recovery path against a non-expression statement-start (`local`),
            // which is not absorbable as a subexpression even without line-break recovery.
            // Mirrors the while/repeat inventory forms (`value + end` / `value + until`).
            name = "assignment binary rhs missing operand keeps later local",
            source = "a = value +\nlocal after = 1\nprint(after)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Binary(+,Id(value),ExpressionNodeSupport))",
                "Local(Id(after)=Const(1))",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Terminator form (end): product always inserts ExpressionNodeSupport for
            // binary right when the next token is an expression terminator — same shape
            // family as LuaParserRecoveryTddTest loop-body incomplete assignment.
            name = "assignment binary rhs missing operand before end keeps later print",
            source = "do\n  a = value +\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Assign(Id(a)=Binary(+,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Unary recovery only inserts a placeholder for expression terminators.
            // NAME after `not` is an expression start, so product absorbs
            // `print(a)` as the unary operand (same family as local unary).
            // Strict mode also accepts by absorbing print as the operand.
            name = "assignment unary rhs absorbs following print as operand",
            source = "a = not\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Unary(not,Call(Id(print):Id(a))))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "local binary initializer missing operand keeps later local",
            source = "local total = value +\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(Id(total)=Binary(+,Id(value),ExpressionNodeSupport))",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Local RHS does not special-case first-statement line breaks; NAME after
            // `not` is an expression start, so the current product absorbs
            // `print(flag)` as the unary operand rather than a sibling CallStmt.
            name = "local unary initializer absorbs following print as operand",
            source = "local flag = not\nprint(flag)",
            requiredShapeFragments = listOf(
                "Local(Id(flag)=Unary(not,Call(Id(print):Id(flag))))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Reliable unary-placeholder form: next token is an expression terminator
            // (`end`), so recovery inserts ExpressionNodeSupport and keeps the
            // surrounding block + following print reachable.
            name = "assignment unary rhs missing operand before end keeps later print",
            source = "do\n  a = not\nend\nprint(a)",
            requiredShapeFragments = listOf(
                "Do(Block[Assign(Id(a)=Unary(not,ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Unclosed paren: recoverToken emits `')' expected`; the parenthesized
            // primary recovers as Id(value) and later print stays a sibling CallStmt.
            // (Strict mode rejects for the missing `)`.)
            name = "assignment parenthesized rhs missing close keeps later print",
            source = "a = (value\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Id(value))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            // Product recovery for unclosed call arg list on
            // `a = factory(seed\nprint(a)` (REVIEW36B actual shape):
            // Chunk(Block[
            //   Assign(Id(a)=Call(Id(factory):ExpressionNodeSupport));
            //   CallStmt(Call(Id(seed):));
            //   CallStmt(Call(Id(print):Id(a)))
            // ])
            // i.e. incomplete call arg becomes ExpressionNodeSupport, leftover `seed`
            // becomes a sibling bad CallStmt, later print remains reachable.
            name = "assignment call rhs missing close keeps later print",
            source = "a = factory(seed\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a)=Call(Id(factory):ExpressionNodeSupport))",
                "CallStmt(Call(Id(seed):))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf(
                "ExpressionNodeSupport",
                "Call(Id(seed):)"
            ),
            warningFragments = listOf("')' expected")
        )
    )

    // Nested / blocked contexts still keep later statements reachable.
    private val nestedBlockCases = listOf(
        RecoveryCase(
            name = "missing local initializer inside do keeps following print after end",
            source = """
                do
                  local value =
                end
                print(after)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Do(Block[Local(Id(value)=ExpressionNodeSupport)])",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "missing assign rhs inside if then keeps following print after end",
            source = """
                if ready then
                  value =
                end
                print(value)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Assign(Id(value)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "missing local name inside while keeps following print after end",
            source = """
                while ready do
                  local =
                end
                print(ready)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Local(Id()=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(ready)))"
            ),
            badShapeFragments = listOf("Local(Id()=ExpressionNodeSupport)"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            name = "missing assign equals inside function keeps following print after end",
            source = """
                function run()
                  a, b
                end
                print(done)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Function(Id(run),Block[Assign(",
                "CallStmt(Call(Id(print):Id(done)))"
            ),
            badShapeFragments = listOf("Assign("),
            warningFragments = listOf("'=' expected")
        )
    )
}
