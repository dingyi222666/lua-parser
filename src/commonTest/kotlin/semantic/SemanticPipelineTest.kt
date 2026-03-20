package semantic

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticPipelineTest {

    @Test
    fun analyze_returnsSemanticAnalysisResultBackedByRealPipeline() {
        val result = SemanticPipeline().analyze(parse("local value = 1"))

        assertEquals("value", result.model.getSymbolAt(Position(1, 7))?.name)
        assertEquals(0, result.summary.diagnosticCount)
    }

    @Test
    fun pipeline_runs_comment_bind_type_check_model_order_for_docDrivenCase() {
        val snapshot = SemanticPipeline().analyzeSnapshot(parse(
            """
            ---@alias Name string
            ---@class User
            ---@field id integer
            ---@method User:getName(): Name
            ---@type User
            local current = {}
            local label = current:getName()
            """.trimIndent()
        ))
        val currentStatement = snapshot.chunk.body.statements.filterIsInstance<LocalStatement>().first()

        assertNotNull(snapshot.comments.getAttachment(currentStatement))
        assertTrue(snapshot.binder.declarationIndex.declarations.any { it.name == "Name" && it.declaredType != null })
        assertTrue(snapshot.binder.declarationIndex.declarations.any { it.name == "User" && it.declaredType != null })
        assertEquals("Name", snapshot.model.getTypeAt(lastIdentifier(snapshot.chunk, "label"))?.displayName)
    }

    @Test
    fun pipeline_summary_matches_model_diagnostics() {
        val result = SemanticPipeline().analyze(parse(
            """
            ---@return string
            local function render()
                return 1
            end
            """.trimIndent()
        ))

        assertEquals(result.model.getDiagnostics().size, result.summary.diagnosticCount)
        assertEquals(result.model.getDiagnostics().size, result.summary.errorCount)
    }

    @Test
    fun malformedInlineTypeDoesNotBreakNeighboringValidAnalysis() {
        val chunk = parse(
            """
            ---@type table<string, number> trailing prose
            local broken = {}

            ---@type string
            local valid = "ok"
            local copy = valid
            """.trimIndent()
        )
        val result = SemanticPipeline().analyze(chunk)

        assertEquals("string", result.model.getTypeAt(lastIdentifier(chunk, "copy"))?.displayName)
        assertEquals("string", result.model.getTypeAt(lastIdentifier(chunk, "valid"))?.displayName)
    }

    @Test
    fun malformedOverloadDoesNotCrashResolutionWhenValidDeclarationsExist() {
        val chunk = parse(
            """
            ---@overload (value: string: number
            ---@param value number
            ---@return number
            local function normalize(value)
                return value
            end

            local ok = normalize(1)
            """.trimIndent()
        )
        val result = SemanticPipeline().analyze(chunk)

        assertEquals("number", result.model.getTypeAt(lastIdentifier(chunk, "ok"))?.displayName)
        assertTrue(result.model.getDiagnostics().none { it.message.contains("Exception") })
    }

    private fun parse(source: String): ChunkNode = LuaParser().parse(source)

    private fun lastIdentifier(chunk: ChunkNode, name: String): Identifier {
        return buildList {
            visit(chunk) { node ->
                if (node is Identifier && node.name == name) {
                    add(node)
                }
            }
        }.last()
    }

    private fun visit(node: BaseASTNode, block: (BaseASTNode) -> Unit) {
        block(node)
        when (node) {
            is ChunkNode -> visit(node.body, block)
            is BlockNode -> {
                node.statements.forEach { visit(it, block) }
                node.returnStatement?.let { visit(it, block) }
            }
            is LocalStatement -> {
                node.init.forEach { visit(it, block) }
                node.variables.forEach { visit(it, block) }
            }
            is ReturnStatement -> node.arguments.forEach { visit(it, block) }
            is AssignmentStatement -> {
                node.init.forEach { visit(it, block) }
                node.variables.forEach { visit(it, block) }
            }
            is FunctionDeclaration -> {
                node.identifier?.let { visit(it, block) }
                node.params.forEach { visit(it, block) }
                node.body?.let { visit(it, block) }
            }
            is MemberExpression -> {
                visit(node.base, block)
                visit(node.identifier, block)
            }
            is CallExpression -> {
                visit(node.base, block)
                node.arguments.forEach { visit(it, block) }
            }
            is BinaryExpression -> {
                node.left?.let { visit(it, block) }
                node.right?.let { visit(it, block) }
            }
            is UnaryExpression -> visit(node.arg, block)
            else -> Unit
        }
    }
}
