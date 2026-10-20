package io.github.dingyi222666.luaparser.lsp
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class LuaLanguageServer(
    private val languageService: LuaLanguageService = LuaLanguageService(JvmWorkspaceEngine()),
    private val processExit: (Int) -> Unit = {}
) : LanguageServer, LanguageClientAware {
    private enum class LifecycleState {
        CREATED,
        INITIALIZED,
        SHUTDOWN,
        EXITED
    }

    private val lifecycleLock = Any()
    @Volatile
    private var lifecycleState = LifecycleState.CREATED
    @Volatile
    private var client: LanguageClient? = null

    /**
     * Returned directly by [getTextDocumentService]. (An earlier pass wrapped this in a
     * `TextDocumentService by` delegation object; that wrapper was a pure transparent
     * proxy — lsp4j class delegation forwards the abstract didOpen/didChange/didClose/
     * didSave and every request override just re-forwarded to this instance, adding no
     * behavior. The UOE-throwing lsp4j default members are all overridden inside
     * [LuaTextDocumentService] itself, and lifecycle gating lives in its
     * acceptsNotifications/requestPolicy hooks.)
     */
    private val textDocuments = LuaTextDocumentService(
        languageService = languageService,
        publishDiagnostics = ::publishDiagnostics,
        acceptsNotifications = ::acceptsMessages,
        requestPolicy = ::textDocumentRequestPolicy
    )
    private val workspaceDelegate = LuaWorkspaceService(
        languageService = languageService,
        onConfigurationChanged = textDocuments::republishDiagnostics
    )
    private val workspace = object : WorkspaceService {
        override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
            if (!acceptsMessages()) {
                return
            }
            workspaceDelegate.didChangeConfiguration(params)
        }

        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
            if (acceptsMessages()) {
                workspaceDelegate.didChangeWatchedFiles(params)
            }
        }

        override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
            if (acceptsMessages()) {
                workspaceDelegate.didChangeWorkspaceFolders(params)
            }
        }

        override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<MutableList<out SymbolInformation>, MutableList<out WorkspaceSymbol>>> {
            // Lifecycle policy (workspace-symbol audit, wave M): CREATED mirrors the text
            // request QuietEmpty policy — a client probing workspace/symbol before
            // initialize settles gets an empty legacy symbol list instead of a hard
            // rejection. SHUTDOWN/EXIT stay rejected (hard lifecycle boundary).
            return when (lifecycleState) {
                LifecycleState.CREATED -> completedLegacyWorkspaceSymbols()
                LifecycleState.INITIALIZED -> workspaceDelegate.symbol(params)
                LifecycleState.SHUTDOWN, LifecycleState.EXITED ->
                    failedFuture(IllegalStateException("Lua language server is not accepting workspace requests"))
            }
        }

        /**
         * `workspaceSymbol/resolve` is NOT advertised (`WorkspaceSymbolOptions.resolveProvider`
         * stays unset — symbol locations are already inline). lsp4j's interface default would
         * throw UnsupportedOperationException if a client invoked it anyway; pre-empt that by
         * quietly returning the input symbol unchanged.
         */
        override fun resolveWorkspaceSymbol(symbol: WorkspaceSymbol?): CompletableFuture<WorkspaceSymbol?> {
            return CompletableFuture.completedFuture(symbol)
        }
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val clientSupportsWorkDoneProgress = params.capabilities
            ?.window
            ?.workDoneProgress == true
        System.err.println("LSP-DEVICE: client window.workDoneProgress=$clientSupportsWorkDoneProgress")
        System.err.println("LSP-DEVICE: initialize entered, state=$lifecycleState")
        return synchronized(lifecycleLock) {
            when (lifecycleState) {
                LifecycleState.CREATED -> {
                    try {
                        val t0 = System.currentTimeMillis()
                        val result = languageService.initialize(params)
                        System.err.println("LSP-DEVICE: languageService.initialize done in ${System.currentTimeMillis() - t0}ms")
                        lifecycleState = LifecycleState.INITIALIZED
                        CompletableFuture.completedFuture(result)
                    } catch (throwable: Throwable) {
                        failedFuture(throwable)
                    }
                }

                LifecycleState.INITIALIZED -> failedFuture(IllegalStateException("Lua language server is already initialized"))
                LifecycleState.SHUTDOWN -> failedFuture(IllegalStateException("Lua language server has already shut down"))
                LifecycleState.EXITED -> failedFuture(IllegalStateException("Lua language server has already exited"))
            }
        }
    }

    override fun initialized(@Suppress("UNUSED_PARAMETER") params: InitializedParams) {
    }

    override fun shutdown(): CompletableFuture<Any> {
        return synchronized(lifecycleLock) {
            when (lifecycleState) {
                LifecycleState.INITIALIZED -> {
                    lifecycleState = LifecycleState.SHUTDOWN
                    languageService.close()
                    CompletableFuture.completedFuture(0)
                }

                LifecycleState.SHUTDOWN -> CompletableFuture.completedFuture(0)
                LifecycleState.CREATED -> failedFuture(IllegalStateException("Lua language server cannot shut down before initialize"))
                LifecycleState.EXITED -> failedFuture(IllegalStateException("Lua language server has already exited"))
            }
        }
    }

    override fun exit() {
        val exitCode = synchronized(lifecycleLock) {
            if (lifecycleState == LifecycleState.EXITED) {
                return@synchronized null
            }
            val code = if (lifecycleState == LifecycleState.SHUTDOWN) 0 else 1
            lifecycleState = LifecycleState.EXITED
            languageService.close()
            client = null
            code
        }
        exitCode?.let(processExit)
    }

    override fun getTextDocumentService(): TextDocumentService = textDocuments

    override fun getWorkspaceService(): WorkspaceService = workspace

    /**
     * Host hook (in-process embedding): receives throttled workspace build
     * progress (phase + completed/total) so the app UI can show indexing status
     * directly — no client round-trip needed.
     */
    fun setOnBuildProgressListener(listener: ((Int, Int, String) -> Unit)?) {
        languageService.onBuildProgress = listener?.let { cb ->
            { progress: io.github.dingyi222666.luaparser.semantic.workspace.AnalysisProgress ->
                cb(progress.completedFiles, progress.totalFiles, progress.phase.name)
            }
        }
    }

    /** Test/embedding hook: wait for the background workspace build to settle. */
    internal fun flushBackgroundRebuild(timeoutMs: Long = 60_000): Boolean =
        languageService.awaitWorkspaceReady(timeoutMs)

    override fun connect(client: LanguageClient) {
        synchronized(lifecycleLock) {
            if (lifecycleState != LifecycleState.EXITED) {
                this.client = client
            }
        }
        // Background-build progress forwarding over $/progress was unwired on purpose
        // (see rel43-49: forwarding raced the emulator's GC and sora's client proxy
        // throws UnsupportedOperationException on notifyProgress — d59f696, 93ae821).
        // What must be preserved here: a host-set listener (setOnBuildProgressListener,
        // android sample) is never overwritten by a protocol connect.
        if (languageService.onBuildProgress != null) {
            return
        }
        // Install a no-op sink so the build path keeps taking the sink != null branch;
        // observably identical to null (the sink is never invoked with an effect).
        languageService.onBuildProgress = { _ -> }
    }

    private fun acceptsMessages(): Boolean {
        return lifecycleState == LifecycleState.INITIALIZED
    }

    private fun textDocumentRequestPolicy(): LspTextDocumentRequestPolicy {
        // CREATED stays quiet-empty (client probes before initialize settle); SHUTDOWN and
        // EXIT are hard lifecycle boundaries and reject identically. EXITED is reachable for
        // embedded usage (the default `processExit` is a no-op; only LuaLanguageServerLauncher
        // wires exitProcess), so post-exit requests must surface the same IllegalStateException
        // as post-shutdown ones instead of silently returning empty results.
        return when (lifecycleState) {
            LifecycleState.CREATED -> LspTextDocumentRequestPolicy.QuietEmpty
            LifecycleState.INITIALIZED -> LspTextDocumentRequestPolicy.Accept
            LifecycleState.SHUTDOWN -> LspTextDocumentRequestPolicy.Reject("Lua language server has already shut down")
            LifecycleState.EXITED -> LspTextDocumentRequestPolicy.Reject("Lua language server has already exited")
        }
    }

    /** Quiet-empty legacy workspace/symbol payload used by the CREATED-state policy. */
    private fun completedLegacyWorkspaceSymbols(): CompletableFuture<Either<MutableList<out SymbolInformation>, MutableList<out WorkspaceSymbol>>> {
        return CompletableFuture.completedFuture(
            Either.forLeft<MutableList<out SymbolInformation>, MutableList<out WorkspaceSymbol>>(
                mutableListOf<SymbolInformation>()
            )
        )
    }

    private fun publishDiagnostics(diagnostics: org.eclipse.lsp4j.PublishDiagnosticsParams) {
        if (acceptsMessages()) {
            client?.publishDiagnostics(diagnostics)
        }
    }

    private fun <T> failedFuture(throwable: Throwable): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        future.completeExceptionally(throwable)
        return future
    }
}
