package io.github.dingyi222666.luaparser.semantic

import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type

internal fun mergeWorkspaceGlobalExtension(baseType: Type, extensionType: Type): Type {
    val extensionFields = when (extensionType) {
        is TableType -> extensionType.fields
        is ModuleType -> extensionType.fields
        else -> return baseType
    }
    val extensionMethods = when (extensionType) {
        is TableType -> extensionType.methods
        is ModuleType -> extensionType.methods
        else -> emptyMap()
    }
    return when (baseType) {
        is TableType -> baseType.copy(
            fields = baseType.fields + extensionFields,
            methods = baseType.methods + extensionMethods
        )
        is ModuleType -> baseType.copy(
            fields = baseType.fields + extensionFields,
            methods = baseType.methods + extensionMethods
        )
        else -> extensionType
    }
}
