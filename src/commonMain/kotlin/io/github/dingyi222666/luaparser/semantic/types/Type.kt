package io.github.dingyi222666.luaparser.semantic.types

sealed interface Type {
    val name: String

    fun isAssignableFrom(other: Type): Boolean

    fun union(other: Type): Type = unionOf(this, other)

    fun intersection(other: Type): Type = intersectionOf(this, other)
}

interface CallableType : Type {
    val callSignatures: List<FunctionType>
}

data class PrimitiveType(
    override val name: String,
    val kind: Kind
) : Type {
    enum class Kind {
        NIL,
        NUMBER,
        STRING,
        BOOLEAN,
        THREAD,
        USERDATA,
        ANY,
        UNKNOWN
    }

    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        return when {
            this == ANY -> true
            normalizedOther == ErrorType || normalizedOther == UnknownType -> true
            normalizedOther == NeverType -> true
            normalizedOther is UnionType -> normalizedOther.types.all { isAssignableFrom(it) }
            normalizedOther is LiteralType -> isAssignableFrom(normalizedOther.baseType)
            normalizedOther is PrimitiveType -> {
                if (normalizedOther == NIL) {
                    this == NIL || this == ANY || this == UNKNOWN
                } else {
                    this == normalizedOther
                }
            }

            else -> false
        }
    }

    companion object {
        val NIL = PrimitiveType("nil", Kind.NIL)
        val NUMBER = PrimitiveType("number", Kind.NUMBER)
        val STRING = PrimitiveType("string", Kind.STRING)
        val BOOLEAN = PrimitiveType("boolean", Kind.BOOLEAN)
        val THREAD = PrimitiveType("thread", Kind.THREAD)
        val USERDATA = PrimitiveType("userdata", Kind.USERDATA)
        val ANY = PrimitiveType("any", Kind.ANY)
        val UNKNOWN = PrimitiveType("unknown", Kind.UNKNOWN)
    }
}

object UnknownType : Type {
    override val name: String = "unknown"

    override fun isAssignableFrom(other: Type): Boolean = true
}

object ErrorType : Type {
    override val name: String = "error"

    override fun isAssignableFrom(other: Type): Boolean = true
}

data class LiteralType(
    val value: Any?,
    val baseType: PrimitiveType,
    override val name: String = literalName(value)
) : Type {
    override fun isAssignableFrom(other: Type): Boolean = when (val normalizedOther = other.unwrapAliases()) {
        ErrorType, UnknownType -> true
        is LiteralType -> normalizedOther.value == value && baseType.isAssignableFrom(normalizedOther.baseType)
        else -> false
    }
}

data class FunctionType(
    val parameters: List<ParameterType>,
    val returnType: Type,
    override val name: String = buildFunctionName(parameters, returnType)
) : CallableType {
    override val callSignatures: List<FunctionType>
        get() = listOf(this)

    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        return when (normalizedOther) {
            ErrorType, UnknownType -> true
            is OverloadedFunctionType -> normalizedOther.callSignatures.any { isAssignableFrom(it) }
            is FunctionType -> {
                if (!parameterListsCompatible(parameters, normalizedOther.parameters)) {
                    return false
                }
                returnType.isAssignableFrom(normalizedOther.returnType)
            }

            else -> false
        }
    }

    fun acceptsArguments(argumentTypes: List<Type>): Boolean {
        val minRequired = parameters.count { !it.optional && !it.vararg }
        val hasVararg = parameters.any { it.vararg }
        if (argumentTypes.size < minRequired) return false
        if (!hasVararg && argumentTypes.size > parameters.size) return false

        argumentTypes.forEachIndexed { index, argumentType ->
            val parameter = parameters.getOrNull(index) ?: parameters.lastOrNull { it.vararg }
            if (parameter == null || !parameter.type.isAssignableFrom(argumentType)) {
                return false
            }
        }

        return true
    }
}

