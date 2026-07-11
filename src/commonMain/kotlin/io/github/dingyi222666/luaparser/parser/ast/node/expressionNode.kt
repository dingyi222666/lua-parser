package io.github.dingyi222666.luaparser.parser.ast.node

import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.util.parseLuaString
import kotlin.jvm.Transient
import kotlin.math.pow
import kotlin.properties.Delegates

private fun <T : BaseASTNode> T.copyExpressionCloneMetadataFrom(source: BaseASTNode): T = also {
    range = source.range.copy()
    bad = source.bad
}

private fun <T : BaseASTNode> T.withExpressionCloneParent(parentNode: BaseASTNode): T = also {
    parent = parentNode
}

private fun BlockNode.cloneExpressionBlockFor(parentNode: BaseASTNode): BlockNode =
    clone().copyExpressionCloneMetadataFrom(this).withExpressionCloneParent(parentNode).also { block ->
        block.statements.forEach { it.parent = block }
        block.returnStatement?.parent = block
    }

private fun <T : CallExpression> T.copyCallExpressionFrom(source: CallExpression): T =
    copyExpressionCloneMetadataFrom(source).also { expression ->
        expression.base = source.base.clone().withExpressionCloneParent(expression)
        for (argument in source.arguments) {
            expression.arguments.add(argument.clone().withExpressionCloneParent(expression))
        }
    }


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
        return Identifier(name = name).copyExpressionCloneMetadataFrom(this).also {
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
        return AttributeIdentifier(name = name, attributeName = attributeName).copyExpressionCloneMetadataFrom(this).also {
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
            TYPE.INTERGER -> parseIntegerValue(newValue)

            TYPE.FLOAT -> parseFloatValue(newValue)

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

    /**
     * Safe integer view of this constant.
     *
     * Never throws [ClassCastException]: values that only fit in [Long] are
     * coerced into the Int range, unparseable / oversized lexemes fall back to 0
     * while [rawValue] still holds the original lexeme.
     */
    fun intOf(): Int {
        return when (val v = _value) {
            is Int -> v
            is Long -> v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            is Number -> v.toInt()
            else -> when (val parsed = parseIntegerValue(v)) {
                is Int -> parsed
                is Long -> parsed.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                is Number -> parsed.toInt()
                else -> 0
            }
        }
    }

    /**
     * Safe float view of this constant.
     *
     * Never throws [ClassCastException]: unparseable lexemes (kept as raw text in
     * [_value]) return [Float.NaN] rather than crashing.
     */
    fun floatOf(): Float {
        return when (val v = _value) {
            is Float -> v
            is Double -> v.toFloat()
            is Number -> v.toFloat()
            else -> when (val parsed = parseFloatValue(v)) {
                is Float -> parsed
                is Double -> parsed.toFloat()
                is Number -> parsed.toFloat()
                else -> Float.NaN
            }
        }
    }

    fun booleanOf(): Boolean {
        return _value as Boolean
    }

    fun nilOf(): ConstantNode = NIL

    override fun toString(): String {
        return "ConstantsNode(type=$constantType, value=$_value)"
    }

    override fun clone(): ConstantNode =
        ConstantNode(constantType = this.constantType, value = this.rawValue).copyExpressionCloneMetadataFrom(this)

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

        /**
         * Classify a Lua 5.3 NUMBER lexeme as [TYPE.FLOAT] or [TYPE.INTERGER].
         *
         * Rules (matching Lua 5.3 lexical / numerical forms):
         * - Hex (`0x`/`0X`): float when the lexeme has a `.` fraction and/or a
         *   binary exponent (`p`/`P`); otherwise integer (hex digits may include
         *   `e`/`E`, which must not be treated as a decimal exponent).
         * - Decimal: float when the lexeme has a `.` fraction and/or a decimal
         *   exponent (`e`/`E`); otherwise integer.
         */
        fun typeForNumberLexeme(lexeme: CharSequence): TYPE {
            val text = lexeme.toString()
            if (text.isEmpty()) return TYPE.INTERGER

            val isHex = text.length >= 2 &&
                text[0] == '0' &&
                (text[1] == 'x' || text[1] == 'X')

            return if (isHex) {
                if (text.indexOf('.') >= 0 ||
                    text.indexOf('p') >= 0 ||
                    text.indexOf('P') >= 0
                ) {
                    TYPE.FLOAT
                } else {
                    TYPE.INTERGER
                }
            } else {
                if (text.indexOf('.') >= 0 ||
                    text.indexOf('e') >= 0 ||
                    text.indexOf('E') >= 0
                ) {
                    TYPE.FLOAT
                } else {
                    TYPE.INTERGER
                }
            }
        }

        /** Build a number [ConstantNode] that keeps the raw lexeme and typed value. */
        fun fromNumberLexeme(lexeme: CharSequence): ConstantNode {
            val text = lexeme.toString()
            return ConstantNode(typeForNumberLexeme(text), text)
        }
    }


}

/**
 * Parse an integer-typed constant value.
 *
 * Accepts [Int]/[Long]/[Number], decimal text, and Lua hex integers (`0xFF`).
 * Values outside [Int] are kept as [Long] when possible; otherwise the raw text
 * is retained (no throw).
 */
