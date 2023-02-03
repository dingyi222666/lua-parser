package io.github.dingyi222666.lua.parser.ast.node

import com.google.gson.annotations.SerializedName
import kotlin.properties.Delegates


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
class ConstantNode(
    var constantType: TYPE = TYPE.UNKNOWN,
    value: Any = 0
) : ExpressionNode by ExpressionNodeSupport() {


    @SerializedName("value")
    private var _value: Any = 0

    @delegate:Transient
    var rawValue by Delegates.observable(
        initialValue = Any(),
        onChange = { _, _, newValue ->
            _value = switchValue(newValue)
        }
    )

    private fun switchValue(newValue: Any): Any {
        return when (constantType) {
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

    fun nilOf(): ConstantNode = NIL

    override fun toString(): String {
        return "ConstantsNode(type=$constantType, value=$_value)"
    }

    fun copy(): ConstantNode = ConstantNode(constantType = this.constantType, value = this.rawValue)

    companion object {
        val NIL = ConstantNode(value = Any(), constantType = TYPE.NIL)
    }
}

/**
 * @author: dingyi
 * @date: 2021/10/9 15:00
 * @description:
 **/
open class CallExpression : ExpressionNode by ExpressionNodeSupport() {
    lateinit var base: ExpressionNode
    val arguments = mutableListOf<ExpressionNode>()
    override fun toString(): String {
        return "CallExpression(base=$base, arguments=$arguments)"
    }

}

class StringCallExpression  : CallExpression() {
    override fun toString(): String {
        return "StringCallExpression(base=$base, arguments=$arguments)"
    }
}


class MemberExpression : ExpressionNode by ExpressionNodeSupport() {
    lateinit var identifier: Identifier
    var indexer: String = "."
    lateinit var base: ExpressionNode
    override fun toString(): String {
        return "MemberExpression(identifier=$identifier, indexer='$indexer', base=$base)"
    }

}

class IndexExpression : ExpressionNode by ExpressionNodeSupport() {
    lateinit var index: ExpressionNode
    lateinit var base: ExpressionNode
}

class VarargLiteral : ExpressionNode by ExpressionNodeSupport() {
    override fun toString(): String {
        return "VarargLiteral()"
    }
}

class UnaryExpression : ExpressionNode by ExpressionNodeSupport() {
    lateinit var operator: ExpressionOperator
    lateinit var arg: ExpressionNode
    override fun toString(): String {
        return "UnaryExpression(operator=$operator, arg=$arg)"
    }

}

class BinaryExpression : ExpressionNode by ExpressionNodeSupport() {
    var left /*by Delegates.notNull<*/: ExpressionNode? = null
    var right: ExpressionNode? = null
    var operator: ExpressionOperator? = null
    override fun toString(): String {
        return "BinaryExpression(left=$left, right=$right, operator=$operator)"
    }

}

enum class ExpressionOperator(val value: String) {
    NOT("not"), GETLEN("#"), BIT_TILDE("~"), MINUS("-"),
    ADD("+"), DIV("/"), OR("or"), MULT("*"), BIT_EXP("^"),
    LT("<"), BIT_LT("<<"), GT(">"), BIT_GT(">>"), BIT_OR("|"),
    BIT_AND("&"), CONCAT(".."), LE("<="), GE(">="), EQ("=="),
    NE("~="), DOUBLE_DIV("//"), MOD("%")
}

class FunctionDeclaration : ExpressionNode, StatementNode, ASTNode() {
    var body: BlockNode? = null
    var params = mutableListOf<Identifier>()
    var identifier: ExpressionNode? = null
    var isLocal = false
    override fun toString(): String {
        return "FunctionDeclaration(body=$body, params=$params, identifier=$identifier, isLocal=$isLocal)"
    }

}