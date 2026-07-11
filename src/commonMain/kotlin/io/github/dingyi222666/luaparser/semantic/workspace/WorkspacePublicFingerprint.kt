package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
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
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType

data class WorkspacePublicFingerprint(
    val providedModuleNames: Set<String>,
    val value: String
) {
    companion object {
        fun from(
            documentFacts: DocumentFacts?,
            moduleExportSurface: ModuleExportSurface?
        ): WorkspacePublicFingerprint {
            val providedModuleNames = providedModuleNamesForFacts(documentFacts)
                .toList()
                .sorted()
                .toCollection(linkedSetOf())
            val payload = buildString {
                append("providers=")
                append(providedModuleNames.joinToString("|"))
                append('\n')
                append("surface=")
                append(moduleExportSurface?.let(::serializeSurface).orEmpty())
            }
            return WorkspacePublicFingerprint(
                providedModuleNames = providedModuleNames,
                value = workspaceFingerprintHash(payload)
            )
        }

        private fun serializeSurface(surface: ModuleExportSurface): String = buildString {
            append(surface.sourceForm.name)
            append('#')
            append(surface.hasSeeAllFallback)
            append('#')
            append(surface.moduleEnvironmentMode?.name.orEmpty())
            append('#')
            append(serializeModuleType(surface.moduleType, linkedSetOf()))
        }

        private fun serializeModuleType(moduleType: ModuleType, javaClassStack: MutableSet<String>): String = buildString {
            append("name=")
            append(moduleType.moduleName)
            append(';')
            append("fields=")
            append(sortedEntries(moduleType.fields).joinToString("|") { (name, type) ->
                "$name:${serializeType(type, javaClassStack)}"
            })
            append(";methods=")
            append(sortedEntries(moduleType.methods).joinToString("|") { (name, type) ->
                "$name:${serializeType(type, javaClassStack)}"
            })
            append(";index=")
            append(moduleType.indexSignature?.let {
                "${serializeType(it.keyType, javaClassStack)}->${serializeType(it.valueType, javaClassStack)}"
            }.orEmpty())
        }

        private fun serializeTypeParameters(
            typeParameters: List<TypeParameterType>,
            javaClassStack: MutableSet<String>
        ): String = typeParameters.joinToString("|") { serializeType(it, javaClassStack) }

        private fun serializeType(type: Type): String = serializeType(type, linkedSetOf())

        private fun serializeType(type: Type, javaClassStack: MutableSet<String>): String = when (type) {
            is ModuleType -> "module(${serializeModuleType(type, javaClassStack)})"
            is TableType -> buildString {
                append("table(fields=")
                append(sortedEntries(type.fields).joinToString("|") { (name, nested) ->
                    "$name:${serializeType(nested, javaClassStack)}"
                })
                append(";methods=")
                append(sortedEntries(type.methods).joinToString("|") { (name, nested) ->
                    "$name:${serializeType(nested, javaClassStack)}"
                })
                append(";index=")
                append(type.indexSignature?.let {
                    "${serializeType(it.keyType, javaClassStack)}->${serializeType(it.valueType, javaClassStack)}"
                }.orEmpty())
                append(')')
            }

            is LiteralType -> "literal(${type.baseType.name}:${type.value})"
            is FunctionType -> buildString {
                append("fun(typeParameters=")
                append(serializeTypeParameters(type.typeParameters, javaClassStack))
                append(";parameters=")
                append(type.parameters.joinToString("|") { parameter ->
                    buildString {
                        append(parameter.name)
                        append(':')
                        append(serializeType(parameter.type, javaClassStack))
                        append(':')
                        append(parameter.optional)
                        append(':')
                        append(parameter.vararg)
                    }
                })
                append(")->")
                append(serializeType(type.returnType, javaClassStack))
            }

            is OverloadedFunctionType -> "overload(${type.callSignatures.joinToString("|") { serializeType(it, javaClassStack) }})"
            is JavaClassType -> serializeJavaClassType(type, javaClassStack)
            is JavaInstanceType -> buildString {
                append("javaInstance(name=")
                append(serializeJavaTypeName(type.javaName))
                append(";class=")
                append(serializeJavaClassType(type.classType, javaClassStack))
                append(";typeArguments=")
                append(type.typeArguments.joinToString("|") { serializeType(it, javaClassStack) })
                append(')')
            }
            is JavaConstructorType -> buildString {
                append("javaConstructor(owner=")
                append(serializeJavaTypeName(type.owner))
                append(";visibility=")
                append(type.visibility.name)
                append(";signature=")
                append(serializeType(type.signature, javaClassStack))
                append(')')
            }
            is JavaStaticMemberType -> buildString {
                append("javaStaticMember(owner=")
                append(serializeJavaTypeName(type.owner))
                append(";name=")
                append(type.memberName)
                append(";kind=")
                append(type.memberKind.name)
                append(";visibility=")
                append(type.visibility.name)
                append(";type=")
                append(serializeType(type.valueType, javaClassStack))
                append(')')
            }
            is JavaInstanceMemberType -> buildString {
                append("javaInstanceMember(owner=")
                append(serializeJavaTypeName(type.owner))
                append(";name=")
                append(type.memberName)
                append(";kind=")
                append(type.memberKind.name)
                append(";visibility=")
                append(type.visibility.name)
                append(";type=")
                append(serializeType(type.valueType, javaClassStack))
                append(')')
            }
            is JavaOverloadType -> buildString {
                append("javaOverload(name=")
                append(serializeJavaTypeName(type.javaName))
                append(";overloadName=")
                append(type.overloadName)
                append(";signatures=")
                append(type.callSignatures.joinToString("|") { serializeType(it, javaClassStack) })
                append(')')
            }
            is JavaPrimitiveType -> "javaPrimitive(${type.kind.name}:${type.name})"
            is JavaArrayType -> "javaArray(${type.dimensions}:${serializeType(type.elementType, javaClassStack)})"
            is CallableType -> "callable(${type.callSignatures.joinToString("|") { serializeType(it, javaClassStack) }})"
            is UnionType -> "union(${type.types.map { serializeType(it, javaClassStack) }.sorted().joinToString("|")})"
            is IntersectionType -> "intersection(${type.types.map { serializeType(it, javaClassStack) }.sorted().joinToString("|")})"
            is TupleType -> "tuple(${type.elementTypes.joinToString("|") { serializeType(it, javaClassStack) }})"
            is MultiReturnType -> "multi(${type.types.joinToString("|") { serializeType(it, javaClassStack) }})"
            is VarargType -> "vararg(${serializeType(type.elementType, javaClassStack)})"
            is ArrayType -> "array(${serializeType(type.elementType, javaClassStack)})"
            is ClassType -> buildString {
                append("class(")
                append(type.name)
                append(";superClass=")
                append(type.superClass?.let { serializeType(it, javaClassStack) }.orEmpty())
                append(";superType=")
                append(type.superType?.let { serializeType(it, javaClassStack) }.orEmpty())
                append(";typeParameters=")
                append(serializeTypeParameters(type.typeParameters, javaClassStack))
                append(";alias=")
                append(type.alias?.let { serializeType(it, javaClassStack) }.orEmpty())
                append(";fields=")
                append(sortedEntries(type.fields).joinToString("|") { (name, nested) ->
                    "$name:${serializeType(nested, javaClassStack)}"
                })
                append(";methods=")
                append(sortedEntries(type.methods).joinToString("|") { (name, nested) ->
                    "$name:${serializeType(nested, javaClassStack)}"
                })
                append(')')
            }

            is AliasType -> "alias(${type.name}:${serializeType(type.target, javaClassStack)})"
            is TypeParameterType -> "typeparam(${type.name}:${type.constraint?.let { serializeType(it, javaClassStack) }.orEmpty()}:${type.defaultType?.let { serializeType(it, javaClassStack) }.orEmpty()})"
            is AppliedType -> "applied(${type.baseName}:${type.typeArguments.joinToString("|") { serializeType(it, javaClassStack) }})"
            is PrimitiveType -> "primitive(${type.kind.name}:${type.name})"
            is CustomType -> "custom(${type.name})"
            UnknownType -> "unknown"
            ErrorType -> "error"
            NeverType -> "never"
        }

        private fun serializeJavaClassType(type: JavaClassType, javaClassStack: MutableSet<String>): String {
            val stackKey = type.javaName.binaryName
            if (!javaClassStack.add(stackKey)) {
                return "javaClassRef(${serializeJavaTypeName(type.javaName)})"
            }

            return try {
                buildString {
                    append("javaClass(name=")
                    append(serializeJavaTypeName(type.javaName))
                    append(";constructors=")
                    append(type.constructors.overloads.joinToString("|") { serializeType(it, javaClassStack) })
                    append(";staticMembers=")
                    append(sortedEntries(type.staticMembers).joinToString("|") { (name, member) ->
                        "$name:${serializeType(member, javaClassStack)}"
                    })
                    append(";instanceMembers=")
                    append(sortedEntries(type.instanceMembers).joinToString("|") { (name, member) ->
                        "$name:${serializeType(member, javaClassStack)}"
                    })
                    append(";innerClasses=")
                    append(sortedEntries(type.innerClasses).joinToString("|") { (name, innerClass) ->
                        "$name:${serializeJavaClassType(innerClass, javaClassStack)}"
                    })
                    append(";superClass=")
                    append(type.superClass?.let { serializeJavaClassType(it, javaClassStack) }.orEmpty())
                    append(";interfaces=")
                    append(type.interfaces.map { serializeJavaClassType(it, javaClassStack) }.sorted().joinToString("|"))
                    append(";typeParameters=")
                    append(serializeTypeParameters(type.typeParameters, javaClassStack))
                    append(')')
                }
            } finally {
                javaClassStack.remove(stackKey)
            }
        }

        private fun serializeJavaTypeName(name: JavaTypeName): String = "${name.binaryName}:${name.canonicalName}"
    }
}

private fun <T> sortedEntries(map: Map<String, T>): List<Map.Entry<String, T>> =
    map.entries.sortedBy { it.key }

internal fun providedModuleNamesForFacts(documentFacts: DocumentFacts?): Set<String> {
    if (documentFacts == null) {
        return emptySet()
    }

    val explicitTopLevelProviders = documentFacts.legacyModuleCalls
        .asSequence()
        .filter { it.isTopLevel }
        .map { it.moduleName }
    val pathProviders = documentFacts.moduleNameCandidates
        .asSequence()
        .filter { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }
        .map { it.moduleName }

    return (explicitTopLevelProviders + pathProviders).toCollection(linkedSetOf())
}

internal fun workspaceFingerprintHash(text: String): String {
    var hash = -3750763034362895579L
    val prime = 1099511628211L
    for (char in text) {
        hash = hash xor char.code.toLong()
        hash *= prime
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
