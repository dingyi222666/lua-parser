package io.github.dingyi222666.luaparser.semantic.workspace.std

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.LegacyModuleEnvironmentPass
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportCollector
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSemanticFile
import io.github.dingyi222666.luaparser.semantic.workspace.workspaceFingerprintHash
import io.github.dingyi222666.luaparser.util.putIfAbsentCompat

object BuiltinOverlayLoader {
    // These synthetic providers use no caller-specific parser state. Lazy also
    // prevents the foreground and background engines from constructing them twice.
    private val frameworkProviders by lazy {
        androidFrameworkProviderModules(catalogFor(LuaVersion.ANDROLUA_5_3).androidFrameworkResources!!)
    }
    fun load(
        version: LuaVersion,
        analyze: (VirtualPath, String) -> WorkspaceSnapshot.FileSnapshot
    ): BuiltinOverlaySnapshot = loadInternal(version, true, analyze)

    /** Lua/AndroLua builtins for live buffers while the framework index builds. */
    fun loadCore(
        version: LuaVersion,
        analyze: (VirtualPath, String) -> WorkspaceSnapshot.FileSnapshot
    ): BuiltinOverlaySnapshot = loadInternal(version, false, analyze)

    // Compiled resource declarations have no workspace imports or classpath. Their complete
    // parse/facts/binding/export revision belongs to the resource catalog, shared by engines.
    private val coreRevisions = LuaVersion.entries.associateWith { version ->
        lazy { loadCore(version) { path, source -> declarationSnapshot(version, path, source) } }
    }
    private val completeRevisions = LuaVersion.entries.associateWith { version ->
        lazy {
            val core = coreRevisions.getValue(version).value
            if (version == LuaVersion.ANDROLUA_5_3) {
                core.copy(providerModules = core.providerModules + frameworkProviders)
            } else core
        }
    }

    internal fun compiledRevision(version: LuaVersion, includeFramework: Boolean): BuiltinOverlaySnapshot =
        (if (includeFramework) completeRevisions else coreRevisions).getValue(version).value

