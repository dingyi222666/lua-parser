package io.github.dingyi222666.luaparser.semantic.types.model

import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeSubstitutor

sealed interface JavaInteropType : Type {
    val javaName: JavaTypeName

    override val name: String
        get() = javaName.canonicalName
}

data class JavaTypeName(
    val packageName: String = "",
    val simpleNames: List<String>,
    val binaryName: String = buildJavaBinaryName(packageName, simpleNames),
    val canonicalName: String = buildJavaCanonicalName(packageName, simpleNames)
) {
    init {
        require(simpleNames.isNotEmpty()) { "Java type names must include at least one simple name." }
    }

    val simpleName: String
        get() = simpleNames.last()

    val topLevelName: String
        get() = simpleNames.first()

    val innerClassNames: List<String>
        get() = simpleNames.drop(1)

    val hasInnerClassName: Boolean
        get() = innerClassNames.isNotEmpty()
}

data class JavaClassType(
    override val javaName: JavaTypeName,
    val constructors: JavaOverloadSet<JavaConstructorType> = JavaOverloadSet(),
    val staticMembers: Map<String, JavaStaticMemberType> = emptyMap(),
    val instanceMembers: Map<String, JavaInstanceMemberType> = emptyMap(),
    val innerClasses: Map<String, JavaClassType> = emptyMap(),
    val superClass: JavaClassType? = null,
    val interfaces: List<JavaClassType> = emptyList(),
    val typeParameters: List<TypeParameterType> = emptyList()
) : JavaInteropType, CallableType {
    override val callSignatures: List<FunctionType>
        get() = constructors.overloads.map { constructor ->
            constructor.signature.withReturnType(JavaInstanceType(this))
        }.ifEmpty {
            listOf(FunctionType(returnType = JavaInstanceType(this)))
        }

    override val displayName: String
        get() = javaName.canonicalName

    fun allStaticMembers(): Map<String, JavaStaticMemberType> {
        return collectStaticMembers(linkedSetOf())
    }

    fun allInstanceMembers(): Map<String, JavaInstanceMemberType> {
        return collectInstanceMembers(linkedSetOf())
    }

    fun allInnerClasses(): Map<String, JavaClassType> {
        return collectInnerClasses(linkedSetOf())
    }

    private fun collectStaticMembers(visited: MutableSet<String>): Map<String, JavaStaticMemberType> {
        if (!visited.add(javaName.binaryName)) {
            return staticMembers
        }
        val result = linkedMapOf<String, JavaStaticMemberType>()
        interfaces.forEach { result.putAll(it.collectStaticMembers(visited)) }
        superClass?.let { result.putAll(it.collectStaticMembers(visited)) }
        result.putAll(staticMembers)
        visited.remove(javaName.binaryName)
        return result
    }

    private fun collectInstanceMembers(visited: MutableSet<String>): Map<String, JavaInstanceMemberType> {
        if (!visited.add(javaName.binaryName)) {
            return instanceMembers
        }
        val result = linkedMapOf<String, JavaInstanceMemberType>()
        interfaces.forEach { result.putAll(it.collectInstanceMembers(visited)) }
        superClass?.let { result.putAll(it.collectInstanceMembers(visited)) }
        result.putAll(instanceMembers)
        visited.remove(javaName.binaryName)
        return result
    }

    private fun collectInnerClasses(visited: MutableSet<String>): Map<String, JavaClassType> {
        if (!visited.add(javaName.binaryName)) {
            return innerClasses
        }
        val result = linkedMapOf<String, JavaClassType>()
        interfaces.forEach { result.putAll(it.collectInnerClasses(visited)) }
        superClass?.let { result.putAll(it.collectInnerClasses(visited)) }
        result.putAll(innerClasses)
        visited.remove(javaName.binaryName)
        return result
    }
}

data class JavaInstanceType(
    val classType: JavaClassType,
    val typeArguments: List<Type> = emptyList(),
    override val javaName: JavaTypeName = classType.javaName
) : JavaInteropType {
    override val displayName: String
        get() = buildJavaParameterizedName(javaName.canonicalName, typeArguments)

    fun allInstanceMembers(): Map<String, JavaInstanceMemberType> {
        val members = classType.allInstanceMembers()
        val mapping = classType.typeArgumentMapping(typeArguments)
        if (mapping.isEmpty()) {
            return members
        }
        val substitutor = TypeSubstitutor()
        return members.mapValues { (_, member) ->
            substitutor.substitute(member, mapping) as JavaInstanceMemberType
        }
    }
}

data class JavaSignatureMetadata(
    val isVarArgs: Boolean = false,
    val typeParameters: List<TypeParameterType> = emptyList(),
    val genericParameterTypeNames: List<String> = emptyList(),
    val genericReturnTypeName: String? = null
)

data class JavaConstructorType(
    val owner: JavaTypeName,
    val signature: FunctionType,
    val signatureMetadata: JavaSignatureMetadata = JavaSignatureMetadata(),
    val visibility: JavaVisibility = JavaVisibility.PUBLIC
) : JavaInteropType, CallableType {
    override val javaName: JavaTypeName
        get() = owner

    override val name: String
        get() = "${owner.canonicalName}.<init>"

    override val displayName: String
        get() = signature.displayName

    override val callSignatures: List<FunctionType>
        get() = listOf(signature)
}

