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
 * Focused recovery corpus for table fields that are missing `=` (TASK-479).
 *
 * Complements [LuaParserRecoveryTableConstructorTddTest] and
 * [LuaParserRecoveryLayoutTableTddTest] with a dedicated missing-equals matrix:
 * - named `Name value` fields (no `=`) recovering as bad TableKeyString
 * - bracket `[key] value` fields (no `=`) recovering as bad TableKey
 * - multi-field / nested / call-site residual shapes
 * - later-statement reachability after recovery
 * - strict-parse rejection vs recovery dual-path
 *
 * Product goldens (aligned with [LuaParser.parseField] / parseTableStringKey /
 * parseTableKey and WAVE recovery matrix):
 * - recovery Name path: when peek-after-Name is not `,` / `;` / `}` / array-start,
 *   the parser forces [parseTableStringKey], [recoverToken] emits `'=' expected`,
 *   marks the field bad, and takes the next expression as the field value
 *   → `TableKeyString(Id(name)=Id(value))` (or other value expression);
 * - recovery bracket path: missing `=` after `]` marks TableKey bad and still
 *   parses the following expression as the value
 *   → `TableKey(Id(key)=Id(value))` + `'=' expected`;
 * - completed earlier fields stay reachable; following print/local/return is
 *   kept as a sibling when the field list ends (closed `}` or fieldsep-less end);
 * - unclosed bare Name without `=` may absorb a following expression-start
 *   statement as the field value (documented absorption shape);
 * - well-formed named/bracket fields must parse cleanly with no recovery
 *   diagnostics under both recovery and strict modes.
 *
 * Test-only; no production LuaParser edits. Verification deferred to TASK-043.
 */
class LuaParserRecoveryTableFieldMissingEqTddTest {

