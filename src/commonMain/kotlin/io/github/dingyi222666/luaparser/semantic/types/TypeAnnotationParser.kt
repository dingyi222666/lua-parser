package io.github.dingyi222666.luaparser.semantic.types

/**
 * Legacy type parsing facade kept for callers that still import `semantic.types.*`.
 *
 * The primary syntax and resolution APIs now live under `semantic.types.syntax.*` and
 * `semantic.types.resolve.*`.
 */
class TypeAnnotationParser {
    private val classes = mutableMapOf<String, ClassDefinition>()
    private val aliases = mutableMapOf<String, TypeSyntax>()

    fun parse(input: String, context: TypeResolutionContext = TypeResolutionContext()): Type {
        return resolve(parseSyntax(input), context)
    }

    fun parseSyntax(input: String): TypeSyntax {
        return Parser(input).parse(requireEof = true)
    }

    fun parseTypePrefix(input: String): ParsedTypePrefix {
        val parser = Parser(input)
        val syntax = parser.parse(requireEof = false)
        return ParsedTypePrefix(syntax, input.substring(parser.currentIndex).trim())
    }

    fun splitTopLevel(input: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var angleDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var parenDepth = 0
        var inString = false
        var stringChar = '\u0000'

        input.forEachIndexed { index, char ->
            if (inString) {
                if (char == stringChar && input.getOrNull(index - 1) != '\\') {
                    inString = false
                }
                return@forEachIndexed
            }

            when (char) {
                '\'', '"' -> {
                    inString = true
                    stringChar = char
                }

                '<' -> angleDepth++
                '>' -> angleDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                '(' -> parenDepth++
                ')' -> parenDepth--
                delimiter -> {
                    if (angleDepth == 0 && braceDepth == 0 && bracketDepth == 0 && parenDepth == 0) {
                        val segment = input.substring(start, index).trim()
                        if (segment.isNotEmpty()) {
                            parts.add(segment)
                        }
                        start = index + 1
                    }
                }
            }
        }

        val tail = input.substring(start).trim()
        if (tail.isNotEmpty()) {
            parts.add(tail)
        }

        return parts
    }

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

    private class Parser(private val source: String) {
        var currentIndex: Int = 0
            private set

        fun parse(requireEof: Boolean): TypeSyntax {
            val syntax = parseType()
            skipWhitespace()
            if (requireEof && !isAtEnd()) {
                error("Unexpected trailing type tokens: '${source.substring(currentIndex)}'")
            }
            return syntax
        }

        private fun parseType(): TypeSyntax = parseUnionType()

        private fun parseUnionType(): TypeSyntax {
            val parts = mutableListOf(parseIntersectionType())
            while (true) {
                skipWhitespace()
                if (!match('|')) {
                    break
                }
                parts.add(parseIntersectionType())
            }
            return if (parts.size == 1) parts[0] else UnionTypeSyntax(parts)
        }

        private fun parseIntersectionType(): TypeSyntax {
            val parts = mutableListOf(parsePostfixType())
            while (true) {
                skipWhitespace()
                if (!match('&')) {
                    break
                }
                parts.add(parsePostfixType())
            }
            return if (parts.size == 1) parts[0] else IntersectionTypeSyntax(parts)
        }

        private fun parsePostfixType(): TypeSyntax {
            var current = parsePrimaryType()
            while (true) {
                skipWhitespace()
                current = when {
                    match('[', ']') -> ArrayTypeSyntax(current)
                    match('?') -> NullableTypeSyntax(current)
                    match('.', '.', '.') -> VarargTypeSyntax(current)
                    else -> return current
                }
            }
        }

