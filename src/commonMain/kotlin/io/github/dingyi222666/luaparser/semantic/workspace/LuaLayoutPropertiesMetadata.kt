package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.semantic.checker.LuaLayoutPropertySuggestion

/**
 * Embedder extension point for AndroLua layout-table completions: workspace metadata under
 * [METADATA_KEY] contributes extra Lua property keys for view classes the reflection surface
 * cannot describe (project-defined custom views, AndroLua adapter helpers, ...).
 *
 * Line format (`#` comments and blank lines ignored):
 * ```
 * LuaRecyclerView: refresh|pull-to-refresh callback, loadMore|load-more callback
 * MyBanner: autoScroll|boolean, bannerWidth
 * ```
 * Each line is `ClassName: prop[|detail][, prop[|detail]]...`. The class name matches the
 * identifier written in the layout table or the class's Java simple name.
 */
object LuaLayoutPropertiesMetadata {
    const val METADATA_KEY = "lua.layout.properties"

    fun parse(metadata: Map<String, String>): Map<String, List<LuaLayoutPropertySuggestion>> {
        val raw = metadata[METADATA_KEY] ?: return emptyMap()
        val result = linkedMapOf<String, List<LuaLayoutPropertySuggestion>>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                return@forEach
            }
            val separator = trimmed.indexOf(':')
            if (separator <= 0) {
                return@forEach
            }
            val className = trimmed.substring(0, separator).trim()
            if (className.isEmpty()) {
                return@forEach
            }
            val suggestions = trimmed.substring(separator + 1)
                .split(',')
                .mapNotNull { entry ->
                    val label = entry.substringBefore('|').trim()
                    if (label.isEmpty()) {
                        return@mapNotNull null
                    }
                    LuaLayoutPropertySuggestion(label, entry.substringAfter('|', "").trim()
                        .ifEmpty { null })
                }
            if (suggestions.isNotEmpty()) {
                result[className] = suggestions
            }
        }
        return result
    }
}
