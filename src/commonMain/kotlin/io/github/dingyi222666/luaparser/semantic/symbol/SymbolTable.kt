package io.github.dingyi222666.luaparser.semantic.symbol

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.Type


class SymbolTable(
    private val parent: SymbolTable? = null,
    val range: Range
) {
    private val symbols = mutableMapOf<String, Symbol>()
    private val children = mutableListOf<SymbolTable>()

    fun define(name: String, type: Type, kind: Symbol.Kind = Symbol.Kind.VARIABLE, range: Range? = null): Symbol =
        Symbol(name, type, kind, range).also { symbols[name] = it }

    // 在指定位置解析符号
    fun resolveAtPosition(name: String, position: Position): Symbol? {
        return symbols[name] ?: parent?.resolveAtPosition(name, position)
    }

    // 从当前作用域解析符号
    fun resolve(name: String): Symbol? = symbols[name] ?: parent?.resolve(name)

    // 获取所有可见的符号
    fun getAllVisibleSymbols(position: Position? = null): List<Symbol> = buildList {
        addAll(symbols.values)
        parent?.getAllVisibleSymbols(position)?.let(::addAll)
    }

    fun createChild(range: Range): SymbolTable = 
        SymbolTable(this, range).also { children.add(it) }

    fun getChildren(): List<SymbolTable> = children
    fun getParent(): SymbolTable? = parent

    override fun toString(): String = toString(0)
    
    private fun toString(indent: Int): String = buildString {
        val indentStr = "  ".repeat(indent)
        val innerIndentStr = "  ".repeat(indent + 1)
        
        appendLine("${indentStr}SymbolTable {")
        appendLine("${innerIndentStr}range: $range,")
        
        if (symbols.isNotEmpty()) {
            appendLine("${innerIndentStr}symbols: [")
            symbols.values.forEachIndexed { index, symbol ->
                val comma = if (index < symbols.size - 1) "," else ""
                appendLine("$innerIndentStr  ${symbol.name}: ${symbol.type} (${symbol.kind})$comma")
            }
            appendLine("${innerIndentStr}],")
        }
        
        if (children.isNotEmpty()) {
            appendLine("${innerIndentStr}children: [")
            children.forEachIndexed { index, child ->
                val comma = if (index < children.size - 1) "," else ""
                append(child.toString(indent + 2))
                appendLine(comma)
            }
            appendLine("${innerIndentStr}]")
        }
        
        append("${indentStr}}")
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

    override fun toString(): String = "$name: ${type.name} (${kind.name})"
}

// Range 扩展函数
fun Range.contains(position: Position): Boolean = when {
    position.line < start.line -> false
    position.line > end.line -> false
    position.line == start.line && position.column < start.column -> false
    position.line == end.line && position.column > end.column -> false
    else -> true
}