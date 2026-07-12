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
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Focused recovery corpus for Android-Lua / AndroLua layout table constructors
 * (TASK-389).
 *
 * Complements [LuaParserRecoveryTableConstructorTddTest] with layout-shaped
 * fixtures (LinearLayout/TextView/ImageView/Button, loadlayout table-call and
 * paren-call, nested child tables, id/orientation/onClick fields) covering:
 * - missing outer / nested `}` braces
 * - missing field commas / separators between widget class and named fields
 * - incomplete named field values and incomplete listener function/lambda fields
 * - incomplete loadlayout / Button table-call arguments
 * - later statements remaining reachable after recovery
 *
 * Goldens track current product behaviour (aligned with
 * [LuaParser.parseTableConstructorExpression] / field-list recovery and
 * [parseExpressionOrMissing] statement-start-after-line-break policy):
 * - unclosed empty `{` + following expression-start (`print` / `loadlayout`) is
 *   absorbed as a table array field rather than a sibling CallStmt;
 * - **newline-after-`{` bare Name is NOT a footgun**: a bare Name after a line
 *   break is a valid table array-field expression start, so
 *   `{` + NL + `LinearLayout, orientation = ...` keeps LinearLayout as
 *   `TableKey(Const(1)=Id(LinearLayout))` (AndroLua loadlayout editing).
 *   [shouldRecoverStatementStartAsMissingExpression] only inserts
 *   `ExpressionNodeSupport` for keyword statement starts (`local` / `return` /
 *   ...) or call-shaped Name (`name(` / `name{` / `name"..."`). Layout fixtures
 *   that need the missing-field placeholder therefore use a following `local`
 *   (not a bare widget class Name);
 * - completed named/array fields leave following print/local as siblings when
 *   the next significant token is not a fieldsep-bound expression start that
 *   still belongs to the open constructor;
 * - nested unclosed child tables emit `'}' expected` and keep residual outer
 *   layout nodes where the product already retains them;
 * - trailing field separators (`,` / `;`) are valid Lua and must not be treated
 *   as strict-parse failures;
 * - missing field commas after a completed named field end the field list and
 *   emit `'}' expected`; residual next-line `id = ...` re-enters as Assign;
 * - missing fieldsep after a bare widget class Name recovers as a bad
 *   TableKeyString absorbing the next Name (`LinearLayout=orientation` /
 *   `TextView=text`) with `'=' expected`, not as sibling Assign of the named
 *   field value;
 * - incomplete `function` listeners without `end` absorb following statements
 *   into the function body; fixtures that need sibling print after a listener
 *   keep a well-formed `end` and only omit the outer `}`.
 *
 * Inventory probe in [documentsLayoutTableRecoveryInventorySizeAndCoverage]
 * locks the bare-Name-after-newline product shape so inventory cannot drift
 * back to the outdated ExpressionNodeSupport-first-field expectation.
 */
class LuaParserRecoveryLayoutTableTddTest {

