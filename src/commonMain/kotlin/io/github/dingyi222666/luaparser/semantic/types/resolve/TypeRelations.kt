package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaConstructorType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaPrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaStaticMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType

object TypeRelations {
    fun isAssignable(target: Type, source: Type): Boolean {
        val normalizedTarget = TypeNormalizer.normalize(target)
        val normalizedSource = TypeNormalizer.normalize(source)

        if (normalizedTarget == UnknownType || normalizedTarget == ErrorType) return true
        if (normalizedSource == UnknownType || normalizedSource == ErrorType) return true
        if (normalizedSource == NeverType) return true
        if (normalizedTarget == NeverType) return normalizedSource == NeverType

        if (normalizedTarget == normalizedSource) return true

        if (normalizedTarget is UnionType) {
            return normalizedTarget.types.any { isAssignable(it, normalizedSource) }
        }
        if (normalizedSource is UnionType) {
            return normalizedSource.types.all { isAssignable(normalizedTarget, it) }
        }
        if (normalizedTarget is IntersectionType) {
            return normalizedTarget.types.all { isAssignable(it, normalizedSource) }
        }
        if (normalizedSource is IntersectionType) {
            return isIntersectionSourceAssignable(normalizedTarget, normalizedSource)
        }

        if (normalizedTarget is TypeParameterType) {
            return when (normalizedSource) {
                is TypeParameterType -> typeParameterShapeCompatible(normalizedTarget, normalizedSource)
                else -> normalizedTarget.constraint?.let { isAssignable(it, normalizedSource) } ?: true
            }
        }
        if (normalizedSource is TypeParameterType) {
            return normalizedSource.constraint?.let { isAssignable(normalizedTarget, it) } ?: false
        }

        return when (normalizedTarget) {
            is LiteralType -> isLiteralAssignable(normalizedTarget, normalizedSource)
            is PrimitiveType -> isPrimitiveAssignable(normalizedTarget, normalizedSource)
            is FunctionType -> isCallableAssignable(normalizedTarget, normalizedSource)
            is OverloadedFunctionType -> isCallableAssignable(normalizedTarget, normalizedSource)
            is TableType -> isTableAssignable(normalizedTarget, normalizedSource)
            is ModuleType -> isModuleAssignable(normalizedTarget, normalizedSource)
            is ClassType -> isClassAssignable(normalizedTarget, normalizedSource)
            is ArrayType -> isArrayAssignable(normalizedTarget, normalizedSource)
            is TupleType -> isTupleAssignable(normalizedTarget, normalizedSource)
            is MultiReturnType -> isMultiReturnAssignable(normalizedTarget, normalizedSource)
            is VarargType -> isVarargAssignable(normalizedTarget, normalizedSource)
            is AppliedType -> isAppliedAssignable(normalizedTarget, normalizedSource)
            is JavaPrimitiveType -> isJavaPrimitiveAssignable(normalizedTarget, normalizedSource)
            is JavaClassType -> isJavaClassAssignable(normalizedTarget, normalizedSource)
            is JavaInstanceType -> isJavaInstanceAssignable(normalizedTarget, normalizedSource)
            is JavaConstructorType -> isCallableAssignable(normalizedTarget, normalizedSource)
            is JavaStaticMemberType -> normalizedSource is JavaStaticMemberType &&
                normalizedTarget.memberName == normalizedSource.memberName &&
                normalizedTarget.owner.binaryName == normalizedSource.owner.binaryName &&
                isAssignable(normalizedTarget.valueType, normalizedSource.valueType)
            is JavaInstanceMemberType -> normalizedSource is JavaInstanceMemberType &&
                normalizedTarget.memberName == normalizedSource.memberName &&
                normalizedTarget.owner.binaryName == normalizedSource.owner.binaryName &&
                isAssignable(normalizedTarget.valueType, normalizedSource.valueType)
            is JavaOverloadType -> isCallableAssignable(normalizedTarget, normalizedSource)
            is JavaArrayType -> isJavaArrayAssignable(normalizedTarget, normalizedSource)
            is CustomType -> normalizedSource is CustomType && normalizedTarget.name == normalizedSource.name
            is AliasType, is UnionType, is IntersectionType -> false
            is TypeParameterType -> false
            UnknownType, ErrorType, NeverType -> false
        }
    }

