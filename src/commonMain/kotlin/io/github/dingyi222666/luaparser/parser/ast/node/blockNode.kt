package io.github.dingyi222666.luaparser.parser.ast.node

import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor

private fun <T : BaseASTNode> T.copyBlockCloneMetadataFrom(source: BaseASTNode): T = also {
    range = source.range.copy()
    bad = source.bad
}

private fun <T : BaseASTNode> T.withBlockCloneParent(parentNode: BaseASTNode): T = also {
    parent = parentNode
}

/**
 * @author: dingyi
 * @date: 2021/10/7 10:11
 * @description:
 **/
class BlockNode : ASTNode() {

    val statements = mutableListOf<StatementNode>()

    var returnStatement: ReturnStatement? = null

    fun addStatement(statement: StatementNode) {
        statements.add(statement)
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitBlockNode(this, value)
    }

    override fun toString(): String {
        return "BlockNode(statements=$statements, returnStatement=$returnStatement)"
    }

    override fun clone(): BlockNode {
        return BlockNode().copyBlockCloneMetadataFrom(this).also { block ->
            statements.forEach {
                block.statements.add(it.clone().withBlockCloneParent(block))
            }
            block.returnStatement = returnStatement?.clone()?.withBlockCloneParent(block)
        }
    }

}

/**
 * @author: dingyi
 * @date: 2021/10/7 10:10
 * @description:
 **/
class ChunkNode : ASTNode() {

    lateinit var body: BlockNode

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitChunkNode(this, value)
    }

    override fun toString(): String {
        return "ChunkNode(body=$body)"
    }

    override fun clone(): ChunkNode {
        return ChunkNode().copyBlockCloneMetadataFrom(this).also { chunk ->
            chunk.body = body.clone().withBlockCloneParent(chunk)
        }
    }

}
