package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SignatureHelp
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo
import io.github.dingyi222666.luaparser.semantic.checker.LuaLayoutValueDomains
import io.github.dingyi222666.luaparser.semantic.api.TypeInfoKind
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.importedSymbolHandle
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.SymbolId
import io.github.dingyi222666.luaparser.semantic.binder.comparePositions
import io.github.dingyi222666.luaparser.semantic.model.DefaultSemanticModel
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.model.isBareWeakType
import io.github.dingyi222666.luaparser.semantic.model.isCompletionSite
import io.github.dingyi222666.luaparser.semantic.model.localDeclarationInitializer
import io.github.dingyi222666.luaparser.semantic.model.toSymbolHandle

class LuaWorkspaceQueryFacade(
    private val snapshot: WorkspaceSnapshot
) {
    private val resolver by lazy { WorkspaceModuleResolver(snapshot) }

    fun diagnostics(path: VirtualPath): List<Diagnostic> {
        return snapshot.files[path]?.semanticFile?.model?.getDiagnostics().orEmpty()
    }

    fun lookupModule(moduleName: String): WorkspaceModuleLookupResult {
        val provider = resolver.activeProvider(moduleName)
        return WorkspaceModuleLookupResult(moduleName = moduleName, provider = provider, exportSurface = provider?.let(resolver::exportSurface))
    }

    fun resolveRequire(path: VirtualPath, moduleName: String): WorkspaceModuleLookupResult {
        // Only report a provider when this consumer actually has a real builtin
        // require("moduleName") call site. Free-form module discovery stays on lookupModule;
        // name-only resolve must not invent providers for files that never required them
        // (or only call a shadowed local require).
        if (!hasBuiltinRequireCallSite(path, moduleName)) {
            return WorkspaceModuleLookupResult(moduleName, provider = null, exportSurface = null)
        }
        val resolved = resolver.resolveRequire(path, moduleName)
        return WorkspaceModuleLookupResult(moduleName, resolved?.provider, resolved?.surface)
    }

    fun resolveRequire(path: VirtualPath, position: Position): WorkspaceModuleLookupResult? {
        requireCallSite(path, position)?.let { return workspaceModuleLookup(path, it) }
        // Position-based resolve is also restricted to real builtin require form:
        // require-backed locals only count when their initializer is an unshadowed
        // builtin require("...") call (requiredModuleNameForDeclaration / builtinRequireModuleName).
        return requireBackedModuleNameAtPosition(path, position)?.let { workspaceModuleLookup(path, it) }
    }

    fun completions(path: VirtualPath, position: Position): List<CompletionItem> =
        completionsMatching(path, position, null)

    internal fun completionsMatching(path: VirtualPath, position: Position,
        nameFilter: ((String) -> Boolean)?): List<CompletionItem> {
        val semanticFile = snapshot.files[path]?.semanticFile
        if (semanticFile != null) { unterminatedStringCompletions(semanticFile, position)?.let { return it } }
        val model = semanticFile?.model
        val baseCompletions = if (model is DefaultSemanticModel)
            model.getCompletionsMatching(position, nameFilter)
            else model?.getCompletionsAt(position).orEmpty().filter { nameFilter?.invoke(it.label) != false }
        if (semanticFile == null) { return baseCompletions }
        val memberExpression = semanticFile.nodeIndex.completionMemberAt(position)
        if (memberExpression != null) {
            // Member-context completions must not merge lexical/import free-identifier
            // candidates (Monaco demo: greeter. flooded with locals + builtins). Prefer
            // require-backed export members when available; otherwise keep model member surface.
            val workspaceMembers = workspaceMemberCompletions(path, semanticFile, memberExpression, nameFilter)
            if (workspaceMembers.isNotEmpty()) {
                val callableDetails = baseCompletions.filter {
                    it.kind == CompletionItemKind.FUNCTION || it.kind == CompletionItemKind.METHOD
                }.associateBy(CompletionItem::label)
                return workspaceMembers.map { item ->
                    callableDetails[item.label]?.detail?.let { item.copy(detail = it) } ?: item
                }
            }
            return baseCompletions
        }
        return mergeCompletions(baseCompletions, importCompletions(path, position, semanticFile, nameFilter))
    }

    fun signatureHelp(path: VirtualPath, position: Position): SignatureHelp? {
        return snapshot.files[path]?.semanticFile?.model?.getSignatureHelpAt(position)
    }

    fun hover(path: VirtualPath, position: Position): WorkspaceHoverResult? {
        val semanticFile = snapshot.files[path]?.semanticFile ?: return null
        val model = semanticFile.model
        val node = semanticFile.nodeAt(position)
        val modelSymbol = model.getSymbolAt(position)
        val memberExport = exportedMemberAt(semanticFile, path, position, node)
        val importCallLocal = importCallTargetHoverSymbol(semanticFile, path, node)
        val requireLocal = requireBackedLocalHoverSymbol(semanticFile, path, node)
        val symbol = importRequireSymbol(path, position)
            ?: memberExport?.let(::exportSymbol)
            ?: importCallLocal
            ?: requireLocal
            ?: modelSymbol
            ?: importedSymbolAt(path, position, node, semanticFile)?.let(::importedSymbol)
            ?: resolver.exportAt(path, position)?.let(::exportSymbol)
        val memberType = if (memberExport == null) memberReceiverType(model, node, symbol) else null
        val exportType = memberExport?.let(::exportTypeInfo)
        val nodeType = node?.let(model::getTypeAt)
        // Prefer declared/inferred FunctionType/ClassType from the semantic model when the
        // binder symbol surface is still bare unknown/any (TASK-601 product lock).
        val declaredOrInferred = symbol?.let { resolved ->
            preferredHoverType(model.getDeclaredType(resolved), model.getInferredType(resolved))
        }
        val preferred = if (symbol?.symbolId?.startsWith("builtin-import:") == true) {
            symbol.type
        } else if (requireLocal != null && memberExport == null && requireLocal.type?.kind == TypeInfoKind.MODULE) {
            // Require-alias locals must surface MODULE typing even when the node/model
            // type is a weak string-literal-ish display (`"greeter"`).
            requireLocal.type
        } else {
            // Prefer import-call / require-alias MODULE typing (carries moduleName) over coarse
            // node types. Always run preferredHoverType so structural table literals collapse
            // to "table" even when the symbol surface has a null type and only nodeType is available.
            val primaryCandidate = importCallLocal?.type ?: requireLocal?.type ?: exportType
                ?: declaredOrInferred ?: symbol?.type ?: memberType ?: nodeType
            val secondaryCandidate = exportType ?: requireLocal?.type ?: memberType ?: nodeType
                ?: symbol?.type ?: declaredOrInferred
            preferredHoverType(preferredHoverType(primaryCandidate, secondaryCandidate), declaredOrInferred)
        }
        return WorkspaceHoverResult(
            path = path,
            position = position,
            symbol = symbol,
            typeInfo = preferred,
            callableDisplayName = model.getCallableHoverAt(position)?.displayName
        )
    }

    fun gotoDefinition(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val node = semanticFile?.nodeAt(position)
        semanticFile?.let { importTargetDefinition(path, it, node) }?.let { return listOf(it) }
        val modelSymbol = semanticFile?.model?.getSymbolAt(position)
        val importedAt = semanticFile?.let { importedSymbolAt(path, position, node, it) }
        // Prefer path-scoped imported MODULE aliases over weak free-global VARIABLE symbols so
        // table/dynamic import mounts (File/Locale) still resolve to JVM providers.
        val symbol = when {
            modelSymbol != null &&
                modelSymbol.kind != SymbolKind.VARIABLE &&
                modelSymbol.kind != SymbolKind.UNKNOWN -> modelSymbol
            importedAt != null -> importedSymbol(importedAt)
            else -> modelSymbol ?: importedAt?.let(::importedSymbol)
        }
        // Prefer bindClass/loadLib/newInstance local aliases over same-file local declaration so
        // definition lands on __jvm__/classes/... provider paths (TASK-628).
        semanticFile?.let { bindClassLocalDefinition(it, path, node, symbol) }?.let { return listOf(it) }
        // Prefer require()-initialized local aliases to the provider module export/return site so
        // goto on `local dep = require("dep")` / `return dep` lands on dep.lua (TASK-664).
        // Do this only when the caret is NOT on a member-access identifier; those use export
        // member resolution below and must stay empty when the export is missing.
        val memberAccess = memberAccessAt(node, position)
        if (memberAccess == null) {
            semanticFile?.let { requireBackedLocalDefinition(it, path, node, symbol) }
                ?.let { return listOf(it) }
            // Locals initialized from require-backed exports (`local parse = url.parse`) must
            // navigate to the provider export, not the local binding site (TASK-674).
            semanticFile?.let { requireBackedMemberLocalDefinition(it, path, node, symbol) }
                ?.let { return listOf(it) }
        }
        // Local AST declarations win for true locals (including shadowing of imported modules
        // and same-name export members: `local foo = function...` must not jump only to a
        // foreign util.lua export when the use is the file-local binding).
        // Imported MODULE aliases continue through the import-definition path below.
        // Require-backed locals already returned above when not on member access.
        // FUNCTION/METHOD locals stay file-local here; require-backed function aliases were
        // handled by requireBackedLocalDefinition / requireBackedMemberLocalDefinition above.
        //
        // Prefer a file-local VALUE declaration visible at the free-id use site before any
        // export-handle / exportAt fallback. getSymbolAt may return a foreign export handle
        // (same name as util.foo/util.trim) even when a shadowing local is in scope; symbolId
        // then fails localDeclarationForSymbol and definition incorrectly jumps only to util.lua.
        if (memberAccess == null && semanticFile != null) {
            // plainIdentifier = the Identifier cast plus the member-selector guard below.
            val freeId = plainIdentifier(node)
            if (freeId != null) {
                val visibleLocal = visibleLocalValueDeclaration(semanticFile, freeId.name, freeId.range.start)
                // Keep require("mod") aliases and `local x = mod.member` rebinds on the
                // provider/export path above — only true non-import shadows stay file-local.
                val isRequireOrMemberImport = visibleLocal != null &&
                    (requiredModuleNameForDeclaration(semanticFile, visibleLocal) != null ||
                        memberInitializerForLocal(visibleLocal) != null)
                if (visibleLocal != null && !isRequireOrMemberImport &&
                    visibleLocal.origin != DeclarationOrigin.BUILTIN &&
                    (visibleLocal.kind == DeclarationKind.LOCAL ||
                        visibleLocal.kind == DeclarationKind.FUNCTION ||
                        visibleLocal.kind == DeclarationKind.PARAMETER)
                ) {
                    declarationReferenceLocation(path, visibleLocal)?.let { return listOf(it) }
                }
            }
        }
        if (symbol != null && symbol.kind != SymbolKind.MODULE) {
            val localDeclaration = semanticFile?.let { declarationLocationForSymbol(it, path, symbol.symbolId) }
            if (localDeclaration != null) {
                return listOf(localDeclaration)
            }
        }
        val memberExport = semanticFile?.let { navigationExportedMemberAt(it, path, position, node) }
        // The two elvis arms below were byte-identical recomputes of earlier pure-snapshot
        // reads (`importedAt`; `bindClassLocalDefinition` already returned non-null above) —
        // only the import-call arm can still produce a definition here.
        (importedSymbolDefinitionLocation(symbol, importedAt)
            ?: semanticFile?.let { importCallLocalDefinition(it, path, node, symbol) })
            ?.let { return listOf(it) }
        requireCallDefinition(path, position)?.let { return listOf(it) }
        memberExport?.let { return listOf(exportLocation(it)) }
        navigationExportForMember(path, position, symbol)?.let { return listOf(exportLocation(it)) }
        // On require-backed member access, never invent a definition from the provider module
        // root / local range when the export member itself is missing (TASK-664).
        if (memberAccess != null && semanticFile != null) {
            val requireReceiver = resolvedRequireForNavigationReceiver(semanticFile, path, memberAccess.base)
            if (requireReceiver != null) {
                return emptyList()
            }
        }
        // Guard: only for non-member-access carets (member miss already returned empty above).
        if (memberAccess == null) {
            semanticFile?.let { requireBackedDefinition(it, path, node, symbol) }
                ?.let { return listOf(it) }
        }
        return symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()
    }

    fun declaration(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val node = semanticFile?.nodeAt(position)
        semanticFile?.let { importTargetDefinition(path, it, node) }?.let { return listOf(it) }
        val importedAt = semanticFile?.let { importedSymbolAt(path, position, node, it) }
        val symbol = semanticFile?.model?.getSymbolAt(position)
            ?: importedAt?.let(::importedSymbol)
        val memberExport = semanticFile?.let { navigationExportedMemberAt(it, path, position, node) }
        memberExport?.let { return listOf(exportLocation(it)) }

        val localDeclaration = semanticFile?.let { declarationLocationForSymbol(it, path, symbol?.symbolId) }
        // Local AST declarations win only when they are true locals, not imported MODULE aliases.
        if (localDeclaration != null && symbol?.kind != SymbolKind.MODULE) {
            return listOf(localDeclaration)
        }

        // Prefer member-owning createProxy / bindClass provider over weak imported handles so
        // multi-interface proxy.compare lands on Comparator rather than the first interface arm.
        semanticFile?.let { bindClassLocalDefinition(it, path, node, symbol) }?.let { return listOf(it) }

        importedSymbolDefinitionLocation(symbol, importedAt)?.let { return listOf(it) }

        navigationExportForMember(path, position, symbol)?.let { return listOf(exportLocation(it)) }

        requireCallDefinition(path, position)?.let { return listOf(it) }

        return symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()
    }

    fun references(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val node = semanticFile?.nodeAt(position)
        val symbol = semanticFile?.model?.getSymbolAt(position)
            ?: semanticFile?.let { importedSymbolAt(path, position, node, it)?.let(::importedSymbol) }
        val memberAccess = memberAccessAt(node, position)
        val memberExport = semanticFile?.let { exportedMemberAt(it, path, position, node) }
        // Missing require-backed export members must not invent local or provider references
        // (TASK-664 sibling missing-export isolation).
        if (memberAccess != null && memberExport == null && semanticFile != null) {
            val requireReceiver = resolvedRequireForNavigationReceiver(semanticFile, path, memberAccess.base)
            val hasExportHandle = symbol?.symbolId?.let { ModuleExportIdentity.parse(it) != null } == true
            if (requireReceiver != null && !hasExportHandle) {
                return emptyList()
            }
        }
        val importedHandle = symbol?.symbolId?.takeIf(::isImportedSymbolHandle)
        if (importedHandle != null) {
            return importedSymbolReferences(importedHandle)
        }

        val localReferences = semanticFile
            ?.takeIf { memberExport == null && memberAccess == null }
            ?.let { localSymbolReferences(it, path, symbol) }
            ?.takeIf { it.isNotEmpty() }
        if (localReferences != null) { return localReferences }

        val targetHandle = memberExport?.handle
            ?: symbol?.symbolId?.takeIf { ModuleExportIdentity.parse(it) != null }
            ?: resolver.exportAt(path, position)?.handle
            ?: return if (memberAccess != null) {
                emptyList()
            } else {
                symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()
            }

        val locations = linkedMapOf<String, WorkspaceLocation>()
        resolver.exportedMemberByHandle(targetHandle)?.let { export ->
            locations.putLocation(exportLocation(export))
        }

        snapshot.files.values
            .mapNotNull { it.semanticFile }
            .forEach { file ->
                file.memberExpressions.forEach { expression ->
                    val usage = file.model.getSymbolAt(expression.identifier.range.start)
                    val workspaceExport = exportedMemberForExpression(file, file.path, expression)
                    if (usage?.symbolId == targetHandle || workspaceExport?.handle == targetHandle) {
                        locations.putLocation(WorkspaceLocation(file.path, expression.identifier.range))
                    }
                }
            }

        return locations.values.toList()
    }


    fun documentHighlights(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile ?: return emptyList()
        val node = semanticFile.nodeAt(position)
        val symbol = semanticFile.model.getSymbolAt(position)
            ?: importedSymbolAt(path, position, node, semanticFile)?.let(::importedSymbol)
        val memberExport = exportedMemberAt(semanticFile, path, position, node)
        val highlights = linkedMapOf<String, WorkspaceLocation>()
        val includeCrossFileHighlights =
            memberExport != null ||
            symbol?.symbolId?.let { isImportedSymbolHandle(it) || ModuleExportIdentity.parse(it) != null } == true ||
                resolver.exportAt(path, position) != null

        symbol?.range?.let { range -> highlights.putLocation(WorkspaceLocation(path, range)) }

        references(path, position)
            .asSequence()
            .filter { includeCrossFileHighlights || it.path == path }
            .forEach { highlights.putLocation(it) }

        if (highlights.isNotEmpty()) {
            return highlights.values.toList()
        }

        return symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()
    }

    fun documentSymbols(path: VirtualPath): List<WorkspaceDocumentSymbol> {
        val semanticFile = snapshot.files[path]?.semanticFile
            ?: snapshot.extraProviders[path]?.semanticFile
        if (semanticFile == null) {
            return hierarchicalExportSymbols(path)
        }
        val declarations = semanticFile.snapshot.binder.declarationIndex.declarations
            .filter(::isNavigableDocumentSymbolDeclaration)
        val byId = declarations.associateBy(BinderDeclaration::id)
        val childrenByOwner = linkedMapOf<DeclarationId, MutableList<BinderDeclaration>>()
        val roots = mutableListOf<BinderDeclaration>()
        val declarationSourceOrder = positionOrder(BinderDeclaration::range, BinderDeclaration::name)

        declarations.forEach { declaration ->
            val ownerId = (declaration.owner as? DeclarationOwner.Declaration)?.declarationId
            if (ownerId != null && ownerId in byId) {
                childrenByOwner.getOrPut(ownerId) { mutableListOf() }.add(declaration)
            } else { roots += declaration }
        }

        fun buildNode(declaration: BinderDeclaration): WorkspaceDocumentSymbol {
            val range = declaration.range
                ?: declaration.anchorNode?.range
                ?: syntheticModuleRange(declaration.name)
            val children = childrenByOwner[declaration.id]
                .orEmpty()
                .sortedWith(declarationSourceOrder)
                .map(::buildNode)
            return WorkspaceDocumentSymbol(
                declaration.name,
                declaration.kind.toWorkspaceSymbolKind(),
                range,
                range,
                declaration.declaredType?.displayName,
                children
            )
        }

        val declarationNodes = roots
            .sortedWith(declarationSourceOrder)
            .map(::buildNode)

        val exportNodes = hierarchicalExportSymbols(path)
        return mergeDocumentSymbolRoots(declarationNodes, exportNodes)
    }

    /**
     * Workspace/symbol query surface (workspace-symbol audit, wave M):
     *
     * - The entry universe ([buildAllWorkspaceSymbolEntries]) is memoized for this facade
     *   instance's lifetime. A facade serves exactly one [snapshot] — the LSP service swaps
     *   in a fresh facade on every workspace build/update — so the full iterate/distinct/
     *   sort pass runs once per workspace build instead of on every query while the caller
     *   holds its state lock. Query filtering stays per-request and cheap.
     * - Results are capped at [WORKSPACE_SYMBOL_RESULT_CAP] AFTER the deterministic sort
     *   (name, path, line, column), so a blank query returns a stable 500-entry prefix of
     *   the workspace instead of dumping every local in every file.
     * - Non-blank queries apply a stable two-bucket ranking (wave Q) to the matched
     *   entries BEFORE the cap: leading-prefix matches ([String.startsWith], ignoreCase)
     *   rank ahead of mid-name substring matches, and each bucket keeps the deterministic
     *   (name, path, line, column) order of the already-built entry list. A short query
     *   therefore cannot push name-prefix symbols out of the 500-entry window behind
     *   alphabetically-earlier substring hits.
     */
    fun workspaceSymbolEntries(query: String): List<WorkspaceSymbolEntry> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return cachedWorkspaceSymbolEntries.take(WORKSPACE_SYMBOL_RESULT_CAP)
        }
        val matches = cachedWorkspaceSymbolEntries.filter { entry ->
            entry.name.contains(normalizedQuery, ignoreCase = true)
        }
        val (prefixMatches, substringMatches) = matches.partition { entry ->
            entry.name.startsWith(normalizedQuery, ignoreCase = true)
        }
        return (prefixMatches + substringMatches).take(WORKSPACE_SYMBOL_RESULT_CAP)
    }

    private val cachedWorkspaceSymbolEntries: List<WorkspaceSymbolEntry> by lazy {
        buildAllWorkspaceSymbolEntries()
    }

    private fun buildAllWorkspaceSymbolEntries(): List<WorkspaceSymbolEntry> {
        // extraProvider symbol entries are kept only for actually indexed providers: paths
        // claimed by the module graph (requireable module surfaces) or paths that are real
        // workspace files. Unclaimed synthetic __jvm__ paths have no real location and
        // would only fabricate file:/// URIs a client cannot navigate to (audit finding 4).
        val indexedProviderPaths = snapshot.graph.providersByModuleName.values
            .flatten()
            .mapTo(linkedSetOf()) { it.path }
        val entries = buildList {
            snapshot.files.forEach { (path, file) ->
                addAll(fileSymbolEntries(path, file))
            }
            snapshot.extraProviders.forEach { (path, file) ->
                if (path !in snapshot.files && path !in indexedProviderPaths) { return@forEach }
                moduleWorkspaceSymbolEntry(path, file)?.let(::add)
                addAll(fileSymbolEntries(path, file))
            }
        }
        // Same key shape as workspaceSymbolEntryKey + the container suffix.
        return entries
            .distinctBy { entry ->
                workspaceSymbolEntryKey(entry, ":${entry.containerName.orEmpty()}")
            }
            .sortedWith(
                compareBy<WorkspaceSymbolEntry>({ it.name }, { it.path.value }, { it.range.start.line }, { it.range.start.column })
            )
    }

    private fun fileSymbolEntries(
        path: VirtualPath,
        file: WorkspaceSnapshot.FileSnapshot
    ): List<WorkspaceSymbolEntry> {
        val semanticFile = file.semanticFile
        val declarations = semanticFile
            ?.let { declarationWorkspaceSymbolEntries(path, it.snapshot.binder.declarationIndex.declarations, it.chunk.body) }
            ?: emptyList()
        val exports = moduleExportWorkspaceSymbolEntries(path, file, declarations)
        return declarations + exports
    }

    private fun declarationWorkspaceSymbolEntries(
        path: VirtualPath,
        declarations: List<BinderDeclaration>,
        chunkBody: BaseASTNode?
    ): List<WorkspaceSymbolEntry> {
        val declarationsById = declarations.associateBy(BinderDeclaration::id)
        return declarations
            .asSequence()
            .filter { isNavigableWorkspaceSymbolDeclaration(it, chunkBody) }
            .mapNotNull { declaration ->
                declaration.range?.let { range ->
                    WorkspaceSymbolEntry(
                        declaration.name,
                        declaration.kind.toWorkspaceSymbolKind(),
                        path,
                        range,
                        workspaceContainerNameFor(declaration, declarationsById)
                    )
                }
            }
            .toList()
    }

    private fun moduleWorkspaceSymbolEntry(
        path: VirtualPath,
        file: WorkspaceSnapshot.FileSnapshot
    ): WorkspaceSymbolEntry? {
        val moduleName = file.moduleExportSurface?.moduleType?.moduleName?.takeIf { it.isNotBlank() }
            ?: return null
        return WorkspaceSymbolEntry(moduleName, SymbolKind.MODULE, path, syntheticModuleRange(moduleName), null)
    }

    private fun moduleExportWorkspaceSymbolEntries(
        path: VirtualPath,
        file: WorkspaceSnapshot.FileSnapshot,
        existingDeclarations: List<WorkspaceSymbolEntry> = emptyList()
    ): List<WorkspaceSymbolEntry> {
        val surface = file.moduleExportSurface ?: return emptyList()
        val moduleName = surface.moduleType.moduleName.takeIf { it.isNotBlank() }
        val declarationKeys = existingDeclarations
            .asSequence()
            .map(::workspaceSymbolEntryKey)
            .toSet()

        return surface.members
            .asSequence()
            .filter { isUserFacingExportName(it.name) }
            .map { member ->
                val range = member.range ?: syntheticModuleRange(member.name)
                WorkspaceSymbolEntry(member.name, member.kind, path, range, moduleName)
            }
            .filterNot { workspaceSymbolEntryKey(it) in declarationKeys }
            .toList()
    }

    private fun hierarchicalExportSymbols(path: VirtualPath): List<WorkspaceDocumentSymbol> {
        val file = snapshot.files[path] ?: snapshot.extraProviders[path] ?: return emptyList()
        val surface = file.moduleExportSurface ?: return emptyList()
        val members = surface.members
            .asSequence()
            .filter { isUserFacingExportName(it.name) && it.exportPath.isNotEmpty() }
            .sortedWith(
                compareBy(
                    { it.exportPath.size },
                    { it.range?.start?.line ?: Int.MAX_VALUE },
                    { it.range?.start?.column ?: Int.MAX_VALUE },
                    { it.exportPath.joinToString(".") }
                )
            )
            .toList()

        data class MutableSymbol(
            val name: String,
            var kind: SymbolKind,
            var range: Range,
            val children: LinkedHashMap<String, MutableSymbol> = linkedMapOf()
        ) {
            fun toDocumentSymbol(): WorkspaceDocumentSymbol {
                val childSymbols = children.values
                    .sortedWith(positionOrder(MutableSymbol::range, MutableSymbol::name))
                    .map { it.toDocumentSymbol() }
                return WorkspaceDocumentSymbol(name, kind, range, range, null, childSymbols)
            }
        }

        val roots = linkedMapOf<String, MutableSymbol>()

        fun ensurePath(exportPath: List<String>, kind: SymbolKind, range: Range): MutableSymbol {
            var currentChildren = roots
            var current: MutableSymbol? = null
            exportPath.forEachIndexed { index, segment ->
                val isLeaf = index == exportPath.lastIndex
                val existing = currentChildren[segment]
                current = if (existing == null) {
                    MutableSymbol(
                        name = segment,
                        kind = if (isLeaf) kind else SymbolKind.FIELD,
                        range = if (isLeaf) range else syntheticModuleRange(segment)
                    ).also { currentChildren[segment] = it }
                } else {
                    if (isLeaf) {
                        existing.kind = preferredDocumentSymbolKind(existing.kind, kind)
                        existing.range = range
                    }
                    existing
                }
                currentChildren = current!!.children
            }
            return current ?: error("export path must not be empty")
        }

        members.forEach { member ->
            val range = member.range ?: syntheticModuleRange(member.name)
            ensurePath(member.exportPath, member.kind, range)
        }

        return roots.values
            .sortedWith(positionOrder(MutableSymbol::range, MutableSymbol::name))
            .map { it.toDocumentSymbol() }
    }

    private fun mergeDocumentSymbolRoots(
        declarations: List<WorkspaceDocumentSymbol>,
        exports: List<WorkspaceDocumentSymbol>
    ): List<WorkspaceDocumentSymbol> {
        if (exports.isEmpty()) { return declarations }
        if (declarations.isEmpty()) { return exports }
        val byName = linkedMapOf<String, WorkspaceDocumentSymbol>()
        declarations.forEach { byName[it.name] = it }

        exports.forEach { export ->
            val existing = byName[export.name]
            if (existing == null) {
                byName[export.name] = export
            } else {
                byName[export.name] = existing.copy(
                    kind = preferredDocumentSymbolKind(existing.kind, export.kind),
                    children = mergeDocumentSymbolRoots(existing.children, export.children)
                )
            }
        }

        return byName.values
            .sortedWith(positionOrder(WorkspaceDocumentSymbol::range, WorkspaceDocumentSymbol::name))
    }

    // Highest-priority kind first; shared by every document/workspace-symbol kind merge.
    private val symbolKindRank = listOf(
        SymbolKind.MODULE, SymbolKind.CLASS, SymbolKind.METHOD, SymbolKind.FUNCTION,
        SymbolKind.FIELD, SymbolKind.TYPE_ALIAS, SymbolKind.LOCAL, SymbolKind.VARIABLE,
        SymbolKind.PARAMETER, SymbolKind.UNKNOWN
    )

    /** Deterministic (line, column, name) order shared by export-tree and merged-root sorting. */
    private fun <T> positionOrder(range: (T) -> Range?, name: (T) -> String): Comparator<T> =
        compareBy({ range(it)?.start?.line ?: Int.MAX_VALUE }, { range(it)?.start?.column ?: Int.MAX_VALUE }, { name(it) })

    /** Kotlin callable display (`fun(...)` / `fun<T>`), shared by hover and completion-kind rules. */
    private fun String.isCallableDisplay(): Boolean = contains("fun(") || contains("fun<")

    private fun preferredDocumentSymbolKind(left: SymbolKind, right: SymbolKind): SymbolKind =
        listOf(left, right).minBy { symbolKindRank.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }

    private fun isNavigableDocumentSymbolDeclaration(declaration: BinderDeclaration): Boolean {
        if (declaration.name.isBlank() || declaration.range == null) { return false }
        if (declaration.origin == DeclarationOrigin.BUILTIN) { return false }
        return declaration.kind != DeclarationKind.PARAMETER && declaration.kind != DeclarationKind.TYPE_PARAMETER
    }

    /**
     * Workspace/symbol navigability narrows the document-symbol rule (workspace-symbol
     * audit, wave M): body-LOCAL declarations — locals bound inside function bodies,
     * loop bodies, or conditional blocks — are noise at workspace scale and never
     * surface. Only chunk-level locals, globals, functions, classes, fields, methods and
     * module export surfaces stay: symbols a user can navigate to meaningfully from a
     * workspace-wide list. The binder anchors chunk-level locals with
     * `DeclarationOwner.Lexical(chunk body)`; any other Lexical owner is a nested body.
     */
    private fun isNavigableWorkspaceSymbolDeclaration(
        declaration: BinderDeclaration,
        chunkBody: BaseASTNode?
    ): Boolean {
        if (!isNavigableDocumentSymbolDeclaration(declaration)) { return false }
        if (declaration.kind != DeclarationKind.LOCAL) { return true }
        val owner = declaration.owner
        if (owner == DeclarationOwner.Root) { return true }
        // AST nodes use identity equality, and the declaration owner node is the exact
        // chunk-body instance this snapshot's binder anchored chunk-level locals with.
        return chunkBody != null && owner == DeclarationOwner.Lexical(chunkBody)
    }

    private fun workspaceContainerNameFor(
        declaration: BinderDeclaration,
        declarationsById: Map<DeclarationId, BinderDeclaration>
    ): String? {
        val ownerId = (declaration.owner as? DeclarationOwner.Declaration)?.declarationId ?: return null
        return declarationsById[ownerId]?.name?.takeIf { it.isNotBlank() }
    }

    /** Dedup key "path:name:kind:line:column" (plus the optional container suffix). */
    private fun workspaceSymbolEntryKey(entry: WorkspaceSymbolEntry, containerSuffix: String = ""): String =
        "${entry.path.value}:${entry.name}:${entry.kind.name}:${entry.range.start.line}:${entry.range.start.column}$containerSuffix"

    private fun DeclarationKind.toWorkspaceSymbolKind(): SymbolKind = when (this) {
        DeclarationKind.LOCAL,
        DeclarationKind.GLOBAL -> SymbolKind.VARIABLE
        DeclarationKind.PARAMETER -> SymbolKind.PARAMETER
        DeclarationKind.FUNCTION -> SymbolKind.FUNCTION
        DeclarationKind.MODULE -> SymbolKind.MODULE
        DeclarationKind.CLASS -> SymbolKind.CLASS
        DeclarationKind.TYPE_ALIAS -> SymbolKind.TYPE_ALIAS
        DeclarationKind.TYPE_PARAMETER -> SymbolKind.TYPE_ALIAS
        DeclarationKind.FIELD -> SymbolKind.FIELD
        DeclarationKind.METHOD -> SymbolKind.METHOD
    }


    private fun preferredHoverType(primary: TypeInfo?, fallback: TypeInfo?): TypeInfo? {
        return when {
            primary == null -> fallback
            // Prefer coarse table kind for local table shadows so hover does not expose
            // the concrete structural table literal display (kind may be TABLE or UNKNOWN).
            primary.displayName.startsWith("{") -> {
                TypeInfo(
                    displayName = "table",
                    detail = "table",
                    typeKey = primary.typeKey,
                    kind = TypeInfoKind.TABLE,
                    moduleName = primary.moduleName ?: fallback?.moduleName
                )
            }
            // Prefer Android-Lua multi-import Array<> display over the structural union[] form
            // produced by generic ArrayType.displayName ("T[]").
            primary.displayName.endsWith("[]") &&
                fallback?.displayName?.startsWith("Array<") == true -> fallback
            // Bare unknown/any must lose to any richer fallback (FunctionType/ClassType/MODULE/fun).
            // The structured-kind/fun/moduleName clauses also cover non-bare fallbacks, so the
            // two former bare-weak arms collapse into one condition (arm 1 ∪ arm 2).
            primary.isBareWeakType() &&
                fallback != null &&
                (!fallback.isBareWeakType() ||
                    isPreferredHoverStructuredKind(fallback.kind) ||
                    fallback.displayName.isCallableDisplay() ||
                    !fallback.moduleName.isNullOrBlank()) -> fallback
            // Dynamic import() locals often evaluate as unknown/any at the node while the
            // symbol surface already carries the resolved MODULE type (moduleName / fun(...)).
            primary.moduleName.isNullOrBlank() && !fallback?.moduleName.isNullOrBlank() -> {
                fallback!!.copy(
                    displayName = primary.displayName.takeUnless {
                        it.isBlank() || it == "unknown" || it == "any"
                    } ?: fallback.displayName,
                    detail = primary.detail?.takeUnless {
                        it.isBlank() || it == "unknown" || it == "any"
                    } ?: fallback.detail
                )
            }
            !primary.displayName.isCallableDisplay() &&
                fallback?.displayName?.isCallableDisplay() == true -> fallback
            // Prefer declared/inferred CLASS/FUNCTION kinds when primary is only UNKNOWN.
            primary.kind == TypeInfoKind.UNKNOWN &&
                fallback != null &&
                isPreferredHoverStructuredKind(fallback.kind) -> fallback
            else -> primary
        }
    }

    private fun isPreferredHoverStructuredKind(kind: TypeInfoKind): Boolean =
        kind == TypeInfoKind.FUNCTION || kind == TypeInfoKind.CLASS || kind == TypeInfoKind.MODULE

    private fun memberReceiverType(model: SemanticModel, node: BaseASTNode?, symbol: Symbol?): TypeInfo? {
        val memberExpression = node as? MemberExpression ?: return null
        return symbol?.kind?.takeIf { it == SymbolKind.FIELD || it == SymbolKind.METHOD }
            ?.let { model.getTypeAt(memberExpression.base) }
    }

    private fun requireBackedDefinition(semanticFile: WorkspaceSemanticFile, path: VirtualPath, node: BaseASTNode?, symbol: Symbol?): WorkspaceLocation? =
        requireBackedModuleNames(semanticFile, node, symbol).firstNotNullOfOrNull { candidate ->
            requireBackedLocation(path, candidate)
        }

    private fun requireBackedLocalDefinition(
        semanticFile: WorkspaceSemanticFile, path: VirtualPath, node: BaseASTNode?, symbol: Symbol?
    ): WorkspaceLocation? {
        val identifier = plainIdentifier(node) ?: return null
        val moduleName = resolveRequiredModuleNameForLocal(semanticFile, symbol?.symbolId, identifier) ?: return null
        return requireBackedLocation(path, moduleName)
    }

    /**
     * Resolve `local parse = url.parse` (and usages of that local) to the require-backed export
     * member on the provider module (e.g. AndroLua socket.url overlay path).
     */
    private fun requireBackedMemberLocalDefinition(
        semanticFile: WorkspaceSemanticFile, path: VirtualPath, node: BaseASTNode?, symbol: Symbol?
    ): WorkspaceLocation? {
        val identifier = plainIdentifier(node) ?: return null
        val declaration = localDeclarationForSymbolOrIdentifier(semanticFile, symbol?.symbolId, identifier) ?: return null
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) { return null }
        val memberInitializer = memberInitializerForLocal(declaration) ?: return null
        val exportPath = requireBackedExportPath(semanticFile, path, memberInitializer)
            ?.takeIf { it.isNotEmpty() } ?: return null
        // Both walkers used to be byte-identical (resolvedRequireForExportPathRoot was
        // folded into resolveRequireByWalkingExpressionBases), so one call resolves the root.
        val resolved = resolveRequireByWalkingExpressionBases(semanticFile, path, memberInitializer)
            ?: return null
        val export = resolver.exportedMember(resolved.provider.path, exportPath)
            ?: exportPath.lastOrNull()?.let { leaf ->
                resolver.exportedMember(resolved.provider.path, listOf(leaf))
            }
        export?.let { return exportLocation(it) }
        // Still navigate to the require-backed provider (e.g. AndroLua socket.url overlay)
        // when the export member range is not yet collected, rather than the local binding site.
        return WorkspaceLocation(resolved.provider.path, syntheticModuleRange(exportPath.last()))
    }

    private fun memberInitializerForLocal(declaration: BinderDeclaration): MemberExpression? =
        localInitializerExpression(declaration) as? MemberExpression

    private fun requireBackedModuleNames(
        semanticFile: WorkspaceSemanticFile, node: BaseASTNode?, symbol: Symbol?
    ): List<String> {
        val identifier = node as? Identifier
        // Member-access carets (dep.missing) must not be treated as require-local aliases.
        // Callers that intentionally want the receiver module should pass the receiver node.
        if (identifier != null && isMemberAccessIdentifier(identifier)) {
            return emptyList()
        }
        return buildList {
            identifier
                ?.let { resolveRequiredModuleNameForLocal(semanticFile, symbol?.symbolId, it) }
                ?.let(::add)
            symbol?.type?.moduleName
                ?.takeIf { it.isNotBlank() && it !in this }
                ?.let(::add)
            exportedModuleNameFromMemberBase(semanticFile, node)
                ?.takeIf { it.isNotBlank() && it !in this }
                ?.let(::add)
            // Do NOT fall back to bare identifier names. Source-activated JVM class providers are
            // mounted workspace-wide for hydration, but simple-name visibility must stay scoped to
            // the file that performed the import (TASK-155 / TASK-176 sibling isolation).
        }
    }

    private fun navigationExportedMemberAt(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        position: Position,
        node: BaseASTNode?
    ): WorkspaceModuleResolver.ResolvedExportMember? {
        val memberExpression = memberAccessAt(node, position)
            ?: semanticFile.memberExpressions.lastOrNull { rangeContains(it.identifier.range, position) }
            ?: return null
        return exportedMemberForExpression(semanticFile, path, memberExpression)
    }

    private fun resolvedRequireForNavigationReceiver(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        receiver: BaseASTNode
    ): WorkspaceModuleResolver.ResolvedRequire? {
        // Nested require-alias receivers (second after local second = first.nested) still count as
        // require-backed for missing-export empty policy and navigation.
        if (receiver is ExpressionNode) {
            requireBackedExportPath(semanticFile, path, receiver)?.let {
                return resolvedRequireForExportPathRootFromExpression(semanticFile, path, receiver)
            }
        }
        if (receiver is Identifier) {
            val receiverSymbol = semanticFile.model.getSymbolAt(receiver.range.start)
            resolveRequiredModuleNameForVisibleLocal(semanticFile, receiverSymbol?.symbolId, receiver)
                ?.let { moduleName -> resolveWorkspaceRequire(path, moduleName) }
                ?.let { return it }
        }
        return resolvedRequireForReceiver(semanticFile, path, receiver)
    }

    private fun resolvedRequireForExportPathRootFromExpression(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        expression: ExpressionNode
    ): WorkspaceModuleResolver.ResolvedRequire? {
        // Keep each branch an expression of ResolvedRequire?. A while-loop block typed as Unit
        // previously made the whole when infer as Any? and failed compileKotlinJvm (TASK-674).
        return when (expression) {
            is MemberExpression, is IndexExpression ->
                resolveRequireByWalkingExpressionBases(semanticFile, path, expression)
            is Identifier, is CallExpression -> resolvedRequireForWorkspaceMemberReceiver(semanticFile, path, expression)
            else -> null
        }
    }

    /**
     * Walk nested Index/Member/alias chains until a require-backed module root is found.
     * Extracted from when-expression branches so the walker can use imperative returns without
     * poisoning the enclosing when type (Windows compileKotlinJvm).
     */
    private fun resolveRequireByWalkingExpressionBases(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        expression: ExpressionNode
    ): WorkspaceModuleResolver.ResolvedRequire? {
        var current: ExpressionNode = expression
        val seen = linkedSetOf<String>()
        while (true) {
            when (current) {
                is MemberExpression -> current = current.base
                is IndexExpression -> current = current.base
                is Identifier -> {
                    val key = "${current.range.start.line}:${current.range.start.column}:${current.name}"
                    if (!seen.add(key)) { return null }
                    resolvedRequireForWorkspaceMemberReceiver(semanticFile, path, current)?.let { return it }
                    val symbol = semanticFile.model.getSymbolAt(current.range.start)
                    val declaration = localDeclarationForSymbolOrIdentifier(semanticFile, symbol?.symbolId, current)
                        ?: return null
                    val initializer = localInitializerExpression(declaration) ?: return null
                    current = initializer
                }
                is CallExpression -> return resolvedRequireForReceiver(semanticFile, path, current)
                else -> return null
            }
        }
    }

    private fun resolveRequiredModuleNameForVisibleLocal(
        semanticFile: WorkspaceSemanticFile,
        symbolId: String?,
        identifier: Identifier
    ): String? {
        val declaration = localDeclarationForSymbol(semanticFile, symbolId)
            ?: visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.start)
            ?: return null
        return requiredModuleNameForDeclaration(semanticFile, declaration)
    }

    private fun exportedModuleNameFromMemberBase(
        semanticFile: WorkspaceSemanticFile,
        node: BaseASTNode?
    ): String? {
        val memberExpression = memberExpressionForNode(node) ?: return null
        return receiverModuleName(semanticFile, memberExpression.base)
    }

    private fun resolveRequiredModuleNameForLocal(
        semanticFile: WorkspaceSemanticFile,
        symbolId: String?,
        identifier: Identifier?
    ): String? {
        val declaration = localDeclarationForSymbolOrIdentifier(semanticFile, symbolId, identifier) ?: return null
        return requiredModuleNameForDeclaration(semanticFile, declaration)
    }

    private fun requiredModuleNameForDeclaration(
        semanticFile: WorkspaceSemanticFile,
        declaration: BinderDeclaration,
        visited: MutableSet<DeclarationId> = linkedSetOf()
    ): String? {
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) { return null }
        if (!visited.add(declaration.id)) { return null }
        return when (val initializer = localInitializerExpression(declaration)) {
            is CallExpression -> builtinRequireModuleName(semanticFile, initializer)
            // local first = dep where dep = require("dep")
            is Identifier -> {
                val target = exactLocalDeclarationForIdentifier(semanticFile, initializer)
                    ?: visibleLocalValueDeclaration(semanticFile, initializer.name, initializer.range.start)
                target?.let { requiredModuleNameForDeclaration(semanticFile, it, visited) }
            }
            else -> null
        }
    }

    private fun localDeclarationForSymbol(
        semanticFile: WorkspaceSemanticFile,
        symbolId: String?
    ): BinderDeclaration? {
        val handle = symbolId?.toSymbolHandle() ?: return null
        return when {
            handle.binderSymbolId != null -> semanticFile.snapshot.binder.declarationIndex
                .getPrimaryDeclaration(SymbolId(handle.binderSymbolId))
            handle.declarationId != null -> semanticFile.snapshot.binder.declarationIndex
                .getDeclaration(DeclarationId(handle.declarationId))
            else -> null
        }
    }

    private fun exactLocalDeclarationForIdentifier(
        semanticFile: WorkspaceSemanticFile,
        identifier: Identifier
    ): BinderDeclaration? {
        return semanticFile.snapshot.binder.declarationIndex
            .getDeclarations(identifier)
            .firstOrNull { it.kind == DeclarationKind.LOCAL && it.anchorNode === identifier }
    }

    /**
     * Shared symbol-first local-declaration ladder: the symbol's declaration when a handle
     * resolves it, else the exact declaration anchored at [identifier], else the visible
     * local value declaration at the identifier position. Null when [identifier] is null and
     * the symbol handle does not resolve.
     */
    private fun localDeclarationForSymbolOrIdentifier(
        semanticFile: WorkspaceSemanticFile,
        symbolId: String?,
        identifier: Identifier?
    ): BinderDeclaration? {
        return localDeclarationForSymbol(semanticFile, symbolId)
            ?: identifier?.let { exactLocalDeclarationForIdentifier(semanticFile, it) }
            ?: identifier?.let { visibleLocalValueDeclaration(semanticFile, it.name, it.range.start) }
    }

    private fun declarationLocationForSymbol(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        symbolId: String?
    ): WorkspaceLocation? {
        val declaration = localDeclarationForSymbol(semanticFile, symbolId) ?: return null
        return declarationReferenceLocation(path, declaration)
    }

    private fun localSymbolReferences(
        semanticFile: WorkspaceSemanticFile, path: VirtualPath, symbol: Symbol?
    ): List<WorkspaceLocation> {
        val declaration = localDeclarationForSymbol(semanticFile, symbol?.symbolId) ?: return emptyList()
        val locations = linkedMapOf<String, WorkspaceLocation>()
        declarationReferenceLocation(path, declaration)?.let { locations.putLocation(it) }
        locations.putIdentifierUsages(semanticFile, path, declaration.name, symbol?.symbolId)
        return locations.values.toList()
    }

    /** Identifier usages whose model symbol resolves to [symbolId], appended as locations. */
    private fun MutableMap<String, WorkspaceLocation>.putIdentifierUsages(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        name: String,
        symbolId: String?
    ) {
        semanticFile.identifiers
            .asSequence()
            .filter { it.name == name }
            .forEach { identifier ->
                val usage = semanticFile.model.getSymbolAt(identifier.range.start) ?: return@forEach
                if (usage.symbolId == symbolId) {
                    putLocation(WorkspaceLocation(path, identifier.range))
                }
            }
    }

    private fun declarationReferenceLocation(
        path: VirtualPath,
        declaration: BinderDeclaration
    ): WorkspaceLocation? {
        val range = declaration.anchorNode?.range ?: declaration.range ?: return null
        return WorkspaceLocation(path, range)
    }

    private fun requireCallDefinition(path: VirtualPath, position: Position): WorkspaceLocation? {
        return requireBackedLocation(path, requireCallSite(path, position) ?: return null)
    }

    /** Provider-path location at the synthetic module range for a resolved require/import module. */
    private fun requireBackedLocation(path: VirtualPath, moduleName: String): WorkspaceLocation? =
        resolveWorkspaceRequire(path, moduleName)?.let {
            WorkspaceLocation(it.provider.path, syntheticModuleRange(moduleName))
        }

    private fun builtinRequireModuleName(
        semanticFile: WorkspaceSemanticFile,
        call: CallExpression
    ): String? {
        // Support both require("mod") and require "mod" (StringCallExpression base).
        val callee = effectiveCallBase(call) as? Identifier ?: return null
        if (callee.name != "require") { return null }
        // Unshadowed free-id `require` is the language builtin even when the overlay-seeded
        // declaration is temporarily invisible (documented ranges live on virtual overlay docs).
        // Only a true non-builtin local/parameter binding may shadow it (TASK-664).
        val declaration = visibleLocalValueDeclaration(semanticFile, callee.name, callee.range.start, localInitializerDeclarationIds(semanticFile, call))
        if (declaration != null &&
            (declaration.origin != DeclarationOrigin.BUILTIN || declaration.name != "require")
        ) {
            return null
        }
        return stringConstantOf(callArguments(call).singleOrNull())
    }

    /** Shared import-target ladder: this file's path-scoped import first, then the global surface. */
    private fun importTarget(path: VirtualPath, target: String): WorkspaceImportedSymbol? =
        resolver.importTargetSymbolFor(path, target) ?: resolver.importTargetSymbol(target)

    private fun importTargetDefinition(
        path: VirtualPath,
        semanticFile: WorkspaceSemanticFile,
        node: BaseASTNode?
    ): WorkspaceLocation? {
        val constant = node as? ConstantNode ?: return null
        if (constant.constantType != ConstantNode.TYPE.STRING) { return null }
        // Prefer parent-linked enclosing call (string-call args parent-linked by the parser).
        // Fall back to range containment for compact AST shapes where argument.parent may be unset.
        val call = enclosingCallExpression(constant)
            ?: importStringCallContaining(semanticFile, constant)
            ?: return null
        if (!callArguments(call).any { it === constant || isSameStringLiteral(it, constant) }) { return null }
        if (!isImportCallee(semanticFile, effectiveCallBase(call))) { return null }
        val target = constant.stringOf()
        val imported = importTarget(path, target) ?: return null
        return WorkspaceLocation(imported.providerPath, syntheticModuleRange(imported.alias))
    }

    /** The expression's string-literal value when it is a STRING-typed [ConstantNode]. */
    private fun stringConstantOf(expression: BaseASTNode?): String? =
        (expression as? ConstantNode)
            ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
            ?.stringOf()

    private fun isSameStringLiteral(expression: ExpressionNode, constant: ConstantNode): Boolean {
        val other = expression as? ConstantNode ?: return false
        return other.constantType == ConstantNode.TYPE.STRING &&
            other.stringOf() == constant.stringOf() &&
            other.range == constant.range
    }

    /**
     * Recover the enclosing import/require string-call when ConstantNode.parent is unset.
     * Walks CallExpressions in the file and matches the caret constant by range identity.
     */
    private fun importStringCallContaining(
        semanticFile: WorkspaceSemanticFile,
        constant: ConstantNode
    ): CallExpression? {
        var match: CallExpression? = null
        val visitor = object : ASTVisitor<Unit> {
            // Shared body for both call shapes (the two overrides were byte-identical):
            // claim the match and stop descending when the caret constant is an argument of
            // an import/require callee; otherwise continue traversal via super.
            fun visitCallLike(node: CallExpression): Boolean {
                if (callArguments(node).none { it === constant || isSameStringLiteral(it, constant) }) { return false }
                if (isImportCallee(semanticFile, effectiveCallBase(node)) ||
                    (effectiveCallBase(node) as? Identifier)?.name == "require"
                ) {
                    match = node
                    return true
                }
                return false
            }

            override fun visitCallExpression(node: CallExpression, value: Unit) {
                if (!visitCallLike(node)) super.visitCallExpression(node, value)
            }

            override fun visitStringCallExpression(node: StringCallExpression, value: Unit) {
                if (!visitCallLike(node)) super.visitStringCallExpression(node, value)
            }

            override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) = Unit
            // visitIdentifier / visitCommentStatement keep their (empty) interface defaults.
        }
        visitor.visitChunkNode(semanticFile.chunk, Unit)
        return match
    }

    private fun callArguments(call: CallExpression): List<ExpressionNode> {
        // Flatten nested string/table-call bases so outer CallExpression(import "X") and
        // the nested StringCallExpression both surface the string argument. String/Table
        // calls recurse into any base; plain calls only into nested string/table bases.
        return buildList {
            fun appendFrom(expression: ExpressionNode) {
                if (expression !is CallExpression) return
                if (expression is StringCallExpression || expression is TableCallExpression) {
                    appendFrom(expression.base)
                } else {
                    (expression.base as? StringCallExpression ?: expression.base as? TableCallExpression)
                        ?.let(::appendFrom)
                }
                addAll(expression.arguments)
            }
            appendFrom(call)
        }
    }

    private fun effectiveCallBase(call: CallExpression): ExpressionNode {
        // Flatten StringCall bases so require "mod" / import "mod" surface their callee.
        val base = call.base
        return if (base is StringCallExpression) base.base else base
    }

    private fun isImportCallee(semanticFile: WorkspaceSemanticFile, expression: ExpressionNode): Boolean {
        val identifier = expression as? Identifier ?: return false
        if (identifier.name == "import") {
            val local = visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.start)
            if (local == null || local.origin == DeclarationOrigin.BUILTIN) { return true }
            return requiredModuleNameForDeclaration(semanticFile, local) == "import"
        }
        return aliasResolvesToImportCallable(semanticFile, identifier, emptySet(), linkedSetOf())
    }

    private fun aliasResolvesToImportCallable(
        semanticFile: WorkspaceSemanticFile,
        identifier: Identifier,
        excludedDeclarations: Set<DeclarationId>,
        visited: MutableSet<DeclarationId>
    ): Boolean {
        val declaration = visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.start, excludedDeclarations) ?: return false
        if (!visited.add(declaration.id)) { return false }
        if (requiredModuleNameForDeclaration(semanticFile, declaration) == "import") { return true }
        val initializer = requireInitializerForLocal(declaration)
        if (initializer != null) { return builtinRequireModuleName(semanticFile, initializer) == "import" }
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return false
        val localIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (localIndex < 0) { return false }
        val initExpression = localStatement.variables.getOrNull(localIndex) as? Identifier ?: return false
        val hopExclusions = localStatementInitDeclarationIds(semanticFile, localStatement)
        return aliasResolvesToImportCallable(semanticFile, initExpression, hopExclusions, visited)
    }

    /**
     * Shared imported-alias definition ladder: symbol-handle location first, else the
     * provider site of the resolved import under the caret. [importedAt] must be the
     * `importedSymbolAt(path, position, node, semanticFile)` result already computed by
     * the caller (the query is a pure snapshot read, so recomputing it is dead weight).
     */
    private fun importedSymbolDefinitionLocation(
        symbol: Symbol?,
        importedAt: WorkspaceImportedSymbol?
    ): WorkspaceLocation? {
        return symbol?.symbolId
            ?.let { ImportedSymbolIdentity.parse(it) }
            ?.let { WorkspaceLocation(it.providerPath, syntheticModuleRange(it.alias)) }
            ?: importedAt?.let { WorkspaceLocation(it.providerPath, syntheticModuleRange(it.alias)) }
    }

    /**
     * Shared export-member navigation ladder: handle → caret export. The former third arm
     * (`exportFromMemberBase`) re-derived `resolver.exportedMemberByHandle` from the same
     * `symbol.symbolId` — `parse(symbolId)!!.asHandle()` round-trips to `symbolId` itself —
     * so it could never yield a value the handle arm had not already returned.
     */
    private fun navigationExportForMember(
        path: VirtualPath,
        position: Position,
        symbol: Symbol?
    ): WorkspaceModuleResolver.ResolvedExportMember? {
        return symbol?.symbolId?.let(resolver::exportedMemberByHandle)
            ?: resolver.exportAt(path, position)
    }

    private fun importedSymbolReferences(symbolId: String): List<WorkspaceLocation> {
        val identity = ImportedSymbolIdentity.parse(symbolId) ?: return emptyList()
        val locations = linkedMapOf<String, WorkspaceLocation>()
        locations.putLocation(WorkspaceLocation(identity.providerPath, syntheticModuleRange(identity.alias)))

        snapshot.files.values
            .mapNotNull { it.semanticFile }
            .forEach { file -> locations.putIdentifierUsages(file, file.path, identity.alias, symbolId) }

        return locations.values.toList()
    }


    private fun isImportedSymbolHandle(symbolId: String): Boolean = ImportedSymbolIdentity.parse(symbolId) != null

    private fun workspaceModuleLookup(path: VirtualPath, moduleName: String): WorkspaceModuleLookupResult? {
        val resolved = resolveWorkspaceRequire(path, moduleName) ?: return null
        return WorkspaceModuleLookupResult(moduleName, resolved.provider, resolved.surface)
    }

    private fun resolveWorkspaceRequire(
        path: VirtualPath,
        moduleName: String
    ): WorkspaceModuleResolver.ResolvedRequire? {
        resolver.resolveRequire(path, moduleName)?.let { return it }
        val provider = resolver.activeProvider(moduleName) ?: return null
        val surface = resolver.exportSurface(provider) ?: return null
        return WorkspaceModuleResolver.ResolvedRequire(moduleName, provider, surface)
    }

    private fun requireBackedModuleNameAtPosition(path: VirtualPath, position: Position): String? {
        val semanticFile = snapshot.files[path]?.semanticFile ?: return null
        val node = semanticFile.nodeAt(position) as? Identifier ?: return null
        val symbol = semanticFile.model.getSymbolAt(position)
        return resolveRequiredModuleNameForLocal(semanticFile, symbol?.symbolId, node)
    }

    private fun importRequireSymbol(path: VirtualPath, position: Position): Symbol? {
        val resolved = resolveRequire(path, position) ?: return null
        if (resolved.moduleName != "import") { return null }
        val callType = resolved.exportSurface?.moduleType?.fields?.get("__call") ?: return null
        // Normalize Android-Lua import callable display regardless of vararg name formatting
        // (the exact normalized form itself also matches the second arm's predicate).
        val rawDisplay = callType.displayName
        val displayName =
            if (rawDisplay.contains("any...") || rawDisplay.startsWith("fun(")) "fun(...: any...): any" else rawDisplay
        return surfaceSymbol(
            "import", SymbolKind.FUNCTION, null,
            TypeInfo(displayName, displayName, null, TypeInfoKind.FUNCTION),
            displayName,
            "builtin-import:${resolved.provider?.path?.value.orEmpty()}"
        )
    }

    private fun providerPathForExport(export: WorkspaceModuleResolver.ResolvedExportMember): VirtualPath {
        return if (export.type is ModuleType) {
            resolver.classProviderForMember(export.providerPath, export.member.name)?.path
                ?: export.definitionProviderPath
        } else { export.definitionProviderPath }
    }

    private fun exportLocation(export: WorkspaceModuleResolver.ResolvedExportMember): WorkspaceLocation {
        val providerPath = providerPathForExport(export)
        val range = export.member.range ?: syntheticModuleRange(export.member.name)
        return WorkspaceLocation(providerPath, range)
    }

    /** Dedup key for reference/highlight locations: "path:line:column". */
    private fun MutableMap<String, WorkspaceLocation>.putLocation(location: WorkspaceLocation) {
        put("${location.path.value}:${location.range.start.line}:${location.range.start.column}", location)
    }

    private fun workspaceMemberCompletions(
        path: VirtualPath,
        semanticFile: WorkspaceSemanticFile,
        memberExpression: MemberExpression,
        nameFilter: ((String) -> Boolean)? = null
    ): List<CompletionItem> {
        // Prefix under the require-backed root: greeter. → []; cfg.ui. → ["ui"].
        val prefixPath = requireBackedExportPath(semanticFile, path, memberExpression.base)
            ?: return emptyList()
        val resolved = when (val base = memberExpression.base) {
            is Identifier -> resolvedRequireForCompletionReceiver(semanticFile, path, base)
            else -> resolvedRequireForExportPathRootFromExpression(semanticFile, path, base)
                ?: resolvedRequireForCompletionReceiver(semanticFile, path, base)
        } ?: return emptyList()
        val expectedDepth = prefixPath.size + 1
        return resolved.surface.members
            .asSequence()
            .filter { member ->
                member.exportPath.size == expectedDepth &&
                    member.exportPath.take(prefixPath.size) == prefixPath &&
                    isUserFacingExportName(member.name) && nameFilter?.invoke(member.name) != false
            }
            .distinctBy { it.name }
            .map { member ->
                val resolvedMember = resolver.exportedMember(resolved.provider.path, member.exportPath)
                val detail = resolvedMember?.type?.displayName
                    ?: member.type.displayName
                completionItem(member.name, exportMemberCompletionKind(member, detail), detail, "0:0000")
            }
            .toList()
    }

    /**
     * Export members keep [SymbolKind.FIELD] at collection time even when the field value is
     * a function (`function M.f()` / table-literal function values). For completions a
     * callable display must surface as [CompletionItemKind.FUNCTION] so LSP clients never
     * render callables with the plain text/value icon; METHOD/FUNCTION kinds pass through.
     */
    private fun exportMemberCompletionKind(
        member: ModuleExportSurface.MemberExport,
        detail: String?
    ): CompletionItemKind = callableUpgradedCompletionKind(
        member.kind.toCompletionItemKind(),
        detail != null && (detail.trim().startsWith("fun(") || detail.contains(" -> "))
    )

    /**
     * Shared callable-display upgrade for both completion producers: a callable display lifts
     * TEXT/FIELD kinds to [CompletionItemKind.FUNCTION] so LSP clients never render callables
     * with the plain text/value icon; METHOD/FUNCTION (and every other kind) pass through.
     * The import producer only feeds TEXT into [callable] (a FIELD-kind import keeps FIELD),
     * the export producer feeds FIELD as well (`function M.f()` collects as FIELD).
     */
    private fun callableUpgradedCompletionKind(base: CompletionItemKind, callable: Boolean): CompletionItemKind =
        if (callable && (base == CompletionItemKind.TEXT || base == CompletionItemKind.FIELD)) {
            CompletionItemKind.FUNCTION
        } else { base }

    /**
     * Hover for `local greeter = require("greeter")` aliases: surface MODULE typing from the
     * resolved export surface instead of a bare local / string-literal-ish display.
     */
    private fun requireBackedLocalHoverSymbol(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        node: BaseASTNode?
    ): Symbol? {
        val identifier = plainIdentifier(node) ?: return null
        val moduleName = resolveRequiredModuleNameForLocal(semanticFile, null, identifier)
            ?: resolveRequiredModuleNameForVisibleLocal(semanticFile, semanticFile.model.getSymbolAt(identifier.range.start)?.symbolId, identifier)
            ?: return null
        val resolved = resolveWorkspaceRequire(path, moduleName) ?: return null
        val moduleType = resolved.moduleType
        val display = "module ${moduleType.moduleName}"
        return surfaceSymbol(
            identifier.name, SymbolKind.MODULE, identifier.range,
            TypeInfo(display, moduleType.displayName, null, TypeInfoKind.MODULE, moduleType.moduleName),
            display,
            "require-module:${resolved.provider.path.value}:$moduleName"
        )
    }

    private fun resolvedRequireForCompletionReceiver(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        receiver: BaseASTNode
    ): WorkspaceModuleResolver.ResolvedRequire? {
        if (receiver is Identifier) {
            val receiverSymbol = semanticFile.model.getSymbolAt(receiver.range.start)
            resolveRequiredModuleNameForVisibleLocal(semanticFile, receiverSymbol?.symbolId, receiver)
                ?.let { moduleName -> resolveWorkspaceRequire(path, moduleName) }
                ?.let { return it }
        }
        return resolvedRequireForReceiver(semanticFile, path, receiver)
    }

    private fun exportedMemberAt(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        position: Position,
        node: BaseASTNode?
    ): WorkspaceModuleResolver.ResolvedExportMember? {
        val memberExpression = memberAccessAt(node, position) ?: return null
        return exportedMemberForExpression(semanticFile, path, memberExpression)
    }

    private fun exportedMemberForExpression(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        memberExpression: MemberExpression
    ): WorkspaceModuleResolver.ResolvedExportMember? {
        val memberName = memberExpression.identifier.name.takeIf { it.isNotBlank() } ?: return null
        // Nested require-alias chains (local second = first.nested; second.value) need the full
        // export path under the provider surface, not only the leaf segment.
        val exportPath = requireBackedExportPath(semanticFile, path, memberExpression) ?: return null
        val resolved = resolveRequireByWalkingExpressionBases(semanticFile, path, memberExpression) ?: return null
        return resolver.exportedMember(resolved.provider.path, exportPath)
            ?: resolver.exportedMember(resolved.provider.path, memberName).takeIf { exportPath.size == 1 }
    }

    /**
     * Resolve require("mod")-backed export path for a member expression, following local rebinding
     * chains such as `local first = dep; local second = first.nested; second.value`.
     */
    private fun requireBackedExportPath(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        expression: ExpressionNode,
        visited: MutableSet<String> = linkedSetOf()
    ): List<String>? {
        return when (expression) {
            is MemberExpression -> {
                val memberName = expression.identifier.name.takeIf { it.isNotBlank() } ?: return null
                val basePath = requireBackedExportPath(semanticFile, path, expression.base, visited)
                    ?: return null
                basePath + memberName
            }
            is IndexExpression -> {
                val key = stringConstantOf(expression.index)?.takeIf { it.isNotBlank() } ?: return null
                val basePath = requireBackedExportPath(semanticFile, path, expression.base, visited)
                    ?: return null
                basePath + key
            }
            is Identifier -> {
                val key = "${expression.range.start.line}:${expression.range.start.column}:${expression.name}"
                if (!visited.add(key)) { return null }
                val symbol = semanticFile.model.getSymbolAt(expression.range.start)
                val declaration = localDeclarationForSymbolOrIdentifier(semanticFile, symbol?.symbolId, expression)
                if (declaration != null) {
                    requiredModuleNameForDeclaration(semanticFile, declaration)?.let {
                        // Root require-backed local: export path is empty before the member segment.
                        return emptyList()
                    }
                    val initializer = localInitializerExpression(declaration)
                    if (initializer != null) { requireBackedExportPath(semanticFile, path, initializer, visited)?.let { return it } }
                }
                // Direct require module name typing on the identifier (no local declaration path).
                resolveRequiredModuleNameForVisibleLocal(semanticFile, symbol?.symbolId, expression)
                    ?.let { return emptyList() }
                null
            }
            is CallExpression -> { builtinRequireModuleName(semanticFile, expression)?.let { emptyList() } }
            else -> null
        }
    }

    private fun localInitializerExpression(declaration: BinderDeclaration): ExpressionNode? =
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) {
            null
        } else {
            localDeclarationInitializer(declaration)
        }

    private fun resolvedRequireForWorkspaceMemberReceiver(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        receiver: BaseASTNode
    ): WorkspaceModuleResolver.ResolvedRequire? {
        if (receiver is Identifier) {
            val receiverSymbol = semanticFile.model.getSymbolAt(receiver.range.start)
            val declaration = localDeclarationForSymbol(semanticFile, receiverSymbol?.symbolId)
                ?: visibleLocalValueDeclaration(semanticFile, receiver.name, receiver.range.start)
            // Direct require("mod") initializer.
            declaration
                ?.let { requireInitializerForLocal(it) }
                ?.let { builtinRequireModuleName(semanticFile, it) }
                ?.let { moduleName -> resolveWorkspaceRequire(path, moduleName) }
                ?.let { return it }
            // Follow local rebinding / nested require-alias chains:
            // local first = dep; local second = first.nested
            declaration
                ?.let { localInitializerExpression(it) }
                ?.let { initializer ->
                    when (initializer) {
                        is Identifier ->
                            resolvedRequireForWorkspaceMemberReceiver(semanticFile, path, initializer)
                        is MemberExpression, is IndexExpression, is CallExpression ->
                            resolvedRequireForExportPathRootFromExpression(semanticFile, path, initializer)
                        else -> null
                    }
                }
                ?.let { return it }
            resolveRequiredModuleNameForVisibleLocal(semanticFile, receiverSymbol?.symbolId, receiver)
                ?.let { moduleName -> resolveWorkspaceRequire(path, moduleName) }
                ?.let { return it }
        }
        return resolvedRequireForReceiver(semanticFile, path, receiver)
    }

    private fun requireInitializerForLocal(declaration: BinderDeclaration): CallExpression? =
        localInitializerExpression(declaration) as? CallExpression

    private fun resolvedRequireForReceiver(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        receiver: BaseASTNode
    ): WorkspaceModuleResolver.ResolvedRequire? {
        val candidates = buildList {
            if (receiver is Identifier) {
                val receiverSymbol = semanticFile.model.getSymbolAt(receiver.range.start)
                addAll(requireBackedModuleNames(semanticFile, receiver, receiverSymbol))
            }
            if (receiver is CallExpression) {
                builtinRequireModuleName(semanticFile, receiver)?.let(::add)
            }
            receiverModuleName(semanticFile, receiver)
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }
        return candidates
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .firstNotNullOfOrNull { moduleName -> resolveWorkspaceRequire(path, moduleName) }
    }

    private fun receiverModuleName(
        semanticFile: WorkspaceSemanticFile,
        receiver: BaseASTNode
    ): String? {
        // Prefer model TypeInfo.moduleName: SemanticModel.getTypeAt returns TypeInfo, not ModuleType.
        semanticFile.model.getTypeAt(receiver)?.moduleName?.takeIf { it.isNotBlank() }?.let { return it }
        if (receiver is Identifier) {
            val symbol = semanticFile.model.getSymbolAt(receiver.range.start)
            symbol?.type?.moduleName?.takeIf { it.isNotBlank() }?.let { return it }
            val declaration = localDeclarationForSymbol(semanticFile, symbol?.symbolId)
                ?: visibleLocalValueDeclaration(semanticFile, receiver.name, receiver.range.start)
            if (declaration != null) { importCallTargetModuleName(semanticFile, semanticFile.path, declaration)?.let { return it } }
        }
        return null
    }

    private fun importCallTargetModuleName(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        declaration: BinderDeclaration
    ): String? {
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) { return null }
        val initializer = requireInitializerForLocal(declaration) ?: return null
        if (!isImportCallee(semanticFile, effectiveCallBase(initializer))) { return null }
        val targets = importCallStringTargets(initializer)
        if (targets.size != 1) { return null }
        val imported = importTarget(path, targets.single()) ?: return null
        return imported.moduleType.moduleName.takeIf { it.isNotBlank() }
    }

    private fun importCallStringTargets(call: CallExpression): List<String> {
        val firstArgument = callArguments(call).firstOrNull()
        return when (firstArgument) {
            is ConstantNode -> stringConstantOf(firstArgument)?.let(::listOf).orEmpty()
            is ArrayConstructorExpression -> firstArgument.values.mapNotNull(::stringConstantOf)
            is TableConstructorExpression -> firstArgument.fields.mapNotNull { stringConstantOf(it.value) }
            else -> emptyList()
        }
    }

    private fun importCallTargetHoverSymbol(
        semanticFile: WorkspaceSemanticFile, path: VirtualPath, node: BaseASTNode?
    ): Symbol? {
        val identifier = plainIdentifier(node) ?: return null
        val declaration = exactLocalDeclarationForIdentifier(semanticFile, identifier)
            ?: visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.start)
            ?: return null
        if (declaration.kind != DeclarationKind.LOCAL) { return null }
        val initializer = requireInitializerForLocal(declaration) ?: return null
        if (!isImportCallee(semanticFile, effectiveCallBase(initializer))) { return null }
        val targets = importCallStringTargets(initializer)
        if (targets.isEmpty()) { return null }
        val imported = targets.mapNotNull { importTarget(path, it) }
        if (imported.isEmpty()) { return null }
        return if (targets.size == 1 && imported.size == 1) {
            importedSymbol(imported.single())
        } else {
            val moduleNames = imported.map { it.moduleType.moduleName }.distinct()
            val display = "Array<${moduleNames.joinToString("|")}>"
            surfaceSymbol(
                declaration.name, SymbolKind.LOCAL, declaration.range,
                TypeInfo(display, display),
                display,
                declaration.symbolId?.let { "binder:$it" } ?: "declaration:${declaration.id.value}"
            )
        }
    }

    private fun importCallLocalDefinition(
        semanticFile: WorkspaceSemanticFile, path: VirtualPath, node: BaseASTNode?, symbol: Symbol?
    ): WorkspaceLocation? {
        val identifier = plainIdentifier(node) ?: return null
        val declaration = localDeclarationForSymbolOrIdentifier(semanticFile, symbol?.symbolId, identifier) ?: return null
        val moduleName = importCallTargetModuleName(semanticFile, path, declaration)
            ?: symbol?.type?.moduleName?.takeIf { it.isNotBlank() }
            ?: return null
        val provider = resolver.activeProvider(moduleName)
            ?: resolver.classProviderForAlias(moduleName)
            ?: return null
        return WorkspaceLocation(provider.path, syntheticModuleRange(moduleName))
    }

    /**
     * Definition for locals initialized by LuaJava class-load helpers
     * (bindClass / newInstance / loadLib / createProxy / createArray).
     * Lands on mounted __jvm__/classes provider when the target class was source-discovered.
     */
    private fun bindClassLocalDefinition(
        semanticFile: WorkspaceSemanticFile, path: VirtualPath, node: BaseASTNode?, symbol: Symbol?
    ): WorkspaceLocation? {
        val identifier = node as? Identifier
        val declaration = when {
            identifier != null -> {
                if (isMemberAccessIdentifier(identifier)) {
                    // Member access: resolve receiver local alias (Locale.getDefault -> Locale).
                    null
                } else {
                    localDeclarationForSymbolOrIdentifier(semanticFile, symbol?.symbolId, identifier)
                }
            }
            else -> localDeclarationForSymbol(semanticFile, symbol?.symbolId)
        }
        // Member receiver path: Identifier base of MemberExpression.
        val memberAccess = memberExpressionForNode(node)
        val accessedMemberName = memberAccess
            ?.takeIf { member ->
                val focus = node as? Identifier
                focus == null || member.identifier === focus
            }
            ?.identifier
            ?.name
            ?.takeIf { it.isNotBlank() }
        val receiverDeclaration = declaration ?: run {
            val member = memberAccess ?: return null
            val receiver = member.base as? Identifier ?: return null
            val receiverSymbol = semanticFile.model.getSymbolAt(receiver.range.start)
            localDeclarationForSymbolOrIdentifier(semanticFile, receiverSymbol?.symbolId, receiver)
                ?: return null
        }
        val moduleName = bindClassTargetModuleName(
            semanticFile = semanticFile,
            path = path,
            declaration = receiverDeclaration,
            preferredMemberName = accessedMemberName
        )
            ?: symbol?.type?.moduleName?.takeIf { it.isNotBlank() }
            ?: return null
        val provider = resolver.activeProvider(moduleName)
            ?: resolver.classProviderForAlias(moduleName)
            ?: return null
        // Only claim definition when a real class provider is mounted (has __class field).
        if (resolver.classProviderForAlias(moduleName) == null &&
            resolver.exportSurface(provider)?.moduleType?.fields?.containsKey("__class") != true
        ) {
            return null
        }
        return WorkspaceLocation(provider.path, syntheticModuleRange(moduleName))
    }

    private fun bindClassTargetModuleName(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        declaration: BinderDeclaration,
        preferredMemberName: String? = null
    ): String? {
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) { return null }
        val initializer = requireInitializerForLocal(declaration) ?: return null
        val target = luaJavaClassLoadTarget(semanticFile, initializer, preferredMemberName) ?: return null
        val imported = importTarget(path, target) ?: return null
        return imported.moduleType.moduleName.takeIf { it.isNotBlank() }
            ?: imported.alias.takeIf { it.isNotBlank() }
    }

    private fun luaJavaClassLoadTarget(
        semanticFile: WorkspaceSemanticFile,
        call: CallExpression,
        preferredMemberName: String? = null
    ): String? {
        if (!isLuaJavaClassLoadCallee(semanticFile, effectiveCallBase(call))) { return null }
        val targets = luaJavaClassLoadTargets(semanticFile, call)
        if (targets.isEmpty()) { return null }
        // Multi-interface createProxy: prefer the interface branch that actually declares the
        // accessed member (proxy.compare -> Comparator, not the first Runnable arm).
        if (!preferredMemberName.isNullOrBlank() && isCreateProxyCallBase(semanticFile, effectiveCallBase(call))) {
            targets.firstOrNull { target ->
                javaProviderExposesMember(target, preferredMemberName)
            }?.let { return it }
        }
        return targets.firstOrNull()
    }

    private fun luaJavaClassLoadTargets(
        semanticFile: WorkspaceSemanticFile,
        call: CallExpression
    ): List<String> {
        // Precondition: the only caller (luaJavaClassLoadTarget) has already verified
        // isLuaJavaClassLoadCallee(effectiveCallBase(call)) — do not re-walk the callee.
        return if (isCreateProxyCallBase(semanticFile, effectiveCallBase(call))) {
            createProxyInterfaceTargets(call)
        } else {
            importCallStringTargets(call)
                .map(String::trim)
                .filter(String::isNotEmpty)
        }
    }

    private fun createProxyInterfaceTargets(call: CallExpression): List<String> {
        val targets = mutableListOf<String>()
        for (argument in callArguments(call)) {
            val value = stringConstantOf(argument) ?: break
            targets += value.split(',').map(String::trim).filter(String::isNotEmpty)
        }
        return targets
    }

    /**
     * Shared LuaJava helper-call base detection: `luajava.<helper>` (or `LuaJava.<helper>`),
     * or a bare identifier that is either an unresolved/builtin helper name or a local alias
     * whose initializer resolves to the same helper. [isCreateProxyCallBase] and
     * [isLuaJavaClassLoadCallee] differ only in the helper name(s) they accept.
     */
    private fun isLuaJavaHelperCallBase(
        semanticFile: WorkspaceSemanticFile, expression: ExpressionNode, helperName: String
    ): Boolean {
        return when (expression) {
            is MemberExpression ->
                expression.identifier.name == helperName && isLuaJavaOwnerIdentifier(expression.base)
            is Identifier -> {
                if (expression.name != helperName) { return false }
                // Bare helper or local alias of luajava.<helper>.
                val declaration = visibleLocalValueDeclaration(semanticFile, expression.name, expression.range.start)
                declaration == null || declaration.origin == DeclarationOrigin.BUILTIN ||
                    declarationResolvesToLuaJavaHelper(semanticFile, declaration, helperName)
            }
            else -> false
        }
    }

    private fun isCreateProxyCallBase(
        semanticFile: WorkspaceSemanticFile,
        expression: ExpressionNode
    ): Boolean {
        return isLuaJavaHelperCallBase(semanticFile, expression, "createProxy")
    }

    private fun javaProviderExposesMember(target: String, memberName: String): Boolean {
        val imported = resolver.importTargetSymbol(target) ?: return false
        val moduleType = imported.moduleType
        if (memberName in moduleType.fields || memberName in moduleType.methods) { return true }
        return when (val classSurface = moduleType.fields["__class"]) {
            is JavaClassType ->
                memberName in classSurface.allInstanceMembers() ||
                    memberName in classSurface.allStaticMembers()
            is JavaInstanceType ->
                memberName in classSurface.allInstanceMembers() ||
                    memberName in classSurface.classType.allStaticMembers()
            else -> false
        }
    }

    private fun isLuaJavaClassLoadCallee(
        semanticFile: WorkspaceSemanticFile,
        expression: ExpressionNode
    ): Boolean {
        return LUA_JAVA_CLASS_LOAD_HELPERS.any { isLuaJavaHelperCallBase(semanticFile, expression, it) }
    }

    private fun isLuaJavaOwnerIdentifier(
        expression: ExpressionNode
    ): Boolean {
        val identifier = expression as? Identifier ?: return false
        return identifier.name == "luajava" || identifier.name == "LuaJava"
    }

    private fun declarationResolvesToLuaJavaHelper(
        semanticFile: WorkspaceSemanticFile,
        declaration: BinderDeclaration,
        helperName: String
    ): Boolean {
        val initializer = requireInitializerForLocal(declaration) ?: return false
        return when (val base = effectiveCallBase(initializer)) {
            is MemberExpression ->
                base.identifier.name == helperName && isLuaJavaOwnerIdentifier(base.base)
            is Identifier -> base.name == helperName
            else -> false
        }
    }

    private companion object {
        val moduleCallBeforeQuote by lazy { Regex("\\b(?:require|import)\\s*\\(?$") }
        val tableKeyBeforeQuote by lazy { Regex("(?:^|[,{])\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*$") }

        /**
         * Workspace/symbol result cap (workspace-symbol audit, wave M). Applied after the
         * deterministic (name, path, line, column) sort so every query — blank included —
         * returns a bounded, stable prefix instead of an unbounded megabyte payload.
         */
        const val WORKSPACE_SYMBOL_RESULT_CAP = 500

        val LUA_JAVA_CLASS_LOAD_HELPERS: Set<String> = setOf("bindClass", "newInstance", "createProxy", "loadLib", "createArray", "newArray")
    }

    /**
     * Mid-typing string literals (`import "mo`, `orientation = "verti`) are unterminated, so
     * the lexer produces no STRING token and AST-based completion cannot see them. Detect the
     * open quote from the raw source line instead and answer from the same value domains the
     * AST path uses: workspace module names after require/import, layout value domains after
     * a `key = "` prefix. Null when the caret is not inside an unterminated single-line quote.
     */
    private fun unterminatedStringCompletions(
        semanticFile: WorkspaceSemanticFile,
        position: Position
    ): List<CompletionItem>? {
        val line = semanticFile.source.lines().getOrNull(position.line - 1) ?: return null
        val column = position.column - 1
        if (column < 0 || column > line.length) { return null }
        val prefix = line.substring(0, column)
        var openQuote: Char? = null
        var openIndex = -1
        var index = 0
        while (index < prefix.length) {
            when (val ch = prefix[index]) {
                '\\' -> index++
                '"', '\'' -> {
                    if (openQuote == ch) {
                        openQuote = null
                    } else if (openQuote == null) {
                        openQuote = ch
                        openIndex = index
                    }
                }
            }
            index++
        }
        if (openQuote == null) { return null }
        val before = prefix.substring(0, openIndex).trimEnd()
        return when {
            moduleCallBeforeQuote.containsMatchIn(before) ->
                resolver.completionModuleNames().map { plainCompletionItem(it, CompletionItemKind.MODULE) }

            else -> {
                val key = tableKeyBeforeQuote.find(before)?.groupValues?.get(1)
                key?.let { LuaLayoutValueDomains.forKey(it) }.orEmpty()
                    .map { plainCompletionItem(it, CompletionItemKind.KEYWORD) }
                    .ifEmpty { null }
            }
        }
    }

    /** Completion item whose label, insert text and sort text all carry [name]. */
    private fun plainCompletionItem(name: String, kind: CompletionItemKind) = CompletionItem(label = name, kind = kind, insertText = name, sortText = name)

    /** Completion item with label/insertText [name] and priority-prefixed sort text. */
    private fun completionItem(
        name: String,
        kind: CompletionItemKind,
        detail: String?,
        sortPrefix: String
    ) = CompletionItem(
        label = name,
        kind = kind,
        detail = detail,
        insertText = name,
        sortText = "$sortPrefix:$name"
    )

    /** True when [identifier] is the member selector of `base.name`, not a standalone variable reference. */
    private fun isMemberAccessIdentifier(identifier: Identifier): Boolean {
        val parent = runCatching { identifier.parent }.getOrNull()
        return parent is MemberExpression && parent.identifier === identifier
    }

    /**
     * [node] as a plain variable-reference identifier: the cast plus the shared member-selector
     * guard (a `dep.missing` caret identifier must never resolve as the require-local itself;
     * callers that want the receiver module pass the receiver node instead). Null for both
     * non-identifiers and member selectors.
     */
    private fun plainIdentifier(node: BaseASTNode?): Identifier? {
        val identifier = node as? Identifier ?: return null
        return identifier.takeUnless { isMemberAccessIdentifier(it) }
    }

    /** [node] itself as a MemberExpression, or the MemberExpression the identifier belongs to. */
    private fun memberExpressionForNode(node: BaseASTNode?): MemberExpression? {
        return when (node) {
            is MemberExpression -> node
            is Identifier -> runCatching { node.parent }.getOrNull() as? MemberExpression
            else -> null
        }
    }

    private fun memberAccessAt(node: BaseASTNode?, position: Position): MemberExpression? {
        val memberExpression = memberExpressionForNode(node) ?: return null
        // Completed member-name carets only. Receiver identifiers must not enter member mode.
        val onMemberName = when {
            node is MemberExpression && rangeContains(memberExpression.identifier.range, position) -> true
            node is Identifier && memberExpression.identifier === node -> true
            else -> false
        }
        if (!onMemberName) { return null }
        return memberExpression.takeIf { it.isCompletionSite(position) }
    }

    private fun SymbolKind.toCompletionItemKind(): CompletionItemKind = when (this) {
        SymbolKind.FUNCTION -> CompletionItemKind.FUNCTION
        SymbolKind.METHOD -> CompletionItemKind.METHOD
        SymbolKind.FIELD -> CompletionItemKind.FIELD
        SymbolKind.CLASS -> CompletionItemKind.CLASS
        SymbolKind.TYPE_ALIAS -> CompletionItemKind.TYPE_ALIAS
        SymbolKind.MODULE -> CompletionItemKind.MODULE
        SymbolKind.PARAMETER -> CompletionItemKind.PARAMETER
        SymbolKind.LOCAL,
        SymbolKind.VARIABLE -> CompletionItemKind.VARIABLE
        SymbolKind.UNKNOWN -> CompletionItemKind.TEXT
    }

    private fun isUserFacingExportName(name: String): Boolean = name.isNotBlank() && !name.startsWith("__")

    private fun importCompletions(
        path: VirtualPath,
        position: Position,
        semanticFile: WorkspaceSemanticFile,
        nameFilter: ((String) -> Boolean)? = null
    ): List<CompletionItem> {
        val localNames = visibleLocalValueNames(semanticFile, position)
        return resolver.importedSymbolsFor(path, nameFilter = nameFilter)
            .values
            .asSequence()
            .filter { it.alias !in localNames }
            .distinctBy { it.alias }
            .map { symbol ->
                completionItem(symbol.alias, importedCompletionKind(symbol), symbol.valueType.displayName, "8:0000")
            }
            .toList()
    }

    /**
     * Import-surface kinds mirror the member rule but only lift UNKNOWN/TEXT: a FIELD-kind
     * import keeps FIELD even when its value is a callable display.
     */
    private fun importedCompletionKind(symbol: WorkspaceImportedSymbol): CompletionItemKind {
        val base = symbol.kind.toCompletionItemKind()
        return callableUpgradedCompletionKind(base, base == CompletionItemKind.TEXT && symbol.valueType.displayName.trim().startsWith("fun("))
    }

    private fun mergeCompletions(
        base: List<CompletionItem>,
        imports: List<CompletionItem>
    ): List<CompletionItem> {
        if (imports.isEmpty()) { return base }
        val byLabel = linkedMapOf<String, CompletionItem>()
        base.forEach { byLabel[it.label] = it }
        imports.forEach { item ->
            val existing = byLabel[item.label]
            // Prefer MODULE import kinds so Java/Android class completions do not degrade to VARIABLE.
            if (existing == null || (existing.kind != CompletionItemKind.MODULE && item.kind == CompletionItemKind.MODULE)) { byLabel[item.label] = item }
        }
        return byLabel.values.sortedBy { it.sortText ?: it.label }
    }

    private fun importedSymbolAt(
        path: VirtualPath,
        position: Position,
        node: BaseASTNode?,
        semanticFile: WorkspaceSemanticFile
    ): WorkspaceImportedSymbol? {
        val identifier = plainIdentifier(node) ?: return null
        if (visibleLocalValueNames(semanticFile, position).contains(identifier.name)) { return null }
        return resolver.importedSymbolFor(path, identifier.name)
    }

    private fun importedSymbol(imported: WorkspaceImportedSymbol): Symbol =
        surfaceSymbol(imported.alias, imported.kind, null, importedTypeInfo(imported.valueType), imported.valueType.displayName, importedSymbolHandle(imported))

    /**
     * Shared api.Symbol assembly for hover/definition surfaces: type and declaredType always
     * carry the same TypeInfo, and detail mirrors the display value.
     */
    private fun surfaceSymbol(
        name: String,
        kind: SymbolKind,
        range: Range?,
        typeInfo: TypeInfo,
        detail: String?,
        symbolId: String
    ): Symbol = Symbol(
        name = name,
        kind = kind,
        range = range,
        type = typeInfo,
        declaredType = typeInfo,
        detail = detail,
        symbolId = symbolId
    )

    private fun importedTypeInfo(type: Type): TypeInfo = TypeInfo(
        displayName = type.displayName,
        kind = when (type) {
            is ModuleType -> TypeInfoKind.MODULE
            is CallableType -> TypeInfoKind.FUNCTION
            is ClassType -> TypeInfoKind.CLASS
            is TableType -> TypeInfoKind.TABLE
            else -> TypeInfoKind.UNKNOWN
        },
        moduleName = (type as? ModuleType)?.moduleName
    )

    /** LOCAL declaration ids anchored at a local statement's initializer list. */
    private fun localStatementInitDeclarationIds(semanticFile: WorkspaceSemanticFile, statement: LocalStatement): Set<DeclarationId> =
        statement.init.mapNotNull { identifier ->
            semanticFile.snapshot.binder.declarationIndex
                .getDeclarations(identifier)
                .firstOrNull { it.kind == DeclarationKind.LOCAL }
                ?.id
        }.toSet()

    private fun localInitializerDeclarationIds(
        semanticFile: WorkspaceSemanticFile,
        node: BaseASTNode
    ): Set<DeclarationId> {
        var current: BaseASTNode? = node
        while (current != null) {
            val parent = runCatching { current.parent }.getOrNull()
            if (parent is LocalStatement) {
                if (parent.variables.none { it === current }) {
                    return emptySet()
                }
                return localStatementInitDeclarationIds(semanticFile, parent)
            }
            current = parent as? BaseASTNode
        }
        return emptySet()
    }

    /**
     * VALUE-namespace declarations along the scope chain at [position], innermost scope first
     * and reversed declaration order within a scope — the shared walk behind both
     * [visibleLocalValueNames] and [visibleLocalValueDeclaration].
     */
    private fun visibleValueDeclarations(
        semanticFile: WorkspaceSemanticFile,
        position: Position
    ): Sequence<BinderDeclaration> = generateSequence(
        semanticFile.snapshot.binder.positionQueries.scopeForFreePositionQuery(position)
    ) { it.parentId?.let(semanticFile.snapshot.binder.scopeGraph::getScope) }
        .flatMap { scope ->
            scope.declarationIds.asReversed().asSequence()
                .mapNotNull(semanticFile.snapshot.binder.declarationIndex::getDeclaration)
        }
        .filter { it.kind.namespace == DeclarationNamespace.VALUE }

    private fun visibleLocalValueNames(semanticFile: WorkspaceSemanticFile, position: Position): Set<String> =
        visibleValueDeclarations(semanticFile, position)
            .filter { isDeclarationVisibleAt(it, position) }
            .mapTo(linkedSetOf()) { it.name }

    private fun visibleLocalValueDeclaration(
        semanticFile: WorkspaceSemanticFile,
        name: String,
        position: Position,
        excludedDeclarations: Set<DeclarationId> = emptySet()
    ): BinderDeclaration? =
        visibleValueDeclarations(semanticFile, position).firstOrNull { declaration ->
            declaration.name == name &&
                declaration.id !in excludedDeclarations &&
                isDeclarationVisibleAt(declaration, position)
        }

    private fun isDeclarationVisibleAt(declaration: BinderDeclaration, position: Position): Boolean {
        // Ambient binder builtins (require/print/import helpers) are file-global. Their
        // documented ranges come from overlay virtual documents and must not gate visibility
        // against real file positions used by require-local / free-id navigation (TASK-664).
        if (declaration.origin == DeclarationOrigin.BUILTIN) { return true }
        val range = declaration.range ?: return true
        return comparePositions(range.start, position) <= 0
    }

    // Position comparisons share the binder's PositionOrdering helpers (same line/column
    // order); the private copies that mirrored them byte-for-byte were removed. Note the
    // binder's rangeContains is END-EXCLUSIVE while this facade's is END-INCLUSIVE on
    // purpose: hover/definition membership here treats a caret exactly at range.end as
    // inside; completionMemberAt independently permits the trailing caret.

    private fun syntheticModuleRange(moduleName: String): Range {
        return Range(start = Position(1, 1), end = Position(1, maxOf(moduleName.length + 1, 2)))
    }

    private fun exportSymbol(export: WorkspaceModuleResolver.ResolvedExportMember): Symbol =
        surfaceSymbol(export.member.name, export.member.kind, export.member.range, exportTypeInfo(export), export.type.displayName, export.handle)

    private fun exportTypeInfo(export: WorkspaceModuleResolver.ResolvedExportMember): TypeInfo {
        val memberType = export.type
        val moduleType = memberType as? ModuleType
        val isFunctionLike = export.member.kind == SymbolKind.FUNCTION ||
            export.member.kind == SymbolKind.METHOD ||
            memberType.displayName.isCallableDisplay()
        return TypeInfo(
            displayName = memberType.displayName,
            detail = memberType.displayName,
            kind = when {
                moduleType != null -> TypeInfoKind.MODULE
                isFunctionLike -> TypeInfoKind.FUNCTION
                export.member.kind == SymbolKind.CLASS -> TypeInfoKind.CLASS
                else -> TypeInfoKind.UNKNOWN
            },
            moduleName = moduleType?.moduleName
        )
    }

    private fun rangeContains(range: Range, position: Position): Boolean {
        return comparePositions(range.start, position) <= 0 && comparePositions(position, range.end) <= 0
    }

    private fun hasBuiltinRequireCallSite(path: VirtualPath, moduleName: String): Boolean {
        if (moduleName.isBlank()) { return false }
        val fileSnapshot = snapshot.files[path] ?: return false
        val semanticFile = fileSnapshot.semanticFile ?: return false
        val facts = fileSnapshot.documentFacts ?: return false
        // Facts record every syntactic require("name") call range (shadowed requires
        // included). Each candidate range is verified through builtinRequireModuleName —
        // the same un-shadowed builtin callee test the former full-chunk AST walk
        // applied — so this consumer recognizes exactly the call forms the walk did,
        // at fact-lookup cost instead of an AST traversal per query.
        return facts.requires.any { fact ->
            fact.moduleName == moduleName &&
                semanticFile.nodeAt(fact.range.start)
                    ?.let(::enclosingCallExpression)
                    ?.let { builtinRequireModuleName(semanticFile, it) == moduleName } == true
        }
    }

    private fun requireCallSite(path: VirtualPath, position: Position): String? {
        val semanticFile = snapshot.files[path]?.semanticFile ?: return null
        val node = semanticFile.nodeAt(position)
        // Prefer parent-linked call, then recover string-call shape when ConstantNode.parent is unset.
        val call = enclosingCallExpression(node)
            ?: (node as? ConstantNode)?.let { importStringCallContaining(semanticFile, it) }
            ?: return null
        return builtinRequireModuleName(semanticFile, call)
    }

    private fun enclosingCallExpression(node: BaseASTNode?): CallExpression? {
        var current = node
        while (current != null) {
            if (current is CallExpression) { return current }
            current = runCatching { current.parent }.getOrNull()
        }
        return null
    }

    private data class ImportedSymbolIdentity(
        val providerPath: VirtualPath,
        val alias: String
    ) {
        companion object {
            fun parse(handle: String?): ImportedSymbolIdentity? {
                return parseColonPathHandle(handle, "imported:")
                    ?.let { (path, alias) -> ImportedSymbolIdentity(path, alias) }
            }
        }
    }
}

data class WorkspaceDocumentSymbol(
    val name: String,
    val kind: SymbolKind,
    val range: Range,
    val selectionRange: Range = range,
    val detail: String? = null,
    val children: List<WorkspaceDocumentSymbol> = emptyList()
)

data class WorkspaceSymbolEntry(val name: String, val kind: SymbolKind, val path: VirtualPath, val range: Range, val containerName: String? = null)
