package io.github.dingyi222666.lua.parser.ast.node

import kotlin.properties.Delegates

/**
 * @author: dingyi
 * @date: 2021/10/7 10:11
 * @description:
 **/
class BlockNode() : ASTNode() {

    constructor(vararg statements: StatementNode) : this() {
        this.statements.addAll(statements)
    }

    val statements = mutableListOf<StatementNode>()

    var returnStatement: StatementNode? = null

    fun addStatement(statement: StatementNode) {
        statements.add(statement)
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
    lateinit var body:BlockNode
    override fun toString(): String {
        return "ChunkNode(body=$body)"
    }

}