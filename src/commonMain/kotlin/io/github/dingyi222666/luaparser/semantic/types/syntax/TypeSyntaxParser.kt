package io.github.dingyi222666.luaparser.semantic.types.syntax

object TypeSyntaxParser {

    fun parse(text: String): TypeSyntax = Parser(text).parse(requireEof = true)

    fun parseOrNull(text: String): TypeSyntax? = try {
        parse(text)
    } catch (_: TypeSyntaxParseException) {
        null
    }

    fun parsePrefix(text: String): ParsedTypePrefix {
        val parser = Parser(text)
        val syntax = parser.parse(requireEof = false, allowBoundaryStop = true)
        return ParsedTypePrefix(
            syntax = syntax,
            endIndex = parser.currentIndex,
            remainder = text.substring(parser.currentIndex)
        )
    }

    data class ParsedTypePrefix(
        val syntax: TypeSyntax,
        val endIndex: Int,
        val remainder: String
    ) {
        val consumedLength: Int
            get() = endIndex
    }

    private class Parser(private val source: String) {
        var currentIndex: Int = 0
            private set

        private var allowBoundaryStop: Boolean = false

        fun parse(requireEof: Boolean, allowBoundaryStop: Boolean = false): TypeSyntax {
            this.allowBoundaryStop = allowBoundaryStop
            val syntax = parseType()
            skipWhitespace()
            if (requireEof && !isAtEnd()) {
                fail("Unexpected trailing type tokens")
            }
            return syntax
        }

        private fun parseType(
            allowMultiReturn: Boolean = true,
            allowPostfixVararg: Boolean = true
        ): TypeSyntax {
            return if (allowMultiReturn) {
                parseMultiReturnType(allowPostfixVararg)
            } else {
                parseUnionType(allowPostfixVararg)
            }
        }

        private fun parseMultiReturnType(allowPostfixVararg: Boolean): TypeSyntax {
            val types = mutableListOf(parseUnionType(allowPostfixVararg))
            while (true) {
                skipWhitespace()
                val checkpoint = currentIndex
                if (!match(',')) {
                    break
                }
                if (allowBoundaryStop && !canParseContinuation { parseUnionType(allowPostfixVararg) }) {
                    currentIndex = checkpoint
                    break
                }
                types.add(parseUnionType(allowPostfixVararg))
            }
            return if (types.size == 1) types[0] else MultiReturnTypeSyntax(types)
        }

        private fun parseUnionType(allowPostfixVararg: Boolean): TypeSyntax {
            val options = mutableListOf(parseIntersectionType(allowPostfixVararg))
            while (true) {
                skipWhitespace()
                val checkpoint = currentIndex
                if (!match('|')) {
                    break
                }
                if (allowBoundaryStop && !canParseContinuation { parseIntersectionType(allowPostfixVararg) }) {
                    currentIndex = checkpoint
                    break
                }
                options.add(parseIntersectionType(allowPostfixVararg))
            }
            return if (options.size == 1) options[0] else UnionTypeSyntax(options)
        }

        private fun parseIntersectionType(allowPostfixVararg: Boolean): TypeSyntax {
            val types = mutableListOf(parsePostfixType(allowPostfixVararg))
            while (true) {
                skipWhitespace()
                val checkpoint = currentIndex
                if (!match('&')) {
                    break
                }
                if (allowBoundaryStop && !canParseContinuation { parsePostfixType(allowPostfixVararg) }) {
                    currentIndex = checkpoint
                    break
                }
                types.add(parsePostfixType(allowPostfixVararg))
            }
            return if (types.size == 1) types[0] else IntersectionTypeSyntax(types)
        }

        private fun parsePostfixType(allowPostfixVararg: Boolean): TypeSyntax {
            var current = parsePrimaryType()

            while (true) {
                skipWhitespace()
                current = when {
                    current is NamedTypeSyntax && peek() == '<' -> parseGenericOrIndexTableType(current)
                    allowPostfixVararg && startsWith("...") -> {
                        consumeText("...", "Expected '...' for vararg type")
                        VarargTypeSyntax(current)
                    }
                    match('[', ']') -> ArrayTypeSyntax(current)
                    match('?') -> NullableTypeSyntax(current)
                    else -> return current
                }
            }
        }

