package io.github.dingyi222666.luaparser.lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class LuaWorkspaceService(
    private val languageService: LuaLanguageService
) : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        val settings = params.settings as? Map<*, *> ?: return
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
        languageService.setWorkspaceMetadata(configuration.applyToMetadata(metadata))
    }

    override fun didChangeWatchedFiles(@Suppress("UNUSED_PARAMETER") params: DidChangeWatchedFilesParams) {
    }

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<MutableList<out SymbolInformation>, MutableList<out WorkspaceSymbol>>> {
        return CompletableFuture.completedFuture(
            Either.forLeft(languageService.workspaceSymbols(params.query).toMutableList())
        )
    }

    private fun Map<*, *>.sectionMap(key: String): Map<*, *> {
        return this[key] as? Map<*, *> ?: emptyMap<Any, Any>()
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

            else -> emptyList()
        }
    }
}
