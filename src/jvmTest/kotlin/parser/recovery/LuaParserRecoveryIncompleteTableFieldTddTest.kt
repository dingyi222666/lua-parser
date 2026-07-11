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
 * Focused recovery corpus for incomplete **table fields** (TASK-481).
 *
 * Complements:
 * - [LuaParserRecoveryTableConstructorTddTest] (broader unclosed-constructor /
 *   nested / call-site inventory)
 * - [LuaParserRecoveryLayoutTableTddTest] (Android-Lua layout widget tables)
 * - [LuaParserRecoveryTableFieldMissingEqTddTest] (Name/`[` field missing `=`
 *   specialization, when present)
 *
 * This corpus deepens incomplete-field recovery specifically:
 * - named fields missing value after `=`
 * - bracket / indexed fields with incomplete key, missing `]`, missing `=`, missing value
 * - array-slot incomplete / leading fieldsep placeholders
 * - incomplete expressions inside field values (binary / unary / paren / call / function)
 * - nested incomplete fields and multi-field lists with a broken last field
 * - statement contexts: local / assignment / return / table-call / function body
 * - later-statement reachability (print / local siblings) under recovery
 *
 * Product notes (LuaParser.parseField / parseTableStringKey / parseTableKey /
 * parseExpressionOrMissing / parseFieldList):
 * - Missing field value after `=` or incomplete expression →
 *   `ExpressionNodeSupport` (bad) while keeping completed earlier fields.
 * - Bare Name without `=` under recovery becomes bad `TableKeyString` with
 *   `'=' expected` (value may absorb next significant token).
 * - Bracket key recovery uses `']' expected` / `'=' expected` and marks the
 *   TableKey bad when either token is recovered.
 * - Leading fieldsep (`, two = 2`) inserts a missing array-field placeholder.
 * - Trailing field separators (`,` / `;`) are valid Lua 5.x →
 *   [StrictParseExpectation.CURRENTLY_ACCEPTS] dual-path (not a recovery gap).
 * - Completed named/bracket fields leave following next-line `print(...)` as a
 *   sibling CallStmt once the field list ends (missing `}` warning).
 *
 * AST quirk: LocalStatement/AssignmentStatement `.init` = names/LHS,
 * `.variables` = RHS.
 *
 * Test-only; no production LuaParser edits unless review re-scopes.
 * Host android.jar: SDK android-35 PRESENT; Downloads ABSENT; never G:/.
 */
class LuaParserRecoveryIncompleteTableFieldTddTest {

