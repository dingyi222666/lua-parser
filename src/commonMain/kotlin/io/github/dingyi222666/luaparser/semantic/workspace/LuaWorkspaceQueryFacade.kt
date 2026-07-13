package io.github.dingyi222666.luaparser.semantic.workspace

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
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SignatureHelp
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo
import io.github.dingyi222666.luaparser.semantic.api.TypeInfoKind
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.model.toSymbolHandle

class LuaWorkspaceQueryFacade(
    private val snapshot: WorkspaceSnapshot
) {
    private val resolver = WorkspaceModuleResolver(snapshot)

    fun diagnostics(path: VirtualPath): List<Diagnostic> {
        return snapshot.files[path]?.semanticFile?.model?.getDiagnostics().orEmpty()
    }

    fun lookupModule(moduleName: String): WorkspaceModuleLookupResult {
        val provider = resolver.activeProvider(moduleName)
        return WorkspaceModuleLookupResult(
            moduleName = moduleName,
            provider = provider,
            exportSurface = provider?.let(resolver::exportSurface)
        )
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
        val callSite = requireCallSite(path, position)
        if (callSite != null) {
            return workspaceModuleLookup(path, callSite.moduleName)
        }
        // Position-based resolve is also restricted to real builtin require form:
        // require-backed locals only count when their initializer is an unshadowed
        // builtin require("...") call (requiredModuleNameForDeclaration / builtinRequireModuleName).
        return requireBackedModuleNameAtPosition(path, position)?.let { workspaceModuleLookup(path, it) }
    }

    fun completions(path: VirtualPath, position: Position): List<CompletionItem> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val baseCompletions = semanticFile?.model?.getCompletionsAt(position).orEmpty()
        if (semanticFile == null) {
            return baseCompletions
        }
        // Prefer require-backed export members whenever a member site is detectable.
        // Incomplete trailing-dot forms (`local x = pkg.|` / `U.|`) often miss half-open
        // nodeAt hits; fall back to source-aware recovery so barrel reexport surfaces
        // (leafRun/leafValue) still complete instead of free-id globals.
        val memberExpression = memberExpressionAt(semanticFile, position)
            ?: incompleteMemberExpressionFromSource(semanticFile, position)
        if (memberExpression != null) {
            // Member-context completions must not merge lexical/import free-identifier
            // candidates (Monaco demo: greeter. flooded with locals + builtins). Prefer
            // require-backed export members when available; otherwise keep model member surface.
            val workspaceMembers = workspaceMemberCompletions(path, semanticFile, memberExpression)
            if (workspaceMembers.isNotEmpty()) {
                return workspaceMembers
            }
            return baseCompletions
        }
        return mergeCompletions(baseCompletions, importCompletions(path, position, semanticFile))
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
            preferredHoverType(
                model.getDeclaredType(resolved),
                model.getInferredType(resolved)
            )
        }
        val preferred = if (symbol?.symbolId?.startsWith("builtin-import:") == true) {
            symbol.type
        } else if (
            requireLocal != null &&
            memberExport == null &&
            requireLocal.type?.kind == TypeInfoKind.MODULE
        ) {
            // Require-alias locals must surface MODULE typing even when the node/model
            // type is a weak string-literal-ish display (`"greeter"`).
            requireLocal.type
        } else {
            // Prefer import-call / require-alias MODULE typing (carries moduleName) over coarse
            // node types. Always run preferredHoverType so structural table literals collapse
            // to "table" even when the symbol surface has a null type and only nodeType is available.
            preferredHoverType(
                preferredHoverType(
                    importCallLocal?.type
                        ?: requireLocal?.type
                        ?: exportType
                        ?: declaredOrInferred
                        ?: symbol?.type
                        ?: memberType
                        ?: nodeType,
                    exportType ?: requireLocal?.type ?: memberType ?: nodeType
                        ?: symbol?.type ?: declaredOrInferred
                ),
                declaredOrInferred
            )
        }
        return WorkspaceHoverResult(
            path = path,
            position = position,
            symbol = symbol,
            typeInfo = preferred
        )
    }

    fun gotoDefinition(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val node = semanticFile?.nodeAt(position)
        val importTargetDefinition = semanticFile?.let { importTargetDefinition(path, it, node) }
        if (importTargetDefinition != null) {
            return listOf(importTargetDefinition)
        }
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
        val bindClassLocalDefinition = semanticFile?.let {
            bindClassLocalDefinition(it, path, node, symbol)
        }
        if (bindClassLocalDefinition != null) {
            return listOf(bindClassLocalDefinition)
        }
        // Prefer require()-initialized local aliases to the provider module export/return site so
        // goto on `local dep = require("dep")` / `return dep` lands on dep.lua (TASK-664).
        // Do this only when the caret is NOT on a member-access identifier; those use export
        // member resolution below and must stay empty when the export is missing.
        val memberAccess = memberAccessAt(node, position)
        if (memberAccess == null) {
            val requireBackedLocalDefinition = semanticFile?.let {
                requireBackedLocalDefinition(it, path, node, symbol)
            }
            if (requireBackedLocalDefinition != null) {
                return listOf(requireBackedLocalDefinition)
            }
            // Locals initialized from require-backed exports (`local parse = url.parse`) must
            // navigate to the provider export, not the local binding site (TASK-674).
            val requireBackedMemberLocalDefinition = semanticFile?.let {
                requireBackedMemberLocalDefinition(it, path, node, symbol)
            }
            if (requireBackedMemberLocalDefinition != null) {
                return listOf(requireBackedMemberLocalDefinition)
            }
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
            val freeId = when (val n = node) {
                is Identifier -> n
                is CallExpression -> n.base as? Identifier
                is io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression ->
                    n.base as? Identifier
                else -> null
            } ?: identifierCoveringPosition(semanticFile, position)
            val parent = freeId?.let { runCatching { it.parent }.getOrNull() }
            val onMemberSelector = freeId != null &&
                parent is MemberExpression &&
                parent.identifier === freeId
            val freeName = freeId?.name?.takeIf { it.isNotBlank() }
                ?: symbol?.name?.takeIf { it.isNotBlank() && !onMemberSelector }
            if (freeName != null && !onMemberSelector) {
                // Probe at caret and at the identifier start so shadow locals win even when
                // binder symbolId points at a foreign export handle (util.foo vs local foo).
                val visibleLocal = visibleLocalValueDeclaration(semanticFile, freeName, position)
                    ?: freeId?.let {
                        visibleLocalValueDeclaration(semanticFile, freeName, it.range.start)
                    }
                    ?: freeId?.let {
                        visibleLocalValueDeclaration(semanticFile, freeName, it.range.end)
                    }
                // Keep require("mod") aliases and `local x = mod.member` rebinds on the
                // provider/export path above — only true non-import shadows stay file-local.
                val isRequireOrMemberImport =
                    visibleLocal != null &&
                        (
                            requiredModuleNameForDeclaration(semanticFile, visibleLocal) != null ||
                                memberInitializerForLocal(visibleLocal) != null
                            )
                if (
                    visibleLocal != null &&
                    !isRequireOrMemberImport &&
                    visibleLocal.origin != DeclarationOrigin.BUILTIN &&
                    (
                        visibleLocal.kind == DeclarationKind.LOCAL ||
                            visibleLocal.kind == DeclarationKind.FUNCTION ||
                            visibleLocal.kind == DeclarationKind.PARAMETER
                        )
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
        val importDefinition = symbol?.symbolId?.let(::importedSymbolLocation)
            ?: importedAt?.let {
                WorkspaceLocation(it.providerPath, syntheticModuleRange(it.alias))
            }
            ?: semanticFile?.let { importedSymbolAt(path, position, node, it) }?.let {
                WorkspaceLocation(it.providerPath, syntheticModuleRange(it.alias))
            }
            ?: semanticFile?.let { importCallLocalDefinition(it, path, node, symbol) }
            ?: semanticFile?.let { bindClassLocalDefinition(it, path, node, symbol) }
        if (importDefinition != null) {
            return listOf(importDefinition)
        }
        val requireCallDefinition = requireCallDefinition(path, position)
        if (requireCallDefinition != null) {
            return listOf(requireCallDefinition)
        }
        if (memberExport != null) {
            return listOf(exportLocation(memberExport))
        }
        val export = symbol?.symbolId?.let(resolver::exportedMemberByHandle)
            ?: resolver.exportAt(path, position)
            ?: exportFromMemberBase(node, symbol)
        if (export != null) {
            return listOf(exportLocation(export))
        }
        // On require-backed member access, never invent a definition from the provider module
        // root / local range when the export member itself is missing (TASK-664).
        if (memberAccess != null && semanticFile != null) {
            val requireReceiver = resolvedRequireForNavigationReceiver(
                semanticFile,
                path,
                memberAccess.base
            )
            if (requireReceiver != null) {
                return emptyList()
            }
        }
        val requireBackedDefinition = semanticFile?.let { requireBackedDefinition(it, path, node, symbol) }
        if (requireBackedDefinition != null) {
            // Guard: only for non-member-access carets (member miss already returned empty above).
            if (memberAccess == null) {
                return listOf(requireBackedDefinition)
            }
        }
        return symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()
    }

    fun declaration(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val node = semanticFile?.nodeAt(position)
        val importTargetDefinition = semanticFile?.let { importTargetDefinition(path, it, node) }
        if (importTargetDefinition != null) {
            return listOf(importTargetDefinition)
        }
        val symbol = semanticFile?.model?.getSymbolAt(position)
            ?: semanticFile?.let { importedSymbolAt(path, position, node, it)?.let(::importedSymbol) }
        val memberExport = semanticFile?.let { navigationExportedMemberAt(it, path, position, node) }

        if (memberExport != null) {
            return listOf(exportLocation(memberExport))
        }

        val localDeclaration = semanticFile?.let { declarationLocationForSymbol(it, path, symbol?.symbolId) }
        // Local AST declarations win only when they are true locals, not imported MODULE aliases.
        if (localDeclaration != null && symbol?.kind != SymbolKind.MODULE) {
            return listOf(localDeclaration)
        }

        // Prefer member-owning createProxy / bindClass provider over weak imported handles so
        // multi-interface proxy.compare lands on Comparator rather than the first interface arm.
        val bindClassLocalDeclaration = semanticFile?.let {
            bindClassLocalDefinition(it, path, node, symbol)
        }
        if (bindClassLocalDeclaration != null) {
            return listOf(bindClassLocalDeclaration)
        }

        val importDeclaration = symbol?.symbolId?.let(::importedSymbolLocation)
            ?: semanticFile?.let { importedSymbolAt(path, position, node, it) }?.let {
                WorkspaceLocation(it.providerPath, syntheticModuleRange(it.alias))
            }
        if (importDeclaration != null) {
            return listOf(importDeclaration)
        }

        val export = symbol?.symbolId?.let(resolver::exportedMemberByHandle)
            ?: resolver.exportAt(path, position)
            ?: exportFromMemberBase(node, symbol)
        if (export != null) {
            return listOf(exportLocation(export))
        }

        val requireCallDeclaration = requireCallDefinition(path, position)
        if (requireCallDeclaration != null) {
            return listOf(requireCallDeclaration)
        }

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
            val requireReceiver = resolvedRequireForNavigationReceiver(
                semanticFile,
                path,
                memberAccess.base
            )
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
        if (localReferences != null) {
            return localReferences
        }

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
            val range = export.member.range ?: syntheticModuleRange(export.member.name)
            val providerPath = providerPathForExport(export)
            locations["${providerPath.value}:${range.start.line}:${range.start.column}"] = WorkspaceLocation(providerPath, range)
        }

        snapshot.files.values
            .mapNotNull { it.semanticFile }
            .forEach { file ->
                file.memberExpressions.forEach { expression ->
                    val usage = file.model.getSymbolAt(expression.identifier.range.start)
                    val workspaceExport = exportedMemberForExpression(file, file.path, expression)
                    if (usage?.symbolId == targetHandle || workspaceExport?.handle == targetHandle) {
                        locations["${file.path.value}:${expression.identifier.range.start.line}:${expression.identifier.range.start.column}"] =
                            WorkspaceLocation(file.path, expression.identifier.range)
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

        symbol?.range?.let { range ->
            highlights["${path.value}:${range.start.line}:${range.start.column}"] = WorkspaceLocation(path, range)
        }

        references(path, position)
            .asSequence()
            .filter { includeCrossFileHighlights || it.path == path }
            .forEach { location ->
                highlights["${location.path.value}:${location.range.start.line}:${location.range.start.column}"] = location
            }

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

        declarations.forEach { declaration ->
            val ownerId = (declaration.owner as? DeclarationOwner.Declaration)?.declarationId
            if (ownerId != null && ownerId in byId) {
                childrenByOwner.getOrPut(ownerId) { mutableListOf() }.add(declaration)
            } else {
                roots += declaration
            }
        }

        fun buildNode(declaration: BinderDeclaration): WorkspaceDocumentSymbol {
            val range = declaration.range
                ?: declaration.anchorNode?.range
                ?: syntheticModuleRange(declaration.name)
            val children = childrenByOwner[declaration.id]
                .orEmpty()
                .sortedWith(
                    compareBy<BinderDeclaration>(
                        { it.range?.start?.line ?: Int.MAX_VALUE },
                        { it.range?.start?.column ?: Int.MAX_VALUE },
                        { it.name }
                    )
                )
                .map(::buildNode)
            return WorkspaceDocumentSymbol(
                name = declaration.name,
                kind = declaration.kind.toWorkspaceSymbolKind(),
                range = range,
                selectionRange = range,
                detail = declaration.declaredType?.displayName,
                children = children
            )
        }

        val declarationNodes = roots
            .sortedWith(
                compareBy<BinderDeclaration>(
                    { it.range?.start?.line ?: Int.MAX_VALUE },
                    { it.range?.start?.column ?: Int.MAX_VALUE },
                    { it.name }
                )
            )
            .map(::buildNode)

        val exportNodes = hierarchicalExportSymbols(path)
        return mergeDocumentSymbolRoots(declarationNodes, exportNodes)
    }

    fun workspaceSymbolEntries(query: String): List<WorkspaceSymbolEntry> {
        val normalizedQuery = query.trim()
        return allWorkspaceSymbolEntries()
            .asSequence()
            .filter { normalizedQuery.isBlank() || it.name.contains(normalizedQuery, ignoreCase = true) }
            .toList()
    }

    private fun allWorkspaceSymbolEntries(): List<WorkspaceSymbolEntry> {
        val entries = buildList {
            snapshot.files.forEach { (path, file) ->
                addAll(fileSymbolEntries(path, file))
            }
            snapshot.extraProviders.forEach { (path, file) ->
                moduleWorkspaceSymbolEntry(path, file)?.let(::add)
                addAll(fileSymbolEntries(path, file))
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
            .sortedWith(
                compareBy<WorkspaceSymbolEntry>(
                    { it.name },
                    { it.path.value },
                    { it.range.start.line },
                    { it.range.start.column }
                )
            )
    }

    private fun fileSymbolEntries(
        path: VirtualPath,
        file: WorkspaceSnapshot.FileSnapshot
    ): List<WorkspaceSymbolEntry> {
        val semanticFile = file.semanticFile
        val declarations = if (semanticFile != null) {
            declarationWorkspaceSymbolEntries(
                path,
                semanticFile.snapshot.binder.declarationIndex.declarations
            )
        } else {
            emptyList()
        }
        val exports = moduleExportWorkspaceSymbolEntries(path, file, declarations)
        return declarations + exports
    }

    private fun declarationWorkspaceSymbolEntries(
        path: VirtualPath,
        declarations: List<BinderDeclaration>
    ): List<WorkspaceSymbolEntry> {
        val declarationsById = declarations.associateBy(BinderDeclaration::id)
        return declarations
            .asSequence()
            .filter(::isNavigableDocumentSymbolDeclaration)
            .mapNotNull { declaration ->
                declaration.range?.let { range ->
                    WorkspaceSymbolEntry(
                        name = declaration.name,
                        kind = declaration.kind.toWorkspaceSymbolKind(),
                        path = path,
                        range = range,
                        containerName = workspaceContainerNameFor(declaration, declarationsById)
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
        return WorkspaceSymbolEntry(
            name = moduleName,
            kind = SymbolKind.MODULE,
            path = path,
            range = syntheticModuleRange(moduleName),
            containerName = null
        )
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
            .map { workspaceSymbolEntryKey(it.path, it.name, it.kind, it.range) }
            .toSet()

        return surface.members
            .asSequence()
            .filter { isUserFacingExportName(it.name) }
            .map { member ->
                val range = member.range ?: syntheticModuleRange(member.name)
                WorkspaceSymbolEntry(
                    name = member.name,
                    kind = member.kind,
                    path = path,
                    range = range,
                    containerName = moduleName
                )
            }
            .filterNot { workspaceSymbolEntryKey(it.path, it.name, it.kind, it.range) in declarationKeys }
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
        if (members.isEmpty()) {
            return emptyList()
        }

        data class MutableSymbol(
            val name: String,
            var kind: SymbolKind,
            var range: Range,
            val children: LinkedHashMap<String, MutableSymbol> = linkedMapOf()
        ) {
            fun toDocumentSymbol(): WorkspaceDocumentSymbol {
                val childSymbols = children.values
                    .sortedWith(
                        compareBy(
                            { it.range.start.line },
                            { it.range.start.column },
                            { it.name }
                        )
                    )
                    .map { it.toDocumentSymbol() }
                return WorkspaceDocumentSymbol(
                    name = name,
                    kind = kind,
                    range = range,
                    selectionRange = range,
                    children = childSymbols
                )
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
            .sortedWith(
                compareBy(
                    { it.range.start.line },
                    { it.range.start.column },
                    { it.name }
                )
            )
            .map { it.toDocumentSymbol() }
    }

    private fun mergeDocumentSymbolRoots(
        declarations: List<WorkspaceDocumentSymbol>,
        exports: List<WorkspaceDocumentSymbol>
    ): List<WorkspaceDocumentSymbol> {
        if (exports.isEmpty()) {
            return declarations
        }
        if (declarations.isEmpty()) {
            return exports
        }

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
            .sortedWith(
                compareBy(
                    { it.range.start.line },
                    { it.range.start.column },
                    { it.name }
                )
            )
            .toList()
    }

    private fun preferredDocumentSymbolKind(left: SymbolKind, right: SymbolKind): SymbolKind {
        val rank = listOf(
            SymbolKind.MODULE,
            SymbolKind.CLASS,
            SymbolKind.METHOD,
            SymbolKind.FUNCTION,
            SymbolKind.FIELD,
            SymbolKind.TYPE_ALIAS,
            SymbolKind.LOCAL,
            SymbolKind.VARIABLE,
            SymbolKind.PARAMETER,
            SymbolKind.UNKNOWN
        )
        return listOf(left, right).minBy { rank.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
    }

    private fun isNavigableDocumentSymbolDeclaration(declaration: BinderDeclaration): Boolean {
        if (declaration.name.isBlank() || declaration.range == null) {
            return false
        }
        if (declaration.origin == DeclarationOrigin.BUILTIN) {
            return false
        }
        return declaration.kind != DeclarationKind.PARAMETER && declaration.kind != DeclarationKind.TYPE_PARAMETER
    }

    private fun workspaceContainerNameFor(
        declaration: BinderDeclaration,
        declarationsById: Map<DeclarationId, BinderDeclaration>
    ): String? {
        val ownerId = (declaration.owner as? DeclarationOwner.Declaration)?.declarationId ?: return null
        return declarationsById[ownerId]?.name?.takeIf { it.isNotBlank() }
    }

    private fun workspaceSymbolEntryKey(
        path: VirtualPath,
        name: String,
        kind: SymbolKind,
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

    private fun DeclarationKind.toWorkspaceSymbolKind(): SymbolKind {
        return when (this) {
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
            isBareWeakHoverType(primary) &&
                fallback != null &&
                !isBareWeakHoverType(fallback) -> fallback
            isBareWeakHoverType(primary) &&
                fallback != null &&
                (isPreferredHoverStructuredKind(fallback.kind) ||
                    fallback.displayName.contains("fun(") ||
                    fallback.displayName.contains("fun<") ||
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
            !isPreferredHoverStructuredKind(primary.kind) &&
                fallback != null &&
                isPreferredHoverStructuredKind(fallback.kind) &&
                isBareWeakHoverType(primary) -> fallback
            !primary.displayName.contains("fun(") &&
                fallback?.displayName?.contains("fun(") == true -> fallback
            !primary.displayName.contains("fun(") &&
                fallback?.displayName?.contains("fun<") == true -> fallback
            isBareWeakHoverType(primary) &&
                fallback != null &&
                (fallback.kind == TypeInfoKind.CLASS ||
                    fallback.kind == TypeInfoKind.FUNCTION ||
                    fallback.kind == TypeInfoKind.MODULE) -> fallback
            // Prefer declared/inferred CLASS/FUNCTION kinds when primary is only UNKNOWN.
            primary.kind == TypeInfoKind.UNKNOWN &&
                fallback != null &&
                isPreferredHoverStructuredKind(fallback.kind) -> fallback
            else -> primary
        }
    }

    private fun isBareWeakHoverType(type: TypeInfo): Boolean {
        val display = type.displayName
        return display.isBlank() ||
            display == "unknown" ||
            display == "any" ||
            (type.kind == TypeInfoKind.UNKNOWN &&
                !display.contains("fun(") &&
                !display.startsWith("Array<") &&
                type.moduleName.isNullOrBlank())
    }

    private fun isPreferredHoverStructuredKind(kind: TypeInfoKind): Boolean {
        return kind == TypeInfoKind.FUNCTION ||
            kind == TypeInfoKind.CLASS ||
            kind == TypeInfoKind.MODULE
    }

    private fun memberReceiverType(
        model: io.github.dingyi222666.luaparser.semantic.model.SemanticModel,
        node: BaseASTNode?,
        symbol: io.github.dingyi222666.luaparser.semantic.api.Symbol?
    ): TypeInfo? {
        val memberExpression = node as? MemberExpression ?: return null
        return symbol?.kind
            ?.takeIf { it == io.github.dingyi222666.luaparser.semantic.api.SymbolKind.FIELD || it == io.github.dingyi222666.luaparser.semantic.api.SymbolKind.METHOD }
            ?.let { model.getTypeAt(memberExpression.base) }
    }

    private fun exportFromMemberBase(
        node: BaseASTNode?,
        symbol: io.github.dingyi222666.luaparser.semantic.api.Symbol?
    ): WorkspaceModuleResolver.ResolvedExportMember? {
        val memberExpression = when (node) {
            is MemberExpression -> node
            is Identifier -> runCatching { node.parent }.getOrNull() as? MemberExpression
            else -> null
        } ?: return null
        if (memberExpression.base !== node && node !is MemberExpression) {
            return null
        }
        val symbolId = symbol?.symbolId ?: return null
        val identity = ModuleExportIdentity.parse(symbolId) ?: return null
        return resolver.exportedMemberByHandle(identity.asHandle())
    }

    private fun requireBackedDefinition(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        node: BaseASTNode?,
        symbol: io.github.dingyi222666.luaparser.semantic.api.Symbol?
    ): WorkspaceLocation? {
        val moduleName = requireBackedModuleNames(semanticFile, node, symbol)
            .firstNotNullOfOrNull { candidate ->
                resolveWorkspaceRequire(path, candidate)?.provider?.path?.let { candidate to it }
            }
            ?: return null
        return WorkspaceLocation(moduleName.second, syntheticModuleRange(moduleName.first))
    }

    private fun requireBackedLocalDefinition(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        node: BaseASTNode?,
        symbol: io.github.dingyi222666.luaparser.semantic.api.Symbol?
    ): WorkspaceLocation? {
        val identifier = node as? Identifier ?: return null
        // Never treat a member-access identifier (dep.missing) as the require-local itself.
        val parent = runCatching { identifier.parent }.getOrNull()
        if (parent is MemberExpression && parent.identifier === identifier) {
            return null
        }
        val moduleName = resolveRequiredModuleNameForLocal(semanticFile, symbol?.symbolId, identifier) ?: return null
        val providerPath = resolveWorkspaceRequire(path, moduleName)?.provider?.path ?: return null
        return WorkspaceLocation(providerPath, syntheticModuleRange(moduleName))
    }

    /**
     * Resolve `local parse = url.parse` (and usages of that local) to the require-backed export
     * member on the provider module (e.g. AndroLua socket.url overlay path).
     */
    private fun requireBackedMemberLocalDefinition(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        node: BaseASTNode?,
        symbol: io.github.dingyi222666.luaparser.semantic.api.Symbol?
    ): WorkspaceLocation? {
        val identifier = node as? Identifier ?: return null
        val parent = runCatching { identifier.parent }.getOrNull()
        if (parent is MemberExpression && parent.identifier === identifier) {
            return null
        }
        val declaration = localDeclarationForSymbol(semanticFile, symbol?.symbolId)
            ?: exactLocalDeclarationForIdentifier(semanticFile, identifier)
            ?: visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.start)
            ?: return null
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) {
            return null
        }
        val memberInitializer = memberInitializerForLocal(declaration) ?: return null
        val exportPath = requireBackedExportPath(semanticFile, path, memberInitializer) ?: return null
        if (exportPath.isEmpty()) {
            return null
        }
        val resolved = resolvedRequireForExportPathRoot(semanticFile, path, memberInitializer)
            ?: resolveRequireByWalkingExpressionBases(semanticFile, path, memberInitializer)
            ?: return null
        val export = resolver.exportedMember(resolved.provider.path, exportPath)
            ?: exportPath.lastOrNull()?.let { leaf ->
                resolver.exportedMember(resolved.provider.path, listOf(leaf))
            }
        if (export != null) {
            return exportLocation(export)
        }
        // Still navigate to the require-backed provider (e.g. AndroLua socket.url overlay)
        // when the export member range is not yet collected, rather than the local binding site.
        return WorkspaceLocation(
            resolved.provider.path,
            syntheticModuleRange(exportPath.last())
        )
    }

    private fun memberInitializerForLocal(declaration: BinderDeclaration): MemberExpression? {
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) {
            return null
        }
        val localStatement = declaration.anchorNode.parent as? LocalStatement ?: return null
        val localIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (localIndex < 0) {
            return null
        }
        return localStatement.variables.getOrNull(localIndex) as? MemberExpression
    }

    private fun requireBackedModuleNames(
        semanticFile: WorkspaceSemanticFile,
        node: BaseASTNode?,
        symbol: io.github.dingyi222666.luaparser.semantic.api.Symbol?
    ): List<String> {
        val identifier = node as? Identifier
        // Member-access carets (dep.missing) must not be treated as require-local aliases.
        // Callers that intentionally want the receiver module should pass the receiver node.
        if (identifier != null) {
            val parent = runCatching { identifier.parent }.getOrNull()
            if (parent is MemberExpression && parent.identifier === identifier) {
                return emptyList()
            }
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
            is MemberExpression -> resolvedRequireForExportPathRoot(semanticFile, path, expression)
            is IndexExpression -> resolveRequireByWalkingExpressionBases(semanticFile, path, expression)
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
                    if (!seen.add(key)) {
                        return null
                    }
                    resolvedRequireForWorkspaceMemberReceiver(semanticFile, path, current)?.let { return it }
                    val symbol = semanticFile.model.getSymbolAt(current.range.start)
                    val declaration = localDeclarationForSymbol(semanticFile, symbol?.symbolId)
                        ?: exactLocalDeclarationForIdentifier(semanticFile, current)
                        ?: visibleLocalValueDeclaration(semanticFile, current.name, current.range.start)
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
            ?: exactLocalDeclarationForIdentifier(semanticFile, identifier)
            ?: visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.start)
            // Synthetic incomplete-member receivers may have approximate ranges; probe end too.
            ?: visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.end)
            ?: return null
        return requiredModuleNameForDeclaration(semanticFile, declaration)
    }

    private fun exportedModuleNameFromMemberBase(
        semanticFile: WorkspaceSemanticFile,
        node: BaseASTNode?
    ): String? {
        val memberExpression = when (node) {
            is MemberExpression -> node
            is Identifier -> runCatching { node.parent }.getOrNull() as? MemberExpression
            else -> null
        } ?: return null
        return receiverModuleName(semanticFile, memberExpression.base)
    }

    private fun resolveRequiredModuleNameForLocal(
        semanticFile: WorkspaceSemanticFile,
        symbolId: String?,
        identifier: Identifier?
    ): String? {
        val declaration = localDeclarationForSymbol(semanticFile, symbolId)
            ?: identifier?.let { exactLocalDeclarationForIdentifier(semanticFile, it) }
            ?: identifier?.let { visibleLocalValueDeclaration(semanticFile, it.name, it.range.start) }
            ?: return null
        return requiredModuleNameForDeclaration(semanticFile, declaration)
    }

    private fun requiredModuleNameForDeclaration(
        semanticFile: WorkspaceSemanticFile,
        declaration: BinderDeclaration,
        visited: MutableSet<DeclarationId> = linkedSetOf()
    ): String? {
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) {
            return null
        }
        if (!visited.add(declaration.id)) {
            return null
        }
        val localStatement = declaration.anchorNode.parent as? LocalStatement ?: return null
        val localIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (localIndex < 0) {
            return null
        }
        return when (val initializer = localStatement.variables.getOrNull(localIndex)) {
            is CallExpression -> builtinRequireModuleName(semanticFile, initializer)
            // local first = dep where dep = require("dep")
            is Identifier -> {
                val target = exactLocalDeclarationForIdentifier(semanticFile, initializer)
                    ?: visibleLocalValueDeclaration(
                        semanticFile,
                        initializer.name,
                        initializer.range.start
                    )
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
                .getPrimaryDeclaration(io.github.dingyi222666.luaparser.semantic.binder.SymbolId(handle.binderSymbolId))
            handle.declarationId != null -> semanticFile.snapshot.binder.declarationIndex
                .getDeclaration(io.github.dingyi222666.luaparser.semantic.binder.DeclarationId(handle.declarationId))
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

    private fun declarationLocationForSymbol(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        symbolId: String?
    ): WorkspaceLocation? {
        val declaration = localDeclarationForSymbol(semanticFile, symbolId) ?: return null
        return declarationReferenceLocation(path, declaration)
    }

    private fun localSymbolReferences(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        symbol: io.github.dingyi222666.luaparser.semantic.api.Symbol?
    ): List<WorkspaceLocation> {
        val declaration = localDeclarationForSymbol(semanticFile, symbol?.symbolId) ?: return emptyList()
        val locations = linkedMapOf<String, WorkspaceLocation>()

        declarationReferenceLocation(path, declaration)?.let { location ->
            locations["${location.path.value}:${location.range.start.line}:${location.range.start.column}"] = location
        }

        semanticFile.identifiers
            .asSequence()
            .filter { it.name == declaration.name }
            .forEach { identifier ->
                val usage = semanticFile.model.getSymbolAt(identifier.range.start) ?: return@forEach
                if (usage.symbolId == symbol?.symbolId) {
                    locations["${path.value}:${identifier.range.start.line}:${identifier.range.start.column}"] =
                        WorkspaceLocation(path, identifier.range)
                }
            }

        return locations.values.toList()
    }

    private fun declarationReferenceLocation(
        path: VirtualPath,
        declaration: BinderDeclaration
    ): WorkspaceLocation? {
        val range = declaration.anchorNode?.range ?: declaration.range ?: return null
        return WorkspaceLocation(path, range)
    }

    private fun requireCallDefinition(path: VirtualPath, position: Position): WorkspaceLocation? {
        val callSite = requireCallSite(path, position) ?: return null
        val providerPath = resolveWorkspaceRequire(path, callSite.moduleName)?.provider?.path ?: return null
        return WorkspaceLocation(providerPath, syntheticModuleRange(callSite.moduleName))
    }

    private fun builtinRequireModuleName(
        semanticFile: WorkspaceSemanticFile,
        call: CallExpression
    ): String? {
        // Support both require("mod") and require "mod" (StringCallExpression base).
        val callee = effectiveCallBase(call) as? Identifier ?: return null
        if (callee.name != "require") {
            return null
        }
        // Unshadowed free-id `require` is the language builtin even when the overlay-seeded
        // declaration is temporarily invisible (documented ranges live on virtual overlay docs).
        // Only a true non-builtin local/parameter binding may shadow it (TASK-664).
        val declaration = visibleLocalValueDeclaration(
            semanticFile,
            callee.name,
            callee.range.start,
            localInitializerDeclarationIds(semanticFile, call)
        )
        if (declaration != null &&
            (declaration.origin != DeclarationOrigin.BUILTIN || declaration.name != "require")
        ) {
            return null
        }
        return (callArguments(call).singleOrNull() as? ConstantNode)
            ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
            ?.stringOf()
    }

    private fun importTargetDefinition(
        path: VirtualPath,
        semanticFile: WorkspaceSemanticFile,
        node: BaseASTNode?
    ): WorkspaceLocation? {
        val constant = node as? ConstantNode ?: return null
        if (constant.constantType != ConstantNode.TYPE.STRING) {
            return null
        }
        // Prefer parent-linked enclosing call (string-call args parent-linked by the parser).
        // Fall back to range containment for compact AST shapes where argument.parent may be unset.
        val call = enclosingCallExpression(constant)
            ?: importStringCallContaining(semanticFile, constant)
            ?: return null
        if (!callArguments(call).any { it === constant || isSameStringLiteral(it, constant) }) {
            return null
        }
        if (!isImportCallee(semanticFile, effectiveCallBase(call))) {
            return null
        }
        val target = constant.stringOf()
        val imported = resolver.importTargetSymbolFor(path, target)
            ?: resolver.importTargetSymbol(target)
            ?: return null
        return WorkspaceLocation(imported.providerPath, syntheticModuleRange(imported.alias))
    }

    private fun isSameStringLiteral(
        expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode,
        constant: ConstantNode
    ): Boolean {
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
        val visitor = object : io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor<Unit> {
            override fun visitCallExpression(node: CallExpression, value: Unit) {
                if (callArguments(node).any { it === constant || isSameStringLiteral(it, constant) }) {
                    if (isImportCallee(semanticFile, effectiveCallBase(node)) ||
                        (effectiveCallBase(node) as? Identifier)?.name == "require"
                    ) {
                        match = node
                        return
                    }
                }
                super.visitCallExpression(node, value)
            }

            override fun visitStringCallExpression(
                node: io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression,
                value: Unit
            ) {
                if (callArguments(node).any { it === constant || isSameStringLiteral(it, constant) }) {
                    if (isImportCallee(semanticFile, effectiveCallBase(node)) ||
                        (effectiveCallBase(node) as? Identifier)?.name == "require"
                    ) {
                        match = node
                        return
                    }
                }
                super.visitStringCallExpression(node, value)
            }

            override fun visitIdentifier(node: Identifier, value: Unit) = Unit
            override fun visitAttributeIdentifier(
                identifier: io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier,
                value: Unit
            ) = Unit
            override fun visitCommentStatement(
                commentStatement: io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement,
                value: Unit
            ) = Unit
        }
        visitor.visitChunkNode(semanticFile.chunk, Unit)
        return match
    }

    private fun callArguments(call: CallExpression): List<io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode> {
        // Flatten nested string/table-call bases so outer CallExpression(import "X") and
        // the nested StringCallExpression both surface the string argument.
        return buildList {
            fun appendFrom(expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode) {
                when (expression) {
                    is io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression -> {
                        appendFrom(expression.base)
                        addAll(expression.arguments)
                    }
                    is io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression -> {
                        appendFrom(expression.base)
                        addAll(expression.arguments)
                    }
                    is CallExpression -> {
                        val nestedBase = expression.base
                        if (nestedBase is io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression ||
                            nestedBase is io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
                        ) {
                            appendFrom(nestedBase)
                        }
                        addAll(expression.arguments)
                    }
                }
            }
            appendFrom(call)
        }
    }

    private fun effectiveCallBase(call: CallExpression): io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode {
        val base = if (call.base is io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression && call.arguments.isEmpty()) {
            call.base
        } else {
            call.base
        }
        return if (base is io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression) base.base else base
    }

    private fun isImportCallee(
        semanticFile: WorkspaceSemanticFile,
        expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
    ): Boolean {
        val identifier = expression as? Identifier ?: return false
        if (identifier.name == "import") {
            val local = visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.start)
            if (local == null || local.origin == DeclarationOrigin.BUILTIN) {
                return true
            }
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
        val declaration = visibleLocalValueDeclaration(
            semanticFile,
            identifier.name,
            identifier.range.start,
            excludedDeclarations
        ) ?: return false
        if (!visited.add(declaration.id)) {
            return false
        }
        if (requiredModuleNameForDeclaration(semanticFile, declaration) == "import") {
            return true
        }
        val initializer = requireInitializerForLocal(declaration)
        if (initializer != null) {
            return builtinRequireModuleName(semanticFile, initializer) == "import"
        }
        val localStatement = declaration.anchorNode?.parent as? LocalStatement ?: return false
        val localIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (localIndex < 0) {
            return false
        }
        val initExpression = localStatement.variables.getOrNull(localIndex) as? Identifier ?: return false
        val hopExclusions = localStatement.init.mapNotNull { localId ->
            semanticFile.snapshot.binder.declarationIndex
                .getDeclarations(localId)
                .firstOrNull { it.kind == DeclarationKind.LOCAL }
                ?.id
        }.toSet()
        return aliasResolvesToImportCallable(semanticFile, initExpression, hopExclusions, visited)
    }

    private fun importedSymbolLocation(symbolId: String): WorkspaceLocation? {
        val identity = ImportedSymbolIdentity.parse(symbolId) ?: return null
        return WorkspaceLocation(identity.providerPath, syntheticModuleRange(identity.alias))
    }

    private fun importedSymbolReferences(symbolId: String): List<WorkspaceLocation> {
        val identity = ImportedSymbolIdentity.parse(symbolId) ?: return emptyList()
        val locations = linkedMapOf<String, WorkspaceLocation>()
        val providerRange = syntheticModuleRange(identity.alias)
        locations["${identity.providerPath.value}:${providerRange.start.line}:${providerRange.start.column}"] =
            WorkspaceLocation(identity.providerPath, providerRange)

        snapshot.files.values
            .mapNotNull { it.semanticFile }
            .forEach { file ->
                file.identifiers
                    .asSequence()
                    .filter { it.name == identity.alias }
                    .forEach { identifier ->
                        val usage = file.model.getSymbolAt(identifier.range.start) ?: return@forEach
                        if (usage.symbolId == symbolId) {
                            locations["${file.path.value}:${identifier.range.start.line}:${identifier.range.start.column}"] =
                                WorkspaceLocation(file.path, identifier.range)
                        }
                    }
            }

        return locations.values.toList()
    }


    private fun isImportedSymbolHandle(symbolId: String): Boolean {
        return ImportedSymbolIdentity.parse(symbolId) != null
    }

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

    private fun importRequireSymbol(
        path: VirtualPath,
        position: Position
    ): io.github.dingyi222666.luaparser.semantic.api.Symbol? {
        val resolved = resolveRequire(path, position) ?: return null
        if (resolved.moduleName != "import") {
            return null
        }
        val callType = resolved.exportSurface?.moduleType?.fields?.get("__call") ?: return null
        // Normalize Android-Lua import callable display regardless of vararg name formatting.
        val displayName = when {
            callType.displayName == "fun(...: any...): any" -> callType.displayName
            callType.displayName.contains("any...") || callType.displayName.startsWith("fun(") ->
                "fun(...: any...): any"
            else -> callType.displayName
        }
        return io.github.dingyi222666.luaparser.semantic.api.Symbol(
            name = "import",
            kind = SymbolKind.FUNCTION,
            range = null,
            type = TypeInfo(
                displayName = displayName,
                detail = displayName,
                kind = TypeInfoKind.FUNCTION
            ),
            declaredType = TypeInfo(
                displayName = displayName,
                detail = displayName,
                kind = TypeInfoKind.FUNCTION
            ),
            detail = displayName,
            symbolId = "builtin-import:${resolved.provider?.path?.value.orEmpty()}"
        )
    }

    private fun providerPathForExport(export: WorkspaceModuleResolver.ResolvedExportMember): VirtualPath {
        return if (export.member.type is ModuleType) {
            resolver.classProviderForMember(export.providerPath, export.member.name)?.path
                ?: export.definitionProviderPath
        } else {
            export.definitionProviderPath
        }
    }

    private fun exportLocation(export: WorkspaceModuleResolver.ResolvedExportMember): WorkspaceLocation {
        val providerPath = providerPathForExport(export)
        val range = export.member.range ?: syntheticModuleRange(export.member.name)
        return WorkspaceLocation(providerPath, range)
    }

    private fun workspaceMemberCompletions(
        path: VirtualPath,
        semanticFile: WorkspaceSemanticFile,
        memberExpression: MemberExpression
    ): List<CompletionItem> {
        // Prefix under the require-backed root: greeter. → []; cfg.ui. → ["ui"].
        // Incomplete trailing-dot recovery may synthesize a receiver Identifier whose range is
        // not binder-backed; fall back to require-local lookup by receiver name so barrel
        // reexports (leafRun/leafValue) still surface for `local pkg = require("pkg.init"); pkg.`
        val resolved = when (val base = memberExpression.base) {
            is Identifier -> resolvedRequireForCompletionReceiver(semanticFile, path, base)
                ?: resolveRequiredModuleNameForVisibleLocal(semanticFile, null, base)
                    ?.let { moduleName -> resolveWorkspaceRequire(path, moduleName) }
            else -> resolvedRequireForExportPathRootFromExpression(semanticFile, path, base)
                ?: resolvedRequireForCompletionReceiver(semanticFile, path, base)
        } ?: return emptyList()
        val prefixPath = requireBackedExportPath(semanticFile, path, memberExpression.base)
            ?: emptyList()
        val expectedDepth = prefixPath.size + 1
        return resolved.surface.members
            .asSequence()
            .filter { member ->
                member.exportPath.size == expectedDepth &&
                    member.exportPath.take(prefixPath.size) == prefixPath &&
                    isUserFacingExportName(member.name)
            }
            .distinctBy { it.name }
            .map { member ->
                val resolvedMember = resolver.exportedMember(resolved.provider.path, member.exportPath)
                val detail = resolvedMember?.let { enrichExportTypeDisplay(it) }
                    ?: member.type.displayName
                CompletionItem(
                    label = member.name,
                    kind = member.kind.toCompletionItemKind(),
                    detail = detail,
                    insertText = member.name,
                    sortText = "0:0000:${member.name}"
                )
            }
            .toList()
    }

    /**
     * Hover for `local greeter = require("greeter")` aliases: surface MODULE typing from the
     * resolved export surface instead of a bare local / string-literal-ish display.
     */
    private fun requireBackedLocalHoverSymbol(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        node: BaseASTNode?
    ): io.github.dingyi222666.luaparser.semantic.api.Symbol? {
        val identifier = node as? Identifier ?: return null
        val parent = runCatching { identifier.parent }.getOrNull()
        if (parent is MemberExpression && parent.identifier === identifier) {
            return null
        }
        val moduleName = resolveRequiredModuleNameForLocal(semanticFile, null, identifier)
            ?: resolveRequiredModuleNameForVisibleLocal(
                semanticFile,
                semanticFile.model.getSymbolAt(identifier.range.start)?.symbolId,
                identifier
            )
            ?: return null
        val resolved = resolveWorkspaceRequire(path, moduleName) ?: return null
        val moduleType = resolved.surface.moduleType
        val display = "module ${moduleType.moduleName}"
        val typeInfo = TypeInfo(
            displayName = display,
            detail = moduleType.displayName,
            kind = TypeInfoKind.MODULE,
            moduleName = moduleType.moduleName
        )
        return io.github.dingyi222666.luaparser.semantic.api.Symbol(
            name = identifier.name,
            kind = SymbolKind.MODULE,
            range = identifier.range,
            type = typeInfo,
            declaredType = typeInfo,
            detail = display,
            symbolId = "require-module:${resolved.provider.path.value}:$moduleName"
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
        val resolved = resolvedRequireForExportPathRoot(semanticFile, path, memberExpression) ?: return null
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
                val key = (expression.index as? ConstantNode)
                    ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                    ?.stringOf()
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
                val basePath = requireBackedExportPath(semanticFile, path, expression.base, visited)
                    ?: return null
                basePath + key
            }
            is Identifier -> {
                val key = "${expression.range.start.line}:${expression.range.start.column}:${expression.name}"
                if (!visited.add(key)) {
                    return null
                }
                val symbol = semanticFile.model.getSymbolAt(expression.range.start)
                val declaration = localDeclarationForSymbol(semanticFile, symbol?.symbolId)
                    ?: exactLocalDeclarationForIdentifier(semanticFile, expression)
                    ?: visibleLocalValueDeclaration(semanticFile, expression.name, expression.range.start)
                    // Synthetic incomplete-member receivers may have approximate ranges; also
                    // probe by name at caret-adjacent positions so require-alias roots still bind.
                    ?: visibleLocalValueDeclaration(semanticFile, expression.name, expression.range.end)
                if (declaration != null) {
                    requiredModuleNameForDeclaration(semanticFile, declaration)?.let {
                        // Root require-backed local: export path is empty before the member segment.
                        return emptyList()
                    }
                    val initializer = localInitializerExpression(declaration)
                    if (initializer != null) {
                        requireBackedExportPath(semanticFile, path, initializer, visited)?.let { return it }
                    }
                }
                // Direct require module name typing on the identifier (no local declaration path).
                resolveRequiredModuleNameForVisibleLocal(semanticFile, symbol?.symbolId, expression)
                    ?.let { return emptyList() }
                null
            }
            is CallExpression -> {
                builtinRequireModuleName(semanticFile, expression)?.let { emptyList() }
            }
            else -> null
        }
    }

    private fun resolvedRequireForExportPathRoot(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        memberExpression: MemberExpression
    ): WorkspaceModuleResolver.ResolvedRequire? {
        // Walk the nested access / alias chain until a require-backed module root is found.
        var current: ExpressionNode = memberExpression
        val seen = linkedSetOf<String>()
        while (true) {
            when (current) {
                is MemberExpression -> current = current.base
                is IndexExpression -> current = current.base
                is Identifier -> {
                    val key = "${current.range.start.line}:${current.range.start.column}:${current.name}"
                    if (!seen.add(key)) {
                        return null
                    }
                    resolvedRequireForWorkspaceMemberReceiver(semanticFile, path, current)?.let { return it }
                    val symbol = semanticFile.model.getSymbolAt(current.range.start)
                    val declaration = localDeclarationForSymbol(semanticFile, symbol?.symbolId)
                        ?: exactLocalDeclarationForIdentifier(semanticFile, current)
                        ?: visibleLocalValueDeclaration(semanticFile, current.name, current.range.start)
                        ?: return null
                    val initializer = localInitializerExpression(declaration) ?: return null
                    current = initializer
                }
                is CallExpression -> {
                    return resolvedRequireForReceiver(semanticFile, path, current)
                }
                else -> return null
            }
        }
    }

    private fun localInitializerExpression(declaration: BinderDeclaration): ExpressionNode? {
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) {
            return null
        }
        val localStatement = declaration.anchorNode.parent as? LocalStatement ?: return null
        val localIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (localIndex < 0) {
            return null
        }
        return localStatement.variables.getOrNull(localIndex)
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

    private fun requireInitializerForLocal(declaration: BinderDeclaration): CallExpression? {
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) {
            return null
        }
        val localStatement = declaration.anchorNode.parent as? LocalStatement ?: return null
        val localIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (localIndex < 0) {
            return null
        }
        return localStatement.variables.getOrNull(localIndex) as? CallExpression
    }

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
            if (declaration != null) {
                importCallTargetModuleName(semanticFile, pathOf(semanticFile), declaration)?.let { return it }
            }
        }
        return null
    }

    private fun pathOf(semanticFile: WorkspaceSemanticFile): VirtualPath = semanticFile.path

    private fun importCallTargetModuleName(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        declaration: BinderDeclaration
    ): String? {
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) {
            return null
        }
        val initializer = requireInitializerForLocal(declaration) ?: return null
        if (!isImportCallee(semanticFile, effectiveCallBase(initializer))) {
            return null
        }
        val targets = importCallStringTargets(initializer)
        if (targets.size != 1) {
            return null
        }
        val imported = resolver.importTargetSymbolFor(path, targets.single())
            ?: resolver.importTargetSymbol(targets.single())
            ?: return null
        return imported.moduleType.moduleName.takeIf { it.isNotBlank() }
    }

    private fun importCallStringTargets(call: CallExpression): List<String> {
        val firstArgument = callArguments(call).firstOrNull()
        return when (firstArgument) {
            is ConstantNode -> {
                if (firstArgument.constantType == ConstantNode.TYPE.STRING) {
                    listOf(firstArgument.stringOf())
                } else {
                    emptyList()
                }
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression ->
                firstArgument.values.mapNotNull { expression ->
                    (expression as? ConstantNode)
                        ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                        ?.stringOf()
                }
            is io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression ->
                firstArgument.fields.mapNotNull { field ->
                    (field.value as? ConstantNode)
                        ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
                        ?.stringOf()
                }
            else -> emptyList()
        }
    }

    private fun importCallTargetHoverSymbol(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        node: BaseASTNode?
    ): io.github.dingyi222666.luaparser.semantic.api.Symbol? {
        val identifier = node as? Identifier ?: return null
        val parent = runCatching { identifier.parent }.getOrNull()
        if (parent is MemberExpression && parent.identifier === identifier) {
            return null
        }
        val declaration = exactLocalDeclarationForIdentifier(semanticFile, identifier)
            ?: visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.start)
            ?: return null
        if (declaration.kind != DeclarationKind.LOCAL) {
            return null
        }
        val initializer = requireInitializerForLocal(declaration) ?: return null
        if (!isImportCallee(semanticFile, effectiveCallBase(initializer))) {
            return null
        }
        val targets = importCallStringTargets(initializer)
        if (targets.isEmpty()) {
            return null
        }
        val imported = targets.mapNotNull { target ->
            resolver.importTargetSymbolFor(path, target) ?: resolver.importTargetSymbol(target)
        }
        if (imported.isEmpty()) {
            return null
        }
        return if (targets.size == 1 && imported.size == 1) {
            importedSymbol(imported.single())
        } else {
            val moduleNames = imported.map { it.moduleType.moduleName }.distinct()
            val display = "Array<${moduleNames.joinToString("|")}>"
            io.github.dingyi222666.luaparser.semantic.api.Symbol(
                name = declaration.name,
                kind = SymbolKind.LOCAL,
                range = declaration.range,
                type = TypeInfo(
                    displayName = display,
                    detail = display,
                    kind = TypeInfoKind.UNKNOWN
                ),
                declaredType = TypeInfo(
                    displayName = display,
                    detail = display,
                    kind = TypeInfoKind.UNKNOWN
                ),
                detail = display,
                symbolId = declaration.symbolId?.let { "binder:$it" } ?: "declaration:${declaration.id.value}"
            )
        }
    }

    private fun importCallLocalDefinition(
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        node: BaseASTNode?,
        symbol: io.github.dingyi222666.luaparser.semantic.api.Symbol?
    ): WorkspaceLocation? {
        val identifier = node as? Identifier ?: return null
        val parent = runCatching { identifier.parent }.getOrNull()
        if (parent is MemberExpression && parent.identifier === identifier) {
            return null
        }
        val declaration = localDeclarationForSymbol(semanticFile, symbol?.symbolId)
            ?: exactLocalDeclarationForIdentifier(semanticFile, identifier)
            ?: visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.start)
            ?: return null
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
        semanticFile: WorkspaceSemanticFile,
        path: VirtualPath,
        node: BaseASTNode?,
        symbol: io.github.dingyi222666.luaparser.semantic.api.Symbol?
    ): WorkspaceLocation? {
        val identifier = node as? Identifier
        val declaration = when {
            identifier != null -> {
                val parent = runCatching { identifier.parent }.getOrNull()
                if (parent is MemberExpression && parent.identifier === identifier) {
                    // Member access: resolve receiver local alias (Locale.getDefault -> Locale).
                    null
                } else {
                    localDeclarationForSymbol(semanticFile, symbol?.symbolId)
                        ?: exactLocalDeclarationForIdentifier(semanticFile, identifier)
                        ?: visibleLocalValueDeclaration(semanticFile, identifier.name, identifier.range.start)
                }
            }
            else -> localDeclarationForSymbol(semanticFile, symbol?.symbolId)
        }
        // Member receiver path: Identifier base of MemberExpression.
        val memberAccess = when (node) {
            is MemberExpression -> node
            is Identifier -> runCatching { node.parent }.getOrNull() as? MemberExpression
            else -> null
        }
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
            localDeclarationForSymbol(semanticFile, receiverSymbol?.symbolId)
                ?: exactLocalDeclarationForIdentifier(semanticFile, receiver)
                ?: visibleLocalValueDeclaration(semanticFile, receiver.name, receiver.range.start)
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
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) {
            return null
        }
        val initializer = requireInitializerForLocal(declaration) ?: return null
        val target = luaJavaClassLoadTarget(semanticFile, initializer, preferredMemberName) ?: return null
        val imported = resolver.importTargetSymbolFor(path, target)
            ?: resolver.importTargetSymbol(target)
            ?: return null
        return imported.moduleType.moduleName.takeIf { it.isNotBlank() }
            ?: imported.alias.takeIf { it.isNotBlank() }
    }

    private fun luaJavaClassLoadTarget(
        semanticFile: WorkspaceSemanticFile,
        call: CallExpression,
        preferredMemberName: String? = null
    ): String? {
        if (!isLuaJavaClassLoadCallee(semanticFile, effectiveCallBase(call))) {
            return null
        }
        val targets = luaJavaClassLoadTargets(semanticFile, call)
        if (targets.isEmpty()) {
            return null
        }
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
        if (!isLuaJavaClassLoadCallee(semanticFile, effectiveCallBase(call))) {
            return emptyList()
        }
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
            val constant = argument as? ConstantNode ?: break
            if (constant.constantType != ConstantNode.TYPE.STRING) {
                break
            }
            targets += constant.stringOf()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
        }
        return targets
    }

    private fun isCreateProxyCallBase(
        semanticFile: WorkspaceSemanticFile,
        expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
    ): Boolean {
        return when (expression) {
            is MemberExpression ->
                expression.identifier.name == "createProxy" && isLuaJavaOwnerIdentifier(expression.base)
            is Identifier -> {
                if (expression.name != "createProxy") {
                    return false
                }
                val declaration = visibleLocalValueDeclaration(
                    semanticFile,
                    expression.name,
                    expression.range.start
                )
                declaration == null ||
                    declaration.origin == DeclarationOrigin.BUILTIN ||
                    declarationResolvesToLuaJavaHelper(semanticFile, declaration, "createProxy")
            }
            else -> false
        }
    }

    private fun javaProviderExposesMember(target: String, memberName: String): Boolean {
        val imported = resolver.importTargetSymbol(target) ?: return false
        val moduleType = imported.moduleType
        if (memberName in moduleType.fields || memberName in moduleType.methods) {
            return true
        }
        return when (val classSurface = moduleType.fields["__class"]) {
            is io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType ->
                memberName in classSurface.allInstanceMembers() ||
                    memberName in classSurface.allStaticMembers()
            is io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType ->
                memberName in classSurface.allInstanceMembers() ||
                    memberName in classSurface.classType.allStaticMembers()
            else -> false
        }
    }

    private fun isLuaJavaClassLoadCallee(
        semanticFile: WorkspaceSemanticFile,
        expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
    ): Boolean {
        return when (expression) {
            is MemberExpression -> {
                val helper = expression.identifier.name
                helper in LUA_JAVA_CLASS_LOAD_HELPERS && isLuaJavaOwnerIdentifier(expression.base)
            }
            is Identifier -> {
                if (expression.name !in LUA_JAVA_CLASS_LOAD_HELPERS) {
                    return false
                }
                // Bare helper or local alias of luajava.bindClass.
                val declaration = visibleLocalValueDeclaration(
                    semanticFile,
                    expression.name,
                    expression.range.start
                )
                declaration == null || declaration.origin == DeclarationOrigin.BUILTIN ||
                    declarationResolvesToLuaJavaHelper(semanticFile, declaration, expression.name)
            }
            else -> false
        }
    }

    private fun isLuaJavaOwnerIdentifier(
        expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
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
        val LUA_JAVA_CLASS_LOAD_HELPERS: Set<String> = setOf(
            "bindClass",
            "newInstance",
            "createProxy",
            "loadLib",
            "createArray",
            "newArray"
        )
    }

    private fun memberExpressionAt(
        semanticFile: WorkspaceSemanticFile,
        position: Position
    ): MemberExpression? {
        val node = semanticFile.nodeAt(position)
        // Only treat completed member names (and incomplete blank members) as member sites.
        // Caret still on the receiver of `messageText:setText` must stay free-id / lexical so
        // layout-id locals (`messageText`, `submitButton`) surface alongside imports (TASK-683).
        memberAccessAt(node, position)?.let { return it }
        if (node is Identifier) {
            val parent = runCatching { node.parent }.getOrNull() as? MemberExpression
            // Incomplete `base.|` / `base:` where caret is on the base and the member name is blank.
            if (
                parent != null &&
                parent.base === node &&
                parent.identifier.name.isBlank() &&
                isMemberCompletionSite(parent, position)
            ) {
                return parent
            }
            // Incomplete `base.|` often lands the caret past blank member Identifier.
            if (
                parent != null &&
                parent.identifier === node &&
                parent.identifier.name.isBlank() &&
                isMemberCompletionSite(parent, position)
            ) {
                return parent
            }
            // Trailing-dot caret may land past half-open MemberExpression.range.end while still
            // sitting on the receiver Identifier (parent-linked incomplete member).
            if (
                parent != null &&
                parent.base === node &&
                parent.identifier.name.isBlank() &&
                position.line == parent.range.end.line &&
                position.column >= parent.range.end.column
            ) {
                return parent
            }
            return null
        }
        // Prefer the most specific (longest) member expression covering the caret, and
        // when tied prefer incomplete trailing-dot forms (`cfg.ui.|`) over completed
        // intermediate segments (`cfg.ui`) so nested export completions use the full prefix.
        // Skip receivers of completed accesses so free-id completions keep layout-id locals.
        val candidates = semanticFile.memberExpressions.filter {
            isMemberCompletionSite(it, position) &&
                (
                    memberCompletionRangeContains(it.range, position) ||
                        rangeContains(it.identifier.range, position) ||
                        // Incomplete blank members: accept caret at/after member range end.
                        (
                            it.identifier.name.isBlank() &&
                                position.line == it.range.end.line &&
                                position.column >= it.range.end.column &&
                                position.column <= it.range.end.column + 1
                            )
                    )
        }
        if (candidates.isEmpty()) {
            return null
        }
        return candidates.maxWithOrNull(
            compareBy<MemberExpression> { spanLength(it.range) }
                .thenBy { if (it.identifier.name.isBlank()) 1 else 0 }
                .thenBy { it.range.end.line }
                .thenBy { it.range.end.column }
        )
    }

    /**
     * Recover incomplete trailing-dot / colon member sites from source text when AST
     * nodeAt / half-open ranges miss the caret (common for `local x = pkg.|`).
     * Builds a synthetic [MemberExpression] over the receiver Identifier so
     * [workspaceMemberCompletions] can resolve require-backed export surfaces.
     */
    private fun incompleteMemberExpressionFromSource(
        semanticFile: WorkspaceSemanticFile,
        position: Position
    ): MemberExpression? {
        val lineText = sourceLineAt(semanticFile.source, position.line) ?: return null
        if (lineText.isEmpty() || position.column <= 1) {
            return null
        }
        // Strip trailing CR so Windows CRLF sources still match the trailing-dot regex.
        val normalizedLine = lineText.trimEnd('\r')
        val caretIndex = (position.column - 1).coerceIn(0, normalizedLine.length)
        val before = normalizedLine.take(caretIndex)
        val match = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*([.:])\s*$""").find(before) ?: return null
        val receiverName = match.groupValues[1]
        val indexer = match.groupValues[2]
        val receiver = semanticFile.identifiers
            .asSequence()
            .filter { identifier ->
                identifier.name == receiverName &&
                    identifier.range.start.line == position.line &&
                    identifier.range.start.column < position.column
            }
            .maxByOrNull { it.range.start.column }
            ?: syntheticReceiverIdentifier(receiverName, position, before, match)
        // Prefer an existing incomplete AST member on this receiver when present.
        semanticFile.memberExpressions
            .lastOrNull {
                (it.base === receiver ||
                    ((it.base as? Identifier)?.name == receiverName &&
                        it.base.range.start.line == position.line)) &&
                    it.identifier.name.isBlank() &&
                    it.indexer == indexer
            }
            ?.let { return it }
        // Synthetic incomplete member for require-alias export completion recovery.
        return MemberExpression().also { member ->
            member.base = receiver
            member.indexer = indexer
            member.identifier = Identifier("").also { blank ->
                blank.range = Range(position, position)
                blank.parent = member
            }
            member.range = Range(receiver.range.start, position)
            // Keep parent unset so free-id paths on the receiver stay unchanged.
        }
    }

    /**
     * Fallback receiver when the incomplete trailing-dot site is present in source but
     * the Identifier AST node was not collected (partial parse / recovery).
     */
    private fun syntheticReceiverIdentifier(
        receiverName: String,
        position: Position,
        before: String,
        match: MatchResult
    ): Identifier {
        val receiverStartColumn = (match.range.first + 1).coerceAtLeast(1)
        val start = Position(position.line, receiverStartColumn)
        val end = Position(position.line, receiverStartColumn + receiverName.length)
        return Identifier(receiverName).also { id ->
            id.range = Range(start, end)
        }
    }

    private fun identifierCoveringPosition(
        semanticFile: WorkspaceSemanticFile,
        position: Position
    ): Identifier? {
        return semanticFile.identifiers
            .asSequence()
            .filter { identifier ->
                rangeContains(identifier.range, position) ||
                    (
                        position.line == identifier.range.start.line &&
                            position.column >= identifier.range.start.column &&
                            position.column <= identifier.range.end.column
                        )
            }
            .minByOrNull { spanLength(it.range) }
    }

    private fun sourceLineAt(source: String, line: Int): String? {
        if (line < 1) {
            return null
        }
        var current = 1
        var start = 0
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            if (ch == '\n') {
                if (current == line) {
                    return source.substring(start, i)
                }
                current += 1
                start = i + 1
            }
            i += 1
        }
        return if (current == line) source.substring(start) else null
    }

    private fun spanLength(range: Range): Int {
        if (range.start.line == range.end.line) {
            return range.end.column - range.start.column
        }
        return (range.end.line - range.start.line) * 10_000 +
            range.end.column + (1_000 - range.start.column)
    }

    private fun memberCompletionRangeContains(range: Range, position: Position): Boolean {
        if (rangeContains(range, position)) {
            return true
        }
        // Half-open AST ranges end at the caret for trailing-dot completions (`table.|`).
        // Accept caret at end or one column past end.
        if (position.line != range.end.line) {
            return false
        }
        return position.column == range.end.column || position.column == range.end.column + 1
    }

    /**
     * Member completions apply when the caret is on the member name / after the indexer
     * (`base.|`, `base:set|`), not when the caret is still on the receiver identifier of a
     * completed access (`mess|ageText:setText` must stay lexical for layout-id locals).
     */
    private fun isMemberCompletionSite(expression: MemberExpression, position: Position): Boolean {
        val baseEnd = expression.base.range.end
        val afterBase =
            position.line > baseEnd.line ||
                (position.line == baseEnd.line && position.column > baseEnd.column)
        if (expression.identifier.name.isBlank()) {
            // Incomplete `base.` / `base:` — member surface once past the base, or on the blank
            // member identifier itself (trailing-dot caret often sits at range end or one past).
            if (afterBase || position.line == baseEnd.line && position.column == baseEnd.column) {
                return memberCompletionRangeContains(expression.range, position) ||
                    rangeContains(expression.identifier.range, position) ||
                    (
                        position.line == expression.range.end.line &&
                            position.column >= expression.range.end.column &&
                            position.column <= expression.range.end.column + 1
                        )
            }
            return false
        }
        if (!afterBase) {
            return false
        }
        return memberCompletionRangeContains(expression.range, position) ||
            rangeContains(expression.identifier.range, position)
    }

    private fun memberAccessAt(node: BaseASTNode?, position: Position): MemberExpression? {
        val memberExpression = when (node) {
            is MemberExpression -> node
            is Identifier -> runCatching { node.parent }.getOrNull() as? MemberExpression
            else -> null
        } ?: return null
        // Completed member-name carets only. Receiver identifiers must not enter member mode.
        val onMemberName = when {
            node is MemberExpression && rangeContains(memberExpression.identifier.range, position) -> true
            node is Identifier && memberExpression.identifier === node -> true
            else -> false
        }
        if (!onMemberName) {
            return null
        }
        return memberExpression.takeIf { isMemberCompletionSite(it, position) }
    }

    private fun SymbolKind.toCompletionItemKind(): CompletionItemKind {
        return when (this) {
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
    }

    private fun isUserFacingExportName(name: String): Boolean {
        return name.isNotBlank() && !name.startsWith("__")
    }

    private fun importCompletions(
        path: VirtualPath,
        position: Position,
        semanticFile: WorkspaceSemanticFile
    ): List<CompletionItem> {
        val localNames = visibleLocalValueNames(semanticFile, position)
        return resolver.importedSymbolsFor(path)
            .values
            .asSequence()
            .filter { it.alias !in localNames }
            .distinctBy { it.alias }
            .map { symbol ->
                CompletionItem(
                    label = symbol.alias,
                    kind = CompletionItemKind.MODULE,
                    detail = symbol.moduleType.displayName,
                    insertText = symbol.alias,
                    sortText = "8:0000:${symbol.alias}"
                )
            }
            .toList()
    }

    private fun mergeCompletions(
        base: List<CompletionItem>,
        imports: List<CompletionItem>
    ): List<CompletionItem> {
        if (imports.isEmpty()) {
            return base
        }
        val byLabel = linkedMapOf<String, CompletionItem>()
        base.forEach { byLabel[it.label] = it }
        imports.forEach { item ->
            val existing = byLabel[item.label]
            // Prefer MODULE import kinds so Java/Android class completions do not degrade to VARIABLE.
            if (existing == null || (existing.kind != CompletionItemKind.MODULE && item.kind == CompletionItemKind.MODULE)) {
                byLabel[item.label] = item
            }
        }
        return byLabel.values.sortedBy { it.sortText ?: it.label }
    }

    private fun importedSymbolAt(
        path: VirtualPath,
        position: Position,
        node: BaseASTNode?,
        semanticFile: WorkspaceSemanticFile
    ): WorkspaceImportedSymbol? {
        val identifier = node as? Identifier ?: return null
        val parent = runCatching { identifier.parent }.getOrNull()
        if (parent is MemberExpression && parent.identifier === identifier) {
            return null
        }
        if (visibleLocalValueNames(semanticFile, position).contains(identifier.name)) {
            return null
        }
        return resolver.importedSymbolFor(path, identifier.name)
    }

    private fun importedSymbol(imported: WorkspaceImportedSymbol): io.github.dingyi222666.luaparser.semantic.api.Symbol {
        return io.github.dingyi222666.luaparser.semantic.api.Symbol(
            name = imported.alias,
            kind = io.github.dingyi222666.luaparser.semantic.api.SymbolKind.MODULE,
            range = null,
            type = TypeInfo(
                displayName = imported.moduleType.name,
                kind = TypeInfoKind.MODULE,
                moduleName = imported.moduleType.moduleName
            ),
            declaredType = TypeInfo(
                displayName = imported.moduleType.name,
                kind = TypeInfoKind.MODULE,
                moduleName = imported.moduleType.moduleName
            ),
            detail = imported.moduleType.displayName,
            symbolId = importedSymbolHandle(imported)
        )
    }

    private fun importedSymbolHandle(imported: WorkspaceImportedSymbol): String {
        return "imported:${imported.providerPath.value}:${imported.alias}"
    }

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
                return parent.init.mapNotNull { identifier ->
                    semanticFile.snapshot.binder.declarationIndex
                        .getDeclarations(identifier)
                        .firstOrNull { it.kind == DeclarationKind.LOCAL }
                        ?.id
                }.toSet()
            }
            current = parent as? BaseASTNode
        }
        return emptySet()
    }

    private fun visibleLocalValueNames(
        semanticFile: WorkspaceSemanticFile,
        position: Position
    ): Set<String> {
        val names = linkedSetOf<String>()
        var scope = semanticFile.snapshot.binder.positionQueries.getScopeAt(position)
        while (scope != null) {
            scope.declarationIds
                .asReversed()
                .mapNotNull(semanticFile.snapshot.binder.declarationIndex::getDeclaration)
                .forEach { declaration ->
                    if (
                        declaration.kind.namespace == io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace.VALUE &&
                        isDeclarationVisibleAt(declaration, position)
                    ) {
                        names += declaration.name
                    }
                }
            scope = scope.parentId?.let(semanticFile.snapshot.binder.scopeGraph::getScope)
        }
        return names
    }

    private fun visibleLocalValueDeclaration(
        semanticFile: WorkspaceSemanticFile,
        name: String,
        position: Position,
        excludedDeclarations: Set<DeclarationId> = emptySet()
    ): BinderDeclaration? {
        var scope = semanticFile.snapshot.binder.positionQueries.getScopeAt(position)
        while (scope != null) {
            scope.declarationIds
                .asReversed()
                .mapNotNull(semanticFile.snapshot.binder.declarationIndex::getDeclaration)
                .firstOrNull { declaration ->
                    declaration.name == name &&
                        declaration.kind.namespace == io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace.VALUE &&
                        declaration.id !in excludedDeclarations &&
                        isDeclarationVisibleAt(declaration, position)
                }
                ?.let { return it }
            scope = scope.parentId?.let(semanticFile.snapshot.binder.scopeGraph::getScope)
        }
        return null
    }

    private fun isDeclarationVisibleAt(declaration: BinderDeclaration, position: Position): Boolean {
        // Ambient binder builtins (require/print/import helpers) are file-global. Their
        // documented ranges come from overlay virtual documents and must not gate visibility
        // against real file positions used by require-local / free-id navigation (TASK-664).
        if (declaration.origin == DeclarationOrigin.BUILTIN) {
            return true
        }
        val range = declaration.range ?: return true
        return comparePositions(range.start, position) <= 0
    }

    private fun comparePositions(left: Position, right: Position): Int {
        val lineComparison = left.line.compareTo(right.line)
        if (lineComparison != 0) {
            return lineComparison
        }
        return left.column.compareTo(right.column)
    }


    private fun syntheticModuleRange(moduleName: String): Range {
        return Range(
            start = Position(1, 1),
            end = Position(1, maxOf(moduleName.length + 1, 2))
        )
    }

    private fun exportSymbol(export: WorkspaceModuleResolver.ResolvedExportMember): io.github.dingyi222666.luaparser.semantic.api.Symbol {
        val typeInfo = exportTypeInfo(export)
        return io.github.dingyi222666.luaparser.semantic.api.Symbol(
            name = export.member.name,
            kind = export.member.kind,
            range = export.member.range,
            type = typeInfo,
            declaredType = typeInfo,
            detail = export.member.type.displayName,
            symbolId = export.handle
        )
    }

    private fun exportTypeInfo(export: WorkspaceModuleResolver.ResolvedExportMember): TypeInfo {
        val memberType = export.member.type
        val moduleType = memberType as? ModuleType
        // Prefer provider-file declared/inferred FunctionType (Emmy @param/@return) when the
        // export surface only carried param names with unknown types (Monaco cross-file hover).
        val enrichedDisplay = enrichExportTypeDisplay(export) ?: memberType.displayName
        val isFunctionLike = export.member.kind == SymbolKind.FUNCTION ||
            export.member.kind == SymbolKind.METHOD ||
            enrichedDisplay.contains("fun(") ||
            enrichedDisplay.contains("fun<")
        return TypeInfo(
            displayName = enrichedDisplay,
            detail = enrichedDisplay,
            kind = when {
                moduleType != null -> TypeInfoKind.MODULE
                isFunctionLike -> TypeInfoKind.FUNCTION
                export.member.kind == SymbolKind.CLASS -> TypeInfoKind.CLASS
                else -> TypeInfoKind.UNKNOWN
            },
            moduleName = moduleType?.moduleName
        )
    }

    private fun enrichExportTypeDisplay(export: WorkspaceModuleResolver.ResolvedExportMember): String? {
        val range = export.member.range ?: return null
        val providerModel = snapshot.files[export.definitionProviderPath]?.semanticFile?.model
            ?: snapshot.files[export.providerPath]?.semanticFile?.model
            ?: return null
        // Probe the export name range; declared/inferred beat bare export FunctionType unknowns.
        val symbol = providerModel.getSymbolAt(range.start) ?: return null
        val declared = providerModel.getDeclaredType(symbol)?.displayName
        val inferred = providerModel.getInferredType(symbol)?.displayName
        val candidates = listOfNotNull(declared, inferred, symbol.declaredType?.displayName, symbol.type?.displayName)
        return candidates.firstOrNull { candidate ->
            candidate.contains("fun(") || candidate.contains("fun<")
        }?.takeUnless { candidate ->
            // Keep export surface if provider only has equally weak fun(): unknown.
            candidate == "fun(): unknown" || candidate == "fun(): any"
        }
    }

    private fun rangeContains(range: Range, position: Position): Boolean {
        return comparePositions(range.start, position) <= 0 && comparePositions(position, range.end) <= 0
    }

    private fun hasBuiltinRequireCallSite(path: VirtualPath, moduleName: String): Boolean {
        if (moduleName.isBlank()) {
            return false
        }
        val fileSnapshot = snapshot.files[path] ?: return false
        val semanticFile = fileSnapshot.semanticFile ?: return false
        // Fast reject: document facts record syntactic require("name") strings. When facts are
        // present and never mention [moduleName], this consumer has no matching call form.
        // (Shadowed require still appears in facts; the AST walk below rejects non-builtin callees.)
        val facts = fileSnapshot.documentFacts
        if (facts != null && facts.requires.none { it.moduleName == moduleName }) {
            return false
        }
        // Explicit AST walk (not ASTVisitor overrides): recognize only unshadowed builtin
        // require("moduleName") / require "moduleName" call sites via builtinRequireModuleName.
        return chunkHasBuiltinRequireModule(semanticFile, semanticFile.chunk.body, moduleName)
    }

    private fun chunkHasBuiltinRequireModule(
        semanticFile: WorkspaceSemanticFile,
        block: io.github.dingyi222666.luaparser.parser.ast.node.BlockNode,
        moduleName: String
    ): Boolean {
        for (statement in block.statements) {
            if (statementHasBuiltinRequireModule(semanticFile, statement, moduleName)) {
                return true
            }
        }
        val returnStatement = block.returnStatement
        if (returnStatement != null) {
            for (argument in returnStatement.arguments) {
                if (expressionHasBuiltinRequireModule(semanticFile, argument, moduleName)) {
                    return true
                }
            }
        }
        return false
    }

    private fun statementHasBuiltinRequireModule(
        semanticFile: WorkspaceSemanticFile,
        statement: io.github.dingyi222666.luaparser.parser.ast.node.StatementNode,
        moduleName: String
    ): Boolean {
        return when (statement) {
            is LocalStatement ->
                statement.variables.any { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) }
            is io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement ->
                statement.variables.any { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) } ||
                    statement.init.any { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) }
            is io.github.dingyi222666.luaparser.parser.ast.node.CallStatement ->
                expressionHasBuiltinRequireModule(semanticFile, statement.expression, moduleName)
            is io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement ->
                statement.arguments.any { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) }
            is io.github.dingyi222666.luaparser.parser.ast.node.DoStatement ->
                chunkHasBuiltinRequireModule(semanticFile, statement.body, moduleName)
            is io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement ->
                expressionHasBuiltinRequireModule(semanticFile, statement.condition, moduleName) ||
                    chunkHasBuiltinRequireModule(semanticFile, statement.body, moduleName)
            is io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement ->
                chunkHasBuiltinRequireModule(semanticFile, statement.body, moduleName) ||
                    expressionHasBuiltinRequireModule(semanticFile, statement.condition, moduleName)
            is io.github.dingyi222666.luaparser.parser.ast.node.IfStatement ->
                statement.causes.any { clause ->
                    when (clause) {
                        // ElseClause / ElseIfClause are IfClause subclasses: match most-specific first.
                        is io.github.dingyi222666.luaparser.parser.ast.node.ElseClause ->
                            chunkHasBuiltinRequireModule(semanticFile, clause.body, moduleName)
                        is io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause ->
                            expressionHasBuiltinRequireModule(semanticFile, clause.condition, moduleName) ||
                                chunkHasBuiltinRequireModule(semanticFile, clause.body, moduleName)
                        is io.github.dingyi222666.luaparser.parser.ast.node.IfClause ->
                            expressionHasBuiltinRequireModule(semanticFile, clause.condition, moduleName) ||
                                chunkHasBuiltinRequireModule(semanticFile, clause.body, moduleName)
                        else -> false
                    }
                }
            is io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement ->
                expressionHasBuiltinRequireModule(semanticFile, statement.start, moduleName) ||
                    expressionHasBuiltinRequireModule(semanticFile, statement.end, moduleName) ||
                    (statement.step?.let { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) } == true) ||
                    chunkHasBuiltinRequireModule(semanticFile, statement.body, moduleName)
            is io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement ->
                statement.iterators.any { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) } ||
                    chunkHasBuiltinRequireModule(semanticFile, statement.body, moduleName)
            is io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration ->
                (statement.identifier?.let { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) } == true) ||
                    (statement.body?.let { chunkHasBuiltinRequireModule(semanticFile, it, moduleName) } == true)
            is io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement ->
                expressionHasBuiltinRequireModule(semanticFile, statement.condition, moduleName) ||
                    statementHasBuiltinRequireModule(semanticFile, statement.ifCause, moduleName) ||
                    (statement.elseCause?.let { statementHasBuiltinRequireModule(semanticFile, it, moduleName) } == true)
            is io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement ->
                expressionHasBuiltinRequireModule(semanticFile, statement.condition, moduleName) ||
                    statement.causes.any { cause ->
                        when (cause) {
                            is io.github.dingyi222666.luaparser.parser.ast.node.CaseCause ->
                                cause.conditions.any { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) } ||
                                    chunkHasBuiltinRequireModule(semanticFile, cause.body, moduleName)
                            is io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause ->
                                chunkHasBuiltinRequireModule(semanticFile, cause.body, moduleName)
                            else -> false
                        }
                    }
            else -> false
        }
    }

    private fun expressionHasBuiltinRequireModule(
        semanticFile: WorkspaceSemanticFile,
        expression: ExpressionNode,
        moduleName: String
    ): Boolean {
        when (expression) {
            is CallExpression -> {
                if (builtinRequireModuleName(semanticFile, expression) == moduleName) {
                    return true
                }
                if (expressionHasBuiltinRequireModule(semanticFile, expression.base, moduleName)) {
                    return true
                }
                if (expression.arguments.any { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) }) {
                    return true
                }
                return false
            }
            is MemberExpression ->
                return expressionHasBuiltinRequireModule(semanticFile, expression.base, moduleName) ||
                    expressionHasBuiltinRequireModule(semanticFile, expression.identifier, moduleName)
            is IndexExpression ->
                return expressionHasBuiltinRequireModule(semanticFile, expression.base, moduleName) ||
                    expressionHasBuiltinRequireModule(semanticFile, expression.index, moduleName)
            is io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression -> {
                val left = expression.left
                val right = expression.right
                return (left != null && expressionHasBuiltinRequireModule(semanticFile, left, moduleName)) ||
                    (right != null && expressionHasBuiltinRequireModule(semanticFile, right, moduleName))
            }
            is io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression ->
                return expressionHasBuiltinRequireModule(semanticFile, expression.arg, moduleName)
            is io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression ->
                return expression.fields.any { field ->
                    expressionHasBuiltinRequireModule(semanticFile, field.value, moduleName) ||
                        expressionHasBuiltinRequireModule(semanticFile, field.key, moduleName)
                }
            is io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression ->
                return expression.values.any { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) }
            is io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration ->
                return (expression.identifier?.let { expressionHasBuiltinRequireModule(semanticFile, it, moduleName) } == true) ||
                    (expression.body?.let { chunkHasBuiltinRequireModule(semanticFile, it, moduleName) } == true)
            is io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration ->
                return expressionHasBuiltinRequireModule(semanticFile, expression.expression, moduleName)
            else -> return false
        }
    }

    private fun requireCallSite(path: VirtualPath, position: Position): RequireCallSite? {
        val semanticFile = snapshot.files[path]?.semanticFile ?: return null
        val node = semanticFile.nodeAt(position)
        // Prefer parent-linked call, then recover string-call shape when ConstantNode.parent is unset.
        val call = enclosingCallExpression(node)
            ?: (node as? ConstantNode)?.let { importStringCallContaining(semanticFile, it) }
            ?: return null
        val moduleName = builtinRequireModuleName(semanticFile, call) ?: return null
        return RequireCallSite(moduleName)
    }

    private fun enclosingCallExpression(node: BaseASTNode?): CallExpression? {
        var current = node
        while (current != null) {
            if (current is CallExpression) {
                return current
            }
            current = runCatching { current.parent }.getOrNull()
        }
        return null
    }

    private data class RequireCallSite(
        val moduleName: String
    )

    private data class ImportedSymbolIdentity(
        val providerPath: VirtualPath,
        val alias: String
    ) {
        companion object {
            fun parse(handle: String?): ImportedSymbolIdentity? {
                if (handle == null || !handle.startsWith("imported:")) {
                    return null
                }
                val body = handle.removePrefix("imported:")
                val separator = body.lastIndexOf(':')
                if (separator <= 0 || separator >= body.lastIndex) {
                    return null
                }
                return ImportedSymbolIdentity(
                    providerPath = VirtualPath.of(body.substring(0, separator)),
                    alias = body.substring(separator + 1)
                )
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

data class WorkspaceSymbolEntry(
    val name: String,
    val kind: SymbolKind,
    val path: VirtualPath,
    val range: Range,
    val containerName: String? = null
)
