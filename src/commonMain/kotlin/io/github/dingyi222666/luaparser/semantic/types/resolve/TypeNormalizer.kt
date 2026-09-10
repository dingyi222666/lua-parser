package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaConstructorType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaSignatureMetadata
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadSet
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
import io.github.dingyi222666.luaparser.semantic.types.model.unwrapAliases
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType

object TypeNormalizer {
    fun normalize(type: Type): Type = normalize(type, mutableListOf())

    private fun normalize(type: Type, aliasStack: MutableList<AliasType>): Type = when (type) {
        is AliasType -> normalizeAlias(type, aliasStack)
        is PrimitiveType -> canonicalizePrimitive(type)
        is LiteralType -> LiteralType(type.value, canonicalizeLiteralBaseType(type.baseType))
        is FunctionType -> FunctionType(
            parameters = type.parameters.map { parameter ->
                FunctionParameter(
                    name = parameter.name,
                    type = normalize(parameter.type, aliasStack),
                    optional = parameter.optional,
                    vararg = parameter.vararg
                )
            },
            returnType = normalize(type.returnType, aliasStack),
            typeParameters = type.typeParameters.map { normalizeTypeParameter(it, aliasStack) }
        )

        is OverloadedFunctionType -> {
            val normalizedSignatures = type.callSignatures.map { normalize(it, aliasStack) as FunctionType }
            val dedupedSignatures = dedupeOverloadSignatures(normalizedSignatures)
            when (dedupedSignatures.size) {
                1 -> dedupedSignatures.single()
                else -> OverloadedFunctionType(dedupedSignatures)
            }
        }

        is TableType -> TableType(
            fields = type.fields.mapValues { (_, value) -> normalize(value, aliasStack) },
            methods = type.methods.mapValues { (_, value) -> normalize(value, aliasStack) },
            indexSignature = type.indexSignature?.let {
                TableType.IndexSignature(
                    keyType = normalize(it.keyType, aliasStack),
                    valueType = normalize(it.valueType, aliasStack)
                )
            }
        )

        is ModuleType -> ModuleType(
            moduleName = type.moduleName,
            fields = type.fields.mapValues { (_, value) -> normalize(value, aliasStack) },
            methods = type.methods.mapValues { (_, value) -> normalize(value, aliasStack) },
            indexSignature = type.indexSignature?.let {
                ModuleType.IndexSignature(
                    keyType = normalize(it.keyType, aliasStack),
                    valueType = normalize(it.valueType, aliasStack)
                )
            }
        )

        is ClassType -> ClassType(
            name = type.name,
            fields = type.fields.mapValues { (_, value) -> normalize(value, aliasStack) },
            methods = type.methods.mapValues { (_, value) -> normalize(value, aliasStack) },
            superClass = type.superClass?.let { normalize(it, aliasStack) as ClassType },
            superType = type.superType?.let { normalize(it, aliasStack) },
            typeParameters = type.typeParameters.map { normalizeTypeParameter(it, aliasStack) },
            alias = type.alias?.let { normalizeAliasPreservingNode(it, aliasStack) },
            javaClassName = type.javaClassName
        )

        is UnionType -> normalizeUnion(type.types.map { normalize(it, aliasStack) })
        is IntersectionType -> normalizeIntersection(type.types.map { normalize(it, aliasStack) })
        is TupleType -> TupleType(type.elementTypes.map { normalize(it, aliasStack) })
        is MultiReturnType -> MultiReturnType(type.types.map { normalize(it, aliasStack) })
        is VarargType -> VarargType(normalize(type.elementType, aliasStack))
        is ArrayType -> ArrayType(normalize(type.elementType, aliasStack))
        is JavaClassType -> normalizeJavaClass(type, aliasStack)
        is JavaInstanceType -> JavaInstanceType(
            classType = normalize(type.classType, aliasStack) as JavaClassType,
            typeArguments = type.typeArguments.map { normalize(it, aliasStack) },
            javaName = type.javaName
        )
        is JavaConstructorType -> JavaConstructorType(
            owner = type.owner,
            signature = normalize(type.signature, aliasStack) as FunctionType,
            signatureMetadata = type.signatureMetadata,
            visibility = type.visibility
        )
        is JavaStaticMemberType -> JavaStaticMemberType(
            owner = type.owner,
            memberName = type.memberName,
            valueType = normalize(type.valueType, aliasStack),
            memberKind = type.memberKind,
            visibility = type.visibility,
            signatureMetadata = type.signatureMetadata
        )
        is JavaInstanceMemberType -> JavaInstanceMemberType(
            owner = type.owner,
            memberName = type.memberName,
            valueType = normalize(type.valueType, aliasStack),
            memberKind = type.memberKind,
            visibility = type.visibility,
            signatureMetadata = type.signatureMetadata
        )
        is JavaOverloadType -> JavaOverloadType(
            javaName = type.javaName,
            overloadName = type.overloadName,
            callSignatures = type.callSignatures.map { normalize(it, aliasStack) as FunctionType },
            signatureMetadata = type.signatureMetadata
        )
        is JavaArrayType -> JavaArrayType(
            elementType = normalize(type.elementType, aliasStack),
            dimensions = type.dimensions
        )
        is JavaPrimitiveType -> type
        is AppliedType -> AppliedType(type.baseName, type.typeArguments.map { normalize(it, aliasStack) })
        is TypeParameterType -> normalizeTypeParameter(type, aliasStack)
        is CustomType -> type
        UnknownType, ErrorType, NeverType -> type
    }

