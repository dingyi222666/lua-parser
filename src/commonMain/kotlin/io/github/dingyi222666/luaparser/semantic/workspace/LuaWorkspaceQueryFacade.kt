package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic

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
        return WorkspaceHoverResult(
            path = path,
            position = position,
            symbol = symbol,
            typeInfo = node?.let(model::getTypeAt) ?: symbol?.type
        )
    }

    fun gotoDefinition(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val symbol = semanticFile?.model?.getSymbolAt(position)
        val export = symbol?.symbolId?.let(resolver::exportedMemberByHandle)
            ?: resolver.exportAt(path, position)
        if (export != null) {
            return export.member.range?.let { listOf(WorkspaceLocation(export.providerPath, it)) }.orEmpty()
        }
        return symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()
    }

    fun references(path: VirtualPath, position: Position): List<WorkspaceLocation> {
        val semanticFile = snapshot.files[path]?.semanticFile
        val symbol = semanticFile?.model?.getSymbolAt(position)
        val targetHandle = symbol?.symbolId?.takeIf { ModuleExportIdentity.parse(it) != null }
            ?: resolver.exportAt(path, position)?.handle
            ?: return symbol?.range?.let { listOf(WorkspaceLocation(path, it)) }.orEmpty()

        val locations = linkedMapOf<String, WorkspaceLocation>()
        resolver.exportedMemberByHandle(targetHandle)?.member?.range?.let { range ->
            val providerPath = resolver.exportedMemberByHandle(targetHandle)!!.providerPath
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
}
