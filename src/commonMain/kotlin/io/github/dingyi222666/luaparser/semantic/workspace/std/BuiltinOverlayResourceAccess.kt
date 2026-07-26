package io.github.dingyi222666.luaparser.semantic.workspace.std

/**
 * Reads builtin overlay files (Lua 5.3/5.4 stdlib, AndroLua, Android framework index).
 *
 * The overlay is authored as plain files under `src/commonMain/resources` and mirrored verbatim
 * into [BuiltinOverlayResourceMirror] by the `generateBuiltinOverlayMirror` Gradle task, so every
 * target — including the ones without classpath resource access — serves the identical overlay.
 */
internal object BuiltinOverlayResourceAccess {
    fun readText(resourcePath: String): String {
        val normalized = if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath"
        return BuiltinOverlayResourceMirror.resources[normalized]
            ?: error("Builtin overlay resource not found: $resourcePath")
    }
}
