package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachmentIndex
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeScopeGraph
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeScopeGraphBuilder

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
        return buildBinderPassResult(scopeGraph, rebuiltDeclarationIndex, commentAttachments)
    }
}

/**
 * Single construction path for a [BinderPassResult]: derives the type-scope graph and the
 * positional queries from the same [scopeGraph]/[declarationIndex] pair. Used by
 * [BinderPass.bind] and by [BinderPassResult.withDeclarations] so the derived members can
 * never be rebuilt from mismatched inputs in one path and not the other.
 */
internal fun buildBinderPassResult(
    scopeGraph: ScopeGraph,
    declarationIndex: DeclarationIndex,
    commentAttachments: CommentAttachmentIndex
): BinderPassResult {
    val typeScopeGraph = TypeScopeGraphBuilder(
        scopeGraph = scopeGraph,
        declarationIndex = declarationIndex
    ).build()
    return BinderPassResult(
        scopeGraph = scopeGraph,
        declarationIndex = declarationIndex,
        typeScopeGraph = typeScopeGraph,
        commentAttachments = commentAttachments,
        positionQueries = BinderPositionQueries(
            scopeGraph = scopeGraph,
            declarationIndex = declarationIndex
        )
    )
}
