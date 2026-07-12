package io.github.dingyi222666.luaparser.semantic.workspace.std

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.TypeAnnotationParser
import io.github.dingyi222666.luaparser.semantic.types.bridges.toModelType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaConstructorType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadSet
import io.github.dingyi222666.luaparser.semantic.types.model.JavaStaticMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import io.github.dingyi222666.luaparser.semantic.types.syntax.ArrayTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.GenericTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IdentifierObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IndexTableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IntersectionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.LiteralTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.MultiReturnTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NullableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.QuotedObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TupleTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.syntax.UnionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.VarargTypeSyntax
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.workspaceFingerprintHash

object BuiltinOverlayLoader {
    fun load(
        version: LuaVersion,
        analyze: (VirtualPath, String) -> WorkspaceSnapshot.FileSnapshot
    ): BuiltinOverlaySnapshot {
        val catalog = catalogFor(version)
        val providerModules = linkedMapOf<VirtualPath, BuiltinOverlaySnapshot.ProviderModuleSnapshot>()
        catalog.providerModuleResourcePaths.forEach { (moduleName, resourcePath) ->
            val path = overlayModulePath(catalog.versionSegment, moduleName)
            val resourceText = BuiltinOverlayResourceAccess.readText(resourcePath)
            val source = documentedModuleSource(moduleName, resourceText)
            val file = analyze(path, source).let { snapshot ->
                snapshot.copy(
                    moduleExportSurface = documentedModuleSurface(
                        moduleName = moduleName,
                        resourceText = resourceText,
                        analyzedSurface = snapshot.moduleExportSurface
                    )
                )
            }
            providerModules[path] = BuiltinOverlaySnapshot.ProviderModuleSnapshot(
                moduleName = moduleName,
                file = file
            )
        }
        catalog.rawProviderModuleResources.forEach { (moduleName, resource) ->
            val path = overlayModulePath(catalog.versionSegment, moduleName)
            val source = rawModuleSource(resource)
            val file = analyze(path, source).let { snapshot ->
                snapshot.copy(
                    moduleExportSurface = mergeRawModuleSurface(
                        moduleName = moduleName,
                        resourceText = source,
                        analyzedSurface = snapshot.moduleExportSurface,
                        syntheticSurface = resource.syntheticSurface
                    )
                )
            }
            providerModules[path] = BuiltinOverlaySnapshot.ProviderModuleSnapshot(
                moduleName = moduleName,
                file = file
            )
        }
        catalog.androidFrameworkResources?.let { resources ->
            androidFrameworkProviderModules(resources).forEach { (path, provider) ->
                providerModules[path] = provider
            }
        }
        val globalsPath = overlayGlobalsPath(catalog.versionSegment)
        val globalsSource = globalsSource(catalog)
        val providerModuleTypes = documentedGlobalProviderModuleTypes(catalog, providerModules)
        val globalsFile = analyze(globalsPath, globalsSource).let { snapshot ->
            snapshot.copy(
                moduleExportSurface = documentedGlobalsSurface(
                    resourceText = globalsSource,
                    analyzedSurface = snapshot.moduleExportSurface,
                    providerModuleTypes = providerModuleTypes
                )
            )
        }
        val globals = BuiltinOverlaySnapshot.GlobalsSnapshot.create(
            path = globalsPath,
            file = globalsFile,
            globalNames = catalog.globalNames,
            moduleFieldNames = catalog.moduleFieldNames
        )

        return BuiltinOverlaySnapshot(
            version = catalog.normalizedVersion,
            providerModules = providerModules,
            globals = globals
        )
    }

    fun standaloneGlobals(version: LuaVersion = LuaVersion.LUA_5_3): BuiltinOverlaySnapshot.GlobalsSnapshot {
        val overlay = load(version) { _, _ -> WorkspaceSnapshot.FileSnapshot() }
        return overlay.globals
    }

    private fun documentedModuleSource(moduleName: String, resourceText: String): String {
        val source = resourceText.trim()
        if (Regex("""(?m)^\s*return\s+${Regex.escape(moduleName)}\s*$""").containsMatchIn(source)) {
            return source
        }
        return "$source\n\nreturn $moduleName"
    }

    private fun rawModuleSource(resource: RawProviderModuleResource): String {
        return readTextOrFallback(resource.resourcePath, resource.fallbackSource).trim()
    }

    private fun mergeRawModuleSurface(
        moduleName: String,
        resourceText: String,
        analyzedSurface: ModuleExportSurface?,
        syntheticSurface: ModuleExportSurface?
    ): ModuleExportSurface? {
        val documentedSurface = documentedModuleSurface(moduleName, resourceText, analyzedSurface)
        return when (syntheticSurface) {
            null -> documentedSurface
            else -> mergeSyntheticSurface(
                documentedSurface = documentedSurface,
                syntheticSurface = syntheticSurface,
                documentedMemberPaths = documentedMemberPaths(moduleName, resourceText)
            )
        }
    }

    private fun readTextOrFallback(resourcePath: String, fallbackSource: String): String {
        return runCatching { BuiltinOverlayResourceAccess.readText(resourcePath) }
            .getOrElse { fallbackSource }
    }

    private fun globalsSource(catalog: Catalog): String {
        val source = catalog.globalsResourcePath
            ?.let { resourcePath -> readTextOrFallback(resourcePath, fallbackGlobalsSource(catalog)) }
            ?: fallbackGlobalsSource(catalog)
        val compatibilitySource = catalog.compatibilityGlobalsSource.trim()
        val declaredSource = listOf(source, compatibilitySource).filter(String::isNotBlank).joinToString("\n")
        val missingGlobals = catalog.globalNames.filterNot { name -> globalNameDeclared(declaredSource, name) }
        return buildString {
            append(source.trim())
            missingGlobals.forEach { name ->
                append('\n')
                append(name)
                append(" = ")
                append(name)
            }
            compatibilitySource
                .takeIf { it.isNotEmpty() }
                ?.let {
                    append('\n')
                    append(it)
            }
        }.trim()
    }

    private fun globalNameDeclared(source: String, name: String): Boolean {
        val escapedName = Regex.escape(name)
        return Regex("""(?m)^\s*(?:function\s+$escapedName\s*\(|$escapedName\s*=)""").containsMatchIn(source)
    }

    private fun fallbackGlobalsSource(catalog: Catalog): String {
        return buildString {
            catalog.globalNames.forEach { name ->
                append(name)
                append(" = ")
                append(name)
                append('\n')
            }
        }.trim()
    }

    private fun documentedGlobalsSurface(
        resourceText: String,
        analyzedSurface: ModuleExportSurface?,
        providerModuleTypes: Map<String, ModuleType?>
    ): ModuleExportSurface {
        return surfaceFromMembers(
            moduleName = "_G",
            members = documentedGlobalMembers(resourceText, analyzedSurface, providerModuleTypes),
            analyzedSurface = analyzedSurface
        )
    }

    private fun documentedGlobalProviderModuleTypes(
        @Suppress("UNUSED_PARAMETER") catalog: Catalog,
        providerModules: Map<VirtualPath, BuiltinOverlaySnapshot.ProviderModuleSnapshot>
    ): Map<String, ModuleType?> {
        // Preserve each provider's own moduleName (luajava stays "luajava").
        // AndroLua free-id helpers after require "import" are merged from the dedicated
        // import module surface in documentedGlobalMembers — never by renaming luajava.
        return providerModules.values.associateTo(linkedMapOf()) { provider ->
            provider.moduleName to provider.file.moduleExportSurface?.moduleType
        }
    }

    private fun documentedModuleSurface(
        moduleName: String,
        resourceText: String,
        analyzedSurface: ModuleExportSurface?
    ): ModuleExportSurface {
        return surfaceFromMembers(
            moduleName = moduleName,
            members = documentedModuleMembers(moduleName, resourceText, analyzedSurface),
            analyzedSurface = analyzedSurface
        )
    }

    private fun surfaceFromMembers(
        moduleName: String,
        members: List<ModuleExportSurface.MemberExport>,
        analyzedSurface: ModuleExportSurface?
    ): ModuleExportSurface {
        val fields = linkedMapOf<String, Type>()
        val methods = linkedMapOf<String, Type>()
        members.forEach { member ->
            if (member.exportPath.size == 1) {
                if (member.kind == SymbolKind.METHOD) {
                    methods[member.name] = member.type
                } else {
                    fields[member.name] = member.type
                }
            }
        }
        return ModuleExportSurface(
            moduleType = ModuleType(moduleName = moduleName, fields = fields, methods = methods),
            sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER,
            hasSeeAllFallback = analyzedSurface?.hasSeeAllFallback ?: false,
            moduleEnvironmentMode = analyzedSurface?.moduleEnvironmentMode,
            members = members
        )
    }

    private fun mergeSyntheticSurface(
        documentedSurface: ModuleExportSurface,
        syntheticSurface: ModuleExportSurface,
        documentedMemberPaths: Set<List<String>>
    ): ModuleExportSurface {
        val membersByPath = linkedMapOf<List<String>, ModuleExportSurface.MemberExport>()
        syntheticSurface.members.forEach { member ->
            membersByPath[member.exportPath] = member
        }
        documentedSurface.members.filter { it.exportPath in documentedMemberPaths }.forEach { member ->
            val syntheticMember = membersByPath[member.exportPath]
            membersByPath[member.exportPath] = when (syntheticMember) {
                null -> member
                else -> member.copy(kind = syntheticMember.kind)
            }
        }

        val fields = linkedMapOf<String, Type>()
        val methods = linkedMapOf<String, Type>()
        membersByPath.values.forEach { member ->
            if (member.exportPath.size == 1) {
                if (member.kind == SymbolKind.METHOD) {
                    methods[member.name] = member.type
                } else {
                    fields[member.name] = member.type
                }
            }
        }

        return documentedSurface.copy(
            moduleType = documentedSurface.moduleType.copy(
                fields = fields,
                methods = methods
            ),
            members = membersByPath.values.sortedWith(
                compareBy<ModuleExportSurface.MemberExport>(
                    { if (it.kind == SymbolKind.FIELD) 0 else 1 },
                    { it.exportPath.joinToString(".") }
                )
            )
        )
    }

