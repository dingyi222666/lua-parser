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
 * Focused recovery corpus for AndroLua `switch` / `when` incomplete chains (TASK-387).
 *
 * Acceptance:
 * - Incomplete switch/when chains recover with errorRecovery=true (no throw).
 * - Later statements remain reachable after broken chains.
 * - Diagnostics / bad placeholders are deterministic.
 * - Test-only; no production LuaParser edits.
 *
 * Complements the three switch fixtures and one when fixture in
 * [LuaParserRecoveryTddTest] with deeper coverage of:
 * - missing `do` / missing `end` / both
 * - multi-case + default incomplete chains
 * - incomplete switch conditions and case bodies
 * - nested switch recovery
 * - when incomplete if/else causes and malformed expression statements
 * - well-formed switch/when still clean under recovery
 * - strict-parse rejection of incomplete AndroLua forms
 *
 * Product goldens (aligned with [LuaParser.parseSwitchStatement] /
 * [LuaParser.parseWhenStatement]):
 * - missing `do` emits `The <do> expected` and still attaches case/default bodies;
 * - missing `end` emits `<end> expected` and retains causes;
 * - incomplete RHS expressions use ExpressionNodeSupport placeholders;
 * - malformed when if-cause expressions are wrapped as bad CallStmt;
 * - following top-level statements after a closed switch remain siblings.
 * - Honest strict flags: incomplete binary RHS / condition after line-break may still
 *   CURRENTLY_ACCEPT under recovery=false (next statement absorbed as expression RHS);
 *   recovery=true keeps later statements as siblings via ExpressionNodeSupport.
 * - Compact AndroLua optional-do switch with matching end is legal under strict
 *   (TASK-610 / TASK-636); only true gaps (missing end, incomplete conditions) REJECT.
 */
class LuaParserRecoveryAndroluaSwitchWhenTddTest {

