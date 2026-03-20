package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity

/**
 * Public result returned by [io.github.dingyi222666.luaparser.semantic.SemanticPipeline].
 */
data class SemanticAnalysisResult(
    val model: SemanticModel = EmptySemanticModel,
    val summary: SemanticAnalysisSummary = SemanticAnalysisSummary.from(model.getDiagnostics())
)

/**
 * Lightweight aggregate of pipeline diagnostics.
 */
data class SemanticAnalysisSummary(
    val diagnosticCount: Int = 0,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val infoCount: Int = 0
) {
    val hasErrors: Boolean
        get() = errorCount > 0

    companion object {
        fun from(diagnostics: List<Diagnostic>): SemanticAnalysisSummary {
            return SemanticAnalysisSummary(
                diagnosticCount = diagnostics.size,
                errorCount = diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
                warningCount = diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
                infoCount = diagnostics.count { it.severity == DiagnosticSeverity.INFO }
            )
        }
    }
}
