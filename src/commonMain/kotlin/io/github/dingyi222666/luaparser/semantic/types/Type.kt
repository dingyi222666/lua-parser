package io.github.dingyi222666.luaparser.semantic.types

sealed interface Type {
    val name: String
    
    fun isAssignableFrom(other: Type): Boolean
    fun union(other: Type): Type
    fun intersection(other: Type): Type
}

// 基础类型
data class PrimitiveType(
    override val name: String,
    val kind: Kind
) : Type {
    enum class Kind {
        NIL,
        NUMBER,
        STRING, 
        BOOLEAN,
        THREAD,
        USERDATA,
        ANY
    }

    override fun isAssignableFrom(other: Type): Boolean {
        if (this == ANY) return true
        if (other == NIL) return true
        return this == other
    }

    override fun union(other: Type): Type = UnionType(setOf(this, other))
    override fun intersection(other: Type): Type = when {
        isAssignableFrom(other) -> other
        other.isAssignableFrom(this) -> this
        else -> NeverType
    }

    companion object {
        val NIL = PrimitiveType("nil", Kind.NIL)
        val NUMBER = PrimitiveType("number", Kind.NUMBER) 
        val STRING = PrimitiveType("string", Kind.STRING)
        val BOOLEAN = PrimitiveType("boolean", Kind.BOOLEAN)
        val THREAD = PrimitiveType("thread", Kind.THREAD)
        val USERDATA = PrimitiveType("userdata", Kind.USERDATA)
        val ANY = PrimitiveType("any", Kind.ANY)
    }
}

// 函数类型
data class FunctionType(
    val parameters: List<ParameterType>,
    val returnType: Type,
    override val name: String = "function"
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        if (other !is FunctionType) return false
        if (parameters.size != other.parameters.size) return false
        
        return parameters.zip(other.parameters).all { (a, b) -> 
            a.type.isAssignableFrom(b.type)
        } && returnType.isAssignableFrom(other.returnType)
    }

    override fun union(other: Type): Type = UnionType(setOf(this, other))
    override fun intersection(other: Type): Type = when {
        isAssignableFrom(other) -> other
        other.isAssignableFrom(this) -> this
        else -> NeverType
    }
}

// 表类型
data class TableType(
    val fields: Map<String, Type>,
    val indexSignature: IndexSignature? = null,
    override val name: String = "table"
) : Type {
    data class IndexSignature(
        val keyType: Type,
        val valueType: Type
    )

    override fun isAssignableFrom(other: Type): Boolean {
        if (other !is TableType) return false
        
        // 检查所有字段
        if (!fields.all { (key, type) ->
            other.fields[key]?.let { type.isAssignableFrom(it) } ?: false
        }) return false

        // 检查索引签名
        if (indexSignature != null) {
            if (other.indexSignature == null) return false
            if (!indexSignature.keyType.isAssignableFrom(other.indexSignature.keyType)) return false 
            if (!indexSignature.valueType.isAssignableFrom(other.indexSignature.valueType)) return false
        }

        return true
    }

    override fun union(other: Type): Type = UnionType(setOf(this, other))
    override fun intersection(other: Type): Type = when {
        isAssignableFrom(other) -> other
        other.isAssignableFrom(this) -> this
        else -> NeverType
    }
}

// 联合类型
data class UnionType(
    val types: Set<Type>,
    override val name: String = types.joinToString("|") { it.name }
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        return types.any { it.isAssignableFrom(other) }
    }

    override fun union(other: Type): Type = UnionType(types + other)
    override fun intersection(other: Type): Type = when(other) {
        is UnionType -> UnionType(types.intersect(other.types))
        else -> types.firstOrNull { it.isAssignableFrom(other) } ?: NeverType
    }
}

// Never类型
object NeverType : Type {
    override val name: String = "never"
    override fun isAssignableFrom(other: Type): Boolean = false
    override fun union(other: Type): Type = other
    override fun intersection(other: Type): Type = this
}

data class ParameterType(
    val name: String,
    val type: Type,
    val optional: Boolean = false,
    val vararg: Boolean = false
)

