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
        is TableType -> source.indexSignature?.let { signature ->
            isAssignable(signature.keyType, PrimitiveType.NUMBER) &&
                isAssignable(target.elementType, signature.valueType)
        } ?: false

        is ModuleType -> source.indexSignature?.let { signature ->
            isAssignable(signature.keyType, PrimitiveType.NUMBER) &&
                isAssignable(target.elementType, signature.valueType)
        } ?: false

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

    private fun isTableLike(type: Type): Boolean = when (type) {
        is TableType, is ModuleType, is ClassType, is ArrayType -> true
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
