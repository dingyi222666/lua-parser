package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaConstructorType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadSet
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaPrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaSignatureMetadata
import io.github.dingyi222666.luaparser.semantic.types.model.JavaStaticMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType

class TypeSubstitutor {

    fun substitute(type: Type, mapping: Map<String, Type>): Type {
        return substituteRecursive(type, mapping, maskOwnTypeParameters = false)
    }

    internal fun substitute(type: Type, mapping: Map<String, Type>, preserveOwnTypeParameters: Boolean): Type {
        return substituteRecursive(type, mapping, maskOwnTypeParameters = preserveOwnTypeParameters)
    }

    private fun substituteRecursive(type: Type, mapping: Map<String, Type>, maskOwnTypeParameters: Boolean): Type {
        if (mapping.isEmpty()) {
            return type
        }

        return when (type) {
            is TypeParameterType -> mapping[type.name]
                ?: TypeParameterType(
                    name = type.name,
                    constraint = type.constraint?.let { substituteRecursive(it, mapping, maskOwnTypeParameters = true) },
                    defaultType = type.defaultType?.let { substituteRecursive(it, mapping, maskOwnTypeParameters = true) }
                )

            is CustomType -> mapping[type.name] ?: type

            is FunctionType -> {
                val scopedMapping = mapping.maskedBy(type.typeParameters, maskOwnTypeParameters)
                FunctionType(
                    parameters = type.parameters.map { parameter ->
                        FunctionParameter(
                            name = parameter.name,
                            type = substituteRecursive(parameter.type, scopedMapping, maskOwnTypeParameters = true),
                            optional = parameter.optional,
                            vararg = parameter.vararg
                        )
                    },
                    returnType = substituteRecursive(type.returnType, scopedMapping, maskOwnTypeParameters = true),
                    typeParameters = substituteTypeParameters(type.typeParameters, scopedMapping)
                )
            }

            is OverloadedFunctionType -> OverloadedFunctionType(
                callSignatures = type.callSignatures.map {
                    substituteRecursive(it, mapping, maskOwnTypeParameters = maskOwnTypeParameters) as FunctionType
                }
            )

            is TableType -> TableType(
                fields = type.fields.mapValues { (_, value) -> substituteRecursive(value, mapping, maskOwnTypeParameters = true) },
                methods = type.methods.mapValues { (_, value) -> substituteRecursive(value, mapping, maskOwnTypeParameters = true) },
                indexSignature = type.indexSignature?.let {
                    TableType.IndexSignature(
                        keyType = substituteRecursive(it.keyType, mapping, maskOwnTypeParameters = true),
                        valueType = substituteRecursive(it.valueType, mapping, maskOwnTypeParameters = true)
                    )
                }
            )

            is ModuleType -> ModuleType(
                moduleName = type.moduleName,
                fields = type.fields.mapValues { (_, value) -> substituteRecursive(value, mapping, maskOwnTypeParameters = true) },
                methods = type.methods.mapValues { (_, value) -> substituteRecursive(value, mapping, maskOwnTypeParameters = true) },
                indexSignature = type.indexSignature?.let {
                    ModuleType.IndexSignature(
                        keyType = substituteRecursive(it.keyType, mapping, maskOwnTypeParameters = true),
                        valueType = substituteRecursive(it.valueType, mapping, maskOwnTypeParameters = true)
                    )
                }
            )

            is ClassType -> {
                val scopedMapping = mapping.maskedBy(type.typeParameters, maskOwnTypeParameters)
                ClassType(
                    name = type.name,
                    fields = type.fields.mapValues { (_, value) -> substituteRecursive(value, scopedMapping, maskOwnTypeParameters = true) },
                    methods = type.methods.mapValues { (_, value) -> substituteRecursive(value, scopedMapping, maskOwnTypeParameters = true) },
                    superClass = type.superClass?.let { substituteRecursive(it, scopedMapping, maskOwnTypeParameters = false) as ClassType },
                    superType = type.superType?.let { substituteRecursive(it, scopedMapping, maskOwnTypeParameters = true) },
                    typeParameters = substituteTypeParameters(type.typeParameters, scopedMapping),
                    alias = type.alias?.let { substituteRecursive(it, scopedMapping, maskOwnTypeParameters = true) as AliasType },
                    javaClassName = type.javaClassName,
                    declarationId = type.declarationId
                )
            }

            is JavaClassType -> {
                val scopedMapping = mapping.maskedBy(type.typeParameters, maskOwnTypeParameters)
                JavaClassType(
                    javaName = type.javaName,
                    constructors = JavaOverloadSet(
                        type.constructors.overloads.map {
                            substituteRecursive(it, scopedMapping, maskOwnTypeParameters = true) as JavaConstructorType
                        }
                    ),
                    staticMembers = type.staticMembers.mapValues { (_, member) ->
                        substituteRecursive(member, scopedMapping, maskOwnTypeParameters = true) as JavaStaticMemberType
                    },
                    instanceMembers = type.instanceMembers.mapValues { (_, member) ->
                        substituteRecursive(member, scopedMapping, maskOwnTypeParameters = true) as JavaInstanceMemberType
                    },
                    innerClasses = type.innerClasses.mapValues { (_, innerClass) ->
                        substituteRecursive(innerClass, scopedMapping, maskOwnTypeParameters = false) as JavaClassType
                    },
                    superClass = type.superClass?.let {
                        substituteRecursive(it, scopedMapping, maskOwnTypeParameters = false) as JavaClassType
                    },
                    interfaces = type.interfaces.map {
                        substituteRecursive(it, scopedMapping, maskOwnTypeParameters = false) as JavaClassType
                    },
                    typeParameters = substituteTypeParameters(type.typeParameters, scopedMapping)
                )
            }

            is JavaInstanceType -> JavaInstanceType(
                classType = substituteRecursive(type.classType, mapping, maskOwnTypeParameters = false) as JavaClassType,
                typeArguments = type.typeArguments.map { substituteRecursive(it, mapping, maskOwnTypeParameters = true) },
                javaName = type.javaName
            )

            is JavaConstructorType -> JavaConstructorType(
                owner = type.owner,
                signature = substituteRecursive(type.signature, mapping, maskOwnTypeParameters = true) as FunctionType,
                signatureMetadata = substituteSignatureMetadata(type.signatureMetadata, mapping),
                visibility = type.visibility
            )

            is JavaStaticMemberType -> JavaStaticMemberType(
                owner = type.owner,
                memberName = type.memberName,
                valueType = substituteRecursive(type.valueType, mapping, maskOwnTypeParameters = true),
                memberKind = type.memberKind,
                visibility = type.visibility,
                signatureMetadata = type.signatureMetadata.map { substituteSignatureMetadata(it, mapping) }
            )

            is JavaInstanceMemberType -> JavaInstanceMemberType(
                owner = type.owner,
                memberName = type.memberName,
                valueType = substituteRecursive(type.valueType, mapping, maskOwnTypeParameters = true),
                memberKind = type.memberKind,
                visibility = type.visibility,
                signatureMetadata = type.signatureMetadata.map { substituteSignatureMetadata(it, mapping) }
            )

            is JavaOverloadType -> JavaOverloadType(
                javaName = type.javaName,
                overloadName = type.overloadName,
                callSignatures = type.callSignatures.map {
                    substituteRecursive(it, mapping, maskOwnTypeParameters = true) as FunctionType
                },
                signatureMetadata = type.signatureMetadata.map { substituteSignatureMetadata(it, mapping) }
            )

            is JavaArrayType -> JavaArrayType(
                elementType = substituteRecursive(type.elementType, mapping, maskOwnTypeParameters = true),
                dimensions = type.dimensions
            )

            is JavaPrimitiveType -> type

            is UnionType -> UnionType(type.types.mapTo(linkedSetOf()) { substituteRecursive(it, mapping, maskOwnTypeParameters = true) })
            is IntersectionType -> IntersectionType(type.types.mapTo(linkedSetOf()) { substituteRecursive(it, mapping, maskOwnTypeParameters = true) })
            is ArrayType -> ArrayType(substituteRecursive(type.elementType, mapping, maskOwnTypeParameters = true))
            is TupleType -> TupleType(type.elementTypes.map { substituteRecursive(it, mapping, maskOwnTypeParameters = true) })
            is MultiReturnType -> MultiReturnType(type.types.map { substituteRecursive(it, mapping, maskOwnTypeParameters = true) })
            is VarargType -> VarargType(substituteRecursive(type.elementType, mapping, maskOwnTypeParameters = true))
            is AliasType -> AliasType(type.name, substituteRecursive(type.target, mapping, maskOwnTypeParameters = true))
            is AppliedType -> AppliedType(type.baseName, type.typeArguments.map { substituteRecursive(it, mapping, maskOwnTypeParameters = true) })
            else -> type
        }
    }

