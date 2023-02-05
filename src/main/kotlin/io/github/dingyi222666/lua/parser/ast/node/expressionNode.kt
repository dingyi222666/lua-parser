package io.github.dingyi222666.lua.parser.ast.node

import com.google.gson.annotations.SerializedName
import io.github.dingyi222666.lua.parser.ast.visitor.ASTVisitor
import kotlin.properties.Delegates


/**
 * @author: dingyi
 * @date: 2021/10/7 10:48
 * @description:
 **/
class Identifier(var name: String = "") : ExpressionNode, ASTNode() {
    override fun toString(): String {
        return "Identifier(name='$name')"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitIdentifier(this, value)
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
) : ExpressionNode, ASTNode() {


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

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitConstantNode(this, value)
    }

    companion object {
        val NIL = ConstantNode(value = Any(), constantType = TYPE.NIL)
    }
}

/**
 * @author: dingyi
 * @date: 2021/10/9 15:00
 * @description:
 **/
open class CallExpression : ExpressionNode, ASTNode() {
    lateinit var base: ExpressionNode
    val arguments = mutableListOf<ExpressionNode>()
    override fun toString(): String {
        return "CallExpression(base=$base, arguments=$arguments)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitCallExpression(this, value)
    }
}

class StringCallExpression : CallExpression() {

    override fun toString(): String {
        return "StringCallExpression(base=$base, arguments=$arguments)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitStringCallExpression(this, value)
    }
}


class TableCallExpression : CallExpression() {

    override fun toString(): String {
        return "TableCallExpression(base=$base, arguments=$arguments)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitTableCallExpression(this, value)
    }
}


class MemberExpression : ExpressionNode, ASTNode() {
    lateinit var identifier: Identifier
    var indexer: String = "."
    lateinit var base: ExpressionNode
    override fun toString(): String {
        return "MemberExpression(identifier=$identifier, indexer='$indexer', base=$base)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitMemberExpression(this, value)
    }

}

class IndexExpression : ExpressionNode, ASTNode() {

    lateinit var index: ExpressionNode
    lateinit var base: ExpressionNode

    override fun toString(): String {
        return "IndexExpression(index=$index, base=$base)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitIndexExpression(this, value)
    }
}

class VarargLiteral : ExpressionNode, ASTNode() {

    override fun toString(): String {
        return "VarargLiteral()"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitVarargLiteral(this, value)
    }
}

class UnaryExpression : ExpressionNode, ASTNode() {
    lateinit var operator: ExpressionOperator
    lateinit var arg: ExpressionNode
    override fun toString(): String {
        return "UnaryExpression(operator=$operator, arg=$arg)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitUnaryExpression(this, value)
    }

}

class BinaryExpression : ExpressionNode, ASTNode() {


    var left /*by Delegates.notNull<*/: ExpressionNode? = null
    var right: ExpressionNode? = null
    var operator: ExpressionOperator? = null
    override fun toString(): String {
        return "BinaryExpression(left=$left, right=$right, operator=$operator)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitBinaryExpression(this, value)
    }
}

class TableConstructorExpression : ExpressionNode, ASTNode() {
    val fields = mutableListOf<TableKey>()

    override fun toString(): String {
        return "TableConstructorExpression(fields=$fields)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitTableConstructorExpression(this, value)
    }
}

class ArrayConstructorExpression : ExpressionNode, ASTNode() {
    val values = mutableListOf<ExpressionNode>()

    override fun toString(): String {
        return "ArrayConstructorExpression(values=$values)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitArrayConstructorExpression(this, value)
    }
}

enum class ExpressionOperator(val value: String) {
    NOT("not"), GETLEN("#"), BIT_TILDE("~"), MINUS("-"),
    ADD("+"), DIV("/"), OR("or"), MULT("*"), BIT_EXP("^"),
    LT("<"), BIT_LT("<<"), GT(">"), BIT_GT(">>"), BIT_OR("|"),
    BIT_AND("&"), CONCAT(".."), LE("<="), GE(">="), EQ("=="),
    NE("~="), DOUBLE_DIV("//"), MOD("%"), AND("and");

    override fun toString(): String {
        return value
    }
}

class LambdaDeclaration : ExpressionNode, ASTNode() {
    val params = mutableListOf<Identifier>()
    lateinit var expression: ExpressionNode

    override fun toString(): String {
        return "LambdaDeclaration(params=$params, expression=$expression)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitLambdaDeclaration(this, value)
    }
}


class FunctionDeclaration : ExpressionNode, StatementNode, ASTNode() {
    var body: BlockNode? = null
    val params = mutableListOf<Identifier>()
    var identifier: ExpressionNode? = null
    var isLocal = false
    override fun toString(): String {
        return "FunctionDeclaration(body=$body, params=$params, identifier=$identifier, isLocal=$isLocal)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitFunctionDeclaration(this, value)
    }
}

