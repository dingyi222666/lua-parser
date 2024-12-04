package io.github.dingyi222666.luaparser.semantic.symbol

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.Type

class SymbolTable(
    private val parent: SymbolTable? = null,
    val range: Range? = null
) {
    private val symbols = mutableMapOf<String, Symbol>()
    private val children = mutableListOf<SymbolTable>()

    fun define(
        name: String, 
        type: Type, 
        kind: Symbol.Kind = Symbol.Kind.VARIABLE,
        range: Range? = null
    ): Symbol {
        val symbol = Symbol(name, type, kind, range)
        symbols[name] = symbol
        return symbol
    }

    // 基于位置查找最合适的符号表，优先匹配最近的作用域
    fun findTableAtPosition(position: Position): SymbolTable? {
        // 如果当前范围不包含该位置，直接返回null
        if (range != null && !range.contains(position)) {
            return null
        }

        // 查找所有匹配的子作用域
        val matchingChildren = children
            .mapNotNull { it.findTableAtPosition(position) }
            .sortedBy { it.range?.let { range -> 
                // 计算范围大小，范围越小越精确
                (range.end.line - range.start.line) * 1000 + 
                    (range.end.column - range.start.column)
            } ?: Int.MAX_VALUE }

        // 返回范围最小的匹配作用域，如果没有则返回当前作用域
        return matchingChildren.firstOrNull() ?: this
    }

    // 在指定位置解析符号，优先从最近的作用域开始查找
    fun resolveAtPosition(name: String, position: Position): Symbol? {
        var currentTable = findTableAtPosition(position)
        
        // 如果找不到匹配的作用域，从父作用域查找
        if (currentTable == null) {
            return parent?.resolveAtPosition(name, position)
        }

        // 在当前作用域中查找
        var symbol = currentTable.symbols[name]
        
        // 如果当前作用域没找到，继续查找父作用域
        while (symbol == null && currentTable?.parent != null) {
            currentTable = currentTable.parent
            symbol = currentTable.symbols[name]
        }

        return symbol
    }

    // 从当前作用域解析符号
    fun resolve(name: String): Symbol? {
        return symbols[name] ?: parent?.resolve(name)
    }

    // 获取所有可见的符号，按作用域距离排序
    fun getAllVisibleSymbols(position: Position? = null): List<Symbol> {
        val result = mutableListOf<Symbol>()
        
        if (position != null) {
            // 找到最近的作用域
            var currentTable = findTableAtPosition(position)
            
            // 收集从最近作用域到根作用域的所有符号
            while (currentTable != null) {
                result.addAll(currentTable.symbols.values)
                currentTable = currentTable.parent
            }
        } else {
            // 如果没有指定位置，收集当前作用域及其父作用域的所有符号
            result.addAll(symbols.values)
            parent?.getAllVisibleSymbols()?.let { result.addAll(it) }
        }
        
        return result
    }

    fun createChild(range: Range? = null): SymbolTable {
        val child = SymbolTable(this, range)
        children.add(child)
        return child
    }

    fun getParent(): SymbolTable? = parent

    override fun toString(): String {
        return buildString {
            append("SymbolTable(")
            append("range=$range, ")
            append("symbols=${symbols.values.joinToString { "${it.name}: ${it.type.name}" }}, ")
            append("children=[")
            children.forEachIndexed { index, child ->
                if (index > 0) append(", ")
                append(child.toString())
            }
            append("])")
        }
    }
}

data class Symbol(
    val name: String,
    val type: Type,
    val kind: Kind,
    val range: Range? = null
) {
    enum class Kind {
        VARIABLE,
        FUNCTION,
        PARAMETER,
        LOCAL,
        CLASS
    }

    override fun toString(): String {
        return "$name: ${type.name} (${kind.name})"
    }
}

// Range 扩展函数
fun Range.contains(position: Position): Boolean {
    return when {
        position.line < start.line -> false
        position.line > end.line -> false
        position.line == start.line && position.column < start.column -> false
        position.line == end.line && position.column > end.column -> false
        else -> true
    }
} 