    fun substituteApplied(type: AppliedType, lexicalScopeId: ScopeId, binder: BinderPassResult): Type? {
        return substituteApplied(type, TypeResolutionContext.forLexicalScope(lexicalScopeId, binder), binder)
    }

    internal fun substituteApplied(type: AppliedType, context: TypeResolutionContext, binder: BinderPassResult): Type? {
        val declaration = resolveAppliedTargetDeclaration(type.baseName, context)
            ?: return null
        if (declaration.kind != DeclarationKind.CLASS && declaration.kind != DeclarationKind.TYPE_ALIAS) {
            return null
        }

        val declaredType = binder.declarationIndex.getDeclaration(declaration.id)?.declaredType
            ?: declaration.declaredType
            ?: return null
        val ownedTypeParameters = binder.declarationIndex.getOwnedDeclarations(DeclarationOwner.Declaration(declaration.id))
            .filter { it.kind == DeclarationKind.TYPE_PARAMETER }
            .mapNotNull { owned ->
                binder.declarationIndex.getDeclaration(owned.id)?.declaredType as? TypeParameterType
                    ?: owned.declaredType as? TypeParameterType
            }
        val typeParameters = if (ownedTypeParameters.isNotEmpty()) {
            ownedTypeParameters
        } else {
            candidateTypeParameterNames(declaration.name).map(::TypeParameterType)
        }
        if (typeParameters.size != type.typeArguments.size) {
            return null
        }

        val mapping = typeParameters.zip(type.typeArguments).associate { (parameter, argument) ->
            parameter.name to argument
        }
        // Alias targets substitute through the aliased type, not the alias node itself.
        val substitutionTarget = (declaredType as? AliasType)?.target ?: declaredType
        return dropMappedTypeParameters(substitute(substitutionTarget, mapping), mapping.keys, dropClassParameters = true)
    }

