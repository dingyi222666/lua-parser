package io.github.dingyi222666.luaparser.semantic.types

class TypeAnnotationParser {
    // 存储已定义的类
    private val classes = mutableMapOf<String, ClassType>()

    fun parse(input: String): Type {
        val trimmed = input.trim()
        
        // 基础类型处理
        when {
            trimmed.startsWith("overload") -> {
                // 移除 overload 前缀并解析剩余部分
                return parse(trimmed.substring("overload".length).trim())
            }
            trimmed == "string" -> return PrimitiveType.STRING
            trimmed == "number" -> return PrimitiveType.NUMBER
            trimmed == "boolean" -> return PrimitiveType.BOOLEAN
            trimmed == "nil" -> return PrimitiveType.NIL
            trimmed == "any" -> return PrimitiveType.ANY
            trimmed == "void" -> return PrimitiveType.NIL
        }

        // 检查是否是已定义的类
        classes[trimmed]?.let { return it }

        // 函数类型处理 - 要在泛型处理之前
        if (trimmed.startsWith("fun")) {
            return parseFunctionType(trimmed)
        }

        // 联合类型处理 - 要在泛型处理之前
        if (trimmed.contains("|")) {
            return parseUnionType(trimmed)
        }

        // 泛型处理
        if (trimmed.contains("<")) {
            return parseGenericType(trimmed)
        }

        // 数组类型
        if (trimmed.endsWith("[]")) {
            val elementType = parse(trimmed.substring(0, trimmed.length - 2))
            return ArrayType(elementType)
        }

        return CustomType(trimmed)
    }

    private fun parseGenericType(input: String): Type {
        var depth = 0
        var start = input.indexOf("<") + 1
        val name = input.substring(0, start - 1).trim()
        val params = mutableListOf<Type>()
        var current = start
        
        while (current < input.length) {
            when (input[current]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth < 0) {
                        if (current > start) {
                            params.add(parse(input.substring(start, current).trim()))
                        }
                        break
                    }
                }
                ',' -> {
                    if (depth == 0) {
                        params.add(parse(input.substring(start, current).trim()))
                        start = current + 1
                    }
                }
            }
            current++
        }
        
        // 检查是否是类的泛型实例
        classes[name]?.let { classType ->
            return ClassType(
                name = classType.name,
                fields = classType.fields,
                methods = classType.methods,
                parent = classType.parent,
                typeParameters = params
            )
        }
        
        return GenericType(name, params)
    }

    private fun parseFunctionType(input: String): FunctionType {
        try {
            // 移除 fun 前缀和周围的空格
            var remaining = input.substring(3).trim()
            
            // 解析参数列表
            val params = mutableListOf<ParameterType>()
            if (remaining.startsWith("(")) {
                val paramEnd = findMatchingParenthesis(remaining)
                if (paramEnd == -1) {
                    throw IllegalArgumentException("Unmatched parentheses in function type")
                }
                
                val paramStr = remaining.substring(1, paramEnd).trim()
                if (paramStr.isNotEmpty()) {
                    params.addAll(parseParameters(paramStr))
                }
                
                remaining = remaining.substring(paramEnd + 1).trim()
            }
            
            // 解析返回类型
            var returnType: Type = PrimitiveType.NIL
            if (remaining.startsWith(":")) {
                returnType = parse(remaining.substring(1).trim())
            }
            
            return FunctionType(params, returnType)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid function type: $input", e)
        }
    }

    private fun parseParameters(paramStr: String): List<ParameterType> {
        if (paramStr.isEmpty()) return emptyList()
        
        val params = mutableListOf<ParameterType>()
        var depth = 0
        var start = 0
        var current = 0
        
        while (current < paramStr.length) {
            when (paramStr[current]) {
                '<' -> depth++
                '>' -> depth--
                '|' -> if (depth == 0) depth = depth // 忽略联合类型中的 |
                ',' -> {
                    if (depth == 0) {
                        parseParameter(paramStr.substring(start, current))?.let { params.add(it) }
                        start = current + 1
                    }
                }
            }
            current++
        }
        
        // 处理最后一个参数
        if (start < paramStr.length) {
            parseParameter(paramStr.substring(start))?.let { params.add(it) }
        }
        
        return params
    }

    private fun parseParameter(paramStr: String): ParameterType? {
        val trimmed = paramStr.trim()
        if (trimmed.isEmpty()) return null
        
        val parts = trimmed.split(":")
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid parameter format: $paramStr")
        }
        
        val name = parts[0].trim()
        val type = parse(parts[1].trim())
        return ParameterType(name, type)
    }

    private fun findMatchingParenthesis(input: String): Int {
        var depth = 0
        input.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    fun defineClass(
        name: String,
        fields: Map<String, Type> = mapOf(),
        methods: Map<String, FunctionType> = mapOf(),
        parent: String? = null,
        typeParameters: List<Type> = emptyList()
    ): ClassType {
        val parentClass = parent?.let { classes[it] }
        val classType = ClassType(
            name = name,
            fields = fields,
            methods = methods,
            parent = parentClass,
            typeParameters = typeParameters
        )
        classes[name] = classType
        return classType
    }

    private fun parseUnionType(input: String): Type {
        var depth = 0
        var start = 0
        val types = mutableSetOf<Type>()
        
        for (i in input.indices) {
            when (input[i]) {
                '<' -> depth++
                '>' -> depth--
                '|' -> {
                    if (depth == 0) {
                        val typeStr = input.substring(start, i).trim()
                        if (typeStr.isNotEmpty()) {
                            types.add(parse(typeStr))
                        }
                        start = i + 1
                    }
                }
            }
        }
        
        // 处理最后一个类型
        val lastType = input.substring(start).trim()
        if (lastType.isNotEmpty()) {
            types.add(parse(lastType))
        }
        
        return UnionType(types)
    }
} 