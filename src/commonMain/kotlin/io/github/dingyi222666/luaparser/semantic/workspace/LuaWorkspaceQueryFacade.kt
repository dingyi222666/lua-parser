package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.TypeInfo

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
        val export = symbol?.symbolId?.let(resolver::exportedMemberByHandle)
            ?: resolver.exportAt(path, position)
            ?: exportFromMemberBase(node, symbol)
        if (export != null) {
            return export.member.range?.let { listOf(WorkspaceLocation(export.providerPath, it)) }
                ?: listOf(WorkspaceLocation(export.providerPath, syntheticModuleRange(export.moduleName)))
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
        val targetHandle = symbol?.symbolId?.takeIf { ModuleExportIdentity.parse(it) != null }
            ?: resolver.exportAt(path, position)?.handle
            ?: return symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()

        val locations = linkedMapOf<String, WorkspaceLocation>()
        resolver.exportedMemberByHandle(targetHandle)?.let { export ->
            val range = export.member.range ?: syntheticModuleRange(export.moduleName)
            val providerPath = export.providerPath
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
                collectImportedIdentifierReferences(file, identity.alias).forEach { range ->
                    locations["${file.path.value}:${range.start.line}:${range.start.column}"] = WorkspaceLocation(file.path, range)
                }
            }

        return locations.values.toList()
    }

    private fun collectImportedIdentifierReferences(
        file: WorkspaceSemanticFile,
        alias: String
    ): List<Range> {
        val output = mutableListOf<Range>()
        file.source.lineSequence().forEachIndexed { index, rawLine ->
            var searchStart = 0
            while (true) {
                val matchIndex = rawLine.indexOf(alias, startIndex = searchStart)
                if (matchIndex < 0) {
                    break
                }
                val before = rawLine.getOrNull(matchIndex - 1)
                val after = rawLine.getOrNull(matchIndex + alias.length)
                val isIdentifierBoundary = !before.isLuaIdentifierPart() && !after.isLuaIdentifierPart()
                if (isIdentifierBoundary) {
                    output += Range(
                        start = Position(index + 1, matchIndex + 1),
                        end = Position(index + 1, matchIndex + alias.length + 1)
                    )
                }
                searchStart = matchIndex + alias.length
            }
        }
        return output
    }

    private fun Char?.isLuaIdentifierPart(): Boolean {
        return this != null && (this == '_' || this.isLetterOrDigit())
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
