package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaConstructorType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaSignatureMetadata
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.javaVarargElementType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeRelations
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom

internal typealias JavaImportResolver = ((String) -> WorkspaceImportedSymbol?)?

internal fun ModuleType.javaInstanceSurface(): Type? {
    return when (val classSurface = fields["__class"]) {
        is JavaClassType -> JavaInstanceType(classSurface)
        is Type -> classSurface
        else -> null
    }
}

internal fun ModuleType.javaClassSurface(): JavaClassType? {
    return when (val classSurface = fields["__class"]) {
        is JavaClassType -> classSurface
        is JavaInstanceType -> classSurface.classType
        else -> null
    }
}

internal fun ModuleType.isJavaBackedModule(): Boolean = fields.containsKey("__class")

internal fun ClassType.isJavaProviderClassReference(): Boolean {
    return name.contains('.') || name.contains('$')
}

/**
 * Conservative readable JavaBean property surface derived from unambiguous getter methods.
 *
 * [setterName] is informational only for now: member/completion export remains read-alias-only
 * (TASK-151/TASK-177). Assignable setter/write-through semantics are intentionally deferred.
 */
internal data class JavaBeanPropertySurface(
    val name: String,
    val valueType: Type,
    val getterName: String,
    val setterName: String? = null
)

internal fun Type.isJavaListenerAssignableFrom(source: Type): Boolean {
    val listenerSignature = javaListenerSignature() ?: return false
    return when (source) {
        is CallableType -> listenerSignature.callableType.isAssignableFrom(source)
        is TableType -> {
            val methodType = source.methods[listenerSignature.name]
                ?: source.fields[listenerSignature.name]
                ?: return false
            listenerSignature.callableType.isAssignableFrom(methodType)
        }
        is ModuleType -> {
            val methodType = source.methods[listenerSignature.name]
                ?: source.fields[listenerSignature.name]
                ?: return false
            listenerSignature.callableType.isAssignableFrom(methodType)
        }
        else -> false
    }
}

/**
 * Conservatively accepts Lua table / array argument shapes for Java array, List, and Map
 * parameters when element / key / value types are statically known. Dynamic or mixed tables
 * remain non-assignable so call checking degrades to diagnostics / unknown rather than
 * inventing precise conversions.
 */
internal fun Type.isJavaContainerAssignableFrom(source: Type): Boolean {
    return TypeRelations.isJavaContainerAssignable(this, source)
}

internal fun JavaClassType.resolveStaticJavaBeanProperty(propertyName: String): JavaBeanPropertySurface? {
    return resolveJavaBeanProperty(propertyName, allStaticMembers().values)
}

internal fun JavaInstanceType.resolveInstanceJavaBeanProperty(propertyName: String): JavaBeanPropertySurface? {
    return resolveJavaBeanProperty(propertyName, allInstanceMembers().values)
}

/**
 * Enumerates conservative readable JavaBean property aliases for static member surfaces.
 * Direct getter/setter method names are never renamed or removed by this export.
 */
internal fun JavaClassType.allReadableStaticJavaBeanProperties(): List<JavaBeanPropertySurface> {
    return allReadableJavaBeanProperties(allStaticMembers().values)
}

/**
 * Enumerates conservative readable JavaBean property aliases for instance member surfaces.
 * Direct getter/setter method names are never renamed or removed by this export.
 */
internal fun JavaInstanceType.allReadableInstanceJavaBeanProperties(): List<JavaBeanPropertySurface> {
    return allReadableJavaBeanProperties(allInstanceMembers().values)
}

