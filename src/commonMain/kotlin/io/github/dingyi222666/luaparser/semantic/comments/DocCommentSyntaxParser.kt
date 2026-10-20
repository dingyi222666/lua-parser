package io.github.dingyi222666.luaparser.semantic.comments

import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.types.syntax.indexOfTopLevelChar
import io.github.dingyi222666.luaparser.semantic.types.syntax.splitTopLevelToken
import io.github.dingyi222666.luaparser.semantic.types.syntax.splitTopLevelTypeText
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser

internal data class ParsedDocComment(
    val syntax: DocCommentSyntax?,
    val inlineTypeText: String?
)

class DocCommentSyntaxParser {

    private companion object {
        /** Longest-first candidate attempts in [recoverOperatorDelimitedTypePrefix]. */
        const val RECOVERY_CANDIDATE_LIMIT = 8
    }

    fun parse(comments: List<CommentStatement>): DocCommentSyntax? = parseWithInlineType(comments).syntax

    internal fun parseWithInlineType(comments: List<CommentStatement>): ParsedDocComment {
        if (comments.isEmpty()) {
            return ParsedDocComment(null, null)
        }

        val description = mutableListOf<String>()
        val tags = mutableListOf<DocTagSyntax>()
        var currentTagIndex = -1
        var inlineTypeText: String? = null

        comments.forEach { comment ->
            comment.comment.lines().forEachIndexed { index, rawLine ->
                val line = normalizeCommentLine(rawLine)
                val lineInfo = DocLineInfo(
                    sourceLine = comment.range.start.line + index,
                    rawLine = rawLine,
                    normalizedLine = line
                )
                if (line.startsWith("@")) {
                    parseTag(lineInfo)?.let {
                        tags.add(it)
                        currentTagIndex = tags.lastIndex
                        if (it is TypeTagSyntax &&
                            lineInfo.normalizedLine.removePrefix("@type").trim().isNotBlank()
                        ) inlineTypeText = it.typeText
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

        return ParsedDocComment(
            syntax = DocCommentSyntax(
                description = description.joinToString("\n").trim(),
                tags = tags
            ),
            inlineTypeText = inlineTypeText
        )
    }

    fun findInlineTypeText(comments: List<CommentStatement>): String? =
        parseWithInlineType(comments).inlineTypeText

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
                "java-class" -> parseJavaClassTag(content, lineInfo)
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
        val (nameToken, remainder) = splitTopLevelToken(content.trim())
        val vararg = nameToken == "..."
        val optional = nameToken.endsWith("?")
        val parsedType = parseTypePrefix(remainder)
        return ParamTagSyntax(
            name = if (vararg) "..." else nameToken.removeSuffix("?"),
            typeText = parsedType?.typeText,
            optional = optional,
            vararg = vararg,
            description = fallbackDescription(parsedType, remainder),
            range = tokenRange(lineInfo, nameToken.removeSuffix("?"))
        )
    }

    private fun parseReturnTag(content: String, lineInfo: DocLineInfo): ReturnTagSyntax {
        val parts = splitTopLevelTypeText(content, ',')
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
        val colonIndex = indexOfTopLevelChar(content, ':')
        val (name, declaredTypeParameters) = parseNamedTypeHeader(
            if (colonIndex == -1) content.trim() else content.substring(0, colonIndex).trim()
        )
        return ClassTagSyntax(
            name = name,
            parentName = colonIndex.takeIf { it != -1 }
                ?.let { content.substring(it + 1).trim().ifBlank { null } },
            declaredTypeParameters = declaredTypeParameters,
            range = tokenRange(lineInfo, name)
        )
    }

    private fun parseJavaClassTag(content: String, lineInfo: DocLineInfo): JavaClassTagSyntax {
        val (className, description) = splitTopLevelToken(content.trim())
        return JavaClassTagSyntax(
            className = className,
            description = description,
            range = tokenRange(lineInfo, className)
        )
    }

    private fun parseFieldTag(content: String, lineInfo: DocLineInfo): FieldTagSyntax {
        val stripped = stripVisibilityModifier(content)
        val (nameToken, remainder) = splitTopLevelToken(stripped.trim())
        val parsedType = parseTypePrefix(remainder)
        return FieldTagSyntax(
            name = nameToken.removeSuffix("?"),
            typeText = parsedType?.typeText,
            optional = nameToken.endsWith("?"),
            description = fallbackDescription(parsedType, remainder),
            range = tokenRange(lineInfo, nameToken.removeSuffix("?"))
        )
    }

    private fun parseGenericTag(content: String, lineInfo: DocLineInfo): GenericTagSyntax {
        val parameters = splitTolerantGenericParameters(content).mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) {
                return@mapNotNull null
            }

            val colonIndex = indexOfTopLevelChar(trimmed, ':')
            val name = if (colonIndex == -1) trimmed else trimmed.substring(0, colonIndex).trim()
            GenericParameterSyntax(
                name = name,
                constraintText = trimmed.substring(colonIndex + 1).trim().ifBlank { null }
                    .takeIf { colonIndex != -1 },
                range = tokenRange(lineInfo, name)
            )
        }

        return GenericTagSyntax(parameters = parameters, range = lineRange(lineInfo))
    }

    private fun splitTolerantGenericParameters(text: String): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }

