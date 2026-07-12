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
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

class LuaParserRecoveryTddTest {

    @Test
    fun recoversMissingEndThenDoAndUntilWhileKeepingStatementsReachable() {
        assertEquals(18, missingDelimiterCases.size)

        missingDelimiterCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversMalformedAssignmentsCallsFunctionsAndIncompleteLambdaForms() {
        assertEquals(15, malformedStatementCases.size)

        malformedStatementCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversLocalDeclarationsAndAssignmentsWithMissingPieces() {
        assertEquals(7, localDeclarationAndAssignmentCases.size)

        localDeclarationAndAssignmentCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversFunctionTableLiteralAndCurrentlySupportedIncompleteCallForms() {
        assertEquals(21, functionTableLiteralAndMixedCallCases.size)

        functionTableLiteralAndMixedCallCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversReturnExpressionAndLoopTailRecoveryGaps() {
        assertEquals(5, returnExpressionAndLoopTailCases.size)

        returnExpressionAndLoopTailCases.forEach(::assertSupportedRecoveryCase)
    }

    @Test
    fun recoversResourceBackedMixedValidAndInvalidAndroidLuaSnippet() {
        val source = loadRecoveryFixture("android_mixed_invalid.lua")
        val case = RecoveryCase(
            name = "resource-backed mixed Android-Lua recovery",
            version = LuaVersion.ANDROLUA_5_3,
            source = source,
            requiredShapeFragments = listOf(
                "CallStmt(Call(StringCall(Id(require):Const(\"import\")):))",
                "CallStmt(Call(StringCall(Member(Id(activity).setTitle):Const(\"Recovery\")):))",
                "CallStmt(Call(Member(Id(obj).):))",
                "CallStmt(Call(Id(print):Const(\"after member\")))",
                "When(Id(ready)?CallStmt(Call(Binary(+,Id(target),Const(1)):)):CallStmt(Call(Id(fallback):)))",
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(handleOne):))]),Default(Block[CallStmt(Call(Id(handleDefault):))]))",
                "Return(Lambda(Id(value):ExpressionNodeSupport))"
            ),
            // Structured diagnostics currently omit this fixture's missing-token warnings.
            badShapeFragments = listOf(
                "Member(Id(obj).)",
                "Call(Binary(+,Id(target),Const(1)):)",
                "ExpressionNodeSupport"
            )
        )

