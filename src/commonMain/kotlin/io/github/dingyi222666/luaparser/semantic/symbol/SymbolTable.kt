package io.github.dingyi222666.luaparser.semantic.symbol

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.Type

/**
 * Legacy symbol table facade retained for compatibility with [io.github.dingyi222666.luaparser.semantic.SemanticAnalyzer].
 *
 * New semantic consumers should prefer [io.github.dingyi222666.luaparser.semantic.model.SemanticModel]
 * and [io.github.dingyi222666.luaparser.semantic.api.Scope].
 */
class SymbolTable(
    private val parent: SymbolTable? = null,
    val range: Range,
    val kind: ScopeKind = ScopeKind.BLOCK,
    val owner: BaseASTNode? = null
) {
    private val symbols = linkedMapOf<String, MutableList<Symbol>>()
    private val children = mutableListOf<SymbolTable>()

    fun define(
        name: String,
        type: Type,
        kind: Symbol.Kind = Symbol.Kind.VARIABLE,
        range: Range? = null,
        declaration: BaseASTNode? = null
    ): Symbol {
        val symbol = Symbol(name, type, kind, range, declaration)
        symbols.getOrPut(name) { mutableListOf() }.add(symbol)
        return symbol
    }

    fun resolveAtPosition(name: String, position: Position): Symbol? {
        val local = symbols[name]
            ?.filter { symbol -> symbol.range?.start?.isBeforeOrEqual(position) ?: true }
            ?.lastOrNull()

        return local ?: parent?.resolveAtPosition(name, position)
    }

    fun resolve(name: String): Symbol? = symbols[name]?.lastOrNull() ?: parent?.resolve(name)

    fun getAllVisibleSymbols(position: Position? = null): List<Symbol> {
        val visible = linkedMapOf<String, Symbol>()

        symbols.forEach { (name, entries) ->
            val symbol = if (position == null) {
                entries.lastOrNull()
            } else {
                entries.filter { it.range?.start?.isBeforeOrEqual(position) ?: true }.lastOrNull()
            }
            if (symbol != null) {
                visible[name] = symbol
            }
        }

        parent?.getAllVisibleSymbols(position)?.forEach { symbol ->
            if (symbol.name !in visible) {
                visible[symbol.name] = symbol
            }
        }

        return visible.values.toList()
    }

    fun createChild(range: Range, kind: ScopeKind = ScopeKind.BLOCK, owner: BaseASTNode? = null): SymbolTable =
        SymbolTable(this, range, kind, owner).also { children.add(it) }

    fun getChildren(): List<SymbolTable> = children

    fun getParent(): SymbolTable? = parent

    override fun toString(): String = toString(0)

    private fun toString(indent: Int): String = buildString {
        val indentStr = "  ".repeat(indent)
        val innerIndent = "  ".repeat(indent + 1)

        appendLine("${indentStr}SymbolTable(kind=$kind) {")
        appendLine("${innerIndent}range: $range,")

        if (symbols.isNotEmpty()) {
            appendLine("${innerIndent}symbols: [")
            symbols.values.flatten().forEach { symbol ->
                appendLine("${innerIndent}  ${symbol.name}: ${symbol.type.name} (${symbol.kind})")
            }
            appendLine("${innerIndent}],")
        }

        if (children.isNotEmpty()) {
            appendLine("${innerIndent}children: [")
            children.forEach { child ->
                append(child.toString(indent + 2))
                appendLine()
            }
            appendLine("${innerIndent}]")
        }

        append("${indentStr}}")
    }

    enum class ScopeKind {
        CHUNK,
        BLOCK,
        FUNCTION,
        MODULE,
        LOOP,
        CONDITIONAL
    }
}

data class Symbol(
    val name: String,
    val type: Type,
    val kind: Kind,
    val range: Range? = null,
    val declaration: BaseASTNode? = null
) {
    enum class Kind {
        VARIABLE,
        FUNCTION,
        MODULE,
        PARAMETER,
        LOCAL,
        CLASS,
        TYPE_ALIAS,
        FIELD,
        METHOD
    }

    override fun toString(): String = "$name: ${type.name} (${kind.name})"
}

fun Range.contains(position: Position): Boolean =
    start.isBeforeOrEqual(position) && position.isBeforeOrEqual(end)

fun Position.isBeforeOrEqual(other: Position): Boolean {
    return when {
        line < other.line -> true
        line > other.line -> false
        else -> column <= other.column
    }
}
