package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParseResult
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParserRecoveryDiagnostic
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
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

        // No-op guard: no source upserts/removals and metadata either absent (delta carries no
        // metadata, so nextMetadata fell back to previous.metadata) or content-identical to the
        // previous snapshot cannot change any derived state — extraProviders (built above from
        // nextMetadata) matching the previous snapshot seals that. The identical-metadata case
        // previously fell through to a full provider pass plus graph build for nothing.
        if (delta.upserts.isEmpty() &&
            delta.removals.isEmpty() &&
            nextMetadata == previous.metadata &&
            builtinOverlay == previous.builtinOverlay &&
            extraProviders == previous.extraProviders
        ) {
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

        // A metadata change (layout completion extensions, JVM class/import configuration)
        // is baked into every per-document model at analysis time, so a metadata-only delta
        // must re-analyze the whole workspace, not just text-dirtied files. Hoisted above the
        // baseSnapshot build because the graph-reuse guard below needs it first.
        val metadataChanged = nextMetadata != previous.metadata

        // SPEC B: the module graph is derived solely from all files' documentFacts plus the
        // builtin overlay and the extra providers. When exactly one file was upserted, nothing
        // was removed, metadata/overlay/providers are unchanged, and that file's graph-relevant
        // facts signature (DocumentFacts.fingerprint, which covers requires, source imports,
        // legacy module calls, module-name candidates, dynamic requires and JVM class loads)
        // is unchanged, a rebuild would derive a structurally identical graph — reuse the
        // previous instance instead, so edge-neutral single-file edits (identifier renames,
        // literal changes, whitespace) stop rebuilding the whole module graph on every
        // keystroke. Identity (not structural equality) against EMPTY: a default-constructed
        // snapshot carries the EMPTY sentinel, which is not a graph this engine built.
        val singleUpsertPath = delta.upserts.keys.singleOrNull()
        val graph = if (singleUpsertPath != null &&
            delta.removals.isEmpty() &&
            !metadataChanged &&
            builtinOverlay == previous.builtinOverlay &&
            extraProviders == previous.extraProviders &&
            previous.graph !== WorkspaceModuleGraph.EMPTY &&
            nextFiles[singleUpsertPath]?.factsSignature == previous.files[singleUpsertPath]?.factsSignature
        ) {
            previous.graph
        } else {
            WorkspaceModuleGraphBuilder.build(nextFiles, builtinOverlay, extraProviders)
        }

        val baseSnapshot = WorkspaceSnapshot(
            files = nextFiles,
            metadata = nextMetadata,
            extraProviders = extraProviders,
            builtinOverlay = builtinOverlay,
            graph = graph
        )

        // SPEC A: the dirty-set planner compares provider-global fingerprints, but those only
        // exist after semantic analysis and planning runs before it. Upserted documents are
        // unconditionally dirty, so pre-analyze exactly them here (chunks hit the parse cache)
        // and attach their fingerprints to the base snapshots; the planner then sees real
        // global-surface values for the edited files instead of the empty "not yet analyzed"
        // default. attachSemanticState re-analyzes the upserts in dependency order against the
        // final workspace view and re-attaches the authoritative value — the extra per-upsert
        // analysis is the bounded price of making global-surface changes visible to the planner
        // within the same update that produced them.
        if (parsingTargets.isNotEmpty()) {
            val provisionalInput = LuaWorkspaceInput(
                files = nextSources,
                metadata = nextMetadata,
                standardLibraryOverlayVersion = baseSnapshot.builtinOverlay.version
            )
            parsingTargets.forEach { path ->
                analyzePathInto(nextFiles, path, nextSources, previous, provisionalInput, baseSnapshot)
            }
        }

        val dirtyPlan = WorkspaceDirtySetPlanner.plan(previous, baseSnapshot)
        val pathsToAnalyze = if (metadataChanged) {
            dirtyPlan.affectedDocuments + nextSources.keys
        } else {
            dirtyPlan.affectedDocuments
        }
        val totalFiles = parsingTargets.size + pathsToAnalyze.size

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
            pathsToAnalyze = pathsToAnalyze,
            previous = previous
        )
        retainCachedDocuments(nextSources.keys)
        reportBindingProgress(pathsToAnalyze.sortedBy { it.value }, parsingTargets.size, totalFiles, reporter)
        reporter.report(AnalysisProgress(AnalysisProgress.Phase.COMPLETE, completedFiles = totalFiles, totalFiles = totalFiles))

        return WorkspaceUpdateResult(
            snapshot = snapshot,
            changedFiles = dirtyPlan.changedFiles,
            publicSurfaceChangedFiles = dirtyPlan.publicSurfaceChangedFiles,
            activeProviderChangedModuleNames = dirtyPlan.activeProviderChangedModuleNames,
            filesWithRequireResolutionChanged = dirtyPlan.filesWithRequireResolutionChanged,
            affectedDocuments = pathsToAnalyze,
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
            overlayGlobals = snapshot.builtinOverlay.globals,
            layoutPropertyExtensions = LuaLayoutPropertiesMetadata.parse(snapshot.metadata)
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
                // Data-class copy carries the whole stored FileSnapshot forward — including the
                // analysis-derived publicFingerprint.globalSymbolsFingerprint. Files that are
                // not re-analyzed must keep the value the dirty-set planner compared them
                // against, or every subsequent update would see a false global-surface change.
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

        val analysisOrder = semanticAnalysisOrder(pathsToAnalyze, baseSnapshot.graph)
        analysisOrder.forEach { path ->
            analyzePathInto(files, path, sources, previous, analysisInput, analysisSnapshot)
        }

        // Cycle re-analysis pass. Members of a multi-member strongly connected component are
        // first analyzed against half-finished peers — whichever cycle member comes first in
        // dependency order sees `null` semantic files for the rest of its cycle — so globals
        // flowing between cycle members bind as Unknown. Now that every path has a completed
        // first pass, re-running the per-path analysis (chunks/sources are reused from the
        // parse cache; each path gets a fresh workspaceContext over this same analysis
        // snapshot) lets cycle members bind against completed peers.
        //
        // Invariant: the re-analyzed set is `analysisOrder` (⊆ pathsToAnalyze, each path once)
        // filtered to multi-member SCC members — re-analyzed ⊆ pathsToAnalyze — so this pass
        // never grows affectedDocuments, which callers derive from `pathsToAnalyze` alone.
        // When no multi-member SCC is dirty the filter is empty and the pass is skipped
        // silently; singleton workspaces and overlay/extra providers (never in
        // pathsToAnalyze) are never re-analyzed.
        val graph = baseSnapshot.graph
        val cycleReAnalysis = analysisOrder.filter { path ->
            (graph.stronglyConnectedComponentByFile[path]?.size ?: 1) > 1
        }
        if (cycleReAnalysis.isNotEmpty()) {
            // Bounded fixpoint, capped at MAX_CYCLE_REANALYSIS_PASSES sweeps. One sweep lets each
            // cycle member bind against peers that are complete *as of the sweep order*; a
            // derivation chain that runs against that order (3-cycle: a feeds b feeds c feeds a)
            // advances only one hop per sweep, so a single sweep can leave the last hop's derived
            // global Unknown. Repeat while a cheap fingerprint of the re-analyzed semantic
            // snapshots (per path: model diagnostics count + evaluated global value-type display
            // names) keeps changing, and stop as soon as it stabilizes: analysis is
            // deterministic, so an unchanged fingerprint means another sweep would be a no-op.
            // The cap bounds worst-case re-analysis cost; paths outside the cycle are never
            // re-analyzed, so affectedDocuments is unaffected either way.
            var fingerprint = cycleReAnalysisFingerprint(cycleReAnalysis, files)
            var sweep = 0
            while (sweep < MAX_CYCLE_REANALYSIS_PASSES) {
                cycleReAnalysis.forEach { path ->
                    analyzePathInto(files, path, sources, previous, analysisInput, analysisSnapshot)
                }
                sweep++
                val nextFingerprint = cycleReAnalysisFingerprint(cycleReAnalysis, files)
                val stabilized = nextFingerprint == fingerprint
                fingerprint = nextFingerprint
                if (stabilized) {
                    break
                }
            }
        }
        // SPEC C: re-point every stored workspace context at ONE resolver over the final
        // snapshot. Invariant: after this pass, every stored context's resolver sees the FINAL
        // workspace state, never a mid-update map. Analysis builds one resolver per analyzed
        // document over the in-progress analysis snapshot; left as-is, a session accumulates a
        // resolver (plus its cold caches) per file per update, each pinning that update's
        // intermediate view. Re-pointing is O(N) per update — every context is rebuilt once —
        // but bounded by workspace size.
        //
        // The resolver itself reads documentFacts, export surfaces and binders, none of which
        // re-pointing touches, so a resolver built over the pre-re-point assembly derives
        // exactly what one built over the returned snapshot does. withWorkspaceImportEffects
        // re-derives the active import set and falls back through baseResolve* — the
        // resolver-independent engine lambdas captured when the context was first composed — so
        // engine-level fallbacks survive the re-point: JvmWorkspaceEngine's resolveImportTarget
        // captures classModuleProvider/configuration (never the resolver), and its
        // resolveImportedSymbol captures the configured/source import map, so stripping the
        // composed lambdas (which DO capture the stale per-document resolver) and recomposing
        // against the final resolver loses nothing.
        val assembledSnapshot = baseSnapshot.copy(files = files.toMap())
        val finalResolver = WorkspaceModuleResolver(assembledSnapshot)
        val repointedFiles = assembledSnapshot.files.mapValues { (_, fileSnapshot) ->
            val semanticFile = fileSnapshot.semanticFile ?: return@mapValues fileSnapshot
            val storedContext = semanticFile.snapshot.workspaceContext
            // withWorkspaceImportEffects early-returns without recomposing when currentPath is
            // null, so a context lacking either piece must not have its lambdas stripped.
            if (storedContext.workspaceResolver == null || storedContext.currentPath == null) {
                return@mapValues fileSnapshot
            }
            val repointedContext = storedContext.copy(
                workspaceResolver = finalResolver,
                // The composed lambdas close over the stale per-document resolver; drop them so
                // withWorkspaceImportEffects recomposes fresh against finalResolver, falling
                // back through the resolver-independent baseResolve* chain.
                resolveImportedSymbol = null,
                resolveImportTarget = null
            ).withWorkspaceImportEffects()
            fileSnapshot.copy(
                semanticFile = WorkspaceSemanticFile(
                    path = semanticFile.path,
                    source = semanticFile.source,
                    chunk = semanticFile.chunk,
                    model = semanticFile.model,
                    snapshot = semanticFile.snapshot.copy(workspaceContext = repointedContext),
                    recoveryDiagnostics = semanticFile.recoveryDiagnostics
                )
            )
        }
        return assembledSnapshot.copy(files = repointedFiles)
    }

    /**
     * Cheap, deterministic convergence fingerprint for the cycle re-analysis pass: per
     * re-analyzed path, the semantic model's diagnostics count plus the evaluated value-type
     * display names of the path's own global declarations. Those evaluated types are exactly
     * what peers and consumers read through the module resolver, so the loop stops precisely
     * when another sweep would re-derive the same semantic state. Built per call (never
     * cached) so it always reflects the live [files] state. Declaration count and field
     * length are capped — mirroring the provider fingerprint's (globalSymbolsFingerprint)
     * pathological-file guards — so a >512-global SCC member cannot turn every convergence
     * check into a full evaluation sweep; the caps only ever merge the tail of huge global
     * surfaces, never the head, so the fixpoint comparison stays deterministic.
     */
    private fun cycleReAnalysisFingerprint(
        paths: List<VirtualPath>,
        files: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>
    ): List<String> {
        return paths.map { path ->
            val semanticFile = files[path]?.semanticFile
            val snapshot = semanticFile?.snapshot
            buildString {
                append(path.value)
                append('#')
                append(semanticFile?.model?.getDiagnostics()?.size ?: -1)
                if (snapshot != null) {
                    val evaluator = ExpressionTypeEvaluator(snapshot.binder, snapshot.workspaceContext)
                    snapshot.binder.declarationIndex.declarations
                        .asSequence()
                        .filter { it.kind == DeclarationKind.GLOBAL && it.origin == DeclarationOrigin.AST }
                        .distinctBy { it.name }
                        .take(CYCLE_REANALYSIS_DECLARATION_LIMIT)
                        .forEach { declaration ->
                            val anchor = declaration.anchorNode as? ExpressionNode ?: return@forEach
                            append('|')
                            append(declaration.name.take(CYCLE_REANALYSIS_FIELD_LIMIT))
                            append('=')
                            append(evaluator.evaluate(anchor).displayName.take(CYCLE_REANALYSIS_FIELD_LIMIT))
                        }
                }
            }
        }
    }

    /**
     * Runs the semantic pipeline for one [path] and stores the resulting [WorkspaceSemanticFile]
     * into [files]. Shared by the main ordered analysis pass and the cycle re-analysis pass;
     * the chunk/source come from the parse cache when the source is unchanged.
     */
    private fun analyzePathInto(
        files: MutableMap<VirtualPath, WorkspaceSnapshot.FileSnapshot>,
        path: VirtualPath,
        sources: Map<VirtualPath, String>,
        previous: WorkspaceSnapshot?,
        analysisInput: LuaWorkspaceInput,
        analysisSnapshot: WorkspaceSnapshot
    ) {
        val fileSnapshot = files[path] ?: return
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
        // SPEC A: attach the provider-global surface fingerprint derived from this document's
        // bound globals. Only semantic analysis can derive it, so it is joined onto the stored
        // publicFingerprint copy here rather than in analyzeFile; the cycle re-analysis pass and
        // the update() pre-plan upsert pass both run through this same store, so every stored
        // snapshot ends up with the authoritative value for its final semantic state. The
        // evaluator is the same expression evaluator the module resolver uses for provider
        // globals, so the fingerprint tracks exactly what consumers re-bind through it.
        val globalTypeEvaluator = ExpressionTypeEvaluator(semanticSnapshot.binder, semanticSnapshot.workspaceContext)
        val publicFingerprint = (fileSnapshot.publicFingerprint
            ?: WorkspacePublicFingerprint.from(fileSnapshot.documentFacts, fileSnapshot.moduleExportSurface))
            .copy(
                globalSymbolsFingerprint = globalSymbolsFingerprint(
                    semanticSnapshot.binder.declarationIndex.declarations,
                    globalTypeEvaluator
                )
            )
        files[path] = fileSnapshot.copy(publicFingerprint = publicFingerprint, semanticFile = semanticFile)
    }

    private fun semanticAnalysisOrder(
        pathsToAnalyze: Set<VirtualPath>,
        graph: WorkspaceModuleGraph
    ): List<VirtualPath> {
        val visiting = mutableSetOf<VirtualPath>()
        val visited = mutableSetOf<VirtualPath>()
        val ordered = mutableListOf<VirtualPath>()
        // Explicit-stack post-order DFS: one frame per in-progress visit holding the node plus
        // its dependency providers in deterministic path-value order, with a cursor to the next
        // child. The recursive visit it replaces emitted the same post-order but nested one
        // call frame per chain link, which overflowed the call stack on deep require chains
        // (thousands of files). `visiting` is the on-path set: a child already on the path is
        // a cycle and is skipped exactly like the recursive early return.
        val stack = ArrayDeque<Pair<VirtualPath, Iterator<VirtualPath>>>()

        fun childrenOf(path: VirtualPath): Iterator<VirtualPath> =
            graph.resolvedDependencies[path]
                .orEmpty()
                .map { it.provider.path }
                .sortedBy { it.value }
                .iterator()

        fun visit(path: VirtualPath) {
            if (path !in pathsToAnalyze || path in visited || !visiting.add(path)) {
                return
            }
            stack.addLast(path to childrenOf(path))
            while (stack.isNotEmpty()) {
                val (node, children) = stack.last()
                val child = if (children.hasNext()) children.next() else null
                if (child != null) {
                    if (child !in pathsToAnalyze || child in visited || !visiting.add(child)) {
                        continue
                    }
                    stack.addLast(child to childrenOf(child))
                } else {
                    // All children emitted (or none): post-order emission, as before.
                    stack.removeLast()
                    visiting -= node
                    visited += node
                    ordered += node
                }
            }
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
            factsSignature = graphReuseRangeSignature(facts),
            legacyModuleEnvironment = legacyEnvironment,
            moduleExportSurface = exportSurface,
            publicFingerprint = publicFingerprint
        )
    }

    /**
     * Signature the module-graph reuse guard compares (SPEC B in [update]): the document facts
     * fingerprint PLUS a hash over the graph-relevant fact ranges. [DocumentFacts.fingerprint]
     * hashes requires / source imports / dynamic requires by NAME only, but the graph bakes
     * each fact's range into its entries (resolved dependencies, unresolved requires, dynamic
     * require sites), so a range-only edit — inserting a line above a `require` — used to pass
     * the guard and leave the reused graph holding stale positions. Hashing the line/column
     * ints alongside the name fingerprint makes range-only edits rebuild the graph. No query
     * reads those ranges today; this closes the latent trap at the only seam that compares
     * these signatures.
     */
    private fun graphReuseRangeSignature(facts: DocumentFacts): String {
        val rangePayload = buildString {
            facts.requires.forEach { appendRangeFact('R', it.moduleName, it.range) }
            facts.sourceImports.forEach { appendRangeFact('I', it.target, it.range) }
            facts.dynamicRequires.forEach { appendRangeFact('D', it.kind.name, it.range) }
        }
        return facts.fingerprint + workspaceFingerprintHash(rangePayload)
    }

    private fun StringBuilder.appendRangeFact(kind: Char, name: String, range: Range) {
        append(kind)
        append(name)
        append('@')
        append(range.start.line)
        append(',')
        append(range.start.column)
        append('-')
        append(range.end.line)
        append(',')
        append(range.end.column)
        append('\n')
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

        /**
         * Upper bound on cycle re-analysis sweeps per workspace pass. Each sweep advances
         * cross-cycle type propagation at least one hop along the sweep order, so the cap
         * covers chained derivations around small strongly connected components while keeping
         * worst-case re-analysis work at a small constant multiple of a single pass.
         */
        const val MAX_CYCLE_REANALYSIS_PASSES = 3

        /**
         * Caps for the cycle re-analysis fingerprint ([cycleReAnalysisFingerprint]), mirroring
         * the provider fingerprint's caps (GLOBAL_FINGERPRINT_DECLARATION_LIMIT /
         * GLOBAL_FINGERPRINT_FIELD_LIMIT in WorkspacePublicFingerprint).
         */
        const val CYCLE_REANALYSIS_DECLARATION_LIMIT = 512
        const val CYCLE_REANALYSIS_FIELD_LIMIT = 256
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
