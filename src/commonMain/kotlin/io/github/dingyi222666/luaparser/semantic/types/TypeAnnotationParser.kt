package io.github.dingyi222666.luaparser.semantic.types

import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.syntax.splitTopLevelTypeText
import io.github.dingyi222666.luaparser.semantic.types.syntax.toLegacySyntax

/**
 * Legacy type parsing facade kept for callers that still import `semantic.types.*`.
 *
 * The primary syntax and resolution APIs now live under `semantic.types.syntax.*` and
 * `semantic.types.resolve.*`. This class no longer carries its own recursive-descent parser:
 * it delegates to the single [TypeSyntaxParser] grammar and projects the result back onto the
 * legacy AST shape, so annotation-grammar fixes only ever have to be made in one place.
 */
class TypeAnnotationParser {
    private val classes = mutableMapOf<String, ClassDefinition>()
    private val aliases = mutableMapOf<String, TypeSyntax>()

    fun parse(input: String, context: TypeResolutionContext = TypeResolutionContext()): Type {
        return resolve(parseSyntax(input), context)
    }

    fun parseSyntax(input: String): TypeSyntax {
        return TypeSyntaxParser.parse(input).toLegacySyntax()
    }

    fun parseTypePrefix(input: String): ParsedTypePrefix {
        val prefix = TypeSyntaxParser.parsePrefix(input)
        return ParsedTypePrefix(prefix.syntax.toLegacySyntax(), prefix.remainder.trim())
    }

    fun splitTopLevel(input: String, delimiter: Char): List<String> =
        splitTopLevelTypeText(input, delimiter)

    fun resolve(syntax: TypeSyntax, context: TypeResolutionContext = TypeResolutionContext()): Type {
        return resolveInternal(syntax, context, mutableSetOf())
    }

    fun defineAlias(name: String, target: TypeSyntax) {
        aliases[name] = target
    }

    fun defineClass(
        name: String,
        fields: Map<String, Type> = emptyMap(),
        methods: Map<String, Type> = emptyMap(),
        parent: String? = null,
        declaredTypeParameters: List<String> = emptyList()
    ): ClassType {
        val existing = classes[name]
        val definition = ClassDefinition(
            name = name,
            fields = if (fields.isNotEmpty()) fields else existing?.fields ?: emptyMap(),
            methods = mergeMethods(existing?.methods ?: emptyMap(), methods),
            parentName = parent ?: existing?.parentName,
            declaredTypeParameters = if (declaredTypeParameters.isNotEmpty()) declaredTypeParameters else existing?.declaredTypeParameters
                ?: emptyList()
        )
        classes[name] = definition
        return buildClassType(definition)
    }

    fun getClass(name: String): ClassType? = classes[name]?.let(::buildClassType)

    fun hasClass(name: String): Boolean = classes.containsKey(name)

    fun hasAlias(name: String): Boolean = aliases.containsKey(name)

    private fun resolveInternal(
        syntax: TypeSyntax,
        context: TypeResolutionContext,
        resolvingAliases: MutableSet<String>
    ): Type {
        return when (syntax) {
            is NamedTypeSyntax -> resolveNamedType(syntax.identifier, context, resolvingAliases)
            is LiteralTypeSyntax -> resolveLiteralType(syntax)
            is UnionTypeSyntax -> UnionType(syntax.types.map { resolveInternal(it, context, resolvingAliases) }.toSet())
            is IntersectionTypeSyntax -> IntersectionType(syntax.types.map { resolveInternal(it, context, resolvingAliases) }.toSet())
            is ArrayTypeSyntax -> ArrayType(resolveInternal(syntax.elementType, context, resolvingAliases))
            is GenericTypeSyntax -> resolveGenericType(syntax, context, resolvingAliases)
            is NullableTypeSyntax -> resolveInternal(syntax.innerType, context, resolvingAliases).union(PrimitiveType.NIL)
            is TupleTypeSyntax -> TupleType(syntax.elementTypes.map { resolveInternal(it, context, resolvingAliases) })
            is MultiReturnTypeSyntax -> MultiReturnType(syntax.types.map { resolveInternal(it, context, resolvingAliases) })
            is VarargTypeSyntax -> VarArgType(listOf(resolveInternal(syntax.elementType, context, resolvingAliases)))
            is FunctionTypeSyntax -> resolveFunctionType(syntax, context, resolvingAliases)
            is ObjectTypeSyntax -> TableType(
                fields = syntax.fields.associate { field ->
                    field.name to resolveInternal(field.type, context, resolvingAliases)
                }
            )

            is IndexTypeSyntax -> TableType(
                fields = emptyMap(),
                indexSignature = TableType.IndexSignature(
                    keyType = resolveInternal(syntax.keyType, context, resolvingAliases),
                    valueType = resolveInternal(syntax.valueType, context, resolvingAliases)
                )
            )
        }
    }

