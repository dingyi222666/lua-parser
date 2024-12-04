package io.github.dingyi222666.luaparser.semantic.comment

import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.semantic.types.*
import io.github.dingyi222666.luaparser.semantic.types.Type

class CommentProcessor(
    private val typeAnnotationParser: TypeAnnotationParser
) {
    // 存储所有注释节点，按行号索引
    private val commentsByLine = mutableMapOf<Int, MutableList<CommentStatement>>()

    // 存储已解析的文档注释
    private val docComments = mutableMapOf<Int, DocComment>()

    // 存储类的方法声明
    private val classMethods = mutableMapOf<String, MutableMap<String, FunctionType>>()

    // 添加新方法用于注册类方法
    fun addClassMethod(className: String, methodName: String, methodType: FunctionType) {
        classMethods.getOrPut(className) { mutableMapOf() }[methodName] = methodType
    }

    // 获取类的所有方法声明
    fun getClassMethods(className: String): Map<String, FunctionType> {
        return classMethods[className] ?: emptyMap()
    }

    // 处理并索引所有注释
    fun processComments(statements: List<StatementNode>) {
        commentsByLine.clear()
        docComments.clear()

        // 收集所有注释并按行号索引
        statements.forEach { stmt ->
            if (stmt is CommentStatement) {
                val line = stmt.range.start.line
                commentsByLine.getOrPut(line) { mutableListOf() }.add(stmt)

                // 如果是文档注释，解析它
                if (stmt.isDocComment) {
                    parseDocComment(stmt, line)
                }
            }
        }
    }

    internal fun parseDocComment(comment: CommentStatement, line: Int): DocComment {
        val lines = comment.comment.lines()
        val description = StringBuilder()
        val tags = mutableListOf<DocTag>()

        var currentTag: DocTag? = null
        var isInDescription = true

        for (lineText in lines) {
            val trimmed = lineText.trim().removePrefix("---").trim()

            if (trimmed.startsWith("@")) {
                isInDescription = false
                currentTag = parseDocTag(trimmed)
                if (currentTag != null) {
                    tags.add(currentTag)
                }
            } else if (currentTag != null) {
                // 添加到当前标签的描述中
                currentTag.description += "\n$trimmed"
            } else if (isInDescription && trimmed.isNotEmpty()) {
                // 添加到主描述中
                description.append(trimmed).append("\n")
            }
        }

        val comment = DocComment(
            description = description.toString().trim(),
            tags = tags
        )

        docComments[line] = comment

        return comment
    }

    private fun parseDocTag(text: String): DocTag? {
        val parts = text.removePrefix("@").split(Regex("\\s+"), 2)
        return when (parts[0]) {
            "param" -> {
                val paramParts = parts.getOrNull(1)?.split(Regex("\\s+"), 2)
                val paramName = paramParts?.getOrNull(0) ?: ""

                // 检查是否是类方法参数 (Class.method.param)
                if (paramName.contains(".")) {
                    val segments = paramName.split(".")
                    if (segments.size >= 3) {
                        val className = segments[0]
                        val methodName = segments[1]
                        val actualParamName = segments[2]

                        // 构建或更新方法类型
                        val currentMethods = classMethods.getOrPut(className) { mutableMapOf() }
                        val currentMethod = currentMethods[methodName] ?: FunctionType(emptyList(), PrimitiveType.ANY)

                        // 更新参数类型
                        val paramType = parseType(paramParts?.getOrNull(1))
                        val updatedParams = currentMethod.parameters.toMutableList()
                        val paramIndex = updatedParams.indexOfFirst { it.name == actualParamName }
                        val newParam = ParameterType(actualParamName, paramType)

                        if (paramIndex >= 0) {
                            updatedParams[paramIndex] = newParam
                        } else {
                            updatedParams.add(newParam)
                        }

                        currentMethods[methodName] = FunctionType(updatedParams, currentMethod.returnType)

                        return ParamTag(actualParamName, paramType, paramParts?.getOrNull(1) ?: "")
                    }
                }

                ParamTag(
                    name = paramName,
                    type = parseType(paramParts?.getOrNull(1)),
                    description = paramParts?.getOrNull(1) ?: ""
                )
            }

            "method" -> {
                // 解析方法声明 @method Class.methodName(param1: type1, param2: type2): returnType
                val methodInfo = parts.getOrNull(1) ?: return null
                val methodMatch = Regex("""(\w+)\.(\w+)\((.*?)\)(?:\s*:\s*(.+))?""").find(methodInfo)
                if (methodMatch != null) {
                    val (className, methodName, params, returnTypeStr) = methodMatch.destructured

                    val parameters = if (params.isNotBlank()) {
                        params.split(",").map { param ->
                            val (name, type) = param.trim().split(":").map { it.trim() }
                            ParameterType(name, parseType(type))
                        }
                    } else emptyList()

                    val returnType = if (returnTypeStr.isNotBlank()) {
                        parseType(returnTypeStr)
                    } else PrimitiveType.NIL

                    val methodType = FunctionType(parameters, returnType)
                    addClassMethod(className, methodName, methodType)

                    return MethodTag(
                        className = className,
                        name = methodName,
                        type = methodType,
                        description = ""
                    )
                }
                null
            }

            "return" -> ReturnTag(
                type = parseType(parts.getOrNull(1)),
                description = parts.getOrNull(1) ?: ""
            )

            "generic" -> GenericTag(
                name = parts.getOrNull(1)?.split(Regex("\\s+"))?.get(0) ?: "",
                constraint = parts.getOrNull(1)?.split(Regex("\\s+"))?.getOrNull(1)?.let { parseType(it) }
            )

            "type" -> TypeTag(
                type = parseType(parts.getOrNull(1)),
                description = parts.getOrNull(1) ?: ""
            )

            "class" -> ClassTag(
                name = parts.getOrNull(1)?.split(Regex("\\s+"))?.get(0) ?: "",
                description = parts.getOrNull(1) ?: ""
            )

            "field" -> FieldTag(
                name = parts.getOrNull(1)?.split(Regex("\\s+"))?.get(0) ?: "",
                type = parseType(parts.getOrNull(1)?.split(Regex("\\s+"))?.getOrNull(1)),
                description = parts.getOrNull(1)?.split(Regex("\\s+"), 3)?.getOrNull(2) ?: ""
            )

            else -> null
        }
    }

    private fun parseType(typeStr: String?): Type {
        if (typeStr == null) return PrimitiveType.ANY
        return typeAnnotationParser.parse(typeStr)
    }

    // 获取节点的类型注释
    fun findTypeAnnotation(node: BaseASTNode): Type? {
        val nodeLine = node.range.start.line

        // 向上查找最近的类型注释
        var currentLine = nodeLine - 1
        while (currentLine > 0) {
            val docComment = docComments[currentLine]
            if (docComment != null) {
                // 查找类型标签
                val typeTag = docComment.tags.firstOrNull { it is TypeTag } as? TypeTag
                if (typeTag != null) {
                    return typeTag.type
                }
            }

            val comments = commentsByLine[currentLine]
            if (comments != null) {
                // 在当前行的注释中查找类型注释
                val typeComment = comments.findLast { comment ->
                    comment.comment.trim().startsWith("---@type")
                }

                if (typeComment != null) {
                    val type = typeComment.comment.trim()
                        .removePrefix("---@type")
                        .trim()
                    return parseType(type)
                }

                // 如果这一行有非类型注释，说明类型注释序列已经中断，停止查找
                if (comments.any { !it.comment.trim().startsWith("---@") }) {
                    break
                }
            }

            // 如果这一行没有注释，且与节点行相差超过1行，停止查找
            if (comments == null && nodeLine - currentLine > 1) {
                break
            }

            currentLine--
        }

        return null
    }

    // 获取函数的文档注释
    fun getFunctionDocComment(node: FunctionDeclaration): DocComment? {
        val nodeLine = node.range.start.line
        return docComments[nodeLine - 1]
    }

    // 获取节点紧邻的所有注释
    fun getAdjacentComments(node: BaseASTNode): List<CommentStatement> {
        val nodeLine = node.range.start.line
        val result = mutableListOf<CommentStatement>()

        // 收集紧邻的上方注释
        var currentLine = nodeLine - 1
        while (currentLine > 0) {
            val comments = commentsByLine[currentLine]
            if (comments != null) {
                result.addAll(0, comments)
            } else if (nodeLine - currentLine > 1) {
                // 如果出现空行，停止收集
                break
            }
            currentLine--
        }

        return result
    }
}

// 文档注释相关的数据类
data class DocComment(
    val description: String,
    val tags: List<DocTag>
)

sealed class DocTag {
    abstract val name: String
    abstract var description: String
}

data class ParamTag(
    override val name: String,
    val type: Type,
    override var description: String
) : DocTag()

data class ReturnTag(
    val type: Type,
    override val name: String = "return",
    override var description: String
) : DocTag()

data class GenericTag(
    override val name: String,
    val constraint: Type?,
    override var description: String = ""
) : DocTag()

data class TypeTag(
    val type: Type,
    override val name: String = "type",
    override var description: String
) : DocTag()

data class ClassTag(
    override val name: String,
    override var description: String
) : DocTag()

data class FieldTag(
    override val name: String,
    val type: Type,
    override var description: String
) : DocTag()

// 添加新的文档标签类型
data class MethodTag(
    val className: String,
    override val name: String,
    val type: FunctionType,
    override var description: String
) : DocTag() {
    val methodName = name
} 