internal fun Type.hydrateJavaProviderType(resolveImportTarget: JavaImportResolver): Type {
    return when (this) {
        is ClassType -> hydrateJavaClassType(resolveImportTarget)
        is CustomType -> hydrateCustomJavaProviderType(resolveImportTarget)
        is JavaClassType -> hydrateJavaClassReference(resolveImportTarget)
        is JavaInstanceType -> copy(
            classType = classType.hydrateJavaClassReference(resolveImportTarget),
            typeArguments = typeArguments.map { it.hydrateJavaProviderType(resolveImportTarget) }
        )
        is FunctionType -> withHydratedJavaSignature(resolveImportTarget)
        is OverloadedFunctionType -> OverloadedFunctionType(
            callSignatures = callSignatures.map { it.withHydratedJavaSignature(resolveImportTarget) }
        )
        is JavaConstructorType -> copy(
            signature = signature.withHydratedJavaSignature(resolveImportTarget)
        )
        is JavaOverloadType -> JavaOverloadType(
            javaName = javaName,
            overloadName = overloadName,
            callSignatures = callSignatures.mapIndexed { index, signature ->
                signature.withHydratedJavaSignature(resolveImportTarget, signatureMetadata.getOrNull(index))
            },
            signatureMetadata = signatureMetadata
        )
        is ArrayType -> ArrayType(elementType.hydrateJavaProviderType(resolveImportTarget))
        is JavaArrayType -> JavaArrayType(
            elementType = elementType.hydrateJavaProviderType(resolveImportTarget),
            dimensions = dimensions
        )
        is VarargType -> VarargType(elementType.hydrateJavaProviderType(resolveImportTarget))
        is UnionType -> UnionType(types.mapTo(linkedSetOf()) { it.hydrateJavaProviderType(resolveImportTarget) })
        is IntersectionType -> IntersectionType(types.mapTo(linkedSetOf()) { it.hydrateJavaProviderType(resolveImportTarget) })
        else -> this
    }
}

internal fun Type.withJavaCallableSurface(
    receiverType: Type? = null,
    includeReceiver: Boolean = false,
    resolveImportTarget: JavaImportResolver = null,
    signatureMetadata: List<JavaSignatureMetadata> = emptyList()
): Type {
    return when (this) {
        is FunctionType -> withJavaCallableSignature(
            receiverType,
            includeReceiver,
            resolveImportTarget,
            signatureMetadata.firstOrNull()
        )
        is OverloadedFunctionType -> OverloadedFunctionType(
            callSignatures = callSignatures.mapIndexed { index, signature ->
                signature.withJavaCallableSignature(
                    receiverType,
                    includeReceiver,
                    resolveImportTarget,
                    signatureMetadata.getOrNull(index)
                )
            }
        )
        is JavaOverloadType -> JavaOverloadType(
            javaName = javaName,
            overloadName = overloadName,
            callSignatures = callSignatures.mapIndexed { index, signature ->
                signature.withJavaCallableSignature(
                    receiverType,
                    includeReceiver,
                    resolveImportTarget,
                    signatureMetadata.getOrNull(index) ?: this.signatureMetadata.getOrNull(index)
                )
            },
            signatureMetadata = this.signatureMetadata
        )
        else -> hydrateJavaProviderType(resolveImportTarget)
    }
}

private fun allReadableJavaBeanProperties(members: Collection<JavaMemberType>): List<JavaBeanPropertySurface> {
    val candidateNames = linkedSetOf<String>()
    members.forEach { member ->
        if (member.memberKind == JavaMemberKind.METHOD) {
            javaBeanGetterPropertyName(member.memberName)?.let { candidateNames += it }
        }
    }
    return candidateNames.mapNotNull { propertyName ->
        resolveJavaBeanProperty(propertyName, members)
    }
}

