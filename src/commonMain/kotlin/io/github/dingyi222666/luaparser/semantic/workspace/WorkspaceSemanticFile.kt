package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.SemanticPipelineSnapshot
import io.github.dingyi222666.luaparser.semantic.model.NodePositionIndex
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel

class WorkspaceSemanticFile internal constructor(
    val path: VirtualPath,
    val source: String,
    val chunk: ChunkNode,
    val model: SemanticModel,
    internal val snapshot: SemanticPipelineSnapshot
) {
    internal val nodeIndex: NodePositionIndex = NodePositionIndex(chunk)
    internal val memberExpressions: List<MemberExpression> = collectMemberExpressions(chunk)
    internal val identifiers: List<Identifier> = collectIdentifiers(chunk)
}

private fun collectMemberExpressions(root: ChunkNode): List<MemberExpression> {
    val members = mutableListOf<MemberExpression>()
    val visitor = object : ASTVisitor<Unit> {
        override fun visitExpressionNode(node: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode, value: Unit) {
            if (node is MemberExpression) {
                members += node
            }
            super.visitExpressionNode(node, value)
        }

        override fun visitIdentifier(node: io.github.dingyi222666.luaparser.parser.ast.node.Identifier, value: Unit) = Unit
        override fun visitAttributeIdentifier(identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier, value: Unit) = Unit
        override fun visitCommentStatement(commentStatement: io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement, value: Unit) = Unit
    }
    visitor.visitChunkNode(root, Unit)
    return members
}

private fun collectIdentifiers(root: ChunkNode): List<Identifier> {
    val identifiers = mutableListOf<Identifier>()
    val visitor = object : ASTVisitor<Unit> {
        override fun visitIdentifier(node: Identifier, value: Unit) {
            identifiers += node
        }

        override fun visitAttributeIdentifier(identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier, value: Unit) = Unit
        override fun visitCommentStatement(commentStatement: io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement, value: Unit) = Unit
    }
    visitor.visitChunkNode(root, Unit)
    return identifiers
}

internal fun WorkspaceSemanticFile.nodeAt(position: io.github.dingyi222666.luaparser.parser.ast.node.Position): BaseASTNode? {
    return nodeIndex.findInnermost(position)
}
