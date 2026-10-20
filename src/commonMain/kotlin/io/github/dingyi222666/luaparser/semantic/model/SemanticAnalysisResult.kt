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
 *
 * Counts must stay aligned with [SemanticModel.getDiagnostics] for the same analyze
 * result (including clean-analyze filtering applied upstream in CheckerPass).
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
        /**
         * Builds a summary from the public diagnostic list already exposed on the model.
         * Callers must pass the same list returned by [SemanticModel.getDiagnostics].
         */
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
