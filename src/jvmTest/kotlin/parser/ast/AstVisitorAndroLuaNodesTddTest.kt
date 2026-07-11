package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BreakStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ContinueStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTModifier
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TASK-191: AST visitor / modifier coverage for AndroLua-only switch/when nodes.
 *
 * Encodes acceptance:
 * - Visit and modify paths for WhenStatement / SwitchStatement / CaseCause / DefaultCause
 * - Visitors do not drop children
 * - Ranges stay intact across visit/modify for structural nodes
 *
 * Test-only; verification deferred to review-owned Gradle run.
 */
class AstVisitorAndroLuaNodesTddTest {

    @Test
    fun visitorTraversesWhenStatementWithElseBranches() {
        val source = "when ready print(1) else fallback(2)"
        val chunk = parse(source)
        val whenStmt = assertIs<WhenStatement>(chunk.body.statements.single())

        val events = mutableListOf<String>()
        recordingVisitor(events).visitChunkNode(chunk, Unit)

        assertContains(events, "When")
        assertContains(events, "Id(ready)")
        assertContains(events, "CallStmt")
        assertContains(events, "Id(print)")
        assertContains(events, "Const(1)")
        assertContains(events, "Id(fallback)")
        assertContains(events, "Const(2)")

        assertSame(whenStmt, whenStmt.condition.parent)
        assertSame(whenStmt, whenStmt.ifCause.parent)
        assertSame(whenStmt, assertNotNull(whenStmt.elseCause).parent)
        assertRangeIntact(source, whenStmt, "when ready print(1) else fallback(2)")
        assertRangeIntact(source, whenStmt.condition, "ready")
    }

    @Test
    fun visitorTraversesWhenStatementWithoutElse() {
        val source = "when ready target = 1"
        val chunk = parse(source)
        val whenStmt = assertIs<WhenStatement>(chunk.body.statements.single())
        assertNull(whenStmt.elseCause)

        val events = mutableListOf<String>()
        recordingVisitor(events).visitChunkNode(chunk, Unit)

        assertContains(events, "When")
        assertContains(events, "Id(ready)")
        assertContains(events, "Assign")
        assertContains(events, "Id(target)")
        assertContains(events, "Const(1)")
        assertEquals(1, events.count { it == "When" })

        assertSame(whenStmt, whenStmt.condition.parent)
        assertSame(whenStmt, whenStmt.ifCause.parent)
        assertRangeIntact(source, whenStmt, "when ready target = 1")
    }

    @Test
    fun visitorTraversesSwitchCasesDefaultAndBodies() {
        val source = """
            switch value do
              case 1, 2 then
                print(value)
              case limit then
                result = limit
              default
                continue
            end
        """.trimIndent()
        val chunk = parse(source)
        val switchStmt = assertIs<SwitchStatement>(chunk.body.statements.single())
        assertEquals(3, switchStmt.causes.size)

        val case0 = assertIs<CaseCause>(switchStmt.causes[0])
        val case1 = assertIs<CaseCause>(switchStmt.causes[1])
        val defaultCause = assertIs<DefaultCause>(switchStmt.causes[2])

        val events = mutableListOf<String>()
        recordingVisitor(events).visitChunkNode(chunk, Unit)

        assertContains(events, "Switch")
        assertContains(events, "Case")
        assertContains(events, "Default")
        assertContains(events, "Id(value)")
        assertContains(events, "Const(1)")
        assertContains(events, "Const(2)")
        assertContains(events, "Id(limit)")
        assertContains(events, "Id(print)")
        assertContains(events, "Id(result)")
        assertContains(events, "Continue")
        assertEquals(2, events.count { it == "Case" })
        assertEquals(1, events.count { it == "Default" })

        assertSame(switchStmt, switchStmt.condition.parent)
        assertSame(switchStmt, case0.parent)
        assertSame(switchStmt, case1.parent)
        assertSame(switchStmt, defaultCause.parent)
        assertEquals(2, case0.conditions.size)
        case0.conditions.forEach { assertSame(case0, it.parent) }
        assertSame(case0, case0.body.parent)
        assertSame(case1, case1.body.parent)
        assertSame(defaultCause, defaultCause.body.parent)

        assertRangeIntact(source, switchStmt, "switch value do")
        assertRangeIntact(source, switchStmt.condition, "value")
        assertRangeIntact(source, case0.conditions[0], "1")
        assertRangeIntact(source, case0.conditions[1], "2")
        assertRangeIntact(source, case1.conditions.single(), "limit")
        assertTrue(
            rangeText(source, defaultCause).contains("continue") ||
                rangeText(source, defaultCause.body).contains("continue")
        )
    }

