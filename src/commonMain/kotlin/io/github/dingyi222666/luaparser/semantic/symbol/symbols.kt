package io.github.dingyi222666.luaparser.semantic.symbol

import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.semantic.typesystem.FunctionType
import io.github.dingyi222666.luaparser.semantic.typesystem.ParameterType
import io.github.dingyi222666.luaparser.semantic.typesystem.Type
import io.github.dingyi222666.luaparser.semantic.typesystem.UnknownLikeTableType

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/

interface Symbol<T : Type> {
    val variable: String
    var type: T
    val range: Range
}


open class VariableSymbol(
    override val variable: String,
    override val range: Range,
    val isLocal: Boolean,
    open val node: ExpressionNode,
    override var type: Type,
) : Symbol<Type> {
    override fun toString(): String {
        return "VariableSymbol(variable='$variable', type=$type, range=$range, isLocal=$isLocal, node=$node)"
    }
}

open class StatementSymbol(
    override val variable: String,
    override val range: Range,
    val node: StatementNode,
    override var type: Type,
) : Symbol<Type> {
    override fun toString(): String {
        return "StatementSymbol(variable='$variable', type=$type, range=$range, node=$node)"
    }
}

class FunctionSymbol(
    override val variable: String,
    override val range: Range,
    val node: FunctionDeclaration,
    override var type: FunctionType = FunctionType(variable)
) : Symbol<FunctionType> {

    var isLocal = false


    override fun toString(): String {
        return "FunctionSymbol(variable='$variable', type=$type, range=$range, isLocal=$isLocal, node=$node"
    }
}

class ParameterSymbol(
    override val variable: String,
    override val range: Range,
    val node: Identifier
) : Symbol<ParameterType> {

    override var type: ParameterType = ParameterType(variable)

    override fun toString(): String {
        return "ParameterSymbol(variable='$variable', type=$type, range=$range, node=$node)"
    }
}


class UnknownLikeTableSymbol(
    override val variable: String,
    override val range: Range,
    val node: ExpressionNode,
) : Symbol<UnknownLikeTableType> {

    val isLocal = false

    override var type: UnknownLikeTableType = UnknownLikeTableType(variable)

    override fun toString(): String {
        return "UnknownLikeTableSymbol(variable='$variable', type=$type, range=$range, isLocal=$isLocal, node=$node)"
    }
}



