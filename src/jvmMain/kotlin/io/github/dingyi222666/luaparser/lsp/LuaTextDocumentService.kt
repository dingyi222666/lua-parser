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

    init {
        languageService.onDocumentDiagnostics = { diagnostics ->
            if (acceptsNotifications()) diagnostics.forEach(publishDiagnostics)
        }
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        if (!acceptsNotifications()) {
            return
        }
        val document = params.textDocument
        val uri = document.uri
        System.err.println("LSP-DEVICE: didOpen uri=$uri len=${document.text.length} v=${document.version}")
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
        System.err.println("LSP-DEVICE: didChange uri=$uri changes=${params.contentChanges.size} v=${document.version}")
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
        // Edited document first, then every other open document the update affected.
        diagnostics.forEach(publishDiagnostics)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        if (!acceptsNotifications()) {
            return
        }
        val diagnostics = synchronized(stateLock) {
            openDocuments.remove(params.textDocument.uri)
            languageService.didClose(params)
        }
        diagnostics.forEach(publishDiagnostics)
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

    override fun hover(params: HoverParams): CompletableFuture<Hover> = guardedRequest(
        quietResponse = { completedFuture<Hover>(null) }
    ) {
        completedFuture(languageService.hover(params))
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
        return guardedRequest(
            quietResponse = { completedFuture(Either.forRight(CompletionList(false, mutableListOf<CompletionItem>()))) }
        ) {
            // Pass the client URI through unchanged. pathFromUri() strips workspace-folder
            // prefixes incorrectly (no access to initialize-time workspaceFolderUriPrefixes),
            // so Monaco/file:// carets resolved to an absolute FS path that missed the
            // open-document snapshot keyed as a workspace-relative VirtualPath — empty
            // require-alias member completions (`utils.` → items[0]).
            languageService.completionAsync(
                params.textDocument.uri,
                params.position.line,
                params.position.character
            ).thenApply { Either.forRight<MutableList<CompletionItem>, CompletionList>(it) }
        }
    }

    /** TASK-514 — completionItem/resolve: enriches detail/documentation without dropping label/kind; quiet policy returns the unresolved item identity-preserving (no throw). */
    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> =
        simpleRequest(unresolved) { languageService.resolveCompletionItem(unresolved) }

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> = guardedRequest(
        quietResponse = { completedFuture<SignatureHelp>(null) }
    ) {
        completedFuture(languageService.signatureHelp(params))
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        return guardedRequest(
            quietResponse = {
                completedFuture(Either.forLeft<MutableList<out Location>, MutableList<out LocationLink>>(mutableListOf()))
            }
        ) {
            // TASK-515: dual-path Location vs LocationLink based on client linkSupport.
            val locations = languageService.definition(params)
            completedFuture(
                if (languageService.supportsDefinitionLink()) {
                    Either.forRight<MutableList<out Location>, MutableList<out LocationLink>>(
                        locations.map { LocationLink(it.uri, it.range, it.range) }.toMutableList()
                    )
                } else {
                    Either.forLeft<MutableList<out Location>, MutableList<out LocationLink>>(locations.toMutableList())
                }
            )
        }
    }

    override fun declaration(params: DeclarationParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> =
        guardedRequest(
            quietResponse = {
                completedFuture(Either.forLeft<MutableList<out Location>, MutableList<out LocationLink>>(mutableListOf()))
            }
        ) {
            completedFuture(Either.forLeft(languageService.declaration(params).toMutableList()))
        }

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<MutableList<out DocumentHighlight>> =
        simpleRequest(mutableListOf()) { languageService.documentHighlights(params).toMutableList() }

    override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> =
        simpleRequest(mutableListOf()) { languageService.references(params).toMutableList() }

    /** TASK-518 — textDocument/prepareRename: identifier-span PrepareRenameResult for renamable locals/params/functions; null for keywords / non-identifiers / free globals; quiet → null. Never throws UOE once wired. */
    override fun prepareRename(
        params: PrepareRenameParams
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
        val nullResult = completedFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>>(null)
        return guardedRequest(
            quietResponse = { nullResult }
        ) {
            val prepared = languageService.prepareRename(params)
            if (prepared == null) {
                nullResult
            } else {
                completedFuture(
                    Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(prepared)
                )
            }
        }
    }

    /** TASK-518 — textDocument/rename: WorkspaceEdit of identifier-span TextEdits for same-file locals (declaration + refs); missing / unsafe positions → empty edit (no crash); quiet → empty edit. Multi-file rename stays limited to same-file lexical sites. */
    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> =
        simpleRequest(WorkspaceEdit(emptyMap())) { languageService.rename(params) }

    /** TASK-520 — textDocument/prepareCallHierarchy: CallHierarchyItem for local functions (definitions or call sites); non-function positions soft-degrade to empty without throw. */
    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<List<CallHierarchyItem>> =
        simpleRequest(emptyList()) { languageService.prepareCallHierarchy(params) }

    /** TASK-520 — callHierarchy/incomingCalls for same-file local call graph subset; unknown / non-function items → empty list (no crash). */
    override fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams
    ): CompletableFuture<List<CallHierarchyIncomingCall>> =
        simpleRequest(emptyList()) { languageService.callHierarchyIncomingCalls(params) }

    /** TASK-520 — callHierarchy/outgoingCalls for same-file local call graph subset; unknown / non-function items → empty list (no crash). */
    override fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams
    ): CompletableFuture<List<CallHierarchyOutgoingCall>> =
        simpleRequest(emptyList()) { languageService.callHierarchyOutgoingCalls(params) }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<MutableList<Either<SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>>> =
        simpleRequest(mutableListOf()) {
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
            symbols
        }

    /** TASK-539 — textDocument/foldingRange for multi-line functions and tables; soft-degrades to an empty list under quiet policies; never throws UOE once wired. */
    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> =
        simpleRequest(emptyList()) { languageService.foldingRanges(params) }

    /** TASK-540 — textDocument/selectionRange nested AST parent chains; unresolvable positions are dropped by the service, so the list never carries null slots. */
    override fun selectionRange(params: SelectionRangeParams): CompletableFuture<List<SelectionRange>> =
        simpleRequest(emptyList()) { languageService.selectionRanges(params) }

    /** TASK-541 — textDocument/semanticTokens/full; soft-degrades to empty tokens under quiet policies; never throws UOE once wired. */
    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> =
        simpleRequest(SemanticTokens(emptyList())) { languageService.semanticTokensFull(params) }

    /** TASK-252 — textDocument/semanticTokens/full/delta; soft-degrades to empty full tokens under quiet policies; never throws UOE once wired. */
    override fun semanticTokensFullDelta(
        params: SemanticTokensDeltaParams
    ): CompletableFuture<Either<SemanticTokens, SemanticTokensDelta>> =
        simpleRequest(Either.forLeft(SemanticTokens(emptyList()))) {
            languageService.semanticTokensFullDelta(params)
        }

    /** TASK-542 — textDocument/codeAction for published diagnostics (quickFix); soft-degrades to an empty list under quiet policies / when no safe fix exists; never throws UOE once wired. */
    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> =
        simpleRequest(emptyList()) { languageService.codeActions(params) }

    /** TASK-543 — textDocument/inlayHint parameter-name surface for call arguments; soft-degrades to an empty list under quiet policies / outside calls / unknown callees; never throws UOE once wired. */
    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> =
        simpleRequest(emptyList()) { languageService.inlayHints(params) }

    /** TASK-544 — textDocument/formatting full-document surface; soft-degrades to an empty list under quiet policies / unknown docs / malformed sources; never throws UOE once wired. */
    override fun formatting(params: DocumentFormattingParams): CompletableFuture<MutableList<out TextEdit>> =
        simpleRequest(mutableListOf()) { languageService.formatting(params).toMutableList() }

    /** TASK-544 — textDocument/rangeFormatting surface (table bodies / selections); soft-degrades to an empty list for quiet policies, missing docs, inverted / OOB ranges, and malformed buffers. */
    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<MutableList<out TextEdit>> =
        simpleRequest(mutableListOf()) { languageService.rangeFormatting(params).toMutableList() }

    /** TASK-274 — textDocument/onTypeFormatting for Lua `end` / `then` triggers; soft-degrades to an empty list under quiet policies / unknown triggers / malformed buffers. */
    override fun onTypeFormatting(
        params: DocumentOnTypeFormattingParams
    ): CompletableFuture<List<out TextEdit>> =
        simpleRequest(emptyList()) { languageService.onTypeFormatting(params) }

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

    /**
     * The common endpoint shape: quiet policy returns [quiet], accepted path completes
     * synchronously with the lambda result. Endpoints with an async accepted path
     * (completion) or a nullable quiet payload (hover, signatureHelp, prepareRename)
     * keep [guardedRequest].
     */
    private fun <T> simpleRequest(quiet: T, accepted: () -> T): CompletableFuture<T> {
        return guardedRequest(
            quietResponse = { completedFuture(quiet) },
            acceptedRequest = { completedFuture(accepted()) }
        )
    }

    private fun <T> completedFuture(value: T?): CompletableFuture<T> {
        @Suppress("UNCHECKED_CAST")
        return CompletableFuture.completedFuture(value) as CompletableFuture<T>
    }

    private fun <T> failedFuture(throwable: Throwable): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        future.completeExceptionally(throwable)
        return future
    }
}