    /**
     * Collapses a normalized overload set, keeping the first-declared survivor of each
     * equivalence group: exact structural duplicates are dropped, and a later signature is
     * dropped when an earlier KEPT signature already serves every call it can accept
     * (equal return type, and the later arguments all flow into the earlier parameters).
     */
    private fun dedupeOverloadSignatures(signatures: List<FunctionType>): List<FunctionType> {
        val kept = mutableListOf<FunctionType>()
        for (signature in signatures) {
            val isDuplicated = signature in kept || kept.any { earlier ->
                earlier.returnType == signature.returnType &&
                    subsumesOverload(earlier.parameters, signature.parameters)
            }
            if (!isDuplicated) {
                kept += signature
            }
        }
        return kept
    }

    /**
     * STRICT subsumption for overload deletion — deliberately NOT the call-site-relaxed
     * [TypeRelations.parameterListsCompatible], whose vararg/bivariance relaxations are
     * valid for "can this handler be assigned" but unsound for "can this overload be
     * deleted": dropping a vararg sibling makes variadic calls NO_MATCHING_SIGNATURE, and
     * bivariant class params would delete overloads that later calls still match.
     */
    private fun subsumesOverload(earlier: List<FunctionParameter>, later: List<FunctionParameter>): Boolean {
        if (earlier.size != later.size) {
            return false
        }
        return earlier.zip(later).all { (earlierParameter, laterParameter) ->
            TypeRelations.isAssignableForOverloadSubsumption(earlierParameter.type, laterParameter.type)
        }
    }

    private fun normalizeJavaClass(type: JavaClassType, aliasStack: MutableList<AliasType>): JavaClassType {
        return JavaClassType(
            javaName = type.javaName,
            constructors = JavaOverloadSet(
                type.constructors.overloads.map { normalize(it, aliasStack) as JavaConstructorType }
            ),
            staticMembers = type.staticMembers.mapValues { (_, member) -> normalize(member, aliasStack) as JavaStaticMemberType },
            instanceMembers = type.instanceMembers.mapValues { (_, member) -> normalize(member, aliasStack) as JavaInstanceMemberType },
            innerClasses = type.innerClasses.mapValues { (_, innerClass) -> normalize(innerClass, aliasStack) as JavaClassType },
            superClass = type.superClass?.let { normalize(it, aliasStack) as JavaClassType },
            interfaces = type.interfaces.map { normalize(it, aliasStack) as JavaClassType },
            typeParameters = type.typeParameters.map { normalizeTypeParameter(it, aliasStack) }
        )
    }