    private fun isIntersectionSourceAssignable(target: Type, source: IntersectionType): Boolean {
        val combinedSource = combineIntersectionSource(source)
        if (combinedSource != null) {
            return isAssignable(target, combinedSource)
        }

        return source.types.all { isAssignable(target, it) }
    }

    private fun isLiteralAssignable(target: LiteralType, source: Type): Boolean = when (source) {
        is LiteralType -> target.value == source.value && canonicalPrimitive(target.baseType) == canonicalPrimitive(source.baseType)
        else -> false
    }

    private fun isPrimitiveAssignable(target: PrimitiveType, source: Type): Boolean = when (target) {
        PrimitiveType.ANY -> true
        PrimitiveType.FUNCTION -> source is CallableType
        PrimitiveType.TABLE -> isTableLike(source)
        else -> when (source) {
            is LiteralType -> canonicalPrimitive(source.baseType) == target
            is PrimitiveType -> canonicalPrimitive(source) == target
            else -> false
        }
    }

    private fun isCallableAssignable(target: CallableType, source: Type): Boolean {
        val sourceCallable = source as? CallableType ?: return false
        return sourceCallable.callSignatures.all { sourceSignature ->
            target.callSignatures.any { targetSignature ->
                parameterListsCompatible(targetSignature.parameters, sourceSignature.parameters) &&
                    isAssignable(targetSignature.returnType, sourceSignature.returnType) &&
                    typeParametersCompatible(targetSignature.typeParameters, sourceSignature.typeParameters)
            }
        }
    }

    private fun isTableAssignable(target: TableType, source: Type): Boolean {
        return when (source) {
            is TableType -> {
                if (!target.fields.all { (name, type) -> source.fields[name]?.let { isAssignable(type, it) } == true }) {
                    false
                } else if (!target.methods.all { (name, type) -> source.methods[name]?.let { isAssignable(type, it) } == true }) {
                    false
                } else {
                    val targetIndex = target.indexSignature
                    if (targetIndex == null) {
                        true
                    } else {
                        val sourceIndex = source.indexSignature ?: return false
                        isAssignable(targetIndex.keyType, sourceIndex.keyType) &&
                            isAssignable(targetIndex.valueType, sourceIndex.valueType)
                    }
                }
            }

            is ModuleType -> isMemberBearingShapeAssignable(
                target.fields,
                target.methods,
                target.indexSignature,
                source.fields,
                source.methods,
                source.indexSignature?.let { TableType.IndexSignature(it.keyType, it.valueType) }
            )

            is ClassType -> {
                val availableFields = source.getAllFields()
                val availableMethods = source.getAllMethods()
                target.fields.all { (name, type) -> availableFields[name]?.let { isAssignable(type, it) } == true } &&
                    target.methods.all { (name, type) -> availableMethods[name]?.let { isAssignable(type, it) } == true } &&
                    target.indexSignature == null
            }

            is ArrayType -> {
                if (target.fields.isNotEmpty() || target.methods.isNotEmpty()) {
                    false
                } else {
                    val signature = target.indexSignature ?: return false
                    isAssignable(signature.keyType, PrimitiveType.NUMBER) &&
                        isAssignable(signature.valueType, source.elementType)
                }
            }

            else -> false
        }
    }

    private fun isClassAssignable(target: ClassType, source: Type): Boolean {
        val sourceClass = source as? ClassType ?: return false
        val matched = isSameOrSubclass(target, sourceClass) ?: return false
        if (target.typeParameters.size != matched.typeParameters.size) {
            return target.typeParameters.isEmpty() && matched.typeParameters.isEmpty()
        }
        return target.typeParameters.zip(matched.typeParameters).all { (expected, actual) ->
            typeParameterShapeCompatible(expected, actual)
        }
    }

