package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot

class JvmWorkspaceEngine(
    parserFactory: () -> LuaParser = { LuaParser() },
    private val classModuleProvider: JvmClassModuleProvider = JvmClassModuleProvider(),
    private val configuration: JvmWorkspaceConfiguration = JvmWorkspaceConfiguration()
) : LuaWorkspaceEngine(parserFactory) {
    override fun extraProviders(input: LuaWorkspaceInput): Map<io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val resolvedConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        val sourceImportedClasses = collectSourceImportedClasses(input, resolvedConfiguration)
        val providerConfiguration = if (sourceImportedClasses.isEmpty()) {
            resolvedConfiguration
        } else {
            resolvedConfiguration.copy(
                classes = (resolvedConfiguration.classes + sourceImportedClasses).toCollection(linkedSetOf())
            )
        }
        return classModuleProvider.providersFor(providerConfiguration)
    }

    internal override fun workspaceContext(input: LuaWorkspaceInput, path: io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath, snapshot: WorkspaceSnapshot): SemanticWorkspaceContext {
        val resolvedConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        val importedSymbols = collectImportedSymbols(input, resolvedConfiguration)
        return SemanticWorkspaceContext(
            currentPath = path,
            workspaceResolver = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver(snapshot),
            overlayGlobals = snapshot.builtinOverlay.globals,
            importedSymbols = importedSymbols,
            resolveImportedSymbol = { name -> importedSymbols[name] },
            resolveImportTarget = { target -> classModuleProvider.importedSymbol(target, resolvedConfiguration) }
        )
    }

    private fun collectImportedSymbols(
        input: LuaWorkspaceInput,
        configuration: JvmWorkspaceConfiguration
    ): Map<String, WorkspaceImportedSymbol> {
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        input.files.values.forEach { source ->
            parseSourceImports(source).forEach { importTarget ->
                classModuleProvider.importedClassNames(importTarget, configuration)
                    .mapNotNull { classModuleProvider.importedSymbol(it, configuration) }
                    .forEach { imported[it.alias] = it }
            }
        }
        return imported
    }

    private fun collectSourceImportedClasses(
        input: LuaWorkspaceInput,
        configuration: JvmWorkspaceConfiguration
    ): Set<String> {
        return buildSet {
            input.files.values.forEach { source ->
                parseSourceImports(source).forEach { importTarget ->
                    classModuleProvider.importedClassNames(importTarget, configuration)
                        .forEach(::add)
                }
                parseDynamicImportTargets(source)
                    .mapNotNull { classModuleProvider.importedClassName(it, configuration) }
                    .forEach(::add)
                parseBindClassTargets(source)
                    .mapNotNull { classModuleProvider.importedClassName(it, configuration) }
                    .forEach(::add)
            }
        }
    }

    private fun parseSourceImports(source: String): List<String> {
        return source.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("import ") }
            .mapNotNull(::parseImportTarget)
            .toList()
    }

    private fun parseBindClassTargets(source: String): List<String> {
        return BIND_CLASS_PATTERN.findAll(source)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun parseDynamicImportTargets(source: String): List<String> {
        val aliases = REQUIRE_IMPORT_ALIAS_PATTERN.findAll(source)
            .map { it.groupValues[1] }
            .toSet() + "import"
        if (aliases.isEmpty()) {
            return emptyList()
        }
        return DYNAMIC_IMPORT_CALL_PATTERN.findAll(source)
            .filter { it.groupValues[1] in aliases }
            .map { it.groupValues[2] }
            .toList()
    }

    private fun parseImportTarget(line: String): String? {
        val raw = line.removePrefix("import").trim()
        if (raw.isEmpty()) {
            return null
        }
        return raw.removeSurrounding("\"", "\"").removeSurrounding("'", "'").trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private val BIND_CLASS_PATTERN = Regex("""luajava\.bindClass\(\s*["']([^"']+)["']\s*\)""")
        private val REQUIRE_IMPORT_ALIAS_PATTERN = Regex("""\blocal\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*require\(\s*[\"']import[\"']\s*\)""")
        private val DYNAMIC_IMPORT_CALL_PATTERN = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*[\"']([^\"']+)[\"']\s*\)""")
    }
}
