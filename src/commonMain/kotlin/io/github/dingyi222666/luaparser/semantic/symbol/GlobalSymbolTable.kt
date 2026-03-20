package io.github.dingyi222666.luaparser.semantic.symbol

import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.Type

/**
 * Legacy global scope facade retained for compatibility with [io.github.dingyi222666.luaparser.semantic.SemanticAnalyzer].
 *
 * This table is populated from pipeline output and does not represent a separate analyzer.
 */
class GlobalSymbolTable {
    private var root: SymbolTable? = null
    private val globalSymbols = mutableMapOf<String, Symbol>()

    fun setRoot(symbolTable: SymbolTable) {
        root = symbolTable
    }

    fun defineGlobal(
        name: String,
        type: Type,
        kind: Symbol.Kind = Symbol.Kind.VARIABLE,
        range: Range? = null
    ) {
        globalSymbols[name] = Symbol(name, type, kind, range)
    }

    fun getGlobalSymbols(): Map<String, Symbol> = globalSymbols.toMap()

    fun resolveAtPosition(name: String, position: Position): Symbol? {
        return findTableAtPosition(position)?.resolveAtPosition(name, position) ?: globalSymbols[name]
    }

    fun rebuildIntervalTree() {
        // Kept for API compatibility. Symbol tables are resolved directly from the scope tree now.
    }

    fun findTableAtPosition(position: Position): SymbolTable? {
        return root?.let { findDeepestScope(it, position) }
    }

    private fun findDeepestScope(table: SymbolTable, position: Position): SymbolTable? {
        if (!table.range.contains(position)) {
            return null
        }

        val child = table.getChildren()
            .mapNotNull { childTable -> findDeepestScope(childTable, position) }
            .minByOrNull { scope ->
                val range = scope.range
                (range.end.line - range.start.line) * 10000 + (range.end.column - range.start.column)
            }

        return child ?: table
    }
}