        val parts = mutableListOf<String>()
        val current = StringBuilder()
        // Open/close delimiter pairs by ASCII offset (< >, { }, [ ], ( ) all differ by 2),
        // tracked per opener so a comma inside any open region never splits.
        val depths = mutableMapOf<Char, Int>()
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
                in "<{[(" -> depths[char] = (depths[char] ?: 0) + 1
                in ">}])" -> {
                    val opener = (char.code - 2).toChar()
                    depths[opener] = ((depths[opener] ?: 0) - 1).coerceAtLeast(0)
                }
                ',' -> {
                    val nextParameter = text.substring(index + 1).trimStart()
                    // Only braces/brackets/parens hard-block a split; an open `<` region
                    // still splits when the next chunk looks like a parameter start.
                    val shouldSplit = when {
                        (depths['{'] ?: 0) > 0 || (depths['['] ?: 0) > 0 || (depths['('] ?: 0) > 0 -> false
                        (depths['<'] ?: 0) == 0 -> true
                        else -> looksLikeGenericParameterStart(nextParameter)
                    }
                    if (shouldSplit) {
                        current.setLength(current.length - 1)
                        flush()
                        depths['<'] = 0
                    }
                }
            }
        }

        flush()
        return parts
    }

    private fun looksLikeGenericParameterStart(text: String): Boolean {
        val first = text.firstOrNull() ?: return false
        return first == '_' || first.isLetter()
    }

    private fun parseAliasTag(content: String, lineInfo: DocLineInfo): AliasTagSyntax {
        val (nameToken, remainder) = splitTopLevelToken(content.trim())
        val parsedType = parseTypePrefix(remainder)
        val (name, declaredTypeParameters) = parseNamedTypeHeader(nameToken)
        return AliasTagSyntax(
            name = name,
            declaredTypeParameters = declaredTypeParameters,
            targetTypeText = parsedType?.typeText,
            description = fallbackDescription(parsedType, remainder),
            range = tokenRange(lineInfo, name)
        )
    }

    private fun parseNamedTypeHeader(header: String): Pair<String, List<String>> {
        val trimmed = header.trim()
        val genericStart = trimmed.indexOf('<')
        if (genericStart == -1 || !trimmed.endsWith(">")) {
            return trimmed to emptyList()
        }

        // splitTopLevelTypeText already trims each part and drops blanks.
        return trimmed.substring(0, genericStart).trim() to
            splitTopLevelTypeText(trimmed.substring(genericStart + 1, trimmed.length - 1), ',')
    }

    private fun parseMethodTag(content: String, lineInfo: DocLineInfo): MethodTagSyntax {
        val normalized = content.trim()
        if (normalized.isEmpty()) {
            return MethodTagSyntax(name = "", range = lineRange(lineInfo))
        }

        // A "fun(" prefix that is not the leading token ("Foo.bar fun(...)") splits the target
        // from the signature at "fun(" itself; otherwise the first "(" splits it ("fun(...)"
        // and "Foo.bar(...)" share the parenthesis branch, since a leading "fun(" has an
        // empty target prefix either way).
        val functionStart = normalized.indexOf("fun(")
        val signatureStart = if (functionStart > 0) functionStart else normalized.indexOf('(')
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
        // Tags are built fresh inside a single parse and are not shared or hashed, so the
        // continuation lines can be merged in place instead of copying per concrete tag type.
        tag.description = listOf(tag.description, extra).filter(String::isNotBlank).joinToString("\n")
        return tag
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
            // Candidates are tried longest-first with a full parse each; a whitespace-heavy
            // prefix makes this quadratic. The real type prefix sits within the first few
            // longest candidates, so cap the attempts.
            .take(RECOVERY_CANDIDATE_LIMIT)

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
        val separatorIndex = maxOf(target.lastIndexOf('.'), target.lastIndexOf(':'))
        return if (separatorIndex == -1) null to target
        else target.substring(0, separatorIndex) to target.substring(separatorIndex + 1)
    }

    /** Shared tag-tail rule: the parsed type's description, else the unconsumed remainder text. */
    private fun fallbackDescription(parsedType: ParsedTypeText?, remainder: String): String {
        // The elvis RHS is only reached when parsedType is null (description is non-null),
        // so no extra null re-check is needed here.
        return parsedType?.description ?: remainder.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun stripVisibilityModifier(text: String): String {
        val (firstToken, remainder) = splitTopLevelToken(text.trim())
        return if (firstToken in setOf("public", "private", "protected", "package")) {
            remainder
        } else {
            text
        }
    }

    private fun normalizeCommentLine(rawLine: String): String =
        rawLine.trim().removePrefix("---").removePrefix("--").removePrefix("-").trim()

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