    @Test
    fun visitorTraversesEmptySwitchWithoutDroppingStructure() {
        val source = "switch value do end"
        val chunk = parse(source)
        val switchStmt = assertIs<SwitchStatement>(chunk.body.statements.single())
        assertTrue(switchStmt.causes.isEmpty())

        val events = mutableListOf<String>()
        recordingVisitor(events).visitChunkNode(chunk, Unit)

        assertContains(events, "Switch")
        assertContains(events, "Id(value)")
        assertEquals(0, events.count { it == "Case" })
        assertEquals(0, events.count { it == "Default" })
        assertSame(switchStmt, switchStmt.condition.parent)
        assertTrue(switchStmt.causes.isEmpty())
        assertRangeIntact(source, switchStmt, "switch value do end")
    }

    @Test
    fun visitorTraversesNestedSwitchInsideDoWithoutDroppingChildren() {
        val source = "do switch value do case 1 then continue default break end end"
        val chunk = parse(source)
        val doStmt = assertIs<DoStatement>(chunk.body.statements.single())
        val switchStmt = assertIs<SwitchStatement>(doStmt.body.statements.single())
        assertEquals(2, switchStmt.causes.size)

        val events = mutableListOf<String>()
        recordingVisitor(events).visitChunkNode(chunk, Unit)

        assertContains(events, "Do")
        assertContains(events, "Switch")
        assertContains(events, "Case")
        assertContains(events, "Default")
        assertContains(events, "Continue")
        assertContains(events, "Break")

        assertSame(doStmt, switchStmt.parent)
        assertSame(switchStmt, switchStmt.causes[0].parent)
        assertSame(switchStmt, switchStmt.causes[1].parent)
        assertIs<ContinueStatement>(assertIs<CaseCause>(switchStmt.causes[0]).body.statements.single())
        assertIs<BreakStatement>(assertIs<DefaultCause>(switchStmt.causes[1]).body.statements.single())
        assertRangeIntact(source, switchStmt, "switch value do")
    }

    @Test
    fun modifierRewritesWhenConditionAndBranchesAndRestoresParents() {
        val source = "when ready target = value else other = fallback"
        val chunk = parse(source)
        val originalWhen = assertIs<WhenStatement>(chunk.body.statements.single())
        val originalWhenRange = originalWhen.range.copy()
        val originalIfRange = originalWhen.ifCause.range.copy()
        val originalElseRange = assertNotNull(originalWhen.elseCause).range.copy()

        val modifier = object : ASTModifier<Unit> {
            override fun visitIdentifier(node: Identifier, value: Unit): Identifier {
                return when (node.name) {
                    "ready" -> rewriteId("rewrittenReady", node)
                    "target" -> rewriteId("rewrittenTarget", node)
                    "value" -> rewriteId("rewrittenValue", node)
                    "other" -> rewriteId("rewrittenOther", node)
                    "fallback" -> rewriteId("rewrittenFallback", node)
                    else -> node
                }
            }
        }

        modifier.visitChunkNode(chunk, Unit)

        val whenStmt = assertIs<WhenStatement>(chunk.body.statements.single())
        assertEquals("rewrittenReady", assertIs<Identifier>(whenStmt.condition).name)
        val ifAssign = assertIs<AssignmentStatement>(whenStmt.ifCause)
        assertEquals("rewrittenTarget", assertIs<Identifier>(ifAssign.init.single()).name)
        assertEquals("rewrittenValue", assertIs<Identifier>(ifAssign.variables.single()).name)
        val elseAssign = assertIs<AssignmentStatement>(assertNotNull(whenStmt.elseCause))
        assertEquals("rewrittenOther", assertIs<Identifier>(elseAssign.init.single()).name)
        assertEquals("rewrittenFallback", assertIs<Identifier>(elseAssign.variables.single()).name)

        assertSame(whenStmt, whenStmt.condition.parent)
        assertSame(whenStmt, whenStmt.ifCause.parent)
        assertSame(whenStmt, whenStmt.elseCause!!.parent)
        assertSame(ifAssign, ifAssign.init.single().parent)
        assertSame(ifAssign, ifAssign.variables.single().parent)
        assertSame(elseAssign, elseAssign.init.single().parent)
        assertSame(elseAssign, elseAssign.variables.single().parent)
        assertSame(chunk.body, whenStmt.parent)

        // Structural ranges must not be corrupted by rewrites of child identifiers.
        assertEquals(originalWhenRange, whenStmt.range)
        assertEquals(originalIfRange, whenStmt.ifCause.range)
        assertEquals(originalElseRange, whenStmt.elseCause!!.range)
    }

