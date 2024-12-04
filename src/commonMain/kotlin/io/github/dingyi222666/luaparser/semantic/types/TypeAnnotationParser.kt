package io.github.dingyi222666.luaparser.semantic.types

class TypeAnnotationParser {
    // 存储已定义的类
    private val classes = mutableMapOf<String, ClassType>()

    fun parse(input: String): Type {
        // 基础类型处理
        when (input.trim()) {
            "string" -> return PrimitiveType.STRING
            "number" -> return PrimitiveType.NUMBER
            "boolean" -> return PrimitiveType.BOOLEAN
            "nil" -> return PrimitiveType.NIL
            "any" -> return PrimitiveType.ANY
        }

        // 检查是否是已定义的类
        classes[input.trim()]?.let { return it }

        // 泛型处理
        if (input.contains("<")) {
            return parseGenericType(input)
        }

        // 函数类型处理
        if (input.startsWith("fun")) {
            return parseFunctionType(input)
        }

        // 数组类型
        if (input.endsWith("[]")) {
            val elementType = parse(input.substring(0, input.length - 2))
            return ArrayType(elementType)
        }

        // 联合类型
        if (input.contains("|")) {
            val types = input.split("|").map { parse(it.trim()) }.toSet()
            return UnionType(types)
        }

        return CustomType(input)
    }

    // 添加新方法用于注册类定义
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

    private fun parseGenericType(input: String): Type {
        val name = input.substring(0, input.indexOf("<"))
        val params = input.substring(input.indexOf("<") + 1, input.lastIndexOf(">"))
            .split(",")
            .map { parse(it.trim()) }
        
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
        // Parse function type with parameters and return type
        // Example: fun(x: number, y: string): boolean
        val paramStart = input.indexOf("(")
        val paramEnd = input.indexOf(")")
        val params = input.substring(paramStart + 1, paramEnd)
            .split(",")
            .map { param ->
                val parts = param.trim().split(":")
                ParameterType(
                    name = parts[0].trim(),
                    type = parse(parts[1].trim())
                )
            }
        
        val returnType = if (input.contains(":")) {
            parse(input.substring(input.indexOf(":") + 1).trim())
        } else {
            PrimitiveType.NIL
        }

        return FunctionType(params, returnType)
    }
} 