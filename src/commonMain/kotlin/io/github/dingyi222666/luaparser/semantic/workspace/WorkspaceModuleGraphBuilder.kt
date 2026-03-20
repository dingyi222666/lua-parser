package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot

object WorkspaceModuleGraphBuilder {
    fun build(
        files: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>,
        builtinOverlay: BuiltinOverlaySnapshot = BuiltinOverlaySnapshot.EMPTY,
        extraProviders: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> = emptyMap()
    ): WorkspaceModuleGraph {
        val providerClaims = linkedMapOf<String, MutableList<WorkspaceModuleGraph.ModuleProvider>>()
        val graphFiles = linkedMapOf<VirtualPath, WorkspaceSnapshot.FileSnapshot>()

        files.forEach { (path, snapshot) ->
            graphFiles[path] = snapshot
            providersFor(path, snapshot.documentFacts).forEach { provider ->
                providerClaims.getOrPut(provider.moduleName) { mutableListOf() } += provider
            }
        }

        builtinOverlay.providerModules.forEach { (path, overlayModule) ->
            graphFiles[path] = overlayModule.file
            providerClaims.getOrPut(overlayModule.moduleName) { mutableListOf() } += WorkspaceModuleGraph.ModuleProvider(
                moduleName = overlayModule.moduleName,
                path = path,
                source = WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY
            )
        }

        extraProviders.forEach { (path, snapshot) ->
            graphFiles[path] = snapshot
            snapshot.moduleExportSurface?.moduleType?.moduleName?.let { moduleName ->
                providerClaims.getOrPut(moduleName) { mutableListOf() } += WorkspaceModuleGraph.ModuleProvider(
                    moduleName = moduleName,
                    path = path,
                    source = WorkspaceModuleGraph.ProviderSource.EXTRA_WORKSPACE_PROVIDER
                )
            }
        }

        val providersByModuleName = providerClaims.mapValues { (_, providers) ->
            providers.distinctBy { it.path to it.moduleName }
                .sortedWith(providerComparator)
        }
        val activeProviders = providersByModuleName.mapValues { (_, providers) -> providers.first() }
        val providerConflicts = providersByModuleName
            .filterValues { it.size > 1 }

        val resolvedDependencies = linkedMapOf<VirtualPath, List<WorkspaceModuleGraph.ResolvedDependency>>()
        val unresolvedStaticRequires = linkedMapOf<VirtualPath, List<WorkspaceModuleGraph.UnresolvedRequire>>()
        val dynamicRequireSites = linkedMapOf<VirtualPath, List<WorkspaceModuleGraph.DynamicRequireSite>>()
        val reverseDependencies = linkedMapOf<VirtualPath, MutableSet<VirtualPath>>()

        graphFiles.forEach { (path, snapshot) ->
            val facts = snapshot.documentFacts
            if (facts == null) {
                return@forEach
            }

            val resolved = mutableListOf<WorkspaceModuleGraph.ResolvedDependency>()
            val unresolved = mutableListOf<WorkspaceModuleGraph.UnresolvedRequire>()
            facts.requires.forEach { requireFact ->
                val provider = activeProviders[requireFact.moduleName]
                if (provider == null) {
                    unresolved += WorkspaceModuleGraph.UnresolvedRequire(
                        consumerPath = path,
                        moduleName = requireFact.moduleName,
                        range = requireFact.range
                    )
                } else {
                    resolved += WorkspaceModuleGraph.ResolvedDependency(
                        consumerPath = path,
                        moduleName = requireFact.moduleName,
                        provider = provider,
                        range = requireFact.range
                    )
                    reverseDependencies.getOrPut(provider.path) { linkedSetOf() } += path
                }
            }

            if (resolved.isNotEmpty()) {
                resolvedDependencies[path] = resolved
            }
            if (unresolved.isNotEmpty()) {
                unresolvedStaticRequires[path] = unresolved
            }

            if (facts.dynamicRequires.isNotEmpty()) {
                dynamicRequireSites[path] = facts.dynamicRequires.map {
                    WorkspaceModuleGraph.DynamicRequireSite(consumerPath = path, fact = it)
                }
            }
        }

        val components = computeStronglyConnectedComponents(
            paths = graphFiles.keys,
            resolvedDependencies = resolvedDependencies
        )
        val componentByFile = linkedMapOf<VirtualPath, Set<VirtualPath>>()
        components.forEach { component ->
            component.forEach { path ->
                componentByFile[path] = component
            }
        }

        return WorkspaceModuleGraph(
            activeProviders = activeProviders,
            providersByModuleName = providersByModuleName,
            providerConflicts = providerConflicts,
            resolvedDependencies = resolvedDependencies,
            unresolvedStaticRequires = unresolvedStaticRequires,
            dynamicRequireSites = dynamicRequireSites,
            reverseDependencies = reverseDependencies.mapValues { it.value.toSet() },
            stronglyConnectedComponents = components,
            stronglyConnectedComponentByFile = componentByFile
        )
    }

