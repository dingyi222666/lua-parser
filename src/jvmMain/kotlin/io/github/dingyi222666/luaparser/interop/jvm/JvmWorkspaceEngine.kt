package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot

class JvmWorkspaceEngine(
    private val workspaceParserFactory: () -> LuaParser = { LuaParser() },
    private val classModuleProvider: JvmClassModuleProvider = JvmClassModuleProvider(),
    private val configuration: JvmWorkspaceConfiguration = JvmWorkspaceConfiguration()
) : LuaWorkspaceEngine(workspaceParserFactory) {
    override fun extraProviders(input: LuaWorkspaceInput): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val baseConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        val documentFacts = collectDocumentFacts(input)
        val resolvedConfiguration = configurationWithWildcardImportPrefixes(baseConfiguration, documentFacts)
        val sourceImportedClasses = collectSourceImportedClasses(documentFacts, resolvedConfiguration)
        val packageProviders = classModuleProvider.packageProvidersFor(collectWildcardImportTargets(documentFacts), resolvedConfiguration)
        val providerConfiguration = if (sourceImportedClasses.isEmpty()) {
            resolvedConfiguration
        } else {
            resolvedConfiguration.copy(
                classes = (resolvedConfiguration.classes + sourceImportedClasses).toCollection(linkedSetOf())
            )
        }
        return classModuleProvider.providersFor(providerConfiguration) + packageProviders
    }

    internal override fun workspaceContext(input: LuaWorkspaceInput, path: io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath, snapshot: WorkspaceSnapshot): SemanticWorkspaceContext {
        val baseConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        val documentFacts = collectDocumentFacts(input)
        val resolvedConfiguration = configurationWithWildcardImportPrefixes(baseConfiguration, documentFacts)
        val importedSymbols = collectImportedSymbols(documentFacts, resolvedConfiguration)
        return SemanticWorkspaceContext(
            currentPath = path,
            workspaceResolver = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver(snapshot),
            overlayGlobals = snapshot.builtinOverlay.globals,
            importedSymbols = importedSymbols,
            resolveImportedSymbol = { name ->
                importedSymbols[name] ?: classModuleProvider.importedSymbol(name, resolvedConfiguration)
            },
            resolveImportTarget = { target -> classModuleProvider.importedSymbolForTarget(target, resolvedConfiguration) }
        )
    }

    private fun collectImportedSymbols(
        documentFacts: Map<VirtualPath, DocumentFacts>,
        configuration: JvmWorkspaceConfiguration
    ): Map<String, WorkspaceImportedSymbol> {
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        documentFacts.values.forEach { facts ->
            facts.sourceImports.forEach { importFact ->
                classModuleProvider.importedClassNames(importFact.target, configuration)
                    .mapNotNull { classModuleProvider.importedSymbol(it, configuration) }
                    .forEach { imported[it.alias] = it }
            }
        }
        return imported
    }

    private fun collectSourceImportedClasses(
        documentFacts: Map<VirtualPath, DocumentFacts>,
        configuration: JvmWorkspaceConfiguration
    ): Set<String> {
        return buildSet {
            documentFacts.values.forEach { facts ->
                facts.sourceImports.forEach { importFact ->
                    classModuleProvider.importedClassNames(importFact.target, configuration)
                        .forEach(::add)
                }
                facts.jvmClassLoads.forEach { fact ->
                    when (fact.kind) {
                        DocumentFacts.JvmClassLoadKind.IMPORT_CALL,
                        DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL,
                        DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL,
                        DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL,
                        DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL -> {
                            classModuleProvider.importedClassName(fact.target, configuration)
                                ?.let(::add)
                        }
                    }
                }
            }
        }
    }

    private fun collectWildcardImportTargets(documentFacts: Map<VirtualPath, DocumentFacts>): Set<String> {
        return buildSet {
            documentFacts.values.forEach { facts ->
                facts.sourceImports.forEach { importFact ->
                    if (wildcardImportPrefix(importFact.target) != null) {
                        add(importFact.target)
                    }
                }
            }
        }
    }

    private fun configurationWithWildcardImportPrefixes(
        configuration: JvmWorkspaceConfiguration,
        documentFacts: Map<VirtualPath, DocumentFacts>
    ): JvmWorkspaceConfiguration {
        val normalized = configuration.normalized()
        val wildcardPrefixes = linkedSetOf<String>()
        documentFacts.values.forEach { facts ->
            facts.sourceImports.forEach { importFact ->
                wildcardImportPrefix(importFact.target)?.let(wildcardPrefixes::add)
            }
        }
        if (wildcardPrefixes.isEmpty()) {
            return normalized
        }
        return normalized.copy(
            importPrefixes = (normalized.importPrefixes + wildcardPrefixes).distinct()
        )
    }

    private fun wildcardImportPrefix(importText: String): String? {
        val normalized = importText.removePrefix("import ").trim()
        if (!normalized.endsWith(".*")) {
            return null
        }
        return normalized.substringAfter(':', normalized).removeSuffix(".*").takeIf(String::isNotBlank)
    }

    private fun collectDocumentFacts(input: LuaWorkspaceInput): Map<VirtualPath, DocumentFacts> {
        return input.files.mapValues { (path, source) ->
            DocumentFactsCollector.collect(path, workspaceParserFactory().parse(source))
        }
    }

}