    private fun resolveNamedType(
        name: String,
        context: TypeResolutionContext,
        resolvingAliases: MutableSet<String>
    ): Type {
        context.getTypeParameter(name)?.let { return it }

        return when (name) {
            "string" -> PrimitiveType.STRING
            "number", "integer" -> PrimitiveType.NUMBER
            "boolean", "bool" -> PrimitiveType.BOOLEAN
            "nil", "void" -> PrimitiveType.NIL
            "any" -> PrimitiveType.ANY
            "unknown" -> UnknownType
            "never" -> NeverType
            else -> {
                aliases[name]?.let { aliasSyntax ->
                    if (!resolvingAliases.add(name)) {
                        return AliasType(name, ErrorType)
                    }
                    val target = resolveInternal(aliasSyntax, context, resolvingAliases)
                    resolvingAliases.remove(name)
                    return AliasType(name, target)
                }

                classes[name]?.let { return buildClassType(it) }
                CustomType(name)
            }
        }
    }

    private fun resolveLiteralType(syntax: LiteralTypeSyntax): Type {
        return when (syntax.value) {
            "true" -> LiteralType(true, PrimitiveType.BOOLEAN)
            "false" -> LiteralType(false, PrimitiveType.BOOLEAN)
            "nil" -> PrimitiveType.NIL
            else -> {
                val numeric = syntax.value.toDoubleOrNull()
                if (numeric != null) {
                    LiteralType(syntax.value, PrimitiveType.NUMBER)
                } else {
                    val unquoted = syntax.value.removeSurrounding("\"").removeSurrounding("'")
                    LiteralType(unquoted, PrimitiveType.STRING)
                }
            }
        }
    }

    private fun resolveGenericType(
        syntax: GenericTypeSyntax,
        context: TypeResolutionContext,
        resolvingAliases: MutableSet<String>
    ): Type {
        if (syntax.baseName == "table" && syntax.arguments.size == 2) {
            return TableType(
                fields = emptyMap(),
                indexSignature = TableType.IndexSignature(
                    resolveInternal(syntax.arguments[0], context, resolvingAliases),
                    resolveInternal(syntax.arguments[1], context, resolvingAliases)
                )
            )
        }

        classes[syntax.baseName]?.let { definition ->
            val arguments = syntax.arguments.map { resolveInternal(it, context, resolvingAliases) }
            return buildClassType(definition, arguments)
        }

        return GenericType(
            baseName = syntax.baseName,
            typeParameters = syntax.arguments.map { resolveInternal(it, context, resolvingAliases) }
        )
    }

    private fun resolveFunctionType(
        syntax: FunctionTypeSyntax,
        context: TypeResolutionContext,
        resolvingAliases: MutableSet<String>
    ): Type {
        val functionContext = context.child()
        syntax.typeParameters.forEach { parameter ->
            val constraint = parameter.constraint?.let { resolveInternal(it, context, resolvingAliases) }
            functionContext.defineTypeParameter(parameter.name, constraint)
        }

        val parameters = syntax.parameters.map { parameter ->
            ParameterType(
                name = parameter.name ?: if (parameter.vararg) "..." else "arg",
                type = resolveInternal(parameter.type, functionContext, resolvingAliases),
                optional = parameter.optional,
                vararg = parameter.vararg
            )
        }

        val returnTypes = syntax.returnTypes.map { resolveInternal(it, functionContext, resolvingAliases) }
        val returnType = when (returnTypes.size) {
            0 -> PrimitiveType.NIL
            1 -> returnTypes[0]
            else -> MultiReturnType(returnTypes)
        }

        return FunctionType(parameters, returnType)
    }

    private fun buildClassType(definition: ClassDefinition, typeArguments: List<Type> = emptyList()): ClassType {
        val resolvedArguments = if (typeArguments.isEmpty() && definition.declaredTypeParameters.isNotEmpty()) {
            definition.declaredTypeParameters.map { TypeParameterType(it) }
        } else {
            typeArguments
        }

        val substitution = definition.declaredTypeParameters.zip(resolvedArguments).toMap()
        val parent = definition.parentName?.let { parentName ->
            classes[parentName]?.let { buildClassType(it) }
        }

        return ClassType(
            name = definition.name,
            fields = definition.fields.mapValues { (_, type) -> substituteType(type, substitution) },
            methods = definition.methods.mapValues { (_, type) -> substituteType(type, substitution) },
            parent = parent,
            typeParameters = resolvedArguments,
            declaredTypeParameters = definition.declaredTypeParameters
        )
    }

