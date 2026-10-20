package io.github.dingyi222666.luaparser.semantic.types.model

sealed interface Type {
    val name: String

    val displayName: String
        get() = name
}

fun Type.unwrapAliases(): Type {
    var current: Type = this
    val visited = linkedSetOf<AliasType>()

    while (current is AliasType) {
        if (!visited.add(current)) {
            return current
        }
        current = current.target
    }

    return current
}
