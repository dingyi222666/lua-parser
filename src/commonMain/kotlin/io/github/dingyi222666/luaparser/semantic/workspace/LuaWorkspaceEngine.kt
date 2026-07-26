package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParserRecoveryDiagnostic
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot

open class LuaWorkspaceEngine(
    private val parserFactory: (LuaVersion) -> LuaParser = { version -> LuaParser(version) }
) {
    private val semanticPipeline = SemanticPipeline()

    /**
     * Grammar dialect currently in force.
     *
     * The workspace declares one [LuaVersion], and it has to drive *both* halves of the front end:
     * the builtin overlay catalog and the parser. It used to only reach the overlay, so a Lua 5.4
     * workspace got a 5.4 stdlib but an AndroLua 5.3 grammar — `local x <close>` was rejected in
     * the very workspace that declared 5.4, and AndroLua-only syntax was silently accepted by
     * workspaces that declared plain 5.3.
     */
    private var parserVersion: LuaVersion = LuaVersion.ANDROLUA_5_3

    private fun useParserVersion(version: LuaVersion) {
        if (parserVersion == version) {
            return
        }
        parserVersion = version
        // Everything memoized below was produced by the previous dialect.
        activeParser = null
        parsedChunks.clear()
        documentFactsCache.clear()
    }

    /**
     * Overlay sources are compiled-in resources and [analyzeFile] is deterministic, so the
     * snapshot for a given version never changes over an engine's lifetime. Re-analyzing the
     * ~65 stdlib/AndroLua overlay modules on every build/update dominated edit latency.
     */
    private val builtinOverlays = mutableMapOf<LuaVersion, BuiltinOverlaySnapshot>()

    private fun builtinOverlay(version: LuaVersion): BuiltinOverlaySnapshot {
        return builtinOverlays.getOrPut(version) { BuiltinOverlayLoader.load(version, ::analyzeFile) }
    }

    open fun build(
        input: LuaWorkspaceInput,
        reporter: ProgressReporter = ProgressReporter.NONE
    ): WorkspaceUpdateResult {
        useParserVersion(input.standardLibraryOverlayVersion)
        val sortedFiles = input.files.keys.sortedBy { it.value }
        val baseSnapshots = linkedMapOf<VirtualPath, WorkspaceSnapshot.FileSnapshot>()
        val builtinOverlay = builtinOverlay(input.standardLibraryOverlayVersion)
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
        retainCachedDocuments(input.files.keys)
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
        useParserVersion(standardLibraryOverlayVersion)
        val builtinOverlay = builtinOverlay(standardLibraryOverlayVersion)
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
        retainCachedDocuments(nextSources.keys)
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

        // Analysis runs in dependency order and only ever *adds* semantic files, so the loop can
        // share one snapshot view backed by the live `files` map instead of copying the whole map
        // once per analyzed document (which made a workspace pass quadratic in file count).
        val analysisSnapshot = baseSnapshot.copy(files = files)
        val analysisInput = LuaWorkspaceInput(
            files = sources,
            metadata = baseSnapshot.metadata,
            standardLibraryOverlayVersion = baseSnapshot.builtinOverlay.version
        )

        semanticAnalysisOrder(pathsToAnalyze, baseSnapshot.graph).forEach { path ->
            val fileSnapshot = files[path] ?: return@forEach
            val carriedOver = previous?.files?.get(path)?.semanticFile ?: fileSnapshot.semanticFile
            // Reuse the carried-over parse (and its diagnostics) when this pass has no source for
            // the path; only fall back to an empty parse when there is nothing at all.
            val parsed = sources[path]?.let { source -> parseWorkspaceResult(path, source) }
            val chunk = parsed?.chunk ?: carriedOver?.chunk ?: parseWorkspaceSource("")
            val semanticSnapshot = semanticPipeline.analyzeSnapshot(
                chunk,
                workspaceContext(analysisInput, path, analysisSnapshot)
            )
            val semanticFile = WorkspaceSemanticFile(
                path = path,
                source = sources[path] ?: carriedOver?.source ?: "",
                chunk = chunk,
                model = semanticSnapshot.model,
                snapshot = semanticSnapshot,
                recoveryDiagnostics = parsed?.recoveryDiagnostics
                    ?: carriedOver?.recoveryDiagnostics.orEmpty()
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

    /**
     * Document facts keyed by path and guarded by the exact source text.
     *
     * Facts are derived deterministically from (path, source) but are needed by several layers in
     * one pass (`analyzeFile` here, provider discovery in the JVM engine). Memoizing them keeps
     * that to a single AST walk per document revision instead of one walk per consumer.
     */
    private val documentFactsCache = mutableMapOf<VirtualPath, Pair<String, DocumentFacts>>()

    protected fun documentFacts(path: VirtualPath, source: String): DocumentFacts {
        documentFactsCache[path]?.let { (cachedSource, facts) ->
            if (cachedSource == source) {
                return facts
            }
        }
        val facts = DocumentFactsCollector.collect(path, parseWorkspaceSource(path, source))
        documentFactsCache[path] = source to facts
        return facts
    }

    /** Drops per-path caches for documents that left the workspace. */
    private fun retainCachedDocuments(paths: Set<VirtualPath>) {
        parsedChunks.keys.retainAll(paths)
        documentFactsCache.keys.retainAll(paths)
    }

    private fun analyzeFile(path: VirtualPath, source: String): WorkspaceSnapshot.FileSnapshot {
        val chunk = parseWorkspaceSource(path, source)
        val facts = documentFacts(path, source)
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

    /**
     * A single workspace pass previously parsed the same document several times (facts
     * collection, provider discovery, then per-document context). Parsing is deterministic, so
     * remember the latest chunk per path and reuse it while the source is unchanged.
     *
     * Keyed by path rather than by source text: AST nodes carry mutable `parent`/`range`, so
     * two distinct documents that happen to hold identical text must never share a chunk.
     */
    private val parsedChunks = mutableMapOf<VirtualPath, Pair<String, LuaParseResult>>()

    protected fun parseWorkspaceSource(path: VirtualPath, source: String): ChunkNode =
        parseWorkspaceResult(path, source).chunk

    /** Cached parse for [path], including the recovery diagnostics the chunk was produced with. */
    private fun parseWorkspaceResult(path: VirtualPath, source: String): LuaParseResult {
        parsedChunks[path]?.let { (cachedSource, result) ->
            if (cachedSource == source) {
                return result
            }
        }
        val result = parseWorkspaceResult(source)
        parsedChunks[path] = source to result
        return result
    }

    protected fun parseWorkspaceSource(source: String): ChunkNode = parseWorkspaceResult(source).chunk

    /**
     * One reusable parser per dialect. `parse()` resets all per-parse state, so allocating a
     * fresh parser (plus the inner snippet parser it wraps) on every keystroke was pure waste.
     */
    private var activeParser: LuaParser? = null

    private fun activeParser(): LuaParser =
        activeParser ?: parserFactory(parserVersion).also { activeParser = it }

    private fun parseWorkspaceResult(source: String): LuaParseResult {
        val parser = activeParser()
        return try {
            parser.parseWorkspaceSnippetWithDiagnostics(source)
        } catch (error: IllegalStateException) {
            // Error recovery itself gave up. Fall back to an empty document so analysis can still
            // run, but keep the failure visible as a diagnostic rather than silently reporting a
            // clean file.
            LuaParseResult(
                chunk = parser.parseWorkspaceSnippetWithDiagnostics("").chunk,
                recoveryDiagnostics = listOf(parseFailureDiagnostic(error.message))
            )
        }
    }

    /**
     * Turns a parser failure message into a positioned diagnostic.
     *
     * Messages are formatted `(line,column): detail`; when that prefix is absent the diagnostic
     * anchors at the start of the document.
     */
    private fun parseFailureDiagnostic(message: String?): LuaParserRecoveryDiagnostic {
        val text = message.orEmpty().ifEmpty { "Failed to parse source" }
        val match = PARSE_FAILURE_LOCATION.find(text)
        val line = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val column = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
        val detail = match?.groupValues?.get(3)?.takeIf(String::isNotBlank) ?: text
        return LuaParserRecoveryDiagnostic(
            message = detail,
            range = Range(Position(line, column), Position(line, column + 1))
        )
    }

    private companion object {
        val PARSE_FAILURE_LOCATION = Regex("""^\((\d+),\s*(\d+)\):\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
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