    private fun isJavaClassAssignable(target: JavaClassType, source: Type): Boolean {
        val sourceClass = source as? JavaClassType ?: return false
        return isSameOrJavaSubclass(target, sourceClass)
    }

    private fun isJavaInstanceAssignable(target: JavaInstanceType, source: Type): Boolean {
        if (isJavaContainerAssignableFromLuaTable(target, source)) {
            return true
        }
        val sourceInstance = source as? JavaInstanceType ?: return false
        if (!isSameOrJavaSubclass(target.classType, sourceInstance.classType)) {
            return false
        }
        if (target.typeArguments.isEmpty()) {
            return true
        }
        if (target.typeArguments.size != sourceInstance.typeArguments.size) {
            return false
        }
        return target.typeArguments.zip(sourceInstance.typeArguments).all { (expected, actual) ->
            isAssignable(expected, actual)
        }
    }

    /**
     * Conservatively models Lua table / array literals as Java List or Map parameters when
     * element / key / value types are statically known. Raw or mixed shapes stay non-assignable.
     */
    internal fun isJavaContainerAssignable(target: Type, source: Type): Boolean {
        return when (target) {
            is JavaArrayType -> isJavaArrayAssignable(target, source)
            is ArrayType -> when (source) {
                is ArrayType -> isAssignable(target.elementType, source.elementType)
                is TableType -> isTableAssignableToJavaArrayElements(target.elementType, source)
                is ModuleType -> isModuleAssignableToJavaArrayElements(target.elementType, source)
                else -> false
            }
            is JavaInstanceType -> isJavaContainerAssignableFromLuaTable(target, source)
            else -> false
        }
    }

    private fun isJavaContainerAssignableFromLuaTable(target: JavaInstanceType, source: Type): Boolean {
        return when (javaContainerKind(target)) {
            JavaContainerKind.LIST -> {
                val elementType = target.typeArguments.singleOrNull() ?: return false
                when (source) {
                    is TableType -> isTableAssignableToJavaArrayElements(elementType, source)
                    is ArrayType -> isAssignable(elementType, source.elementType)
                    is ModuleType -> isModuleAssignableToJavaArrayElements(elementType, source)
                    else -> false
                }
            }
            JavaContainerKind.MAP -> {
                if (target.typeArguments.size < 2) {
                    return false
                }
                val keyType = target.typeArguments[0]
                val valueType = target.typeArguments[1]
                when (source) {
                    is TableType -> isTableAssignableToJavaMap(keyType, valueType, source)
                    is ModuleType -> isModuleAssignableToJavaMap(keyType, valueType, source)
                    else -> false
                }
            }
            null -> false
        }
    }

    private fun javaContainerKind(target: JavaInstanceType): JavaContainerKind? {
        val names = collectJavaBinaryNames(target.classType)
        return when {
            names.any { it in javaListContainerNames } -> JavaContainerKind.LIST
            names.any { it in javaMapContainerNames } -> JavaContainerKind.MAP
            else -> null
        }
    }

    private fun collectJavaBinaryNames(classType: JavaClassType): Set<String> {
        val names = linkedSetOf<String>()
        fun visit(current: JavaClassType?) {
            current ?: return
            if (!names.add(current.javaName.binaryName)) {
                return
            }
            visit(current.superClass)
            current.interfaces.forEach(::visit)
        }
        visit(classType)
        return names
    }