    private fun substituteType(type: Type, substitution: Map<String, Type>): Type {
        if (substitution.isEmpty()) {
            return type
        }

        return when (val normalized = type.unwrapAliases()) {
            is TypeParameterType -> substitution[normalized.name] ?: normalized
            is FunctionType -> FunctionType(
                parameters = normalized.parameters.map { parameter ->
                    parameter.copy(type = substituteType(parameter.type, substitution))
                },
                returnType = substituteType(normalized.returnType, substitution)
            )

            is OverloadedFunctionType -> OverloadedFunctionType(
                normalized.callSignatures.map { signature ->
                    substituteType(signature, substitution) as FunctionType
                }
            )

            is TableType -> TableType(
                fields = normalized.fields.mapValues { (_, memberType) -> substituteType(memberType, substitution) },
                methods = normalized.methods.mapValues { (_, memberType) -> substituteType(memberType, substitution) },
                indexSignature = normalized.indexSignature?.let { signature ->
                    TableType.IndexSignature(
                        substituteType(signature.keyType, substitution),
                        substituteType(signature.valueType, substitution)
                    )
                }
            )

            is UnionType -> UnionType(normalized.types.map { substituteType(it, substitution) }.toSet())
            is IntersectionType -> IntersectionType(normalized.types.map { substituteType(it, substitution) }.toSet())
            is ArrayType -> ArrayType(substituteType(normalized.elementType, substitution))
            is TupleType -> TupleType(normalized.elementTypes.map { substituteType(it, substitution) })
            is MultiReturnType -> MultiReturnType(normalized.types.map { substituteType(it, substitution) })
            is VarArgType -> VarArgType(normalized.types.map { substituteType(it, substitution) })
            is GenericType -> GenericType(normalized.baseName, normalized.typeParameters.map { substituteType(it, substitution) })
            is AliasType -> AliasType(normalized.name, substituteType(normalized.target, substitution))
            is ClassType -> normalized.copy(
                fields = normalized.fields.mapValues { (_, memberType) -> substituteType(memberType, substitution) },
                methods = normalized.methods.mapValues { (_, memberType) -> substituteType(memberType, substitution) },
                parent = normalized.parent?.let { substituteType(it, substitution) as ClassType },
                typeParameters = normalized.typeParameters.map { substituteType(it, substitution) }
            )

            else -> normalized
        }
    }

    private fun mergeMethods(existing: Map<String, Type>, incoming: Map<String, Type>): Map<String, Type> {
        if (incoming.isEmpty()) {
            return existing
        }

        val merged = existing.toMutableMap()
        incoming.forEach { (name, type) ->
            val previous = merged[name]
            merged[name] = when {
                previous == null -> type
                previous is OverloadedFunctionType && type is FunctionType -> {
                    OverloadedFunctionType(previous.callSignatures + type)
                }

                previous is FunctionType && type is FunctionType -> {
                    OverloadedFunctionType(listOf(previous, type))
                }

                previous is OverloadedFunctionType && type is OverloadedFunctionType -> {
                    OverloadedFunctionType(previous.callSignatures + type.callSignatures)
                }

                else -> type
            }
        }
        return merged
    }

    data class ParsedTypePrefix(
        val syntax: TypeSyntax,
        val remainder: String
    )

    data class ClassDefinition(
        val name: String,
        val fields: Map<String, Type>,
        val methods: Map<String, Type>,
        val parentName: String?,
        val declaredTypeParameters: List<String>
    )

}

class TypeResolutionContext(private val parent: TypeResolutionContext? = null) {
    private val typeParameters = mutableMapOf<String, TypeParameterType>()

    fun defineTypeParameter(name: String, constraint: Type? = null): TypeParameterType {
        val parameterType = TypeParameterType(name, constraint)
        typeParameters[name] = parameterType
        return parameterType
    }

    fun getTypeParameter(name: String): TypeParameterType? =
        typeParameters[name] ?: parent?.getTypeParameter(name)

    fun child(): TypeResolutionContext = TypeResolutionContext(this)
}