    @Test
    fun modifierRewritesSwitchConditionCasesDefaultAndRestoresParents() {
        val source = """
            switch value do
              case 1, limit then
                print(value)
              default
                result = fallback
            end
        """.trimIndent()
        val chunk = parse(source)
        val originalSwitch = assertIs<SwitchStatement>(chunk.body.statements.single())
        val originalSwitchRange = originalSwitch.range.copy()
        val originalCase = assertIs<CaseCause>(originalSwitch.causes[0])
        val originalCaseRange = originalCase.range.copy()
        val originalCaseBodyRange = originalCase.body.range.copy()
        val originalDefault = assertIs<DefaultCause>(originalSwitch.causes[1])
        val originalDefaultRange = originalDefault.range.copy()
        val originalDefaultBodyRange = originalDefault.body.range.copy()

        val modifier = object : ASTModifier<Unit> {
            override fun visitIdentifier(node: Identifier, value: Unit): Identifier {
                return when (node.name) {
                    "value" -> rewriteId("rewrittenValue", node)
                    "limit" -> rewriteId("rewrittenLimit", node)
                    "print" -> rewriteId("rewrittenPrint", node)
                    "result" -> rewriteId("rewrittenResult", node)
                    "fallback" -> rewriteId("rewrittenFallback", node)
                    else -> node
                }
            }

            override fun visitConstantNode(node: ConstantNode, value: Unit): ConstantNode {
                return if (node.constantType == ConstantNode.TYPE.INTERGER && node.rawValue == 1) {
                    ConstantNode(ConstantNode.TYPE.INTERGER, 99).also { it.range = node.range.copy() }
                } else {
                    node
                }
            }
        }

        modifier.visitChunkNode(chunk, Unit)

        val switchStmt = assertIs<SwitchStatement>(chunk.body.statements.single())
        assertEquals(2, switchStmt.causes.size)
        assertEquals("rewrittenValue", assertIs<Identifier>(switchStmt.condition).name)

        val caseCause = assertIs<CaseCause>(switchStmt.causes[0])
        assertEquals(2, caseCause.conditions.size)
        assertEquals(99, assertIs<ConstantNode>(caseCause.conditions[0]).rawValue)
        assertEquals("rewrittenLimit", assertIs<Identifier>(caseCause.conditions[1]).name)
        val caseCall = assertIs<CallStatement>(caseCause.body.statements.single())
        val caseCallExpr = assertIs<CallExpression>(caseCall.expression)
        assertEquals("rewrittenPrint", assertIs<Identifier>(caseCallExpr.base).name)
        assertEquals("rewrittenValue", assertIs<Identifier>(caseCallExpr.arguments.single()).name)

        val defaultCause = assertIs<DefaultCause>(switchStmt.causes[1])
        val defaultAssign = assertIs<AssignmentStatement>(defaultCause.body.statements.single())
        assertEquals("rewrittenResult", assertIs<Identifier>(defaultAssign.init.single()).name)
        assertEquals("rewrittenFallback", assertIs<Identifier>(defaultAssign.variables.single()).name)

        assertSame(chunk.body, switchStmt.parent)
        assertSame(switchStmt, switchStmt.condition.parent)
        assertSame(switchStmt, caseCause.parent)
        assertSame(switchStmt, defaultCause.parent)
        caseCause.conditions.forEach { assertSame(caseCause, it.parent) }
        assertSame(caseCause, caseCause.body.parent)
        assertSame(caseCause.body, caseCall.parent)
        assertSame(caseCall, caseCallExpr.parent)
        assertSame(caseCallExpr, caseCallExpr.base.parent)
        assertSame(caseCallExpr, caseCallExpr.arguments.single().parent)
        assertSame(defaultCause, defaultCause.body.parent)
        assertSame(defaultCause.body, defaultAssign.parent)
        assertSame(defaultAssign, defaultAssign.init.single().parent)
        assertSame(defaultAssign, defaultAssign.variables.single().parent)

        assertEquals(originalSwitchRange, switchStmt.range)
        assertEquals(originalCaseRange, caseCause.range)
        assertEquals(originalCaseBodyRange, caseCause.body.range)
        assertEquals(originalDefaultRange, defaultCause.range)
        assertEquals(originalDefaultBodyRange, defaultCause.body.range)
    }

