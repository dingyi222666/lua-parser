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
 * Focused recovery corpus for `while` loops missing `do` / `end` (TASK-319).
 *
 * Complements the single "missing while do keeps loop body" and
 * "missing while end at eof keeps loop body" fixtures in
 * [LuaParserRecoveryTddTest] with deeper coverage of:
 * - identifier / binary / unary / call conditions
 * - missing `do` with present `end` (later statements remain siblings)
 * - missing `do` and missing `end` (body statements remain reachable)
 * - missing `end` alone (body retained to EOF)
 * - nested while recovery
 * - incomplete loop bodies after missing `do`/`end`
 * - well-formed while still clean under recovery
 * - strict-parse rejection of incomplete while sources
 *
 * Test-only; no production LuaParser edits unless review re-scopes.
 *
 * Product goldens (aligned with [LuaParser.parseWhileStatement]):
 * - missing `do` recovers a While and emits `The <do> expected`;
 * - with a matching `end`, following statements remain top-level siblings;
 * - without `end`, statements after the loop header stay inside the while body
 *   and `<end> expected` is emitted;
 * - incomplete RHS expressions use ExpressionNodeSupport placeholders and do
 *   not themselves emit do/end diagnostics when both `do` and `end` are present.
 */
class LuaParserRecoveryWhileDoTddTest {

