package semantic

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticAnalyzer
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.model.SemanticAnalysisResult
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REVIEW19 / TASK-232 corpus: public SemanticPipeline facade compatibility.
 *
 * Locks the public entrypoints that external callers / JVM LSP layers depend on:
 * - [SemanticPipeline] default construction and [SemanticPipeline.analyze]
 * - analyze result model completion facade ([SemanticModel.getCompletionsAt])
 * - deprecated [SemanticAnalyzer] still constructs and adapts pipeline output
 *
 * Test-only; product edits are out of scope for this task.
 */
@Suppress("DEPRECATION")
class SemanticPipelinePublicFacadeTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // SemanticPipeline public analyze facade
    // -------------------------------------------------------------------------

    @Test
    fun defaultConstructedPipelineAnalyzeReturnsPublicResultModel() {
        val chunk = parser.parse(
            """
            local value = 1
            return value
            """.trimIndent()
        )

        val result: SemanticAnalysisResult = pipeline.analyze(chunk)
        val model: SemanticModel = result.model

        assertNotNull(model)
        assertEquals(result.model.getDiagnostics().size, result.summary.diagnosticCount)
        assertEquals("value", model.getSymbolAt(Position(1, 7))?.name)
    }

    @Test
    fun analyzeFacadeIsCallableRepeatedlyOnSamePipelineInstance() {
        val first = pipeline.analyze(parser.parse("local a = 1"))
        val second = pipeline.analyze(parser.parse("local b = 2"))

        assertEquals("a", first.model.getSymbolAt(Position(1, 7))?.name)
        assertEquals("b", second.model.getSymbolAt(Position(1, 7))?.name)
        assertEquals(0, first.summary.diagnosticCount)
        assertEquals(0, second.summary.diagnosticCount)
    }

    @Test
    fun analyzeFacadeExposesScopeAndDiagnosticsSurfaces() {
        val result = pipeline.analyze(
            parser.parse(
                """
                local outer = 1
                do
                    local inner = outer
                end
                """.trimIndent()
            )
        )

        val scope = assertNotNull(result.model.getScopeAt(Position(3, 15)))
        assertTrue(scope.symbols.any { it.name == "inner" })
        assertTrue(scope.symbols.any { it.name == "outer" })
        assertNotNull(result.model.getDiagnostics())
        assertEquals(result.model.getDiagnostics().size, result.summary.diagnosticCount)
    }

    // -------------------------------------------------------------------------
    // Completion facade via public SemanticPipeline.analyze model
    // -------------------------------------------------------------------------

    @Test
    fun analyzeResultModelExposesLexicalCompletionsFacade() {
        val result = pipeline.analyze(
            parser.parse(
                """
                local outer = 1
                local function render(param)
                    local inner = param
                    return inner
                end
                """.trimIndent()
            )
        )

        val completions = result.model.getCompletionsAt(positionOf(
            """
            local outer = 1
            local function render(param)
                local inner = param
                return inner
            end
            """.trimIndent(),
            "return"
        ))
        val labels = completions.map { it.label }

        assertTrue("inner" in labels, "Expected local inner in completions; got $labels")
        assertTrue("param" in labels, "Expected parameter param in completions; got $labels")
        assertTrue("render" in labels, "Expected function render in completions; got $labels")
        assertTrue("outer" in labels, "Expected outer local in completions; got $labels")
        assertTrue(
            completions.any { it.label == "render" && it.kind == CompletionItemKind.FUNCTION },
            "Expected render completion kind FUNCTION; got ${completions.filter { it.label == "render" }}"
        )
    }

    @Test
    fun analyzeResultModelExposesMemberCompletionsFacade() {
        val source = """
            ---@class User
            ---@field name string
            ---@method User:getName(): string
            ---@type User
            local user = {}
            local fieldValue = user.name
            local methodValue = user:getName()
        """.trimIndent()
        val result = pipeline.analyze(parser.parse(source))

        val fieldCompletions = result.model.getCompletionsAt(positionOf(source, "name", occurrence = 2))
        val methodCompletions = result.model.getCompletionsAt(positionOf(source, "getName", occurrence = 2))

        assertTrue(
            fieldCompletions.any { it.label == "name" },
            "Expected field completion name; got ${fieldCompletions.map { it.label }}"
        )
        assertTrue(
            methodCompletions.any { it.label == "getName" },
            "Expected method completion getName; got ${methodCompletions.map { it.label }}"
        )
    }

    @Test
    fun analyzeResultModelCompletionsAreStableAcrossFreshPipelineInstances() {
        val source = """
            local alpha = 1
            local beta = alpha
        """.trimIndent()
        val chunk = parser.parse(source)
        val position = positionOf(source, "beta")

        val labelsA = SemanticPipeline().analyze(chunk).model.getCompletionsAt(position).map { it.label }
        val labelsB = SemanticPipeline().analyze(chunk).model.getCompletionsAt(position).map { it.label }

        assertTrue("alpha" in labelsA)
        assertEquals(labelsA, labelsB)
    }

    // -------------------------------------------------------------------------
    // Deprecated SemanticAnalyzer still constructs / adapts
    // -------------------------------------------------------------------------

    @Test
    fun deprecatedSemanticAnalyzerStillConstructsWithDefaultPipeline() {
        val analyzer = SemanticAnalyzer()
        val result = analyzer.analyze(parser.parse("local value = 1"))

        assertNotNull(result)
        assertNotNull(result.diagnostics)
        assertNotNull(result.symbolTable)
        assertNotNull(result.globalSymbolTable)
    }

    @Test
    fun deprecatedSemanticAnalyzerAcceptsInjectedPipelineAndAnalyzes() {
        val analyzer = SemanticAnalyzer(pipeline = SemanticPipeline())
        val result = analyzer.analyze(
            parser.parse(
                """
                local outer = 1
                do
                    local outer = "x"
                    local inner = outer
                end
                """.trimIndent()
            )
        )

        val scope = assertNotNull(result.globalSymbolTable.findTableAtPosition(Position(4, 23)))
        val visible = scope.getAllVisibleSymbols(Position(4, 23))
        assertEquals(1, visible.count { it.name == "outer" })
        assertTrue(visible.any { it.name == "inner" })
    }

    @Test
    fun deprecatedAnalyzerDiagnosticsAlignWithPipelineMessagesForSharedCases() {
        val source = """
            ---@param first string
            ---@param missing number
            ---@return string
            local function render(first)
                return 1, 2
            end
        """.trimIndent()
        val chunk = parser.parse(source)

        val pipelineMessages = pipeline.analyze(chunk).model.getDiagnostics().map { it.message }
        val legacyMessages = SemanticAnalyzer().analyze(chunk).diagnostics.map { it.message }

        assertTrue(pipelineMessages.isNotEmpty(), "Expected pipeline diagnostics for doc/signature case")
        assertTrue(
            pipelineMessages.all { message -> legacyMessages.contains(message) },
            "Legacy analyzer should surface pipeline diagnostics; pipeline=$pipelineMessages legacy=$legacyMessages"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var fromIndex = 0
        var matchIndex = -1
        repeat(occurrence) {
            matchIndex = source.indexOf(needle, fromIndex)
            require(matchIndex >= 0) {
                "Needle '$needle' occurrence $occurrence not found in source"
            }
            fromIndex = matchIndex + needle.length
        }
        val lineStart = source.lastIndexOf('\n', startIndex = matchIndex).let { if (it < 0) 0 else it + 1 }
        val line = source.substring(0, matchIndex).count { it == '\n' } + 1
        val column = matchIndex - lineStart + 1
        return Position(line, column)
    }
}
