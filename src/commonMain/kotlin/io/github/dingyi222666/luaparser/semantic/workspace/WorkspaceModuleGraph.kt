package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.Range

data class WorkspaceModuleGraph(
    val activeProviders: Map<String, ModuleProvider> = emptyMap(),
    val providersByModuleName: Map<String, List<ModuleProvider>> = emptyMap(),
    val providerConflicts: Map<String, List<ModuleProvider>> = emptyMap(),
    val resolvedDependencies: Map<VirtualPath, List<ResolvedDependency>> = emptyMap(),
    val unresolvedStaticRequires: Map<VirtualPath, List<UnresolvedRequire>> = emptyMap(),
    val dynamicRequireSites: Map<VirtualPath, List<DynamicRequireSite>> = emptyMap(),
    val reverseDependencies: Map<VirtualPath, Set<VirtualPath>> = emptyMap(),
    val stronglyConnectedComponents: List<Set<VirtualPath>> = emptyList(),
    val stronglyConnectedComponentByFile: Map<VirtualPath, Set<VirtualPath>> = emptyMap()
) {
    data class ModuleProvider(
        val moduleName: String,
        val path: VirtualPath,
        val source: ProviderSource
    )

    enum class ProviderSource {
        LEGACY_TOP_LEVEL,
        VIRTUAL_PATH,
        STANDARD_LIBRARY_OVERLAY
    }

    data class ResolvedDependency(
        val consumerPath: VirtualPath,
        val moduleName: String,
        val provider: ModuleProvider,
        val range: Range
    )

    data class UnresolvedRequire(
        val consumerPath: VirtualPath,
        val moduleName: String,
        val range: Range
    )

    data class DynamicRequireSite(
        val consumerPath: VirtualPath,
        val fact: DocumentFacts.DynamicRequireFact
    )

    companion object {
        val EMPTY = WorkspaceModuleGraph()
    }
}
