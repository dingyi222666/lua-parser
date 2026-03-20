package io.github.dingyi222666.luaparser.semantic.workspace

object WorkspaceDirtySetPlanner {
    data class Result(
        val changedFiles: Set<VirtualPath>,
        val publicSurfaceChangedFiles: Set<VirtualPath>,
        val activeProviderChangedModuleNames: Set<String>,
        val filesWithRequireResolutionChanged: Set<VirtualPath>,
        val reverseDependencyDirtyClosure: Set<VirtualPath>,
        val sccExpandedDirtyClosure: Set<VirtualPath>,
        val affectedDocuments: Set<VirtualPath>,
        val affectedModuleNames: Set<String>
    )

    fun plan(
        previous: WorkspaceSnapshot,
        current: WorkspaceSnapshot
    ): Result {
        val changedFiles = changedFiles(previous, current)
        val publicSurfaceChangedFiles = publicSurfaceChangedFiles(previous, current)
        val activeProviderChangedModuleNames = activeProviderChangedModuleNames(previous.graph, current.graph)
        val filesWithRequireResolutionChanged = filesWithRequireResolutionChanged(previous.graph, current.graph)
        val seeAllFallbackDocuments = seeAllFallbackDocumentsWithBuiltinGlobalsChanged(previous, current)

        val reverseDirty = reverseDependencyDirtyClosure(
            previous = previous,
            current = current,
            publicSurfaceChangedFiles = publicSurfaceChangedFiles,
            activeProviderChangedModuleNames = activeProviderChangedModuleNames,
            filesWithRequireResolutionChanged = filesWithRequireResolutionChanged,
            seeAllFallbackDocuments = seeAllFallbackDocuments
        )
        val sccExpanded = expandThroughStronglyConnectedComponents(previous.graph, current.graph, reverseDirty)
        val affectedDocuments = (
            changedFiles.filterTo(linkedSetOf()) { it in current.files } +
                sccExpanded +
                seeAllFallbackDocuments
            ).toSet()
        val affectedModuleNames = affectedModuleNames(
            previous = previous,
            current = current,
            publicSurfaceChangedFiles = publicSurfaceChangedFiles,
            activeProviderChangedModuleNames = activeProviderChangedModuleNames
        )

        return Result(
            changedFiles = changedFiles,
            publicSurfaceChangedFiles = publicSurfaceChangedFiles,
            activeProviderChangedModuleNames = activeProviderChangedModuleNames,
            filesWithRequireResolutionChanged = filesWithRequireResolutionChanged,
            reverseDependencyDirtyClosure = reverseDirty,
            sccExpandedDirtyClosure = sccExpanded,
            affectedDocuments = affectedDocuments,
            affectedModuleNames = affectedModuleNames
        )
    }

    private fun changedFiles(previous: WorkspaceSnapshot, current: WorkspaceSnapshot): Set<VirtualPath> {
        val allPaths = previous.files.keys + current.files.keys
        return allPaths.filterTo(linkedSetOf()) { path ->
            previous.files[path] != current.files[path]
        }
    }

    private fun publicSurfaceChangedFiles(previous: WorkspaceSnapshot, current: WorkspaceSnapshot): Set<VirtualPath> {
        val allPaths = previous.files.keys + current.files.keys
        return allPaths.filterTo(linkedSetOf()) { path ->
            previous.files[path]?.publicFingerprint?.value != current.files[path]?.publicFingerprint?.value
        }
    }

    private fun activeProviderChangedModuleNames(
        previous: WorkspaceModuleGraph,
        current: WorkspaceModuleGraph
    ): Set<String> {
        val allModules = previous.activeProviders.keys + current.activeProviders.keys
        return allModules.filterTo(linkedSetOf()) { moduleName ->
            previous.activeProviders[moduleName]?.path != current.activeProviders[moduleName]?.path
        }
    }

    private fun filesWithRequireResolutionChanged(
        previous: WorkspaceModuleGraph,
        current: WorkspaceModuleGraph
    ): Set<VirtualPath> {
        val allFiles = previous.resolvedDependencies.keys + current.resolvedDependencies.keys +
            previous.unresolvedStaticRequires.keys + current.unresolvedStaticRequires.keys
        return allFiles.filterTo(linkedSetOf()) { path ->
            requireResolutionStates(previous, path) != requireResolutionStates(current, path)
        }
    }

