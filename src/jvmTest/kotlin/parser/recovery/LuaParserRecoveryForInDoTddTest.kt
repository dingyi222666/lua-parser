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
 * Focused recovery corpus for generic for-in loops missing `do` (TASK-282).
 *
 * Complements the single "missing generic for do" fixture in
 * [LuaParserRecoveryTddTest] with deeper coverage of:
 * - multi-name / single-name namelists
 * - iterator call forms (`pairs` / `ipairs` / bare iterators)
 * - missing `do` with present `end` (later statements remain siblings)
 * - missing `do` and missing `end` (body statements remain reachable)
 * - nested for-in recovery
 * - incomplete loop bodies after missing `do`
 * - well-formed for-in still clean under recovery
 * - strict-parse rejection of incomplete for-in sources
 *
 * Test-only; no production LuaParser edits unless review re-scopes.
 *
 * Product goldens (aligned with existing recovery inventory):
 * - missing `do` after `in explist` recovers a ForGeneric and emits
 *   `The <do> expected`;
 * - with a matching `end`, following statements remain top-level siblings;
 * - without `end`, statements after the loop header stay inside the for body
 *   (same as while/do missing-end recovery).
 */
class LuaParserRecoveryForInDoTddTest {

    @Test
    fun recoversMissingDoOnGenericForAndKeepsLoopBody() {
        assertEquals(6, missingDoBodyCases.size)
        missingDoBodyCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingDoAndKeepsLaterSiblingStatements() {
        assertEquals(6, missingDoLaterStatementCases.size)
        missingDoLaterStatementCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingDoAndEndEofAndNestedForIn() {
        assertEquals(5, missingDoEndAndNestedCases.size)
        missingDoEndAndNestedCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteBodiesAfterMissingDo() {
        assertEquals(4, incompleteBodyAfterMissingDoCases.size)
        incompleteBodyAfterMissingDoCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun exposesTypedForGenericStructureAfterMissingDoRecovery() {
        val chunk = parseRecoveringWithoutThrow(
            "for key, value in pairs(items) use(key, value) end\nprint(items)"
        )

        val forStmt = assertIs<ForGenericStatement>(chunk.body.statements[0])
        assertEquals(2, forStmt.variables.size)
        assertEquals("Id(key)", renderShape(forStmt.variables[0]))
        assertEquals("Id(value)", renderShape(forStmt.variables[1]))
        assertEquals(1, forStmt.iterators.size)
        assertEquals("Call(Id(pairs):Id(items))", renderShape(forStmt.iterators.single()))
        assertIs<CallStatement>(forStmt.body.statements.single())
        assertIs<CallStatement>(chunk.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "for key, value in pairs(items) use(key, value) end",
            "for item in items work(item) end\nprint(item)",
            "for k, v in pairs(t) use(k, v)",
            "for i, v in ipairs(list) consume(v) end\nlocal after = 1",
            "for k, v in pairs(outer) for i, x in ipairs(v) use(x) end end\nprint(outer)"
        )

        sources.forEach { source ->
            val first = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            val second = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)

            assertEquals(renderShape(first.chunk), renderShape(second.chunk), "shape deterministic: $source")
            assertEquals(first.warnings, second.warnings, "warnings deterministic: $source")
            assertTrue(
                first.warnings.any { it.contains("The <do> expected") },
                "expected <do> recovery diagnostic for: $source; actual=${first.warnings}"
            )
        }
    }

    @Test
    fun strictParseRejectsMissingDoWhileRecoveryDoesNotThrow() {
        val incompleteSources = listOf(
            "for key, value in pairs(items) use(key, value) end",
            "for item in items work(item) end\nprint(item)",
            "for k, v in pairs(t) use(k, v)",
            "for i, v in ipairs(list) consume(v) end\nlocal after = 1",
            "for k, v in pairs(t) use(k, v) end\nprint(t)",
            "for a, b in next, t, nil handle(a, b) end\nprint(t)"
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
    fun wellFormedForInStillParsesCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "for key, value in pairs(items) do use(key, value) end",
            "for item in items do work(item) end\nprint(item)",
            "for i, v in ipairs(list) do consume(v) end\nlocal after = 1",
            "for k, v in pairs(outer) do for i, x in ipairs(v) do use(x) end end\nprint(outer)",
            "for a, b in next, t, nil do handle(a, b) end\nprint(t)"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed for-in should not emit recovery diagnostics: $source"
            )
            assertTrue(
                renderShape(recovered.chunk).contains("ForGeneric("),
                "well-formed source should produce ForGeneric shape: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict))
        }
    }

    @Test
    fun documentsForInMissingDoInventorySizeAndCoverage() {
        assertEquals(
            missingDoBodyCases.size +
                missingDoLaterStatementCases.size +
                missingDoEndAndNestedCases.size +
                incompleteBodyAfterMissingDoCases.size,
            allCases().size
        )
        assertEquals(21, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("pairs") })
        assertTrue(names.any { it.contains("ipairs") || it.contains("single name") })
        assertTrue(names.any { it.contains("later") || it.contains("following") })
        assertTrue(names.any { it.contains("missing end") || it.contains("eof") })
        assertTrue(names.any { it.contains("nested") })
        assertTrue(allCases().all { it.warningFragments.any { w -> w.contains("The <do> expected") } })
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
        return missingDoBodyCases +
            missingDoLaterStatementCases +
            missingDoEndAndNestedCases +
            incompleteBodyAfterMissingDoCases
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

    // Body recovery when `do` is missing but `end` closes the loop.
    private val missingDoBodyCases = listOf(
        RecoveryCase(
            name = "missing do after pairs keeps multi-name body",
            source = "for key, value in pairs(items) use(key, value) end",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[CallStmt(Call(Id(use):Id(key),Id(value)))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do after ipairs keeps multi-name body",
            source = "for index, value in ipairs(list) consume(value) end",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(index),Id(value) in Call(Id(ipairs):Id(list)):Block[CallStmt(Call(Id(consume):Id(value)))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do single name keeps body",
            source = "for item in items work(item) end",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(item) in Id(items):Block[CallStmt(Call(Id(work):Id(item)))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do with multi-iterator explist keeps body",
            source = "for a, b in next, t, nil handle(a, b) end",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(a),Id(b) in Id(next),Id(t),Const(nil):Block[CallStmt(Call(Id(handle):Id(a),Id(b)))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps multi-statement body",
            source = "for k, v in pairs(t) local copy = v use(k, copy) end",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[Local(Id(copy)=Id(v));CallStmt(Call(Id(use):Id(k),Id(copy)))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps assignment body",
            source = "for k, v in pairs(t) total = total + v end",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[Assign(Id(total)=Binary(+,Id(total),Id(v)))])"
            ),
            warningFragments = listOf("The <do> expected")
        )
    )

    // With `end` present, following statements remain outside the for-in loop.
    private val missingDoLaterStatementCases = listOf(
        RecoveryCase(
            name = "missing do keeps following print after pairs loop",
            source = "for key, value in pairs(items) use(key, value) end\nprint(items)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[CallStmt(Call(Id(use):Id(key),Id(value)))])",
                "CallStmt(Call(Id(print):Id(items)))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps following local after single name loop",
            source = "for item in items work(item) end\nlocal after = 1",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(item) in Id(items):Block[CallStmt(Call(Id(work):Id(item)))])",
                "Local(Id(after)=Const(1))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps following return after ipairs loop",
            source = "for i, v in ipairs(list) consume(v) end\nreturn list",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(i),Id(v) in Call(Id(ipairs):Id(list)):Block[CallStmt(Call(Id(consume):Id(v)))])",
                "Return(Id(list))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps following assignment after multi-iterator loop",
            source = "for a, b in next, t, nil handle(a, b) end\ndone = true",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(a),Id(b) in Id(next),Id(t),Const(nil):Block[CallStmt(Call(Id(handle):Id(a),Id(b)))])",
                "Assign(Id(done)=Const(true))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps multiple following statements",
            source = "for k, v in pairs(t) use(k, v) end\nlocal after = 1\nprint(after)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[CallStmt(Call(Id(use):Id(k),Id(v)))])",
                "Local(Id(after)=Const(1))",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps following call after empty body",
            source = "for k, v in pairs(t) end\nprint(t)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[])",
                "CallStmt(Call(Id(print):Id(t)))"
            ),
            warningFragments = listOf("The <do> expected")
        )
    )

    // Missing end / nested recovery shapes.
    private val missingDoEndAndNestedCases = listOf(
        RecoveryCase(
            name = "missing do and end at eof keeps body statements",
            source = "for key, value in pairs(items) use(key, value) print(\"after\")",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[CallStmt(Call(Id(use):Id(key),Id(value)));CallStmt(Call(Id(print):Const(\"after\")))])"
            ),
            warningFragments = listOf("The <do> expected", "<end> expected")
        ),
        RecoveryCase(
            name = "missing do and end at eof keeps local and call body",
            source = "for item in items local copy = item work(copy)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(item) in Id(items):Block[Local(Id(copy)=Id(item));CallStmt(Call(Id(work):Id(copy)))])"
            ),
            warningFragments = listOf("The <do> expected", "<end> expected")
        ),
        RecoveryCase(
            name = "nested for-in missing inner do keeps outer end and following print",
            source = "for k, v in pairs(outer) do for i, x in ipairs(v) use(x) end end\nprint(outer)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(outer)):Block[ForGeneric(Id(i),Id(x) in Call(Id(ipairs):Id(v)):Block[CallStmt(Call(Id(use):Id(x)))])])",
                "CallStmt(Call(Id(print):Id(outer)))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "nested for-in missing outer do keeps inner and following print",
            source = "for k, v in pairs(outer) for i, x in ipairs(v) do use(x) end end\nprint(outer)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(outer)):Block[ForGeneric(Id(i),Id(x) in Call(Id(ipairs):Id(v)):Block[CallStmt(Call(Id(use):Id(x)))])])",
                "CallStmt(Call(Id(print):Id(outer)))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "nested for-in missing both do keeps bodies and following print",
            source = "for k, v in pairs(outer) for i, x in ipairs(v) use(x) end end\nprint(outer)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(outer)):Block[ForGeneric(Id(i),Id(x) in Call(Id(ipairs):Id(v)):Block[CallStmt(Call(Id(use):Id(x)))])])",
                "CallStmt(Call(Id(print):Id(outer)))"
            ),
            warningFragments = listOf("The <do> expected")
        )
    )

    // Incomplete body content after missing do still recovers and keeps later siblings.
    private val incompleteBodyAfterMissingDoCases = listOf(
        RecoveryCase(
            name = "missing do with incomplete assignment body keeps following print",
            source = "for k, v in pairs(t) total = total + end\nprint(total)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(total)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do with incomplete call body keeps following print",
            source = "for k, v in pairs(t) use(k end\nprint(t)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):",
                "CallStmt(Call(Id(print):Id(t)))"
            ),
            warningFragments = listOf("The <do> expected", "')' expected")
        ),
        RecoveryCase(
            name = "missing do with incomplete local initializer keeps following print",
            source = "for item in items local copy = end\nprint(items)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(item) in Id(items):Block[Local(Id(copy)=ExpressionNodeSupport)])",
                "CallStmt(Call(Id(print):Id(items)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do with incomplete return body keeps following print",
            source = "for item in items return item + end\nprint(item)",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(item) in Id(items):Block[Return(Binary(+,Id(item),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(item)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("The <do> expected")
        )
    )
}
