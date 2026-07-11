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
                    typeParameters = type.typeParameters.map { parameter ->
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
                )
            }

            is OverloadedFunctionType -> OverloadedFunctionType(
                callSignatures = type.callSignatures.map {
                    substituteRecursive(it, mapping, maskOwnTypeParameters = true) as FunctionType
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
                    typeParameters = type.typeParameters.map { parameter ->
                        TypeParameterType(
                            name = parameter.name,
                            constraint = parameter.constraint?.let {
                                substituteRecursive(it, scopedMapping, maskOwnTypeParameters = true)
                            },
                            defaultType = parameter.defaultType?.let {
                                substituteRecursive(it, scopedMapping, maskOwnTypeParameters = true)
                            }
                        )
                    },
                    alias = type.alias?.let { substituteRecursive(it, scopedMapping, maskOwnTypeParameters = true) as AliasType }
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
                    typeParameters = type.typeParameters.map { parameter ->
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
        return when (declaredType) {
            is AliasType -> substitute(declaredType.target, mapping)
            else -> substitute(declaredType, mapping)
        }
    }

    private fun resolveAppliedTargetDeclaration(baseName: String, context: TypeResolutionContext) =
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
            typeParameters = metadata.typeParameters.map { parameter ->
                TypeParameterType(
                    name = parameter.name,
                    constraint = parameter.constraint?.let {
                        substituteRecursive(it, scopedMapping, maskOwnTypeParameters = true)
                    },
                    defaultType = parameter.defaultType?.let {
                        substituteRecursive(it, scopedMapping, maskOwnTypeParameters = true)
                    }
                )
            },
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
}