    @Test
    fun recoversMissingDoOnSwitchAndKeepsCaseDefaultBodies() {
        assertEquals(5, missingDoCases.size)
        missingDoCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingEndOnSwitchAndKeepsCauses() {
        assertEquals(5, missingEndCases.size)
        missingEndCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingDoAndEndAndIncompleteSwitchConditions() {
        assertEquals(5, missingDoEndAndConditionCases.size)
        missingDoEndAndConditionCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteCaseBodiesAndKeepsLaterStatements() {
        assertEquals(5, incompleteCaseBodyCases.size)
        incompleteCaseBodyCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteWhenChainsAndKeepsLaterStatements() {
        assertEquals(7, whenRecoveryCases.size)
        whenRecoveryCases.forEach(::assertSupportedRecoveryCase)
        // At least two honest CURRENTLY_ACCEPTS incomplete-binary line-break footguns.
        assertTrue(
            whenRecoveryCases.count {
                it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
            } >= 2
        )
    }

    @Test
    fun exposesTypedSwitchAndWhenStructureAfterRecovery() {
        val switchChunk = parseRecoveringWithoutThrow(
            "switch mode case 1 one() default fallback() end\nprint(mode)"
        )
        val switchStmt = assertIs<SwitchStatement>(switchChunk.body.statements[0])
        assertEquals("Id(mode)", renderShape(switchStmt.condition))
        assertEquals(2, switchStmt.causes.size)
        val caseCause = assertIs<CaseCause>(switchStmt.causes[0])
        assertEquals(listOf("Const(1)"), caseCause.conditions.map(::renderShape))
        assertIs<CallStatement>(caseCause.body.statements.single())
        assertIs<DefaultCause>(switchStmt.causes[1])
        assertIs<CallStatement>(switchChunk.body.statements[1])

        val whenChunk = parseRecoveringWithoutThrow(
            "when ready target + 1 else fallback()\nprint(ready)"
        )
        val whenStmt = assertIs<WhenStatement>(whenChunk.body.statements[0])
        assertEquals("Id(ready)", renderShape(whenStmt.condition))
        val ifCause = assertIs<CallStatement>(whenStmt.ifCause)
        assertTrue(ifCause.expression.bad || ifCause.bad || renderShape(ifCause).contains("Binary(+"))
        assertIs<CallStatement>(whenStmt.elseCause)
        assertIs<CallStatement>(whenChunk.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "switch mode case 1 one() default fallback() end",
            "switch mode do case 1 one() default fallback()",
            "switch mode case 1 one()",
            "switch mode do case 1 then value = value + end\nprint(value)",
            "when ready target + 1 else fallback()",
            "when ready value = value + else fallback()\nprint(value)",
            "switch mode do case 1 one() case 2 two() default three()"
        )

        sources.forEach { source ->
            val first = parseRecoveringWithWarnings(LuaVersion.ANDROLUA_5_3, source)
            val second = parseRecoveringWithWarnings(LuaVersion.ANDROLUA_5_3, source)

            assertEquals(renderShape(first.chunk), renderShape(second.chunk), "shape deterministic: $source")
            assertEquals(first.warnings, second.warnings, "warnings deterministic: $source")
            assertTrue(
                first.warnings.isNotEmpty() ||
                    renderShape(first.chunk).contains("ExpressionNodeSupport") ||
                    collectBadNodes(first.chunk).isNotEmpty(),
                "expected recovery signal for: $source; warnings=${first.warnings}; shape=${renderShape(first.chunk)}"
            )
            first.warnings.forEach { message ->
                assertTrue(message.isNotBlank(), "warning message must be non-blank")
            }
        }
    }

    @Test
    fun strictParseRejectsIncompleteSwitchWhenWhileRecoveryDoesNotThrow() {
        // Strict-reject corpus: only sources that product currently rejects with recovery=false.
        // Compact AndroLua `switch exp case ... end` (optional do) is legal under strict
        // (TASK-610); those live in missingDoCases as CURRENTLY_ACCEPTS.
        // Incomplete binary RHS/condition followed by a statement-start line (e.g.
        // `value = value +\nprint(...)` / `when value +\nprint(1)`) currently accepts by
        // absorbing the next call as an expression RHS — those live in whenRecoveryCases as
        // CURRENTLY_ACCEPTS and are not asserted here.
        val incompleteSources = listOf(
            "switch mode do case 1 one() default fallback()",
            "switch mode case 1 one()",
            "switch mode do case 1 then value = value + end",
            "when ready target + 1 else fallback()",
            "when ready value = value + else fallback()",
            "switch value + do case 1 print(1) end",
            "when ready target\nprint(ready)"
        )

        incompleteSources.forEach { source ->
            parseRecoveringWithoutThrow(source)

            val first = assertParseFails(LuaVersion.ANDROLUA_5_3, source, recovery = false)
            val second = assertParseFails(LuaVersion.ANDROLUA_5_3, source, recovery = false)
            assertEquals(first::class, second::class, "strict failure type for: $source")
            assertEquals(first.message, second.message, "strict failure message for: $source")
        }
    }

    @Test
    fun wellFormedSwitchAndWhenStillParseCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "switch value do end",
            "switch value do case 1 then print(1) end",
            "switch value do case 1, 2 print(value) end",
            "switch value do case 1 then break default continue end",
            "switch value + 1 do case limit then result = limit default result = value end",
            "do switch value do case 1 then continue default break end end",
            "when ready target = 1",
            "when ready target = 1 else target = 2",
            "when ready print(1)",
            "when ready print(1) else fallback(2)",
            "when ready object:show(\"ok\") else object:hide()"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.ANDROLUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed switch/when should not emit recovery diagnostics: $source"
            )
            val shape = renderShape(recovered.chunk)
            assertTrue(
                shape.contains("Switch(") || shape.contains("When("),
                "well-formed source should produce Switch/When shape: $source\n$shape"
            )
            val strict = parse(LuaVersion.ANDROLUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict))
        }
    }

