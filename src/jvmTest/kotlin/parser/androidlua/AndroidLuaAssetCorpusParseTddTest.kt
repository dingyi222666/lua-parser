package parser.androidlua

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AbsSwitchCause
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
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
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.assertParseFails
import parser.parse
import parser.parseRecovering

private const val ANDROID_LUA_CORPUS = "/parser/tdd/androidlua/corpus/android_lua_asset_corpus.txt"

class AndroidLuaAssetCorpusParseTddTest {

    @Test
    fun corpusManifestRecordsProvenanceAndExpectedBehavior() {
        val cases = loadCorpusCases()

        assertTrue(cases.size >= 30, "Expected at least 30 Android-Lua corpus snippets")
        assertEquals(cases.size, cases.map { it.id }.distinct().size, "Corpus case ids must be unique")
        assertTrue(cases.any { it.kind == "lua" }, "Corpus must include .lua asset snippets")
        assertTrue(cases.any { it.kind == "aly" }, "Corpus must include .aly layout snippets")
        assertTrue(cases.any { it.behavior == Behavior.RECOVERY }, "Corpus should record at least one recovery case")

        cases.forEach { case ->
            assertTrue(case.asset.isNotBlank(), "${case.id} must record the source asset")
            assertTrue(case.lines.isNotBlank(), "${case.id} must record source line provenance")
            assertTrue(case.source.isNotBlank(), "${case.id} must include source text")
            assertTrue(case.minNodes > 0, "${case.id} must require meaningful AST nodes")
            assertTrue(case.expectedNodeNames.isNotEmpty(), "${case.id} must list expected AST node types")
        }
    }

