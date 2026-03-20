package io.github.dingyi222666.luaparser.semantic.api

import io.github.dingyi222666.luaparser.parser.ast.node.Range

data class Symbol(
    val name: String,
    val kind: SymbolKind,
    val range: Range? = null,
    val type: TypeInfo? = null,
    val declaredType: TypeInfo? = null,
    val detail: String? = null,
    val symbolId: String? = null
)

data class TypeInfo(
    val displayName: String,
    val detail: String? = null,
    val typeKey: String? = null,
    val kind: TypeInfoKind = TypeInfoKind.UNKNOWN,
    val moduleName: String? = null
)

enum class TypeInfoKind {
    UNKNOWN,
    FUNCTION,
    TABLE,
    CLASS,
    MODULE
}

enum class SymbolKind {
    VARIABLE,
    FUNCTION,
    PARAMETER,
    LOCAL,
    CLASS,
    TYPE_ALIAS,
    FIELD,
    METHOD,
    MODULE,
    UNKNOWN
}
