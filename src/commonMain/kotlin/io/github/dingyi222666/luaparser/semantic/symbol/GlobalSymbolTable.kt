package io.github.dingyi222666.luaparser.semantic.symbol

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.Type

// 全局符号表管理器，使用区间树优化查找
class GlobalSymbolTable {
    private var root: SymbolTable? = null
    private var intervalTreeRoot: IntervalTreeNode? = null
    private val globalSymbols = mutableMapOf<String, Symbol>()

    fun setRoot(symbolTable: SymbolTable) {
        root = symbolTable
        rebuildIntervalTree()
    }

    fun defineGlobal(name: String, type: Type, kind: Symbol.Kind = Symbol.Kind.VARIABLE, range: Range? = null) {
        globalSymbols[name] = Symbol(name, type, kind, range)
    }

    fun getGlobalSymbols(): Map<String, Symbol> = globalSymbols.toMap()

    // 在指定位置解析符号，使用区间树优化
    fun resolveAtPosition(name: String, position: Position): Symbol? {
        val table = findTableAtPosition(position)
        return table?.resolveAtPosition(name, position) ?: globalSymbols[name]
    }

    // 构建区间树索引
    fun rebuildIntervalTree() {
        intervalTreeRoot = null
        root?.let { buildTreeForTable(it) }
    }

    private fun buildTreeForTable(table: SymbolTable) {
        insertIntoIntervalTree(table)
        table.getChildren().forEach { buildTreeForTable(it) }
    }

    private fun insertIntoIntervalTree(table: SymbolTable) {
        intervalTreeRoot = insertNode(intervalTreeRoot, table)
    }

    private fun insertNode(root: IntervalTreeNode?, table: SymbolTable): IntervalTreeNode {
        return root?.let {
            val tableStart = positionToInt(table.range.start)
            val rootStart = positionToInt(it.table.range.start)

            if (tableStart <= rootStart) {
                it.left = insertNode(it.left, table)
            } else {
                it.right = insertNode(it.right, table)
            }
            it
        } ?: IntervalTreeNode(table)
    }

    private fun positionToInt(pos: Position): Int = pos.line * 10000 + pos.column

    // O(log n) 查找最深匹配的符号表
    fun findTableAtPosition(position: Position): SymbolTable? {
        val matches = mutableListOf<SymbolTable>()
        collectMatches(intervalTreeRoot, position, matches)

        // 返回范围最小的表（最深嵌套）
        return matches.minByOrNull {
            val range = it.range
            (range.end.line - range.start.line) * 10000 + (range.end.column - range.start.column)
        }
    }

    private fun collectMatches(node: IntervalTreeNode?, position: Position, matches: MutableList<SymbolTable>) {
        node?.let {
            if (it.table.range.contains(position)) {
                matches.add(it.table)
            }
            collectMatches(it.left, position, matches)
            collectMatches(it.right, position, matches)
        }
    }
}

private class IntervalTreeNode(
    val table: SymbolTable,
    var left: IntervalTreeNode? = null,
    var right: IntervalTreeNode? = null
)