    private fun documentedMemberPaths(moduleName: String, resourceText: String): Set<List<String>> {
        val functionRegex = Regex("""^\s*function\s+${Regex.escape(moduleName)}[.:]([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val assignmentRegex = Regex("""^\s*${Regex.escape(moduleName)}\.([A-Za-z_][A-Za-z0-9_]*)\s*=""")
        val paths = documentedClassFieldMembers(moduleName, resourceText)
            .mapTo(linkedSetOf()) { member -> member.exportPath }
        val pendingDocLines = mutableListOf<String>()

        resourceText.lineSequence().forEach { line ->
            val trimmedStart = line.trimStart()
            if (trimmedStart.startsWith("---")) {
                pendingDocLines += trimmedStart
                return@forEach
            }

            val memberName = functionRegex.find(line)?.groupValues?.get(1)
                ?: assignmentRegex.find(line)?.groupValues?.get(1)
            if (memberName != null) {
                if (pendingDocLines.hasTypedMemberDoc()) {
                    paths += listOf(memberName)
                }
                pendingDocLines.clear()
                return@forEach
            }

            if (line.isBlank() || !trimmedStart.startsWith("--")) {
                pendingDocLines.clear()
            }
        }

        return paths
    }

    private fun List<String>.hasTypedMemberDoc(): Boolean = any { line ->
        val tag = line.removePrefix("---").trimStart()
        tag.startsWith("@generic ") ||
            tag.startsWith("@field ") ||
            tag.startsWith("@param ") ||
            tag.startsWith("@return ") ||
            tag.startsWith("@type ") ||
            tag.startsWith("@overload ") ||
            tag.startsWith("@method ")
    }

    private fun documentedGlobalMembers(
        resourceText: String,
        analyzedSurface: ModuleExportSurface?,
        providerModuleTypes: Map<String, ModuleType?>
    ): List<ModuleExportSurface.MemberExport> {
        val functionRegex = Regex("""^\s*function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""")
        val assignmentRegex = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""")
        val analyzedByPath = analyzedSurface?.members.orEmpty().associateBy { it.exportPath }
        val membersByPath = linkedMapOf<List<String>, StandardMemberDescriptor>()
        val pendingDocLines = mutableListOf<String>()
        val documentedClassTypes = documentedClassTypes(resourceText)

        resourceText.lineSequence().forEach { line ->
            val trimmedStart = line.trimStart()
            if (trimmedStart.startsWith("---")) {
                pendingDocLines += trimmedStart
                return@forEach
            }

            val functionMatch = functionRegex.find(line)
            if (functionMatch != null) {
                val name = functionMatch.groupValues[1]
                val exportPath = listOf(name)
                val doc = parseMemberDoc(pendingDocLines)
                membersByPath[exportPath] = StandardMemberDescriptor(
                    name = name,
                    exportPath = exportPath,
                    kind = if (name in DOCUMENTED_CALLABLE_GLOBAL_VALUE_NAMES) SymbolKind.FIELD else SymbolKind.FUNCTION,
                    type = hydrateDocumentedType(
                        documentedFunctionType(functionMatch.groupValues[2], doc),
                        documentedClassTypes
                    ),
                    range = analyzedByPath[exportPath]?.range
                )
                pendingDocLines.clear()
                return@forEach
            }

            val assignmentMatch = assignmentRegex.find(line)
            if (assignmentMatch != null) {
                val name = assignmentMatch.groupValues[1]
                val exportPath = listOf(name)
                val doc = parseMemberDoc(pendingDocLines)
                val assignmentType = providerModuleTypes[name]
                    ?: doc.inlineType
                    ?: documentedAssignmentType(assignmentMatch.groupValues[2])
                membersByPath.putIfAbsent(
                    exportPath,
                    StandardMemberDescriptor(
                        name = name,
                        exportPath = exportPath,
                        kind = SymbolKind.FIELD,
                        type = hydrateDocumentedType(assignmentType, documentedClassTypes),
                        range = analyzedByPath[exportPath]?.range
                    )
                )
                pendingDocLines.clear()
                return@forEach
            }

            if (line.isBlank() || !trimmedStart.startsWith("--")) {
                pendingDocLines.clear()
            }
        }

        analyzedSurface?.members.orEmpty().forEach { member ->
            membersByPath.putIfAbsent(
                member.exportPath,
                StandardMemberDescriptor(
                    name = member.name,
                    exportPath = member.exportPath,
                    kind = member.kind,
                    type = hydrateDocumentedType(member.type, documentedClassTypes),
                    range = member.range
                )
            )
        }

        // AndroLua require "import" / env_import(_G) installs import-module helpers as free
        // globals. Prefer richer types from the dedicated import provider surface when the
        // globals document only thin stubs (TASK-536/TASK-603 install surface finalize).
        providerModuleTypes["import"]?.let { importModuleType ->
            mergeAndroluaImportHelperGlobals(
                membersByPath = membersByPath,
                importModuleType = importModuleType,
                documentedClassTypes = documentedClassTypes,
                analyzedByPath = analyzedByPath
            )
        }

        // Hard-lock documented pairs/ipairs generic callable metadata so completion +
        // signature help always surface fun<K, V>/fun<V> shapes (TASK-668 / TASK-122).
        // EmmyLua parse paths can collapse to fun(): unknown when generic return fun(...)
        // multi-returns are not fully rehydrated into binder-visible declaredType.
        applyDocumentedPairsIpairsHardLocks(membersByPath, analyzedByPath)

        return membersByPath.values
            .sortedWith(compareBy<StandardMemberDescriptor>({ symbolKindOrder(it.kind) }, { it.exportPath.joinToString(".") }))
            .map { descriptor ->
                ModuleExportSurface.MemberExport(
                    name = descriptor.name,
                    exportPath = descriptor.exportPath,
                    kind = descriptor.kind,
                    type = descriptor.type,
                    // Builtin globals are seeded into file binders via documented.range.
                    // Overlay analysis ranges live on a different virtual document and would make
                    // activity/loadlayout invisible at real file positions (start > position).
                    range = null
                )
            }
    }

    /**
     * Documented EmmyLua pairs/ipairs shapes for free-id completion and signature help.
     * pairs: fun of K,V table/array to iterator returning K, V.
     * ipairs: fun of V table/array to iterator returning number, V.
     */
    private fun applyDocumentedPairsIpairsHardLocks(
        membersByPath: MutableMap<List<String>, StandardMemberDescriptor>,
        analyzedByPath: Map<List<String>, ModuleExportSurface.MemberExport>
    ) {
        val pairsType = documentedPairsFunctionType()
        val ipairsType = documentedIpairsFunctionType()
        listOf(
            "pairs" to pairsType,
            "ipairs" to ipairsType
        ).forEach { (name, type) ->
            val exportPath = listOf(name)
            val existing = membersByPath[exportPath]
            membersByPath[exportPath] = StandardMemberDescriptor(
                name = name,
                exportPath = exportPath,
                // Keep FIELD so BuiltinSymbolSeeder still treats these as value callables with
                // declared FunctionType (DOCUMENTED_CALLABLE_GLOBAL_VALUE_NAMES).
                kind = SymbolKind.FIELD,
                type = type,
                range = existing?.range ?: analyzedByPath[exportPath]?.range
            )
        }
    }

    private fun documentedPairsFunctionType(): FunctionType {
        val key = TypeParameterType(name = "K")
        val value = TypeParameterType(name = "V")
        val tableKV = TableType(indexSignature = TableType.IndexSignature(key, value))
        val paramT = unionTypeOf(tableKV, ArrayType(value))
        val iterator = FunctionType(
            parameters = listOf(FunctionParameter(name = "tbl", type = tableKV)),
            returnType = MultiReturnType(listOf(key, value))
        )
        return FunctionType(
            parameters = listOf(FunctionParameter(name = "t", type = paramT)),
            returnType = iterator,
            typeParameters = listOf(key, value)
        )
    }

