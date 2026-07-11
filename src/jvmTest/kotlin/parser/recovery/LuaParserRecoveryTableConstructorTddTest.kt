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
import parser.renderShape

/**
 * Focused recovery corpus for incomplete table constructors (TASK-189).
 *
 * Complements [LuaParserRecoveryTddTest] table-field fixtures with unclosed
 * braces, nested constructors, call/return sites, trailing separators, and
 * incomplete field expressions. Recovery must not throw; later statements stay
 * reachable where the current product keeps them reachable. Test-only until
 * review expands production scope.
 *
 * Goldens track current product behaviour (REVIEW21C rework):
 * - following statement-start tokens that are also expression starts (e.g.
 *   `print(...)`) may be absorbed as array-field values inside an unclosed `{`;
 * - bare `Name` fields without `=` recover as bad [TableKeyString] placeholders
 *   rather than implicit array keys when recovery is enabled;
 * - trailing field separators (`,` / `;`) are valid Lua and must not be treated
 *   as strict-parse failures.
 */
class LuaParserRecoveryTableConstructorTddTest {

    @Test
    fun recoversUnclosedTableConstructorsAndKeepsLaterStatements() {
        assertEquals(7, unclosedBraceCases.size)
        unclosedBraceCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteNamedIndexedAndArrayFields() {
        assertEquals(10, incompleteFieldCases.size)
        incompleteFieldCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversTrailingSeparatorsNestedTablesAndCallSites() {
        assertEquals(8, nestedCallAndSeparatorCases.size)
        nestedCallAndSeparatorCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversIncompleteExpressionsInsideTableFields() {
        assertEquals(5, incompleteFieldExpressionCases.size)
        incompleteFieldExpressionCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun documentsIncompleteTableConstructorInventorySize() {
        assertEquals(
            unclosedBraceCases.size +
                incompleteFieldCases.size +
                nestedCallAndSeparatorCases.size +
                incompleteFieldExpressionCases.size,
            allCases().size
        )
        assertEquals(30, allCases().size)

        val strictAccepts = allCases()
            .filter { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS }
            .map { it.name }
        assertEquals(
            listOf(
                "trailing comma after array field is valid lua and keeps print",
                "trailing semicolon after named field is valid lua and keeps print"
            ),
            strictAccepts
        )
    }

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

    private fun assertStrictParseProducesDeterministicFailure(case: RecoveryCase) {
        val first = assertParseFails(case.version, case.source, recovery = false)
        val second = assertParseFails(case.version, case.source, recovery = false)

        assertEquals(first::class, second::class, "${case.name} strict failure type")
        assertEquals(first.message, second.message, "${case.name} strict failure message")
    }

    private fun assertStrictParseCurrentlyAccepts(case: RecoveryCase) {
        val first = parseWithoutRecovery(case.version, case.source)
        val second = parseWithoutRecovery(case.version, case.source)

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

    private fun parseRecoveringWithWarnings(version: LuaVersion, source: String): RecoveryRun {
        val result = LuaParser(luaVersion = version, errorRecovery = true)
            .parseWithDiagnostics(source)

        return RecoveryRun(
            chunk = result.chunk,
            warnings = result.recoveryDiagnostics.map { it.message }
        )
    }

    private fun parseWithoutRecovery(version: LuaVersion, source: String): ChunkNode {
        return LuaParser(luaVersion = version, errorRecovery = false).parse(source)
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
        return unclosedBraceCases +
            incompleteFieldCases +
            nestedCallAndSeparatorCases +
            incompleteFieldExpressionCases
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

    private val unclosedBraceCases = listOf(
        RecoveryCase(
            name = "unclosed empty table at eof keeps local binding",
            source = "local config = {",
            requiredShapeFragments = listOf(
                "Local(Id(config)=Table())"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            // Following print(...) is also a valid table array-field expression, so the
            // current product absorbs it into the unclosed constructor rather than
            // leaving a sibling CallStmt. Document that shape explicitly.
            name = "unclosed empty table absorbs following print call as array field",
            source = "local config = {\nprint(config)",
            requiredShapeFragments = listOf(
                "Local(Id(config)=Table(TableKey(Const(1)=Call(Id(print):Id(config)))))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            // Statement-start tokens that are not expression starts still force a missing
            // array-field placeholder, then leave the later local as a sibling statement.
            name = "unclosed empty table keeps following local statement",
            source = "local config = {\nlocal after = 1",
            requiredShapeFragments = listOf(
                "Local(Id(config)=Table(TableKey(Const(1)=ExpressionNodeSupport)))",
                "Local(Id(after)=Const(1))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            // Bare Name without '=' recovers as a bad named field; linebreak + statement
            // start yields a missing value placeholder so print stays a sibling.
            name = "unclosed bare name field recovers as named field and keeps following print",
            source = "local config = { one\nprint(config)",
            requiredShapeFragments = listOf(
                "Local(Id(config)=Table(TableKeyString(Id(one)=ExpressionNodeSupport)))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf(
                "TableKeyString(Id(one)=ExpressionNodeSupport)",
                "ExpressionNodeSupport"
            ),
            warningFragments = listOf("'=' expected", "'}' expected")
        ),
        RecoveryCase(
            name = "unclosed table with named field keeps following print",
            source = "local config = { name = value\nprint(config)",
            requiredShapeFragments = listOf(
                "Local(Id(config)=Table(TableKeyString(Id(name)=Id(value))))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "unclosed table with bracket field keeps following print",
            source = "local config = { [key] = value\nprint(config)",
            requiredShapeFragments = listOf(
                "Local(Id(config)=Table(TableKey(Id(key)=Id(value))))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "unclosed table at return keeps table node",
            source = "return { a = 1, b = 2",
            requiredShapeFragments = listOf(
                "Return(Table(TableKeyString(Id(a)=Const(1)),TableKeyString(Id(b)=Const(2))))"
            ),
            warningFragments = listOf("'}' expected")
        )
    )

    private val incompleteFieldCases = listOf(
        RecoveryCase(
            name = "named field missing value keeps following print",
            source = "local config = { name = }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "named field missing equals keeps following print",
            source = "local config = { name value }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "bracket field missing value keeps following print",
            source = "local config = { [key] = }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "bracket field missing closing bracket keeps following print",
            source = "local config = { [key = value }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKey(Id(key)=Id(value))"),
            warningFragments = listOf("']' expected")
        ),
        RecoveryCase(
            name = "bracket field empty key keeps following print",
            source = "local config = { [] = value }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "bracket field missing equals keeps following print",
            source = "local config = { [key] value }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("TableKey(Id(key)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "second named field missing value keeps first field and print",
            source = "local config = { one = 1, two = }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(one)=Const(1))",
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
            name = "array field missing expression after open keeps following print",
            source = "local config = { , two = 2 }\nprint(config)",
            requiredShapeFragments = listOf(
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
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )

    private val nestedCallAndSeparatorCases = listOf(
        RecoveryCase(
            name = "semicolon field separator with missing value keeps following print",
            source = "local config = { one = 1; two = }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(one)=Const(1))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            // Trailing fieldsep is valid Lua 5.x; strict parse must accept.
            name = "trailing semicolon after named field is valid lua and keeps print",
            source = "local config = { one = 1; }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(one)=Const(1))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "nested table missing close keeps outer local and following print",
            source = "local config = { nested = { a = 1 }\nprint(config)",
            requiredShapeFragments = listOf(
                "TableKeyString(Id(nested)=Table(TableKeyString(Id(a)=Const(1))))",
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "nested table missing both closes keeps following print",
            source = "local config = { nested = { a = 1\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "nested incomplete named field keeps outer print",
            source = "local config = { nested = { name = } }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "table call argument missing close keeps following statement",
            source = "emit{ name = value\nprint(after)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            warningFragments = listOf("'}' expected")
        ),
        RecoveryCase(
            name = "table call argument incomplete field keeps following statement",
            source = "emit{ name = }\nprint(after)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "assignment rhs incomplete table keeps following print",
            source = "config = { [key] =\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )

    private val incompleteFieldExpressionCases = listOf(
        RecoveryCase(
            name = "binary expression missing rhs in named field keeps following print",
            source = "local config = { total = value + }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "unary expression missing operand in array field keeps following print",
            source = "local config = { not }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "parenthesized expression missing close in field keeps following print",
            source = "local config = { value = (ready }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "call missing close paren inside table field keeps following print",
            source = "local config = { value = factory(seed }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "function expression missing end inside table field keeps following print",
            source = "local config = { make = function(a) return a }\nprint(config)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(print):Id(config)))"
            ),
            warningFragments = listOf("<end> expected")
        )
    )
}