        assertSupportedRecoveryCase(case)
    }

    @Test
    fun documentsRequiredRecoveryInventoryAndStrictParseGaps() {
        val strictParseGaps = requiredRecoveryCases()
            .filter { it.strictParseExpectation == StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS }
            .map { it.name }

        assertEquals(66, requiredRecoveryCases().size)
        assertContentEquals(
            listOf(
                "assignment missing rhs expression should keep later print",
                "assignment missing trailing rhs after comma should keep later print"
            ),
            strictParseGaps
        )
    }

    @Test
    fun documentsRequiredRecoveryCasesAreNotProductionBlocked() {
        assertTrue(productionBlockedRequiredRecoveryCases.isEmpty())

        val functionBodyMissingParen = functionTableLiteralAndMixedCallCases.single {
            it.name == "function body missing closing paren should keep return"
        }
        assertTrue(functionBodyMissingParen.requiredShapeFragments.contains("Return(Id(a))"))
        assertTrue(functionBodyMissingParen.warningFragments.contains(") expected"))

        val mixedAndroidLuaIncompleteCall = functionTableLiteralAndMixedCallCases.single {
            it.name == "mixed Android-Lua incomplete call should keep later setContentView"
        }
        assertTrue(
            mixedAndroidLuaIncompleteCall.requiredShapeFragments.contains(
                "CallStmt(Call(Member(Id(activity).setContentView):Id(view)))"
            )
        )
        assertTrue(mixedAndroidLuaIncompleteCall.badShapeFragments.contains("Call(Member(Id(view):setText):)"))
        assertTrue(mixedAndroidLuaIncompleteCall.warningFragments.contains("')' expected"))

        val trailingCommaTopLevel = functionTableLiteralAndMixedCallCases.single {
            it.name == "top-level incomplete call trailing comma keeps later print"
        }
        assertTrue(
            trailingCommaTopLevel.requiredShapeFragments.contains(
                "CallStmt(Call(Id(print):Id(a)))"
            )
        )
        assertTrue(trailingCommaTopLevel.badShapeFragments.contains("Call(Id(foo):Id(a))"))
        assertTrue(trailingCommaTopLevel.warningFragments.contains("')' expected"))
    }

    @Test
    fun rejectsOutOfScopeRecoveryBoundariesEvenWithRecoveryEnabled() {
        assertEquals(4, intentionallyRejectedRecoveryBoundaryCases.size)

        intentionallyRejectedRecoveryBoundaryCases.forEach(::assertIntentionallyRejectedRecoveryBoundary)
    }

    private fun assertSupportedRecoveryCase(case: RecoveryCase) {
        val first = recoverTwiceAndAssertDeterministic(case)
        assertShapeFragments(case, first.chunk)
        assertBadShapeFragments(case, first.chunk)
        assertWarningFragments(case, first.warnings)
        when (case.strictParseExpectation) {
            StrictParseExpectation.REJECTS -> assertStrictParseProducesDeterministicFailure(case)
            StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS,
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

    private fun loadRecoveryFixture(name: String): String {
        val path = "/parser/tdd/recovery/$name"
        return checkNotNull(javaClass.getResourceAsStream(path)) {
            "Missing recovery fixture: $path"
        }.bufferedReader().use { it.readText() }
    }

    private fun requiredRecoveryCases(): List<RecoveryCase> {
        return missingDelimiterCases +
            malformedStatementCases +
            localDeclarationAndAssignmentCases +
            functionTableLiteralAndMixedCallCases +
            returnExpressionAndLoopTailCases +
            productionBlockedRequiredRecoveryCases
    }

    private enum class StrictParseExpectation {
        REJECTS,
        CURRENTLY_ACCEPTS_MISSING_RHS,
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
        val productionFollowUpTask: String? = null,
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

    private val missingDelimiterCases = listOf(
        RecoveryCase(
            name = "missing if then keeps body statements",
            source = "if ready local value = 1 print(value) end",
            requiredShapeFragments = listOf(
                "If(Clause(Id(ready):Block[Local(Id(value)=Const(1));CallStmt(Call(Id(print):Id(value)))])"
            ),
            warningFragments = listOf("The <then> expected")
        ),
        RecoveryCase(
            // TASK-613: AndroLua optional `then` (asset-main scaleup/scaledown).
            name = "AndroLua optional if then accepts empty then-branch before else",
            version = LuaVersion.ANDROLUA_5_3,
            source = "if actp.height<dp2px(50)\n else\n  actp.height = actp.height - 1\n end",
            requiredShapeFragments = listOf(
                "If(Clause(Binary(<,Member(Id(actp).height),Call(Id(dp2px):Const(50))):Block[]),Else(Block[Assign(Member(Id(actp).height)=Binary(-,Member(Id(actp).height),Const(1)))]))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "AndroLua optional if then accepts body without then keyword",
            version = LuaVersion.ANDROLUA_5_3,
            source = "if actp.height>actheight\n  stop=true\n else\n  actp.height = actp.height + 1\n end",
            requiredShapeFragments = listOf(
                "If(Clause(Binary(>,Member(Id(actp).height),Id(actheight)):Block[Assign(Id(stop)=Const(true))]),Else(Block[Assign(Member(Id(actp).height)=Binary(+,Member(Id(actp).height),Const(1)))]))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // TASK-614: asset-main2-editor C-style inequality (`!=`).
            name = "AndroLua C-style != inequality is strict accepted",
            version = LuaVersion.ANDROLUA_5_3,
            source = "if (ts(parent) != ts(name)) then mark() end",
            requiredShapeFragments = listOf(
                "Binary(~=,Call(Id(ts):Id(parent)),Call(Id(ts):Id(name)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            // TASK-614: asset-file C-style logical and (`&&`).
            name = "AndroLua C-style && logical and is strict accepted",
            version = LuaVersion.ANDROLUA_5_3,
            source = "if (find(uri) && code == 11 && uri ~= nil) then open() end",
            requiredShapeFragments = listOf(
                "Binary(and,"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "missing elseif then keeps elseif and else branches",
            source = "if first then one() elseif second two() else three() end",
            requiredShapeFragments = listOf(
                "ElseIf(Id(second):Block[CallStmt(Call(Id(two):))])",
                "Else(Block[CallStmt(Call(Id(three):))])"
            ),
            warningFragments = listOf("The <then> expected")
        ),
        RecoveryCase(
            name = "missing while do keeps loop body",
            source = "while ready local tick = 1 tick = tick + 1 end",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[Local(Id(tick)=Const(1));Assign(Id(tick)=Binary(+,Id(tick),Const(1)))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing numeric for do keeps loop body",
            source = "for i = 1, 3 print(i) end",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(3),null:Block[CallStmt(Call(Id(print):Id(i)))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing generic for do keeps loop body",
            source = "for key, value in pairs(items) use(key, value) end",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(key),Id(value) in Call(Id(pairs):Id(items)):Block[CallStmt(Call(Id(use):Id(key),Id(value)))])"
            ),
            warningFragments = listOf("The <do> expected")
        ),
        RecoveryCase(
            name = "missing switch do keeps case and default bodies",
            version = LuaVersion.ANDROLUA_5_3,
            source = "switch mode case 1 one() default fallback() end",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(one):))]),Default(Block[CallStmt(Call(Id(fallback):))]))"
            ),
            // Recovery still records historical missing-do diagnostic; AndroLua strict
            // accepts compact `switch exp case ... end` (TASK-610 Android-Lua assets).
            warningFragments = listOf("The <do> expected"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "missing do end at eof keeps all body statements",
            source = "do local value = 1 print(value)",
            requiredShapeFragments = listOf(
                "Do(Block[Local(Id(value)=Const(1));CallStmt(Call(Id(print):Id(value)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing while end at eof keeps loop body",
            source = "while ready do work() print(\"after\")",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[CallStmt(Call(Id(work):));CallStmt(Call(Id(print):Const(\"after\")))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing if end at eof keeps branch body",
            source = "if ready then work() print(\"after\")",
            requiredShapeFragments = listOf(
                "If(Clause(Id(ready):Block[CallStmt(Call(Id(work):));CallStmt(Call(Id(print):Const(\"after\")))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing repeat until at eof keeps body and marks condition bad",
            source = "repeat local value = 1 print(value)",
            requiredShapeFragments = listOf(
                "Repeat(Block[Local(Id(value)=Const(1));CallStmt(Call(Id(print):Id(value)))]:ExpressionNodeSupport)"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            warningFragments = listOf("'until' expected")
        ),
        RecoveryCase(
            name = "missing switch end at eof keeps default body",
            version = LuaVersion.ANDROLUA_5_3,
            source = "switch mode do case 1 one() default fallback()",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(one):))]),Default(Block[CallStmt(Call(Id(fallback):))]))"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing outer do end preserves nested and following body statements",
            source = "do do local inner = 1 end local outer = 2 print(outer)",
            requiredShapeFragments = listOf(
                "Do(Block[Do(Block[Local(Id(inner)=Const(1))]);Local(Id(outer)=Const(2));CallStmt(Call(Id(print):Id(outer)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing for end at eof keeps assignment body",
            source = "for i = 1, 2 do total = total + i",
            requiredShapeFragments = listOf(
                "ForNumeric(Id(i)=Const(1),Const(2),null:Block[Assign(Id(total)=Binary(+,Id(total),Id(i)))])"
            ),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "missing generic for in still reaches iterator call and body",
            source = "for item items do consume(item) end",
            requiredShapeFragments = listOf(
                "ForGeneric(Id(item) in Id(items):Block[CallStmt(Call(Id(consume):Id(item)))])"
            ),
            warningFragments = listOf("The <in> expected")
        ),
        RecoveryCase(
            name = "missing Lua 5.4 attribute closing bracket keeps local initializer",
            version = LuaVersion.LUA_5_4,
            source = "local value<const = 1",
            requiredShapeFragments = listOf("Local(AttrId(value<const>)=Const(1))"),
            warningFragments = listOf("'>' expected")
        ),
        RecoveryCase(
            name = "missing switch do and end still keeps first case body",
            version = LuaVersion.ANDROLUA_5_3,
            source = "switch mode case 1 one()",
            requiredShapeFragments = listOf(
                "Switch(Id(mode):Case(Const(1):Block[CallStmt(Call(Id(one):))]))"
            ),
            warningFragments = listOf("The <do> expected", "<end> expected")
        )
    )

    private val malformedStatementCases = listOf(
        RecoveryCase(
            name = "bare identifier statement becomes bad call and later call is reachable",
            source = "target\nprint(1)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(target):))",
                "CallStmt(Call(Id(print):Const(1)))"
            ),
            badShapeFragments = listOf("Call(Id(target):)")
        ),
        RecoveryCase(
            name = "binary expression statement becomes bad call and later statement is reachable",
            source = "target + 1\nprint(target)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Binary(+,Id(target),Const(1)):))",
                "CallStmt(Call(Id(print):Id(target)))"
            ),
            badShapeFragments = listOf("Call(Binary(+,Id(target),Const(1)):")
        ),
        RecoveryCase(
            name = "member expression statement becomes bad call and following call is reachable",
            source = "object.name\nprint(object)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Member(Id(object).name):))",
                "CallStmt(Call(Id(print):Id(object)))"
            ),
            badShapeFragments = listOf("Call(Member(Id(object).name):)")
        ),
        RecoveryCase(
            name = "index expression statement becomes bad call and following call is reachable",
            source = "items[i]\nprint(items)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Index(Id(items)[Id(i)]):))",
                "CallStmt(Call(Id(print):Id(items)))"
            ),
            badShapeFragments = listOf("Call(Index(Id(items)[Id(i)]):)")
        ),
        RecoveryCase(
            name = "method reference without call becomes bad call",
            source = "object:method\nprint(object)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Member(Id(object):method):))",
                "CallStmt(Call(Id(print):Id(object)))"
            ),
            badShapeFragments = listOf("Call(Member(Id(object):method):)")
        ),
        RecoveryCase(
            name = "broken dot member keeps later statement reachable",
            source = "object.\nprint(object)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Member(Id(object).):))",
                "CallStmt(Call(Id(print):Id(object)))"
            ),
            badShapeFragments = listOf("Member(Id(object).)", "Call(Member(Id(object).):)")
        ),
        RecoveryCase(
            name = "broken colon member keeps later statement reachable",
            source = "object:\nprint(object)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Member(Id(object):):))",
                "CallStmt(Call(Id(print):Id(object)))"
            ),
            badShapeFragments = listOf("Member(Id(object):)", "Call(Member(Id(object):):)")
        ),
        RecoveryCase(
            name = "broken chained member stops at the bad suffix and resumes",
            source = "root.child.\nprint(root)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Member(Member(Id(root).child).):))",
                "CallStmt(Call(Id(print):Id(root)))"
            ),
            badShapeFragments = listOf("Member(Member(Id(root).child).)")
        ),
        RecoveryCase(
            name = "malformed when if cause expression is wrapped as bad call",
            version = LuaVersion.ANDROLUA_5_3,
            source = "when ready target + 1 else fallback()",
            requiredShapeFragments = listOf(
                "When(Id(ready)?CallStmt(Call(Binary(+,Id(target),Const(1)):)):CallStmt(Call(Id(fallback):)))"
            ),
            badShapeFragments = listOf("Call(Binary(+,Id(target),Const(1)):")
        ),
        RecoveryCase(
            name = "lambda missing body marks placeholder expression bad",
            version = LuaVersion.ANDROLUA_5_3,
            source = "return lambda value ->",
            requiredShapeFragments = listOf("Return(Lambda(Id(value):ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "lambda missing greater-than still parses body expression",
            version = LuaVersion.ANDROLUA_5_3,
            source = "return lambda value - value",
            requiredShapeFragments = listOf("Return(Lambda(Id(value):Id(value)))"),
            warningFragments = listOf("'->' expected")
        ),
        RecoveryCase(
            name = "lambda missing equals greater-than still parses body expression",
            version = LuaVersion.ANDROLUA_5_3,
            source = "return lambda value = value",
            requiredShapeFragments = listOf("Return(Lambda(Id(value):Id(value)))"),
            warningFragments = listOf("'=>' expected")
        ),
        RecoveryCase(
            name = "function name missing after dot still keeps body and following call",
            source = "function module.() local after = 1 end\nprint(after)",
            requiredShapeFragments = listOf(
                "Function(Member(Id(module).),Block[Local(Id(after)=Const(1))])",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("Member(Id(module).)")
        ),
        RecoveryCase(
            name = "function name missing after colon still keeps return body",
            source = "function module:() return self end",
            requiredShapeFragments = listOf(
                "Function(Member(Id(module):),Block[Return(Id(self))])"
            ),
            badShapeFragments = listOf("Member(Id(module):)")
        ),
        RecoveryCase(
            name = "doc comment before broken member keeps comment and later call reachable",
            source = "---@type fun()\nobj.\nprint(1)",
            requiredShapeFragments = listOf(
                "Comment(doc:---@type fun())",
                "CallStmt(Call(Member(Id(obj).):))",
                "CallStmt(Call(Id(print):Const(1)))"
            ),
            badShapeFragments = listOf("Member(Id(obj).)")
        )
    )

    private val localDeclarationAndAssignmentCases = listOf(
        RecoveryCase(
            name = "local declaration missing name should keep later local",
            source = "local =\nlocal after = 1\nreturn after",
            requiredShapeFragments = listOf("Local(Id(after)=Const(1))", "Return(Id(after))"),
            badShapeFragments = listOf("Local(Id()=ExpressionNodeSupport)")
        ),
        RecoveryCase(
            name = "local declaration missing initializer expression should keep later local",
            source = "local value =\nlocal after = 1\nreturn after",
            requiredShapeFragments = listOf("Local(Id(after)=Const(1))", "Return(Id(after))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "assignment missing name after comma should keep print",
            source = "a, = 1\nprint(a)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(a)))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "assignment missing equals after varlist should keep print",
            source = "a, b\nprint(a)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(a)))"),
            badShapeFragments = listOf("Assign(Id(a),ExpressionNodeSupport=)", "Call(Id(b):)"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "assignment missing rhs expression should keep later print",
            source = "a =\nprint(a)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(a)))"),
            badShapeFragments = listOf("ExpressionNodeSupport"),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS_MISSING_RHS
        ),
        RecoveryCase(
            // After comma, call-shaped print is a valid second RHS (TASK-546 later-term policy).
            name = "assignment missing trailing rhs after comma should keep later print",
            source = "a, b = 1,\nprint(a)",
            requiredShapeFragments = listOf(
                "Assign(Id(a),Id(b)=Const(1),Call(Id(print):Id(a)))"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "assignment with empty index should keep later statement",
            source = "items[] = value\nprint(value)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(value)))"),
            badShapeFragments = listOf("Index(Id(items)[ExpressionNodeSupport])")
        )
    )

    private val functionTableLiteralAndMixedCallCases = listOf(
        RecoveryCase(
            name = "function body missing end should return declaration with body",
            source = "function broken(a) return a",
            requiredShapeFragments = listOf("Function(Id(broken),Block[Return(Id(a))])"),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            // TASK-613: loadlayout.lua OnClickListener compact body
            // `function(a)(root[v] or _G[v])(a)end` — parenthesized call statement.
            name = "anonymous function body parenthesized call statement is strict accepted",
            version = LuaVersion.ANDROLUA_5_3,
            source = "return function(a)(root[v] or _G[v])(a)end",
            requiredShapeFragments = listOf(
                "Function(null,Block[CallStmt(Call(Binary(or,Index(Id(root)[Id(v)]),Index(Id(_G)[Id(v)])):Id(a)))])"
            ),
            strictParseExpectation = StrictParseExpectation.CURRENTLY_ACCEPTS
        ),
        RecoveryCase(
            name = "local function missing name should keep following call",
            source = "local function (a) return a end\nprint(\"after\")",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Const(\"after\")))"),
            badShapeFragments = listOf("Id()")
        ),
        RecoveryCase(
            name = "function malformed parameter list should keep later print",
            source = "function module.run( return 1 end\nprint(\"after\")",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Const(\"after\")))"),
            badShapeFragments = listOf("Id()"),
            warningFragments = listOf(") expected")
        ),
        RecoveryCase(
            name = "function body missing closing paren should keep return",
            source = "function broken(a\nreturn a\nend",
            requiredShapeFragments = listOf("Return(Id(a))"),
            warningFragments = listOf(") expected")
        ),
        RecoveryCase(
            name = "mixed Android-Lua incomplete call should keep later setContentView",
            version = LuaVersion.ANDROLUA_5_3,
            source = """
                require "import"
                import "android.widget.TextView"
                local view = TextView(activity)
                view:setText(
                activity.setContentView(view)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "CallStmt(Call(StringCall(Id(require):Const(\"import\")):))",
                "CallStmt(Call(StringCall(Id(import):Const(\"android.widget.TextView\")):))",
                "CallStmt(Call(Member(Id(activity).setContentView):Id(view)))"
            ),
            badShapeFragments = listOf("Call(Member(Id(view):setText):)"),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            // TASK-551: incomplete `foo(a,` must not absorb the following call as a second arg.
            name = "top-level incomplete call trailing comma keeps later print",
            source = "foo(a,\nprint(a)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(foo):Id(a)))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("Call(Id(foo):Id(a))"),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "top-level incomplete call missing paren after arg keeps later print",
            source = "foo(a\nprint(a)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(foo):Id(a)))",
                "CallStmt(Call(Id(print):Id(a)))"
            ),
            badShapeFragments = listOf("Call(Id(foo):Id(a))"),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "top-level incomplete call trailing comma keeps later local",
            source = "foo(a,\nlocal after = 1\nprint(after)",
            requiredShapeFragments = listOf(
                "CallStmt(Call(Id(foo):Id(a)))",
                "Local(Id(after)=Const(1))",
                "CallStmt(Call(Id(print):Id(after)))"
            ),
            badShapeFragments = listOf("Call(Id(foo):Id(a))"),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "mixed Android-Lua incomplete call trailing comma keeps later setContentView",
            version = LuaVersion.ANDROLUA_5_3,
            source = """
                require "import"
                import "android.widget.TextView"
                local view = TextView(activity)
                view:setText(view,
                activity.setContentView(view)
            """.trimIndent(),
            requiredShapeFragments = listOf(
                "CallStmt(Call(StringCall(Id(require):Const(\"import\")):))",
                "CallStmt(Call(StringCall(Id(import):Const(\"android.widget.TextView\")):))",
                "CallStmt(Call(Member(Id(view):setText):Id(view)))",
                "CallStmt(Call(Member(Id(activity).setContentView):Id(view)))"
            ),
            badShapeFragments = listOf("Call(Member(Id(view):setText):Id(view))"),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "anonymous function missing end should keep function expression body",
            source = "return function(a) return a",
            requiredShapeFragments = listOf("Function(null,Block[Return(Id(a))])"),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "table string field missing value should keep following print",
            source = "local config = { name = }\nprint(config)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(config)))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "table indexed field missing value should keep following print",
            source = "local config = { [key] = }\nprint(config)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(config)))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "table indexed field missing closing bracket should keep following print",
            source = "local config = { [key = value }\nprint(config)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(config)))"),
            badShapeFragments = listOf("TableKey(Id(key)=Id(value))"),
            warningFragments = listOf("']' expected")
        ),
        RecoveryCase(
            name = "table string field missing equals should keep following print",
            source = "local config = { name value }\nprint(config)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(config)))"),
            badShapeFragments = listOf("TableKeyString(Id(name)=Id(value))"),
            warningFragments = listOf("'=' expected")
        ),
        RecoveryCase(
            name = "table field list missing second value should keep following print",
            source = "local config = { one = 1, two = }\nprint(config)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(config)))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "table indexed field empty key should keep following print",
            source = "local config = { [] = value }\nprint(config)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(config)))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "unclosed double-quoted string should keep following print",
            source = "local text = \"unterminated\nprint(text)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(text)))"),
            badShapeFragments = listOf("Const(\"unterminated)")
        ),
        RecoveryCase(
            name = "unclosed single-quoted string should keep following print",
            source = "local text = 'unterminated\nprint(text)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(text)))"),
            badShapeFragments = listOf("Const('unterminated)")
        ),
        RecoveryCase(
            name = "unclosed long string should keep following print",
            source = "local text = [[unterminated\nprint(text)",
            requiredShapeFragments = listOf("CallStmt(Call(Id(print):Id(text)))"),
            badShapeFragments = listOf("Const([[unterminated)")
        ),
        RecoveryCase(
            name = "unclosed block comment should keep following local",
            source = "--[[unterminated\nlocal after = 1\nreturn after",
            requiredShapeFragments = listOf("Local(Id(after)=Const(1))", "Return(Id(after))"),
            warningFragments = listOf("malformed token near --[[unterminated")
        )
    )

    private val productionBlockedRequiredRecoveryCases = emptyList<RecoveryCase>()

    private val returnExpressionAndLoopTailCases = listOf(
        RecoveryCase(
            name = "return binary expression missing rhs should keep return node",
            source = "return value +",
            requiredShapeFragments = listOf("Return(Binary(+,Id(value),ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "return parenthesized expression missing closing paren should keep value",
            source = "return (value",
            requiredShapeFragments = listOf("Return(Id(value))"),
            warningFragments = listOf("')' expected")
        ),
        RecoveryCase(
            name = "return unary expression missing operand should keep placeholder",
            source = "return not",
            requiredShapeFragments = listOf("Return(Unary(not,ExpressionNodeSupport))"),
            badShapeFragments = listOf("ExpressionNodeSupport")
        ),
        RecoveryCase(
            name = "nested return missing expression should keep function declaration",
            source = "return function() return",
            requiredShapeFragments = listOf("Function(null,Block[Return()])"),
            warningFragments = listOf("<end> expected")
        ),
        RecoveryCase(
            name = "loop body incomplete assignment should keep following print",
            source = "while ready do value = value + end\nprint(value)",
            requiredShapeFragments = listOf(
                "While(Id(ready):Block[Assign(Id(value)=Binary(+,Id(value),ExpressionNodeSupport))])",
                "CallStmt(Call(Id(print):Id(value)))"
            ),
            badShapeFragments = listOf("ExpressionNodeSupport")
        )
    )

    private val intentionallyRejectedRecoveryBoundaryCases = listOf(
        RecoveryRejectionCase(
            name = "top-level end is not a recoverable source chunk",
            source = "end\nprint(1)",
            version = LuaVersion.LUA_5_3,
            rationale = "normal source recovery does not discard unmatched block terminators at chunk scope"
        ),
        RecoveryRejectionCase(
            name = "top-level else is not a recoverable source chunk",
            source = "else print(1)",
            version = LuaVersion.LUA_5_3,
            rationale = "else branches must be attached to an if statement"
        ),
        RecoveryRejectionCase(
            name = "top-level AndroLua case is not a recoverable source chunk",
            source = "case 1 print(1)",
            version = LuaVersion.ANDROLUA_5_3,
            rationale = "case clauses must be attached to an AndroLua switch statement"
        ),
        RecoveryRejectionCase(
            name = "AndroLua array literal remains rejected in plain Lua 5.3",
            source = "return [1]",
            version = LuaVersion.LUA_5_3,
            rationale = "version gating is not parser recovery"
        )
    )
}
