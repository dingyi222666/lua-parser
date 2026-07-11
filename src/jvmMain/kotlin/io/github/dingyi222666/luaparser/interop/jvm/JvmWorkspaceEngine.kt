package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.UnresolvedLuaJavaTarget
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
        val sourceDiscoveredClasses = collectSourceDiscoveredClasses(documentFacts, resolvedConfiguration)
        val packageProviders = classModuleProvider.packageProvidersFor(
            collectWildcardImportTargets(baseConfiguration, documentFacts),
            resolvedConfiguration
        )
        val providerConfiguration = if (sourceDiscoveredClasses.isEmpty()) {
            resolvedConfiguration
        } else {
            resolvedConfiguration.copy(
                classes = (resolvedConfiguration.classes + sourceDiscoveredClasses).toCollection(linkedSetOf())
            )
        }
        return classModuleProvider.providersFor(providerConfiguration) + packageProviders
    }

    internal override fun workspaceContext(input: LuaWorkspaceInput, path: io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath, snapshot: WorkspaceSnapshot): SemanticWorkspaceContext {
        val baseConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        val documentFacts = collectDocumentFacts(input)
        val currentFacts = snapshot.files[path]?.documentFacts ?: documentFacts[path]
        val resolvedConfiguration = configurationWithWildcardImportPrefixes(
            baseConfiguration,
            currentFacts?.let { mapOf(path to it) }.orEmpty()
        )
        // Configured imports are workspace-wide; source imports stay scoped to the current file.
        val configuredImports = collectConfiguredImports(baseConfiguration)
        val sourceImports = collectSourceImports(currentFacts, resolvedConfiguration)
        val activeImports = linkedMapOf<String, WorkspaceImportedSymbol>().apply {
            putAll(configuredImports)
            putAll(sourceImports)
        }
        return SemanticWorkspaceContext(
            currentPath = path,
            workspaceResolver = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver(snapshot),
            overlayGlobals = snapshot.builtinOverlay.globals,
            // Only base configured imports go in the context map; document source imports are
            // re-applied by SemanticWorkspaceContext.withWorkspaceImportEffects() for this path.
            // Keep activeImports here so current-file analysis already sees both layers.
            importedSymbols = activeImports,
            resolveImportedSymbol = { name ->
                activeImports[name]
            },
            // Dynamic import()/import "..." targets must resolve even when not yet on the
            // sourceImports activation set for this path (engine-level JVM resolution).
            resolveImportTarget = { target ->
                classModuleProvider.importedSymbolForTarget(target, resolvedConfiguration)
            },
            unresolvedLuaJavaTargets = collectUnresolvedLuaJavaTargets(currentFacts, resolvedConfiguration)
        )
    }

    private fun collectUnresolvedLuaJavaTargets(
        facts: DocumentFacts?,
        configuration: JvmWorkspaceConfiguration
    ): List<UnresolvedLuaJavaTarget> {
        if (facts == null) {
            return emptyList()
        }
        return facts.jvmClassLoads
            .asSequence()
            .filter { it.kind in diagnosticLuaJavaClassLoadKinds }
            .filter { classModuleProvider.importedClassName(it.target, configuration) == null }
            .map { fact ->
                UnresolvedLuaJavaTarget(
                    target = fact.target,
                    helperName = luaJavaHelperName(fact.kind),
                    range = fact.range
                )
            }
            .distinctBy { target ->
                listOf(
                    target.range?.start?.line,
                    target.range?.start?.column,
                    target.range?.end?.line,
                    target.range?.end?.column,
                    target.helperName,
                    target.target
                )
            }
            .toList()
    }

    private fun collectConfiguredImports(
        configuration: JvmWorkspaceConfiguration
    ): Map<String, WorkspaceImportedSymbol> {
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        configuration.normalized().androluaImports.forEach { importText ->
            classModuleProvider.importedClassNames(importText, configuration)
                .mapNotNull { classModuleProvider.importedSymbol(it, configuration) }
                .forEach { imported[it.alias] = it }
        }
        return imported
    }

    private fun collectSourceImports(
        facts: DocumentFacts?,
        configuration: JvmWorkspaceConfiguration
    ): Map<String, WorkspaceImportedSymbol> {
        if (facts == null) {
            return emptyMap()
        }
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        facts.sourceImports.forEach { importFact ->
            classModuleProvider.importedClassNames(importFact.target, configuration)
                .mapNotNull { classModuleProvider.importedSymbol(it, configuration) }
                .forEach { imported[it.alias] = it }
        }
        return imported
    }

    private fun collectSourceDiscoveredClasses(
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

                        DocumentFacts.JvmClassLoadKind.CREATE_ARRAY_CALL -> Unit
                    }
                }
            }
        }
    }

    private fun collectWildcardImportTargets(
        configuration: JvmWorkspaceConfiguration,
        documentFacts: Map<VirtualPath, DocumentFacts>
    ): Set<String> {
        return buildSet {
            configuration.normalized().androluaImports.forEach { importText ->
                if (wildcardImportPrefix(importText) != null) {
                    add(importText)
                }
            }
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
        val wildcardPrefixes = linkedSetOf<String>().apply {
            normalized.androluaImports.forEach { importText ->
                wildcardImportPrefix(importText)?.let { add(it) }
            }
        }
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
            DocumentFactsCollector.collect(path, parseWorkspaceSource(source))
        }
    }

    private fun luaJavaHelperName(kind: DocumentFacts.JvmClassLoadKind): String {
        return when (kind) {
            DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL -> "bindClass"
            DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL -> "newInstance"
            DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL -> "createProxy"
            DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL -> "loadLib"
            DocumentFacts.JvmClassLoadKind.IMPORT_CALL,
            DocumentFacts.JvmClassLoadKind.CREATE_ARRAY_CALL -> kind.name
        }
    }

    private companion object {
        val diagnosticLuaJavaClassLoadKinds: Set<DocumentFacts.JvmClassLoadKind> = setOf(
            DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL,
            DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL,
            DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL,
            DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL
        )
    }

}
