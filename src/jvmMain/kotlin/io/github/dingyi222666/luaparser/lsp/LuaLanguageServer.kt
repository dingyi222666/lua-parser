package io.github.dingyi222666.luaparser.lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
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
    private val languageService: LuaLanguageService = LuaLanguageService(JvmWorkspaceEngine())
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
    private val textDocuments = LuaTextDocumentService(
        languageService = languageService,
        publishDiagnostics = ::publishDiagnostics,
        acceptsNotifications = ::acceptsMessages,
        requestPolicy = ::textDocumentRequestPolicy
    )
    private val textDocumentService = object : TextDocumentService by textDocuments {
        override fun didOpen(params: DidOpenTextDocumentParams) {
            if (acceptsMessages()) {
                textDocuments.didOpen(params)
            }
        }

        override fun didChange(params: DidChangeTextDocumentParams) {
            if (acceptsMessages()) {
                textDocuments.didChange(params)
            }
        }

        override fun didClose(params: DidCloseTextDocumentParams) {
            if (acceptsMessages()) {
                textDocuments.didClose(params)
            }
        }

        override fun didSave(params: DidSaveTextDocumentParams) {
            if (acceptsMessages()) {
                textDocuments.didSave(params)
            }
        }

        override fun hover(params: HoverParams): CompletableFuture<Hover> {
            return textDocumentRequest(
                quietResponse = { nullFuture() }
            ) {
                textDocuments.hover(params)
            }
        }

        override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
            return textDocumentRequest(
                quietResponse = {
                    CompletableFuture.completedFuture(Either.forRight(CompletionList(false, mutableListOf<CompletionItem>())))
                }
            ) {
                textDocuments.completion(params)
            }
        }

        override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> {
            return textDocumentRequest(
                quietResponse = { nullFuture() }
            ) {
                textDocuments.signatureHelp(params)
            }
        }

        override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
            return textDocumentRequest(
                quietResponse = {
                    CompletableFuture.completedFuture(
                        Either.forLeft<MutableList<out Location>, MutableList<out LocationLink>>(mutableListOf<Location>())
                    )
                }
            ) {
                textDocuments.definition(params)
            }
        }

        override fun declaration(params: DeclarationParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
            return textDocumentRequest(
                quietResponse = {
                    CompletableFuture.completedFuture(
                        Either.forLeft<MutableList<out Location>, MutableList<out LocationLink>>(mutableListOf<Location>())
                    )
                }
            ) {
                textDocuments.declaration(params)
            }
        }

        override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<MutableList<out DocumentHighlight>> {
            return textDocumentRequest(
                quietResponse = { CompletableFuture.completedFuture(mutableListOf()) }
            ) {
                textDocuments.documentHighlight(params)
            }
        }

        override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> {
            return textDocumentRequest(
                quietResponse = { CompletableFuture.completedFuture(mutableListOf()) }
            ) {
                textDocuments.references(params)
            }
        }

        override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> {
            return textDocumentRequest(
                quietResponse = { CompletableFuture.completedFuture(mutableListOf()) }
            ) {
                textDocuments.documentSymbol(params)
            }
        }
    }
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

        override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<MutableList<out SymbolInformation>, MutableList<out WorkspaceSymbol>>> {
            if (!acceptsMessages()) {
                return failedFuture(IllegalStateException("Lua language server is not accepting workspace requests"))
            }
            return workspaceDelegate.symbol(params)
        }
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        return synchronized(lifecycleLock) {
            when (lifecycleState) {
                LifecycleState.CREATED -> {
                    try {
                        val result = languageService.initialize(params)
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
                    CompletableFuture.completedFuture(0)
                }

                LifecycleState.SHUTDOWN -> CompletableFuture.completedFuture(0)
                LifecycleState.CREATED -> failedFuture(IllegalStateException("Lua language server cannot shut down before initialize"))
                LifecycleState.EXITED -> failedFuture(IllegalStateException("Lua language server has already exited"))
            }
        }
    }

    override fun exit() {
        synchronized(lifecycleLock) {
            lifecycleState = LifecycleState.EXITED
            client = null
        }
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspace

    override fun connect(client: LanguageClient) {
        synchronized(lifecycleLock) {
            if (lifecycleState != LifecycleState.EXITED) {
                this.client = client
            }
        }
    }

    private fun acceptsMessages(): Boolean {
        return lifecycleState == LifecycleState.INITIALIZED
    }

    private fun <T> textDocumentRequest(
        quietResponse: () -> CompletableFuture<T>,
        block: () -> CompletableFuture<T>
    ): CompletableFuture<T> {
        return when (val policy = textDocumentRequestPolicy()) {
            LspTextDocumentRequestPolicy.Accept -> block()
            LspTextDocumentRequestPolicy.QuietEmpty -> quietResponse()
            is LspTextDocumentRequestPolicy.Reject -> failedFuture(IllegalStateException(policy.reason))
        }
    }

    private fun textDocumentRequestPolicy(): LspTextDocumentRequestPolicy {
        return when (lifecycleState) {
            LifecycleState.CREATED -> LspTextDocumentRequestPolicy.QuietEmpty
            LifecycleState.INITIALIZED -> LspTextDocumentRequestPolicy.Accept
            LifecycleState.SHUTDOWN -> LspTextDocumentRequestPolicy.Reject("Lua language server has already shut down")
            LifecycleState.EXITED -> LspTextDocumentRequestPolicy.QuietEmpty
        }
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

    private fun <T> nullFuture(): CompletableFuture<T> {
        @Suppress("UNCHECKED_CAST")
        return CompletableFuture.completedFuture(null) as CompletableFuture<T>
    }
}