    private fun normalizeAlias(type: AliasType, aliasStack: MutableList<AliasType>): Type {
        val unwrapped = type.unwrapAliases()
        if (unwrapped !is AliasType || unwrapped !== type) {
            return normalize(unwrapped, aliasStack)
        }

        if (aliasStack.any { it === type }) {
            return type
        }

        aliasStack += type
        val normalizedTarget = normalize(type.target, aliasStack)
        aliasStack.removeAt(aliasStack.lastIndex)
        return if (normalizedTarget === type) type else normalizedTarget
    }

    private fun normalizeAliasPreservingNode(type: AliasType, aliasStack: MutableList<AliasType>): AliasType {
        if (aliasStack.any { it === type }) {
            return type
        }

        aliasStack += type
        val normalizedTarget = normalize(type.target, aliasStack)
        aliasStack.removeAt(aliasStack.lastIndex)
        return AliasType(type.name, normalizedTarget)
    }

    private fun normalizeTypeParameter(type: TypeParameterType, aliasStack: MutableList<AliasType>): TypeParameterType =
        TypeParameterType(
            name = type.name,
            constraint = type.constraint?.let { normalize(it, aliasStack) },
            defaultType = type.defaultType?.let { normalize(it, aliasStack) }
        )

    private fun normalizeUnion(types: Iterable<Type>): Type {
        val members = linkedSetOf<Type>()
        types.forEach { type ->
            when (type) {
                is UnionType -> members.addAll(type.types)
                else -> members += type
            }
        }

        val simplifiedMembers = linkedSetOf<Type>()
        members.forEach { member ->
            when (member) {
                NeverType -> Unit
                else -> simplifiedMembers += member
            }
        }

        // A doc-declared Java member (no reflection metadata) and its reflected twin encode
        // the SAME member; plain data-class equality would keep both in the union and
        // duplicate completion / hover entries (see dedupeSignatureMetadataDuplicates).
        val metadataDedupedMembers = dedupeSignatureMetadataDuplicates(simplifiedMembers)

        if (metadataDedupedMembers.any { it == PrimitiveType.ANY }) return PrimitiveType.ANY
        if (metadataDedupedMembers.any { it == UnknownType }) return UnknownType

        val primitiveKinds = metadataDedupedMembers
            .filterIsInstance<PrimitiveType>()
            .map { it.kind }
            .toSet()

        val dedupedMembers = linkedSetOf<Type>()
        metadataDedupedMembers.forEach { member ->
            if (member is LiteralType && member.baseType.kind in primitiveKinds) {
                return@forEach
            }
            dedupedMembers += member
        }

        return when (dedupedMembers.size) {
            0 -> NeverType
            1 -> dedupedMembers.single()
            else -> UnionType(dedupedMembers)
        }
    }

    /**
     * Collapses Java members that differ ONLY in [JavaMemberType.signatureMetadata].
     *
     * A doc-declared member (empty metadata) and its reflected twin (varargs / generic
     * metadata from the JVM index) are the same member; data-class equality would keep
     * both in a union built via [unionTypeOf], duplicating completion and hover entries.
     * Members are therefore keyed on every field EXCEPT signatureMetadata, and of each
     * duplicate pair the metadata carrier is kept (it renders richer signature help);
     * when both are bare the first occurrence wins so insertion order stays stable.
     */
    private fun dedupeSignatureMetadataDuplicates(members: Set<Type>): Set<Type> {
        if (members.none { it is JavaMemberType }) {
            return members
        }

        val deduped = linkedSetOf<Type>()
        val carrierByKey = mutableMapOf<JavaMemberType, JavaMemberType>()
        members.forEach { member ->
            val javaMember = member as? JavaMemberType
            if (javaMember == null) {
                deduped += member
                return@forEach
            }
            val key = javaMember.withoutSignatureMetadata()
            val existing = carrierByKey[key]
            when {
                existing == null -> {
                    deduped += javaMember
                    carrierByKey[key] = javaMember
                }
                existing.javaMemberMetadata().isEmpty() && javaMember.javaMemberMetadata().isNotEmpty() -> {
                    deduped.remove(existing)
                    deduped += javaMember
                    carrierByKey[key] = javaMember
                }
                // else: earlier member already carries the metadata (or both are bare).
                else -> Unit
            }
        }
        return deduped
    }

