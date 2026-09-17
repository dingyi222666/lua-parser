package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionParameterSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.syntax.indexOfTopLevelChar
import io.github.dingyi222666.luaparser.semantic.types.syntax.splitTopLevelTypeText

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

        return splitTopLevelTypeText(text, ',').map { parseParameter(it) ?: return null }
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

        indexOfTopLevelChar(normalized, ':').takeIf { it >= 0 }?.let { colonIndex ->
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

    private fun parseParameterType(text: String): Pair<TypeSyntax, Boolean>? {
        val normalized = text.trim()
        val vararg = normalized.endsWith("...")
        val typeText = if (vararg) normalized.removeSuffix("...").trimEnd() else normalized
        if (typeText.isEmpty()) {
            return null
        }
        return parseCompleteType(typeText)?.let { it to vararg }
    }

    private fun parseCompleteType(text: String): TypeSyntax? {
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
}
