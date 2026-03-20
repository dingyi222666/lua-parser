package io.github.dingyi222666.luaparser.semantic.api

import io.github.dingyi222666.luaparser.parser.ast.node.Range

data class Diagnostic(
    val message: String,
    val range: Range? = null,
    val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    val code: String? = null
)

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO
}
