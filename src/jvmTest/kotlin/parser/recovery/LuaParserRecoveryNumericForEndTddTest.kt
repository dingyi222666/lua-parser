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
 * Focused recovery corpus for numeric `for` loops missing `end` (TASK-283).
 *
 * Complements the single "missing for end at eof keeps assignment body" fixture in
 * [LuaParserRecoveryTddTest] with deeper coverage of:
 * - multi-statement / empty / call / local / return loop bodies
 * - optional step expression present or absent
 * - missing `end` at EOF (body statements remain inside the for)
 * - nested numeric for recovery (inner consumes a shared `end` when only one is present)
 * - incomplete body expressions after missing `end`
 * - well-formed numeric for still clean under recovery
 * - strict-parse rejection of incomplete numeric for sources
 *
 * Test-only; no production LuaParser edits unless review re-scopes.
 *
 * Product goldens (aligned with [LuaParser.parseForNumericStatement]):
 * - missing `end` recovers a ForNumeric and emits `<end> expected`;
 * - without `end`, statements after the loop header stay inside the for body
 *   (block stops only at terminators / EOF);
 * - nested for with a single shared `end` lets the inner for consume it; the outer
 *   then recovers with its own missing-end warning.
 */
class LuaParserRecoveryNumericForEndTddTest {