private fun resolveJavaBeanProperty(
    propertyName: String,
    members: Collection<JavaMemberType>
): JavaBeanPropertySurface? {
    if (propertyName.isBlank()) {
        return null
    }
    val getterCandidates = members.filter { member ->
        member.memberKind == JavaMemberKind.METHOD &&
            javaBeanGetterPropertyName(member.memberName) == propertyName
    }
    if (getterCandidates.isEmpty()) {
        return null
    }
    val getters = getterCandidates.mapNotNull { member ->
        val returnType = javaBeanGetterReturnType(member) ?: return@mapNotNull null
        member to returnType
    }
    if (getters.size != getterCandidates.size) {
        return null
    }
    val getterTypes = getters.map { it.second }.distinct()
    if (getterTypes.size != 1) {
        return null
    }
    val getterNames = getters.map { it.first.memberName }.distinct()
    val capitalizedName = javaBeanCapitalizedName(propertyName)
    val getterName = when {
        getterNames.size == 1 -> getterNames.single()
        getterNames.all { it == "is$capitalizedName" || it == "get$capitalizedName" } ->
            getterNames.firstOrNull { it.startsWith("is") } ?: getterNames.first()
        else -> return null
    }
    val valueType = getterTypes.single()
    val setter = javaBeanSetterFor(propertyName, valueType, members)
    return JavaBeanPropertySurface(
        name = propertyName,
        valueType = valueType,
        getterName = getterName,
        setterName = setter?.memberName
    )
}

private fun javaBeanGetterPropertyName(methodName: String): String? {
    if (methodName == "getClass") {
        return null
    }
    val propertyPart = when {
        methodName.startsWith("get") -> methodName.removePrefix("get")
        methodName.startsWith("is") -> methodName.removePrefix("is")
        else -> return null
    }
    return javaBeanPropertyName(propertyPart)
}

private fun javaBeanSetterPropertyName(methodName: String): String? {
    if (!methodName.startsWith("set")) {
        return null
    }
    return javaBeanPropertyName(methodName.removePrefix("set"))
}

private fun javaBeanPropertyName(propertyPart: String): String? {
    if (propertyPart.isEmpty() || !propertyPart.first().isUpperCase()) {
        return null
    }
    return if (propertyPart.length >= 2 && propertyPart[0].isUpperCase() && propertyPart[1].isUpperCase()) {
        propertyPart
    } else {
        javaBeanLowercaseFirst(propertyPart)
    }
}

private fun javaBeanCapitalizedName(propertyName: String): String {
    if (propertyName.isEmpty()) {
        return propertyName
    }
    return javaBeanUppercaseFirst(propertyName)
}

private fun javaBeanLowercaseFirst(value: String): String {
    val first = value.first()
    val lowered = if (first in 'A'..'Z') {
        (first.code + ('a'.code - 'A'.code)).toChar()
    } else {
        first
    }
    return lowered + value.drop(1)
}

private fun javaBeanUppercaseFirst(value: String): String {
    val first = value.first()
    val uppered = if (first in 'a'..'z') {
        (first.code - ('a'.code - 'A'.code)).toChar()
    } else {
        first
    }
    return uppered + value.drop(1)
}

private fun javaBeanGetterReturnType(member: JavaMemberType): Type? {
    if (member.memberKind != JavaMemberKind.METHOD) {
        return null
    }
    val signatures = (member.valueType as? CallableType)?.callSignatures ?: return null
    // Conservative JavaBean getters must be an unambiguous zero-argument method surface.
    // Multi-parameter overloads under the same name (e.g. getCode() / getCode(int)) must
    // not synthesize a readable property alias — that excludes overloaded-getter cases.
    // Distinct method names such as getProperties() vs getProperty(...) remain independent.
    if (signatures.size != 1) {
        return null
    }
    val signature = signatures.single()
    if (signature.parameters.isNotEmpty()) {
        return null
    }
    if (
        signature.returnType == PrimitiveType.NIL ||
        signature.returnType == PrimitiveType.UNKNOWN ||
        signature.returnType == UnknownType
    ) {
        return null
    }
    if (member.memberName.startsWith("is") && signature.returnType != PrimitiveType.BOOLEAN) {
        return null
    }
    return signature.returnType
}

private fun javaBeanSetterFor(
    propertyName: String,
    valueType: Type,
    members: Collection<JavaMemberType>
): JavaMemberType? {
    val setters = members.filter { member ->
        javaBeanSetterPropertyName(member.memberName) == propertyName &&
            javaBeanSetterParameterType(member)?.let { parameterType ->
                parameterType.isAssignableFrom(valueType)
            } == true
    }
    return setters.singleOrNull()
}

