package io.github.dingyi222666.luaparser.semantic.types.model

data class TableType(
    val fields: Map<String, Type> = emptyMap(),
    val methods: Map<String, Type> = emptyMap(),
    val indexSignature: IndexSignature? = null,
    override val name: String = buildTableTypeName(fields, methods, indexSignature)
) : Type {
    data class IndexSignature(
        val keyType: Type,
        val valueType: Type
    )
}

private fun buildTableTypeName(
    fields: Map<String, Type>,
    methods: Map<String, Type>,
    indexSignature: TableType.IndexSignature?
): String {
    if (fields.isEmpty() && methods.isEmpty() && indexSignature == null) {
        return "table"
    }

    val parts = buildList {
        fields.forEach { (fieldName, fieldType) ->
            add("$fieldName: ${fieldType.displayName}")
        }
        methods.forEach { (methodName, methodType) ->
            add("$methodName: ${methodType.displayName}")
        }
        indexSignature?.let {
            add("[${it.keyType.displayName}]: ${it.valueType.displayName}")
        }
    }

    return "{ ${parts.joinToString(", ")} }"
}
