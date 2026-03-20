package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Range

internal class SymbolTableBuilder {
    private data class MutableScope(
        val id: ScopeId,
        val kind: ScopeKind,
        val range: Range,
        val ownerNode: BaseASTNode?,
        val ownerDeclarationId: DeclarationId?,
        val parentId: ScopeId?,
        val childIds: MutableList<ScopeId> = mutableListOf(),
        val declarationIds: MutableList<DeclarationId> = mutableListOf()
    )

    private var nextScopeId = 1
    private var nextDeclarationId = 1
    private var nextSymbolId = 1

    private val scopes = linkedMapOf<ScopeId, MutableScope>()
    private val scopeStack = mutableListOf<ScopeId>()
    private val declarations = mutableListOf<BinderDeclaration>()
    private val symbols = mutableListOf<BinderSymbol>()
    private val nodeToScope = linkedMapOf<BaseASTNode, ScopeId>()

    val currentScopeId: ScopeId
        get() = scopeStack.last()

    val rootScopeId: ScopeId
        get() = scopes.keys.first()

    val currentScope: Scope
        get() = requireNotNull(buildScope(scopes[currentScopeId]))

    fun nextDeclarationId(): DeclarationId = DeclarationId(nextDeclarationId++)

    fun nextSymbolId(): SymbolId = SymbolId(nextSymbolId++)

    fun createScope(
        kind: ScopeKind,
        range: Range,
        ownerNode: BaseASTNode?,
        ownerDeclarationId: DeclarationId? = null
    ): ScopeId {
        val scopeId = ScopeId(nextScopeId++)
        val parentId = scopeStack.lastOrNull()
        val scope = MutableScope(
            id = scopeId,
            kind = kind,
            range = range,
            ownerNode = ownerNode,
            ownerDeclarationId = ownerDeclarationId,
            parentId = parentId
        )
        scopes[scopeId] = scope
        if (parentId != null) {
            scopes.getValue(parentId).childIds += scopeId
        }
        if (ownerNode != null) {
            nodeToScope[ownerNode] = scopeId
        }
        return scopeId
    }

    fun pushScope(scopeId: ScopeId) {
        require(scopeId in scopes) { "Unknown scope id $scopeId." }
        scopeStack += scopeId
    }

    fun popScope() {
        require(scopeStack.isNotEmpty()) { "No scope available to pop." }
        scopeStack.removeAt(scopeStack.lastIndex)
    }

    fun addDeclaration(declaration: BinderDeclaration, scopeId: ScopeId? = currentScopeId) {
        declarations += declaration
        if (scopeId != null) {
            scopes.getValue(scopeId).declarationIds += declaration.id
        }
    }

    fun addDeclarationWithSymbol(declaration: BinderDeclaration, scopeId: ScopeId? = currentScopeId): BinderDeclaration {
        val symbol = createSymbolFor(declaration)
        val declarationWithSymbol = declaration.copy(symbolId = symbol.id)
        declarations += declarationWithSymbol
        if (scopeId != null) {
            scopes.getValue(scopeId).declarationIds += declarationWithSymbol.id
        }
        return declarationWithSymbol
    }

    fun createSymbolFor(declaration: BinderDeclaration): BinderSymbol {
        val symbol = BinderSymbol(
            id = nextSymbolId(),
            name = declaration.name,
            namespace = declaration.kind.namespace,
            declarationIds = listOf(declaration.id),
            primaryDeclarationId = declaration.id
        )
        symbols += symbol
        return symbol
    }

    fun buildScopeGraph(): ScopeGraph {
        val immutableScopes = scopes.values.mapNotNull(::buildScope)
        return ScopeGraph(
            scopes = immutableScopes,
            rootScopeId = immutableScopes.first().id,
            nodeToScopeId = nodeToScope
        )
    }

    fun buildDeclarationIndex(): DeclarationIndex = DeclarationIndex(
        declarations = declarations.toList(),
        symbols = symbols.toList()
    )

    private fun buildScope(scope: MutableScope?): Scope? {
        if (scope == null) {
            return null
        }

        return Scope(
            id = scope.id,
            kind = scope.kind,
            range = scope.range,
            ownerNode = scope.ownerNode,
            ownerDeclarationId = scope.ownerDeclarationId,
            parentId = scope.parentId,
            childIds = scope.childIds.toList(),
            declarationIds = scope.declarationIds.toList()
        )
    }
}