        private fun parsePrimaryType(): TypeSyntax {
            skipWhitespace()

            if (isAtEnd()) {
                fail("Expected type annotation")
            }

            return when {
                peekKeyword("fun") -> parseFunctionType()
                peek() == '{' -> parseObjectType()
                peek() == '[' -> parseTupleType()
                startsWith("...") -> fail("Use postfix vararg syntax like 'T...'")
                peek() == '(' -> {
                    advance()
                    val inner = parseType()
                    skipWhitespace()
                    consume(')', "Expected ')' to close grouped type")
                    inner
                }

                peek() == '\'' || peek() == '"' -> LiteralTypeSyntax(parseQuotedText())
                peek()?.isDigit() == true || (peek() == '-' && peek(1)?.isDigit() == true) -> LiteralTypeSyntax(parseNumberLiteralText())
                else -> parseNamedOrKeywordType()
            }
        }

        private fun parseFunctionType(): TypeSyntax {
            consumeKeyword("fun")
            skipWhitespace()
            val typeParameters = if (peek() == '<') parseTypeParameters() else emptyList()
            consume('(', "Expected '(' to start function parameter list")
            val parameters = parseFunctionParameters()
            consume(')', "Expected ')' to close function parameter list")
            consume(':', "Expected ':' before function return type")
            val returnType = parseType(allowMultiReturn = true)
            return FunctionTypeSyntax(
                parameters = parameters,
                returnType = returnType,
                typeParameters = typeParameters
            )
        }

        private fun parseTypeParameters(): List<TypeParameterSyntax> {
            consume('<', "Expected '<' to start type parameter list")
            val parameters = mutableListOf<TypeParameterSyntax>()
            skipWhitespace()

            if (match('>')) {
                return parameters
            }

            while (true) {
                val name = parseIdentifier()
                skipWhitespace()
                    val constraint = if (match(':')) parseType(allowMultiReturn = false) else null
                parameters.add(TypeParameterSyntax(name = name, constraint = constraint))
                skipWhitespace()
                when {
                    match('>') -> return parameters
                    match(',') -> continue
                    else -> fail("Expected ',' or '>' in type parameter list")
                }
            }
        }

        private fun parseFunctionParameters(): List<FunctionParameterSyntax> {
            val parameters = mutableListOf<FunctionParameterSyntax>()
            skipWhitespace()
            if (peek() == ')') {
                return parameters
            }

            while (true) {
                parameters.add(parseFunctionParameter())
                skipWhitespace()
                if (peek() == ')') {
                    return parameters
                }
                if (isAtEnd()) {
                    fail("Expected ')' to close function parameter list")
                }
                if (!match(',')) {
                    fail("Expected ',' or ')' in function parameter list")
                }
            }
        }

        private fun parseFunctionParameter(): FunctionParameterSyntax {
            skipWhitespace()
            val checkpoint = currentIndex
            if (isIdentifierStart(peek())) {
                val name = parseIdentifier()
                skipWhitespace()
                val optional = match('?')
                skipWhitespace()
                if (match(':')) {
                    val type = parseType(allowMultiReturn = false, allowPostfixVararg = false)
                    val vararg = if (startsWith("...")) {
                        consumeText("...", "Expected '...' for vararg parameter")
                        true
                    } else {
                        false
                    }
                    return FunctionParameterSyntax(name = name, type = type, optional = optional, vararg = vararg)
                }
                currentIndex = checkpoint
            }

            val type = parseType(allowMultiReturn = false, allowPostfixVararg = false)
            val vararg = if (startsWith("...")) {
                consumeText("...", "Expected '...' for vararg parameter")
                true
            } else {
                false
            }
            return FunctionParameterSyntax(name = null, type = type, vararg = vararg)
        }

        private fun parseObjectType(): TypeSyntax {
            consume('{', "Expected '{' to start object type")
            val fields = mutableListOf<ObjectFieldSyntax>()
            val indexers = mutableListOf<ObjectIndexerSyntax>()
            skipWhitespace()

            if (match('}')) {
                return ObjectTypeSyntax()
            }

            while (true) {
                skipWhitespace()
                if (peek() == '[') {
                    indexers.add(parseObjectIndexer())
                } else {
                    fields.add(parseObjectField())
                }

                skipWhitespace()
                when {
                    match('}') -> return ObjectTypeSyntax(fields = fields, indexers = indexers)
                    match(',') || match(';') -> continue
                    else -> fail("Expected ',' or '}' in object type")
                }
            }
        }

