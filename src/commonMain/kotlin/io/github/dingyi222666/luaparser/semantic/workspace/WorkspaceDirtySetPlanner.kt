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
        // Both changed-file comparisons run over the same (files, extraProviders) merged views;
        // built once here instead of inside each comparison.
        val previousSnapshots = previous.files + previous.extraProviders
        val currentSnapshots = current.files + current.extraProviders
        val (changedFiles, publicSurfaceChangedFiles) =
            changedFileSets(previousSnapshots, currentSnapshots)
        val activeProviderChangedModuleNames = activeProviderChangedModuleNames(previous.graph, current.graph)
        val filesWithRequireResolutionChanged = filesWithRequireResolutionChanged(previous.graph, current.graph)
        val seeAllFallbackDocuments = seeAllFallbackDocumentsWithBuiltinGlobalsChanged(previous, current)

        val reverseDirty = reverseDependencyDirtyClosure(
            previous = previous, current = current,
            publicSurfaceChangedFiles = publicSurfaceChangedFiles,
            activeProviderChangedModuleNames = activeProviderChangedModuleNames,
            filesWithRequireResolutionChanged = filesWithRequireResolutionChanged,
            seeAllFallbackDocuments = seeAllFallbackDocuments
        )
        // Each dirty file inherits its previous AND current SCC members as dirty (cycles must
        // re-analyze together in both graph revisions).
        val sccExpanded = reverseDirty.flatMapTo(linkedSetOf()) { path ->
            previous.graph.stronglyConnectedComponentByFile[path].orEmpty() +
                current.graph.stronglyConnectedComponentByFile[path].orEmpty()
        }
        val affectedDocuments = (changedFiles.filterTo(linkedSetOf()) { it in current.files } +
            sccExpanded + seeAllFallbackDocuments).toSet()
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

    /** Changed-path and public-surface-changed-path sets share the merged-snapshot walk. */
    private fun changedFileSets(
        previousSnapshots: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>,
        currentSnapshots: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>
    ): Pair<Set<VirtualPath>, Set<VirtualPath>> {
        val allPaths = previousSnapshots.keys + currentSnapshots.keys
        val changedFiles = allPaths.filterTo(linkedSetOf()) { path ->
            val previous = previousSnapshots[path]
            val current = currentSnapshots[path]
            if (previous === current) return@filterTo false
            if (previous == null || current == null) return@filterTo true
            // Whole-FileSnapshot structural equals deep-walks the semantic model
            // (ModuleType → JavaClassType → hundreds of members) for every provider
            // path on every update — device-measured seconds of pure equals/hashCode
            // per update. What distinguishes a changed document for dirty planning is
            // exactly its source, its public fingerprint and its recovery diagnostics;
            // identical triples mean an unchanged public surface.
            previous.semanticFile?.source != current.semanticFile?.source ||
                previous.cacheKey != current.cacheKey ||
                previous.factsSignature != current.factsSignature ||
                previous.publicFingerprint != current.publicFingerprint ||
                previous.semanticFile?.recoveryDiagnostics != current.semanticFile?.recoveryDiagnostics ||
                previous.semanticFile?.model?.getDiagnostics() != current.semanticFile?.model?.getDiagnostics()
        }
        val publicSurfaceChangedFiles = allPaths.filterTo(linkedSetOf()) { path ->
            val previousFingerprint = previousSnapshots[path]?.publicFingerprint
            val currentFingerprint = currentSnapshots[path]?.publicFingerprint
            // Both comparisons below run over the same four previous/current (files,
            // extraProviders) combinations the merged maps encode. The global-symbols
            // fingerprint changes when a provider's non-exported globals change — invisible to
            // the export-surface `value` — and must flag the provider so its consumers are
            // re-analyzed and re-bind those globals through the module resolver. Empty on both
            // sides means "never analyzed" (extra providers) and compares equal.
            previousFingerprint?.value != currentFingerprint?.value ||
                previousFingerprint?.globalSymbolsFingerprint != currentFingerprint?.globalSymbolsFingerprint
        }
        return changedFiles to publicSurfaceChangedFiles
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
        val seedFiles = filesWithRequireResolutionChanged.filterTo(linkedSetOf()) { it in current.files }
        seedFiles += seeAllFallbackDocuments
        val globalReaders = buildMap<String, MutableSet<VirtualPath>> {
            current.files.forEach { (path, file) ->
                file.documentFacts?.referencedNames.orEmpty().forEach { name ->
                    getOrPut(name) { linkedSetOf() } += path
                }
            }
        }
        fun sharedGlobalDependents(path: VirtualPath): Set<VirtualPath> = buildSet {
            val names = previous.files[path]?.publicFingerprint?.globalWriteNames.orEmpty() +
                current.files[path]?.publicFingerprint?.globalWriteNames.orEmpty()
            names.forEach { name -> addAll(globalReaders[name].orEmpty()) }
            remove(path)
        }

        publicSurfaceChangedFiles.forEach { path ->
            seedFiles += previous.graph.reverseDependencies[path].orEmpty() +
                current.graph.reverseDependencies[path].orEmpty() +
                currentExtraProviderConsumers(path, current)
            val before = previous.files[path]?.publicFingerprint
            val after = current.files[path]?.publicFingerprint
            if (before?.globalSymbolsFingerprint != after?.globalSymbolsFingerprint || before?.value != after?.value) {
                seedFiles += sharedGlobalDependents(path)
            }
        }

        activeProviderChangedModuleNames.forEach { moduleName ->
            seedFiles += consumersOfModule(moduleName, previous.graph) + consumersOfModule(moduleName, current.graph)
        }

        val reverseEdges = linkedMapOf<VirtualPath, Set<VirtualPath>>()
        (previous.graph.reverseDependencies.keys + current.graph.reverseDependencies.keys +
            previous.files.keys + current.files.keys).forEach { path ->
            reverseEdges[path] = previous.graph.reverseDependencies[path].orEmpty() +
                current.graph.reverseDependencies[path].orEmpty() + sharedGlobalDependents(path)
        }

        val queue = ArrayDeque(seedFiles)
        val visited = linkedSetOf<VirtualPath>()
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!visited.add(path)) { continue }
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

        return current.files.filterTo(linkedMapOf()) { (_, snapshot) ->
            snapshot.legacyModuleEnvironment?.segments.orEmpty().any { it.hasSeeAllFallback }
        }.keys
    }

    private fun consumersOfModule(moduleName: String, graph: WorkspaceModuleGraph): Set<VirtualPath> {
        return graph.resolvedDependencies.values
            .flatten()
            .filter { it.moduleName == moduleName }
            .mapTo(linkedSetOf()) { it.consumerPath }
    }

    private fun currentExtraProviderConsumers(path: VirtualPath, current: WorkspaceSnapshot): Set<VirtualPath> {
        if (path !in current.extraProviders) {
            return emptySet()
        }
        return current.graph.reverseDependencies[path]
            .orEmpty()
            .filterTo(linkedSetOf()) { it in current.files }
    }

    private fun affectedModuleNames(
        previous: WorkspaceSnapshot,
        current: WorkspaceSnapshot,
        publicSurfaceChangedFiles: Set<VirtualPath>,
        activeProviderChangedModuleNames: Set<String>
    ): Set<String> {
        return buildSet {
            addAll(activeProviderChangedModuleNames)
            publicSurfaceChangedFiles.forEach { path ->
                sequenceOf(previous.files, previous.extraProviders, current.files, current.extraProviders)
                    .forEach { addAll(it[path]?.publicFingerprint?.providedModuleNames.orEmpty()) }
            }
        }
    }
}