    private fun isTableAssignableToJavaArrayElements(elementType: Type, source: TableType): Boolean {
        if (!isKnownType(elementType)) {
            return false
        }
        val index = source.indexSignature
        if (index != null) {
            if (!isNumberKeyType(index.keyType) || !isAssignable(elementType, index.valueType)) {
                return false
            }
        }
        if (source.fields.isEmpty()) {
            // Empty table or index-only array-like table.
            return index != null || source.methods.isEmpty()
        }
        source.fields.forEach { (name, fieldType) ->
            if (name.toIntOrNull() == null) {
                return false
            }
            if (!isAssignable(elementType, fieldType)) {
                return false
            }
        }
        // Named methods without integer keys are map/object shaped, not array/list shaped.
        source.methods.keys.forEach { name ->
            if (name.toIntOrNull() == null && name !in source.fields) {
                return false
            }
        }
        return true
    }

    private fun isModuleAssignableToJavaArrayElements(elementType: Type, source: ModuleType): Boolean {
        return isTableAssignableToJavaArrayElements(
            elementType,
            TableType(
                fields = source.fields,
                methods = source.methods,
                indexSignature = source.indexSignature?.let {
                    TableType.IndexSignature(it.keyType, it.valueType)
                }
            )
        )
    }

    private fun isTableAssignableToJavaMap(keyType: Type, valueType: Type, source: TableType): Boolean {
        if (!isKnownType(keyType) || !isKnownType(valueType)) {
            return false
        }
        val index = source.indexSignature
        if (index != null) {
            if (!isAssignable(keyType, index.keyType) || !isAssignable(valueType, index.valueType)) {
                return false
            }
        }
        if (source.fields.isEmpty() && source.methods.isEmpty()) {
            return true
        }
        // Mixed array+map shapes (integer keys plus non-integer keys) are not converted.
        val hasIntegerKeys = source.fields.keys.any { it.toIntOrNull() != null } ||
            source.methods.keys.any { it.toIntOrNull() != null }
        val hasNamedKeys = source.fields.keys.any { it.toIntOrNull() == null } ||
            source.methods.keys.any { it.toIntOrNull() == null }
        if (hasIntegerKeys && hasNamedKeys) {
            return false
        }
        source.fields.forEach { (name, fieldType) ->
            if (!isAssignable(keyType, tableFieldKeyType(name))) {
                return false
            }
            if (!isAssignable(valueType, fieldType)) {
                return false
            }
        }
        source.methods.forEach { (name, methodType) ->
            if (name in source.fields) {
                return@forEach
            }
            if (!isAssignable(keyType, tableFieldKeyType(name))) {
                return false
            }
            if (!isAssignable(valueType, methodType)) {
                return false
            }
        }
        return true
    }

    private fun isModuleAssignableToJavaMap(keyType: Type, valueType: Type, source: ModuleType): Boolean {
        return isTableAssignableToJavaMap(
            keyType,
            valueType,
            TableType(
                fields = source.fields,
                methods = source.methods,
                indexSignature = source.indexSignature?.let {
                    TableType.IndexSignature(it.keyType, it.valueType)
                }
            )
        )
    }

    private fun tableFieldKeyType(fieldName: String): Type {
        return if (fieldName.toIntOrNull() != null) {
            PrimitiveType.NUMBER
        } else {
            PrimitiveType.STRING
        }
    }

    private fun isNumberKeyType(type: Type): Boolean {
        return isAssignable(PrimitiveType.NUMBER, type) || isAssignable(type, PrimitiveType.NUMBER)
    }

    private fun isKnownType(type: Type): Boolean {
        val normalized = TypeNormalizer.normalize(type)
        return when (normalized) {
            UnknownType, ErrorType, NeverType -> false
            PrimitiveType.UNKNOWN, PrimitiveType.ANY, PrimitiveType.NEVER, PrimitiveType.ERROR -> false
            is TypeParameterType -> normalized.constraint?.let(::isKnownType) == true
            is UnionType -> normalized.types.isNotEmpty() && normalized.types.all(::isKnownType)
            // Raw Object / wildcard upper-bound is not a precise container element type.
            is JavaInstanceType ->
                normalized.classType.javaName.binaryName != "java.lang.Object"
            is JavaClassType ->
                normalized.javaName.binaryName != "java.lang.Object"
            else -> true
        }
    }

    private enum class JavaContainerKind {
        LIST,
        MAP
    }