private fun javaBeanSetterParameterType(member: JavaMemberType): Type? {
    if (member.memberKind != JavaMemberKind.METHOD) {
        return null
    }
    val signatures = (member.valueType as? CallableType)?.callSignatures ?: return null
    if (signatures.size != 1) {
        return null
    }
    val signature = signatures.single()
    if (signature.returnType != PrimitiveType.NIL || signature.parameters.size != 1) {
        return null
    }
    val parameter = signature.parameters.single()
    return parameter.type.takeUnless { parameter.vararg }
}

private fun Type.javaListenerSignature(): JavaListenerSignature? {
    val instanceType = this as? JavaInstanceType ?: return null
    val classType = instanceType.classType
    if (classType.constructors.isNotEmpty || classType.superClass != null) {
        return null
    }
    if (!classType.javaName.simpleName.endsWith("Listener") && !classType.javaName.simpleName.endsWith("Callback")) {
        return null
    }
    val methods = classType.allInstanceMembers().values
        .filter { member ->
            member.memberKind == JavaMemberKind.METHOD &&
                member.owner.binaryName != "java.lang.Object" &&
                member.memberName !in objectMethodNames
        }
    if (methods.size != 1) {
        return null
    }
    val method = methods.single()
    val signature = (method.valueType as? CallableType)?.callSignatures?.singleOrNull() ?: return null
    if (signature.parameters.any { it.vararg || it.optional }) {
        return null
    }
    return JavaListenerSignature(
        name = method.memberName,
        callableType = FunctionType(
            parameters = signature.parameters,
            returnType = signature.returnType
        )
    )
}

private val objectMethodNames = setOf(
    "equals",
    "getClass",
    "hashCode",
    "notify",
    "notifyAll",
    "toString",
    "wait"
)

private data class JavaListenerSignature(
    val name: String,
    val callableType: FunctionType
)

private fun ClassType.hydrateJavaClassType(resolveImportTarget: JavaImportResolver): Type {
    if (!isJavaProviderClassReference()) {
        return this
    }
    if (fields.isNotEmpty() || methods.isNotEmpty() || superClass != null || superType != null) {
        return this
    }

    val imported = resolveImportTarget?.invoke(name) ?: return this
    return imported.moduleType.javaInstanceSurface() ?: this
}

/**
 * Android-Lua stub aliases such as `AndroidView`, `AndroidMenu`, and bare `Bitmap`
 * are modeled as CustomType names in overlays. When a workspace import resolver can
 * map those aliases onto Java providers, prefer the reflected/instance surface so
 * members such as `performClick` / `getWidth` / `add` resolve.
 */
private fun CustomType.hydrateCustomJavaProviderType(resolveImportTarget: JavaImportResolver): Type {
    val candidates = androidLuaCustomTypeImportCandidates(name)
    if (candidates.isEmpty()) {
        return this
    }
    for (candidate in candidates) {
        val imported = resolveImportTarget?.invoke(candidate) ?: continue
        imported.moduleType.javaInstanceSurface()
            ?.hydrateJavaProviderType(resolveImportTarget)
            ?.let { return it }
    }
    return this
}

private fun androidLuaCustomTypeImportCandidates(name: String): List<String> {
    return when (name) {
        "AndroidView" -> listOf("android.view.View", "View")
        "AndroidMenu" -> listOf("android.view.Menu", "Menu")
        "AndroidMenuItem" -> listOf("android.view.MenuItem", "MenuItem")
        "Bitmap" -> listOf("android.graphics.Bitmap", "Bitmap")
        "Drawable" -> listOf(
            "android.graphics.drawable.Drawable",
            "android.graphics.Drawable",
            "Drawable"
        )
        else -> if (name.contains('.')) listOf(name) else emptyList()
    }
}

