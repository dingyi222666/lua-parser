package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachmentIndex
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeScopeGraph

data class BinderPassResult(
    val scopeGraph: ScopeGraph,
    val declarationIndex: DeclarationIndex,
    val typeScopeGraph: TypeScopeGraph,
    val commentAttachments: CommentAttachmentIndex,
    val positionQueries: BinderPositionQueries
) {
    fun withDeclarations(declarations: List<BinderDeclaration>): BinderPassResult {
        val rebuiltDeclarationIndex = DeclarationIndex(
            declarations = declarations,
            symbols = declarationIndex.symbols
        )
        return BinderPassResult(
            scopeGraph = scopeGraph,
            declarationIndex = rebuiltDeclarationIndex,
            typeScopeGraph = io.github.dingyi222666.luaparser.semantic.types.resolve.TypeScopeGraphBuilder(
                scopeGraph = scopeGraph,
                declarationIndex = rebuiltDeclarationIndex
            ).build(),
            commentAttachments = commentAttachments,
            positionQueries = BinderPositionQueries(
                scopeGraph = scopeGraph,
                declarationIndex = rebuiltDeclarationIndex
            )
        )
    }
}
