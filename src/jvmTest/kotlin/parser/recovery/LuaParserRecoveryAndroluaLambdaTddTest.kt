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
 * Focused AndroLua lambda / arrow recovery corpus (TASK-388).
 *
 * Complements the 3 baseline lambda cases in [LuaParserRecoveryTddTest] and the
 * regression smoke in [parser.ParserRecoveryRegressionTest] with deeper coverage of:
 * - incomplete bodies for `:`, `->`, and `=>` forms (including zero-arg / paren params)
 * - missing / partial arrow tokens (`-` without `>`, `=` without `>`, bare missing `:`)
 * - incomplete param lists (missing `)`, trailing comma)
 * - incomplete binary/unary bodies (terminator → placeholder)
 * - later-statement reachability when the body ends at an expression terminator
 *   (`end`, `)`, `}`, `,`, EOF) so a sibling statement remains reachable
 * - version gating: lambda remains AndroLua-only (not recovery)
 *
 * Product notes (LuaParser.parseLambdaExp / parseSubExp / parseSubExpTail):
 * - Body uses `isExpressionTerminator` only — there is **no** lambda-body
 *   statement-start-after-line-break path. A following `NAME`/`print(...)` after
 *   `->` / `=>` / `:` is absorbed as the body expression (same family as unary).
 * - A following non-expression statement-start (`local` / `return` / …) after an
 *   incomplete body is **not** recovered as a placeholder and can still throw under
 *   recovery; those forms stay out of the supported inventory (product follow-up).
 * - Incomplete `->` / `=>` still parse the body after a warning (`'->' expected` /
 *   `'=>' expected`); missing arrow entirely warns `':' expected` and continues.
 * - Missing body at EOF / `end` / `)` / `}` / `,` inserts bad `ExpressionNodeSupport`.
 * - Incomplete binary right operand uses terminator + statement-start-after-line-break
 *   recovery (shared expression path), so `lambda x: x +\nprint(1)` keeps a
 *   placeholder right operand and leaves `print` as a sibling when the lambda is not
 *   the sole chunk-terminating return expression that swallows the tail.
 *
 * Test-only; no production LuaParser edits unless review re-scopes.
 * Keep [StrictParseExpectation] flags honest against current product.
 * Verification deferred to TASK-043 serial filter:
 *   parser.recovery.LuaParserRecoveryAndroluaLambdaTddTest
 */
class LuaParserRecoveryAndroluaLambdaTddTest {

