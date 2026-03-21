package io.github.dingyi222666.luaparser.lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
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
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverOptions
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceOptions
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbolOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI

class LuaLanguageService(
    private val engine: JvmWorkspaceEngine = JvmWorkspaceEngine()
) {
    private var snapshot: WorkspaceSnapshot = WorkspaceSnapshot()
    private var queries: LuaWorkspaceQueryFacade = LuaWorkspaceQueryFacade(snapshot)
    private val openDocuments = linkedMapOf<VirtualPath, String>()
    private var workspaceMetadata: Map<String, String> = emptyMap()
    private var workspaceFolders: List<WorkspaceFolder> = emptyList()

    fun initialize(params: InitializeParams): InitializeResult {
        workspaceFolders = params.workspaceFolders.orEmpty()
        rebuild()
        return InitializeResult(serverCapabilities())
    }

    fun setWorkspaceMetadata(metadata: Map<String, String>) {
        workspaceMetadata = metadata
        rebuild()
    }

    fun didOpen(params: DidOpenTextDocumentParams): PublishDiagnosticsParams {
        val document = params.textDocument
        openDocuments[pathOf(document)] = document.text
        rebuild()
        return publishDiagnostics(pathOf(document))
    }

    fun didChange(params: DidChangeTextDocumentParams): PublishDiagnosticsParams {
        val path = pathOf(params.textDocument)
        openDocuments[path] = applyContentChanges(openDocuments[path].orEmpty(), params.contentChanges)
        rebuild()
        return publishDiagnostics(path)
    }

    fun didClose(params: DidCloseTextDocumentParams): PublishDiagnosticsParams {
        val path = pathOf(params.textDocument)
        openDocuments.remove(path)
        rebuild()
        return PublishDiagnosticsParams(uriOf(path), emptyList())
    }

    fun didSave(@Suppress("UNUSED_PARAMETER") params: DidSaveTextDocumentParams) {
    }

    fun hover(params: HoverParams): Hover? {
        val path = pathOf(params.textDocument)
        val result = queries.hover(path, params.position.toParserPosition()) ?: return null
        val content = buildHoverContent(result.symbol?.name, result.symbol?.detail, result.typeInfo?.displayName)
            ?: return null
        val hover = Hover()
        hover.contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, content))
        return hover
    }

    fun completion(path: String, line: Int, character: Int): CompletionList {
        val items = queries.completions(VirtualPath.of(path), Position(line + 1, character + 1)).map { completion ->
            CompletionItem(completion.label).apply {
                kind = completion.kind.toLspKind()
                detail = completion.detail
                insertText = completion.insertText
                insertTextFormat = InsertTextFormat.PlainText
                sortText = completion.sortText
            }
        }
        return CompletionList(false, items)
    }

    fun signatureHelp(params: SignatureHelpParams): SignatureHelp? {
        val path = pathOf(params.textDocument)
        val help = queries.signatureHelp(path, params.position.toParserPosition()) ?: return null
        return SignatureHelp(
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

    fun definition(params: DefinitionParams): List<Location> {
        val path = pathOf(params.textDocument)
        return queries.gotoDefinition(path, params.position.toParserPosition()).map { location ->
            Location(uriOf(location.path), location.range.toLspRange())
        }
    }

    fun declaration(params: DeclarationParams): List<Location> {
        val path = pathOf(params.textDocument)
        return queries.declaration(path, params.position.toParserPosition()).map { location ->
            Location(uriOf(location.path), location.range.toLspRange())
        }
    }

    fun documentHighlights(params: DocumentHighlightParams): List<DocumentHighlight> {
        val path = pathOf(params.textDocument)
        return queries.documentHighlights(path, params.position.toParserPosition()).map { location ->
            DocumentHighlight(location.range.toLspRange(), DocumentHighlightKind.Read)
        }
    }

    fun references(params: ReferenceParams): List<Location> {
        val path = pathOf(params.textDocument)
        return queries.references(path, params.position.toParserPosition()).map { location ->
            Location(uriOf(location.path), location.range.toLspRange())
        }
    }

    fun documentSymbols(path: String): List<SymbolInformation> {
        val virtualPath = VirtualPath.of(path)
        val file = snapshot.files[virtualPath]?.semanticFile ?: return emptyList()
        return declarationSymbolEntries(virtualPath, file.snapshot.binder.declarationIndex.declarations)
            .map(::toSymbolInformation)
    }

    fun workspaceSymbols(query: String): List<SymbolInformation> {
        val normalizedQuery = query.trim()
        return allSymbolEntries()
            .asSequence()
            .filter { normalizedQuery.isBlank() || it.name.contains(normalizedQuery, ignoreCase = true) }
            .map(::toSymbolInformation)
            .toList()
    }

    fun diagnostics(path: String): PublishDiagnosticsParams {
        return publishDiagnostics(VirtualPath.of(path))
    }

    private fun publishDiagnostics(path: VirtualPath): PublishDiagnosticsParams {
        val diagnostics = queries.diagnostics(path).map { diagnostic ->
            Diagnostic().apply {
                message = diagnostic.message
                severity = diagnostic.severity.toLspSeverity()
                code = diagnostic.code?.let { Either.forLeft<String, Int>(it) }
                range = diagnostic.range?.toLspRange() ?: Range(Position(1, 1), Position(1, 1)).toLspRange()
            }
        }
        return PublishDiagnosticsParams(uriOf(path), diagnostics)
    }

    private fun rebuild() {
        val result = engine.build(
            LuaWorkspaceInput(
                files = openDocuments.toMap(),
                metadata = workspaceMetadata
            )
        )
        snapshot = result.snapshot
        queries = LuaWorkspaceQueryFacade(snapshot)
    }

    private fun serverCapabilities(): ServerCapabilities {
        return ServerCapabilities().apply {
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)
            hoverProvider = Either.forRight(HoverOptions())
            declarationProvider = Either.forLeft(true)
            definitionProvider = Either.forRight(DefinitionOptions())
            referencesProvider = Either.forRight(ReferenceOptions())
            documentHighlightProvider = Either.forLeft(true)
            completionProvider = CompletionOptions()
            signatureHelpProvider = SignatureHelpOptions(listOf("(", ","), listOf(")"))
            documentSymbolProvider = Either.forLeft(true)
            workspaceSymbolProvider = Either.forRight(WorkspaceSymbolOptions())
        }
    }

    private fun allSymbolEntries(): List<LspSymbolEntry> {
        val entries = buildList {
            snapshot.files.forEach { (path, file) ->
                file.semanticFile?.let { semanticFile ->
                    addAll(declarationSymbolEntries(path, semanticFile.snapshot.binder.declarationIndex.declarations))
                }
            }
            snapshot.extraProviders.forEach { (path, file) ->
                moduleSymbolEntry(path, file)?.let(::add)
                file.semanticFile?.let { semanticFile ->
                    addAll(declarationSymbolEntries(path, semanticFile.snapshot.binder.declarationIndex.declarations))
                }
            }
        }
        return entries
            .distinctBy { entry ->
                listOf(
                    entry.path.value,
                    entry.name,
                    entry.kind.name,
                    entry.range.start.line.toString(),
                    entry.range.start.column.toString(),
                    entry.containerName.orEmpty()
                ).joinToString(":")
            }
            .sortedWith(compareBy<LspSymbolEntry>({ it.name }, { it.path.value }, { it.range.start.line }, { it.range.start.column }))
    }

    private fun declarationSymbolEntries(path: VirtualPath, declarations: List<BinderDeclaration>): List<LspSymbolEntry> {
        val declarationsById = declarations.associateBy(BinderDeclaration::id)
        return declarations
            .asSequence()
            .filter(::isNavigableSymbolDeclaration)
            .mapNotNull { declaration ->
                declaration.range?.let { range ->
                    LspSymbolEntry(
                        name = declaration.name,
                        kind = declaration.kind.toLspSymbolKind(),
                        path = path,
                        range = range,
                        containerName = containerNameFor(declaration, declarationsById)
                    )
                }
            }
            .toList()
    }

    private fun isNavigableSymbolDeclaration(declaration: BinderDeclaration): Boolean {
        if (declaration.name.isBlank() || declaration.range == null) {
            return false
        }
        if (declaration.origin == DeclarationOrigin.BUILTIN) {
            return false
        }
        return declaration.kind != DeclarationKind.PARAMETER && declaration.kind != DeclarationKind.TYPE_PARAMETER
    }

    private fun containerNameFor(
        declaration: BinderDeclaration,
        declarationsById: Map<io.github.dingyi222666.luaparser.semantic.binder.DeclarationId, BinderDeclaration>
    ): String? {
        val ownerId = (declaration.owner as? DeclarationOwner.Declaration)?.declarationId ?: return null
        return declarationsById[ownerId]?.name?.takeIf { it.isNotBlank() }
    }

    private fun moduleSymbolEntry(path: VirtualPath, file: WorkspaceSnapshot.FileSnapshot): LspSymbolEntry? {
        val moduleName = file.moduleExportSurface?.moduleType?.moduleName?.takeIf { it.isNotBlank() } ?: return null
        return LspSymbolEntry(
            name = moduleName,
            kind = org.eclipse.lsp4j.SymbolKind.Module,
            path = path,
            range = syntheticModuleRange(moduleName),
            containerName = null
        )
    }

    private fun syntheticModuleRange(moduleName: String): Range {
        return Range(
            start = Position(1, 1),
            end = Position(1, maxOf(moduleName.length + 1, 2))
        )
    }

    private fun pathOf(document: TextDocumentItem): VirtualPath = pathOf(document.uri)

    private fun pathOf(document: TextDocumentIdentifier): VirtualPath = pathOf(document.uri)

    private fun pathOf(uri: String): VirtualPath {
        val parsed = URI(uri)
        val rawPath = when {
            parsed.scheme.equals("file", ignoreCase = true) -> parsed.path.removePrefix("/")
            else -> parsed.path.ifEmpty { uri }
        }
        return VirtualPath.of(rawPath)
    }

    private fun uriOf(path: VirtualPath): String {
        val normalized = path.value.replace('\\', '/')
        return if (normalized.length >= 2 && normalized[1] == ':') {
            "file:///" + normalized
        } else {
            "file:///" + normalized.trimStart('/')
        }
    }

    private fun applyContentChanges(current: String, changes: List<TextDocumentContentChangeEvent>): String {
        if (changes.isEmpty()) {
            return current
        }
        return changes.last().text
    }

    private fun buildHoverContent(name: String?, detail: String?, typeDisplayName: String?): String? {
        val parts = buildList {
            name?.let { add("**$it**") }
            detail?.takeIf { it.isNotBlank() }?.let(::add)
            typeDisplayName?.takeIf { it.isNotBlank() }?.let { add("Type: `$it`") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
}

private data class LspSymbolEntry(
    val name: String,
    val kind: org.eclipse.lsp4j.SymbolKind,
    val path: VirtualPath,
    val range: Range,
    val containerName: String?
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

private fun toSymbolInformation(entry: LspSymbolEntry): SymbolInformation {
    return SymbolInformation(
        entry.name,
        entry.kind,
        Location(uriOf(entry.path), entry.range.toLspRange()),
        entry.containerName
    )
}

private fun uriOf(path: VirtualPath): String {
    val normalized = path.value.replace('\\', '/')
    return if (normalized.length >= 2 && normalized[1] == ':') {
        "file:///" + normalized
    } else {
        "file:///" + normalized.trimStart('/')
    }
}

private fun DeclarationKind.toLspSymbolKind(): org.eclipse.lsp4j.SymbolKind {
    return when (this) {
        DeclarationKind.LOCAL,
        DeclarationKind.GLOBAL,
        DeclarationKind.PARAMETER -> org.eclipse.lsp4j.SymbolKind.Variable

        DeclarationKind.FUNCTION -> org.eclipse.lsp4j.SymbolKind.Function
        DeclarationKind.MODULE -> org.eclipse.lsp4j.SymbolKind.Module
        DeclarationKind.CLASS -> org.eclipse.lsp4j.SymbolKind.Class
        DeclarationKind.TYPE_ALIAS,
        DeclarationKind.TYPE_PARAMETER -> org.eclipse.lsp4j.SymbolKind.TypeParameter

        DeclarationKind.FIELD -> org.eclipse.lsp4j.SymbolKind.Field
        DeclarationKind.METHOD -> org.eclipse.lsp4j.SymbolKind.Method
    }
}
