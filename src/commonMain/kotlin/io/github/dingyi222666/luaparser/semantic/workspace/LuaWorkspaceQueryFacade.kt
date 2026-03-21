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
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SignatureHelp
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
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
        val callSite = requireCallSite(path, position) ?: return null
        val resolved = resolver.resolveRequire(path, callSite.moduleName)
        return WorkspaceModuleLookupResult(callSite.moduleName, resolved?.provider, resolved?.surface)
    }

    fun completions(path: VirtualPath, position: Position): List<CompletionItem> {
        return snapshot.files[path]?.semanticFile?.model?.getCompletionsAt(position).orEmpty()
    }

    fun signatureHelp(path: VirtualPath, position: Position): SignatureHelp? {
        return snapshot.files[path]?.semanticFile?.model?.getSignatureHelpAt(position)
    }

    fun hover(path: VirtualPath, position: Position): WorkspaceHoverResult? {
        val semanticFile = snapshot.files[path]?.semanticFile ?: return null
        val model = semanticFile.model
        val node = semanticFile.nodeAt(position)
        val symbol = model.getSymbolAt(position) ?: resolver.exportAt(path, position)?.let(::exportSymbol)
        val memberType = memberReceiverType(model, node, symbol)
        val nodeType = node?.let(model::getTypeAt)
        return WorkspaceHoverResult(
            path = path,
            position = position,
            symbol = symbol,
            typeInfo = memberType ?: preferredHoverType(nodeType, symbol?.type)
        )
    }

    fun gotoDefinition(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val node = semanticFile?.nodeAt(position)
        val symbol = semanticFile?.model?.getSymbolAt(position)
        val importDefinition = symbol?.symbolId?.let(::importedSymbolLocation)
        if (importDefinition != null) {
            return listOf(importDefinition)
        }
        val requireCallDefinition = requireCallDefinition(path, position)
        if (requireCallDefinition != null) {
            return listOf(requireCallDefinition)
        }
        val requireBackedDefinition = semanticFile?.let { requireBackedDefinition(it, path, node, symbol) }
        if (requireBackedDefinition != null) {
            return listOf(requireBackedDefinition)
        }
        val export = symbol?.symbolId?.let(resolver::exportedMemberByHandle)
            ?: resolver.exportAt(path, position)
            ?: exportFromMemberBase(node, symbol)
        if (export != null) {
            return export.member.range?.let { listOf(WorkspaceLocation(export.definitionProviderPath, it)) }
                ?: listOf(WorkspaceLocation(export.definitionProviderPath, syntheticModuleRange(export.member.name)))
        }
        return symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()
    }

    fun declaration(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val node = semanticFile?.nodeAt(position)
        val symbol = semanticFile?.model?.getSymbolAt(position)

        val localDeclaration = semanticFile?.let { declarationLocationForSymbol(it, path, symbol?.symbolId) }
        if (localDeclaration != null) {
            return listOf(localDeclaration)
        }

        val importDeclaration = symbol?.symbolId?.let(::importedSymbolLocation)
        if (importDeclaration != null) {
            return listOf(importDeclaration)
        }

        val export = symbol?.symbolId?.let(resolver::exportedMemberByHandle)
            ?: resolver.exportAt(path, position)
            ?: exportFromMemberBase(node, symbol)
        if (export != null) {
            return export.member.range?.let { listOf(WorkspaceLocation(export.definitionProviderPath, it)) }
                ?: listOf(WorkspaceLocation(export.definitionProviderPath, syntheticModuleRange(export.member.name)))
        }

        val requireCallDeclaration = requireCallDefinition(path, position)
        if (requireCallDeclaration != null) {
            return listOf(requireCallDeclaration)
        }

        return symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()
    }

    fun references(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val symbol = semanticFile?.model?.getSymbolAt(position)
        val importedHandle = symbol?.symbolId?.takeIf(::isImportedSymbolHandle)
        if (importedHandle != null) {
            return importedSymbolReferences(importedHandle)
        }

        val localReferences = semanticFile
            ?.let { localSymbolReferences(it, path, symbol) }
            ?.takeIf { it.isNotEmpty() }
        if (localReferences != null) {
            return localReferences
        }

        val targetHandle = symbol?.symbolId?.takeIf { ModuleExportIdentity.parse(it) != null }
            ?: resolver.exportAt(path, position)?.handle
            ?: return symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()

        val locations = linkedMapOf<String, WorkspaceLocation>()
        resolver.exportedMemberByHandle(targetHandle)?.let { export ->
            val range = export.member.range ?: syntheticModuleRange(export.member.name)
            val providerPath = export.definitionProviderPath
            locations["${providerPath.value}:${range.start.line}:${range.start.column}"] = WorkspaceLocation(providerPath, range)
        }

        snapshot.files.values
            .mapNotNull { it.semanticFile }
            .forEach { file ->
                file.memberExpressions.forEach { expression ->
                    val usage = file.model.getSymbolAt(expression.identifier.range.start) ?: return@forEach
                    if (usage.symbolId == targetHandle) {
                        locations["${file.path.value}:${expression.identifier.range.start.line}:${expression.identifier.range.start.column}"] =
                            WorkspaceLocation(file.path, expression.identifier.range)
                    }
                }
            }

        return locations.values.toList()
    }


    fun documentHighlights(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile ?: return emptyList()
        val symbol = semanticFile.model.getSymbolAt(position)
        val highlights = linkedMapOf<String, WorkspaceLocation>()
        val includeCrossFileHighlights =
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

    private fun preferredHoverType(primary: TypeInfo?, fallback: TypeInfo?): TypeInfo? {
        return when {
            primary == null -> fallback
            primary.displayName == "unknown" && fallback != null -> fallback
            else -> primary
        }
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
                resolver.resolveRequire(path, candidate)?.provider?.path?.let { candidate to it }
            }
            ?: return null
        return WorkspaceLocation(moduleName.second, syntheticModuleRange(moduleName.first))
    }

    private fun requireBackedModuleNames(
        semanticFile: WorkspaceSemanticFile,
        node: BaseASTNode?,
        symbol: io.github.dingyi222666.luaparser.semantic.api.Symbol?
    ): List<String> {
        val identifier = node as? Identifier
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
            identifier?.name
                ?.takeIf { it.isNotBlank() && it !in this }
                ?.let(::add)
        }
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
        val moduleType = semanticFile.model.getTypeAt(memberExpression.base) as? ModuleType ?: return null
        return moduleType.moduleName.takeIf { it.isNotBlank() }
    }

    private fun resolveRequiredModuleNameForLocal(
        semanticFile: WorkspaceSemanticFile,
        symbolId: String?,
        identifier: Identifier
    ): String? {
        val declaration = localDeclarationForSymbol(semanticFile, symbolId)
            ?: semanticFile.snapshot.binder.positionQueries.getDeclarationAt(identifier.range.start)
            ?: return null
        if (declaration.kind != DeclarationKind.LOCAL || declaration.anchorNode !is Identifier) {
            return null
        }
        val localStatement = declaration.anchorNode.parent as? LocalStatement ?: return null
        val initializerIndex = localStatement.init.indexOf(declaration.anchorNode)
        if (initializerIndex < 0) {
            return null
        }
        val initializer = localStatement.variables.getOrNull(initializerIndex) as? CallExpression ?: return null
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
        val providerPath = resolver.resolveRequire(path, callSite.moduleName)?.provider?.path ?: return null
        return WorkspaceLocation(providerPath, syntheticModuleRange(callSite.moduleName))
    }

    private fun builtinRequireModuleName(
        semanticFile: WorkspaceSemanticFile,
        call: CallExpression
    ): String? {
        val callee = call.base as? Identifier ?: return null
        if (callee.name != "require") {
            return null
        }
        val declaration = semanticFile.snapshot.binder.positionQueries.getDeclarationAt(callee.range.start) ?: return null
        if (declaration.origin != DeclarationOrigin.BUILTIN || declaration.name != "require") {
            return null
        }
        return (call.arguments.singleOrNull() as? ConstantNode)
            ?.takeIf { it.constantType == ConstantNode.TYPE.STRING }
            ?.stringOf()
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

    private fun syntheticModuleRange(moduleName: String): Range {
        return Range(
            start = Position(1, 1),
            end = Position(1, maxOf(moduleName.length + 1, 2))
        )
    }

    private fun exportSymbol(export: WorkspaceModuleResolver.ResolvedExportMember): io.github.dingyi222666.luaparser.semantic.api.Symbol {
        return io.github.dingyi222666.luaparser.semantic.api.Symbol(
            name = export.member.name,
            kind = export.member.kind,
            range = export.member.range,
            detail = export.member.type.displayName,
            symbolId = export.handle
        )
    }

    private fun requireCallSite(path: VirtualPath, position: Position): RequireCallSite? {
        val semanticFile = snapshot.files[path]?.semanticFile ?: return null
        val call = enclosingCallExpression(semanticFile.nodeAt(position)) ?: return null
        val callee = call.base as? Identifier ?: return null
        if (callee.name != "require") {
            return null
        }
        val declaration = semanticFile.snapshot.binder.positionQueries.getDeclarationAt(callee.range.start) ?: return null
        if (declaration.origin != DeclarationOrigin.BUILTIN || declaration.name != "require") {
            return null
        }
        val moduleName = (call.arguments.singleOrNull() as? ConstantNode)
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