    private fun documentedIpairsFunctionType(): FunctionType {
        val value = TypeParameterType(name = "V")
        val tableNV = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.NUMBER, value)
        )
        val paramT = unionTypeOf(tableNV, ArrayType(value))
        val iterator = FunctionType(
            parameters = listOf(FunctionParameter(name = "tbl", type = tableNV)),
            returnType = MultiReturnType(listOf(PrimitiveType.NUMBER, value))
        )
        return FunctionType(
            parameters = listOf(FunctionParameter(name = "t", type = paramT)),
            returnType = iterator,
            typeParameters = listOf(value)
        )
    }

    /**
     * Merge require "import" helper install surface into free-id globals.
     *
     * Runtime import.lua returns env_import and mutates the target env with import helpers
     * (import/env_import/compile/enum/each/dump/printstack/getids/thread/task/timer plus
     * loadlayout/loadbitmap/loadmenu/luajava). Keep FUNCTION kind for callables so
     * BuiltinSymbolSeeder + completions expose CompletionItemKind.FUNCTION.
     */
    private fun mergeAndroluaImportHelperGlobals(
        membersByPath: MutableMap<List<String>, StandardMemberDescriptor>,
        importModuleType: ModuleType,
        documentedClassTypes: Map<String, ClassType>,
        analyzedByPath: Map<List<String>, ModuleExportSurface.MemberExport>
    ) {
        val envImportType = androluaEnvImportFunctionType()
        val helperMethods = linkedMapOf<String, Type>()
        importModuleType.methods.forEach { (name, type) -> helperMethods[name] = type }
        // __call is the env_import installer returned by require "import".
        importModuleType.fields["__call"]?.let { callType ->
            helperMethods.putIfAbsent("env_import", callType)
            helperMethods.putIfAbsent("import", callType)
        }
        importModuleType.fields["env_import"]?.let { helperMethods["env_import"] = it }
        // Prefer dedicated env_import signature for free-id hover/call typing.
        helperMethods["env_import"] = envImportType

        // Callable layout helpers installed as globals after require "import".
        listOf("loadlayout", "loadbitmap", "loadmenu").forEach { helperName ->
            val field = importModuleType.fields[helperName]
            val callType = when (field) {
                is ModuleType -> field.fields["__call"]
                is FunctionType, is OverloadedFunctionType -> field
                else -> null
            } ?: importModuleType.methods[helperName]
            if (callType != null) {
                helperMethods.putIfAbsent(helperName, callType)
            }
        }

        helperMethods.forEach { (name, type) ->
            val exportPath = listOf(name)
            val hydrated = hydrateDocumentedType(type, documentedClassTypes)
            val existing = membersByPath[exportPath]
            when {
                existing == null -> {
                    membersByPath[exportPath] = StandardMemberDescriptor(
                        name = name,
                        exportPath = exportPath,
                        kind = SymbolKind.FUNCTION,
                        type = hydrated,
                        range = analyzedByPath[exportPath]?.range
                    )
                }
                // Upgrade thin unknown/any stubs and force FUNCTION for install helpers.
                existing.type is UnknownType ||
                    existing.type == PrimitiveType.ANY ||
                    (
                        existing.kind != SymbolKind.FUNCTION &&
                            (hydrated is FunctionType || hydrated is OverloadedFunctionType)
                    ) -> {
                    membersByPath[exportPath] = existing.copy(
                        kind = SymbolKind.FUNCTION,
                        type = when {
                            existing.type is UnknownType || existing.type == PrimitiveType.ANY -> hydrated
                            name == "env_import" -> envImportType
                            else -> existing.type
                        }
                    )
                }
                name == "env_import" -> {
                    membersByPath[exportPath] = existing.copy(
                        kind = SymbolKind.FUNCTION,
                        type = envImportType
                    )
                }
                else -> {
                    // Keep documented types but ensure FUNCTION kind for completions.
                    if (existing.kind != SymbolKind.FUNCTION &&
                        (existing.type is FunctionType || existing.type is OverloadedFunctionType)
                    ) {
                        membersByPath[exportPath] = existing.copy(kind = SymbolKind.FUNCTION)
                    }
                }
            }
        }

        // Free-id global `luajava` reuses the documented luajava member surface installed by
        // require "import", but brands moduleName as "import" while keeping displayName
        // "luajava" (TASK-146 / TASK-668 import.luajava surface contract).
        val documentedLuajava = when (val field = importModuleType.fields["luajava"]) {
            is ModuleType -> field
            else -> null
        }
        val brandedLuajava = when {
            documentedLuajava != null -> ModuleType(
                moduleName = "import",
                fields = documentedLuajava.fields,
                methods = documentedLuajava.methods,
                indexSignature = documentedLuajava.indexSignature,
                name = "luajava"
            )
            else -> ModuleType(
                moduleName = "import",
                fields = importModuleType.fields.filterKeys { it != "luajava" && it != "__call" && it != "env_import" && it !in listOf("loadlayout", "loadbitmap", "loadmenu") }
                    .filterValues { it !is FunctionType && it !is OverloadedFunctionType }
                    .ifEmpty {
                        // Fall back to import-module methods/fields that mirror luajava helpers.
                        linkedMapOf<String, Type>().also { fields ->
                            importModuleType.fields.forEach { (name, type) ->
                                if (name in LUJAVA_SURFACE_FIELD_NAMES) {
                                    fields[name] = type
                                }
                            }
                        }
                    },
                methods = importModuleType.methods.filterKeys { it in LUJAVA_SURFACE_METHOD_NAMES },
                name = "luajava"
            )
        }
        val exportPath = listOf("luajava")
        val hydrated = hydrateDocumentedType(brandedLuajava, documentedClassTypes)
        val existing = membersByPath[exportPath]
        membersByPath[exportPath] = StandardMemberDescriptor(
            name = "luajava",
            exportPath = exportPath,
            kind = SymbolKind.FIELD,
            type = hydrated,
            range = existing?.range ?: analyzedByPath[exportPath]?.range
        )
    }

    private fun documentedModuleMembers(
        moduleName: String,
        resourceText: String,
        analyzedSurface: ModuleExportSurface?
    ): List<ModuleExportSurface.MemberExport> {
        val functionRegex = Regex("""^\s*function\s+${Regex.escape(moduleName)}([.:])([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""")
        val assignmentRegex = Regex("""^\s*${Regex.escape(moduleName)}\.([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""")
        val analyzedByPath = analyzedSurface?.members.orEmpty().associateBy { it.exportPath }
        val membersByPath = linkedMapOf<List<String>, StandardMemberDescriptor>()
        val pendingDocLines = mutableListOf<String>()
        val documentedClassTypes = documentedClassTypes(resourceText)

        documentedClassFieldMembers(moduleName, resourceText, analyzedByPath).forEach { descriptor ->
            membersByPath[descriptor.exportPath] = descriptor.copy(
                type = hydrateDocumentedType(descriptor.type, documentedClassTypes)
            )
        }

        resourceText.lineSequence().forEach { line ->
            val trimmedStart = line.trimStart()
            if (trimmedStart.startsWith("---")) {
                pendingDocLines += trimmedStart
                return@forEach
            }

            val functionMatch = functionRegex.find(line)
            if (functionMatch != null) {
                val separator = functionMatch.groupValues[1]
                val memberName = functionMatch.groupValues[2]
                val exportPath = listOf(memberName)
                val doc = parseMemberDoc(pendingDocLines)
                val type = hydrateDocumentedType(
                    documentedFunctionType(functionMatch.groupValues[3], doc),
                    documentedClassTypes
                )
                membersByPath[exportPath] = StandardMemberDescriptor(
                    name = memberName,
                    exportPath = exportPath,
                    kind = if (separator == ":") SymbolKind.METHOD else SymbolKind.FIELD,
                    type = type,
                    range = analyzedByPath[exportPath]?.range
                )
                pendingDocLines.clear()
                return@forEach
            }

            val assignmentMatch = assignmentRegex.find(line)
            if (assignmentMatch != null) {
                val memberName = assignmentMatch.groupValues[1]
                val exportPath = listOf(memberName)
                val doc = parseMemberDoc(pendingDocLines)
                membersByPath[exportPath] = StandardMemberDescriptor(
                    name = memberName,
                    exportPath = exportPath,
                    kind = SymbolKind.FIELD,
                    type = hydrateDocumentedType(
                        doc.inlineType ?: documentedAssignmentType(assignmentMatch.groupValues[2]),
                        documentedClassTypes
                    ),
                    range = analyzedByPath[exportPath]?.range
                )
                pendingDocLines.clear()
                return@forEach
            }

            if (line.isBlank() || !trimmedStart.startsWith("--")) {
                pendingDocLines.clear()
            }
        }

        analyzedSurface?.members.orEmpty().forEach { member ->
            membersByPath.putIfAbsent(
                member.exportPath,
                StandardMemberDescriptor(
                    name = member.name,
                    exportPath = member.exportPath,
                    kind = member.kind,
                    type = hydrateDocumentedType(member.type, documentedClassTypes),
                    range = member.range
                )
            )
        }

        return membersByPath.values
            .sortedWith(compareBy<StandardMemberDescriptor>({ symbolKindOrder(it.kind) }, { it.exportPath.joinToString(".") }))
            .map { descriptor ->
                ModuleExportSurface.MemberExport(
                    name = descriptor.name,
                    exportPath = descriptor.exportPath,
                    kind = descriptor.kind,
                    type = descriptor.type,
                    range = descriptor.range
                )
            }
    }

    private fun documentedClassFieldMembers(
        moduleName: String,
        resourceText: String,
        analyzedByPath: Map<List<String>, ModuleExportSurface.MemberExport> = emptyMap()
    ): List<StandardMemberDescriptor> {
        val members = mutableListOf<StandardMemberDescriptor>()
        var activeModuleClass = false

        resourceText.lineSequence().forEach { line ->
            val tag = line.trimStart().takeIf { it.startsWith("---") }
                ?.removePrefix("---")
                ?.trimStart()

            if (tag == null) {
                if (line.isNotBlank() && !line.trimStart().startsWith("--")) {
                    activeModuleClass = false
                }
                return@forEach
            }

            if (tag.startsWith("@class ")) {
                activeModuleClass = documentedClassName(tag.removePrefix("@class").trim()) == moduleName
                return@forEach
            }

            if (activeModuleClass && tag.startsWith("@field ")) {
                parseDocumentedField(tag.removePrefix("@field").trim())?.let { field ->
                    val exportPath = listOf(field.name)
                    members += StandardMemberDescriptor(
                        name = field.name,
                        exportPath = exportPath,
                        kind = SymbolKind.FIELD,
                        type = field.type,
                        range = analyzedByPath[exportPath]?.range
                    )
                }
            }
        }

        return members
    }


    /**
     * Build ClassType models from EmmyLua ---@class / ---@field / ---@method docs so
     * Android-Lua globals such as activity:getLuaDir resolve member surfaces.
     */
    private fun documentedClassTypes(resourceText: String): Map<String, ClassType> {
        data class ClassDraft(
            val name: String,
            val superTypeNames: List<String>,
            val typeParameters: List<TypeParameterType>,
            val fields: MutableMap<String, Type> = linkedMapOf(),
            val methods: MutableMap<String, Type> = linkedMapOf()
        )

        val drafts = linkedMapOf<String, ClassDraft>()
        var activeClassName: String? = null

        resourceText.lineSequence().forEach { line ->
            val tag = line.trimStart().takeIf { it.startsWith("---") }
                ?.removePrefix("---")
                ?.trimStart()
                ?: return@forEach

            when {
                tag.startsWith("@class ") -> {
                    val classText = tag.removePrefix("@class").trim()
                    val name = documentedClassName(classText)
                    if (name.isEmpty()) {
                        activeClassName = null
                        return@forEach
                    }
                    val superTypes = classText.substringAfter(':', missingDelimiterValue = "")
                        .trim()
                        .takeIf(String::isNotEmpty)
                        ?.let { TypeAnnotationParser().splitTopLevel(it, ',') }
                        .orEmpty()
                        .map { documentedClassName(it.trim()) }
                        .filter { it.isNotEmpty() && it !in ANDROID_FRAMEWORK_UNMODELED_SUPER_TYPES }
                    drafts[name] = ClassDraft(
                        name = name,
                        superTypeNames = superTypes,
                        typeParameters = documentedClassTypeParameters(classText)
                    )
                    activeClassName = name
                }

                tag.startsWith("@field ") -> {
                    val className = activeClassName ?: return@forEach
                    val field = parseDocumentedField(tag.removePrefix("@field").trim()) ?: return@forEach
                    val draft = drafts.getOrPut(className) {
                        ClassDraft(name = className, superTypeNames = emptyList(), typeParameters = emptyList())
                    }
                    // Callable fields are modeled as methods so ClassType member resolution
                    // (and hover SymbolKind.METHOD) matches Android-Lua context APIs like getLuaDir.
                    if (field.type is FunctionType || field.type is OverloadedFunctionType) {
                        draft.methods.mergeDocumentedFunction(field.name, field.type)
                    } else {
                        draft.fields[field.name] = field.type
                    }
                }

                tag.startsWith("@method ") -> {
                    val body = tag.removePrefix("@method").trim()
                    val signatureText = body.substringBefore(' ').trim()
                    val typeText = body.substringAfter(' ', "").trim()
                    val methodMatch = Regex("""^([A-Za-z_][\w.]*)[:.]([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(([^)]*)\))?$""")
                        .find(signatureText)
                    val bareMethodMatch = if (methodMatch == null) {
                        Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(([^)]*)\))?$""").find(signatureText)
                    } else {
                        null
                    }
                    val className: String
                    val methodName: String
                    val parameterList: String
                    when {
                        methodMatch != null -> {
                            className = methodMatch.groupValues[1]
                            methodName = methodMatch.groupValues[2]
                            parameterList = methodMatch.groupValues[3]
                        }
                        bareMethodMatch != null && activeClassName != null -> {
                            className = activeClassName!!
                            methodName = bareMethodMatch.groupValues[1]
                            parameterList = bareMethodMatch.groupValues[2]
                        }
                        else -> return@forEach
                    }
                    val returnTypeText = when {
                        typeText.startsWith("fun") -> null
                        typeText.startsWith(":") -> typeText.removePrefix(":").trim()
                        typeText.isNotEmpty() && !typeText.startsWith("(") -> typeText
                        else -> null
                    }
                    val functionType = when {
                        typeText.startsWith("fun") ->
                            (parseDocumentedType(typeText) as? FunctionType)
                                ?: anyFunction(parseDocumentedType(returnTypeText.orEmpty()) ?: UnknownType)

                        else -> {
                            val returnType = returnTypeText
                                ?.takeIf(String::isNotEmpty)
                                ?.let(::parseDocumentedType)
                                ?: UnknownType
                            val parameters = parseFunctionParameterNames(parameterList).map { parameterName ->
                                val optional = parameterName.endsWith("?")
                                val normalizedName = parameterName.removeSuffix("?")
                                val vararg = normalizedName == "..."
                                FunctionParameter(
                                    name = normalizedName,
                                    type = if (vararg) VarargType(PrimitiveType.ANY) else PrimitiveType.ANY,
                                    optional = optional,
                                    vararg = vararg
                                )
                            }
                            FunctionType(parameters = parameters, returnType = returnType)
                        }
                    }
                    drafts.getOrPut(className) {
                        ClassDraft(name = className, superTypeNames = emptyList(), typeParameters = emptyList())
                    }.methods.mergeDocumentedFunction(methodName, functionType)
                    activeClassName = className
                }
            }
        }

        if (drafts.isEmpty()) {
            return emptyMap()
        }

        val cache = linkedMapOf<String, ClassType>()
        fun build(name: String, stack: MutableSet<String>): ClassType {
            cache[name]?.let { return it }
            val draft = drafts[name]
            if (draft == null) {
                return ClassType(name = name)
            }
            if (!stack.add(name)) {
                return ClassType(name = name, typeParameters = draft.typeParameters)
            }
            val superClasses = draft.superTypeNames.map { superName -> build(superName, stack) }
            val classType = ClassType(
                name = name,
                fields = sortedTypeMap(draft.fields),
                methods = sortedTypeMap(draft.methods),
                superClass = superClasses.firstOrNull(),
                superType = superClasses.drop(1).firstOrNull(),
                typeParameters = draft.typeParameters
            )
            stack.remove(name)
            cache[name] = classType
            return classType
        }

        drafts.keys.sorted().forEach { name -> build(name, linkedSetOf()) }
        return cache
    }

    private fun hydrateDocumentedType(type: Type, classTypes: Map<String, ClassType>): Type {
        if (classTypes.isEmpty()) {
            return type
        }
        return when (type) {
            is CustomType -> classTypes[type.name] ?: type
            is ClassType -> classTypes[type.name] ?: type
            is FunctionType -> FunctionType(
                parameters = type.parameters.map { parameter ->
                    parameter.copy(type = hydrateDocumentedType(parameter.type, classTypes))
                },
                returnType = hydrateDocumentedType(type.returnType, classTypes),
                typeParameters = type.typeParameters
            )
            is OverloadedFunctionType -> OverloadedFunctionType(
                type.callSignatures.map { signature ->
                    hydrateDocumentedType(signature, classTypes) as FunctionType
                }
            )
            is ArrayType -> ArrayType(hydrateDocumentedType(type.elementType, classTypes))
            is VarargType -> VarargType(hydrateDocumentedType(type.elementType, classTypes))
            is TupleType -> TupleType(type.elementTypes.map { hydrateDocumentedType(it, classTypes) })
            is MultiReturnType -> MultiReturnType(type.types.map { hydrateDocumentedType(it, classTypes) })
            is AppliedType -> AppliedType(
                baseName = type.baseName,
                typeArguments = type.typeArguments.map { hydrateDocumentedType(it, classTypes) }
            )
            is TableType -> TableType(
                fields = type.fields.mapValues { (_, fieldType) -> hydrateDocumentedType(fieldType, classTypes) },
                indexSignature = type.indexSignature?.let { signature ->
                    TableType.IndexSignature(
                        keyType = hydrateDocumentedType(signature.keyType, classTypes),
                        valueType = hydrateDocumentedType(signature.valueType, classTypes)
                    )
                }
            )
            is ModuleType -> type.copy(
                fields = type.fields.mapValues { (_, fieldType) -> hydrateDocumentedType(fieldType, classTypes) },
                methods = type.methods.mapValues { (_, methodType) -> hydrateDocumentedType(methodType, classTypes) }
            )
            is UnionType -> unionTypeOf(type.types.map { hydrateDocumentedType(it, classTypes) })
            is IntersectionType -> IntersectionType(type.types.map { hydrateDocumentedType(it, classTypes) }.toSet())
            else -> type
        }
    }

    private fun documentedClassName(classText: String): String {
        return classText
            .substringBefore(':')
            .trim()
            .substringBefore(' ')
            .substringBefore('<')
    }

    private fun androidFrameworkClassDeclaration(
        classText: String,
        indexedClassNames: Set<String>
    ): AndroidFrameworkClassDeclaration {
        return AndroidFrameworkClassDeclaration(
            binaryName = normalizeAndroidFrameworkDocumentedClassName(
                className = documentedClassName(classText),
                indexedClassNames = indexedClassNames
            ),
            declaredSuperTypes = documentedClassSuperTypes(classText, indexedClassNames),
            typeParameters = documentedClassTypeParameters(classText)
        )
    }

    private fun documentedClassSuperTypes(classText: String, indexedClassNames: Set<String>): List<String> {
        val superText = classText.substringAfter(':', missingDelimiterValue = "").trim()
        if (superText.isEmpty()) {
            return emptyList()
        }
        return TypeAnnotationParser().splitTopLevel(superText, ',')
            .mapNotNull { superTypeText ->
                val superTypeName = documentedClassName(superTypeText.trim())
                    .takeIf { it.isNotEmpty() && it !in ANDROID_FRAMEWORK_UNMODELED_SUPER_TYPES }
                    ?: return@mapNotNull null
                normalizeAndroidFrameworkDocumentedClassName(superTypeName, indexedClassNames)
            }
    }

    private fun documentedClassTypeParameters(classText: String): List<TypeParameterType> {
        val classHead = classText.substringBefore(':').substringBefore(' ').trim()
        val genericStart = classHead.indexOf('<')
        val genericEnd = classHead.lastIndexOf('>')
        if (genericStart == -1 || genericEnd <= genericStart) {
            return emptyList()
        }
        return parseGenericParameters(classHead.substring(genericStart + 1, genericEnd))
    }

    private fun parseDocumentedField(fieldText: String): DocumentedField? {
        val nameToken = fieldText.substringBefore(' ').trim()
        val typeAndDescription = fieldText.substringAfter(' ', "").trim()
        if (nameToken.isEmpty() || typeAndDescription.isEmpty() || nameToken.startsWith("[")) {
            return null
        }
        val fieldName = nameToken.removeSuffix("?")
        val type = parseDocumentedType(typeAndDescription) ?: return null
        return DocumentedField(
            name = fieldName,
            type = if (nameToken.endsWith("?")) unionTypeOf(type, PrimitiveType.NIL) else type
        )
    }

    private fun symbolKindOrder(kind: SymbolKind): Int {
        return when (kind) {
            SymbolKind.FIELD -> 0
            SymbolKind.FUNCTION -> 1
            SymbolKind.METHOD -> 2
            else -> 3
        }
    }

    private fun documentedFunctionType(parameterListText: String, doc: MemberDoc): Type {
        val parameters = parseFunctionParameterNames(parameterListText).map { parameterName ->
            val paramDoc = doc.parameters[parameterName]
            val vararg = parameterName == "..." || paramDoc?.vararg == true
            val parameterType = paramDoc?.type ?: PrimitiveType.ANY
            FunctionParameter(
                name = parameterName,
                type = if (vararg && parameterType !is VarargType) VarargType(parameterType) else parameterType,
                optional = paramDoc?.optional ?: false,
                vararg = vararg
            )
        }
        val primary = FunctionType(
            parameters = parameters,
            returnType = doc.returnType ?: UnknownType,
            typeParameters = doc.genericParameters
        )
        val signatures = listOf(primary) + doc.overloads
        return when (signatures.size) {
            1 -> primary
            else -> OverloadedFunctionType(signatures)
        }
    }

    private fun MutableMap<String, Type>.mergeDocumentedFunction(name: String, type: Type) {
        val existing = this[name]
        val signatures = existing?.let(::functionSignatures).orEmpty() + functionSignatures(type)
        this[name] = when (signatures.size) {
            0 -> type
            1 -> signatures.single()
            else -> OverloadedFunctionType(signatures)
        }
    }

    private fun functionSignatures(type: Type): List<FunctionType> = when (type) {
        is FunctionType -> listOf(type)
        is OverloadedFunctionType -> type.callSignatures
        else -> emptyList()
    }

    private fun parseFunctionParameterNames(parameterListText: String): List<String> =
        parameterListText.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)

    private fun documentedAssignmentType(valueText: String): Type {
        val normalized = valueText.trim()
        return when {
            normalized == "nil" -> UnknownType
            normalized == "true" || normalized == "false" -> LiteralType(normalized.toBooleanStrict(), PrimitiveType.BOOLEAN)
            normalized.startsWith("\"") || normalized.startsWith("'") -> LiteralType(
                normalized.takeWhileStringLiteral().removeSurrounding("\"").removeSurrounding("'"),
                PrimitiveType.STRING
            )
            normalized.toDoubleOrNull() != null -> LiteralType(normalized.toDouble(), PrimitiveType.NUMBER)
            normalized.startsWith("{") -> tableAny()
            else -> UnknownType
        }
    }

    private fun String.takeWhileStringLiteral(): String {
        val quote = firstOrNull() ?: return this
        if (quote != '"' && quote != '\'') {
            return this
        }
        var escaped = false
        for (index in 1 until length) {
            val current = this[index]
            if (escaped) {
                escaped = false
            } else if (current == '\\') {
                escaped = true
            } else if (current == quote) {
                return substring(0, index + 1)
            }
        }
        return this
    }

    private fun parseMemberDoc(lines: List<String>): MemberDoc {
        val parameters = linkedMapOf<String, ParameterDoc>()
        val overloads = mutableListOf<FunctionType>()
        val genericParameters = mutableListOf<TypeParameterType>()
        var returnType: Type? = null
        var inlineType: Type? = null

        lines.forEach { line ->
            val tag = line.removePrefix("---").trimStart()
            when {
                tag.startsWith("@generic ") -> {
                    genericParameters += parseGenericParameters(tag.removePrefix("@generic").trim())
                }

                tag.startsWith("@param ") -> {
                    val body = tag.removePrefix("@param").trim()
                    val nameToken = body.substringBefore(' ').trim()
                    val typeText = body.substringAfter(' ', "").trim()
                    val parameterName = nameToken.removeSuffix("?")
                    val type = parseDocumentedType(typeText, genericParameters) ?: PrimitiveType.ANY
                    parameters[parameterName] = ParameterDoc(
                        type = type,
                        optional = nameToken.endsWith("?"),
                        vararg = parameterName == "..."
                    )
                }

                tag.startsWith("@return ") -> {
                    returnType = parseDocumentedReturnType(tag.removePrefix("@return").trim(), genericParameters)
                }

                tag.startsWith("@type ") -> {
                    inlineType = parseDocumentedType(tag.removePrefix("@type").trim(), genericParameters)
                }

                tag.startsWith("@overload ") -> {
                    (parseDocumentedType(tag.removePrefix("@overload").trim(), genericParameters) as? FunctionType)
                        ?.let(overloads::add)
                }
            }
        }

        return MemberDoc(
            parameters = parameters,
            returnType = returnType,
            inlineType = inlineType,
            overloads = overloads,
            genericParameters = genericParameters
        )
    }

    private fun parseGenericParameters(typeText: String): List<TypeParameterType> {
        return TypeAnnotationParser().splitTopLevel(typeText, ',').mapNotNull { part ->
            val normalized = part.trim()
            if (normalized.isEmpty()) {
                return@mapNotNull null
            }
            val colonIndex = findTopLevelChar(normalized, ':')
            val name = if (colonIndex == -1) normalized else normalized.substring(0, colonIndex).trim()
            if (name.isEmpty()) {
                return@mapNotNull null
            }
            val constraint = if (colonIndex == -1) {
                null
            } else {
                parseDocumentedType(normalized.substring(colonIndex + 1).trim(), emptyList())
            }
            TypeParameterType(name = name, constraint = constraint)
        }
    }

    private fun parseDocumentedReturnType(typeText: String, genericParameters: List<TypeParameterType>): Type? {
        parseDocumentedCompleteType(typeText, genericParameters)?.let { type ->
            return type
        }
        val parts = TypeAnnotationParser().splitTopLevel(typeText, ',')
        val returnTypes = parts.mapNotNull { parseDocumentedType(it, genericParameters) }
        return when (returnTypes.size) {
            0 -> null
            1 -> returnTypes.single()
            else -> MultiReturnType(returnTypes)
        }
    }

    private fun parseDocumentedType(typeText: String, genericParameters: List<TypeParameterType> = emptyList()): Type? {
        val normalized = typeText.trim()
        if (normalized.isEmpty()) {
            return null
        }

        parseDocumentedCompleteType(normalized, genericParameters)?.let { return it }
        parseDocumentedTypePrefix(normalized, genericParameters)?.let { return it }
        val parser = TypeAnnotationParser()
        return runCatching {
            val prefix = parser.parseTypePrefix(normalized)
            parser.resolve(prefix.syntax).toModelType()
        }.getOrNull()
    }

    private fun parseDocumentedCompleteType(
        typeText: String,
        genericParameters: List<TypeParameterType>
    ): Type? {
        parseDocumentedTypeSyntax(typeText, genericParameters)?.let { return it }
        return runCatching { TypeAnnotationParser().parse(typeText).toModelType() }.getOrNull()
    }

    private fun parseDocumentedTypeSyntax(
        typeText: String,
        genericParameters: List<TypeParameterType>
    ): Type? {
        val syntax = runCatching { TypeSyntaxParser.parse(typeText) }.getOrNull() ?: return null
        return resolveDocumentedTypeSyntax(
            syntax = syntax,
            genericParameters = genericParameters.associateBy { it.name }
        )
    }

    private fun parseDocumentedTypePrefix(
        typeText: String,
        genericParameters: List<TypeParameterType>
    ): Type? {
        val parsed = runCatching { TypeSyntaxParser.parsePrefix(typeText) }.getOrNull() ?: return null
        if (parsed.consumedLength <= 0) {
            return null
        }
        return resolveDocumentedTypeSyntax(
            syntax = parsed.syntax,
            genericParameters = genericParameters.associateBy { it.name }
        )
    }

    private fun resolveDocumentedTypeSyntax(
        syntax: TypeSyntax,
        genericParameters: Map<String, TypeParameterType>
    ): Type {
        return when (syntax) {
            is NamedTypeSyntax -> primitiveDocumentedType(syntax.name)
                ?: genericParameters[syntax.name]
                ?: CustomType(syntax.name)

            is LiteralTypeSyntax -> literalDocumentedType(syntax.value)
            is UnionTypeSyntax -> unionTypeOf(syntax.options.map { resolveDocumentedTypeSyntax(it, genericParameters) })
            is IntersectionTypeSyntax -> IntersectionType(syntax.types.map { resolveDocumentedTypeSyntax(it, genericParameters) }.toSet())
            is ArrayTypeSyntax -> ArrayType(resolveDocumentedTypeSyntax(syntax.elementType, genericParameters))
            is GenericTypeSyntax -> resolveDocumentedGenericType(syntax, genericParameters)
            is NullableTypeSyntax -> unionTypeOf(resolveDocumentedTypeSyntax(syntax.innerType, genericParameters), PrimitiveType.NIL)
            is TupleTypeSyntax -> TupleType(syntax.elements.map { resolveDocumentedTypeSyntax(it, genericParameters) })
            is MultiReturnTypeSyntax -> MultiReturnType(syntax.types.map { resolveDocumentedTypeSyntax(it, genericParameters) })
            is VarargTypeSyntax -> VarargType(resolveDocumentedTypeSyntax(syntax.elementType, genericParameters))
            is FunctionTypeSyntax -> resolveDocumentedFunctionType(syntax, genericParameters)
            is ObjectTypeSyntax -> resolveDocumentedObjectType(syntax, genericParameters)
            is IndexTableTypeSyntax -> TableType(
                indexSignature = TableType.IndexSignature(
                    keyType = resolveDocumentedTypeSyntax(syntax.keyType, genericParameters),
                    valueType = resolveDocumentedTypeSyntax(syntax.valueType, genericParameters)
                )
            )
        }
    }

    private fun resolveDocumentedFunctionType(
        syntax: FunctionTypeSyntax,
        genericParameters: Map<String, TypeParameterType>
    ): FunctionType {
        val inlineParameters = syntax.typeParameters.map { parameter ->
            TypeParameterType(
                name = parameter.name,
                constraint = parameter.constraint?.let { resolveDocumentedTypeSyntax(it, genericParameters) }
            )
        }
        val nestedGenerics = genericParameters + inlineParameters.associateBy { it.name }
        val parameters = syntax.parameters.map { parameter ->
            val type = resolveDocumentedTypeSyntax(parameter.type, nestedGenerics)
            FunctionParameter(
                name = parameter.name ?: if (parameter.vararg) "..." else "_",
                type = if (parameter.vararg) VarargType(type) else type,
                optional = parameter.optional,
                vararg = parameter.vararg
            )
        }
        return FunctionType(
            parameters = parameters,
            returnType = resolveDocumentedTypeSyntax(syntax.returnType, nestedGenerics),
            typeParameters = inlineParameters
        )
    }

    private fun resolveDocumentedGenericType(
        syntax: GenericTypeSyntax,
        genericParameters: Map<String, TypeParameterType>
    ): Type {
        val baseName = (syntax.baseType as? NamedTypeSyntax)?.name
            ?: resolveDocumentedTypeSyntax(syntax.baseType, genericParameters).displayName
        val arguments = syntax.arguments.map { resolveDocumentedTypeSyntax(it, genericParameters) }
        return if (baseName == "table" && arguments.size == 2) {
            TableType(indexSignature = TableType.IndexSignature(arguments[0], arguments[1]))
        } else {
            AppliedType(baseName = baseName, typeArguments = arguments)
        }
    }

    private fun resolveDocumentedObjectType(
        syntax: ObjectTypeSyntax,
        genericParameters: Map<String, TypeParameterType>
    ): Type {
        val fields = linkedMapOf<String, Type>()
        syntax.fields.forEach { field ->
            val name = when (val fieldName = field.name) {
                is IdentifierObjectFieldNameSyntax -> fieldName.value
                is QuotedObjectFieldNameSyntax -> fieldName.literal.removeSurrounding("\"").removeSurrounding("'")
            }
            val type = resolveDocumentedTypeSyntax(field.type, genericParameters)
            fields[name] = if (field.optional) unionTypeOf(type, PrimitiveType.NIL) else type
        }
        val indexSignature = syntax.indexers.firstOrNull()?.let { indexer ->
            TableType.IndexSignature(
                keyType = resolveDocumentedTypeSyntax(indexer.keyType, genericParameters),
                valueType = resolveDocumentedTypeSyntax(indexer.valueType, genericParameters)
            )
        }
        return TableType(fields = fields, indexSignature = indexSignature)
    }

    private fun primitiveDocumentedType(name: String): Type? = when (name) {
        "string" -> PrimitiveType.STRING
        "number" -> PrimitiveType.NUMBER
        "integer" -> DOCUMENTED_INTEGER_TYPE
        "boolean", "bool" -> PrimitiveType.BOOLEAN
        "nil", "void" -> PrimitiveType.NIL
        "function" -> PrimitiveType.FUNCTION
        "table" -> PrimitiveType.TABLE
        "thread" -> PrimitiveType.THREAD
        "userdata" -> PrimitiveType.USERDATA
        "any" -> PrimitiveType.ANY
        "unknown" -> UnknownType
        else -> null
    }

    private fun literalDocumentedType(value: String): Type {
        return when (value) {
            "true" -> LiteralType(true, PrimitiveType.BOOLEAN)
            "false" -> LiteralType(false, PrimitiveType.BOOLEAN)
            "nil" -> LiteralType(null, PrimitiveType.NIL)
            else -> value.toDoubleOrNull()
                ?.let { LiteralType(it, PrimitiveType.NUMBER) }
                ?: LiteralType(value.removeSurrounding("\"").removeSurrounding("'"), PrimitiveType.STRING)
        }
    }

    private fun findTopLevelChar(text: String, target: Char): Int {
        var angleDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var parenDepth = 0
        var inString = false
        var stringChar = '\u0000'

        text.forEachIndexed { index, char ->
            if (inString) {
                if (char == stringChar && text.getOrNull(index - 1) != '\\') {
                    inString = false
                }
                return@forEachIndexed
            }

            when (char) {
                '\'', '"' -> {
                    inString = true
                    stringChar = char
                }

                '<' -> angleDepth++
                '>' -> angleDepth = (angleDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth++
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                target -> if (angleDepth == 0 && braceDepth == 0 && bracketDepth == 0 && parenDepth == 0) {
                    return index
                }
            }
        }

        return -1
    }

    private data class StandardMemberDescriptor(
        val name: String,
        val exportPath: List<String>,
        val kind: SymbolKind,
        val type: Type,
        val range: Range?
    )

    private data class MemberDoc(
        val parameters: Map<String, ParameterDoc>,
        val returnType: Type?,
        val inlineType: Type?,
        val overloads: List<FunctionType>,
        val genericParameters: List<TypeParameterType>
    )

    private data class DocumentedField(
        val name: String,
        val type: Type
    )

    private data class ParameterDoc(
        val type: Type,
        val optional: Boolean,
        val vararg: Boolean
    )

    private fun overlayModulePath(versionSegment: String, moduleName: String): VirtualPath =
        VirtualPath.of("__lua_std__/$versionSegment/$moduleName.lua")

    private fun overlayGlobalsPath(versionSegment: String): VirtualPath =
        VirtualPath.of("__lua_std__/$versionSegment/_G.lua")

    private fun catalogFor(version: LuaVersion): Catalog = when (version) {
        LuaVersion.LUA_5_3 -> lua53Catalog()
        LuaVersion.LUA_5_4 -> lua54Catalog()
        LuaVersion.ANDROLUA_5_3 -> androlua53Catalog()
    }

    private fun lua53Catalog() = Catalog(
        normalizedVersion = LuaVersion.LUA_5_3,
        versionSegment = "5.3",
        globalsResourcePath = "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/global.lua",
        providerModuleResourcePaths = lua53ProviderModuleResourcePaths(),
        compatibilityGlobalsSource = """
            bit32 = bit32
            function package.seeall(module) end
        """.trimIndent(),
        globalNames = linkedSetOf(
            "_G", "_VERSION", "assert", "bit32", "collectgarbage", "coroutine", "debug", "dofile",
            "error", "getmetatable", "io", "ipairs", "load", "loadfile", "math", "module", "next",
            "os", "package", "pairs", "pcall", "print", "rawequal", "rawget", "rawlen", "rawset",
            "require", "select", "setmetatable", "string", "table", "tonumber", "tostring", "type",
            "utf8", "xpcall"
        ),
        moduleFieldNames = linkedMapOf(
            "bit32" to linkedSetOf("band"),
            "coroutine" to linkedSetOf("create"),
            "debug" to linkedSetOf("traceback"),
            "io" to linkedSetOf("open"),
            "math" to linkedSetOf("abs"),
            "os" to linkedSetOf("clock"),
            "package" to linkedSetOf("loaded", "searchpath", "seeall"),
            "string" to linkedSetOf("format"),
            "table" to linkedSetOf("insert"),
            "utf8" to linkedSetOf("len")
        )
    )

    private fun androlua53Catalog(): Catalog {
        val lua53 = lua53Catalog()
        val luajavaSurface = androluaLuaJavaSurface()
        val androluaModules = androlua53RawProviderModuleResources(luajavaSurface.moduleType)
        return lua53.copy(
            normalizedVersion = LuaVersion.ANDROLUA_5_3,
            versionSegment = "androlua5.3",
            globalsResourcePath = ANDROLUA_RESOURCE_ROOT + "_G.lua",
            rawProviderModuleResources = linkedMapOf(
                "luajava" to RawProviderModuleResource(
                    resourcePath = ANDROLUA_LUAJAVA_RESOURCE,
                    fallbackSource = AndroidLua53LuaJavaBuiltinOverlaySources.luajavaSource,
                    syntheticSurface = luajavaSurface
                )
            ).also { it.putAll(androluaModules) },
            androidFrameworkResources = AndroidFrameworkResources(
                rootResource = ANDROID_FRAMEWORK_RESOURCE_ROOT,
                manifestResource = ANDROID_FRAMEWORK_MANIFEST_RESOURCE
            ),
            compatibilityGlobalsSource = lua53.compatibilityGlobalsSource,
            globalNames = linkedSetOf(
                *lua53.globalNames.toTypedArray(),
                "activity",
                "service",
                "this",
                "context",
                "luajava",
                "import",
                "env_import",
                "compile",
                "enum",
                "each",
                "dump",
                "printstack",
                "getids",
                "thread",
                "task",
                "timer",
                "loadbitmap",
                "loadlayout",
                "loadmenu"
            ),
            moduleFieldNames = linkedMapOf(
                *lua53.moduleFieldNames.entries.map { it.key to it.value }.toTypedArray(),
                "activity" to emptySet<String>(),
                "service" to emptySet<String>(),
                "this" to emptySet<String>(),
                "context" to emptySet<String>(),
                "luajava" to linkedSetOf(
                    "loaded",
                    "imported",
                    "ids",
                    "luadir",
                    "bindClass",
                    "new",
                    "newInstance",
                    "loadLib",
                    "createProxy",
                    "newArray",
                    "createArray",
                    "astable",
                    "tostring",
                    "instanceof",
                    "getContext",
                    "override"
                ),
                "import" to linkedSetOf("import", "env_import", "loadlayout", "loadbitmap", "loadmenu", "luajava")
            )
        )
    }

    private fun androluaLuaJavaSurface(): ModuleExportSurface {
        val resourceText = readTextOrFallback(
            resourcePath = ANDROLUA_LUAJAVA_RESOURCE,
            fallbackSource = AndroidLua53LuaJavaBuiltinOverlaySources.luajavaSource
        ).trim()
        return mergeRawModuleSurface(
            moduleName = "luajava",
            resourceText = resourceText,
            analyzedSurface = null,
            syntheticSurface = androluaLuaJavaSyntheticSurface()
        ) ?: androluaLuaJavaSyntheticSurface()
    }

    private fun androluaLuaJavaSyntheticSurface(): ModuleExportSurface = moduleSurface(
        moduleName = "luajava",
        fields = linkedMapOf(
            "loaded" to tableAny(),
            "imported" to tableAny(),
            "ids" to tableAny(),
            "luadir" to PrimitiveType.STRING
        ),
        methods = methodMap(
            "bindClass", "new", "newInstance", "loadLib", "createProxy", "newArray", "createArray",
            "astable", "tostring", "instanceof", "getContext", "override"
        )
    )

    private fun androlua53RawProviderModuleResources(luajavaModuleType: ModuleType): Map<String, RawProviderModuleResource> {
        val managedModules = linkedMapOf<String, RawProviderModuleResource>(
            "import" to androidLuaResource("import.lua", surface = androluaImportSurface(luajavaModuleType)),
            callableAndroidLuaResource("loadlayout.lua", "loadlayout", returnType = CustomType("AndroidView")),
            callableAndroidLuaResource("loadbitmap.lua", "loadbitmap", returnType = CustomType("Bitmap")),
            callableAndroidLuaResource("loadmenu.lua", "loadmenu", returnType = CustomType("AndroidMenu")),
            callableAndroidLuaResource("modules/autotheme.lua", "autotheme", returnType = PrimitiveType.NUMBER),
            androidLuaModule(
                "modules/base64.lua",
                "base64",
                methods = listOf("encode", "decode"),
                methodTypes = linkedMapOf(
                    "encode" to anyFunction(PrimitiveType.STRING),
                    "decode" to anyFunction(PrimitiveType.STRING)
                )
            ),
            callableAndroidLuaResource("modules/bin.lua", "bin"),
            androidLuaModule("modules/bmob.lua", "bmob", methods = listOf("sign", "login")),
            androidLuaModule("modules/check.lua", "check", methods = listOf("check", "uncheck")),
            androidLuaModule("modules/console.lua", "console", methods = listOf("build", "build_aly")),
            androidLuaModule("modules/ftp.lua", "ftp", methods = listOf("put", "get", "command")),
            androidLuaModule("modules/hex.lua", "hex", methods = listOf("encode", "decode", "dump", "smart_dump", "pack", "smart_pack")),
            androidLuaModule(
                "modules/http.lua",
                "http",
                fields = linkedMapOf("cookie" to PrimitiveType.STRING, "header" to tableAny(), "ua" to PrimitiveType.STRING),
                methods = listOf("request", "get", "post", "download", "upload", "open"),
                methodTypes = linkedMapOf(
                    "get" to anyFunction(tableAny()),
                    "post" to anyFunction(tableAny()),
                    "request" to anyFunction(tableAny())
                )
            ),
            androidLuaModule(
                "modules/json.lua",
                "json",
                methods = listOf("encode", "decode", "null", "encodeString", "isArray", "isEncodable"),
                methodTypes = linkedMapOf(
                    "encode" to anyFunction(PrimitiveType.STRING),
                    "decode" to anyFunction(PrimitiveType.ANY),
                    "encodeString" to anyFunction(PrimitiveType.STRING)
                )
            ),
            androidLuaModule("modules/logcat.lua", "logcat", methods = listOf("readlog", "clearlog", "show")),
            androidLuaModule(
                "modules/ltn12.lua",
                "ltn12",
                fields = linkedMapOf("filter" to tableAny(), "source" to tableAny(), "sink" to tableAny(), "pump" to tableAny())
            ),
            androidLuaModule("modules/mbox.lua", "mbox", methods = listOf("parse", "parse_message")),
            androidLuaModule("modules/mime.lua", "mime", methods = listOf("normalize", "stuff", "wrap", "encode", "decode")),
            androidLuaModule("modules/options.lua", "options"),
            androidLuaModule(
                "modules/permission.lua",
                "permission",
                fields = linkedMapOf("permission" to tableAny(), "permission_info" to tableAny())
            ),
            androidLuaModule("modules/smtp.lua", "smtp", methods = listOf("message", "send")),
            androidLuaModule(
                "modules/socket.lua",
                "socket",
                fields = linkedMapOf("BLOCKSIZE" to PrimitiveType.NUMBER, "sourcet" to tableAny(), "sinkt" to tableAny()),
                methods = listOf("connect", "connect4", "connect6", "bind", "choose", "newtry", "try", "protect", "skip", "sink", "source")
            ),
            androidLuaModule("modules/socket/headers.lua", "socket.headers", fields = linkedMapOf("canonic" to tableAny())),
            androidLuaModule("modules/socket/tp.lua", "socket.tp", fields = linkedMapOf("TIMEOUT" to PrimitiveType.NUMBER), methods = listOf("connect")),
            androidLuaModule(
                "modules/socket/url.lua",
                "socket.url",
                fields = linkedMapOf("_VERSION" to PrimitiveType.STRING),
                methods = listOf("parse", "build", "escape", "unescape", "absolute", "parse_path", "build_path"),
                methodTypes = linkedMapOf(
                    "parse" to anyFunction(
                        TableType(
                            fields = linkedMapOf(
                                "scheme" to PrimitiveType.STRING,
                                "host" to PrimitiveType.STRING,
                                "path" to PrimitiveType.STRING,
                                "port" to PrimitiveType.STRING,
                                "query" to PrimitiveType.STRING
                            )
                        )
                    ),
                    "build" to anyFunction(PrimitiveType.STRING),
                    "escape" to anyFunction(PrimitiveType.STRING),
                    "unescape" to anyFunction(PrimitiveType.STRING),
                    "absolute" to anyFunction(PrimitiveType.STRING)
                )
            ),
            callableAndroidLuaResource("modules/su.lua", "su", returnType = PrimitiveType.STRING),
            androidLuaModule("modules/test.lua", "test"),
            androidLuaModule("modules/xml.lua", "xml", methods = listOf("new", "tag", "append", "str", "save", "find"))
        )
        val assetHelpers = linkedMapOf<String, RawProviderModuleResource>(
            androidLuaModule(
                "helpers/AndLua.lua",
                "AndLua",
                methods = listOf(
                    "MD\u63d0\u793a", "\u7a97\u53e3\u6807\u9898", "\u8f7d\u5165\u754c\u9762", "\u63d0\u793a",
                    "\u968f\u673a\u6570", "\u5199\u5165\u6587\u4ef6", "\u6587\u4ef6\u662f\u5426\u5b58\u5728",
                    "setTitle", "setContentView", "toast", "roundCorner", "getDeviceId", "getScreenWidth",
                    "getScreenHeight", "isAppInstalled", "fileExists", "createFile", "createDirectory", "writeFile",
                    "shareText", "setClipboard", "getClipboard"
                )
            ),
            // Callable factory + MyBottomSheetDialog method; reserved simple name keeps this
            // ahead of android.app.Dialog (see ANDROLUA_RESERVED_SIMPLE_MODULE_NAMES).
            androidLuaModule(
                "helpers/Dialog.lua",
                "Dialog",
                fields = linkedMapOf("__call" to anyFunction(CustomType("MyBottomSheetDialog"))),
                methods = listOf("MyBottomSheetDialog"),
                methodTypes = linkedMapOf(
                    "MyBottomSheetDialog" to anyFunction(CustomType("MyBottomSheetDialog"))
                )
            ),
            androidLuaModule("helpers/ThomeLua.lua", "ThomeLua", methods = listOf("byteEquals")),
            androidLuaModule(
                "helpers/file.lua",
                "file",
                methods = listOf("exists", "isDirectory", "isFile", "createFile", "createDirectory", "deleteFile", "getFileList", "loadbitmap", "getExtension", "attrdir"),
                methodTypes = linkedMapOf(
                    "exists" to anyFunction(PrimitiveType.BOOLEAN),
                    "isDirectory" to anyFunction(PrimitiveType.BOOLEAN),
                    "isFile" to anyFunction(PrimitiveType.BOOLEAN)
                )
            ),
            callableAndroidLuaResource("helpers/loadlayout2.lua", "loadlayout2"),
            callableAndroidLuaResource("helpers/loadlayout3.lua", "loadlayout3"),
            androidLuaModule("helpers/toast.lua", "toast", methods = listOf("print", "show")),
            androidLuaModule("helpers/xml2table.lua", "xml2table", methods = listOf("xml2table", "show", "editlayout")),
            callableAndroidLuaResource("helpers/bin.lua", "bin"),
            androidLuaModule("helpers/bmob.lua", "bmob", methods = listOf("sign", "login"))
        )
        return managedModules.withAndroidLuaAssetHelperFallbacks(assetHelpers)
    }

    private fun Map<String, RawProviderModuleResource>.withAndroidLuaAssetHelperFallbacks(
        assetHelpers: Map<String, RawProviderModuleResource>
    ): Map<String, RawProviderModuleResource> = linkedMapOf<String, RawProviderModuleResource>()
        .also { merged ->
            merged.putAll(this)
            assetHelpers.forEach { (moduleName, resource) ->
                if (moduleName in merged) {
                    require(moduleName in ANDROLUA_MANAGED_MODULE_PRECEDENCE_NAMES) {
                        "Android-Lua asset helper '$moduleName' duplicates a managed module without explicit precedence."
                    }
                } else {
                    merged[moduleName] = resource
                }
            }
        }

    private fun androluaImportSurface(luajavaModuleType: ModuleType): ModuleExportSurface {
        // Runtime import.lua returns env_import and installs helpers onto _G. Model both the
        // callable require("import") return shape and the helper members used by library stubs.
        val envImportType = androluaEnvImportFunctionType()
        val fields = linkedMapOf(
            "__call" to envImportType,
            "env_import" to envImportType,
            "luajava" to luajavaModuleType,
            "loadlayout" to ModuleType("loadlayout", fields = linkedMapOf("__call" to anyFunction(CustomType("AndroidView")))),
            "loadbitmap" to ModuleType("loadbitmap", fields = linkedMapOf("__call" to anyFunction(CustomType("Bitmap")))),
            "loadmenu" to ModuleType("loadmenu", fields = linkedMapOf("__call" to anyFunction(CustomType("AndroidMenu"))))
        )
        fields.putAll(luajavaModuleType.fields)

        val methods = methodMap("import", "compile", "enum", "each", "dump", "printstack", "getids", "thread", "task", "timer")
            .toMutableMap()
        // Keep env_import discoverable as a modeled callable on the import module surface as well
        // as via free-identifier globals after require "import".
        methods["env_import"] = envImportType
        methods.putAll(luajavaModuleType.methods)

        return moduleSurface(
            moduleName = "import",
            fields = fields,
            methods = methods
        )
    }

    /**
     * AndroLua runtime `env_import(env?)` installs import helpers and returns either the target
     * environment table or a lazily resolved JavaClass when called with a class name string.
     */
    private fun androluaEnvImportFunctionType(): FunctionType {
        val envOrClassName = unionTypeOf(PrimitiveType.TABLE, PrimitiveType.STRING)
        val returnType = unionTypeOf(
            PrimitiveType.TABLE,
            AppliedType(baseName = "JavaClass", typeArguments = listOf(PrimitiveType.ANY))
        )
        return FunctionType(
            parameters = listOf(
                FunctionParameter(
                    name = "env",
                    type = envOrClassName,
                    optional = true
                )
            ),
            returnType = returnType
        )
    }

    private fun androidLuaModule(
        relativePath: String,
        moduleName: String,
        fields: Map<String, Type> = emptyMap(),
        methods: List<String> = emptyList(),
        methodTypes: Map<String, Type> = emptyMap()
    ): Pair<String, RawProviderModuleResource> {
        val resolvedMethods = methodMap(*methods.toTypedArray()).toMutableMap()
        methodTypes.forEach { (name, type) -> resolvedMethods[name] = type }
        return moduleName to androidLuaResource(
            relativePath,
            surface = moduleSurface(
                moduleName = moduleName,
                fields = fields,
                methods = resolvedMethods
            )
        )
    }

    private fun callableAndroidLuaResource(
        relativePath: String,
        moduleName: String,
        returnType: Type = PrimitiveType.ANY,
        methods: List<String> = emptyList()
    ): Pair<String, RawProviderModuleResource> = moduleName to androidLuaResource(
        relativePath,
        surface = moduleSurface(
            moduleName = moduleName,
            fields = linkedMapOf("__call" to anyFunction(returnType)),
            methods = methodMap(*methods.toTypedArray())
        )
    )

    private fun androidLuaResource(
        relativePath: String,
        surface: ModuleExportSurface
    ): RawProviderModuleResource = RawProviderModuleResource(
        resourcePath = ANDROLUA_RESOURCE_ROOT + relativePath,
        fallbackSource = androidLuaFallbackSource(relativePath),
        syntheticSurface = surface
    )

    private fun androidFrameworkProviderModules(
        resources: AndroidFrameworkResources
    ): Map<VirtualPath, BuiltinOverlaySnapshot.ProviderModuleSnapshot> {
        val manifest = androidFrameworkManifest(resources)
        val classModels = linkedMapOf<String, AndroidFrameworkClassModel>()
        manifest.forEach { entry ->
            val indexedClassNames = androidFrameworkClassIndex(entry.classIndexResource)
                .also { classNames ->
                    require(classNames.isNotEmpty()) {
                        "Android framework package '${entry.packageName}' has an empty class index: ${entry.classIndexResource}."
                    }
                    if (entry.packageName != ANDROID_FRAMEWORK_SUPPORTING_PACKAGE) {
                        require(classNames.all { it.startsWith("${entry.packageName}.") }) {
                            "Android framework package '${entry.packageName}' has class-index entries outside its package."
                        }
                    }
                }
            val documentedClasses = androidFrameworkDocumentedClasses(entry.modelResource, indexedClassNames)
                .associateBy { it.binaryName }
            indexedClassNames.forEach { className ->
                classModels[className] = documentedClasses[className] ?: AndroidFrameworkClassModel(
                    binaryName = className,
                    constructors = emptyList(),
                    fields = emptyMap(),
                    staticMethods = emptyMap(),
                    methods = emptyMap(),
                    declaredSuperTypes = emptyList(),
                    typeParameters = emptyList()
                )
            }
        }
        require(classModels.isNotEmpty()) {
            "Android framework manifest '${resources.manifestResource}' did not provide any class models."
        }

        val providerModuleNames = androidFrameworkClassProviderModuleNames(classModels.keys)
        val classTypes = androidFrameworkClassTypes(classModels)
        val providers = linkedMapOf<VirtualPath, BuiltinOverlaySnapshot.ProviderModuleSnapshot>()
        classModels.values.sortedBy { it.binaryName }.forEach { classModel ->
            val moduleType = androidFrameworkClassModuleType(
                classModel = classModel,
                classModels = classModels,
                providerModuleNames = providerModuleNames,
                classTypes = classTypes,
                moduleName = androidFrameworkSimpleAlias(classModel.binaryName)
            )
            val path = androidFrameworkClassProviderPath(classModel.binaryName)
            val providerFile = androidFrameworkProviderFile(
                source = "-- Android framework resource class provider for ${classModel.binaryName}\n",
                surface = ModuleExportSurface(
                    moduleType = moduleType,
                    sourceForm = ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL,
                    members = androidFrameworkClassModuleMembers(moduleType)
                ),
                providedModuleNames = androidFrameworkProviderNames(classModel.binaryName)
            )
            val primaryModuleName = providerModuleNames.getValue(classModel.binaryName)
            providers[path] = BuiltinOverlaySnapshot.ProviderModuleSnapshot(
                moduleName = primaryModuleName,
                file = providerFile
            )
            androidFrameworkProviderNames(classModel.binaryName)
                .filterNot { alias -> alias == primaryModuleName }
                .filterNot { alias ->
                    alias == androidFrameworkSimpleAlias(classModel.binaryName) && primaryModuleName != alias
                }
                .forEach { alias ->
                    providers[androidFrameworkClassProviderAliasPath(classModel.binaryName, alias)] =
                        BuiltinOverlaySnapshot.ProviderModuleSnapshot(
                            moduleName = alias,
                            file = providerFile
                        )
                }
        }
        androidFrameworkPackageNames(classModels.keys).forEach { packageName ->
            val moduleType = androidFrameworkPackageModuleType(packageName, classModels, providerModuleNames, classTypes)
            val path = androidFrameworkPackageProviderPath(packageName)
            providers[path] = BuiltinOverlaySnapshot.ProviderModuleSnapshot(
                moduleName = packageName,
                file = androidFrameworkProviderFile(
                    source = "-- Android framework resource package provider for $packageName\n",
                    surface = ModuleExportSurface(
                        moduleType = moduleType,
                        sourceForm = ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL,
                        members = moduleMembers(moduleType)
                    ),
                    providedModuleNames = linkedSetOf(packageName)
                )
            )
        }
        return providers
    }

    private fun androidFrameworkManifest(resources: AndroidFrameworkResources): List<AndroidFrameworkManifestEntry> {
        val entries = readRequiredText(resources.manifestResource)
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
            .mapIndexed { index, line ->
                val parts = line.split('|').map(String::trim)
                require(parts.size == 3 && parts.none(String::isEmpty)) {
                    "Invalid Android framework manifest row ${index + 1}: '$line'."
                }
                AndroidFrameworkManifestEntry(
                    packageName = parts[0],
                    classIndexResource = resources.resolve(parts[1]),
                    modelResource = resources.resolve(parts[2])
                )
            }
            .toList()
        require(entries.isNotEmpty()) {
            "Android framework manifest '${resources.manifestResource}' is empty."
        }
        return entries
    }

    private fun androidFrameworkClassIndex(classIndexResource: String): Set<String> {
        return readRequiredText(classIndexResource)
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
            .map { className -> normalizeAndroidFrameworkIndexedClassName(className) }
            .onEach { className ->
                require(className.isNotBlank() && '.' in className) {
                    "Invalid Android framework class name '$className' in $classIndexResource."
                }
            }
            .toCollection(linkedSetOf())
    }

    private fun androidFrameworkDocumentedClasses(
        modelResource: String,
        indexedClassNames: Set<String>
    ): List<AndroidFrameworkClassModel> {
        val models = mutableListOf<AndroidFrameworkClassModel>()
        val modelText = readRequiredText(modelResource)
        val fieldsByClass = linkedMapOf<String, MutableMap<String, Type>>()
        val staticMethodsByClass = linkedMapOf<String, MutableMap<String, Type>>()
        val methodsByClass = linkedMapOf<String, MutableMap<String, Type>>()
        val constructorsByClass = linkedMapOf<String, MutableList<FunctionType>>()
        val classDeclarations = linkedMapOf<String, AndroidFrameworkClassDeclaration>()
        val pendingDocLines = mutableListOf<String>()
        var activeClassName: String? = null

        modelText.lineSequence().forEach { line ->
            val trimmedStart = line.trimStart()
            if (trimmedStart.startsWith("---")) {
                val tag = trimmedStart.removePrefix("---").trimStart()
                pendingDocLines += trimmedStart
                if (tag.startsWith("@class ")) {
                    val declaration = androidFrameworkClassDeclaration(
                        classText = tag.removePrefix("@class").trim(),
                        indexedClassNames = indexedClassNames
                    )
                    val className = declaration.binaryName
                    require(className in indexedClassNames) {
                        "Android framework model '$modelResource' documents class '$className' outside its manifest index."
                    }
                    classDeclarations[className] = declaration
                    activeClassName = className
                    fieldsByClass.getOrPut(className) { linkedMapOf() }
                    staticMethodsByClass.getOrPut(className) { linkedMapOf() }
                    methodsByClass.getOrPut(className) { linkedMapOf() }
                    constructorsByClass.getOrPut(className) { mutableListOf() }
                } else if (tag.startsWith("@field ")) {
                    val className = activeClassName
                    val field = parseDocumentedField(tag.removePrefix("@field").trim())
                    if (className != null && field != null) {
                        fieldsByClass.getOrPut(className) { linkedMapOf() }[field.name] = field.type
                    }
                }
                return@forEach
            }

            val functionMatch = ANDROID_FRAMEWORK_FUNCTION_REGEX.find(line)
            if (functionMatch != null) {
                val localName = functionMatch.groupValues[1]
                val separator = functionMatch.groupValues[2]
                val memberName = functionMatch.groupValues[3]
                val parameterList = functionMatch.groupValues[4]
                val className = activeClassName ?: androidFrameworkClassNameForLocal(localName)
                if (className != null) {
                    val doc = parseMemberDoc(pendingDocLines)
                    val type = documentedFunctionType(parameterList, doc)
                    if (separator == ":" && memberName == "new") {
                        constructorsByClass.getOrPut(className) { mutableListOf() }
                            .addAll(functionSignatures(type))
                    } else {
                        val target = if (separator == ":") {
                            methodsByClass.getOrPut(className) { linkedMapOf() }
                        } else {
                            staticMethodsByClass.getOrPut(className) { linkedMapOf() }
                        }
                        target.mergeDocumentedFunction(memberName, type)
                    }
                }
                pendingDocLines.clear()
                return@forEach
            }

            if (line.isNotBlank() && !trimmedStart.startsWith("--")) {
                pendingDocLines.clear()
            }
        }

        (fieldsByClass.keys + staticMethodsByClass.keys + methodsByClass.keys + constructorsByClass.keys + classDeclarations.keys)
            .sorted()
            .forEach { className ->
                val declaration = classDeclarations[className]
                models += AndroidFrameworkClassModel(
                    binaryName = className,
                    constructors = constructorsByClass[className].orEmpty(),
                    fields = sortedTypeMap(fieldsByClass[className].orEmpty()),
                    staticMethods = sortedTypeMap(staticMethodsByClass[className].orEmpty()),
                    methods = sortedTypeMap(methodsByClass[className].orEmpty()),
                    declaredSuperTypes = declaration?.declaredSuperTypes.orEmpty(),
                    typeParameters = declaration?.typeParameters.orEmpty()
                )
            }
        return models
    }

    private fun androidFrameworkClassNameForLocal(localName: String): String? {
        return localName
            .takeIf { it.isNotBlank() }
            ?.replace(Regex("([a-z])([A-Z])"), "$1.$2")
            ?.let(::normalizeAndroidFrameworkIndexedClassName)
    }

    private fun androidFrameworkClassModuleType(
        classModel: AndroidFrameworkClassModel,
        classModels: Map<String, AndroidFrameworkClassModel>,
        providerModuleNames: Map<String, String>,
        classTypes: Map<String, JavaClassType>,
        moduleName: String = providerModuleNames.getValue(classModel.binaryName)
    ): ModuleType {
        val classType = classTypes.getValue(classModel.binaryName)
        val fields = linkedMapOf<String, Type>("__class" to JavaInstanceType(classType))
        if (classModel.constructors.isNotEmpty()) {
            fields["__call"] = classType
        }
        return ModuleType(
            moduleName = moduleName,
            fields = fields.also { fields ->
                fields.putAll(classModel.fields)
                androidFrameworkImmediateInnerClassNames(classModel.binaryName, classModels.keys).forEach { innerClass ->
                    fields[androidFrameworkSimpleAlias(innerClass)] = androidFrameworkClassModuleType(
                        classModel = classModels.getValue(innerClass),
                        classModels = classModels,
                        providerModuleNames = providerModuleNames,
                        classTypes = classTypes
                    )
                }
            },
            methods = classModel.staticMethods
        )
    }

    private fun androidFrameworkClassTypes(classModels: Map<String, AndroidFrameworkClassModel>): Map<String, JavaClassType> {
        val cache = linkedMapOf<String, JavaClassType>()

        fun build(className: String, stack: MutableSet<String>): JavaClassType {
            cache[className]?.let { return it }
            val classModel = classModels.getValue(className)
            val javaName = androidFrameworkJavaTypeName(classModel.binaryName)
            if (!stack.add(className)) {
                return JavaClassType(javaName = javaName, typeParameters = classModel.typeParameters)
            }
            val resolvedSuperTypes = classModel.declaredSuperTypes.mapNotNull { superType ->
                resolveAndroidFrameworkClassName(superType, classModels.keys)
            }.filter { superType -> superType != className }
            val classType = JavaClassType(
                javaName = javaName,
                constructors = JavaOverloadSet(
                    classModel.constructors.map { signature ->
                        JavaConstructorType(owner = javaName, signature = signature)
                    }
                ),
                staticMembers = classModel.fields.mapValues { (name, type) ->
                    JavaStaticMemberType(
                        owner = javaName,
                        memberName = name,
                        valueType = type,
                        memberKind = JavaMemberKind.FIELD
                    )
                } + classModel.staticMethods.mapValues { (name, type) ->
                    JavaStaticMemberType(
                        owner = javaName,
                        memberName = name,
                        valueType = type,
                        memberKind = JavaMemberKind.METHOD
                    )
                },
                instanceMembers = classModel.methods.mapValues { (name, type) ->
                    JavaInstanceMemberType(
                        owner = javaName,
                        memberName = name,
                        valueType = type,
                        memberKind = JavaMemberKind.METHOD
                    )
                },
                innerClasses = androidFrameworkImmediateInnerClassNames(classModel.binaryName, classModels.keys)
                    .associate { innerClass -> androidFrameworkSimpleAlias(innerClass) to build(innerClass, stack) },
                superClass = resolvedSuperTypes.firstOrNull()?.let { build(it, stack) },
                interfaces = resolvedSuperTypes.drop(1).map { build(it, stack) },
                typeParameters = classModel.typeParameters
            )
            stack.remove(className)
            cache[className] = classType
            return classType
        }

        classModels.keys.sorted().forEach { className -> build(className, linkedSetOf()) }
        return cache
    }

    private fun androidFrameworkPackageModuleType(
        packageName: String,
        classModels: Map<String, AndroidFrameworkClassModel>,
        providerModuleNames: Map<String, String>,
        classTypes: Map<String, JavaClassType>
    ): ModuleType {
        val fields = classModels.keys
            .asSequence()
            .filter { className -> androidFrameworkPackageName(className) == packageName }
            .filter { className -> '$' !in className.substringAfterLast('.') }
            .sortedBy(::androidFrameworkSimpleAlias)
            .associateTo(linkedMapOf()) { className ->
                androidFrameworkSimpleAlias(className) to androidFrameworkClassModuleType(
                    classModel = classModels.getValue(className),
                    classModels = classModels,
                    providerModuleNames = providerModuleNames,
                    classTypes = classTypes
                )
            }
        return ModuleType(
            moduleName = packageName,
            fields = fields,
            indexSignature = ModuleType.IndexSignature(PrimitiveType.STRING, UnknownType)
        )
    }

    private fun androidFrameworkPackageNames(classNames: Set<String>): Set<String> {
        return classNames
            .mapTo(linkedSetOf()) { className -> androidFrameworkPackageName(className) }
            .filterTo(linkedSetOf(), String::isNotBlank)
    }

    private fun androidFrameworkPackageName(className: String): String {
        val topLevel = className.substringBefore('$')
        return topLevel.substringBeforeLast('.', "")
    }

    private fun androidFrameworkSimpleAlias(className: String): String {
        return className.substringAfterLast('.').substringAfterLast('$')
    }

    private fun androidFrameworkImmediateInnerClassNames(
        className: String,
        allClassNames: Set<String>
    ): List<String> {
        val prefix = "$className$"
        return allClassNames
            .filter { candidate -> candidate.startsWith(prefix) && '$' !in candidate.removePrefix(prefix) }
            .sortedBy(::androidFrameworkSimpleAlias)
    }

    private fun androidFrameworkJavaTypeName(className: String): JavaTypeName {
        val packageName = androidFrameworkPackageName(className)
        val simpleNames = className
            .removePrefix(packageName.takeIf(String::isNotEmpty)?.let { "$it." } ?: "")
            .split('$')
            .filter(String::isNotBlank)
        return JavaTypeName(
            packageName = packageName,
            simpleNames = simpleNames,
            binaryName = className,
            canonicalName = className.replace('$', '.')
        )
    }

    private fun androidFrameworkClassModuleMembers(moduleType: ModuleType): List<ModuleExportSurface.MemberExport> {
        return moduleMembers(moduleType) + (moduleType.fields["__class"] as? JavaInstanceType)
            ?.allInstanceMembers()
            .orEmpty()
            .map { (name, member) ->
                ModuleExportSurface.MemberExport(
                    name = name,
                    exportPath = listOf("__class", name),
                    kind = SymbolKind.METHOD,
                    type = member.valueType,
                    range = null
                )
            }
    }

    private fun moduleMembers(moduleType: ModuleType): List<ModuleExportSurface.MemberExport> {
        return buildList {
            moduleType.fields.forEach { (name, type) ->
                add(ModuleExportSurface.MemberExport(name, listOf(name), SymbolKind.FIELD, type, null))
            }
            moduleType.methods.forEach { (name, type) ->
                add(ModuleExportSurface.MemberExport(name, listOf(name), SymbolKind.METHOD, type, null))
            }
        }.sortedWith(compareBy<ModuleExportSurface.MemberExport>({ it.exportPath.size }, { it.name }))
    }

    private fun androidFrameworkProviderFile(
        source: String,
        surface: ModuleExportSurface,
        providedModuleNames: Set<String>
    ): WorkspaceSnapshot.FileSnapshot {
        val normalizedProvidedModuleNames = providedModuleNames.toList().sorted().toCollection(linkedSetOf())
        val surfaceFingerprint = WorkspacePublicFingerprint.from(null, surface).value
        val publicFingerprint = WorkspacePublicFingerprint(
            providedModuleNames = normalizedProvidedModuleNames,
            value = workspaceFingerprintHash(
                "providers=${normalizedProvidedModuleNames.joinToString("|")}\nsurface=$surfaceFingerprint"
            )
        )
        return WorkspaceSnapshot.FileSnapshot(
            cacheKey = workspaceFingerprintHash(source),
            moduleExportSurface = surface,
            publicFingerprint = publicFingerprint
        )
    }

    private fun androidFrameworkProviderNames(className: String): Set<String> {
        // Do not advertise reserved Android-Lua simple module names (Dialog/file/toast/...) as
        // framework aliases; those names belong to helpers/managed modules for require().
        val simple = androidFrameworkSimpleAlias(className)
        return linkedSetOf(
            simple.takeUnless { it in ANDROLUA_RESERVED_SIMPLE_MODULE_NAMES },
            className,
            className.replace('$', '.'),
            className.substringAfterLast('_').takeUnless { it in ANDROLUA_RESERVED_SIMPLE_MODULE_NAMES }
        ).filterNotNullTo(linkedSetOf()).filterTo(linkedSetOf(), String::isNotBlank)
    }

    private fun androidFrameworkClassProviderModuleNames(classNames: Set<String>): Map<String, String> {
        val aliasCounts = classNames.groupingBy(::androidFrameworkSimpleAlias).eachCount()
        return classNames.associateWithTo(linkedMapOf()) { className ->
            val alias = androidFrameworkSimpleAlias(className)
            // Keep Android-Lua helper/managed module names (e.g. require("Dialog")) free:
            // android.app.Dialog otherwise claims the simple alias "Dialog" via the same
            // STANDARD_LIBRARY_OVERLAY rank and a lexicographically earlier __jvm__ path.
            if (aliasCounts.getValue(alias) == 1 && alias !in ANDROLUA_RESERVED_SIMPLE_MODULE_NAMES) {
                alias
            } else {
                className.replace('$', '.')
            }
        }
    }

    private fun androidFrameworkClassProviderPath(className: String): VirtualPath =
        VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")

    private fun androidFrameworkClassProviderAliasPath(className: String, alias: String): VirtualPath =
        VirtualPath.of(
            "__jvm__/class-aliases/${className.replace('.', '/').replace('$', '/')}/${androidFrameworkAliasPathSegment(alias)}.lua"
        )

    private fun androidFrameworkPackageProviderPath(packageName: String): VirtualPath =
        VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua")

    private fun androidFrameworkAliasPathSegment(alias: String): String = alias
        .replace("\$", "_dollar_")
        .replace('.', '/')

    private fun normalizeAndroidFrameworkIndexedClassName(className: String): String {
        val normalized = className.trim()
        if (normalized.indexOf('_') == -1) {
            return normalized
        }
        val packageName = normalized.substringBeforeLast('.', "")
        val classPart = normalized.removePrefix(packageName.takeIf(String::isNotEmpty)?.let { "$it." } ?: "")
        return packageName.takeIf(String::isNotEmpty)
            ?.let { "$it.${classPart.replace('_', '$')}" }
            ?: classPart.replace('_', '$')
    }

    private fun normalizeAndroidFrameworkDocumentedClassName(
        className: String,
        indexedClassNames: Set<String>
    ): String {
        val normalized = normalizeAndroidFrameworkIndexedClassName(className)
        if (normalized in indexedClassNames) {
            return normalized
        }
        return candidateAndroidFrameworkBinaryClassNames(normalized)
            .firstOrNull { it in indexedClassNames }
            ?: normalized
    }

    private fun resolveAndroidFrameworkClassName(className: String, allClassNames: Set<String>): String? {
        val normalized = normalizeAndroidFrameworkIndexedClassName(className)
        if (normalized in allClassNames) {
            return normalized
        }
        return candidateAndroidFrameworkBinaryClassNames(normalized).firstOrNull { it in allClassNames }
    }

    private fun candidateAndroidFrameworkBinaryClassNames(className: String): List<String> {
        val separatorIndexes = className.indices.filter { className[it] == '.' }
        return buildSet {
            add(className)
            if (separatorIndexes.isNotEmpty()) {
                val combinations = 1 shl separatorIndexes.size
                for (mask in 1 until combinations) {
                    val chars = className.toCharArray()
                    separatorIndexes.forEachIndexed { index, separator ->
                        if ((mask and (1 shl index)) != 0) {
                            chars[separator] = '$'
                        }
                    }
                    add(String(chars))
                }
            }
        }.toList()
    }

    private fun sortedTypeMap(types: Map<String, Type>): Map<String, Type> {
        return types.entries
            .sortedBy { it.key }
            .associateTo(linkedMapOf()) { it.key to it.value }
    }

    private fun readRequiredText(resourcePath: String): String {
        return runCatching { BuiltinOverlayResourceAccess.readText(resourcePath) }
            .getOrElse { error("Missing required Android framework overlay resource '$resourcePath'.") }
    }

    private fun moduleSurface(
        moduleName: String,
        fields: Map<String, Type> = emptyMap(),
        methods: Map<String, Type> = emptyMap()
    ): ModuleExportSurface {
        val moduleType = ModuleType(moduleName = moduleName, fields = fields, methods = methods)
        return ModuleExportSurface(
            moduleType = moduleType,
            sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER,
            members = buildList {
                fields.forEach { (name, type) ->
                    add(ModuleExportSurface.MemberExport(name, listOf(name), SymbolKind.FIELD, type, null))
                }
                methods.forEach { (name, type) ->
                    add(ModuleExportSurface.MemberExport(name, listOf(name), SymbolKind.METHOD, type, null))
                }
            }
        )
    }

    private fun methodMap(vararg names: String): Map<String, Type> = names.associateWithTo(linkedMapOf()) { anyFunction() }

    private fun anyFunction(returnType: Type = PrimitiveType.ANY): FunctionType = FunctionType(
        parameters = listOf(FunctionParameter(name = "...", type = VarargType(PrimitiveType.ANY), vararg = true)),
        returnType = returnType
    )

    private fun tableAny(): TableType = TableType(
        indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.ANY)
    )

    private fun androidLuaFallbackSource(relativePath: String): String = """
        -- Android-Lua model fallback for $relativePath.
        local M = {}
        return M
    """.trimIndent()

    private fun lua54Catalog() = Catalog(
        normalizedVersion = LuaVersion.LUA_5_4,
        versionSegment = "5.4",
        globalsResourcePath = "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/global.lua",
        providerModuleResourcePaths = linkedMapOf(
            "coroutine" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/coroutine.lua",
            "debug" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/debug.lua",
            "io" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/io.lua",
            "math" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/math.lua",
            "os" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/os.lua",
            "package" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/package.lua",
            "string" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/string.lua",
            "table" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/table.lua",
            "utf8" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua54/utf8.lua"
        ),
        compatibilityGlobalsSource = """
            warn = warn
            function package.seeall(module) end
        """.trimIndent(),
        globalNames = linkedSetOf(
            "_G", "_VERSION", "assert", "collectgarbage", "coroutine", "debug", "dofile", "error",
            "getmetatable", "io", "ipairs", "load", "loadfile", "math", "next", "os", "package",
            "pairs", "pcall", "print", "rawequal", "rawget", "rawlen", "rawset", "require", "select",
            "setmetatable", "string", "table", "tonumber", "tostring", "type", "utf8", "warn", "xpcall"
        ),
        moduleFieldNames = linkedMapOf(
            "coroutine" to linkedSetOf("create"),
            "debug" to linkedSetOf("traceback"),
            "io" to linkedSetOf("open"),
            "math" to linkedSetOf("abs"),
            "os" to linkedSetOf("clock"),
            "package" to linkedSetOf("loaded", "searchpath", "seeall"),
            "string" to linkedSetOf("format"),
            "table" to linkedSetOf("insert"),
            "utf8" to linkedSetOf("len")
        )
    )

    private fun lua53ProviderModuleResourcePaths() = linkedMapOf(
        "coroutine" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/coroutine.lua",
        "debug" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/debug.lua",
        "io" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/io.lua",
        "math" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/math.lua",
        "os" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/os.lua",
        "package" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/package.lua",
        "string" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/string.lua",
        "table" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/table.lua",
        "utf8" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/utf8.lua"
    )

    private data class Catalog(
        val normalizedVersion: LuaVersion,
        val versionSegment: String,
        val globalsResourcePath: String? = null,
        val providerModuleResourcePaths: Map<String, String>,
        val rawProviderModuleResources: Map<String, RawProviderModuleResource> = emptyMap(),
        val androidFrameworkResources: AndroidFrameworkResources? = null,
        val compatibilityGlobalsSource: String,
        val globalNames: Set<String>,
        val moduleFieldNames: Map<String, Set<String>>
    )

    private data class RawProviderModuleResource(
        val resourcePath: String,
        val fallbackSource: String,
        val syntheticSurface: ModuleExportSurface? = null
    )

    private data class AndroidFrameworkResources(
        val rootResource: String,
        val manifestResource: String
    ) {
        fun resolve(relativePath: String): String = rootResource + relativePath
    }

    private data class AndroidFrameworkManifestEntry(
        val packageName: String,
        val classIndexResource: String,
        val modelResource: String
    )

    private data class AndroidFrameworkClassModel(
        val binaryName: String,
        val constructors: List<FunctionType>,
        val fields: Map<String, Type>,
        val staticMethods: Map<String, Type>,
        val methods: Map<String, Type>,
        val declaredSuperTypes: List<String>,
        val typeParameters: List<TypeParameterType>
    )

    private data class AndroidFrameworkClassDeclaration(
        val binaryName: String,
        val declaredSuperTypes: List<String>,
        val typeParameters: List<TypeParameterType>
    )

    private const val ANDROLUA_RESOURCE_ROOT =
        "/io/github/dingyi222666/luaparser/semantic/workspace/androidlua/androlua5.3/"

    private const val ANDROLUA_LUAJAVA_RESOURCE =
        "/io/github/dingyi222666/luaparser/semantic/workspace/std/androlua53-luajava/luajava.lua"
    private const val ANDROLUA_GLOBALS_RESOURCE =
        "/io/github/dingyi222666/luaparser/semantic/workspace/std/androlua53-luajava/_G.lua"
    private const val ANDROID_FRAMEWORK_RESOURCE_ROOT =
        "/io/github/dingyi222666/luaparser/semantic/workspace/android-framework/"
    private const val ANDROID_FRAMEWORK_MANIFEST_RESOURCE =
        "/io/github/dingyi222666/luaparser/semantic/workspace/android-framework/manifest.index"
    private const val ANDROID_FRAMEWORK_SUPPORTING_PACKAGE = "android.supporting"
    private val ANDROID_FRAMEWORK_UNMODELED_SUPER_TYPES = setOf("JavaObject", "any")
    private val ANDROLUA_MANAGED_MODULE_PRECEDENCE_NAMES = setOf("bin", "bmob")
    /**
     * Simple module names owned by Android-Lua overlay helpers/managed modules.
     * Framework class providers must not claim these simple aliases, or require("Dialog")
     * resolves to android.app.Dialog instead of helpers/Dialog.lua.
     */
    private val ANDROLUA_RESERVED_SIMPLE_MODULE_NAMES = setOf(
        "AndLua",
        "Dialog",
        "ThomeLua",
        "autotheme",
        "base64",
        "bin",
        "bmob",
        "check",
        "console",
        "file",
        "ftp",
        "hex",
        "http",
        "import",
        "json",
        "loadbitmap",
        "loadlayout",
        "loadlayout2",
        "loadlayout3",
        "loadmenu",
        "logcat",
        "ltn12",
        "luajava",
        "mbox",
        "mime",
        "options",
        "permission",
        "smtp",
        "socket",
        "su",
        "test",
        "toast",
        "xml",
        "xml2table"
    )
    private val DOCUMENTED_CALLABLE_GLOBAL_VALUE_NAMES = setOf("ipairs", "pairs")
    private val LUJAVA_SURFACE_FIELD_NAMES = setOf("loaded", "imported", "ids", "luadir")
    private val LUJAVA_SURFACE_METHOD_NAMES = setOf(
        "bindClass", "new", "newInstance", "loadLib", "createProxy", "newArray", "createArray",
        "astable", "tostring", "instanceof", "getContext", "override"
    )
    private val DOCUMENTED_INTEGER_TYPE = PrimitiveType("integer", PrimitiveType.Kind.NUMBER)
    private val ANDROID_FRAMEWORK_FUNCTION_REGEX =
        Regex("""^\s*function\s+([A-Za-z_][A-Za-z0-9_]*)([.:])([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""")
}