    private fun JavaMemberType.javaMemberMetadata(): List<JavaSignatureMetadata> = when (this) {
        is JavaStaticMemberType -> signatureMetadata
        is JavaInstanceMemberType -> signatureMetadata
        else -> emptyList()
    }

    private fun JavaMemberType.withoutSignatureMetadata(): JavaMemberType = when (this) {
        is JavaStaticMemberType -> copy(signatureMetadata = emptyList())
        is JavaInstanceMemberType -> copy(signatureMetadata = emptyList())
    }

    private fun normalizeIntersection(types: Iterable<Type>): Type {
        val members = linkedSetOf<Type>()
        types.forEach { type ->
            when (type) {
                is IntersectionType -> members.addAll(type.types)
                else -> members += type
            }
        }

        if (members.any { it == NeverType }) return NeverType

        val filtered = linkedSetOf<Type>()
        val hasConcreteMembers = members.any {
            it != UnknownType && it != ErrorType && it != PrimitiveType.ANY
        }
        members.forEach { member ->
            when {
                member == PrimitiveType.ANY -> Unit
                (member == UnknownType || member == ErrorType) && hasConcreteMembers -> Unit
                else -> filtered += member
            }
        }

        val narrowed = filtered.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in narrowed.indices) {
                val left = narrowed[i]
                for (j in narrowed.indices) {
                    if (i == j) continue
                    val right = narrowed[j]

                    if (isIncompatiblePrimitiveOrLiteral(left, right)) {
                        return NeverType
                    }

                    val leftAcceptsRight = TypeRelations.isAssignable(left, right)
                    val rightAcceptsLeft = TypeRelations.isAssignable(right, left)
                    if (leftAcceptsRight && !rightAcceptsLeft) {
                        narrowed.removeAt(i)
                        changed = true
                        break@loop
                    }
                }
            }
        }

        val deduped = LinkedHashSet<Type>(narrowed)
        return when (deduped.size) {
            0 -> PrimitiveType.ANY
            1 -> deduped.single()
            else -> IntersectionType(deduped)
        }
    }

    private fun canonicalizePrimitive(type: PrimitiveType): Type = when (type.kind) {
        PrimitiveType.Kind.NIL -> PrimitiveType.NIL
        PrimitiveType.Kind.BOOLEAN -> PrimitiveType.BOOLEAN
        PrimitiveType.Kind.NUMBER -> PrimitiveType.NUMBER
        PrimitiveType.Kind.STRING -> PrimitiveType.STRING
        PrimitiveType.Kind.FUNCTION -> PrimitiveType.FUNCTION
        PrimitiveType.Kind.TABLE -> PrimitiveType.TABLE
        PrimitiveType.Kind.THREAD -> PrimitiveType.THREAD
        PrimitiveType.Kind.USERDATA -> PrimitiveType.USERDATA
        PrimitiveType.Kind.ANY -> PrimitiveType.ANY
        PrimitiveType.Kind.UNKNOWN -> UnknownType
        PrimitiveType.Kind.NEVER -> NeverType
        PrimitiveType.Kind.ERROR -> ErrorType
    }

    private fun canonicalizeLiteralBaseType(type: PrimitiveType): PrimitiveType = when (type.kind) {
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

    private fun isIncompatiblePrimitiveOrLiteral(left: Type, right: Type): Boolean {
        val leftPrimitive = primitiveKindOf(left) ?: return false
        val rightPrimitive = primitiveKindOf(right) ?: return false
        if (leftPrimitive != rightPrimitive) return true

        if (left is LiteralType && right is LiteralType) {
            return left.value != right.value
        }

        return false
    }

    private fun primitiveKindOf(type: Type): PrimitiveType.Kind? = when (type) {
        is PrimitiveType -> type.kind
        is LiteralType -> type.baseType.kind
        else -> null
    }
}
