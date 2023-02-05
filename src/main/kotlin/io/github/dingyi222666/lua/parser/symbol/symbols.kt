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
    var type: Type
    val range: Range
}

open class VariableSymbol(
    override val variable: String,
    open override var type: Type,
    override val range: Range,
    val isLocal: Boolean,
    open val node: ExpressionNode
) : Symbol {
    override fun toString(): String {
        return "VariableSymbol(variable='$variable', type=$type, range=$range, isLocal=$isLocal, node=$node)"
    }
}

class FunctionSymbol(
    override val variable: String,
    override val range: Range,
    override val node: FunctionDeclaration,
    isLocal: Boolean
) : VariableSymbol(variable, BaseType.FUNCTION, range, isLocal, node) {
    override var type: Type = BaseType.FUNCTION

    override fun toString(): String {
        return "FunctionSymbol(variable='$variable', type=$type, range=$range, isLocal=$isLocal, node=$node)"
    }
}

class TableSymbol(
    override val variable: String,
    override val range: Range,
    override val node: TableConstructorExpression,
    isLocal: Boolean
) : VariableSymbol(variable, BaseType.TABLE, range, isLocal, node) {
    override var type: Type = BaseType.TABLE

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
    override var type: Type,
    override val range: Range,
    val node: ExpressionNode
) : Symbol

class UnknownSymbol(
    override val variable: String,
    override val range: Range,
    val node: ExpressionNode
) : Symbol {
    override var type: Type = BaseType.ANY
}

class UnknownLikeTableSymbol(
    override val variable: String,
    override val range: Range,
    val node: ExpressionNode
) : Symbol {
    override var type: Type = BaseType.ANY

    internal val keyValues = mutableMapOf<String, Symbol>()

    fun addKeyValue(key: String, value: Symbol) {
        keyValues[key] = value
    }

    fun getKeyValue(key: String): Symbol? {
        return keyValues[key]
    }

    fun getKeyValueLikeLua(key: String): Symbol? {
        val splitList = key.split(":", ".")
        var result: Symbol? = this
        for (split in splitList) {
            if (result is UnknownLikeTableSymbol) {
                result = result.getKeyValue(split)
            } else {
                return null
            }
        }
        return result
    }

    override fun toString(): String {
        return "UnknownLikeTableSymbol(variable='$variable', range=$range, node=$node, type=$type, keyValues=$keyValues)"
    }


}