    @Test
    fun modifierDoesNotDropSwitchChildrenWhenOnlyTouchingIdentifiers() {
        val source = "switch mode do case 1 then break case 2, 3 then continue default print(mode) end"
        val chunk = parse(source)
        val before = assertIs<SwitchStatement>(chunk.body.statements.single())
        assertEquals(3, before.causes.size)
        val beforeCase0Conds = assertIs<CaseCause>(before.causes[0]).conditions.size
        val beforeCase1Conds = assertIs<CaseCause>(before.causes[1]).conditions.size
        val beforeSwitchRange = before.range.copy()

        val modifier = object : ASTModifier<Unit> {
            override fun visitIdentifier(node: Identifier, value: Unit): Identifier {
                return if (node.name == "mode") rewriteId("rewrittenMode", node) else node
            }
        }
        modifier.visitChunkNode(chunk, Unit)

        val after = assertIs<SwitchStatement>(chunk.body.statements.single())
        assertEquals(3, after.causes.size)
        assertIs<CaseCause>(after.causes[0])
        assertIs<CaseCause>(after.causes[1])
        assertIs<DefaultCause>(after.causes[2])
        assertEquals(beforeCase0Conds, assertIs<CaseCause>(after.causes[0]).conditions.size)
        assertEquals(beforeCase1Conds, assertIs<CaseCause>(after.causes[1]).conditions.size)
        assertEquals(1, assertIs<CaseCause>(after.causes[0]).body.statements.size)
        assertEquals(1, assertIs<CaseCause>(after.causes[1]).body.statements.size)
        assertEquals(1, assertIs<DefaultCause>(after.causes[2]).body.statements.size)
        assertEquals("rewrittenMode", assertIs<Identifier>(after.condition).name)
        assertEquals(beforeSwitchRange, after.range)
        after.causes.forEach { assertSame(after, it.parent) }
    }

    @Test
    fun acceptDispatchesToWhenAndSwitchVisitorHooks() {
        val whenChunk = parse("when ready print(1)")
        val switchChunk = parse("switch value do case 1 then break default continue end")

        val whenHits = mutableListOf<String>()
        val switchHits = mutableListOf<String>()

        object : ASTVisitor<Unit> {
            override fun visitWhenStatement(node: WhenStatement, value: Unit) {
                whenHits += "When"
                super.visitWhenStatement(node, value)
            }

            override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) = Unit
        }.visitChunkNode(whenChunk, Unit)

        object : ASTVisitor<Unit> {
            override fun visitSwitchStatement(node: SwitchStatement, value: Unit) {
                switchHits += "Switch"
                super.visitSwitchStatement(node, value)
            }

            override fun visitCaseCause(node: CaseCause, value: Unit) {
                switchHits += "Case"
                super.visitCaseCause(node, value)
            }

            override fun visitDefaultCause(node: DefaultCause, value: Unit) {
                switchHits += "Default"
                super.visitDefaultCause(node, value)
            }

