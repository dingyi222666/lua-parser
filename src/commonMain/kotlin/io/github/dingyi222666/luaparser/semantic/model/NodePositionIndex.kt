package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor

internal class NodePositionIndex(root: BaseASTNode) {
    private data class Entry(
        val node: BaseASTNode,
        val range: Range,
        val order: Int
    )

    private val entries: List<Entry>

    init {
        val collected = mutableListOf<Entry>()
        val startNode = when (root) {
            is BlockNode -> runCatching { root.parent }.getOrNull() as? ChunkNode ?: root
            else -> root
        }

        var order = 0
        val visitor = object : ASTVisitor<Unit> {
            private fun record(node: BaseASTNode) {
                if (containsNodeRange(node.range)) {
                    collected += Entry(node = node, range = node.range, order = order++)
                }
            }

            override fun visitChunkNode(node: ChunkNode, value: Unit) {
                record(node)
                super.visitChunkNode(node, value)
            }

            override fun visitBlockNode(node: BlockNode, value: Unit) {
                record(node)
                super.visitBlockNode(node, value)
            }

            override fun visitStatementNode(node: io.github.dingyi222666.luaparser.parser.ast.node.StatementNode, value: Unit) {
                record(node)
                super.visitStatementNode(node, value)
            }

            override fun visitExpressionNode(node: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode, value: Unit) {
                record(node)
                super.visitExpressionNode(node, value)
            }

            override fun visitIdentifier(node: io.github.dingyi222666.luaparser.parser.ast.node.Identifier, value: Unit) {
                record(node)
            }

            override fun visitAttributeIdentifier(identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier, value: Unit) {
                record(identifier)
            }

            override fun visitCommentStatement(commentStatement: io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement, value: Unit) {
                record(commentStatement)
            }
        }

        when (startNode) {
            is ChunkNode -> visitor.visitChunkNode(startNode, Unit)
            is BlockNode -> visitor.visitBlockNode(startNode, Unit)
            is io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode -> visitor.visitExpressionNode(startNode, Unit)
            is io.github.dingyi222666.luaparser.parser.ast.node.StatementNode -> visitor.visitStatementNode(startNode, Unit)
            else -> {
                if (containsNodeRange(startNode.range)) {
                    collected += Entry(startNode, startNode.range, order)
                }
            }
        }

        // Specificity ordering depends only on ranges, so establish it once here instead of
        // re-sorting the matching subset on every position query.
        collected.sortWith(
            Comparator { a, b ->
                val specificity = compareSpecificity(a.range, b.range)
                if (specificity != 0) specificity else b.order.compareTo(a.order)
            }
        )
        entries = collected
    }

    fun findInnermost(position: Position): BaseASTNode? {
        return entries.firstOrNull { contains(it.range, position) }?.node
    }

    fun findEnclosing(position: Position): List<BaseASTNode> {
        return entries.filter { contains(it.range, position) }.map(Entry::node)
    }

    private fun containsNodeRange(range: Range): Boolean {
        return compare(range.start, range.end) < 0
    }

    private fun contains(range: Range, position: Position): Boolean {
        return compare(range.start, position) <= 0 && compare(position, range.end) < 0
    }

    private fun compareSpecificity(a: Range, b: Range): Int {
        val startComparison = compare(b.start, a.start)
        if (startComparison != 0) {
            return startComparison
        }
        return compare(a.end, b.end)
    }

    private fun compare(a: Position, b: Position): Int {
        val lineComparison = a.line.compareTo(b.line)
        if (lineComparison != 0) {
            return lineComparison
        }
        return a.column.compareTo(b.column)
    }
}