/**
 * Incremental didChange application shared by [LuaTextDocumentService] and
 * [LuaLanguageService] (previously two byte-identical private copies): a change
 * without a range replaces the whole buffer; a ranged edit folds onto the current
 * text with end clamped to the start.
 */
internal fun applyContentChanges(current: String, changes: List<TextDocumentContentChangeEvent>): String {
    return changes.fold(current) { text, change ->
        val range = change.range
        if (range == null) {
            change.text
        } else {
            val start = lspOffsetAt(text, range.start)
            val end = lspOffsetAt(text, range.end).coerceAtLeast(start)
            text.replaceRange(start, end, change.text)
        }
    }
}

/** LSP 0-based line/character position → string offset, clamped to the line end. */
internal fun lspOffsetAt(text: String, position: org.eclipse.lsp4j.Position): Int {
    val lineStarts = mutableListOf(0)
    text.forEachIndexed { index, character ->
        if (character == '\n') {
            lineStarts += index + 1
        }
    }

    if (position.line <= 0) {
        return position.character.coerceAtLeast(0).coerceAtMost(lspLineEnd(text, 0))
    }

    if (position.line >= lineStarts.size) {
        return text.length
    }

    val lineStart = lineStarts[position.line]
    val lineEnd = lspLineEnd(text, lineStart)
    return (lineStart + position.character.coerceAtLeast(0)).coerceAtMost(lineEnd)
}

/** Offset just past the last character of the line whose content starts at [lineStart]. */
private fun lspLineEnd(text: String, lineStart: Int): Int {
    val newline = text.indexOf('\n', lineStart)
    val end = if (newline >= 0) newline else text.length
    return if (end > lineStart && text[end - 1] == '\r') end - 1 else end
}
