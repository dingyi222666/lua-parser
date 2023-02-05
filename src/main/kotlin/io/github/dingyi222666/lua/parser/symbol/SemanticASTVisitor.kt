package io.github.dingyi222666.lua.parser.symbol

import io.github.dingyi222666.lua.parser.ast.node.*
import io.github.dingyi222666.lua.parser.ast.visitor.ASTVisitor

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/
class SemanticASTVisitor : ASTVisitor<BaseASTNode> {

    private val scopeStack = ArrayDeque<Scope>()

    private val globalScope = GlobalScope(range = Range.EMPTY)

    private fun createLocalScope(node: BaseASTNode): LocalScope {
        val parent = scopeStack.first()
        val localScope = LocalScope(parent, node.range)
        scopeStack.addFirst(localScope)
        globalScope.addScope(localScope)
        return localScope
    }

    private fun createLoopScope(node: BaseASTNode): LoopScope {
        val parent = scopeStack.first()
        val loopScope = LoopScope(parent, node.range)
        scopeStack.addFirst(loopScope)
        globalScope.addScope(loopScope)
        return loopScope
    }

    private fun destroyScope() {
        scopeStack.removeFirst()
    }

    fun analyze(node: ChunkNode):GlobalScope {
        globalScope.range = node.range
        scopeStack.addFirst(globalScope)
        createLocalScope(node)
        visitChunkNode(node, node)
        return globalScope
    }


    override fun visitChunkNode(node: ChunkNode, value: BaseASTNode) {
        globalScope.range = node.range
        scopeStack.addFirst(globalScope)
        createLocalScope(node)
        super.visitChunkNode(node, value)
        destroyScope()
    }

    override fun visitBlockNode(node: BlockNode, value: BaseASTNode) {
        super.visitBlockNode(node, node)
        destroyScope()
    }

    override fun visitStatementNode(node: StatementNode, value: BaseASTNode) {
        super.visitStatementNode(node, node)
    }

    override fun visitLocalStatement(node: LocalStatement, value: BaseASTNode) {
        super.visitLocalStatement(node, value)
    }

    override fun visitFunctionDeclaration(node: FunctionDeclaration, value: BaseASTNode) {
        if (node.isLocal && node.identifier is Identifier) {
            createLocalFunctionName(node)
        }
        node.body?.let {
            createLocalScope(it)
            visitBlockNode(it, value)
        }
    }

    private fun createLocalFunctionName(node: FunctionDeclaration) {
        val currentScope = scopeStack.first()
        val identifier = node.identifier as Identifier

        val symbol = FunctionSymbol(
            variable = identifier.name,
            // 范围是整个local作用域
            range = Range(
                node.range.start,
                currentScope.range.end
            ),
            node = node,
            isLocal = node.isLocal
        )
        currentScope.addSymbol(symbol)
    }
}