    private val javaListContainerNames = setOf(
        "java.util.List",
        "java.util.Collection",
        "java.util.AbstractCollection",
        "java.util.AbstractList",
        "java.util.ArrayList",
        "java.util.LinkedList",
        "java.util.Vector",
        "java.util.Stack",
        "java.util.CopyOnWriteArrayList",
        "java.util.Set",
        "java.util.AbstractSet",
        "java.util.HashSet",
        "java.util.LinkedHashSet",
        "java.util.SortedSet",
        "java.util.NavigableSet",
        "java.util.TreeSet",
        "java.util.Queue",
        "java.util.Deque",
        "java.util.AbstractQueue",
        "java.util.ArrayDeque",
        "java.util.concurrent.BlockingQueue",
        "java.util.concurrent.BlockingDeque",
        "java.util.concurrent.CopyOnWriteArraySet"
    )

    private val javaMapContainerNames = setOf(
        "java.util.Map",
        "java.util.AbstractMap",
        "java.util.HashMap",
        "java.util.LinkedHashMap",
        "java.util.TreeMap",
        "java.util.Hashtable",
        "java.util.WeakHashMap",
        "java.util.IdentityHashMap",
        "java.util.SortedMap",
        "java.util.NavigableMap",
        "java.util.concurrent.ConcurrentMap",
        "java.util.concurrent.ConcurrentHashMap",
        "java.util.concurrent.ConcurrentNavigableMap",
        "java.util.concurrent.ConcurrentSkipListMap"
    )

    private fun isJavaArrayAssignable(target: JavaArrayType, source: Type): Boolean = when (source) {
        is JavaArrayType -> target.dimensions == source.dimensions && isAssignable(target.elementType, source.elementType)
        is ArrayType -> target.dimensions == 1 && isAssignable(target.elementType, source.elementType)
        is TableType -> target.dimensions == 1 && isTableAssignableToJavaArrayElements(target.elementType, source)
        is ModuleType -> target.dimensions == 1 && isModuleAssignableToJavaArrayElements(target.elementType, source)
        else -> false
    }

    private fun isJavaPrimitiveAssignable(target: JavaPrimitiveType, source: Type): Boolean {
        if (source is JavaPrimitiveType) {
            return target.kind == source.kind || isNumericJavaWidening(target.kind, source.kind)
        }
        return when (target.kind) {
            JavaPrimitiveType.Kind.BOOLEAN -> PrimitiveType.BOOLEAN.isAssignableFrom(source)
            JavaPrimitiveType.Kind.CHAR -> PrimitiveType.STRING.isAssignableFrom(source)
            JavaPrimitiveType.Kind.BYTE,
            JavaPrimitiveType.Kind.SHORT,
            JavaPrimitiveType.Kind.INT,
            JavaPrimitiveType.Kind.LONG,
            JavaPrimitiveType.Kind.FLOAT,
            JavaPrimitiveType.Kind.DOUBLE -> PrimitiveType.NUMBER.isAssignableFrom(source)
            JavaPrimitiveType.Kind.VOID -> source == PrimitiveType.NIL
        }
    }

    private fun isModuleAssignable(target: ModuleType, source: Type): Boolean {
        return when (source) {
            is ModuleType -> isMemberBearingShapeAssignable(
                target.fields,
                target.methods,
                target.indexSignature?.let { TableType.IndexSignature(it.keyType, it.valueType) },
                source.fields,
                source.methods,
                source.indexSignature?.let { TableType.IndexSignature(it.keyType, it.valueType) }
            )

            is TableType -> isMemberBearingShapeAssignable(
                target.fields,
                target.methods,
                target.indexSignature?.let { TableType.IndexSignature(it.keyType, it.valueType) },
                source.fields,
                source.methods,
                source.indexSignature
            )

            is ClassType -> {
                val availableFields = source.getAllFields()
                val availableMethods = source.getAllMethods()
                target.fields.all { (name, type) -> availableFields[name]?.let { isAssignable(type, it) } == true } &&
                    target.methods.all { (name, type) -> availableMethods[name]?.let { isAssignable(type, it) } == true } &&
                    target.indexSignature == null
            }

            is ArrayType -> {
                if (target.fields.isNotEmpty() || target.methods.isNotEmpty()) {
                    false
                } else {
                    val signature = target.indexSignature ?: return false
                    isAssignable(signature.keyType, PrimitiveType.NUMBER) &&
                        isAssignable(signature.valueType, source.elementType)
                }
            }

            else -> false
        }
    }

