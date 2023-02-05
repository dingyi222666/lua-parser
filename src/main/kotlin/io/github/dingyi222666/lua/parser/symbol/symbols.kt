package io.github.dingyi222666.lua.parser.symbol

import io.github.dingyi222666.lua.parser.ast.node.*
import io.github.dingyi222666.lua.parser.typesystem.BaseType
import io.github.dingyi222666.lua.parser.typesystem.Type
import java.lang.reflect.TypeVariable

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/

interface Symbol {
    val variable: String
    val type: Type
    val range: Range
}

open class VariableSymbol(
    override val variable: String,
    override val type: Type,
    override val range: Range,
    val isLocal: Boolean,
    open val node: ExpressionNode
) : Symbol

class ParamSymbol(
    override val variable: String,
    override val type: Type,
    override val range: Range,
    override val node: ExpressionNode
) : VariableSymbol(variable, type, range, true, node)

class FunctionSymbol(
    override val variable: String,
    override val range: Range,
    override val node: FunctionDeclaration,
    isLocal: Boolean,
    val params: MutableList<ParamSymbol> = mutableListOf()
) : VariableSymbol(variable, BaseType.FUNCTION, range, isLocal, node) {
    override val type = BaseType.FUNCTION
}

class TableSymbol(
    override val variable: String,
    override val range: Range,
    override val node: TableConstructorExpression,
    isLocal: Boolean
) : VariableSymbol(variable, BaseType.TABLE, range, isLocal, node) {
    override val type = BaseType.TABLE

    internal val keyValues = mutableMapOf<String, Symbol>()

    fun addKeyValue(key: String, value: Symbol) {
        keyValues[key] = value
    }

    fun getKeyValue(key: String): Symbol? {
        return keyValues[key]
    }
}

class ExpressionSymbol(
    override val variable: String,
    override val type: Type,
    override val range: Range,
    val node: ExpressionNode
) : Symbol

class UnknownSymbol(
    override val variable: String,
    override val range: Range,
    val node: ExpressionNode
) : Symbol {
    override val type = BaseType.ANY
}

class UnknownLikeTableSymbol(
    override val variable: String,
    override val range: Range,
    val node: ExpressionNode
) : Symbol {
    override val type = BaseType.ANY

    internal val keyValues = mutableMapOf<String, Symbol>()

    fun addKeyValue(key: String, value: Symbol) {
        keyValues[key] = value
    }

    fun getKeyValue(key: String): Symbol? {
        return keyValues[key]
    }
}


