package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression

internal class SymbolTableBuilder {
    private data class MutableScope(
        val id: ScopeId,
        val kind: ScopeKind,
        val range: Range,
        val ownerNode: BaseASTNode?,
        val ownerDeclarationId: DeclarationId?,
        val parentId: ScopeId?,
        val childIds: MutableList<ScopeId> = mutableListOf(),
        val declarationIds: MutableList<DeclarationId> = mutableListOf(),
        val valuesByName: MutableMap<String, BinderDeclaration> = mutableMapOf()
    )

    private var nextScopeId = 1
    private var nextDeclarationId = 1
    private var nextSymbolId = 1

    private val scopes = linkedMapOf<ScopeId, MutableScope>()
    private val scopeStack = mutableListOf<ScopeId>()
    private val declarations = mutableListOf<BinderDeclaration>()
    private val symbols = mutableListOf<BinderSymbol>()
    private val symbolIndices = mutableMapOf<SymbolId, Int>()
    private val nodeToScope = linkedMapOf<BaseASTNode, ScopeId>()
    private val calls = linkedSetOf<CallExpression>()
    private val globalWrites = linkedSetOf<BaseASTNode>()
    private val globalWriteNames = linkedSetOf<String>()

    fun recordCall(call: CallExpression) { calls += call }
    fun recordGlobalWrite(write: BaseASTNode, name: String) { globalWrites += write; globalWriteNames += name }

    val currentScopeId: ScopeId
        get() = scopeStack.last()

    val rootScopeId: ScopeId
        get() = scopes.keys.first()

    val currentScope: Scope
        get() = requireNotNull(buildScope(scopes[currentScopeId]))

    fun nextDeclarationId(): DeclarationId = DeclarationId(nextDeclarationId++)

    fun createScope(
        kind: ScopeKind,
        range: Range,
        ownerNode: BaseASTNode?,
        ownerDeclarationId: DeclarationId? = null
    ): ScopeId {
        val scopeId = ScopeId(nextScopeId++)
        val parentId = scopeStack.lastOrNull()
        scopes[scopeId] = MutableScope(
            id = scopeId,
            kind = kind,
            range = range,
            ownerNode = ownerNode,
            ownerDeclarationId = ownerDeclarationId,
            parentId = parentId
        )
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

    fun addDeclarationWithSymbol(declaration: BinderDeclaration, scopeId: ScopeId? = currentScopeId): BinderDeclaration {
        val symbol = createSymbolFor(declaration)
        return commit(declaration.copy(symbolId = symbol.id), scopeId)
    }

    /**
     * Attach [declaration] to an existing [symbolId] without minting a peer symbol.
     * Primary declaration stays the first declaration already on the symbol.
     */
    fun addDeclarationToExistingSymbol(
        declaration: BinderDeclaration,
        symbolId: SymbolId,
        scopeId: ScopeId? = currentScopeId
    ): BinderDeclaration {
        val symbolIndex = requireNotNull(symbolIndices[symbolId]) { "Unknown symbol id $symbolId." }
        val existing = symbols[symbolIndex]
        require(declaration.kind.namespace == existing.namespace) {
            "Declaration namespace ${declaration.kind.namespace} does not match symbol namespace ${existing.namespace}."
        }

        val declarationWithSymbol = declaration.copy(symbolId = symbolId)
        symbols[symbolIndex] = existing.copy(
            declarationIds = existing.declarationIds + declarationWithSymbol.id
        )
        return commit(declarationWithSymbol, scopeId)
    }

    /** Shared registration epilog: append the declaration, then index it into [scopeId]. */
    private fun commit(declaration: BinderDeclaration, scopeId: ScopeId?): BinderDeclaration {
        declarations += declaration
        if (scopeId != null) {
            indexDeclaration(scopeId, declaration)
        }
        return declaration
    }

    /**
     * Walk the lexical scope chain from [fromScopeId] (default: current) looking for the
     * nearest VALUE-namespace declaration with [name]. Later declarations in the same
     * scope win (Lua-style shadowing within a block as binders are visited in order).
     */
    fun findVisibleValueDeclaration(
        name: String,
        fromScopeId: ScopeId = currentScopeId
    ): BinderDeclaration? =
        generateSequence(fromScopeId) { scopeId -> scopes.getValue(scopeId).parentId }
            .firstNotNullOfOrNull { scopeId -> scopes.getValue(scopeId).valuesByName[name] }

    private fun indexDeclaration(scopeId: ScopeId, declaration: BinderDeclaration) {
        val scope = scopes.getValue(scopeId)
        scope.declarationIds += declaration.id
        if (declaration.kind.namespace == DeclarationNamespace.VALUE) scope.valuesByName[declaration.name] = declaration
    }

    fun createSymbolFor(declaration: BinderDeclaration): BinderSymbol {
        val symbol = BinderSymbol(
            id = SymbolId(nextSymbolId++),
            name = declaration.name,
            namespace = declaration.kind.namespace,
            declarationIds = listOf(declaration.id),
            primaryDeclarationId = declaration.id
        )
        symbolIndices[symbol.id] = symbols.size
        symbols += symbol
        return symbol
    }

    fun buildScopeGraph(): ScopeGraph {
        val immutableScopes = scopes.values.mapNotNull(::buildScope)
        return ScopeGraph(
            scopes = immutableScopes,
            rootScopeId = immutableScopes.first().id,
            nodeToScopeId = nodeToScope,
            calls = calls,
            globalWrites = globalWrites,
            globalWriteNames = globalWriteNames
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