data class OverloadedFunctionType(
    override val callSignatures: List<FunctionType>,
    override val name: String = callSignatures.joinToString(" & ") { it.name }
) : CallableType {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        return when (normalizedOther) {
            ErrorType, UnknownType -> true
            is FunctionType -> callSignatures.any { it.isAssignableFrom(normalizedOther) }
            is OverloadedFunctionType -> normalizedOther.callSignatures.all { signature ->
                callSignatures.any { it.isAssignableFrom(signature) }
            }

            else -> false
        }
    }

    fun selectSignature(argumentTypes: List<Type>): FunctionType? =
        callSignatures.firstOrNull { it.acceptsArguments(argumentTypes) } ?: callSignatures.firstOrNull()
}

data class TableType(
    override val fields: Map<String, Type>,
    override val methods: Map<String, Type> = emptyMap(),
    override val indexSignature: IndexSignature? = null,
    override val name: String = buildTableName(fields, indexSignature)
) : Type, MemberBearingLegacyType {
    data class IndexSignature(
        val keyType: Type,
        val valueType: Type
    )

    override fun isAssignableFrom(other: Type): Boolean {
        return isAssignableFromMemberBearingType(this, other.unwrapAliases())
    }
}

data class ModuleType(
    val moduleName: String,
    override val fields: Map<String, Type> = emptyMap(),
    override val methods: Map<String, Type> = emptyMap(),
    override val indexSignature: IndexSignature? = null,
    override val name: String = moduleName
) : Type, MemberBearingLegacyType {
    data class IndexSignature(
        val keyType: Type,
        val valueType: Type
    )

    override fun isAssignableFrom(other: Type): Boolean {
        return isAssignableFromMemberBearingType(this, other.unwrapAliases())
    }
}

data class UnionType(
    val types: Set<Type>,
    override val name: String = types.joinToString(" | ") { it.name }
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        if (normalizedOther == ErrorType || normalizedOther == UnknownType) {
            return true
        }
        return types.any { it.isAssignableFrom(normalizedOther) }
    }
}

data class IntersectionType(
    val types: Set<Type>,
    override val name: String = types.joinToString(" & ") { it.name }
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        if (normalizedOther == ErrorType || normalizedOther == UnknownType) {
            return true
        }
        return types.all { it.isAssignableFrom(normalizedOther) }
    }
}

object NeverType : Type {
    override val name: String = "never"

    override fun isAssignableFrom(other: Type): Boolean = other.unwrapAliases() == NeverType
}

data class ParameterType(
    val name: String,
    val type: Type,
    val optional: Boolean = false,
    val vararg: Boolean = false
)

data class VarArgType(
    val types: List<Type>,
    override val name: String = "vararg<${types.joinToString(", ") { it.name }}>"
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        return when (normalizedOther) {
            ErrorType, UnknownType -> true
            is VarArgType -> {
                if (types.size != normalizedOther.types.size) return false
                types.zip(normalizedOther.types).all { (a, b) -> a.isAssignableFrom(b) }
            }

            else -> false
        }
    }
}

data class MultiReturnType(
    val types: List<Type>,
    override val name: String = types.joinToString(", ") { it.name }
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        return when (normalizedOther) {
            ErrorType, UnknownType -> true
            is MultiReturnType -> {
                if (types.size != normalizedOther.types.size) return false
                types.zip(normalizedOther.types).all { (expected, actual) -> expected.isAssignableFrom(actual) }
            }

            else -> false
        }
    }
}

data class TypeParameterType(
    val symbolName: String,
    val constraint: Type? = null,
    override val name: String = symbolName
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        return normalizedOther == ErrorType || normalizedOther == UnknownType || constraint?.isAssignableFrom(normalizedOther) != false
    }
}

data class GenericType(
    val baseName: String,
    val typeParameters: List<Type>,
    override val name: String = "$baseName<${typeParameters.joinToString(", ") { it.name }}>"
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        return when (normalizedOther) {
            ErrorType, UnknownType -> true
            is GenericType -> {
                if (baseName != normalizedOther.baseName) return false
                if (typeParameters.size != normalizedOther.typeParameters.size) return false
                typeParameters.zip(normalizedOther.typeParameters).all { (expected, actual) ->
                    expected.isAssignableFrom(actual)
                }
            }

            else -> false
        }
    }
}

