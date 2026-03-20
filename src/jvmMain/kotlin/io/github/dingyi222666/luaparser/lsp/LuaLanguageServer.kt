package io.github.dingyi222666.luaparser.lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class LuaLanguageServer(
    private val languageService: LuaLanguageService = LuaLanguageService(JvmWorkspaceEngine())
) : LanguageServer, LanguageClientAware {
    private var client: LanguageClient? = null
    private val textDocuments = LuaTextDocumentService(languageService) { diagnostics ->
        client?.publishDiagnostics(diagnostics)
    }
    private val workspace = LuaWorkspaceService(languageService)

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        return CompletableFuture.completedFuture(languageService.initialize(params))
    }

    override fun initialized(@Suppress("UNUSED_PARAMETER") params: InitializedParams) {
    }

    override fun shutdown(): CompletableFuture<Any> {
        return CompletableFuture.completedFuture(0)
    }

    override fun exit() {
    }

    override fun getTextDocumentService(): TextDocumentService = textDocuments

    override fun getWorkspaceService(): WorkspaceService = workspace

    override fun connect(client: LanguageClient) {
        this.client = client
    }
}
