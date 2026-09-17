package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
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
    val value: String,
    /**
     * Fingerprint of this document's provider-global symbol surface (see
     * [globalSymbolsFingerprint]). Empty until semantic analysis attached it; consumers read
     * provider globals through the module resolver, so a change here is a public-surface
     * change even when the module export surface ([value]) is untouched.
     */
    val globalSymbolsFingerprint: String = ""
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
                append(";javaClass=")
                append(type.javaClassName.orEmpty())
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

/** Upper bound on declarations hashed into [globalSymbolsFingerprint] (pathological-file guard). */
private const val GLOBAL_FINGERPRINT_DECLARATION_LIMIT = 512

/** Per-field truncation for [globalSymbolsFingerprint] lines (pathological-name guard). */
private const val GLOBAL_FINGERPRINT_FIELD_LIMIT = 256

/**
 * Fingerprint of a provider's global symbol surface as consumers see it through the workspace
 * module resolver: every distinct AST-originated global, rendered as `name:kind:<value type
 * display name>` in binder order, hashed with [workspaceFingerprintHash].
 *
 * [WorkspacePublicFingerprint.value] only covers the module export surface (returned members,
 * legacy environments, `---@` annotations), so editing a NON-exported global
 * (`shared = 42` -> `shared = 43`) used to change nothing a consumer's dirty check could see —
 * even though every consumer binding that global through the resolver now derives a different
 * type. This fingerprint is derived from the binder's declaration index, so it can only be
 * attached after semantic analysis (the workspace engine attaches it to the stored
 * [WorkspacePublicFingerprint] copy); the empty default marks "not yet analyzed".
 *
 * [typeEvaluator] resolves each global's value type off its anchor expression — the same
 * derivation the module resolver and the cycle re-analysis pass use. It is required in
 * practice: `declaredType` only carries annotation-derived type syntax, so un-annotated
 * assignments (`shared = 42`) have a null declaredType and would hash identically regardless
 * of their initializer. When omitted, the fingerprint degrades to annotation-declared types
 * only. Declaration count and field length are capped so adversarial documents cannot produce
 * unbounded fingerprint payloads; the caps only ever merge the tail of huge global surfaces,
 * never the head, so ordinary files hash deterministically.
 */
internal fun globalSymbolsFingerprint(
    declarations: List<BinderDeclaration>
): String {
    val payload = declarations.asSequence()
        .filter { it.kind == DeclarationKind.GLOBAL && it.origin == DeclarationOrigin.AST }
        .distinctBy { it.name }
        .take(GLOBAL_FINGERPRINT_DECLARATION_LIMIT)
        .joinToString(separator = "\n") { declaration ->
            buildString {
                append(declaration.name.take(GLOBAL_FINGERPRINT_FIELD_LIMIT))
                append(':')
                append(declaration.kind.name.take(GLOBAL_FINGERPRINT_FIELD_LIMIT))
                append(':')
                append(globalValueShape(declaration).take(GLOBAL_FINGERPRINT_FIELD_LIMIT))
            }
        }
    return workspaceFingerprintHash(payload)
}

/**
 * Value-type display name of one global as consumers see it: the evaluated anchor-expression
 * type when an evaluator is available and the anchor is an expression, otherwise the
 * annotation-declared type (empty for un-annotated non-expression globals).
 */
/**
 * Structural shape of the global's assigned VALUE expression (node kinds, identifiers,
 * literals; depth/length capped), read straight off the assignment AST - NO type
 * evaluation, which dominated per-update fingerprint cost. Distinguishes literal and
 * expression changes (`42` vs `'forty-two'`, `f(1)` vs `f(2)` by argument kinds).
 */
private fun globalValueShape(declaration: BinderDeclaration): String {
    val anchor = declaration.anchorNode ?: return declaration.declaredType?.displayName.orEmpty()
    val assignment = runCatching { anchor.parent }.getOrNull() as? AssignmentStatement
    if (assignment != null) {
        val index = assignment.init.indexOf(anchor)
        val rhs = assignment.variables.getOrNull(index)
        if (rhs != null) {
            return buildString { expressionShape(rhs, this, 0) }
        }
    }
    val functionBody = (anchor.parent as? FunctionDeclaration ?: run {
        val member = anchor.parent as? MemberExpression
        member?.parent as? FunctionDeclaration
    })
    if (functionBody != null) {
        return "function"
    }
    return declaration.declaredType?.displayName.orEmpty()
}

private fun expressionShape(node: ExpressionNode, sb: StringBuilder, depth: Int) {
    if (depth > 4 || sb.length > GLOBAL_FINGERPRINT_FIELD_LIMIT) {
        return
    }
    sb.append(node::class.simpleName).append('(')
    when (node) {
        is Identifier -> sb.append(node.name)
        is ConstantNode -> {
            sb.append(node.constantType.name).append(':')
            sb.append(node.stringOf()?.toString() ?: node.rawValue?.toString() ?: "?")
        }
        is MemberExpression -> {
            expressionShape(node.base, sb, depth + 1)
            sb.append('.').append(node.identifier.name)
        }
        is CallExpression -> {
            expressionShape(node.base, sb, depth + 1)
            node.arguments.forEach { arg ->
                sb.append(',')
                expressionShape(arg, sb, depth + 1)
            }
        }
        is StringCallExpression -> sb.append("str")
        is BinaryExpression -> {
            node.left?.let { expressionShape(it, sb, depth + 1) }
            sb.append(node.operator.name)
            node.right?.let { expressionShape(it, sb, depth + 1) }
        }
        is UnaryExpression -> {
            sb.append(node.operator.name)
            node.arg?.let { expressionShape(it, sb, depth + 1) }
        }
        is TableConstructorExpression -> sb.append("table")
        is FunctionDeclaration, is LambdaDeclaration -> sb.append("function")
        else -> Unit
    }
    sb.append(')')
}