private fun JavaClassType.hydrateJavaClassReference(resolveImportTarget: JavaImportResolver): JavaClassType {
    if (staticMembers.isNotEmpty() || instanceMembers.isNotEmpty() || constructors.isNotEmpty || superClass != null || interfaces.isNotEmpty()) {
        return this
    }
    val imported = resolveImportTarget?.invoke(javaName.canonicalName)
        ?: resolveImportTarget?.invoke(javaName.binaryName)
        ?: return this
    return imported.moduleType.javaClassSurface() ?: this
}

private fun FunctionType.withHydratedJavaSignature(
    resolveImportTarget: JavaImportResolver,
    signatureMetadata: JavaSignatureMetadata? = null
): FunctionType {
    val hydratedParameters = parameters.map { parameter ->
        parameter.copy(type = parameter.type.hydrateJavaProviderType(resolveImportTarget))
    }
    val hydratedReturnType = javaMetadataReturnType(signatureMetadata, resolveImportTarget)
        ?: returnType.hydrateJavaProviderType(resolveImportTarget)
    val hydratedTypeParameters = typeParameters.map { it.hydrateJavaTypeParameter(resolveImportTarget) }
    return copy(
        parameters = hydratedParameters,
        returnType = hydratedReturnType,
        typeParameters = hydratedTypeParameters,
        name = FunctionType(
            parameters = hydratedParameters,
            returnType = hydratedReturnType,
            typeParameters = hydratedTypeParameters
        ).name
    )
}

private fun FunctionType.withJavaCallableSignature(
    receiverType: Type?,
    includeReceiver: Boolean,
    resolveImportTarget: JavaImportResolver,
    signatureMetadata: JavaSignatureMetadata? = null
): FunctionType {
    val javaParameters = parameters.withJavaCallableParameters(resolveImportTarget).let { resolved ->
        if (!includeReceiver || receiverType == null) {
            resolved
        } else if (hasCompatibleReceiverParameter(resolved, receiverType)) {
            resolved
        } else {
            listOf(FunctionParameter(name = "this", type = receiverType.hydrateJavaProviderType(resolveImportTarget))) + resolved
        }
    }
    val javaReturnType = javaMetadataReturnType(signatureMetadata, resolveImportTarget)
        ?: returnType.hydrateJavaProviderType(resolveImportTarget)
    val javaTypeParameters = typeParameters.map { it.hydrateJavaTypeParameter(resolveImportTarget) }
    return copy(
        parameters = javaParameters,
        returnType = javaReturnType,
        typeParameters = javaTypeParameters,
        name = FunctionType(
            parameters = javaParameters,
            returnType = javaReturnType,
            typeParameters = javaTypeParameters
        ).name
    )
}

private fun TypeParameterType.hydrateJavaTypeParameter(resolveImportTarget: JavaImportResolver): TypeParameterType {
    return copy(
        constraint = constraint?.hydrateJavaProviderType(resolveImportTarget),
        defaultType = defaultType?.hydrateJavaProviderType(resolveImportTarget)
    )
}

private fun List<FunctionParameter>.withJavaCallableParameters(resolveImportTarget: JavaImportResolver): List<FunctionParameter> {
    if (isEmpty()) {
        return this
    }
    return mapIndexed { index, parameter ->
        val hydratedType = parameter.type.hydrateJavaProviderType(resolveImportTarget)
        if (!parameter.vararg || index != lastIndex) {
            return@mapIndexed parameter.copy(type = hydratedType)
        }

        when (hydratedType) {
            is VarargType -> parameter.copy(type = hydratedType.elementType, vararg = true)
            else -> {
                val elementType = hydratedType.javaVarargElementType()
                parameter.copy(
                    type = elementType ?: hydratedType,
                    vararg = true
                )
            }
        }
    }
}

private fun hasCompatibleReceiverParameter(parameters: List<FunctionParameter>, receiverType: Type): Boolean {
    val firstParameter = parameters.firstOrNull() ?: return false
    return firstParameter.name in setOf("self", "this") && firstParameter.type.isAssignableFrom(receiverType)
}