            override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) = Unit
        }.visitChunkNode(switchChunk, Unit)

        assertEquals(listOf("When"), whenHits)
        assertEquals(listOf("Switch", "Case", "Default"), switchHits)
    }

    @Test
    fun pureVisitDoesNotMutateRangesOnAndroLuaControlFlow() {
        val sources = listOf(
            "when ready print(1) else fallback(2)",
            "when ready target = 1",
            "switch value do case 1, 2 then print(value) default result = value end",
            "switch value do end",
            "do switch value do case 1 then continue default break end end"
        )

        for (source in sources) {
            val chunk = parse(source)
            val before = snapshotRanges(chunk)
            recordingVisitor(mutableListOf()).visitChunkNode(chunk, Unit)
            val after = snapshotRanges(chunk)
            assertEquals(before, after, "Visitor mutated ranges for source: $source")
        }
    }

    private fun parse(source: String): ChunkNode {
        return LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false).parse(source)
    }

    private fun rewriteId(name: String, original: Identifier): Identifier {
        return Identifier(name).also { it.range = original.range.copy() }
    }

    private fun recordingVisitor(events: MutableList<String>): ASTVisitor<Unit> {
        return object : ASTVisitor<Unit> {
            override fun visitWhenStatement(node: WhenStatement, value: Unit) {
                events += "When"
                super.visitWhenStatement(node, value)
            }

            override fun visitSwitchStatement(node: SwitchStatement, value: Unit) {
                events += "Switch"
                super.visitSwitchStatement(node, value)
            }

            override fun visitCaseCause(node: CaseCause, value: Unit) {
                events += "Case"
                super.visitCaseCause(node, value)
            }

            override fun visitDefaultCause(node: DefaultCause, value: Unit) {
                events += "Default"
                super.visitDefaultCause(node, value)
            }

            override fun visitDoStatement(node: DoStatement, value: Unit) {
                events += "Do"
                super.visitDoStatement(node, value)
            }

            override fun visitAssignmentStatement(node: AssignmentStatement, value: Unit) {
                events += "Assign"
                super.visitAssignmentStatement(node, value)
            }

            override fun visitCallStatement(node: CallStatement, value: Unit) {
                events += "CallStmt"
                super.visitCallStatement(node, value)
            }

            override fun visitBreakStatement(node: BreakStatement, value: Unit) {
                events += "Break"
            }

            override fun visitContinueStatement(node: ContinueStatement, value: Unit) {
                events += "Continue"
            }

            override fun visitIdentifier(node: Identifier, value: Unit) {
                events += "Id(${node.name})"
            }

            override fun visitConstantNode(node: ConstantNode, value: Unit) {
                events += "Const(${node.rawValue})"
            }

            override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) = Unit
        }
    }

    private fun assertRangeIntact(source: String, node: BaseASTNode, expectedSubstring: String) {
        val text = rangeText(source, node)
        assertTrue(
            text.contains(expectedSubstring) || expectedSubstring.contains(text.trim()),
            "Range text '$text' does not cover expected '$expectedSubstring' for ${node::class.simpleName}"
        )
        assertTrue(node.range.start.line >= 1)
        assertTrue(node.range.start.column >= 1)
        assertTrue(
            node.range.end.line > node.range.start.line ||
                (node.range.end.line == node.range.start.line &&
                    node.range.end.column >= node.range.start.column)
        )
    }

    private fun rangeText(source: String, node: BaseASTNode): String {
        val start = offsetOf(source, node.range.start.line, node.range.start.column)
        val end = offsetOf(source, node.range.end.line, node.range.end.column)
        return source.substring(start.coerceAtMost(source.length), end.coerceAtMost(source.length))
    }

    private fun offsetOf(source: String, line: Int, column: Int): Int {
        var currentLine = 1
        var index = 0
        while (index < source.length && currentLine < line) {
            if (source[index] == '\n') {
                currentLine++
            }
            index++
        }
        return index + (column - 1).coerceAtLeast(0)
    }

    private fun snapshotRanges(node: BaseASTNode): List<Pair<String, Range>> {
        val out = mutableListOf<Pair<String, Range>>()
        fun walk(current: BaseASTNode, path: String) {
            out += path to current.range.copy()
            when (current) {
                is ChunkNode -> walk(current.body, "$path/body")
                is io.github.dingyi222666.luaparser.parser.ast.node.BlockNode -> {
                    current.statements.forEachIndexed { i, stmt -> walk(stmt, "$path/s$i") }
                    current.returnStatement?.let { walk(it, "$path/return") }
                }
                is WhenStatement -> {
                    walk(current.condition, "$path/cond")
                    walk(current.ifCause, "$path/if")
                    current.elseCause?.let { walk(it, "$path/else") }
                }
                is SwitchStatement -> {
                    walk(current.condition, "$path/cond")
                    current.causes.forEachIndexed { i, cause -> walk(cause, "$path/c$i") }
                }
                is CaseCause -> {
                    current.conditions.forEachIndexed { i, cond -> walk(cond, "$path/cond$i") }
                    walk(current.body, "$path/body")
                }
                is DefaultCause -> walk(current.body, "$path/body")
                is DoStatement -> walk(current.body, "$path/body")
                is AssignmentStatement -> {
                    current.init.forEachIndexed { i, e -> walk(e, "$path/init$i") }
                    current.variables.forEachIndexed { i, e -> walk(e, "$path/var$i") }
                }
                is CallStatement -> walk(current.expression, "$path/expr")
                is CallExpression -> {
                    walk(current.base, "$path/base")
                    current.arguments.forEachIndexed { i, e -> walk(e, "$path/arg$i") }
                }
            }
        }
        walk(node, "root")
        return out
    }
}
