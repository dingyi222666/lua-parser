package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachmentIndex
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeScopeGraphBuilder

class BinderPass(
    private val builtinGlobals: BuiltinOverlaySnapshot.GlobalsSnapshot = BuiltinOverlayLoader.standaloneGlobals()
) {

    fun bind(
        chunk: ChunkNode,
        comments: CommentAttachmentIndex,
        builtinGlobalsOverride: BuiltinOverlaySnapshot.GlobalsSnapshot? = null
    ): BinderPassResult {
        val builder = SymbolTableBuilder()
        val rootScopeId = builder.createScope(
            kind = ScopeKind.CHUNK,
            range = extendRootScopeRange(chunk.range),
            ownerNode = chunk.body
        )
        builder.pushScope(rootScopeId)

        try {
            BuiltinSymbolSeeder.seed(builder, builtinGlobalsOverride ?: builtinGlobals)
            DeclarationBinder(builder, comments).bind(chunk)
        } finally {
            builder.popScope()
        }

        val scopeGraph = builder.buildScopeGraph()
        val declarationIndex = builder.buildDeclarationIndex()
        val typeScopeGraph = TypeScopeGraphBuilder(
            scopeGraph = scopeGraph,
            declarationIndex = declarationIndex
        ).build()

        return BinderPassResult(
            scopeGraph = scopeGraph,
            declarationIndex = declarationIndex,
            typeScopeGraph = typeScopeGraph,
            commentAttachments = comments,
            positionQueries = BinderPositionQueries(
                scopeGraph = scopeGraph,
                declarationIndex = declarationIndex
            )
        )
    }

    private fun extendRootScopeRange(range: Range): Range {
        return Range(
            start = range.start,
            end = Position(range.end.line, range.end.column + 1)
        )
    }
}
