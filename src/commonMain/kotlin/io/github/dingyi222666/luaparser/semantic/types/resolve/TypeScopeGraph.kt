package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationIndex
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId

class TypeScopeGraph(
    scopes: List<TypeScope>,
    rootScopeId: TypeScopeId,
    private val declarationIndex: DeclarationIndex
) {
    private val scopesById = scopes.associateBy(TypeScope::id)
    private val lexicalScopesByScopeId = scopes
        .filter { it.kind == TypeScopeKind.LEXICAL }
        .associateBy { requireNotNull(it.lexicalScopeId) }
    private val declarationScopesByOwnerId = scopes
        .filter { it.kind == TypeScopeKind.DECLARATION }
        .associateBy { requireNotNull(it.ownerDeclarationId) }

    val scopes: List<TypeScope> = scopes.toList()
    val rootScope: TypeScope = requireNotNull(scopesById[rootScopeId]) {
        "Missing root type scope $rootScopeId."
    }

    init {
        require(scopesById.size == scopes.size) { "Duplicate type scope ids are not allowed." }
        scopes.forEach { scope ->
            scope.parentId?.let { parentId ->
                require(parentId in scopesById) { "Type scope ${scope.id} references missing parent scope $parentId." }
            }

            when (scope.kind) {
                TypeScopeKind.LEXICAL -> {
                    require(scope.lexicalScopeId != null) { "Lexical type scope ${scope.id} is missing lexicalScopeId." }
                    require(scope.ownerDeclarationId == null) {
                        "Lexical type scope ${scope.id} cannot have ownerDeclarationId."
                    }
                }

                TypeScopeKind.DECLARATION -> {
                    require(scope.ownerDeclarationId != null) {
                        "Declaration type scope ${scope.id} is missing ownerDeclarationId."
                    }
                    require(scope.lexicalScopeId == null) {
                        "Declaration type scope ${scope.id} cannot have lexicalScopeId."
                    }
                }
            }
        }
    }

    fun getScope(id: TypeScopeId): TypeScope? = scopesById[id]

    fun getLexicalScope(scopeId: ScopeId): TypeScope? = lexicalScopesByScopeId[scopeId]

    fun getDeclarationScope(declarationId: DeclarationId): TypeScope? = declarationScopesByOwnerId[declarationId]

    fun getParent(id: TypeScopeId): TypeScope? = scopesById[id]?.parentId?.let(scopesById::get)

    fun getDeclarations(id: TypeScopeId): List<BinderDeclaration> {
        val scope = scopesById[id] ?: return emptyList()
        return scope.declarationIds.mapNotNull(declarationIndex::getDeclaration)
    }
}
