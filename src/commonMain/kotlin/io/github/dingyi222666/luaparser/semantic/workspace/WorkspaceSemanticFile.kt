package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParserRecoveryDiagnostic
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.SemanticPipelineSnapshot
import io.github.dingyi222666.luaparser.semantic.model.NodePositionIndex
import io.github.dingyi222666.luaparser.semantic.model.NodePositionIndexProvider
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
        (model as? NodePositionIndexProvider)?.nodePositionIndex ?: NodePositionIndex(chunk)
    }

    private val nodeLists: NodeLists by lazy { collectNodeLists(chunk) }
    internal val memberExpressions: List<MemberExpression> get() = nodeLists.memberExpressions
    internal val identifiers: List<Identifier> get() = nodeLists.identifiers
}

private class NodeLists(
    val memberExpressions: List<MemberExpression>,
    val identifiers: List<Identifier>
)

/**
 * Member expressions and identifiers are gathered in a single traversal; the two collections
 * are disjoint node kinds, so one walk produces both in the same document order as before.
 */
private fun collectNodeLists(root: ChunkNode): NodeLists {
    val memberExpressions = mutableListOf<MemberExpression>()
    val identifiers = mutableListOf<Identifier>()
    val visitor = object : ASTVisitor<Unit> {
        override fun visitExpressionNode(node: ExpressionNode, value: Unit) {
            if (node is MemberExpression) {
                memberExpressions += node
            }
            super.visitExpressionNode(node, value)
        }

        override fun visitIdentifier(node: Identifier, value: Unit) {
            identifiers += node
        }

        override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) = Unit
        override fun visitCommentStatement(commentStatement: CommentStatement, value: Unit) = Unit
    }
    visitor.visitChunkNode(root, Unit)
    return NodeLists(memberExpressions, identifiers)
}

internal fun WorkspaceSemanticFile.nodeAt(position: Position): BaseASTNode? {
    return nodeIndex.findInnermost(position)
}