        private fun parsePrimaryType(): TypeSyntax {
            skipWhitespace()
            if (isAtEnd()) {
                error("Expected type annotation")
            }

            return when {
                peekKeyword("fun") -> parseFunctionType()
                peek() == '{' -> parseObjectType()
                peek() == '[' -> parseTupleType()
                peek() == '(' -> {
                    advance()
                    val inner = parseType()
                    skipWhitespace()
                    consume(')', "Expected ')' to close grouped type")
                    inner
                }

                peek() == '\'' || peek() == '"' -> parseStringLiteralType()
                peek()?.isDigit() == true || (peek() == '-' && peek(1)?.isDigit() == true) -> parseNumberLiteralType()
                else -> parseNamedType()
            }
        }

        private fun parseFunctionType(): TypeSyntax {
            consumeKeyword("fun")
            skipWhitespace()

            val typeParameters = if (peek() == '<') parseTypeParameters() else emptyList()

            skipWhitespace()
            consume('(', "Expected '(' after function type")
            val parameters = parseDelimited(')') { parseFunctionParameter() }
            skipWhitespace()
            consume(')', "Expected ')' after function parameter list")

            skipWhitespace()
            val returnTypes = if (match(':')) {
                listOf(parseType())
            } else {
                listOf(NamedTypeSyntax("nil"))
            }

            return FunctionTypeSyntax(parameters, returnTypes, typeParameters)
        }

        private fun parseTypeParameters(): List<TypeParameterDeclarationSyntax> {
            consume('<', "Expected '<' to start type parameter list")
            val parameters = parseDelimited('>') {
                val name = parseIdentifier()
                skipWhitespace()
                val constraint = if (match(':')) parseType() else null
                TypeParameterDeclarationSyntax(name, constraint)
            }
            skipWhitespace()
            consume('>', "Expected '>' to close type parameter list")
            return parameters
        }

        private fun parseFunctionParameter(): FunctionParameterSyntax {
            skipWhitespace()
            if (match('.', '.', '.')) {
                skipWhitespace()
                val parameterType = if (match(':')) parseType() else NamedTypeSyntax("any")
                return FunctionParameterSyntax(name = "...", type = parameterType, vararg = true)
            }

            val start = currentIndex
            val identifier = tryParseIdentifier()
            if (identifier == null) {
                currentIndex = start
                return FunctionParameterSyntax(
                    name = null,
                    type = parseType(),
                    optional = false
                )
            }
            skipWhitespace()
            val optional = match('?')
            skipWhitespace()
            return if (match(':')) {
                FunctionParameterSyntax(
                    name = identifier,
                    type = parseType(),
                    optional = optional
                )
            } else {
                currentIndex = start
                FunctionParameterSyntax(
                    name = null,
                    type = parseType(),
                    optional = false
                )
            }
        }

        private fun parseObjectType(): TypeSyntax {
            consume('{', "Expected '{' to start object type")
            val fields = parseDelimited('}') {
                skipWhitespace()
                val fieldName = if (peek() == '\'' || peek() == '"') {
                    parseQuotedText().removeSurrounding("\"").removeSurrounding("'")
                } else {
                    parseIdentifier()
                }
                skipWhitespace()
                val optional = match('?')
                skipWhitespace()
                consume(':', "Expected ':' after object field name")
                ObjectFieldSyntax(fieldName, parseType(), optional)
            }
            skipWhitespace()
            consume('}', "Expected '}' after object type")
            return ObjectTypeSyntax(fields)
        }

        private fun parseTupleType(): TypeSyntax {
            consume('[', "Expected '[' to start tuple type")
            val types = parseDelimited(']') { parseType() }
            skipWhitespace()
            consume(']', "Expected ']' after tuple type")
            return TupleTypeSyntax(types)
        }

        private fun parseStringLiteralType(): TypeSyntax {
            return LiteralTypeSyntax(parseQuotedText())
        }

        private fun parseNumberLiteralType(): TypeSyntax {
            val start = currentIndex
            if (peek() == '-') advance()
            while (peek()?.isDigit() == true || peek() == '.') {
                advance()
            }
            return LiteralTypeSyntax(source.substring(start, currentIndex))
        }

