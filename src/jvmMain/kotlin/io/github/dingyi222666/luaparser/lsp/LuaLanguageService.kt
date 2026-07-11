package io.github.dingyi222666.luaparser.lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaParserRecoveryDiagnostic
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind as SemanticSymbolKind
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceUpdateResult
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
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
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
        val content = buildHoverContent(result.symbol?.name, result.symbol?.detail, result.typeInfo?.displayName)
            ?: return@synchronized null
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
        queries.gotoDefinition(path, params.position.toParserPosition()).map { location ->
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
        queries.documentHighlights(path, params.position.toParserPosition()).map { location ->
            DocumentHighlight(location.range.toLspRange(), DocumentHighlightKind.Read)
        }
    }

    fun references(params: ReferenceParams): List<Location> = synchronized(stateLock) {
        val path = pathOf(params.textDocument)
        queries.references(path, params.position.toParserPosition()).map { location ->
            Location(uriFor(location.path), location.range.toLspRange())
        }
    }

    fun documentSymbols(path: String): List<SymbolInformation> = synchronized(stateLock) {
        val virtualPath = pathFromClientPath(path)
        val file = snapshot.files[virtualPath]?.semanticFile ?: return@synchronized emptyList()
        declarationSymbolEntries(virtualPath, file.snapshot.binder.declarationIndex.declarations)
            .map { toSymbolInformation(it) }
    }

    fun workspaceSymbols(query: String): List<SymbolInformation> = synchronized(stateLock) {
        val normalizedQuery = query.trim()
        allSymbolEntries()
            .asSequence()
            .filter { normalizedQuery.isBlank() || it.name.contains(normalizedQuery, ignoreCase = true) }
            .map { toSymbolInformation(it) }
            .toList()
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
            rawWorkspacePathFromUri(uri)
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

    private fun workspaceFolderRoot(folder: WorkspaceFolder): Path? {
        val uri = folder.uri ?: return null
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

    private fun workspaceFolderName(uri: String): String {
        return rawWorkspacePathFromUri(uri)
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
        val candidate = rawWorkspacePathFromUri(uri) ?: uri
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
            }
            signatureHelpProvider = SignatureHelpOptions(listOf("(", ","), listOf(")"))
            documentSymbolProvider = Either.forLeft(true)
            workspaceSymbolProvider = Either.forRight(WorkspaceSymbolOptions())
        }
    }

    private fun allSymbolEntries(): List<LspSymbolEntry> {
        val entries = buildList {
            snapshot.files.forEach { (path, file) ->
                file.semanticFile?.let { semanticFile ->
                    val declarations = declarationSymbolEntries(path, semanticFile.snapshot.binder.declarationIndex.declarations)
                    addAll(declarations)
                    addAll(moduleExportMemberSymbolEntries(path, file, declarations))
                }
            }
            snapshot.extraProviders.forEach { (path, file) ->
                moduleSymbolEntry(path, file)?.let(::add)
                val semanticFile = file.semanticFile
                if (semanticFile != null) {
                    val declarations = declarationSymbolEntries(path, semanticFile.snapshot.binder.declarationIndex.declarations)
                    addAll(declarations)
                    addAll(moduleExportMemberSymbolEntries(path, file, declarations))
                } else {
                    addAll(moduleExportMemberSymbolEntries(path, file))
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

    private fun moduleExportMemberSymbolEntries(
        path: VirtualPath,
        file: WorkspaceSnapshot.FileSnapshot,
        existingDeclarations: List<LspSymbolEntry> = emptyList()
    ): List<LspSymbolEntry> {
        val surface = file.moduleExportSurface ?: return emptyList()
        val moduleName = surface.moduleType.moduleName.takeIf { it.isNotBlank() }
        val declarationKeys = existingDeclarations
            .asSequence()
            .map { symbolEntryKey(it.path, it.name, it.kind, it.range) }
            .toSet()

        return surface.members
            .asSequence()
            .filter { isUserFacingExportName(it.name) }
            .map { member ->
                val range = member.range ?: syntheticModuleRange(member.name)
                LspSymbolEntry(
                    name = member.name,
                    kind = member.kind.toLspSymbolKind(),
                    path = path,
                    range = range,
                    containerName = moduleName
                )
            }
            .filterNot { symbolEntryKey(it.path, it.name, it.kind, it.range) in declarationKeys }
            .toList()
    }

    private fun symbolEntryKey(
        path: VirtualPath,
        name: String,
        kind: org.eclipse.lsp4j.SymbolKind,
        range: Range
    ): String {
        return listOf(
            path.value,
            name,
            kind.name,
            range.start.line.toString(),
            range.start.column.toString()
        ).joinToString(":")
    }

    private fun isUserFacingExportName(name: String): Boolean {
        return name.isNotBlank() && !name.startsWith("__")
    }

    private fun syntheticModuleRange(moduleName: String): Range {
        return Range(
            start = Position(1, 1),
            end = Position(1, maxOf(moduleName.length + 1, 2))
        )
    }

    private fun toSymbolInformation(entry: LspSymbolEntry): SymbolInformation {
        return SymbolInformation(
            entry.name,
            entry.kind,
            Location(uriFor(entry.path), entry.range.toLspRange()),
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

internal fun lspVirtualPathFromUri(
    uri: String,
    workspaceFolderUriPrefixes: Map<String, String> = emptyMap(),
    collapseSyntheticWorkspaceRoot: Boolean = false
): VirtualPath {
    val parsedPath = rawWorkspacePathFromUri(uri)
    val workspacePath = parsedPath?.workspaceRelativePath(workspaceFolderUriPrefixes)
        ?.syntheticWorkspaceRelativePath(collapseSyntheticWorkspaceRoot)
    return workspacePath?.toVirtualPathOrNull() ?: encodedUriPath(uri)
}

private fun rawWorkspacePathFromUri(uri: String): String? {
    val parsed = try {
        URI(uri)
    } catch (_: IllegalArgumentException) {
        return uri.removePrefix("file:///")
    }

    return when {
        parsed.scheme.equals("file", ignoreCase = true) -> parsed.fileWorkspacePath(uri)
        parsed.scheme.isNullOrBlank() -> parsed.path?.takeIf { it.isNotBlank() } ?: uri
        else -> null
    }
}

private fun URI.fileWorkspacePath(originalUri: String): String {
    path?.takeIf { it.isNotBlank() }?.let { return it.removePrefix("/") }
    schemeSpecificPart?.takeIf { it.isNotBlank() }?.let { return it.removePrefix("///").removePrefix("/") }
    return originalUri.removePrefix("file:///")
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
        .filter { prefix -> normalized == prefix || normalized.startsWith("$prefix/") }
        .maxByOrNull { it.length }
        ?: return normalized
    return normalized.removePrefix(match).removePrefix("/").ifBlank { normalized }
}

private fun String.syntheticWorkspaceRelativePath(enabled: Boolean): String {
    return if (enabled) removePrefix("workspace/").ifBlank { this } else this
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

private fun lspFileUri(path: VirtualPath): String {
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
