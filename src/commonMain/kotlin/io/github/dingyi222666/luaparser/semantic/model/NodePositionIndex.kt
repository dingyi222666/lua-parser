package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.binder.comparePositions

internal class NodePositionIndex(root: BaseASTNode) {
    private data class Entry(
        val node: BaseASTNode,
        val range: Range,
        val order: Int
    )

    private val entries: List<Entry>
    val memberExpressions: List<MemberExpression>
    val identifiers: List<Identifier>

    init {
        val collected = mutableListOf<Entry>()
        val collectedMembers = mutableListOf<MemberExpression>()
        val collectedIdentifiers = mutableListOf<Identifier>()
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
                if (node is MemberExpression) collectedMembers += node
                super.visitExpressionNode(node, value)
            }

            override fun visitIdentifier(node: io.github.dingyi222666.luaparser.parser.ast.node.Identifier, value: Unit) {
                record(node)
                collectedIdentifiers += node
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

        // Keep AST entries in traversal order. Sorting every node by range made
        // the first completion pay an O(n log n) cost before it even inspected
        // the caret. Queries below sort only the small set of nodes enclosing
        // the requested position.
        entries = collected
        memberExpressions = collectedMembers
        identifiers = collectedIdentifiers
    }

    fun findInnermost(position: Position): BaseASTNode? {
        var best: Entry? = null
        entries.forEach { entry ->
            if (contains(entry.range, position) &&
                (best == null || compareEntries(entry, best!!) < 0)
            ) {
                best = entry
            }
        }
        return best?.node
    }

    fun findEnclosing(position: Position): List<BaseASTNode> {
        return entries.asSequence()
            .filter { contains(it.range, position) }
            .sortedWith(::compareEntries)
            .map(Entry::node)
            .toList()
    }

    /** The model and workspace facade share one caret-to-member decision. */
    fun completionMemberAt(position: Position): MemberExpression? =
        memberExpressions.asSequence().filter { it.isCompletionSite(position) }
            .maxWithOrNull(compareBy<MemberExpression> { it.base.range.end.line }
                .thenBy { it.base.range.end.column }
                .thenBy { it.range.start.line }
                .thenBy { it.range.start.column }
                .thenByDescending { it.range.end.line }
                .thenByDescending { it.range.end.column }
                .thenBy { if (it.identifier.name.isBlank()) 1 else 0 })

    private fun containsNodeRange(range: Range): Boolean {
        return comparePositions(range.start, range.end) < 0
    }

    private fun contains(range: Range, position: Position): Boolean {
        return comparePositions(range.start, position) <= 0 && comparePositions(position, range.end) < 0
    }

    private fun compareSpecificity(a: Range, b: Range): Int {
        val startComparison = comparePositions(b.start, a.start)
        if (startComparison != 0) {
            return startComparison
        }
        return comparePositions(a.end, b.end)
    }

    private fun compareEntries(a: Entry, b: Entry): Int {
        val specificity = compareSpecificity(a.range, b.range)
        return if (specificity != 0) specificity else b.order.compareTo(a.order)
    }
}

internal fun MemberExpression.isCompletionSite(position: Position): Boolean {
    // Receivers of completed accesses stay lexical. The indexer, member prefix and
    // trailing blank member are completion sites, including half-open range ends.
    if (comparePositions(position, base.range.end) < 0) return false
    if (comparePositions(position, range.start) < 0) return false
    val end = range.end
    return comparePositions(position, end) <= 0 ||
        (position.line == end.line && position.column == end.column + 1)
}
