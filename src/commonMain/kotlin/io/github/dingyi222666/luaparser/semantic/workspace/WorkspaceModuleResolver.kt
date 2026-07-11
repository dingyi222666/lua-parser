package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType

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
        if (dependency != null) {
            val surface = exportSurface(dependency.provider) ?: return null
            return ResolvedRequire(moduleName, dependency.provider, surface)
        }

        if (moduleName == "import") {
            val provider = activeProvider(moduleName)
            val surface = provider?.let(::exportSurface)
            if (provider != null && surface != null) {
                return ResolvedRequire(moduleName, provider, surface)
            }
        }

        if (moduleName == "import" && "import" in snapshot.builtinOverlay.globals.globalNames) {
            return ResolvedRequire(
                moduleName = moduleName,
                provider = WorkspaceModuleGraph.ModuleProvider(
                    moduleName = moduleName,
                    path = snapshot.builtinOverlay.globals.path,
                    source = WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY
                ),
                surface = syntheticImportSurface()
            )
        }

        return null
    }

    fun exportedMember(providerPath: VirtualPath, memberName: String): ResolvedExportMember? {
        return exportedMember(providerPath, listOf(memberName))
    }

    fun exportedMember(providerPath: VirtualPath, exportPath: List<String>): ResolvedExportMember? {
        val file = fileSnapshot(providerPath) ?: return null
        val surface = file.moduleExportSurface ?: return null
        val member = surface.members.firstOrNull { it.exportPath == exportPath } ?: return null
        val definitionProviderPath = when (val memberType = member.type) {
            is ModuleType -> activeProvider(memberType.moduleName)?.path ?: providerPath
            else -> providerPath
        }
        return ResolvedExportMember(
            providerPath = providerPath,
            definitionProviderPath = definitionProviderPath,
            moduleName = surface.moduleType.moduleName,
            member = member
        )
    }

    fun exportedMemberByHandle(handle: String): ResolvedExportMember? {
        val identity = ModuleExportIdentity.parse(handle) ?: return null
        return exportedMember(identity.providerPath, identity.exportPath)
    }

    fun exportAt(path: VirtualPath, position: io.github.dingyi222666.luaparser.parser.ast.node.Position): ResolvedExportMember? {
        val surface = fileSnapshot(path)?.moduleExportSurface ?: return null
        val member = surface.members.firstOrNull { rangeContains(it.range, position) } ?: return null
        return exportedMember(path, member.exportPath)
    }

    fun exportHandle(providerPath: VirtualPath, exportPath: List<String>): String {
        return ModuleExportIdentity(providerPath, exportPath).asHandle()
    }

    fun importedSymbolsFor(path: VirtualPath): Map<String, WorkspaceImportedSymbol> {
        val facts = snapshot.files[path]?.documentFacts ?: return emptyMap()
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        activeImportTargets(facts).forEach { target ->
            importedSymbolsForTarget(target).forEach { symbol ->
                imported[symbol.alias] = symbol
            }
        }
        return imported
    }

    fun importedSymbolFor(path: VirtualPath, alias: String): WorkspaceImportedSymbol? {
        return importedSymbolsFor(path)[alias]
    }

    fun importTargetSymbol(target: String): WorkspaceImportedSymbol? {
        val normalized = normalizeImportTarget(target)
        return if (normalized.endsWith(".*")) {
            importedPackageSymbol(normalized.removeSuffix(".*"))
        } else {
            importedClassSymbol(normalized)
        }
    }

    fun importTargetSymbolFor(path: VirtualPath, target: String): WorkspaceImportedSymbol? {
        val normalized = normalizeImportTarget(target)
        val facts = snapshot.files[path]?.documentFacts ?: return null
        val activeTargets = activeImportTargets(facts)
        if (normalized !in activeTargets) {
            return null
        }
        return importTargetSymbol(normalized)
    }

    fun importedSymbolsForTarget(target: String): List<WorkspaceImportedSymbol> {
        val normalized = normalizeImportTarget(target)
        return if (normalized.endsWith(".*")) {
            packageMembers(normalized.removeSuffix(".*"))
        } else {
            importedClassSymbol(normalized)?.let(::listOf).orEmpty()
        }
    }

    fun classProviderForAlias(alias: String): WorkspaceModuleGraph.ModuleProvider? {
        val provider = activeProvider(alias) ?: return null
        val moduleType = exportSurface(provider)?.moduleType ?: return null
        return if (moduleType.fields.containsKey("__class")) provider else null
    }

    fun classProviderForMember(
        packageProviderPath: VirtualPath,
        memberName: String
    ): WorkspaceModuleGraph.ModuleProvider? {
        val packageSurface = fileSnapshot(packageProviderPath)?.moduleExportSurface ?: return null
        val memberType = packageSurface.moduleType.fields[memberName] as? ModuleType ?: return null
        return classProviderForAlias(memberType.moduleName)
    }

    private fun fileSnapshot(path: VirtualPath): WorkspaceSnapshot.FileSnapshot? {
        return snapshot.files[path]
            ?: snapshot.extraProviders[path]
            ?: snapshot.builtinOverlay.providerModules[path]?.file
    }

    private fun activeImportTargets(facts: DocumentFacts): Set<String> {
        val targets = linkedSetOf<String>()
        facts.sourceImports.forEach { fact ->
            targets += normalizeImportTarget(fact.target)
        }
        facts.jvmClassLoads
            .asSequence()
            .filter { it.kind == DocumentFacts.JvmClassLoadKind.IMPORT_CALL }
            .forEach { fact ->
                targets += normalizeImportTarget(fact.target)
            }
        return targets
    }

    private fun importedClassSymbol(importText: String): WorkspaceImportedSymbol? {
        val aliases = classAliasCandidates(importText)
        val provider = aliases.firstNotNullOfOrNull(::classProviderForAlias) ?: return null
        val surface = exportSurface(provider) ?: return null
        return WorkspaceImportedSymbol(
            alias = surface.moduleType.moduleName,
            moduleName = surface.moduleType.moduleName,
            providerPath = provider.path,
            moduleType = surface.moduleType
        )
    }

    private fun importedPackageSymbol(packageName: String): WorkspaceImportedSymbol? {
        val packageProvider = packageProvider(packageName) ?: return null
        val moduleType = exportSurface(packageProvider)?.moduleType ?: return null
        return WorkspaceImportedSymbol(
            alias = packageName,
            moduleName = moduleType.moduleName,
            providerPath = packageProvider.path,
            moduleType = moduleType
        )
    }

    private fun packageMembers(packageName: String): List<WorkspaceImportedSymbol> {
        val packageProvider = packageProvider(packageName) ?: return emptyList()
        val packageType = exportSurface(packageProvider)?.moduleType ?: return emptyList()
        return packageType.fields
            .entries
            .sortedBy { it.key }
            .mapNotNull { (alias, memberType) ->
                val moduleType = memberType as? ModuleType ?: return@mapNotNull null
                val provider = classProviderForAlias(moduleType.moduleName) ?: classProviderForAlias(alias) ?: return@mapNotNull null
                WorkspaceImportedSymbol(
                    alias = alias,
                    moduleName = moduleType.moduleName,
                    providerPath = provider.path,
                    moduleType = moduleType
                )
            }
    }

    private fun packageProvider(packageName: String): WorkspaceModuleGraph.ModuleProvider? {
        val provider = activeProvider(packageName) ?: return null
        val surface = exportSurface(provider) ?: return null
        return if (surface.moduleType.moduleName == packageName) provider else null
    }

    private fun normalizeImportTarget(target: String): String {
        return target.removePrefix("import ")
            .trim()
            .substringAfter(':', target.removePrefix("import ").trim())
            .trim()
    }

    private fun classAliasCandidates(importText: String): List<String> {
        val normalized = normalizeImportTarget(importText)
        if (normalized.isBlank() || normalized.endsWith(".*")) {
            return emptyList()
        }
        val simpleName = normalized.substringAfterLast('.').substringAfterLast('$')
        return buildList {
            add(simpleName)
            val underscoreAlias = normalized.substringAfterLast('_')
            if (underscoreAlias != simpleName) {
                add(underscoreAlias)
            }
            val binaryAlias = normalized.substringAfterLast('$')
            if (binaryAlias != simpleName && binaryAlias != underscoreAlias) {
                add(binaryAlias)
            }
            if (normalized !in this) {
                add(normalized)
            }
        }.filter(String::isNotBlank)
    }

    private fun syntheticImportSurface(): ModuleExportSurface {
        val importType = FunctionType(
            parameters = listOf(FunctionParameter(name = "...", type = VarargType(PrimitiveType.ANY), vararg = true)),
            returnType = PrimitiveType.ANY
        )
        val moduleType = ModuleType(
            moduleName = "import",
            fields = linkedMapOf("__call" to importType),
            methods = linkedMapOf("import" to importType)
        )
        return ModuleExportSurface(
            moduleType = moduleType,
            sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER,
            members = listOf(
                ModuleExportSurface.MemberExport(
                    name = "__call",
                    exportPath = listOf("__call"),
                    kind = SymbolKind.FIELD,
                    type = importType,
                    range = null
                ),
                ModuleExportSurface.MemberExport(
                    name = "import",
                    exportPath = listOf("import"),
                    kind = SymbolKind.METHOD,
                    type = importType,
                    range = null
                )
            )
        )
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
        val definitionProviderPath: VirtualPath,
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
