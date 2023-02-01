package io.github.dingyi222666.lua.parser.ast.node

import kotlin.properties.Delegates

/**
 * @author: dingyi
 * @date: 2023/2/1
 * @description:
 **/
class MemberExpression : ExpressionNode by ExpressionNodeSupport() {


    var identifier by Delegates.notNull<Identifier>()

    var indexer by Delegates.notNull<String>()

    var base by Delegates.notNull<ExpressionNode>()

}

/**
 * @author: dingyi
 * @date: 2021/10/7 10:48
 * @description:
 **/
class Identifier(var name: String = "") : ExpressionNode by ExpressionNodeSupport() {
    override fun toString(): String {
        return "Identifier(name='$name')"
    }

    var isLocal = false
}


/**
 * @author: dingyi
 * @date: 2021/10/7 10:38
 * @description:
 **/
class ConstantsNode(
    var type: TYPE = TYPE.UNKNOWN,
    value: Any = 0
) : ExpressionNode by ExpressionNodeSupport() {

    private var _value: Any = 0

    var rawValue by Delegates.observable(
        initialValue = Any(),
        onChange = { _, _, newValue ->
            _value = switchValue(newValue)
        }
    )

    private fun switchValue(newValue: Any): Any {
        return when (type) {
            TYPE.INTERGER -> {
                newValue.toString().toIntOrNull()
                    ?: newValue
            }

            TYPE.FLOAT -> {
                newValue.toString().toFloatOrNull() ?: newValue
            }

            //TODO： STRING/LONG STRING
            else -> newValue
        }
    }


    init {
        this.rawValue = value
    }

    enum class TYPE {
        FLOAT, INTERGER, BOOLEAN, STRING, NIL, UNKNOWN
    }

    fun intOf(): Int {
        return _value as Int
    }

    fun floatOf(): Float {
        return _value as Float
    }

    fun booleanOf(): Boolean {
        return _value as Boolean
    }

    fun nilOf(): ConstantsNode = NIL

    override fun toString(): String {
        return "ConstantsNode(type=$type, value=$_value)"
    }

    fun copy(): ConstantsNode = ConstantsNode(type = this.type, value = this.rawValue)

    companion object {
        val NIL = ConstantsNode(value = Any(), type = TYPE.NIL)
    }
}

/**
 * @author: dingyi
 * @date: 2021/10/9 15:00
 * @description:
 **/
class CallExpression : ExpressionNode by ExpressionNodeSupport() {
    var base by Delegates.notNull<ExpressionNode>()
    val arguments = mutableListOf<ExpressionNode>()
}

class VarargLiteral : ExpressionNode by ExpressionNodeSupport() {
    override fun toString(): String {
        return "VarargLiteral()"
    }
}

class FunctionDeclaration : ExpressionNode, StatementNode, ASTNode() {
    var body: BlockNode? = null
    var params = mutableListOf<Identifier>()
    var identifier:ExpressionNode? = null
    var isLocal = false
    override fun toString(): String {
        return "FunctionDeclaration(body=$body, params=$params, identifier=$identifier, isLocal=$isLocal)"
    }


}