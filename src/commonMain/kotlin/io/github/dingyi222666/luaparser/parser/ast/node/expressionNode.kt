package io.github.dingyi222666.luaparser.parser.ast.node

import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.util.parseLuaString
import kotlin.jvm.Transient
import kotlin.properties.Delegates


/**
 * @author: dingyi
 * @date: 2021/10/7 10:48
 * @description:
 **/
open class Identifier(open var name: String = "") : ExpressionNode, ASTNode() {
    open var isLocal = false

    override fun toString(): String {
        return "Identifier(name='$name')"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitIdentifier(this, value)
    }

    override fun clone(): Identifier {
        return Identifier(name = name).also {
            it.isLocal = isLocal
        }
    }

}

/**
 * @author: dingyi
 * @date: 2024/9/19 18:04
 * @description:
 **/
class AttributeIdentifier(
    override var name: String = "",
    var attributeName: String? = null
) : Identifier(name) {
    override var isLocal = true
    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitAttributeIdentifier(this, value)
    }

    override fun clone(): AttributeIdentifier {
        return AttributeIdentifier(name = name, attributeName = attributeName).also {
            it.isLocal = true
        }
    }
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

            TYPE.BOOLEAN -> {
                newValue.toString()
            }

            TYPE.NIL -> "nil"

            else -> newValue
        }
    }


    init {
        this.rawValue = value
    }

    enum class TYPE {
        FLOAT, INTERGER, BOOLEAN, STRING, NIL, UNKNOWN
    }

    fun stringOf(): String {
        return parseLuaString(rawValue.toString())
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

    override fun clone(): ConstantNode = ConstantNode(constantType = this.constantType, value = this.rawValue)

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitConstantNode(this, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (this::class != other::class) return false

        other as ConstantNode

        if (constantType != other.constantType) return false
        if (_value != other._value) return false
        return rawValue == other.rawValue
    }

    override fun hashCode(): Int {
        var result = constantType.hashCode()
        result = 31 * result + _value.hashCode()
        result = 31 * result + rawValue.hashCode()
        return result
    }

    companion object {
        val NIL = ConstantNode(value = "nil", constantType = TYPE.NIL)
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

    override fun clone(): CallExpression {
        return CallExpression().also {
            it.base = base.clone()
            for (argument in arguments) {
                it.arguments.add(argument.clone())
            }
        }
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

    override fun clone(): MemberExpression {
        return MemberExpression().also {
            it.identifier = identifier.clone()
            it.base = base.clone()
            it.indexer = indexer
        }
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

    override fun clone(): IndexExpression {
        return IndexExpression().also {
            it.base = base.clone()
            it.index = index.clone()
        }
    }
}

class VarargLiteral : ExpressionNode, ASTNode() {

    override fun toString(): String {
        return "VarargLiteral()"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitVarargLiteral(this, value)
    }

    override fun clone(): VarargLiteral {
        return VarargLiteral()
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

    override fun clone(): UnaryExpression {
        return UnaryExpression().also {
            it.operator = operator
            it.arg = arg.clone()
        }
    }


}

class BinaryExpression : ExpressionNode, ASTNode() {
    var left /*by Delegates.notNull<*/: ExpressionNode? = null
    var right: ExpressionNode? = null
    lateinit var operator: ExpressionOperator/*? = null*/
    override fun toString(): String {
        return "BinaryExpression(left=$left, right=$right, operator=$operator)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitBinaryExpression(this, value)
    }

    override fun clone(): BinaryExpression {
        return BinaryExpression().also {
            it.operator = operator
            it.left = left?.clone()
            it.right = right?.clone()
        }
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

    override fun clone(): TableConstructorExpression {
        return TableConstructorExpression().also {
            for (field in fields) {
                it.fields.add(field.clone())
            }
        }
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

    override fun clone(): ArrayConstructorExpression {
        return ArrayConstructorExpression().also {
            for (value in values) {
                it.values.add(value.clone())
            }
        }
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

    override fun clone(): LambdaDeclaration {
        return LambdaDeclaration().also { declaration ->
            declaration.params.addAll(params.map { it.clone() })

            declaration.expression = expression.clone()
        }
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

    override fun clone(): FunctionDeclaration {
        return FunctionDeclaration().also { declaration ->
            declaration.body = body?.clone()
            declaration.params.addAll(params.map { it.clone() })
            declaration.identifier = identifier?.clone()
            declaration.isLocal = isLocal
        }
    }
}