    @Test
    fun recoversIncompleteLambdaBodiesWithPlaceholders() {
        assertEquals(8, incompleteBodyCases.size)
        incompleteBodyCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingOrPartialArrowTokensAndStillParsesBody() {
        assertEquals(6, missingOrPartialArrowCases.size)
        missingOrPartialArrowCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteParamListsAndKeepsBodyOrPlaceholder() {
        assertEquals(5, incompleteParamListCases.size)
        incompleteParamListCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteBinaryAndUnaryBodies() {
        assertEquals(5, incompleteBinaryUnaryBodyCases.size)
        incompleteBinaryUnaryBodyCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversLambdaFormsWhileKeepingLaterStatementsReachable() {
        assertEquals(7, laterStatementReachabilityCases.size)
        laterStatementReachabilityCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun absorbsFollowingPrintAsLambdaBodyWithoutSiblingRecovery() {
        assertEquals(3, bodyAbsorbCases.size)
        bodyAbsorbCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun exposesTypedLambdaStructureAfterRecovery() {
        val missingBody = parseRecoveringWithoutThrow(
            "return lambda value ->",
            version = LuaVersion.ANDROLUA_5_3,
        )
        val lambda = assertIs<LambdaDeclaration>(
            assertIs<ReturnStatement>(missingBody.body.returnStatement).arguments.single()
        )
        assertEquals(listOf("value"), lambda.params.map { it.name })
        assertEquals("ExpressionNodeSupport", renderShape(lambda.expression))
        assertTrue(lambda.expression.bad)

        val partialArrow = parseRecoveringWithoutThrow(
            "return lambda value - value",
            version = LuaVersion.ANDROLUA_5_3,
        )
        val partial = assertIs<LambdaDeclaration>(
            assertIs<ReturnStatement>(partialArrow.body.returnStatement).arguments.single()
        )
        assertEquals("Id(value)", renderShape(partial.expression))

        val table = parseRecoveringWithoutThrow(
            "local t = { f = lambda x: }\nprint(t)",
            version = LuaVersion.ANDROLUA_5_3,
        )
        val local = assertIs<LocalStatement>(table.body.statements[0])
        val ctor = assertIs<TableConstructorExpression>(local.variables.single())
        val field = assertIs<TableKeyString>(ctor.fields.single())
        val fieldLambda = assertIs<LambdaDeclaration>(field.value)
        assertEquals("ExpressionNodeSupport", renderShape(fieldLambda.expression))
        assertTrue(fieldLambda.expression.bad)
        assertIs<CallStatement>(table.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "return lambda value ->",
            "return lambda value - value",
            "return lambda value = value",
            "return lambda value value",
            "return lambda(x, y) =>",
            "return lambda(x -> x",
            "return lambda x: x +",
            "local t = { f = lambda x: }\nprint(t)",
            "use(lambda x:)\nprint(1)",
            "do local f = lambda value -> end\nprint(f)",
            "local f = lambda value ->\nprint(f)",
        )

        sources.forEach { source ->
            val first = parseRecoveringWithWarnings(LuaVersion.ANDROLUA_5_3, source)
            val second = parseRecoveringWithWarnings(LuaVersion.ANDROLUA_5_3, source)

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
        assertTrue(
            accepts.map { it.name }.containsAll(
                listOf(
                    "lambda arrow body absorbs following print as body expression",
                    "well-formed colon lambda still parses cleanly under recovery"
                )
            )
        )
        accepts.forEach(::assertStrictParseCurrentlyAccepts)
    }

    @Test
    fun wellFormedLambdaFormsStillParseCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "return lambda value: value",
            "return lambda value -> value",
            "return lambda(x, y) => x + y",
            "return lambda: 1",
            "return lambda(): value",
            "local f = lambda x: x + 1\nprint(f)",
            "use(lambda v: v)\nprint(1)",
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.ANDROLUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed lambda should not emit recovery diagnostics: $source"
            )
            val strict = parse(LuaVersion.ANDROLUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict))
        }
    }

    @Test
    fun lambdaRemainsRejectedInPlainLuaEvenWithRecoveryEnabled() {
        intentionallyRejectedBoundaryCases.forEach(::assertIntentionallyRejectedRecoveryBoundary)
    }

    @Test
    fun documentsAndroluaLambdaRecoveryInventorySizeAndCoverage() {
        assertEquals(
            incompleteBodyCases.size +
                missingOrPartialArrowCases.size +
                incompleteParamListCases.size +
                incompleteBinaryUnaryBodyCases.size +
                laterStatementReachabilityCases.size +
                bodyAbsorbCases.size,
            allCases().size
        )
        assertEquals(34, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("missing body") || it.contains("placeholder") })
        assertTrue(names.any { it.contains("->") || it.contains("arrow") })
        assertTrue(names.any { it.contains("=>") || it.contains("fat") })
        assertTrue(names.any { it.contains("param") || it.contains("paren") })
        assertTrue(names.any { it.contains("later") || it.contains("sibling") || it.contains("print") })
        assertTrue(
            allCases().any {
                it.strictParseExpectation == StrictParseExpectation.REJECTS
            }
        )
        assertTrue(
            allCases().any {
                it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
            }
        )
        assertEquals(4, intentionallyRejectedBoundaryCases.size)
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
        version: LuaVersion = LuaVersion.ANDROLUA_5_3,
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

    private fun assertIntentionallyRejectedRecoveryBoundary(case: RecoveryRejectionCase) {
        val label = "${case.name}: ${case.rationale}"
        assertDeterministicParseFailure(label, case.version, case.source, recovery = false)
        assertDeterministicParseFailure(label, case.version, case.source, recovery = true)
    }

    private fun assertDeterministicParseFailure(
        label: String,
        version: LuaVersion,
        source: String,
        recovery: Boolean,
    ) {
        val first = assertParseFails(version, source, recovery = recovery)
        val second = assertParseFails(version, source, recovery = recovery)

        assertEquals(first::class, second::class, "$label failure type with recovery=$recovery")
        assertEquals(first.message, second.message, "$label failure message with recovery=$recovery")
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
        return incompleteBodyCases +
            missingOrPartialArrowCases +
            incompleteParamListCases +
            incompleteBinaryUnaryBodyCases +
            laterStatementReachabilityCases +
            bodyAbsorbCases
    }

    private enum class StrictParseExpectation {
        REJECTS,
        CURRENTLY_ACCEPTS,
    }

    private data class RecoveryCase(
        val name: String,
        val source: String,
        val version: LuaVersion = LuaVersion.ANDROLUA_5_3,
        val requiredShapeFragments: List<String>,
        val badShapeFragments: List<String> = emptyList(),
        val warningFragments: List<String> = emptyList(),
        val strictParseExpectation: StrictParseExpectation = StrictParseExpectation.REJECTS,
    )

    private data class RecoveryRejectionCase(
        val name: String,
        val source: String,
        val version: LuaVersion,
        val rationale: String,
    )

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )

    // Incomplete bodies at expression terminators (EOF / end / ) / } / ,) insert
    // bad ExpressionNodeSupport placeholders for :, ->, and => forms.
    private val incompleteBodyCases = listOf(
        RecoveryCase(
            name = "arrow lambda missing body at eof marks placeholder expression bad",
            source = "return lambda value ->",
            requiredShapeFragments = listOf("Return(Lambda(Id(value):ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "fat-arrow lambda missing body at eof marks placeholder expression bad",
            source = "return lambda(x, y) =>",
            requiredShapeFragments = listOf("Return(Lambda(Id(x),Id(y):ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "colon lambda missing body at eof marks placeholder expression bad",
            source = "return lambda value:",
            requiredShapeFragments = listOf("Return(Lambda(Id(value):ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "zero-arg colon lambda missing body at eof marks placeholder",
            source = "return lambda:",
            requiredShapeFragments = listOf("Return(Lambda(:ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "zero-arg paren arrow lambda missing body at eof marks placeholder",
            source = "return lambda() ->",
            requiredShapeFragments = listOf("Return(Lambda(:ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "arrow lambda missing body before end keeps local and later print",
            source = "do local f = lambda value -> end\nprint(f)",
            requiredShapeFragments = listOf(
                "Local(Id(f)=Lambda(Id(value):ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(f)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "colon lambda missing body before rparen keeps following print",
            source = "use(lambda x:)\nprint(1)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(use):Lambda(Id(x):ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Const(1)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "colon lambda missing body before table close keeps following print",
            source = "local t = { f = lambda x: }\nprint(t)",
            requiredShapeFragments = listOf(
                "Local(Id(t)=Table(TableKeyString(Id(f)=Lambda(Id(x):ExpressionNodeSupport))))",
                "CallStmt(Call(Id(print):Id(t)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )

    // Partial / missing arrow tokens still recover the body expression with warnings.
    private val missingOrPartialArrowCases = listOf(
        RecoveryCase(
            name = "arrow lambda missing greater-than still parses body expression",
            source = "return lambda value - value",
            requiredShapeFragments = listOf("Return(Lambda(Id(value):Id(value)))"),
            warningFragments = listOf("'->' expected")
        ),
        RecoveryCase(
            name = "fat-arrow lambda missing greater-than still parses body expression",
            source = "return lambda value = value",
            requiredShapeFragments = listOf("Return(Lambda(Id(value):Id(value)))"),
            warningFragments = listOf("'=>' expected")
        ),
        RecoveryCase(
            name = "paren fat-arrow lambda missing greater-than still parses body",
            source = "return lambda(x, y) = x + y",
            requiredShapeFragments = listOf("Return(Lambda(Id(x),Id(y):Binary(+,Id(x),Id(y))))"),
            warningFragments = listOf("'=>' expected")
        ),
        RecoveryCase(
            name = "lambda missing arrow entirely still parses following body with colon warning",
            source = "return lambda value value",
            requiredShapeFragments = listOf("Return(Lambda(Id(value):Id(value)))"),
            warningFragments = listOf("':' expected")
        ),
        RecoveryCase(
            name = "partial arrow missing body at eof still inserts placeholder",
            source = "return lambda value -",
            requiredShapeFragments = listOf("Return(Lambda(Id(value):ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("'->' expected")
        ),
        RecoveryCase(
            name = "partial fat arrow missing body at eof still inserts placeholder",
            source = "return lambda value =",
            requiredShapeFragments = listOf("Return(Lambda(Id(value):ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("'=>' expected")
        )
    )

    // Incomplete param lists (missing ')', trailing comma) recover with warnings /
    // bad empty names while still building a LambdaDeclaration.
    private val incompleteParamListCases = listOf(
        RecoveryCase(
            name = "paren params missing closing paren still keeps arrow body",
            source = "return lambda(x -> x",
            requiredShapeFragments = listOf("Return(Lambda(Id(x):Id(x)))"),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "paren multi params missing closing paren still keeps fat-arrow body",
            source = "return lambda(x, y => x + y",
            requiredShapeFragments = listOf("Return(Lambda(Id(x),Id(y):Binary(+,Id(x),Id(y))))"),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "paren params trailing comma inserts bad empty name and keeps body",
            source = "return lambda(x, ) -> x",
            requiredShapeFragments = listOf("Return(Lambda(Id(x),Id():Id(x)))"),
            badShapeFragments = listOf("Id()"),
            warningFragments = listOf("<name> expected")
        ),
        RecoveryCase(
            name = "paren params missing name after open paren recovers empty param list with body",
            source = "return lambda( -> 1",
            requiredShapeFragments = listOf("Return(Lambda(:Const(1)))"),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "paren params missing closing paren and body still inserts placeholder",
            source = "return lambda(x, y =>",
            requiredShapeFragments = listOf("Return(Lambda(Id(x),Id(y):ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("')' expected")
        )
    )

    // Incomplete binary / unary lambda bodies share the expression recovery paths.
    private val incompleteBinaryUnaryBodyCases = listOf(
        RecoveryCase(
            name = "colon lambda binary body missing rhs at eof keeps placeholder",
            source = "return lambda x: x +",
            requiredShapeFragments = listOf(
                "Return(Lambda(Id(x):Binary(+,Id(x),ExpressionNodeSupport)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "arrow lambda binary body missing rhs before end keeps later print",
            source = "do local f = lambda x: x + end\nprint(f)",
            requiredShapeFragments = listOf(
                "Local(Id(f)=Lambda(Id(x):Binary(+,Id(x),ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Id(f)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "fat-arrow lambda unary body missing operand at eof keeps placeholder",
            source = "return lambda x => not",
            requiredShapeFragments = listOf(
                "Return(Lambda(Id(x):Unary(not,ExpressionNodeSupport)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "colon lambda unary body missing operand before end keeps later print",
            source = "do local f = lambda x: not end\nprint(f)",
            requiredShapeFragments = listOf(
                "Local(Id(f)=Lambda(Id(x):Unary(not,ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Id(f)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Shared binary statement-start-after-line-break path: right operand is
            // placeholder; print remains a sibling CallStmt after the local.
            name = "local lambda binary body missing rhs leaves print sibling",
            source = "local f = lambda x: x +\nprint(f)",
            requiredShapeFragments = listOf(
                "Local(Id(f)=Lambda(Id(x):Binary(+,Id(x),ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Id(f)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            // Strict mode currently absorbs the following print call as the binary right.
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )

    // Later-statement reachability via terminator-bounded incomplete bodies.
    private val laterStatementReachabilityCases = listOf(
        RecoveryCase(
            name = "table field incomplete lambda keeps following local and print",
            source = "local t = { f = lambda x: }\nlocal after = 1\nprint(after)",
            requiredShapeFragments = listOf(
                "Lambda(Id(x):ExpressionNodeSupport)",
                "Local(Id(after)=Const(1))",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "call arg incomplete fat-arrow lambda keeps following print",
            source = "use(lambda(x, y) =>)\nprint(1)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(use):Lambda(Id(x),Id(y):ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Const(1)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "assignment incomplete arrow lambda before end keeps later print",
            source = "do f = lambda value -> end\nprint(f)",
            requiredShapeFragments = listOf(
                "Assign(Id(f)=Lambda(Id(value):ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(f)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "return multi-exp incomplete lambda before comma keeps second return value",
            source = "return lambda value ->, 1",
            requiredShapeFragments = listOf(
                "Return(Lambda(Id(value):ExpressionNodeSupport),Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "nested incomplete outer lambda before end keeps later print",
            source = "do local f = lambda x: lambda y -> end\nprint(f)",
            requiredShapeFragments = listOf(
                "Local(Id(f)=Lambda(Id(x):Lambda(Id(y):ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Id(f)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "array element incomplete lambda keeps following print",
            source = "local a = [lambda x:]\nprint(a)",
            requiredShapeFragments = listOf(
                "Local(Id(a)=Array(Lambda(Id(x):ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "well-formed colon lambda still parses cleanly under recovery",
            source = "local f = lambda x: x + 1\nprint(f)",
            requiredShapeFragments = listOf(
                "Local(Id(f)=Lambda(Id(x):Binary(+,Id(x),Const(1))))",
                "CallStmt(Call(Id(print):Id(f)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )

    // Document absorb-vs-sibling product path: following print becomes the body.
    private val bodyAbsorbCases = listOf(
        RecoveryCase(
            name = "lambda arrow body absorbs following print as body expression",
            source = "local f = lambda value ->\nprint(f)",
            requiredShapeFragments = listOf(
                "Local(Id(f)=Lambda(Id(value):Call(Id(print):Id(f))))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "lambda colon body absorbs following print as body expression",
            source = "local f = lambda value:\nprint(f)",
            requiredShapeFragments = listOf(
                "Local(Id(f)=Lambda(Id(value):Call(Id(print):Id(f))))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "lambda fat-arrow body absorbs following print as body expression",
            source = "local f = lambda(x) =>\nprint(x)",
            requiredShapeFragments = listOf(
                "Local(Id(f)=Lambda(Id(x):Call(Id(print):Id(x))))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )

    // Version gating / out-of-scope boundaries (not recovery).
    private val intentionallyRejectedBoundaryCases = listOf(
        RecoveryRejectionCase(
            name = "arrow lambda remains rejected in plain Lua 5.3 even with recovery",
            source = "return lambda value -> value",
            version = LuaVersion.LUA_5_3,
            rationale = "lambda is AndroLua-only syntax; version gating is not parser recovery"
        ),
        RecoveryRejectionCase(
            name = "fat-arrow lambda remains rejected in plain Lua 5.4 even with recovery",
            source = "return lambda(x, y) => x + y",
            version = LuaVersion.LUA_5_4,
            rationale = "lambda is AndroLua-only syntax; version gating is not parser recovery"
        ),
        RecoveryRejectionCase(
            name = "incomplete arrow lambda remains rejected in plain Lua 5.3 with recovery",
            source = "return lambda value ->",
            version = LuaVersion.LUA_5_3,
            rationale = "recovery does not unlock AndroLua lambda in plain Lua modes"
        ),
        RecoveryRejectionCase(
            name = "colon lambda remains rejected in plain Lua 5.3 with recovery",
            source = "return lambda value: value",
            version = LuaVersion.LUA_5_3,
            rationale = "recovery does not unlock AndroLua lambda in plain Lua modes"
        )
    )
}
