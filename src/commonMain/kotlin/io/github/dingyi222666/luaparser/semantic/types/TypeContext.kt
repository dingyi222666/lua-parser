package io.github.dingyi222666.luaparser.semantic.types

class TypeContext(
    private val parent: TypeContext? = null
) {
    private val types = mutableMapOf<String, Type>()
    
    fun defineType(name: String, type: Type) {
        types[name] = type
    }
    
    fun getType(name: String): Type {
        return types[name] ?: parent?.getType(name) ?: PrimitiveType.ANY
    }
    
    fun hasType(name: String): Boolean {
        return types.containsKey(name) || (parent?.hasType(name) ?: false)
    }
} 