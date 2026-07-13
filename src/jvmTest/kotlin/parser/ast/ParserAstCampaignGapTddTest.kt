package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.source.AST2Lua
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.parse
import parser.renderShape

class ParserAstCampaignGapTddTest {

    private val printer = AST2Lua()

    @Test
    fun campaignLua53FixtureExposesNestedControlFlowParserShape() {
        val chunk = parse(LuaVersion.LUA_5_3, loadCampaignFixture("lua53_control_gap.lua"))

        assertEquals(5, chunk.body.statements.size)
        assertIs<LocalStatement>(chunk.body.statements[0])
        assertIs<LocalStatement>(chunk.body.statements[1])
        val tally = assertIs<FunctionDeclaration>(chunk.body.statements[2])
        assertEquals("tally", assertIs<Identifier>(tally.identifier).name)
        assertIs<ForGenericStatement>(tally.body!!.statements[1])
        assertIs<RepeatStatement>(tally.body!!.statements[2])
        assertIs<ForGenericStatement>(chunk.body.statements[3])
        assertIs<WhileStatement>(chunk.body.statements[4])
        assertEquals("Return(Id(total))", renderShape(chunk.body.returnStatement!!))
    }

    @Test
    fun campaignAndroLuaFixtureExposesExtensionParserShape() {
        val chunk = parse(LuaVersion.ANDROLUA_5_3, loadCampaignFixture("androlua_extension_gap.lua"))
        val shape = renderShape(chunk)

        assertTrue(shape.contains("Lambda(Id(view),Id(index):Binary(+,Call(Member(Id(view):getId):),Id(index)))"))
        assertTrue(shape.contains("Array(Call(Id(mapper):Id(button),Const(1)),Call(Id(mapper):Id(label),Const(2)),Array(Const(3),Const(4)))"))
        assertTrue(shape.contains("When(Id(ready)?CallStmt"))
        assertTrue(shape.contains("Switch(Id(current):Case(Const(1),Const(2):"))
        assertTrue(shape.contains("Continue"))
    }

    @Test
    fun ast2LuaRoundTripPreservesCloneSafeLua53FixtureShape() {
        val initial = parse(LuaVersion.LUA_5_3, loadCampaignFixture("clone_safe_gap.lua"))
        val reparsed = parse(LuaVersion.LUA_5_3, printer.asCode(initial))

        assertEquals(renderShape(initial), renderShape(reparsed))
    }

    @Test
    fun parserRejectsAndroLuaOnlyCampaignFixtureInLua53Mode() {
        assertParseFails(LuaVersion.LUA_5_3, loadCampaignFixture("androlua_extension_gap.lua"))
        assertParseFails(LuaVersion.LUA_5_3, loadCampaignFixture("androlua_roundtrip_gap.lua"))
    }

    private fun loadCampaignFixture(name: String): String {
        val path = "/parser/tdd/campaign-parser-ast/$name"
        return checkNotNull(javaClass.getResourceAsStream(path)) {
            "Missing parser/AST campaign fixture: $path"
        }.bufferedReader().use { it.readText() }
    }

    private fun collectVisitedNodeNames(root: ChunkNode): List<String> {
        return RecordingVisitor().also { it.visitChunkNode(root, Unit) }.names
    }

    private open class RecordingVisitor : ASTVisitor<Unit> {
        val names = mutableListOf<String>()

        private fun record(node: BaseASTNode) {
            names += node::class.simpleName ?: node::class.qualifiedName ?: "UnknownNode"
        }

        override fun visitChunkNode(node: ChunkNode, value: Unit) {
            record(node)
            super<ASTVisitor>.visitChunkNode(node, value)
        }

        override fun visitBlockNode(node: BlockNode, value: Unit) {
            record(node)
            super<ASTVisitor>.visitBlockNode(node, value)
        }

        override fun visitStatementNode(node: StatementNode, value: Unit) {
            record(node)
            super<ASTVisitor>.visitStatementNode(node, value)
        }

        override fun visitExpressionNode(node: ExpressionNode, value: Unit) {
            record(node)
            super<ASTVisitor>.visitExpressionNode(node, value)
        }

        override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) {
            record(identifier)
        }
    }

    private fun rangeText(source: String, node: BaseASTNode): String {
        return source.substring(offsetOf(source, node.range.start.line, node.range.start.column), offsetOf(source, node.range.end.line, node.range.end.column))
    }

    private fun offsetOf(source: String, line: Int, column: Int): Int {
        var currentLine = 1
        var currentColumn = 1
        source.forEachIndexed { index, char ->
            if (currentLine == line && currentColumn == column) {
                return index
            }
            if (char == '\n') {
                currentLine += 1
                currentColumn = 1
            } else {
                currentColumn += 1
            }
        }
        return source.length
    }
}