    private fun providersFor(
        path: VirtualPath,
        facts: DocumentFacts?
    ): List<WorkspaceModuleGraph.ModuleProvider> {
        if (facts == null) {
            return emptyList()
        }

        val explicit = facts.legacyModuleCalls
            .asSequence()
            .filter { it.isTopLevel }
            .map { it.moduleName }
            .distinct()
            .map {
                WorkspaceModuleGraph.ModuleProvider(
                    moduleName = it,
                    path = path,
                    source = WorkspaceModuleGraph.ProviderSource.LEGACY_TOP_LEVEL
                )
            }
        val derived = facts.moduleNameCandidates
            .asSequence()
            .filter { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }
            .map { it.moduleName }
            .distinct()
            .map {
                WorkspaceModuleGraph.ModuleProvider(
                    moduleName = it,
                    path = path,
                    source = WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH
                )
            }

        return (explicit + derived).toList()
    }

    private fun computeStronglyConnectedComponents(
        paths: Set<VirtualPath>,
        resolvedDependencies: Map<VirtualPath, List<WorkspaceModuleGraph.ResolvedDependency>>
    ): List<Set<VirtualPath>> {
        var index = 0
        val indexByNode = mutableMapOf<VirtualPath, Int>()
        val lowLinkByNode = mutableMapOf<VirtualPath, Int>()
        val stack = ArrayDeque<VirtualPath>()
        val onStack = mutableSetOf<VirtualPath>()
        val components = mutableListOf<Set<VirtualPath>>()

        fun strongConnect(path: VirtualPath) {
            indexByNode[path] = index
            lowLinkByNode[path] = index
            index += 1
            stack.addLast(path)
            onStack += path

            resolvedDependencies[path].orEmpty()
                .map { it.provider.path }
                .filter { it in paths }
                .forEach { dependency ->
                    if (dependency !in indexByNode) {
                        strongConnect(dependency)
                        lowLinkByNode[path] = minOf(
                            lowLinkByNode.getValue(path),
                            lowLinkByNode.getValue(dependency)
                        )
                    } else if (dependency in onStack) {
                        lowLinkByNode[path] = minOf(
                            lowLinkByNode.getValue(path),
                            indexByNode.getValue(dependency)
                        )
                    }
                }

            if (lowLinkByNode.getValue(path) != indexByNode.getValue(path)) {
                return
            }

            val component = linkedSetOf<VirtualPath>()
            while (true) {
                val member = stack.removeLast()
                onStack -= member
                component += member
                if (member == path) {
                    break
                }
            }
            components += component
        }

        paths.sortedBy { it.value }.forEach { path ->
            if (path !in indexByNode) {
                strongConnect(path)
            }
        }

        return components.sortedBy { component -> component.minOf { it.value } }
    }

    private val providerComparator = compareBy<WorkspaceModuleGraph.ModuleProvider>(
        { providerSourceRank(it.source) },
        { it.path.value }
    )

    private fun providerSourceRank(source: WorkspaceModuleGraph.ProviderSource): Int = when (source) {
        WorkspaceModuleGraph.ProviderSource.LEGACY_TOP_LEVEL -> 0
        WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH -> 1
        WorkspaceModuleGraph.ProviderSource.EXTRA_WORKSPACE_PROVIDER -> 2
        WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY -> 3
    }
}
