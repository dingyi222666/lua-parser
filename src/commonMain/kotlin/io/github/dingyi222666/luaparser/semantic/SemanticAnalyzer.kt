package io.github.dingyi222666.luaparser.semantic

import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.symbol.GlobalSymbolTable
import io.github.dingyi222666.luaparser.semantic.symbol.SymbolTable

/**
 * Compatibility wrapper over [SemanticPipeline].
 *
 * New code should use [SemanticPipeline] and [io.github.dingyi222666.luaparser.semantic.model.SemanticAnalysisResult].
 */
@Deprecated(
    message = "SemanticAnalyzer is kept for compatibility. Use SemanticPipeline instead.",
    replaceWith = ReplaceWith(
        expression = "SemanticPipeline().analyze(ast)",
        imports = ["io.github.dingyi222666.luaparser.semantic.SemanticPipeline"]
    )
)
class SemanticAnalyzer(
    private val pipeline: SemanticPipeline = SemanticPipeline()
) {
    fun analyze(ast: ChunkNode): AnalysisResult {
        return LegacyAnalysisAdapters.adapt(pipeline.analyzeSnapshot(ast))
    }
}

/**
 * Legacy analyzer result adapted from [SemanticPipeline].
 */
data class AnalysisResult(
    val diagnostics: List<Diagnostic>,
    val symbolTable: SymbolTable,
    val globalSymbolTable: GlobalSymbolTable
)

/**
 * Legacy diagnostic shape retained for compatibility with [SemanticAnalyzer].
 */
data class Diagnostic(
    val range: Range,
    val message: String,
    val severity: Severity = Severity.ERROR
) {
    enum class Severity {
        ERROR,
        WARNING,
        INFO
    }
}
