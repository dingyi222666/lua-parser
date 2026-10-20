package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId

class TypeResolutionContext private constructor(
    private val graph: TypeScopeGraph,
    private val startScopeId: TypeScopeId,
    private val overlayDeclarations: List<BinderDeclaration> = emptyList()
) {
    fun lookup(name: String): List<BinderDeclaration> =
        visibleDeclarations().filter { it.name == name }

    fun lookupNamedType(name: String): List<BinderDeclaration> =
        lookup(name).filter { it.kind == DeclarationKind.CLASS || it.kind == DeclarationKind.TYPE_ALIAS }

    fun lookupTypeParameter(name: String): List<BinderDeclaration> =
        lookup(name).filter { it.kind == DeclarationKind.TYPE_PARAMETER }

    fun visibleDeclarations(): List<BinderDeclaration> {
        val result = mutableListOf<BinderDeclaration>()
        result += overlayDeclarations
        var scopeId: TypeScopeId? = startScopeId
        while (scopeId != null) {
            result += graph.getDeclarations(scopeId)
            scopeId = graph.getParent(scopeId)?.id
        }
        return result
    }

    fun withOverlayDeclarations(declarations: List<BinderDeclaration>): TypeResolutionContext {
        if (declarations.isEmpty()) {
            return this
        }
        return TypeResolutionContext(graph, startScopeId, overlayDeclarations + declarations)
    }

    fun resolveTypeParameter(name: String): BinderDeclaration? =
        visibleDeclarationsForResolution().firstOrNull { it.name == name && it.kind == DeclarationKind.TYPE_PARAMETER }

    fun resolveNamedType(name: String): BinderDeclaration? =
        visibleDeclarationsForResolution().firstOrNull {
            it.name == name && (it.kind == DeclarationKind.CLASS || it.kind == DeclarationKind.TYPE_ALIAS)
        }

    fun resolveTypeReference(name: String): BinderDeclaration? =
        resolveTypeParameter(name) ?: resolveNamedType(name)

    private fun visibleDeclarationsForResolution(): List<BinderDeclaration> {
        val result = mutableListOf<BinderDeclaration>()
        result += overlayDeclarations.asReversed()
        var scopeId: TypeScopeId? = startScopeId
        while (scopeId != null) {
            result += graph.getDeclarations(scopeId).asReversed()
            scopeId = graph.getParent(scopeId)?.id
        }
        return result
    }

    companion object {
        fun forLexicalScope(scopeId: ScopeId, binder: BinderPassResult): TypeResolutionContext {
            val typeScope = requireNotNull(binder.typeScopeGraph.getLexicalScope(scopeId)) {
                "Missing type scope for lexical scope $scopeId."
            }
            return TypeResolutionContext(binder.typeScopeGraph, typeScope.id)
        }

        fun forDeclaration(declarationId: DeclarationId, binder: BinderPassResult): TypeResolutionContext {
            val startScope = binder.typeScopeGraph.getDeclarationScope(declarationId)
                ?: resolveOwnedDeclarationScope(declarationId, binder)
                ?: resolveLexicalDeclarationScope(declarationId, binder)
                ?: error("Missing type resolution scope for declaration $declarationId.")
            return TypeResolutionContext(binder.typeScopeGraph, startScope.id)
        }

        private fun resolveOwnedDeclarationScope(
            declarationId: DeclarationId,
            binder: BinderPassResult
        ): TypeScope? {
            val declaration = binder.declarationIndex.getDeclaration(declarationId) ?: return null
            val ownerDeclarationId = (declaration.owner as? DeclarationOwner.Declaration)?.declarationId ?: return null
            return binder.typeScopeGraph.getDeclarationScope(ownerDeclarationId)
                ?: resolveLexicalDeclarationScope(ownerDeclarationId, binder)
        }

        private fun resolveLexicalDeclarationScope(
            declarationId: DeclarationId,
            binder: BinderPassResult
        ): TypeScope? {
            val lexicalScope = binder.scopeGraph.getDeclarationScope(declarationId) ?: return null
            return binder.typeScopeGraph.getLexicalScope(lexicalScope.id)
        }
    }
}
