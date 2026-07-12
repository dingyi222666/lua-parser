package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
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
        val resolved = resolver.resolveRequire(path, moduleName)
        return WorkspaceModuleLookupResult(moduleName, resolved?.provider, resolved?.surface)
    }

    fun resolveRequire(path: VirtualPath, position: Position): WorkspaceModuleLookupResult? {
        val callSite = requireCallSite(path, position)
        if (callSite != null) {
            return workspaceModuleLookup(path, callSite.moduleName)
        }
        return requireBackedModuleNameAtPosition(path, position)?.let { workspaceModuleLookup(path, it) }
    }

    fun completions(path: VirtualPath, position: Position): List<CompletionItem> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val baseCompletions = semanticFile?.model?.getCompletionsAt(position).orEmpty()
        if (semanticFile == null) {
            return baseCompletions
        }
        val memberExpression = memberExpressionAt(semanticFile, position)
        if (memberExpression != null) {
            return mergeCompletions(baseCompletions, workspaceMemberCompletions(path, semanticFile, memberExpression))
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
        val symbol = importRequireSymbol(path, position)
            ?: memberExport?.let(::exportSymbol)
            ?: importCallLocal
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
        } else {
            // Prefer import-call local MODULE typing (carries moduleName) over coarse node types.
            // Always run preferredHoverType so structural table literals collapse to "table"
            // even when the symbol surface has a null type and only nodeType is available.
            preferredHoverType(
                preferredHoverType(
                    importCallLocal?.type
                        ?: declaredOrInferred
                        ?: symbol?.type
                        ?: exportType
                        ?: memberType
                        ?: nodeType,
                    exportType ?: memberType ?: nodeType ?: symbol?.type ?: declaredOrInferred
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
        }
        // Local AST declarations win for true locals (including shadowing of imported modules).
        // Imported MODULE aliases continue through the import-definition path below.
        // Require-backed locals already returned above when not on member access.
        if (symbol != null && symbol.kind != SymbolKind.MODULE && symbol.kind != SymbolKind.FUNCTION) {
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
        val memberName = memberExpression.identifier.name.takeIf { it.isNotBlank() } ?: return null
        val resolved = resolvedRequireForNavigationReceiver(semanticFile, path, memberExpression.base) ?: return null
        return resolver.exportedMember(resolved.provider.path, memberName)
    }

    private fun resolvedRequireForNavigationReceiver(
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
        declaration: BinderDeclaration
    ): String? {
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) {
            return null
        }
        val localStatement = declaration.anchorNode.parent as? LocalStatement ?: return null
        val localIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (localIndex < 0) {
            return null
        }
        val initializer = localStatement.variables.getOrNull(localIndex) as? CallExpression ?: return null
        return builtinRequireModuleName(semanticFile, initializer)
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
        val call = enclosingCallExpression(constant) ?: return null
        if (!callArguments(call).any { it === constant }) {
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

    private fun callArguments(call: CallExpression): List<io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode> {
        val stringCallBase = call.base as? io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
        return buildList {
            if (stringCallBase != null) {
                addAll(stringCallBase.arguments)
            }
            addAll(call.arguments)
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
        val resolved = resolvedRequireForCompletionReceiver(semanticFile, path, memberExpression.base)
            ?: return emptyList()
        return resolved.surface.members
            .asSequence()
            .filter { it.exportPath.size == 1 && isUserFacingExportName(it.name) }
            .distinctBy { it.name }
            .map { member ->
                CompletionItem(
                    label = member.name,
                    kind = member.kind.toCompletionItemKind(),
                    detail = member.type.displayName,
                    insertText = member.name,
                    sortText = "4:0000:${member.name}"
                )
            }
            .toList()
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
        val resolved = resolvedRequireForWorkspaceMemberReceiver(semanticFile, path, memberExpression.base) ?: return null
        return resolver.exportedMember(resolved.provider.path, memberName)
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
            declaration
                ?.let { requireInitializerForLocal(it) }
                ?.let { builtinRequireModuleName(semanticFile, it) }
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
        memberAccessAt(node, position)?.let { return it }
        if (node is Identifier) {
            val parent = runCatching { node.parent }.getOrNull() as? MemberExpression
            if (
                parent?.base === node &&
                comparePositions(node.range.end, position) <= 0 &&
                memberCompletionRangeContains(parent.range, position)
            ) {
                return parent
            }
            return null
        }
        return semanticFile.memberExpressions.lastOrNull {
            memberCompletionRangeContains(it.range, position) ||
                rangeContains(it.identifier.range, position)
        }
    }

    private fun memberCompletionRangeContains(range: Range, position: Position): Boolean {
        if (rangeContains(range, position)) {
            return true
        }
        return position.line == range.end.line && position.column == range.end.column + 1
    }

    private fun memberAccessAt(node: BaseASTNode?, position: Position): MemberExpression? {
        val memberExpression = when (node) {
            is MemberExpression -> node
            is Identifier -> runCatching { node.parent }.getOrNull() as? MemberExpression
            else -> null
        } ?: return null
        return when {
            node is MemberExpression && rangeContains(memberExpression.identifier.range, position) -> memberExpression
            node is Identifier && memberExpression.identifier === node -> memberExpression
            else -> null
        }
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
        return TypeInfo(
            displayName = memberType.displayName,
            detail = memberType.displayName,
            kind = when {
                moduleType != null -> TypeInfoKind.MODULE
                export.member.kind == SymbolKind.FUNCTION || export.member.kind == SymbolKind.METHOD -> TypeInfoKind.FUNCTION
                export.member.kind == SymbolKind.CLASS -> TypeInfoKind.CLASS
                else -> TypeInfoKind.UNKNOWN
            },
            moduleName = moduleType?.moduleName
        )
    }

    private fun rangeContains(range: Range, position: Position): Boolean {
        return comparePositions(range.start, position) <= 0 && comparePositions(position, range.end) <= 0
    }

    private fun requireCallSite(path: VirtualPath, position: Position): RequireCallSite? {
        val semanticFile = snapshot.files[path]?.semanticFile ?: return null
        val call = enclosingCallExpression(semanticFile.nodeAt(position)) ?: return null
        val callee = effectiveCallBase(call) as? Identifier ?: return null
        if (callee.name != "require") {
            return null
        }
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
        val moduleName = (callArguments(call).singleOrNull() as? ConstantNode)
            ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
            ?.stringOf()
            ?: return null
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
