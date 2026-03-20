package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode

class ScopeGraph(
    scopes: List<Scope>,
    rootScopeId: ScopeId,
    nodeToScopeId: Map<BaseASTNode, ScopeId>
) {
    private val scopesById = scopes.associateBy(Scope::id)
    private val nodeToScope = nodeToScopeId.toMap()

    val scopes: List<Scope> = scopes.toList()
    val rootScope: Scope = requireNotNull(scopesById[rootScopeId]) {
        "Missing root scope $rootScopeId."
    }

    init {
        require(scopesById.size == scopes.size) { "Duplicate scope ids are not allowed." }
        scopes.forEach { scope ->
            scope.parentId?.let { parentId ->
                require(parentId in scopesById) { "Scope ${scope.id} references missing parent scope $parentId." }
            }
            scope.childIds.forEach { childId ->
                require(childId in scopesById) { "Scope ${scope.id} references missing child scope $childId." }
            }
        }
    }

    fun getScope(id: ScopeId): Scope? = scopesById[id]

    fun getScope(node: BaseASTNode): Scope? = nodeToScope[node]?.let(scopesById::get)

    fun getParent(id: ScopeId): Scope? = scopesById[id]?.parentId?.let(scopesById::get)

    fun getChildren(id: ScopeId): List<Scope> =
        scopesById[id]?.childIds?.mapNotNull(scopesById::get).orEmpty()

    fun getDeclarations(id: ScopeId): List<DeclarationId> = scopesById[id]?.declarationIds.orEmpty()

    fun getDeclarationScope(id: DeclarationId): Scope? = scopes.firstOrNull { id in it.declarationIds }
}
