package io.github.dingyi222666.luaparser.semantic.api

import io.github.dingyi222666.luaparser.parser.ast.node.Range

data class Diagnostic(
    val message: String,
    val range: Range? = null,
    val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    val code: String? = null,
    /**
     * LSP DiagnosticTag values (org.eclipse.lsp4j.DiagnosticTag): 1 = Unnecessary,
     * 2 = Deprecated. Kept as lsp4j-free Ints so this common api surface stays
     * dependency-free; the JVM LSP publish mapping translates them onto
     * lsp4j Diagnostic.tags (clients render Unnecessary as faded/struck text).
     */
    val tags: List<Int> = emptyList()
)

/**
 * LSP DiagnosticTag.Unnecessary (org.eclipse.lsp4j.DiagnosticTag value 1) — the canonical
 * unused-code signal; emitted by the checker and translated by the JVM LSP publish mapping.
 * Previously duplicated as private consts in ExpressionUsageChecker and LuaLanguageService.
 */
internal const val DIAGNOSTIC_TAG_UNNECESSARY = 1

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO
}
