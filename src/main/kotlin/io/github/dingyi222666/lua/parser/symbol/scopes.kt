package io.github.dingyi222666.lua.parser.symbol

import io.github.dingyi222666.lua.parser.ast.node.Position
import io.github.dingyi222666.lua.parser.ast.node.Range
import io.github.dingyi222666.lua.parser.util.require

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/
interface Scope {

    fun resolveSymbol(symbolName: String): Symbol?

    fun resolveSymbol(symbolName: String, position: Position): Symbol?

    fun addSymbol(symbol: Symbol)

    fun removeSymbol(symbol: Symbol)

    fun renameSymbol(symbol: Symbol, newName: String)

    var range: Range
}

abstract class BaseScope(
    val parent: Scope?,
    override var range: Range
) : Scope {
    protected val symbolMap = mutableMapOf<String, Symbol>()
    override fun resolveSymbol(symbolName: String): Symbol? {
        return symbolMap[symbolName]
    }

    override fun resolveSymbol(symbolName: String, position: Position): Symbol? {
        return symbolMap[symbolName] ?: return parent?.resolveSymbol(
            symbolName, position
        )
    }

    override fun removeSymbol(symbol: Symbol) {
        symbolMap.remove(symbol.variable)
    }

    override fun renameSymbol(symbol: Symbol, newName: String) {
        symbolMap.remove(symbol.variable)
        symbolMap[newName] = symbol
    }

    override fun addSymbol(symbol: Symbol) {
        symbolMap[symbol.variable] = symbol
    }

}

class GlobalScope(
    range: Range
) : BaseScope(null, range) {
    private val childScopes = mutableListOf<Scope>()

    fun addScope(scope: Scope) {
        childScopes.add(scope)
    }

    fun removeScope(scope: Scope) {
        childScopes.remove(scope)
    }

    private fun binarySearchScope(position: Position): Scope? {
        var low = 0
        var high = childScopes.size - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val midVal = childScopes[mid].range
            if (midVal.end.compareTo(position) == 0) {
                return childScopes[mid]
            } else if (midVal.end > position) {
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return childScopes.getOrNull(high)
    }

    override fun resolveSymbol(symbolName: String): Symbol? {
        return resolveSymbol(symbolName, Position.EMPTY)
    }

    override fun resolveSymbol(symbolName: String, position: Position): Symbol? {
        val scope = binarySearchScope(position) ?: this
        return scope.resolveSymbol(symbolName, position)
    }
}


class LoopScope(
    parent: Scope,
    range: Range
) : BaseScope(parent, range)

class LocalScope(
    parent: Scope,
    range: Range
) : BaseScope(parent, range)