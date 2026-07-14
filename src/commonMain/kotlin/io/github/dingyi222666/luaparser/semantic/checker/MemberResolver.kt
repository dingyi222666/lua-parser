package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
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
            is JavaClassType -> resolveJavaStaticMember(normalized, memberName)
            is JavaInstanceType -> resolveJavaInstanceMember(normalized, memberName, preferMethod)
            is JavaArrayType -> resolveJavaArrayMember(normalized, memberName)
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
            is JavaClassType -> resolveJavaClassIndex(normalized, indexNode)
            is JavaInstanceType -> resolveJavaInstanceIndex(normalized, indexNode)
            is JavaArrayType -> {
                if (PrimitiveType.NUMBER.isAssignableFrom(indexType)) {
                    // Peel one rank: nested single-rank wrappers return elementType;
                    // flat multi-dim (dimensions>1) reduces dimensions by one so hover
                    // stays T[]...[] without inventing bare component early.
                    val peeled = when {
                        normalized.dimensions > 1 ->
                            JavaArrayType(
                                elementType = normalized.elementType,
                                dimensions = normalized.dimensions - 1
                            )
                        else -> normalized.elementType
                    }
                    MemberResolution(
                        type = peeled,
                        accessKind = MemberAccessKind.INDEX,
                        baseType = normalized
                    )
                } else {
                    MemberResolution(baseType = normalized, failureReason = MemberFailureReason.INVALID_INDEX_TYPE)
                }
            }
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
            MemberResolution(
                type = bindMethodReceiver(type, receiverType, kind, preferMethod),
                accessKind = kind,
                baseType = tableType
            )
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
            val resolvedType = if (classType.isJavaProviderClassReference() && kind == MemberAccessKind.METHOD) {
                type.withJavaCallableSurface(receiverType = receiverType, includeReceiver = preferMethod)
            } else {
                // ClassType methods bind implicit self for both colon and dot access when the
                // signature lacks a compatible receiver (TASK-681). Module/table free-function
                // surfaces keep preferMethod-gated binding via their own resolvers (TASK-668).
                bindMethodReceiver(
                    type,
                    receiverType,
                    kind,
                    preferMethod = preferMethod || kind == MemberAccessKind.METHOD
                )
            }
            MemberResolution(type = resolvedType, accessKind = kind, baseType = classType)
        } ?: MemberResolution(baseType = classType, failureReason = MemberFailureReason.MISSING_MEMBER)
    }

    private fun resolveJavaStaticMember(
        classType: JavaClassType,
        memberName: String
    ): MemberResolution {
        classType.allInnerClasses()[memberName]?.let { innerClass ->
            return MemberResolution(type = innerClass, accessKind = MemberAccessKind.FIELD, baseType = classType)
        }

        val member = classType.allStaticMembers()[memberName]
            ?: return classType.resolveStaticJavaBeanProperty(memberName)
                ?.let { property ->
                    MemberResolution(
                        type = property.valueType,
                        accessKind = MemberAccessKind.FIELD,
                        baseType = classType
                    )
                }
            ?: MemberResolution(baseType = classType, failureReason = MemberFailureReason.MISSING_MEMBER)
        val accessKind = javaAccessKind(member.memberKind, member.valueType)
        return MemberResolution(
            type = if (accessKind == MemberAccessKind.METHOD) {
                member.valueType.withJavaCallableSurface(signatureMetadata = member.signatureMetadata)
            } else {
                member.valueType
            },
            accessKind = accessKind,
            baseType = classType
        )
    }

    private fun resolveJavaInstanceMember(
        instanceType: JavaInstanceType,
        memberName: String,
        includeReceiver: Boolean
    ): MemberResolution {
        val member = instanceType.allInstanceMembers()[memberName]
            ?: return instanceType.resolveInstanceJavaBeanProperty(memberName)
                ?.let { property ->
                    MemberResolution(
                        type = property.valueType,
                        accessKind = MemberAccessKind.FIELD,
                        baseType = instanceType
                    )
                }
            ?: MemberResolution(baseType = instanceType, failureReason = MemberFailureReason.MISSING_MEMBER)
        val accessKind = javaAccessKind(member.memberKind, member.valueType)
        return MemberResolution(
            type = if (accessKind == MemberAccessKind.METHOD) {
                member.valueType.withJavaCallableSurface(
                    receiverType = instanceType,
                    includeReceiver = includeReceiver,
                    signatureMetadata = member.signatureMetadata
                )
            } else {
                member.valueType
            },
            accessKind = accessKind,
            baseType = instanceType
        )
    }

    private fun resolveJavaArrayMember(arrayType: JavaArrayType, memberName: String): MemberResolution {
        return if (memberName == "length") {
            MemberResolution(type = PrimitiveType.NUMBER, accessKind = MemberAccessKind.FIELD, baseType = arrayType)
        } else {
            MemberResolution(baseType = arrayType, failureReason = MemberFailureReason.MISSING_MEMBER)
        }
    }

    private fun resolveModuleMember(
        moduleType: ModuleType,
        receiverType: Type,
        memberName: String,
        preferMethod: Boolean
    ): MemberResolution {
        // Prefer reflected static methods from __class (e.g. Map$Entry.comparingByKey) over any
        // non-callable field collision on the module table surface.
        val javaStatic = moduleType.javaClassSurface()
            ?.let { resolveJavaStaticMember(it, memberName) }
            ?.takeIf { it.isSuccess }
            ?.copy(baseType = moduleType)

        val member = if (preferMethod) {
            moduleType.methods[memberName]?.let { MemberAccessKind.METHOD to it }
                ?: moduleType.fields[memberName]?.let { MemberAccessKind.FIELD to it }
        } else {
            moduleType.fields[memberName]?.let { MemberAccessKind.FIELD to it }
                ?: moduleType.methods[memberName]?.let { MemberAccessKind.METHOD to it }
        }

        val moduleResolution = member?.let { (kind, type) ->
            val resolvedType = if (moduleType.isJavaBackedModule() && kind == MemberAccessKind.METHOD) {
                type.withJavaCallableSurface()
            } else if (moduleType.isJavaBackedModule() && kind == MemberAccessKind.FIELD && type is CallableType) {
                // Static helpers may land on fields as callable types; still surface as methods.
                type.withJavaCallableSurface()
            } else {
                // Dot access (preferMethod=false) keeps free-function shape for module methods
                // such as AndroLua luajava.bindClass / createProxy — no synthetic self: luajava.
                bindMethodReceiver(type, receiverType, kind, preferMethod)
            }
            val accessKind = when {
                kind == MemberAccessKind.METHOD -> MemberAccessKind.METHOD
                moduleType.isJavaBackedModule() && type is CallableType -> MemberAccessKind.METHOD
                else -> kind
            }
            MemberResolution(type = resolvedType, accessKind = accessKind, baseType = moduleType)
        }

        return when {
            javaStatic != null && javaStatic.accessKind == MemberAccessKind.METHOD -> javaStatic
            moduleResolution != null && moduleResolution.accessKind == MemberAccessKind.METHOD -> moduleResolution
            javaStatic != null -> javaStatic
            moduleResolution != null -> moduleResolution
            else -> MemberResolution(baseType = moduleType, failureReason = MemberFailureReason.MISSING_MEMBER)
        }
    }

    private fun resolveTableIndex(tableType: TableType, indexNode: ExpressionNode, indexType: Type): MemberResolution {
        val literalKey = literalTableKey(indexNode)
        if (literalKey != null) {
            tableType.fields[literalKey]?.let {
                return MemberResolution(type = it, accessKind = MemberAccessKind.FIELD, baseType = tableType)
            }
            tableType.methods[literalKey]?.let {
                return MemberResolution(
                    // Index access is never colon sugar; keep free-function callable shape.
                    type = bindMethodReceiver(
                        it,
                        receiverBindingType(tableType),
                        MemberAccessKind.METHOD,
                        preferMethod = false
                    ),
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
            val resolvedType = if (classType.isJavaProviderClassReference()) {
                it.withJavaCallableSurface(receiverType = receiverBindingType(classType), includeReceiver = false)
            } else {
                // Index access is never colon sugar; keep free-function callable shape.
                bindMethodReceiver(
                    it,
                    receiverBindingType(classType),
                    MemberAccessKind.METHOD,
                    preferMethod = false
                )
            }
            return MemberResolution(
                type = resolvedType,
                accessKind = MemberAccessKind.METHOD,
                baseType = classType
            )
        }
        return MemberResolution(baseType = classType, failureReason = MemberFailureReason.MISSING_MEMBER)
    }

    private fun resolveJavaClassIndex(
        classType: JavaClassType,
        indexNode: ExpressionNode
    ): MemberResolution {
        val key = stringLiteralKey(indexNode)
            ?: return MemberResolution(baseType = classType, failureReason = MemberFailureReason.INVALID_INDEX_TYPE)
        return resolveJavaStaticMember(classType, key)
    }

    private fun resolveJavaInstanceIndex(
        instanceType: JavaInstanceType,
        indexNode: ExpressionNode
    ): MemberResolution {
        val key = stringLiteralKey(indexNode)
            ?: return MemberResolution(baseType = instanceType, failureReason = MemberFailureReason.INVALID_INDEX_TYPE)
        return resolveJavaInstanceMember(instanceType, key, includeReceiver = false)
    }

    private fun resolveModuleIndex(moduleType: ModuleType, indexNode: ExpressionNode, indexType: Type): MemberResolution {
        val literalKey = literalTableKey(indexNode)
        if (literalKey != null) {
            // Prefer nested/interface static members from __class (Map$Entry.comparingByKey style).
            moduleType.javaClassSurface()?.let { classType ->
                val javaResolution = resolveJavaStaticMember(classType, literalKey)
                if (javaResolution.isSuccess && javaResolution.accessKind == MemberAccessKind.METHOD) {
                    return javaResolution.copy(baseType = moduleType)
                }
            }
            moduleType.fields[literalKey]?.let {
                if (moduleType.isJavaBackedModule() && it is CallableType) {
                    return MemberResolution(
                        type = it.withJavaCallableSurface(),
                        accessKind = MemberAccessKind.METHOD,
                        baseType = moduleType
                    )
                }
                return MemberResolution(type = it, accessKind = MemberAccessKind.FIELD, baseType = moduleType)
            }
            moduleType.methods[literalKey]?.let {
                val resolvedType = if (moduleType.isJavaBackedModule()) {
                    it.withJavaCallableSurface()
                } else {
                    // Index access is never colon sugar; keep free-function callable shape.
                    bindMethodReceiver(
                        it,
                        receiverBindingType(moduleType),
                        MemberAccessKind.METHOD,
                        preferMethod = false
                    )
                }
                return MemberResolution(
                    type = resolvedType,
                    accessKind = MemberAccessKind.METHOD,
                    baseType = moduleType
                )
            }
            moduleType.javaClassSurface()?.let { classType ->
                val javaResolution = resolveJavaStaticMember(classType, literalKey)
                if (javaResolution.isSuccess) {
                    return javaResolution.copy(baseType = moduleType)
                }
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

    /**
     * Bind an implicit self receiver only for colon-method access ([preferMethod] true).
     *
     * Dot/index access on METHOD members is free-function style. Injecting `self: Receiver`
     * there invents fake parameters for helpers such as AndroLua `luajava.bindClass` /
     * `createProxy` and breaks documented completion/hover details (TASK-668).
     */
    private fun bindMethodReceiver(
        type: Type,
        receiverType: Type,
        accessKind: MemberAccessKind,
        preferMethod: Boolean
    ): Type {
        if (accessKind != MemberAccessKind.METHOD || !preferMethod) {
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
        if (firstParameter?.name == "self") {
            val parameters = signature.parameters.toMutableList()
            parameters[0] = firstParameter.copy(type = receiverType)
            return FunctionType(
                parameters = parameters,
                returnType = signature.returnType,
                typeParameters = signature.typeParameters
            )
        }
        if (firstParameter?.type?.isAssignableFrom(receiverType) == true) {
            return signature
        }

        // Rebuild name so displayName/signature help stay aligned with the injected self param.
        val parameters = listOf(FunctionParameter(name = "self", type = receiverType)) + signature.parameters
        return FunctionType(
            parameters = parameters,
            returnType = signature.returnType,
            typeParameters = signature.typeParameters
        )
    }

    private fun receiverBindingType(baseType: Type): Type {
        val normalized = baseType
        return if (normalized is AppliedType) normalized else normalized
    }

    private fun javaAccessKind(memberKind: JavaMemberKind, valueType: Type): MemberAccessKind {
        return when {
            memberKind == JavaMemberKind.METHOD -> MemberAccessKind.METHOD
            valueType is CallableType -> MemberAccessKind.METHOD
            else -> MemberAccessKind.FIELD
        }
    }
}