    @Test
    fun recoversMissingDoOnWhileAndKeepsLoopBody() {
        assertEquals(6, missingDoBodyCases.size)
        missingDoBodyCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingDoAndKeepsLaterSiblingStatements() {
        assertEquals(6, missingDoLaterStatementCases.size)
        missingDoLaterStatementCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingDoAndEndEofAndNestedWhile() {
        assertEquals(5, missingDoEndAndNestedCases.size)
        missingDoEndAndNestedCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingEndAloneAtEof() {
        assertEquals(4, missingEndOnlyCases.size)
        missingEndOnlyCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteBodiesAfterMissingDoOrEnd() {
        assertEquals(6, incompleteBodyCases.size)
        incompleteBodyCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun exposesTypedWhileStructureAfterMissingDoRecovery() {
        val chunk = parseRecoveringWithoutThrow(
            "while ready work() end\nprint(ready)"
        )

        val whileStmt = assertIs<WhileStatement>(chunk.body.statements[0])
        assertEquals("Id(ready)", renderShape(whileStmt.condition))
        assertIs<CallStatement>(whileStmt.body.statements.single())
        assertIs<CallStatement>(chunk.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        // Product [LuaParser.parseWhileStatement] only emits do/end recovery
        // diagnostics when those tokens are actually missing. Incomplete RHS
        // forms with both `do` and `end` present (e.g. `value = value + end`)
        // recover via ExpressionNodeSupport without do/end warnings.
        val sources = listOf(
            "while ready work() end",
            "while ready local tick = 1 tick = tick + 1 end",
            "while ready do work() print(\"after\")",
            "while ready work()",
            "while ready do while inner do work() end",
            "while ready do value = value +"
        )

        sources.forEach { source ->
            val first = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            val second = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)

            assertEquals(renderShape(first.chunk), renderShape(second.chunk), "shape deterministic: $source")
            assertEquals(first.warnings, second.warnings, "warnings deterministic: $source")
            assertTrue(
                first.warnings.any {
                    it.contains("The <do> expected") || it.contains("<end> expected")
                },
                "expected while recovery diagnostic for: $source; actual=${first.warnings}"
            )
        }
    }

    @Test
    fun strictParseRejectsIncompleteWhileWhileRecoveryDoesNotThrow() {
        val incompleteSources = listOf(
            "while ready work() end",
            "while ready local tick = 1 tick = tick + 1 end",
            "while ready do work() print(\"after\")",
            "while ready work()",
            "while ready do while inner do work() end",
            "while n > 0 consume(n) end\nprint(n)"
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
    fun wellFormedWhileStillParsesCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "while ready do work() end",
            "while ready do local tick = 1 tick = tick + 1 end\nprint(ready)",
            "while n > 0 do consume(n) end\nlocal after = 1",
            "while outer do while inner do use(inner) end end\nprint(outer)",
            "while not done do handle() end\nreturn done"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed while should not emit recovery diagnostics: $source"
            )
            assertTrue(
                renderShape(recovered.chunk).contains("While("),
                "well-formed source should produce While shape: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict))
        }
    }

    @Test
    fun documentsWhileMissingDoEndInventorySizeAndCoverage() {
        assertEquals(
            missingDoBodyCases.size +
                missingDoLaterStatementCases.size +
                missingDoEndAndNestedCases.size +
                missingEndOnlyCases.size +
                incompleteBodyCases.size,
            allCases().size
        )
        assertEquals(27, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("missing do") })
        assertTrue(names.any { it.contains("later") || it.contains("following") })
        assertTrue(names.any { it.contains("missing end") || it.contains("eof") })
        assertTrue(names.any { it.contains("nested") })
        assertTrue(names.any { it.contains("incomplete") })
        assertTrue(
            allCases().all { case ->
                case.warningFragments.any {
                    it.contains("The <do> expected") ||
                        it.contains("<end> expected") ||
                        it.contains("')' expected")
                }
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
            missingEndOnlyCases +
            incompleteBodyCases
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
            name = "missing do keeps multi-statement local/assign body",
            source = "while ready local tick = 1 tick = tick + 1 end",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[Local(Id(tick)=Const(1));Assign(Id(tick)=Binary(+,Id(tick),Const(1)))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps call body",
            source = "while ready work() end",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[CallStmt(Call(Id(work):))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do with binary condition keeps body",
            source = "while n > 0 consume(n) end",
            requiredShapeFragments = listOf(
                "While(Binary(>,Id(n),Const(0)):Block[CallStmt(Call(Id(consume):Id(n)))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do with unary condition keeps body",
            source = "while not done handle() end",
            requiredShapeFragments = listOf(
                "While(Unary(not,Id(done)):Block[CallStmt(Call(Id(handle):))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do with call condition keeps body",
            source = "while hasMore() next() end",
            requiredShapeFragments = listOf(
                "While(Call(Id(hasMore):):Block[CallStmt(Call(Id(next):))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps assignment body",
            source = "while ready total = total + 1 end",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[Assign(Id(total)=Binary(+,Id(total),Const(1)))])"
            ),
            warningFragments = listOf("The <do> expected")
        )
    )

    // With `end` present, following statements remain outside the while loop.
    private val missingDoLaterStatementCases = listOf(
        RecoveryCase(
            name = "missing do keeps following print after while",
            source = "while ready work() end\nprint(ready)",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[CallStmt(Call(Id(work):))])",
                "CallStmt(Call(Id(print):Id(ready)))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps following local after binary-condition while",
            source = "while n > 0 consume(n) end\nlocal after = 1",
            requiredShapeFragments = listOf(
                "While(Binary(>,Id(n),Const(0)):Block[CallStmt(Call(Id(consume):Id(n)))])",
                "Local(Id(after)=Const(1))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps following return after unary-condition while",
            source = "while not done handle() end\nreturn done",
            requiredShapeFragments = listOf(
                "While(Unary(not,Id(done)):Block[CallStmt(Call(Id(handle):))])",
                "Return(Id(done))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps following assignment after call-condition while",
            source = "while hasMore() next() end\ndone = true",
            requiredShapeFragments = listOf(
                "While(Call(Id(hasMore):):Block[CallStmt(Call(Id(next):))])",
                "Assign(Id(done)=Const(true))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps multiple following statements",
            source = "while ready work() end\nlocal after = 1\nprint(after)",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[CallStmt(Call(Id(work):))])",
                "Local(Id(after)=Const(1))",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing do keeps following call after empty body",
            source = "while ready end\nprint(ready)",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[])",
                "CallStmt(Call(Id(print):Id(ready)))"
            ),
            warningFragments = listOf("The <do> expected")
        )
    )

    // Missing end / nested recovery shapes.
    private val missingDoEndAndNestedCases = listOf(
        RecoveryCase(
            name = "missing do and end at eof keeps body statements",
            source = "while ready work() print(\"after\")",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[CallStmt(Call(Id(work):));CallStmt(Call(Id(print):Const(\"after\")))])"
            ),
            warningFragments = listOf("The <do> expected", "<end> expected")
        ),
        RecoveryCase(
            name = "missing do and end at eof keeps local and call body",
            source = "while ready local copy = 1 work(copy)",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[Local(Id(copy)=Const(1));CallStmt(Call(Id(work):Id(copy)))])"
            ),
            warningFragments = listOf("The <do> expected", "<end> expected")
        ),
        RecoveryCase(
            name = "nested while missing inner do keeps outer end and following print",
            source = "while outer do while inner use(inner) end end\nprint(outer)",
            requiredShapeFragments = listOf(
                "While(Id(outer):Block[While(Id(inner):Block[CallStmt(Call(Id(use):Id(inner)))])])",
                "CallStmt(Call(Id(print):Id(outer)))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "nested while missing outer do keeps inner and following print",
            source = "while outer while inner do use(inner) end end\nprint(outer)",
            requiredShapeFragments = listOf(
                "While(Id(outer):Block[While(Id(inner):Block[CallStmt(Call(Id(use):Id(inner)))])])",
                "CallStmt(Call(Id(print):Id(outer)))"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "nested while missing both do keeps bodies and following print",
            source = "while outer while inner use(inner) end end\nprint(outer)",
            requiredShapeFragments = listOf(
                "While(Id(outer):Block[While(Id(inner):Block[CallStmt(Call(Id(use):Id(inner)))])])",
                "CallStmt(Call(Id(print):Id(outer)))"
            ),
            warningFragments = listOf("The <do> expected")
        )
    )

    // Missing end alone (do present) still recovers residual body to EOF.
    private val missingEndOnlyCases = listOf(
        RecoveryCase(
            name = "missing end at eof keeps multi-statement body",
            source = "while ready do work() print(\"after\")",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[CallStmt(Call(Id(work):));CallStmt(Call(Id(print):Const(\"after\")))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end at eof keeps local and call body",
            source = "while ready do local copy = 1 work(copy)",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[Local(Id(copy)=Const(1));CallStmt(Call(Id(work):Id(copy)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end at eof keeps empty body",
            source = "while ready do",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "nested missing outer end keeps closed inner in outer body",
            source = "while outer do while inner do use(inner) end print(outer)",
            requiredShapeFragments = listOf(
                "While(Id(outer):Block[While(Id(inner):Block[CallStmt(Call(Id(use):Id(inner)))]);CallStmt(Call(Id(print):Id(outer)))])"
            ),
            warningFragments = listOf("<end> expected")
        )
    )

    // Incomplete body content after missing do/end still recovers residual loop + following stmts.
    // Product: incomplete RHS uses ExpressionNodeSupport; do/end diagnostics only when those tokens missing.
    // TASK-551: incomplete parenthesized call trailing-comma / missing-`)` keeps later statements siblings.
    private val incompleteBodyCases = listOf(
        RecoveryCase(
            name = "missing do with incomplete assignment body keeps following print",
            source = "while ready total = total + end\nprint(total)",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(total)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing end with incomplete assignment body keeps placeholder",
            source = "while ready do value = value +",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport))])"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing do with incomplete call body keeps following print",
            source = "while ready use(n end\nprint(ready)",
            requiredShapeFragments = listOf(
                "While(Id(ready):",
                "CallStmt(Call(Id(print):Id(ready)))"
            ),
            warningFragments = listOf("The <do> expected", "')' expected")
        ),
        RecoveryCase(
            name = "missing do with incomplete local initializer keeps following print",
            source = "while ready local copy = end\nprint(ready)",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[Local(Id(copy)=ExpressionNodeSupport)])",
                "CallStmt(Call(Id(print):Id(ready)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "incomplete call trailing comma in while body keeps following print",
            source = "while ready do use(n,\nprint(ready) end",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[CallStmt(Call(Id(use):Id(n)));CallStmt(Call(Id(print):Id(ready)))])"
            ),
            badShapeFragments = listOf("Call(Id(use):Id(n))"),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "incomplete call missing paren after arg in while body keeps following print",
            source = "while ready do use(n\nprint(ready) end",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[CallStmt(Call(Id(use):Id(n)));CallStmt(Call(Id(print):Id(ready)))])"
            ),
            badShapeFragments = listOf("Call(Id(use):Id(n))"),
            warningFragments = listOf("')' expected")
        )
    )
}