    private fun isArrayAssignable(target: ArrayType, source: Type): Boolean = when (source) {
        is ArrayType -> isAssignable(target.elementType, source.elementType)
        // Prefer index-signature array tables; also accept integer-keyed table literals so
        // reflected Java array parameters can be checked against Lua table constructors.
        is TableType -> isTableAssignableToJavaArrayElements(target.elementType, source)
        is ModuleType -> isModuleAssignableToJavaArrayElements(target.elementType, source)
        else -> false
    }

    private fun isTupleAssignable(target: TupleType, source: Type): Boolean {
        val sourceTuple = source as? TupleType ?: return false
        if (target.elementTypes.size != sourceTuple.elementTypes.size) return false
        return target.elementTypes.zip(sourceTuple.elementTypes).all { (expected, actual) ->
            isAssignable(expected, actual)
        }
    }

    private fun isMultiReturnAssignable(target: MultiReturnType, source: Type): Boolean {
        val sourceMultiReturn = source as? MultiReturnType ?: return false
        if (target.types.size != sourceMultiReturn.types.size) return false
        return target.types.zip(sourceMultiReturn.types).all { (expected, actual) ->
            isAssignable(expected, actual)
        }
    }

    private fun isVarargAssignable(target: VarargType, source: Type): Boolean {
        val sourceVararg = source as? VarargType ?: return false
        return isAssignable(target.elementType, sourceVararg.elementType)
    }

    private fun isAppliedAssignable(target: AppliedType, source: Type): Boolean {
        val sourceApplied = source as? AppliedType ?: return false
        if (target.baseName != sourceApplied.baseName) return false
        if (target.typeArguments.size != sourceApplied.typeArguments.size) return false
        return target.typeArguments.zip(sourceApplied.typeArguments).all { (expected, actual) ->
            isAssignable(expected, actual)
        }
    }

    private fun typeParametersCompatible(target: List<TypeParameterType>, source: List<TypeParameterType>): Boolean {
        if (target.size != source.size) return false
        return target.zip(source).all { (expected, actual) -> typeParameterShapeCompatible(expected, actual) }
    }

    private fun typeParameterShapeCompatible(target: TypeParameterType, source: TypeParameterType): Boolean {
        val targetConstraint = target.constraint
        val sourceConstraint = source.constraint
        if ((targetConstraint == null) != (sourceConstraint == null)) return false
        if (targetConstraint != null && sourceConstraint != null && !isAssignable(targetConstraint, sourceConstraint)) return false

        val targetDefault = target.defaultType
        val sourceDefault = source.defaultType
        if ((targetDefault == null) != (sourceDefault == null)) return false
        if (targetDefault != null && sourceDefault != null && !isAssignable(targetDefault, sourceDefault)) return false

        return true
    }

    private fun parameterListsCompatible(target: List<FunctionParameter>, source: List<FunctionParameter>): Boolean {
        val requiredTarget = target.count { !it.optional && !it.vararg }
        val requiredSource = source.count { !it.optional && !it.vararg }
        if (requiredTarget != requiredSource && target.none { it.vararg } && source.none { it.vararg }) {
            return false
        }

        val maxParameters = maxOf(target.size, source.size)
        for (index in 0 until maxParameters) {
            val targetParameter = target.getOrNull(index) ?: target.lastOrNull { it.vararg } ?: return false
            val sourceParameter = source.getOrNull(index)
                ?: source.lastOrNull { it.vararg }
                ?: return targetParameter.optional || targetParameter.vararg

            if (!isAssignable(targetParameter.type, sourceParameter.type)) {
                return false
            }
        }

        return true
    }