    /**
     * Applying concrete arguments (or renaming an alias's own parameters, its other caller
     * in TypeResolver.renameBareAliasTypeParameters) replaces the applied target's own type
     * parameters, so any type-parameter declarations still riding the substituted surface
     * under those names are stale phantoms: `Box<number>` materialized `class Box<T>` with
     * `value: number` but kept the `class Box<T>` marker (hover rendered the phantom `<T>`;
     * an applied `Mapper<number>` alias rendered `fun<T>(value: number): number`). Drop the
     * mapped parameters — the concrete arguments already carry the type information.
     * Parameters whose names were NOT mapped are kept: they are genuinely unbound.
     *
     * [dropClassParameters] covers the applied-type caller only: materialized `class Box<T>`
     * surfaces keep their phantom marker until the concrete arguments replaced them. The
     * alias-rename caller deliberately leaves ClassType parameters untouched (aliases whose
     * target is a class keep the class's own slots).
     */
    internal fun dropMappedTypeParameters(
        type: Type,
        mapping: Set<String>,
        dropClassParameters: Boolean
    ): Type {
        if (mapping.isEmpty()) {
            return type
        }
        return when (type) {
            is ClassType -> {
                if (!dropClassParameters) return type
                val typeParameters = type.typeParameters.filterNot { parameter -> parameter.name in mapping }
                if (typeParameters.size == type.typeParameters.size) {
                    type
                } else {
                    type.copy(typeParameters = typeParameters)
                }
            }
            is FunctionType -> {
                val typeParameters = type.typeParameters.filterNot { parameter -> parameter.name in mapping }
                if (typeParameters.size == type.typeParameters.size) {
                    type
                } else {
                    // FunctionType.copy keeps the stale fun<T>(...) name, so rebuild to refresh it.
                    FunctionType(
                        parameters = type.parameters,
                        returnType = type.returnType,
                        typeParameters = typeParameters
                    )
                }
            }
            is OverloadedFunctionType -> OverloadedFunctionType(
                callSignatures = type.callSignatures.map {
                    dropMappedTypeParameters(it, mapping, dropClassParameters) as FunctionType
                }
            )
            else -> type
        }
    }

