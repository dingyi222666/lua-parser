package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationIndex
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.ScopeGraph
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId

class TypeScopeGraphBuilder(
    private val scopeGraph: ScopeGraph,
    private val declarationIndex: DeclarationIndex
) {
    private var nextTypeScopeId = 1

    fun build(): TypeScopeGraph {
        val lexicalTypeScopeIds = linkedMapOf<ScopeId, TypeScopeId>()
        val typeScopes = mutableListOf<TypeScope>()

        scopeGraph.scopes.forEach { scope ->
            val typeScopeId = nextTypeScopeId()
            lexicalTypeScopeIds[scope.id] = typeScopeId
            typeScopes += TypeScope(
                id = typeScopeId,
                kind = TypeScopeKind.LEXICAL,
                parentId = scope.parentId?.let(lexicalTypeScopeIds::get),
                lexicalScopeId = scope.id,
                declarationIds = scope.declarationIds.mapNotNull(declarationIndex::getDeclaration)
                    .filter(::isLexicalTypeDeclaration)
                    .map(BinderDeclaration::id)
            )
        }

        declarationIndex.declarations
            .filter { it.kind == DeclarationKind.TYPE_PARAMETER }
            .groupBy(::typeParameterOwnerId)
            .entries
            .sortedBy { (ownerDeclarationId, _) -> ownerDeclarationId.value }
            .forEach { (ownerDeclarationId, declarations) ->
                val parentScopeId = lexicalTypeScopeIds.getValue(findOwnerLexicalScopeId(ownerDeclarationId))
                typeScopes += TypeScope(
                    id = nextTypeScopeId(),
                    kind = TypeScopeKind.DECLARATION,
                    parentId = parentScopeId,
                    ownerDeclarationId = ownerDeclarationId,
                    declarationIds = declarations.map(BinderDeclaration::id)
                )
            }

        return TypeScopeGraph(
            scopes = typeScopes,
            rootScopeId = lexicalTypeScopeIds.getValue(scopeGraph.rootScope.id),
            declarationIndex = declarationIndex
        )
    }

    private fun findOwnerLexicalScopeId(ownerDeclarationId: DeclarationId): ScopeId {
        return requireNotNull(scopeGraph.getDeclarationScope(ownerDeclarationId)) {
            "Missing lexical scope for declaration owner $ownerDeclarationId."
        }.id
    }

    private fun typeParameterOwnerId(declaration: BinderDeclaration): DeclarationId {
        val owner = declaration.owner as? DeclarationOwner.Declaration
        require(owner != null) { "Type parameter ${declaration.id} must have declaration owner." }
        return owner.declarationId
    }

    private fun isLexicalTypeDeclaration(declaration: BinderDeclaration): Boolean {
        return declaration.kind == DeclarationKind.CLASS || declaration.kind == DeclarationKind.TYPE_ALIAS
    }

    private fun nextTypeScopeId(): TypeScopeId = TypeScopeId(nextTypeScopeId++)
}
