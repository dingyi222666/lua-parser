package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeExpansion
import io.github.dingyi222666.luaparser.semantic.types.resolve.intersectionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf

class MemberResolver(
    private val binder: BinderPassResult
) {

    fun resolveMember(baseType: Type, memberName: String, preferMethod: Boolean, lexicalScopeId: ScopeId): MemberResolution {
        val receiverType = receiverBindingType(baseType)
        val normalized = TypeExpansion.expandForMemberSurface(baseType, lexicalScopeId, binder)

        return when (normalized) {
            is TableType -> resolveTableMember(normalized, receiverType, memberName, preferMethod)
            is ModuleType -> resolveModuleMember(normalized, receiverType, memberName, preferMethod)
            is ClassType -> resolveClassMember(normalized, receiverType, memberName, preferMethod)
            is TypeParameterType -> normalized.constraint
                ?.let { resolveMember(it, memberName, preferMethod, lexicalScopeId) }
                ?: MemberResolution(baseType = normalized, failureReason = MemberFailureReason.UNSUPPORTED_BASE_TYPE)

            is UnionType -> resolveUnion(normalized.types) { branch ->
                resolveMember(branch, memberName, preferMethod, lexicalScopeId)
            }

            is IntersectionType -> resolveIntersection(normalized.types) { branch ->
                resolveMember(branch, memberName, preferMethod, lexicalScopeId)
            }

            else -> MemberResolution(baseType = normalized, failureReason = MemberFailureReason.UNSUPPORTED_BASE_TYPE)
        }
    }

    fun resolveIndex(baseType: Type, indexNode: ExpressionNode, indexType: Type, lexicalScopeId: ScopeId): MemberResolution {
        val normalized = TypeExpansion.expandForMemberSurface(baseType, lexicalScopeId, binder)

        return when (normalized) {
            is ArrayType -> {
                if (PrimitiveType.NUMBER.isAssignableFrom(indexType)) {
                    MemberResolution(type = normalized.elementType, accessKind = MemberAccessKind.INDEX, baseType = normalized)
                } else {
                    MemberResolution(baseType = normalized, failureReason = MemberFailureReason.INVALID_INDEX_TYPE)
                }
            }

            is TupleType -> {
                val index = integerLiteralIndex(indexNode)
                val type = index?.let { normalized.elementTypes.getOrNull(it - 1) }
                if (type != null) {
                    MemberResolution(type = type, accessKind = MemberAccessKind.INDEX, baseType = normalized)
                } else {
                    MemberResolution(baseType = normalized, failureReason = MemberFailureReason.INVALID_INDEX_TYPE)
                }
            }

            is TableType -> resolveTableIndex(normalized, indexNode, indexType)
            is ModuleType -> resolveModuleIndex(normalized, indexNode, indexType)
            is ClassType -> resolveClassIndex(normalized, indexNode)
            is TypeParameterType -> normalized.constraint
                ?.let { resolveIndex(it, indexNode, indexType, lexicalScopeId) }
                ?: MemberResolution(baseType = normalized, failureReason = MemberFailureReason.UNSUPPORTED_BASE_TYPE)

            is UnionType -> resolveUnion(normalized.types) { branch ->
                resolveIndex(branch, indexNode, indexType, lexicalScopeId)
            }

            is IntersectionType -> resolveIntersection(normalized.types) { branch ->
                resolveIndex(branch, indexNode, indexType, lexicalScopeId)
            }

            else -> MemberResolution(baseType = normalized, failureReason = MemberFailureReason.UNSUPPORTED_BASE_TYPE)
        }
    }

    private fun resolveTableMember(
        tableType: TableType,
        receiverType: Type,
        memberName: String,
        preferMethod: Boolean
    ): MemberResolution {
        val member = if (preferMethod) {
            tableType.methods[memberName]?.let { MemberAccessKind.METHOD to it }
                ?: tableType.fields[memberName]?.let { MemberAccessKind.FIELD to it }
        } else {
            tableType.fields[memberName]?.let { MemberAccessKind.FIELD to it }
                ?: tableType.methods[memberName]?.let { MemberAccessKind.METHOD to it }
        }
        return member?.let { (kind, type) ->
            MemberResolution(type = bindMethodReceiver(type, receiverType, kind), accessKind = kind, baseType = tableType)
        } ?: MemberResolution(baseType = tableType, failureReason = MemberFailureReason.MISSING_MEMBER)
    }

    private fun resolveClassMember(
        classType: ClassType,
        receiverType: Type,
        memberName: String,
        preferMethod: Boolean
    ): MemberResolution {
        val fields = classType.getAllFields()
        val methods = classType.getAllMethods()
        val member = if (preferMethod) {
            methods[memberName]?.let { MemberAccessKind.METHOD to it }
                ?: fields[memberName]?.let { MemberAccessKind.FIELD to it }
        } else {
            fields[memberName]?.let { MemberAccessKind.FIELD to it }
                ?: methods[memberName]?.let { MemberAccessKind.METHOD to it }
        }
        return member?.let { (kind, type) ->
            MemberResolution(type = bindMethodReceiver(type, receiverType, kind), accessKind = kind, baseType = classType)
        } ?: MemberResolution(baseType = classType, failureReason = MemberFailureReason.MISSING_MEMBER)
    }

    private fun resolveModuleMember(
        moduleType: ModuleType,
        receiverType: Type,
        memberName: String,
        preferMethod: Boolean
    ): MemberResolution {
        val member = if (preferMethod) {
            moduleType.methods[memberName]?.let { MemberAccessKind.METHOD to it }
                ?: moduleType.fields[memberName]?.let { MemberAccessKind.FIELD to it }
        } else {
            moduleType.fields[memberName]?.let { MemberAccessKind.FIELD to it }
                ?: moduleType.methods[memberName]?.let { MemberAccessKind.METHOD to it }
        }
        return member?.let { (kind, type) ->
            MemberResolution(type = bindMethodReceiver(type, receiverType, kind), accessKind = kind, baseType = moduleType)
        } ?: MemberResolution(baseType = moduleType, failureReason = MemberFailureReason.MISSING_MEMBER)
    }

    private fun resolveTableIndex(tableType: TableType, indexNode: ExpressionNode, indexType: Type): MemberResolution {
        val literalKey = literalTableKey(indexNode)
        if (literalKey != null) {
            tableType.fields[literalKey]?.let {
                return MemberResolution(type = it, accessKind = MemberAccessKind.FIELD, baseType = tableType)
            }
            tableType.methods[literalKey]?.let {
                return MemberResolution(
                    type = bindMethodReceiver(it, receiverBindingType(tableType), MemberAccessKind.METHOD),
                    accessKind = MemberAccessKind.METHOD,
                    baseType = tableType
                )
            }
        }

        val indexSignature = tableType.indexSignature
        return if (indexSignature != null && indexSignature.keyType.isAssignableFrom(indexType)) {
            MemberResolution(type = indexSignature.valueType, accessKind = MemberAccessKind.INDEX, baseType = tableType)
        } else {
            MemberResolution(
                baseType = tableType,
                failureReason = if (indexSignature == null) MemberFailureReason.MISSING_MEMBER else MemberFailureReason.INVALID_INDEX_TYPE
            )
        }
    }

    private fun resolveClassIndex(classType: ClassType, indexNode: ExpressionNode): MemberResolution {
        val key = stringLiteralKey(indexNode)
            ?: return MemberResolution(baseType = classType, failureReason = MemberFailureReason.INVALID_INDEX_TYPE)
        classType.getAllFields()[key]?.let {
            return MemberResolution(type = it, accessKind = MemberAccessKind.FIELD, baseType = classType)
        }
        classType.getAllMethods()[key]?.let {
                return MemberResolution(
                type = bindMethodReceiver(it, receiverBindingType(classType), MemberAccessKind.METHOD),
                accessKind = MemberAccessKind.METHOD,
                baseType = classType
            )
        }
        return MemberResolution(baseType = classType, failureReason = MemberFailureReason.MISSING_MEMBER)
    }

    private fun resolveModuleIndex(moduleType: ModuleType, indexNode: ExpressionNode, indexType: Type): MemberResolution {
        val literalKey = literalTableKey(indexNode)
        if (literalKey != null) {
            moduleType.fields[literalKey]?.let {
                return MemberResolution(type = it, accessKind = MemberAccessKind.FIELD, baseType = moduleType)
            }
            moduleType.methods[literalKey]?.let {
                return MemberResolution(
                    type = bindMethodReceiver(it, receiverBindingType(moduleType), MemberAccessKind.METHOD),
                    accessKind = MemberAccessKind.METHOD,
                    baseType = moduleType
                )
            }
        }

        val indexSignature = moduleType.indexSignature
        return if (indexSignature != null && indexSignature.keyType.isAssignableFrom(indexType)) {
            MemberResolution(type = indexSignature.valueType, accessKind = MemberAccessKind.INDEX, baseType = moduleType)
        } else {
            MemberResolution(
                baseType = moduleType,
                failureReason = if (indexSignature == null) MemberFailureReason.MISSING_MEMBER else MemberFailureReason.INVALID_INDEX_TYPE
            )
        }
    }

    private fun resolveUnion(branches: Set<Type>, resolver: (Type) -> MemberResolution): MemberResolution {
        val results = branches.map { resolver(it) }
        if (results.any { !it.isSuccess }) {
            return results.first { !it.isSuccess }
        }
        val types = results.mapNotNull { it.type }
        return MemberResolution(
            type = unionTypeOf(types),
            accessKind = results.firstOrNull()?.accessKind,
            baseType = UnionType(branches)
        )
    }

    private fun resolveIntersection(branches: Set<Type>, resolver: (Type) -> MemberResolution): MemberResolution {
        val results = branches.mapNotNull { resolver(it).takeIf(MemberResolution::isSuccess) }
        if (results.isEmpty()) {
            return MemberResolution(baseType = IntersectionType(branches), failureReason = MemberFailureReason.MISSING_MEMBER)
        }
        val types = results.mapNotNull { it.type }
        val merged = when (types.size) {
            0 -> null
            1 -> types.single()
            else -> intersectionTypeOf(types)
        }
        return MemberResolution(type = merged, accessKind = results.firstOrNull()?.accessKind, baseType = IntersectionType(branches))
    }

    private fun literalTableKey(node: ExpressionNode): String? {
        stringLiteralKey(node)?.let { return it }
        return integerLiteralIndex(node)?.toString()
    }

    private fun stringLiteralKey(node: ExpressionNode): String? {
        val constant = node as? ConstantNode ?: return null
        return if (constant.constantType == ConstantNode.TYPE.STRING) constant.stringOf() else null
    }

    private fun integerLiteralIndex(node: ExpressionNode): Int? {
        val constant = node as? ConstantNode ?: return null
        return when (constant.constantType) {
            ConstantNode.TYPE.INTERGER -> constant.rawValue.toString().toIntOrNull()
            ConstantNode.TYPE.FLOAT -> {
                val value = constant.rawValue.toString().toDoubleOrNull() ?: return null
                value.toInt().takeIf { it.toDouble() == value }
            }

            else -> null
        }
    }

    private fun bindMethodReceiver(type: Type, receiverType: Type, accessKind: MemberAccessKind): Type {
        if (accessKind != MemberAccessKind.METHOD) {
            return type
        }

        return when (type) {
            is FunctionType -> bindMethodSignature(type, receiverType)
            is OverloadedFunctionType -> OverloadedFunctionType(type.callSignatures.map { bindMethodSignature(it, receiverType) })
            else -> type
        }
    }

    private fun bindMethodSignature(signature: FunctionType, receiverType: Type): FunctionType {
        val firstParameter = signature.parameters.firstOrNull()
        if (firstParameter != null && firstParameter.type.isAssignableFrom(receiverType)) {
            return signature
        }

        return signature.copy(
            parameters = listOf(FunctionParameter(name = "self", type = receiverType)) + signature.parameters
        )
    }

    private fun receiverBindingType(baseType: Type): Type {
        val normalized = baseType
        return if (normalized is AppliedType) normalized else normalized
    }
}
