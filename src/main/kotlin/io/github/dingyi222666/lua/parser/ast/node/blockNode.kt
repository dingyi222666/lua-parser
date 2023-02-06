package io.github.dingyi222666.lua.parser.ast.node

import io.github.dingyi222666.lua.parser.ast.visitor.ASTVisitor
import kotlin.properties.Delegates

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

}