data class ArrayType(
    val elementType: Type,
    override val name: String = "${elementType.name}[]"
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        return when (normalizedOther) {
            ErrorType, UnknownType -> true
            is ArrayType -> elementType.isAssignableFrom(normalizedOther.elementType)
            is TableType -> normalizedOther.indexSignature?.let { signature ->
                signature.keyType.isAssignableFrom(PrimitiveType.NUMBER) && elementType.isAssignableFrom(signature.valueType)
            } ?: false

            is ModuleType -> normalizedOther.indexSignature?.let { signature ->
                signature.keyType.isAssignableFrom(PrimitiveType.NUMBER) && elementType.isAssignableFrom(signature.valueType)
            } ?: false

            else -> false
        }
    }
}

data class TupleType(
    val elementTypes: List<Type>,
    override val name: String = "[${elementTypes.joinToString(", ") { it.name }}]"
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        return when (normalizedOther) {
            ErrorType, UnknownType -> true
            is TupleType -> {
                if (elementTypes.size != normalizedOther.elementTypes.size) return false
                elementTypes.zip(normalizedOther.elementTypes).all { (expected, actual) -> expected.isAssignableFrom(actual) }
            }

            else -> false
        }
    }
}

data class CustomType(
    override val name: String
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        return normalizedOther == ErrorType || normalizedOther == UnknownType || (normalizedOther is CustomType && name == normalizedOther.name)
    }
}

data class AliasType(
    override val name: String,
    val target: Type
) : Type {
    override fun isAssignableFrom(other: Type): Boolean = target.isAssignableFrom(other)
}

data class ClassType(
    override val name: String,
    val fields: Map<String, Type> = emptyMap(),
    val methods: Map<String, Type> = emptyMap(),
    val parent: ClassType? = null,
    val typeParameters: List<Type> = emptyList(),
    val declaredTypeParameters: List<String> = typeParameters.filterIsInstance<TypeParameterType>().map { it.name }
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        val normalizedOther = other.unwrapAliases()
        if (normalizedOther == ErrorType || normalizedOther == UnknownType) {
            return true
        }
        if (normalizedOther !is ClassType) {
            return false
        }

        var current: ClassType? = normalizedOther
        while (current != null) {
            if (current.name == name) {
                if (typeParameters.size != current.typeParameters.size) {
                    return typeParameters.isEmpty() && current.typeParameters.isEmpty()
                }
                return typeParameters.zip(current.typeParameters).all { (expected, actual) ->
                    expected.isAssignableFrom(actual)
                }
            }
            current = current.parent
        }

        return false
    }

    fun getAllFields(): Map<String, Type> = buildMap {
        parent?.getAllFields()?.let(::putAll)
        putAll(fields)
    }

    fun getAllMethods(): Map<String, Type> = buildMap {
        parent?.getAllMethods()?.let(::putAll)
        putAll(methods)
    }
}

fun Type.unwrapAliases(): Type = when (this) {
    is AliasType -> target.unwrapAliases()
    else -> this
}

fun Type.asCallableType(): CallableType? = when (val normalized = unwrapAliases()) {
    is CallableType -> normalized
    else -> null
}

fun Type.mergeMemberType(other: Type): Type = when {
    this == ErrorType -> other
    other == ErrorType -> this
    this == other -> this
    else -> unionOf(this, other)
}

private fun unionOf(left: Type, right: Type): Type {
    if (left == right) return left
    if (left == ErrorType) return right
    if (right == ErrorType) return left
    if (left == UnknownType || right == UnknownType) return UnknownType
    val leftTypes = (left as? UnionType)?.types ?: setOf(left)
    val rightTypes = (right as? UnionType)?.types ?: setOf(right)
    return UnionType(leftTypes + rightTypes)
}

private fun intersectionOf(left: Type, right: Type): Type {
    if (left == right) return left
    if (left == ErrorType) return right
    if (right == ErrorType) return left
    if (left == UnknownType) return right
    if (right == UnknownType) return left
    if (left.isAssignableFrom(right)) return right
    if (right.isAssignableFrom(left)) return left
    val leftTypes = (left as? IntersectionType)?.types ?: setOf(left)
    val rightTypes = (right as? IntersectionType)?.types ?: setOf(right)
    return IntersectionType(leftTypes + rightTypes)
}

