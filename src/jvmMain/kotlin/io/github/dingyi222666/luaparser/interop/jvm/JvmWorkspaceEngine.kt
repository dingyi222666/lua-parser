package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.UnresolvedLuaJavaTarget
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot

/**
 * JVM workspace engine for Android-Lua / LuaJava reflective modules.
 * Host android.jar discovery uses ANDROID_HOME/SDK_ROOT and well-known SDK roots; never G:/.
 */
open class JvmWorkspaceEngine(
    private val workspaceParserFactory: (LuaVersion) -> LuaParser = { version -> LuaParser(version) },
    private val classModuleProvider: JvmClassModuleProvider = JvmClassModuleProvider(),
    private val configuration: JvmWorkspaceConfiguration = JvmWorkspaceConfiguration(),
    private val analysisCheckpoint: () -> Unit = {},
    private val deferDependencyGlobals: Boolean = false,
    private val workspaceParallelism: Int = 1,
    /**
     * Interactive snapshots do not need a complete package directory. Resolve the
     * class names actually referenced by the current buffers and let the full
     * background index enumerate wildcard packages later.
     */
    private val deferWildcardPackageIndex: Boolean = false
) : LuaWorkspaceEngine(workspaceParserFactory) {
    override fun <T> analysisStage(stage: String, path: VirtualPath?, action: () -> T): T {
        if (System.getProperty("lua.parser.perf") != "true" && System.getenv("LUA_PARSER_PERF") == null) {
            return action()
        }
        val started = System.nanoTime()
        return try { action() } finally {
            System.err.println("ANALYSIS stage=$stage path=${path ?: "workspace"} " +
                "ms=${(System.nanoTime() - started) / 1_000_000} thread=${Thread.currentThread().name}")
        }
    }

    override fun <T> mapIndependentDocuments(paths: List<VirtualPath>, action: (VirtualPath) -> T): List<T> {
        val workers = workspaceParallelism.coerceIn(1, 8).coerceAtMost(paths.size)
        if (workers < 2) return super.mapIndependentDocuments(paths, action)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(workers) { task ->
            Thread(task, "lua-workspace-frontend").apply { isDaemon = true }
        }
        val futures = paths.map { path -> executor.submit(java.util.concurrent.Callable {
            analysisCheckpoint()
            action(path).also { analysisCheckpoint() }
        }) }
        try {
            // Consume in input order so publication and declaration precedence stay deterministic.
            return futures.map { future ->
                try { future.get() } catch (failure: java.util.concurrent.ExecutionException) {
                    throw failure.cause ?: failure
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }

    /**
     * Per-update [io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver]
     * reuse (wave K perf). A fresh resolver used to be built per analyzed file per update, so
     * its caches (`importedSymbolsFor` keyed by path, `activeProvider` keyed by module name,
     * provider globals keyed by provider path) never survived and `packageMembers` re-ran its
     * O(wildcardMembers x extraProviders) linear scans for every file on every keystroke.
     *
     * Reuse is sound because the resolver derives solely from the snapshot it is handed: its
     * mutable state is keyed by path/module name (no cross-path bleed), and nothing it reads
     * changes while that snapshot instance drives one analysis pass. The cache is keyed by
     * snapshot IDENTITY — each analysis pass (build / update / attachSemanticState) composes a
     * fresh analysis snapshot instance, so a new update naturally starts a new generation.
     *
     * A served path that repeats marks a cycle re-analysis sweep (attachSemanticState re-runs
     * SCC members against rewritten peer semantic files). Sweeps therefore start a fresh
     * resolver generation, preserving exactly the per-sweep freshness the old per-file
     * resolvers provided — a cached import map or provider-global list must never outlive the
     * peer binding state it was derived from.
     */
    private var cachedResolverSnapshot: WorkspaceSnapshot? = null
    private var cachedResolver: io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver? = null
    private val cachedResolverPaths = mutableSetOf<VirtualPath>()

    internal override fun createWorkspaceResolver(snapshot: WorkspaceSnapshot) =
        io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver(snapshot, deferDependencyGlobals) { candidate ->
            cachedChunkFor(snapshot.files, candidate)
        }

    private fun workspaceResolverFor(
        snapshot: WorkspaceSnapshot,
        path: VirtualPath
    ): io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver {
        val cached = cachedResolver
        if (cached != null && cachedResolverSnapshot === snapshot && path !in cachedResolverPaths) {
            cachedResolverPaths += path
            return cached
        }
        val fresh = createWorkspaceResolver(snapshot)
        cachedResolverSnapshot = snapshot
        cachedResolver = fresh
        cachedResolverPaths.clear()
        cachedResolverPaths += path
        return fresh
    }

    override fun extraProviders(input: LuaWorkspaceInput): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        analysisCheckpoint()
        val baseConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        val documentFacts = input.files.mapValues { (path, source) -> documentFacts(path, source) }
        val resolvedConfiguration = configurationWithWildcardImportPrefixes(
            baseConfiguration,
            documentFacts
        )
        if (deferWildcardPackageIndex) {
            // The live query context owns path-scoped imported symbols, including
            // direct JVM/DEX shells. Keep the cold graph free of reflective class
            // and package providers; the authoritative background engine mounts
            // that complete catalog later.
            return emptyMap()
        }
        // Explicit / bindClass / simple import targets only. Wildcard package modules list under
        // packageProvidersFor (packages/ paths only). Shallow class providers for package
        // members come from packageMemberClassProvidersFor (classes/ paths) so package-list
        // keys never mix __jvm__/classes prefixes.
        // Free UpperCamel identifiers still activate path-scoped import aliases in
        // workspaceContext (Android-Lua bare Locale/TextView). They must NOT auto-mount
        // reflective class providers into extraProviders: short require("Arrays") without
        // CLASSES_METADATA / explicit import/bindClass must stay unresolved (TASK-628).
        val sourceDiscoveredClasses = collectSourceDiscoveredClasses(
            documentFacts,
            resolvedConfiguration
        )
        val packageTargets = collectWildcardImportTargets(baseConfiguration, documentFacts)
            .mapNotNull(::normalizePackageProviderTarget)
            .toCollection(linkedSetOf())
        val packageProviders = classModuleProvider.packageProvidersFor(packageTargets, resolvedConfiguration)
        val packageMemberClassProviders = classModuleProvider.packageMemberClassProvidersFor(packageTargets, resolvedConfiguration)
        // Bare `import "libs/classes.dex"` library mounts (library module + per-class
        // providers) fed from configured imports, document source imports and AST targets.
        // The provider re-filters to bare existing dex/apk paths; nothing mounts by
        // directory scan alone.
        val dexLibraryProviders = classModuleProvider.dexLibraryProvidersFor(
            collectDexLibraryImportTargets(baseConfiguration, documentFacts),
            resolvedConfiguration,
            deferMembers = deferDependencyGlobals
        )
        val providerConfiguration = if (sourceDiscoveredClasses.isEmpty()) {
            resolvedConfiguration
        } else {
            resolvedConfiguration.copy(
                classes = (resolvedConfiguration.classes + sourceDiscoveredClasses)
                    .toCollection(linkedSetOf())
            )
        }
        // Package modules + shallow class providers + dex library mounts first; explicit/
        // full providers win on path collision so bindClass / configured classes keep deep
        // reflection surfaces.
        return packageProviders + packageMemberClassProviders + dexLibraryProviders +
            classModuleProvider.providersFor(providerConfiguration, deferMembers = deferDependencyGlobals)
    }

    internal override fun workspaceContext(
        input: LuaWorkspaceInput,
        path: VirtualPath,
        snapshot: WorkspaceSnapshot
    ): SemanticWorkspaceContext {
        val baseConfiguration = configuration.overlay(JvmWorkspaceConfiguration.fromMetadata(input.metadata))
        // Path-scoped only: never re-parse the full multi-file workspace for each document.
        // Snapshot documentFacts from analyzeFile are authoritative; fall back to a single-path collect.
        val currentSource = input.files[path]
            ?: snapshot.files[path]?.semanticFile?.source
        val currentFacts = snapshot.files[path]?.documentFacts
            ?: currentSource?.let { documentFacts(path, it) }
        val configuredImports = if (deferWildcardPackageIndex) emptyMap()
        else analysisStage("configured-imports", path) { activateImports(baseConfiguration.normalized().androluaImports, emptySet(), baseConfiguration) }
        val resolvedConfiguration = configurationWithWildcardImportPrefixes(
            baseConfiguration, currentFacts?.let { mapOf(path to it) }.orEmpty()
        )
        val mustMountDex = currentFacts?.sourceImports.orEmpty().any { importFact ->
            val target = importFact.target.removePrefix("import ").trim()
            target.endsWith(".dex") || target.endsWith(".apk") || target.contains(':')
        }
        val sourceImports = if (deferWildcardPackageIndex && !mustMountDex) emptyMap()
        else analysisStage("source-imports", path) { activateImports(currentFacts?.sourceImports.orEmpty().map { it.target },
            currentFacts?.classLikeNames.orEmpty(), resolvedConfiguration) }
        val activeImports = linkedMapOf<String, WorkspaceImportedSymbol>().apply {
            putAll(configuredImports)
            putAll(sourceImports)
        }
        val workspaceResolver = analysisStage("resolver", path) {
            workspaceResolverFor(snapshot, path)
        }
        // Compose on the common-engine base context so currentPath / overlayGlobals /
        // layoutPropertyExtensions derive from one place; only the JVM-specific layers
        // (per-update resolver, configured+source imports, dynamic target resolution,
        // unresolved LuaJava diagnostics) are stacked on top via copy.
        val baseContext = analysisStage("base-context", path) {
            super.workspaceContext(input, path, snapshot)
        }
        return baseContext.copy(
            analysisCheckpoint = analysisCheckpoint,
            workspaceResolver = workspaceResolver,
            // Only base configured imports go in the context map; document source imports are
            // re-applied by SemanticWorkspaceContext.withWorkspaceImportEffects() for this path.
            // Keep activeImports here so current-file analysis already sees both layers.
            importedSymbols = activeImports,
            // Resolver-free fallbacks: when a consumer nulls the composed resolve* lambdas
            // (the live-diagnostics checker rebuilds its context against a deferred
            // resolver), withWorkspaceImportEffects() recomposes from these instead of
            // losing engine-level JVM resolution — otherwise deferred-shell module types
            // (dynamic dex imports) can never hydrate their member surfaces.
            baseResolveImportedSymbol = { name ->
                lazyImportedSymbol(name, currentFacts, baseConfiguration, resolvedConfiguration)
            },
            baseResolveImportTarget = { target ->
                classModuleProvider.importedSymbolForTarget(target, resolvedConfiguration)
            },
            resolveImportedSymbol = { name ->
                activeImports[name] ?: lazyImportedSymbol(
                    name, currentFacts, baseConfiguration, resolvedConfiguration
                )
            },
            // Dynamic import()/import "..." targets must resolve even when not yet on the
            // sourceImports activation set for this path (engine-level JVM resolution).
            resolveImportTarget = { target ->
                classModuleProvider.importedSymbolForTarget(target, resolvedConfiguration)
            },
            unresolvedLuaJavaTargets = collectUnresolvedLuaJavaTargets(
                currentFacts,
                resolvedConfiguration,
                workspaceResolver
            ),
            lazyImportedSymbols = deferWildcardPackageIndex
        )
    }

    private fun lazyImportedSymbol(
        alias: String,
        facts: DocumentFacts?,
        baseConfiguration: JvmWorkspaceConfiguration,
        resolvedConfiguration: JvmWorkspaceConfiguration
    ): WorkspaceImportedSymbol? {
        val targets = buildList {
            facts?.sourceImports.orEmpty().forEach { add(it.target) }
            baseConfiguration.normalized().androluaImports.forEach(::add)
        }
        return targets.firstNotNullOfOrNull { target ->
            val normalized = target.removePrefix("import ").trim()
            val simple = normalized.substringAfterLast('.').removeSuffix("*")
            if (simple == alias || normalized == alias) {
                classModuleProvider.importedSymbolForTarget(target, resolvedConfiguration)
                    ?.copy(alias = alias)
            } else if (normalized.endsWith(".*")) {
                val packageName = normalized.removeSuffix(".*").substringAfter(':')
                classModuleProvider.importedSymbolForTarget(
                    "$packageName.$alias", resolvedConfiguration,
                    deferMembers = deferDependencyGlobals
                )?.copy(alias = alias)
            } else null
        }
    }

    private fun collectUnresolvedLuaJavaTargets(
        facts: DocumentFacts?,
        configuration: JvmWorkspaceConfiguration,
        workspaceResolver: io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver
    ): List<UnresolvedLuaJavaTarget> {
        if (facts == null) {
            return emptyList()
        }
        return (
            facts.jvmClassLoads
                .asSequence()
                .filter { it.kind in diagnosticLuaJavaClassLoadKinds }
                .filter { fact ->
                    classModuleProvider.importedClassName(fact.target, configuration) == null &&
                        (fact.kind != DocumentFacts.JvmClassLoadKind.IMPORT_CALL ||
                            workspaceResolver.importTargetSymbol(fact.target) == null)
                }
                .map { fact ->
                    UnresolvedLuaJavaTarget(
                        target = fact.target,
                        helperName = luaJavaHelperName(fact.kind),
                        range = fact.range
                    )
                } +
                // Dead wildcard/package imports: `pkg.*` whose package enumeration came back
                // empty (typo, case mismatch, absent jar) used to emit nothing because the
                // wildcard targets are stripped from jvmClassLoads and IMPORT_CALL stays off
                // diagnosticLuaJavaClassLoadKinds. sourceImports still carries every wildcard
                // target; the resolver resolves a wildcard only through its mounted package
                // provider (packageProvidersFor never mounts an empty enumeration), so a null
                // symbol proves the package enumerated zero classes.
                facts.sourceImports
                    .asSequence()
                    // Dead wildcard/package imports only: `pkg.*` whose package enumeration
                    // mounted no provider (typo, case mismatch, absent jar). Non-wildcard
                    // targets keep their existing diagnostic policy.
                    .filter { importFact ->
                        val target = importFact.target.removePrefix("import ").trim()
                        target.endsWith(".*") && workspaceResolver.importTargetSymbol(target) == null
                    }
                    .map { importFact ->
                        UnresolvedLuaJavaTarget(
                            target = importFact.target,
                            helperName = "import",
                            range = importFact.range,
                            fromWildcardImport = true
                        )
                    }
            )
            .distinctBy { target ->
                listOf(
                    target.range?.start?.line,
                    target.range?.start?.column,
                    target.range?.end?.line,
                    target.range?.end?.column,
                    target.helperName,
                    target.target
                )
            }
            .toList()
    }

    private data class ImportFileIdentity(val path: String, val exists: Boolean, val size: Long, val modified: Long)
    private data class ImportActivationKey(
        val configuration: JvmWorkspaceConfiguration,
        val targets: List<String>,
        val classNames: Set<String>,
        val files: List<ImportFileIdentity>
    )
    private val importActivations = java.util.LinkedHashMap<ImportActivationKey, Map<String, WorkspaceImportedSymbol>>(16, 0.75f, true)
    internal var onImportActivation: (() -> Unit)? = null

    private fun activateImports(
        targets: List<String>,
        classNames: Set<String>,
        configuration: JvmWorkspaceConfiguration
    ): Map<String, WorkspaceImportedSymbol> {
        val normalized = configuration.normalized()
        val files = (normalized.reflectionClasspathEntries() + listOfNotNull(normalized.androidDex) +
            targets.mapNotNull { target ->
                val text = target.removePrefix("import ").trim()
                when {
                    ':' in text -> text.substringBefore(':')
                    text.endsWith(".dex") || text.endsWith(".apk") -> text
                    else -> null
                }
            }).distinct().map { java.io.File(it) }
        // Exploded class directories can change without updating the root timestamp.
        // Their providers remain uncached here rather than silently reusing stale activations.
        val key = if (files.any { it.isDirectory }) null else ImportActivationKey(normalized,
            targets.toList(), classNames.toSet(), files.map { file ->
                ImportFileIdentity(file.absolutePath, file.exists(), file.length(), file.lastModified())
            })
        key?.let { importActivations[it]?.let { return it } }
        analysisCheckpoint()
        onImportActivation?.invoke()
        val imported = linkedMapOf<String, WorkspaceImportedSymbol>()
        targets.forEach { target ->
            if (deferWildcardPackageIndex && isWildcardOrPackageTarget(target)) return@forEach
            val symbol = classModuleProvider.importedSymbolForTarget(target, normalized,
                deferMembers = deferDependencyGlobals)
            symbol?.let { imported[it.alias] = it }
            if (!isWildcardOrPackageTarget(target) && symbol == null) {
                classModuleProvider.importedClassNames(target, normalized)
                    .mapNotNull { classModuleProvider.importedSymbol(it, normalized, deferMembers = deferDependencyGlobals) }
                    .forEach { imported[it.alias] = it }
            }
        }
        classNames.filter(::isClassLikeSimpleName).forEach { name ->
            if (name !in imported) classModuleProvider.implicitClassSymbol(name, normalized)?.let { imported[it.alias] = it }
        }
        return imported.toMap().also { result ->
            if (key != null) {
                importActivations[key] = result
                if (importActivations.size > 32) importActivations.remove(importActivations.keys.first())
            }
        }
    }

    private fun collectSourceDiscoveredClasses(
        documentFacts: Map<VirtualPath, DocumentFacts>,
        configuration: JvmWorkspaceConfiguration
    ): Set<String> {
        return buildSet {
            fun addExplicitClassTarget(target: String) {
                // Never expand wildcards/package aliases into full package class lists here.
                // packageProvidersFor + packageMemberClassProvidersFor mount package modules
                // and shallow class providers instead.
                if (isWildcardOrPackageTarget(target)) {
                    return
                }
                classModuleProvider.importedClassName(target, configuration)?.let(::add)
            }
            documentFacts.values.forEach { facts ->
                facts.sourceImports.forEach { importFact ->
                    addExplicitClassTarget(importFact.target)
                }
                facts.jvmClassLoads.forEach { fact ->
                    when (fact.kind) {
                        DocumentFacts.JvmClassLoadKind.IMPORT_CALL,
                        DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL,
                        DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL,
                        DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL,
                        DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL,
                        // createArray("pkg.Foo", ...) mounts element class provider without separate bindClass.
                        DocumentFacts.JvmClassLoadKind.CREATE_ARRAY_CALL -> {
                            addExplicitClassTarget(fact.target)
                        }
                    }
                }
            }
        }
    }

    private fun isClassLikeSimpleName(name: String): Boolean {
        if (name.isEmpty() || name in NON_CLASS_FREE_IDENTIFIERS) {
            return false
        }
        val first = name.first()
        if (!first.isUpperCase() || !first.isLetter()) {
            return false
        }
        return name.all { ch -> ch.isLetterOrDigit() || ch == '_' }
    }

    private fun isWildcardOrPackageTarget(importText: String): Boolean {
        // Same membership as [packageNameAliasPrefix] (dotted lowercase segments, >=2 segments,
        // not a loadable-class shape) plus the explicit `pkg.*` wildcard form.
        val normalized = importText.removePrefix("import ").trim()
        val target = normalized.substringAfter(':', normalized).trim()
        return target.endsWith(".*") || packageNameAliasPrefix(importText) != null
    }

    /**
     * Candidate import targets for dex library mounts (`import "libs/classes.dex"`):
     * configured imports, document source imports and AST import targets. The provider
     * re-filters to bare, existing dex/apk paths — this set is only the candidate pool.
     */
    private fun collectDexLibraryImportTargets(
        configuration: JvmWorkspaceConfiguration,
        documentFacts: Map<VirtualPath, DocumentFacts>
    ): Set<String> {
        return buildSet {
            configuration.normalized().androluaImports.forEach(::add)
            documentFacts.values.forEach { facts ->
                facts.sourceImports.forEach { importFact ->
                    add(importFact.target)
                }
            }
        }
    }

    private fun collectWildcardImportTargets(
        configuration: JvmWorkspaceConfiguration,
        documentFacts: Map<VirtualPath, DocumentFacts>
    ): Set<String> {
        // Mount package module providers for:
        // - wildcard proxies: import "android.widget.*" / import("android.widget.*")
        // - package-name aliases: import("android.widget") / import("java.util")
        // Package modules power widget./util. member completions and hover moduleName.
        return buildSet {
            fun maybeAddPackageTarget(importText: String) {
                if (wildcardImportPrefix(importText) != null || packageNameAliasPrefix(importText) != null) {
                    add(importText)
                }
            }
            configuration.normalized().androluaImports.forEach(::maybeAddPackageTarget)
            documentFacts.values.forEach { facts ->
                facts.sourceImports.forEach { importFact ->
                    maybeAddPackageTarget(importFact.target)
                }
            }
        }
    }

    private fun configurationWithWildcardImportPrefixes(
        configuration: JvmWorkspaceConfiguration,
        documentFacts: Map<VirtualPath, DocumentFacts>
    ): JvmWorkspaceConfiguration {
        val normalized = configuration.normalized()
        // Import precedence (wave K): AndroLua installs imports into _G sequentially, so the
        // LAST document import wins short-name collisions (import "android.widget.*" followed
        // by import "android.support.v7.widget.*" must resolve Toolbar to the support class).
        // The document's own wildcard prefixes therefore go FIRST in reverse document order,
        // then the workspace-configured (`androlua.imports` / jvm.importPrefixes) wildcard
        // prefixes, then DEFAULT_IMPORT_PREFIXES — all as fallbacks for identifiers the
        // document never imported (bare Button under a support-only import still lands on
        // android.widget.Button). Previously every wildcard suffix was appended after the
        // defaults, so neither a document import nor a configured one could override a
        // default-prefix short name. Per-document resolution (workspaceContext) sees exactly
        // one file's facts, so reverse document order is exact there; the workspace-wide
        // extraProviders pass flattens every file's facts in input order before reversing,
        // which keeps each file's internal order intact.
        val documentWildcardPrefixes = documentFacts.values.flatMap { facts ->
            facts.sourceImports.mapNotNull { importFact -> wildcardImportPrefix(importFact.target) }
        }
        val configuredWildcardPrefixes = normalized.androluaImports.mapNotNull(::wildcardImportPrefix)
        if (documentWildcardPrefixes.isEmpty() && configuredWildcardPrefixes.isEmpty()) {
            return normalized
        }
        return normalized.copy(
            importPrefixes = (
                documentWildcardPrefixes.asReversed() +
                    configuredWildcardPrefixes +
                    normalized.importPrefixes
                ).distinct()
        )
    }

    private fun wildcardImportPrefix(importText: String): String? {
        val normalized = importText.removePrefix("import ").trim()
        if (!normalized.endsWith(".*")) {
            return null
        }
        return normalized.substringAfter(':', normalized).removeSuffix(".*").takeIf(String::isNotBlank)
    }

    /**
     * Normalize package-list targets to wildcard form (`pkg.*`).
     * [JvmClassModuleProvider.packageProvidersFor] is wildcard-only so package-name aliases
     * such as `android.widget` are rewritten to `android.widget.*` before mount.
     */
    private fun normalizePackageProviderTarget(importText: String): String? {
        wildcardImportPrefix(importText)?.let { return "$it.*" }
        packageNameAliasPrefix(importText)?.let { return "$it.*" }
        return null
    }

    private fun luaJavaHelperName(kind: DocumentFacts.JvmClassLoadKind): String {
        return when (kind) {
            DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL -> "bindClass"
            DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL -> "newInstance"
            DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL -> "createProxy"
            DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL -> "loadLib"
            DocumentFacts.JvmClassLoadKind.IMPORT_CALL,
            DocumentFacts.JvmClassLoadKind.CREATE_ARRAY_CALL -> kind.name
        }
    }

    private companion object {
        val diagnosticLuaJavaClassLoadKinds: Set<DocumentFacts.JvmClassLoadKind> = setOf(
            DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL,
            DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL,
            DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL,
            DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL
        )

        // UpperCamel free identifiers that must not mount as JVM class roots.
        // Filters host/Lua-ish false positives; real classes (Locale/TextView/File) stay eligible.
        private val NON_CLASS_FREE_IDENTIFIERS: Set<String> = setOf(
            "And",
            "Or",
            "Not",
            "True",
            "False",
            "Nil",
            "Self",
            "This",
            "Super",
            "Global"
        )
    }


    private fun packageNameAliasPrefix(importText: String): String? {
        val normalized = importText.removePrefix("import ").trim()
        val target = normalized.substringAfter(':', normalized).trim()
        if (target.isBlank() || target.endsWith(".*") || '.' !in target) {
            return null
        }
        val segments = target.split('.')
        if (segments.size < 2) {
            return null
        }
        // Package segments are lowercase-leading (android / widget / util); class simple names
        // are UpperCamelCase and must not mount as package modules.
        val packageLike = segments.all { segment ->
            segment.isNotEmpty() &&
                segment.first().isLowerCase() &&
                segment.all { ch -> ch.isLetterOrDigit() || ch == '_' }
        }
        return target.takeIf { packageLike }
    }
}
