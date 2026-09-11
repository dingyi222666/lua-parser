package io.github.dingyi222666.luaparser.semantic.types.model

data class ClassType(
    override val name: String,
    val fields: Map<String, Type> = emptyMap(),
    val methods: Map<String, Type> = emptyMap(),
    val superClass: ClassType? = null,
    val superType: Type? = null,
    val typeParameters: List<TypeParameterType> = emptyList(),
    val alias: AliasType? = null,
    val javaClassName: String? = null,
    /**
     * Binder declaration id ([io.github.dingyi222666.luaparser.semantic.binder.DeclarationId.value])
     * when this type was materialized from a real declaration. Null = synthetic/overlay/bridged
     * (identity matching disabled; same-name ClassTypes are then matched by name only).
     */
    val declarationId: Int? = null
) : Type {
    fun getAllFields(): Map<String, Type> = collectAllFields(mutableListOf())

    fun getAllMethods(): Map<String, Type> = collectAllMethods(mutableListOf())

    private fun collectAllFields(visited: MutableList<ClassType>): Map<String, Type> {
        if (visited.any { it === this }) {
            return fields
        }
        visited += this
        val result = buildMap {
            superClass?.collectAllFields(visited)?.let(::putAll)
            putAll(fields)
        }
        visited.removeAt(visited.lastIndex)
        return result
    }

    private fun collectAllMethods(visited: MutableList<ClassType>): Map<String, Type> {
        if (visited.any { it === this }) {
            return methods
        }
        visited += this
        val result = buildMap {
            superClass?.collectAllMethods(visited)?.let(::putAll)
            putAll(methods)
        }
        visited.removeAt(visited.lastIndex)
        return result
    }
}
