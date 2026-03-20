package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.TypeAnnotationParser
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser

class DocCommentSyntaxParser(
    private val typeAnnotationParser: TypeAnnotationParser = TypeAnnotationParser()
) {

    fun parse(comments: List<CommentStatement>): DocCommentSyntax? {
        if (comments.isEmpty()) {
            return null
        }

        val description = mutableListOf<String>()
        val tags = mutableListOf<DocTagSyntax>()
        var currentTagIndex = -1

        comments.forEach { comment ->
            val rawLines = comment.comment.lines()
            normalizeDocLines(comment.comment).forEachIndexed { index, line ->
                val lineInfo = DocLineInfo(
                    sourceLine = comment.range.start.line + index,
                    rawLine = rawLines.getOrElse(index) { "" },
                    normalizedLine = line
                )
                if (line.startsWith("@")) {
                    parseTag(lineInfo)?.let {
                        tags.add(it)
                        currentTagIndex = tags.lastIndex
                    }
                } else if (line.isNotBlank()) {
                    if (currentTagIndex >= 0) {
                        tags[currentTagIndex] = appendDescription(tags[currentTagIndex], line)
                    } else {
                        description.add(line)
                    }
                }
            }
        }

        return DocCommentSyntax(
            description = description.joinToString("\n").trim(),
            tags = tags
        )
    }

    fun findInlineTypeText(comments: List<CommentStatement>): String? {
        return comments.asReversed().firstNotNullOfOrNull { comment ->
            normalizeDocLines(comment.comment).asReversed().firstNotNullOfOrNull { line ->
                if (!line.startsWith("@type")) {
                    return@firstNotNullOfOrNull null
                }

                val content = line.removePrefix("@type").trim()
                if (content.isBlank()) {
                    return@firstNotNullOfOrNull null
                }

                parseTypePrefix(content)?.typeText ?: content
            }
        }
    }

    private fun parseTag(lineInfo: DocLineInfo): DocTagSyntax? {
        val line = lineInfo.normalizedLine
        val body = line.removePrefix("@").trim()
        if (body.isEmpty()) {
            return null
        }

        val tagName = body.takeWhile { !it.isWhitespace() }
        val content = body.drop(tagName.length).trim()
        return runCatching {
            when (tagName) {
                "type" -> parseTypeTag(content, lineInfo)
                "param" -> parseParamTag(content, lineInfo)
                "return" -> parseReturnTag(content, lineInfo)
                "class" -> parseClassTag(content, lineInfo)
                "field" -> parseFieldTag(content, lineInfo)
                "generic" -> parseGenericTag(content, lineInfo)
                "alias" -> parseAliasTag(content, lineInfo)
                "overload" -> OverloadTagSyntax(signatureText = content, range = lineRange(lineInfo))
                "method" -> parseMethodTag(content, lineInfo)
                else -> UnknownTagSyntax(tagName = tagName, content = content, range = lineRange(lineInfo))
            }
        }.getOrElse {
            UnknownTagSyntax(tagName = tagName, content = content, range = lineRange(lineInfo))
        }
    }

    private fun parseTypeTag(content: String, lineInfo: DocLineInfo): TypeTagSyntax {
        val parsedType = parseTypePrefix(content)
        return TypeTagSyntax(
            typeText = parsedType?.typeText ?: content.ifBlank { "any" },
            description = parsedType?.description.orEmpty(),
            range = lineRange(lineInfo)
        )
    }

    private fun parseParamTag(content: String, lineInfo: DocLineInfo): ParamTagSyntax {
        val (nameToken, remainder) = splitFirstToken(content)
        val vararg = nameToken == "..."
        val optional = nameToken.endsWith("?")
        val parsedType = parseTypePrefix(remainder)
        return ParamTagSyntax(
            name = if (vararg) "..." else nameToken.removeSuffix("?"),
            typeText = parsedType?.typeText,
            optional = optional,
            vararg = vararg,
            description = parsedType?.description ?: remainder.takeIf { it.isNotBlank() && parsedType == null }.orEmpty(),
            range = tokenRange(lineInfo, nameToken.removeSuffix("?"))
        )
    }

    private fun parseReturnTag(content: String, lineInfo: DocLineInfo): ReturnTagSyntax {
        val parts = typeAnnotationParser.splitTopLevel(content, ',')
        if (parts.isEmpty()) {
            return ReturnTagSyntax()
        }

        val typeTexts = mutableListOf<String>()
        var description = ""
        parts.forEachIndexed { index, part ->
            val parsedType = parseTypePrefix(part)
            when {
                parsedType != null -> {
                    typeTexts.add(parsedType.typeText)
                    if (index == parts.lastIndex && parsedType.description.isNotBlank()) {
                        description = parsedType.description
                    }
                }

                part.isNotBlank() && index == parts.lastIndex && typeTexts.isNotEmpty() -> {
                    description = part.trim()
                }

                part.isNotBlank() -> typeTexts.add(part.trim())
            }
        }

        return ReturnTagSyntax(typeTexts = typeTexts, description = description, range = lineRange(lineInfo))
    }

    private fun parseClassTag(content: String, lineInfo: DocLineInfo): ClassTagSyntax {
        val colonIndex = findTopLevelChar(content, ':')
        val nameSegment = if (colonIndex == -1) content.trim() else content.substring(0, colonIndex).trim()
        val parentName = if (colonIndex == -1) null else content.substring(colonIndex + 1).trim().ifBlank { null }
        val genericStart = nameSegment.indexOf('<')
        val name = if (genericStart == -1) nameSegment else nameSegment.substring(0, genericStart).trim()
        val declaredTypeParameters = if (genericStart == -1 || !nameSegment.endsWith(">")) {
            emptyList()
        } else {
            typeAnnotationParser.splitTopLevel(
                nameSegment.substring(genericStart + 1, nameSegment.length - 1),
                ','
            ).map { it.trim() }
        }

        return ClassTagSyntax(
            name = name,
            parentName = parentName,
            declaredTypeParameters = declaredTypeParameters,
            range = tokenRange(lineInfo, name)
        )
    }

    private fun parseFieldTag(content: String, lineInfo: DocLineInfo): FieldTagSyntax {
        val stripped = stripVisibilityModifier(content)
        val (nameToken, remainder) = splitFirstToken(stripped)
        val parsedType = parseTypePrefix(remainder)
        return FieldTagSyntax(
            name = nameToken.removeSuffix("?"),
            typeText = parsedType?.typeText,
            optional = nameToken.endsWith("?"),
            description = parsedType?.description ?: remainder.takeIf { it.isNotBlank() && parsedType == null }.orEmpty(),
            range = tokenRange(lineInfo, nameToken.removeSuffix("?"))
        )
    }

    private fun parseGenericTag(content: String, lineInfo: DocLineInfo): GenericTagSyntax {
        val parameters = splitTolerantGenericParameters(content).mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) {
                return@mapNotNull null
            }

            val colonIndex = findTopLevelChar(trimmed, ':')
            if (colonIndex == -1) {
                GenericParameterSyntax(name = trimmed, range = tokenRange(lineInfo, trimmed))
            } else {
                GenericParameterSyntax(
                    name = trimmed.substring(0, colonIndex).trim(),
                    constraintText = trimmed.substring(colonIndex + 1).trim().ifBlank { null },
                    range = tokenRange(lineInfo, trimmed.substring(0, colonIndex).trim())
                )
            }
        }

        return GenericTagSyntax(parameters = parameters, range = lineRange(lineInfo))
    }

    private fun splitTolerantGenericParameters(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var angleDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var parenDepth = 0
        var inString = false
        var stringChar = '\u0000'

        fun flush() {
            val value = current.toString().trim()
            if (value.isNotEmpty()) {
                parts += value
            }
            current.clear()
        }

        text.forEachIndexed { index, char ->
            current.append(char)
            if (inString) {
                if (char == stringChar && text.getOrNull(index - 1) != '\\') {
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
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                ',' -> {
                    val nextParameter = text.substring(index + 1).trimStart()
                    val shouldSplit = when {
                        braceDepth > 0 || bracketDepth > 0 || parenDepth > 0 -> false
                        angleDepth == 0 -> true
                        else -> looksLikeGenericParameterStart(nextParameter)
                    }
                    if (shouldSplit) {
                        current.setLength(current.length - 1)
                        flush()
                        angleDepth = 0
                    }
                }
            }
        }

        flush()
        return parts
    }

    private fun looksLikeGenericParameterStart(text: String): Boolean {
        if (text.isEmpty()) {
            return false
        }
        val first = text.first()
        return first == '_' || first.isLetter()
    }

    private fun parseAliasTag(content: String, lineInfo: DocLineInfo): AliasTagSyntax {
        val (nameToken, remainder) = splitAliasHeader(content)
        val parsedType = parseTypePrefix(remainder)
        val (name, declaredTypeParameters) = parseNamedTypeHeader(nameToken)
        return AliasTagSyntax(
            name = name,
            declaredTypeParameters = declaredTypeParameters,
            targetTypeText = parsedType?.typeText,
            description = parsedType?.description ?: remainder.takeIf { it.isNotBlank() && parsedType == null }.orEmpty(),
            range = tokenRange(lineInfo, name)
        )
    }

    private fun parseNamedTypeHeader(header: String): Pair<String, List<String>> {
        val trimmed = header.trim()
        val genericStart = trimmed.indexOf('<')
        if (genericStart == -1 || !trimmed.endsWith(">")) {
            return trimmed to emptyList()
        }

        val name = trimmed.substring(0, genericStart).trim()
        val parameters = typeAnnotationParser.splitTopLevel(
            trimmed.substring(genericStart + 1, trimmed.length - 1),
            ','
        ).mapNotNull { part ->
            part.trim().takeIf(String::isNotEmpty)
        }
        return name to parameters
    }

    private fun splitAliasHeader(text: String): Pair<String, String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return "" to ""
        }
        return splitTopLevelToken(trimmed)
    }

    private fun parseMethodTag(content: String, lineInfo: DocLineInfo): MethodTagSyntax {
        val normalized = content.trim()
        if (normalized.isEmpty()) {
            return MethodTagSyntax(name = "", range = lineRange(lineInfo))
        }

        val functionStart = normalized.indexOf("fun(")
        if (functionStart > 0) {
            val target = normalized.substring(0, functionStart).trim()
            val (className, methodName) = splitMethodTarget(target)
            return MethodTagSyntax(
                name = methodName,
                className = className,
                signatureText = normalized.substring(functionStart).trim(),
                range = tokenRange(lineInfo, methodName)
            )
        }

        val signatureStart = normalized.indexOf('(')
        if (signatureStart == -1) {
            val (className, methodName) = splitMethodTarget(normalized)
            return MethodTagSyntax(name = methodName, className = className, range = tokenRange(lineInfo, methodName))
        }

        val target = normalized.substring(0, signatureStart).trim()
        val (className, methodName) = splitMethodTarget(target)
        return MethodTagSyntax(
            name = methodName,
            className = className,
            signatureText = normalized.substring(signatureStart).trim(),
            range = tokenRange(lineInfo, methodName)
        )
    }

    private fun appendDescription(tag: DocTagSyntax, extra: String): DocTagSyntax {
        val mergedDescription = listOf(tag.description, extra).filter(String::isNotBlank).joinToString("\n")
        return when (tag) {
            is AliasTagSyntax -> tag.copy(description = mergedDescription)
            is ClassTagSyntax -> tag.copy(description = mergedDescription)
            is FieldTagSyntax -> tag.copy(description = mergedDescription)
            is GenericTagSyntax -> tag.copy(description = mergedDescription)
            is MethodTagSyntax -> tag.copy(description = mergedDescription)
            is OverloadTagSyntax -> tag.copy(description = mergedDescription)
            is ParamTagSyntax -> tag.copy(description = mergedDescription)
            is ReturnTagSyntax -> tag.copy(description = mergedDescription)
            is TypeTagSyntax -> tag.copy(description = mergedDescription)
            is UnknownTagSyntax -> tag.copy(description = mergedDescription)
        }
    }

    private fun parseTypePrefix(text: String): ParsedTypeText? {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return null
        }

        val parsed = runCatching { TypeSyntaxParser.parsePrefix(normalized) }.getOrNull() ?: return null
        val recovered = parsed.remainder.trimStart().takeIf { it.startsWith("|") || it.startsWith("&") || it.startsWith(",") }
            ?.let { recoverOperatorDelimitedTypePrefix(normalized) }
        if (recovered != null) {
            return recovered
        }

        val remainder = parsed.remainder.trim()
        val typeText = if (remainder.isEmpty()) {
            normalized
        } else if (normalized.endsWith(remainder)) {
            normalized.substring(0, normalized.length - remainder.length).trimEnd()
        } else {
            normalized
        }

        return typeText.takeIf(String::isNotBlank)?.let {
            ParsedTypeText(typeText = it, description = remainder)
        }
    }

    private fun recoverOperatorDelimitedTypePrefix(text: String): ParsedTypeText? {
        val candidateEnds = buildList {
            add(text.length)
            text.forEachIndexed { index, char ->
                if (char.isWhitespace()) {
                    add(index)
                }
            }
        }.distinct().sortedDescending()

        candidateEnds.forEach { endIndex ->
            val typeCandidate = text.substring(0, endIndex).trimEnd()
            if (typeCandidate.isEmpty()) {
                return@forEach
            }
            if (TypeSyntaxParser.parseOrNull(typeCandidate) == null) {
                return@forEach
            }

            val description = text.substring(endIndex).trim()
            return ParsedTypeText(typeText = typeCandidate, description = description)
        }

        return null
    }

    private fun splitMethodTarget(target: String): Pair<String?, String> {
        val dotIndex = target.lastIndexOf('.')
        val colonIndex = target.lastIndexOf(':')
        val separatorIndex = maxOf(dotIndex, colonIndex)
        return if (separatorIndex == -1) {
            null to target
        } else {
            target.substring(0, separatorIndex) to target.substring(separatorIndex + 1)
        }
    }

    private fun splitFirstToken(text: String): Pair<String, String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return "" to ""
        }

        return splitTopLevelToken(trimmed)
    }

    private fun stripVisibilityModifier(text: String): String {
        val (firstToken, remainder) = splitFirstToken(text)
        return if (firstToken in setOf("public", "private", "protected", "package")) {
            remainder
        } else {
            text
        }
    }

    private fun normalizeDocLines(rawComment: String): List<String> {
        return rawComment.lines().map(::normalizeCommentLine)
    }

    private fun normalizeCommentLine(rawLine: String): String {
        return rawLine.trim().removePrefix("---").removePrefix("--").removePrefix("-").trim()
    }

    private fun lineRange(lineInfo: DocLineInfo): Range {
        return Range(
            start = Position(lineInfo.sourceLine, 1),
            end = Position(lineInfo.sourceLine, lineInfo.rawLine.length.coerceAtLeast(1) + 1)
        )
    }

    private fun tokenRange(lineInfo: DocLineInfo, token: String): Range {
        if (token.isBlank()) {
            return lineRange(lineInfo)
        }
        val normalizedLineStart = lineInfo.rawLine.indexOf(lineInfo.normalizedLine)
            .takeIf { it >= 0 }
            ?: 0
        val tokenOffset = lineInfo.normalizedLine.indexOf(token)
            .takeIf { it >= 0 }
            ?: lineInfo.rawLine.indexOf(token).takeIf { it >= 0 }
            ?: 0
        val column = normalizedLineStart + tokenOffset + 1
        return Range(
            start = Position(lineInfo.sourceLine, column),
            end = Position(lineInfo.sourceLine, column + token.length)
        )
    }

    private fun findTopLevelChar(text: String, target: Char): Int {
        var state = TopLevelScanState()

        text.forEachIndexed { index, char ->
            if (!state.accept(text, index, char)) {
                return@forEachIndexed
            }

            if (char == target && state.isTopLevel()) {
                return index
            }
        }

        return -1
    }

    private fun splitTopLevelToken(text: String): Pair<String, String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return "" to ""
        }

        val state = TopLevelScanState()
        trimmed.forEachIndexed { index, char ->
            if (!state.accept(trimmed, index, char)) {
                return@forEachIndexed
            }

            if (char.isWhitespace() && state.isTopLevel()) {
                return trimmed.substring(0, index) to trimmed.substring(index + 1).trim()
            }
        }

        return trimmed to ""
    }

    private class TopLevelScanState {
        var angleDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var parenDepth = 0
        var inString = false
        var stringChar = '\u0000'

        fun accept(text: String, index: Int, char: Char): Boolean {
            if (inString) {
                if (char == stringChar && text.getOrNull(index - 1) != '\\') {
                    inString = false
                }
                return false
            }

            when (char) {
                '\'', '"' -> {
                    inString = true
                    stringChar = char
                }

                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
            }

            return true
        }

        fun isTopLevel(): Boolean {
            return angleDepth == 0 && braceDepth == 0 && bracketDepth == 0 && parenDepth == 0 && !inString
        }
    }

    private data class ParsedTypeText(
        val typeText: String,
        val description: String
    )

    private data class DocLineInfo(
        val sourceLine: Int,
        val rawLine: String,
        val normalizedLine: String
    )
}
