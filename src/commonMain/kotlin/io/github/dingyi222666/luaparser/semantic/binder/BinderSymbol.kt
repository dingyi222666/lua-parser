package io.github.dingyi222666.luaparser.semantic.binder

data class BinderSymbol(
    val id: SymbolId,
    val name: String,
    val namespace: DeclarationNamespace,
    val declarationIds: List<DeclarationId>,
    val primaryDeclarationId: DeclarationId = declarationIds.firstOrNull() ?: DeclarationId(-1)
) {
    init {
        require(declarationIds.isNotEmpty()) { "BinderSymbol must contain at least one declaration id." }
        require(primaryDeclarationId in declarationIds) {
            "Primary declaration id $primaryDeclarationId must be included in declarationIds."
        }
    }
}
