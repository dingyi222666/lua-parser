package io.github.dingyi222666.parser.lua.ast.node

import kotlin.properties.Delegates

/**
 * @author: dingyi
 * @date: 2023/2/1
 * @description:
 **/
class MemberExpression : ExpressionNode() {


    var identifier by Delegates.notNull<Identifier>()

    var indexer by Delegates.notNull<String>()

    var base by Delegates.notNull<ExpressionNode>()

}

/**
 * @author: dingyi
 * @date: 2021/10/7 10:48
 * @description:
 **/
class Identifier(var name: String = "") : ExpressionNode() {
    override fun toString(): String {
        return "Identifier(name='$name')"
    }
}


/**
 * @author: dingyi
 * @date: 2021/10/7 10:38
 * @description:
 **/
class ConstantsNode(
    var type: TYPE = TYPE.UNKNOWN,
    value: Any = 0
) : ExpressionNode() {

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

    companion object {
        val NIL = ConstantsNode(value = Any(), type = ConstantsNode.TYPE.NIL)
    }
}

/**
 * @author: dingyi
 * @date: 2021/10/9 15:00
 * @description:
 **/
class CallExpression : ExpressionNode() {
    var base by Delegates.notNull<ExpressionNode>()
    val arguments = mutableListOf<ExpressionNode>()
}