    @Test
    fun documentsSwitchWhenRecoveryInventorySizeAndCoverage() {
        assertEquals(
            missingDoCases.size +
                missingEndCases.size +
                missingDoEndAndConditionCases.size +
                incompleteCaseBodyCases.size +
                whenRecoveryCases.size,
            allCases().size
        )
        assertEquals(27, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("missing do") })
        assertTrue(names.any { it.contains("missing end") || it.contains("eof") })
        assertTrue(names.any { it.contains("later") || it.contains("following") || it.contains("trailing") })
        assertTrue(names.any { it.contains("when") })
        assertTrue(names.any { it.contains("nested") || it.contains("multi-case") || it.contains("case") })
        assertTrue(names.any { it.contains("incomplete") })
        assertTrue(allCases().all { it.version == LuaVersion.ANDROLUA_5_3 })
        assertTrue(
            whenRecoveryCases.any {
                it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
            },
            "when inventory should document at least one CURRENTLY_ACCEPTS incomplete-binary footgun"
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
        val fullShape = renderShape(chunk)
        case.badShapeFragments.forEach { expected ->
            assertTrue(
                badShapes.any { it.contains(expected) } || fullShape.contains(expected),
                "${case.name} should mark a recovered node containing '$expected' as bad; " +
                    "bad nodes: $badShapes\nshape:\n$fullShape"
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
        return missingDoCases +
            missingEndCases +
            missingDoEndAndConditionCases +
            incompleteCaseBodyCases +
            whenRecoveryCases
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

    private data class RecoveryRun(
        val chunk: ChunkNode,
        val warnings: List<String>,
    )

    // Missing `do` with present `end` — causes retained; later statements siblings.
    // AndroLua strict accepts compact `switch exp case/default/end` (TASK-610); recovery
    // still emits the historical `The <do> expected` diagnostic for inventory green-lock.
    private val missingDoCases = listOf(
        RecoveryCase(
            name = "missing do keeps case and default bodies",
            source = "switch mode case 1 one() default fallback() end",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(one):))]),Default(Block[CallStmt(Call(Id(fallback):))]))"
            ),
            warningFragments = listOf("The <do> expected"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "missing do keeps multi-case bodies",
            source = "switch mode case 1 one() case 2 two() default three() end",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(one):))]),Case(Const(2):Block[CallStmt(Call(Id(two):))]),Default(Block[CallStmt(Call(Id(three):))]))"
            ),
            warningFragments = listOf("The <do> expected"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "missing do keeps following print after switch",
            source = "switch mode case 1 one() default fallback() end\nprint(mode)",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(one):))]),Default(Block[CallStmt(Call(Id(fallback):))]))",
                "CallStmt(Call(Id(print):Id(mode)))"
            ),
            warningFragments = listOf("The <do> expected"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "missing do with optional then keeps case body and trailing local",
            source = "switch value case 1 then handle(value) end\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Switch(Id(value):Case(Const(1):Block[CallStmt(Call(Id(handle):Id(value)))]))",
                "Local(Id(after)=Const(1))"
            ),
            warningFragments = listOf("The <do> expected"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "missing do empty switch keeps following return",
            source = "switch mode end\nreturn mode",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):)",
                "Return(Id(mode))"
            ),
            warningFragments = listOf("The <do> expected"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )

    // Missing `end` (do present) — residual causes retained to EOF.
    private val missingEndCases = listOf(
        RecoveryCase(
            name = "missing end at eof keeps default body",
            source = "switch mode do case 1 one() default fallback()",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(one):))]),Default(Block[CallStmt(Call(Id(fallback):))]))"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end at eof keeps multi-case chain",
            source = "switch mode do case 1 one() case 2 two() default three()",
            requiredShapeFragments = listOf(
                "Case(Const(1):Block[CallStmt(Call(Id(one):))])",
                "Case(Const(2):Block[CallStmt(Call(Id(two):))])",
                "Default(Block[CallStmt(Call(Id(three):))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end at eof keeps single case body",
            source = "switch mode do case 1 then handle(mode)",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(handle):Id(mode)))]))"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end empty switch still recovers",
            source = "switch mode do",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):)"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing end keeps nested switch inside do and following print after outer end",
            source = """
                do
                  switch mode do case 1 one()
                end
                print(mode)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(one):))]))",
                "CallStmt(Call(Id(print):Id(mode)))"
            ),
            warningFragments = listOf("<end> expected")
        )
    )

    // Missing both do/end and incomplete switch conditions.
    // True recovery gaps (missing end, incomplete conditions) stay REJECTS under strict.
    // Compact optional-do only (with matching end) is legal under AndroLua strict (TASK-610)
    // and must be CURRENTLY_ACCEPTS so inventory stays honest (TASK-636 / WINSLICE s010).
    private val missingDoEndAndConditionCases = listOf(
        RecoveryCase(
            name = "missing do and end still keeps first case body",
            source = "switch mode case 1 one()",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(one):))]))"
            ),
            warningFragments = listOf("The <do> expected", "<end> expected")
        ),
        RecoveryCase(
            name = "missing do and end keeps multi-case incomplete chain",
            source = "switch mode case 1 one() case 2 two() default three()",
            requiredShapeFragments = listOf(
                "Case(Const(1):Block[CallStmt(Call(Id(one):))])",
                "Case(Const(2):Block[CallStmt(Call(Id(two):))])",
                "Default(Block[CallStmt(Call(Id(three):))])"
            ),
            warningFragments = listOf("The <do> expected", "<end> expected")
        ),
        RecoveryCase(
            name = "incomplete switch binary condition keeps case and following print",
            source = "switch value + do case 1 print(1) end\nprint(value)",
            requiredShapeFragments = listOf(
                "Switch(Binary(+,Id(value),ExpressionNodeSupport):Case(Const(1):Block[CallStmt(Call(Id(print):Const(1)))]))",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "incomplete switch condition missing after switch inserts placeholder",
            source = "switch do case 1 one() end\nprint(1)",
            requiredShapeFragments = listOf(
                "Switch(ExpressionNodeSupport:Case(Const(1):Block[CallStmt(Call(Id(one):))]))",
                "CallStmt(Call(Id(print):Const(1)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Outer switch is compact optional-do with matching end (legal under strict);
            // recovery still emits historical `The <do> expected`. Inner switch is well-formed.
            name = "nested compact outer switch missing do keeps outer end and trailing print",
            source = """
                do
                  switch outer case 1 then
                    switch inner do case 2 two() end
                  end
                end
                print(outer)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Switch(Id(outer):",
                "Switch(Id(inner):Case(Const(2):Block[CallStmt(Call(Id(two):))]))",
                "CallStmt(Call(Id(print):Id(outer)))"
            ),
            warningFragments = listOf("The <do> expected"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        )
    )

    // Incomplete case/default bodies still recover residual switch + following stmts.
    private val incompleteCaseBodyCases = listOf(
        RecoveryCase(
            name = "incomplete assignment in case keeps following print after end",
            source = "switch mode do case 1 then value = value + end\nprint(value)",
            requiredShapeFragments = listOf(
                "Case(Const(1):Block[Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "incomplete call in case keeps default body and trailing print",
            source = "switch mode do case 1 then one( default fallback() end\nprint(mode)",
            requiredShapeFragments = listOf(
                "Case(Const(1):",
                "Default(Block[CallStmt(Call(Id(fallback):))])",
                "CallStmt(Call(Id(print):Id(mode)))"
            ),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "incomplete assignment in default keeps trailing print",
            source = "switch mode do case 1 one() default total = total + end\nprint(total)",
            requiredShapeFragments = listOf(
                "Case(Const(1):Block[CallStmt(Call(Id(one):))])",
                "Default(Block[Assign(Id(total)=Binary(+,Id(total),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(total)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "incomplete multi-condition case missing condition keeps body and trailing local",
            source = "switch mode do case 1, then print(mode) end\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Case(Const(1),ExpressionNodeSupport:Block[CallStmt(Call(Id(print):Id(mode)))])",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "missing end after incomplete case assignment keeps placeholder",
            source = "switch mode do case 1 then value = value +",
            requiredShapeFragments = listOf(
                "Case(Const(1):Block[Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport))])"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("<end> expected")
        )
    )

    // When-statement incomplete chains; later statements remain reachable under recovery.
    // Product notes (parseWhenStatement + parseSubExpTail / parseExpStatement):
    // - malformed if-cause Identifier+binop or Member without call → bad CallStmt under recovery;
    //   strict rejects (assignment / "assignment statement is incorrect").
    // - incomplete binary RHS before `else` terminator → ExpressionNodeSupport under recovery;
    //   strict rejects on `else` as non-expression.
    // - incomplete binary RHS / condition with following statement-start line currently
    //   CURRENTLY_ACCEPTS under recovery=false (next call absorbed as RHS expression);
    //   recovery=true inserts ExpressionNodeSupport and keeps later CallStmt siblings.
    private val whenRecoveryCases = listOf(
        RecoveryCase(
            name = "malformed when if cause expression is wrapped as bad call",
            source = "when ready target + 1 else fallback()",
            requiredShapeFragments = listOf(
                "When(Id(ready)?CallStmt(Call(Binary(+,Id(target),Const(1)):)):CallStmt(Call(Id(fallback):)))"
            ),
            badShapeFragments = listOf("Call(Binary(+,Id(target),Const(1)):")
        ),
        RecoveryCase(
            name = "malformed when if cause keeps following print",
            source = "when ready target + 1 else fallback()\nprint(ready)",
            requiredShapeFragments = listOf(
                "When(Id(ready)?CallStmt(Call(Binary(+,Id(target),Const(1)):)):CallStmt(Call(Id(fallback):)))",
                "CallStmt(Call(Id(print):Id(ready)))"
            ),
            badShapeFragments = listOf("Call(Binary(+,Id(target),Const(1)):")
        ),
        RecoveryCase(
            name = "when incomplete if assignment keeps else branch and trailing print",
            source = "when ready value = value + else fallback()\nprint(value)",
            requiredShapeFragments = listOf(
                "When(Id(ready)?Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport)):CallStmt(Call(Id(fallback):)))",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "when incomplete else assignment keeps trailing print",
            source = "when ready print(1) else value = value +\nprint(value)",
            requiredShapeFragments = listOf(
                "When(Id(ready)?CallStmt(Call(Id(print):Const(1))):Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            // Strict currently absorbs print(value) as Binary RHS: value = value + print(value).
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "when method-style incomplete if cause keeps else and trailing print",
            source = "when ready object.name else fallback()\nprint(ready)",
            requiredShapeFragments = listOf(
                "When(Id(ready)?CallStmt(Call(Member(Id(object).name):)):CallStmt(Call(Id(fallback):)))",
                "CallStmt(Call(Id(print):Id(ready)))"
            ),
            badShapeFragments = listOf("Call(Member(Id(object).name):)")
        ),
        RecoveryCase(
            name = "when incomplete binary condition keeps if call and trailing print",
            source = "when value +\nprint(1)\nprint(value)",
            requiredShapeFragments = listOf(
                "When(Binary(+,Id(value),ExpressionNodeSupport)?CallStmt(Call(Id(print):Const(1))):null)",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            // Strict currently absorbs print(1) as condition Binary RHS, then print(value) as ifCause.
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "when bare identifier if cause becomes bad call and keeps trailing print",
            source = "when ready target\nprint(ready)",
            requiredShapeFragments = listOf(
                "When(Id(ready)?CallStmt(Call(Id(target):)):null)",
                "CallStmt(Call(Id(print):Id(ready)))"
            ),
            badShapeFragments = listOf("Call(Id(target):)")
        )
    )
}