    @Test
    fun parsesRepresentativeAndroidLuaAssetCorpus() {
        val failures = loadCorpusCases().mapNotNull { case ->
            runCatching {
                val chunk = when (case.behavior) {
                    Behavior.STRICT -> parse(LuaVersion.ANDROLUA_5_3, case.source)
                    Behavior.RECOVERY -> {
                        assertParseFails(LuaVersion.ANDROLUA_5_3, case.source)
                        parseRecovering(LuaVersion.ANDROLUA_5_3, case.source)
                    }
                }

                val nodeNames = collectMeaningfulNodeNames(chunk)
                val badNodes = collectBadNodeNames(chunk)

                assertTrue(
                    nodeNames.size >= case.minNodes,
                    "${case.id} produced ${nodeNames.size} meaningful AST nodes, expected at least ${case.minNodes}"
                )
                case.expectedNodeNames.forEach { expected ->
                    assertTrue(
                        expected in nodeNames,
                        "${case.id} (${case.asset} ${case.lines}) missing $expected in ${nodeNames.distinct().sorted()}"
                    )
                }
                if (case.behavior == Behavior.STRICT) {
                    assertTrue(badNodes.isEmpty(), "${case.id} strict parse produced bad AST nodes: $badNodes")
                }
            }.exceptionOrNull()?.let { failure ->
                "${case.id} from ${case.asset} (${case.behavior.name.lowercase()} ${case.lines})\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    private fun loadCorpusCases(): List<CorpusCase> {
        val text = checkNotNull(javaClass.getResourceAsStream(ANDROID_LUA_CORPUS)) {
            "Missing Android-Lua corpus resource: $ANDROID_LUA_CORPUS"
        }.bufferedReader().use { it.readText() }

        val cases = mutableListOf<CorpusCase>()
        var id: String? = null
        var inSource = false
        var metadata = linkedMapOf<String, String>()
        var source = StringBuilder()

        fun flush() {
            val caseId = id ?: return
            val behaviorText = metadata.requireValue(caseId, "behavior")
            cases += CorpusCase(
                id = caseId,
                asset = metadata.requireValue(caseId, "asset"),
                kind = metadata.requireValue(caseId, "kind"),
                lines = metadata.requireValue(caseId, "lines"),
                behavior = Behavior.valueOf(behaviorText.uppercase()),
                minNodes = metadata.requireValue(caseId, "minNodes").toInt(),
                expectedNodeNames = metadata.requireValue(caseId, "expected").split(',').map { it.trim() }
                    .filter { it.isNotEmpty() },
                source = source.toString().trimEnd() + "\n",
            )
        }

        text.lineSequence().forEach { line ->
            when {
                line.startsWith("### ") -> {
                    flush()
                    id = line.removePrefix("### ").trim()
                    inSource = false
                    metadata = linkedMapOf()
                    source = StringBuilder()
                }

                id == null -> Unit
                !inSource && line == "---" -> inSource = true
                inSource -> source.append(line).append('\n')
                line.isBlank() -> Unit
                else -> {
                    val separator = line.indexOf(':')
                    require(separator > 0) { "Malformed metadata line for $id: $line" }
                    metadata[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
                }
            }
        }
        flush()

        return cases
    }

    private fun Map<String, String>.requireValue(caseId: String, key: String): String {
        return requireNotNull(this[key]) { "Missing $key metadata for corpus case $caseId" }
    }

    private fun collectMeaningfulNodeNames(chunk: ChunkNode): List<String> {
        return collectNodes(chunk)
            .map { it::class.simpleName ?: it::class.qualifiedName ?: "UnknownNode" }
            .filterNot { it == "ChunkNode" || it == "BlockNode" }
    }

    private fun collectBadNodeNames(chunk: ChunkNode): List<String> {
        return collectNodes(chunk)
            .filter { it.bad }
            .map { it::class.simpleName ?: it::class.qualifiedName ?: "UnknownNode" }
    }

    private fun collectNodes(root: BaseASTNode): List<BaseASTNode> {
        val nodes = mutableListOf<BaseASTNode>()

        fun visit(node: BaseASTNode?) {
            if (node == null) return
            nodes += node
            when (node) {
                is ChunkNode -> visit(node.body)
                is BlockNode -> {
                    node.statements.forEach(::visit)
                    visit(node.returnStatement)
                }

                is LocalStatement -> {
                    node.init.forEach(::visit)
                    node.variables.forEach(::visit)
                }

                is AssignmentStatement -> {
                    node.variables.forEach(::visit)
                    node.init.forEach(::visit)
                }

                is ForGenericStatement -> {
                    node.variables.forEach(::visit)
                    node.iterators.forEach(::visit)
                    visit(node.body)
                }

                is ForNumericStatement -> {
                    visit(node.variable)
                    visit(node.start)
                    visit(node.end)
                    visit(node.step)
                    visit(node.body)
                }

                is CallStatement -> visit(node.expression)
                is WhileStatement -> {
                    visit(node.condition)
                    visit(node.body)
                }

                is RepeatStatement -> {
                    visit(node.body)
                    visit(node.condition)
                }

                is LabelStatement -> visit(node.identifier)
                is GotoStatement -> visit(node.identifier)
                is ReturnStatement -> node.arguments.forEach(::visit)
                is WhenStatement -> {
                    visit(node.condition)
                    visit(node.ifCause)
                    visit(node.elseCause)
                }

                is SwitchStatement -> {
                    visit(node.condition)
                    node.causes.forEach(::visit)
                }

                is CaseCause -> {
                    node.conditions.forEach(::visit)
                    visit(node.body)
                }

                is DefaultCause -> visit(node.body)
                is AbsSwitchCause -> Unit
                is IfStatement -> node.causes.forEach(::visit)
                is ElseClause -> visit(node.body)
                is ElseIfClause -> {
                    visit(node.condition)
                    visit(node.body)
                }

                is IfClause -> {
                    visit(node.condition)
                    visit(node.body)
                }

                is DoStatement -> visit(node.body)
                is TableKey -> {
                    visit(node.key)
                    visit(node.value)
                }

                is FunctionDeclaration -> {
                    visit(node.identifier)
                    node.params.forEach(::visit)
                    visit(node.body)
                }

                is LambdaDeclaration -> {
                    node.params.forEach(::visit)
                    visit(node.expression)
                }

                is BinaryExpression -> {
                    visit(node.left)
                    visit(node.right)
                }

                is UnaryExpression -> visit(node.arg)
                is CallExpression -> {
                    visit(node.base)
                    node.arguments.forEach(::visit)
                }

                is MemberExpression -> {
                    visit(node.base)
                    visit(node.identifier)
                }

                is IndexExpression -> {
                    visit(node.base)
                    visit(node.index)
                }

                is TableConstructorExpression -> node.fields.forEach(::visit)
                is ArrayConstructorExpression -> node.values.forEach(::visit)
                is Identifier, is ExpressionNode, is StatementNode -> Unit
            }
        }

        visit(root)
        return nodes
    }

    private data class CorpusCase(
        val id: String,
        val asset: String,
        val kind: String,
        val lines: String,
        val behavior: Behavior,
        val minNodes: Int,
        val expectedNodeNames: List<String>,
        val source: String,
    )

    private enum class Behavior {
        STRICT,
        RECOVERY,
    }
}