    private fun requireResolutionStates(graph: WorkspaceModuleGraph, path: VirtualPath): Set<Pair<String, Boolean>> {
        val resolved = graph.resolvedDependencies[path].orEmpty().map { it.moduleName to true }
        val unresolved = graph.unresolvedStaticRequires[path].orEmpty().map { it.moduleName to false }
        return (resolved + unresolved).toSet()
    }

    private fun reverseDependencyDirtyClosure(
        previous: WorkspaceSnapshot,
        current: WorkspaceSnapshot,
        publicSurfaceChangedFiles: Set<VirtualPath>,
        activeProviderChangedModuleNames: Set<String>,
        filesWithRequireResolutionChanged: Set<VirtualPath>,
        seeAllFallbackDocuments: Set<VirtualPath>
    ): Set<VirtualPath> {
        val seedFiles = linkedSetOf<VirtualPath>()
        seedFiles += filesWithRequireResolutionChanged.filter { it in current.files }
        seedFiles += seeAllFallbackDocuments

        publicSurfaceChangedFiles.forEach { path ->
            seedFiles += reverseDependentsOf(path, previous.graph)
            seedFiles += reverseDependentsOf(path, current.graph)
        }

        activeProviderChangedModuleNames.forEach { moduleName ->
            seedFiles += consumersOfModule(moduleName, previous.graph)
            seedFiles += consumersOfModule(moduleName, current.graph)
        }

        val reverseEdges = linkedMapOf<VirtualPath, Set<VirtualPath>>()
        (previous.graph.reverseDependencies.keys + current.graph.reverseDependencies.keys).forEach { path ->
            reverseEdges[path] = reverseDependentsOf(path, previous.graph) + reverseDependentsOf(path, current.graph)
        }

        val queue = ArrayDeque(seedFiles)
        val visited = linkedSetOf<VirtualPath>()
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!visited.add(path)) {
                continue
            }
            reverseEdges[path].orEmpty().sortedBy { it.value }.forEach(queue::addLast)
        }
        return visited.filterTo(linkedSetOf()) { it in current.files }
    }

    private fun seeAllFallbackDocumentsWithBuiltinGlobalsChanged(
        previous: WorkspaceSnapshot,
        current: WorkspaceSnapshot
    ): Set<VirtualPath> {
        if (previous.builtinOverlay.globals.metadataFingerprint == current.builtinOverlay.globals.metadataFingerprint) {
            return emptySet()
        }

        return current.files
            .filter { (_, snapshot) ->
                snapshot.legacyModuleEnvironment?.segments.orEmpty().any { it.hasSeeAllFallback }
            }
            .keys
            .toCollection(linkedSetOf())
    }

    private fun expandThroughStronglyConnectedComponents(
        previous: WorkspaceModuleGraph,
        current: WorkspaceModuleGraph,
        dirtyFiles: Set<VirtualPath>
    ): Set<VirtualPath> {
        return dirtyFiles.flatMapTo(linkedSetOf()) { path ->
            previous.graphComponent(path) + current.graphComponent(path)
        }
    }

    private fun WorkspaceModuleGraph.graphComponent(path: VirtualPath): Set<VirtualPath> =
        stronglyConnectedComponentByFile[path].orEmpty()

    private fun reverseDependentsOf(path: VirtualPath, graph: WorkspaceModuleGraph): Set<VirtualPath> =
        graph.reverseDependencies[path].orEmpty()

    private fun consumersOfModule(moduleName: String, graph: WorkspaceModuleGraph): Set<VirtualPath> {
        return graph.resolvedDependencies.values
            .flatten()
            .filter { it.moduleName == moduleName }
            .mapTo(linkedSetOf()) { it.consumerPath }
    }

    private fun affectedModuleNames(
        previous: WorkspaceSnapshot,
        current: WorkspaceSnapshot,
        publicSurfaceChangedFiles: Set<VirtualPath>,
        activeProviderChangedModuleNames: Set<String>
    ): Set<String> {
        val moduleNames = linkedSetOf<String>()
        moduleNames += activeProviderChangedModuleNames
        publicSurfaceChangedFiles.forEach { path ->
            moduleNames += previous.files[path]?.publicFingerprint?.providedModuleNames.orEmpty()
            moduleNames += current.files[path]?.publicFingerprint?.providedModuleNames.orEmpty()
        }
        return moduleNames
    }
}
