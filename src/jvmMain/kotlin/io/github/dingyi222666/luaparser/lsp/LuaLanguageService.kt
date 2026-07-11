package io.github.dingyi222666.luaparser.lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.source.AST2Lua
import io.github.dingyi222666.luaparser.parser.LuaParserRecoveryDiagnostic
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
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Range

import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.model.NodePositionIndex
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceLocation

import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSemanticFile
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind as SemanticSymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDocumentSymbol
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSymbolEntry
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceUpdateResult
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionOptions
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.DeclarationParams
import org.eclipse.lsp4j.DefinitionOptions
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolOptions
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverOptions
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.InsertTextFormat

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceOptions
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.RenameOptions
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities

import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceFoldersOptions
import org.eclipse.lsp4j.WorkspaceServerCapabilities
import org.eclipse.lsp4j.WorkspaceSymbolOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

class LuaLanguageService(
    private val engine: JvmWorkspaceEngine = JvmWorkspaceEngine()
) {
    private val stateLock = Any()
    private var snapshot: WorkspaceSnapshot = WorkspaceSnapshot()
    private var queries: LuaWorkspaceQueryFacade = LuaWorkspaceQueryFacade(snapshot)
    private val indexedWorkspaceFiles = linkedMapOf<VirtualPath, String>()
    private val indexedWorkspaceUris = linkedMapOf<VirtualPath, String>()
    private val workspaceFolderUriPrefixes = linkedMapOf<String, String>()
    private val openDocuments = linkedMapOf<VirtualPath, String>()
    private val documentUris = linkedMapOf<VirtualPath, String>()
    private var workspaceMetadata: Map<String, String> = emptyMap()
    private var workspaceFolders: List<WorkspaceFolder> = emptyList()
    /**
     * Captured from client initialize:
     * `textDocument.documentSymbol.hierarchicalDocumentSymbolSupport`.
     * When true, textDocument/documentSymbol may return nested DocumentSymbol (Either.right);
     * otherwise flatten to SymbolInformation (Either.left).
     */
    private var hierarchicalDocumentSymbolSupport: Boolean = false
    /**
     * Captured from client initialize:
     * `workspace.symbol.resolveSupport` (modern WorkspaceSymbol client capability).
     * When present (non-null), workspace/symbol may return WorkspaceSymbol (Either.right);
     * otherwise legacy SymbolInformation (Either.left).
     */
    private var modernWorkspaceSymbolSupport: Boolean = false
    /**
     * Captured from client initialize:
     * `textDocument.definition.linkSupport`.
     * When true, textDocument/definition may return LocationLink (Either.right);
     * when absent/false, keep Location lists (Either.left) without inventing links.
     */
    private var definitionLinkSupport: Boolean = false
    /** Files last applied to [snapshot] via build/update (indexed + open overlays). */
    private var lastSyncedFiles: Map<VirtualPath, String> = emptyMap()
    private var snapshotReady: Boolean = false

    /** Counts full engine.build invocations (initialize / metadata invalidation). Test-visible. */
    internal var fullRebuildCount: Int = 0
        private set
    /** Counts engine.update delta invocations for open/change/close/watched paths. Test-visible. */
    internal var incrementalUpdateCount: Int = 0
        private set

    fun initialize(params: InitializeParams): InitializeResult = synchronized(stateLock) {
        hierarchicalDocumentSymbolSupport =
            params.capabilities
                ?.textDocument
                ?.documentSymbol
                ?.hierarchicalDocumentSymbolSupport == true
        // Clients that understand modern WorkspaceSymbol advertise resolveSupport on
        // workspace.symbol (properties they can resolve). Presence of that object is the
        // dual-path signal for Either.right WorkspaceSymbol lists.
        modernWorkspaceSymbolSupport =
            params.capabilities
                ?.workspace
                ?.symbol
                ?.resolveSupport != null
        // textDocument.definition.linkSupport → LocationLink (Either.right) for definition.
        definitionLinkSupport =
            params.capabilities
                ?.textDocument
                ?.definition
                ?.linkSupport == true
        workspaceFolders = configuredWorkspaceFolders(params)
        refreshWorkspaceFolderUriPrefixes()
        refreshWorkspaceFolderIndex()
        // Cold start always uses a full rebuild so the first snapshot is authoritative.
        rebuildFull()
        InitializeResult(serverCapabilities())
    }

    fun setWorkspaceMetadata(metadata: Map<String, String>) = synchronized(stateLock) {
        workspaceMetadata = metadata.toMap()
        // Configuration that invalidates global metadata falls back to a full rebuild.
        rebuildFull()
    }

    /**
     * Applies workspace/didChangeWatchedFiles create/change/delete events to the
     * indexed workspace snapshot. Open-document overlays remain authoritative for
     * unsaved buffers. Non-Lua/ALY files are ignored.
     */
    fun applyWatchedFileChanges(changes: List<FileEvent>) = synchronized(stateLock) {
        var mutated = false
        changes.forEach { event ->
            val uri = event.uri?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!isLuaOrAlyUri(uri)) {
                return@forEach
            }
            val virtualPath = virtualPathForWatchedUri(uri)
            when (event.type) {
                FileChangeType.Deleted -> {
                    val removedSource = indexedWorkspaceFiles.remove(virtualPath) != null
                    // Keep URI mapping so diagnostics("path") still clears against the
                    // original file URI after the source is dropped from the snapshot.
                    indexedWorkspaceUris.putIfAbsent(virtualPath, uri)
                    if (removedSource) {
                        mutated = true
                    }
                }

                FileChangeType.Created, FileChangeType.Changed -> {
                    val source = readWorkspaceSourceFromUri(uri)
                    if (source != null) {
                        val previous = indexedWorkspaceFiles.put(virtualPath, source)
                        indexedWorkspaceUris[virtualPath] = uri
                        if (previous != source) {
                            mutated = true
                        }
                    } else if (event.type == FileChangeType.Changed) {
                        // File may have been replaced/truncated; drop stale index entry if unreadable.
                        if (indexedWorkspaceFiles.remove(virtualPath) != null) {
                            indexedWorkspaceUris.putIfAbsent(virtualPath, uri)
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
     * Applies workspace/didChangeWorkspaceFolders add/remove without a process restart.
     *
     * Added folders are walked for `.lua`/`.aly` sources via [indexWorkspaceFolder].
     * Removed folders drop their indexed sources/symbols. Open-document overlays in
     * [openDocuments] remain authoritative over disk index entries (same as watched-file
     * and initialize paths).
     *
     * Multi-root relative-path collision remains last-write-wins: each folder indexes
     * files under a root-relative virtual key (`dep.lua`), so two roots that both
     * contain the same relative path share one map entry unless a follow-up
     * disambiguates virtual keys (folder-qualified paths / URI-keyed index).
     */
    fun applyWorkspaceFolderChanges(
        added: List<WorkspaceFolder>,
        removed: List<WorkspaceFolder>
    ) = synchronized(stateLock) {
        if (added.isEmpty() && removed.isEmpty()) {
            return
        }

        val removedKeys = removed.mapNotNull { folderUriKey(it) }.toSet()
        if (removedKeys.isNotEmpty()) {
            workspaceFolders = workspaceFolders.filter { folder ->
                folderUriKey(folder) !in removedKeys
            }
        }

        if (added.isNotEmpty()) {
            val existingKeys = workspaceFolders.mapNotNull { folderUriKey(it) }.toSet()
            val toAdd = added.filter { folder ->
                val key = folderUriKey(folder) ?: return@filter false
                key !in existingKeys
            }
            if (toAdd.isNotEmpty()) {
                workspaceFolders = workspaceFolders + toAdd
            }
        }

        // Reuse initialize-time helpers: recompute URI prefixes then re-walk remaining
        // folders. Open overlays are merged later in currentWorkspaceFiles().
        refreshWorkspaceFolderUriPrefixes()
        refreshWorkspaceFolderIndex()
        refreshIncremental()
    }

    private fun folderUriKey(folder: WorkspaceFolder): String? {
        val uri = folder.uri?.takeIf { it.isNotBlank() } ?: return null
        val absolute = pathFromFileUri(uri)
        if (absolute != null) {
            return absolute.toString()
        }
        return normalizeLspFileUriPath(uri)?.normalizeWorkspacePathPrefix()
            ?: uri.trim().trimEnd('/')
    }

    fun didOpen(params: DidOpenTextDocumentParams): PublishDiagnosticsParams = synchronized(stateLock) {
        val document = params.textDocument
        val path = pathOf(document)
        openDocuments[path] = document.text
        documentUris[path] = document.uri
        refreshIncremental()
        publishDiagnostics(path)
    }

    fun didChange(params: DidChangeTextDocumentParams): PublishDiagnosticsParams = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        openDocuments[path] = applyContentChanges(openDocuments[path].orEmpty(), params.contentChanges)
        documentUris[path] = params.textDocument.uri
        refreshIncremental()
        publishDiagnostics(path)
    }

    fun didClose(params: DidCloseTextDocumentParams): PublishDiagnosticsParams = synchronized(stateLock) {
        val uri = params.textDocument.uri
        val path = pathOf(uri)
        openDocuments.remove(path)
        documentUris.remove(path)
        refreshIncremental()
        PublishDiagnosticsParams(uri, emptyList())
    }

    fun didSave(@Suppress("UNUSED_PARAMETER") params: DidSaveTextDocumentParams) {
    }

    fun hover(params: HoverParams): Hover? = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        val result = queries.hover(path, params.position.toParserPosition()) ?: return@synchronized null
        // Prefer collapsed preferredHoverType surface (FunctionType/ClassType/MODULE) over bare
        // unknown symbol detail so multi-doc Android-Lua import hovers stay non-empty/rich.
        val preferredTypeDisplay = preferredLspHoverTypeDisplay(
            primary = result.typeInfo?.displayName,
            secondary = result.symbol?.declaredType?.displayName ?: result.symbol?.type?.displayName,
            tertiary = result.symbol?.detail
        )
        val content = buildHoverContent(
            name = result.symbol?.name,
            detail = result.symbol?.detail?.takeUnless { detail ->
                preferredTypeDisplay != null &&
                    (detail == "unknown" || detail == "any") &&
                    preferredTypeDisplay != detail
            },
            typeDisplayName = preferredTypeDisplay
        ) ?: return@synchronized null
        val hover = Hover()
        hover.contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, content))
        hover
    }

    fun completion(path: String, line: Int, character: Int): CompletionList = synchronized(stateLock) {
        val items = queries.completions(pathFromClientPath(path), Position(line + 1, character + 1)).map { completion ->
            CompletionItem(completion.label).apply {
                kind = completion.kind.toLspKind()
                detail = completion.detail
                insertText = completion.insertText
                insertTextFormat = InsertTextFormat.PlainText
                sortText = completion.sortText
            }
        }
        CompletionList(false, items)
    }

    /**
     * TASK-514 — completionItem/resolve.
     * Enriches [detail]/[documentation] for local/function/member (and other) labels
     * using fields already present on the unresolved item plus existing completion-kind
     * metadata. Never renames the label, never blanks insertText when set, and soft-degrades
     * to an identity-preserving item on missing/unknown input (no throw).
     */
    fun resolveCompletionItem(item: CompletionItem): CompletionItem = synchronized(stateLock) {
        try {
            enrichResolvedCompletionItem(item)
        } catch (_: Exception) {
            // Soft identity: resolve must not hard-fail once advertised.
            item
        }
    }

    private fun enrichResolvedCompletionItem(item: CompletionItem): CompletionItem {
        val label = item.label
        if (label.isNullOrBlank()) {
            return item
        }

        val resolved = CompletionItem(label).apply {
            // Preserve core identity fields first — never drop label/kind.
            kind = item.kind
            detail = item.detail
            documentation = item.documentation
            insertText = item.insertText
            insertTextFormat = item.insertTextFormat
            sortText = item.sortText
            filterText = item.filterText
            textEdit = item.textEdit
            additionalTextEdits = item.additionalTextEdits
            command = item.command
            data = item.data
            preselect = item.preselect
            tags = item.tags
            commitCharacters = item.commitCharacters
            deprecated = item.deprecated
        }

        // Fill detail from kind when the list path left it empty (reuse kind metadata only).
        if (resolved.detail.isNullOrBlank()) {
            defaultDetailForCompletionKind(resolved.kind)?.let { resolved.detail = it }
        }

        // Enrich documentation when absent; keep client-provided docs intact.
        if (resolved.documentation == null) {
            val docs = buildCompletionResolveDocumentation(
                label = resolved.label,
                kind = resolved.kind,
                detail = resolved.detail
            )
            if (!docs.isNullOrBlank()) {
                resolved.documentation = Either.forRight(
                    MarkupContent(MarkupKind.MARKDOWN, docs)
                )
            }
        }

        // Never blank insertText when the unresolved item had one.
        if (resolved.insertText != null && resolved.insertText.isBlank() && !item.insertText.isNullOrBlank()) {
            resolved.insertText = item.insertText
        }

        return resolved
    }

    private fun defaultDetailForCompletionKind(
        kind: org.eclipse.lsp4j.CompletionItemKind?
    ): String? {
        return when (kind) {
            org.eclipse.lsp4j.CompletionItemKind.Function,
            org.eclipse.lsp4j.CompletionItemKind.Method -> "function"
            org.eclipse.lsp4j.CompletionItemKind.Variable -> "variable"
            org.eclipse.lsp4j.CompletionItemKind.Field,
            org.eclipse.lsp4j.CompletionItemKind.Property -> "field"
            org.eclipse.lsp4j.CompletionItemKind.Keyword -> "keyword"
            org.eclipse.lsp4j.CompletionItemKind.Snippet -> "snippet"
            org.eclipse.lsp4j.CompletionItemKind.Module -> "module"
            org.eclipse.lsp4j.CompletionItemKind.Class -> "class"
            org.eclipse.lsp4j.CompletionItemKind.TypeParameter -> "type"
            else -> null
        }
    }

    /**
     * Lightweight markdown for resolve-time docs. Reuses label/kind/detail already on the
     * completion item (no new ranking / query algorithms).
     */
    private fun buildCompletionResolveDocumentation(
        label: String?,
        kind: org.eclipse.lsp4j.CompletionItemKind?,
        detail: String?
    ): String? {
        if (label.isNullOrBlank()) {
            return null
        }
        val parts = buildList {
            add("**$label**")
            kind?.let { add("_${completionKindDisplayName(it)}_") }
            detail?.takeIf { it.isNotBlank() }?.let { add("`$it`") }
        }
        return parts.joinToString("\n\n")
    }

    private fun completionKindDisplayName(kind: org.eclipse.lsp4j.CompletionItemKind): String {
        return when (kind) {
            org.eclipse.lsp4j.CompletionItemKind.Function -> "function"
            org.eclipse.lsp4j.CompletionItemKind.Method -> "method"
            org.eclipse.lsp4j.CompletionItemKind.Variable -> "variable"
            org.eclipse.lsp4j.CompletionItemKind.Field,
            org.eclipse.lsp4j.CompletionItemKind.Property -> "field"
            org.eclipse.lsp4j.CompletionItemKind.Keyword -> "keyword"
            org.eclipse.lsp4j.CompletionItemKind.Snippet -> "snippet"
            org.eclipse.lsp4j.CompletionItemKind.Module -> "module"
            org.eclipse.lsp4j.CompletionItemKind.Class -> "class"
            org.eclipse.lsp4j.CompletionItemKind.TypeParameter -> "type"
            org.eclipse.lsp4j.CompletionItemKind.Text -> "text"
            else -> kind.name.lowercase()
        }
    }

    fun signatureHelp(params: SignatureHelpParams): SignatureHelp? = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        val help = queries.signatureHelp(path, params.position.toParserPosition()) ?: return@synchronized null
        SignatureHelp(
            help.signatures.map { signature ->
                SignatureInformation(signature.label).apply {
                    documentation = signature.documentation
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it)) }
                    parameters = signature.parameters.map { parameter ->
                        ParameterInformation(parameter.label).apply {
                            documentation = parameter.documentation
                                ?.takeIf { it.isNotBlank() }
                                ?.let { Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it)) }
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
        // Prefer require-backed module provider when the caret is on a require-local alias
        // (e.g. `return dep` after `local dep = require("definition-dep")`) or inside a
        // require() call. Workspace gotoDefinition currently returns the same-file local
        // binding for true LOCAL/VARIABLE symbols, which yields the consumer file URI
        // instead of the module file. resolveRequire() still maps the alias to the
        // provider path; re-route here so LSP Location.uri matches the opened module.
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
        queries.gotoDefinition(path, position).map { location ->
            Location(uriFor(location.path), location.range.toLspRange())
        }
    }

    fun declaration(params: DeclarationParams): List<Location> = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        queries.declaration(path, params.position.toParserPosition()).map { location ->
            Location(uriFor(location.path), location.range.toLspRange())
        }
    }

    fun documentHighlights(params: DocumentHighlightParams): List<DocumentHighlight> = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        val semanticFile = snapshot.files[path]?.semanticFile
        queries.documentHighlights(path, params.position.toParserPosition()).map { location ->
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
        val path = pathOf(params.textDocument)
        val source = openDocuments[path] ?: indexedWorkspaceFiles[path]
        val semanticFile = snapshot.files[path]?.semanticFile
        val chunk = semanticFile?.chunk ?: source?.let { parseChunkForFolding(it) }
        if (chunk == null) {
            return@synchronized emptyList()
        }
        val lineCount = source?.let { documentLineCount(it) }
        collectFoldingRanges(chunk, lineCount)
    }

    /**
     * TASK-540 — Selection ranges for nested block expand/shrink selection.
     * Builds a parent chain from the innermost AST node covering each position,
     * walking only real AST parents (no invented spans). Unknown documents,
     * empty positions, and out-of-range cursors soft-degrade to empty / null
     * entries without throwing.
     */
    fun selectionRanges(params: SelectionRangeParams): List<SelectionRange?> = synchronized(stateLock) {
        val positions = params.positions.orEmpty()
        if (positions.isEmpty()) {
            return@synchronized emptyList()
        }
        val path = pathOf(params.textDocument)
        val source = openDocuments[path] ?: indexedWorkspaceFiles[path]
        val semanticFile = snapshot.files[path]?.semanticFile
        val chunk = semanticFile?.chunk ?: source?.let { parseChunkForFolding(it) }
        if (chunk == null) {
            return@synchronized emptyList()
        }
        val index = NodePositionIndex(chunk)
        val results = positions.map { lspPosition ->
            selectionRangeAt(index, lspPosition.toParserPosition())
        }
        if (results.all { it == null }) {
            emptyList()
        } else {
            results
        }
    }

    /**
     * TASK-541 — textDocument/semanticTokens/full.
     * Returns LSP delta-encoded semantic tokens (5-int groups) for keyword /
     * function / variable / parameter / string / number / comment highlighting.
     * Empty / unknown / malformed sources soft-degrade to empty data without throw.
     */
    fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        val source = openDocuments[path] ?: indexedWorkspaceFiles[path]
        if (source.isNullOrEmpty()) {
            return@synchronized SemanticTokens(emptyList())
        }
        val semanticFile = snapshot.files[path]?.semanticFile
        return@synchronized try {
            SemanticTokens(encodeSemanticTokens(source, semanticFile))
        } catch (_: Exception) {
            // Malformed buffers / lexer edge cases must not kill the request path.
            SemanticTokens(emptyList())
        }
    }

    /**
     * TASK-542 — textDocument/codeAction quick-fix surface for published diagnostics.
     * Returns well-formed CodeAction/Command entries when a deterministic fix is
     * available; otherwise an empty list. Never throws for empty selection,
     * inverted/out-of-bounds ranges, or empty diagnostic context.
     */
    fun codeActions(params: CodeActionParams): List<Either<Command, CodeAction>> = synchronized(stateLock) {
        try {
            collectCodeActions(params)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * TASK-543 — textDocument/inlayHint parameter-name surface.
     * For call arguments of annotated/local (and other signature-help-known) callees,
     * yields [InlayHintKind.Parameter] labels derived from formal names. Outside-call
     * ranges, unknown callees, empty/malformed docs soft-degrade to an empty list
     * without throwing. Reuses SignatureHelp formal metadata (no extra ranking).
     */
    fun inlayHints(params: InlayHintParams): List<InlayHint> = synchronized(stateLock) {
        try {
            collectParameterInlayHints(params)
        } catch (_: Exception) {
            emptyList()
        }
    }


    /**
     * TASK-544 — textDocument/formatting full-document surface.
     * Prefers AST2Lua pretty-print when the buffer parses cleanly; otherwise applies
     * a minimal indent/newline normalize. Malformed / unknown sources soft-degrade
     * to an empty edit list without throwing.
     */
    fun formatting(params: DocumentFormattingParams): List<TextEdit> = synchronized(stateLock) {
        try {
            formatDocument(params.textDocument, params.options, range = null)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * TASK-544 — textDocument/rangeFormatting surface.
     * Formats the selected span (or full document when range is null/invalid) using
     * the same AST2Lua / indent-normalize pipeline. Inverted, beyond-EOF, negative,
     * missing-document, and empty ranges soft-degrade to an empty list.
     */
    fun rangeFormatting(params: DocumentRangeFormattingParams): List<TextEdit> = synchronized(stateLock) {
        try {
            formatDocument(params.textDocument, params.options, range = params.range)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * TASK-520 — textDocument/prepareCallHierarchy.
     *
     * Same-file local-function surface: returns a [CallHierarchyItem] when the caret
     * sits on a local function name (definition or call site). Non-function positions
     * (keywords, whitespace, number bindings, free unknowns, empty/malformed buffers)
     * soft-degrade to an empty list without throw.
     */
    fun prepareCallHierarchy(params: CallHierarchyPrepareParams): List<CallHierarchyItem> =
        synchronized(stateLock) {
            try {
                val item = resolveCallHierarchyItem(params.textDocument, params.position)
                if (item == null) emptyList() else listOf(item)
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * TASK-520 — callHierarchy/incomingCalls.
     *
     * Builds a same-file call-graph subset: callers of the item's local function via
     * existing references (use sites) + enclosing local function resolution. Unknown
     * / non-function items yield empty without throw.
     */
    fun callHierarchyIncomingCalls(
        params: CallHierarchyIncomingCallsParams
    ): List<CallHierarchyIncomingCall> = synchronized(stateLock) {
        try {
            val item = params.item ?: return@synchronized emptyList()
            collectIncomingCalls(item)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * TASK-520 — callHierarchy/outgoingCalls.
     *
     * Builds a same-file call-graph subset: callees invoked inside the item's local
     * function body, resolved through definition/goto when possible. Unknown /
     * non-function items yield empty without throw.
     */
    fun callHierarchyOutgoingCalls(
        params: CallHierarchyOutgoingCallsParams
    ): List<CallHierarchyOutgoingCall> = synchronized(stateLock) {
        try {
            val item = params.item ?: return@synchronized emptyList()
            collectOutgoingCalls(item)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * textDocument/references. Honors [ReferenceParams.context] /
     * [org.eclipse.lsp4j.ReferenceContext.isIncludeDeclaration]:
     * - true (default when context absent): declaration site + uses
     * - false: use sites only (declaration locations from [LuaWorkspaceQueryFacade.declaration]
     *   / [LuaWorkspaceQueryFacade.gotoDefinition] stripped when present)
     * Non-symbol / out-of-range / unbound soft-degrade to empty or a short list without throw.
     */
    fun references(params: ReferenceParams): List<Location> = synchronized(stateLock) {
        try {
            val path = pathOf(params.textDocument)
            val position = params.position.toParserPosition()
            val all = queries.references(path, position).map { location ->
                Location(uriFor(location.path), location.range.toLspRange())
            }
            // LSP defaults includeDeclaration to true when clients omit context.
            val includeDeclaration = params.context?.isIncludeDeclaration ?: true
            if (includeDeclaration || all.isEmpty()) {
                return@synchronized all
            }
            filterOutDeclarationLocations(path, position, all)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * TASK-518 — textDocument/prepareRename.
     *
     * Accepts renamable local-like identifiers (locals, params, for-loop names,
     * local functions, attribute locals) with an identifier-span range + placeholder.
     * Rejects keywords, literals, operators, comments, free globals without a local
     * binding, and out-of-range positions by returning null (LSP reject). Soft-accepts
     * table field / method name identifier spans when the caret is on that NAME token
     * (multi-file field rename remains limited at [rename]). Never throws.
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
     * TASK-518 — textDocument/rename.
     *
     * Builds a [WorkspaceEdit] of identifier-span TextEdits covering declaration +
     * references for same-file lexical locals using existing references queries.
     * Multi-file rename is intentionally limited: only same-file URI edits are
     * emitted (cross-file module fields soft-empty rather than invent unsafe edits).
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

            val referenceLocations = if (highlightLocations.isNotEmpty()) {
                highlightLocations
            } else {
                queries.references(requestPath, target.parserPosition).map { location ->
                    Location(uriFor(location.path), location.range.toLspRange())
                }
            }

            // Same-file lexical policy: only emit edits for the requesting document URI.
            // Multi-file field rename may stay empty (documented limit).
            val sameFile = referenceLocations.filter { location ->
                location.uri == requestUri || location.uri == uriFor(requestPath)
            }

            val ranges = linkedMapOf<String, org.eclipse.lsp4j.Range>()
            // Always include the prepared identifier span at the caret.
            ranges[referenceLocationKey(requestUri, target.identifierRange)] = target.identifierRange
            sameFile.forEach { location ->
                val range = location.range
                if (isSingleLineIdentifierSpan(range, target.placeholder.length)) {
                    ranges[referenceLocationKey(location.uri, range)] = range
                }
            }

            if (ranges.isEmpty()) {
                return@synchronized WorkspaceEdit(emptyMap())
            }

            val edits = ranges.values
                .sortedWith(
                    compareBy(
                        { it.start.line },
                        { it.start.character },
                        { it.end.line },
                        { it.end.character }
                    )
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
     * (provider-backed members / synthetic module ranges).
     */
    private fun filterOutDeclarationLocations(
        path: VirtualPath,
        position: Position,
        locations: List<Location>
    ): List<Location> {
        val declarationKeys = buildSet {
            queries.declaration(path, position).forEach { location ->
                add(referenceLocationKey(uriFor(location.path), location.range.toLspRange()))
            }
            if (isEmpty()) {
                queries.gotoDefinition(path, position).forEach { location ->
                    add(referenceLocationKey(uriFor(location.path), location.range.toLspRange()))
                }
            }
        }
        if (declarationKeys.isEmpty()) {
            // No known declaration site (unbound / non-symbol): keep use sites as-is.
            return locations
        }
        return locations.filterNot { location ->
            referenceLocationKey(location.uri, location.range) in declarationKeys
        }
    }

    private fun referenceLocationKey(uri: String, range: org.eclipse.lsp4j.Range): String {
        return "${uri}|${range.start.line}:${range.start.character}|" +
            "${range.end.line}:${range.end.character}"
    }

    /**
     * Resolve a renamable identifier at [lspPosition]. Returns null for non-identifiers,
     * keywords, free globals without a local binding, and missing documents.
     */
    private fun resolveRenameTarget(
        document: TextDocumentIdentifier?,
        lspPosition: org.eclipse.lsp4j.Position?
    ): RenameTarget? {
        if (document == null || lspPosition == null) {
            return null
        }
        if (lspPosition.line < 0 || lspPosition.character < 0) {
            return null
        }
        val path = pathOf(document)
        val source = openDocuments[path] ?: indexedWorkspaceFiles[path] ?: return null
        val parserPosition = lspPosition.toParserPosition()
        val semanticFile = snapshot.files[path]?.semanticFile

        // Prefer AST identifier when available (covers locals, params, for vars, fields).
        val identifier = renameIdentifierAt(semanticFile, parserPosition)
        if (identifier != null) {
            if (!isValidLuaIdentifier(identifier.name)) {
                return null
            }
            // Soft-accept member field / method NAME spans (policy allows accept or soft-reject).
            val parent = runCatching { identifier.parent }.getOrNull()
            if (parent is MemberExpression && parent.identifier === identifier) {
                return RenameTarget(
                    placeholder = identifier.name,
                    identifierRange = identifier.range.toLspRange(),
                    parserPosition = parserPosition
                )
            }
            // Require a local-like binding for bare identifiers (reject free globals / builtins).
            if (!isRenamableLocalLike(semanticFile, path, identifier, parserPosition)) {
                return null
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
        val hasLocalAstBinding = semanticFile?.identifiers?.any { other ->
            other.name == lexerName.text && isLocalBindingIdentifier(other)
        } == true
        if (!hasLocalAstBinding) {
            return null
        }
        val highlights = try {
            queries.documentHighlights(path, parserPosition)
        } catch (_: Exception) {
            emptyList()
        }.filter { it.path == path }
        val refs = if (highlights.isEmpty()) {
            try {
                queries.references(path, parserPosition)
            } catch (_: Exception) {
                emptyList()
            }.filter { it.path == path }
        } else {
            highlights
        }
        if (refs.isEmpty() && highlights.isEmpty()) {
            // Still allow pure declaration-site when AST local binding exists.
            return RenameTarget(
                placeholder = lexerName.text,
                identifierRange = lexerName.range,
                parserPosition = parserPosition
            )
        }
        return RenameTarget(
            placeholder = lexerName.text,
            identifierRange = lexerName.range,
            parserPosition = parserPosition
        )
    }

    private fun renameIdentifierAt(
        semanticFile: WorkspaceSemanticFile?,
        position: Position
    ): Identifier? {
        if (semanticFile == null) {
            return null
        }
        // Exact start match first.
        semanticFile.identifiers.firstOrNull { identifier ->
            identifier.range.start.line == position.line &&
                identifier.range.start.column == position.column
        }?.let { return it }

        // Identifier covering the caret (inclusive start). Uses the public
        // identifiers index only — WorkspaceSemanticFile.nodeAt is package-internal
        // and not visible from jvmMain/lsp (TASK-538 compile fix).
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
        // AttributeIdentifier is always a local binding site.
        if (identifier is io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier) {
            return true
        }
        val parent = runCatching { identifier.parent }.getOrNull()
        when (parent) {
            is LocalStatement -> if (parent.init.any { it === identifier }) return true
            is ForNumericStatement -> if (parent.variable === identifier) return true
            is ForGenericStatement -> if (parent.variables.any { it === identifier }) return true
            is FunctionDeclaration -> {
                if (parent.params.any { it === identifier }) return true
                if (parent.isLocal && functionDeclarationNameIs(parent.identifier, identifier)) {
                    return true
                }
                // Global function name is not a same-file lexical local rename target.
                if (!parent.isLocal && functionDeclarationNameIs(parent.identifier, identifier)) {
                    return false
                }
            }
        }

        // Direct AST local flag on the identifier node (decl site).
        if (identifier.isLocal) {
            return true
        }

        val symbol = semanticFile?.model?.getSymbolAt(position)
            ?: semanticFile?.model?.getSymbolAt(identifier.range.start)

        if (symbol?.kind == SemanticSymbolKind.MODULE ||
            symbol?.kind == SemanticSymbolKind.FIELD ||
            symbol?.kind == SemanticSymbolKind.METHOD ||
            symbol?.kind == SemanticSymbolKind.CLASS ||
            symbol?.kind == SemanticSymbolKind.TYPE_ALIAS
        ) {
            // Fields/methods are soft-accepted earlier via MemberExpression parent.
            // Bare MODULE / class aliases are not lexical local renames.
            return false
        }

        if (symbol?.kind == SemanticSymbolKind.PARAMETER ||
            symbol?.kind == SemanticSymbolKind.LOCAL
        ) {
            return true
        }

        // VARIABLE / FUNCTION / unknown: require a same-file AST local binding of this name
        // so free globals (print, freeName) and builtins are rejected.
        val hasLocalAstBinding = semanticFile?.identifiers?.any { other ->
            other.name == identifier.name && isLocalBindingIdentifier(other)
        } == true
        if (!hasLocalAstBinding) {
            return false
        }

        // Confirm the caret symbol participates in that local binding via highlights/refs.
        val sameFileHighlights = try {
            queries.documentHighlights(path, position)
        } catch (_: Exception) {
            emptyList()
        }.filter { it.path == path }

        if (sameFileHighlights.isNotEmpty()) {
            return true
        }

        val sameFileRefs = try {
            queries.references(path, position)
        } catch (_: Exception) {
            emptyList()
        }.filter { it.path == path }

        return sameFileRefs.isNotEmpty()
    }

    /** True when [identifier] is a declaration-site local / param / for-var / local function name. */
    private fun isLocalBindingIdentifier(identifier: Identifier): Boolean {
        if (identifier.isLocal) {
            return true
        }
        if (identifier is io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier) {
            return true
        }
        val parent = runCatching { identifier.parent }.getOrNull() ?: return false
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

    private fun isValidLuaIdentifier(name: String): Boolean {
        if (name.isEmpty()) {
            return false
        }
        val first = name[0]
        if (!(first == '_' || first.isLetter())) {
            return false
        }
        for (i in 1 until name.length) {
            val ch = name[i]
            if (!(ch == '_' || ch.isLetterOrDigit())) {
                return false
            }
        }
        // Keywords are not renamable identifiers (reject prepare on keyword positions).
        return name !in LUA_KEYWORDS
    }

    private fun isSingleLineIdentifierSpan(range: org.eclipse.lsp4j.Range, expectedLength: Int): Boolean {
        if (range.start.line != range.end.line) {
            return false
        }
        val length = range.end.character - range.start.character
        return length == expectedLength && length > 0
    }

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
        val targetLine = lspPosition.line
        val targetChar = lspPosition.character
        val lexer = LuaLexer(source)
        try {
            while (true) {
                val type = lexer.nextToken()
                if (type == LuaTokenTypes.EOF) {
                    break
                }
                val line0 = (lexer.tokenLine - 1).coerceAtLeast(0)
                val col0 = lexer.tokenColumn.coerceAtLeast(0)
                val text = lexer.tokenText.toString()
                if (text.isEmpty()) {
                    continue
                }
                // Only single-line NAME tokens are rename targets.
                if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                    continue
                }
                val endCol = col0 + text.length
                val covers =
                    line0 == targetLine &&
                        targetChar >= col0 &&
                        targetChar < endCol
                if (!covers) {
                    continue
                }
                if (type != LuaTokenTypes.NAME) {
                    return null
                }
                if (!isValidLuaIdentifier(text)) {
                    return null
                }
                return LexerNameToken(
                    text = text,
                    range = org.eclipse.lsp4j.Range(
                        org.eclipse.lsp4j.Position(line0, col0),
                        org.eclipse.lsp4j.Position(line0, endCol)
                    )
                )
            }
        } catch (_: Exception) {
            return null
        }
        return null
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

    /**
     * Whether the client advertised hierarchical DocumentSymbol support at initialize.
     * Used by [LuaTextDocumentService.documentSymbol] to choose Either.right vs left.
     */
    fun supportsHierarchicalDocumentSymbols(): Boolean = synchronized(stateLock) {
        hierarchicalDocumentSymbolSupport
    }

    /**
     * Whether the client advertised modern WorkspaceSymbol support at initialize
     * (`workspace.symbol.resolveSupport` present). Used by [LuaWorkspaceService.symbol]
     * to choose Either.right([modernWorkspaceSymbols]) vs Either.left([workspaceSymbols]).
     */
    fun supportsModernWorkspaceSymbols(): Boolean = synchronized(stateLock) {
        modernWorkspaceSymbolSupport
    }

    /**
     * Whether the client advertised `textDocument.definition.linkSupport` at initialize.
     * Used by [LuaTextDocumentService.definition] to choose Either.right([LocationLink])
     * vs Either.left([Location]). Absent/false keeps Location lists (no inventing links).
     */
    fun supportsDefinitionLink(): Boolean = synchronized(stateLock) {
        definitionLinkSupport
    }

    fun workspaceSymbols(query: String): List<SymbolInformation> = synchronized(stateLock) {
        queries.workspaceSymbolEntries(query).map { toSymbolInformation(it) }
    }

    fun modernWorkspaceSymbols(query: String): List<WorkspaceSymbol> = synchronized(stateLock) {
        queries.workspaceSymbolEntries(query).map { toWorkspaceSymbol(it) }
    }

    fun diagnostics(path: String): PublishDiagnosticsParams = synchronized(stateLock) {
        publishDiagnostics(pathFromClientPath(path))
    }

    fun diagnosticsForUri(uri: String): PublishDiagnosticsParams = synchronized(stateLock) {
        publishDiagnostics(pathOf(uri), uri)
    }

    private fun publishDiagnostics(path: VirtualPath, uri: String? = null): PublishDiagnosticsParams {
        val diagnostics = (parseDiagnostics(path) + queries.diagnostics(path).map { diagnostic ->
            Diagnostic().apply {
                message = diagnostic.message
                severity = diagnostic.severity.toLspSeverity()
                code = diagnostic.code?.let { Either.forLeft<String, Int>(it) }
                range = diagnostic.range?.toLspRange() ?: Range(Position(1, 1), Position(1, 1)).toLspRange()
            }
        }).distinctBy { diagnostic ->
            listOf(
                diagnostic.range?.start?.line,
                diagnostic.range?.start?.character,
                diagnostic.range?.end?.line,
                diagnostic.range?.end?.character,
                diagnostic.severity,
                diagnostic.message
            )
        }
        return PublishDiagnosticsParams(uri ?: uriFor(path), diagnostics)
    }

    private fun parseDiagnostics(path: VirtualPath): List<Diagnostic> {
        val source = openDocuments[path] ?: indexedWorkspaceFiles[path] ?: return emptyList()
        val result = try {
            LuaParser().parseWithDiagnostics(source)
        } catch (error: IllegalStateException) {
            return listOf(parseDiagnostic(error.message, Range(Position(1, 1), Position(1, 2))))
        }
        return result.recoveryDiagnostics.map(::parseDiagnostic)
    }

    private fun parseDiagnostic(diagnostic: LuaParserRecoveryDiagnostic): Diagnostic {
        return parseDiagnostic(diagnostic.message, diagnostic.range)
    }

    private fun parseDiagnostic(message: String?, range: Range): Diagnostic {
        return Diagnostic().apply {
            this.message = message?.takeIf { it.isNotBlank() } ?: "Lua parse error"
            severity = org.eclipse.lsp4j.DiagnosticSeverity.Error
            code = Either.forLeft<String, Int>("lua-parse")
            this.range = range.toLspRange()
        }
    }

    private fun currentWorkspaceFiles(): Map<VirtualPath, String> {
        val files = linkedMapOf<VirtualPath, String>()
        files.putAll(indexedWorkspaceFiles)
        files.putAll(openDocuments)
        return files
    }

    private fun applyWorkspaceResult(result: WorkspaceUpdateResult, files: Map<VirtualPath, String>) {
        snapshot = result.snapshot
        queries = LuaWorkspaceQueryFacade(snapshot)
        lastSyncedFiles = files.toMap()
        snapshotReady = true
    }

    /** Full workspace rebuild used for cold start and metadata invalidation. */
    private fun rebuildFull() {
        val files = currentWorkspaceFiles()
        val result = engine.build(
            LuaWorkspaceInput(
                files = files,
                metadata = workspaceMetadata
            )
        )
        fullRebuildCount += 1
        applyWorkspaceResult(result, files)
    }

    /**
     * Incremental path: compute a [WorkspaceDelta] against the last applied file map
     * and call engine.update (LuaWorkspaceEngine.update). Falls back to a full
     * rebuild when no snapshot has been established yet.
     */
    private fun refreshIncremental() {
        val files = currentWorkspaceFiles()
        if (!snapshotReady) {
            rebuildFull()
            return
        }

        val upserts = linkedMapOf<VirtualPath, String>()
        val removals = linkedSetOf<VirtualPath>()
        for ((path, source) in files) {
            if (lastSyncedFiles[path] != source) {
                upserts[path] = source
            }
        }
        for (path in lastSyncedFiles.keys) {
            if (path !in files) {
                removals += path
            }
        }

        if (upserts.isEmpty() && removals.isEmpty()) {
            return
        }

        val result = engine.update(
            previous = snapshot,
            delta = WorkspaceDelta(
                upserts = upserts,
                removals = removals
            )
        )
        incrementalUpdateCount += 1
        applyWorkspaceResult(result, files)
    }

    private fun configuredWorkspaceFolders(params: InitializeParams): List<WorkspaceFolder> {
        val folders = params.workspaceFolders.orEmpty().toList()
        if (folders.isNotEmpty()) {
            return folders
        }
        // rootUri shares the same WorkspaceFolder + normalization path as workspace folders.
        val rootUri = params.rootUri?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(WorkspaceFolder(rootUri, workspaceFolderName(rootUri)))
    }

    private fun refreshWorkspaceFolderIndex() {
        indexedWorkspaceFiles.clear()
        indexedWorkspaceUris.clear()
        workspaceFolders.forEach { folder ->
            val root = workspaceFolderRoot(folder) ?: return@forEach
            if (!Files.isDirectory(root)) {
                return@forEach
            }
            indexWorkspaceFolder(root)
        }
    }

    private fun refreshWorkspaceFolderUriPrefixes() {
        workspaceFolderUriPrefixes.clear()
        workspaceFolders.forEach { folder ->
            val root = workspaceFolderRoot(folder) ?: return@forEach
            if (!Files.isDirectory(root)) {
                return@forEach
            }
            val uri = folder.uri ?: return@forEach
            normalizeLspFileUriPath(uri)
                ?.normalizeWorkspacePathPrefix()
                ?.takeIf { it.isNotBlank() }
                ?.let { workspaceFolderUriPrefixes[normalizeUriPrefixKey(it)] = "" }
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
            stream
                .filter { path -> isLuaWorkspaceFile(path) }
                .forEach { path ->
                    val virtualPath = virtualPathForWorkspaceFile(root, path) ?: return@forEach
                    val source = readWorkspaceSource(path) ?: return@forEach
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
    private fun workspaceFolderRoot(folder: WorkspaceFolder): Path? {
        val uri = folder.uri ?: return null
        return pathFromFileUri(uri)
    }

    private fun workspaceFolderName(uri: String): String {
        return normalizeLspFileUriPath(uri)
            ?.normalizeWorkspacePathPrefix()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "workspace"
    }

    private fun isLuaWorkspaceFile(path: Path): Boolean {
        if (!Files.isRegularFile(path)) {
            return false
        }
        val fileName = path.fileName?.toString()?.lowercase() ?: return false
        return fileName.endsWith(".lua") || fileName.endsWith(".aly")
    }

    private fun isLuaOrAlyUri(uri: String): Boolean {
        val candidate = normalizeLspFileUriPath(uri) ?: uri
        val lower = candidate.lowercase()
        return lower.endsWith(".lua") || lower.endsWith(".aly")
    }

    private fun virtualPathForWatchedUri(uri: String): VirtualPath {
        val absolute = pathFromFileUri(uri)
        if (absolute != null) {
            for (folder in workspaceFolders) {
                val root = workspaceFolderRoot(folder) ?: continue
                val absoluteRoot = root.toAbsolutePath().normalize()
                if (!absolute.startsWith(absoluteRoot)) {
                    continue
                }
                val relative = virtualPathForWorkspaceFile(absoluteRoot, absolute)
                if (relative != null) {
                    return relative
                }
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
        return try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun readWorkspaceSourceFromUri(uri: String): String? {
        val path = pathFromFileUri(uri) ?: return null
        return readWorkspaceSource(path)
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
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: FileSystemNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun serverCapabilities(): ServerCapabilities {
        return ServerCapabilities().apply {
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)
            hoverProvider = Either.forRight(HoverOptions())
            declarationProvider = Either.forLeft(true)
            definitionProvider = Either.forRight(DefinitionOptions())
            referencesProvider = Either.forRight(ReferenceOptions())
            documentHighlightProvider = Either.forLeft(true)
            completionProvider = CompletionOptions().apply {
                triggerCharacters = listOf(".", ":")
                // TASK-514: advertise completionItem/resolve once product resolve is live.
                resolveProvider = true
            }
            signatureHelpProvider = SignatureHelpOptions(listOf("(", ","), listOf(")"))
            documentSymbolProvider = Either.forRight(DocumentSymbolOptions())
            workspaceSymbolProvider = Either.forRight(WorkspaceSymbolOptions())
            // TASK-539: advertise folding ranges for multi-line functions/tables.
            foldingRangeProvider = Either.forLeft(true)
            // TASK-540: advertise selection ranges for nested expand/shrink selection.
            selectionRangeProvider = Either.forLeft(true)
            // TASK-541: full semantic tokens with legend (keyword/function/variable/...).
            semanticTokensProvider = SemanticTokensWithRegistrationOptions(
                SEMANTIC_TOKENS_LEGEND,
                true
            )
            // TASK-542: advertise code actions (quickfix) for published diagnostics.
            codeActionProvider = Either.forRight(
                CodeActionOptions(listOf(CodeActionKind.QuickFix)).apply {
                    resolveProvider = false
                }
            )
            // TASK-543: advertise parameter-name inlay hints for call arguments.
            inlayHintProvider = Either.forLeft(true)
            // TASK-544: advertise full-document and range formatting once product is live.
            documentFormattingProvider = Either.forLeft(true)
            documentRangeFormattingProvider = Either.forLeft(true)
            // TASK-520: advertise call hierarchy for local function prepare/incoming/outgoing.
            callHierarchyProvider = Either.forLeft(true)
            // TASK-518: advertise rename with prepareRename once product is live.
            // prepareProvider=true (lsp4j name for prepareSupport) signals prepareRename.
            renameProvider = Either.forRight(RenameOptions(true))
            // Multi-root: advertise workspace folder support + change notifications so
            // clients send workspace/didChangeWorkspaceFolders (TASK-516).
            workspace = WorkspaceServerCapabilities(

                WorkspaceFoldersOptions().apply {
                    supported = true
                    setChangeNotifications(true)
                }
            )
        }
    }

    private fun hierarchicalDocumentSymbols(path: VirtualPath): List<DocumentSymbol> {
        return queries.documentSymbols(path).map(::toDocumentSymbol)
    }

    private fun flattenDocumentSymbol(
        symbol: DocumentSymbol,
        uri: String,
        containerName: String?
    ): List<SymbolInformation> {
        val current = SymbolInformation(
            symbol.name,
            symbol.kind,
            Location(uri, symbol.selectionRange ?: symbol.range),
            containerName
        )
        val children = symbol.children.orEmpty().flatMap { child ->
            flattenDocumentSymbol(child, uri, symbol.name)
        }
        return listOf(current) + children
    }

    private fun toDocumentSymbol(symbol: WorkspaceDocumentSymbol): DocumentSymbol {
        return DocumentSymbol(
            symbol.name,
            symbol.kind.toLspSymbolKind(),
            symbol.range.toLspRange(),
            symbol.selectionRange.toLspRange(),
            symbol.detail,
            symbol.children.map(::toDocumentSymbol)
        )
    }

    private fun toSymbolInformation(entry: WorkspaceSymbolEntry): SymbolInformation {
        return SymbolInformation(
            entry.name,
            entry.kind.toLspSymbolKind(),
            Location(uriFor(entry.path), entry.range.toLspRange()),
            entry.containerName
        )
    }

    private fun toWorkspaceSymbol(entry: WorkspaceSymbolEntry): WorkspaceSymbol {
        return WorkspaceSymbol(
            entry.name,
            entry.kind.toLspSymbolKind(),
            Either.forLeft(Location(uriFor(entry.path), entry.range.toLspRange())),
            entry.containerName
        )
    }

    private fun pathOf(document: TextDocumentItem): VirtualPath = pathOf(document.uri)

    private fun pathOf(document: TextDocumentIdentifier): VirtualPath = pathOf(document.uri)

    private fun pathOf(uri: String): VirtualPath = lspVirtualPathFromUri(
        uri,
        workspaceFolderUriPrefixes,
        collapseSyntheticWorkspaceRoot = shouldCollapseSyntheticWorkspaceRoot()
    )

    private fun pathFromClientPath(pathOrUri: String): VirtualPath {
        val normalizedPath = pathOrUri.normalizeWorkspacePathPrefix()
        val workspacePath = normalizedPath.workspaceRelativePath(workspaceFolderUriPrefixes)
        if (workspacePath != normalizedPath) {
            return workspacePath.toVirtualPathOrNull() ?: lspVirtualPathFromUri(
                pathOrUri,
                workspaceFolderUriPrefixes,
                collapseSyntheticWorkspaceRoot = shouldCollapseSyntheticWorkspaceRoot()
            )
        }
        if (pathOrUri.looksLikeUri()) {
            return lspVirtualPathFromUri(
                pathOrUri,
                workspaceFolderUriPrefixes,
                collapseSyntheticWorkspaceRoot = shouldCollapseSyntheticWorkspaceRoot()
            )
        }
        val clientPath = normalizedPath.syntheticWorkspaceRelativePath(shouldCollapseSyntheticWorkspaceRoot())
        return clientPath.toVirtualPathOrNull() ?: lspVirtualPathFromUri(
            pathOrUri,
            workspaceFolderUriPrefixes,
            collapseSyntheticWorkspaceRoot = shouldCollapseSyntheticWorkspaceRoot()
        )
    }

    private fun uriFor(path: VirtualPath): String = documentUris[path] ?: indexedWorkspaceUris[path] ?: lspFileUri(path)

    private fun shouldCollapseSyntheticWorkspaceRoot(): Boolean = workspaceFolders.isEmpty()

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
        // when the current highlight fully covers that token on the same line/start region.
        val coversIdentifier =
            compareHighlightPositions(location.range.start, idRange.start) <= 0 &&
                compareHighlightPositions(idRange.end, location.range.end) <= 0
        if (!coversIdentifier) {
            return location
        }
        val alreadyTight =
            location.range.start.line == idRange.start.line &&
                location.range.start.column == idRange.start.column &&
                location.range.end.line == idRange.end.line &&
                location.range.end.column == idRange.end.column
        if (alreadyTight) {
            return location
        }
        return WorkspaceLocation(location.path, idRange)
    }

    private fun documentHighlightKindFor(
        semanticFile: WorkspaceSemanticFile?,
        requestPath: VirtualPath,
        location: WorkspaceLocation
    ): DocumentHighlightKind {
        if (semanticFile == null || location.path != requestPath) {
            return DocumentHighlightKind.Read
        }
        val identifier = identifierAtHighlight(semanticFile, location.range)
            ?: return DocumentHighlightKind.Read
        return if (isDocumentHighlightWriteSite(identifier)) {
            DocumentHighlightKind.Write
        } else {
            DocumentHighlightKind.Read
        }
    }

    private fun identifierAtHighlight(
        semanticFile: WorkspaceSemanticFile,
        range: Range
    ): Identifier? {
        // Prefer an exact start match (already identifier-shaped ranges).
        semanticFile.identifiers.firstOrNull { identifier ->
            identifier.range.start.line == range.start.line &&
                identifier.range.start.column == range.start.column
        }?.let { return it }

        // Identifier fully contained by an over-extended highlight (declaration lines).
        semanticFile.identifiers.firstOrNull { identifier ->
            compareHighlightPositions(range.start, identifier.range.start) <= 0 &&
                compareHighlightPositions(identifier.range.end, range.end) <= 0 &&
                identifier.range.start.line == range.start.line
        }?.let { return it }

        // Fall back to the innermost identifier covering the highlight start.
        // Do not call WorkspaceSemanticFile.nodeAt: it is an internal top-level
        // extension in semantic.workspace and unresolved from jvmMain/lsp.
        return semanticFile.identifiers.lastOrNull { identifier ->
            rangeContainsHighlight(identifier.range, range.start)
        }
    }

    /**
     * Write sites for documentHighlight kinds:
     * - local / for-loop / parameter / function-name declarations
     * - assignment LHS identifiers (AST quirk: AssignmentStatement.init = LHS)
     * Pure uses (reads) stay Read.
     */
    private fun isDocumentHighlightWriteSite(identifier: Identifier): Boolean {
        val parent = runCatching { identifier.parent }.getOrNull() ?: return false
        return when (parent) {
            is LocalStatement -> parent.init.any { it === identifier }
            is AssignmentStatement -> parent.init.any { lhs ->
                lhs === identifier || assignmentLhsContainsIdentifier(lhs, identifier)
            }
            is FunctionDeclaration -> {
                parent.identifier === identifier ||
                    parent.params.any { it === identifier } ||
                    functionDeclarationNameIs(parent.identifier, identifier)
            }
            is ForGenericStatement -> parent.variables.any { it === identifier }
            is ForNumericStatement -> parent.variable === identifier
            is MemberExpression -> {
                // Member assignment LHS: t.field = ... where highlight is on `field`.
                parent.identifier === identifier && isAssignmentLhsExpression(parent)
            }
            else -> false
        }
    }

    private fun functionDeclarationNameIs(
        nameExpression: ExpressionNode?,
        identifier: Identifier
    ): Boolean {
        return when (nameExpression) {
            is Identifier -> nameExpression === identifier
            is MemberExpression -> nameExpression.identifier === identifier
            else -> false
        }
    }

    private fun assignmentLhsContainsIdentifier(
        lhs: ExpressionNode,
        identifier: Identifier
    ): Boolean {
        return when (lhs) {
            is Identifier -> lhs === identifier
            is MemberExpression -> lhs.identifier === identifier || assignmentLhsContainsIdentifier(lhs.base, identifier)
            else -> false
        }
    }

    private fun isAssignmentLhsExpression(expression: ExpressionNode): Boolean {
        var current: BaseASTNode? = expression
        while (current != null) {
            val parent = runCatching { current!!.parent }.getOrNull() ?: return false
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

    private fun rangeContainsHighlight(range: Range, position: Position): Boolean {
        return compareHighlightPositions(range.start, position) <= 0 &&
            compareHighlightPositions(position, range.end) <= 0
    }

    private fun compareHighlightPositions(left: Position, right: Position): Int {
        val lineComparison = left.line.compareTo(right.line)
        if (lineComparison != 0) {
            return lineComparison
        }
        return left.column.compareTo(right.column)
    }


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
                if (type == LuaTokenTypes.WHITE_SPACE || type == LuaTokenTypes.NEW_LINE) {
                    continue
                }
                if (type == LuaTokenTypes.BAD_CHARACTER) {
                    previousWasFunctionKeyword = false
                    pendingNameAsFunctionCall = false
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
                    previousWasFunctionKeyword = true
                    pendingNameAsFunctionCall = false
                } else if (type == LuaTokenTypes.NAME) {
                    previousWasFunctionKeyword = false
                    pendingNameAsFunctionCall = true
                } else if (type == LuaTokenTypes.LPAREN) {
                    // NAME ( … ) → treat the preceding NAME as a function/call when no
                    // stronger classification already applied.
                    if (pendingNameAsFunctionCall && absolute.isNotEmpty()) {
                        val last = absolute.last()
                        if (last.tokenType == TYPE_VARIABLE) {
                            absolute[absolute.lastIndex] = last.copy(tokenType = TYPE_FUNCTION)
                        }
                    }
                    previousWasFunctionKeyword = false
                    pendingNameAsFunctionCall = false
                } else if (type != LuaTokenTypes.DOT && type != LuaTokenTypes.COLON) {
                    previousWasFunctionKeyword = false
                    pendingNameAsFunctionCall = false
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
        try {
            val visitor = object : ASTVisitor<Unit> {
                override fun visitAttributeIdentifier(
                    identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier,
                    value: Unit
                ) = Unit

                override fun visitFunctionDeclaration(node: FunctionDeclaration, value: Unit) {
                    node.params.forEach { param ->
                        parameters += nameKey(param)
                    }
                    when (val name = node.identifier) {
                        is Identifier -> functions += nameKey(name)
                        is MemberExpression -> functions += nameKey(name.identifier)
                        else -> Unit
                    }
                    super.visitFunctionDeclaration(node, value)
                }
            }
            visitor.visitChunkNode(semanticFile.chunk, Unit)
        } catch (_: Exception) {
            // Soft-degrade: lexer-only classification remains available.
        }
        return NameClassification(parameters = parameters, functions = functions)
    }

    private fun nameKey(identifier: Identifier): NameKey {
        return NameKey(
            line = identifier.range.start.line,
            column = identifier.range.start.column,
            name = identifier.name
        )
    }

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

            LuaTokenTypes.NAME -> classifyNameToken(
                text = text,
                line1 = line1,
                column1 = column1,
                previousWasFunctionKeyword = previousWasFunctionKeyword,
                classification = classification
            )

            // Keywords (incl. literals true/false/nil and operators and/or/not/in).
            LuaTokenTypes.AND,
            LuaTokenTypes.BREAK,
            LuaTokenTypes.CASE,
            LuaTokenTypes.CONTINUE,
            LuaTokenTypes.DEFAULT,
            LuaTokenTypes.DO,
            LuaTokenTypes.ELSE,
            LuaTokenTypes.ELSEIF,
            LuaTokenTypes.END,
            LuaTokenTypes.FALSE,
            LuaTokenTypes.FOR,
            LuaTokenTypes.FUNCTION,
            LuaTokenTypes.GOTO,
            LuaTokenTypes.IF,
            LuaTokenTypes.IN,
            LuaTokenTypes.LAMBDA,
            LuaTokenTypes.LOCAL,
            LuaTokenTypes.NIL,
            LuaTokenTypes.NOT,
            LuaTokenTypes.OR,
            LuaTokenTypes.REPEAT,
            LuaTokenTypes.RETURN,
            LuaTokenTypes.SWITCH,
            LuaTokenTypes.THEN,
            LuaTokenTypes.TRUE,
            LuaTokenTypes.UNTIL,
            LuaTokenTypes.WHEN,
            LuaTokenTypes.WHILE -> TYPE_KEYWORD

            else -> null
        }
    }

    private fun classifyNameToken(
        text: String,
        line1: Int,
        column1: Int,
        previousWasFunctionKeyword: Boolean,
        classification: NameClassification
    ): Int {
        val key = NameKey(line = line1, column = column1, name = text)
        if (key in classification.parameters) {
            return TYPE_PARAMETER
        }
        if (key in classification.functions || previousWasFunctionKeyword) {
            return TYPE_FUNCTION
        }
        // Soft fallback: same-name parameter references elsewhere in the file.
        if (classification.parameters.any { it.name == text }) {
            return TYPE_PARAMETER
        }
        if (classification.functions.any { it.name == text }) {
            return TYPE_FUNCTION
        }
        return TYPE_VARIABLE
    }

    private fun appendAbsoluteTokenSpans(
        out: MutableList<AbsoluteSemanticToken>,
        startLine: Int,
        startChar: Int,
        text: String,
        tokenType: Int
    ) {
        if (text.isEmpty()) {
            return
        }
        var line = startLine
        var col = startChar
        var index = 0
        while (index < text.length) {
            var end = index
            while (end < text.length) {
                val ch = text[end]
                if (ch == '\n' || ch == '\r') {
                    break
                }
                end++
            }
            val length = end - index
            if (length > 0) {
                out += AbsoluteSemanticToken(
                    line = line,
                    startChar = col,
                    length = length,
                    tokenType = tokenType
                )
            }
            if (end >= text.length) {
                break
            }
            // Consume newline sequence (LF, CR, or CRLF).
            if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') {
                index = end + 2
            } else {
                index = end + 1
            }
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
            if (token.length < 0 || token.line < 0 || token.startChar < 0) {
                continue
            }
            val deltaLine = token.line - prevLine
            if (deltaLine < 0) {
                continue
            }
            val deltaStart = if (deltaLine == 0) {
                token.startChar - prevChar
            } else {
                token.startChar
            }
            if (deltaStart < 0) {
                continue
            }
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

    private fun parseChunkForFolding(source: String): ChunkNode? {
        return try {
            LuaParser().parseWithDiagnostics(source).chunk
        } catch (_: IllegalStateException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun documentLineCount(source: String): Int {
        if (source.isEmpty()) {
            return 0
        }
        return source.count { it == '\n' } + 1
    }

    /**
     * Walk the AST and emit a FoldingRange for every multi-line
     * [FunctionDeclaration] and [TableConstructorExpression]. Nested
     * function-in-table / table-in-function shapes remain foldable independently.
     */
    private fun collectFoldingRanges(chunk: ChunkNode, lineCount: Int?): List<FoldingRange> {
        val ranges = mutableListOf<FoldingRange>()
        val visitor = object : ASTVisitor<Unit> {
            override fun visitAttributeIdentifier(
                identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier,
                value: Unit
            ) = Unit

            override fun visitFunctionDeclaration(node: FunctionDeclaration, value: Unit) {
                maybeAddFold(node.range, ranges, lineCount)
                // Continue into body / nested tables & functions.
                super.visitFunctionDeclaration(node, value)
            }

            override fun visitTableConstructorExpression(node: TableConstructorExpression, value: Unit) {
                maybeAddFold(node.range, ranges, lineCount)
                super.visitTableConstructorExpression(node, value)
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
        // Parser positions are 1-based; LSP FoldingRange lines are 0-based.
        val startLine = (range.start.line - 1).coerceAtLeast(0)
        // End position is exclusive-ish in some recoveries; clamp to the last
        // character line and convert carefully so endLine stays in-bounds.
        var endLine = (range.end.line - 1).coerceAtLeast(0)
        // When the end column is 1 on a line after content, the fold still ends
        // on that line; only drop same-line spans.
        if (endLine < startLine) {
            return
        }
        if (endLine == startLine) {
            // Single-line function/table: not foldable for this provider.
            return
        }
        if (lineCount != null) {
            if (lineCount <= 0) {
                return
            }
            if (startLine >= lineCount) {
                return
            }
            endLine = endLine.coerceAtMost(lineCount - 1)
            if (endLine <= startLine) {
                return
            }
        }
        out += FoldingRange(startLine, endLine)
    }

    /**
     * Build an LSP SelectionRange chain for [position] by walking the AST parent
     * links from the innermost covering node. Only real AST ranges are used;
     * consecutive identical ranges are collapsed; each parent must fully contain
     * its child (LSP SelectionRange contract).
     */
    private fun selectionRangeAt(index: NodePositionIndex, position: Position): SelectionRange? {
        val leaf = index.findInnermost(position) ?: return null
        val chainRanges = mutableListOf<Range>()
        var current: BaseASTNode? = leaf
        var depth = 0
        while (current != null && depth < 256) {
            val range = current.range
            if (isOrderedAstRange(range)) {
                val previous = chainRanges.lastOrNull()
                if (previous == null) {
                    chainRanges += range
                } else if (!sameAstRange(previous, range) && selectionRangeContains(range, previous)) {
                    // Only expand when the parent strictly contains the prior span.
                    chainRanges += range
                }
                // Skip equal/non-containing parents; keep walking upward for a wider span.
            }
            current = runCatching { current!!.parent }.getOrNull()
            depth += 1
        }
        if (chainRanges.isEmpty()) {
            return null
        }
        // chainRanges[0] = leaf … chainRanges[last] = outermost.
        // Nest from outermost down so the returned root is the leaf selection.
        var nested: SelectionRange? = null
        for (i in chainRanges.lastIndex downTo 0) {
            nested = SelectionRange(chainRanges[i].toLspRange(), nested)
        }
        return nested
    }

    private fun isOrderedAstRange(range: Range): Boolean {
        val start = range.start
        val end = range.end
        if (start.line < 1 || start.column < 1 || end.line < 1 || end.column < 1) {
            return false
        }
        return end.line > start.line || (end.line == start.line && end.column >= start.column)
    }

    private fun sameAstRange(left: Range, right: Range): Boolean {
        return left.start.line == right.start.line &&
            left.start.column == right.start.column &&
            left.end.line == right.end.line &&
            left.end.column == right.end.column
    }

    /**
     * True when [outer] fully contains [inner] (inclusive). Used to enforce the
     * LSP SelectionRange parent/child containment contract while walking AST parents.
     */
    private fun selectionRangeContains(outer: Range, inner: Range): Boolean {
        val startOk =
            inner.start.line > outer.start.line ||
                (inner.start.line == outer.start.line && inner.start.column >= outer.start.column)
        val endOk =
            inner.end.line < outer.end.line ||
                (inner.end.line == outer.end.line && inner.end.column <= outer.end.column)
        return startOk && endOk
    }


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
        val source = openDocuments[path] ?: indexedWorkspaceFiles[path] ?: return emptyList()
        if (source.isEmpty()) {
            // Empty buffer: only a no-op is safe (tests reject non-origin ranges).
            return emptyList()
        }

        val tabSize = ((options?.tabSize ?: 4).takeIf { it > 0 } ?: 4).coerceIn(1, 16)
        val insertSpaces = options?.isInsertSpaces ?: true

        if (range == null) {
            val formatted = formatSourceText(source, tabSize, insertSpaces) ?: return emptyList()
            if (formatted == source) {
                return emptyList()
            }
            return listOf(TextEdit(fullDocumentLspRange(source), formatted))
        }

        // Validate / clamp requested range; invalid → empty edits (safety contract).
        val clamped = validateAndClampFormatRange(range, source) ?: return emptyList()
        val startOffset = offsetAt(source, clamped.start)
        val endOffset = offsetAt(source, clamped.end).coerceAtLeast(startOffset)
        if (startOffset == endOffset) {
            // Zero-width selection: no-op is always safe.
            return emptyList()
        }
        val selected = source.substring(startOffset, endOffset)
        // Prefer formatting the selected slice alone (table bodies, etc.). Falls back
        // to indent normalize when the fragment is not a complete parse unit.
        val selectedFormatted = formatSourceText(selected, tabSize, insertSpaces)
            ?: normalizeIndentNewlines(selected, tabSize, insertSpaces)
        if (selectedFormatted == selected) {
            return emptyList()
        }
        if (!isOrderedLspRange(clamped)) {
            return emptyList()
        }
        return listOf(TextEdit(clamped, selectedFormatted))
    }

    private fun formatSourceText(source: String, tabSize: Int, insertSpaces: Boolean): String? {
        if (source.isEmpty()) {
            return source
        }
        // Prefer AST2Lua when the buffer parses without recovery diagnostics.
        try {
            val parseResult = LuaParser().parseWithDiagnostics(source)
            if (parseResult.recoveryDiagnostics.isEmpty()) {
                val printer = AST2Lua().apply {
                    indentSize = if (insertSpaces) tabSize else tabSize.coerceAtLeast(1)
                }
                var printed = printer.asCode(parseResult.chunk)
                // AST2Lua often starts with a leading newline from visitBlock/statement.
                printed = printed.trimStart('\n', '\r')
                if (!insertSpaces) {
                    printed = convertLeadingSpacesToTabs(printed, tabSize)
                }
                // Preserve a trailing newline if the source had one.
                if (source.endsWith("\n") && !printed.endsWith("\n")) {
                    printed += "\n"
                } else if (!source.endsWith("\n") && printed.endsWith("\n")) {
                    printed = printed.trimEnd('\n', '\r')
                }
                // CRLF source → emit CRLF so editors don't thrash line endings.
                if (source.contains("\r\n")) {
                    printed = printed.replace("\r\n", "\n").replace("\n", "\r\n")
                }
                return printed
            }
        } catch (_: Exception) {
            // Fall through to indent normalize / empty degrade.
        }
        // Minimal indent/newline normalize for safety corpora (mixed tabs/spaces, etc.).
        return try {
            normalizeIndentNewlines(source, tabSize, insertSpaces)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeIndentNewlines(source: String, tabSize: Int, insertSpaces: Boolean): String {
        val usesCrlf = source.contains("\r\n")
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val indentUnit = if (insertSpaces) " ".repeat(tabSize.coerceAtLeast(1)) else "\t"
        val out = StringBuilder()
        var depth = 0
        for ((index, rawLine) in lines.withIndex()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                // Preserve empty lines as empty (no trailing spaces).
                if (index < lines.lastIndex || source.endsWith("\n") || source.endsWith("\r\n")) {
                    if (index > 0 || lines.size > 1) {
                        // only append newline separators between lines / trailing
                    }
                }
                if (index < lines.lastIndex) {
                    out.append(if (usesCrlf) "\r\n" else "\n")
                } else if (source.endsWith("\n") || source.endsWith("\r\n")) {
                    out.append(if (usesCrlf) "\r\n" else "\n")
                }
                continue
            }
            // Decrease depth for lines that close a block before indenting.
            val lower = trimmed.lowercase()
            val closesBefore =
                lower == "end" ||
                    lower.startsWith("end ") ||
                    lower.startsWith("end;") ||
                    lower == "else" ||
                    lower.startsWith("else ") ||
                    lower.startsWith("elseif") ||
                    lower == "until" ||
                    lower.startsWith("until ") ||
                    trimmed == "}" ||
                    trimmed.startsWith("},") ||
                    trimmed.startsWith("};")
            if (closesBefore) {
                depth = (depth - 1).coerceAtLeast(0)
            }
            out.append(indentUnit.repeat(depth))
            out.append(trimmed)
            if (index < lines.lastIndex || source.endsWith("\n") || source.endsWith("\r\n")) {
                out.append(if (usesCrlf) "\r\n" else "\n")
            }
            // Increase depth after openers.
            val opensAfter =
                lower.endsWith(" then") ||
                    lower.endsWith(" do") ||
                    lower.endsWith(" else") ||
                    lower == "else" ||
                    lower.startsWith("function") ||
                    lower.startsWith("local function") ||
                    lower.startsWith("repeat") ||
                    trimmed.endsWith("{") ||
                    trimmed.endsWith("({")
            // crude: count unmatched { on the line
            val openBraces = trimmed.count { it == '{' }
            val closeBraces = trimmed.count { it == '}' }
            var nextDepth = depth
            if (opensAfter || openBraces > closeBraces) {
                nextDepth += 1
            }
            // `else` / `elseif` re-open after the pre-close above.
            if (lower == "else" || lower.startsWith("elseif") || lower.startsWith("else ")) {
                nextDepth = depth + 1
            }
            depth = nextDepth.coerceAtLeast(0)
        }
        // If original had no trailing newline and we added one only because of loop, trim
        // when last line was non-empty and source lacked trailing newline.
        var result = out.toString()
        if (!source.endsWith("\n") && !source.endsWith("\r\n") && (result.endsWith("\n") || result.endsWith("\r\n"))) {
            result = result.trimEnd('\n', '\r')
        }
        return result
    }

    private fun convertLeadingSpacesToTabs(text: String, tabSize: Int): String {
        val size = tabSize.coerceAtLeast(1)
        val usesCrlf = text.contains("\r\n")
        val nl = if (usesCrlf) "\r\n" else "\n"
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return lines.joinToString(nl) { line ->
            var i = 0
            while (i < line.length && line[i] == ' ') {
                i++
            }
            if (i == 0) {
                line
            } else {
                val tabs = i / size
                val spaces = i % size
                "\t".repeat(tabs) + " ".repeat(spaces) + line.substring(i)
            }
        }
    }

    private fun fullDocumentLspRange(source: String): org.eclipse.lsp4j.Range {
        if (source.isEmpty()) {
            return org.eclipse.lsp4j.Range(
                org.eclipse.lsp4j.Position(0, 0),
                org.eclipse.lsp4j.Position(0, 0)
            )
        }
        var line = 0
        var col = 0
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            if (ch == '\r' && i + 1 < source.length && source[i + 1] == '\n') {
                line += 1
                col = 0
                i += 2
            } else if (ch == '\n' || ch == '\r') {
                line += 1
                col = 0
                i += 1
            } else {
                col += 1
                i += 1
            }
        }
        return org.eclipse.lsp4j.Range(
            org.eclipse.lsp4j.Position(0, 0),
            org.eclipse.lsp4j.Position(line, col)
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
        if (start.line < 0 || start.character < 0 || end.line < 0 || end.character < 0) {
            return null
        }
        // Inverted: end before start.
        if (end.line < start.line || (end.line == start.line && end.character < start.character)) {
            return null
        }
        val lineCount = documentLineCount(source)
        if (lineCount == 0) {
            return if (start.line == 0 && end.line == 0 && start.character == 0 && end.character == 0) {
                range
            } else {
                null
            }
        }
        // Beyond EOF (start past last line) → empty.
        if (start.line >= lineCount) {
            return null
        }
        return clampFormatRange(range, source)
    }

    private fun clampFormatRange(
        range: org.eclipse.lsp4j.Range,
        source: String
    ): org.eclipse.lsp4j.Range? {
        val start = range.start ?: return null
        val end = range.end ?: return null
        if (start.line < 0 || start.character < 0 || end.line < 0 || end.character < 0) {
            return null
        }
        if (end.line < start.line || (end.line == start.line && end.character < start.character)) {
            return null
        }
        val lineCount = documentLineCount(source)
        if (lineCount == 0) {
            return org.eclipse.lsp4j.Range(
                org.eclipse.lsp4j.Position(0, 0),
                org.eclipse.lsp4j.Position(0, 0)
            )
        }
        if (start.line >= lineCount) {
            return null
        }
        val startLine = start.line.coerceIn(0, lineCount - 1)
        val endLine = end.line.coerceIn(0, lineCount) // exclusive EOF line allowed
        val startChar = start.character.coerceAtLeast(0)
        val endChar = end.character.coerceAtLeast(0)
        val startClamped = org.eclipse.lsp4j.Position(
            startLine,
            startChar.coerceAtMost(lineLengthAt(source, startLine))
        )
        val endClamped = if (endLine >= lineCount) {
            org.eclipse.lsp4j.Position(lineCount, 0)
        } else {
            org.eclipse.lsp4j.Position(
                endLine,
                endChar.coerceAtMost(lineLengthAt(source, endLine))
            )
        }
        if (endClamped.line < startClamped.line ||
            (endClamped.line == startClamped.line && endClamped.character < startClamped.character)
        ) {
            return null
        }
        return org.eclipse.lsp4j.Range(startClamped, endClamped)
    }

    private fun lineLengthAt(source: String, line: Int): Int {
        if (source.isEmpty() || line < 0) {
            return 0
        }
        var currentLine = 0
        var index = 0
        var lineStart = 0
        while (index < source.length) {
            val ch = source[index]
            if (ch == '\n') {
                if (currentLine == line) {
                    // Exclude trailing CR on CRLF.
                    var end = index
                    if (end > lineStart && source[end - 1] == '\r') {
                        end -= 1
                    }
                    return end - lineStart
                }
                currentLine += 1
                lineStart = index + 1
            }
            index += 1
        }
        if (currentLine == line) {
            return source.length - lineStart
        }
        return 0
    }

    private fun isOrderedLspRange(range: org.eclipse.lsp4j.Range): Boolean {
        val start = range.start ?: return false
        val end = range.end ?: return false
        if (start.line < 0 || start.character < 0 || end.line < 0 || end.character < 0) {
            return false
        }
        return end.line > start.line || (end.line == start.line && end.character >= start.character)
    }

    private fun buildHoverContent(name: String?, detail: String?, typeDisplayName: String?): String? {
        val parts = buildList {
            name?.let { add("**$it**") }
            detail?.takeIf { it.isNotBlank() && it != typeDisplayName }?.let(::add)
            typeDisplayName?.takeIf { it.isNotBlank() }?.let { add("Type: `$it`") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * Prefer non-weak FunctionType/ClassType/MODULE displays for LSP hover markup.
     * Bare unknown/any loses to any richer candidate (TASK-601).
     */
    private fun preferredLspHoverTypeDisplay(
        primary: String?,
        secondary: String?,
        tertiary: String?
    ): String? {
        fun isWeak(value: String?): Boolean {
            if (value.isNullOrBlank()) return true
            return value == "unknown" || value == "any"
        }
        val candidates = listOf(primary, secondary, tertiary).filterNot { it.isNullOrBlank() }
        candidates.firstOrNull { value ->
            !isWeak(value) && (value!!.contains("fun(") || value.contains("fun<"))
        }?.let { return it }
        candidates.firstOrNull { value ->
            !isWeak(value) && value!!.startsWith("Array<")
        }?.let { return it }
        candidates.firstOrNull { value -> !isWeak(value) }?.let { return it }
        return candidates.firstOrNull()
    }

    /**
     * Soft code-action collection. Empty / malformed / non-quickfix filters yield [].
     * Only emits actions when a deterministic edit can be built; otherwise empty.
     */
    // -------------------------------------------------------------------------
    // TASK-520 — Call hierarchy (same-file local functions)
    // -------------------------------------------------------------------------

    private fun resolveCallHierarchyItem(
        document: TextDocumentIdentifier?,
        lspPosition: org.eclipse.lsp4j.Position?
    ): CallHierarchyItem? {
        if (document == null || lspPosition == null) {
            return null
        }
        if (lspPosition.line < 0 || lspPosition.character < 0) {
            return null
        }
        val path = pathOf(document)
        val source = openDocuments[path] ?: indexedWorkspaceFiles[path] ?: return null
        if (source.isEmpty()) {
            return null
        }
        val parserPosition = lspPosition.toParserPosition()
        val semanticFile = snapshot.files[path]?.semanticFile
        val chunk = semanticFile?.chunk ?: parseChunkForFolding(source) ?: return null
        val uri = document.uri ?: uriFor(path)

        // Prefer AST identifier covering the caret.
        val identifier = callHierarchyIdentifierAt(semanticFile, chunk, parserPosition)
            ?: return null
        if (!isValidLuaIdentifier(identifier.name) || identifier.name in LUA_KEYWORDS) {
            return null
        }

        val functionDecl = resolveLocalFunctionDeclaration(chunk, semanticFile, path, identifier)
            ?: return null
        return callHierarchyItemForFunction(functionDecl, uri)
    }

    private fun callHierarchyIdentifierAt(
        semanticFile: WorkspaceSemanticFile?,
        chunk: ChunkNode,
        position: Position
    ): Identifier? {
        if (semanticFile != null) {
            semanticFile.identifiers.firstOrNull { identifier ->
                identifier.range.start.line == position.line &&
                    identifier.range.start.column == position.column
            }?.let { return it }

            semanticFile.identifiers.lastOrNull { identifier ->
                rangeContainsHighlight(identifier.range, position)
            }?.let { return it }

            // nodeAt is package-internal on WorkspaceSemanticFile; use identifiers + AST index.
            semanticFile.identifiers.firstOrNull { id ->
                rangeContainsHighlight(id.range, position)
            }?.let { return it }
        }

        // Fallback: walk chunk identifiers via NodePositionIndex when semantic file is thin.
        val index = NodePositionIndex(chunk)
        return when (val node = index.findInnermost(position)) {
            is Identifier -> node
            is MemberExpression -> node.identifier.takeIf {
                rangeContainsHighlight(it.range, position)
            }
            else -> null
        }
    }

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
        val parent = runCatching { identifier.parent }.getOrNull()
        if (parent is FunctionDeclaration &&
            parent.isLocal &&
            functionDeclarationNameIs(parent.identifier, identifier)
        ) {
            return parent
        }

        // Local assigned function: `local add = function(...)` — LocalStatement.init = names,
        // LocalStatement.variables = RHS (AST quirk).
        if (parent is LocalStatement && parent.init.any { it === identifier }) {
            val index = parent.init.indexOfFirst { it === identifier }
            val rhs = parent.variables.getOrNull(index)
            if (rhs is FunctionDeclaration) {
                return rhs
            }
        }

        // Call-site / use: resolve via definition/goto to the declaration, then map to AST.
        val defLocations = try {
            val defs = queries.gotoDefinition(path, identifier.range.start)
            if (defs.isNotEmpty()) defs else queries.declaration(path, identifier.range.start)
        } catch (_: Exception) {
            emptyList()
        }.filter { it.path == path }

        for (location in defLocations) {
            val declId = (
                if (semanticFile != null) {
                    identifierAtHighlight(semanticFile, location.range)
                } else {
                    null
                }
            ) ?: findIdentifierAtRange(chunk, location.range)
            if (declId != null) {
                val declParent = runCatching { declId.parent }.getOrNull()
                if (declParent is FunctionDeclaration &&
                    declParent.isLocal &&
                    functionDeclarationNameIs(declParent.identifier, declId)
                ) {
                    return declParent
                }
                if (declParent is LocalStatement && declParent.init.any { it === declId }) {
                    val index = declParent.init.indexOfFirst { it === declId }
                    val rhs = declParent.variables.getOrNull(index)
                    if (rhs is FunctionDeclaration) {
                        return rhs
                    }
                }
            }
        }

        // Fallback: scan local functions by name and match via same-file references/highlights.
        val candidates = collectLocalFunctionDeclarations(chunk)
        if (candidates.isEmpty()) {
            return null
        }
        val sameFileRefs = try {
            queries.documentHighlights(path, identifier.range.start)
        } catch (_: Exception) {
            emptyList()
        }.filter { it.path == path }.ifEmpty {
            try {
                queries.references(path, identifier.range.start)
            } catch (_: Exception) {
                emptyList()
            }.filter { it.path == path }
        }

        for (candidate in candidates) {
            val nameId = localFunctionNameIdentifier(candidate) ?: continue
            if (nameId.name != identifier.name) {
                continue
            }
            // Exact name-node identity
            if (nameId === identifier) {
                return candidate
            }
            // Reference range covers this candidate's name or the caret identifier.
            val nameRange = nameId.range
            val matchesRef = sameFileRefs.any { location ->
                rangesOverlapParser(location.range, nameRange) ||
                    rangesOverlapParser(location.range, identifier.range)
            }
            if (matchesRef || sameFileRefs.isEmpty() && nameId.name == identifier.name) {
                // Prefer declaration whose name range is closest to a definition hit.
                if (defLocations.any { rangesOverlapParser(it.range, nameRange) }) {
                    return candidate
                }
            }
        }

        // Last resort: if caret identifier name uniquely matches one local function.
        val byName = candidates.filter { localFunctionNameIdentifier(it)?.name == identifier.name }
        return byName.singleOrNull()
    }

    private fun findIdentifierAtRange(chunk: ChunkNode, range: Range): Identifier? {
        val index = NodePositionIndex(chunk)
        return when (val node = index.findInnermost(range.start)) {
            is Identifier -> node
            is MemberExpression -> node.identifier
            else -> {
                // Walk all identifiers via visitor.
                var found: Identifier? = null
                val visitor = object : ASTVisitor<Unit> {
                    override fun visitAttributeIdentifier(
                        identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier,
                        value: Unit
                    ) = Unit

                    override fun visitIdentifier(node: Identifier, value: Unit) {
                        if (found == null &&
                            node.range.start.line == range.start.line &&
                            node.range.start.column == range.start.column
                        ) {
                            found = node
                        }
                        super.visitIdentifier(node, value)
                    }
                }
                visitor.visitChunkNode(chunk, Unit)
                found
            }
        }
    }

    private fun collectLocalFunctionDeclarations(chunk: ChunkNode): List<FunctionDeclaration> {
        val out = mutableListOf<FunctionDeclaration>()
        val visitor = object : ASTVisitor<Unit> {
            override fun visitAttributeIdentifier(
                identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier,
                value: Unit
            ) = Unit

            override fun visitFunctionDeclaration(node: FunctionDeclaration, value: Unit) {
                if (isLocalFunctionDeclaration(node)) {
                    out += node
                }
                super.visitFunctionDeclaration(node, value)
            }
        }
        visitor.visitChunkNode(chunk, Unit)
        return out
    }

    private fun isLocalFunctionDeclaration(node: FunctionDeclaration): Boolean {
        if (node.isLocal && localFunctionNameIdentifier(node) != null) {
            return true
        }
        // Anonymous function expression bound to a local name: local f = function()
        val parent = runCatching { node.parent }.getOrNull()
        if (parent is LocalStatement) {
            val idx = parent.variables.indexOfFirst { it === node }
            if (idx >= 0 && parent.init.getOrNull(idx) is Identifier) {
                return true
            }
        }
        return false
    }

    private fun localFunctionNameIdentifier(node: FunctionDeclaration): Identifier? {
        when (val name = node.identifier) {
            is Identifier -> return name
            is MemberExpression -> return name.identifier
        }
        // local name = function(...) — name lives on LocalStatement.init (names),
        // FunctionDeclaration is in LocalStatement.variables (RHS).
        val parent = runCatching { node.parent }.getOrNull()
        if (parent is LocalStatement) {
            val idx = parent.variables.indexOfFirst { it === node }
            val name = parent.init.getOrNull(idx)
            if (name is Identifier) {
                return name
            }
        }
        return null
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
        val kind = when {
            functionDecl.identifier is MemberExpression -> SymbolKind.Method
            else -> SymbolKind.Function
        }
        return CallHierarchyItem(name, kind, uri, range, selection)
    }

    private fun containsRangeLsp(
        outer: org.eclipse.lsp4j.Range,
        inner: org.eclipse.lsp4j.Range
    ): Boolean {
        val startsOk =
            inner.start.line > outer.start.line ||
                (inner.start.line == outer.start.line &&
                    inner.start.character >= outer.start.character)
        val endsOk =
            inner.end.line < outer.end.line ||
                (inner.end.line == outer.end.line &&
                    inner.end.character <= outer.end.character)
        return startsOk && endsOk
    }

    private fun rangesOverlapParser(left: Range, right: Range): Boolean {
        val leftStartsBeforeRightEnds =
            left.start.line < right.end.line ||
                (left.start.line == right.end.line && left.start.column <= right.end.column)
        val rightStartsBeforeLeftEnds =
            right.start.line < left.end.line ||
                (right.start.line == left.end.line && right.start.column <= left.end.column)
        return leftStartsBeforeRightEnds && rightStartsBeforeLeftEnds
    }

    private fun collectIncomingCalls(item: CallHierarchyItem): List<CallHierarchyIncomingCall> {
        val uri = item.uri ?: return emptyList()
        val path = pathOf(uri)
        val source = openDocuments[path] ?: indexedWorkspaceFiles[path] ?: return emptyList()
        val semanticFile = snapshot.files[path]?.semanticFile
        val chunk = semanticFile?.chunk ?: parseChunkForFolding(source) ?: return emptyList()

        val selectionStart = item.selectionRange?.start ?: item.range?.start ?: return emptyList()
        val parserPos = selectionStart.toParserPosition()
        val targetDecl = resolveLocalFunctionFromItem(chunk, semanticFile, path, item)
            ?: return emptyList()
        val targetName = localFunctionNameIdentifier(targetDecl) ?: return emptyList()

        // Use sites from references (exclude declaration) + document highlights as fallback.
        val useSites = try {
            queries.references(path, targetName.range.start)
        } catch (_: Exception) {
            emptyList()
        }.filter { it.path == path }

        val highlightSites = if (useSites.isEmpty()) {
            try {
                queries.documentHighlights(path, targetName.range.start)
            } catch (_: Exception) {
                emptyList()
            }.filter { it.path == path }
        } else {
            emptyList()
        }

        val sites = (useSites + highlightSites).distinctBy {
            "${it.range.start.line}:${it.range.start.column}-${it.range.end.line}:${it.range.end.column}"
        }

        // Group call sites by enclosing local function caller.
        data class IncomingAgg(
            val caller: FunctionDeclaration,
            val fromRanges: MutableList<org.eclipse.lsp4j.Range>
        )
        val byCaller = linkedMapOf<FunctionDeclaration, IncomingAgg>()

        for (site in sites) {
            // Skip the declaration name itself.
            if (rangesOverlapParser(site.range, targetName.range) &&
                site.range.start.line == targetName.range.start.line &&
                site.range.start.column == targetName.range.start.column
            ) {
                continue
            }
            val siteId = (
                if (semanticFile != null) {
                    identifierAtHighlight(semanticFile, site.range)
                } else {
                    null
                }
            ) ?: findIdentifierAtRange(chunk, site.range)
                ?: continue
            // Only treat as a call site when parent chain includes CallExpression with this base.
            if (!isCallSiteIdentifier(siteId)) {
                // Still allow plain references that sit inside a caller's body (soft).
                // Prefer true call sites.
            }
            val caller = enclosingLocalFunctionAllowSelf(siteId) ?: continue
            val agg = byCaller.getOrPut(caller) {
                IncomingAgg(caller, mutableListOf())
            }
            val fromRange = site.range.toLspRange()
            if (agg.fromRanges.none { sameLspRange(it, fromRange) }) {
                agg.fromRanges += fromRange
            }
        }

        // AST walk fallback: find CallExpressions whose base is the target name.
        if (byCaller.isEmpty()) {
            val visitor = object : ASTVisitor<Unit> {
                override fun visitAttributeIdentifier(
                    identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier,
                    value: Unit
                ) = Unit

                override fun visitCallExpression(node: CallExpression, value: Unit) {
                    val baseName = callBaseIdentifier(node)
                    if (baseName != null && baseName.name == targetName.name) {
                        val caller = enclosingLocalFunction(node, exclude = null)
                        if (caller != null) {
                            val agg = byCaller.getOrPut(caller) {
                                IncomingAgg(caller, mutableListOf())
                            }
                            val fromRange = baseName.range.toLspRange()
                            if (agg.fromRanges.none { sameLspRange(it, fromRange) }) {
                                agg.fromRanges += fromRange
                            }
                        }
                    }
                    super.visitCallExpression(node, value)
                }
            }
            visitor.visitChunkNode(chunk, Unit)
        }

        return byCaller.values.mapNotNull { agg ->
            val fromItem = callHierarchyItemForFunction(agg.caller, uri) ?: return@mapNotNull null
            CallHierarchyIncomingCall(fromItem, agg.fromRanges)
        }
    }

    private fun collectOutgoingCalls(item: CallHierarchyItem): List<CallHierarchyOutgoingCall> {
        val uri = item.uri ?: return emptyList()
        val path = pathOf(uri)
        val source = openDocuments[path] ?: indexedWorkspaceFiles[path] ?: return emptyList()
        val semanticFile = snapshot.files[path]?.semanticFile
        val chunk = semanticFile?.chunk ?: parseChunkForFolding(source) ?: return emptyList()

        val rootDecl = resolveLocalFunctionFromItem(chunk, semanticFile, path, item)
            ?: return emptyList()
        val body = rootDecl.body ?: return emptyList()

        data class OutgoingAgg(
            val callee: FunctionDeclaration,
            val fromRanges: MutableList<org.eclipse.lsp4j.Range>
        )
        val byCallee = linkedMapOf<FunctionDeclaration, OutgoingAgg>()

        val visitor = object : ASTVisitor<Unit> {
            override fun visitAttributeIdentifier(
                identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier,
                value: Unit
            ) = Unit

            override fun visitCallExpression(node: CallExpression, value: Unit) {
                val baseName = callBaseIdentifier(node)
                if (baseName != null) {
                    val callee = resolveLocalFunctionDeclaration(chunk, semanticFile, path, baseName)
                    if (callee != null) {
                        // Prefer calls whose nearest enclosing local function is rootDecl.
                        val enclosing = enclosingLocalFunctionAllowSelf(node)
                        if (enclosing === rootDecl) {
                            val agg = byCallee.getOrPut(callee) {
                                OutgoingAgg(callee, mutableListOf())
                            }
                            val fromRange = baseName.range.toLspRange()
                            if (agg.fromRanges.none { sameLspRange(it, fromRange) }) {
                                agg.fromRanges += fromRange
                            }
                        }
                    }
                }
                super.visitCallExpression(node, value)
            }
        }
        // Visit only the root function body to avoid other top-level calls.
        try {
            body.accept(visitor, Unit)
        } catch (_: Exception) {
            visitor.visitChunkNode(chunk, Unit)
        }

        return byCallee.values.mapNotNull { agg ->
            val toItem = callHierarchyItemForFunction(agg.callee, uri) ?: return@mapNotNull null
            CallHierarchyOutgoingCall(toItem, agg.fromRanges)
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
        val candidates = collectLocalFunctionDeclarations(chunk).filter {
            localFunctionNameIdentifier(it)?.name == name
        }
        if (candidates.isEmpty()) {
            return null
        }
        if (candidates.size == 1) {
            return candidates.first()
        }
        return candidates.firstOrNull { decl ->
            val nameId = localFunctionNameIdentifier(decl) ?: return@firstOrNull false
            val sel = nameId.range.toLspRange()
            sameLspRange(sel, selection) || containsRangeLsp(selection, sel) || containsRangeLsp(sel, selection)
        } ?: candidates.firstOrNull()
    }

    private fun callBaseIdentifier(call: CallExpression): Identifier? {
        return when (val base = call.base) {
            is Identifier -> base
            is MemberExpression -> base.identifier
            else -> null
        }
    }

    private fun isCallSiteIdentifier(identifier: Identifier): Boolean {
        var current: BaseASTNode? = identifier
        var depth = 0
        while (current != null && depth < 16) {
            val parent = runCatching { current!!.parent }.getOrNull() ?: return false
            if (parent is CallExpression) {
                val base = parent.base
                return base === current ||
                    (base is MemberExpression && base.identifier === identifier)
            }
            if (parent is MemberExpression && parent.identifier === current) {
                current = parent
                depth += 1
                continue
            }
            return false
        }
        return false
    }

    private fun enclosingLocalFunction(
        node: BaseASTNode,
        exclude: FunctionDeclaration?
    ): FunctionDeclaration? {
        var current: BaseASTNode? = node
        var depth = 0
        while (current != null && depth < 256) {
            if (current is FunctionDeclaration && isLocalFunctionDeclaration(current)) {
                if (exclude == null || current !== exclude) {
                    // When walking from a call site, the first enclosing local function is the caller.
                    return current
                }
                // If exclude matches (e.g. we started at the declaration name), keep walking.
            }
            current = runCatching { current!!.parent }.getOrNull()
            depth += 1
        }
        // Retry without exclude if we only hit the excluded decl.
        if (exclude != null) {
            return enclosingLocalFunctionAllowSelf(node)
        }
        return null
    }

    private fun enclosingLocalFunctionAllowSelf(node: BaseASTNode): FunctionDeclaration? {
        var current: BaseASTNode? = node
        var depth = 0
        while (current != null && depth < 256) {
            if (current is FunctionDeclaration && isLocalFunctionDeclaration(current)) {
                return current
            }
            current = runCatching { current!!.parent }.getOrNull()
            depth += 1
        }
        return null
    }

    private fun isNodeInside(node: BaseASTNode, container: BaseASTNode): Boolean {
        var current: BaseASTNode? = node
        var depth = 0
        while (current != null && depth < 256) {
            if (current === container) {
                return true
            }
            current = runCatching { current!!.parent }.getOrNull()
            depth += 1
        }
        return false
    }

    private fun sameLspRange(left: org.eclipse.lsp4j.Range, right: org.eclipse.lsp4j.Range): Boolean {
        return left.start.line == right.start.line &&
            left.start.character == right.start.character &&
            left.end.line == right.end.line &&
            left.end.character == right.end.character
    }

    private fun collectCodeActions(params: CodeActionParams): List<Either<Command, CodeAction>> {
        val only = params.context?.only
        if (!only.isNullOrEmpty() && !only.any { kind -> acceptsCodeActionKind(kind) }) {
            // Client asked only for kinds we do not provide; soft empty.
            return emptyList()
        }

        // Selection range is accepted even when inverted / OOB — empty-fix path does
        // not index into source with client positions.
        val diagnostics = params.context?.diagnostics.orEmpty()
        if (diagnostics.isEmpty()) {
            return emptyList()
        }

        // No deterministic auto-fix yet for parse/type diagnostics; empty list is the
        // safe product contract (well-formed zero actions, no UnsupportedOperationException).
        return emptyList()
    }

    private fun acceptsCodeActionKind(kind: String?): Boolean {
        if (kind.isNullOrBlank()) {
            return true
        }
        return kind == CodeActionKind.Empty ||
            kind == CodeActionKind.QuickFix ||
            kind.startsWith("${CodeActionKind.QuickFix}.")
    }

    /**
     * Collect Parameter-kind inlay hints for call arguments intersecting the request
     * range. Formal names come from SignatureHelp (same ranking / formals as caret
     * signature help) — no invented overload selection beyond that surface.
     */
    private fun collectParameterInlayHints(params: InlayHintParams): List<InlayHint> {
        val path = pathOf(params.textDocument)
        val source = openDocuments[path] ?: indexedWorkspaceFiles[path]
        val semanticFile = snapshot.files[path]?.semanticFile
        val chunk = semanticFile?.chunk ?: source?.let { parseChunkForFolding(it) }
            ?: return emptyList()

        val requestRange = params.range
        val calls = mutableListOf<CallExpression>()
        val visitor = object : ASTVisitor<Unit> {
            override fun visitAttributeIdentifier(
                identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier,
                value: Unit
            ) = Unit

            override fun visitCallExpression(node: CallExpression, value: Unit) {
                // String/Table call subclasses still visit through here after their own hooks.
                if (node !is StringCallExpression && node !is TableCallExpression) {
                    if (callIntersectsRequestRange(node, requestRange)) {
                        calls += node
                    }
                }
                super.visitCallExpression(node, value)
            }

            override fun visitStringCallExpression(node: StringCallExpression, value: Unit) {
                if (callIntersectsRequestRange(node, requestRange)) {
                    calls += node
                }
                super.visitStringCallExpression(node, value)
            }

            override fun visitTableCallExpression(node: TableCallExpression, value: Unit) {
                if (callIntersectsRequestRange(node, requestRange)) {
                    calls += node
                }
                super.visitTableCallExpression(node, value)
            }
        }
        visitor.visitChunkNode(chunk, Unit)

        if (calls.isEmpty()) {
            return emptyList()
        }

        val hints = mutableListOf<InlayHint>()
        for (call in calls) {
            hints += parameterInlayHintsForCall(path, call, requestRange)
        }
        return hints
    }

    private fun parameterInlayHintsForCall(
        path: VirtualPath,
        call: CallExpression,
        requestRange: org.eclipse.lsp4j.Range?
    ): List<InlayHint> {
        val arguments = inlayCallArguments(call)
        if (arguments.isEmpty()) {
            return emptyList()
        }

        // Probe signature help at the first argument start so we reuse the same
        // formal metadata / activeSignature ranking as SignatureHelpProvider.
        val help = queries.signatureHelp(path, arguments.first().range.start) ?: return emptyList()
        if (help.signatures.isEmpty()) {
            return emptyList()
        }
        val signature = help.signatures.getOrNull(help.activeSignature.coerceAtLeast(0))
            ?: help.signatures.first()
        val formalNames = signature.parameters.mapNotNull { formalNameFromParameterLabel(it.label) }
        if (formalNames.isEmpty()) {
            return emptyList()
        }

        // Colon method calls: signature formals may include leading `self` while the
        // call site only has explicit args (value, label, ...). Skip the implicit self.
        val isColonCall = call.base is MemberExpression && (call.base as MemberExpression).indexer == ":"
        val formalOffset = if (isColonCall && formalNames.firstOrNull() == "self") 1 else 0

        val hints = mutableListOf<InlayHint>()
        arguments.forEachIndexed { index, argument ->
            val formalIndex = formalOffset + index
            val name = formalNames.getOrNull(formalIndex) ?: return@forEachIndexed
            if (name.isBlank() || name == "...") {
                return@forEachIndexed
            }
            val position = argument.range.start.toLspPosition()
            if (requestRange != null && !lspPositionInRange(position, requestRange)) {
                return@forEachIndexed
            }
            if (position.line < 0 || position.character < 0) {
                return@forEachIndexed
            }
            hints += InlayHint(position, Either.forLeft("$name:")).apply {
                kind = InlayHintKind.Parameter
                paddingRight = true
            }
        }
        return hints
    }

    private fun inlayCallArguments(call: CallExpression): List<ExpressionNode> {
        val stringCall = call.base as? StringCallExpression
        return buildList {
            if (stringCall != null) {
                addAll(stringCall.arguments)
            }
            addAll(call.arguments)
        }
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
        // `name: type`, `name?: type`, `...: type` (skip pure vararg marker)
        val colon = trimmed.indexOf(':')
        if (colon > 0) {
            val namePart = trimmed.substring(0, colon).trim().trimEnd('?')
            if (namePart.isNotEmpty() && namePart != "...") {
                return namePart
            }
            return null
        }
        // Bare identifier formal (no type annotation in the label).
        if (trimmed.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            return trimmed
        }
        return null
    }

    private fun callIntersectsRequestRange(
        call: CallExpression,
        requestRange: org.eclipse.lsp4j.Range?
    ): Boolean {
        if (requestRange == null) {
            return true
        }
        val callLsp = call.range.toLspRange()
        return lspRangesIntersect(callLsp, requestRange)
    }

    private fun lspRangesIntersect(
        left: org.eclipse.lsp4j.Range,
        right: org.eclipse.lsp4j.Range
    ): Boolean {
        // Empty / inverted ranges: still accept if either endpoint is inside the other.
        if (lspPositionInRange(left.start, right) || lspPositionInRange(left.end, right)) {
            return true
        }
        if (lspPositionInRange(right.start, left) || lspPositionInRange(right.end, left)) {
            return true
        }
        return false
    }

    private fun lspPositionInRange(
        position: org.eclipse.lsp4j.Position,
        range: org.eclipse.lsp4j.Range
    ): Boolean {
        val start = range.start
        val end = range.end
        val afterStart =
            position.line > start.line ||
                (position.line == start.line && position.character >= start.character)
        val beforeEnd =
            position.line < end.line ||
                (position.line == end.line && position.character <= end.character)
        return afterStart && beforeEnd
    }
}

// TASK-541 legend indices — keep stable; clients map by name from the legend list.
private val SEMANTIC_TOKEN_TYPES: List<String> = listOf(
    SemanticTokenTypes.Keyword,
    SemanticTokenTypes.Function,
    SemanticTokenTypes.Variable,
    SemanticTokenTypes.Parameter,
    SemanticTokenTypes.String,
    SemanticTokenTypes.Number,
    SemanticTokenTypes.Comment
)

private val SEMANTIC_TOKEN_MODIFIERS: List<String> = listOf(
    SemanticTokenModifiers.Declaration,
    SemanticTokenModifiers.Readonly
)

private val SEMANTIC_TOKENS_LEGEND = SemanticTokensLegend(
    SEMANTIC_TOKEN_TYPES,
    SEMANTIC_TOKEN_MODIFIERS
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

private fun org.eclipse.lsp4j.Position.toParserPosition(): Position {
    return Position(line + 1, character + 1)
}

private fun Range.toLspRange(): org.eclipse.lsp4j.Range {
    return org.eclipse.lsp4j.Range(start.toLspPosition(), end.toLspPosition())
}

private fun Position.toLspPosition(): org.eclipse.lsp4j.Position {
    return org.eclipse.lsp4j.Position(line - 1, column - 1)
}

private fun DiagnosticSeverity.toLspSeverity(): org.eclipse.lsp4j.DiagnosticSeverity {
    return when (this) {
        DiagnosticSeverity.ERROR -> org.eclipse.lsp4j.DiagnosticSeverity.Error
        DiagnosticSeverity.WARNING -> org.eclipse.lsp4j.DiagnosticSeverity.Warning
        DiagnosticSeverity.INFO -> org.eclipse.lsp4j.DiagnosticSeverity.Information
    }
}

private fun CompletionItemKind.toLspKind(): org.eclipse.lsp4j.CompletionItemKind {
    return when (this) {
        CompletionItemKind.TEXT -> org.eclipse.lsp4j.CompletionItemKind.Text
        CompletionItemKind.VARIABLE -> org.eclipse.lsp4j.CompletionItemKind.Variable
        CompletionItemKind.PARAMETER -> org.eclipse.lsp4j.CompletionItemKind.Variable
        CompletionItemKind.FUNCTION -> org.eclipse.lsp4j.CompletionItemKind.Function
        CompletionItemKind.METHOD -> org.eclipse.lsp4j.CompletionItemKind.Method
        CompletionItemKind.FIELD -> org.eclipse.lsp4j.CompletionItemKind.Field
        CompletionItemKind.CLASS -> org.eclipse.lsp4j.CompletionItemKind.Class
        CompletionItemKind.TYPE_ALIAS -> org.eclipse.lsp4j.CompletionItemKind.TypeParameter
        CompletionItemKind.MODULE -> org.eclipse.lsp4j.CompletionItemKind.Module
        CompletionItemKind.KEYWORD -> org.eclipse.lsp4j.CompletionItemKind.Keyword
        CompletionItemKind.SNIPPET -> org.eclipse.lsp4j.CompletionItemKind.Snippet
    }
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
    val parsed = try {
        URI(uri)
    } catch (_: IllegalArgumentException) {
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

private fun fallbackFileUriPath(uri: String): String? {
    val trimmed = uri.trim()
    if (!trimmed.regionMatches(0, "file:", 0, 5, ignoreCase = true)) {
        return trimmed.takeIf { it.isNotBlank() }
    }
    var rest = trimmed.substring(5)
    // file:///path, file://localhost/path, file:/path
    rest = when {
        rest.startsWith("///") -> rest.substring(2) // keep one leading '/'
        rest.startsWith("//") -> {
            val authorityAndPath = rest.substring(2)
            val slash = authorityAndPath.indexOf('/')
            if (slash >= 0) authorityAndPath.substring(slash) else authorityAndPath
        }
        rest.startsWith("/") -> rest
        else -> "/$rest"
    }
    return normalizeFileSystemPathFromUriPath(rest.replace('\\', '/'))
}

private fun URI.fileWorkspacePath(originalUri: String): String {
    val rawPath = when {
        !path.isNullOrBlank() -> path
        !schemeSpecificPart.isNullOrBlank() -> {
            val ssp = schemeSpecificPart
            when {
                ssp.startsWith("///") -> ssp.substring(2) // "/..."
                ssp.startsWith("//") -> {
                    val authorityAndPath = ssp.substring(2)
                    val slash = authorityAndPath.indexOf('/')
                    if (slash >= 0) authorityAndPath.substring(slash) else authorityAndPath
                }
                else -> ssp
            }
        }
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
    // Windows drive encoded as "/C:/..." or "/c|/..." style from file URIs.
    if (uriPath.length >= 3 &&
        uriPath[0] == '/' &&
        uriPath[1].isLetter() &&
        (uriPath[2] == ':' || uriPath[2] == '|')
    ) {
        val drive = uriPath[1]
        val rest = uriPath.substring(3)
        return buildString {
            append(drive)
            append(':')
            if (rest.isNotEmpty() && !rest.startsWith('/')) {
                append('/')
            }
            append(rest.replace('|', ':'))
        }
    }
    // Already a Windows drive path: "C:/..."
    if (uriPath.length >= 2 && uriPath[0].isLetter() && (uriPath[1] == ':' || uriPath[1] == '|')) {
        return uriPath[0] + ":" + uriPath.substring(2).replace('|', ':')
    }
    // Unix absolute and all other forms keep their shape (including leading '/').
    return uriPath
}

private fun String.toVirtualPathOrNull(): VirtualPath? {
    if (isBlank()) {
        return null
    }
    return runCatching { VirtualPath.of(this) }.getOrNull()
}

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
    if (!enabled) {
        return this
    }
    val normalized = replace('\\', '/')
    return when {
        normalized == "workspace" || normalized == "/workspace" -> normalized
        normalized.startsWith("workspace/") -> normalized.removePrefix("workspace/").ifBlank { this }
        normalized.startsWith("/workspace/") -> normalized.removePrefix("/workspace/").ifBlank { this }
        else -> this
    }
}

private fun String.normalizeWorkspacePathPrefix(): String {
    return replace('\\', '/').trimEnd('/')
}

private fun normalizeUriPrefixKey(path: String): String {
    return path.replace('\\', '/').trimEnd('/')
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
    val normalized = path.value.replace('\\', '/')
    val uriPath = when {
        isWindowsDrivePath(normalized) -> "/$normalized"
        normalized.startsWith("/") -> normalized
        else -> "/$normalized"
    }
    return URI("file", "", uriPath, null).toASCIIString()
}

private fun isWindowsDrivePath(path: String): Boolean {
    return path.length >= 2 && path[0].isLetter() && path[1] == ':'
}

private fun SemanticSymbolKind.toLspSymbolKind(): org.eclipse.lsp4j.SymbolKind {
    return when (this) {
        SemanticSymbolKind.VARIABLE,
        SemanticSymbolKind.PARAMETER,
        SemanticSymbolKind.LOCAL -> org.eclipse.lsp4j.SymbolKind.Variable

        SemanticSymbolKind.FUNCTION -> org.eclipse.lsp4j.SymbolKind.Function
        SemanticSymbolKind.METHOD -> org.eclipse.lsp4j.SymbolKind.Method
        SemanticSymbolKind.FIELD -> org.eclipse.lsp4j.SymbolKind.Field
        SemanticSymbolKind.CLASS -> org.eclipse.lsp4j.SymbolKind.Class
        SemanticSymbolKind.TYPE_ALIAS -> org.eclipse.lsp4j.SymbolKind.TypeParameter
        SemanticSymbolKind.MODULE -> org.eclipse.lsp4j.SymbolKind.Module
        SemanticSymbolKind.UNKNOWN -> org.eclipse.lsp4j.SymbolKind.Property
    }
}
