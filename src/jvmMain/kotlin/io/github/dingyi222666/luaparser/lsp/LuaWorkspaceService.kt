package io.github.dingyi222666.luaparser.lsp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class LuaWorkspaceService(
    private val languageService: LuaLanguageService,
    private val acceptsRequests: () -> Boolean = { true },
    private val onConfigurationChanged: () -> Unit = {}
) : WorkspaceService {
    private val stateLock = Any()
    private var workspaceMetadata: Map<String, String> = emptyMap()
    private var watchedFileChanges: List<FileEvent> = emptyList()

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        if (!acceptsRequests()) {
            return
        }
        val metadata = parseWorkspaceMetadata(params.settings) ?: return
        synchronized(stateLock) {
            if (metadata == workspaceMetadata) {
                return
            }
            workspaceMetadata = metadata.toMap()
            languageService.setWorkspaceMetadata(workspaceMetadata)
        }
        onConfigurationChanged()
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        if (!acceptsRequests()) {
            return
        }
        val changes = params.changes.orEmpty().toList()
        synchronized(stateLock) {
            watchedFileChanges = changes
            languageService.applyWatchedFileChanges(changes)
        }
        // Republish open-document diagnostics after workspace snapshot updates
        // (create/change/delete can change require/module resolution for open files).
        onConfigurationChanged()
    }

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<MutableList<out SymbolInformation>, MutableList<out WorkspaceSymbol>>> {
        if (!acceptsRequests()) {
            return completedWorkspaceSymbols(mutableListOf())
        }
        val query = params.query.orEmpty()
        val symbols = synchronized(stateLock) {
            languageService.workspaceSymbols(query).toMutableList()
        }
        return completedWorkspaceSymbols(symbols)
    }

    fun currentWorkspaceMetadata(): Map<String, String> {
        return synchronized(stateLock) { workspaceMetadata.toMap() }
    }

    fun lastWatchedFileChanges(): List<FileEvent> {
        return synchronized(stateLock) { watchedFileChanges.toList() }
    }

    private fun parseWorkspaceMetadata(settingsValue: Any?): Map<String, String>? {
        val settings = settingsValue.settingsMap() ?: return null
        if (!settings.hasWorkspaceConfiguration()) {
            return null
        }
        val metadata = mutableMapOf<String, String>()
        val jvmSettings = settings.sectionMap("jvm")
        val androluaSettings = settings.sectionMap("androlua")

        val classes = settings.stringList("jvm.classes") + jvmSettings.stringList("classes")
        val imports = settings.stringList("androlua.imports") + androluaSettings.stringList("imports")
        val classpath = settings.stringList("jvm.classpath") + jvmSettings.stringList("classpath")
        val androidJar = settings.stringValue("jvm.androidJar") ?: jvmSettings.stringValue("androidJar")
        val importPrefixes = settings.stringList("jvm.importPrefixes") + jvmSettings.stringList("importPrefixes")

        val configuration = JvmWorkspaceConfiguration(
            classes = classes.toCollection(linkedSetOf()),
            androluaImports = imports.distinct(),
            classpathEntries = classpath.distinct(),
            androidJar = androidJar,
            importPrefixes = importPrefixes.distinct()
        )
        return configuration.applyToMetadata(metadata)
    }

    private fun Map<*, *>.hasWorkspaceConfiguration(): Boolean {
        return containsAny(WORKSPACE_CONFIGURATION_KEYS) ||
            sectionMap("jvm").containsAny(JVM_CONFIGURATION_KEYS) ||
            sectionMap("androlua").containsAny(ANDROLUA_CONFIGURATION_KEYS)
    }

    private fun Any?.settingsMap(): Map<*, *>? {
        return when (this) {
            is Map<*, *> -> this
            is JsonObject -> toPlainMap()
            is JsonElement -> takeIf(JsonElement::isJsonObject)?.asJsonObject?.toPlainMap()
            else -> null
        }
    }

    private fun JsonObject.toPlainMap(): Map<String, Any?> {
        return entrySet().associate { entry -> entry.key to entry.value.toPlainValue() }
    }

    private fun JsonElement.toPlainValue(): Any? {
        return when {
            isJsonNull -> null
            isJsonObject -> asJsonObject.toPlainMap()
            isJsonArray -> asJsonArray.map { it.toPlainValue() }
            isJsonPrimitive -> asJsonPrimitive.toPlainValue()
            else -> null
        }
    }

    private fun JsonPrimitive.toPlainValue(): Any {
        return when {
            isString -> asString
            isBoolean -> asBoolean
            isNumber -> asNumber
            else -> asString
        }
    }

    private fun Map<*, *>.sectionMap(key: String): Map<*, *> {
        return this[key] as? Map<*, *> ?: emptyMap<Any, Any>()
    }

    private fun Map<*, *>.containsAny(keys: Collection<String>): Boolean {
        return keys.any { key -> containsKey(key) }
    }

    private fun Map<*, *>.stringValue(key: String): String? {
        return (this[key] as? String)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun Map<*, *>.stringList(key: String): List<String> {
        val value = this[key] ?: return emptyList()
        return when (value) {
            is String -> value
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()

            is List<*> -> value.filterIsInstance<String>()
                .map(String::trim)
                .filter(String::isNotEmpty)

            is Array<*> -> value.filterIsInstance<String>()
                .map(String::trim)
                .filter(String::isNotEmpty)

            else -> emptyList()
        }
    }

    private fun completedWorkspaceSymbols(
        symbols: MutableList<out SymbolInformation>
    ): CompletableFuture<Either<MutableList<out SymbolInformation>, MutableList<out WorkspaceSymbol>>> {
        return CompletableFuture.completedFuture(Either.forLeft(symbols))
    }

    private companion object {
        val WORKSPACE_CONFIGURATION_KEYS = setOf(
            "jvm.classes",
            "androlua.imports",
            "jvm.classpath",
            "jvm.androidJar",
            "jvm.importPrefixes"
        )
        val JVM_CONFIGURATION_KEYS = setOf("classes", "classpath", "androidJar", "importPrefixes")
        val ANDROLUA_CONFIGURATION_KEYS = setOf("imports")
    }
}
