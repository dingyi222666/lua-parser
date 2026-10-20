package io.github.dingyi222666.luaparser.lsp

import java.util.concurrent.TimeUnit

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.source.AST2Lua
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Range

import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.model.NodePositionIndex
import io.github.dingyi222666.luaparser.semantic.binder.comparePositions
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceLocation

import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSemanticFile
import io.github.dingyi222666.luaparser.semantic.api.DIAGNOSTIC_TAG_UNNECESSARY
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind as SemanticSymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.AnalysisProgress
import io.github.dingyi222666.luaparser.semantic.workspace.ProgressReporter
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDocumentSymbol
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSymbolEntry
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceUpdateResult
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.CompletableFuture

class LuaLanguageService(
    engine: JvmWorkspaceEngine? = null,
    initialWorkspaceMetadata: Map<String, String> = emptyMap(),
    private val deferDocumentAnalysis: Boolean = false
) {
    private val stateLock = Any()
    @Volatile private var closed = false

    private companion object {
        /** Burst-edit coalescing window before a deferred live-buffer analysis starts. */
        private const val LIVE_ANALYSIS_DEBOUNCE_MS = 400L

        /**
         * How long a completion issued against a not-yet-republished buffer waits
         * for the in-flight live analysis. Warm republications land in 0.4–1.5s on
         * the device, so the grace keeps every interactive edit path fully fresh;
         * a cold analysis (measured ~11s for one 5KB document on the emulated
         * host) instead gets served from the last published facade at grace
         * expiry — an unbounded wait blew the client's whole 10s request budget
         * AND pinned one of the two completion workers for that entire time.
         * Polling clients re-request and pick up the publication.
         */
        private const val COMPLETION_PUBLICATION_GRACE_MS = 2500L
    }
    private fun checkConnectionOpen() {
        if (closed) throw java.util.concurrent.CancellationException("Language server closed")
    }
    private val engine = engine ?: object : JvmWorkspaceEngine(
        classModuleProvider = io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider(
            deferPackageMemberReflection = deferDocumentAnalysis,
            analysisCheckpoint = ::checkConnectionOpen
        ),
        analysisCheckpoint = ::checkConnectionOpen,
        deferDependencyGlobals = deferDocumentAnalysis,
        workspaceParallelism = if (deferDocumentAnalysis)
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 8) else 1
    ) {
        // Interactive clients need the complete binding before checker work.
        // The diagnostic worker checks open documents against that binding.
        override val checkDiagnostics = !deferDocumentAnalysis
    }
    private var nextQueryPublication = CompletableFuture<Unit>()
    // Two workers: the LSP client supersedes requests while an earlier one is still
    // running (every poll is a new request), and a single worker made the newest
    // request queue behind the stale one's full enumeration (measured 6.5s queue
    // wait + 7.4s execution against a 10s client budget). Completions are read-only
    // facade queries, so two concurrent evaluations are safe; the bound keeps
    // device CPU for the analysis workers.
    private val completionExecutor = java.util.concurrent.Executors.newFixedThreadPool(2) { task ->
        Thread(task, "lsp-completion").apply { isDaemon = true }
    }
    // Fires the publication-wait grace deadlines. One shared daemon thread; the
    // completion workers must never sleep while waiting for a republication.
    private val publicationGraceScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "lsp-completion-grace").apply { isDaemon = true }
        }

    private fun signalQueryPublication() {
        val published = nextQueryPublication
        nextQueryPublication = CompletableFuture()
        published.complete(Unit)
    }
    private val cancellationReporter = ProgressReporter {
        checkConnectionOpen()
    }

    /** Ends background work when the owning protocol connection shuts down. */
    internal fun close() {
        closed = true
        onBuildProgress = null; onLiveAnalysisProgress = null
        onLiveDiagnosticsFileChecked = null; onDocumentDiagnostics = null
        backgroundRebuildThread?.interrupt()
        synchronized(documentRefreshLock) { documentRefreshPending = false }
        synchronized(liveDiagnosticLock) {
            pendingLiveDiagnostics = null
            pendingEditedCheck = null
            checkedLiveDiagnostics.clear()
            lastDiagnosticFiles = emptyMap()
        }
        synchronized(stateLock) {
            nextQueryPublication.completeExceptionally(java.util.concurrent.CancellationException("Language server closed"))
        }
        completionExecutor.shutdownNow()
        publicationGraceScheduler.shutdownNow()
    }

    /**
     * Position indexes are derived purely from a chunk, but rename/call-hierarchy walks look up
     * one identifier per reference site and previously rebuilt the whole index each time.
     * Remember the index for the most recent chunk; all accesses are under [stateLock].
     */
    private var cachedIndexChunk: ChunkNode? = null
    private var cachedIndex: NodePositionIndex? = null

    private fun positionIndexFor(chunk: ChunkNode): NodePositionIndex {
        cachedIndex?.takeIf { cachedIndexChunk === chunk }?.let { return it }
        return NodePositionIndex(chunk).also {
            cachedIndexChunk = chunk
            cachedIndex = it
        }
    }

    private var snapshot: WorkspaceSnapshot = WorkspaceSnapshot()
    @Volatile
    private var publishedQueries: LuaWorkspaceQueryFacade? = null
    private var queries: LuaWorkspaceQueryFacade
        get() = publishedQueries ?: synchronized(stateLock) {
            publishedQueries ?: LuaWorkspaceQueryFacade(snapshot).also { publishedQueries = it }
        }
        set(value) { publishedQueries = value }
    private val indexedWorkspaceFiles = linkedMapOf<VirtualPath, String>()
    private val indexedWorkspaceUris = linkedMapOf<VirtualPath, String>()
    private val workspaceFolderUriPrefixes = linkedMapOf<String, String>()
    private val openDocuments = linkedMapOf<VirtualPath, String>()
    private val documentUris = linkedMapOf<VirtualPath, String>()
    /** Last full semantic-tokens payload per path for delta requests (TASK-252). */
    private val semanticTokensCache = linkedMapOf<VirtualPath, CachedSemanticTokens>()
    private var semanticTokensResultSeq: Long = 0L
    private var workspaceMetadata: Map<String, String> = initialWorkspaceMetadata.toMap()
    private var workspaceFolders: List<WorkspaceFolder> = emptyList()
    /** Captured at initialize: `documentSymbol.hierarchicalDocumentSymbolSupport` → Either.right nested symbols. */
    private var hierarchicalDocumentSymbolSupport: Boolean = false
    /** Captured at initialize: `workspace.symbol.resolveSupport` present → Either.right WorkspaceSymbol lists. */
    private var modernWorkspaceSymbolSupport: Boolean = false
    /** Captured at initialize: `definition.linkSupport` → Either.right LocationLink (never invented). */
    private var definitionLinkSupport: Boolean = false
    /** Files last applied to [snapshot] via build/update (indexed + open overlays). */
    private var lastSyncedFiles: Map<VirtualPath, String> = emptyMap()
    @Volatile
    private var snapshotReady: Boolean = false

    /** Counts full engine.build invocations (initialize / metadata invalidation). Test-visible. */
    internal var fullRebuildCount: Int = 0
        private set
    /** Counts engine.update delta invocations for open/change/close/watched paths. Test-visible. */
    internal var incrementalUpdateCount: Int = 0
        private set

    fun initialize(params: InitializeParams): InitializeResult = synchronized(stateLock) {
        // Capture the three client capability signals that pick Either.right payloads:
        // hierarchical DocumentSymbol support, workspace/symbol resolveSupport (modern
        // WorkspaceSymbol) and definition linkSupport (LocationLink).
        hierarchicalDocumentSymbolSupport =
            params.capabilities?.textDocument?.documentSymbol?.hierarchicalDocumentSymbolSupport == true
        modernWorkspaceSymbolSupport = params.capabilities?.workspace?.symbol?.resolveSupport != null
        definitionLinkSupport = params.capabilities?.textDocument?.definition?.linkSupport == true
        workspaceFolders = configuredWorkspaceFolders(params)
        refreshWorkspaceFolderUriPrefixes()
        refreshWorkspaceFolderIndex()
        // The full workspace build (corpus analyze + dex mount) takes 13-18s on a
        // phone — running it here blocks the initialize RESPONSE and every
        // subsequent request serializes behind stateLock (completions time out,
        // the editor looks dead). Answer immediately with an empty snapshot:
        // didOpen documents still resolve through openDocuments overlays, and
        // the authoritative build runs on a background thread.
        InitializeResult(serverCapabilities()).also {
            warmBuiltinOverlay()
            scheduleBackgroundRebuild()
        }
    }

    private val builtinOverlayWarmed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Counts down when the builtin overlay compile finishes; dex warmup serializes behind it. */
    private val overlayCompileGate = java.util.concurrent.CountDownLatch(1)

    /**
     * Pre-compiles the live path's builtin overlay on a background thread at
     * initialize. The compiled revision is a process-wide static cache whose first
     * build measured 10.3s on the E2E emulator — until warmed, it ran as an
     * unabortable stage INSIDE the first live analysis, pushing that analysis's
     * first cancellation point past every interactive budget. Warming overlaps it
     * with the client's own startup work; the first analysis then joins the cache.
     */
    private fun warmBuiltinOverlay() {
        if (!builtinOverlayWarmed.compareAndSet(false, true)) return
        // The bounded-parallel overlay compile is wired OFF on this host, measured:
        // 4 compile workers on the TCG emulator ran 10.0-16.2s vs 8.8-10.3s
        // sequential (rel43-49) — the workers' allocation rate thrashes the
        // emulator's concurrent-copying GC, which serializes them anyway. The
        // injectable hook stays for hosts with real cores.
        // The globals-document analysis is the dominant sequential tail after the
        // per-module loop: one ~21KB concatenated _G document (lua53 global.lua +
        // androlua _G.lua + compat) paying a full parse + bind + facts pass, then a
        // per-global type evaluation during the surface merge. It reads no per-module
        // state, so run it on its own daemon worker concurrently with the 4-worker
        // module pool and join after the loop (the surface merge still needs
        // providerModuleTypes, so it stays on the caller thread).
        io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
            .parallelGlobalsAnalyze = { unit ->
            val task = java.util.concurrent.FutureTask(java.util.concurrent.Callable { unit() })
            Thread(task, "lsp-overlay-globals").apply { isDaemon = true; start() }
            // Preserve the sequential path's failure semantics: surface the root cause
            // so warmup's runCatching logs the real error, not a wrapper.
            val join: () -> io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot.FileSnapshot = {
                try {
                    task.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
            join
        }
        // Surface assembly inside the dex adapter must observe the same per-thread
        // checkpoints as the engines: a framework class surface is assembled from
        // hundreds of descriptor translations (2-8s per class on the emulator) and
        // a superseded check assembling it must abort mid-class.
        io.github.dingyi222666.luaparser.interop.jvm.DexClassModelAdapter.analysisCheckpoint =
            ::checkLiveAnalysisCurrent
        Thread({
            runCatching {
                io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
                    .compiledRevision(LuaVersion.ANDROLUA_5_3, includeFramework = false)
            }.onFailure { System.err.println("LSP: builtin overlay warmup failed: $it") }
            overlayCompileGate.countDown()
            // Then pre-assemble the workspace's own Java surfaces: the first check of
            // a framework-importing document otherwise pays dozens of dex surface
            // assemblies inside a single client request budget.
            val sources = synchronized(stateLock) { indexedWorkspaceFiles.values.toList() }
            val metadata = synchronized(stateLock) { workspaceMetadata.toMap() }
            runCatching { liveBufferEngine.warmJavaSurfaces(sources, metadata) }
                .onFailure { System.err.println("LSP: java surface warmup failed: $it") }
        }, "lsp-builtin-warmup").apply { isDaemon = true; start() }
    }

    /**
     * Test/device hook: blocks until the background build flips [snapshotReady].
     * Returns true when ready, false on timeout.
     */
    internal fun awaitWorkspaceReady(timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!snapshotReady && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return snapshotReady
    }

    /**
     * Runs the authoritative workspace build off the request path. While it
     * runs, requests serve from openDocuments overlays (empty snapshot).
     * Completion of the build flips [snapshotReady], after which cross-file
     * resolution (dex classes, workspace globals) lights up.
     */
    private val rebuildLock = Any()
    private var backgroundRebuildRunning = false
    private var backgroundRebuildThread: Thread? = null
    private val firstLivePublication = java.util.concurrent.CountDownLatch(1)
    private fun scheduleBackgroundRebuild() {
        synchronized(rebuildLock) {
            if (closed || backgroundRebuildRunning) {
                return
            }
            backgroundRebuildRunning = true
        }
        val thread = Thread({
            try {
                if (deferDocumentAnalysis) {
                    // Wait for actual foreground publication. A fixed head start
                    // expired while the cold parser was still running, making the
                    // two engines contend for CPU and shared builtin templates.
                    // Clients that never open a buffer still receive a full index;
                    // a broken/continuously edited live buffer cannot starve it.
                    if (!firstLivePublication.await(8, TimeUnit.SECONDS)) {
                        val hasOpenBuffers = synchronized(stateLock) { openDocuments.isNotEmpty() }
                        if (hasOpenBuffers) firstLivePublication.await(30, TimeUnit.SECONDS)
                    }
                }
                if (closed) return@Thread
                runBackgroundRebuild()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: java.util.concurrent.CancellationException) {
            } finally {
                synchronized(rebuildLock) { backgroundRebuildRunning = false }
            }
        }, "lsp-workspace-build")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        backgroundRebuildThread = thread
        thread.start()
    }

    /**
     * Test/device hook: runs the pending background rebuild synchronously and
     * returns once snapshotReady is true. No-op when nothing is pending.
     */
    internal fun flushBackgroundRebuild(timeoutMs: Long = 60_000): Boolean {
        val thread = synchronized(rebuildLock) { backgroundRebuildThread }
        thread?.takeIf { it.isAlive }?.join(timeoutMs)
        if (closed) return false
        // If the thread was discarded by the stale-swap guard, run one
        // synchronous build so observers see a settled snapshot.
        if (thread?.isAlive == true) return false
        if (!snapshotReady) {
            synchronized(stateLock) { rebuildFullLocked(currentWorkspaceFiles()) }
        }
        return synchronized(stateLock) { snapshotReady }
    }

    private val engineLock = Any()
    private class ObsoleteLiveAnalysis : java.util.concurrent.CancellationException()
    private val liveAnalysisCheckpoint = ThreadLocal<() -> Unit>()
    internal var onLiveAnalysisProgress: ((AnalysisProgress) -> Unit)? = null

    private fun checkLiveAnalysisCurrent() {
        liveAnalysisCheckpoint.get()?.invoke()
    }

    private val liveBufferEngine = object : JvmWorkspaceEngine(
        classModuleProvider = io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider(
            deferPackageMemberReflection = true,
            analysisCheckpoint = ::checkLiveAnalysisCurrent
        ),
        analysisCheckpoint = ::checkLiveAnalysisCurrent,
        deferDependencyGlobals = true,
        deferWildcardPackageIndex = true
    ) {
        override val includeAndroidFramework = false
        override val checkDiagnostics = false

        // The live path is the latency-critical one and the emulated device hides
        // costs that the JVM mirror cannot reproduce (a cold first analysis measured
        // ~11s whose phase split was unobservable). Always log live stages.
        override fun <T> analysisStage(stage: String, path: VirtualPath?, action: () -> T): T {
            val started = System.nanoTime()
            return try { action() } finally {
                System.err.println(
                    "LSP-DEVICE: live-stage $stage path=${path?.value ?: "workspace"} " +
                        "ms=${(System.nanoTime() - started) / 1_000_000}"
                )
            }
        }
    }

    private var liveBufferSnapshot: WorkspaceSnapshot? = null

    private fun runBackgroundRebuild() {
        // Do the expensive parser/dex work without holding stateLock. Requests
        // can keep updating the open-document overlay while the cold index is
        // built; publish the result only if its generation is still current.
        val (files, metadata) = synchronized(stateLock) {
            currentWorkspaceFiles() to workspaceMetadata
        }
        var result = buildWorkspace(files, metadata)
        var builtFiles = files
        while (true) {
            var settled = false
            var diagnostics: List<PublishDiagnosticsParams> = emptyList()
            var diagnosticFiles: Map<VirtualPath, String> = emptyMap()
            val latestFiles = synchronized(stateLock) {
                if (closed || workspaceMetadata != metadata) return
                val latest = currentWorkspaceFiles()
                if (latest == builtFiles) {
                    fullRebuildCount += 1
                    applyWorkspaceResult(result, builtFiles)
                    settled = true
                    if (deferDocumentAnalysis) {
                        diagnosticFiles = openDocuments.toMap()
                        diagnostics = openDocuments.keys.map { publishDiagnostics(it) }
                    }
                }
                latest
            }
            if (settled) {
                onDocumentDiagnostics?.invoke(diagnostics)
                if (deferDocumentAnalysis) scheduleLiveDiagnostics(result.snapshot, diagnosticFiles, result.affectedDocuments)
                return
            }
            // Reconcile edits without holding the request lock. The foreground
            // continues to serve live-buffer queries until publication is atomic.
            result = synchronized(engineLock) {
                engine.update(result.snapshot, workspaceDelta(builtFiles, latestFiles), reporter = cancellationReporter)
            }
            builtFiles = latestFiles
        }
    }

    /** Editor-visible build progress sink (AnalysisProgress), throttled upstream. */
    internal var onBuildProgress: ((AnalysisProgress) -> Unit)? = null

    /**
     * Shared build-progress reporter construction ([rebuildFullLocked] and
     * [buildWorkspace]): forwards to [progressSink] on COMPLETE and every 5th file;
     * [preReport] runs on every callback (the background build aborts on shutdown there).
     */
    private fun workspaceBuildReporter(
        progressSink: ((AnalysisProgress) -> Unit)?,
        preReport: () -> Unit = {}
    ): ProgressReporter {
        val sink = progressSink ?: return ProgressReporter { preReport() }
        return ProgressReporter { progress ->
            preReport()
            if (progress.phase == AnalysisProgress.Phase.COMPLETE ||
                progress.completedFiles % 5 == 0
            ) {
                sink(progress)
            }
        }
    }

    private fun rebuildFullLocked(files: Map<VirtualPath, String>): Set<VirtualPath> {
        val reporter = workspaceBuildReporter(onBuildProgress)
        val result = synchronized(engineLock) {
            engine.build(
                LuaWorkspaceInput(files = files, metadata = workspaceMetadata),
                reporter = reporter
            )
        }
        fullRebuildCount += 1
        applyWorkspaceResult(result, files)
        return result.affectedDocuments
    }

    private fun buildWorkspace(
        files: Map<VirtualPath, String>,
        metadata: Map<String, String>
    ): WorkspaceUpdateResult {
        // The background build aborts mid-stage on shutdown; see workspaceBuildReporter.
        val reporter = workspaceBuildReporter(onBuildProgress) {
            if (closed) throw java.util.concurrent.CancellationException("Language server closed")
        }
        return synchronized(engineLock) {
            cancellationReporter.report(AnalysisProgress(AnalysisProgress.Phase.PARSING))
            engine.build(LuaWorkspaceInput(files = files, metadata = metadata), reporter = reporter)
        }
    }

    private val dexWarmupStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Pre-mounts the configured framework/classpath dex files on a background
     * thread at configuration time. The dex parse (5,981 framework classes took
     * 9.2s on the E2E emulator) otherwise runs lazily on the first framework
     * member query — which lands mid-await in interactive phases and blows their
     * budgets. Mounting at configuration time overlaps the parse with the
     * client's own startup work; later queries join the in-flight mount future.
     */
    private fun warmUpConfiguredDexLibraries(metadata: Map<String, String>) {
        if (!dexWarmupStarted.compareAndSet(false, true)) return
        val dexPaths = buildList {
            metadata["jvm.androidDex"]?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            metadata["jvm.classpath"]?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        }
        if (dexPaths.isEmpty()) return
        Thread({
            // Serialize the service's own warmups behind the overlay compile: at
            // initialize, the overlay compile (4 workers), the framework dex parse
            // (~10s) and the first live analysis all raced on the emulator's 6
            // TCG-emulated cores — the mutual contention inflated EVERY cold stage
            // (builtins 10.0s -> 16.0s across runs at identical code). The first
            // analysis needs builtins first; the dex warmup is only needed when the
            // first check resolves framework imports (~15s later).
            try {
                overlayCompileGate.await(60, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
            dexPaths.forEach { path ->
                runCatching {
                    io.github.dingyi222666.luaparser.interop.jvm.DexLibraryMounter.libraryFor(java.io.File(path))
                }
            }
        }, "lsp-dex-warmup").apply { isDaemon = true; start() }
    }

    fun setWorkspaceMetadata(metadata: Map<String, String>): Unit = synchronized(stateLock) {
        // Synchronous by contract: callers/tests expect the snapshot settled
        // when this returns. Config changes are rare (connect-time push).
        workspaceMetadata = metadata.toMap()
        warmUpConfiguredDexLibraries(workspaceMetadata)
        rebuildFull()
    }

    private val warmedDexImports: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet())

    /**
     * Pre-mounts dex/apk libraries referenced by an open buffer's `import '<path>.dex'`
     * statements on a background thread, at content-change time. The import chain's
     * first resolution otherwise lands mid-analysis of the very edit that introduced
     * it (measured 4.7s of a 5.2s re-analysis on the emulator); warming overlaps it
     * with the still-running analysis of the previous content. Deduped per
     * (path, mtime, length) — a rewritten library re-warms.
     */
    private fun warmDexImportsIn(source: String) {
        Regex("""import\s*['"]([^'"\n]+)['"]""").findAll(source).forEach { match ->
            val target = match.groupValues[1].trim()
            if (!target.endsWith(".dex") && !target.endsWith(".apk")) return@forEach
            val file = java.io.File(target)
            if (!file.isFile) return@forEach
            val key = "${file.absolutePath}#${file.lastModified()}#${file.length()}"
            if (!warmedDexImports.add(key)) return@forEach
            val importTarget = "import '${file.absolutePath}'"
            Thread({
                runCatching {
                    io.github.dingyi222666.luaparser.interop.jvm.DexLibraryMounter.libraryFor(file)
                    // Mount alone is not enough: the provider snapshot + fingerprint
                    // activation (measured 3.4-5.9s on the emulator) previously ran
                    // inside the import-carrying analysis the client waits on.
                    val metadata = synchronized(stateLock) { workspaceMetadata.toMap() }
                    liveBufferEngine.warmImportTargets(listOf(importTarget), metadata)
                }.onFailure {
                    System.err.println("LSP-DEVICE: dex import warmup failed for ${file.name}: $it")
                }
            }, "lsp-dex-import-warmup").apply { isDaemon = true }.start()
        }
    }

    /**
     * After a watched file's index entry is dropped (deleted or unreadable), keep the
     * observed URI mapping so diagnostics("path") still clears against the original
     * file URI, and drop the semantic-token cache when the file is not open (no
     * token stream to cache for a closed document).
     */
    private fun forgetIndexedFileTokens(virtualPath: VirtualPath, uri: String) {
        indexedWorkspaceUris.putIfAbsent(virtualPath, uri)
        if (virtualPath !in openDocuments) {
            semanticTokensCache.remove(virtualPath)
        }
    }

    /**
     * Applies workspace/didChangeWatchedFiles create/change/delete events to the
     * indexed workspace snapshot. Open-document overlays remain authoritative for
     * unsaved buffers. Non-Lua/ALY files are ignored.
     */
    fun applyWatchedFileChanges(changes: List<FileEvent>): Unit = synchronized(stateLock) {
        var mutated = false
        changes.forEach { event ->
            val uri = event.uri?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!isLuaOrAlyUri(uri)) {
                return@forEach
            }
            val virtualPath = virtualPathForWatchedUri(uri)
            when (event.type) {
                FileChangeType.Deleted -> {
                    if (indexedWorkspaceFiles.remove(virtualPath) != null) mutated = true
                    forgetIndexedFileTokens(virtualPath, uri)
                }

                FileChangeType.Created, FileChangeType.Changed -> {
                    val source = pathFromFileUri(uri)?.let(::readWorkspaceSource)
                    if (source != null) {
                        val previous = indexedWorkspaceFiles.put(virtualPath, source)
                        indexedWorkspaceUris[virtualPath] = uri
                        if (previous != source) {
                            mutated = true
                        }
                    } else if (event.type == FileChangeType.Changed) {
                        // File may have been replaced/truncated; drop stale index entry if unreadable.
                        if (indexedWorkspaceFiles.remove(virtualPath) != null) {
                            forgetIndexedFileTokens(virtualPath, uri)
                            mutated = true
                        }
                    }
                }

                else -> Unit
            }
        }
        if (mutated) {
            refreshIncremental()
        }
    }

    /**
     * Applies workspace/didChangeWorkspaceFolders add/remove without a process restart:
     * added folders are walked for `.lua`/`.aly` sources via [indexWorkspaceFolder],
     * removed folders drop their indexed sources/symbols, and open-document overlays in
     * [openDocuments] remain authoritative over disk index entries (same as watched-file
     * and initialize paths). Multi-root relative-path collision remains last-write-wins:
     * each folder indexes files under a root-relative virtual key (`dep.lua`).
     */
    fun applyWorkspaceFolderChanges(
        added: List<WorkspaceFolder>,
        removed: List<WorkspaceFolder>
    ): Unit = synchronized(stateLock) {
        if (added.isEmpty() && removed.isEmpty()) {
            return
        }

        val removedKeys = removed.mapNotNull(::folderUriKey).toSet()
        if (removedKeys.isNotEmpty()) {
            workspaceFolders = workspaceFolders.filter { folderUriKey(it) !in removedKeys }
        }

        // Dedup against the (possibly updated) folder list; an empty `added` yields no adds.
        val existingKeys = workspaceFolders.mapNotNull(::folderUriKey).toSet()
        val toAdd = added.filter { folder -> folderUriKey(folder)?.let { it !in existingKeys } == true }
        if (toAdd.isNotEmpty()) {
            workspaceFolders = workspaceFolders + toAdd
        }

        // Reuse initialize-time helpers: recompute URI prefixes then re-walk remaining
        // folders. Open overlays are merged later in currentWorkspaceFiles().
        refreshWorkspaceFolderUriPrefixes()
        refreshWorkspaceFolderIndex()
        refreshIncremental()
    }

    private fun folderUriKey(folder: WorkspaceFolder): String? {
        val uri = folder.uri?.takeIf { it.isNotBlank() } ?: return null
        return pathFromFileUri(uri)?.toString()
            ?: normalizeLspFileUriPath(uri)?.normalizeWorkspacePathPrefix()
            ?: uri.trim().trimEnd('/')
    }

    /** Receives fresh diagnostics after deferred Android document analysis. */
    internal var onDocumentDiagnostics: ((List<PublishDiagnosticsParams>) -> Unit)? = null

    private val documentRefreshLock = Any()
    private var documentRefreshPending = false
    private var documentRefreshRunning = false

    private data class LiveDiagnosticWork(
        val snapshot: WorkspaceSnapshot,
        val files: Map<VirtualPath, String>,
        val checkOrder: List<VirtualPath>
    )
    private val liveDiagnosticLock = Any()
    private var pendingLiveDiagnostics: LiveDiagnosticWork? = null
    private var liveDiagnosticRunning = false
    // Edited-file fast lane, bounded to ONE thread. Head-of-line blocking happens
    // ACROSS work items: the content-diff preemption only fires at checkpoints
    // INSIDE the in-flight check, so an earlier work item's multi-second check
    // (e.g. a 5KB main.lua with framework imports) occupies the single worker
    // while a newer schedule containing the edited file waits in
    // pendingLiveDiagnostics. This latest-wins slot holds ONLY changed-content
    // files for a dedicated one-shot lane.
    private var pendingEditedCheck: LiveDiagnosticWork? = null
    private var editedCheckRunning = false
    private var lastDiagnosticFiles: Map<VirtualPath, String> = emptyMap()
    // Checker diagnostics are pure derivations of the document's own AST: for identical
    // source text they stay valid across re-binds (refresh republications produce a
    // new binder for unchanged content). Key by (path, source) and store ONLY the
    // diagnostic list so no stale binder graph is pinned; bounded LRU.
    private val checkedLiveDiagnostics =
        LinkedHashMap<Pair<VirtualPath, String>, List<io.github.dingyi222666.luaparser.semantic.api.Diagnostic>>(16, 0.75f, true)

    private fun rememberCheckedDiagnostics(
        key: Pair<VirtualPath, String>,
        value: List<io.github.dingyi222666.luaparser.semantic.api.Diagnostic>
    ) {
        synchronized(liveDiagnosticLock) {
            if (closed) return
            checkedLiveDiagnostics[key] = value
            while (checkedLiveDiagnostics.size > 32) {
                checkedLiveDiagnostics.remove(checkedLiveDiagnostics.keys.first())
            }
        }
    }
    internal var onLiveDiagnosticsFileChecked: ((VirtualPath) -> Unit)? = null

    private fun scheduleLiveDiagnostics(
        snapshot: WorkspaceSnapshot,
        files: Map<VirtualPath, String>,
        affectedDocuments: Set<VirtualPath>
    ) {
        var spawnEditedLane = false
        synchronized(liveDiagnosticLock) {
            if (closed) return
            checkedLiveDiagnostics.keys.retainAll { key -> key.first in files }
            affectedDocuments.forEach { path -> checkedLiveDiagnostics.keys.removeAll { it.first == path } }
            val changed = files.keys.filter { files[it] != lastDiagnosticFiles[it] }
            val unchanged = files.keys - changed.toSet()
            // Size-ascending within each tier: the first schedule counts every file
            // as "changed" (lastDiagnosticFiles is empty), which degenerated the
            // changed-first tier to didOpen order — a 5KB cold check with framework
            // imports queued ahead of small documents' warnings (device timeline:
            // the edited file's warning waited behind main.lua's multi-second check).
            // No-change re-enqueues (empty changed) keep a deterministic order too.
            pendingLiveDiagnostics = LiveDiagnosticWork(
                snapshot, files,
                changed.sortedBy { files[it]?.length ?: Int.MAX_VALUE } +
                    unchanged.sortedBy { files[it]?.length ?: Int.MAX_VALUE }
            )
            lastDiagnosticFiles = files
            // Edited-file fast lane: when the slow lane is busy with an earlier work
            // item (head-of-line blocking ACROSS work items — the content-diff
            // preemption only fires at checkpoints inside the in-flight check, so a
            // check stuck in a non-yielding stretch delays the edit), park the
            // changed-content files in the latest-wins fast slot and run them on a
            // dedicated one-shot lane. The slow lane's pending work above keeps the
            // full changed+unchanged set: an idle slow lane behaves exactly as
            // before (no fast lane is spawned), and the currency cache makes the
            // slow lane's eventual re-check of a file the fast lane already checked
            // a cheap cache hit. Only changed files enter the fast slot — unchanged
            // files cannot produce a fresh report for the edited-file latency
            // budget and would only duplicate slow-lane work.
            if (changed.isNotEmpty() && liveDiagnosticRunning) {
                pendingEditedCheck = LiveDiagnosticWork(
                    snapshot, files,
                    changed.sortedBy { files[it]?.length ?: Int.MAX_VALUE }
                )
                if (!editedCheckRunning) {
                    editedCheckRunning = true
                    spawnEditedLane = true
                }
            }
            if (liveDiagnosticRunning) return
            liveDiagnosticRunning = true
        }
        if (spawnEditedLane) {
            // Spawned after the lock is released, like the slow lane below;
            // editedCheckRunning was already set under the lock, so no second
            // fast-lane thread can be started concurrently.
            spawnLiveDiagnosticsWorker(
                threadName = "lsp-live-diagnostics-edited",
                laneTag = "edited",
                takeWork = { takeEditedLaneWork() },
                supersededFor = { work -> editedLaneSupersededFor(work) },
                clearLaneRunning = { synchronized(liveDiagnosticLock) { editedCheckRunning = false } }
            )
        }
        spawnLiveDiagnosticsWorker(
            threadName = "lsp-live-diagnostics",
            laneTag = "all",
            takeWork = { takeSlowLaneWork() },
            supersededFor = { work -> slowLaneSupersededFor(work) },
            clearLaneRunning = { synchronized(liveDiagnosticLock) { liveDiagnosticRunning = false } }
        )
    }

    /** Spawns ONE daemon worker thread for a diagnostics lane ("all" or "edited"). */
    private fun spawnLiveDiagnosticsWorker(
        threadName: String,
        laneTag: String,
        takeWork: () -> LiveDiagnosticWork?,
        supersededFor: (LiveDiagnosticWork) -> (VirtualPath) -> Boolean,
        clearLaneRunning: () -> Unit
    ) {
        Thread({
            runLiveDiagnosticsWorker(laneTag, takeWork, supersededFor, clearLaneRunning)
        }, threadName).apply {
            isDaemon = true
            // NORM, not MIN: the emulator's software emulation starves MIN_PRIORITY
            // threads for tens of seconds under concurrent analysis/completion load
            // (measured: a 5KB document check took 24.1s and its published warning
            // missed the client's 10s budget entirely). The checker is interactive
            // output, not scavenger work — it competes at normal priority and the
            // content-currency cache keeps repeated runs cheap.
            start()
        }
    }

    /**
     * Shared worker loop for both diagnostic lanes. Exactly one thread runs per
     * lane at any time: the lane's running flag is set by the spawner under
     * [liveDiagnosticLock] and cleared only inside the lane's take-work section
     * under the same lock when no work remains — so a spawner that sees the flag
     * set never starts a second thread, and a preempted item hands off to the
     * newer pending work on the very next loop iteration below.
     */
    private fun runLiveDiagnosticsWorker(
        laneTag: String,
        takeWork: () -> LiveDiagnosticWork?,
        supersededFor: (LiveDiagnosticWork) -> (VirtualPath) -> Boolean,
        clearLaneRunning: () -> Unit
    ) {
        try {
            // Completion and diagnostics evaluate concurrently. Keep the checker's
            // provider/global caches separate from the published query resolver.
            val pipeline = io.github.dingyi222666.luaparser.semantic.SemanticPipeline()
            while (true) {
                val work = takeWork() ?: return
                try {
                    System.err.println(
                        "LSP-DEVICE: liveDiagnostics worker start lane=$laneTag files=${work.files.size} " +
                            "order=${work.checkOrder.map { it.value }}"
                    )
                    val checkedFiles = work.snapshot.files.toMutableMap()
                    val diagnosticResolver = io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver(
                        work.snapshot, deferDependencyGlobals = true
                    )
                    val completedPaths = mutableListOf<VirtualPath>()
                    val publishedPaths = mutableSetOf<VirtualPath>()
                    val superseded = supersededFor(work)
                    for (path in work.checkOrder) {
                        // Newer pending work supersedes the remaining files ONLY when
                        // this file's content changed in it: republications with
                        // unchanged content (background rebuild finishing, no-delta
                        // refreshes) arrive every few seconds on the device, and an
                        // aborted check is never cached — content-blind preemption
                        // restarted the same check forever without ever publishing
                        // (measured: five consecutive runs, zero checked lines).
                        if (superseded(path)) break
                        checkLiveDiagnosticsFile(
                            work, path, checkedFiles, completedPaths, publishedPaths,
                            pipeline, diagnosticResolver, superseded
                        )
                    }
                    liveAnalysisCheckpoint.remove()
                    finishLiveDiagnosticsWork(work, checkedFiles, completedPaths, publishedPaths, pipeline)
                } catch (_: ObsoleteLiveAnalysis) {
                } catch (failure: Throwable) {
                    // Throwable, not Exception: a silent Error kill would leave
                    // the lane flag set forever and every later republication
                    // would queue work no thread will ever take (observed on device as
                    // a window with zero checker activity and zero error lines).
                    System.err.println("LSP: live diagnostics failed: $failure")
                } finally {
                    liveAnalysisCheckpoint.remove()
                }
            }
        } catch (t: Throwable) {
            // Last-resort guard: only the pipeline construction sits outside the
            // per-work try; a crash there must not leave the lane flag set forever
            // with queued work no thread will ever take.
            System.err.println("LSP: live diagnostics $laneTag lane worker died: $t")
            clearLaneRunning()
        }
    }

    /**
     * Slow-lane take: drains the full pending work slot. Returns null (and clears
     * liveDiagnosticRunning under [liveDiagnosticLock]) when the lane should exit.
     */
    private fun takeSlowLaneWork(): LiveDiagnosticWork? = try {
        synchronized(liveDiagnosticLock) {
            val pending = if (closed) null else pendingLiveDiagnostics
            if (pending == null) {
                liveDiagnosticRunning = false
                null
            } else {
                pendingLiveDiagnostics = null
                pending
            }
        }
    } catch (t: Throwable) {
        // This section sits outside the per-work try; a crash here would otherwise
        // leave liveDiagnosticRunning=true forever and every later republication
        // would queue work no thread will ever take. Reset the flag so the next
        // schedule can spawn a worker.
        synchronized(liveDiagnosticLock) { liveDiagnosticRunning = false }
        System.err.println("LSP: live diagnostics worker died taking work: $t")
        null
    }

    /** Fast-lane take: latest-wins edited slot; null (and flag clear) exits the lane. */
    private fun takeEditedLaneWork(): LiveDiagnosticWork? = try {
        synchronized(liveDiagnosticLock) {
            val pending = if (closed) null else pendingEditedCheck
            if (pending == null) {
                editedCheckRunning = false
                null
            } else {
                pendingEditedCheck = null
                pending
            }
        }
    } catch (t: Throwable) {
        // Same Throwable-hardened take as the slow lane: a crash here must not
        // leave editedCheckRunning=true forever.
        synchronized(liveDiagnosticLock) { editedCheckRunning = false }
        System.err.println("LSP: live diagnostics edited lane worker died taking work: $t")
        null
    }

    /**
     * Slow-lane preemption predicate: the newest content for this path in EITHER
     * pending slot (the full work OR the edited fast lane's work) differing from
     * the work in flight means a newer buffer is waiting; shutdown supersedes all.
     */
    private fun slowLaneSupersededFor(work: LiveDiagnosticWork): (VirtualPath) -> Boolean = { path ->
        synchronized(liveDiagnosticLock) {
            val full = pendingLiveDiagnostics
            val edited = pendingEditedCheck
            closed ||
                (full != null && full.files[path] != work.files[path]) ||
                (edited != null && edited.files[path] != work.files[path])
        }
    }

    /** Fast-lane preemption predicate: only a newer edited-lane work (or shutdown) supersedes. */
    private fun editedLaneSupersededFor(work: LiveDiagnosticWork): (VirtualPath) -> Boolean = { path ->
        synchronized(liveDiagnosticLock) {
            val edited = pendingEditedCheck
            closed || (edited != null && edited.files[path] != work.files[path])
        }
    }

    /**
     * Checks ONE file of a work item and publishes its diagnostics; shared by BOTH
     * lanes, so the fast lane runs the exact same per-file logic (cache lookup,
     * watchdog/checkpoint wiring, per-file publish) as the slow lane. Returns true
     * when the file completed; false when it was skipped (no snapshot / stale
     * buffer). Throws [ObsoleteLiveAnalysis] on content-diff preemption, which the
     * caller's per-work catch swallows so the next loop iteration takes the newer
     * pending work.
     */
    private fun checkLiveDiagnosticsFile(
        work: LiveDiagnosticWork,
        path: VirtualPath,
        checkedFiles: MutableMap<VirtualPath, WorkspaceSnapshot.FileSnapshot>,
        completedPaths: MutableList<VirtualPath>,
        publishedPaths: MutableSet<VirtualPath>,
        pipeline: io.github.dingyi222666.luaparser.semantic.SemanticPipeline,
        diagnosticResolver: io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver,
        superseded: (VirtualPath) -> Boolean
    ): Boolean {
        val checkStart = System.currentTimeMillis()
        val file = checkedFiles[path] ?: return false
        val semantic = file.semanticFile ?: return false
        // An unrelated edit need not discard a checker already running:
        // the report derives from this document's own AST, so only a
        // content change of THIS buffer (or shutdown) invalidates it.
        fun isCurrent() = !closed && openDocuments[path] == work.files[path]
        if (!synchronized(stateLock) { isCurrent() }) {
            System.err.println("LSP-DEVICE: liveDiagnostics skip ${path.value} (superseded)")
            return false
        }
        liveAnalysisCheckpoint.set {
            synchronized(stateLock) {
                if (!isCurrent()) throw ObsoleteLiveAnalysis()
            }
            // Preempt ONLY when this file's content changed in the newer
            // work (see the loop-head note): unchanged republications
            // must not abort an in-flight check that can never be
            // resumed from cache.
            if (superseded(path)) {
                throw ObsoleteLiveAnalysis()
            }
        }
        val cachedDiagnostics = synchronized(liveDiagnosticLock) {
            checkedLiveDiagnostics[path to semantic.source]
        }
        val checked = if (cachedDiagnostics != null) {
            pipeline.finishDiagnostics(
                semantic.snapshot,
                io.github.dingyi222666.luaparser.semantic.checker.CheckerPassResult(
                    binder = semantic.snapshot.binder,
                    diagnostics = cachedDiagnostics
                )
            )
        } else {
            // Watchdog: the emulated device once hid a >14s check with no
            // observable progress; this attributes the time (evaluation
            // counter vs elapsed) on the device log itself.
            val evaluations = java.util.concurrent.atomic.AtomicLong()
            val checkerThread = Thread.currentThread()
            val watchdog = Thread({
                var ticks = 0
                while (!closed) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                    ticks++
                    val stack = checkerThread.stackTrace
                        .filter { it.className.contains("luaparser") }
                        .take(8)
                        .joinToString(" <- ") { it.methodName }
                    System.err.println(
                        "LSP-DEVICE: liveDiagnostics checking ${path.value} " +
                            "ms=${System.currentTimeMillis() - checkStart} evals=${evaluations.get()} " +
                            "at=[$stack]"
                    )
                    if (ticks >= 10) return@Thread
                }
            }, "lsp-check-watchdog").apply { isDaemon = true }
            watchdog.start()
            val checkingSnapshot = semantic.snapshot.copy(
                workspaceContext = semantic.snapshot.workspaceContext.copy(
                    workspaceResolver = diagnosticResolver,
                    resolveImportedSymbol = null,
                    resolveImportTarget = null,
                    // Every evaluate() entry must ALSO test the live
                    // checkpoint, not only count for the watchdog:
                    // between expression-evaluation checkpoints a
                    // superseded check otherwise grinds through a
                    // non-yielding stretch (measured ~3.7s on device
                    // between a newer schedule and the next
                    // member-resolution / class-load site). Throwing
                    // ObsoleteLiveAnalysis from here propagates up
                    // through evaluate() exactly like the
                    // analysisCheckpoint throw, so the worker loop
                    // takes the newer pending work immediately.
                    onExpressionEvaluation = {
                        evaluations.incrementAndGet()
                        checkLiveAnalysisCurrent()
                    },
                    analysisCheckpoint = {
                        checkLiveAnalysisCurrent()
                        semantic.snapshot.workspaceContext.analysisCheckpoint()
                        checkLiveAnalysisCurrent()
                    }
                ).withWorkspaceImportEffects()
            )
            try {
                pipeline.finishDiagnostics(checkingSnapshot)
            } finally {
                watchdog.interrupt()
            }
                .copy(workspaceContext = semantic.snapshot.workspaceContext).also {
                checkLiveAnalysisCurrent()
                rememberCheckedDiagnostics(path to semantic.source, it.checker.diagnostics)
            }
        }
        System.err.println(
            "LSP-DEVICE: liveDiagnostics checked $path cached=${cachedDiagnostics != null} " +
                "in ${System.currentTimeMillis() - checkStart}ms"
        )
        val checkedFile = file.copy(semanticFile = WorkspaceSemanticFile(
            path, semantic.source, semantic.chunk, checked.model, checked, semantic.recoveryDiagnostics
        ))
        checkedFiles[path] = checkedFile
        completedPaths += path
        // Keep query publication atomic, but do not make a fresh document
        // warning wait for every other open document's checker.
        val diagnostic = synchronized(stateLock) {
            if (isCurrent()) {
                publishDiagnostics(path, checkedFile = checkedFile)
            } else null
        }
        diagnostic?.let {
            onDocumentDiagnostics?.invoke(listOf(it))
            publishedPaths += path
        }
        if (cachedDiagnostics == null) onLiveDiagnosticsFileChecked?.invoke(path)
        return true
    }

    /**
     * Per-work-item merge epilogue, run by BOTH lanes at the end of each work item.
     * It validates every completed result against the CURRENT buffer content before
     * merging/publishing, so concurrent lanes publishing under [stateLock] cannot
     * interleave stale semantic snapshots into the live workspace.
     */
    private fun finishLiveDiagnosticsWork(
        work: LiveDiagnosticWork,
        checkedFiles: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>,
        completedPaths: List<VirtualPath>,
        publishedPaths: Set<VirtualPath>,
        pipeline: io.github.dingyi222666.luaparser.semantic.SemanticPipeline
    ) {
        val diagnostics = synchronized(stateLock) {
            if (!closed) {
                // Merge each valid checker result into the CURRENT workspace.
                // Unrelated edits can publish a new workspace while an unchanged
                // document is checking; that must neither discard its report nor
                // replace the newly edited file with an older semantic snapshot.
                val merged = this.snapshot.files.toMutableMap()
                val mergedPaths = completedPaths.filter { path ->
                    val checkedFile = checkedFiles[path] ?: return@filter false
                    val checkedSemantic = checkedFile.semanticFile ?: return@filter false
                    val currentFile = merged[path] ?: return@filter false
                    val currentSemantic = currentFile.semanticFile ?: return@filter false
                    if (openDocuments[path] != work.files[path] ||
                        currentSemantic.source != checkedSemantic.source) return@filter false
                    val checked = pipeline.finishDiagnostics(
                        currentSemantic.snapshot,
                        checkedSemantic.snapshot.checker.copy(binder = currentSemantic.snapshot.binder)
                    )
                    merged[path] = currentFile.copy(semanticFile = WorkspaceSemanticFile(
                        path, currentSemantic.source, currentSemantic.chunk,
                        checked.model, checked,
                        currentSemantic.recoveryDiagnostics
                    ))
                    true
                }
                this.snapshot = this.snapshot.copy(files = merged)
                queries = LuaWorkspaceQueryFacade(this.snapshot)
                mergedPaths.filterNot { it in publishedPaths }.map { publishDiagnostics(it) }
            } else emptyList()
        }
        onDocumentDiagnostics?.invoke(diagnostics)
    }

    private fun scheduleDocumentRefresh() {
        synchronized(documentRefreshLock) {
            if (closed) return
            documentRefreshPending = true
            if (documentRefreshRunning) return
            documentRefreshRunning = true
        }
        Thread({
            try {
                while (true) {
                    synchronized(documentRefreshLock) {
                        if (closed || !documentRefreshPending) {
                            documentRefreshRunning = false
                            return@Thread
                        }
                        documentRefreshPending = false
                    }
                    // Burst debounce: programmatic flows (didOpen immediately followed by
                    // a full-buffer setText) re-set pending within a second; a short
                    // window lets the newest buffer content win before the analysis
                    // starts (the existing pending flag re-loops without publishing).
                    Thread.sleep(LIVE_ANALYSIS_DEBOUNCE_MS)
                    synchronized(documentRefreshLock) {
                        if (closed) {
                            documentRefreshRunning = false
                            return@Thread
                        }
                    }
                    if (synchronized(documentRefreshLock) { documentRefreshPending }) {
                        continue
                    }
                    val liveFiles = synchronized(stateLock) {
                        if (snapshotReady) null else openDocuments.toMap()
                    }
                    if (liveFiles != null) {
                        // Parse and bind outside the protocol lock. Publish only
                        // if the buffers still match and the full index has not
                        // superseded this small live snapshot.
                        val result = try {
                            analyzeLiveBuffers(liveFiles)
                        } catch (_: ObsoleteLiveAnalysis) {
                            synchronized(documentRefreshLock) { documentRefreshPending = true }
                            continue
                        }
                        val published = synchronized(stateLock) {
                            if (!closed && !snapshotReady && openDocuments == liveFiles) {
                                snapshot = result.snapshot
                                queries = LuaWorkspaceQueryFacade(snapshot)
                                System.err.println("LSP-DEVICE: published live files=${liveFiles.map { (p, s) -> "$p:${s.length}/${snapshot.files[p]?.semanticFile?.source?.length}" }}")
                                signalQueryPublication()
                                firstLivePublication.countDown()
                                true
                            } else {
                                synchronized(documentRefreshLock) { documentRefreshPending = true }
                                false
                            }
                        }
                        if (published) scheduleLiveDiagnostics(result.snapshot, liveFiles, result.affectedDocuments)
                        continue
                    }
                    val diagnostics = refreshDeferredWorkspace()
                    onDocumentDiagnostics?.invoke(diagnostics)
                }
            } catch (failure: Exception) {
                synchronized(documentRefreshLock) { documentRefreshRunning = false }
                if (!closed) System.err.println("LSP: document refresh failed: ${failure.message}")
            }
        }, "lsp-document-refresh").apply {
            isDaemon = true
            start()
        }
    }

    private fun refreshDeferredWorkspace(): List<PublishDiagnosticsParams> {
        val (previous, syncedFiles, files) = synchronized(stateLock) {
            Triple(snapshot, lastSyncedFiles, currentWorkspaceFiles())
        }
        val delta = workspaceDelta(syncedFiles, files)
        if (delta.upserts.isEmpty() && delta.removals.isEmpty()) {
            // Opening an indexed file has no source delta, but its checker may
            // never have run. Closing/reopening also needs the current report.
            val openFiles = synchronized(stateLock) { openDocuments.toMap() }
            scheduleLiveDiagnostics(previous, openFiles, emptySet())
            return emptyList()
        }
        val result = synchronized(engineLock) {
            engine.update(previous, delta, reporter = cancellationReporter)
        }
        var diagnosticFiles: Map<VirtualPath, String>? = null
        val diagnostics = synchronized(stateLock) {
            if (!closed && snapshot === previous && currentWorkspaceFiles() == files) {
                incrementalUpdateCount += 1
                applyWorkspaceResult(result, files)
                diagnosticFiles = openDocuments.toMap()
                openDocuments.keys.map { publishDiagnostics(it) }
            } else {
                synchronized(documentRefreshLock) { documentRefreshPending = true }
                emptyList()
            }
        }
        // Publish the unchecked/syntax report before starting a checker that
        // could otherwise finish first and have its fresh report overwritten.
        onDocumentDiagnostics?.invoke(diagnostics)
        diagnosticFiles?.let { scheduleLiveDiagnostics(result.snapshot, it, result.affectedDocuments) }
        return emptyList()
    }

    fun didOpen(params: DidOpenTextDocumentParams): PublishDiagnosticsParams {
        val document = params.textDocument
        val path = pathOf(document.uri)
        synchronized(stateLock) {
            openDocuments[path] = document.text
            documentUris[path] = document.uri
            if (!deferDocumentAnalysis) {
                refreshIncremental()
                return publishDiagnostics(path)
            }
        }
        warmDexImportsIn(document.text)
        scheduleDocumentRefresh()
        return PublishDiagnosticsParams(document.uri, emptyList())
    }

    /**
     * Applies a didChange and returns the diagnostics to publish, edited document first.
     * The edit base resolves like [applyWatchedFileChanges] — open overlay, then indexed
     * source, then analyzed snapshot — so a ranged edit on an indexed-but-never-opened
     * file cannot turn the edit fragment into the whole overlay. After the refresh, every
     * affected open document is republished (deduped, edited path first); closed indexed
     * files stay pull-only through [diagnostics] / [diagnosticsForUri].
     */
    fun didChange(params: DidChangeTextDocumentParams): List<PublishDiagnosticsParams> = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        val baseSource = sourceFor(path)
            ?: snapshot.files[path]?.semanticFile?.source
            ?: ""
        openDocuments[path] = applyContentChanges(baseSource, params.contentChanges)
        documentUris[path] = params.textDocument.uri
        if (deferDocumentAnalysis) {
            openDocuments[path]?.let(::warmDexImportsIn)
            scheduleDocumentRefresh()
            return@synchronized emptyList<PublishDiagnosticsParams>()
        }
        val affected = refreshIncremental()
        val publishOrder = linkedSetOf(path)
        affected.filter { it in openDocuments }.sortedBy { it.value }.forEach(publishOrder::add)
        publishOrder.map(::publishDiagnostics)
    }

    fun didClose(params: DidCloseTextDocumentParams): List<PublishDiagnosticsParams> {
        val uri = params.textDocument.uri
        val path = pathOf(uri)
        val republished = synchronized(stateLock) {
            openDocuments.remove(path)
            documentUris.remove(path)
            semanticTokensCache.remove(path)
            if (!deferDocumentAnalysis) {
                refreshIncremental().filter { it != path && it in openDocuments }.map(::publishDiagnostics)
            } else null
        }
        // Close notifications are on Sora's disconnect path. Analyze later and
        // publish dependent diagnostics through the same coalescing worker.
        if (republished == null) scheduleDocumentRefresh()
        return republished.orEmpty() + PublishDiagnosticsParams(uri, emptyList())
    }

    fun didSave(@Suppress("UNUSED_PARAMETER") params: DidSaveTextDocumentParams) {
    }

    fun hover(params: HoverParams): Hover? {
        // Lock-free read (see completion): never queue behind the cold build.
        val queriesNow = queries
        val path = pathOf(params.textDocument)
        val result = queriesNow.hover(path, params.position.toParserPosition()) ?: return null
        // Prefer the collapsed preferredHoverType surface (FunctionType/ClassType/MODULE)
        // over bare unknown symbol detail so import hovers stay non-empty/rich.
        val preferredTypeDisplay = preferredLspHoverTypeDisplay(
            primary = result.callableDisplayName ?: result.typeInfo?.displayName,
            secondary = result.symbol?.declaredType?.displayName ?: result.symbol?.type?.displayName,
            tertiary = result.symbol?.detail
        )
        val content = buildHoverContent(
            name = result.symbol?.name,
            detail = result.symbol?.detail?.takeUnless { detail ->
                result.callableDisplayName != null ||
                    (preferredTypeDisplay != null && (detail == "unknown" || detail == "any") &&
                        preferredTypeDisplay != detail)
            },
            typeDisplayName = preferredTypeDisplay
        ) ?: return null
        return Hover().apply { contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, content)) }
    }

    /**
     * Completion for [path] at the given text position. [line] and [character] are
     * 0-based LSP integers, converted to the parser's 1-based [Position] internally
     * (`Position(line + 1, character + 1)`) before hitting the semantic model.
     */
    fun completion(path: String, line: Int, character: Int): CompletionList {
        // Lock-free read: never queue behind the cold build's stateLock — the
        // popup must appear instantly. During the first background build the
        // snapshot is the empty overlay (few/no items); cross-file entries
        // light up the moment the build swaps its snapshot in. `queries` is an
        // atomically-swapped reference and the facade is read-only, so a
        // lock-free read is safe.
        return completion(queries, path, line, character)
    }

    private fun completion(queriesNow: LuaWorkspaceQueryFacade, path: String, line: Int, character: Int,
        nameFilter: ((String) -> Boolean)? = null): CompletionList {
        val t0 = System.currentTimeMillis()
        val items = queriesNow.completionsMatching(pathFromClientPath(path), Position(line + 1, character + 1), nameFilter).map { completion ->
            CompletionItem(completion.label).apply {
                kind = completion.kind.toLspKind()
                detail = completion.detail
                insertText = completion.insertText
                insertTextFormat = InsertTextFormat.PlainText
                sortText = completion.sortText
            }
        }
        System.err.println("LSP-DEVICE: completion ${System.currentTimeMillis() - t0}ms items=${items.size} ready=$snapshotReady path=$path")
        return CompletionList(false, items)
    }

    internal fun completionAsync(path: String, line: Int, character: Int): CompletableFuture<CompletionList> {
        if (!deferDocumentAnalysis) return CompletableFuture.completedFuture(completion(path, line, character))
        // Validate on the executor, not before enqueue: didChange/publication may occur
        // while an earlier completion owns the worker. Capture the validated query revision
        // and evaluate that exact facade outside the protocol lock.
        if (closed) return cancelledCompletion()
        return try {
            CompletableFuture.supplyAsync({
                val virtualPath = pathFromClientPath(path)
                val revision = synchronized(stateLock) {
                    checkConnectionOpen()
                    val source = openDocuments[virtualPath]
                    val semantic = snapshot.files[virtualPath]?.semanticFile
                    Triple(queries to semantic?.snapshot?.binder, source,
                        if (source == null || semantic?.source == source) null else nextQueryPublication)
                }
                val waiting = revision.third
                // Stale-buffer fallback: serve the last published facade with the client's
                // prefix filter applied (re-requests pick up the fresh binding).
                fun staleFacadeCompletion(): CompletionList =
                    completion(revision.first.first, path, line, character,
                        revision.second?.let { completionNameFilter(it, line, character) })
                if (waiting != null) {
                    // Bound the publication wait (see COMPLETION_PUBLICATION_GRACE_MS):
                    // after the grace window serve the last published facade — the
                    // client's poll loop re-requests and sees the fresh binding.
                    val grace = CompletableFuture<Boolean>()
                    publicationGraceScheduler.schedule(
                        { grace.complete(false) },
                        COMPLETION_PUBLICATION_GRACE_MS, TimeUnit.MILLISECONDS
                    )
                    waiting.thenApply { true }
                        .applyToEither(grace) { published -> published }
                        .thenCompose { published ->
                            if (published) completionAsync(path, line, character)
                            else {
                                synchronized(stateLock) { checkConnectionOpen() }
                                CompletableFuture.completedFuture(staleFacadeCompletion())
                            }
                        }
                } else {
                    val result = staleFacadeCompletion()
                    val stillCurrent = synchronized(stateLock) {
                        checkConnectionOpen()
                        // Diagnostics-only publication keeps the binding and query caches valid.
                        // Source/dependency invalidation gives affected documents a new binding.
                        openDocuments[virtualPath] == revision.second &&
                            snapshot.files[virtualPath]?.semanticFile?.snapshot?.binder === revision.first.second
                    }
                    if (stillCurrent) CompletableFuture.completedFuture(result)
                    else completionAsync(path, line, character)
                }
            }, completionExecutor).thenCompose { it }
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            if (closed) cancelledCompletion() else throw rejected
        }
    }

    /** Apply the client's identifier prefix before typing unrelated lexical candidates. */
    private fun completionNameFilter(source: String, line: Int, character: Int): ((String) -> Boolean)? {
        val end = lspOffsetAt(source, org.eclipse.lsp4j.Position(line, character))
        var start = end
        while (start > 0) {
            val c = source[start - 1]
            if (c != '_' && !c.isLetterOrDigit()) break
            start--
        }
        if (start == end) return null
        val prefix = source.substring(start, end)
        // Preserve case-insensitive subsequence matches, including camel-case initials.
        return { candidate ->
            var matched = 0
            for (c in candidate) {
                if (c.equals(prefix[matched], ignoreCase = true)) matched++
                if (matched == prefix.length) break
            }
            matched == prefix.length
        }
    }

    private fun cancelledCompletion() = CompletableFuture<CompletionList>().apply {
        completeExceptionally(java.util.concurrent.CancellationException("Language server closed"))
    }

    /**
     * TASK-514 — completionItem/resolve. Enriches [detail]/[documentation] from fields
     * already present on the unresolved item; never renames the label, never blanks
     * insertText when set, and soft-degrades to an identity-preserving item on
     * missing/unknown input (no throw).
     */
    fun resolveCompletionItem(item: CompletionItem): CompletionItem = synchronized(stateLock) {
        try { enrichResolvedCompletionItem(item) } catch (_: Exception) { item }
    }

    private fun enrichResolvedCompletionItem(item: CompletionItem): CompletionItem {
        val label = item.label
        if (label.isNullOrBlank()) {
            return item
        }

        val resolved = CompletionItem(label).apply {
            // Preserve core identity fields first — never drop label/kind. Label
            // presentation and insert-mode travel with the label.
            kind = item.kind; labelDetails = item.labelDetails; insertTextMode = item.insertTextMode
            detail = item.detail; documentation = item.documentation
            insertText = item.insertText; insertTextFormat = item.insertTextFormat
            sortText = item.sortText; filterText = item.filterText
            textEdit = item.textEdit; additionalTextEdits = item.additionalTextEdits
            command = item.command; data = item.data; preselect = item.preselect
            tags = item.tags; commitCharacters = item.commitCharacters; deprecated = item.deprecated
        }

        // Fill detail from kind when the list path left it empty (reuse kind metadata only).
        resolved.detail = resolved.detail?.takeIf(String::isNotBlank)
            ?: completionKindPresentation[resolved.kind]?.first?.takeIf(String::isNotBlank)

        // Enrich documentation when absent; keep client-provided docs intact.
        // Markdown reuses label/kind/detail already on the item (no new ranking);
        // the kind display falls back to the enum name lowercased.
        if (resolved.documentation == null) {
            resolved.documentation = buildList {
                add("**${resolved.label}**")
                resolved.kind?.let { add("_${completionKindPresentation[it]?.second ?: it.name.lowercase()}_") }
                resolved.detail?.takeIf { it.isNotBlank() }?.let { add("`$it`") }
            }.joinToString("\n\n").toLspMarkdownDoc()
        }

        return resolved
    }

    /**
     * Single presentation table for completion kinds: (resolve-time detail, display
     * name). Detail and display deliberately differ only for Method, whose resolve
     * detail stays "function" while its markdown display reads "method".
     */
    private val completionKindPresentation:
        Map<org.eclipse.lsp4j.CompletionItemKind, Pair<String, String>> = mapOf(
        org.eclipse.lsp4j.CompletionItemKind.Function to ("function" to "function"),
        org.eclipse.lsp4j.CompletionItemKind.Method to ("function" to "method"),
        org.eclipse.lsp4j.CompletionItemKind.Variable to ("variable" to "variable"),
        org.eclipse.lsp4j.CompletionItemKind.Field to ("field" to "field"),
        org.eclipse.lsp4j.CompletionItemKind.Property to ("field" to "field"),
        org.eclipse.lsp4j.CompletionItemKind.Keyword to ("keyword" to "keyword"),
        org.eclipse.lsp4j.CompletionItemKind.Snippet to ("snippet" to "snippet"),
        org.eclipse.lsp4j.CompletionItemKind.Module to ("module" to "module"),
        org.eclipse.lsp4j.CompletionItemKind.Class to ("class" to "class"),
        org.eclipse.lsp4j.CompletionItemKind.TypeParameter to ("type" to "type"),
        org.eclipse.lsp4j.CompletionItemKind.Text to ("" to "text")
    )

    fun signatureHelp(params: SignatureHelpParams): SignatureHelp? = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        val help = queries.signatureHelp(path, params.position.toParserPosition()) ?: return@synchronized null
        SignatureHelp(
            help.signatures.map { signature ->
                SignatureInformation(signature.label).apply {
                    documentation = signature.documentation.toLspMarkdownDoc()
                    parameters = signature.parameters.map { parameter ->
                        ParameterInformation(parameter.label).apply {
                            documentation = parameter.documentation.toLspMarkdownDoc()
                        }
                    }
                }
            },
            help.activeSignature,
            help.activeParameter
        )
    }

    fun definition(params: DefinitionParams): List<Location> = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        val position = params.position.toParserPosition()
        // Prefer the require-backed module provider when the caret is on a require-local
        // alias (e.g. `local dep = require("definition-dep")`) or inside a require() call:
        // gotoDefinition returns the same-file local binding for LOCAL/VARIABLE symbols,
        // yielding the consumer file URI instead of the module file; resolveRequire()
        // maps the alias to the provider path, so re-route to keep Location.uri on the
        // opened module.
        val requireLookup = queries.resolveRequire(path, position)
        val provider = requireLookup?.provider
        if (provider != null) {
            val moduleName = requireLookup.moduleName
            val range = Range(
                start = Position(1, 1),
                end = Position(1, maxOf(moduleName.length + 1, 2))
            )
            return listOf(Location(uriFor(provider.path), range.toLspRange()))
        }
        queries.gotoDefinition(path, position).map { it.toLspLocation() }
    }

    fun declaration(params: DeclarationParams): List<Location> = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        queries.declaration(path, params.position.toParserPosition()).map { it.toLspLocation() }
    }

    fun documentHighlights(params: DocumentHighlightParams): List<DocumentHighlight> = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        val semanticFile = snapshot.files[path]?.semanticFile
        queries.documentHighlights(path, params.position.toParserPosition())
            // DocumentHighlight has no URI: cross-file locations (require-backed members)
            // would render as garbage ranges inside this document.
            .filter { it.path == path }
            .map { location ->
                val tightened = tightenDocumentHighlightLocation(semanticFile, path, location)
                DocumentHighlight(
                    tightened.range.toLspRange(),
                    documentHighlightKindFor(semanticFile, path, tightened)
                )
            }
    }

    /**
     * TASK-539 — Collect foldable multi-line function bodies and table constructors.
     * Empty / malformed sources yield an empty list (no throw). Ranges are 0-based
     * LSP lines with startLine < endLine and endLine inside the document when known.
     */
    fun foldingRanges(params: FoldingRangeRequestParams): List<FoldingRange> = synchronized(stateLock) {
        val context = documentContextFor(params.textDocument, requireSource = false)
            ?: return@synchronized emptyList()
        collectFoldingRanges(context.chunk, documentLineCount(context.source))
    }
    /**
     * TASK-540 — Selection ranges for nested block expand/shrink selection: a parent
     * chain from the innermost AST node covering each position, walking only real AST
     * parents (no invented spans). Unknown documents, empty positions, and
     * out-of-range cursors soft-degrade to an empty list without throwing. Positions
     * that resolve to no AST node are dropped per item (`SelectionRange[]` carries no
     * nulls on the wire).
     */
    fun selectionRanges(params: SelectionRangeParams): List<SelectionRange> = synchronized(stateLock) {
        val positions = params.positions.orEmpty()
        if (positions.isEmpty()) {
            return@synchronized emptyList()
        }
        val context = documentContextFor(params.textDocument, requireSource = true)
            ?: return@synchronized emptyList()
        val index = positionIndexFor(context.chunk)
        positions.mapNotNull { lspPosition ->
            selectionRangeAt(index, lspPosition.toParserPosition())
        }
    }

    /**
     * TASK-541 — textDocument/semanticTokens/full.
     * Returns LSP delta-encoded semantic tokens (5-int groups) for keyword /
     * function / variable / parameter / string / number / comment highlighting.
     * Empty / unknown / malformed sources soft-degrade to empty data without throw.
     * Caches the payload + resultId so semanticTokens/full/delta can answer (TASK-252).
     */
    fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens =
        synchronized(stateLock) { computeSemanticTokensFull(pathOf(params.textDocument)) }

    /**
     * TASK-252 — textDocument/semanticTokens/full/delta. Empty-edit [SemanticTokensDelta]
     * when [previousResultId] matches the cached unchanged full payload; otherwise a full
     * [SemanticTokens] payload (LSP allows Either.left full on the delta request).
     * Soft-degrades without throw.
     */
    fun semanticTokensFullDelta(
        params: SemanticTokensDeltaParams
    ): Either<SemanticTokens, SemanticTokensDelta> = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        return@synchronized try {
            val full = computeSemanticTokensFull(path)
            // computeSemanticTokensFull writes `full` into semanticTokensCache before
            // returning, so the previous cached-vs-full data comparison always held;
            // an unchanged payload keeps its resultId, hence an id match IS the
            // unchanged case and everything else re-sends full tokens.
            if (params.previousResultId == full.resultId) {
                Either.forRight<SemanticTokens, SemanticTokensDelta>(
                    SemanticTokensDelta(emptyList(), full.resultId)
                )
            } else {
                Either.forLeft(full)
            }
        } catch (_: Exception) {
            Either.forLeft(SemanticTokens(emptyList()))
        }
    }

    /**
     * TASK-274 — textDocument/onTypeFormatting for Lua block keywords (`end` / `then`):
     * reuses the full-document formatting pipeline after typing the final character of
     * `end` / `then` (or newline). Unknown triggers, empty/missing docs, and OOB
     * positions soft-degrade to an empty edit list without throw.
     */
    fun onTypeFormatting(params: DocumentOnTypeFormattingParams): List<TextEdit> = synchronized(stateLock) {
        try {
            val ch = params.ch.orEmpty()
            // Accept advertised triggers: d (end), n (then), and newline after keyword line.
            if (ch != "d" && ch != "n" && ch != "\n") {
                return@synchronized emptyList()
            }
            formatDocument(params.textDocument, params.options, range = null)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun computeSemanticTokensFull(path: VirtualPath): SemanticTokens {
        val source = sourceFor(path)
        if (source.isNullOrEmpty()) {
            semanticTokensCache.remove(path)
            return SemanticTokens(emptyList())
        }
        val semanticFile = snapshot.files[path]?.semanticFile
        val data = try {
            encodeSemanticTokens(source, semanticFile)
        } catch (_: Exception) {
            // Malformed buffers / lexer edge cases must not kill the request path.
            emptyList()
        }
        val previous = semanticTokensCache[path]
        val resultId = if (previous != null && previous.data == data) {
            previous.resultId
        } else {
            semanticTokensResultSeq += 1L
            "st-${semanticTokensResultSeq}"
        }
        semanticTokensCache[path] = CachedSemanticTokens(resultId = resultId, data = data)
        return SemanticTokens(resultId, data)
    }

    /**
     * TASK-542 — textDocument/codeAction surface: a well-formed zero-action list (no
     * UnsupportedOperationException) for any params shape — there is no deterministic
     * auto-fix for parse/type diagnostics yet.
     */
    fun codeActions(params: CodeActionParams): List<Either<Command, CodeAction>> {
        return emptyList()
    }

    /**
     * TASK-543 — textDocument/inlayHint parameter-name surface: [InlayHintKind.Parameter]
     * labels for call arguments of signature-help-known callees, derived from formal
     * names (reuses SignatureHelp formal metadata — no extra ranking). Outside-call
     * ranges, unknown callees, empty/malformed docs soft-degrade to an empty list.
     */
    fun inlayHints(params: InlayHintParams): List<InlayHint> = synchronized(stateLock) {
        try { collectParameterInlayHints(params) } catch (_: Exception) { emptyList() }
    }

    /**
     * TASK-544 — textDocument/formatting full-document surface.
     * Formats through AST2Lua only when both the source and printed text parse cleanly.
     * An unsupported source produces no replacement.
     */
    fun formatting(params: DocumentFormattingParams): List<TextEdit> = synchronized(stateLock) {
        try { formatDocument(params.textDocument, params.options, range = null) } catch (_: Exception) { emptyList() }
    }

    /**
     * TASK-544 — textDocument/rangeFormatting surface.
     * Formats the selected span (or full document when range is null/invalid) using
     * the same AST2Lua pipeline. Inverted, beyond-EOF, negative,
     * missing-document, and empty ranges soft-degrade to an empty list.
     */
    fun rangeFormatting(params: DocumentRangeFormattingParams): List<TextEdit> = synchronized(stateLock) {
        try { formatDocument(params.textDocument, params.options, range = params.range) } catch (_: Exception) { emptyList() }
    }

    /**
     * TASK-520 — textDocument/prepareCallHierarchy: a [CallHierarchyItem] when the caret
     * sits on a same-file local function name (definition or call site). Non-function
     * positions and empty/malformed buffers soft-degrade to an empty list without throw.
     */
    fun prepareCallHierarchy(params: CallHierarchyPrepareParams): List<CallHierarchyItem> =
        synchronized(stateLock) {
            try {
                listOfNotNull(resolveCallHierarchyItem(params.textDocument, params.position))
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * TASK-520 — callHierarchy/incomingCalls: same-file callers of the item's local
     * function via existing references (use sites) + enclosing local function
     * resolution. Unknown / non-function items yield empty without throw.
     */
    fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams
    ): List<CallHierarchyIncomingCall> = synchronized(stateLock) {
        try {
            collectIncomingCalls(params.item ?: return@synchronized emptyList())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * TASK-520 — callHierarchy/outgoingCalls: same-file callees invoked inside the
     * item's local function body, resolved through definition/goto when possible.
     * Unknown / non-function items yield empty without throw.
     */
    fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams
    ): List<CallHierarchyOutgoingCall> = synchronized(stateLock) {
        try {
            collectOutgoingCalls(params.item ?: return@synchronized emptyList())
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * textDocument/references. Honors [org.eclipse.lsp4j.ReferenceContext.isIncludeDeclaration]
     * (true — the LSP default when context is absent — keeps declaration sites; false
     * strips them). Non-symbol / out-of-range / unbound soft-degrade to empty or a
     * short list without throw.
     */
    fun references(params: ReferenceParams): List<Location> = synchronized(stateLock) {
        try {
            val path = pathOf(params.textDocument)
            val position = params.position.toParserPosition()
            val all = queries.references(path, position).map { it.toLspLocation() }
            // LSP defaults includeDeclaration to true when clients omit context.
            val includeDeclaration = params.context?.isIncludeDeclaration ?: true
            if (includeDeclaration || all.isEmpty()) all
            else filterOutDeclarationLocations(path, position, all)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * TASK-518 — textDocument/prepareRename: accepts renamable local-like identifiers
     * (locals, params, for-loop names, local functions, attribute locals) with an
     * identifier-span range + placeholder; soft-accepts table field / method NAME spans
     * on the NAME token. Keywords, literals, operators, comments, free globals without
     * a local binding, and out-of-range positions reject with null (LSP reject). Never throws.
     */
    fun prepareRename(params: PrepareRenameParams): PrepareRenameResult? = synchronized(stateLock) {
        try {
            resolveRenameTarget(params.textDocument, params.position)?.let { target ->
                PrepareRenameResult(target.identifierRange, target.placeholder)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * TASK-518 — textDocument/rename: a [WorkspaceEdit] of identifier-span TextEdits
     * covering declaration + references for same-file lexical locals using existing
     * references queries. Multi-file rename is intentionally limited to same-file URI
     * edits (cross-file module fields soft-empty rather than invent unsafe edits).
     * Missing / non-renamable positions yield an empty WorkspaceEdit without throw.
     */
    fun rename(params: RenameParams): WorkspaceEdit = synchronized(stateLock) {
        try {
            val newName = params.newName?.trim().orEmpty()
            if (newName.isEmpty() || !isValidLuaIdentifier(newName)) {
                return@synchronized WorkspaceEdit(emptyMap())
            }
            val target = resolveRenameTarget(params.textDocument, params.position)
                ?: return@synchronized WorkspaceEdit(emptyMap())
            val requestUri = params.textDocument.uri
            val requestPath = pathOf(params.textDocument)

            // Prefer same-file document highlights (already tightened to identifier spans)
            // so nested shadowing and local multi-occurrence renames stay lexical.
            val highlightLocations = queries.documentHighlights(requestPath, target.parserPosition)
                .mapNotNull { location ->
                    if (location.path != requestPath) {
                        return@mapNotNull null
                    }
                    val tightened = tightenDocumentHighlightLocation(
                        snapshot.files[requestPath]?.semanticFile,
                        requestPath,
                        location
                    )
                    Location(uriFor(tightened.path), tightened.range.toLspRange())
                }

            val referenceLocations = highlightLocations.ifEmpty {
                queries.references(requestPath, target.parserPosition).map { it.toLspLocation() }
            }

            // Same-file lexical policy: only emit edits for the requesting document URI.
            // Multi-file field rename may stay empty (documented limit).
            val sameFile = referenceLocations.filter { it.uri == requestUri || it.uri == uriFor(requestPath) }

            val ranges = linkedMapOf<Pair<String, org.eclipse.lsp4j.Range>, org.eclipse.lsp4j.Range>()
            // Always include the prepared identifier span at the caret.
            ranges[requestUri to target.identifierRange] = target.identifierRange
            sameFile.forEach { location ->
                val range = location.range
                // Only single-line spans of the expected identifier length are safe edits.
                // placeholder is a validated non-empty identifier, so an equal length is > 0.
                val length = range.end.character - range.start.character
                if (range.start.line == range.end.line && length == target.placeholder.length) {
                    ranges[location.uri to range] = range
                }
            }

            if (ranges.isEmpty()) {
                return@synchronized WorkspaceEdit(emptyMap())
            }

            val edits = ranges.values
                .sortedWith(
                    compareBy({ it.start.line }, { it.start.character }, { it.end.line }, { it.end.character })
                )
                .map { range -> TextEdit(range, newName) }

            WorkspaceEdit(mapOf(requestUri to edits))
        } catch (_: Exception) {
            WorkspaceEdit(emptyMap())
        }
    }

    /**
     * Drop declaration-site locations from a references multiset when
     * [org.eclipse.lsp4j.ReferenceContext.isIncludeDeclaration] is false.
     * Prefer [LuaWorkspaceQueryFacade.declaration] keys; fall back to
     * [LuaWorkspaceQueryFacade.gotoDefinition] when declaration is empty
     * (provider-backed members / synthetic module ranges). Dedup keys are
     * `(uri, lsp4j Range)` pairs — lsp4j Range has structural equals, so the
     * pair key matches the previous line/column string key exactly.
     */
    private fun filterOutDeclarationLocations(
        path: VirtualPath,
        position: Position,
        locations: List<Location>
    ): List<Location> {
        fun declarationKeysOf(query: (VirtualPath, Position) -> List<WorkspaceLocation>): Set<Pair<String, org.eclipse.lsp4j.Range>> =
            query(path, position).mapTo(mutableSetOf()) { uriFor(it.path) to it.range.toLspRange() }
        val declarationKeys = declarationKeysOf(queries::declaration).ifEmpty {
            declarationKeysOf(queries::gotoDefinition)
        }
        if (declarationKeys.isEmpty()) {
            // No known declaration site (unbound / non-symbol): keep use sites as-is.
            return locations
        }
        return locations.filterNot { location ->
            location.uri to location.range in declarationKeys
        }
    }

    /**
     * Resolve a renamable identifier at [lspPosition]. Returns null for non-identifiers,
     * keywords, free globals without a local binding, and missing documents.
     */
    private fun resolveRenameTarget(
        document: TextDocumentIdentifier?,
        lspPosition: org.eclipse.lsp4j.Position?
    ): RenameTarget? {
        if (document == null || lspPosition == null || lspPosition.line < 0 || lspPosition.character < 0) {
            return null
        }
        val path = pathOf(document)
        val source = sourceFor(path) ?: return null
        val parserPosition = lspPosition.toParserPosition()
        val semanticFile = snapshot.files[path]?.semanticFile

        // Prefer AST identifier when available (covers locals, params, for vars, fields).
        val identifier = identifierAt(semanticFile, parserPosition)
        if (identifier != null) {
            if (!isValidLuaIdentifier(identifier.name)) {
                return null
            }
            // Soft-accept member field / method NAME spans (policy allows accept or
            // soft-reject); everything else requires a local-like binding (rejects
            // free globals / builtins).
            val parent = identifier.parentOrNull()
            if (parent !is MemberExpression || parent.identifier !== identifier) {
                if (!isRenamableLocalLike(semanticFile, path, identifier, parserPosition)) {
                    return null
                }
            }
            return RenameTarget(
                placeholder = identifier.name,
                identifierRange = identifier.range.toLspRange(),
                parserPosition = parserPosition
            )
        }

        // Fallback: lexer NAME token at caret when semantic file is incomplete.
        val lexerName = lexerNameTokenAt(source, lspPosition) ?: return null
        // Without AST identifier, only accept when a same-file local AST binding of
        // this name exists and highlights/refs resolve (rejects free globals / builtins).
        if (!hasSameFileLocalBinding(semanticFile, lexerName.text)) {
            return null
        }
        // Same return for every reached path (declaration-site rename is accepted whenever a
        // same-file local AST binding exists); the highlight/reference probes that used to
        // gate two identical returns were pure reads with no effect on the result.
        return RenameTarget(
            placeholder = lexerName.text,
            identifierRange = lexerName.range,
            parserPosition = parserPosition
        )
    }

    /**
     * One identifier-at-caret scan shared by rename, highlight and call-hierarchy:
     * exact start match first, then the identifier covering the caret (inclusive
     * start) from the public identifiers index.
     */
    private fun identifierAt(
        semanticFile: WorkspaceSemanticFile?,
        position: Position
    ): Identifier? {
        if (semanticFile == null) {
            return null
        }
        semanticFile.identifiers.firstOrNull { identifier ->
            // Parser Range/Position are data classes: start equality == line+column match.
            identifier.range.start == position
        }?.let { return it }
        return semanticFile.identifiers.lastOrNull { identifier ->
            rangeContainsHighlight(identifier.range, position)
        }
    }

    /**
     * True when [identifier] resolves to a renamable local-like binding:
     * LOCAL / PARAMETER / local FUNCTION, for-loop vars, attribute locals.
     * Free globals (print, freeName) and MODULE/import aliases reject under lexical policy.
     */
    private fun isRenamableLocalLike(
        semanticFile: WorkspaceSemanticFile?,
        path: VirtualPath,
        identifier: Identifier,
        position: Position
    ): Boolean {
        // AttributeIdentifier / local decl / for-var / param / local function name
        // all resolve through the shared local-binding-site predicate.
        if (isLocalBindingSite(identifier)) {
            return true
        }

        val symbol = semanticFile?.model?.getSymbolAt(position)
            ?: semanticFile?.model?.getSymbolAt(identifier.range.start)

        when (symbol?.kind) {
            // Bare MODULE / class aliases are not lexical local renames; fields/methods
            // are soft-accepted earlier via the MemberExpression parent.
            SemanticSymbolKind.MODULE, SemanticSymbolKind.FIELD, SemanticSymbolKind.METHOD,
            SemanticSymbolKind.CLASS, SemanticSymbolKind.TYPE_ALIAS -> return false
            SemanticSymbolKind.PARAMETER, SemanticSymbolKind.LOCAL -> return true
            else -> Unit
        }

        // VARIABLE / FUNCTION / unknown: require a same-file AST local binding of this name
        // so free globals (print, freeName) and builtins are rejected.
        if (!hasSameFileLocalBinding(semanticFile, identifier.name)) {
            return false
        }

        // Confirm the caret symbol participates in that local binding via highlights/refs.
        return sameFileHighlights(path, position).isNotEmpty() ||
            sameFileReferences(path, position).isNotEmpty()
    }

    /**
     * Shared local-binding-site predicate (single owner for rename eligibility,
     * document-highlight write kinds and same-file local-binding scans):
     * attribute identifiers, `local`-flagged declarations, local-statement init
     * names, for-loop variables, function parameters and local function names.
     */
    private fun isLocalBindingSite(identifier: Identifier): Boolean {
        if (identifier.isLocal ||
            identifier is io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
        ) {
            return true
        }
        val parent = identifier.parentOrNull() ?: return false
        return when (parent) {
            is LocalStatement -> parent.init.any { it === identifier }
            is ForNumericStatement -> parent.variable === identifier
            is ForGenericStatement -> parent.variables.any { it === identifier }
            is FunctionDeclaration -> {
                parent.params.any { it === identifier } ||
                    (parent.isLocal && functionDeclarationNameIs(parent.identifier, identifier))
            }
            else -> false
        }
    }

    /**
     * True when a same-file AST local binding of [name] exists — the gate that
     * rejects free globals / builtins in the rename paths.
     */
    private fun hasSameFileLocalBinding(semanticFile: WorkspaceSemanticFile?, name: String): Boolean =
        semanticFile?.identifiers?.any { other ->
            other.name == name && isLocalBindingSite(other)
        } == true

    private fun isValidLuaIdentifier(name: String): Boolean =
        name.isNotEmpty() && (name[0] == '_' || name[0].isLetter()) &&
            // Keywords are not renamable identifiers (reject prepare on keyword positions).
            name.substring(1).all { ch -> ch == '_' || ch.isLetterOrDigit() } && name !in LUA_KEYWORDS

    private data class LexerNameToken(
        val text: String,
        val range: org.eclipse.lsp4j.Range
    )

    private data class RenameTarget(
        val placeholder: String,
        val identifierRange: org.eclipse.lsp4j.Range,
        val parserPosition: Position
    )

    /**
     * Find a NAME lexer token covering [lspPosition] (0-based). Returns null for
     * keywords, numbers, strings, operators, comments, whitespace, past-EOF.
     */
    private fun lexerNameTokenAt(source: String, lspPosition: org.eclipse.lsp4j.Position): LexerNameToken? {
        if (source.isEmpty()) {
            return null
        }
        val lexer = LuaLexer(source)
        try {
            while (true) {
                val type = lexer.nextToken()
                if (type == LuaTokenTypes.EOF) {
                    return null
                }
                val line0 = (lexer.tokenLine - 1).coerceAtLeast(0)
                val col0 = lexer.tokenColumn.coerceAtLeast(0)
                val text = lexer.tokenText.toString()
                // Only single-line NAME tokens covering the caret are rename targets.
                if (text.isEmpty() || '\n' in text || '\r' in text || line0 != lspPosition.line ||
                    lspPosition.character !in col0 until col0 + text.length
                ) {
                    continue
                }
                if (type != LuaTokenTypes.NAME || !isValidLuaIdentifier(text)) return null
                return LexerNameToken(text, org.eclipse.lsp4j.Range(
                    org.eclipse.lsp4j.Position(line0, col0),
                    org.eclipse.lsp4j.Position(line0, col0 + text.length)
                ))
            }
        } catch (_: Exception) {
            return null
        }
    }

    fun documentSymbols(path: String): List<SymbolInformation> = synchronized(stateLock) {
        val virtualPath = pathFromClientPath(path)
        val uri = uriFor(virtualPath)
        hierarchicalDocumentSymbols(virtualPath)
            .flatMap { flattenDocumentSymbol(it, uri = uri, containerName = null) }
    }

    fun hierarchicalDocumentSymbols(path: String): List<DocumentSymbol> = synchronized(stateLock) {
        hierarchicalDocumentSymbols(pathFromClientPath(path))
    }

    /** Client advertised hierarchical DocumentSymbol support (Either.right vs left in [LuaTextDocumentService.documentSymbol]). */
    fun supportsHierarchicalDocumentSymbols(): Boolean = synchronized(stateLock) { hierarchicalDocumentSymbolSupport }

    /** Client advertised workspace/symbol resolveSupport (Either.right vs left in [LuaWorkspaceService.symbol]). */
    fun supportsModernWorkspaceSymbols(): Boolean = synchronized(stateLock) { modernWorkspaceSymbolSupport }

    /** Client advertised definition.linkSupport (Either.right LocationLink vs left in [LuaTextDocumentService.definition]). */
    fun supportsDefinitionLink(): Boolean = synchronized(stateLock) { definitionLinkSupport }

    /**
     * Legacy workspace/symbol surface (Either.left SymbolInformation payloads). Both
     * this and [modernWorkspaceSymbols] delegate to
     * [LuaWorkspaceQueryFacade.workspaceSymbolEntries], which memoizes the
     * distinct+sorted entry universe per snapshot, caps results at 500 AFTER the
     * deterministic sort, excludes body-LOCAL declarations (only chunk-level locals
     * join globals/functions/classes/fields/methods and module export surfaces), and
     * drops extraProvider entries whose synthetic __jvm__ path is not actually indexed.
     */
    fun workspaceSymbols(query: String): List<SymbolInformation> = synchronized(stateLock) {
        queries.workspaceSymbolEntries(query).map { toSymbolInformation(it) }
    }

    /**
     * Modern workspace/symbol surface (WorkspaceSymbol payloads with inline Location).
     * Shares the facade entry policy documented on [workspaceSymbols]; location URIs stay
     * Either.left(Location) so legacy and modern payloads remain in lock-step.
     */
    fun modernWorkspaceSymbols(query: String): List<WorkspaceSymbol> = synchronized(stateLock) {
        queries.workspaceSymbolEntries(query).map { toWorkspaceSymbol(it) }
    }

    fun diagnostics(path: String): PublishDiagnosticsParams = synchronized(stateLock) {
        publishDiagnostics(pathFromClientPath(path))
    }

    fun diagnosticsForUri(uri: String): PublishDiagnosticsParams = synchronized(stateLock) {
        publishDiagnostics(pathOf(uri), uri)
    }

    private fun publishDiagnostics(
        path: VirtualPath,
        uri: String? = null,
        checkedFile: WorkspaceSnapshot.FileSnapshot? = null
    ): PublishDiagnosticsParams {
        val buffer = sourceFor(path)
        val semanticFile = (checkedFile ?: snapshot.files[path])?.semanticFile
        val bufferAnalyzed = semanticFile != null && buffer != null && semanticFile.source == buffer
        val parse = parseDiagnostics(path)
        // A recoverable syntax error in one region must not hide valid semantic
        // findings elsewhere; only analyzed buffers contribute semantic findings.
        val semantic = (semanticFile?.model?.getDiagnostics().orEmpty())
            .takeIf { bufferAnalyzed }
            ?.map { diagnostic ->
                Diagnostic().apply {
                    message = diagnostic.message
                    severity = diagnostic.severity.toLspSeverity()
                    code = diagnostic.code?.let { Either.forLeft<String, Int>(it) }
                    range = diagnostic.range
                        ?.takeIf { range -> range.start != range.end }
                        ?.toLspRange()
                        ?: fallbackDiagnosticRange(buffer)
                    // Raw LSP DiagnosticTag Ints (see toLspDiagnosticTag): unused-local
                    // publishes Unnecessary so clients fade it.
                    if (diagnostic.tags.isNotEmpty()) {
                        tags = diagnostic.tags.mapNotNull { tag -> tag.toLspDiagnosticTag() }
                    }
                }
            }
            .orEmpty()
        val distinct = (parse + semantic).distinctBy { diagnostic ->
            // lsp4j Range has structural equals, so range identity == start/end line+column.
            listOf(diagnostic.range, diagnostic.severity, diagnostic.message)
        }
        // Bound the payload: a typo-heavy file can produce hundreds of per-site
        // unresolved-global warnings; clients choke on megabyte diagnostic arrays.
        // Truncation is announced with one trailing Hint (code diagnostics.truncated),
        // so a capped payload is distinguishable from a clean bill of health.
        val truncatedCount = distinct.size - PUBLISH_DIAGNOSTICS_CAP
        val diagnostics = if (truncatedCount > 0) {
            distinct.take(PUBLISH_DIAGNOSTICS_CAP) +
                Diagnostic().apply {
                    range = fallbackDiagnosticRange(buffer)
                    severity = org.eclipse.lsp4j.DiagnosticSeverity.Hint
                    code = Either.forLeft<String, Int>("diagnostics.truncated")
                    message = "$truncatedCount more diagnostics truncated"
                }
        } else {
            distinct
        }
        return PublishDiagnosticsParams(uri ?: uriFor(path), diagnostics)
    }

    /**
     * Fallback anchor for semantic diagnostics without a usable range (null or zero-width):
     * a 1-character span on the first token of the document's first non-blank line, or a
     * 1-character span at the document head when no source is available / blank.
     */
    private fun fallbackDiagnosticRange(source: String?): org.eclipse.lsp4j.Range =
        source.orEmpty().lineSequence()
            .mapIndexedNotNull { lineIndex, lineText ->
                lineText.indexOfFirst { char -> !char.isWhitespace() }.takeIf { it >= 0 }?.let { column ->
                    org.eclipse.lsp4j.Range(
                        org.eclipse.lsp4j.Position(lineIndex, column),
                        org.eclipse.lsp4j.Position(lineIndex, column + 1)
                    )
                }
            }
            .firstOrNull()
            ?: org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(0, 0), org.eclipse.lsp4j.Position(0, 1))

    /**
     * Parse diagnostics for [path], taken from the workspace parse (which recovers
     * leniently — an improvement over the old per-keystroke strict re-parse). The
     * strict re-parse remains only as a fallback for not-yet-analyzed documents.
     */
    private fun parseDiagnostics(path: VirtualPath): List<Diagnostic> {
        val source = sourceFor(path) ?: return emptyList()
        val semanticFile = snapshot.files[path]?.semanticFile
        if (semanticFile != null && semanticFile.source == source) {
            return semanticFile.recoveryDiagnostics.map { parseDiagnostic(it.message, it.range) }
        }
        val result = try {
            LuaParser().parseWithDiagnostics(source)
        } catch (error: IllegalStateException) {
            return listOf(parseDiagnostic(error.message, Range(Position(1, 1), Position(1, 2))))
        }
        return result.recoveryDiagnostics.map { parseDiagnostic(it.message, it.range) }
    }

    private fun parseDiagnostic(message: String?, range: Range): Diagnostic = Diagnostic().apply {
        this.message = message?.takeIf { it.isNotBlank() } ?: "Lua parse error"
        severity = org.eclipse.lsp4j.DiagnosticSeverity.Error
        code = Either.forLeft<String, Int>("lua-parse")
        this.range = range.toLspRange()
    }

    // Open overlays are authoritative over disk index entries (index files first,
    // then open buffers — same insertion order as the previous hand-built map).
    private fun currentWorkspaceFiles(): Map<VirtualPath, String> = indexedWorkspaceFiles + openDocuments

    private fun applyWorkspaceResult(result: WorkspaceUpdateResult, files: Map<VirtualPath, String>) {
        snapshot = result.snapshot
        queries = LuaWorkspaceQueryFacade(snapshot)
        signalQueryPublication()
        lastSyncedFiles = files.toMap()
        snapshotReady = true
    }

    /**
     * Full workspace rebuild used for cold start and metadata invalidation.
     * Returns the documents the engine reported as affected (every analyzed file on a
     * cold build) so callers can republish diagnostics without re-deriving the set.
     */
    private fun rebuildFull(): Set<VirtualPath> = synchronized(stateLock) {
        rebuildFullLocked(currentWorkspaceFiles())
    }

    private val liveBufferAnalysisLock = Any()

    private fun analyzeLiveBuffers(files: Map<VirtualPath, String>): WorkspaceUpdateResult =
        synchronized(liveBufferAnalysisLock) {
            val started = System.currentTimeMillis()
            System.err.println("LSP-DEVICE: live analysis start files=${files.size}")
            val previous = liveBufferSnapshot
            liveAnalysisCheckpoint.set {
                synchronized(stateLock) {
                    if (closed) throw java.util.concurrent.CancellationException("Language server closed")
                    if (snapshotReady || openDocuments != files) throw ObsoleteLiveAnalysis()
                }
            }
            val reporter = ProgressReporter { progress ->
                onLiveAnalysisProgress?.invoke(progress)
                checkLiveAnalysisCurrent()
            }
            val result = try {
                if (previous == null) {
                    liveBufferEngine.build(LuaWorkspaceInput(files = files,
                        metadata = synchronized(stateLock) { workspaceMetadata.toMap() }), reporter = reporter)
                } else {
                    liveBufferEngine.update(previous, WorkspaceDelta(
                        upserts = files.filter { (path, text) -> previous.files[path]?.semanticFile?.source != text },
                        removals = previous.files.keys - files.keys
                    ), reporter = reporter)
                }
            } finally {
                liveAnalysisCheckpoint.remove()
            }
            liveBufferSnapshot = result.snapshot
            System.err.println("LSP-DEVICE: live analysis done ${System.currentTimeMillis() - started}ms files=${files.size}")
            result
        }

    /** Open-buffer overlay first, then the indexed workspace source. */
    private fun sourceFor(path: VirtualPath): String? =
        openDocuments[path] ?: indexedWorkspaceFiles[path]

    /**
     * One delta computation shared by the foreground incremental refresh, the
     * deferred-analysis worker and the background rebuild reconcile loop:
     * upserts are paths whose source differs from [previous], removals are
     * paths absent from [files].
     */
    private fun workspaceDelta(
        previous: Map<VirtualPath, String>,
        files: Map<VirtualPath, String>
    ): WorkspaceDelta = WorkspaceDelta(
        // filter / set-difference preserve the maps' iteration order (LinkedHashMap in,
        // LinkedHashMap out), matching the previous hand-rolled loops.
        upserts = files.filter { (path, source) -> previous[path] != source },
        removals = previous.keys - files.keys
    )

    private fun refreshIncremental(): Set<VirtualPath> {
        val files = currentWorkspaceFiles()
        if (!snapshotReady) {
            if (!deferDocumentAnalysis) return rebuildFullLocked(files)
            // Analyze only the live buffers while the authoritative JVM index
            // builds. A separate Lua engine avoids both the dex cost and access
            // to the background engine's mutable caches.
            val result = analyzeLiveBuffers(openDocuments.toMap())
            snapshot = result.snapshot
            queries = LuaWorkspaceQueryFacade(snapshot)
            return result.affectedDocuments
        }

        val delta = workspaceDelta(lastSyncedFiles, files)
        if (delta.upserts.isEmpty() && delta.removals.isEmpty()) {
            return emptySet()
        }

        val result = synchronized(engineLock) { engine.update(
            previous = snapshot,
            delta = delta
        ) }
        incrementalUpdateCount += 1
        applyWorkspaceResult(result, files)
        return result.affectedDocuments
    }

    private fun configuredWorkspaceFolders(params: InitializeParams): List<WorkspaceFolder> {
        params.workspaceFolders.orEmpty().toList().takeIf { it.isNotEmpty() }?.let { return it }
        // rootUri shares the same WorkspaceFolder + normalization path as workspace folders.
        val rootUri = params.rootUri?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(WorkspaceFolder(rootUri, workspaceFolderName(rootUri)))
    }

    /** Runs [action] for every configured folder whose URI resolves to an existing directory. */
    private inline fun eachIndexedWorkspaceFolderRoot(action: (WorkspaceFolder, Path) -> Unit) =
        workspaceFolders.forEach { folder ->
            workspaceFolderRoot(folder)?.takeIf(Files::isDirectory)?.let { action(folder, it) }
        }

    private fun refreshWorkspaceFolderIndex() {
        indexedWorkspaceFiles.clear()
        indexedWorkspaceUris.clear()
        eachIndexedWorkspaceFolderRoot { _, root -> indexWorkspaceFolder(root) }
    }

    private fun refreshWorkspaceFolderUriPrefixes() {
        workspaceFolderUriPrefixes.clear()
        eachIndexedWorkspaceFolderRoot { folder, _ ->
            val uri = folder.uri ?: return@eachIndexedWorkspaceFolderRoot
            normalizeLspFileUriPath(uri)?.normalizeWorkspacePathPrefix()
                // Already normalized in the chain above (normalizeWorkspacePathPrefix is idempotent).
                ?.takeIf { it.isNotBlank() }
                ?.let { workspaceFolderUriPrefixes[it] = "" }
        }
    }

    private fun indexWorkspaceFolder(root: Path) {
        val stream = try {
            Files.walk(root)
        } catch (_: IOException) {
            return
        } catch (_: SecurityException) {
            return
        }

        try {
            stream.filter { path -> isLuaWorkspaceFile(path) }.forEach { path ->
                val source = readWorkspaceSource(path) ?: return@forEach
                val virtualPath = virtualPathForWorkspaceFile(root, path) ?: return@forEach
                indexedWorkspaceFiles[virtualPath] = source
                indexedWorkspaceUris[virtualPath] = path.toUri().toString()
            }
        } catch (_: UncheckedIOException) {
        } catch (_: SecurityException) {
        } finally {
            stream.close()
        }
    }

    /**
     * Resolves a workspace folder (from either `workspaceFolders` or legacy `rootUri`)
     * through the shared file-URI → Path normalization path.
     */
    private fun workspaceFolderRoot(folder: WorkspaceFolder): Path? =
        folder.uri?.let(::pathFromFileUri)

    private fun workspaceFolderName(uri: String): String =
        normalizeLspFileUriPath(uri)
            ?.normalizeWorkspacePathPrefix()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "workspace"

    /** Shared `.lua`/`.aly` extension test (case-insensitive) for workspace files and URIs. */
    private fun hasLuaOrAlyExtension(name: String): Boolean =
        name.lowercase().let { it.endsWith(".lua") || it.endsWith(".aly") }

    private fun isLuaWorkspaceFile(path: Path): Boolean =
        Files.isRegularFile(path) && hasLuaOrAlyExtension(path.fileName?.toString().orEmpty())

    private fun isLuaOrAlyUri(uri: String): Boolean =
        hasLuaOrAlyExtension(normalizeLspFileUriPath(uri) ?: uri)

    private fun virtualPathForWatchedUri(uri: String): VirtualPath {
        val absolute = pathFromFileUri(uri)
        if (absolute != null) {
            for (folder in workspaceFolders) {
                val absoluteRoot = workspaceFolderRoot(folder)?.toAbsolutePath()?.normalize() ?: continue
                if (!absolute.startsWith(absoluteRoot)) continue
                virtualPathForWorkspaceFile(absoluteRoot, absolute)?.let { return it }
            }
        }
        return pathOf(uri)
    }

    private fun virtualPathForWorkspaceFile(root: Path, path: Path): VirtualPath? {
        val absoluteRoot = root.toAbsolutePath().normalize()
        val absolutePath = path.toAbsolutePath().normalize()
        val relativePath = try {
            absoluteRoot.relativize(absolutePath)
        } catch (_: IllegalArgumentException) {
            absolutePath
        }
        return relativePath.toString().toVirtualPathOrNull()
    }

    private fun readWorkspaceSource(path: Path): String? {
        // Stream-based (not Files.readString): available on every Android API level
        // the android/ module compiles against; identical semantics here.
        return try {
            Files.newInputStream(path).use { input -> input.readBytes().toString(StandardCharsets.UTF_8) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Shared file/scheme-less URI → filesystem [Path] conversion used by rootUri,
     * workspace folders, watched-file events, and disk reads.
     */
    private fun pathFromFileUri(uri: String): Path? {
        return try {
            val parsed = URI(uri)
            val scheme = parsed.scheme
            val path = when {
                scheme.equals("file", ignoreCase = true) -> Paths.get(parsed)
                scheme.isNullOrBlank() -> Paths.get(uri)
                else -> return null
            }
            path.toAbsolutePath().normalize()
        } catch (_: Exception) {
            // URISyntaxException / IllegalArgumentException / FileSystemNotFoundException:
            // unencoded spaces and other reserved characters in file URIs must not crash
            // workspace open/watch paths; fall back to manual path extraction.
            val fallback = normalizeLspFileUriPath(uri) ?: return null
            try {
                Paths.get(fallback).toAbsolutePath().normalize()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun serverCapabilities(): ServerCapabilities {
        return ServerCapabilities().apply {
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Incremental)
            hoverProvider = Either.forRight(HoverOptions())
            declarationProvider = Either.forLeft(true)
            definitionProvider = Either.forRight(DefinitionOptions())
            referencesProvider = Either.forRight(ReferenceOptions())
            documentHighlightProvider = Either.forLeft(true)
            completionProvider = CompletionOptions().apply {
                triggerCharacters = listOf(".", ":")
                resolveProvider = true
            }
            signatureHelpProvider = SignatureHelpOptions(listOf("(", ","), listOf(")"))
            documentSymbolProvider = Either.forRight(DocumentSymbolOptions())
            workspaceSymbolProvider = Either.forRight(WorkspaceSymbolOptions())
            foldingRangeProvider = Either.forLeft(true)
            selectionRangeProvider = Either.forLeft(true)
            // TASK-541 / TASK-252: full semantic tokens with legend + delta support.
            semanticTokensProvider = SemanticTokensWithRegistrationOptions(
                SEMANTIC_TOKENS_LEGEND,
                SemanticTokensServerFull(/* delta = */ true)
            )
            // TASK-274 onTypeFormatting and TASK-542 codeAction are intentionally NOT
            // advertised (adversarial audit: whole-document reformat on keystroke /
            // always-empty action list).
            inlayHintProvider = Either.forLeft(true)
            documentFormattingProvider = Either.forLeft(true)
            documentRangeFormattingProvider = Either.forLeft(true)
            callHierarchyProvider = Either.forLeft(true)
            // TASK-518: prepareProvider=true (lsp4j name for prepareSupport) signals prepareRename.
            renameProvider = Either.forRight(RenameOptions(true))
            // Multi-root: advertise workspace folder support + change notifications (TASK-516).
            workspace = WorkspaceServerCapabilities(
                WorkspaceFoldersOptions().apply {
                    supported = true
                    setChangeNotifications(true)
                }
            )
        }
    }

    private fun hierarchicalDocumentSymbols(path: VirtualPath): List<DocumentSymbol> =
        queries.documentSymbols(path).map(::toDocumentSymbol)

    private fun flattenDocumentSymbol(
        symbol: DocumentSymbol,
        uri: String,
        containerName: String?
    ): List<SymbolInformation> =
        listOf(SymbolInformation(symbol.name, symbol.kind, Location(uri, symbol.selectionRange ?: symbol.range), containerName)) +
            symbol.children.orEmpty().flatMap { flattenDocumentSymbol(it, uri, symbol.name) }

    private fun toDocumentSymbol(symbol: WorkspaceDocumentSymbol): DocumentSymbol = DocumentSymbol(
        symbol.name,
        symbol.kind.toLspSymbolKind(),
        symbol.range.toLspRange(),
        symbol.selectionRange.toLspRange(),
        symbol.detail,
        symbol.children.map(::toDocumentSymbol)
    )

    /** Shared workspace-symbol-entry → LSP Location (same URI + range policy for both surfaces). */
    private fun WorkspaceSymbolEntry.toEntryLocation(): Location =
        Location(uriFor(path), range.toLspRange())

    private fun toSymbolInformation(entry: WorkspaceSymbolEntry): SymbolInformation =
        SymbolInformation(entry.name, entry.kind.toLspSymbolKind(), entry.toEntryLocation(), entry.containerName)

    private fun toWorkspaceSymbol(entry: WorkspaceSymbolEntry): WorkspaceSymbol =
        WorkspaceSymbol(entry.name, entry.kind.toLspSymbolKind(), Either.forLeft(entry.toEntryLocation()), entry.containerName)

    private fun pathOf(document: TextDocumentIdentifier): VirtualPath = pathOf(document.uri)

    private fun pathOf(uri: String): VirtualPath = lspVirtualPathFromUri(
        uri,
        workspaceFolderUriPrefixes,
        collapseSyntheticWorkspaceRoot = shouldCollapseSyntheticWorkspaceRoot()
    )

    private fun pathFromClientPath(pathOrUri: String): VirtualPath {
        // [pathOf] is the shared fallback for every branch below: the raw client text
        // re-interpreted as a document URI (same prefix/collapse policy).
        val normalizedPath = pathOrUri.normalizeWorkspacePathPrefix()
        val workspacePath = normalizedPath.workspaceRelativePath(workspaceFolderUriPrefixes)
        if (workspacePath != normalizedPath) {
            return workspacePath.toVirtualPathOrNull() ?: pathOf(pathOrUri)
        }
        if (pathOrUri.looksLikeUri()) {
            return pathOf(pathOrUri)
        }
        val clientPath = normalizedPath.syntheticWorkspaceRelativePath(shouldCollapseSyntheticWorkspaceRoot())
        return clientPath.toVirtualPathOrNull() ?: pathOf(pathOrUri)
    }

    private fun uriFor(path: VirtualPath): String = documentUris[path] ?: indexedWorkspaceUris[path] ?: lspFileUri(path)

    /** Workspace location → LSP Location (URI from the document/observed URI maps first). */
    private fun WorkspaceLocation.toLspLocation(): Location {
        return Location(uriFor(path), range.toLspRange())
    }

    /** Blank docs stay null; otherwise a markdown MarkupContent Either (shared with hover). */
    private fun String?.toLspMarkdownDoc(): Either<String, MarkupContent>? {
        return this?.takeIf { it.isNotBlank() }?.let { Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it)) }
    }

    /**
     * Same-file facade query shared by [sameFileHighlights] and [sameFileReferences]:
     * soft-degrades to empty when the facade throws, then filters to [path].
     */
    private fun <T : WorkspaceLocation> sameFileQuery(
        path: VirtualPath,
        position: Position,
        query: LuaWorkspaceQueryFacade.(VirtualPath, Position) -> List<T>
    ): List<T> = try {
        queries.query(path, position)
    } catch (_: Exception) {
        emptyList()
    }.filter { it.path == path }

    private fun sameFileHighlights(path: VirtualPath, position: Position): List<WorkspaceLocation> =
        sameFileQuery(path, position) { file, at -> documentHighlights(file, at) }

    private fun sameFileReferences(path: VirtualPath, position: Position): List<WorkspaceLocation> =
        sameFileQuery(path, position) { file, at -> references(file, at) }

    private fun shouldCollapseSyntheticWorkspaceRoot(): Boolean = workspaceFolders.isEmpty()

    /**
     * Prefer identifier-token ranges when the workspace highlight surface over-extends
     * (e.g. binary RHS or multi-line declaration spans). Same-file only.
     */
    private fun tightenDocumentHighlightLocation(
        semanticFile: WorkspaceSemanticFile?,
        requestPath: VirtualPath,
        location: WorkspaceLocation
    ): WorkspaceLocation {
        if (semanticFile == null || location.path != requestPath) {
            return location
        }
        val identifier = identifierAtHighlight(semanticFile, location.range) ?: return location
        val idRange = identifier.range
        // Shrink over-extended declaration/expression spans down to the identifier token
        // when the current highlight fully covers that token on the same line/start region;
        // an already-equal span stays as-is (Parser Range is a data class).
        return if (comparePositions(location.range.start, idRange.start) <= 0 &&
            comparePositions(idRange.end, location.range.end) <= 0 &&
            location.range != idRange
        ) {
            WorkspaceLocation(location.path, idRange)
        } else {
            location
        }
    }

    private fun documentHighlightKindFor(
        semanticFile: WorkspaceSemanticFile?,
        requestPath: VirtualPath,
        location: WorkspaceLocation
    ): DocumentHighlightKind {
        val identifier = semanticFile
            ?.takeIf { location.path == requestPath }
            ?.let { identifierAtHighlight(it, location.range) }
            ?: return DocumentHighlightKind.Read
        return if (isDocumentHighlightWriteSite(identifier)) DocumentHighlightKind.Write else DocumentHighlightKind.Read
    }
    private fun identifierAtHighlight(
        semanticFile: WorkspaceSemanticFile,
        range: Range
    ): Identifier? {
        // Exact-start/covering stages come from the shared identifierAt scan; the
        // over-extended-highlight containment stage is highlight-specific.
        semanticFile.identifiers.firstOrNull { identifier ->
            identifier.range.start == range.start
        }?.let { return it }

        // Identifier fully contained by an over-extended highlight (declaration lines).
        semanticFile.identifiers.firstOrNull { identifier ->
            comparePositions(range.start, identifier.range.start) <= 0 &&
                comparePositions(identifier.range.end, range.end) <= 0 &&
                identifier.range.start.line == range.start.line
        }?.let { return it }

        return identifierAt(semanticFile, range.start)
    }

    /**
     * Write sites for documentHighlight kinds:
     * - local / for-loop / parameter / function-name declarations
     * - assignment LHS identifiers (AST quirk: AssignmentStatement.init = LHS)
     * Pure uses (reads) stay Read.
     */
    private fun isDocumentHighlightWriteSite(identifier: Identifier): Boolean {
        // Declaration sites (local / for-var / param / local function name) are writes.
        if (isLocalBindingSite(identifier)) {
            return true
        }
        val parent = identifier.parentOrNull() ?: return false
        return when (parent) {
            is AssignmentStatement -> parent.init.any { lhs ->
                lhs === identifier || assignmentLhsContainsIdentifier(lhs, identifier)
            }
            is FunctionDeclaration ->
                // Global function names are write sites too (they assign the global).
                functionDeclarationNameIs(parent.identifier, identifier)
            is MemberExpression ->
                // Member assignment LHS: t.field = ... where highlight is on `field`.
                parent.identifier === identifier && isAssignmentLhsExpression(parent)
            else -> false
        }
    }

    private fun functionDeclarationNameIs(nameExpression: ExpressionNode?, identifier: Identifier): Boolean =
        when (nameExpression) {
            is Identifier -> nameExpression === identifier
            is MemberExpression -> nameExpression.identifier === identifier
            else -> false
        }

    private fun assignmentLhsContainsIdentifier(lhs: ExpressionNode, identifier: Identifier): Boolean =
        functionDeclarationNameIs(lhs, identifier) ||
            lhs is MemberExpression && assignmentLhsContainsIdentifier(lhs.base, identifier)

    private fun isAssignmentLhsExpression(expression: ExpressionNode): Boolean {
        var current: BaseASTNode? = expression
        while (current != null) {
            val parent = current!!.parentOrNull() ?: return false
            if (parent is AssignmentStatement) {
                return parent.init.any { it === current }
            }
            if (parent is MemberExpression && parent.base === current) {
                current = parent
                continue
            }
            return false
        }
        return false
    }

    private fun rangeContainsHighlight(range: Range, position: Position): Boolean =
        comparePositions(range.start, position) <= 0 && comparePositions(position, range.end) <= 0

    /**
     * Lex [source] and emit LSP semantic-token data:
     * `[deltaLine, deltaStart, length, tokenType, tokenModifiers]*`.
     * Multi-line comments/strings are split into per-line spans (LSP contract).
     */
    private fun encodeSemanticTokens(
        source: String,
        semanticFile: WorkspaceSemanticFile?
    ): List<Int> {
        if (source.isEmpty()) {
            return emptyList()
        }
        val classification = buildSemanticTokenNameClassification(semanticFile)
        val absolute = mutableListOf<AbsoluteSemanticToken>()
        val lexer = LuaLexer(source)
        var previousWasFunctionKeyword = false
        var pendingNameAsFunctionCall = false
        try {
            while (true) {
                val type = lexer.nextToken()
                if (type == LuaTokenTypes.EOF) {
                    break
                }
                // Whitespace / newlines must NOT clear function-name lookahead
                // (`function   greet`) or call-site lookahead (`greet  (`).
                if (type == LuaTokenTypes.WHITE_SPACE || type == LuaTokenTypes.NEW_LINE) continue
                if (type == LuaTokenTypes.BAD_CHARACTER) {
                    previousWasFunctionKeyword = false; pendingNameAsFunctionCall = false
                    continue
                }

                val line0 = (lexer.tokenLine - 1).coerceAtLeast(0)
                val col0 = lexer.tokenColumn.coerceAtLeast(0)
                val text = lexer.tokenText.toString()
                val mapped = mapLexerTokenToSemanticType(
                    type = type,
                    text = text,
                    line1 = lexer.tokenLine,
                    column1 = lexer.tokenColumn + 1, // parser positions are 1-based columns
                    previousWasFunctionKeyword = previousWasFunctionKeyword,
                    classification = classification
                )

                if (type == LuaTokenTypes.FUNCTION || type == LuaTokenTypes.LAMBDA) {
                    previousWasFunctionKeyword = true; pendingNameAsFunctionCall = false
                } else if (type == LuaTokenTypes.NAME) {
                    previousWasFunctionKeyword = false; pendingNameAsFunctionCall = true
                } else if (type == LuaTokenTypes.LPAREN) {
                    // NAME ( … ) → treat the preceding NAME as a function/call when no
                    // stronger classification already applied.
                    if (pendingNameAsFunctionCall) {
                        absolute.lastOrNull()?.takeIf { it.tokenType == TYPE_VARIABLE }?.let { last ->
                            absolute[absolute.lastIndex] = last.copy(tokenType = TYPE_FUNCTION)
                        }
                    }
                    previousWasFunctionKeyword = false; pendingNameAsFunctionCall = false
                } else if (type != LuaTokenTypes.DOT && type != LuaTokenTypes.COLON) {
                    previousWasFunctionKeyword = false; pendingNameAsFunctionCall = false
                }

                if (mapped == null) {
                    continue
                }
                appendAbsoluteTokenSpans(absolute, line0, col0, text, mapped)
            }
        } catch (_: Exception) {
            // Partial tokens already collected are still useful for editor highlighting.
        }

        return deltaEncodeSemanticTokens(absolute)
    }

    private data class AbsoluteSemanticToken(
        val line: Int,
        val startChar: Int,
        val length: Int,
        val tokenType: Int
    )

    private data class CachedSemanticTokens(
        val resultId: String,
        val data: List<Int>
    )

    private data class NameClassification(
        val parameters: Set<NameKey>,
        val functions: Set<NameKey>
    )

    private data class NameKey(val line: Int, val column: Int, val name: String)

    private fun buildSemanticTokenNameClassification(
        semanticFile: WorkspaceSemanticFile?
    ): NameClassification {
        if (semanticFile == null) {
            return NameClassification(parameters = emptySet(), functions = emptySet())
        }
        val parameters = linkedSetOf<NameKey>()
        val functions = linkedSetOf<NameKey>()
        // Resolve each occurrence in its own lexical scope. A name used as a parameter
        // in one function says nothing about a same-named local in another scope.
        semanticFile.identifiers.forEach { identifier ->            when (semanticFile.model.getSymbolAt(identifier.range.start)?.kind) {
                SemanticSymbolKind.PARAMETER -> parameters += nameKey(identifier)
                SemanticSymbolKind.FUNCTION, SemanticSymbolKind.METHOD -> functions += nameKey(identifier)
                else -> Unit
            }
        }
        return NameClassification(parameters = parameters, functions = functions)
    }

    private fun nameKey(identifier: Identifier): NameKey =
        NameKey(identifier.range.start.line, identifier.range.start.column, identifier.name)

    private fun mapLexerTokenToSemanticType(
        type: LuaTokenTypes,
        text: String,
        line1: Int,
        column1: Int,
        previousWasFunctionKeyword: Boolean,
        classification: NameClassification
    ): Int? {
        return when (type) {
            LuaTokenTypes.SHORT_COMMENT,
            LuaTokenTypes.BLOCK_COMMENT,
            LuaTokenTypes.DOC_COMMENT,
            LuaTokenTypes.SHEBANG_CONTENT -> TYPE_COMMENT

            LuaTokenTypes.STRING,
            LuaTokenTypes.LONG_STRING -> TYPE_STRING

            LuaTokenTypes.NUMBER -> TYPE_NUMBER

            // NAME classification order: parameter > function/call > variable.
            LuaTokenTypes.NAME -> when {
                NameKey(line1, column1, text) in classification.parameters -> TYPE_PARAMETER
                NameKey(line1, column1, text) in classification.functions ||
                    previousWasFunctionKeyword -> TYPE_FUNCTION
                else -> TYPE_VARIABLE
            }

            // Keywords (incl. literals true/false/nil and operators and/or/not/in).
            LuaTokenTypes.AND, LuaTokenTypes.BREAK, LuaTokenTypes.CASE, LuaTokenTypes.CONTINUE,
            LuaTokenTypes.DEFAULT, LuaTokenTypes.DO, LuaTokenTypes.ELSE, LuaTokenTypes.ELSEIF,
            LuaTokenTypes.END, LuaTokenTypes.FALSE, LuaTokenTypes.FOR, LuaTokenTypes.FUNCTION,
            LuaTokenTypes.GOTO, LuaTokenTypes.IF, LuaTokenTypes.IN, LuaTokenTypes.LAMBDA,
            LuaTokenTypes.LOCAL, LuaTokenTypes.NIL, LuaTokenTypes.NOT, LuaTokenTypes.OR,
            LuaTokenTypes.REPEAT, LuaTokenTypes.RETURN, LuaTokenTypes.SWITCH, LuaTokenTypes.THEN,
            LuaTokenTypes.TRUE, LuaTokenTypes.UNTIL, LuaTokenTypes.WHEN, LuaTokenTypes.WHILE -> TYPE_KEYWORD

            else -> null
        }
    }

    private fun appendAbsoluteTokenSpans(
        out: MutableList<AbsoluteSemanticToken>,
        startLine: Int,
        startChar: Int,
        text: String,
        tokenType: Int
    ) {
        if (text.isEmpty()) return
        var line = startLine
        var col = startChar
        var index = 0
        while (index < text.length) {
            // Span end = next line break (or end of text).
            val end = text.indexOfAny(charArrayOf('\n', '\r'), index).takeIf { it >= 0 } ?: text.length
            if (end > index) {
                out += AbsoluteSemanticToken(line, col, end - index, tokenType)
            }
            if (end >= text.length) {
                break
            }
            // Consume newline sequence (LF, CR, or CRLF).
            index = if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') end + 2 else end + 1
            line += 1
            col = 0
        }
    }

    private fun deltaEncodeSemanticTokens(tokens: List<AbsoluteSemanticToken>): List<Int> {
        if (tokens.isEmpty()) {
            return emptyList()
        }
        val ordered = tokens.sortedWith(
            compareBy<AbsoluteSemanticToken> { it.line }.thenBy { it.startChar }
        )
        val data = ArrayList<Int>(ordered.size * 5)
        var prevLine = 0
        var prevChar = 0
        for (token in ordered) {
            // appendAbsoluteTokenSpans only emits non-negative, non-empty spans and the
            // sort above orders by line then startChar, so deltaLine/deltaStart are >= 0.
            val deltaLine = token.line - prevLine
            val deltaStart = if (deltaLine == 0) token.startChar - prevChar else token.startChar
            data += deltaLine
            data += deltaStart
            data += token.length
            data += token.tokenType
            data += 0 // no modifiers in this provider
            prevLine = token.line
            prevChar = token.startChar
        }
        return data
    }

    /**
     * AST for a document the snapshot has not analyzed yet (folding, selection ranges,
     * call hierarchy, inlay hints). Parses leniently to match how the workspace would
     * have parsed the same buffer — a strict parse here produced a differently shaped
     * tree than the identical document once indexed.
     */
    private fun parseChunkForFolding(source: String): ChunkNode? {
        return try {
            LuaParser().parseWorkspaceSnippet(source)
        } catch (_: Exception) {
            null
        }
    }

    private fun documentLineCount(source: String): Int =
        if (source.isEmpty()) 0 else source.count { it == '\n' } + 1

    /**
     * Walk the AST and emit a FoldingRange for every multi-line
     * [FunctionDeclaration] and [TableConstructorExpression]. Nested
     * function-in-table / table-in-function shapes remain foldable independently.
     */
    private fun collectFoldingRanges(chunk: ChunkNode, lineCount: Int?): List<FoldingRange> {
        val ranges = mutableListOf<FoldingRange>()
        val visitor = object : UnitVisitor() {
            override fun visitFunctionDeclaration(node: FunctionDeclaration, value: Unit) {
                maybeAddFold(node.range, ranges, lineCount); super.visitFunctionDeclaration(node, value)
            }

            override fun visitTableConstructorExpression(node: TableConstructorExpression, value: Unit) {
                maybeAddFold(node.range, ranges, lineCount); super.visitTableConstructorExpression(node, value)
            }
        }
        visitor.visitChunkNode(chunk, Unit)
        return ranges
    }

    private fun maybeAddFold(
        range: Range,
        out: MutableList<FoldingRange>,
        lineCount: Int?
    ) {
        // Parser positions are 1-based; LSP FoldingRange lines are 0-based. The end
        // position is exclusive-ish in some recoveries: clamp to the last character
        // line first, then require a strictly multi-line span (a pre-clamp check is
        // subsumed — clamping never extends endLine).
        val startLine = (range.start.line - 1).coerceAtLeast(0)
        var endLine = (range.end.line - 1).coerceAtLeast(0)
        if (lineCount != null) {
            if (startLine >= lineCount) return
            endLine = endLine.coerceAtMost(lineCount - 1)
        }
        if (endLine <= startLine) return
        out += FoldingRange(startLine, endLine)
    }

    /**
     * Build an LSP SelectionRange chain for [position] by walking the AST parent
     * links from the innermost covering node. Only real AST ranges are used;
     * consecutive identical ranges are collapsed; each parent must fully contain
     * its child (LSP SelectionRange contract). Nest from outermost down so the
     * returned root is the leaf selection.
     */
    private fun selectionRangeAt(index: NodePositionIndex, position: Position): SelectionRange? {
        val leaf = index.findInnermost(position) ?: return null
        val chainRanges = mutableListOf<Range>()
        var current: BaseASTNode? = leaf
        var depth = 0
        while (current != null && depth < 256) {
            val range = current!!.range
            val previous = chainRanges.lastOrNull()
            if (isOrderedAstRange(range) && (previous == null ||
                    // Only expand when the parent strictly contains the prior span;
                    // equal/non-containing parents keep walking upward for a wider span.
                    (previous != range && rangeContainsHighlight(range, previous.start) &&
                        rangeContainsHighlight(range, previous.end)))
            ) {
                chainRanges += range
            }
            current = current.parentOrNull()
            depth += 1
        }
        return chainRanges.foldRight(null as SelectionRange?) { range, parent ->
            SelectionRange(range.toLspRange(), parent)
        }
    }

    private class DocumentContext(
        val path: VirtualPath,
        /** Non-null by construction: documentContextFor rejects unresolvable sources. */
        val source: String,
        val semanticFile: WorkspaceSemanticFile?,
        /** Non-null by construction: documentContextFor rejects unresolvable chunks. */
        val chunk: ChunkNode
    )

    /** Hoists the anonymous-visitor no-op visitAttributeIdentifier boilerplate into one base class. */
    private open class UnitVisitor : ASTVisitor<Unit> {
        override fun visitAttributeIdentifier(
            identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier,
            value: Unit
        ) = Unit
    }

    /**
     * Shared request preamble for document-scoped features: resolve the virtual path,
     * pick the open-overlay source (falling back to the disk index), take the analyzed
     * snapshot chunk when present, else parse the source leniently. [requireSource]
     * mirrors the two caller shapes: false (folding/inlay) parses no source for unknown
     * documents; true (selection ranges / call hierarchy) degrades immediately.
     */
    private fun documentContextFor(
        document: TextDocumentIdentifier,
        requireSource: Boolean
    ): DocumentContext? {
        val path = pathOf(document)
        val source = sourceFor(path)
        if (source == null || (requireSource && source.isEmpty())) {
            return null
        }
        val semanticFile = snapshot.files[path]?.semanticFile
        val chunk = semanticFile?.chunk ?: source.takeIf { it.isNotEmpty() }?.let { parseChunkForFolding(it) }
        if (chunk == null) {
            return null
        }
        return DocumentContext(path, source, semanticFile, chunk)
    }

    /**
     * Unlinked nodes throw on the [BaseASTNode.parent] access itself, so every AST-parent
     * walk in this file soft-degrades through this accessor.
     */
    private fun BaseASTNode.parentOrNull(): BaseASTNode? =
        runCatching { parent }.getOrNull()

    private fun isOrderedAstRange(range: Range): Boolean =
        range.start.line >= 1 && range.start.column >= 1 && range.end.line >= 1 && range.end.column >= 1 &&
            (range.end.line > range.start.line || (range.end.line == range.start.line && range.end.column >= range.start.column))

    /**
     * Shared document / range formatting pipeline (TASK-544).
     * [range] null → full document. Invalid ranges (inverted, OOB, negative) → empty.
     * Returns at most one full-span replacement TextEdit when the formatted text differs.
     */
    private fun formatDocument(
        document: TextDocumentIdentifier?,
        options: FormattingOptions?,
        range: org.eclipse.lsp4j.Range?
    ): List<TextEdit> {
        if (document == null || document.uri.isNullOrBlank()) {
            return emptyList()
        }
        val path = pathOf(document)
        val source = sourceFor(path) ?: return emptyList()
        if (source.isEmpty()) {
            // Empty buffer: only a no-op is safe (tests reject non-origin ranges).
            return emptyList()
        }

        val tabSize = ((options?.tabSize ?: 4).takeIf { it > 0 } ?: 4).coerceIn(1, 16)
        val insertSpaces = options?.isInsertSpaces ?: true

        if (range == null) {
            // Whole-document formatting operates on exactly the buffer the workspace analyzed,
            // so reuse that parse instead of running a second one per format request.
            val analyzed = snapshot.files[path]?.semanticFile?.takeIf { it.source == source }
            val formatted = formatSourceText(source, tabSize, insertSpaces, analyzed) ?: return emptyList()
            if (formatted == source) {
                return emptyList()
            }
            return listOf(TextEdit(fullDocumentLspRange(source), formatted))
        }

        // Validate / clamp requested range; invalid → empty edits (safety contract).
        val clamped = validateAndClampFormatRange(range, source) ?: return emptyList()
        val startOffset = lspOffsetAt(source, clamped.start)
        val endOffset = lspOffsetAt(source, clamped.end).coerceAtLeast(startOffset)
        if (startOffset == endOffset) {
            // Zero-width selection: no-op is always safe.
            return emptyList()
        }
        val selected = source.substring(startOffset, endOffset)
        // Only a complete, parseable selection can produce a syntax-based replacement.
        val selectedFormatted = formatSourceText(selected, tabSize, insertSpaces)
            ?: return emptyList()
        if (selectedFormatted == selected) {
            return emptyList()
        }
        return listOf(TextEdit(clamped, selectedFormatted))
    }

    /**
     * @param analyzed the workspace's own parse of exactly this text, when available. Range
     *   formatting passes a substring that has no snapshot entry, so it parses here instead.
     *
     * Guard rail: the AST2Lua surface is re-parsed before it is returned. A printer defect
     * that drops or invents tokens would otherwise reach the editor as a full-document
     * replacement that corrupts the buffer; any recovery diagnostic (or throw) returns
     * null and the original source is preserved.
     */
    private fun formatSourceText(
        source: String,
        tabSize: Int,
        insertSpaces: Boolean,
        analyzed: WorkspaceSemanticFile? = null
    ): String? {
        if (source.isEmpty()) {
            return source
        }
        // Prefer AST2Lua when the buffer parses without recovery diagnostics.
        try {
            val (chunk, recoveryDiagnostics) = analyzed?.let { it.chunk to it.recoveryDiagnostics }
                ?: LuaParser().parseWithDiagnostics(source).let { it.chunk to it.recoveryDiagnostics }
            if (recoveryDiagnostics.isEmpty()) {
                val printer = AST2Lua().apply {
                    indentSize = if (insertSpaces) tabSize else tabSize.coerceAtLeast(1)
                }
                var printed = printer.asCode(chunk)
                // AST2Lua often starts with a leading newline from visitBlock/statement.
                printed = printed.trimStart('\n', '\r')
                if (!insertSpaces) {
                    printed = convertLeadingSpacesToTabs(printed, tabSize)
                }
                // Preserve a trailing newline if the source had one.
                if (source.endsWith("\n") && !printed.endsWith("\n")) printed += "\n"
                else if (!source.endsWith("\n") && printed.endsWith("\n")) printed = printed.trimEnd('\n', '\r')
                // CRLF source → emit CRLF so editors don't thrash line endings.
                if (source.contains("\r\n")) {
                    printed = printed.replace("\r\n", "\n").replace("\n", "\r\n")
                }
                // The exact replacement text must re-parse cleanly (the KDoc guard rail);
                // any diagnostic (or throw) means no replacement is published.
                val printedReparsesCleanly = runCatching {
                    LuaParser().parseWithDiagnostics(printed).recoveryDiagnostics.isEmpty()
                }.getOrDefault(false)
                if (printedReparsesCleanly) {
                    return printed
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }

    private fun convertLeadingSpacesToTabs(text: String, tabSize: Int): String {
        val size = tabSize.coerceAtLeast(1)
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return lines.joinToString(nl) { line ->
            val spaces = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
            if (spaces == 0) line
            else "\t".repeat(spaces / size) + " ".repeat(spaces % size) + line.substring(spaces)
        }
    }

    /** Whole-document replacement span: end position is (lastLineIndex, lastLineLength). */
    private fun fullDocumentLspRange(source: String): org.eclipse.lsp4j.Range {
        if (source.isEmpty()) {
            return org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(0, 0), org.eclipse.lsp4j.Position(0, 0))
        }
        val lines = source.lineSequence().toList()
        return org.eclipse.lsp4j.Range(
            org.eclipse.lsp4j.Position(0, 0),
            org.eclipse.lsp4j.Position(lines.lastIndex, lines.last().length)
        )
    }

    /**
     * Returns null when the range is invalid for formatting (inverted, negative,
     * clearly beyond EOF). Zero-width ranges at valid positions are allowed (caller
     * may still choose empty edits).
     */
    private fun validateAndClampFormatRange(
        range: org.eclipse.lsp4j.Range,
        source: String
    ): org.eclipse.lsp4j.Range? {
        val start = range.start ?: return null
        val end = range.end ?: return null
        if (start.line < 0 || start.character < 0 || end.line < 0 || end.character < 0) return null
        // Inverted: end before start.
        if (end.line < start.line || (end.line == start.line && end.character < start.character)) {
            return null
        }
        // documentLineCount is 0 only for an empty source; formatDocument rejects
        // empty buffers before validating, so an all-zero range short-circuit is
        // unnecessary here. Clamp the validated range to document bounds (negatives
        // were rejected above, so the lower bounds of the coercions hold); return
        // null when clamping inverts the span.
        val lineCount = documentLineCount(source)
        // Beyond EOF (start past last line) → empty.
        if (start.line >= lineCount) {
            return null
        }
        val endLine = end.line.coerceAtMost(lineCount) // exclusive EOF line allowed
        val startClamped = org.eclipse.lsp4j.Position(
            start.line,
            start.character.coerceAtMost(lineLengthAt(source, start.line))
        )
        val endClamped = if (endLine >= lineCount) org.eclipse.lsp4j.Position(lineCount, 0)
        else org.eclipse.lsp4j.Position(endLine, end.character.coerceAtMost(lineLengthAt(source, endLine)))
        if (endClamped.line < startClamped.line ||
            (endClamped.line == startClamped.line && endClamped.character < startClamped.character)
        ) {
            return null
        }
        return org.eclipse.lsp4j.Range(startClamped, endClamped)
    }

    private fun lineLengthAt(source: String, line: Int): Int {
        // Splits on '\n' only (matching lspOffsetAt's line model): a lone '\r' stays in the
        // line, with a trailing CR before the newline excluded on CRLF sources.
        if (source.isEmpty() || line < 0) {
            return 0
        }
        var lineStart = 0
        repeat(line) {
            val nl = source.indexOf('\n', lineStart)
            if (nl < 0) return 0
            lineStart = nl + 1
        }
        val nl = source.indexOf('\n', lineStart)
        val end = if (nl < 0) source.length else nl
        return (if (end > lineStart && source[end - 1] == '\r') end - 1 else end) - lineStart
    }

    private fun buildHoverContent(name: String?, detail: String?, typeDisplayName: String?): String? {
        val parts = buildList {
            name?.let { add("**$it**") }
            detail?.takeIf { it.isNotBlank() && it != typeDisplayName }?.let(::add)
            typeDisplayName?.takeIf { it.isNotBlank() }?.let {
                add(
                    if ('\n' in it) "Type:\n```lua\n$it\n```"
                    else "Type: `$it`"
                )
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * Prefer non-weak FunctionType/ClassType/MODULE displays for LSP hover markup.
     * Bare unknown/any loses to any richer candidate (TASK-601).
     *
     * [strong] holds present, non-unknown/any candidates; the ladders run a fixed
     * preference order over it — module-prefixed, callable, Array-prefixed,
     * non-quoted-literal, then any strong candidate — falling back to the first
     * present (possibly weak) candidate only when every candidate is weak.
     */
    private fun preferredLspHoverTypeDisplay(
        primary: String?,
        secondary: String?,
        tertiary: String?
    ): String? {
        val present = sequenceOf(primary, secondary, tertiary)
            .filterNotNull().filter { it.isNotBlank() }.toList()
        // On an already-non-blank candidate, weak reduces to unknown/any. A quoted
        // literal ("...") is any candidate of length >= 2 wrapped in double quotes.
        fun isQuotedLiteral(value: String) =
            value.length >= 2 && value.startsWith('"') && value.endsWith('"')
        val strong = present.filter { it != "unknown" && it != "any" }
        strong.firstOrNull { it.startsWith("module ") }?.let { return it }
        strong.firstOrNull { it.contains("fun(") || it.contains("fun<") }?.let { return it }
        strong.firstOrNull { it.startsWith("Array<") }?.let { return it }
        strong.firstOrNull { !isQuotedLiteral(it) }?.let { return it }
        return strong.firstOrNull() ?: present.firstOrNull()
    }

    // -------------------------------------------------------------------------
    // TASK-520 — Call hierarchy (same-file local functions)
    // -------------------------------------------------------------------------

    private fun resolveCallHierarchyItem(
        document: TextDocumentIdentifier?,
        lspPosition: org.eclipse.lsp4j.Position?
    ): CallHierarchyItem? {
        if (document == null || lspPosition == null || lspPosition.line < 0 || lspPosition.character < 0) {
            return null
        }
        val context = documentContextFor(document, requireSource = true) ?: return null
        val path = context.path
        val uri = document.uri ?: uriFor(path)

        // Prefer AST identifier covering the caret.
        val identifier = callHierarchyIdentifierAt(context.semanticFile, context.chunk, lspPosition.toParserPosition())
            ?: return null
        // isValidLuaIdentifier already rejects reserved words (LUA_KEYWORDS).
        if (!isValidLuaIdentifier(identifier.name)) {
            return null
        }

        val functionDecl = resolveLocalFunctionDeclaration(context.chunk, context.semanticFile, path, identifier)
            ?: return null
        return callHierarchyItemForFunction(functionDecl, uri)
    }

    private fun callHierarchyIdentifierAt(
        semanticFile: WorkspaceSemanticFile?,
        chunk: ChunkNode,
        position: Position
    ): Identifier? {
        identifierAt(semanticFile, position)?.let { return it }

        // Fallback: walk chunk identifiers via NodePositionIndex when semantic file is thin.
        val index = positionIndexFor(chunk)
        return when (val node = index.findInnermost(position)) {
            is Identifier -> node
            is MemberExpression -> node.identifier.takeIf {
                rangeContainsHighlight(it.range, position)
            }
            else -> null
        }
    }

    /**
     * Direct lexical resolution of [identifier]'s local function binding:
     * the name of a local FunctionDeclaration, or an anonymous function bound
     * by `local add = function(...)` (LocalStatement.init = names,
     * LocalStatement.variables = RHS — AST quirk). Shared by the caret path
     * and the definition-location path of [resolveLocalFunctionDeclaration].
     */
    private fun localFunctionDeclarationOf(identifier: Identifier): FunctionDeclaration? {
        val parent = identifier.parentOrNull() ?: return null
        if (parent is FunctionDeclaration &&
            parent.isLocal &&
            functionDeclarationNameIs(parent.identifier, identifier)
        ) {
            return parent
        }
        if (parent is LocalStatement) {
            val index = parent.init.indexOfFirst { it === identifier }
            val rhs = parent.variables.getOrNull(index)
            if (rhs is FunctionDeclaration) {
                return rhs
            }
        }
        return null
    }

    /** [identifierAtHighlight] behind a null-safe semantic file (shared by call-graph walkers). */
    private fun identifierAtHighlightOrNull(
        semanticFile: WorkspaceSemanticFile?,
        range: Range
    ): Identifier? = semanticFile?.let { identifierAtHighlight(it, range) }

    /**
     * Resolve the local [FunctionDeclaration] for [identifier] (definition name or
     * call-site name bound to a local function / local assigned function expression).
     */
    private fun resolveLocalFunctionDeclaration(
        chunk: ChunkNode,
        semanticFile: WorkspaceSemanticFile?,
        path: VirtualPath,
        identifier: Identifier
    ): FunctionDeclaration? {
        // Direct: identifier is the name of a local FunctionDeclaration.
        localFunctionDeclarationOf(identifier)?.let { return it }

        // Call-site / use: resolve via definition/goto to the declaration, then map to AST.
        val defLocations = try {
            val defs = queries.gotoDefinition(path, identifier.range.start)
            if (defs.isNotEmpty()) defs else queries.declaration(path, identifier.range.start)
        } catch (_: Exception) {
            emptyList()
        }.filter { it.path == path }

        for (location in defLocations) {
            val declId = identifierAtHighlightOrNull(semanticFile, location.range)
                ?: findIdentifierAtRange(chunk, location.range) ?: continue
            localFunctionDeclarationOf(declId)?.let { return it }
        }

        // No lexical binding means no call-graph edge, even when one declaration
        // elsewhere in the file happens to have the same spelling.
        return null
    }

    private fun findIdentifierAtRange(chunk: ChunkNode, range: Range): Identifier? {
        val index = positionIndexFor(chunk)
        return when (val node = index.findInnermost(range.start)) {
            is Identifier -> node
            is MemberExpression -> node.identifier
            else -> {
                // Walk all identifiers via visitor.
                var found: Identifier? = null
                object : UnitVisitor() {
                    override fun visitIdentifier(node: Identifier, value: Unit) {
                        if (found == null && node.range.start == range.start) found = node
                        super.visitIdentifier(node, value)
                    }
                }.visitChunkNode(chunk, Unit)
                found
            }
        }
    }

    private fun collectLocalFunctionDeclarations(chunk: ChunkNode): List<FunctionDeclaration> {
        val out = mutableListOf<FunctionDeclaration>()
        object : UnitVisitor() {
            override fun visitFunctionDeclaration(node: FunctionDeclaration, value: Unit) {
                if (isLocalFunctionDeclaration(node)) out += node
                super.visitFunctionDeclaration(node, value)
            }
        }.visitChunkNode(chunk, Unit)
        return out
    }

    /**
     * A local function is either a `local function` declaration with a resolvable name
     * (bare or member NAME) or an anonymous function expression bound to a local name
     * (`local f = function(...)` — AST quirk: init = names, variables = RHS; the
     * LocalStatement branch of [localFunctionNameIdentifier] only resolves when that
     * RHS slot pairing exists, which is exactly the second historical arm).
     */
    private fun isLocalFunctionDeclaration(node: FunctionDeclaration): Boolean =
        localFunctionNameIdentifier(node) != null &&
            (node.isLocal || node.parentOrNull() is LocalStatement)

    private fun localFunctionNameIdentifier(node: FunctionDeclaration): Identifier? {
        when (val name = node.identifier) {
            is Identifier -> return name
            is MemberExpression -> return name.identifier
        }
        // local name = function(...) — name lives on LocalStatement.init (names),
        // FunctionDeclaration is in LocalStatement.variables (RHS).
        val parent = node.parentOrNull() as? LocalStatement ?: return null
        val idx = parent.variables.indexOfFirst { it === node }
        return parent.init.getOrNull(idx) as? Identifier
    }

    private fun callHierarchyItemForFunction(
        functionDecl: FunctionDeclaration,
        uri: String
    ): CallHierarchyItem? {
        val nameId = localFunctionNameIdentifier(functionDecl) ?: return null
        val name = nameId.name
        if (name.isBlank()) {
            return null
        }
        val selection = nameId.range.toLspRange()
        val full = functionDecl.range.toLspRange()
        // Ensure selection is inside range; if function range is odd, fall back to selection.
        val range = if (containsRangeLsp(full, selection)) full else selection
        val kind = if (functionDecl.identifier is MemberExpression) SymbolKind.Method else SymbolKind.Function
        return CallHierarchyItem(name, kind, uri, range, selection)
    }

    private fun containsRangeLsp(
        outer: org.eclipse.lsp4j.Range,
        inner: org.eclipse.lsp4j.Range
    ): Boolean = lspPositionInRange(inner.start, outer) && lspPositionInRange(inner.end, outer)

    private fun rangesOverlapParser(left: Range, right: Range): Boolean =
        // Standard interval overlap over 1-based (line, column) positions.
        (left.start.line < right.end.line ||
            (left.start.line == right.end.line && left.start.column <= right.end.column)) &&
            (right.start.line < left.end.line ||
                (right.start.line == left.end.line && right.start.column <= left.end.column))

    /**
     * Shared call-hierarchy preamble for [collectIncomingCalls] / [collectOutgoingCalls]:
     * resolve the item's document context and its same-file local-function target.
     * Returns the context (path/semanticFile/chunk), the item URI, and the target declaration.
     */
    private fun callHierarchyTarget(
        item: CallHierarchyItem
    ): Triple<DocumentContext, String, FunctionDeclaration>? {
        val uri = item.uri ?: return null
        val context = documentContextFor(TextDocumentIdentifier(uri), requireSource = true) ?: return null
        val target = resolveLocalFunctionFromItem(context.chunk, context.semanticFile, context.path, item)
            ?: return null
        return Triple(context, uri, target)
    }

    private fun collectIncomingCalls(item: CallHierarchyItem): List<CallHierarchyIncomingCall> {
        val (context, uri, targetDecl) = callHierarchyTarget(item) ?: return emptyList()
        val path = context.path
        val semanticFile = context.semanticFile
        val chunk = context.chunk
        val targetName = localFunctionNameIdentifier(targetDecl) ?: return emptyList()

        // Use sites from references (exclude declaration) + document highlights as fallback.
        val useSites = sameFileReferences(path, targetName.range.start)
        val highlightSites = if (useSites.isEmpty()) sameFileHighlights(path, targetName.range.start) else emptyList()

        // WorkspaceLocation / parser Range are data classes, so range equality is the
        // same dedup key as the previous start/end line+column string.
        val sites = (useSites + highlightSites).distinctBy { it.range }

        // Group call sites by enclosing local function caller (ranges deduped per caller).
        val byCaller = linkedMapOf<FunctionDeclaration, MutableList<org.eclipse.lsp4j.Range>>()

        for (site in sites) {
            // Skip the declaration name itself (same start position as the target name).
            if (site.range.start == targetName.range.start &&
                rangesOverlapParser(site.range, targetName.range)
            ) {
                continue
            }
            val siteId = identifierAtHighlightOrNull(semanticFile, site.range)
                ?: findIdentifierAtRange(chunk, site.range)
                ?: continue
            val caller = enclosingLocalFunctionAllowSelf(siteId) ?: continue
            byCaller.getOrPut(caller) { mutableListOf() }
                .addCallRangeIfDistinct(site.range.toLspRange())
        }

        return byCaller.mapNotNull { (caller, fromRanges) ->
            val fromItem = callHierarchyItemForFunction(caller, uri) ?: return@mapNotNull null
            CallHierarchyIncomingCall(fromItem, fromRanges)
        }
    }

    private fun collectOutgoingCalls(item: CallHierarchyItem): List<CallHierarchyOutgoingCall> {
        val (context, uri, rootDecl) = callHierarchyTarget(item) ?: return emptyList()
        val path = context.path
        val semanticFile = context.semanticFile
        val chunk = context.chunk
        val body = rootDecl.body ?: return emptyList()

        // Callee → call-site ranges (deduped per callee), preserving first-seen order.
        val byCallee = linkedMapOf<FunctionDeclaration, MutableList<org.eclipse.lsp4j.Range>>()

        val visitor = object : UnitVisitor() {
            override fun visitCallExpression(node: CallExpression, value: Unit) {
                val baseName = when (val base = node.base) {
                    is Identifier -> base
                    is MemberExpression -> base.identifier
                    else -> null
                }
                if (baseName != null) {
                    val callee = resolveLocalFunctionDeclaration(chunk, semanticFile, path, baseName)
                    if (callee != null) {
                        // Prefer calls whose nearest enclosing local function is rootDecl.
                        val enclosing = enclosingLocalFunctionAllowSelf(node)
                        if (enclosing === rootDecl) {
                            byCallee.getOrPut(callee) { mutableListOf() }
                                .addCallRangeIfDistinct(baseName.range.toLspRange())
                        }
                    }
                }
                super.visitCallExpression(node, value)
            }
        }
        // Visit only the root function body to avoid other top-level calls.
        body.accept(visitor, Unit)

        return byCallee.mapNotNull { (callee, fromRanges) ->
            val toItem = callHierarchyItemForFunction(callee, uri) ?: return@mapNotNull null
            CallHierarchyOutgoingCall(toItem, fromRanges)
        }
    }

    private fun resolveLocalFunctionFromItem(
        chunk: ChunkNode,
        semanticFile: WorkspaceSemanticFile?,
        path: VirtualPath,
        item: CallHierarchyItem
    ): FunctionDeclaration? {
        val selection = item.selectionRange ?: item.range ?: return null
        val parserPos = selection.start.toParserPosition()
        val id = callHierarchyIdentifierAt(semanticFile, chunk, parserPos)
        if (id != null) {
            resolveLocalFunctionDeclaration(chunk, semanticFile, path, id)?.let { return it }
        }
        // Match by name + selection range against collected local functions.
        val name = item.name ?: return null
        return collectLocalFunctionDeclarations(chunk)
            .filter { localFunctionNameIdentifier(it)?.name == name }
            .firstOrNull { decl ->
                val nameId = localFunctionNameIdentifier(decl) ?: return@firstOrNull false
                val sel = nameId.range.toLspRange()
                sel == selection || containsRangeLsp(selection, sel) || containsRangeLsp(sel, selection)
            }
    }

    private fun enclosingLocalFunctionAllowSelf(node: BaseASTNode): FunctionDeclaration? {
        var current: BaseASTNode? = node
        repeat(256) {
            if (current is FunctionDeclaration && isLocalFunctionDeclaration(current)) {
                return current
            }
            current = current?.parentOrNull() ?: return null
        }
        return null
    }

    /**
     * Shared per-caller/callee range sink for the same-file call graph
     * ([collectIncomingCalls] / [collectOutgoingCalls]): appends [range] unless an
     * identical LSP range is already recorded (org.eclipse.lsp4j.Range has structural
     * equals), preserving first-seen insertion order.
     */
    private fun MutableList<org.eclipse.lsp4j.Range>.addCallRangeIfDistinct(range: org.eclipse.lsp4j.Range) {
        if (none { it == range }) {
            add(range)
        }
    }

    /**
     * Collect Parameter-kind inlay hints for call arguments intersecting the request
     * range. Formal names come from SignatureHelp (same ranking / formals as caret
     * signature help) — no invented overload selection beyond that surface.
     */
    private fun collectParameterInlayHints(params: InlayHintParams): List<InlayHint> {
        val context = documentContextFor(params.textDocument, requireSource = false)
            ?: return emptyList()
        val path = context.path
        val chunk = context.chunk

        val requestRange = params.range
        val calls = mutableListOf<CallExpression>()
        // One hook covers every call shape: the ASTVisitor dispatches String/Table call
        // sugar through visitCallExpression (which routes the subclasses to their own
        // walkers), so each call node is collected exactly once here. Null request
        // range (full-document hint request) accepts every call.
        val visitor = object : UnitVisitor() {
            override fun visitCallExpression(node: CallExpression, value: Unit) {
                if (requestRange == null || lspRangesIntersect(node.range.toLspRange(), requestRange)) {
                    calls += node
                }
                super.visitCallExpression(node, value)
            }
        }
        visitor.visitChunkNode(chunk, Unit)

        return calls.flatMap { call -> parameterInlayHintsForCall(path, call, requestRange) }
    }

    private fun parameterInlayHintsForCall(
        path: VirtualPath,
        call: CallExpression,
        requestRange: org.eclipse.lsp4j.Range?
    ): List<InlayHint> {
        // String-call sugar (`f"str"`) carries its argument on the base expression;
        // regular call arguments follow it.
        val arguments = (call.base as? StringCallExpression)?.arguments.orEmpty() + call.arguments
        if (arguments.isEmpty()) {
            return emptyList()
        }

        // Probe signature help at the first argument start so we reuse the same
        // formal metadata / activeSignature ranking as SignatureHelpProvider.
        val help = queries.signatureHelp(path, arguments.first().range.start) ?: return emptyList()
        val signature = help.signatures.getOrNull(help.activeSignature.coerceAtLeast(0))
            ?: help.signatures.firstOrNull()
            ?: return emptyList()
        val formalNames = signature.parameters.mapNotNull { formalNameFromParameterLabel(it.label) }
        if (formalNames.isEmpty()) {
            return emptyList()
        }

        // Colon method calls: signature formals may include leading `self` while the
        // call site only has explicit args (value, label, ...). Skip the implicit self.
        val formalOffset = if ((call.base as? MemberExpression)?.indexer == ":" &&
            formalNames.firstOrNull() == "self"
        ) 1 else 0

        val hints = mutableListOf<InlayHint>()
        arguments.forEachIndexed { index, argument ->
            val name = formalNames.getOrNull(formalOffset + index) ?: return@forEachIndexed
            if (name.isBlank() || name == "...") {
                return@forEachIndexed
            }
            val position = argument.range.start.toLspPosition()
            if (position.line < 0 || position.character < 0 ||
                (requestRange != null && !lspPositionInRange(position, requestRange))
            ) {
                return@forEachIndexed
            }
            hints += InlayHint(position, Either.forLeft("$name:")).apply {
                kind = InlayHintKind.Parameter
                paddingRight = true
            }
        }
        return hints
    }

    /**
     * SignatureHelp parameter labels look like `value: number`, `label?: string`,
     * or bare type-only labels (`number`). Extract a formal name when present.
     */
    private fun formalNameFromParameterLabel(label: String): String? {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        // `name: type` / `name?: type` / `...: type` (skip pure vararg marker);
        // otherwise a bare identifier formal (no type annotation in the label).
        val colon = trimmed.indexOf(':')
        if (colon > 0) {
            return trimmed.substring(0, colon).trim().trimEnd('?')
                .takeIf { it.isNotEmpty() && it != "..." }
        }
        return trimmed.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
    }

    private fun lspRangesIntersect(
        left: org.eclipse.lsp4j.Range,
        right: org.eclipse.lsp4j.Range
    ): Boolean =
        // Empty / inverted ranges: still accept if either endpoint is inside the other.
        lspPositionInRange(left.start, right) || lspPositionInRange(left.end, right) ||
            lspPositionInRange(right.start, left) || lspPositionInRange(right.end, left)

    private fun lspPositionInRange(
        position: org.eclipse.lsp4j.Position,
        range: org.eclipse.lsp4j.Range
    ): Boolean {
        val start = range.start
        val end = range.end
        return (position.line > start.line ||
            (position.line == start.line && position.character >= start.character)) &&
            (position.line < end.line ||
                (position.line == end.line && position.character <= end.character))
    }
}

/** org.eclipse.lsp4j.DiagnosticTag.Deprecated. */
private const val DIAGNOSTIC_TAG_DEPRECATED = 2

// TASK-541 legend indices — keep stable; clients map by name from the legend list.
// TYPE_* constants below index into this list (Keyword=0 … Comment=6).
private val SEMANTIC_TOKENS_LEGEND = SemanticTokensLegend(
    listOf(
        SemanticTokenTypes.Keyword, SemanticTokenTypes.Function, SemanticTokenTypes.Variable,
        SemanticTokenTypes.Parameter, SemanticTokenTypes.String, SemanticTokenTypes.Number,
        SemanticTokenTypes.Comment
    ),
    listOf(SemanticTokenModifiers.Declaration, SemanticTokenModifiers.Readonly)
)

private const val TYPE_KEYWORD = 0
private const val TYPE_FUNCTION = 1
private const val TYPE_VARIABLE = 2
private const val TYPE_PARAMETER = 3
private const val TYPE_STRING = 4
private const val TYPE_NUMBER = 5
private const val TYPE_COMMENT = 6

/** Lua reserved words — never accepted as prepareRename identifier placeholders. */
private val LUA_KEYWORDS: Set<String> = setOf(
    "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
    "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return",
    "then", "true", "until", "while",
    // Project extensions seen in lexer keyword set:
    "case", "continue", "default", "lambda", "switch", "when"
)

private fun org.eclipse.lsp4j.Position.toParserPosition(): Position = Position(line + 1, character + 1)

private fun Range.toLspRange(): org.eclipse.lsp4j.Range =
    org.eclipse.lsp4j.Range(start.toLspPosition(), end.toLspPosition())

private fun Position.toLspPosition(): org.eclipse.lsp4j.Position = org.eclipse.lsp4j.Position(line - 1, column - 1)

private fun DiagnosticSeverity.toLspSeverity(): org.eclipse.lsp4j.DiagnosticSeverity = when (this) {
    DiagnosticSeverity.ERROR -> org.eclipse.lsp4j.DiagnosticSeverity.Error
    DiagnosticSeverity.WARNING -> org.eclipse.lsp4j.DiagnosticSeverity.Warning
    DiagnosticSeverity.INFO -> org.eclipse.lsp4j.DiagnosticSeverity.Information
}

/**
 * api Diagnostic.tags (raw LSP DiagnosticTag values as Ints, so the common api stays
 * lsp4j-free) → lsp4j DiagnosticTag. Unknown values are dropped, not crash the publish pass.
 */
private fun Int.toLspDiagnosticTag(): org.eclipse.lsp4j.DiagnosticTag? = when (this) {
    DIAGNOSTIC_TAG_UNNECESSARY -> org.eclipse.lsp4j.DiagnosticTag.Unnecessary
    DIAGNOSTIC_TAG_DEPRECATED -> org.eclipse.lsp4j.DiagnosticTag.Deprecated
    else -> null
}

/** Name-derived bridge; only the two non-identity constants need explicit arms. */
private fun CompletionItemKind.toLspKind(): org.eclipse.lsp4j.CompletionItemKind = when (this) {
    CompletionItemKind.PARAMETER -> org.eclipse.lsp4j.CompletionItemKind.Variable
    CompletionItemKind.TYPE_ALIAS -> org.eclipse.lsp4j.CompletionItemKind.TypeParameter
    else -> org.eclipse.lsp4j.CompletionItemKind.valueOf(
        name.lowercase().replaceFirstChar(Char::uppercaseChar)
    )
}

internal fun lspVirtualPathFromUri(
    uri: String,
    workspaceFolderUriPrefixes: Map<String, String> = emptyMap(),
    collapseSyntheticWorkspaceRoot: Boolean = false
): VirtualPath {
    val parsedPath = normalizeLspFileUriPath(uri)
    val workspacePath = parsedPath?.workspaceRelativePath(workspaceFolderUriPrefixes)
        ?.syntheticWorkspaceRelativePath(collapseSyntheticWorkspaceRoot)
    return workspacePath?.toVirtualPathOrNull() ?: encodedUriPath(uri)
}

/**
 * Cross-platform LSP URI → path-string policy (shared by rootUri, workspace folders,
 * document mapping, and watched-file handling):
 *
 * - `file:` URIs yield a filesystem path string:
 *   - Unix absolute paths keep their leading slash (`/home/...`).
 *   - Windows drive paths are returned as `C:/...` (only the slash before the drive is stripped).
 *   - Percent-encoded segments are decoded (via [URI]) so path text round-trips safely with [lspFileUri].
 * - Scheme-less values are treated as raw filesystem/path text.
 * - Non-file / custom schemes return `null` so callers map them to a synthetic virtual path
 *   while preserving the original document URI separately (never force `file:///...`).
 */
internal fun normalizeLspFileUriPath(uri: String): String? {
    // java.net.URI rejects unencoded spaces and other reserved characters with
    // URISyntaxException (a checked Exception, not IllegalArgumentException). Editors
    // and tests may still hand file:///.../My Screen.lua URIs; fall back to manual
    // path extraction so didOpen/didChange/diagnostics never throw on the URI alone.
    val parsed = try {
        URI(uri)
    } catch (_: Exception) {
        return fallbackFileUriPath(uri)
    }

    return when {
        parsed.scheme.equals("file", ignoreCase = true) -> parsed.fileWorkspacePath(uri)
        parsed.scheme.isNullOrBlank() -> {
            val raw = parsed.path?.takeIf { it.isNotBlank() } ?: uri
            normalizeFileSystemPathFromUriPath(raw.replace('\\', '/'))
        }
        else -> null
    }
}

/** Strips a leading `//authority` (including the extra `//` of `file:///path`), keeping the path part. */
private fun String.uriAuthoritySuffix(): String {
    if (!startsWith("//")) return this
    val authorityAndPath = substring(2)
    val slash = authorityAndPath.indexOf('/')
    return if (slash >= 0) authorityAndPath.substring(slash) else authorityAndPath
}

private fun fallbackFileUriPath(uri: String): String? {
    val trimmed = uri.trim()
    if (!trimmed.regionMatches(0, "file:", 0, 5, ignoreCase = true)) {
        return trimmed.takeIf { it.isNotBlank() }
    }
    // file:///path, file://localhost/path, file:/path → ensure a leading '/', then strip authority.
    var rest = trimmed.substring(5)
    if (!rest.startsWith("/")) rest = "/$rest"
    return normalizeFileSystemPathFromUriPath(rest.uriAuthoritySuffix().replace('\\', '/'))
}

private fun URI.fileWorkspacePath(originalUri: String): String {
    val rawPath = when {
        !path.isNullOrBlank() -> path
        !schemeSpecificPart.isNullOrBlank() -> schemeSpecificPart.uriAuthoritySuffix()
        else -> return fallbackFileUriPath(originalUri) ?: originalUri
    }
    return normalizeFileSystemPathFromUriPath(rawPath.replace('\\', '/'))
}

/**
 * Normalize a URI path component into a cross-platform filesystem path string.
 * Keeps Unix absolute leading `/`; strips only the extra slash before a Windows drive.
 */
internal fun normalizeFileSystemPathFromUriPath(uriPath: String): String {
    if (uriPath.isEmpty()) {
        return uriPath
    }
    // Windows drive encoded as "/C:/..." or "/c|/..." from file URIs, or already "C:/...".
    val driveIndex = when {
        uriPath.length >= 3 && uriPath[0] == '/' && uriPath[1].isLetter() &&
            (uriPath[2] == ':' || uriPath[2] == '|') -> 1
        uriPath.length >= 2 && uriPath[0].isLetter() && (uriPath[1] == ':' || uriPath[1] == '|') -> 0
        // Unix absolute and all other forms keep their shape (including leading '/').
        else -> return uriPath
    }
    val rest = uriPath.substring(driveIndex + 2)
    return buildString {
        append(uriPath[driveIndex])
        append(':')
        if (driveIndex == 1 && rest.isNotEmpty() && !rest.startsWith('/')) {
            append('/')
        }
        append(rest.replace('|', ':'))
    }
}

private fun String.toVirtualPathOrNull(): VirtualPath? =
    takeIf { it.isNotBlank() }?.let { runCatching { VirtualPath.of(it) }.getOrNull() }

private fun String.workspaceRelativePath(workspaceFolderUriPrefixes: Map<String, String>): String {
    val normalized = normalizeWorkspacePathPrefix()
    val match = workspaceFolderUriPrefixes.keys
        .filter { prefix ->
            val key = prefix.normalizeWorkspacePathPrefix()
            normalized == key || normalized.startsWith("$key/")
        }
        .maxByOrNull { it.length }
        ?: return normalized
    return normalized.removePrefix(match.normalizeWorkspacePathPrefix()).removePrefix("/").ifBlank { normalized }
}

private fun String.syntheticWorkspaceRelativePath(enabled: Boolean): String {
    if (!enabled) return this
    val normalized = replace('\\', '/')
    val rel = normalized.removePrefix("/")
    return when {
        normalized == "workspace" || normalized == "/workspace" -> normalized
        rel.startsWith("workspace/") -> rel.removePrefix("workspace/").ifBlank { this }
        else -> this
    }
}

private fun String.normalizeWorkspacePathPrefix(): String {
    return replace('\\', '/').trimEnd('/')
}

private fun String.looksLikeUri(): Boolean {
    val separator = indexOf(':')
    if (separator <= 1) {
        return false
    }
    val scheme = substring(0, separator)
    return scheme.first().isLetter() && scheme.all { character ->
        character.isLetterOrDigit() || character == '+' || character == '-' || character == '.'
    }
}

private fun encodedUriPath(uri: String): VirtualPath {
    val encoded = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(uri.toByteArray(StandardCharsets.UTF_8))
    return VirtualPath.of("__lsp_uri__/$encoded.lua")
}

/**
 * Build a `file:` URI from a virtual path, percent-encoding path segments so that
 * paths containing spaces or other reserved characters round-trip with [normalizeLspFileUriPath].
 */
internal fun lspFileUri(path: VirtualPath): String {
    // Windows drive paths (C:/...) get the leading slash too, like every relative form.
    val normalized = path.value.replace('\\', '/')
    val uriPath = if (normalized.startsWith("/")) normalized else "/$normalized"
    return URI("file", "", uriPath, null).toASCIIString()
}

/** Name-derived bridge; only the four non-identity constants need explicit arms. */
private fun SemanticSymbolKind.toLspSymbolKind(): org.eclipse.lsp4j.SymbolKind = when (this) {
    SemanticSymbolKind.PARAMETER, SemanticSymbolKind.LOCAL -> org.eclipse.lsp4j.SymbolKind.Variable
    SemanticSymbolKind.TYPE_ALIAS -> org.eclipse.lsp4j.SymbolKind.TypeParameter
    SemanticSymbolKind.UNKNOWN -> org.eclipse.lsp4j.SymbolKind.Property
    else -> org.eclipse.lsp4j.SymbolKind.valueOf(
        name.lowercase().replaceFirstChar(Char::uppercaseChar)
    )
}

/** Bound for the per-document published diagnostic payload (adversarial audit wave N). */
private const val PUBLISH_DIAGNOSTICS_CAP = 300