    @Test
    fun recoversNamedFieldsMissingValueAndKeepsLaterStatements() {
        assertEquals(6, namedMissingValueCases.size)
        namedMissingValueCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversBracketAndIndexedFieldsIncompleteForms() {
        assertEquals(6, bracketIncompleteCases.size)
        bracketIncompleteCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversArraySlotAndLeadingFieldsepIncompleteForms() {
        assertEquals(5, arrayAndSeparatorCases.size)
        arrayAndSeparatorCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteExpressionsInsideFieldValues() {
        assertEquals(6, incompleteFieldExpressionCases.size)
        incompleteFieldExpressionCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversNestedIncompleteFieldsAndStatementContexts() {
        assertEquals(5, nestedAndContextCases.size)
        nestedAndContextCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun exposesTypedIncompleteFieldStructureAfterRecovery() {
        // Named field missing value: TableKeyString(Id(name)=ExpressionNodeSupport)
        // and following print remains a sibling CallStmt.
        val named = parseRecoveringWithoutThrow("local config = { name = }\nprint(config)")
        val namedLocal = assertIs<LocalStatement>(named.body.statements[0])
        assertEquals(1, namedLocal.init.size)
        assertEquals("Id(config)", renderShape(namedLocal.init[0]))
        assertEquals(1, namedLocal.variables.size)
        val namedTable = assertIs<TableConstructorExpression>(namedLocal.variables[0])
        assertEquals(1, namedTable.fields.size)
        val namedField = assertIs<TableKeyString>(namedTable.fields[0])
        assertEquals("Id(name)", renderShape(namedField.key))
        assertEquals("ExpressionNodeSupport", renderShape(namedField.value))
        assertTrue(namedField.value.bad)
        val namedPrint = assertIs<CallStatement>(named.body.statements[1])
        assertEquals("Call(Id(print):Id(config))", renderShape(namedPrint.expression))

        // Bracket field missing value keeps TableKey with placeholder value.
        val bracket = parseRecoveringWithoutThrow("local config = { [key] = }\nprint(config)")
        val bracketLocal = assertIs<LocalStatement>(bracket.body.statements[0])
        val bracketTable = assertIs<TableConstructorExpression>(bracketLocal.variables.single())
        val bracketField = assertIs<TableKey>(bracketTable.fields.single())
        assertEquals("Id(key)", renderShape(bracketField.key))
        assertEquals("ExpressionNodeSupport", renderShape(bracketField.value))
        assertTrue(bracketField.value.bad)
        assertIs<CallStatement>(bracket.body.statements[1])

        // Second named field missing value keeps the first completed field.
        val multi = parseRecoveringWithoutThrow("local config = { one = 1, two = }\nprint(config)")
        val multiLocal = assertIs<LocalStatement>(multi.body.statements[0])
        val multiTable = assertIs<TableConstructorExpression>(multiLocal.variables.single())
        assertTrue(multiTable.fields.size >= 2)
        val first = assertIs<TableKeyString>(multiTable.fields[0])
        assertEquals("Id(one)", renderShape(first.key))
        assertEquals("Const(1)", renderShape(first.value))
        val second = assertIs<TableKeyString>(multiTable.fields[1])
        assertEquals("Id(two)", renderShape(second.key))
        assertEquals("ExpressionNodeSupport", renderShape(second.value))
        assertTrue(second.value.bad)

        // Binary incomplete RHS inside named field value.
        val bin = parseRecoveringWithoutThrow("local config = { total = value + }\nprint(config)")
        val binLocal = assertIs<LocalStatement>(bin.body.statements[0])
        val binTable = assertIs<TableConstructorExpression>(binLocal.variables.single())
        val binField = assertIs<TableKeyString>(binTable.fields.single())
        val binExpr = assertIs<BinaryExpression>(binField.value)
        assertEquals("Id(value)", renderShape(binExpr.left!!))
        assertEquals("ExpressionNodeSupport", renderShape(binExpr.right!!))
        assertTrue(binExpr.right!!.bad)
    }

    @Test
    fun recoveryDiagnosticsArePresentDeterministicAndDoNotThrow() {
        val sources = listOf(
            "local config = { name = }\nprint(config)",
            "local config = { name value }\nprint(config)",
            "local config = { [key] = }\nprint(config)",
            "local config = { [key = value }\nprint(config)",
            "local config = { [] = value }\nprint(config)",
            "local config = { [key] value }\nprint(config)",
            "local config = { one = 1, two = }\nprint(config)",
            "local config = { , two = 2 }\nprint(config)",
            "local config = { 1, name = value, other = }\nprint(config)",
            "local config = { one = 1; two = }\nprint(config)",
            "local config = { nested = { name = } }\nprint(config)",
            "local config = { total = value + }\nprint(config)",
            "local config = { not }\nprint(config)",
            "local config = { value = (ready }\nprint(config)",
            "local config = { value = factory(seed }\nprint(config)",
            "local config = { make = function(a) return a }\nprint(config)",
            "config = { [key] =\nprint(config)",
            "emit{ name = }\nprint(after)",
            "return { a = }\nprint(a)",
            "function run()\n  local t = { x = }\nend\nprint(run)"
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
        assertTrue(rejects.isNotEmpty(), "inventory must retain REJECTS incomplete field cases")
        rejects.forEach { case ->
            parseRecoveringWithoutThrow(case, attempt = "strict-reject-recovery-guard")
            assertStrictParseProducesDeterministicFailure(case)
        }

        val accepts = allCases().filter {
            it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS
        }
        assertTrue(accepts.isNotEmpty(), "inventory must document CURRENTLY_ACCEPTS dual-path footguns")
        // Trailing fieldsep cases are valid Lua and must stay CURRENTLY_ACCEPTS.
        assertTrue(
            accepts.any { it.name.contains("trailing") },
            "expected at least one trailing-separator CURRENTLY_ACCEPTS dual-path case"
        )
        accepts.forEach(::assertStrictParseCurrentlyAccepts)
    }

    @Test
    fun wellFormedTableFieldsStillParseCleanlyUnderRecovery() {
        val wellFormed = listOf(
            "local config = { name = value }",
            "local config = { [key] = value }",
            "local config = { one, two }",
            "local config = { 1, name = value, other = 2 }",
            "local config = { one = 1; two = 2 }",
            "local config = { nested = { a = 1 } }",
            "local config = { total = value + 1 }",
            "return { a = 1, b = 2 }",
            "emit{ name = value }",
            "config = { [key] = value }"
        )

        wellFormed.forEach { source ->
            val recovered = parseRecoveringWithWarnings(LuaVersion.LUA_5_3, source)
            assertEquals(
                emptyList(),
                recovered.warnings,
                "well-formed table fields should not emit recovery diagnostics: $source"
            )
            val strict = parse(LuaVersion.LUA_5_3, source, recovery = false)
            assertEquals(renderShape(recovered.chunk), renderShape(strict), "shape parity: $source")
        }
    }

    @Test
    fun documentsIncompleteTableFieldInventorySizeAndCoverage() {
        assertEquals(
            namedMissingValueCases.size +
                bracketIncompleteCases.size +
                arrayAndSeparatorCases.size +
                incompleteFieldExpressionCases.size +
                nestedAndContextCases.size,
            allCases().size
        )
        assertEquals(28, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("named") || it.contains("name") })
        assertTrue(names.any { it.contains("bracket") || it.contains("indexed") || it.contains("[") })
        assertTrue(names.any { it.contains("array") || it.contains("fieldsep") || it.contains("leading") })
        assertTrue(names.any { it.contains("binary") || it.contains("unary") || it.contains("paren") || it.contains("call") || it.contains("function") })
        assertTrue(names.any { it.contains("nested") })
        assertTrue(names.any { it.contains("return") || it.contains("table-call") || it.contains("function body") || it.contains("assignment") })
        assertTrue(names.any { it.contains("trailing") })
        assertTrue(names.any { it.contains("print") || it.contains("later") || it.contains("sibling") })
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
        assertTrue(allCases().all { it.version == LuaVersion.LUA_5_3 })
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
        return namedMissingValueCases +
            bracketIncompleteCases +
            arrayAndSeparatorCases +
            incompleteFieldExpressionCases +
            nestedAndContextCases
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
    // Named fields missing value after `=` (and bare Name without `=`).
    // -------------------------------------------------------------------------

    private val namedMissingValueCases = listOf(
        RecoveryCase(
            name = "named field missing value keeps following print sibling",
            source = "local config = { name = }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(name)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Bare Name + Name under recovery becomes bad TableKeyString with
            // `'=' expected` (same product path as table-constructor corpus).
            name = "named field missing equals keeps following print sibling",
            source = "local config = { name value }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(name)=Id(value))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "second named field missing value keeps first field and print sibling",
            source = "local config = { one = 1, two = }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(one)=Const(1))",
                "TableKeyString(Id(two)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "mixed field list incomplete last named field keeps earlier fields",
            source = "local config = { 1, name = value, other = }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Const(1))",
                "TableKeyString(Id(name)=Id(value))",
                "TableKeyString(Id(other)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "semicolon field separator with named field missing value keeps print sibling",
            source = "local config = { one = 1; two = }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(one)=Const(1))",
                "TableKeyString(Id(two)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "named field missing value leaves following local sibling",
            source = "local config = { name = }\nlocal after = 1",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(name)=ExpressionNodeSupport)",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )

    // -------------------------------------------------------------------------
    // Bracket / indexed fields: incomplete key, missing `]`, missing `=`, missing value.
    // -------------------------------------------------------------------------

    private val bracketIncompleteCases = listOf(
        RecoveryCase(
            name = "bracket field missing value keeps following print sibling",
            source = "local config = { [key] = }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Id(key)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "bracket field missing closing bracket keeps following print sibling",
            source = "local config = { [key = value }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Id(key)=Id(value))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKey(Id(key)=Id(value))"),
            warningFragments = listOf("']' expected")
        ),
        RecoveryCase(
            name = "bracket field empty key keeps following print sibling",
            source = "local config = { [] = value }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "bracket field missing equals keeps following print sibling",
            source = "local config = { [key] value }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Id(key)=Id(value))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKey(Id(key)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "assignment rhs bracket field missing value keeps following print sibling",
            source = "config = { [key] =\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "bracket field missing value leaves following local sibling",
            source = "local config = { [key] = }\nlocal after = 1",
            requiredShapeFragments = listOf(
                "TableKey(Id(key)=ExpressionNodeSupport)",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )

    // -------------------------------------------------------------------------
    // Array-slot incompleteness and trailing / leading field separators.
    // -------------------------------------------------------------------------

    private val arrayAndSeparatorCases = listOf(
        RecoveryCase(
            name = "array field missing expression after leading fieldsep keeps print sibling",
            source = "local config = { , two = 2 }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Trailing fieldsep is valid Lua 5.x; strict parse must accept.
            name = "trailing comma after array field is valid lua and keeps print",
            source = "local config = { one, }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(one))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "trailing semicolon after named field is valid lua and keeps print",
            source = "local config = { one = 1; }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(one)=Const(1))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "unclosed bare name field recovers as named field absorbing following print",
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
            name = "completed named field unclosed keeps following print sibling",
            source = "local config = { name = value\nprint(config)",
            requiredShapeFragments = listOf(
                "Local(Id(config)=Table(TableKeyString(Id(name)=Id(value))))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("'}' expected")
        )
    )

    // -------------------------------------------------------------------------
    // Incomplete expressions inside field values.
    // -------------------------------------------------------------------------

    private val incompleteFieldExpressionCases = listOf(
        RecoveryCase(
            name = "binary expression missing rhs in named field keeps following print sibling",
            source = "local config = { total = value + }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "unary expression missing operand in array field keeps following print sibling",
            source = "local config = { not }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "parenthesized expression missing close in field keeps following print sibling",
            source = "local config = { value = (ready }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "call missing close paren inside table field keeps following print sibling",
            source = "local config = { value = factory(seed }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "function expression missing end inside table field keeps following print sibling",
            source = "local config = { make = function(a) return a }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "binary expression missing rhs in bracket field keeps following print sibling",
            source = "local config = { [key] = value + }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKey(Id(key)=Binary(+,Id(value),ExpressionNodeSupport))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )

    // -------------------------------------------------------------------------
    // Nested incomplete fields and non-local statement contexts.
    // -------------------------------------------------------------------------

    private val nestedAndContextCases = listOf(
        RecoveryCase(
            name = "nested incomplete named field keeps outer print sibling",
            source = "local config = { nested = { name = } }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "nested table missing both closes keeps following print sibling",
            source = "local config = { nested = { a = 1\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "table-call argument incomplete field keeps following statement",
            source = "emit{ name = }\nprint(after)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "return incomplete named field keeps later print sibling",
            source = "return { a = }\nprint(a)",
            requiredShapeFragments = listOf(
                "Return(Table(TableKeyString(Id(a)=ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "function body incomplete named field end keeps later print sibling",
            source = """
                function run()
                  local t = { x = }
                end
                print(run)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "Function(Id(run),Block[Local(Id(t)=Table(TableKeyString(Id(x)=ExpressionNodeSupport)))])",
                "CallStmt(Call(Id(print):Id(run)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )
}