        private fun parseNamedType(): TypeSyntax {
            val identifier = parseQualifiedIdentifier()
            skipWhitespace()

            if (peek() == '<') {
                val arguments = parseTypeArguments()
                return if (identifier == "table" && arguments.size == 2) {
                    IndexTypeSyntax(arguments[0], arguments[1])
                } else {
                    GenericTypeSyntax(identifier, arguments)
                }
            }

            return when (identifier) {
                "true", "false", "nil" -> LiteralTypeSyntax(identifier)
                else -> NamedTypeSyntax(identifier)
            }
        }

        private fun parseTypeArguments(): List<TypeSyntax> {
            consume('<', "Expected '<' to start generic argument list")
            val arguments = parseDelimited('>') { parseType() }
            skipWhitespace()
            consume('>', "Expected '>' after generic argument list")
            return arguments
        }

        private fun <T> parseDelimited(terminator: Char, parser: () -> T): List<T> {
            val values = mutableListOf<T>()
            skipWhitespace()
            if (peek() == terminator) {
                return values
            }

            while (!isAtEnd() && peek() != terminator) {
                values.add(parser())
                skipWhitespace()
                if (peek() == terminator) {
                    break
                }
                if (!match(',')) {
                    error("Expected ',' or '$terminator' in type list")
                }
                skipWhitespace()
            }

            return values
        }

        private fun parseQualifiedIdentifier(): String {
            val builder = StringBuilder(parseIdentifier())
            while (true) {
                skipWhitespace()
                if (!match('.')) {
                    break
                }
                builder.append('.')
                builder.append(parseIdentifier())
            }
            return builder.toString()
        }

        private fun parseIdentifier(): String {
            skipWhitespace()
            val start = currentIndex
            val first = peek() ?: error("Expected identifier")
            if (!first.isLetter() && first != '_') {
                error("Expected identifier")
            }
            advance()
            while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) {
                advance()
            }
            return source.substring(start, currentIndex)
        }

        private fun tryParseIdentifier(): String? {
            skipWhitespace()
            val first = peek() ?: return null
            if (!first.isLetter() && first != '_') {
                return null
            }
            return parseIdentifier()
        }

        private fun parseQuotedText(): String {
            skipWhitespace()
            val quote = peek() ?: error("Expected string literal")
            if (quote != '\'' && quote != '"') {
                error("Expected string literal")
            }
            val start = currentIndex
            advance()
            while (!isAtEnd()) {
                val current = advance()
                if (current == quote && source.getOrNull(currentIndex - 2) != '\\') {
                    break
                }
            }
            return source.substring(start, currentIndex)
        }

        private fun peekKeyword(keyword: String): Boolean {
            skipWhitespace()
            if (!source.startsWith(keyword, currentIndex)) {
                return false
            }
            val next = source.getOrNull(currentIndex + keyword.length)
            return next == null || (!next.isLetterOrDigit() && next != '_')
        }

        private fun consumeKeyword(keyword: String) {
            if (!peekKeyword(keyword)) {
                error("Expected keyword '$keyword'")
            }
            repeat(keyword.length) { advance() }
        }

        private fun consume(expected: Char, message: String) {
            if (!match(expected)) {
                error(message)
            }
        }

        private fun match(vararg chars: Char): Boolean {
            skipWhitespace()
            if (chars.isEmpty()) return false
            if (currentIndex + chars.size > source.length) return false
            chars.forEachIndexed { offset, char ->
                if (source[currentIndex + offset] != char) {
                    return false
                }
            }
            currentIndex += chars.size
            return true
        }

        private fun peek(offset: Int = 0): Char? = source.getOrNull(currentIndex + offset)

        private fun advance(): Char {
            val current = source[currentIndex]
            currentIndex++
            return current
        }

        private fun skipWhitespace() {
            while (peek()?.isWhitespace() == true) {
                currentIndex++
            }
        }

        private fun isAtEnd(): Boolean = currentIndex >= source.length

        private fun error(message: String): Nothing = throw IllegalArgumentException(message)
    }
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
