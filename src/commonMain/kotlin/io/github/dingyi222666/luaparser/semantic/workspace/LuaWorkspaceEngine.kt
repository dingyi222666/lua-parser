package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader

open class LuaWorkspaceEngine(
    private val parserFactory: () -> LuaParser = { LuaParser() }
) {
    private val semanticPipeline = SemanticPipeline()

    open fun build(
        input: LuaWorkspaceInput,
        reporter: ProgressReporter = ProgressReporter.NONE
    ): WorkspaceUpdateResult {
        val sortedFiles = input.files.keys.sortedBy { it.value }
        val baseSnapshots = linkedMapOf<VirtualPath, WorkspaceSnapshot.FileSnapshot>()
        val builtinOverlay = BuiltinOverlayLoader.load(input.standardLibraryOverlayVersion, ::analyzeFile)
        val extraProviders = extraProviders(input)
        val totalFiles = sortedFiles.size * 2

        sortedFiles.forEachIndexed { index, path ->
            reporter.report(
                AnalysisProgress(
                    phase = AnalysisProgress.Phase.PARSING,
                    currentFile = path,
                    completedFiles = index,
                    totalFiles = totalFiles
                )
            )
            baseSnapshots[path] = analyzeFile(path, input.files.getValue(path))
            reporter.report(
                AnalysisProgress(
                    phase = AnalysisProgress.Phase.PARSING,
                    currentFile = path,
                    completedFiles = index + 1,
                    totalFiles = totalFiles
                )
            )
        }

        val baseSnapshot = WorkspaceSnapshot(
            files = baseSnapshots,
            metadata = input.metadata,
            extraProviders = extraProviders,
            builtinOverlay = builtinOverlay,
            graph = WorkspaceModuleGraphBuilder.build(baseSnapshots, builtinOverlay, extraProviders)
        )
        val snapshot = attachSemanticState(
            baseSnapshot = baseSnapshot,
            sources = input.files,
            pathsToAnalyze = sortedFiles.toSet(),
            previous = null
        )
        val affectedDocuments = sortedFiles.toSet()
        reportBindingProgress(sortedFiles, sortedFiles.size, totalFiles, reporter)
        reporter.report(AnalysisProgress(AnalysisProgress.Phase.COMPLETE, completedFiles = totalFiles, totalFiles = totalFiles))

        return WorkspaceUpdateResult(
            snapshot = snapshot,
            changedFiles = affectedDocuments,
            publicSurfaceChangedFiles = affectedDocuments,
            activeProviderChangedModuleNames = snapshot.graph.activeProviders.keys,
            filesWithRequireResolutionChanged = emptySet(),
            affectedDocuments = affectedDocuments,
            affectedModuleNames = snapshot.graph.activeProviders.keys
        )
    }

    open fun update(
        previous: WorkspaceSnapshot,
        delta: WorkspaceDelta,
        standardLibraryOverlayVersion: LuaVersion = previous.builtinOverlay.version,
        reporter: ProgressReporter = ProgressReporter.NONE
    ): WorkspaceUpdateResult {
        val builtinOverlay = BuiltinOverlayLoader.load(standardLibraryOverlayVersion, ::analyzeFile)
        val nextMetadata = delta.metadata ?: previous.metadata

        // Merge previous snapshot sources with the delta so extraProviders and dirty
        // re-analysis see the full workspace, not only the upserted paths.
        val nextSources = linkedMapOf<VirtualPath, String>()
        previous.files.forEach { (path, fileSnapshot) ->
            if (path in delta.removals) {
                return@forEach
            }
            val source = fileSnapshot.semanticFile?.source
            if (source != null) {
                nextSources[path] = source
            }
        }
        nextSources.putAll(delta.upserts)

        val extraProviders = extraProviders(
            LuaWorkspaceInput(
                files = nextSources,
                metadata = nextMetadata,
                standardLibraryOverlayVersion = standardLibraryOverlayVersion
            )
        )

        if (delta.isEmpty() && builtinOverlay == previous.builtinOverlay && extraProviders == previous.extraProviders) {
            reporter.report(AnalysisProgress(AnalysisProgress.Phase.COMPLETE, completedFiles = 0, totalFiles = 0))
            return WorkspaceUpdateResult(
                snapshot = previous,
                changedFiles = emptySet(),
                publicSurfaceChangedFiles = emptySet(),
                activeProviderChangedModuleNames = emptySet(),
                filesWithRequireResolutionChanged = emptySet(),
                affectedDocuments = emptySet(),
                affectedModuleNames = emptySet()
            )
        }

        val parsingTargets = delta.upserts.keys.sortedBy { it.value }
        val nextFiles = previous.files.toMutableMap()
        delta.removals.forEach(nextFiles::remove)
        parsingTargets.forEach { path ->
            nextFiles[path] = analyzeFile(path, delta.upserts.getValue(path))
        }

        val baseSnapshot = WorkspaceSnapshot(
            files = nextFiles,
            metadata = nextMetadata,
            extraProviders = extraProviders,
            builtinOverlay = builtinOverlay,
            graph = WorkspaceModuleGraphBuilder.build(nextFiles, builtinOverlay, extraProviders)
        )
        val dirtyPlan = WorkspaceDirtySetPlanner.plan(previous, baseSnapshot)
        val totalFiles = parsingTargets.size + dirtyPlan.affectedDocuments.size

        parsingTargets.forEachIndexed { index, path ->
            reporter.report(
                AnalysisProgress(
                    phase = AnalysisProgress.Phase.PARSING,
                    currentFile = path,
                    completedFiles = index + 1,
                    totalFiles = totalFiles
                )
            )
        }

        val snapshot = attachSemanticState(
            baseSnapshot = baseSnapshot,
            sources = nextSources,
            pathsToAnalyze = dirtyPlan.affectedDocuments,
            previous = previous
        )
        reportBindingProgress(dirtyPlan.affectedDocuments.sortedBy { it.value }, parsingTargets.size, totalFiles, reporter)
        reporter.report(AnalysisProgress(AnalysisProgress.Phase.COMPLETE, completedFiles = totalFiles, totalFiles = totalFiles))

        return WorkspaceUpdateResult(
            snapshot = snapshot,
            changedFiles = dirtyPlan.changedFiles,
            publicSurfaceChangedFiles = dirtyPlan.publicSurfaceChangedFiles,
            activeProviderChangedModuleNames = dirtyPlan.activeProviderChangedModuleNames,
            filesWithRequireResolutionChanged = dirtyPlan.filesWithRequireResolutionChanged,
            affectedDocuments = dirtyPlan.affectedDocuments,
            affectedModuleNames = dirtyPlan.affectedModuleNames
        )
    }

    protected open fun extraProviders(input: LuaWorkspaceInput): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> = emptyMap()

    internal open fun workspaceContext(
        input: LuaWorkspaceInput,
        path: VirtualPath,
        snapshot: WorkspaceSnapshot
    ): SemanticWorkspaceContext {
        return SemanticWorkspaceContext(
            currentPath = path,
            workspaceResolver = WorkspaceModuleResolver(snapshot),
            overlayGlobals = snapshot.builtinOverlay.globals
        )
    }

    private fun attachSemanticState(
        baseSnapshot: WorkspaceSnapshot,
        sources: Map<VirtualPath, String>,
        pathsToAnalyze: Set<VirtualPath>,
        previous: WorkspaceSnapshot?
    ): WorkspaceSnapshot {
        val files = linkedMapOf<VirtualPath, WorkspaceSnapshot.FileSnapshot>()
        baseSnapshot.files.forEach { (path, fileSnapshot) ->
            if (path !in pathsToAnalyze) {
                val existing = previous?.files?.get(path)?.semanticFile ?: fileSnapshot.semanticFile
                files[path] = if (existing == null) fileSnapshot else fileSnapshot.copy(semanticFile = existing)
            } else {
                files[path] = fileSnapshot.copy(semanticFile = null)
            }
        }

        semanticAnalysisOrder(pathsToAnalyze, baseSnapshot.graph).forEach { path ->
            val fileSnapshot = files[path] ?: return@forEach
            val chunk = sources[path]?.let(::parseWorkspaceSource)
                ?: previous?.files?.get(path)?.semanticFile?.chunk
                ?: fileSnapshot.semanticFile?.chunk
                ?: parseWorkspaceSource("")
            val currentSnapshot = baseSnapshot.copy(files = files.toMap())
            val semanticSnapshot = semanticPipeline.analyzeSnapshot(
                chunk,
                workspaceContext(
                    LuaWorkspaceInput(
                        files = sources,
                        metadata = baseSnapshot.metadata,
                        standardLibraryOverlayVersion = baseSnapshot.builtinOverlay.version
                    ),
                    path,
                    currentSnapshot
                )
            )
            val semanticFile = WorkspaceSemanticFile(
                path = path,
                source = sources[path]
                    ?: previous?.files?.get(path)?.semanticFile?.source
                    ?: fileSnapshot.semanticFile?.source
                    ?: "",
                chunk = chunk,
                model = semanticSnapshot.model,
                snapshot = semanticSnapshot
            )
            files[path] = fileSnapshot.copy(semanticFile = semanticFile)
        }
        return baseSnapshot.copy(files = files.toMap())
    }

    private fun semanticAnalysisOrder(
        pathsToAnalyze: Set<VirtualPath>,
        graph: WorkspaceModuleGraph
    ): List<VirtualPath> {
        val visiting = mutableSetOf<VirtualPath>()
        val visited = mutableSetOf<VirtualPath>()
        val ordered = mutableListOf<VirtualPath>()

        fun visit(path: VirtualPath) {
            if (path !in pathsToAnalyze || path in visited || !visiting.add(path)) {
                return
            }
            graph.resolvedDependencies[path]
                .orEmpty()
                .map { it.provider.path }
                .sortedBy { it.value }
                .forEach(::visit)
            visiting -= path
            visited += path
            ordered += path
        }

        pathsToAnalyze.sortedBy { it.value }.forEach(::visit)
        return ordered
    }

    private fun analyzeFile(path: VirtualPath, source: String): WorkspaceSnapshot.FileSnapshot {
        val chunk = parseWorkspaceSource(source)
        val facts = DocumentFactsCollector.collect(path, chunk)
        val legacyEnvironment = LegacyModuleEnvironmentPass.analyze(path, facts)
        val exportSurface = ModuleExportCollector.collect(chunk, facts, legacyEnvironment)
        val publicTypeAnnotations = source.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("---@") }
            .joinToString("\n")
        val publicFingerprint = WorkspacePublicFingerprint.from(facts, exportSurface, publicTypeAnnotations)

        return WorkspaceSnapshot.FileSnapshot(
            cacheKey = workspaceFingerprintHash(source),
            documentFacts = facts,
            factsSignature = facts.fingerprint,
            legacyModuleEnvironment = legacyEnvironment,
            moduleExportSurface = exportSurface,
            publicFingerprint = publicFingerprint
        )
    }

    protected fun parseWorkspaceSource(source: String) = try {
        parserFactory().parseWorkspaceSnippet(source)
    } catch (_: IllegalStateException) {
        parserFactory().parseWorkspaceSnippet("")
    }

    private fun reportBindingProgress(
        affectedDocuments: List<VirtualPath>,
        changedFileCount: Int,
        totalFiles: Int,
        reporter: ProgressReporter
    ) {
        affectedDocuments.forEachIndexed { index, path ->
            reporter.report(
                AnalysisProgress(
                    phase = AnalysisProgress.Phase.BINDING,
                    currentFile = path,
                    completedFiles = changedFileCount + index + 1,
                    totalFiles = totalFiles
                )
            )
        }
    }
}
