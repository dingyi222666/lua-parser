package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.semantic.types.model.*

data class WorkspacePublicFingerprint(
    val providedModuleNames: Set<String>,
    val value: String,
    /**
     * Fingerprint of this document's provider-global symbol surface (see
     * [globalSymbolsFingerprint]). Empty until semantic analysis attached it; consumers read
     * provider globals through the module resolver, so a change here is a public-surface
     * change even when the module export surface ([value]) is untouched.
     */
    val globalSymbolsFingerprint: String = "",
    /** Includes environment extensions (table.foo) as well as introduced globals. */
    val globalWriteNames: Set<String> = emptySet()
) {
    companion object {
        fun from(
            documentFacts: DocumentFacts?,
            moduleExportSurface: ModuleExportSurface?,
            publicTypeAnnotations: String = ""
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
                append('\n')
                append("typeAnnotations=")
                append(publicTypeAnnotations)
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

        /** Shared `fields=…;methods=…;index=…` payload segment (module and table serialization). */
        private fun serializeFieldMethodIndex(
            fields: Map<String, Type>,
            methods: Map<String, Type>,
            indexSignature: Pair<Type, Type>?,
            javaClassStack: MutableSet<String>
        ): String = buildString {
            append("fields=")
            append(serializeTypeMap(fields, javaClassStack))
            append(";methods=")
            append(serializeTypeMap(methods, javaClassStack))
            append(";index=")
            append(indexSignature?.let {
                "${serializeType(it.first, javaClassStack)}->${serializeType(it.second, javaClassStack)}"
            }.orEmpty())
        }

        private fun serializeTypeMap(
            entries: Map<String, Type>,
            javaClassStack: MutableSet<String>
        ): String = sortedEntries(entries).joinToString("|") { (name, type) ->
            "$name:${serializeType(type, javaClassStack)}"
        }

        private fun serializeModuleType(moduleType: ModuleType, javaClassStack: MutableSet<String>): String = buildString {
            append("name=")
            append(moduleType.moduleName)
            append(';')
            append(
                serializeFieldMethodIndex(
                    moduleType.fields,
                    moduleType.methods,
                    moduleType.indexSignature?.let { it.keyType to it.valueType },
                    javaClassStack
                )
            )
        }

        private fun serializeTypeParameters(
            typeParameters: List<TypeParameterType>,
            javaClassStack: MutableSet<String>
        ): String = typeParameters.joinToString("|") { serializeType(it, javaClassStack) }

        private fun serializeType(type: Type, javaClassStack: MutableSet<String>): String = when (type) {
            is ModuleType -> "module(${serializeModuleType(type, javaClassStack)})"
            is TableType -> buildString {
                append("table(")
                append(
                    serializeFieldMethodIndex(
                        type.fields,
                        type.methods,
                        type.indexSignature?.let { it.keyType to it.valueType },
                        javaClassStack
                    )
                )
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
            // JavaMemberType is sealed with exactly these two subtypes; they serialize
            // identically apart from the discriminator label.
            is JavaMemberType -> buildString {
                append(if (type is JavaStaticMemberType) "javaStaticMember(" else "javaInstanceMember(")
                append("owner=")
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
                append(";javaClass=")
                append(type.javaClassName.orEmpty())
                append(";fields=")
                append(serializeTypeMap(type.fields, javaClassStack))
                append(";methods=")
                append(serializeTypeMap(type.methods, javaClassStack))
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
                    append(serializeTypeMap(type.staticMembers, javaClassStack))
                    append(";instanceMembers=")
                    append(serializeTypeMap(type.instanceMembers, javaClassStack))
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

/** Complete global writes, including repeated assignments and full nested values/bodies. */
internal fun globalSymbolsFingerprint(
    writes: List<io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode>,
    source: SourceTokenFingerprint
): String = workspaceFingerprintHash(buildString {
    writes.forEach { write -> append(source.hash(write.range)).append(';') }
})