private fun javaMetadataReturnType(
    signatureMetadata: JavaSignatureMetadata?,
    resolveImportTarget: JavaImportResolver
): Type? {
    val typeName = signatureMetadata?.genericReturnTypeName
        ?.let(::normalizeJavaMetadataTypeName)
        ?: return null
    primitiveJavaMetadataType(typeName)?.let { return it }
    val arrayElementName = typeName.removeSuffix("[]").takeIf { it.length != typeName.length }
    if (arrayElementName != null) {
        return javaMetadataReferenceType(arrayElementName, resolveImportTarget)
            ?.let { JavaArrayType(it) }
    }
    return javaMetadataReferenceType(typeName, resolveImportTarget)
}

private fun primitiveJavaMetadataType(typeName: String): Type? {
    return when (typeName) {
        "void", "java.lang.Void" -> PrimitiveType.NIL
        "boolean", "java.lang.Boolean" -> PrimitiveType.BOOLEAN
        "byte", "short", "int", "long", "float", "double",
        "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
        "java.lang.Float", "java.lang.Double", "java.lang.Number" -> PrimitiveType.NUMBER
        "char", "java.lang.Character", "java.lang.String", "java.lang.CharSequence" -> PrimitiveType.STRING
        "java.lang.Object" -> UnknownType
        else -> null
    }
}

private fun javaMetadataReferenceType(typeName: String, resolveImportTarget: JavaImportResolver): Type? {
    if (!typeName.contains('.')) {
        return null
    }
    val javaName = javaTypeNameFromMetadata(typeName) ?: return null
    val imported = resolveImportTarget?.invoke(javaName.canonicalName)
        ?: resolveImportTarget?.invoke(javaName.binaryName)
    return imported?.moduleType?.javaInstanceSurface()
        ?.hydrateJavaProviderType(resolveImportTarget)
        ?.let { type ->
            if (type is JavaInstanceType) type.copy(javaName = javaName) else type
        }
        ?: JavaInstanceType(JavaClassType(javaName = javaName))
}

private fun normalizeJavaMetadataTypeName(typeName: String): String {
    var normalized = typeName
        .removePrefix("class ")
        .removePrefix("interface ")
        .trim()
    while (normalized.startsWith("? extends ")) {
        normalized = normalized.removePrefix("? extends ").trim()
    }
    while (normalized.startsWith("? super ")) {
        normalized = normalized.removePrefix("? super ").trim()
    }
    val genericStart = normalized.indexOf('<')
    if (genericStart >= 0) {
        normalized = normalized.substring(0, genericStart)
    }
    return normalized.trim()
}

private fun javaTypeNameFromMetadata(typeName: String): JavaTypeName? {
    val rawParts = typeName
        .replace('$', '.')
        .split('.')
        .filter(String::isNotBlank)
    if (rawParts.isEmpty()) {
        return null
    }
    val classStart = rawParts.indexOfFirst { part -> part.firstOrNull()?.isUpperCase() == true }
        .takeIf { it >= 0 }
        ?: rawParts.lastIndex
    val packageName = rawParts.take(classStart).joinToString(".")
    val simpleNames = rawParts.drop(classStart)
    if (simpleNames.isEmpty()) {
        return null
    }
    val classBinaryName = simpleNames.joinToString("\$")
    val classCanonicalName = simpleNames.joinToString(".")
    val canonicalName = if ('$' in typeName) {
        typeName
    } else {
        packageName.takeIf(String::isNotEmpty)?.let { "$it.$classCanonicalName" } ?: classCanonicalName
    }
    return JavaTypeName(
        packageName = packageName,
        simpleNames = simpleNames,
        binaryName = packageName.takeIf(String::isNotEmpty)?.let { "$it.$classBinaryName" } ?: classBinaryName,
        canonicalName = canonicalName
    )
}
