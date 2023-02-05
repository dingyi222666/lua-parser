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

    fun addChild(scope: Scope)

    var range: Range
}

abstract class BaseScope(
    override var range: Range
) : Scope {
    protected val symbolMap = mutableMapOf<String, Symbol>()
    protected val childScopes = mutableListOf<Scope>()
    override fun resolveSymbol(symbolName: String): Symbol? {
        return symbolMap[symbolName]
    }

    override fun resolveSymbol(symbolName: String, position: Position): Symbol? {
        return symbolMap[symbolName] ?: return resolveSymbolInChild(
            symbolName, position
        )
    }

    private fun resolveSymbolInChild(symbolName: String, position: Position): Symbol? {
        val matchScope = childScopes
            .filter {
                it.range
                    .start <= position ||
                        it.range.end >= position
            }

        for (scope in matchScope) {
            val symbol = scope.resolveSymbol(symbolName, position)
            if (symbol != null) {
                return symbol
            }
        }
        return null
    }

    override fun addSymbol(symbol: Symbol) {
        symbolMap[symbol.variable] = symbol
    }

    override fun addChild(scope: Scope) {
        childScopes.add(scope)
    }
}

class GlobalScope(
    range: Range
) : BaseScope(range) {


}