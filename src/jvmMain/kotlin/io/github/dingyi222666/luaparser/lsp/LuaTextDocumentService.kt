package io.github.dingyi222666.luaparser.lsp

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Location

import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensDelta
import org.eclipse.lsp4j.SemanticTokensDeltaParams
import org.eclipse.lsp4j.SignatureHelp

import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture

sealed class LspTextDocumentRequestPolicy {
    object Accept : LspTextDocumentRequestPolicy()
    object QuietEmpty : LspTextDocumentRequestPolicy()
    data class Reject(val reason: String) : LspTextDocumentRequestPolicy()
}

class LuaTextDocumentService(
    private val languageService: LuaLanguageService,
    private val publishDiagnostics: (PublishDiagnosticsParams) -> Unit = {},
    private val acceptsNotifications: () -> Boolean = { true },
    private val requestPolicy: () -> LspTextDocumentRequestPolicy = { LspTextDocumentRequestPolicy.Accept }
) : TextDocumentService {
    private data class OpenDocument(
        val uri: String,
        val text: String,
        val version: Int?
    )

    private val stateLock = Any()
    private val openDocuments = linkedMapOf<String, OpenDocument>()

    override fun didOpen(params: DidOpenTextDocumentParams) {
        if (!acceptsNotifications()) {
            return
        }
        val document = params.textDocument
        val uri = document.uri
        val diagnostics = synchronized(stateLock) {
            openDocuments[uri] = OpenDocument(uri, document.text, document.version)
            languageService.didOpen(params)
        }
        publishDiagnostics(diagnostics)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        if (!acceptsNotifications()) {
            return
        }
        val document = params.textDocument
        val uri = document.uri
        val diagnostics = synchronized(stateLock) {
            val openDocument = openDocuments[uri] ?: return@synchronized null
            val incomingVersion = document.version
            if (incomingVersion != null && openDocument.version != null && incomingVersion <= openDocument.version) {
                return@synchronized null
            }

            val updatedText = applyContentChanges(openDocument.text, params.contentChanges)
            openDocuments[uri] = OpenDocument(uri, updatedText, incomingVersion ?: openDocument.version)
            languageService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, incomingVersion),
                    listOf(TextDocumentContentChangeEvent(updatedText))
                )
            )
        } ?: return
        publishDiagnostics(diagnostics)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        if (!acceptsNotifications()) {
            return
        }
        val diagnostics = synchronized(stateLock) {
            openDocuments.remove(params.textDocument.uri)
            languageService.didClose(params)
        }
        publishDiagnostics(diagnostics)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        if (acceptsNotifications()) {
            languageService.didSave(params)
        }
    }

    fun republishDiagnostics() {
        if (!acceptsNotifications()) {
            return
        }
        val documents = synchronized(stateLock) { openDocuments.values.toList() }
        documents.forEach { document ->
            publishDiagnostics(languageService.diagnosticsForUri(document.uri))
        }
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        return guardedRequest(
            quietResponse = { nullFuture<Hover>() }
        ) {
            nullablePayloadFuture(languageService.hover(params))
        }
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
        return guardedRequest(
            quietResponse = {
                CompletableFuture.completedFuture(Either.forRight(CompletionList(false, mutableListOf<CompletionItem>())))
            }
        ) {
            // Pass the client URI through unchanged. pathFromUri() strips workspace-folder
            // prefixes incorrectly (no access to initialize-time workspaceFolderUriPrefixes),
            // so Monaco/file:// carets resolved to an absolute FS path that missed the
            // open-document snapshot keyed as a workspace-relative VirtualPath — empty
            // require-alias member completions (`utils.` → items[0]).
            val completionList = languageService.completion(
                params.textDocument.uri,
                params.position.line,
                params.position.character
            )
            CompletableFuture.completedFuture(Either.forRight(completionList))
        }
    }

    /**
     * TASK-514 — completionItem/resolve.
     * Enriches detail/documentation for unresolved items without dropping label/kind.
     * Quiet policy returns the unresolved item identity-preserving (no throw).
     */
    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(unresolved) }
        ) {
            CompletableFuture.completedFuture(languageService.resolveCompletionItem(unresolved))
        }
    }

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> {
        return guardedRequest(
            quietResponse = { nullFuture<SignatureHelp>() }
        ) {
            nullablePayloadFuture(languageService.signatureHelp(params))
        }
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        return guardedRequest(
            quietResponse = {
                CompletableFuture.completedFuture(
                    Either.forLeft<MutableList<out Location>, MutableList<out LocationLink>>(mutableListOf<Location>())
                )
            }
        ) {
            // TASK-515: dual-path Location vs LocationLink based on client linkSupport.
            val locations = languageService.definition(params)
            if (languageService.supportsDefinitionLink()) {
                val links = locations.map { location ->
                    LocationLink(
                        /* targetUri = */ location.uri,
                        /* targetRange = */ location.range,
                        /* targetSelectionRange = */ location.range
                    )
                }.toMutableList()
                CompletableFuture.completedFuture(
                    Either.forRight<MutableList<out Location>, MutableList<out LocationLink>>(links)
                )
            } else {
                CompletableFuture.completedFuture(
                    Either.forLeft(locations.toMutableList())
                )
            }
        }
    }

    override fun declaration(params: DeclarationParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        return guardedRequest(
            quietResponse = {
                CompletableFuture.completedFuture(
                    Either.forLeft<MutableList<out Location>, MutableList<out LocationLink>>(mutableListOf<Location>())
                )
            }
        ) {
            CompletableFuture.completedFuture(
                Either.forLeft(languageService.declaration(params).toMutableList())
            )
        }
    }

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<MutableList<out DocumentHighlight>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(mutableListOf()) }
        ) {
            CompletableFuture.completedFuture(languageService.documentHighlights(params).toMutableList())
        }
    }

    override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(mutableListOf()) }
        ) {
            CompletableFuture.completedFuture(languageService.references(params).toMutableList())
        }
    }

    /**
     * TASK-518 — textDocument/prepareRename.
     * Returns identifier-span PrepareRenameResult for renamable locals/params/functions;
     * null (reject) for keywords / non-identifiers / free globals. Quiet policy → null.
     * Never throws UnsupportedOperationException once wired.
     */
    override fun prepareRename(
        params: PrepareRenameParams
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
        return guardedRequest(
            quietResponse = {
                @Suppress("UNCHECKED_CAST")
                CompletableFuture.completedFuture(null)
                    as CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>>
            }
        ) {
            val prepared = languageService.prepareRename(params)
            if (prepared == null) {
                @Suppress("UNCHECKED_CAST")
                return@guardedRequest CompletableFuture.completedFuture(null)
                    as CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>>
            }
            CompletableFuture.completedFuture(
                Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(prepared)
            )
        }
    }

    /**
     * TASK-518 — textDocument/rename.
     * WorkspaceEdit of identifier-span TextEdits for same-file locals (declaration + refs).
     * Missing / unsafe positions → empty WorkspaceEdit (no crash). Quiet → empty edit.
     * Multi-file rename stays limited to same-file lexical sites.
     */
    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(WorkspaceEdit(emptyMap())) }
        ) {
            CompletableFuture.completedFuture(languageService.rename(params))
        }
    }


    /**
     * TASK-520 — textDocument/prepareCallHierarchy.
     * Yields CallHierarchyItem for local functions (definitions or call sites);
     * non-function positions soft-degrade to empty without throw.
     */
    override fun prepareCallHierarchy(
        params: CallHierarchyPrepareParams
    ): CompletableFuture<List<CallHierarchyItem>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(emptyList()) }
        ) {
            CompletableFuture.completedFuture(languageService.prepareCallHierarchy(params))
        }
    }

    /**
     * TASK-520 — callHierarchy/incomingCalls for same-file local call graph subset.
     * Unknown / non-function items → empty list (no crash).
     */
    override fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams
    ): CompletableFuture<List<CallHierarchyIncomingCall>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(emptyList()) }
        ) {
            CompletableFuture.completedFuture(languageService.callHierarchyIncomingCalls(params))
        }
    }

    /**
     * TASK-520 — callHierarchy/outgoingCalls for same-file local call graph subset.
     * Unknown / non-function items → empty list (no crash).
     */
    override fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams
    ): CompletableFuture<List<CallHierarchyOutgoingCall>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(emptyList()) }
        ) {
            CompletableFuture.completedFuture(languageService.callHierarchyOutgoingCalls(params))
        }
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<MutableList<Either<SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(mutableListOf()) }
        ) {
            // Same URI-pass-through as completion: languageService pathOf/pathFromClientPath
            // owns workspace-folder prefix stripping; pathFromUri() does not.
            val path = params.textDocument.uri
            val symbols: MutableList<Either<SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>> =
                if (languageService.supportsHierarchicalDocumentSymbols()) {
                    // Client capability hierarchicalDocumentSymbolSupport == true:
                    // return nested DocumentSymbol tree via existing hierarchical helper.
                    languageService.hierarchicalDocumentSymbols(path)
                        .map { Either.forRight<SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>(it) }
                        .toMutableList()
                } else {
                    // Legacy / false / unset: flatten to SymbolInformation.
                    languageService.documentSymbols(path)
                        .map { Either.forLeft<SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>(it) }
                        .toMutableList()
                }
            CompletableFuture.completedFuture(symbols)
        }
    }

    /**
     * TASK-539 — textDocument/foldingRange for multi-line functions and tables.
     * Soft-degrades to an empty list under quiet policies; never throws
     * UnsupportedOperationException once wired.
     */
    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(emptyList()) }
        ) {
            CompletableFuture.completedFuture(languageService.foldingRanges(params))
        }
    }

    /**
     * TASK-540 — textDocument/selectionRange nested AST parent chains.
     * Soft-degrades to an empty list under quiet policies / unknown positions;
     * never throws UnsupportedOperationException once wired.
     */
    override fun selectionRange(params: SelectionRangeParams): CompletableFuture<List<SelectionRange>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(emptyList()) }
        ) {
            @Suppress("UNCHECKED_CAST")
            CompletableFuture.completedFuture(
                languageService.selectionRanges(params) as List<SelectionRange>
            )
        }
    }

    /**
     * TASK-541 — textDocument/semanticTokens/full.
     * Soft-degrades to empty SemanticTokens under quiet policies; never throws
     * UnsupportedOperationException once wired.
     */
    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(SemanticTokens(emptyList())) }
        ) {
            CompletableFuture.completedFuture(languageService.semanticTokensFull(params))
        }
    }

    /**
     * TASK-252 — textDocument/semanticTokens/full/delta.
     * Soft-degrades to empty full tokens under quiet policies; never throws
     * UnsupportedOperationException once wired.
     */
    override fun semanticTokensFullDelta(
        params: SemanticTokensDeltaParams
    ): CompletableFuture<Either<SemanticTokens, SemanticTokensDelta>> {
        return guardedRequest(
            quietResponse = {
                CompletableFuture.completedFuture(
                    Either.forLeft(SemanticTokens(emptyList()))
                )
            }
        ) {
            CompletableFuture.completedFuture(languageService.semanticTokensFullDelta(params))
        }
    }

    /**
     * TASK-542 — textDocument/codeAction for published diagnostics (quickFix).
     * Soft-degrades to an empty list under quiet policies / when no safe fix exists;
     * never throws UnsupportedOperationException once wired.
     */
    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(emptyList()) }
        ) {
            CompletableFuture.completedFuture(languageService.codeActions(params))
        }
    }

    /**
     * TASK-543 — textDocument/inlayHint parameter-name surface for call arguments.
     * Soft-degrades to an empty list under quiet policies / outside calls / unknown
     * callees; never throws UnsupportedOperationException once wired.
     */
    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(emptyList()) }
        ) {
            CompletableFuture.completedFuture(languageService.inlayHints(params))
        }
    }


    /**
     * TASK-544 — textDocument/formatting full-document surface.
     * Soft-degrades to an empty list under quiet policies / unknown docs / malformed
     * sources; never throws UnsupportedOperationException once wired.
     */
    override fun formatting(params: DocumentFormattingParams): CompletableFuture<MutableList<out TextEdit>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(mutableListOf()) }
        ) {
            CompletableFuture.completedFuture(languageService.formatting(params).toMutableList())
        }
    }

    /**
     * TASK-544 — textDocument/rangeFormatting surface (table bodies / selections).
     * Soft-degrades to an empty list for quiet policies, missing docs, inverted /
     * OOB ranges, and malformed buffers; never throws UnsupportedOperationException.
     */
    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<MutableList<out TextEdit>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(mutableListOf()) }
        ) {
            CompletableFuture.completedFuture(languageService.rangeFormatting(params).toMutableList())
        }
    }

    /**
     * TASK-274 — textDocument/onTypeFormatting for Lua `end` / `then` triggers.
     * Soft-degrades to an empty list under quiet policies / unknown triggers /
     * malformed buffers; never throws UnsupportedOperationException once wired.
     */
    override fun onTypeFormatting(
        params: DocumentOnTypeFormattingParams
    ): CompletableFuture<List<out TextEdit>> {
        return guardedRequest(
            quietResponse = { CompletableFuture.completedFuture(emptyList()) }
        ) {
            CompletableFuture.completedFuture(languageService.onTypeFormatting(params))
        }
    }

    private fun <T> guardedRequest(
        quietResponse: () -> CompletableFuture<T>,
        acceptedRequest: () -> CompletableFuture<T>
    ): CompletableFuture<T> {
        return when (val policy = requestPolicy()) {
            LspTextDocumentRequestPolicy.Accept -> acceptedRequest()
            LspTextDocumentRequestPolicy.QuietEmpty -> quietResponse()
            is LspTextDocumentRequestPolicy.Reject -> failedFuture(IllegalStateException(policy.reason))
        }
    }

    private fun applyContentChanges(current: String, changes: List<TextDocumentContentChangeEvent>): String {
        return changes.fold(current) { text, change ->
            val range = change.range
            if (range == null) {
                change.text
            } else {
                val start = offsetAt(text, range.start)
                val end = offsetAt(text, range.end).coerceAtLeast(start)
                text.replaceRange(start, end, change.text)
            }
        }
    }

    private fun offsetAt(text: String, position: org.eclipse.lsp4j.Position): Int {
        val lineStarts = mutableListOf(0)
        text.forEachIndexed { index, character ->
            if (character == '\n') {
                lineStarts += index + 1
            }
        }

        if (position.line <= 0) {
            return position.character.coerceAtLeast(0).coerceAtMost(lineEnd(text, 0))
        }

        if (position.line >= lineStarts.size) {
            return text.length
        }

        val lineStart = lineStarts[position.line]
        val lineEnd = lineEnd(text, lineStart)
        return (lineStart + position.character.coerceAtLeast(0)).coerceAtMost(lineEnd)
    }

    private fun lineEnd(text: String, lineStart: Int): Int {
        val newline = text.indexOf('\n', lineStart)
        val end = if (newline >= 0) newline else text.length
        return if (end > lineStart && text[end - 1] == '\r') end - 1 else end
    }

    private fun <T> nullFuture(): CompletableFuture<T> {
        @Suppress("UNCHECKED_CAST")
        return CompletableFuture.completedFuture(null) as CompletableFuture<T>
    }

    private fun <T> nullablePayloadFuture(value: T?): CompletableFuture<T> {
        @Suppress("UNCHECKED_CAST")
        return CompletableFuture.completedFuture(value) as CompletableFuture<T>
    }

    private fun <T> failedFuture(throwable: Throwable): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        future.completeExceptionally(throwable)
        return future
    }
}
