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
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot

open class LuaWorkspaceEngine(
    private val parserFactory: (LuaVersion) -> LuaParser = { version -> LuaParser(version) }
) {
    private val commentAttachPass = CommentAttachPass()

    /**
     * Cancellation hook fired at per-file analysis boundaries. Interactive
     * engines (the LSP live-buffer path) override it to abort a stale
     * in-flight analysis the moment the buffers it is analyzing change;
     * without it a superseding edit waits for the entire previous analysis.
     */
    protected open fun checkAnalysisCancellation() {}
    private val semanticPipeline = SemanticPipeline(commentAttachPass = commentAttachPass)

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
        documentRevisions.clear()
    }

    protected open val includeAndroidFramework: Boolean = true
    protected open val checkDiagnostics: Boolean = true

    /** Independent front-end work only; workspace-dependent resolution stays ordered. */
    protected open fun <T> mapIndependentDocuments(
        paths: List<VirtualPath>,
        action: (VirtualPath) -> T
    ): List<T> = paths.map(action)

    /** One hook for measuring the real passes without changing their ownership. */
    protected open fun <T> analysisStage(stage: String, path: VirtualPath? = null, action: () -> T): T = action()

    private fun builtinOverlay(version: LuaVersion): BuiltinOverlaySnapshot =
        BuiltinOverlayLoader.compiledRevision(version, includeAndroidFramework)

    open fun build(
        input: LuaWorkspaceInput,
        reporter: ProgressReporter = ProgressReporter.NONE
    ): WorkspaceUpdateResult {
        useParserVersion(input.standardLibraryOverlayVersion)
        analysisStage("parse") { prepareDocumentParses(input.files) }
        val sortedFiles = input.files.keys.sortedBy { it.value }
        val baseSnapshots = linkedMapOf<VirtualPath, WorkspaceSnapshot.FileSnapshot>()
        val builtinOverlay = analysisStage("builtins") { builtinOverlay(input.standardLibraryOverlayVersion) }
        val extraProviders = analysisStage("providers") { extraProviders(input) }
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
            checkAnalysisCancellation()
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
            graph = analysisStage("graph") { WorkspaceModuleGraphBuilder.build(baseSnapshots, builtinOverlay, extraProviders) }
        )
        val snapshot = attachSemanticState(
            baseSnapshot = baseSnapshot,
            sources = input.files,
            pathsToAnalyze = sortedFiles.toSet(),
            previous = null,
            reportBinding = bindingReporter(sortedFiles.size, totalFiles, reporter),
            preparedBindings = prepareBindings(sortedFiles, input.files, builtinOverlay)
        )
        val affectedDocuments = sortedFiles.toSet()
        retainCachedDocuments(input.files.keys)
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
        analysisStage("parse") { prepareDocumentParses(delta.upserts) }
        val builtinOverlay = analysisStage("builtins") { builtinOverlay(standardLibraryOverlayVersion) }
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

        val extraProviders = analysisStage("providers") { extraProviders(
            LuaWorkspaceInput(
                files = nextSources,
                metadata = nextMetadata,
                standardLibraryOverlayVersion = standardLibraryOverlayVersion
            )
        ) }

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
            checkAnalysisCancellation()
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
            analysisStage("graph") { WorkspaceModuleGraphBuilder.build(nextFiles, builtinOverlay, extraProviders) }
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
        // binding is reused in the authoritative pass after planning.
        val preparedBindings = prepareBindings(parsingTargets, nextSources, builtinOverlay)
        if (parsingTargets.isNotEmpty()) {
            // LIGHT pre-plan pass: the planner only needs each upsert's global-symbols
            // fingerprint, so run bind+resolve WITHOUT the checker/model (the two dominant
            // analysis costs) instead of a full analyzePathInto.
            parsingTargets.forEach { path ->
                val fileSnapshot = nextFiles[path] ?: return@forEach
                val resolvedBinder = preparedBindings[path] ?: return@forEach
                nextFiles[path] = fileSnapshot.withGlobalSymbolsFingerprint(path,
                    resolvedBinder
                )
            }
        }

        val dirtyPlan = analysisStage("invalidation") { WorkspaceDirtySetPlanner.plan(previous, baseSnapshot) }
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
            previous = previous,
            upserts = delta.upserts.keys,
            reportBinding = bindingReporter(parsingTargets.size, totalFiles, reporter),
            preparedBindings = preparedBindings
        )
        retainCachedDocuments(nextSources.keys)
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

    internal open fun createWorkspaceResolver(snapshot: WorkspaceSnapshot): WorkspaceModuleResolver =
        WorkspaceModuleResolver(snapshot) { candidate -> cachedChunkFor(snapshot.files, candidate) }

    internal open fun workspaceContext(
        input: LuaWorkspaceInput,
        path: VirtualPath,
        snapshot: WorkspaceSnapshot
    ): SemanticWorkspaceContext {
        return SemanticWorkspaceContext(
            currentPath = path,
            workspaceResolver = createWorkspaceResolver(snapshot),
            overlayGlobals = snapshot.builtinOverlay.globals,
            layoutPropertyExtensions = LuaLayoutPropertiesMetadata.parse(snapshot.metadata)
        )
    }

    private fun attachSemanticState(
        baseSnapshot: WorkspaceSnapshot,
        sources: Map<VirtualPath, String>,
        pathsToAnalyze: Set<VirtualPath>,
        previous: WorkspaceSnapshot?,
        upserts: Set<VirtualPath> = pathsToAnalyze,
        reportBinding: ((VirtualPath, Int) -> Unit)? = null,
        preparedBindings: Map<VirtualPath, BinderPassResult> = emptyMap()
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
        analysisOrder.forEachIndexed { index, path ->
            checkAnalysisCancellation()
            reportBinding?.invoke(path, index)
            analyzePathInto(files, path, sources, previous, analysisInput, analysisSnapshot, preparedBindings[path])
            checkAnalysisCancellation()
            reportBinding?.invoke(path, index + 1)
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
            val scc = graph.stronglyConnectedComponentByFile[path] ?: return@filter false
            if (scc.size <= 1) return@filter false
            // A second sweep only matters when a SCC PEER is also being re-analyzed in
            // THIS update: peers carried over from the previous snapshot are already
            // complete, so the first pass bound against their final state and the sweep
            // would deterministically re-produce it (a full pipeline run per keystroke
            // for single-file edits otherwise).
            // Compare against UPSERTS (actually re-edited files), not the affected set:
            // an affected peer carries its completed semantic file from the previous
            // snapshot, so the first pass already bound against its final state.
            scc.any { peer -> peer != path && peer in upserts }
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
                    reportBinding?.invoke(path, analysisOrder.size)
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
        // Published models and checkers must read the same finalized dependency view.
        // Only dirty documents receive new query state; unaffected immutable revisions
        // retain their warmed model because the planner proved their inputs unchanged.
        val assembledSnapshot = baseSnapshot.copy(files = files.toMap())
        val finalResolver = createWorkspaceResolver(assembledSnapshot)
        val repointedFiles = assembledSnapshot.files.mapValues { (path, fileSnapshot) ->
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
            val repointed = if (path in pathsToAnalyze) {
                semanticPipeline.withWorkspaceContext(semanticFile.snapshot, repointedContext)
            } else semanticFile.snapshot.copy(workspaceContext = repointedContext)
            fileSnapshot.copy(
                semanticFile = WorkspaceSemanticFile(
                    path = semanticFile.path,
                    source = semanticFile.source,
                    chunk = semanticFile.chunk,
                    model = repointed.model,
                    snapshot = repointed,
                    recoveryDiagnostics = semanticFile.recoveryDiagnostics
                )
            )
        }
        return assembledSnapshot.copy(files = repointedFiles)
    }

    /** Complete evaluated global display state for the bounded SCC fixpoint sweeps. */
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
                        .forEach { declaration ->
                            val anchor = declaration.anchorNode as? ExpressionNode ?: return@forEach
                            append('|')
                            append(declaration.name)
                            append('=')
                            append(evaluator.evaluate(anchor).displayName)
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
        analysisSnapshot: WorkspaceSnapshot,
        preparedBinder: BinderPassResult? = null
    ) {
        val fileSnapshot = files[path] ?: return
        val carriedOver = previous?.files?.get(path)?.semanticFile ?: fileSnapshot.semanticFile
        // Reuse the carried-over parse (and its diagnostics) when this pass has no source for
        // the path; only fall back to an empty parse when there is nothing at all.
        val parsed = sources[path]?.let { source -> parseWorkspaceResult(path, source) }
        val chunk = parsed?.chunk ?: carriedOver?.chunk ?: parseWorkspaceSource("")
        val context = analysisStage("context", path) { workspaceContext(analysisInput, path, analysisSnapshot) }
        val comments = documentRevisions.comments(path) { commentAttachPass.attach(chunk) }
        val semanticSnapshot = analysisStage("semantic", path) {
            semanticPipeline.analyzeSnapshot(chunk, context, checkDiagnostics, preparedBinder, comments)
        }
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
        // token range index captures complete source writes without invoking the evaluator.
        files[path] = fileSnapshot
            .withGlobalSymbolsFingerprint(path, semanticSnapshot.binder)
            .copy(semanticFile = semanticFile)
    }

    /**
     * Joins the analysis-derived global-surface fingerprint onto this snapshot's stored
     * [WorkspaceSnapshot.FileSnapshot.publicFingerprint], creating the document-facts baseline
     * when none was stored yet. Shared by [analyzePathInto] (full + cycle re-analysis passes)
     * and the update() pre-plan upsert pass so both join the value identically.
     */
    private fun WorkspaceSnapshot.FileSnapshot.withGlobalSymbolsFingerprint(
        path: VirtualPath,
        binder: BinderPassResult
    ): WorkspaceSnapshot.FileSnapshot = copy(
        publicFingerprint = (publicFingerprint
            ?: WorkspacePublicFingerprint.from(documentFacts, moduleExportSurface))
            .copy(globalSymbolsFingerprint = globalSymbolsFingerprint(binder.scopeGraph.globalWrites, documentRevisions.require(path).tokenFingerprint),
                globalWriteNames = binder.scopeGraph.globalWriteNames)
    )

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
    protected fun documentFacts(path: VirtualPath, source: String): DocumentFacts {
        val parsed = parseWorkspaceResult(path, source)
        val revision = documentRevisions.require(path)
        revision.facts?.let { return it }
        val facts = analysisStage("facts", path) {
            DocumentFactsCollector.collect(path, parsed.chunk)
        }
        revision.facts = facts
        return facts
    }

    /** Drops per-path caches for documents that left the workspace. */
    private fun retainCachedDocuments(paths: Set<VirtualPath>) {
        documentRevisions.retain(paths)
    }

    private fun analyzeFile(path: VirtualPath, source: String): WorkspaceSnapshot.FileSnapshot {
        val chunk = parseWorkspaceSource(path, source)
        val facts = documentFacts(path, source)
        val legacyEnvironment = LegacyModuleEnvironmentPass.analyze(path, facts)
        val exportSurface = analysisStage("exports", path) {
            ModuleExportCollector.collect(chunk, facts, legacyEnvironment)
        }
        val publicTypeAnnotations = source.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("---@") }
            .joinToString("\n")
        val publicFingerprint = WorkspacePublicFingerprint.from(facts, exportSurface, publicTypeAnnotations)

        return WorkspaceSnapshot.FileSnapshot(
            cacheKey = documentRevisions.require(path).fingerprint,
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
    private val documentRevisions = DocumentRevisionStore()

    private fun prepareDocumentParses(sources: Map<VirtualPath, String>) {
        val missing = sources.keys.filterNot { documentRevisions.matches(it, sources.getValue(it)) }.sortedBy { it.value }
        if (missing.size < 2) {
            missing.forEach { parseWorkspaceResult(it, sources.getValue(it)) }
            return
        }
        val parsed = mapIndependentDocuments(missing) { path ->
            analysisStage("parse-file", path) {
                parseWorkspaceResult(sources.getValue(path), parserFactory(parserVersion))
            }
        }
        // Only the engine owner mutates the cache; workers own their parser and AST.
        missing.zip(parsed).forEach { (path, result) -> documentRevisions.put(path, sources.getValue(path), result) }
    }

    private fun prepareBindings(
        paths: List<VirtualPath>,
        sources: Map<VirtualPath, String>,
        overlay: BuiltinOverlaySnapshot
    ): Map<VirtualPath, BinderPassResult> {
        val chunks = paths.associateWith { parseWorkspaceResult(it, sources.getValue(it)).chunk }
        return paths.zip(mapIndependentDocuments(paths) { path ->
            // TypeResolver has mutable per-pass state; never share it between workers.
            analysisStage("binding", path) {
                val comments = documentRevisions.comments(path) {
                    commentAttachPass.attach(chunks.getValue(path))
                }
                // TypeResolver owns mutable per-pass expansion state. Keep the revision inputs
                // shared, but give each bounded worker its own pipeline instance.
                SemanticPipeline(commentAttachPass = commentAttachPass)
                    .bindForGlobalFingerprint(chunks.getValue(path), overlay.globals, comments)
            }
        }).toMap()
    }

    protected fun parseWorkspaceSource(path: VirtualPath, source: String): ChunkNode =
        parseWorkspaceResult(path, source).chunk

    /**
     * Parse-cache chunk lookup for [path], valid only while the snapshot's stored source is
     * the one the chunk was parsed from (cacheKey comparison). Backs the module resolver's
     * layout-path resolution: non-queried documents keep `semanticFile = null` in the
     * snapshot, but their parsed chunk is here.
     */
    protected fun cachedChunkFor(files: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>, path: VirtualPath): ChunkNode? {
        val fileSnapshot = files[path] ?: return null
        val cached = documentRevisions[path] ?: return null
        return if (cached.fingerprint == fileSnapshot.cacheKey) cached.parsed.chunk else null
    }

    /** Cached parse for [path], including the recovery diagnostics the chunk was produced with. */
    private fun parseWorkspaceResult(path: VirtualPath, source: String): LuaParseResult {
        documentRevisions[path]?.let { revision ->
            if (revision.source == source) {
                return revision.parsed
            }
        }
        val result = analysisStage("parse-file", path) { parseWorkspaceResult(source) }
        documentRevisions.put(path, source, result)
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

    private fun parseWorkspaceResult(source: String, parser: LuaParser = activeParser()): LuaParseResult {
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
        // `[\s\S]` spans newlines portably — RegexOption.DOT_MATCHES_ALL is JVM-only and
        // breaks the JS target compilation of this common source set.
        val PARSE_FAILURE_LOCATION = Regex("""^\((\d+),\s*(\d+)\):\s*([\s\S]*)$""")

        /**
         * Upper bound on cycle re-analysis sweeps per workspace pass. Each sweep advances
         * cross-cycle type propagation at least one hop along the sweep order, so the cap
         * covers chained derivations around small strongly connected components while keeping
         * worst-case re-analysis work at a small constant multiple of a single pass.
         */
        const val MAX_CYCLE_REANALYSIS_PASSES = 3

    }

    private fun bindingReporter(
        changedFileCount: Int,
        totalFiles: Int,
        reporter: ProgressReporter
    ): ((VirtualPath, Int) -> Unit)? {
        if (reporter === ProgressReporter.NONE) return null
        return { path, completed ->
            reporter.report(
                AnalysisProgress(
                    phase = AnalysisProgress.Phase.BINDING,
                    currentFile = path,
                    completedFiles = changedFileCount + completed,
                    totalFiles = totalFiles
                )
            )
        }
    }
}