// 添加 VarArgType 类型
data class VarArgType(
    val types: List<Type>,
    override val name: String = "vararg<${types.joinToString(", ") { it.name }}>"
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        return when (other) {
            is VarArgType -> {
                if (types.size != other.types.size) return false
                types.zip(other.types).all { (a, b) -> a.isAssignableFrom(b) }
            }

            else -> false
        }
    }

    override fun union(other: Type): Type = UnionType(setOf(this, other))

    override fun intersection(other: Type): Type = when {
        isAssignableFrom(other) -> other
        other.isAssignableFrom(this) -> this
        else -> NeverType
    }
}

// 泛型类型
data class GenericType(
    val baseName: String,
    val typeParameters: List<Type>,
    override val name: String = "$baseName<${typeParameters.joinToString(", ") { it.name }}>"
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        if (other !is GenericType) return false
        if (baseName != other.baseName) return false
        if (typeParameters.size != other.typeParameters.size) return false
        
        return typeParameters.zip(other.typeParameters).all { (a, b) ->
            a.isAssignableFrom(b)
        }
    }

    override fun union(other: Type): Type = UnionType(setOf(this, other))
    override fun intersection(other: Type): Type = when {
        isAssignableFrom(other) -> other
        other.isAssignableFrom(this) -> this
        else -> NeverType
    }
}

// 数组类型
data class ArrayType(
    val elementType: Type,
    override val name: String = "${elementType.name}[]"
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        return when (other) {
            is ArrayType -> elementType.isAssignableFrom(other.elementType)
            // 特殊处理：允许将表类型赋值给数组类型，如果表的索引签名匹配
            is TableType -> {
                other.indexSignature?.let { signature ->
                    signature.keyType == PrimitiveType.NUMBER && 
                    elementType.isAssignableFrom(signature.valueType)
                } ?: false
            }
            else -> false
        }
    }

    override fun union(other: Type): Type = when (other) {
        is ArrayType -> ArrayType(elementType.union(other.elementType))
        else -> UnionType(setOf(this, other))
    }

    override fun intersection(other: Type): Type = when {
        isAssignableFrom(other) -> other
        other.isAssignableFrom(this) -> this
        other is ArrayType -> ArrayType(elementType.intersection(other.elementType))
        else -> NeverType
    }
}

// 自定义类型（用于处理未知的类型名称）
data class CustomType(
    override val name: String
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        return other is CustomType && name == other.name
    }

    override fun union(other: Type): Type = UnionType(setOf(this, other))
    override fun intersection(other: Type): Type = when {
        isAssignableFrom(other) -> other
        other.isAssignableFrom(this) -> this
        else -> NeverType
    }
}

// 类类型
data class ClassType(
    override val name: String,
    val fields: Map<String, Type> = mapOf(),
    val methods: Map<String, FunctionType> = mapOf(),
    val parent: ClassType? = null,
    val typeParameters: List<Type> = emptyList()
) : Type {
    override fun isAssignableFrom(other: Type): Boolean {
        if (other is ClassType) {
            // 检查是否是同一个类或其子类
            var current: ClassType? = other
            while (current != null) {
                if (current.name == name) {
                    // 检查泛型参数
                    if (typeParameters.size != current.typeParameters.size) return false
                    if (!typeParameters.zip(current.typeParameters).all { (a, b) -> 
                        a.isAssignableFrom(b) 
                    }) return false
                    return true
                }
                current = current.parent
            }
        }
        return false
    }

    override fun union(other: Type): Type = UnionType(setOf(this, other))
    
    override fun intersection(other: Type): Type = when {
        isAssignableFrom(other) -> other
        other.isAssignableFrom(this) -> this
        else -> NeverType
    }

    // 获取类的所有字段（包括继承的）
    fun getAllFields(): Map<String, Type> {
        val allFields = mutableMapOf<String, Type>()
        var current: ClassType? = this
        while (current != null) {
            allFields.putAll(current.fields)
            current = current.parent
        }
        return allFields
    }

    // 获取类的所有方法（包括继承的）
    fun getAllMethods(): Map<String, FunctionType> {
        val allMethods = mutableMapOf<String, FunctionType>()
        var current: ClassType? = this
        while (current != null) {
            allMethods.putAll(current.methods)
            current = current.parent
        }
        return allMethods
    }
} 