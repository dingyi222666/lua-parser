package io.github.dingyi222666.luaparser.lsp

import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture

class LuaTextDocumentService(
    private val languageService: LuaLanguageService,
    private val publishDiagnostics: (PublishDiagnosticsParams) -> Unit = {}
) : TextDocumentService {
    override fun didOpen(params: DidOpenTextDocumentParams) {
        publishDiagnostics(languageService.didOpen(params))
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        publishDiagnostics(languageService.didChange(params))
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        publishDiagnostics(languageService.didClose(params))
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        languageService.didSave(params)
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        return CompletableFuture.completedFuture(languageService.hover(params))
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<org.eclipse.lsp4j.CompletionItem>, CompletionList>> {
        val path = params.textDocument.uri.removePrefix("file:///")
        val completionList = languageService.completion(path, params.position.line, params.position.character)
        return CompletableFuture.completedFuture(Either.forRight(completionList))
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        return CompletableFuture.completedFuture(Either.forLeft(languageService.definition(params).toMutableList()))
    }

    override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> {
        return CompletableFuture.completedFuture(languageService.references(params).toMutableList())
    }
}
