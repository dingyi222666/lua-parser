package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachmentIndex
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot

class BinderPass(
    private val builtinGlobals: BuiltinOverlaySnapshot.GlobalsSnapshot? = null
) {

    fun bind(
        chunk: ChunkNode,
        comments: CommentAttachmentIndex,
        builtinGlobalsOverride: BuiltinOverlaySnapshot.GlobalsSnapshot? = null
    ): BinderPassResult {
        val builder = SymbolTableBuilder()
        // One column past the chunk end so the root scope also covers the trailing caret /
        // EOF position after the last committed token.
        val rootScopeId = builder.createScope(
            kind = ScopeKind.CHUNK,
            range = Range(chunk.range.start, Position(chunk.range.end.line, chunk.range.end.column + 1)),
            ownerNode = chunk.body
        )
        builder.pushScope(rootScopeId)

        try {
            BuiltinSymbolSeeder.seed(builder, builtinGlobalsOverride ?: builtinGlobals ?: BuiltinOverlayLoader.standaloneGlobals())
            DeclarationBinder(builder, comments).bind(chunk)
        } finally {
            builder.popScope()
        }

        val scopeGraph = builder.buildScopeGraph()
        val declarationIndex = builder.buildDeclarationIndex()
        return buildBinderPassResult(scopeGraph, declarationIndex, comments)
    }
}