    @Test
    fun recoversNamedFieldsMissingEqualsAndKeepsLaterStatements() {
        assertEquals(8, namedMissingEqCases.size)
        namedMissingEqCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversBracketFieldsMissingEqualsAndKeepsLaterStatements() {
        assertEquals(5, bracketMissingEqCases.size)
        bracketMissingEqCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMultiFieldNestedAndCallSiteMissingEquals() {
        assertEquals(8, multiNestedCallMissingEqCases.size)
        multiNestedCallMissingEqCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversUnclosedAndEofMissingEqualsAbsorptionShapes() {
        assertEquals(4, unclosedAbsorptionCases.size)
        unclosedAbsorptionCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun exposesTypedNamedAndBracketMissingEqStructureAfterRecovery() {
        val namedChunk = parseRecoveringWithoutThrow(
            "local config = { name value }\nprint(config)"
        )
        val namedLocal = assertIs<LocalStatement>(namedChunk.body.statements[0])
        val namedTable = assertIs<TableConstructorExpression>(namedLocal.variables.single())
        val namedField = assertIs<TableKeyString>(namedTable.fields.single())
        assertTrue(namedField.bad)
        assertEquals("Id(name)", renderShape(namedField.key))
        assertEquals("Id(value)", renderShape(namedField.value))
        assertIs<CallStatement>(namedChunk.body.statements[1])

        val bracketChunk = parseRecoveringWithoutThrow(
            "local config = { [key] value }\nprint(config)"
        )
        val bracketLocal = assertIs<LocalStatement>(bracketChunk.body.statements[0])
        val bracketTable = assertIs<TableConstructorExpression>(bracketLocal.variables.single())
        val bracketField = assertIs<TableKey>(bracketTable.fields.single())
        assertTrue(bracketField.bad)
        assertEquals("Id(key)", renderShape(bracketField.key))
        assertEquals("Id(value)", renderShape(bracketField.value))
        assertIs<CallStatement>(bracketChunk.body.statements[1])
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "local config = { name value }\nprint(config)",
            "local config = { [key] value }\nprint(config)",
            "local config = { one = 1, two three }\nprint(config)",
            "local config = { nested = { name value } }\nprint(config)",
            "emit{ name value }\nprint(after)",
            "local config = { name\nprint(config)"
        )

        sources.forEach { source ->
            val first = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            val second = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)

            assertEquals(renderShape(first.chunk), renderShape(second.chunk), "shape deterministic: $source")
            assertEquals(first.warnings, second.warnings, "warnings deterministic: $source")
            assertTrue(
                first.warnings.any { it.contains("'=' expected") },
                "missing-eq recovery should emit '=' expected for: $source; actual: ${first.warnings}"
            )
        }
    }

    @Test
    fun strictParseRejectsMissingEqualsWhileRecoveryKeepsLaterStatements() {
        val rejects = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.REJECTS
        }
        assertTrue(rejects.isNotEmpty())
        rejects.forEach { case ->
            parseRecoveringWithoutThrow(case, attempt = "strict-reject-recovery-guard")
            assertStrictParseProducesDeterministicFailure(case)
        }

        // Dual-path inventory: every REJECTS case must still recover without throw.
        assertTrue(rejects.all { case ->
            runCatching { parseRecoveringWithWarnings(case.version, case.source) }.isSuccess
        })
    }

    @Test
    fun wellFormedNamedAndBracketFieldsStillParseCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "local config = { name = value }",
            "local config = { name = value }\nprint(config)",
            "local config = { [key] = value }",
            "local config = { one = 1, two = 2 }",
            "local config = { nested = { name = value } }",
            "emit{ name = value }\nprint(after)",
            "return { a = 1, b = 2 }"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed table fields should not emit recovery diagnostics: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict))
        }
    }

    @Test
    fun documentsTableFieldMissingEqInventorySizeAndCoverage() {
        assertEquals(
            namedMissingEqCases.size +
                bracketMissingEqCases.size +
                multiNestedCallMissingEqCases.size +
                unclosedAbsorptionCases.size,
            allCases().size
        )
        assertEquals(25, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("named") && it.contains("missing equals") })
        assertTrue(names.any { it.contains("bracket") && it.contains("missing equals") })
        assertTrue(names.any { it.contains("nested") || it.contains("call") })
        assertTrue(names.any { it.contains("unclosed") || it.contains("absorb") })
        assertTrue(
            allCases().all {
                it.strictParseExpectation == StrictParseExpectation.REJECTS ||
                    it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
            }
        )
        assertTrue(
            allCases().count { it.strictParseExpectation == StrictParseExpectation.REJECTS } >= 20
        )
        assertTrue(
            allCases().all { case ->
                case.warningFragments.isEmpty() ||
                    case.warningFragments.any { it.contains("'=' expected") || it.contains("'}' expected") }
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
        return namedMissingEqCases +
            bracketMissingEqCases +
            multiNestedCallMissingEqCases +
            unclosedAbsorptionCases
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

    // -------------------------------------------------------------------------
    // Named field missing `=` (Name value → bad TableKeyString).
    // -------------------------------------------------------------------------

    private val namedMissingEqCases = listOf(
        RecoveryCase(
            name = "named field missing equals keeps following print",
            source = "local config = { name value }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(name)=Id(value))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "named field missing equals with nil value keeps following print",
            source = "local config = { name nil }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(name)=Const(nil))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Const(nil))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "named field missing equals with number value keeps following print",
            source = "local config = { count 42 }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(count)=Const(42))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(count)=Const(42))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "named field missing equals with true value keeps following print",
            source = "local config = { ready true }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(ready)=Const(true))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(ready)=Const(true))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "named field missing equals with call value keeps following print",
            source = "local config = { make factory(seed) }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(make)=Call(Id(factory):Id(seed)))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(make)=Call(Id(factory):Id(seed)))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "named field missing equals with unary value keeps following print",
            source = "local config = { flag not ready }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(flag)=Unary(not,Id(ready)))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(flag)=Unary(not,Id(ready)))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "named field missing equals keeps following local",
            source = "local config = { name value }\nlocal after = 1",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(name)=Id(value))",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "named field missing equals keeps following return",
            source = "local config = { name value }\nreturn config",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(name)=Id(value))",
                "Return(Id(config))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected")
        )
    )

    // -------------------------------------------------------------------------
    // Bracket field missing `=` ([key] value → bad TableKey).
    // -------------------------------------------------------------------------

    private val bracketMissingEqCases = listOf(
        RecoveryCase(
            name = "bracket field missing equals keeps following print",
            source = "local config = { [key] value }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Id(key)=Id(value))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKey(Id(key)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "bracket field missing equals with string key keeps following print",
            source = "local config = { [\"key\"] value }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Const(\"key\")=Id(value))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKey(Const(\"key\")=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "bracket field missing equals with number value keeps following print",
            source = "local config = { [key] 7 }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Id(key)=Const(7))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKey(Id(key)=Const(7))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "bracket field missing equals keeps following local",
            source = "local config = { [key] value }\nlocal after = 1",
            requiredShapeFragments = listOf(
                "TableKey(Id(key)=Id(value))",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("TableKey(Id(key)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "bracket field missing equals at return keeps table node",
            source = "return { [key] value }",
            requiredShapeFragments = listOf(
                "Return(Table(TableKey(Id(key)=Id(value))))"
            ),
            badShapeFragments = listOf("TableKey(Id(key)=Id(value))"),
            warningFragments = listOf("'=' expected")
        )
    )

    // -------------------------------------------------------------------------
    // Multi-field, nested, assignment RHS, and table-call missing-eq forms.
    // -------------------------------------------------------------------------

    private val multiNestedCallMissingEqCases = listOf(
        RecoveryCase(
            name = "second named field missing equals keeps first field and following print",
            source = "local config = { one = 1, two three }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(one)=Const(1))",
                "TableKeyString(Id(two)=Id(three))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(two)=Id(three))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "first named field missing equals keeps later named field and print",
            source = "local config = { one two, three = 3 }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(one)=Id(two))",
                "TableKeyString(Id(three)=Const(3))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(one)=Id(two))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "mixed array then named field missing equals keeps both and print",
            source = "local config = { 1, name value }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Const(1))",
                "TableKeyString(Id(name)=Id(value))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "nested table named field missing equals keeps outer print",
            source = "local config = { nested = { name value } }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(nested)=Table(TableKeyString(Id(name)=Id(value))))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "nested bracket field missing equals keeps outer print",
            source = "local config = { nested = { [key] value } }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Id(key)=Id(value))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKey(Id(key)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "table call argument named field missing equals keeps following statement",
            source = "emit{ name value }\nprint(after)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(name)=Id(value))",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "assignment rhs table named field missing equals keeps following print",
            source = "config = { name value }\nprint(config)",
            requiredShapeFragments = listOf(
                "Assign(Id(config)=Table(TableKeyString(Id(name)=Id(value))))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "semicolon separator then named field missing equals keeps first field and print",
            source = "local config = { one = 1; two three }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(one)=Const(1))",
                "TableKeyString(Id(two)=Id(three))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(two)=Id(three))"),
            warningFragments = listOf("'=' expected")
        )
    )

    // -------------------------------------------------------------------------
    // Unclosed / absorption shapes for missing-eq bare Name (product footguns).
    // -------------------------------------------------------------------------

    private val unclosedAbsorptionCases = listOf(
        RecoveryCase(
            // Bare Name without '=' recovers as a bad named field. Failed recoverToken('=')
            // leaves the lexer on the next significant token, so a following next-line
            // expression start is absorbed as the field value (not a sibling CallStmt).
            name = "unclosed named field missing equals absorbs following print as value",
            source = "local config = { one\nprint(config)",
            requiredShapeFragments = listOf(
                "Local(Id(config)=Table(TableKeyString(Id(one)=Call(Id(print):Id(config)))))"
            ),
            badShapeFragments = listOf(
                "TableKeyString(Id(one)=Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("'=' expected", "'}' expected")
        ),
        RecoveryCase(
            // Statement-start that is not an expression start forces a missing value
            // placeholder after the recovered named field, then leaves later local.
            name = "unclosed named field missing equals keeps following local as sibling",
            source = "local config = { one\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(one)="),
            warningFragments = listOf("'=' expected", "'}' expected")
        ),
        RecoveryCase(
            name = "unclosed bracket field missing equals keeps following print",
            source = "local config = { [key] value\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Id(key)=Id(value))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKey(Id(key)=Id(value))"),
            warningFragments = listOf("'=' expected", "'}' expected")
        ),
        RecoveryCase(
            name = "named field missing equals at eof still recovers bad field",
            source = "local config = { name value",
            requiredShapeFragments = listOf(
                "Local(Id(config)=Table(TableKeyString(Id(name)=Id(value))))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected", "'}' expected")
        )
    )
}
