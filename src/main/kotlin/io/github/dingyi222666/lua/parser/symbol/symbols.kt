package io.github.dingyi222666.lua.parser.symbol

import io.github.dingyi222666.lua.parser.ast.node.ASTNode
import io.github.dingyi222666.lua.parser.ast.node.ConstantNode
import io.github.dingyi222666.lua.parser.ast.node.Position
import io.github.dingyi222666.lua.parser.typesystem.Type
import java.lang.reflect.TypeVariable

/**
 * @author: dingyi
 * @date: 2023/2/5
 * @description:
 **/

data class Symbol(
    val variable: String,
    val metadata: SymbolMetaData,
    val type: Type
)

data class SymbolMetaData(
    var isLocal: Boolean,
    var initPosition: Position,
    var isInit: Boolean = false
)

