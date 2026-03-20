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

    fun build(
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

    fun update(
        previous: WorkspaceSnapshot,
        delta: WorkspaceDelta,
        standardLibraryOverlayVersion: LuaVersion = previous.builtinOverlay.version,
        reporter: ProgressReporter = ProgressReporter.NONE
    ): WorkspaceUpdateResult {
        val builtinOverlay = BuiltinOverlayLoader.load(standardLibraryOverlayVersion, ::analyzeFile)
        val nextMetadata = delta.metadata ?: previous.metadata
        val extraProviders = extraProviders(
            LuaWorkspaceInput(
                files = delta.upserts,
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
            sources = delta.upserts,
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
        val resolver = WorkspaceModuleResolver(baseSnapshot)
        val files = baseSnapshot.files.mapValues { (path, fileSnapshot) ->
            if (path !in pathsToAnalyze) {
                previous?.files?.get(path)?.semanticFile?.let { existing ->
                    return@mapValues fileSnapshot.copy(semanticFile = existing)
                }
                return@mapValues fileSnapshot
            }
            val chunk = sources[path]?.let { parserFactory().parse(it) }
                ?: previous?.files?.get(path)?.semanticFile?.chunk
                ?: parserFactory().parse("")
            val semanticSnapshot = semanticPipeline.analyzeSnapshot(
                chunk,
                workspaceContext(
                    LuaWorkspaceInput(
                        files = sources,
                        metadata = baseSnapshot.metadata,
                        standardLibraryOverlayVersion = baseSnapshot.builtinOverlay.version
                    ),
                    path,
                    baseSnapshot
                )
            )
            fileSnapshot.copy(
                semanticFile = WorkspaceSemanticFile(
                    path = path,
                    source = sources[path]
                        ?: previous?.files?.get(path)?.semanticFile?.source
                        ?: "",
                    chunk = chunk,
                    model = semanticSnapshot.model,
                    snapshot = semanticSnapshot
                )
            )
        }
        return baseSnapshot.copy(files = files)
    }

    private fun analyzeFile(path: VirtualPath, source: String): WorkspaceSnapshot.FileSnapshot {
        val chunk = parserFactory().parse(source)
        val facts = DocumentFactsCollector.collect(path, chunk)
        val legacyEnvironment = LegacyModuleEnvironmentPass.analyze(path, facts)
        val exportSurface = ModuleExportCollector.collect(chunk, facts, legacyEnvironment)
        val publicFingerprint = WorkspacePublicFingerprint.from(facts, exportSurface)

        return WorkspaceSnapshot.FileSnapshot(
            cacheKey = workspaceFingerprintHash(source),
            documentFacts = facts,
            factsSignature = facts.fingerprint,
            legacyModuleEnvironment = legacyEnvironment,
            moduleExportSurface = exportSurface,
            publicFingerprint = publicFingerprint
        )
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