    @Test
    fun recoversMissingEndAtEofAndKeepsLoopBody() {
        assertEquals(6, missingEndBodyCases.size)
        missingEndBodyCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingEndWithOptionalStepAndBoundsForms() {
        assertEquals(5, missingEndStepAndBoundsCases.size)
        missingEndStepAndBoundsCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversNestedNumericForMissingEndForms() {
        assertEquals(5, nestedMissingEndCases.size)
        nestedMissingEndCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteBodiesAfterMissingEnd() {
        assertEquals(4, incompleteBodyAfterMissingEndCases.size)
        incompleteBodyAfterMissingEndCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun exposesTypedForNumericStructureAfterMissingEndRecovery() {
        val chunk = parseRecoveringWithoutThrow(
            "for i = 1, 3, 1 do use(i) print(i)"
        )

        val forStmt = assertIs<ForNumericStatement>(chunk.body.statements.single())
        assertEquals("Id(i)", renderShape(forStmt.variable))
        assertEquals("Const(1)", renderShape(forStmt.start))
        assertEquals("Const(3)", renderShape(forStmt.end))
        assertEquals("Const(1)", renderShape(forStmt.step!!))
        assertEquals(2, forStmt.body.statements.size)
        assertIs<CallStatement>(forStmt.body.statements[0])
        assertIs<CallStatement>(forStmt.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "for i = 1, 2 do total = total + i",
            "for i = 1, 3 do work(i) print(\"after\")",
            "for i = 1, 10, 2 do use(i)",
            "for i = 1, n do local copy = i work(copy)",
            "for i = 1, 3 do for j = 1, 2 do use(i, j) end"
        )

        sources.forEach { source ->
            val first = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            val second = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)

            assertEquals(renderShape(first.chunk), renderShape(second.chunk), "shape deterministic: $source")
            assertEquals(first.warnings, second.warnings, "warnings deterministic: $source")
            assertTrue(
                first.warnings.any { it.contains("<end> expected") },
                "expected <end> recovery diagnostic for: $source; actual=${first.warnings}"
            )
        }
    }

    @Test
    fun strictParseRejectsMissingEndWhileRecoveryDoesNotThrow() {
        val incompleteSources = listOf(
            "for i = 1, 2 do total = total + i",
            "for i = 1, 3 do work(i) print(\"after\")",
            "for i = 1, 10, 2 do use(i)",
            "for i = 1, n do local copy = i work(copy)",
            "for i = 1, 3 do for j = 1, 2 do use(i, j) end",
            "for i = 1, 3 do for j = 1, 2 do use(i, j)"
        )

        incompleteSources.forEach { source ->
            parseRecoveringWithoutThrow(source)

            val first = assertParseFails(LuaVersion.LUA_5_3, source, recovery = false)
            val second = assertParseFails(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(first::class, second::class, "strict failure type for: $source")
            assertEquals(first.message, second.message, "strict failure message for: $source")
        }
    }

    @Test
    fun wellFormedNumericForStillParsesCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "for i = 1, 3 do use(i) end",
            "for i = 1, 10, 2 do consume(i) end\nlocal after = 1",
            "for i = 1, n do local copy = i work(copy) end\nprint(n)",
            "for i = 1, 3 do for j = 1, 2 do use(i, j) end end\nprint(done)",
            "for i = start, finish, step do handle(i) end\nreturn finish"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed numeric for should not emit recovery diagnostics: $source"
            )
            assertTrue(
                renderShape(recovered.chunk).contains("ForNumeric("),
                "well-formed source should produce ForNumeric shape: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict))
        }
    }

    @Test
    fun documentsNumericForMissingEndInventorySizeAndCoverage() {
        assertEquals(
            missingEndBodyCases.size +
                missingEndStepAndBoundsCases.size +
                nestedMissingEndCases.size +
                incompleteBodyAfterMissingEndCases.size,
            allCases().size
        )
        assertEquals(20, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("multi-statement") || it.contains("call body") })
        assertTrue(names.any { it.contains("step") })
        assertTrue(names.any { it.contains("nested") })
        assertTrue(names.any { it.contains("incomplete") })
        assertTrue(allCases().all { it.warningFragments.any { w -> w.contains("<end> expected") } })
    }

    // --- helpers -----------------------------------------------------------------

    private fun assertSupportedRecoveryCase(case: RecoveryCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)
        when (case.strictParseExpectation) {
            StrictParseExpectation.REJECTS -> assertStrictParseProducesDeterministicFailure(case)
            StrictParseExpectation.CURRENTLY_ACCEPTS -> assertStrictParseCurrentlyAccepts(case)
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
        return missingEndBodyCases +
            missingEndStepAndBoundsCases +
            nestedMissingEndCases +
            incompleteBodyAfterMissingEndCases
    }

    private enum class StrictParseExpectation {
        REJECTS,
        CURRENTLY_ACCEPTS,
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

    // Missing end at EOF: partial loop body is retained inside ForNumeric.
    private val missingEndBodyCases = listOf(
        RecoveryCase(
            name = "missing end keeps assignment body",
            source = "for i = 1, 2 do total = total + i",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(2),null:Block[Assign(Id(total)=Binary(+,Id(total),Id(i)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end keeps multi-statement call body",
            source = "for i = 1, 3 do work(i) print(\"after\")",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[CallStmt(Call(Id(work):Id(i)));CallStmt(Call(Id(print):Const(\"after\")))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end keeps local and call body",
            source = "for i = 1, n do local copy = i work(copy)",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Id(n),null:Block[Local(Id(copy)=Id(i));CallStmt(Call(Id(work):Id(copy)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end keeps return body",
            source = "for i = 1, 3 do return i",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[Return(Id(i))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end keeps empty body at eof",
            source = "for i = 1, 3 do",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end keeps break body",
            source = "for i = 1, 10 do break",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(10),null:Block[Break])"
            ),
            warningFragments = listOf("<end> expected")
        )
    )

    // Optional step / identifier bounds still recover missing end with body retained.
    private val missingEndStepAndBoundsCases = listOf(
        RecoveryCase(
            name = "missing end with step keeps call body",
            source = "for i = 1, 10, 2 do use(i)",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(10),Const(2):Block[CallStmt(Call(Id(use):Id(i)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end with identifier bounds keeps multi-statement body",
            source = "for i = start, finish do local v = i handle(v)",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Id(start),Id(finish),null:Block[Local(Id(v)=Id(i));CallStmt(Call(Id(handle):Id(v)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end with identifier step keeps assignment body",
            source = "for i = start, finish, step do total = total + i",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Id(start),Id(finish),Id(step):Block[Assign(Id(total)=Binary(+,Id(total),Id(i)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end with float bounds keeps call body",
            source = "for i = 0.5, 2.5, 0.5 do sample(i)",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(0.5),Const(2.5),Const(0.5):Block[CallStmt(Call(Id(sample):Id(i)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end with negative step keeps call body",
            source = "for i = 3, 1, -1 do countdown(i)",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(3),Const(1),Unary(-,Const(1)):Block[CallStmt(Call(Id(countdown):Id(i)))])"
            ),
            warningFragments = listOf("<end> expected")
        )
    )

    // Nested numeric for missing-end recovery stays bounded.
    private val nestedMissingEndCases = listOf(
        RecoveryCase(
            name = "nested missing outer end keeps closed inner and trailing print in outer body",
            // Inner has end; outer missing end — trailing print is absorbed into outer body
            // (no outer end to close before print).
            source = "for i = 1, 3 do for j = 1, 2 do use(i, j) end print(i)",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[ForNumeric(Id(j)=Const(1),Const(2),null:Block[CallStmt(Call(Id(use):Id(i),Id(j)))]);CallStmt(Call(Id(print):Id(i)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "nested missing outer end keeps local and closed inner body",
            source = "for i = 1, 3 do local a = i for j = 1, 2 do use(a, j) end",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[Local(Id(a)=Id(i));ForNumeric(Id(j)=Const(1),Const(2),null:Block[CallStmt(Call(Id(use):Id(a),Id(j)))])])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "nested both missing end at eof keeps both bodies",
            source = "for i = 1, 3 do for j = 1, 2 do use(i, j) print(done)",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[ForNumeric(Id(j)=Const(1),Const(2),null:Block[CallStmt(Call(Id(use):Id(i),Id(j)));CallStmt(Call(Id(print):Id(done)))])])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "nested missing both ends with only one end: end closes inner first",
            // parseForBody/block: END terminates inner body; recoverToken on inner consumes END;
            // outer then misses end (EOF).
            source = "for i = 1, 3 do for j = 1, 2 do use(i, j) end",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[ForNumeric(Id(j)=Const(1),Const(2),null:Block[CallStmt(Call(Id(use):Id(i),Id(j)))])])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "nested missing end keeps three-level body partial AST",
            source = "for i = 1, 2 do for j = 1, 2 do for k = 1, 2 do tick(i, j, k)",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(2),null:Block[ForNumeric(Id(j)=Const(1),Const(2),null:Block[ForNumeric(Id(k)=Const(1),Const(2),null:Block[CallStmt(Call(Id(tick):Id(i),Id(j),Id(k)))])])])"
            ),
            warningFragments = listOf("<end> expected")
        )
    )

    // Incomplete body content with missing end still recovers partial AST.
    private val incompleteBodyAfterMissingEndCases = listOf(
        RecoveryCase(
            name = "missing end with incomplete assignment body keeps placeholder",
            source = "for i = 1, 3 do total = total +",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))])"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end with incomplete call body keeps partial call",
            source = "for i = 1, 3 do use(i",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:"
            ),
            warningFragments = listOf("<end> expected", "')' expected")
        ),
        RecoveryCase(
            name = "missing end with incomplete local initializer keeps placeholder",
            source = "for i = 1, 3 do local copy =",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[Local(Id(copy)=ExpressionNodeSupport)])"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end with incomplete return body keeps placeholder",
            source = "for i = 1, 3 do return i +",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[Return(Binary(+,Id(i),ExpressionNodeSupport))])"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("<end> expected")
        )
    )
}