private fun parseIntegerValue(newValue: Any): Any {
    when (newValue) {
        is Int -> return newValue
        is Long -> {
            return if (newValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                newValue.toInt()
            } else {
                newValue
            }
        }
        is Number -> {
            val asLong = newValue.toLong()
            return if (asLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                asLong.toInt()
            } else {
                asLong
            }
        }
    }

    val text = newValue.toString().trim()
    if (text.isEmpty()) return text

    text.toIntOrNull()?.let { return it }
    text.toLongOrNull()?.let { return it }

    if (text.length >= 3 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) {
        val hexBody = text.substring(2)
        if (hexBody.isNotEmpty() && hexBody.all { it.isHexDigit() }) {
            hexBody.toIntOrNull(16)?.let { return it }
            hexBody.toLongOrNull(16)?.let { return it }
        }
        return text
    }

    return text
}

/**
 * Parse a float-typed constant value.
 *
 * Accepts numeric values, decimal/scientific text, and Lua 5.3 hex floats
 * (`0x1.8p1`, `0x1p10`, `0x1.f`). Unparseable input keeps the raw text.
 */
private fun parseFloatValue(newValue: Any): Any {
    when (newValue) {
        is Float -> return newValue
        is Double -> return newValue.toFloat()
        is Number -> return newValue.toFloat()
    }

    val text = newValue.toString().trim()
    if (text.isEmpty()) return text

    parseLuaHexFloat(text)?.let { return it }
    text.toFloatOrNull()?.let { return it }
    text.toDoubleOrNull()?.let { return it.toFloat() }
    return text
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * Parse a Lua 5.3 hex float lexeme into a [Float], or null when the form is not
 * a hex float / hex-with-binary-exponent.
 */
private fun parseLuaHexFloat(text: String): Float? {
    if (text.length < 3) return null
    if (!(text[0] == '0' && (text[1] == 'x' || text[1] == 'X'))) return null

    val body = text.substring(2)
    val pIndex = body.indexOfFirst { it == 'p' || it == 'P' }
    val mantStr: String
    val exp: Int
    if (pIndex >= 0) {
        mantStr = body.substring(0, pIndex)
        val expStr = body.substring(pIndex + 1)
        if (expStr.isEmpty()) return null
        exp = expStr.toIntOrNull() ?: return null
    } else {
        // Fraction-only hex float without binary exponent (e.g. 0x1.f) is still float.
        if (body.indexOf('.') < 0) return null
        mantStr = body
        exp = 0
    }

    if (mantStr.isEmpty() || mantStr == ".") return null

    val dot = mantStr.indexOf('.')
    val intPart: String
    val fracPart: String
    if (dot >= 0) {
        intPart = mantStr.substring(0, dot)
        fracPart = mantStr.substring(dot + 1)
    } else {
        intPart = mantStr
        fracPart = ""
    }

    if (intPart.any { !it.isHexDigit() } || fracPart.any { !it.isHexDigit() }) {
        return null
    }

    var value = 0.0
    for (c in intPart) {
        value = value * 16.0 + c.hexValue()
    }
    var place = 16.0
    for (c in fracPart) {
        value += c.hexValue() / place
        place *= 16.0
    }

    value *= 2.0.pow(exp.toDouble())
    return value.toFloat()
}

private fun Char.hexValue(): Int = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> 0
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
        return CallExpression().copyCallExpressionFrom(this)
    }
}

class StringCallExpression : CallExpression() {

    override fun toString(): String {
        return "StringCallExpression(base=$base, arguments=$arguments)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitStringCallExpression(this, value)
    }

    override fun clone(): StringCallExpression {
        return StringCallExpression().copyCallExpressionFrom(this)
    }
}


class TableCallExpression : CallExpression() {

    override fun toString(): String {
        return "TableCallExpression(base=$base, arguments=$arguments)"
    }

    override fun <T> accept(visitor: ASTVisitor<T>, value: T) {
        visitor.visitTableCallExpression(this, value)
    }

    override fun clone(): TableCallExpression {
        return TableCallExpression().copyCallExpressionFrom(this)
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
        return MemberExpression().copyExpressionCloneMetadataFrom(this).also {
            it.identifier = identifier.clone().withExpressionCloneParent(it)
            it.base = base.clone().withExpressionCloneParent(it)
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
        return IndexExpression().copyExpressionCloneMetadataFrom(this).also {
            it.base = base.clone().withExpressionCloneParent(it)
            it.index = index.clone().withExpressionCloneParent(it)
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
        return VarargLiteral().copyExpressionCloneMetadataFrom(this)
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
        return UnaryExpression().copyExpressionCloneMetadataFrom(this).also {
            it.operator = operator
            it.arg = arg.clone().withExpressionCloneParent(it)
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
        return BinaryExpression().copyExpressionCloneMetadataFrom(this).also {
            it.operator = operator
            it.left = left?.clone()?.withExpressionCloneParent(it)
            it.right = right?.clone()?.withExpressionCloneParent(it)
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
        return TableConstructorExpression().copyExpressionCloneMetadataFrom(this).also {
            for (field in fields) {
                it.fields.add(field.clone().withExpressionCloneParent(it))
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
        return ArrayConstructorExpression().copyExpressionCloneMetadataFrom(this).also {
            for (value in values) {
                it.values.add(value.clone().withExpressionCloneParent(it))
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
        return LambdaDeclaration().copyExpressionCloneMetadataFrom(this).also { declaration ->
            params.forEach {
                declaration.params.add(it.clone().withExpressionCloneParent(declaration))
            }

            declaration.expression = expression.clone().withExpressionCloneParent(declaration)
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
        return FunctionDeclaration().copyExpressionCloneMetadataFrom(this).also { declaration ->
            declaration.body = body?.cloneExpressionBlockFor(declaration)
            params.forEach {
                declaration.params.add(it.clone().withExpressionCloneParent(declaration))
            }
            declaration.identifier = identifier?.clone()?.withExpressionCloneParent(declaration)
            declaration.isLocal = isLocal
        }
    }
}