    @Test
    fun recoversMissingOuterBracesOnLayoutTablesAndKeepsLaterStatements() {
        assertEquals(7, missingOuterBraceCases.size)
        missingOuterBraceCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingNestedChildBracesAndKeepsOuterLayout() {
        assertEquals(6, missingNestedBraceCases.size)
        missingNestedBraceCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMissingCommasAndSeparatorsInLayoutFieldLists() {
        assertEquals(7, missingCommaCases.size)
        missingCommaCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteLayoutFieldsListenersAndLoadlayoutCalls() {
        assertEquals(8, incompleteFieldAndCallCases.size)
        incompleteFieldAndCallCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun documentsLayoutTableRecoveryInventorySizeAndCoverage() {
        assertEquals(
            missingOuterBraceCases.size +
                missingNestedBraceCases.size +
                missingCommaCases.size +
                incompleteFieldAndCallCases.size,
            allCases().size
        )
        assertEquals(28, allCases().size)

        val names = allCases().map { it.name }
        assertTrue(names.any { it.contains("brace") || it.contains("unclosed") || it.contains("close") })
        assertTrue(names.any { it.contains("comma") || it.contains("separator") || it.contains("fieldsep") })
        assertTrue(names.any { it.contains("loadlayout") || it.contains("LinearLayout") || it.contains("layout") })
        assertTrue(names.any { it.contains("nested") || it.contains("child") })
        assertTrue(names.any { it.contains("later") || it.contains("following") || it.contains("trailing") })
        assertTrue(allCases().all { it.version == LuaVersion.ANDROLUA_5_3 || it.version == LuaVersion.LUA_5_3 })

        val strictAccepts = allCases()
            .filter { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS }
            .map { it.name }
        assertEquals(
            listOf(
                "trailing comma after nested TextView child is valid lua and keeps print",
                "trailing semicolon after orientation field is valid lua and keeps print"
            ),
            strictAccepts
        )

        // Explicit product probe: newline after `{` before first bare array Name.
        // Bare Name is a valid expression start (not call-shaped / keyword statement),
        // so product keeps LinearLayout as the first table field for AndroLua layouts.
        // Contrast with unclosed empty layout + following `local`, which does insert
        // ExpressionNodeSupport (see missingOuterBraceCases).
        val bareNameAfterNewline = parseRecoveringWithWarnings(
            LuaVersion.ANDROLUA_5_3,
            "local layout = {\nLinearLayout,\norientation = \"vertical\"\nprint(layout)"
        )
        val bareNameShape = renderShape(bareNameAfterNewline.chunk)
        assertTrue(
            bareNameShape.contains(
                "Local(Id(layout)=Table(TableKey(Const(1)=Id(LinearLayout)),TableKeyString(Id(orientation)=Const(\"vertical\"))))"
            ),
            "newline-after-{ bare Name should keep LinearLayout/orientation as table fields:\n$bareNameShape"
        )
        assertTrue(
            !bareNameShape.contains("Table(TableKey(Const(1)=ExpressionNodeSupport))"),
            "newline-after-{ bare Name must not invent ExpressionNodeSupport first field:\n$bareNameShape"
        )
        assertTrue(
            bareNameShape.contains("CallStmt(Call(Id(print):Id(layout)))"),
            "newline-after-{ bare Name should keep following print as sibling:\n$bareNameShape"
        )

        // Honest keyword footgun: statement-start after `{` + NL still inserts
        // ExpressionNodeSupport and leaves the later local as a sibling.
        val keywordFootgun = parseRecoveringWithWarnings(
            LuaVersion.ANDROLUA_5_3,
            "local layout = {\nlocal after = 1"
        )
        val keywordShape = renderShape(keywordFootgun.chunk)
        assertTrue(
            keywordShape.contains("Table(TableKey(Const(1)=ExpressionNodeSupport))"),
            "newline-after-{ keyword statement-start should insert ExpressionNodeSupport:\n$keywordShape"
        )
        assertTrue(
            keywordShape.contains("Local(Id(after)=Const(1))"),
            "newline-after-{ keyword statement-start should keep following local sibling:\n$keywordShape"
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
        return missingOuterBraceCases +
            missingNestedBraceCases +
            missingCommaCases +
            incompleteFieldAndCallCases
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

    // -------------------------------------------------------------------------
    // Missing outer `}` on layout tables (LinearLayout root + named props).
    // Same-line `{ LinearLayout, ...` and newline-after-`{` bare Name both keep
    // the widget class as TableKey array field; only keyword/call-shaped
    // statement starts after `{` + NL insert ExpressionNodeSupport.
    // -------------------------------------------------------------------------

    private val missingOuterBraceCases = listOf(
        RecoveryCase(
            name = "unclosed empty layout table at eof keeps local binding",
            source = "local layout = {",
            requiredShapeFragments = listOf(
                "Local(Id(layout)=Table())"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            // Following print(...) is a valid table array-field expression start, so the
            // product absorbs it into the unclosed constructor (same as generic table recovery).
            name = "unclosed empty layout absorbs following print call as array field",
            source = "local layout = {\nprint(layout)",
            requiredShapeFragments = listOf(
                "Local(Id(layout)=Table(TableKey(Const(1)=Call(Id(print):Id(layout)))))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "unclosed empty layout absorbs following loadlayout call as array field",
            source = "local layout = {\nloadlayout(layout)",
            requiredShapeFragments = listOf(
                "Local(Id(layout)=Table(TableKey(Const(1)=Call(Id(loadlayout):Id(layout)))))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            // Statement-start tokens that are not expression starts force a missing
            // array-field placeholder, then leave the later local as a sibling statement.
            name = "unclosed empty layout keeps following local statement",
            source = "local layout = {\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(Id(layout)=Table(TableKey(Const(1)=ExpressionNodeSupport)))",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "unclosed LinearLayout named fields keep following print",
            source = "local layout = { LinearLayout, orientation = \"vertical\", id = \"root\"\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=Const(\"vertical\"))",
                "TableKeyString(Id(id)=Const(\"root\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "unclosed layout at return keeps LinearLayout table node",
            source = "return { LinearLayout, orientation = \"vertical\", id = \"root\"",
            requiredShapeFragments = listOf(
                "Return(Table(TableKey(Const(1)=Id(LinearLayout)),TableKeyString(Id(orientation)=Const(\"vertical\")),TableKeyString(Id(id)=Const(\"root\"))))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "unclosed layout after completed child keeps following print",
            source = "local layout = { LinearLayout, orientation = \"vertical\", { TextView, text = \"Hello\" }\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=Const(\"vertical\"))",
                "Id(TextView)",
                "TableKeyString(Id(text)=Const(\"Hello\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        )
    )

    // -------------------------------------------------------------------------
    // Missing nested child braces inside layout tables.
    // -------------------------------------------------------------------------

    private val missingNestedBraceCases = listOf(
        RecoveryCase(
            name = "nested TextView child missing close keeps outer layout and following print",
            source = "local layout = { LinearLayout, orientation = \"vertical\", { TextView, text = \"Hello\"\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=Const(\"vertical\"))",
                "Id(TextView)",
                "TableKeyString(Id(text)=Const(\"Hello\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "nested TextView child missing both closes keeps following print",
            source = "local layout = { LinearLayout, { TextView, text = \"Hello\"\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "Id(TextView)",
                "TableKeyString(Id(text)=Const(\"Hello\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "nested ImageView child missing close keeps loadbitmap field and following print",
            source = "local layout = { LinearLayout, { ImageView, id = \"icon\", src = loadbitmap(\"icon.png\")\nprint(layout)",
            requiredShapeFragments = listOf(
                "Id(ImageView)",
                "TableKeyString(Id(id)=Const(\"icon\"))",
                "Call(Id(loadbitmap):Const(\"icon.png\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "nested incomplete named field in child keeps outer print",
            source = "local layout = { LinearLayout, { TextView, text = } }\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "Id(TextView)",
                "TableKeyString(Id(text)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "doubly nested child missing inner close keeps following print",
            source = "local layout = { LinearLayout, { LinearLayout, orientation = \"horizontal\", { TextView, text = \"nested\" }\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=Const(\"horizontal\"))",
                "Id(TextView)",
                "TableKeyString(Id(text)=Const(\"nested\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "nested child missing close inside return layout keeps table node",
            source = "return { LinearLayout, { TextView, text = \"Hello\"",
            requiredShapeFragments = listOf(
                "Return(Table(",
                "Id(LinearLayout)",
                "Id(TextView)",
                "TableKeyString(Id(text)=Const(\"Hello\"))"
            ),
            warningFragments = listOf("'}' expected")
        )
    )

    // -------------------------------------------------------------------------
    // Missing commas / field separators in layout field lists.
    // -------------------------------------------------------------------------

    private val missingCommaCases = listOf(
        RecoveryCase(
            // Bare widget class then next-line Name without fieldsep recovers as bad
            // TableKeyString(Id(LinearLayout)=Id(orientation)) with '=' expected;
            // residual `=` / string become unexpected tokens; print remains sibling.
            name = "missing comma after LinearLayout class recovers bad named field and keeps following print",
            source = "local layout = { LinearLayout\norientation = \"vertical\"\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(LinearLayout)=Id(orientation))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(LinearLayout)=Id(orientation))"),
            warningFragments = listOf("'=' expected", "'}' expected")
        ),
        RecoveryCase(
            name = "missing comma between orientation and id ends fieldlist and keeps following print",
            source = "local layout = { LinearLayout, orientation = \"vertical\"\nid = \"root\"\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=Const(\"vertical\"))",
                "Assign(Id(id)=Const(\"root\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            // Missing fieldsep before next-line print: fieldlist ends; print is sibling CallStmt.
            name = "missing fieldsep before following print ends layout fieldlist",
            source = "local layout = { LinearLayout, orientation = \"vertical\", id = \"root\"\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=Const(\"vertical\"))",
                "TableKeyString(Id(id)=Const(\"root\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "missing fieldsep after completed nested TextView child before following print",
            source = "local layout = { LinearLayout, { TextView, text = \"Hello\" }\nprint(layout)",
            requiredShapeFragments = listOf(
                "Id(TextView)",
                "TableKeyString(Id(text)=Const(\"Hello\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            // Trailing fieldsep is valid Lua 5.x; strict parse must accept.
            name = "trailing comma after nested TextView child is valid lua and keeps print",
            source = "local layout = { LinearLayout, { TextView, text = \"Hello\" }, }\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "Id(TextView)",
                "TableKeyString(Id(text)=Const(\"Hello\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "trailing semicolon after orientation field is valid lua and keeps print",
            source = "local layout = { LinearLayout; orientation = \"vertical\"; }\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=Const(\"vertical\"))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // Inside nested child: TextView then next-line Name without fieldsep
            // recovers as bad TableKeyString(Id(TextView)=Id(text)).
            name = "missing fieldsep after array widget class before named field inside child",
            source = "local layout = { LinearLayout, { TextView\ntext = \"Hello\"\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(TextView)=Id(text))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(TextView)=Id(text))"),
            warningFragments = listOf("'=' expected", "'}' expected")
        )
    )

    // -------------------------------------------------------------------------
    // Incomplete fields, listeners, and loadlayout / Button table-call sites.
    // -------------------------------------------------------------------------

    private val incompleteFieldAndCallCases = listOf(
        RecoveryCase(
            name = "layout named field missing value keeps following print",
            source = "local layout = { LinearLayout, orientation = }\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Bare Name + Name recovers as bad TableKeyString with '=' expected
            // (same product path as generic table constructor recovery).
            name = "layout named field missing equals keeps following print",
            source = "local layout = { LinearLayout, orientation value }\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=Id(value))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(orientation)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            // Incomplete function without `end` absorbs following statements into the
            // function body. Keep a well-formed `end` and only omit the outer `}` so
            // print remains a sibling after recovery.
            name = "layout onClick function with end missing outer close keeps following print",
            source = "local layout = { LinearLayout, { TextView, onClick = function(view) view.setText(\"Clicked\") end }\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "Id(TextView)",
                "TableKeyString(Id(onClick)=Function(",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "layout onClick lambda incomplete keeps following print",
            source = "local layout = { LinearLayout, { TextView, onClick = lambda view -> } }\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "Id(TextView)",
                "Lambda(Id(view):ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "loadlayout table-call argument missing close keeps following statement",
            source = "loadlayout{ LinearLayout, orientation = \"vertical\", id = \"root\"\nprint(after)",
            requiredShapeFragments = listOf(
                "TableCall(Id(loadlayout):Table(",
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=Const(\"vertical\"))",
                "TableKeyString(Id(id)=Const(\"root\"))",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "loadlayout table-call incomplete nested child keeps following statement",
            source = "loadlayout{ LinearLayout, { TextView, text = }\nprint(after)",
            requiredShapeFragments = listOf(
                "TableCall(Id(loadlayout):Table(",
                "Id(TextView)",
                "TableKeyString(Id(text)=ExpressionNodeSupport)",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "Button table-call style widget missing close keeps following print",
            source = "local layout = { LinearLayout, Button { text = \"Save\", onClick = lambda view -> save(view)\nprint(layout)",
            requiredShapeFragments = listOf(
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableCall(Id(Button):Table(",
                "TableKeyString(Id(text)=Const(\"Save\"))",
                "Lambda(Id(view):Call(Id(save):Id(view)))",
                "CallStmt(Call(Id(print):Id(layout)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "paren loadlayout with incomplete layout arg keeps following print",
            source = "local view = loadlayout({ LinearLayout, orientation = \"vertical\"\nprint(view)",
            requiredShapeFragments = listOf(
                "Call(Id(loadlayout):Table(",
                "TableKey(Const(1)=Id(LinearLayout))",
                "TableKeyString(Id(orientation)=Const(\"vertical\"))",
                "CallStmt(Call(Id(print):Id(view)))"
            ),
            warningFragments = listOf("'}' expected", "')' expected")
        )
    )
}
