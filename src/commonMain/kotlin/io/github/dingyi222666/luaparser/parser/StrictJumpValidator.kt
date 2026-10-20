package io.github.dingyi222666.luaparser.parser

import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor

/** Checks block visibility and local lifetimes after forward labels are known. */
internal class StrictJumpValidator : ASTVisitor<Unit> {
    private class Label(var locals: Set<BaseASTNode> = emptySet())
    private class Block(val parent: Block?) {
        val labels = mutableMapOf<String, Label>()
    }
    private class Jump(val node: GotoStatement, val block: Block, val locals: Set<BaseASTNode>)

    private var block: Block? = null
    private var locals = linkedSetOf<BaseASTNode>()
    private val jumps = mutableListOf<Jump>()

    fun validate(chunk: ChunkNode) {
        visitChunkNode(chunk, Unit)
        jumps.forEach { jump ->
            var scope: Block? = jump.block
            var target: Label? = null
            while (scope != null && target == null) {
                target = scope.labels[jump.node.identifier.name]
                scope = scope.parent
            }
            checkNotNull(target) { "no visible label '${jump.node.identifier.name}' for goto at ${jump.node.range.start}" }
            check(jump.locals.containsAll(target.locals)) {
                "goto '${jump.node.identifier.name}' enters a local variable's scope at ${jump.node.range.start}"
            }
        }
    }

    override fun visitBlockNode(node: BlockNode, value: Unit) {
        val enclosingBlock = block
        val enclosingLocals = locals
        val current = Block(enclosingBlock)
        block = current
        locals = LinkedHashSet(enclosingLocals)
        node.statements.filterIsInstance<LabelStatement>().forEach { label ->
            check(current.labels.put(label.identifier.name, Label()) == null) {
                "duplicate label '${label.identifier.name}' at ${label.range.start}"
            }
        }
        // A trailing label lies outside this block's locals, except in repeat
        // bodies whose locals remain live through the until condition.
        val lastExecutable = if (node.returnStatement != null) node.statements.size else
            node.statements.indexOfLast { it !is LabelStatement && it !is CommentStatement }
        try {
            node.statements.forEachIndexed { index, statement ->
                if (statement is LabelStatement) {
                    current.labels.getValue(statement.identifier.name).locals =
                        if (index > lastExecutable && node.parent !is RepeatStatement) enclosingLocals.toSet()
                        else locals.toSet()
                }
                visitStatementNode(statement, value)
                when (statement) {
                    is LocalStatement -> locals.addAll(statement.init)
                    is FunctionDeclaration -> if (statement.isLocal) locals.add(statement)
                    else -> Unit
                }
            }
            node.returnStatement?.let { visitStatementNode(it, value) }
        } finally {
            block = enclosingBlock
            locals = enclosingLocals
        }
    }

    override fun visitGotoStatement(node: GotoStatement, value: Unit) {
        jumps += Jump(node, checkNotNull(block), locals.toSet())
    }

    override fun visitFunctionDeclaration(node: FunctionDeclaration, value: Unit) {
        val enclosingBlock = block
        val enclosingLocals = locals
        block = null
        locals = linkedSetOf()
        try {
            node.body?.let { visitBlockNode(it, value) }
        } finally {
            block = enclosingBlock
            locals = enclosingLocals
        }
    }

    override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) = Unit
}