        private fun parseObjectField(): ObjectFieldSyntax {
            val name = parseObjectFieldName()
            val optional = match('?')
            consume(':', "Expected ':' after object field name")
            val type = parseType(allowMultiReturn = false)
            return ObjectFieldSyntax(name = name, type = type, optional = optional)
        }

        private fun parseObjectFieldName(): ObjectFieldNameSyntax {
            skipWhitespace()
            return when (peek()) {
                '\'', '"' -> QuotedObjectFieldNameSyntax(parseQuotedText())
                else -> IdentifierObjectFieldNameSyntax(parseIdentifier())
            }
        }

        private fun parseObjectIndexer(): ObjectIndexerSyntax {
            consume('[', "Expected '[' to start object indexer")
            skipWhitespace()

            val checkpoint = currentIndex
            var keyName: String? = null
            if (isIdentifierStart(peek())) {
                val identifier = parseIdentifier()
                skipWhitespace()
                if (match(':')) {
                    keyName = identifier
                } else {
                    currentIndex = checkpoint
                }
            }

            val keyType = parseType(allowMultiReturn = false)
            consume(']', "Expected ']' to close object indexer")
            consume(':', "Expected ':' after object indexer")
            val valueType = parseType(allowMultiReturn = false)
            return ObjectIndexerSyntax(keyType = keyType, valueType = valueType, keyName = keyName)
        }

        private fun parseTupleType(): TypeSyntax {
            consume('[', "Expected '[' to start tuple type")
            val elements = mutableListOf<TypeSyntax>()
            skipWhitespace()
            if (match(']')) {
                return TupleTypeSyntax(elements)
            }

            while (true) {
                elements.add(parseType(allowMultiReturn = false))
                skipWhitespace()
                when {
                    match(']') -> return TupleTypeSyntax(elements)
                    match(',') -> continue
                    else -> fail("Expected ',' or ']' in tuple type")
                }
            }
        }

        private fun parseNamedOrKeywordType(): TypeSyntax {
            val identifier = parseQualifiedIdentifier()
            return when (identifier) {
                "true", "false", "nil" -> LiteralTypeSyntax(identifier)
                else -> NamedTypeSyntax(identifier)
            }
        }

        private fun parseTypeArguments(): List<TypeSyntax> {
            consume('<', "Expected '<' to start generic argument list")
            val arguments = mutableListOf<TypeSyntax>()
            skipWhitespace()

            if (peek() == '>') {
                advance()
                return arguments
            }

            while (true) {
                if (isAtEnd()) {
                    fail("Expected '>' to close generic argument list")
                }

                arguments.add(parseType(allowMultiReturn = false))
                skipWhitespace()

                when {
                    match('>') -> return arguments
                    isAtEnd() -> fail("Expected '>' to close generic argument list")
                    match(',') -> {
                        skipWhitespace()
                        if (isAtEnd()) {
                            fail("Expected '>' to close generic argument list")
                        }
                    }

                    else -> fail("Expected ',' or '>' in type list")
                }
            }
        }

        private fun parseGenericOrIndexTableType(baseType: NamedTypeSyntax): TypeSyntax {
            val arguments = parseTypeArguments()
            return if (baseType.name == "table" && arguments.size == 2) {
                IndexTableTypeSyntax(keyType = arguments[0], valueType = arguments[1])
            } else {
                GenericTypeSyntax(baseType, arguments)
            }
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
                    fail("Expected ',' or '$terminator' in type list")
                }
            }

