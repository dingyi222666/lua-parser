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
            // providersFor already merges the path-derived `.aly` aliases into its claims on
            // every branch (null facts, empty fact claims, and the fact+aly merge).
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
                // Prefer activeProviders (includes path-derived `.aly` claims). Fall back to a
                // workspace `.aly` path match so layout requires still form graph edges even if
                // a claim was lost to an overlay/extra conflict.
                val provider = activeProviders[requireFact.moduleName]
                    ?: findOverlayProvider(requireFact.moduleName, builtinOverlay)
                    ?: findAlyLayoutProvider(requireFact.moduleName, graphFiles)
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

            facts.sourceImports.forEach { importFact ->
                val provider = activeProviders[importFact.target]
                if (provider != null && provider.path in files) {
                    resolved += WorkspaceModuleGraph.ResolvedDependency(
                        consumerPath = path,
                        moduleName = importFact.target,
                        provider = provider,
                        range = importFact.range
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
        // Always claim Android-Lua `.aly` layout modules by path, even when document facts are
        // sparse/null, so require("…layout") without a `.lua` suffix can resolve the workspace file.
        if (facts == null) {
            return alyPathProviders(path)
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
            .flatMap { moduleName ->
                virtualPathModuleAliases(moduleName).map { alias ->
                    WorkspaceModuleGraph.ModuleProvider(
                        moduleName = alias,
                        path = path,
                        source = WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH
                    )
                }
            }

        val fromFacts = (explicit + derived).toList()
        // Keep fact-derived claims and also merge any path-only `.aly` aliases so layout
        // modules remain require()-able under their full virtual-path module name; when facts
        // omitted VIRTUAL_PATH candidates the merged list is exactly the path-derived `.aly`
        // claim.
        return (fromFacts + alyPathProviders(path)).distinctBy { it.moduleName to it.path }
    }

    private fun virtualPathModuleAliases(moduleName: String): Sequence<String> {
        return sequenceOf(moduleName, moduleName.replace('.', '/')).distinct()
    }

    private fun alyPathProviders(path: VirtualPath): List<WorkspaceModuleGraph.ModuleProvider> {
        val moduleName = alyModuleNameFromPath(path) ?: return emptyList()
        return listOf(
            WorkspaceModuleGraph.ModuleProvider(
                moduleName = moduleName,
                path = path,
                source = WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH
            )
        )
    }

    /**
     * Recover a STANDARD_LIBRARY_OVERLAY provider for dotted module names such as socket.url
     * when activeProviders missed the claim. Uses the mounted builtin overlay snapshot only.
     */
    private fun findOverlayProvider(
        moduleName: String,
        builtinOverlay: BuiltinOverlaySnapshot
    ): WorkspaceModuleGraph.ModuleProvider? {
        if (moduleName.isBlank()) {
            return null
        }
        // Prefer exact dotted moduleName claim (socket.url stays dotted).
        builtinOverlay.providerModules.entries.firstOrNull { (_, provider) ->
            provider.moduleName == moduleName
        }?.let { (path, provider) ->
            return WorkspaceModuleGraph.ModuleProvider(
                moduleName = provider.moduleName,
                path = path,
                source = WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY
            )
        }
        val suffix = "/$moduleName.lua"
        val bare = "$moduleName.lua"
        builtinOverlay.providerModules.entries.firstOrNull { (path, provider) ->
            val value = path.value
            (value.endsWith(suffix) || value == bare) &&
                (provider.moduleName == moduleName || provider.moduleName.replace('/', '.') == moduleName)
        }?.let { (path, _) ->
            return WorkspaceModuleGraph.ModuleProvider(
                moduleName = moduleName,
                path = path,
                source = WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY
            )
        }
        return null
    }

    private fun findAlyLayoutProvider(
        moduleName: String,
        graphFiles: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>
    ): WorkspaceModuleGraph.ModuleProvider? {
        if (moduleName.isBlank()) {
            return null
        }
        val dotted = moduleName.replace('\\', '/')
        val candidates = listOf(
            "$dotted.aly",
            "${dotted.replace('.', '/')}.aly"
        )
        val match = graphFiles.keys.firstOrNull { path ->
            val value = path.value
            if (!value.endsWith(".aly")) {
                return@firstOrNull false
            }
            val pathModule = alyModuleNameFromPath(path)
            pathModule == moduleName ||
                candidates.any { candidate ->
                    value == candidate || value.endsWith("/$candidate")
                }
        } ?: return null
        return WorkspaceModuleGraph.ModuleProvider(
            moduleName = moduleName,
            path = match,
            source = WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH
        )
    }

    private fun alyModuleNameFromPath(path: VirtualPath): String? {
        val normalized = path.value.replace('\\', '/')
        if (!normalized.endsWith(".aly")) {
            return null
        }
        return normalized.removeSuffix(".aly").replace('/', '.').ifBlank { null }
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
        // Iterative Tarjan: one call-stack frame per strongConnect() activation, holding the
        // node plus its (already filtered) dependency edges and a cursor to the next edge.
        // The frame loop below reproduces the recursive DFS edge-for-edge while keeping the
        // depth on the heap — the recursive version overflowed the call stack on deep require
        // chains (thousands of files).
        val callStack = ArrayDeque<Pair<VirtualPath, Iterator<VirtualPath>>>()

        fun edgesOf(path: VirtualPath): Iterator<VirtualPath> =
            resolvedDependencies[path]
                .orEmpty()
                .map { it.provider.path }
                .filter { it in paths }
                .iterator()

        fun pushActivation(path: VirtualPath) {
            // strongConnect(path) prologue: assign the next index and push onto the Tarjan stack.
            indexByNode[path] = index
            lowLinkByNode[path] = index
            index += 1
            stack.addLast(path)
            onStack += path
            callStack.addLast(path to edgesOf(path))
        }

        paths.sortedBy { it.value }.forEach { path ->
            if (path !in indexByNode) {
                pushActivation(path)
            }
            while (callStack.isNotEmpty()) {
                val (node, edges) = callStack.last()
                val dependency = if (edges.hasNext()) edges.next() else null
                if (dependency != null) {
                    if (dependency !in indexByNode) {
                        // Descend: run the child prologue and continue the loop on the child
                        // frame, now on top of the call stack.
                        pushActivation(dependency)
                    } else if (dependency in onStack) {
                        lowLinkByNode[node] = minOf(
                            lowLinkByNode.getValue(node),
                            indexByNode.getValue(dependency)
                        )
                    }
                } else {
                    // All of node's edges are processed: strongConnect(node) returns here.
                    callStack.removeLast()
                    // Lowlink merge on pop, exactly where the recursive version merged: right
                    // after strongConnect(child) returned to the parent's edge loop. Applied
                    // unconditionally on purpose — when the child was an SCC root its lowlink
                    // equals its own (strictly larger) index, so the min is a no-op and a
                    // parent never absorbs a lowlink from an already-emitted component.
                    callStack.lastOrNull()?.first?.let { parent ->
                        lowLinkByNode[parent] = minOf(
                            lowLinkByNode.getValue(parent),
                            lowLinkByNode.getValue(node)
                        )
                    }
                    if (lowLinkByNode.getValue(node) != indexByNode.getValue(node)) {
                        continue
                    }

                    val component = linkedSetOf<VirtualPath>()
                    while (true) {
                        val member = stack.removeLast()
                        onStack -= member
                        component += member
                        if (member == node) {
                            break
                        }
                    }
                    components += component
                }
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
