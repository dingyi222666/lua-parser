package io.github.dingyi222666.luaparser.lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService

class LuaWorkspaceService(
    private val languageService: LuaLanguageService
) : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        val settings = params.settings as? Map<*, *> ?: return
        val metadata = mutableMapOf<String, String>()

        val classes = (settings["jvm.classes"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val imports = (settings["androlua.imports"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val classpath = (settings["jvm.classpath"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val androidJar = settings["jvm.androidJar"] as? String
        val importPrefixes = (settings["jvm.importPrefixes"] as? List<*>)?.filterIsInstance<String>().orEmpty()

        val configuration = JvmWorkspaceConfiguration(
            classes = classes.toCollection(linkedSetOf()),
            androluaImports = imports,
            classpathEntries = classpath,
            androidJar = androidJar,
            importPrefixes = importPrefixes
        )
        languageService.setWorkspaceMetadata(configuration.applyToMetadata(metadata))
    }

    override fun didChangeWatchedFiles(@Suppress("UNUSED_PARAMETER") params: DidChangeWatchedFilesParams) {
    }
}
