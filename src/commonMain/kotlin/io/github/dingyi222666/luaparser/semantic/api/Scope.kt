package io.github.dingyi222666.luaparser.semantic.api

import io.github.dingyi222666.luaparser.parser.ast.node.Range

data class Scope(
    val kind: ScopeKind,
    val range: Range? = null,
    val symbols: List<Symbol> = emptyList(),
    val name: String? = null,
    val scopeId: String? = null
)

enum class ScopeKind {
    CHUNK,
    BLOCK,
    FUNCTION,
    MODULE,
    LOOP,
    CONDITIONAL,
    UNKNOWN
}