    private fun isSameOrSubclass(target: ClassType, source: ClassType): ClassType? {
        var current: ClassType? = source
        while (current != null) {
            if (current.name == target.name) {
                return current
            }
            current = current.superClass
        }
        return null
    }

    private fun isSameOrJavaSubclass(target: JavaClassType, source: JavaClassType): Boolean {
        fun visit(current: JavaClassType?, visited: MutableSet<String>): Boolean {
            current ?: return false
            if (!visited.add(current.javaName.binaryName)) {
                return false
            }
            if (current.javaName.binaryName == target.javaName.binaryName) {
                return true
            }
            if (visit(current.superClass, visited)) {
                return true
            }
            return current.interfaces.any { visit(it, visited) }
        }
        return visit(source, linkedSetOf())
    }

    private fun isNumericJavaWidening(target: JavaPrimitiveType.Kind, source: JavaPrimitiveType.Kind): Boolean {
        val sourceRank = javaNumericRank(source) ?: return false
        val targetRank = javaNumericRank(target) ?: return false
        return sourceRank <= targetRank
    }

    private fun javaNumericRank(kind: JavaPrimitiveType.Kind): Int? = when (kind) {
        JavaPrimitiveType.Kind.BYTE -> 1
        JavaPrimitiveType.Kind.SHORT -> 2
        JavaPrimitiveType.Kind.CHAR -> 2
        JavaPrimitiveType.Kind.INT -> 3
        JavaPrimitiveType.Kind.LONG -> 4
        JavaPrimitiveType.Kind.FLOAT -> 5
        JavaPrimitiveType.Kind.DOUBLE -> 6
        JavaPrimitiveType.Kind.BOOLEAN,
        JavaPrimitiveType.Kind.VOID -> null
    }

    private fun isTableLike(type: Type): Boolean = when (type) {
        is TableType, is ModuleType, is ClassType, is ArrayType, is JavaClassType, is JavaInstanceType, is JavaArrayType -> true
        else -> false
    }

    private fun combineIntersectionSource(source: IntersectionType): Type? {
        val tableShapes = source.types.mapNotNull(::asTableShape)
        if (tableShapes.size != source.types.size) {
            return null
        }

        var combined = tableShapes.first()
        for (next in tableShapes.drop(1)) {
            combined = mergeTableShapes(combined, next) ?: return null
        }

        return combined
    }

