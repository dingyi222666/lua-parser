package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.semantic.api.SymbolKind

internal class WorkspaceModuleResolver(
    private val snapshot: WorkspaceSnapshot
) {
    fun activeProvider(moduleName: String): WorkspaceModuleGraph.ModuleProvider? {
        return snapshot.graph.activeProviders[moduleName]
    }

    fun exportSurface(provider: WorkspaceModuleGraph.ModuleProvider): ModuleExportSurface? {
        return fileSnapshot(provider.path)?.moduleExportSurface
    }

    fun resolveRequire(consumerPath: VirtualPath, moduleName: String): ResolvedRequire? {
        val dependency = snapshot.graph.resolvedDependencies[consumerPath]
            .orEmpty()
            .firstOrNull { it.moduleName == moduleName }
            ?: return null
        val surface = exportSurface(dependency.provider) ?: return null
        return ResolvedRequire(moduleName, dependency.provider, surface)
    }

    fun exportedMember(providerPath: VirtualPath, memberName: String): ResolvedExportMember? {
        val file = fileSnapshot(providerPath) ?: return null
        val surface = file.moduleExportSurface ?: return null
        val member = surface.members.firstOrNull { it.name == memberName } ?: return null
        return ResolvedExportMember(
            providerPath = providerPath,
            moduleName = surface.moduleType.moduleName,
            member = member
        )
    }

    fun exportedMemberByHandle(handle: String): ResolvedExportMember? {
        val identity = ModuleExportIdentity.parse(handle) ?: return null
        return exportedMember(identity.providerPath, identity.exportPath.firstOrNull() ?: return null)
    }

    fun exportAt(path: VirtualPath, position: io.github.dingyi222666.luaparser.parser.ast.node.Position): ResolvedExportMember? {
        val surface = fileSnapshot(path)?.moduleExportSurface ?: return null
        val member = surface.members.firstOrNull { rangeContains(it.range, position) } ?: return null
        return ResolvedExportMember(path, surface.moduleType.moduleName, member)
    }

    fun exportHandle(providerPath: VirtualPath, exportPath: List<String>): String {
        return ModuleExportIdentity(providerPath, exportPath).asHandle()
    }

    private fun fileSnapshot(path: VirtualPath): WorkspaceSnapshot.FileSnapshot? {
        return snapshot.files[path] ?: snapshot.builtinOverlay.providerModules[path]?.file
    }

    private fun rangeContains(
        range: io.github.dingyi222666.luaparser.parser.ast.node.Range?,
        position: io.github.dingyi222666.luaparser.parser.ast.node.Position
    ): Boolean {
        range ?: return false
        return compare(range.start, position) <= 0 && compare(position, range.end) < 0
    }

    private fun compare(
        left: io.github.dingyi222666.luaparser.parser.ast.node.Position,
        right: io.github.dingyi222666.luaparser.parser.ast.node.Position
    ): Int {
        val lineComparison = left.line.compareTo(right.line)
        return if (lineComparison != 0) lineComparison else left.column.compareTo(right.column)
    }

    data class ResolvedRequire(
        val moduleName: String,
        val provider: WorkspaceModuleGraph.ModuleProvider,
        val surface: ModuleExportSurface
    )

    data class ResolvedExportMember(
        val providerPath: VirtualPath,
        val moduleName: String,
        val member: ModuleExportSurface.MemberExport
    ) {
        val handle: String = ModuleExportIdentity(providerPath, member.exportPath).asHandle()
        val kind: SymbolKind = member.kind
    }
}

internal data class ModuleExportIdentity(
    val providerPath: VirtualPath,
    val exportPath: List<String>
) {
    fun asHandle(): String {
        return "module-export:${providerPath.value}:${exportPath.joinToString(".")}" 
    }

    companion object {
        fun parse(handle: String?): ModuleExportIdentity? {
            if (handle == null || !handle.startsWith("module-export:")) {
                return null
            }
            val body = handle.removePrefix("module-export:")
            val separator = body.lastIndexOf(':')
            if (separator <= 0 || separator >= body.lastIndex) {
                return null
            }
            val path = body.substring(0, separator)
            val export = body.substring(separator + 1)
            if (export.isBlank()) {
                return null
            }
            return ModuleExportIdentity(VirtualPath.of(path), export.split('.'))
        }
    }
}
