package io.github.dingyi222666.luaparser.semantic.workspace.std

internal actual object BuiltinOverlayResourceAccess {
    actual fun readText(resourcePath: String): String {
        val stream = BuiltinOverlayResourceAccess::class.java.getResourceAsStream(resourcePath)
            ?: error("Builtin overlay resource not found: $resourcePath")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
