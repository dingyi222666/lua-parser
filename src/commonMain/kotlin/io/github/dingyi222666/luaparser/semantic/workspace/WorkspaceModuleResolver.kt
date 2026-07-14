package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.mergeWorkspaceGlobalExtension
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType

internal class WorkspaceModuleResolver(
    private val snapshot: WorkspaceSnapshot
) {
    private val activeProviderCache = mutableMapOf<String, WorkspaceModuleGraph.ModuleProvider?>()
    private val importedSymbolsCache = mutableMapOf<VirtualPath, Map<String, WorkspaceImportedSymbol>>()
    private val providerGlobalSymbolsCache = mutableMapOf<VirtualPath, List<WorkspaceImportedSymbol>>()

    fun activeProvider(moduleName: String): WorkspaceModuleGraph.ModuleProvider? {
        if (moduleName.isBlank()) {
            return null
        }
        if (moduleName in activeProviderCache) {
            return activeProviderCache[moduleName]
        }
        // Dotted stdlib overlay recovery (e.g. AndroLua socket.url): keep dotted module names
        // even when graph activeProviders missed or only recorded a slash alias.
        // Extra JVM providers are claimed by moduleName during graph build; if a claim was lost
        // (duplicate simple names), still recover the exact class/package provider path.
        val provider = snapshot.graph.activeProviders[moduleName]
            ?: findOverlayProvider(moduleName)
            ?: findExtraClassProviderByAlias(moduleName)
            ?: snapshot.extraProviders.entries.firstOrNull { (_, file) ->
                file.moduleExportSurface?.moduleType?.moduleName == moduleName
            }?.let { (path, _) ->
                WorkspaceModuleGraph.ModuleProvider(
                    moduleName = moduleName,
                    path = path,
                    source = WorkspaceModuleGraph.ProviderSource.EXTRA_WORKSPACE_PROVIDER
                )
            }
            // Free-form Android-Lua layout modules (.aly) may exist in the workspace without a
            // pre-built dependency edge (e.g. partial graph rebuilds). Recover by path/module alias.
            ?: findAlyLayoutProvider(moduleName)
        activeProviderCache[moduleName] = provider
        return provider
    }

    fun exportSurface(provider: WorkspaceModuleGraph.ModuleProvider): ModuleExportSurface? {
        return fileSnapshot(provider.path)?.moduleExportSurface
            ?: syntheticAlyLayoutSurface(provider)
    }

    fun resolveRequire(consumerPath: VirtualPath, moduleName: String): ResolvedRequire? {
        val dependency = snapshot.graph.resolvedDependencies[consumerPath]
            .orEmpty()
            .firstOrNull { it.moduleName == moduleName }
        if (dependency != null) {
            // Always keep the resolved provider. Free-form .aly modules historically dropped here
            // when export collection was partial (`exportSurface(...) ?: return null`).
            val surface = exportSurface(dependency.provider)
                ?: syntheticAlyLayoutSurface(dependency.provider)
                ?: return null
            return resolvedRequire(moduleName, dependency.provider, surface)
        }

        if (moduleName == "import") {
            // Prefer a real Android-Lua STANDARD_LIBRARY_OVERLAY provider when present so
            // require("import") does not fall back to synthetic globals-only surfaces.
            val overlayProvider = snapshot.graph.providersByModuleName[moduleName]
                .orEmpty()
                .firstOrNull { it.source == WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY }
            val overlaySurface = overlayProvider?.let(::exportSurface)
            if (overlayProvider != null && overlaySurface != null) {
                return resolvedRequire(moduleName, overlayProvider, overlaySurface)
            }

            val provider = activeProvider(moduleName)
            val surface = provider?.let(::exportSurface)
            if (provider != null && surface != null) {
                return resolvedRequire(moduleName, provider, surface)
            }
        }

        if (moduleName == "import" && "import" in snapshot.builtinOverlay.globals.globalNames) {
            return resolvedRequire(
                moduleName = moduleName,
                provider = WorkspaceModuleGraph.ModuleProvider(
                    moduleName = moduleName,
                    path = snapshot.builtinOverlay.globals.path,
                    source = WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY
                ),
                surface = syntheticImportSurface()
            )
        }

        // Generic fallback: active provider (workspace VIRTUAL_PATH / .aly / extra / overlay).
        // Previously only "import" recovered here, so require("…representative_layout") without a
        // dependency edge returned null even when the .aly file was present in the workspace.
        val provider = activeProvider(moduleName) ?: return null
        val surface = exportSurface(provider) ?: syntheticAlyLayoutSurface(provider) ?: return null
        return resolvedRequire(moduleName, provider, surface)
    }

    fun exportedMember(providerPath: VirtualPath, memberName: String): ResolvedExportMember? {
        return exportedMember(providerPath, listOf(memberName))
    }

    fun exportedMember(providerPath: VirtualPath, exportPath: List<String>): ResolvedExportMember? {
        val file = fileSnapshot(providerPath) ?: return null
        val surface = file.moduleExportSurface ?: return null
        val member = surface.members.firstOrNull { it.exportPath == exportPath } ?: return null
        val resolvedType = resolvedMemberType(file, member) ?: member.type
        val definitionProviderPath = when (resolvedType) {
            is ModuleType -> activeProvider(resolvedType.moduleName)?.path ?: providerPath
            else -> providerPath
        }
        return ResolvedExportMember(
            providerPath = providerPath,
            definitionProviderPath = definitionProviderPath,
            moduleName = surface.moduleType.moduleName,
            member = member,
            type = resolvedType
        )
    }

    private fun resolvedRequire(
        moduleName: String,
        provider: WorkspaceModuleGraph.ModuleProvider,
        surface: ModuleExportSurface
    ): ResolvedRequire {
        return ResolvedRequire(
            moduleName = moduleName,
            provider = provider,
            surface = surface,
            moduleType = resolvedModuleType(provider.path, surface)
        )
    }

    private fun resolvedModuleType(providerPath: VirtualPath, surface: ModuleExportSurface): ModuleType {
        val file = fileSnapshot(providerPath) ?: return surface.moduleType
        val resolvedTypes = surface.members.mapNotNull { member ->
            resolvedMemberType(file, member)?.let { member.exportPath to it }
        }.toMap()
        if (resolvedTypes.isEmpty()) {
            return surface.moduleType
        }
        return surface.moduleType.copy(
            fields = surface.moduleType.fields.mapValues { (name, type) ->
                resolvedExportType(type, listOf(name), resolvedTypes)
            },
            methods = surface.moduleType.methods.mapValues { (name, type) ->
                resolvedExportType(type, listOf(name), resolvedTypes)
            }
        )
    }

    private fun resolvedExportType(
        structuralType: Type,
        exportPath: List<String>,
        resolvedTypes: Map<List<String>, Type>
    ): Type {
        resolvedTypes[exportPath]?.let { return it }
        return when (structuralType) {
            is TableType -> TableType(
                fields = structuralType.fields.mapValues { (name, type) ->
                    resolvedExportType(type, exportPath + name, resolvedTypes)
                },
                methods = structuralType.methods.mapValues { (name, type) ->
                    resolvedExportType(type, exportPath + name, resolvedTypes)
                },
                indexSignature = structuralType.indexSignature
            )
            is ModuleType -> structuralType.copy(
                fields = structuralType.fields.mapValues { (name, type) ->
                    resolvedExportType(type, exportPath + name, resolvedTypes)
                },
                methods = structuralType.methods.mapValues { (name, type) ->
                    resolvedExportType(type, exportPath + name, resolvedTypes)
                }
            )
            else -> structuralType
        }
    }

    private fun resolvedMemberType(
        file: WorkspaceSnapshot.FileSnapshot,
        member: ModuleExportSurface.MemberExport
    ): Type? {
        val range = member.range ?: return null
        val binder = file.semanticFile?.snapshot?.binder ?: return null
        val declaration = binder.declarationIndex.declarations
            .asSequence()
            .filter { it.name == member.name && it.declaredType != null }
            .sortedBy { if (it.anchorNode?.range == range) 0 else 1 }
            .firstOrNull { it.anchorNode?.range == range || it.range == range }
            ?: return null
        return mergeResolvedType(member.type, declaration.declaredType ?: return null)
    }

    private fun mergeResolvedType(structuralType: Type, resolvedType: Type): Type {
        if (resolvedType is OverloadedFunctionType) {
            return resolvedType
        }
        if (structuralType !is FunctionType || resolvedType !is FunctionType) {
            return resolvedType
        }
        val structuralParameters = structuralType.parameters.associateBy { it.name }
        val parameters = resolvedType.parameters.map { parameter ->
            val structural = structuralParameters[parameter.name]
            if (parameter.type == UnknownType && structural?.type != null) {
                parameter.copy(type = structural.type)
            } else {
                parameter
            }
        }
        return FunctionType(
            parameters = parameters,
            returnType = if (resolvedType.returnType == UnknownType) structuralType.returnType else resolvedType.returnType,
            typeParameters = resolvedType.typeParameters
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
        importedSymbolsCache[path]?.let { return it }
        val facts = snapshot.files[path]?.documentFacts ?: return emptyMap()
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        activeImportTargets(facts).forEach { target ->
            importedSymbolsForTarget(target).forEach { symbol ->
                imported[symbol.alias] = symbol
            }
        }
        // Also index by the last path segment so bare Locale/File lookups succeed even when a
        // provider surface temporarily reports a non-simple moduleName alias.
        activeImportTargets(facts).forEach { target ->
            val normalized = normalizeImportTarget(target)
            if (normalized.endsWith(".*")) {
                return@forEach
            }
            val simpleName = normalized.substringAfterLast('.').substringAfterLast('/').substringAfterLast('$').substringAfterLast('_')
            if (simpleName.isNotBlank() && simpleName !in imported) {
                importTargetSymbol(normalized)?.let { symbol ->
                    imported[simpleName] = symbol.copy(alias = simpleName)
                }
            }
        }
        dependencyGlobalSymbolsFor(path).forEach { symbol ->
            val existing = imported[symbol.alias]
            imported[symbol.alias] = if (symbol.extendsExistingGlobal && existing?.extendsExistingGlobal == true) {
                symbol.copy(valueType = mergeWorkspaceGlobalExtension(existing.valueType, symbol.valueType))
            } else {
                symbol
            }
        }
        return imported.toMap().also { importedSymbolsCache[path] = it }
    }

    private fun dependencyGlobalSymbolsFor(path: VirtualPath): List<WorkspaceImportedSymbol> {
        val facts = snapshot.files[path]?.documentFacts ?: return emptyList()
        val activeModules = buildSet {
            facts.sourceImports.forEach { sourceImport ->
                val target = normalizeImportTarget(sourceImport.target)
                if (!target.endsWith(".*")) {
                    add(target)
                }
            }
            facts.requires.forEach { require -> add(require.moduleName) }
        }
        if (activeModules.isEmpty()) {
            return emptyList()
        }
        return snapshot.graph.resolvedDependencies[path]
            .orEmpty()
            .filter { dependency -> normalizeImportTarget(dependency.moduleName) in activeModules }
            .flatMap { dependency -> providerGlobalSymbols(dependency.provider) }
    }

    private fun providerGlobalSymbols(
        provider: WorkspaceModuleGraph.ModuleProvider
    ): List<WorkspaceImportedSymbol> {
        providerGlobalSymbolsCache[provider.path]?.let { return it }
        val file = snapshot.files[provider.path] ?: return emptyList()
        val semanticSnapshot = file.semanticFile?.snapshot ?: return emptyList()
        val binder = semanticSnapshot.binder
        val evaluator = ExpressionTypeEvaluator(binder, semanticSnapshot.workspaceContext)
        val providerModuleType = file.moduleExportSurface?.moduleType ?: ModuleType(provider.moduleName)
        val globals = binder.declarationIndex.declarations
            .asReversed()
            .asSequence()
            .filter { declaration ->
                declaration.kind == DeclarationKind.GLOBAL && declaration.origin == DeclarationOrigin.AST
            }
            .distinctBy { it.name }
            .mapNotNull { declaration ->
                val anchor = declaration.anchorNode as? ExpressionNode ?: return@mapNotNull null
                val valueType = evaluator.evaluate(anchor)
                val kind = when (valueType) {
                    is CallableType -> SymbolKind.FUNCTION
                    is ModuleType, is TableType -> SymbolKind.MODULE
                    is ClassType -> SymbolKind.CLASS
                    else -> SymbolKind.VARIABLE
                }
                WorkspaceImportedSymbol(
                    alias = declaration.name,
                    moduleName = provider.moduleName,
                    providerPath = provider.path,
                    moduleType = providerModuleType,
                    valueType = valueType,
                    kind = kind,
                    definitionRange = declaration.anchorNode?.range ?: declaration.range
                )
            }
            .toList()
        val extendedGlobals = providerGlobalObjectExtensions(
            provider = provider,
            providerModuleType = providerModuleType,
            evaluator = evaluator,
            declarations = binder.declarationIndex.declarations,
            globalNames = semanticSnapshot.workspaceContext.overlayGlobals.globalNames
        )
        return (globals + extendedGlobals).also { providerGlobalSymbolsCache[provider.path] = it }
    }

    private fun providerGlobalObjectExtensions(
        provider: WorkspaceModuleGraph.ModuleProvider,
        providerModuleType: ModuleType,
        evaluator: ExpressionTypeEvaluator,
        declarations: List<io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration>,
        globalNames: Set<String>
    ): List<WorkspaceImportedSymbol> {
        val extensionsByRoot = declarations.asSequence()
            .filter { declaration ->
                declaration.origin == DeclarationOrigin.AST &&
                    declaration.kind in setOf(DeclarationKind.FIELD, DeclarationKind.METHOD)
            }
            .mapNotNull { declaration ->
                val member = declaration.anchorNode?.parent as? MemberExpression ?: return@mapNotNull null
                val root = member.base as? Identifier ?: return@mapNotNull null
                if (root.name !in globalNames) {
                    return@mapNotNull null
                }
                Triple(root, member, declaration)
            }
            .groupBy { (root) -> root.name }

        return extensionsByRoot.mapNotNull { (rootName, entries) ->
            val baseType = evaluator.evaluate(entries.first().first)
            val fields = linkedMapOf<String, Type>()
            val methods = linkedMapOf<String, Type>()
            entries.forEach { (_, member, declaration) ->
                val memberType = assignedMemberValueType(member, evaluator)
                    ?: evaluator.evaluate(member).takeUnless { it === UnknownType }
                    ?: declaration.declaredType?.takeUnless { it === UnknownType }
                    ?: return@forEach
                if (declaration.kind == DeclarationKind.METHOD) {
                    methods[declaration.name] = memberType
                } else {
                    fields[declaration.name] = memberType
                }
            }
            if (fields.isEmpty() && methods.isEmpty()) {
                return@mapNotNull null
            }
            val extensionType = TableType(fields = fields, methods = methods)
            WorkspaceImportedSymbol(
                alias = rootName,
                moduleName = provider.moduleName,
                providerPath = provider.path,
                moduleType = providerModuleType,
                valueType = mergeWorkspaceGlobalExtension(baseType, extensionType),
                kind = SymbolKind.MODULE,
                definitionRange = entries.first().third.anchorNode?.range ?: entries.first().third.range,
                extendsExistingGlobal = true
            )
        }
    }

    private fun assignedMemberValueType(
        member: MemberExpression,
        evaluator: ExpressionTypeEvaluator
    ): Type? {
        val assignment = member.parent as? AssignmentStatement ?: return null
        val targetIndex = assignment.init.indexOf(member)
        if (targetIndex < 0 || assignment.variables.isEmpty()) {
            return null
        }
        val initializer = assignment.variables.getOrNull(targetIndex)
            ?: assignment.variables.last()
        return evaluator.evaluate(initializer).takeUnless { it === UnknownType }
    }

    fun importedSymbolFor(path: VirtualPath, alias: String): WorkspaceImportedSymbol? {
        importedSymbolsFor(path)[alias]?.let { return it }
        // Path-scoped bare-name recovery: only when this file actively imported a matching target.
        val facts = snapshot.files[path]?.documentFacts ?: return null
        val match = activeImportTargets(facts).firstOrNull { target ->
            val normalized = normalizeImportTarget(target)
            if (normalized.endsWith(".*")) {
                false
            } else {
                val simpleName = normalized.substringAfterLast('.').substringAfterLast('/').substringAfterLast('$').substringAfterLast('_')
                simpleName == alias || normalized == alias
            }
        } ?: return null
        return importTargetSymbol(match)?.copy(alias = alias)
    }

    fun importTargetSymbol(target: String): WorkspaceImportedSymbol? {
        val normalized = normalizeImportTarget(target)
        return if (normalized.endsWith(".*")) {
            importedPackageSymbol(normalized.removeSuffix(".*"))
        } else {
            importedClassSymbol(normalized) ?: importedLuaModuleSymbol(normalized)
        }
    }

    fun importTargetSymbolFor(path: VirtualPath, target: String): WorkspaceImportedSymbol? {
        val normalized = normalizeImportTarget(target)
        val facts = snapshot.files[path]?.documentFacts ?: return null
        val activeTargets = activeImportTargets(facts)
        if (normalized !in activeTargets) {
            // Allow simple-name activation only for targets that this file imported.
            val matched = activeTargets.firstOrNull { active ->
                val activeNormalized = normalizeImportTarget(active)
                activeNormalized == normalized ||
                    (!activeNormalized.endsWith(".*") &&
                        activeNormalized.substringAfterLast('.').substringAfterLast('$') == normalized)
            } ?: return null
            return importTargetSymbol(matched)
        }
        return importTargetSymbol(normalized)
    }

    fun importedSymbolsForTarget(target: String): List<WorkspaceImportedSymbol> {
        val normalized = normalizeImportTarget(target)
        return if (normalized.endsWith(".*")) {
            packageMembers(normalized.removeSuffix(".*"))
        } else {
            importTargetSymbol(normalized)?.let(::listOf).orEmpty()
        }
    }

    fun classProviderForAlias(alias: String): WorkspaceModuleGraph.ModuleProvider? {
        // Never let a non-class activeProvider claim (package/overlay/module without __class)
        // short-circuit path recovery for JVM class modules (File / Locale / BigDecimal).
        activeProvider(alias)?.takeIf(::isJvmClassProvider)?.let { return it }
        findExtraClassProviderByAlias(alias)?.takeIf(::isJvmClassProvider)?.let { return it }
        return null
    }

    private fun isJvmClassProvider(provider: WorkspaceModuleGraph.ModuleProvider): Boolean {
        val moduleType = exportSurface(provider)?.moduleType ?: return false
        return moduleType.fields.containsKey("__class")
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
        // LuaJava class-load helpers (bindClass / loadLib / createProxy / newInstance /
        // createArray) and import() all mount class modules that must resolve as path-scoped
        // imported MODULE aliases (System after loadLib("java.lang.System", ...)).
        facts.jvmClassLoads.forEach { fact ->
            targets += normalizeImportTarget(fact.target)
        }
        return targets
    }

    private fun importedClassSymbol(importText: String): WorkspaceImportedSymbol? {
        val aliases = classAliasCandidates(importText)
        val provider = aliases.firstNotNullOfOrNull(::classProviderForAlias)
            // Simple-name source imports (import "File" / import "BigDecimal") may only be
            // discoverable via mounted __jvm__/classes/... path suffixes when graph claims
            // temporarily omit the simple moduleName alias.
            ?: simpleNameClassProvider(importText)
            ?: return null
        val surface = exportSurface(provider) ?: return null
        val simpleName = aliases.firstOrNull { it == surface.moduleType.moduleName }
            ?: aliases.firstOrNull { !it.contains('.') && !it.contains('$') && !it.contains('_') }
            ?: surface.moduleType.moduleName
        return WorkspaceImportedSymbol(
            alias = simpleName,
            moduleName = surface.moduleType.moduleName,
            providerPath = provider.path,
            moduleType = surface.moduleType
        )
    }

    private fun importedLuaModuleSymbol(importText: String): WorkspaceImportedSymbol? {
        val normalized = normalizeImportTarget(importText)
        if (normalized.isBlank() || normalized.endsWith(".*")) {
            return null
        }
        val provider = activeProvider(normalized) ?: return null
        if (provider.path !in snapshot.files ||
            (!provider.path.value.endsWith(".lua") && !provider.path.value.endsWith(".aly"))
        ) {
            return null
        }
        val surface = exportSurface(provider) ?: return null
        val alias = normalized
            .replace('\\', '/')
            .substringAfterLast('/')
            .substringAfterLast('.')
            .ifBlank { return null }
        return WorkspaceImportedSymbol(
            alias = alias,
            moduleName = surface.moduleType.moduleName,
            providerPath = provider.path,
            moduleType = resolvedModuleType(provider.path, surface)
        )
    }

    /**
     * Recover a mounted JVM class provider for a simple import target (File / BigDecimal)
     * from extraProviders path shape when active-provider indexing missed the alias.
     * Never invents providers: only existing __jvm__/classes entries with __class surfaces.
     */
    private fun simpleNameClassProvider(importText: String): WorkspaceModuleGraph.ModuleProvider? {
        val normalized = normalizeImportTarget(importText)
        if (normalized.isBlank() || normalized.endsWith(".*") || '.' in normalized || '$' in normalized) {
            return null
        }
        return classProviderForAlias(normalized) ?: findExtraClassProviderByAlias(normalized)
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
        val provider = activeProvider(packageName)
            ?: snapshot.extraProviders.entries.firstOrNull { (path, file) ->
                path.value == "__jvm__/packages/${packageName.replace('.', '/')}.lua" ||
                    file.moduleExportSurface?.moduleType?.moduleName == packageName
            }?.let { (path, _) ->
                WorkspaceModuleGraph.ModuleProvider(
                    moduleName = packageName,
                    path = path,
                    source = WorkspaceModuleGraph.ProviderSource.EXTRA_WORKSPACE_PROVIDER
                )
            }
            ?: return null
        val surface = exportSurface(provider) ?: return null
        return if (surface.moduleType.moduleName == packageName) provider else null
    }

    private fun findExtraClassProviderByAlias(alias: String): WorkspaceModuleGraph.ModuleProvider? {
        // Never invent providers. Recover only from already-mounted extras.
        // Slash paths are not aliases; blank is invalid. Dotted FQCNs still recover via
        // simpleName tail (java.io.File → File) when path ends with /File.lua.
        if (alias.isBlank() || alias.contains('/')) {
            return null
        }
        val simpleAlias = alias.substringAfterLast('.').substringAfterLast('$').substringAfterLast('_')
        if (simpleAlias.isBlank()) {
            return null
        }
        // Prefer reflective class modules under __jvm__/classes so package providers and
        // non-class extras never win simple-name recovery for Android-Lua source imports
        // (import "File" / import "BigDecimal" under default or custom importPrefixes).
        val classMatch = snapshot.extraProviders.entries.firstOrNull { (path, file) ->
            val value = path.value
            if (!value.startsWith("__jvm__/classes/")) {
                return@firstOrNull false
            }
            val moduleName = file.moduleExportSurface?.moduleType?.moduleName
            moduleName == alias ||
                moduleName == simpleAlias ||
                value.endsWith("/$simpleAlias.lua") ||
                value.endsWith("\$$simpleAlias.lua") ||
                value.endsWith("/$alias.lua") ||
                value.endsWith("\$$alias.lua")
        }
        val match = classMatch ?: snapshot.extraProviders.entries.firstOrNull { (path, file) ->
            val moduleName = file.moduleExportSurface?.moduleType?.moduleName
            moduleName == alias ||
                moduleName == simpleAlias ||
                path.value.endsWith("/$simpleAlias.lua") ||
                path.value.endsWith("\$$simpleAlias.lua") ||
                path.value.endsWith("/$alias.lua") ||
                path.value.endsWith("\$$alias.lua")
        } ?: return null
        val moduleName = match.value.moduleExportSurface?.moduleType?.moduleName
            ?: simpleAlias
        return WorkspaceModuleGraph.ModuleProvider(
            moduleName = moduleName,
            path = match.key,
            source = WorkspaceModuleGraph.ProviderSource.EXTRA_WORKSPACE_PROVIDER
        )
    }

    /**
     * Recover a STANDARD_LIBRARY_OVERLAY provider for dotted module names such as socket.url.
     * Prefer graph claims, then the mounted builtin overlay snapshot (path + moduleName).
     */
    private fun findOverlayProvider(moduleName: String): WorkspaceModuleGraph.ModuleProvider? {
        if (moduleName.isBlank()) {
            return null
        }
        val graphMatch = snapshot.graph.providersByModuleName[moduleName]
            .orEmpty()
            .firstOrNull { it.source == WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY }
        if (graphMatch != null) {
            return graphMatch
        }
        // Match by explicit overlay moduleName first (keeps dotted names like socket.url).
        snapshot.builtinOverlay.providerModules.entries.firstOrNull { (_, provider) ->
            provider.moduleName == moduleName
        }?.let { (path, provider) ->
            return WorkspaceModuleGraph.ModuleProvider(
                moduleName = provider.moduleName,
                path = path,
                source = WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY
            )
        }
        // Path-suffix recovery: __lua_std__/<ver>/socket.url.lua for module "socket.url".
        // Also accept nested historical paths (__lua_std__/<ver>/socket/url.lua) when present.
        val dotted = moduleName.replace('\\', '/').replace('/', '.')
        val slash = dotted.replace('.', '/')
        val candidates = listOf(
            "/$dotted.lua",
            "$dotted.lua",
            "/$slash.lua",
            "$slash.lua"
        )
        snapshot.builtinOverlay.providerModules.entries.firstOrNull { (path, provider) ->
            val value = path.value
            val providerDotted = provider.moduleName.replace('\\', '/').replace('/', '.')
            val nameMatches = providerDotted == dotted || provider.moduleName == moduleName
            val pathMatches = candidates.any { candidate ->
                value == candidate.trimStart('/') ||
                    value.endsWith(candidate) ||
                    value.endsWith("/$dotted.lua") ||
                    value.endsWith("/$slash.lua")
            }
            pathMatches && (nameMatches || provider.moduleName.isBlank())
        }?.let { (path, provider) ->
            return WorkspaceModuleGraph.ModuleProvider(
                moduleName = moduleName,
                path = path,
                source = WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY
            )
        }
        return null
    }

    /**
     * Recover an Android-Lua `.aly` layout provider for [moduleName] when graph active-provider
     * lookup missed it (path-derived claim not indexed, or only present as a workspace file).
     *
     * Never invents a fabricated `.lua` / JVM / stdlib path: only real workspace `.aly` files.
     */
    private fun findAlyLayoutProvider(moduleName: String): WorkspaceModuleGraph.ModuleProvider? {
        if (moduleName.isBlank()) {
            return null
        }
        val dotted = moduleName.replace('\\', '/')
        val candidates = listOf(
            "$dotted.aly",
            "${dotted.replace('.', '/')}.aly"
        )
        val match = snapshot.files.entries.firstOrNull { (path, _) ->
            val value = path.value
            if (!value.endsWith(".aly")) {
                return@firstOrNull false
            }
            val pathModule = alyModuleNameFromPath(path)
            pathModule == moduleName ||
                candidates.any { candidate ->
                    value == candidate || value.endsWith("/$candidate") || value.endsWith(candidate)
                }
        } ?: return null
        return WorkspaceModuleGraph.ModuleProvider(
            moduleName = moduleName,
            path = match.key,
            source = WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH
        )
    }

    private fun alyModuleNameFromPath(path: VirtualPath): String? {
        val normalized = path.value.replace('\\', '/')
        if (!normalized.endsWith(".aly")) {
            return null
        }
        return normalized.removeSuffix(".aly").replace('/', '.')
    }

    /**
     * Synthetic LuaLayoutSpec-like export for free-form `.aly` modules when AST export collection
     * did not produce a surface (e.g. sparse layout tables with only sequence view-class children).
     */
    private fun syntheticAlyLayoutSurface(provider: WorkspaceModuleGraph.ModuleProvider): ModuleExportSurface? {
        if (!provider.path.value.endsWith(".aly")) {
            return null
        }
        val moduleName = provider.moduleName.ifBlank {
            alyModuleNameFromPath(provider.path) ?: provider.path.value
        }
        return ModuleExportCollector.alyLayoutExportSurface(
            moduleName = moduleName,
            fields = emptyMap(),
            methods = emptyMap(),
            members = emptyList(),
            sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER
        )
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
        // Match Android-Lua import callable display: fun(...: any...): any
        val importType = FunctionType(
            parameters = listOf(
                FunctionParameter(
                    name = "...",
                    type = VarargType(PrimitiveType.ANY),
                    vararg = true
                )
            ),
            returnType = PrimitiveType.ANY,
            name = "fun(...: any...): any"
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
        val surface: ModuleExportSurface,
        val moduleType: ModuleType = surface.moduleType
    )

    data class ResolvedExportMember(
        val providerPath: VirtualPath,
        val definitionProviderPath: VirtualPath,
        val moduleName: String,
        val member: ModuleExportSurface.MemberExport,
        val type: Type
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