private fun parameterListsCompatible(expected: List<ParameterType>, actual: List<ParameterType>): Boolean {
    val requiredExpected = expected.count { !it.optional && !it.vararg }
    val requiredActual = actual.count { !it.optional && !it.vararg }
    if (requiredExpected != requiredActual && expected.none { it.vararg } && actual.none { it.vararg }) {
        return false
    }

    val max = maxOf(expected.size, actual.size)
    for (index in 0 until max) {
        val expectedParameter = expected.getOrNull(index) ?: expected.lastOrNull { it.vararg } ?: return false
        val actualParameter = actual.getOrNull(index) ?: actual.lastOrNull { it.vararg } ?: return expectedParameter.optional || expectedParameter.vararg
        if (!expectedParameter.type.isAssignableFrom(actualParameter.type)) {
            return false
        }
    }

    return true
}

private fun literalName(value: Any?): String = when (value) {
    null -> "nil"
    is String -> '"' + value + '"'
    else -> value.toString()
}

private fun buildFunctionName(parameters: List<ParameterType>, returnType: Type): String {
    val parameterText = parameters.joinToString(", ") { parameter ->
        buildString {
            append(parameter.name)
            if (parameter.optional) append('?')
            append(": ")
            append(parameter.type.name)
            if (parameter.vararg) append("...")
        }
    }
    return "fun($parameterText): ${returnType.name}"
}

private fun buildTableName(fields: Map<String, Type>, indexSignature: TableType.IndexSignature?): String {
    if (fields.isEmpty() && indexSignature == null) {
        return "table"
    }

    val pieces = mutableListOf<String>()
    fields.forEach { (name, type) ->
        pieces.add("$name: ${type.name}")
    }
    indexSignature?.let {
        pieces.add("[${it.keyType.name}]: ${it.valueType.name}")
    }
    return "{ ${pieces.joinToString(", ")} }"
}

private interface MemberBearingLegacyType {
    val fields: Map<String, Type>
    val methods: Map<String, Type>
    val indexSignature: Any?
}

private fun isAssignableFromMemberBearingType(target: MemberBearingLegacyType, other: Type): Boolean {
    return when (other) {
        ErrorType, UnknownType -> true
        is TableType -> isAssignableFromMemberShape(
            target.fields,
            target.methods,
            target.indexSignature.toTableIndexSignature(),
            other.fields,
            other.methods,
            other.indexSignature
        )

        is ModuleType -> isAssignableFromMemberShape(
            target.fields,
            target.methods,
            target.indexSignature.toTableIndexSignature(),
            other.fields,
            other.methods,
            other.indexSignature.toTableIndexSignature()
        )

        is ClassType -> {
            val availableFields = other.getAllFields()
            val availableMethods = other.getAllMethods()
            target.fields.all { (key, type) -> availableFields[key]?.let(type::isAssignableFrom) ?: false } &&
                target.methods.all { (key, type) -> availableMethods[key]?.let(type::isAssignableFrom) ?: false } &&
                target.indexSignature == null
        }

        else -> false
    }
}

private fun isAssignableFromMemberShape(
    targetFields: Map<String, Type>,
    targetMethods: Map<String, Type>,
    targetIndexSignature: TableType.IndexSignature?,
    sourceFields: Map<String, Type>,
    sourceMethods: Map<String, Type>,
    sourceIndexSignature: TableType.IndexSignature?
): Boolean {
    if (!targetFields.all { (key, type) -> sourceFields[key]?.let(type::isAssignableFrom) ?: false }) {
        return false
    }
    if (!targetMethods.all { (key, type) -> sourceMethods[key]?.let(type::isAssignableFrom) ?: false }) {
        return false
    }
    if (targetIndexSignature == null) {
        return true
    }
    val otherIndex = sourceIndexSignature ?: return false
    if (!targetIndexSignature.keyType.isAssignableFrom(otherIndex.keyType)) return false
    if (!targetIndexSignature.valueType.isAssignableFrom(otherIndex.valueType)) return false
    return true
}

private fun Any?.toTableIndexSignature(): TableType.IndexSignature? = when (this) {
    is TableType.IndexSignature -> this
    is ModuleType.IndexSignature -> TableType.IndexSignature(keyType, valueType)
    else -> null
}
