package io.github.dingyi222666.luaparser.semantic.api

data class CompletionItem(
    val label: String,
    val kind: CompletionItemKind = CompletionItemKind.TEXT,
    val detail: String? = null,
    val insertText: String = label,
    val sortText: String? = null
)

enum class CompletionItemKind {
    TEXT,
    VARIABLE,
    PARAMETER,
    FUNCTION,
    METHOD,
    FIELD,
    CLASS,
    TYPE_ALIAS,
    MODULE,
    KEYWORD,
    SNIPPET
}
