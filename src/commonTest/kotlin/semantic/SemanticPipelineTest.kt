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
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticPipelineTest {

    @Test
    fun class_table_definition_preserves_documented_fields_and_written_methods() {
        val snapshot = SemanticPipeline().analyzeSnapshot(parse("""
            ---@class Client
            ---@field version string
            ---@type Client
            local API = {}
            ---@return string
            function API.read() end
            return API
        """.trimIndent()))
        val returned = snapshot.chunk.body.returnStatement!!.arguments.single()
        val type = assertNotNull(snapshot.model.getTypeAt(returned))
        val members = snapshot.model.getMembers(type)
        assertEquals(setOf("version", "read"), members.map { it.name }.toSet())
        assertEquals("string", members.single { it.name == "version" }.type?.displayName)
        assertContains(members.single { it.name == "read" }.type!!.displayName, ": string")
    }

    @Test
    fun prepared_binding_checks_returns_using_the_current_import_context() {
        val pipeline = SemanticPipeline()
        val globals = BuiltinOverlayLoader.standaloneGlobals()
        val chunk = parse("""
            ---@return string
            local function read() return externalValue end
            return read()
        """.trimIndent())
        val prepared = pipeline.bindForGlobalFingerprint(chunk, globals)
        val imported = WorkspaceImportedSymbol("externalValue", "provider", VirtualPath.of("provider.lua"),
            ModuleType("provider"), valueType = PrimitiveType.NUMBER)
        val snapshot = pipeline.analyzeSnapshot(chunk, SemanticWorkspaceContext(
            overlayGlobals = globals,
            resolveImportedSymbol = { if (it == "externalValue") imported else null }
        ), preparedBinder = prepared)
        assertTrue(snapshot.model.getDiagnostics().any {
            it.code == "checker.function.return.typeMismatch" && it.message.contains("number")
        }, "a reused binder must still check imports against the current workspace context")
        assertTrue(snapshot.model.getDiagnostics().none { it.code == "checker.global.unresolved" })
    }

    @Test
    fun prepared_binding_from_another_chunk_is_rejected() {
        val pipeline = SemanticPipeline()
        val prepared = pipeline.bindForGlobalFingerprint(parse("local first = 1"),
            BuiltinOverlayLoader.standaloneGlobals())
        assertFailsWith<IllegalArgumentException> {
            pipeline.analyzeSnapshot(parse("local second = 2"), preparedBinder = prepared)
        }
    }

    @Test
    fun analyze_returnsSemanticAnalysisResultBackedByRealPipeline() {
        val result = SemanticPipeline().analyze(parse("local value = 1"))

        assertEquals("value", result.model.getSymbolAt(Position(1, 7))?.name)
        assertEquals(1, result.summary.diagnosticCount)
        assertEquals("checker.local.unused", result.model.getDiagnostics().single().code)
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

        val diagnostics = result.model.getDiagnostics()
        assertEquals(diagnostics.size, result.summary.diagnosticCount)
        // render is an uncalled chunk-level local function: 1 error + 1 unused INFO.
        assertEquals(1, result.summary.errorCount)
        assertEquals(1, result.summary.infoCount)
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
