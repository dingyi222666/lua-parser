package io.github.dingyi222666.luaparser.semantic.workspace.std

internal expect object BuiltinOverlayResourceAccess {
    fun readText(resourcePath: String): String
}
