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
     * when this type was materialized from a real declaration. Null = synthetic/overlay/bridged.
     * Informational only: binder ids are PER-DOCUMENT counters, not workspace identities, so
     * the id is EXCLUDED from equals/hashCode — otherwise a materialized ClassType and its
     * synthetic twin stop collapsing in union dedup (adversarial audit wave N).
     */
    val declarationId: Int? = null
) : Type {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClassType) return false
        // declarationId excluded: per-document counters are informational, and counting
        // them would stop same-named materialized/synthetic twins from collapsing in
        // union dedup (adversarial audit wave N).
        return name == other.name &&
            fields == other.fields &&
            methods == other.methods &&
            superClass == other.superClass &&
            superType == other.superType &&
            typeParameters == other.typeParameters &&
            alias == other.alias &&
            javaClassName == other.javaClassName
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + fields.hashCode()
        result = 31 * result + methods.hashCode()
        result = 31 * result + (superClass?.hashCode() ?: 0)
        result = 31 * result + (superType?.hashCode() ?: 0)
        result = 31 * result + typeParameters.hashCode()
        result = 31 * result + (alias?.hashCode() ?: 0)
        result = 31 * result + (javaClassName?.hashCode() ?: 0)
        return result
    }

    fun getAllFields(): Map<String, Type> = collectAll(mutableListOf()) { fields }

    fun getAllMethods(): Map<String, Type> = collectAll(mutableListOf()) { methods }

    private fun collectAll(
        visited: MutableList<ClassType>,
        own: ClassType.() -> Map<String, Type>
    ): Map<String, Type> {
        if (visited.any { it === this }) {
            return this.own()
        }
        visited += this
        val result = buildMap {
            superClass?.collectAll(visited, own)?.let(::putAll)
            putAll(this@ClassType.own())
        }
        visited.removeAt(visited.lastIndex)
        return result
    }
}
