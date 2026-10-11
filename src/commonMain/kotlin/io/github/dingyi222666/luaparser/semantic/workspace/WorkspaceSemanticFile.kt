package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParserRecoveryDiagnostic
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipelineSnapshot
import io.github.dingyi222666.luaparser.semantic.model.DefaultSemanticModel
import io.github.dingyi222666.luaparser.semantic.model.NodePositionIndex
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel

class WorkspaceSemanticFile internal constructor(
    val path: VirtualPath,
    val source: String,
    val chunk: ChunkNode,
    val model: SemanticModel,
    internal val snapshot: SemanticPipelineSnapshot,
    /**
     * Syntax-recovery diagnostics from the parse that produced [chunk].
     *
     * These used to be dropped inside the parser, which forced every consumer that wanted parse
     * errors (notably LSP diagnostics) to re-parse the same buffer. Carrying them on the semantic
     * file means the workspace parse is the only parse.
     */
    val recoveryDiagnostics: List<LuaParserRecoveryDiagnostic> = emptyList()
) {
    // Built on demand: a workspace pass creates a semantic file per document, but only the
    // documents actually queried (hover, rename, completion) need these indexes. The model
    // already indexes this exact chunk, so reuse it instead of walking the AST a second time.
    internal val nodeIndex: NodePositionIndex by lazy {
        // DefaultSemanticModel is the only SemanticModel the pipeline builds, so this cast
        // always succeeds in practice; the fallback keeps EmptySemanticModel-style models safe.
        (model as? DefaultSemanticModel)?.nodePositionIndex ?: NodePositionIndex(chunk)
    }

    internal val memberExpressions: List<MemberExpression> get() = nodeIndex.memberExpressions
    internal val identifiers: List<Identifier> get() = nodeIndex.identifiers
}

internal fun WorkspaceSemanticFile.nodeAt(position: Position): BaseASTNode? {
    return nodeIndex.findInnermost(position)
}
