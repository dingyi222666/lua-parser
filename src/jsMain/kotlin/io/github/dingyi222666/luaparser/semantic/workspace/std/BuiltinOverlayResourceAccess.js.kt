package io.github.dingyi222666.luaparser.semantic.workspace.std

internal actual object BuiltinOverlayResourceAccess {
    actual fun readText(resourcePath: String): String {
        val normalized = resourcePath.removePrefix("/")
        val segments = normalized.split('/')
        if (segments.size < 2) {
            error("Builtin overlay resource not found: $resourcePath")
        }

        val version = segments[segments.size - 2]
        val fileName = segments.last()
        val source = when (version) {
            "lua53" -> lua53Source(fileName)
            "lua54" -> lua54Source(fileName)
            else -> null
        } ?: error("Builtin overlay resource not found: $resourcePath")

        return source.trimIndent()
    }

    private fun lua53Source(fileName: String): String? = when (fileName) {
        "_G.lua" -> Lua53BuiltinOverlaySources.globalsSource
        else -> Lua53BuiltinOverlaySources.providerModuleSources[fileName.removeSuffix(".lua")]
    }

    private fun lua54Source(fileName: String): String? = when (fileName) {
        "_G.lua" -> Lua54BuiltinOverlaySources.globalsSource
        else -> Lua54BuiltinOverlaySources.providerModuleSources[fileName.removeSuffix(".lua")]
    }
}
