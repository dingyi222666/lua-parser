package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationDocumentation
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.FieldTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.MethodTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.OverloadTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ReturnTagSyntax
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
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
import io.github.dingyi222666.luaparser.semantic.types.syntax.ArrayTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.GenericTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IdentifierObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IndexTableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IntersectionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.LiteralTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.MultiReturnTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NullableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.QuotedObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TupleTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeParameterSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.syntax.UnionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.VarargTypeSyntax
import io.github.dingyi222666.luaparser.semantic.checker.isColonMethodDeclaration
import io.github.dingyi222666.luaparser.semantic.checker.resolveOwningFunctionDeclaration

class TypeResolver(
    private val docFunctionTypeSyntaxParser: DocFunctionTypeSyntaxParser = DocFunctionTypeSyntaxParser()
) {

    private lateinit var binder: BinderPassResult
    private val resolvedDeclarations = mutableMapOf<DeclarationId, BinderDeclaration>()
    private val aliasStack = mutableSetOf<DeclarationId>()
    private val classStack = mutableSetOf<DeclarationId>()
    private val typeParameterStack = mutableSetOf<DeclarationId>()
    private val inlineTypeParameters = mutableMapOf<DeclarationId, TypeParameterType>()
    private val inlineTypeParameterStack = mutableSetOf<DeclarationId>()
    private val typeSubstitutor = TypeSubstitutor()
    private var nextSyntheticDeclarationId = -1

    fun resolve(binder: BinderPassResult): BinderPassResult {
        this.binder = binder
        resolvedDeclarations.clear()
        aliasStack.clear()
        classStack.clear()
        typeParameterStack.clear()
        inlineTypeParameters.clear()
        inlineTypeParameterStack.clear()
        nextSyntheticDeclarationId = -1

        val declarations = binder.declarationIndex.declarations.map { declaration ->
            resolveDeclaration(declaration.id)
        }
        return binder.withDeclarations(declarations)
    }

    private fun resolveDeclaration(declarationId: DeclarationId): BinderDeclaration {
        resolvedDeclarations[declarationId]?.let { return it }
        val declaration = requireNotNull(binder.declarationIndex.getDeclaration(declarationId)) {
            "Missing declaration $declarationId."
        }

        val resolved = when (declaration.kind) {
            DeclarationKind.TYPE_PARAMETER -> resolveTypeParameterDeclaration(declaration)
            DeclarationKind.TYPE_ALIAS -> resolveAliasDeclaration(declaration)
            DeclarationKind.CLASS -> resolveClassDeclaration(declaration)
            DeclarationKind.FIELD -> resolveFieldDeclaration(declaration)
            DeclarationKind.METHOD -> resolveMethodDeclaration(declaration)
            DeclarationKind.PARAMETER -> resolveParameterDeclaration(declaration)
            DeclarationKind.FUNCTION -> resolveFunctionDeclaration(declaration)
            DeclarationKind.LOCAL, DeclarationKind.GLOBAL, DeclarationKind.MODULE -> resolveValueDeclaration(declaration)
        }

        resolvedDeclarations[declarationId] = resolved
        return resolved
    }

    private fun resolveTypeParameterDeclaration(declaration: BinderDeclaration): BinderDeclaration {
        val context = TypeResolutionContext.forDeclaration(declaration.id, binder)
        val type = resolveTypeParameterReference(declaration, context)
        return declaration.copy(declaredType = type)
    }

    private fun resolveAliasDeclaration(declaration: BinderDeclaration): BinderDeclaration {
        if (!aliasStack.add(declaration.id)) {
            val cycleAlias = AliasType(declaration.name, ErrorType)
            return declaration.copy(
                declaredType = cycleAlias,
                documentation = declaration.documentation.withResolved(inlineType = ErrorType)
            )
        }

        try {
            val context = TypeResolutionContext.forDeclaration(declaration.id, binder)
            val ownedTypeParameters = binder.declarationIndex.getOwnedDeclarations(DeclarationOwner.Declaration(declaration.id))
                .filter { it.kind == DeclarationKind.TYPE_PARAMETER }
                .mapNotNull { resolveDeclaration(it.id).declaredType as? TypeParameterType }
            val resolvedTargetType = declaration.declaredTypeSyntax
                ?.let { resolveSyntax(it, context) }
                ?.let { attachAliasOwnedTypeParameters(it, ownedTypeParameters) }
                ?: UnknownType
            val targetType = if (resolvedTargetType.unwrapAliases() == ErrorType) ErrorType else resolvedTargetType
            val aliasType = AliasType(declaration.name, targetType)
            return declaration.copy(
                declaredType = aliasType,
                documentation = declaration.documentation.withResolved(inlineType = targetType)
            )
        } finally {
            aliasStack.remove(declaration.id)
        }
    }

    private fun attachAliasOwnedTypeParameters(type: Type, typeParameters: List<TypeParameterType>): Type {
        if (typeParameters.isEmpty()) {
            return type
        }

        return when (type) {
            is FunctionType -> type.copy(
                typeParameters = mergeTypeParameters(typeParameters, type.typeParameters),
                name = FunctionType(
                    parameters = type.parameters,
                    returnType = type.returnType,
                    typeParameters = mergeTypeParameters(typeParameters, type.typeParameters)
                ).name
            )

            is OverloadedFunctionType -> OverloadedFunctionType(
                callSignatures = type.callSignatures.map { signature ->
                    val mergedTypeParameters = mergeTypeParameters(typeParameters, signature.typeParameters)
                    signature.copy(
                        typeParameters = mergedTypeParameters,
                        name = FunctionType(
                            parameters = signature.parameters,
                            returnType = signature.returnType,
                            typeParameters = mergedTypeParameters
                        ).name
                    )
                }
            )

            else -> type
        }
    }

    private fun mergeTypeParameters(
        ownedTypeParameters: List<TypeParameterType>,
        declaredTypeParameters: List<TypeParameterType>
    ): List<TypeParameterType> {
        return (ownedTypeParameters + declaredTypeParameters)
            .distinctBy(TypeParameterType::name)
    }

    private fun resolveClassDeclaration(declaration: BinderDeclaration): BinderDeclaration {
        if (!classStack.add(declaration.id)) {
            return declaration.copy(declaredType = ClassType(declaration.name))
        }

        try {
            val owner = DeclarationOwner.Declaration(declaration.id)
            val ownedDeclarations = binder.declarationIndex.getOwnedDeclarations(owner)
            val context = TypeResolutionContext.forDeclaration(declaration.id, binder)
            val typeParameters = ownedDeclarations
                .filter { it.kind == DeclarationKind.TYPE_PARAMETER }
                .mapNotNull { resolveDeclaration(it.id).declaredType as? TypeParameterType }
            val fields = linkedMapOf<String, Type>()
            ownedDeclarations.filter { it.kind == DeclarationKind.FIELD }.forEach { field ->
                fields[field.name] = resolveDeclaration(field.id).declaredType ?: UnknownType
            }
            val methods = linkedMapOf<String, Type>()
            ownedDeclarations.filter { it.kind == DeclarationKind.METHOD }.forEach { method ->
                methods[method.name] = resolveDeclaration(method.id).declaredType ?: UnknownType
            }

            val parentType = declaration.documentation.findDocTag<ClassTagSyntax>()
                ?.parentName
                ?.let(::parseTypeSyntax)
                ?.let { resolveSyntax(it, context) }
            val superClass = parentType?.let { materializeParentClassSurface(it, context) }
            val classType = ClassType(
                name = declaration.name,
                fields = fields,
                methods = methods,
                superClass = superClass,
                superType = parentType,
                typeParameters = typeParameters
            )
            return declaration.copy(
                declaredType = classType,
                documentation = declaration.documentation.withResolved(parentType = parentType)
            )
        } finally {
            classStack.remove(declaration.id)
        }
    }

    private fun resolveFieldDeclaration(declaration: BinderDeclaration): BinderDeclaration {
            val context = TypeResolutionContext.forDeclaration(declaration.id, binder)
        val fieldTag = declaration.documentation.findDocTag<FieldTagSyntax>()
        val type = declaration.declaredTypeSyntax
            ?.let { resolveSyntax(it, context) }
            ?.let { if (fieldTag?.optional == true) makeOptionalType(it) else it }
        return declaration.copy(
            declaredType = type,
            documentation = declaration.documentation.withResolved(inlineType = type)
        )
    }

    private fun resolveMethodDeclaration(declaration: BinderDeclaration): BinderDeclaration {
        val owner = DeclarationOwner.Declaration(declaration.id)
        val ownedDeclarations = binder.declarationIndex.getOwnedDeclarations(owner)
        val isColonMethod = isColonMethodDeclaration(binder, declaration)
        val context = TypeResolutionContext.forDeclaration(declaration.id, binder)
        val syntax = declaration.declaredTypeSyntax
            ?: declaration.documentation.findDocTag<MethodTagSyntax>()
                ?.signatureText
                ?.let(docFunctionTypeSyntaxParser::parseOrNull)
        val primaryType = syntax?.let { resolveSyntax(it, context) as? FunctionType }
        val overloadTypes = resolveOverloadTypes(declaration, context)

        val parameters = buildList {
            if (isColonMethod) {
                val selfType = declaration.documentation?.docComment?.tags
                    .orEmpty()
                    .filterIsInstance<ParamTagSyntax>()
                    .lastOrNull { it.name == "self" }
                    ?.typeText
                    ?.let(::parseNamedTypeText)
                    ?.let { resolveSyntax(it, context) }
                    ?: UnknownType
                add(FunctionParameter(name = "self", type = selfType))
            }

            addAll(
                ownedDeclarations
                    .filter { it.kind == DeclarationKind.PARAMETER }
                    .map { parameterDeclaration ->
                        val resolvedParameter = resolveDeclaration(parameterDeclaration.id)
                        val paramTag = resolvedParameter.findOwningFunctionParamTag()
                        val isVararg = paramTag?.vararg == true || resolvedParameter.name == "..."
                        val parameterType = resolvedParameter.declaredType?.let {
                            if (isVararg && it !is VarargType) VarargType(it) else it
                        } ?: UnknownType
                        FunctionParameter(
                            name = resolvedParameter.name,
                            type = parameterType,
                            optional = paramTag?.optional == true,
                            vararg = isVararg
                        )
                    }
            )
        }

        val returnTypes = declaration.documentation?.docComment
            ?.tags
            .orEmpty()
            .filterIsInstance<ReturnTagSyntax>()
            .lastOrNull()
            ?.typeTexts
            ?.map(::parseNamedTypeText)
            ?.map { syntaxNode -> resolveSyntax(syntaxNode, context) }
            .orEmpty()
        val returnType = when (returnTypes.size) {
            0 -> UnknownType
            1 -> returnTypes.single()
            else -> MultiReturnType(returnTypes)
        }

        val inferredPrimaryType = if (primaryType == null && (parameters.isNotEmpty() || returnTypes.isNotEmpty())) {
            FunctionType(parameters = parameters, returnType = returnType)
        } else {
            primaryType
        }
        val type = combineMethodCallableType(inferredPrimaryType, overloadTypes)
        val resolvedParameterTypes = linkedMapOf<String, Type>()
        if (isColonMethod) {
            parameters.firstOrNull { it.name == "self" }?.type?.let { resolvedParameterTypes["self"] = it }
        }
        ownedDeclarations.filter { it.kind == DeclarationKind.PARAMETER }.forEach { parameter ->
            resolveDeclaration(parameter.id).declaredType?.let { resolvedParameterTypes[parameter.name] = it }
        }
        return declaration.copy(
            declaredType = type,
            documentation = declaration.documentation.withResolved(
                inlineType = type,
                parameterTypes = resolvedParameterTypes,
                returnTypes = returnTypes,
                overloadTypes = overloadTypes
            )
        )
    }

    private fun resolveParameterDeclaration(declaration: BinderDeclaration): BinderDeclaration {
        val context = TypeResolutionContext.forDeclaration(declaration.id, binder)
        val paramTag = declaration.findOwningFunctionParamTag()
        val syntax = declaration.declaredTypeSyntax
            ?: paramTag?.typeText?.let(::parseNamedTypeText)
        val resolved = syntax?.let { resolveSyntax(it, context) }
        val type = when {
            resolved == null -> null
            paramTag?.vararg == true -> VarargType(resolved)
            paramTag?.optional == true -> makeOptionalType(resolved)
            else -> resolved
        }
        return declaration.copy(
            declaredType = type,
            documentation = declaration.documentation.withResolved(inlineType = type)
        )
    }

    private fun resolveFunctionDeclaration(declaration: BinderDeclaration): BinderDeclaration {
        val owner = DeclarationOwner.Declaration(declaration.id)
        val ownedDeclarations = binder.declarationIndex.getOwnedDeclarations(owner)
        val typeParameters = ownedDeclarations
            .filter { it.kind == DeclarationKind.TYPE_PARAMETER }
            .mapNotNull { resolveDeclaration(it.id).declaredType as? TypeParameterType }

        val parameters = ownedDeclarations
            .filter { it.kind == DeclarationKind.PARAMETER }
            .map { parameterDeclaration ->
                val resolvedParameter = resolveDeclaration(parameterDeclaration.id)
                val paramTag = resolvedParameter.findOwningFunctionParamTag()
                val isVararg = paramTag?.vararg == true || resolvedParameter.name == "..."
                val parameterType = resolvedParameter.declaredType?.let {
                    if (isVararg && it !is VarargType) VarargType(it) else it
                } ?: UnknownType
                FunctionParameter(
                    name = resolvedParameter.name,
                    type = parameterType,
                    optional = paramTag?.optional == true,
                    vararg = isVararg
                )
            }

        val context = TypeResolutionContext.forDeclaration(declaration.id, binder)
        val overloadTypes = resolveOverloadTypes(declaration, context)
        val returnTypes = declaration.documentation?.docComment
            ?.tags
            .orEmpty()
            .filterIsInstance<ReturnTagSyntax>()
            .lastOrNull()
            ?.typeTexts
            ?.map(::parseNamedTypeText)
            ?.map { syntax -> resolveSyntax(syntax, context) }
            .orEmpty()
        val returnType = when (returnTypes.size) {
            0 -> UnknownType
            1 -> returnTypes.single()
            else -> MultiReturnType(returnTypes)
        }

        val resolvedParameterTypes = linkedMapOf<String, Type>()
        ownedDeclarations.filter { it.kind == DeclarationKind.PARAMETER }.forEach { parameter ->
            resolveDeclaration(parameter.id).declaredType?.let { resolvedParameterTypes[parameter.name] = it }
        }

        // Leave bare undocumentated functions without a declaredType so expression evaluation
        // can still infer ordinary Lua returns (table literals, etc.). Materializing
        // `function(...): unknown` here previously suppressed local helper-shadow return inference.
        val hasDocumentedShape =
            returnTypes.isNotEmpty() ||
                typeParameters.isNotEmpty() ||
                overloadTypes.isNotEmpty() ||
                resolvedParameterTypes.isNotEmpty() ||
                parameters.any { it.type != UnknownType }
        if (!hasDocumentedShape) {
            return declaration
        }

        val functionType = FunctionType(
            parameters = parameters,
            returnType = returnType,
            typeParameters = typeParameters
        )
        return declaration.copy(
            declaredType = functionType,
            documentation = declaration.documentation.withResolved(
                parameterTypes = resolvedParameterTypes,
                returnTypes = returnTypes,
                overloadTypes = overloadTypes
            )
        )
    }

    private fun resolveOverloadTypes(
        declaration: BinderDeclaration,
        context: TypeResolutionContext
    ): List<FunctionType> {
        return declaration.documentation?.docComment?.tags
            .orEmpty()
            .filterIsInstance<OverloadTagSyntax>()
            .mapNotNull { resolveOverloadTagSyntax(it, context) }
    }

    private fun resolveOverloadTagSyntax(
        overloadTag: OverloadTagSyntax,
        context: TypeResolutionContext
    ): FunctionType? {
        val syntax = docFunctionTypeSyntaxParser.parseOrNull(overloadTag.signatureText) ?: return null
        return resolveSyntax(syntax, context) as? FunctionType
    }

    private fun combineMethodCallableType(primaryType: FunctionType?, overloadTypes: List<FunctionType>): Type? {
        val signatures = buildList {
            primaryType?.let(::add)
            addAll(overloadTypes)
        }
        return when (signatures.size) {
            0 -> null
            1 -> signatures.single()
            else -> OverloadedFunctionType(signatures)
        }
    }

    private fun resolveValueDeclaration(declaration: BinderDeclaration): BinderDeclaration {
        val syntax = declaration.declaredTypeSyntax ?: return declaration
        val context = TypeResolutionContext.forDeclaration(declaration.id, binder)
        val type = resolveSyntax(syntax, context)
        return declaration.copy(
            declaredType = type,
            documentation = declaration.documentation.withResolved(inlineType = type)
        )
    }

    private fun resolveSyntax(typeSyntax: TypeSyntax, context: TypeResolutionContext): Type = when (typeSyntax) {
        is NamedTypeSyntax -> resolveNamedType(typeSyntax.name, context)
        is LiteralTypeSyntax -> normalizeLiteral(typeSyntax.value)
        is UnionTypeSyntax -> makeUnionType(typeSyntax.options.map { resolveSyntax(it, context) })
        is IntersectionTypeSyntax -> makeIntersectionType(typeSyntax.types.map { resolveSyntax(it, context) })
        is ArrayTypeSyntax -> ArrayType(resolveSyntax(typeSyntax.elementType, context))
        is GenericTypeSyntax -> resolveGenericType(typeSyntax, context)
        is NullableTypeSyntax -> makeOptionalType(resolveSyntax(typeSyntax.innerType, context))
        is TupleTypeSyntax -> TupleType(typeSyntax.elements.map { resolveSyntax(it, context) })
        is MultiReturnTypeSyntax -> MultiReturnType(typeSyntax.types.map { resolveSyntax(it, context) })
        is VarargTypeSyntax -> VarargType(resolveSyntax(typeSyntax.elementType, context))
        is FunctionTypeSyntax -> resolveFunctionType(typeSyntax, context)
        is ObjectTypeSyntax -> resolveObjectType(typeSyntax, context)
        is IndexTableTypeSyntax -> TableType(
            indexSignature = TableType.IndexSignature(
                keyType = resolveSyntax(typeSyntax.keyType, context),
                valueType = resolveSyntax(typeSyntax.valueType, context)
            )
        )
    }

    private fun resolveNamedType(name: String, context: TypeResolutionContext): Type {
        primitiveTypeFor(name)?.let { return it }
        val declaration = context.resolveTypeReference(name) ?: return CustomType(name)
        return when {
            declaration.kind == DeclarationKind.TYPE_PARAMETER -> resolveTypeParameterReference(declaration, context)
            binder.declarationIndex.getDeclaration(declaration.id) != null -> resolveDeclaration(declaration.id).declaredType ?: CustomType(name)
            else -> CustomType(name)
        }
    }

    private fun resolveGenericType(typeSyntax: GenericTypeSyntax, context: TypeResolutionContext): Type {
        val baseName = (typeSyntax.baseType as? NamedTypeSyntax)?.name ?: resolveSyntax(typeSyntax.baseType, context).displayName
        val arguments = typeSyntax.arguments.map { resolveSyntax(it, context) }
        if (baseName == "table" && arguments.size == 2) {
            return TableType(indexSignature = TableType.IndexSignature(arguments[0], arguments[1]))
        }
        return AppliedType(baseName = baseName, typeArguments = arguments)
    }

    private fun resolveFunctionType(typeSyntax: FunctionTypeSyntax, context: TypeResolutionContext): FunctionType {
        val inlineDeclarations = typeSyntax.typeParameters.map(::inlineTypeParameterDeclaration)
        val childContext = context.withOverlayDeclarations(inlineDeclarations)
        val typeParameters = inlineDeclarations.map { resolveTypeParameterReference(it, childContext) }
        val parameters = typeSyntax.parameters.map { parameter ->
            val baseType = resolveSyntax(parameter.type, childContext)
            FunctionParameter(
                name = parameter.name ?: if (parameter.vararg) "..." else "_",
                type = if (parameter.vararg) VarargType(baseType) else baseType,
                optional = parameter.optional,
                vararg = parameter.vararg
            )
        }
        val returnType = resolveSyntax(typeSyntax.returnType, childContext)
        return FunctionType(parameters = parameters, returnType = returnType, typeParameters = typeParameters)
    }

    private fun resolveObjectType(typeSyntax: ObjectTypeSyntax, context: TypeResolutionContext): Type {
        val fields = linkedMapOf<String, Type>()
        typeSyntax.fields.forEach { field ->
            val name = when (val fieldName = field.name) {
                is IdentifierObjectFieldNameSyntax -> fieldName.value
                is QuotedObjectFieldNameSyntax -> fieldName.literal.removeSurrounding("\"").removeSurrounding("'")
            }
            val valueType = resolveSyntax(field.type, context)
            fields[name] = if (field.optional) makeOptionalType(valueType) else valueType
        }

        val indexTables = typeSyntax.indexers.map { indexer ->
            TableType(
                fields = emptyMap(),
                indexSignature = TableType.IndexSignature(
                    keyType = resolveSyntax(indexer.keyType, context),
                    valueType = resolveSyntax(indexer.valueType, context)
                )
            )
        }

        return when (indexTables.size) {
            0 -> TableType(fields = fields)
            1 -> TableType(fields = fields, indexSignature = indexTables.single().indexSignature)
            else -> makeIntersectionType(
                listOf(TableType(fields = fields, indexSignature = indexTables.first().indexSignature)) + indexTables.drop(1)
            )
        }
    }

    private fun resolveTypeParameterReference(
        declaration: BinderDeclaration,
        context: TypeResolutionContext
    ): TypeParameterType {
        if (binder.declarationIndex.getDeclaration(declaration.id) != null) {
            return if (resolvedDeclarations[declaration.id]?.declaredType is TypeParameterType) {
                resolvedDeclarations.getValue(declaration.id).declaredType as TypeParameterType
            } else if (!typeParameterStack.add(declaration.id)) {
                TypeParameterType(declaration.name)
            } else {
                try {
                    val constraint = declaration.declaredTypeSyntax?.let { resolveSyntax(it, context) }
                    TypeParameterType(declaration.name, constraint)
                } finally {
                    typeParameterStack.remove(declaration.id)
                }
            }
        }

        inlineTypeParameters[declaration.id]?.let { return it }
        if (!inlineTypeParameterStack.add(declaration.id)) {
            return TypeParameterType(declaration.name)
        }

        try {
            val constraint = declaration.declaredTypeSyntax?.let { resolveSyntax(it, context) }
            return TypeParameterType(declaration.name, constraint).also {
                inlineTypeParameters[declaration.id] = it
            }
        } finally {
            inlineTypeParameterStack.remove(declaration.id)
        }
    }

    private fun normalizeLiteral(value: String): Type {
        val normalized = value.trim()
        return when {
            normalized == "true" -> LiteralType(true, PrimitiveType.BOOLEAN)
            normalized == "false" -> LiteralType(false, PrimitiveType.BOOLEAN)
            normalized == "nil" -> PrimitiveType.NIL
            normalized.startsWith("\"") || normalized.startsWith("'") -> {
                LiteralType(normalized.removeSurrounding("\"").removeSurrounding("'"), PrimitiveType.STRING)
            }

            normalized.contains('.') -> LiteralType(normalized.toDoubleOrNull() ?: normalized, PrimitiveType.NUMBER)
            else -> LiteralType(normalized.toLongOrNull() ?: normalized, PrimitiveType.NUMBER)
        }
    }

    private fun inlineTypeParameterDeclaration(parameter: TypeParameterSyntax): BinderDeclaration {
        return BinderDeclaration(
            id = DeclarationId(nextSyntheticDeclarationId--),
            name = parameter.name,
            kind = DeclarationKind.TYPE_PARAMETER,
            origin = DeclarationOrigin.SYNTHETIC,
            owner = DeclarationOwner.Root,
            declaredTypeSyntax = parameter.constraint
        )
    }

    private fun parseNamedTypeText(text: String): TypeSyntax =
        TypeSyntaxParser.parseOrNull(text.trim()) ?: NamedTypeSyntax(text.trim())

    private fun parseTypeSyntax(text: String): TypeSyntax =
        TypeSyntaxParser.parseOrNull(text.trim()) ?: NamedTypeSyntax(text.trim())

    private fun materializeParentClassSurface(type: Type, context: TypeResolutionContext): ClassType? {
        return materializeParentClassSurface(type, context, mutableListOf())
    }

    private fun materializeParentClassSurface(
        type: Type,
        context: TypeResolutionContext,
        aliasStack: MutableList<AliasType>
    ): ClassType? {
        return when (type) {
            is ClassType -> type
            is AppliedType -> materializeAppliedParentClassSurface(type, context, aliasStack)

            is AliasType -> {
                if (aliasStack.any { it === type }) {
                    null
                } else {
                    aliasStack += type
                    val result = materializeParentClassSurface(type.target, context, aliasStack)
                    aliasStack.removeAt(aliasStack.lastIndex)
                    result
                }
            }

            else -> type.unwrapAliases() as? ClassType
        }
    }

    private fun materializeAppliedParentClassSurface(
        type: AppliedType,
        context: TypeResolutionContext,
        aliasStack: MutableList<AliasType>
    ): ClassType? {
        val declaration = context.resolveNamedType(type.baseName)
            ?: context.visibleDeclarations().firstOrNull { candidate ->
                (candidate.kind == DeclarationKind.CLASS || candidate.kind == DeclarationKind.TYPE_ALIAS) &&
                    candidate.name.substringBefore('<').trim() == type.baseName
            }
            ?: return null

        val resolvedDeclaration = resolveDeclaration(declaration.id)
        val resolvedType = resolvedDeclaration.declaredType ?: return null
        val ownedTypeParameters = binder.declarationIndex.getOwnedDeclarations(DeclarationOwner.Declaration(declaration.id))
            .filter { it.kind == DeclarationKind.TYPE_PARAMETER }
            .mapNotNull { owned -> resolveDeclaration(owned.id).declaredType as? TypeParameterType }

        if (ownedTypeParameters.size != type.typeArguments.size) {
            return null
        }

        val mapping = ownedTypeParameters.zip(type.typeArguments).associate { (parameter, argument) ->
            parameter.name to argument
        }
        val substituted = when (resolvedType) {
            is AliasType -> typeSubstitutor.substitute(resolvedType.target, mapping, preserveOwnTypeParameters = false)
            else -> typeSubstitutor.substitute(resolvedType, mapping, preserveOwnTypeParameters = false)
        }

        return materializeParentClassSurface(substituted, context, aliasStack)
    }

    private fun primitiveTypeFor(name: String): Type? = when (name) {
        "string" -> PrimitiveType.STRING
        "number", "integer" -> PrimitiveType.NUMBER
        "boolean", "bool" -> PrimitiveType.BOOLEAN
        "nil", "void" -> PrimitiveType.NIL
        "function" -> PrimitiveType.FUNCTION
        "table" -> PrimitiveType.TABLE
        "thread" -> PrimitiveType.THREAD
        "userdata" -> PrimitiveType.USERDATA
        "any" -> PrimitiveType.ANY
        "unknown" -> UnknownType
        "never" -> NeverType
        else -> null
    }

    private fun BinderDeclaration.findOwningFunctionParamTag(): ParamTagSyntax? {
        val ownerId = (owner as? DeclarationOwner.Declaration)?.declarationId ?: return null
        val functionDeclaration = binder.declarationIndex.getDeclaration(ownerId) ?: return null
        return functionDeclaration.documentation?.docComment?.tags
            .orEmpty()
            .filterIsInstance<ParamTagSyntax>()
            .lastOrNull { tag -> tag.name == name || (tag.vararg && name == "...") }
    }

    private fun makeUnionType(types: List<Type>): Type {
        val flattened = linkedSetOf<Type>()
        types.forEach { type ->
            if (type is UnionType) {
                flattened += type.types
            } else {
                flattened += type
            }
        }
        return when (flattened.size) {
            0 -> NeverType
            1 -> flattened.single()
            else -> UnionType(flattened)
        }
    }

    private fun makeIntersectionType(types: List<Type>): Type {
        val flattened = linkedSetOf<Type>()
        types.forEach { type ->
            if (type is IntersectionType) {
                flattened += type.types
            } else {
                flattened += type
            }
        }
        return when (flattened.size) {
            0 -> PrimitiveType.ANY
            1 -> flattened.single()
            else -> IntersectionType(flattened)
        }
    }

    private fun makeOptionalType(type: Type): Type = makeUnionType(listOf(type, PrimitiveType.NIL))
}

private inline fun <reified T> DeclarationDocumentation?.findDocTag(): T? {
    return this?.docComment?.tags?.filterIsInstance<T>()?.lastOrNull()
}

private fun DeclarationDocumentation?.withResolved(
    inlineType: Type? = this?.resolvedInlineType,
    parameterTypes: Map<String, Type> = this?.resolvedParameterTypes.orEmpty(),
    returnTypes: List<Type> = this?.resolvedReturnTypes.orEmpty(),
    parentType: Type? = this?.resolvedParentType,
    overloadTypes: List<FunctionType> = this?.resolvedOverloadTypes.orEmpty()
): DeclarationDocumentation? {
    val current = this ?: if (
        inlineType == null && parameterTypes.isEmpty() && returnTypes.isEmpty() && parentType == null && overloadTypes.isEmpty()
    ) {
        return null
    } else {
        DeclarationDocumentation()
    }

    return current.copy(
        resolvedInlineType = inlineType,
        resolvedParameterTypes = parameterTypes,
        resolvedReturnTypes = returnTypes,
        resolvedParentType = parentType,
        resolvedOverloadTypes = overloadTypes
    )
}