    private fun loadInternal(
        version: LuaVersion,
        includeAndroidFramework: Boolean,
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
                        analyzedSurface = snapshot.moduleExportSurface,
                        preparedBinder = snapshot.semanticFile?.snapshot?.binder
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
                        syntheticSurface = resource.surface?.value,
                        preparedBinder = snapshot.semanticFile?.snapshot?.binder
                    )
                )
            }
            providerModules[path] = BuiltinOverlaySnapshot.ProviderModuleSnapshot(
                moduleName = moduleName,
                file = file
            )
        }
        catalog.androidFrameworkResources?.takeIf { includeAndroidFramework }?.let {
            frameworkProviders.forEach { (path, provider) ->
                providerModules[path] = provider
            }
        }
        val globalsPath = overlayGlobalsPath(catalog.versionSegment)
        val globalsSource = globalsSource(catalog)
        val providerModuleTypes = documentedGlobalProviderModuleTypes(providerModules)
        val globalsFile = analyze(globalsPath, globalsSource).let { snapshot ->
            snapshot.copy(
                moduleExportSurface = documentedGlobalsSurface(
                    resourceText = globalsSource,
                    analyzedSurface = snapshot.moduleExportSurface,
                    providerModuleTypes = providerModuleTypes.filterKeys { it in catalog.globalNames },
                    preparedBinder = snapshot.semanticFile?.snapshot?.binder
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

    /**
     * Standalone globals are derived purely from compiled-in overlay resources, but they sit in
     * default-argument position on [SemanticWorkspaceContext] and [BinderPass], so a naive
     * implementation rebuilt the whole catalog on every context/evaluator construction.
     */

    private fun declarationSnapshot(version: LuaVersion, path: VirtualPath, source: String): WorkspaceSnapshot.FileSnapshot {
        val declaration = if (version == LuaVersion.ANDROLUA_5_3) declarationBinding(source)
            else bindDeclaration(source, version)
        val parsed = declaration.parsed
        val semantic = declaration.semantic
        val facts = DocumentFactsCollector.collect(path, parsed.chunk)
        val environment = LegacyModuleEnvironmentPass.analyze(path, facts)
        val surface = ModuleExportCollector.collect(parsed.chunk, facts, environment)
        return WorkspaceSnapshot.FileSnapshot(
            cacheKey = workspaceFingerprintHash(source), documentFacts = facts,
            legacyModuleEnvironment = environment, moduleExportSurface = surface,
            publicFingerprint = WorkspacePublicFingerprint.from(facts, surface),
            semanticFile = WorkspaceSemanticFile(path, source, parsed.chunk, semantic.model,
                semantic, parsed.recoveryDiagnostics)
        )
    }

    fun standaloneGlobals(version: LuaVersion = LuaVersion.LUA_5_3): BuiltinOverlaySnapshot.GlobalsSnapshot {
        return compiledRevision(version, includeFramework = false).globals
    }

    private fun documentedModuleSource(moduleName: String, resourceText: String): String {
        val source = resourceText.trim()
        // MULTILINE as a RegexOption (not an inline (?m) group): inline flags are
        // java.util.regex syntax and throw on the JS target.
        if (Regex("""^\s*return\s+${Regex.escape(moduleName)}\s*$""", RegexOption.MULTILINE).containsMatchIn(source)) {
            return source
        }
        return "$source\n\nreturn $moduleName"
    }

    private fun rawModuleSource(resource: RawProviderModuleResource): String {
        return BuiltinOverlayResourceAccess.readText(resource.resourcePath).trim()
    }

    private fun mergeRawModuleSurface(
        moduleName: String,
        resourceText: String,
        analyzedSurface: ModuleExportSurface?,
        syntheticSurface: ModuleExportSurface?,
        preparedBinder: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult? = null
    ): ModuleExportSurface {
        val documentedSurface = documentedModuleSurface(moduleName, resourceText, analyzedSurface, preparedBinder)
        return when (syntheticSurface) {
            null -> documentedSurface
            else -> mergeSyntheticSurface(
                documentedSurface = documentedSurface,
                syntheticSurface = syntheticSurface,
                documentedMemberPaths = documentedMemberPaths(moduleName, resourceText)
            )
        }
    }

    private fun globalsSource(catalog: Catalog): String = buildList {
        catalog.inheritedGlobalsResourcePath?.let { add(BuiltinOverlayResourceAccess.readText(it).trim()) }
        add(BuiltinOverlayResourceAccess.readText(catalog.globalsResourcePath).trim())
        catalog.compatibilityGlobalsSource.trim().takeIf(String::isNotEmpty)?.let(::add)
    }.joinToString("\n\n")

    private fun documentedGlobalsSurface(
        resourceText: String,
        analyzedSurface: ModuleExportSurface?,
        providerModuleTypes: Map<String, ModuleType?>,
        preparedBinder: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult? = null
    ): ModuleExportSurface {
        return surfaceFromMembers(
            moduleName = "_G",
            members = documentedGlobalMembers(resourceText, analyzedSurface, providerModuleTypes, preparedBinder),
            analyzedSurface = analyzedSurface
        )
    }

    private fun documentedGlobalProviderModuleTypes(
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
        analyzedSurface: ModuleExportSurface?,
        preparedBinder: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult? = null
    ): ModuleExportSurface {
        return surfaceFromMembers(
            moduleName = moduleName,
            members = documentedModuleMembers(moduleName, resourceText, analyzedSurface, preparedBinder),
            analyzedSurface = analyzedSurface
        )
    }

    private fun surfaceFromMembers(
        moduleName: String,
        members: List<ModuleExportSurface.MemberExport>,
        analyzedSurface: ModuleExportSurface?
    ): ModuleExportSurface {
        val (fields, methods) = topLevelFieldsAndMethods(members)
        return ModuleExportSurface(
            moduleType = ModuleType(moduleName = moduleName, fields = fields, methods = methods),
            sourceForm = ModuleExportSurface.SourceForm.RETURN_IDENTIFIER,
            hasSeeAllFallback = analyzedSurface?.hasSeeAllFallback ?: false,
            moduleEnvironmentMode = analyzedSurface?.moduleEnvironmentMode,
            members = members
        )
    }

    /**
     * Split a member list into the ModuleType fields/methods maps. Shared by
     * [surfaceFromMembers] and [mergeSyntheticSurface] (previously two identical loops);
     * only depth-1 members map onto the module surface, nested paths stay members-only.
     */
    private fun topLevelFieldsAndMethods(
        members: Iterable<ModuleExportSurface.MemberExport>
    ): Pair<LinkedHashMap<String, Type>, LinkedHashMap<String, Type>> {
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
        return fields to methods
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

        val (fields, methods) = topLevelFieldsAndMethods(membersByPath.values)

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

    // Full dotted module name plus its short local alias (socket.url + url); AndroLua docs
    // address dotted-module members through either form.
    private fun dottedModuleNameAliases(moduleName: String): LinkedHashSet<String> {
        val nameAliases = linkedSetOf(moduleName)
        if (moduleName.contains('.')) {
            nameAliases += moduleName.substringAfterLast('.')
        }
        return nameAliases
    }

    private fun documentedGlobalMembers(
        resourceText: String,
        analyzedSurface: ModuleExportSurface?,
        providerModuleTypes: Map<String, ModuleType?>,
        preparedBinder: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult?
    ): List<ModuleExportSurface.MemberExport> {
        val binder = preparedBinder ?: declarationBinding(resourceText).binder
        val analyzedByPath = analyzedSurface?.members.orEmpty().associateBy { it.exportPath }
        val membersByPath = linkedMapOf<List<String>, StandardMemberDescriptor>()
        val documentedClassTypes = documentedClassTypes(resourceText, binder)
        val evaluator = declarationEvaluator(binder)
        binder.declarationIndex.declarations.forEach { declaration ->
            if (declaration.origin != io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin.AST ||
                declaration.kind != io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind.GLOBAL) return@forEach
            val anchor = declaration.anchorNode as? Identifier ?: return@forEach
            val path = listOf(declaration.name)
            val type = declaration.declaredType ?: evaluator.evaluate(anchor)
            val kind = if (anchor.parent is io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration &&
                declaration.name !in DOCUMENTED_CALLABLE_GLOBAL_VALUE_NAMES) SymbolKind.FUNCTION else SymbolKind.FIELD
            membersByPath[path] = StandardMemberDescriptor(declaration.name, path, kind,
                hydrateDocumentedType(type, documentedClassTypes), analyzedByPath[path]?.range)
        }
        analyzedSurface?.members.orEmpty().forEach { member ->
            membersByPath.putIfAbsentCompat(member.exportPath, StandardMemberDescriptor(member.name,
                member.exportPath, member.kind, hydrateDocumentedType(member.type, documentedClassTypes), member.range))
        }

        providerModuleTypes.forEach { (name, module) ->
            if (module != null && name != "import") {
                val path = listOf(name)
                // A module registry entry and an installed free function may share a name.
                // Keep the declared function signature; replacing it with the require()
                // module table loses its parameter and return annotations at binding time.
                if (membersByPath[path]?.type !is CallableType) {
                    membersByPath[path] = StandardMemberDescriptor(name, path, SymbolKind.FIELD, module, null)
                }
            }
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
            helperMethods.putIfAbsentCompat("env_import", callType)
            helperMethods.putIfAbsentCompat("import", callType)
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
                helperMethods.putIfAbsentCompat(helperName, callType)
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
        val documentedLuajava = importModuleType.fields["luajava"] as? ModuleType ?: return
        val brandedLuajava = ModuleType(
            moduleName = "import",
            fields = documentedLuajava.fields,
            methods = documentedLuajava.methods,
            indexSignature = documentedLuajava.indexSignature,
            name = "luajava"
        )
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

    private fun declarationEvaluator(binder: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult) =
        ExpressionTypeEvaluator(binder, SemanticWorkspaceContext(overlayGlobals = BuiltinOverlaySnapshot.EMPTY.globals))

    private fun documentedModuleMembers(
        moduleName: String,
        resourceText: String,
        analyzedSurface: ModuleExportSurface?,
        preparedBinder: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult? = null
    ): List<ModuleExportSurface.MemberExport> {
        val aliases = dottedModuleNameAliases(moduleName)
        val binder = preparedBinder ?: declarationBinding(resourceText).binder
        val members = linkedMapOf<List<String>, ModuleExportSurface.MemberExport>()
        val ranges = analyzedSurface?.members.orEmpty().associateBy { it.exportPath }
        val classes = documentedClassTypes(resourceText, binder)
        aliases.forEach { alias ->
            val cls = classes[alias] ?: return@forEach
            cls.getAllFields().forEach { (name, type) ->
                members[listOf(name)] = ModuleExportSurface.MemberExport(name, listOf(name), SymbolKind.FIELD,
                    type, ranges[listOf(name)]?.range)
            }
            cls.getAllMethods().forEach { (name, type) ->
                members[listOf(name)] = ModuleExportSurface.MemberExport(name, listOf(name), SymbolKind.METHOD,
                    type, ranges[listOf(name)]?.range)
            }
        }
        val evaluator = declarationEvaluator(binder)
        binder.declarationIndex.declarations.forEach { declaration ->
            val anchor = declaration.anchorNode as? Identifier ?: return@forEach
            val member = anchor.parent as? MemberExpression ?: return@forEach
            val owner = expressionName(member.base) ?: return@forEach
            if (owner !in aliases) return@forEach
            val kind = when {
                member.indexer == ":" || moduleName.contains('.') -> SymbolKind.METHOD
                else -> SymbolKind.FIELD
            }
            val type = declaration.declaredType ?: evaluator.evaluate(member)
            val path = listOf(declaration.name)
            members[path] = ModuleExportSurface.MemberExport(declaration.name, path, kind,
                hydrateDocumentedType(type, classes), ranges[path]?.range)
        }
        analyzedSurface?.members.orEmpty().forEach { member ->
            members.putIfAbsentCompat(member.exportPath,
                member.copy(type = hydrateDocumentedType(member.type, classes)))
        }
        return members.values.sortedWith(compareBy({ symbolKindOrder(it.kind) }, { it.exportPath.joinToString(".") }))
    }

    private fun expressionName(expression: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode): String? =
        when (expression) {
            is Identifier -> expression.name
            is MemberExpression -> expressionName(expression.base)?.let { "$it.${expression.identifier.name}" }
            else -> null
        }

    private fun documentedMemberPaths(moduleName: String, resourceText: String): Set<List<String>> =
        documentedModuleMembers(moduleName, resourceText, null).mapTo(linkedSetOf()) { it.exportPath }

    private data class DeclarationBinding(
        val parsed: io.github.dingyi222666.luaparser.parser.LuaParseResult,
        val semantic: io.github.dingyi222666.luaparser.semantic.SemanticPipelineSnapshot
    ) {
        val chunk get() = parsed.chunk
        val binder get() = semantic.binder
        val classes by lazy { resolvedClassTypes(binder) }
    }

    private fun bindDeclaration(source: String, version: LuaVersion = LuaVersion.ANDROLUA_5_3): DeclarationBinding {
        val parsed = LuaParser(version).parseWorkspaceSnippetWithDiagnostics(source)
        val semantic = SemanticPipeline().analyzeSnapshot(parsed.chunk,
            SemanticWorkspaceContext(overlayGlobals = BuiltinOverlaySnapshot.EMPTY.globals),
            checkDiagnostics = false)
        return DeclarationBinding(parsed, semantic)
    }

    // Resource-owned AST/binding inputs are immutable and context-free. Signature extraction
    // and class/member hydration consume the same binding instead of attaching comments twice.
    private val resourceBindings by lazy {
        val paths = androidLuaDeclarationResources.map { ANDROLUA_RESOURCE_ROOT + it + ".lua" } +
            listOf(ANDROLUA_RESOURCE_ROOT + "import.lua", ANDROLUA_LUAJAVA_RESOURCE) +
            lua53ProviderModuleResourcePaths().values + lua54Catalog().providerModuleResourcePaths.values
        paths.distinct().associate { resource ->
            val source = BuiltinOverlayResourceAccess.readText(resource).trim()
            source to lazy { bindDeclaration(source) }
        }
    }

    private fun declarationBinding(source: String): DeclarationBinding =
        resourceBindings[source.trim()]?.value ?: bindDeclaration(source)

    private fun documentedClassTypes(
        resourceText: String,
        preparedBinder: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult? = null
    ): Map<String, ClassType> {
        if (!resourceText.contains("@class ")) return emptyMap()
        if (preparedBinder == null) return declarationBinding(resourceText).classes
        return resolvedClassTypes(preparedBinder)
    }

    private fun resolvedClassTypes(binder: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult): Map<String, ClassType> {
        val classes = binder.declarationIndex.declarations.asSequence()
            .filter { it.kind == io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind.CLASS }
            .mapNotNull { declaration ->
                (declaration.declaredType as? ClassType)?.let { declaration.name to it }
            }.toMap().toMutableMap()
        val evaluator = ExpressionTypeEvaluator(binder,
            SemanticWorkspaceContext(overlayGlobals = BuiltinOverlaySnapshot.EMPTY.globals))
        // Class declarations own annotations; their table values also own ordinary AST
        // methods (for example the standard file handle's file:read/file:write methods).
        // Use the same value derivation as user code to join these two real inputs.
        binder.declarationIndex.declarations.forEach { declaration ->
            val declared = declaration.declaredType as? ClassType ?: return@forEach
            if (declaration.kind.namespace != io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace.VALUE) return@forEach
            val anchor = declaration.anchorNode as? Identifier ?: return@forEach
            val value = evaluator.evaluate(anchor) as? ClassType ?: return@forEach
            classes[declared.name] = value
        }
        return classes
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
            // TypeResolver already canonicalized this union. Class surface substitution is
            // nominal: preserve the branches and do not recursively normalize the full
            // documented class graph again for every parameter and return type.
            is UnionType -> UnionType(type.types.mapTo(linkedSetOf()) { hydrateDocumentedType(it, classTypes) })
            is IntersectionType -> IntersectionType(type.types.map { hydrateDocumentedType(it, classTypes) }.toSet())
            else -> type
        }
    }

    private fun symbolKindOrder(kind: SymbolKind): Int = when (kind) {
        SymbolKind.FIELD -> 0
        SymbolKind.FUNCTION -> 1
        SymbolKind.METHOD -> 2
        else -> 3
    }

    private data class StandardMemberDescriptor(
        val name: String,
        val exportPath: List<String>,
        val kind: SymbolKind,
        val type: Type,
        val range: Range?
    )

    private fun overlayModulePath(versionSegment: String, moduleName: String): VirtualPath {
        // Keep dotted module names (socket.url) as a single path segment so providers stay
        // at __lua_std__/<ver>/socket.url.lua rather than a nested socket/url.lua tree.
        // Never rewrite '.' to '/': VirtualPath normalizes only separators, so dotted names
        // must remain one segment for require("socket.url") dotted-name resolution.
        val safeName = moduleName
            .replace('\\', '/')
            .trim('/')
            .replace('/', '.')
            .trim('.')
        return VirtualPath.of("__lua_std__/$versionSegment/$safeName.lua")
    }

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
            inheritedGlobalsResourcePath = lua53.globalsResourcePath,
            // LuaJ (AndroLua's host) keeps the 5.2-era bit32 API available under 5.3; real
            // projects call it (bit32.band(app.applicationInfo.flags, ...)), so the AndroLua
            // flavor mounts a full bit32 provider while stock Lua 5.3 keeps the compat global.
            providerModuleResourcePaths = lua53.providerModuleResourcePaths + (
                "bit32" to "/io/github/dingyi222666/luaparser/semantic/workspace/std/lua53/bit32.lua"
                ),
            rawProviderModuleResources = linkedMapOf(
                "luajava" to RawProviderModuleResource(
                    resourcePath = ANDROLUA_LUAJAVA_RESOURCE,
                    surface = lazy { luajavaSurface }
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
                "loadmenu",
                // loadlayout injects the parent view of the loaded layout as a global;
                // demo main.lua reads parent.getPageIds / parent.getIdsTable (FIXER-UNDEF).
                "parent"
            ),
            moduleFieldNames = linkedMapOf(
                *lua53.moduleFieldNames.entries.map { it.key to it.value }.toTypedArray(),
                "bit32" to linkedSetOf(
                    "arshift", "band", "bnot", "bor", "bswap", "btest", "bxor",
                    "extract", "lshift", "replace", "rshift", "tobit", "tohex"
                ),
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

    private fun androluaLuaJavaSurface(): ModuleExportSurface = luaJavaSurface

    private val luaJavaSurface by lazy {
        compileDeclarationSurface("luajava", ANDROLUA_LUAJAVA_RESOURCE)
    }

    // These are resource registrations, not a second table of hand-written members.
    private fun androlua53RawProviderModuleResources(luajavaModuleType: ModuleType): Map<String, RawProviderModuleResource> {
        val modules = linkedMapOf(
            "import" to androidLuaResource("import.lua", androluaImportSurface(luajavaModuleType))
        )
        androidLuaDeclarationResources.forEach { relative ->
            val name = if (relative.startsWith("modules/socket/")) relative.removePrefix("modules/").replace('/', '.')
                else relative.substringAfterLast('/')
            // Managed modules take precedence over the asset-helper locations of the same name.
            if (name !in modules) {
                val resource = ANDROLUA_RESOURCE_ROOT + relative + ".lua"
                modules[name] = RawProviderModuleResource(resource, lazy { declarationSurface(name, resource) })
            }
        }
        return modules
    }

    private val androidLuaDeclarationResources = listOf(
        "loadlayout", "loadbitmap", "loadmenu", "modules/autotheme", "modules/base64",
        "modules/bin", "modules/bmob", "modules/check", "modules/console", "modules/ftp",
        "modules/hex", "modules/http", "modules/json", "modules/logcat", "modules/ltn12",
        "modules/mbox", "modules/mime", "modules/options", "modules/permission", "modules/smtp",
        "modules/socket", "modules/socket/headers", "modules/socket/tp", "modules/socket/url",
        "modules/su", "modules/test", "modules/xml", "helpers/AndLua", "helpers/Dialog",
        "helpers/ThomeLua", "helpers/file", "helpers/loadlayout2", "helpers/loadlayout3",
        "helpers/toast", "helpers/xml2table", "helpers/bin", "helpers/bmob"
    )

    private val declarationSurfaces by lazy {
        (androidLuaDeclarationResources + "import").associate { relative ->
            val name = if (relative.startsWith("modules/socket/")) relative.removePrefix("modules/").replace('/', '.')
                else relative.substringAfterLast('/')
            val resource = ANDROLUA_RESOURCE_ROOT + relative + ".lua"
            resource to lazy { compileDeclarationSurface(name, resource) }
        }
    }

    private fun declarationSurface(moduleName: String, resource: String): ModuleExportSurface =
        declarationSurfaces.getValue(resource).value.also { require(it.moduleType.moduleName == moduleName) }

    /** Run the complete declaration source through the canonical comment/binder/type pipeline. */
    private fun compileDeclarationSurface(moduleName: String, resource: String): ModuleExportSurface {
        val declaration = declarationBinding(BuiltinOverlayResourceAccess.readText(resource))
        val chunk = declaration.chunk
        val binder = declaration.binder
        val context = SemanticWorkspaceContext(overlayGlobals = BuiltinOverlaySnapshot.EMPTY.globals)
        val evaluator = ExpressionTypeEvaluator(binder, context)
        val returned = chunk.body.returnStatement?.arguments?.singleOrNull()
        val type = returned?.let(evaluator::evaluate) ?: UnknownType
        val fields = when (type) {
            is ModuleType -> type.fields
            is TableType -> type.fields
            is ClassType -> type.getAllFields()
            is CallableType -> mapOf("__call" to type)
            else -> emptyMap()
        }.toMutableMap()
        val methods = when (type) {
            is ModuleType -> type.methods
            is TableType -> type.methods
            is ClassType -> type.getAllMethods()
            else -> emptyMap()
        }.toMutableMap()
        // import.lua's returned installer publishes its declared M namespace into _G.
        if (moduleName == "import") {
            binder.declarationIndex.declarations.forEach { declaration ->
                val member = declaration.anchorNode?.parent as? MemberExpression ?: return@forEach
                if ((member.base as? Identifier)?.name == "M") {
                    declaration.declaredType?.let { fields[declaration.name] = it }
                }
            }
        }
        fields.entries.filter { it.key != "__call" && it.value is CallableType }.forEach { (name, value) ->
            methods[name] = value
        }
        methods.keys.forEach(fields::remove)
        return moduleSurface(moduleName, fields, methods)
    }

    private fun androluaImportSurface(luajavaModuleType: ModuleType): ModuleExportSurface {
        // Runtime import.lua returns env_import and installs helpers onto _G. Model both the
        // callable require("import") return shape and the helper members used by library stubs.
        val envImportType = androluaEnvImportFunctionType()
        val fields = linkedMapOf(
            "__call" to envImportType,
            "env_import" to envImportType,
            "luajava" to luajavaModuleType,
            "loadlayout" to declarationSurface("loadlayout", ANDROLUA_RESOURCE_ROOT + "loadlayout.lua").moduleType,
            "loadbitmap" to declarationSurface("loadbitmap", ANDROLUA_RESOURCE_ROOT + "loadbitmap.lua").moduleType,
            "loadmenu" to declarationSurface("loadmenu", ANDROLUA_RESOURCE_ROOT + "loadmenu.lua").moduleType
        )
        fields.putAll(luajavaModuleType.fields)

        val declared = declarationSurface("import", ANDROLUA_RESOURCE_ROOT + "import.lua")
        val methods = declared.moduleType.methods.toMutableMap()
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

    private fun androluaEnvImportFunctionType(): FunctionType =
        declarationSurface("import", ANDROLUA_RESOURCE_ROOT + "import.lua")
            .moduleType.fields.getValue("__call") as FunctionType

    private fun androidLuaResource(
        relativePath: String,
        surface: ModuleExportSurface
    ): RawProviderModuleResource = RawProviderModuleResource(
        resourcePath = ANDROLUA_RESOURCE_ROOT + relativePath,
        surface = lazy { surface }
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
            indexedClassNames.forEach { className ->
                classModels[className] = AndroidFrameworkClassModel(
                    binaryName = className
                )
            }
        }
        require(classModels.isNotEmpty()) {
            "Android framework manifest '${resources.manifestResource}' did not provide any class models."
        }

        val providerModuleNames = androidFrameworkClassProviderModuleNames(classModels.keys)
        val immediateInnerClasses = androidFrameworkImmediateInnerClassIndex(classModels.keys)
        val classTypes = androidFrameworkClassTypes(classModels, immediateInnerClasses)
        val moduleTypes = linkedMapOf<String, ModuleType>()
        val providers = linkedMapOf<VirtualPath, BuiltinOverlaySnapshot.ProviderModuleSnapshot>()
        classModels.values.sortedBy { it.binaryName }.forEach { classModel ->
            val moduleType = androidFrameworkClassModuleType(
                classModel = classModel,
                classModels = classModels,
                providerModuleNames = providerModuleNames,
                classTypes = classTypes,
                immediateInnerClasses = immediateInnerClasses,
                moduleTypes = moduleTypes,
                moduleName = androidFrameworkSimpleAlias(classModel.binaryName)
            )
            val path = androidFrameworkClassProviderPath(classModel.binaryName)
            val providerNames = androidFrameworkProviderNames(classModel.binaryName)
            val providerFile = androidFrameworkProviderFile(
                source = "-- Android framework resource class provider for ${classModel.binaryName}\n",
                surface = ModuleExportSurface(
                    moduleType = moduleType,
                    sourceForm = ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL,
                    members = androidFrameworkClassModuleMembers(moduleType)
                ),
                providedModuleNames = providerNames
            )
            val primaryModuleName = providerModuleNames.getValue(classModel.binaryName)
            providers[path] = BuiltinOverlaySnapshot.ProviderModuleSnapshot(
                moduleName = primaryModuleName,
                file = providerFile
            )
            providerNames
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
            val moduleType = androidFrameworkPackageModuleType(packageName, classModels, providerModuleNames, classTypes, immediateInnerClasses, moduleTypes)
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
                require(parts.size == 2 && parts.none(String::isEmpty)) {
                    "Invalid Android framework manifest row ${index + 1}: '$line'."
                }
                AndroidFrameworkManifestEntry(
                    packageName = parts[0],
                    classIndexResource = resources.resolve(parts[1])
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

    private fun androidFrameworkClassModuleType(
        classModel: AndroidFrameworkClassModel,
        classModels: Map<String, AndroidFrameworkClassModel>,
        providerModuleNames: Map<String, String>,
        classTypes: Map<String, JavaClassType>,
        immediateInnerClasses: Map<String, List<String>>,
        moduleTypes: MutableMap<String, ModuleType>,
        moduleName: String = providerModuleNames.getValue(classModel.binaryName)
    ): ModuleType {
        val cacheKey = "${classModel.binaryName}|$moduleName"
        moduleTypes[cacheKey]?.let { return it }
        val classType = classTypes.getValue(classModel.binaryName)
        val fields = linkedMapOf<String, Type>("__class" to JavaInstanceType(classType))
        val result = ModuleType(
            moduleName = moduleName,
            fields = fields.also { fields ->
                immediateInnerClasses[classModel.binaryName].orEmpty().forEach { innerClass ->
                    fields[androidFrameworkSimpleAlias(innerClass)] = androidFrameworkClassModuleType(
                        classModel = classModels.getValue(innerClass),
                        classModels = classModels,
                        providerModuleNames = providerModuleNames,
                        classTypes = classTypes,
                        immediateInnerClasses = immediateInnerClasses,
                        moduleTypes = moduleTypes
                    )
                }
            }
        )
        moduleTypes[cacheKey] = result
        return result
    }

    private fun androidFrameworkClassTypes(
        classModels: Map<String, AndroidFrameworkClassModel>,
        immediateInnerClasses: Map<String, List<String>>
    ): Map<String, JavaClassType> {
        val cache = linkedMapOf<String, JavaClassType>()

        fun build(className: String, stack: MutableSet<String>): JavaClassType {
            cache[className]?.let { return it }
            val classModel = classModels.getValue(className)
            val javaName = androidFrameworkJavaTypeName(classModel.binaryName)
            if (!stack.add(className)) {
                return JavaClassType(javaName = javaName)
            }
            val classType = JavaClassType(
                javaName = javaName,
                innerClasses = immediateInnerClasses[classModel.binaryName].orEmpty()
                    .associate { innerClass -> androidFrameworkSimpleAlias(innerClass) to build(innerClass, stack) }
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
        classTypes: Map<String, JavaClassType>,
        immediateInnerClasses: Map<String, List<String>>,
        moduleTypes: MutableMap<String, ModuleType>
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
                    classTypes = classTypes,
                    immediateInnerClasses = immediateInnerClasses,
                    moduleTypes = moduleTypes
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

    private fun androidFrameworkImmediateInnerClassIndex(classNames: Set<String>): Map<String, List<String>> {
        val result = classNames.associateWith { mutableListOf<String>() }.toMutableMap()
        classNames.forEach { candidate ->
            val parent = candidate.substringBeforeLast('$', "")
            if (parent.isNotEmpty() && parent in result) result.getValue(parent).add(candidate)
        }
        return result.mapValues { (_, children) -> children.sortedBy(::androidFrameworkSimpleAlias) }
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
        val globalsResourcePath: String,
        val inheritedGlobalsResourcePath: String? = null,
        val providerModuleResourcePaths: Map<String, String>,
        val rawProviderModuleResources: Map<String, RawProviderModuleResource> = emptyMap(),
        val androidFrameworkResources: AndroidFrameworkResources? = null,
        val compatibilityGlobalsSource: String,
        val globalNames: Set<String>,
        val moduleFieldNames: Map<String, Set<String>>
    )

    private data class RawProviderModuleResource(
        val resourcePath: String,
        val surface: Lazy<ModuleExportSurface>? = null
    )

    private data class AndroidFrameworkResources(
        val rootResource: String,
        val manifestResource: String
    ) {
        fun resolve(relativePath: String): String = rootResource + relativePath
    }

    private data class AndroidFrameworkManifestEntry(
        val packageName: String,
        val classIndexResource: String
    )

    private data class AndroidFrameworkClassModel(
        val binaryName: String
    )

    private const val ANDROLUA_RESOURCE_ROOT =
        "/io/github/dingyi222666/luaparser/semantic/workspace/androidlua/androlua5.3/"

    private const val ANDROLUA_LUAJAVA_RESOURCE =
        "/io/github/dingyi222666/luaparser/semantic/workspace/std/androlua53-luajava/luajava.lua"
    private const val ANDROID_FRAMEWORK_RESOURCE_ROOT =
        "/io/github/dingyi222666/luaparser/semantic/workspace/android-framework/"
    private const val ANDROID_FRAMEWORK_MANIFEST_RESOURCE =
        "/io/github/dingyi222666/luaparser/semantic/workspace/android-framework/manifest.index"
    private const val ANDROID_FRAMEWORK_SUPPORTING_PACKAGE = "android.supporting"
    private val ANDROID_FRAMEWORK_UNMODELED_SUPER_TYPES = setOf("JavaObject", "any")
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
        "xml2table",
        // Lua standard library module names: android resource classes reflect as lowercase
        // simple names (android.R$string → "string", R$layout → "layout") and must never
        // claim the std module a global identifier resolves to.
        "bit32",
        "coroutine",
        "debug",
        "io",
        "math",
        "os",
        "package",
        "string",
        "table",
        "utf8"
    )
    private val DOCUMENTED_CALLABLE_GLOBAL_VALUE_NAMES = setOf("ipairs", "pairs")
}
