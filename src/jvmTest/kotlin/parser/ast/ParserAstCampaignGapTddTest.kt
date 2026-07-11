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
    fun campaignLua54AttributesExposeAttributeIdentifiers() {
        val chunk = parse(LuaVersion.LUA_5_4, "local pinned<const>, handle<close> = 1, open()\nreturn pinned, handle")
        val local = assertIs<LocalStatement>(chunk.body.statements.single())

        val pinned = assertIs<AttributeIdentifier>(local.init[0])
        val handle = assertIs<AttributeIdentifier>(local.init[1])
        assertEquals("pinned", pinned.name)
        assertEquals("const", pinned.attributeName)
        assertEquals("handle", handle.name)
        assertEquals("close", handle.attributeName)
        assertTrue(pinned.isLocal)
        assertTrue(handle.isLocal)
    }

    @Test
    fun clonePreservesLocalLoopAndTableShapeForCloneSafeNodes() {
        val chunk = parse(LuaVersion.LUA_5_3, loadCampaignFixture("clone_safe_gap.lua"))
        val clone = chunk.clone()

        assertNotSame(chunk, clone)
        assertEquals(renderShape(chunk), renderShape(clone))
        assertIs<ForNumericStatement>(clone.body.statements[1])
    }

    @Test
    fun clonePreservesLocalFunctionParametersAndBodyShapeForCloneSafeNodes() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            """
            local function wrap(first, ...)
                local pack = { first, ... }
            end
            """.trimIndent()
        )
        val clone = chunk.clone()

        assertNotSame(chunk, clone)
        assertEquals(renderShape(chunk), renderShape(clone))

        val wrapper = assertIs<FunctionDeclaration>(clone.body.statements.single())
        assertTrue(wrapper.isLocal)
        assertContentEquals(listOf("first", "..."), wrapper.params.map { it.name })
        assertIs<LocalStatement>(wrapper.body!!.statements.single())
    }

    @Test
    fun cloneMutatingLocalIdentifierDoesNotRewriteOriginalTree() {
        val chunk = parse(LuaVersion.LUA_5_3, "local value = 1\nreturn value")
        val clone = chunk.clone()
        val clonedLocal = assertIs<LocalStatement>(clone.body.statements.single())

        clonedLocal.init.single().name = "changed"

        val originalLocal = assertIs<LocalStatement>(chunk.body.statements.single())
        assertEquals("value", originalLocal.init.single().name)
        assertEquals("changed", clonedLocal.init.single().name)
        assertEquals("Return(Id(value))", renderShape(chunk.body.returnStatement!!))
    }

    @Test
    fun cloneMutatingNestedBinaryOperandDoesNotRewriteOriginalTree() {
        val chunk = parse(LuaVersion.LUA_5_3, "local value = left + right\nreturn value")
        val clone = chunk.clone()
        val clonedLocal = assertIs<LocalStatement>(clone.body.statements.single())
        val clonedBinary = assertIs<BinaryExpression>(clonedLocal.variables.single())
        val clonedLeft = assertIs<Identifier>(clonedBinary.left)

        clonedLeft.name = "changed"

        val originalBinary = assertIs<BinaryExpression>(assertIs<LocalStatement>(chunk.body.statements.single()).variables.single())
        assertEquals("Id(left)", renderShape(originalBinary.left!!))
        assertEquals("Id(changed)", renderShape(clonedBinary.left!!))
    }

    @Test
    fun clonePreservesAttributeIdentifierNamesFlagsAndAttributes() {
        val chunk = parse(LuaVersion.LUA_5_4, "local pinned<const>, handle<close> = 1, open()")
        val clone = chunk.clone()
        val original = assertIs<LocalStatement>(chunk.body.statements.single())
        val cloned = assertIs<LocalStatement>(clone.body.statements.single())

        assertNotSame(original.init[0], cloned.init[0])
        assertContentEquals(listOf("pinned", "handle"), cloned.init.map { it.name })
        assertContentEquals(listOf("const", "close"), cloned.init.map { assertIs<AttributeIdentifier>(it).attributeName })
        assertTrue(cloned.init.all { assertIs<AttributeIdentifier>(it).isLocal })
    }

    @Test
    fun defaultVisitorTraversesControlFlowStatementsFromLua53Fixture() {
        val names = collectVisitedNodeNames(parse(LuaVersion.LUA_5_3, loadCampaignFixture("lua53_control_gap.lua")))

        assertTrue(names.count { it == "FunctionDeclaration" } >= 1)
        assertTrue(names.count { it == "ForGenericStatement" } >= 2)
        assertTrue("IfStatement" in names)
        assertTrue("RepeatStatement" in names)
        assertTrue("WhileStatement" in names)
        assertTrue("ReturnStatement" in names)
    }

    @Test
    fun defaultVisitorTraversesAndroLuaExtensionExpressionKinds() {
        val names = collectVisitedNodeNames(parse(LuaVersion.ANDROLUA_5_3, loadCampaignFixture("androlua_extension_gap.lua")))

        assertTrue("LambdaDeclaration" in names)
        assertTrue("ArrayConstructorExpression" in names)
        assertTrue("StringCallExpression" in names)
        assertTrue("TableCallExpression" in names)
        assertTrue("WhenStatement" in names)
        assertTrue("SwitchStatement" in names)
        assertTrue("ContinueStatement" in names)
    }

    @Test
    fun visitorSeesReturnArgumentsInsideNestedFunctionDeclarations() {
        val chunk = parse(LuaVersion.LUA_5_3, loadCampaignFixture("function_clone_gap.lua"))
        val returnShapes = mutableListOf<List<String>>()
        val visitor = object : RecordingVisitor() {
            override fun visitReturnStatement(node: ReturnStatement, value: Unit) {
                returnShapes += node.arguments.map(::renderShape)
                node.arguments.forEach { visitExpressionNode(it, value) }
            }
        }

        visitor.visitChunkNode(chunk, Unit)

        assertTrue(listOf("Id(first)", "Id(extra)", "Vararg") in returnShapes)
        assertTrue(listOf("Call(Id(wrap):Const(1),Const(2))") in returnShapes)
    }

    @Test
    fun visitorTraversesTableConstructorFieldValuesFromCampaignFixtures() {
        val constants = mutableListOf<String>()
        val visitor = object : RecordingVisitor() {
            override fun visitConstantNode(node: ConstantNode, value: Unit) {
                constants += node.rawValue.toString()
            }
        }

        visitor.visitChunkNode(parse(LuaVersion.LUA_5_3, loadCampaignFixture("lua53_control_gap.lua")), Unit)
        visitor.visitChunkNode(parse(LuaVersion.ANDROLUA_5_3, loadCampaignFixture("androlua_extension_gap.lua")), Unit)

        assertTrue(listOf("1", "2", "3").all { it in constants })
        assertTrue("\"blocked\"" in constants)
        assertTrue("\"fallback\"" in constants)
    }

    @Test
    fun ast2LuaRoundTripPreservesCloneSafeLua53FixtureShape() {
        val initial = parse(LuaVersion.LUA_5_3, loadCampaignFixture("clone_safe_gap.lua"))
        val reparsed = parse(LuaVersion.LUA_5_3, printer.asCode(initial))

        assertEquals(renderShape(initial), renderShape(reparsed))
    }

    @Test
    fun ast2LuaRoundTripPreservesAndroLuaArrayLambdaAndCompactCallShape() {
        val initial = parse(LuaVersion.ANDROLUA_5_3, loadCampaignFixture("androlua_roundtrip_gap.lua"))
        val reparsed = parse(LuaVersion.ANDROLUA_5_3, printer.asCode(initial))

        assertEquals(renderShape(initial), renderShape(reparsed))
    }

    @Test
    fun parserRejectsAndroLuaOnlyCampaignFixtureInLua53Mode() {
        assertParseFails(LuaVersion.LUA_5_3, loadCampaignFixture("androlua_extension_gap.lua"))
        assertParseFails(LuaVersion.LUA_5_3, loadCampaignFixture("androlua_roundtrip_gap.lua"))
    }

    @Test
    fun sourceRangesCoverMultilineFunctionLoopAndReturnFixtureSegments() {
        val source = loadCampaignFixture("lua53_control_gap.lua")
        val chunk = parse(LuaVersion.LUA_5_3, source)
        val tally = assertIs<FunctionDeclaration>(chunk.body.statements[2])
        val repeat = assertIs<RepeatStatement>(tally.body!!.statements[2])
        val topLoop = assertIs<ForGenericStatement>(chunk.body.statements[3])
        val finalReturn = chunk.body.returnStatement!!

        assertTrue(rangeText(source, tally).startsWith("function tally(list)"))
        assertTrue(rangeText(source, tally).contains("return sum"))
        assertTrue(rangeText(source, repeat).startsWith("repeat"))
        assertTrue(rangeText(source, repeat).contains("until sum <= 10"))
        assertTrue(rangeText(source, topLoop).contains("tally({ key, value })"))
        assertEquals("return total", rangeText(source, finalReturn).trim())
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
