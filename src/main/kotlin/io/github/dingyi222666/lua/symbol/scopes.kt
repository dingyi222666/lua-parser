package io.github.dingyi222666.lua.symbol

import io.github.dingyi222666.lua.parser.ast.node.Position
import io.github.dingyi222666.lua.parser.ast.node.Range
import io.github.dingyi222666.lua.typesystem.Type
import io.github.dingyi222666.lua.util.require

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/
interface Scope {

    fun resolveSymbol(symbolName: String): Symbol<Type>?

    fun resolveSymbol(symbolName: String, position: Position, onlyResolveOnThisScope: Boolean = false): Symbol<Type>?

    fun addSymbol(symbol: Symbol<Type>)

    fun removeSymbol(symbol: Symbol<Type>)

    fun renameSymbol(oldName: String, newSymbol: Symbol<Type>)

    var range: Range
}

abstract class BaseScope(
    val parent: Scope?,
    override var range: Range
) : Scope {
    protected val symbolMap = mutableMapOf<String, Symbol<Type>>()
    override fun resolveSymbol(symbolName: String): Symbol<Type>? {
        return symbolMap[symbolName]
    }

    override fun resolveSymbol(
        symbolName: String,
        position: Position,
        onlyResolveOnThisScope: Boolean
    ): Symbol<Type>? {
        val scope = symbolMap[symbolName]

        if (scope != null) {
            return scope
        }

        if (onlyResolveOnThisScope) {
            return null
        }

        return if (parent is GlobalScope) {
            parent.resolveSymbol(symbolName)
        } else {
            parent?.resolveSymbol(
                symbolName, position, false
            )
        }

    }

    override fun removeSymbol(symbol: Symbol<Type>) {
        symbolMap.remove(symbol.variable)
    }

    override fun renameSymbol(oldName: String, newSymbol: Symbol<Type>) {
        symbolMap.remove(oldName)
        symbolMap[newSymbol.variable] = newSymbol
    }

    override fun addSymbol(symbol: Symbol<Type>) {
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

    override fun resolveSymbol(symbolName: String): Symbol<Type>? {
        return super.resolveSymbol(symbolName)
    }

    fun resolveScope(position: Position): Scope {
        return binarySearchScope(position) ?: this
    }

    override fun resolveSymbol(
        symbolName: String,
        position: Position,
        onlyResolveOnThisScope: Boolean
    ): Symbol<Type>? {
        val searchSymbol = this.resolveSymbol(symbolName)
        if (onlyResolveOnThisScope || searchSymbol != null) {
            return searchSymbol
        }
        val scope = binarySearchScope(position)
        return scope?.resolveSymbol(symbolName, position)
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

class FunctionScope(
    parent: Scope,
    range: Range
) : BaseScope(parent, range)