    private fun asTableShape(type: Type): TableType? = when (type) {
        is TableType -> type
        is ModuleType -> TableType(fields = type.fields, methods = type.methods, indexSignature = type.indexSignature?.let {
            TableType.IndexSignature(it.keyType, it.valueType)
        })
        is ClassType -> TableType(fields = type.getAllFields(), methods = type.getAllMethods())
        is ArrayType -> TableType(indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, type.elementType))
        is JavaClassType -> TableType(
            fields = type.allStaticMembers().mapValues { (_, member) -> member.valueType } + type.allInnerClasses(),
            methods = type.allStaticMembers().filterValues { it.valueType is CallableType }.mapValues { (_, member) -> member.valueType }
        )
        is JavaInstanceType -> TableType(
            fields = type.allInstanceMembers().mapValues { (_, member) -> member.valueType },
            methods = type.allInstanceMembers().filterValues { it.valueType is CallableType }.mapValues { (_, member) -> member.valueType }
        )
        is JavaArrayType -> TableType(indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, type.elementType))
        else -> null
    }

    private fun isMemberBearingShapeAssignable(
        targetFields: Map<String, Type>,
        targetMethods: Map<String, Type>,
        targetIndex: TableType.IndexSignature?,
        sourceFields: Map<String, Type>,
        sourceMethods: Map<String, Type>,
        sourceIndex: TableType.IndexSignature?
    ): Boolean {
        if (!targetFields.all { (name, type) -> sourceFields[name]?.let { isAssignable(type, it) } == true }) {
            return false
        }
        if (!targetMethods.all { (name, type) -> sourceMethods[name]?.let { isAssignable(type, it) } == true }) {
            return false
        }
        if (targetIndex == null) {
            return true
        }
        val resolvedSourceIndex = sourceIndex ?: return false
        return isAssignable(targetIndex.keyType, resolvedSourceIndex.keyType) &&
            isAssignable(targetIndex.valueType, resolvedSourceIndex.valueType)
    }

    private fun mergeTableShapes(left: TableType, right: TableType): TableType? {
        val mergedFields = mergeStructuralMembers(left.fields, right.fields) ?: return null
        val mergedMethods = mergeStructuralMembers(left.methods, right.methods) ?: return null
        val mergedIndexSignature = mergeIndexSignatures(left.indexSignature, right.indexSignature)
        if (mergedIndexSignature.conflict) return null

        return TableType(
            fields = mergedFields,
            methods = mergedMethods,
            indexSignature = mergedIndexSignature.indexSignature
        )
    }

    private fun mergeStructuralMembers(left: Map<String, Type>, right: Map<String, Type>): Map<String, Type>? = buildMap {
        putAll(left)
        right.forEach { (name, type) ->
            val existing = this[name]
            if (existing == null) {
                put(name, type)
            } else {
                val mergedType = mergeStructuralType(existing, type) ?: return null
                put(name, mergedType)
            }
        }
    }

    private fun mergeIndexSignatures(
        left: TableType.IndexSignature?,
        right: TableType.IndexSignature?
    ): MergedIndexSignature {
        if (left == null && right == null) return MergedIndexSignature()
        if (left == null) return MergedIndexSignature(indexSignature = right)
        if (right == null) return MergedIndexSignature(indexSignature = left)

        val mergedKeyType = mergeStructuralType(left.keyType, right.keyType)
            ?: return MergedIndexSignature(conflict = true)
        val mergedValueType = mergeStructuralType(left.valueType, right.valueType)
            ?: return MergedIndexSignature(conflict = true)

        return MergedIndexSignature(
            indexSignature = TableType.IndexSignature(
                keyType = mergedKeyType,
                valueType = mergedValueType
            )
        )
    }

    private data class MergedIndexSignature(
        val indexSignature: TableType.IndexSignature? = null,
        val conflict: Boolean = false
    )

    private fun mergeStructuralType(left: Type, right: Type): Type? {
        val merged = TypeNormalizer.normalize(IntersectionType(linkedSetOf(left, right)))
        return if (merged == NeverType) null else merged
    }

    private fun canonicalPrimitive(type: PrimitiveType): PrimitiveType = when (type.kind) {
        PrimitiveType.Kind.NIL -> PrimitiveType.NIL
        PrimitiveType.Kind.BOOLEAN -> PrimitiveType.BOOLEAN
        PrimitiveType.Kind.NUMBER -> PrimitiveType.NUMBER
        PrimitiveType.Kind.STRING -> PrimitiveType.STRING
        PrimitiveType.Kind.FUNCTION -> PrimitiveType.FUNCTION
        PrimitiveType.Kind.TABLE -> PrimitiveType.TABLE
        PrimitiveType.Kind.THREAD -> PrimitiveType.THREAD
        PrimitiveType.Kind.USERDATA -> PrimitiveType.USERDATA
        PrimitiveType.Kind.ANY -> PrimitiveType.ANY
        PrimitiveType.Kind.UNKNOWN -> PrimitiveType.UNKNOWN
        PrimitiveType.Kind.NEVER -> PrimitiveType.NEVER
        PrimitiveType.Kind.ERROR -> PrimitiveType.ERROR
    }
}

fun Type.isAssignableFrom(source: Type): Boolean = TypeRelations.isAssignable(this, source)
