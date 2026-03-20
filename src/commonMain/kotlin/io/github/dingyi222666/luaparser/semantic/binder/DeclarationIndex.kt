package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode

class DeclarationIndex(
    val declarations: List<BinderDeclaration>,
    val symbols: List<BinderSymbol>
) {
    private val declarationsById = declarations.associateBy(BinderDeclaration::id)
    private val symbolsById = symbols.associateBy(BinderSymbol::id)
    private val declarationsByNode = declarations
        .filter { it.anchorNode != null }
        .groupBy { it.anchorNode!! }
    private val declarationsByOwner = declarations.groupBy(BinderDeclaration::owner)
    private val symbolsByName = symbols.groupBy(BinderSymbol::name)

    init {
        require(declarationsById.size == declarations.size) { "Duplicate declaration ids are not allowed." }
        require(symbolsById.size == symbols.size) { "Duplicate symbol ids are not allowed." }

        val symbolIdsByDeclarationId = mutableMapOf<DeclarationId, SymbolId>()

        declarations.forEach { declaration ->
            declaration.symbolId?.let { symbolId ->
                val symbol = requireNotNull(symbolsById[symbolId]) {
                    "Declaration ${declaration.id} references missing symbol id $symbolId."
                }
                require(declaration.id in symbol.declarationIds) {
                    "Declaration ${declaration.id} references symbol id $symbolId, but the symbol does not include the declaration."
                }
            }

            val owner = declaration.owner
            if (owner is DeclarationOwner.Declaration) {
                require(owner.declarationId in declarationsById) {
                    "Declaration ${declaration.id} references missing owner declaration id ${owner.declarationId}."
                }
            }
        }

        symbols.forEach { symbol ->
            symbol.declarationIds.forEach { declarationId ->
                val declaration = requireNotNull(declarationsById[declarationId]) {
                    "Symbol ${symbol.id} references missing declaration id $declarationId."
                }
                val previousSymbolId = symbolIdsByDeclarationId.put(declarationId, symbol.id)
                require(previousSymbolId == null || previousSymbolId == symbol.id) {
                    "Declaration $declarationId cannot belong to multiple symbols: $previousSymbolId and ${symbol.id}."
                }
                require(declaration.kind.namespace == symbol.namespace) {
                    "Symbol ${symbol.id} namespace ${symbol.namespace} does not match declaration ${declaration.id} namespace ${declaration.kind.namespace}."
                }
                val declarationSymbolId = declaration.symbolId
                require(declarationSymbolId == symbol.id) {
                    "Declaration ${declaration.id} belongs to symbol $declarationSymbolId, not ${symbol.id}."
                }
            }
        }
    }

    fun getDeclaration(id: DeclarationId): BinderDeclaration? = declarationsById[id]

    fun getSymbol(id: SymbolId): BinderSymbol? = symbolsById[id]

    fun getDeclarations(symbolId: SymbolId): List<BinderDeclaration> {
        val symbol = symbolsById[symbolId] ?: return emptyList()
        return symbol.declarationIds.mapNotNull(declarationsById::get)
    }

    fun getPrimaryDeclaration(symbolId: SymbolId): BinderDeclaration? {
        val symbol = symbolsById[symbolId] ?: return null
        return declarationsById[symbol.primaryDeclarationId]
    }

    fun getDeclarations(node: BaseASTNode): List<BinderDeclaration> = declarationsByNode[node].orEmpty()

    fun getOwnedDeclarations(owner: DeclarationOwner): List<BinderDeclaration> = declarationsByOwner[owner].orEmpty()

    fun getSymbols(name: String, namespace: DeclarationNamespace? = null): List<BinderSymbol> {
        val candidates = symbolsByName[name].orEmpty()
        return if (namespace == null) candidates else candidates.filter { it.namespace == namespace }
    }

    fun withDeclarations(declarations: List<BinderDeclaration>): DeclarationIndex = DeclarationIndex(
        declarations = declarations,
        symbols = symbols
    )
}