    /** Shared CLASS/TYPE_ALIAS applied-target lookup (also used by TypeResolver parent materialization). */
    internal fun resolveAppliedTargetDeclaration(baseName: String, context: TypeResolutionContext) =
        context.resolveNamedType(baseName)
            ?: context.visibleDeclarations()
                .firstOrNull { candidate ->
                    (candidate.kind == DeclarationKind.CLASS || candidate.kind == DeclarationKind.TYPE_ALIAS) &&
                        candidateBaseName(candidate.name) == baseName
                }

    private fun candidateBaseName(name: String): String = name.substringBefore('<').trim()

    private fun candidateTypeParameterNames(name: String): List<String> {
        val start = name.indexOf('<')
        val end = name.lastIndexOf('>')
        if (start == -1 || end <= start) {
            return emptyList()
        }
        return name.substring(start + 1, end)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun substituteSignatureMetadata(
        metadata: JavaSignatureMetadata,
        mapping: Map<String, Type>
    ): JavaSignatureMetadata {
        if (mapping.isEmpty()) {
            return metadata
        }
        val scopedMapping = mapping.maskedBy(metadata.typeParameters, maskOwnTypeParameters = true)
        if (scopedMapping.isEmpty()) {
            return metadata
        }
        return metadata.copy(
            typeParameters = substituteTypeParameters(metadata.typeParameters, scopedMapping),
            genericReturnTypeName = metadata.genericReturnTypeName
                ?.takeUnless { returnName ->
                    scopedMapping.keys.any { typeParameterName ->
                        returnName.referencesTypeParameterName(typeParameterName)
                    }
                }
        )
    }

    private fun String.referencesTypeParameterName(name: String): Boolean {
        val escaped = Regex.escape(name)
        return Regex("(?<![A-Za-z0-9_${'$'}])$escaped(?![A-Za-z0-9_${'$'}])").containsMatchIn(this)
    }

    private fun Map<String, Type>.maskedBy(
        typeParameters: List<TypeParameterType>,
        maskOwnTypeParameters: Boolean
    ): Map<String, Type> {
        if (!maskOwnTypeParameters || typeParameters.isEmpty()) {
            return this
        }
        return this - typeParameters.map(TypeParameterType::name).toSet()
    }

    /** Shared constraint/defaultType substitution over declared type-parameter slots (FunctionType, ClassType, JavaClassType, signature metadata). */
    private fun substituteTypeParameters(
        typeParameters: List<TypeParameterType>,
        scopedMapping: Map<String, Type>
    ): List<TypeParameterType> = typeParameters.map { parameter ->
        TypeParameterType(
            name = parameter.name,
            constraint = parameter.constraint?.let {
                substituteRecursive(it, scopedMapping, maskOwnTypeParameters = true)
            },
            defaultType = parameter.defaultType?.let {
                substituteRecursive(it, scopedMapping, maskOwnTypeParameters = true)
            }
        )
    }
}