            return values
        }

        private fun parseQualifiedIdentifier(): String {
            val builder = StringBuilder(parseIdentifier())
            while (true) {
                skipWhitespace()
                if (peek() != '.' || !isIdentifierStart(peek(1))) {
                    break
                }
                advance()
                builder.append('.')
                builder.append(parseIdentifier())
            }
            return builder.toString()
        }

        private fun isIdentifierStart(char: Char?): Boolean {
            return char?.let { it.isLetter() || it == '_' } == true
        }

        private fun isTypeStartAhead(): Boolean {
            val checkpoint = currentIndex
            skipWhitespace()
            val result = when {
                isAtEnd() -> false
                startsWith("...") -> false
                peekKeyword("fun") -> true
                peek() in listOf('{', '[', '(', '\'', '"') -> true
                peek()?.isDigit() == true -> true
                peek() == '-' && peek(1)?.isDigit() == true -> true
                isIdentifierStart(peek()) -> true
                else -> false
            }
            currentIndex = checkpoint
            return result
        }

        private fun canParseContinuation(parseOperand: () -> TypeSyntax): Boolean {
            val checkpoint = currentIndex
            if (!isTypeStartAhead()) {
                currentIndex = checkpoint
                return false
            }

            val parsedSuccessfully = runCatching { parseOperand() }.isSuccess
            if (!parsedSuccessfully) {
                currentIndex = checkpoint
                return false
            }

            skipWhitespace()
            // A continuation arm is kept only when the arm itself is a complete type
            // prefix: EOF, another type-syntax delimiter, or a Lua line comment that
            // terminates the type for doc-comment consumers (`string | number -- note`).
            // Non-boundary prose after a candidate arm (e.g. `trailing prose`) rejects
            // the arm so parsePrefix can stop before the operator without throwing.
            val validBoundary = isValidContinuationBoundary()
            currentIndex = checkpoint
            return validBoundary
        }

        private fun isValidContinuationBoundary(): Boolean {
            val boundary = peek() ?: return true
            if (boundary in listOf(',', '|', '&', ')', ']', '}', '>')) {
                return true
            }
            // Lua line comment starts a non-type tail after a complete arm.
            if (boundary == '-' && peek(1) == '-') {
                return true
            }
            return false
        }

        private fun parseIdentifier(): String {
            skipWhitespace()
            val start = currentIndex
            val first = peek() ?: fail("Expected identifier")
            if (!first.isLetter() && first != '_') {
                fail("Expected identifier")
            }
            advance()
            while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) {
                advance()
            }
            return source.substring(start, currentIndex)
        }

        private fun parseNumberLiteralText(): String {
            val start = currentIndex
            if (peek() == '-') {
                advance()
            }

            while (peek()?.isDigit() == true) {
                advance()
            }

            if (peek() == '.' && peek(1)?.isDigit() == true) {
                advance()
                while (peek()?.isDigit() == true) {
                    advance()
                }
            }

            return source.substring(start, currentIndex)
        }

        private fun parseQuotedText(): String {
            skipWhitespace()
            val quote = peek() ?: fail("Expected string literal")
            if (quote != '\'' && quote != '"') {
                fail("Expected string literal")
            }

            val start = currentIndex
            advance()
            while (!isAtEnd()) {
                val current = advance()
                if (current == quote && source.getOrNull(currentIndex - 2) != '\\') {
                    return source.substring(start, currentIndex)
                }
            }

            fail("Unterminated string literal")
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
                fail("Expected '$keyword'")
            }
            currentIndex += keyword.length
        }

        private fun consume(expected: Char, message: String) {
            if (!match(expected)) {
                fail(message)
            }
        }

        private fun consumeText(expected: String, message: String) {
            skipWhitespace()
            if (!source.startsWith(expected, currentIndex)) {
                fail(message)
            }
            currentIndex += expected.length
        }

        private fun match(vararg chars: Char): Boolean {
            skipWhitespace()
            if (chars.isEmpty() || currentIndex + chars.size > source.length) {
                return false
            }

            chars.forEachIndexed { index, char ->
                if (source[currentIndex + index] != char) {
                    return false
                }
            }

            currentIndex += chars.size
            return true
        }

        private fun startsWith(text: String): Boolean {
            skipWhitespace()
            return source.startsWith(text, currentIndex)
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

        private fun fail(message: String): Nothing {
            val context = source.substring(currentIndex).take(20)
            val suffix = if (context.isEmpty()) "" else " at index $currentIndex near '$context'"
            throw TypeSyntaxParseException(message + suffix)
        }
    }
}

class TypeSyntaxParseException(message: String) : IllegalArgumentException(message)
