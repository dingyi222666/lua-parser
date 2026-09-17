package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionParameterSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser

class DocFunctionTypeSyntaxParser {

    fun parseOrNull(text: String?): FunctionTypeSyntax? {
        val normalized = text?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }

        val direct = TypeSyntaxParser.parseOrNull(normalized)
        if (direct is FunctionTypeSyntax) {
            return direct
        }

        if (!normalized.startsWith("(")) {
            return null
        }

        return parseBareSignature(normalized)
    }

    private fun parseBareSignature(text: String): FunctionTypeSyntax? {
        val closeIndex = findMatchingParen(text) ?: return null
        val parameters = parseParameters(text.substring(1, closeIndex)) ?: return null
        val remainder = text.substring(closeIndex + 1).trim()
        if (!remainder.startsWith(':')) {
            return null
        }

        val returnTypeText = remainder.removePrefix(":").trim()
        val returnType = TypeSyntaxParser.parseOrNull(returnTypeText) ?: return null
        return FunctionTypeSyntax(parameters = parameters, returnType = returnType)
    }

    private fun parseParameters(text: String): List<FunctionParameterSyntax>? {
        if (text.isBlank()) {
            return emptyList()
        }

        return splitTopLevel(text, ',').map { parseParameter(it) ?: return null }
    }

    private fun parseParameter(text: String): FunctionParameterSyntax? {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return null
        }

        if (normalized.startsWith("...")) {
            val type = TypeSyntaxParser.parseOrNull(normalized.removePrefix("...").trim()) ?: return null
            return FunctionParameterSyntax(name = "...", type = type, vararg = true)
        }

        findTopLevelChar(normalized, ':')?.let { colonIndex ->
            val nameToken = normalized.substring(0, colonIndex).trim()
            val typeText = normalized.substring(colonIndex + 1).trim()
            if (nameToken.isEmpty() || typeText.isEmpty()) {
                return null
            }
            val (parsedType, vararg) = parseParameterType(typeText) ?: return null
            return FunctionParameterSyntax(
                name = nameToken.removeSuffix("?").takeIf { it.isNotEmpty() },
                type = parsedType,
                optional = nameToken.endsWith('?'),
                vararg = vararg
            )
        }

        val firstWhitespace = normalized.indexOfFirst(Char::isWhitespace)
        if (firstWhitespace <= 0) {
            val (type, vararg) = parseParameterType(normalized) ?: return null
            return FunctionParameterSyntax(name = null, type = type, vararg = vararg)
        }

        val nameToken = normalized.substring(0, firstWhitespace)
        val typeText = normalized.substring(firstWhitespace).trim()
        val (type, vararg) = parseParameterType(typeText) ?: return null
        return FunctionParameterSyntax(
            name = nameToken.removeSuffix("?"),
            type = type,
            optional = nameToken.endsWith('?'),
            vararg = vararg
        )
    }

    private fun parseParameterType(text: String): Pair<io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax, Boolean>? {
        val normalized = text.trim()
        val vararg = normalized.endsWith("...")
        val typeText = if (vararg) normalized.removeSuffix("...").trimEnd() else normalized
        if (typeText.isEmpty()) {
            return null
        }
        return parseCompleteType(typeText)?.let { it to vararg }
    }

    private fun parseCompleteType(text: String): io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax? {
        val parsed = runCatching { TypeSyntaxParser.parsePrefix(text) }.getOrNull() ?: return null
        return if (parsed.remainder.trim().isEmpty()) parsed.syntax else null
    }

    private fun findMatchingParen(text: String): Int? {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        text.forEachIndexed { index, char ->
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                return@forEachIndexed
            }

            when (char) {
                '\'', '"' -> quote = char
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return index
                    }
                }
            }
        }
        return null
    }

    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var angleDepth = 0
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var quote: Char? = null
        var escaped = false
        var start = 0

        text.forEachIndexed { index, char ->
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                return@forEachIndexed
            }

            when (char) {
                '\'', '"' -> quote = char
                '<' -> angleDepth++
                '>' -> angleDepth--
                '(' -> parenDepth++
                ')' -> parenDepth--
                '{' -> braceDepth++
                '}' -> braceDepth--
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                delimiter -> if (angleDepth == 0 && parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                    parts += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }

        parts += text.substring(start).trim()
        return parts.filter { it.isNotEmpty() }
    }

    private fun findTopLevelChar(text: String, target: Char): Int? {
        var angleDepth = 0
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var quote: Char? = null
        var escaped = false

        text.forEachIndexed { index, char ->
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                return@forEachIndexed
            }

            when (char) {
                '\'', '"' -> quote = char
                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                target -> if (angleDepth == 0 && parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                    return index
                }
            }
        }

        return null
    }
}