sealed interface JavaMemberType : JavaInteropType {
    val owner: JavaTypeName
    val memberName: String
    val memberKind: JavaMemberKind
    val valueType: Type
    val visibility: JavaVisibility

    override val javaName: JavaTypeName
        get() = owner

    override val name: String
        get() = "${owner.canonicalName}.$memberName"
}

data class JavaStaticMemberType(
    override val owner: JavaTypeName,
    override val memberName: String,
    override val valueType: Type,
    override val memberKind: JavaMemberKind = JavaMemberKind.FIELD,
    override val visibility: JavaVisibility = JavaVisibility.PUBLIC,
    val signatureMetadata: List<JavaSignatureMetadata> = emptyList()
) : JavaMemberType {
    override val displayName: String
        get() = valueType.displayName
}

data class JavaInstanceMemberType(
    override val owner: JavaTypeName,
    override val memberName: String,
    override val valueType: Type,
    override val memberKind: JavaMemberKind = JavaMemberKind.FIELD,
    override val visibility: JavaVisibility = JavaVisibility.PUBLIC,
    val signatureMetadata: List<JavaSignatureMetadata> = emptyList()
) : JavaMemberType {
    override val displayName: String
        get() = valueType.displayName
}

data class JavaOverloadSet<out T : CallableType>(
    val overloads: List<T> = emptyList()
) {
    val isEmpty: Boolean
        get() = overloads.isEmpty()

    val isNotEmpty: Boolean
        get() = overloads.isNotEmpty()
}

data class JavaOverloadType(
    override val javaName: JavaTypeName,
    val overloadName: String,
    override val callSignatures: List<FunctionType>,
    val signatureMetadata: List<JavaSignatureMetadata> = emptyList(),
    override val name: String = "${javaName.canonicalName}.$overloadName"
) : JavaInteropType, CallableType {
    override val displayName: String
        get() = callSignatures.joinToString(" & ") { it.displayName }
}

data class JavaPrimitiveType(
    override val name: String,
    val kind: Kind
) : Type {
    enum class Kind {
        BOOLEAN,
        BYTE,
        CHAR,
        SHORT,
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        VOID
    }

    companion object {
        val BOOLEAN = JavaPrimitiveType("boolean", Kind.BOOLEAN)
        val BYTE = JavaPrimitiveType("byte", Kind.BYTE)
        val CHAR = JavaPrimitiveType("char", Kind.CHAR)
        val SHORT = JavaPrimitiveType("short", Kind.SHORT)
        val INT = JavaPrimitiveType("int", Kind.INT)
        val LONG = JavaPrimitiveType("long", Kind.LONG)
        val FLOAT = JavaPrimitiveType("float", Kind.FLOAT)
        val DOUBLE = JavaPrimitiveType("double", Kind.DOUBLE)
        val VOID = JavaPrimitiveType("void", Kind.VOID)
    }
}

/** JVM array surface used for reflected array returns and LuaJava typed array helpers. */
data class JavaArrayType(
    val elementType: Type,
    val dimensions: Int = 1,
    override val name: String = buildJavaArrayName(elementType, dimensions)
) : Type {
    init {
        require(dimensions > 0) { "Java array dimensions must be positive." }
    }
}

internal fun Type.javaVarargElementType(): Type? = when (this) {
    is ArrayType -> elementType
    is JavaArrayType -> elementType
    else -> null
}

internal fun FunctionType.withReturnType(returnType: Type): FunctionType {
    return copy(
        returnType = returnType,
        name = FunctionType(
            parameters = parameters,
            returnType = returnType,
            typeParameters = typeParameters
        ).name
    )
}

enum class JavaVisibility {
    PUBLIC,
    PROTECTED,
    PACKAGE_PRIVATE,
    PRIVATE
}

enum class JavaMemberKind {
    FIELD,
    METHOD
}

private fun buildJavaBinaryName(packageName: String, simpleNames: List<String>): String {
    val className = simpleNames.joinToString("\$")
    return packageName.takeIf { it.isNotEmpty() }?.let { "$it.$className" } ?: className
}

private fun buildJavaCanonicalName(packageName: String, simpleNames: List<String>): String {
    val className = simpleNames.joinToString(".")
    return packageName.takeIf { it.isNotEmpty() }?.let { "$it.$className" } ?: className
}

private fun buildJavaParameterizedName(name: String, typeArguments: List<Type>): String {
    if (typeArguments.isEmpty()) {
        return name
    }
    return typeArguments.joinToString(prefix = "$name<", postfix = ">") { it.displayName }
}

private fun JavaClassType.typeArgumentMapping(typeArguments: List<Type>): Map<String, Type> {
    if (typeArguments.isEmpty() || typeParameters.size != typeArguments.size) {
        return emptyMap()
    }
    return typeParameters.zip(typeArguments).associate { (parameter, argument) ->
        parameter.name to argument
    }
}

private fun buildJavaArrayName(elementType: Type, dimensions: Int): String {
    val suffix = "[]".repeat(dimensions.coerceAtLeast(1))
    return "${elementType.displayName}$suffix"
}
