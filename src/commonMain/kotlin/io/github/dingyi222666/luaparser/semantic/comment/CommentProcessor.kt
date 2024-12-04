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
                val line = stmt.range.end.line
                commentsByLine.getOrPut(line) { mutableListOf() }.add(stmt)

                // 如果是文档注释，解析它
                if (stmt.isDocComment) {
                    val docComment = parseDocComment(stmt, line)

                    // 处理类定义
                    docComment.tags.filterIsInstance<ClassTag>().forEach { classTag ->
                        // 收集类的字段和方法
                        val fields = mutableMapOf<String, Type>()
                        val methods = mutableMapOf<String, FunctionType>()

                        docComment.tags.forEach { tag ->
                            when (tag) {
                                is FieldTag -> fields[tag.name] = tag.type
                                is MethodTag -> methods[tag.methodName] = tag.type
                                is ClassTag -> TODO()
                                is GenericTag -> TODO()
                                is ParamTag -> TODO()
                                is ReturnTag -> TODO()
                                is TypeTag -> TODO()
                            }
                        }

                        // 注册类定义
                        typeAnnotationParser.defineClass(
                            name = classTag.name,
                            fields = fields,
                            methods = methods
                        )
                    }
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
                val paramTypeStr = paramParts?.getOrNull(1)
                
                // 处理参数类型，保留完整的类型字符串
                val paramType = if (paramTypeStr != null) {
                    parseType(paramTypeStr)
                } else PrimitiveType.ANY

                ParamTag(
                    name = paramName,
                    type = paramType,
                    description = paramParts?.getOrNull(1) ?: ""
                )
            }

            "method" -> {
                // 解析方法声明 @method Class.methodName(param1: type1, param2: type2): returnType
                val methodInfo = parts.getOrNull(1) ?: return null
                val methodMatch = Regex("""(\w+)\.(\w+)\((.*?)\)(?:\s*:\s*(.+))?"""").find(methodInfo)
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

            "return" -> {
                val returnTypeStr = parts.getOrNull(1)
                if (returnTypeStr != null) {
                    // 保留完整的返回类型字符串，不要分割
                    ReturnTag(
                        type = parseType(returnTypeStr),
                        description = returnTypeStr
                    )
                } else {
                    ReturnTag(
                        type = PrimitiveType.NIL,
                        description = ""
                    )
                }
            }

            "generic" -> {
                val genericParts = parts.getOrNull(1)?.split(Regex("\\s+"), 2)
                GenericTag(
                    name = genericParts?.getOrNull(0) ?: "",
                    constraint = genericParts?.getOrNull(1)?.let { parseType(it) },
                    description = ""
                )
            }

            "type" -> {
                val typeStr = parts.getOrNull(1)
                if (typeStr != null) {
                    val type = parseType(typeStr)
                    TypeTag(
                        type = type,
                        description = typeStr
                    )
                } else {
                    TypeTag(
                        type = PrimitiveType.ANY,
                        description = ""
                    )
                }
            }

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
        
        val trimmed = typeStr.trim()
        
        // 保持完整的类型字符串传递给 typeAnnotationParser
        return typeAnnotationParser.parse(trimmed)
    }

    // 获取函数的文档注释
    fun getFunctionDocComment(node: FunctionDeclaration): DocComment? {
        val nodeLine = node.range.start.line
        return docComments[nodeLine]
    }

    // 获取节点的类型注释
    fun findTypeAnnotation(node: BaseASTNode): Type? {
        val nodeLine = node.range.start.line
        var currentLine = nodeLine
        
        while (currentLine > 0) {
            // 先检查是否有文档注释
            val docComment = docComments[currentLine]
            if (docComment != null) {
                // 1. 检查是否有直接的类型标签
                val typeTag = docComment.tags.firstOrNull { it is TypeTag } as? TypeTag
                if (typeTag != null) {
                    return typeTag.type
                }

                // 2. 如果是函数声明，尝试从参数和返回类型标签构建函数类型
                if (node is FunctionDeclaration) {
                    val paramTags = docComment.tags.filterIsInstance<ParamTag>()
                    val returnTag = docComment.tags.firstOrNull { it is ReturnTag } as? ReturnTag
                    
                    if (paramTags.isNotEmpty() || returnTag != null) {
                        val params = paramTags.map { ParameterType(it.name, it.type) }
                        val returnType = returnTag?.type ?: PrimitiveType.NIL
                        return FunctionType(params, returnType)
                    }
                }
                
                break
            }

            // 然后检查是否有类型注释
            val comments = commentsByLine[currentLine]
            if (comments != null) {
                val typeComment = comments.findLast { comment ->
                    comment.comment.trim().startsWith("---@type")
                }

                if (typeComment != null) {
                    val type = typeComment.comment.trim()
                        .removePrefix("---@type")
                        .trim()
                    return typeAnnotationParser.parse(type)
                }

                // 如果有非类型注释，停止查找
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