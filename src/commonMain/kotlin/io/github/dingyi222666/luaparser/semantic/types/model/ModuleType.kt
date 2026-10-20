package io.github.dingyi222666.luaparser.semantic.types.model

data class ModuleType(
    val moduleName: String,
    val fields: Map<String, Type> = emptyMap(),
    val methods: Map<String, Type> = emptyMap(),
    val indexSignature: IndexSignature? = null,
    override val name: String = moduleName
) : Type {
    data class IndexSignature(
        val keyType: Type,
        val valueType: Type
    )
}
