package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.interop.dex.DexClass
import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaConstructorType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadSet
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaSignatureMetadata
import io.github.dingyi222666.luaparser.semantic.types.model.JavaStaticMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.workspaceFingerprintHash
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.net.JarURLConnection
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.JarFile
import java.lang.reflect.Type as ReflectType

class JvmClassModuleProvider(
    private val baseClassLoader: ClassLoader = JvmClassModuleProvider::class.java.classLoader,
    private val defaultImportPrefixes: List<String> = DEFAULT_IMPORT_PREFIXES
) {
    data class ImportDiagnostic(
        val importText: String,
        val pathPrefix: String,
        val className: String,
        val message: String,
        val code: String
    )

    // Product heap bounds (TASK-611): memoize expensive reflection so multi-file workspace
    // builds and per-document workspaceContext never re-expand android.jar providers.
    private val fullClassProviderCache =
        linkedMapOf<String, Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot>>()
    private val shallowClassProviderCache =
        linkedMapOf<String, Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot>>()
    private val packageProviderCache =
        linkedMapOf<String, Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot>>()
    private val wildcardClassNameCache = linkedMapOf<String, List<String>>()
    private val classLoaderCache = linkedMapOf<String, ClassLoader>()
    private val moduleTypeCache = linkedMapOf<String, ModuleType>()
    private val shallowModuleTypeCache = linkedMapOf<String, ModuleType>()
    // Wave K perf: reflectiveClasspathFiles() is needed to BUILD the wildcard/package cache
    // keys below (and to construct class loaders), so an uncached call re-ran env reads plus
    // SDK-root directory listings on every short-name/package resolution even when every
    // downstream cache was going to hit. The resolution is deterministic in (configured
    // classpath fields + env values + well-known SDK roots); env and the filesystem SDK roots
    // cannot change mid-process, so entries are intentionally never invalidated.
    private val reflectiveClasspathFilesCache = linkedMapOf<String, List<File>>()
    // Negative Class.forName cache: CNFE carries a full stack trace, and probing
    // thousands of absent names (import guesses, wildcard expansions) dominated analysis
    // time. Keyed per resolved classloader string so a classpath change re-probes.
    private val classLoadMisses = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    // Dex mounting (libs/classes.dex): parse caching is process-wide in DexLibraryMounter;
    // this instance map additionally registers libraries discovered through bare
    // `import "libs/classes.dex"` source imports so later bindClass/newInstance dotted
    // targets resolve against them within the same provider (workspace update) lifetime —
    // mirroring the AndroLua runtime where importing a dex loads it.
    private val mountedDexLibraries = linkedMapOf<String, DexLibraryMounter.DexLibrary>()
    private val dexLibrariesCache = linkedMapOf<String, List<DexLibraryMounter.DexLibrary>>()

    private fun loadClassOrNull(className: String, classLoader: ClassLoader): Class<*>? {
        val key = className + '#' + System.identityHashCode(classLoader)
        if (classLoadMisses.containsKey(key)) {
            return null
        }
        val loaded = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
        if (loaded == null) {
            classLoadMisses[key] = true
        }
        return loaded
    }

    fun providersFor(metadata: Map<String, String>): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        return providersFor(JvmWorkspaceConfiguration.fromMetadata(metadata))
    }

    fun providersFor(configuration: JvmWorkspaceConfiguration): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        val requested = requestedClassLoads(normalized, classLoader)
        val bareDexTargets = bareDexLibraryTargets(normalized)
        if (requested.isEmpty() && bareDexTargets.isEmpty()) {
            return emptyMap()
        }

        val providers = linkedMapOf<VirtualPath, WorkspaceSnapshot.FileSnapshot>()
        requested.forEach { request ->
            // Never invent framework members: only mount classes that Class.forName can
            // load — or that a configured/imported dex library actually declares (dex
            // classes cannot load reflectively; they mount through DexClassModelAdapter).
            val provider = request.dexLibrary
                ?.let { dex ->
                    dex.classForBinaryName(request.className.replace('.', '/'))
                        ?.let { cls -> providerForDexClass(dex, cls) }
                }
                ?: loadClassOrNull(request.className, request.classLoader)?.let(::providerForClass)
            if (provider != null) {
                providers[provider.first] = provider.second
            }
        }
        // Bare `import "libs/classes.dex"` targets mount the library module plus a
        // provider per declared class (the packageMemberClassProvidersFor pattern) so
        // simple-name aliases resolve through the workspace provider graph.
        bareDexTargets.forEach { target -> mountDexLibraryProviders(target, providers) }
        return providers
    }

    /**
     * Mount providers for bare dex library import targets (`import "libs/classes.dex"`).
     *
     * Fed by [JvmWorkspaceEngine.extraProviders] with configured imports, document
     * source imports and AST import targets; this provider re-filters to bare,
     * existing, mountable dex/apk paths. Workspace `libs/` directories are never
     * auto-scanned — a dex library mounts only when explicitly named (classpath
     * entry or import target), matching the existing reflective classpath contract.
     */
    internal fun dexLibraryProvidersFor(
        importTargets: Collection<String>,
        configuration: JvmWorkspaceConfiguration
    ): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        configuration.normalized(defaultImportPrefixes)
        val providers = linkedMapOf<VirtualPath, WorkspaceSnapshot.FileSnapshot>()
        importTargets.forEach { importText ->
            val parsed = parseImportTarget(importText) ?: return@forEach
            if (parsed.pathPrefix != null) {
                return@forEach
            }
            mountDexLibraryProviders(parsed.className, providers)
        }
        return providers
    }

    private fun mountDexLibraryProviders(
        target: String,
        providers: MutableMap<VirtualPath, WorkspaceSnapshot.FileSnapshot>
    ) {
        val library = bareDexLibrary(target)?.let { DexLibraryMounter.libraryFor(it) } ?: return
        registerDexLibrary(library)
        val (libraryPath, librarySnapshot) = providerForDexLibrary(library, target.trim())
        providers.putIfAbsent(libraryPath, librarySnapshot)
        library.classes.forEach { cls ->
            val (classPath, classSnapshot) = providerForDexClass(library, cls)
            providers.putIfAbsent(classPath, classSnapshot)
        }
    }

    /**
     * Package-list surface for wildcard targets only (`pkg.*`).
     *
     * Keys are stable package virtual paths under `__jvm__/packages/<segments>.lua`.
     * Class modules (`__jvm__/classes/...`) are never package-list keys — they are mounted
     * by [packageMemberClassProvidersFor] / [providersFor] on class-resolution surfaces.
     *
     * Non-wildcard package-name aliases, FQCN class targets, and blank/malformed inputs
     * yield no package-list entries (and never throw).
     */
    internal fun packageProvidersFor(
        importTargets: Collection<String>,
        configuration: JvmWorkspaceConfiguration
    ): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        // Always prefer a reflective classpath loader for package wildcards so host
        // android.jar enumeration is not blocked by a caller-supplied ClassLoader that
        // cannot see the configured jar. Caller classLoader is used only as the parent.
        val classLoader = classLoaderForPackageEnumeration(normalized)
        val providers = linkedMapOf<VirtualPath, WorkspaceSnapshot.FileSnapshot>()
        val visitedPackages = linkedSetOf<String>()
        importTargets.forEach { importText ->
            // Wildcard-only listing: `android.widget.*` / `java.util.concurrent.*`.
            // Package-name aliases and class FQCNs stay off the package-list key set.
            val packageName = wildcardPackageName(importText) ?: return@forEach
            if (!visitedPackages.add(packageName)) {
                return@forEach
            }
            val packageProvider = packageProviderFor(packageName, normalized, classLoader) ?: return@forEach
            providers[packageProvider.first] = packageProvider.second
        }
        return providers
    }

    /**
     * Shallow class providers for members of wildcard package targets.
     *
     * Used by [JvmWorkspaceEngine] so wildcard imports can resolve identifiers
     * (TextView/File) without deep hierarchy expand of every framework type.
     * Paths are always `__jvm__/classes/...` — never mixed into package-list keys.
     */
    internal fun packageMemberClassProvidersFor(
        importTargets: Collection<String>,
        configuration: JvmWorkspaceConfiguration
    ): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = classLoaderForPackageEnumeration(normalized)
        val providers = linkedMapOf<VirtualPath, WorkspaceSnapshot.FileSnapshot>()
        val visitedPackages = linkedSetOf<String>()
        importTargets.forEach { importText ->
            // Accept both `pkg.*` wildcards and package-name aliases so engine-normalized
            // targets and raw alias forms both mount shallow class modules.
            val packageName = wildcardPackageName(importText)
                ?: packageNameFromImport(importText, normalized, classLoader)
                ?: return@forEach
            if (!visitedPackages.add(packageName)) {
                return@forEach
            }
            resolvedPackageClasses(packageName, classLoader, normalized).forEach { clazz ->
                val (path, snapshot) = shallowProviderForClass(clazz)
                providers.putIfAbsent(path, snapshot)
            }
        }
        return providers
    }

    internal fun requestedClasses(configuration: JvmWorkspaceConfiguration): Set<String> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        return requestedClassLoads(normalized, normalized.classLoader ?: classLoaderFor(normalized))
            .mapTo(linkedSetOf()) { it.className }
    }

    internal fun importedClassName(importText: String, configuration: JvmWorkspaceConfiguration): String? {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        return resolveImports(importText, normalized.importPrefixes, classLoader, normalized).firstOrNull()
    }

    internal fun importedClassNames(importText: String, configuration: JvmWorkspaceConfiguration): List<String> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        // Heap bound: never expand wildcard/package targets into full class lists for
        // configuration.classes / source discovery. Package members are mounted via
        // packageProvidersFor (package paths) + packageMemberClassProvidersFor (class paths).
        val parsed = parseImportTarget(importText)?.className ?: return emptyList()
        if (parsed.endsWith(".*")) {
            return emptyList()
        }
        return resolveImports(importText, normalized.importPrefixes, classLoader, normalized)
    }

    internal fun importDiagnostics(configuration: JvmWorkspaceConfiguration): List<ImportDiagnostic> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        return distinctImportDiagnostics(normalized.classes + normalized.androluaImports, normalized)
    }

    internal fun importDiagnostics(
        importTargets: Collection<String>,
        configuration: JvmWorkspaceConfiguration
    ): List<ImportDiagnostic> {
        return distinctImportDiagnostics(importTargets, configuration.normalized(defaultImportPrefixes))
    }

    /** Shared body behind both [importDiagnostics] overloads (already-normalized config). */
    private fun distinctImportDiagnostics(
        importTargets: Collection<String>,
        normalized: JvmWorkspaceConfiguration
    ): List<ImportDiagnostic> {
        return importTargets
            .mapNotNull { importDiagnostic(it, normalized) }
            .distinctBy { diagnostic ->
                listOf(diagnostic.importText, diagnostic.pathPrefix, diagnostic.className, diagnostic.code)
            }
    }

    internal fun importedSymbolForTarget(importText: String, configuration: JvmWorkspaceConfiguration): WorkspaceImportedSymbol? {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = classLoaderForPackageEnumeration(normalized)
        // 0) bare dex library target (`import "libs/classes.dex"`) → library module symbol
        //    with every declared class keyed by simple name.
        importedDexLibrarySymbol(importText, normalized)?.let { return it }
        // 1) wildcard package  2) loadable class  3) package-name alias when package enumerates.
        return when {
            wildcardPackageName(importText) != null -> importedPackageSymbol(importText, normalized, classLoader)
            else -> importedClassSymbol(importText, normalized, classLoader)
                ?: importedPackageSymbol(importText, normalized, classLoader)
        }
    }

    internal fun importedSymbol(importText: String, configuration: JvmWorkspaceConfiguration): WorkspaceImportedSymbol? {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        return importedClassSymbol(importText, normalized, classLoader)
    }

    private fun importedClassSymbol(
        importText: String,
        configuration: JvmWorkspaceConfiguration,
        classLoader: ClassLoader
    ): WorkspaceImportedSymbol? {
        // Prefer a single class resolution; package-name targets with many classes fall through.
        val loads = resolveClassLoads(
            importText = importText,
            importPrefixes = configuration.importPrefixes,
            classLoader = classLoader,
            configuration = configuration,
            allowPackageEnumeration = false
        )
        val request = loads.firstOrNull() ?: return null
        // Dex classes cannot Class.forName: mount through the parsed dex library instead.
        request.dexLibrary?.let { dex ->
            val cls = dex.classForBinaryName(request.className.replace('.', '/')) ?: return null
            registerDexLibrary(dex)
            val moduleType = dex.moduleTypeFor(cls)
            val (providerPath, _) = providerForDexClass(dex, cls)
            return WorkspaceImportedSymbol(
                // Alias by the std-collision-guarded module name so a dex `R$string`
                // class never activates the bare "string" Lua std alias.
                alias = moduleType.moduleName,
                moduleName = moduleType.moduleName,
                providerPath = providerPath,
                moduleType = moduleType
            )
        }
        val clazz = loadClassOrNull(request.className, request.classLoader) ?: return null
        val (providerPath, _) = providerForClass(clazz)
        return WorkspaceImportedSymbol(
            alias = clazz.simpleName,
            moduleName = clazz.simpleName,
            providerPath = providerPath,
            moduleType = moduleTypeFor(clazz)
        )
    }

    /**
     * Bare dex library import target (`import "libs/classes.dex"`): mounts the library
     * module (fields keyed by class simple name) and registers the library so later
     * dotted/simple-name targets resolve against it. Prefix forms
     * (`libs/classes.dex:com.foo.Bar`) resolve per-class via [resolveDexClassLoad] instead.
     */
    private fun importedDexLibrarySymbol(
        importText: String,
        configuration: JvmWorkspaceConfiguration
    ): WorkspaceImportedSymbol? {
        val parsed = parseImportTarget(importText) ?: return null
        if (parsed.pathPrefix != null) {
            return null
        }
        val library = bareDexLibrary(parsed.className)?.let { DexLibraryMounter.libraryFor(it) } ?: return null
        registerDexLibrary(library)
        val (providerPath, snapshot) = providerForDexLibrary(library, parsed.className.trim())
        val moduleType = snapshot.moduleExportSurface?.moduleType ?: return null
        return WorkspaceImportedSymbol(
            alias = moduleType.moduleName,
            moduleName = moduleType.moduleName,
            providerPath = providerPath,
            moduleType = moduleType
        )
    }

    private fun importedPackageSymbol(
        importText: String,
        configuration: JvmWorkspaceConfiguration,
        classLoader: ClassLoader
    ): WorkspaceImportedSymbol? {
        val packageName = packageNameFromImport(importText, configuration, classLoader) ?: return null
        val classes = resolvedPackageClasses(packageName, classLoader, configuration)
        if (classes.isEmpty()) {
            return null
        }
        val moduleType = packageModuleTypeFor(packageName, classes)
        val (providerPath, _) = providerForPackage(packageName, moduleType)
        return WorkspaceImportedSymbol(
            // Key the activation alias by the FULL package name. A short
            // substringBeforeLast('.') alias ("widget") collides silently when two wildcard
            // packages share the last segment (android.widget.* vs android.support.v7.widget.*,
            // demo main.lua) — last-wins would drop the other package symbol. The resolver's
            // importedPackageSymbol already aliases by the full package name; match it here.
            alias = packageName,
            moduleName = moduleType.moduleName,
            providerPath = providerPath,
            moduleType = moduleType
        )
    }

    private fun packageProviderFor(
        packageName: String,
        configuration: JvmWorkspaceConfiguration,
        classLoader: ClassLoader
    ): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot>? {
        // Include reflective classpath + loader identity so package modules from distinct
        // classpath configurations never share a name-only cache entry.
        val cacheKey = buildString {
            append(packageName)
            append('#')
            append(System.identityHashCode(classLoader))
            append('#')
            reflectiveClasspathFiles(configuration).forEach { entry ->
                append(entry.absolutePath)
                append(';')
            }
        }
        packageProviderCache[cacheKey]?.let { return it }
        val classes = resolvedPackageClasses(packageName, classLoader, configuration)
        if (classes.isEmpty()) {
            return null
        }
        val provider = providerForPackage(packageName, packageModuleTypeFor(packageName, classes))
        packageProviderCache[cacheKey] = provider
        return provider
    }

    private fun requestedClassLoads(
        configuration: JvmWorkspaceConfiguration,
        classLoader: ClassLoader
    ): List<ResolvedClassLoad> {
        val explicit = configuration.classes.flatMap { className ->
            resolveClassLoads(className, configuration.importPrefixes, classLoader, configuration)
        }
        val imports = configuration.androluaImports
            .flatMap { resolveClassLoads(it, configuration.importPrefixes, classLoader, configuration) }
        return (explicit + imports)
            .distinctBy { it.className }
    }

    private fun resolveImports(
        importText: String,
        importPrefixes: List<String>,
        classLoader: ClassLoader,
        configuration: JvmWorkspaceConfiguration
    ): List<String> {
        return resolveClassLoads(importText, importPrefixes, classLoader, configuration)
            .map { it.className }
    }

    private fun resolveClassLoads(
        importText: String,
        importPrefixes: List<String>,
        classLoader: ClassLoader,
        configuration: JvmWorkspaceConfiguration,
        allowPackageEnumeration: Boolean = false
    ): List<ResolvedClassLoad> {
        val parsed = parseImportTarget(importText) ?: return emptyList()
        val targetClassLoader = classLoaderForImportTarget(parsed, configuration, classLoader) ?: return emptyList()
        val target = parsed.className
        if (target.isEmpty()) {
            return emptyList()
        }
        if (target.endsWith(".*")) {
            return resolveWildcardImports(target.removeSuffix(".*"), targetClassLoader, configuration)
                .map { ResolvedClassLoad(it, targetClassLoader) }
        }
        if ('.' in target) {
            val asClass = candidateClassNames(target).firstNotNullOfOrNull { candidate ->
                runCatching { Class.forName(candidate, false, targetClassLoader) }
                    .getOrNull()
                    ?.name
                    ?.let { listOf(ResolvedClassLoad(it, targetClassLoader)) }
            }
            if (asClass != null) {
                return asClass
            }
            // Dex classes cannot Class.forName: resolve dotted targets against the
            // prefix-named dex library first (`libs/classes.dex:com.foo.Bar`), then every
            // visible dex library (classpath entries + bare-imported libraries).
            // Reflection keeps precedence — this only runs after every Class.forName miss.
            resolveDexClassLoad(parsed, target, configuration)?.let { return listOf(it) }
            // Package-name alias support (import "android.widget") only when explicitly allowed.
            // Default class-load paths keep this off so providersFor does not reflect whole packages.
            if (allowPackageEnumeration) {
                return resolveWildcardImports(target, targetClassLoader, configuration)
                    .map { ResolvedClassLoad(it, targetClassLoader) }
            }
            return emptyList()
        }
        // Short AndroLua names under import prefixes:
        // - OnClickListener / Entry / BindServiceFlags with enclosing-type prefix
        //   (android.view.View + OnClickListener → View$OnClickListener)
        // - View_OnClickListener / Map_Entry style underscore aliases with package prefix
        //   (android.view + View_OnClickListener → android.view.View$OnClickListener via
        //   candidateClassNames underscore→$ rewrite)
        // Prefer binary `$` join first so host android.jar nested types resolve before
        // non-loadable pure-dotted Class.forName attempts.
        return importPrefixes.firstNotNullOfOrNull { prefix ->
            val trimmedPrefix = prefix.trim()
            if (trimmedPrefix.isEmpty()) {
                return@firstNotNullOfOrNull null
            }
            val shortNameCandidates = shortNameClassCandidates(trimmedPrefix, target)
            shortNameCandidates.firstNotNullOfOrNull { candidate ->
                runCatching { Class.forName(candidate, false, targetClassLoader) }
                    .getOrNull()
                    ?.name
                    ?.let { listOf(ResolvedClassLoad(it, targetClassLoader)) }
            }
        }.orEmpty().ifEmpty {
            // Dex simple-name fallback for short AndroLua names (`bindClass("Greeter")`
            // after `import "libs/classes.dex"`): unambiguous bare aliases only, never
            // Lua std module names.
            listOfNotNull(resolveDexSimpleNameLoad(target, configuration))
        }
    }

    /**
     * Dotted-target dex resolution: a dex library path prefix scopes the lookup to that
     * library (`libs/classes.dex:com.foo.Bar`); otherwise every visible dex library
     * ([dexLibrariesFor]) is consulted. Returns null when the target declares nothing.
     */
    private fun resolveDexClassLoad(
        parsed: ImportTarget,
        target: String,
        configuration: JvmWorkspaceConfiguration
    ): ResolvedClassLoad? {
        parsed.pathPrefix?.trim()?.takeIf(String::isNotEmpty)?.let { prefix ->
            DexLibraryMounter.libraryFor(File(prefix))?.let { scoped ->
                scoped.classForTarget(target)?.let { cls ->
                    return dexResolvedClassLoad(cls, scoped)
                }
            }
        }
        return dexLibrariesFor(configuration).firstNotNullOfOrNull { library ->
            library.classForTarget(target)?.let { cls -> dexResolvedClassLoad(cls, library) }
        }
    }

    /**
     * Short-name dex resolution (no dots/slashes): unambiguous simple aliases only;
     * Lua std module names never resolve bare (string/io/... stay library names).
     */
    private fun resolveDexSimpleNameLoad(
        target: String,
        configuration: JvmWorkspaceConfiguration
    ): ResolvedClassLoad? {
        if (target.isBlank() || '.' in target || '/' in target) {
            return null
        }
        return dexLibrariesFor(configuration).firstNotNullOfOrNull { library ->
            library.classForSimpleAlias(target)?.let { cls -> dexResolvedClassLoad(cls, library) }
        }
    }

    /** Dex loads key [ResolvedClassLoad.className] by the dotted binary name. */
    private fun dexResolvedClassLoad(
        cls: DexClass,
        library: DexLibraryMounter.DexLibrary
    ): ResolvedClassLoad {
        return ResolvedClassLoad(
            className = cls.binaryName.replace('/', '.'),
            classLoader = baseClassLoader,
            dexLibrary = library
        )
    }

    /**
     * Bare dex library path target (`libs/classes.dex`, absolute `.apk` path), or null.
     * Bare = no `path:Class` separator; the extension must be dex/apk and the file must
     * exist. Workspace `libs/` directories are never auto-scanned by path — dex libraries
     * mount only when explicitly named (mirrors the reflective classpath contract).
     */
    private fun bareDexLibrary(target: String): File? {
        val trimmed = target.trim()
        if (trimmed.isEmpty() || ':' in trimmed) {
            return null
        }
        val file = File(trimmed)
        return file.takeIf(DexLibraryMounter::isMountableDexLibrary)
    }

    private fun bareDexLibraryTargets(normalized: JvmWorkspaceConfiguration): List<String> {
        return (normalized.classes.asSequence() + normalized.androluaImports.asSequence())
            .filter { bareDexLibrary(it) != null }
            .map(String::trim)
            .distinct()
            .toList()
    }

    private fun registerDexLibrary(library: DexLibraryMounter.DexLibrary) {
        mountedDexLibraries.putIfAbsent(library.cacheIdentity, library)
    }

    /**
     * Dex libraries visible to resolution: configured classpath dex/apk entries, bare
     * dex paths named in classes/androluaImports, plus libraries registered by bare
     * `import "libs/classes.dex"` source imports during this provider instance.
     */
    private fun dexLibrariesFor(configuration: JvmWorkspaceConfiguration): List<DexLibraryMounter.DexLibrary> {
        val cacheKey = buildString {
            configuration.classpathEntries.forEach { append(it).append(';') }
            append('#')
            configuration.classes.forEach { append(it).append(';') }
            append('#')
            configuration.androluaImports.forEach { append(it).append(';') }
        }
        dexLibrariesCache[cacheKey]?.let { configured ->
            return (configured + mountedDexLibraries.values).distinctBy(DexLibraryMounter.DexLibrary::cacheIdentity)
        }
        val configured = buildList {
            configuration.classpathEntries.forEach { entry ->
                DexLibraryMounter.libraryFor(File(entry.trim()))?.let(::add)
            }
            (configuration.classes + configuration.androluaImports).forEach { target ->
                bareDexLibrary(target)?.let { path -> DexLibraryMounter.libraryFor(path)?.let(::add) }
            }
        }.distinctBy(DexLibraryMounter.DexLibrary::cacheIdentity)
        dexLibrariesCache[cacheKey] = configured
        return (configured + mountedDexLibraries.values).distinctBy(DexLibraryMounter.DexLibrary::cacheIdentity)
    }

    /**
     * Expand AndroLua short-name import targets under a single import prefix.
     *
     * Handles:
     * - enclosing-type prefixes: `android.view.View` + `OnClickListener` →
     *   `android.view.View$OnClickListener` (binary `$` preferred)
     * - package prefixes + Outer_Inner short names: `android.view` + `View_OnClickListener` →
     *   `android.view.View$OnClickListener`
     * - package prefixes that already end in Outer: same as enclosing-type path
     *
     * Never invents loadable names; [Class.forName] remains the authority.
     */
    private fun shortNameClassCandidates(prefix: String, target: String): List<String> {
        if (target.isEmpty()) {
            return emptyList()
        }
        return buildList {
            val upperCamelTarget = target.first().isUpperCase()
            if (upperCamelTarget) {
                // Prefer binary `$` join for nested types (View$OnClickListener / Map$Entry).
                add("$prefix\$$target")
                add("${prefix}_$target")
                // Underscore-alias short names already encode Outer_Inner under a package prefix.
                if ('_' in target) {
                    val nestedBinary = target.replace('_', '$')
                    add("$prefix.$nestedBinary")
                    add("$prefix\$$nestedBinary")
                    // Also keep package.Outer.Inner dotted form for candidateClassNames rewrite.
                    add("$prefix.${target.replace('_', '.')}")
                }
                // When prefix is a package (android.view) and target is a nested simple name,
                // try common Outer$target patterns only via explicit Outer_Inner aliases above;
                // do not invent Outer type names here.
            }
            addAll(candidateClassNames("$prefix.$target"))
        }.distinct()
    }

    /**
     * Resolve a package name from either:
     * - wildcard form: `android.widget.*` / `import android.widget.*`
     * - package-name alias form: `android.widget` / `java.util` when the package enumerates
     *   real reflected classes and is not itself a loadable Class.
     *
     * Never invents package names: enumeration must yield at least one loadable class.
     */
    private fun packageNameFromImport(
        importText: String,
        configuration: JvmWorkspaceConfiguration,
        classLoader: ClassLoader
    ): String? {
        wildcardPackageName(importText)?.let { return it }

        val parsed = parseImportTarget(importText) ?: return null
        val target = parsed.className
        if (target.isBlank() || target.endsWith(".*")) {
            return null
        }
        // Package-name aliases are dotted (android.widget / java.util). Bare simple names are
        // class lookups under import prefixes, not package modules.
        if ('.' !in target) {
            return null
        }

        val targetClassLoader = classLoaderForImportTarget(parsed, configuration, classLoader) ?: classLoader
        val isLoadableClass = candidateClassNames(target).any { candidate ->
            runCatching { Class.forName(candidate, false, targetClassLoader) }.getOrNull() != null
        }
        // Fully-qualified class targets stay on the class path; package-name aliases must not
        // steal `import("android.widget.TextView")`.
        if (isLoadableClass) {
            return null
        }

        val classNames = resolveWildcardImports(target, targetClassLoader, configuration)
        return target.takeIf { classNames.isNotEmpty() }
    }

    /**
     * Load top-level classes for a package wildcard.
     *
     * Only public, non-inner, non-array, non-anonymous classes become package members.
     * Names discovered from jars/directories that fail [Class.forName] are dropped so
     * package modules never invent framework members that reflection cannot load.
     *
     * Product lock: android.app / android.content / android.view / android.widget wildcards
     * must surface Activity / Context / View / TextView when host android.jar is present.
     */
    private fun resolvedPackageClasses(
        packageName: String,
        classLoader: ClassLoader,
        configuration: JvmWorkspaceConfiguration
    ): List<Class<*>> {
        if (packageName.isBlank()) {
            return emptyList()
        }
        // Prefer a loader that can actually load classes from reflective classpath jars
        // (android.jar). When Class.forName fails on the caller's loader, retry with the
        // reflective classpath loader so jar enumeration is not a false empty surface.
        val reflectiveLoader = classLoaderFor(configuration)
        return resolveWildcardImports(packageName, classLoader, configuration)
            .mapNotNull { className ->
                runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                    ?: runCatching { Class.forName(className, false, reflectiveLoader) }.getOrNull()
            }
            .filter(::isTopLevelPackageMemberClass)
            .filter { clazz ->
                // Exact package membership only — never promote nested-package types.
                val ownerPackage = clazz.name.substringBeforeLast('.', missingDelimiterValue = "")
                ownerPackage == packageName
            }
            .distinctBy { it.name }
            .sortedBy { it.simpleName }
    }

    /**
     * Package wildcards expose only public top-level types from the exact package.
     * Inner/anonymous/local/array classes are never package fields (BufferType, VERSION, Theme, …).
     */
    private fun isTopLevelPackageMemberClass(clazz: Class<*>): Boolean {
        if (!Modifier.isPublic(clazz.modifiers)) {
            return false
        }
        if (clazz.isArray || clazz.isAnonymousClass || clazz.isLocalClass || clazz.isSynthetic) {
            return false
        }
        if (clazz.enclosingClass != null || clazz.declaringClass != null) {
            return false
        }
        // Defense-in-depth: binary names with '$' are nested even if reflection flags differ.
        if ('$' in clazz.name) {
            return false
        }
        return clazz.simpleName.isNotEmpty()
    }

    private fun importDiagnostic(
        importText: String,
        configuration: JvmWorkspaceConfiguration
    ): ImportDiagnostic? {
        val parsed = parseImportTarget(importText) ?: return null
        val pathPrefix = parsed.pathPrefix ?: return null
        val reason = configuration.prefixedImportUnsupportedReason(pathPrefix) ?: return null
        return ImportDiagnostic(
            importText = parsed.original,
            pathPrefix = pathPrefix,
            className = parsed.className,
            message = "Unsupported prefixed JVM import '${parsed.original}': ${reason.diagnosticDetail} " +
                "Preserved prefix '$pathPrefix' and class target '${parsed.className}' for diagnostics.",
            code = UNSUPPORTED_PREFIXED_IMPORT_CODE
        )
    }

    private fun classLoaderForImportTarget(
        importTarget: ImportTarget,
        configuration: JvmWorkspaceConfiguration,
        fallback: ClassLoader
    ): ClassLoader? {
        val pathPrefix = importTarget.pathPrefix ?: return fallback
        val entry = configuration.prefixedImportClasspathEntry(pathPrefix) ?: return fallback
        // Dex/apk prefix entries carry Dalvik bytecode a URLClassLoader cannot define:
        // the scoped lookup in resolveDexClassLoad resolves them against the fallback.
        if (DexLibraryMounter.isMountableDexLibrary(entry)) {
            return fallback
        }
        return cachedImportTargetClassLoader(entry, fallback) ?: fallback
    }

    /**
     * Cached loader for prefixed import targets (`/path/to.jar:com.example.Foo`).
     *
     * Leak bound (adversarial audit): this path used to construct a fresh, uncached,
     * never-closed [URLClassLoader] on every import resolution, leaking one loader (plus
     * every class it defined) per resolve and thrashing the reflected-class caches, which
     * key on loader identity. Loaders are now cached per (entry path, parent identity) in
     * [classLoaderCache] — the same policy as [classLoaderFor] and
     * [classLoaderForPackageEnumeration] — so the same jar path never produces a duplicate
     * loader for the same parent chain. Entries are intentionally never closed, including
     * when a provider instance is discarded: classes defined by these loaders may still be
     * live in cached module types and provider snapshots.
     */
    internal fun cachedImportTargetClassLoader(entry: File, parent: ClassLoader): ClassLoader? {
        val cacheKey = classLoaderCacheKey("importTarget", parent, listOf(entry))
        classLoaderCache[cacheKey]?.let { return it }
        val loader = runCatching { URLClassLoader(arrayOf(entry.toURI().toURL()), parent) }.getOrNull()
            ?: return null
        classLoaderCache[cacheKey] = loader
        return loader
    }

    private fun wildcardPackageName(importText: String): String? {
        val target = parseImportTarget(importText)?.className ?: return null
        if (!target.endsWith(".*")) {
            return null
        }
        return target.removeSuffix(".*").takeIf(String::isNotBlank)
    }

    private fun parseImportTarget(importText: String): ImportTarget? {
        val normalized = importText.removePrefix("import ").trim()
        if (normalized.isEmpty()) {
            return null
        }
        val separator = normalized.lastIndexOf(':')
        if (separator <= 0 || separator == normalized.lastIndex) {
            return ImportTarget(
                original = normalized,
                pathPrefix = null,
                className = normalized
            )
        }
        return ImportTarget(
            original = normalized,
            pathPrefix = normalized.substring(0, separator).trim(),
            className = normalized.substring(separator + 1).trim()
        )
    }

    private data class ImportTarget(
        val original: String,
        val pathPrefix: String?,
        val className: String
    )

    private data class ResolvedClassLoad(
        val className: String,
        val classLoader: ClassLoader,
        // Non-null when className resolved from a dex library (Class.forName can never
        // load it); providersFor/importedClassSymbol mount through DexClassModelAdapter.
        val dexLibrary: DexLibraryMounter.DexLibrary? = null
    )

    private fun packageModuleTypeFor(
        packageName: String,
        classes: List<Class<*>>
    ): ModuleType {
        // Stable simple-name fields for AndroLua package modules (Activity/Context/View/TextView).
        // When two public top-level types share a simpleName (should not happen in one package),
        // first-by-sorted-name wins; never invent names that reflection did not load.
        //
        // Heap bound (TASK-611): package members use shallow class modules (no recursive
        // super/interface graph expand). Instance surface still flattens public inherited
        // methods/fields so Button()/button. after wildcards exposes View APIs.
        // Full deep moduleTypeFor remains for explicitly requested/bindClass class providers.
        val members = linkedMapOf<String, Type>()
        classes.forEach { clazz ->
            val simpleName = clazz.simpleName
            if (simpleName.isEmpty() || simpleName in members) {
                return@forEach
            }
            // Enforce exact package membership so subpackage types never leak into parent wildcards.
            val ownerPackage = clazz.name.substringBeforeLast('.', missingDelimiterValue = "")
            if (ownerPackage != packageName) {
                return@forEach
            }
            val memberType = runCatching { shallowModuleTypeFor(clazz) }.getOrNull()
                ?: runCatching { JavaInstanceType(typeReferenceForJavaClass(clazz)) }.getOrNull()
                ?: return@forEach
            members[simpleName] = memberType
        }
        return ModuleType(
            moduleName = packageName,
            fields = members.toSortedMap(),
            indexSignature = ModuleType.IndexSignature(PrimitiveType.STRING, UnknownType)
        )
    }

    /**
     * Candidate binary names for AndroLua / LuaJava nested class aliases.
     *
     * Supports:
     * - binary `$` names: `java.util.Map$Entry`, `android.view.View$OnClickListener`,
     *   `android.content.Context$BindServiceFlags`
     * - dotted nested names: `java.util.Map.Entry`, `android.view.View.OnClickListener`,
     *   `android.content.Context.BindServiceFlags`
     * - underscore nested aliases: `java.util.Map_Entry`, `android.view.View_OnClickListener`,
     *   `android.content.Context_BindServiceFlags`
     * - prefix + short-name combinations for `importPrefixes` such as `android.view.View` +
     *   `OnClickListener` → `android.view.View$OnClickListener` or `android.content.Context` +
     *   `BindServiceFlags` → `android.content.Context$BindServiceFlags`
     *
     * Binary `$` candidates are preferred so host android.jar nested types resolve via the
     * loadable JVM name first (Class.forName rejects pure dotted nested forms).
     * Never invents non-loadable names; callers must still Class.forName each candidate.
     */
    private fun candidateClassNames(className: String): List<String> {
        val trimmed = className.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val separatorIndexes = trimmed.indices.filter { trimmed[it] == '.' }
        val candidates = buildSet {
            add(trimmed)
            // AndroLua underscore nested aliases: Map_Entry / View_OnClickListener /
            // Context_BindServiceFlags → Map$Entry / View$OnClickListener / Context$BindServiceFlags.
            // Only rewrite '_' segments that look like Outer_Inner (UpperCamel_UpperCamel), not
            // arbitrary package underscores.
            if ('_' in trimmed) {
                add(trimmed.replace('_', '$'))
                // Also allow mixed forms where only the final Outer_Inner segment is rewritten.
                val lastDot = trimmed.lastIndexOf('.')
                if (lastDot >= 0) {
                    val head = trimmed.substring(0, lastDot + 1)
                    val tail = trimmed.substring(lastDot + 1)
                    if ('_' in tail) {
                        add(head + tail.replace('_', '$'))
                    }
                }
            }
            // Dotted nested segments: Map.Entry / Context.BindServiceFlags → Map$Entry /
            // Context$BindServiceFlags by selectively replacing '.' with '$'
            if (separatorIndexes.isNotEmpty()) {
                val combinations = 1 shl separatorIndexes.size
                for (mask in 1 until combinations) {
                    val chars = trimmed.toCharArray()
                    separatorIndexes.forEachIndexed { index, separator ->
                        if ((mask and (1 shl index)) != 0) {
                            chars[separator] = '$'
                        }
                    }
                    add(String(chars))
                }
            }
        }
        // Prefer binary `$` nested forms first so dotted Android nested types
        // (Context.BindServiceFlags / View.OnClickListener) resolve without relying on
        // non-loadable pure-dotted Class.forName attempts.
        return candidates.sortedWith(
            compareBy<String> { candidate ->
                when {
                    '$' in candidate -> 0
                    candidate == trimmed -> 1
                    else -> 2
                }
            }.thenBy { it }
        )
    }

    /**
     * Enumerate top-level class binary names under [packageName].
     *
     * Sources (union, never invents):
     * 1. [ClassLoader.getResources] for the package path (file/jar/jrt URLs)
     * 2. JRT modules (JDK packages)
     * 3. Explicit [JvmWorkspaceConfiguration.reflectionClasspathEntries] jars/dirs
     *    (critical for host android.jar when URLClassLoader resource scanning is flaky)
     *
     * Only immediate package children are returned (no subpackages). Inner classes
     * (`$` in the simple name) are excluded so wildcards never surface BufferType /
     * VERSION / Theme as top-level package fields.
     *
     * Host product lock packages: android.app, android.content, android.view, android.widget
     * must enumerate Activity, Context, View, TextView when android.jar is present.
     */
    private fun resolveWildcardImports(
        packageName: String,
        classLoader: ClassLoader,
        configuration: JvmWorkspaceConfiguration
    ): List<String> {
        if (packageName.isBlank()) {
            return emptyList()
        }
        val cacheKey = buildString {
            append(packageName)
            append('#')
            reflectiveClasspathFiles(configuration).forEach { entry ->
                append(entry.absolutePath)
                append(';')
            }
        }
        wildcardClassNameCache[cacheKey]?.let { return it }
        val path = packageName.replace('.', '/')
        val classNames = linkedSetOf<String>()
        val resources = runCatching { classLoader.getResources(path) }.getOrNull()
        if (resources != null) {
            while (resources.hasMoreElements()) {
                val url = resources.nextElement()
                when (url.protocol) {
                    "file" -> collectClassesFromDirectory(url.path, packageName, classNames)
                    "jar" -> collectClassesFromJar(url, path, classNames)
                    "jrt" -> collectClassesFromJrt(path, classNames)
                }
            }
        }
        collectClassesFromJrt(path, classNames)
        // Always scan reflective classpath jars/dirs directly. android.jar package
        // enumeration must not depend solely on ClassLoader resource URLs (some hosts
        // fail to list package directory entries for platform jars).
        reflectiveClasspathFiles(configuration).forEach { entry ->
            collectClassesFromClasspathEntry(entry, path, packageName, classNames)
        }
        val resolved = classNames
            .asSequence()
            .filter { binaryName -> isTopLevelPackageBinaryName(binaryName, packageName) }
            .sorted()
            .toList()
        wildcardClassNameCache[cacheKey] = resolved
        return resolved
    }

    /**
     * Binary-name gate for package wildcards: exact package, no '$' nested segment,
     * non-blank simple name (skips package-info / malformed entries).
     */
    private fun isTopLevelPackageBinaryName(binaryName: String, packageName: String): Boolean {
        if (binaryName.isBlank() || '$' in binaryName) {
            return false
        }
        val lastDot = binaryName.lastIndexOf('.')
        if (lastDot <= 0 || lastDot == binaryName.lastIndex) {
            return false
        }
        val ownerPackage = binaryName.substring(0, lastDot)
        val simpleName = binaryName.substring(lastDot + 1)
        return ownerPackage == packageName &&
            simpleName.isNotEmpty() &&
            simpleName != "package-info" &&
            simpleName != "module-info"
    }

    private fun collectClassesFromDirectory(directoryPath: String, packageName: String, output: MutableSet<String>) {
        val root = runCatching { Paths.get(URI.create("file:$directoryPath")) }
            .recoverCatching { Paths.get(directoryPath) }
            .getOrNull()
            ?: return
        if (!Files.isDirectory(root)) {
            return
        }
        runCatching {
            Files.list(root).use { entries ->
                entries
                    .filter { entry -> Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".class") }
                    .forEach { entry ->
                        val fileName = entry.fileName.toString().removeSuffix(".class")
                        if ('$' !in fileName && fileName.isNotEmpty()) {
                            output += "$packageName.$fileName"
                        }
                    }
            }
        }
    }

    private fun collectClassesFromClasspathEntry(
        entry: File,
        packagePath: String,
        packageName: String,
        output: MutableSet<String>
    ) {
        when {
            entry.isDirectory -> collectClassesFromDirectory(
                entry.toPath().resolve(packagePath).toString(),
                packageName,
                output
            )
            entry.isFile && entry.extension.equals("jar", ignoreCase = true) ->
                collectClassesFromJarFile(entry, packagePath, output)
        }
    }

    private fun collectClassesFromJar(url: java.net.URL, packagePath: String, output: MutableSet<String>) {
        // Prefer decoding the jar file path and opening a dedicated JarFile so we never
        // close a ClassLoader-shared JarURLConnection cache (closing it can break later
        // Class.forName loads of Activity/Context/View/TextView from the same loader).
        val jarPath = jarFilePathFromUrl(url)
        if (jarPath != null) {
            collectClassesFromJarFile(File(jarPath), packagePath, output)
            return
        }
        val connection = runCatching { url.openConnection() as JarURLConnection }.getOrNull() ?: return
        // Do NOT close connection.jarFile — it may be cached/shared by the URLClassLoader.
        val jarFile = runCatching { connection.jarFile }.getOrNull() ?: return
        collectClassesFromJarEntries(jarFile, packagePath, output)
    }

    /**
     * Extract a filesystem path from `jar:file:/...!/package` or plain `file:` URLs.
     * Returns null for non-file jar protocols so callers can fall back to JarURLConnection.
     */
    private fun jarFilePathFromUrl(url: java.net.URL): String? {
        return runCatching {
            when (url.protocol) {
                "jar" -> {
                    val connection = url.openConnection() as? JarURLConnection
                    val fileUrl = connection?.jarFileURL
                    if (fileUrl != null && fileUrl.protocol == "file") {
                        return@runCatching Paths.get(fileUrl.toURI()).toFile().path
                    }
                    val spec = url.file // e.g. file:/path/to.jar!/android/app
                    val bang = spec.indexOf('!')
                    val jarPart = if (bang >= 0) spec.substring(0, bang) else spec
                    val fileUri = if (jarPart.startsWith("file:")) jarPart else "file:$jarPart"
                    Paths.get(URI.create(fileUri)).toFile().path
                }
                "file" -> Paths.get(url.toURI()).toFile().path
                else -> null
            }
        }.getOrNull()?.takeIf { path -> path.isNotBlank() && File(path).isFile }
    }

    private fun collectClassesFromJarFile(file: File, packagePath: String, output: MutableSet<String>) {
        if (!file.isFile) {
            return
        }
        runCatching {
            JarFile(file).use { jar -> collectClassesFromJarEntries(jar, packagePath, output) }
        }
    }

    private fun collectClassesFromJarEntries(jar: JarFile, packagePath: String, output: MutableSet<String>) {
        val prefix = "$packagePath/"
        jar.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".class") }
            .map { it.name.removeSuffix(".class") }
            // Immediate children only: no subpackages (android.content.pm under android.content.*).
            .filter { name -> '/' !in name.removePrefix(prefix) }
            // Skip nested/inner binary names (TextView$BufferType, View$OnClickListener, …).
            .filter { '$' !in it.substringAfterLast('/') }
            .forEach { entry -> output += entry.replace('/', '.') }
    }

    private fun collectClassesFromJrt(packagePath: String, output: MutableSet<String>) {
        val root = runCatching { Paths.get(URI.create("jrt:/modules")) }.getOrNull() ?: return
        if (!Files.isDirectory(root)) {
            return
        }
        runCatching {
            Files.list(root).use { modules ->
                modules.forEach { module ->
                    val packageDir = module.resolve(packagePath)
                    if (!Files.isDirectory(packageDir)) {
                        return@forEach
                    }
                    runCatching {
                        Files.list(packageDir).use { entries ->
                            entries
                                .filter { entry -> Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".class") }
                                .forEach { entry ->
                                    val fileName = entry.fileName.toString().removeSuffix(".class")
                                    if ('$' !in fileName && fileName.isNotEmpty()) {
                                        output += "${packagePath.replace('/', '.')}.$fileName"
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    /**
     * Existing reflective classpath files for ClassLoader + package enumeration, memoized per
     * provider instance (wave K perf; see [reflectiveClasspathFilesCache]).
     */
    private fun reflectiveClasspathFiles(configuration: JvmWorkspaceConfiguration): List<File> {
        val cacheKey = reflectiveClasspathCacheKey(configuration)
        reflectiveClasspathFilesCache[cacheKey]?.let { return it }
        return resolveReflectiveClasspathFiles(configuration)
            .also { resolved -> reflectiveClasspathFilesCache[cacheKey] = resolved }
    }

    /**
     * Cache identity for [reflectiveClasspathFiles]: the configuration fields that feed the
     * resolution (classpath entries, androidJar, and — via the soft-fallback gate — classes
     * and imports) plus the environment values discovery consults. System properties used by
     * discovery (user.home, os.name) are process-constant and need no key component.
     */
    private fun reflectiveClasspathCacheKey(configuration: JvmWorkspaceConfiguration): String {
        val environment = System.getenv()
        return buildString {
            append(configuration.classpathEntries.joinToString(";"))
            append('#')
            append(configuration.androidJar.orEmpty())
            append('#')
            append(configuration.classes.joinToString(";"))
            append('#')
            append(configuration.androluaImports.joinToString(";"))
            append('#')
            append(environment[JvmWorkspaceConfiguration.ANDROID_HOME_ENV].orEmpty())
            append('#')
            append(environment[JvmWorkspaceConfiguration.ANDROID_SDK_ROOT_ENV].orEmpty())
            append('#')
            append(environment[JvmWorkspaceConfiguration.LOCAL_APPDATA_ENV].orEmpty())
        }
    }

    /**
     * Bundled Android-Lua runtime classes (com.androlua.*, com.luajava.*, compiled from the
     * Android-Lua app sources, MIT license — see LICENSE attribution in the source repo).
     * Extracted once to a temp file so the reflective classloader can mount real runtime
     * members (LuaActivity.get/set/call/showToast/…) without host-specific configuration.
     * Null when the resource is absent (never invents classes).
     */
    /**
     * Instance view of the process-wide bundled runtime jar ([Companion.companionRuntimeJarRef]).
     * Provider instances are created per workspace update — the shared companion lazy
     * guarantees exactly one temp extraction per JVM, and reading it directly keeps that
     * single-extraction contract (the old instance-level `by lazy` wrapper was a pure
     * pass-through with no additional memoization).
     *
     * Test surface: two provider instances must observe the SAME extracted jar file.
     */
    internal fun bundledRuntimeJarForDiagnostics(): File? = companionRuntimeJarRef

    /**
     * Existing reflective classpath files for ClassLoader + package enumeration.
     *
     * Uses [JvmWorkspaceConfiguration.reflectionClasspathEntries] first. When an explicit
     * jvm.androidJar metadata path is missing on disk (common with Windows-only G: fixtures
     * on macOS hosts, macOS Library/Android/sdk absolute paths on Windows CI, or a missing
     * Windows AppData android-35 hard-lock candidate), soft-falls back to host SDK discovery
     * via [JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath] so nested AndroLua
     * aliases (View$OnClickListener, Map$Entry) still resolve when another host jar is present.
     *
     * Never invents framework classes: only existing directories/jars are returned.
     * Never hard-requires G:, a missing AppData android-35 path alone, or a macOS-only
     * absolute path.
     */
    private fun resolveReflectiveClasspathFiles(configuration: JvmWorkspaceConfiguration): List<File> {
        val entries = mutableListOf<File>()
        // The bundled runtime always leads the classpath: it carries the AndroLua-facing
        // helpers (LuaActivity.get/set/call, LuaService, luajava) that android.jar lacks.
        companionRuntimeJarRef?.let(entries::add)
        entries += configuration.reflectionClasspathEntries()
            .map(::File)
            .filter { entry ->
                entry.isDirectory || (entry.isFile && entry.extension.equals("jar", ignoreCase = true))
            }
        val hasAndroidJar = entries.any { entry ->
            entry.isFile && entry.name.equals("android.jar", ignoreCase = true)
        }
        if (!hasAndroidJar && shouldSoftFallbackToHostAndroidJar(configuration)) {
            // Foreign host fixtures / missing well-known dual-path candidates soft-fall back
            // to host SDK discovery so nested AndroLua aliases still resolve when a real jar
            // exists elsewhere. Explicit missing non-well-known paths (isolation tests) stay
            // empty and never invent framework classes.
            JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()
                ?.let(::File)
                ?.takeIf { hostJar ->
                    hostJar.isFile && hostJar.extension.equals("jar", ignoreCase = true)
                }
                ?.let { hostJar -> entries += hostJar }
        }
        return entries.distinctBy { it.absolutePath }
    }

    internal fun shouldSoftFallbackToHostAndroidJar(configuration: JvmWorkspaceConfiguration): Boolean {
        val configuredJar = configuration.androidJar?.trim()?.takeIf(String::isNotEmpty)
        if (configuredJar.isNullOrBlank()) {
            // Host-jar substitution guard (adversarial audit): with no jvm.androidJar, the
            // host SDK jar is already mounted through the documented discovery inside
            // reflectionClasspathEntries(). This soft fallback therefore stays deliberate —
            // it applies only when the configuration actually carries JVM interop settings
            // (classpath entries, classes, or imports). A fully empty configuration must
            // not silently mount whatever host SDK jar happens to exist on the current
            // machine. Nondeterminism risk: which host jar (if any) discovery finds is
            // host-dependent (macOS ~/Library/Android/sdk vs Windows %LOCALAPPDATA% vs
            // none), so blank-jar surfaces differ across hosts; tests and workspaces that
            // need a stable surface must pin jvm.androidJar explicitly.
            return configuration.classpathEntries.isNotEmpty() ||
                configuration.classes.isNotEmpty() ||
                configuration.androluaImports.isNotEmpty()
        }
        if (File(configuredJar).isFile) {
            return false
        }
        val normalized = configuredJar.replace('\\', '/')
        // Soft-fallback only for known foreign host fixtures / dual-path hard-lock candidates
        // that may be missing on this OS while another host jar is present:
        // - documented Windows G: inventing root on non-G hosts
        // - macOS Library/Android/sdk absolute path when missing on Windows/Linux CI
        // - Windows AppData/Local/Android/Sdk android-35|34 candidates when absent on hosts
        //   that still have another present SDK root
        // Never invent jars for arbitrary missing paths (isolation tests must stay empty).
        val isWindowsDocumentedSdkPath = normalized.startsWith("G:/Android/Sdk", ignoreCase = true)
        val isMacLibrarySdkPath = normalized.contains("/Library/Android/sdk/", ignoreCase = true) ||
            normalized.contains("/Library/Android/sdk", ignoreCase = true)
        val isWindowsAppDataSdkPath =
            normalized.contains("/AppData/Local/Android/Sdk/", ignoreCase = true) ||
                normalized.contains("/AppData/Local/Android/sdk/", ignoreCase = true)
        val isWellKnownPlatformAndroidJar =
            normalized.endsWith("/platforms/android-35/android.jar", ignoreCase = true) ||
                normalized.endsWith("/platforms/android-34/android.jar", ignoreCase = true)
        return isWindowsDocumentedSdkPath ||
            isMacLibrarySdkPath ||
            isWindowsAppDataSdkPath ||
            (isWellKnownPlatformAndroidJar && (
                normalized.contains("/Android/Sdk/", ignoreCase = true) ||
                    normalized.contains("/Android/sdk/", ignoreCase = true) ||
                    normalized.contains("/Library/Android/", ignoreCase = true)
                ))
    }

    /**
     * ClassLoader used for package wildcard enumeration.
     *
     * Always mounts existing reflective classpath jars (host android.jar) so
     * `android.app.*` / `android.content.*` / `android.view.*` / `android.widget.*`
     * can Class.forName Activity/Context/View/TextView. Caller-supplied configuration
     * classLoaders become the parent chain only — they must not hide the jar.
     */
    private fun classLoaderForPackageEnumeration(configuration: JvmWorkspaceConfiguration): ClassLoader {
        return classLoaderFor(configuration, cacheKeyKind = "package")
    }

    private fun classLoaderFor(configuration: JvmWorkspaceConfiguration): ClassLoader {
        // Only existing reflective classpath entries are mounted. Missing android.jar paths
        // (including G:/ candidates) never invent framework classes; host SDK soft-fallback
        // mounts only when discovery finds a real jar (see reflectiveClasspathFiles).
        return classLoaderFor(configuration, cacheKeyKind = "class")
    }

    /** Shared loader construction behind [classLoaderFor] / [classLoaderForPackageEnumeration]. */
    private fun classLoaderFor(
        configuration: JvmWorkspaceConfiguration,
        cacheKeyKind: String
    ): ClassLoader {
        val parent = configuration.classLoader ?: baseClassLoader
        val entries = reflectiveClasspathFiles(configuration)
        val dexLoader = dexReflectionLoaderFor(configuration, parent)
        if (entries.isEmpty()) {
            // Device path: no jar/dir entries, but a configured framework dex
            // (android.jar converted by d8) reflects through DexClassLoader.
            return dexLoader ?: parent
        }
        val cacheKey = classLoaderCacheKey(cacheKeyKind, parent, entries)
        classLoaderCache[cacheKey]?.let { return it }
        val urls = entries
            .map(File::toURI)
            .map { it.toURL() }
            .toTypedArray()
        return URLClassLoader(urls, dexLoader ?: parent).also { classLoaderCache[cacheKey] = it }
    }

    /**
     * On an Android host, a configured `jvm.androidDex` (android.jar converted by
     * d8) reflects through DexClassLoader instead of URLClassLoader-over-jar:
     * parent delegation against the app classloader resolves the REAL framework
     * classes, so the entire reflection pipeline works unchanged.
     */
    private fun dexReflectionLoaderFor(
        configuration: JvmWorkspaceConfiguration,
        parent: ClassLoader
    ): ClassLoader? {
        val dexPath = configuration.androidDex?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val dexFile = File(dexPath)
        if (!dexFile.isFile) {
            return null
        }
        val cacheKey = "dex#$cacheKeyKind#${System.identityHashCode(parent)}#${dexFile.absolutePath}#${dexFile.lastModified()}"
        classLoaderCache[cacheKey]?.let { return it }
        val optimizedDir = File(System.getProperty("java.io.tmpdir"), "luaparser-dexopt")
            .apply { mkdirs() }
        val loader = AndroidDexClassLoaderFactory.create(
            listOf(dexFile.absolutePath), optimizedDir, parent
        ) ?: return null
        classLoaderCache[cacheKey] = loader
        return loader
    }

    private fun classLoaderCacheKey(kind: String, parent: ClassLoader, entries: List<File>): String {
        return buildString {
            append(kind)
            append('#')
            append(System.identityHashCode(parent))
            append('#')
            entries.forEach { entry ->
                append(entry.absolutePath)
                append(';')
            }
        }
    }

    private fun providerForClass(clazz: Class<*>): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        // Cache by defining ClassLoader + binary name so separate classpath configs that
        // load the same binary name (PluginMarker from alpha.jar vs beta.jar) stay isolated.
        val cacheKey = reflectedClassCacheKey(clazz)
        fullClassProviderCache[cacheKey]?.let { return it }
        val moduleType = moduleTypeFor(clazz)
        val provider = classProviderSnapshot(clazz, moduleType)
        fullClassProviderCache[cacheKey] = provider
        // Full providers supersede shallow ones for the same reflected class identity.
        shallowClassProviderCache[cacheKey] = provider
        return provider
    }

    private fun shallowProviderForClass(clazz: Class<*>): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val cacheKey = reflectedClassCacheKey(clazz)
        fullClassProviderCache[cacheKey]?.let { return it }
        shallowClassProviderCache[cacheKey]?.let { return it }
        val moduleType = shallowModuleTypeFor(clazz)
        val provider = classProviderSnapshot(clazz, moduleType)
        shallowClassProviderCache[cacheKey] = provider
        return provider
    }

    /**
     * Provider snapshot for one dex class, mounted at the same `__jvm__/classes/...`
     * path convention as reflected classes so workspace path recovery, export lookup
     * and the fingerprint machinery work unchanged. Module tables come from
     * [DexClassModelAdapter] (same `__class`/`__call`/static/instance shape).
     */
    private fun providerForDexClass(
        library: DexLibraryMounter.DexLibrary,
        cls: DexClass
    ): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val cacheKey = "dex#${library.cacheIdentity}#${cls.binaryName}"
        fullClassProviderCache[cacheKey]?.let { return it }
        val moduleType = library.moduleTypeFor(cls)
        val provider = moduleProviderSnapshot(
            providerPath = VirtualPath.of("__jvm__/classes/${cls.binaryName}.lua"),
            providerKind = "dex class",
            claimName = cls.binaryName.replace('/', '.'),
            providedModuleNames = linkedSetOf(moduleType.moduleName),
            moduleType = moduleType
        )
        fullClassProviderCache[cacheKey] = provider
        return provider
    }

    /**
     * Library module provider for a bare dex import target (`import "libs/classes.dex"`):
     * module fields keyed by class simple name → the class module table, mirroring the
     * package-module shape (first-by-sorted-binary-name wins simple-name collisions).
     * No `__class` field, so the resolver never mistakes it for a class provider.
     */
    private fun providerForDexLibrary(
        library: DexLibraryMounter.DexLibrary,
        target: String
    ): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val cacheKey = "dexlib#${library.cacheIdentity}#$target"
        packageProviderCache[cacheKey]?.let { return it }
        val moduleAlias = File(target).nameWithoutExtension.ifBlank { target }
        val members = linkedMapOf<String, Type>()
        library.classes.sortedBy(DexClass::binaryName).forEach { cls ->
            val simpleName = cls.binaryName.substringAfterLast('/')
            if (simpleName.isEmpty() || simpleName in members) {
                return@forEach
            }
            members[simpleName] = library.moduleTypeFor(cls)
        }
        val moduleType = ModuleType(
            moduleName = moduleAlias,
            fields = members.toSortedMap(),
            indexSignature = ModuleType.IndexSignature(PrimitiveType.STRING, UnknownType)
        )
        val provider = moduleProviderSnapshot(
            providerPath = VirtualPath.of("__jvm__/dex/$target.lua"),
            providerKind = "dex library",
            claimName = target,
            providedModuleNames = linkedSetOf(moduleAlias),
            moduleType = moduleType
        )
        packageProviderCache[cacheKey] = provider
        return provider
    }

    private fun classProviderSnapshot(clazz: Class<*>, moduleType: ModuleType): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        return moduleProviderSnapshot(
            providerPath = VirtualPath.of("__jvm__/classes/${clazz.name.replace('.', '/')}.lua"),
            providerKind = "class",
            claimName = clazz.name,
            providedModuleNames = reflectedClassProviderModuleNames(clazz),
            moduleType = moduleType
        )
    }

    /**
     * Module name for a reflected class provider. Nested Android resource classes reflect
     * with lowercase simple names (android.R$string → "string"); a bare claim of those
     * names outranks the Lua standard library overlay for the same module name and strips
     * every string.* member from completion/hover. Qualify only std-colliding nested
     * names ("R.string"); ordinary classes keep the bare simple name.
     */
    private fun classModuleSimpleName(clazz: Class<*>): String {
        val simple = clazz.simpleName
        val enclosing = clazz.enclosingClass ?: return simple
        return if (simple in LUA_STD_MODULE_NAMES) "${enclosing.simpleName}.$simple" else simple
    }

    /**
     * Module names advertised on a reflected class provider public fingerprint.
     *
     * Always the reflection simple name only (String / Locale / TextView / State / Entry /
     * String[]). Nested types such as Thread$State and Map$Entry must not expand binary,
     * dotted, or underscore aliases into providedModuleNames — that over-broad set broke
     * Thread.State fingerprint equality (expected {State}).
     *
     * Binary/dotted/underscore AndroLua aliases remain loadable via candidateClassNames and
     * path recovery; they are not fingerprint claims. Never invents names outside reflection.
     */
    private fun reflectedClassProviderModuleNames(clazz: Class<*>): Set<String> {
        val simple = clazz.simpleName.takeIf(String::isNotBlank)
            ?: clazz.name.substringAfterLast('$').substringAfterLast('.').takeIf(String::isNotBlank)
        return if (simple.isNullOrBlank()) {
            emptySet()
        } else {
            linkedSetOf(simple)
        }
    }

    private fun providerForPackage(
        packageName: String,
        moduleType: ModuleType
    ): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        return moduleProviderSnapshot(
            providerPath = VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua"),
            providerKind = "package",
            claimName = packageName,
            providedModuleNames = linkedSetOf(moduleType.moduleName),
            moduleType = moduleType
        )
    }

    /**
     * Shared reflected-provider snapshot builder behind [classProviderSnapshot] and
     * [providerForPackage]: identical ModuleExportSurface shape, fingerprint payload
     * (`claimName + displayName + sorted member triplets`) and cacheKey derivation;
     * only the virtual-path prefix, source comment, claim name and advertised module
     * names differ.
     */
    private fun moduleProviderSnapshot(
        providerPath: VirtualPath,
        providerKind: String,
        claimName: String,
        providedModuleNames: Set<String>,
        moduleType: ModuleType
    ): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val source = buildString {
            append("-- reflected JVM ")
            append(providerKind)
            append(" provider for ")
            append(claimName)
            append('\n')
        }
        val surface = ModuleExportSurface(
            moduleType = moduleType,
            sourceForm = ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL,
            members = moduleMembers(moduleType)
        )
        val fingerprintPayload = buildString {
            append(claimName)
            append('\n')
            append(moduleType.displayName)
            append('\n')
            append(surface.members.joinToString("|") { "${it.kind}:${it.name}:${it.type.displayName}" })
        }
        return providerPath to WorkspaceSnapshot.FileSnapshot(
            cacheKey = workspaceFingerprintHash(source),
            moduleExportSurface = surface,
            publicFingerprint = io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint(
                // Fingerprint module-name surface is the simple name only (Locale / State /
                // Entry / String[]). Nested binary/dotted/underscore aliases resolve via
                // Class.forName candidates and path recovery, not providedModuleNames.
                providedModuleNames = providedModuleNames,
                value = workspaceFingerprintHash(fingerprintPayload)
            )
        )
    }

    private fun moduleTypeFor(clazz: Class<*>): ModuleType {
        val cacheKey = reflectedClassCacheKey(clazz)
        moduleTypeCache[cacheKey]?.let { return it }
        // Reflection robustness (adversarial audit): a single jar method/field referencing a
        // missing type makes getMethods/getFields/generic-type resolution throw
        // NoClassDefFoundError. That must never propagate out of providersFor and abort the
        // whole workspace update — degrade this one class to a member-less cheap shell
        // (mirroring the shallow path in packageModuleTypeFor) and keep every other mounted
        // surface. No diagnostics channel is reachable from this provider, so the
        // degradation is intentionally silent; the failure mode is documented here.
        return runCatching { moduleTypeFor(clazz, emptySet(), 0) }
            .getOrElse { shellModuleTypeFor(clazz) }
            .also { moduleTypeCache[cacheKey] = it }
    }

    /**
     * Member-less cheap shell for classes whose deep reflective expansion failed
     * (NoClassDefFoundError / linkage errors from a broken jar entry).
     *
     * Mirrors the shallow fallback in [packageModuleTypeFor]: the module keeps only the
     * `__class` instance shell backed by a name-only [JavaClassType] (no members, no
     * constructors, no hierarchy), so the import/bindClass module and its provider path
     * still resolve without inventing members reflection cannot see.
     */
    internal fun shellModuleTypeFor(clazz: Class<*>): ModuleType {
        val shellClassType = runCatching { typeReferenceForJavaClass(clazz) }
            .getOrElse { JavaClassType(javaName = javaTypeNameFor(clazz)) }
        return ModuleType(
            moduleName = classModuleSimpleName(clazz),
            fields = linkedMapOf("__class" to JavaInstanceType(shellClassType))
        )
    }

    /**
     * Package-member class module used for wildcard mounts (`import "android.widget.*"`).
     *
     * Avoids deep super/interface *graph* expansion that OOMs multi-file android.jar wildcards
     * (TASK-611). Instance member surface still flattens [Class.getMethods]/[Class.getFields]
     * so constructed locals (`Button()` then `button.`) expose View/TextView inherited APIs
     * without walking empty super shells. The static surface uses the same reflected
     * [Class.getFields]/[Class.getMethods] helpers as the explicit-import deep surface so
     * inherited statics (`TextView.VISIBLE` from View) resolve identically through
     * `import "android.widget.*"` and `import "android.widget.TextView"`.
     */
    private fun shallowModuleTypeFor(clazz: Class<*>): ModuleType {
        val cacheKey = reflectedClassCacheKey(clazz)
        shallowModuleTypeCache[cacheKey]?.let { return it }
        // Nested public types as type references only (no recursive deep module expand).
        return classModuleTypeFor(clazz, shallowJavaClassTypeFor(clazz)) { innerClass ->
            typeReferenceForJavaClass(innerClass)
        }.also { shallowModuleTypeCache[cacheKey] = it }
    }

    /**
     * Cache identity for reflected class modules/providers.
     *
     * Binary name alone is insufficient: two URLClassLoaders can both define
     * fixture.dupe.PluginMarker with different static fields. Keying only by name
     * would leak ALPHA_ONLY into a BETA_ONLY classpath configuration.
     */
    private fun reflectedClassCacheKey(clazz: Class<*>): String {
        return buildString {
            append(System.identityHashCode(clazz.classLoader))
            append('#')
            append(clazz.name)
            append('#')
            append(System.identityHashCode(clazz))
        }
    }

    /**
     * Reflected member surfaces shared verbatim by the shallow (wildcard) and deep
     * (explicit import) [JavaClassType] builders: public static/instance fields and
     * methods with identical owner/kind/signature-metadata mapping.
     */
    private class ReflectedMemberMaps(
        val staticFields: Map<String, JavaStaticMemberType>,
        val staticMethods: Map<String, JavaStaticMemberType>,
        val instanceFields: Map<String, JavaInstanceMemberType>,
        val instanceMethods: Map<String, JavaInstanceMemberType>
    )

    private fun reflectedMemberMapsFor(clazz: Class<*>): ReflectedMemberMaps {
        val staticFields = publicStaticFields(clazz)
            .associate { field ->
                field.name to JavaStaticMemberType(
                    owner = javaTypeNameFor(field.declaringClass),
                    memberName = field.name,
                    valueType = javaTypeToType(field.genericType),
                    memberKind = JavaMemberKind.FIELD
                )
            }
        val staticMethods = publicStaticMethods(clazz)
            .groupBy(Method::getName)
            .mapValues { (name, overloads) ->
                JavaStaticMemberType(
                    owner = javaTypeNameFor(overloads.first().declaringClass),
                    memberName = name,
                    valueType = javaMethodType(overloads),
                    memberKind = JavaMemberKind.METHOD,
                    signatureMetadata = overloads.map { javaSignatureMetadata(it, it.genericReturnType) }
                )
            }
        // Flatten inherited public instance surface onto this type. Super shells are empty
        // type refs, so allInstanceMembers() would otherwise only see Button-declared methods.
        val instanceFields = publicInstanceFields(clazz)
            .associate { field ->
                field.name to JavaInstanceMemberType(
                    owner = javaTypeNameFor(field.declaringClass),
                    memberName = field.name,
                    valueType = javaTypeToType(field.genericType),
                    memberKind = JavaMemberKind.FIELD
                )
            }
        val instanceMethods = publicInstanceMethods(clazz)
            .groupBy(Method::getName)
            .mapValues { (name, overloads) ->
                JavaInstanceMemberType(
                    owner = javaTypeNameFor(overloads.first().declaringClass),
                    memberName = name,
                    valueType = javaMethodType(overloads),
                    memberKind = JavaMemberKind.METHOD,
                    signatureMetadata = overloads.map { javaSignatureMetadata(it, it.genericReturnType) }
                )
            }
        return ReflectedMemberMaps(staticFields, staticMethods, instanceFields, instanceMethods)
    }

    /**
     * Shared [JavaClassType] core behind the shallow (wildcard) and deep (explicit import)
     * builders: identical constructor/member/inner-class/type-parameter construction from
     * the reflected member maps; only the super/interface edges differ (name-only type
     * references for shallow, recursive member expansion for deep), supplied as lambdas.
     */
    private fun reflectedJavaClassType(
        clazz: Class<*>,
        superClass: () -> JavaClassType?,
        interfaces: () -> List<JavaClassType>
    ): JavaClassType {
        val javaName = javaTypeNameFor(clazz)
        val members = reflectedMemberMapsFor(clazz)
        return JavaClassType(
            javaName = javaName,
            constructors = constructorTypesFor(clazz, javaName),
            staticMembers = (members.staticFields + members.staticMethods).toSortedMap(),
            instanceMembers = (members.instanceFields + members.instanceMethods).toSortedMap(),
            innerClasses = clazz.classes
                .filter { Modifier.isPublic(it.modifiers) }
                .associate { it.simpleName to typeReferenceForJavaClass(it) }
                .toSortedMap(),
            superClass = superClass(),
            interfaces = interfaces(),
            typeParameters = clazz.typeParameters.map(::javaTypeParameterFor)
        )
    }

    /**
     * Package-wildcard class type: no recursive super/interface *member* expand (TASK-611),
     * but instance members use the full public reflection surface ([Class.getMethods] /
     * [Class.getFields]) so inherited View APIs appear on `button.` after
     * `import "android.widget.*"`. The static member surface uses the same reflected
     * helpers as the explicit-import deep surface so inherited statics
     * (`TextView.VISIBLE` from View) match the explicit import exactly.
     * Super/interfaces stay name-only type references.
     */
    private fun shallowJavaClassTypeFor(clazz: Class<*>): JavaClassType {
        // Name-only super/interface edges — no recursive member expand for wildcards.
        return reflectedJavaClassType(
            clazz,
            superClass = { clazz.superclass?.let(::typeReferenceForJavaClass) },
            interfaces = { clazz.interfaces.map(::typeReferenceForJavaClass) }
        )
    }

    private fun moduleTypeFor(
        clazz: Class<*>,
        reflectedClassStack: Set<String>,
        innerClassDepth: Int
    ): ModuleType {
        val nextReflectedClassStack = reflectedClassStack + clazz.name
        return classModuleTypeFor(clazz, javaClassTypeFor(clazz)) { innerClass ->
            reflectedInnerClassTypeFor(
                innerClass = innerClass,
                reflectedClassStack = nextReflectedClassStack,
                innerClassDepth = innerClassDepth
            )
        }
    }

    /**
     * Shared module-table core behind [shallowModuleTypeFor] and the deep
     * [moduleTypeFor]: `__class`/`__call` fields, reflected public static fields and
     * static method overloads, nested public types (depth policy supplied by
     * [nestedClassType]) and the `classModuleSimpleName` module name.
     */
    private fun classModuleTypeFor(
        clazz: Class<*>,
        classType: JavaClassType,
        nestedClassType: (Class<*>) -> Type
    ): ModuleType {
        val instanceType = JavaInstanceType(classType)
        val fields = linkedMapOf<String, Type>("__class" to instanceType)
        val methods = linkedMapOf<String, Type>()

        if (classType.constructors.isNotEmpty) {
            fields["__call"] = classType
        }

        // Reflected public static fields incl. inherited ones (TextView.VISIBLE from View,
        // File.separator, TextView.AUTO_SIZE_*, …) — same helper as the deep explicit surface.
        publicStaticFields(clazz).forEach { field ->
            fields[field.name] = javaTypeToType(field.genericType)
        }

        clazz.classes
            .filter { Modifier.isPublic(it.modifiers) }
            .forEach { innerClass ->
                fields[innerClass.simpleName] = nestedClassType(innerClass)
            }

        publicStaticMethods(clazz)
            .groupBy(Method::getName)
            .forEach { (name, overloads) ->
                methods[name] = javaMethodType(overloads)
            }

        return ModuleType(
            moduleName = classModuleSimpleName(clazz),
            fields = fields,
            methods = methods
        )
    }

    private fun reflectedInnerClassTypeFor(
        innerClass: Class<*>,
        reflectedClassStack: Set<String>,
        innerClassDepth: Int
    ): Type {
        if (innerClass.name in reflectedClassStack || innerClassDepth >= MAX_REFLECTED_INNER_CLASS_DEPTH) {
            return javaClassTypeFor(innerClass)
        }
        return moduleTypeFor(innerClass, reflectedClassStack, innerClassDepth + 1)
    }

    private fun javaClassTypeFor(clazz: Class<*>): JavaClassType {
        // Same robustness contract as moduleTypeFor(Class<*>): one unresolvable referenced
        // type must degrade to a name-only type reference instead of crashing the caller.
        return runCatching { javaClassTypeFor(clazz, emptySet(), 0) }
            .getOrElse {
                runCatching { typeReferenceForJavaClass(clazz) }
                    .getOrElse { JavaClassType(javaName = javaTypeNameFor(clazz)) }
            }
    }

    private fun javaClassTypeFor(
        clazz: Class<*>,
        hierarchyStack: Set<String>,
        hierarchyDepth: Int
    ): JavaClassType {
        // Bound deep hierarchy expand for multi-file android.jar workspaces (TASK-611).
        // Explicit class providers keep declared members + a short super/interface chain.
        // Past the member-expand depth, still hydrate a lightweight super/interface skeleton so
        // assignability can see transitive interfaces (ArrayList -> List -> Collection -> Iterable)
        // without re-expanding every inherited member surface.
        if (clazz.name in hierarchyStack) {
            return typeReferenceForJavaClass(clazz)
        }
        if (hierarchyDepth >= MAX_DEEP_HIERARCHY_EXPAND_DEPTH) {
            return hierarchySkeletonForJavaClass(clazz, hierarchyStack, hierarchyDepth)
        }
        val nextHierarchyStack = hierarchyStack + clazz.name
        return reflectedJavaClassType(
            clazz,
            superClass = { clazz.superclass?.let { javaClassTypeFor(it, nextHierarchyStack, hierarchyDepth + 1) } },
            interfaces = { clazz.interfaces.map { javaClassTypeFor(it, nextHierarchyStack, hierarchyDepth + 1) } }
        )
    }

    /**
     * Lightweight super/interface chain for assignability after the member-expand depth cap.
     * Keeps names + type parameters + transitive hierarchy edges only (no members/constructors).
     */
    private fun hierarchySkeletonForJavaClass(
        clazz: Class<*>,
        hierarchyStack: Set<String>,
        hierarchyDepth: Int
    ): JavaClassType {
        if (clazz.name in hierarchyStack || hierarchyDepth >= MAX_HIERARCHY_SKELETON_DEPTH) {
            return typeReferenceForJavaClass(clazz)
        }
        val nextHierarchyStack = hierarchyStack + clazz.name
        return JavaClassType(
            javaName = javaTypeNameFor(clazz),
            typeParameters = clazz.typeParameters.map(::javaTypeParameterFor),
            superClass = clazz.superclass
                ?.let { hierarchySkeletonForJavaClass(it, nextHierarchyStack, hierarchyDepth + 1) },
            interfaces = clazz.interfaces.map {
                hierarchySkeletonForJavaClass(it, nextHierarchyStack, hierarchyDepth + 1)
            }
        )
    }


    /** Shared public, non-synthetic field filter; [static] picks the static vs instance surface. */
    private fun publicFields(clazz: Class<*>, static: Boolean): List<Field> {
        return clazz.fields
            .filter { field ->
                Modifier.isPublic(field.modifiers) &&
                    Modifier.isStatic(field.modifiers) == static &&
                    !field.isSynthetic
            }
    }

    /**
     * Public, non-synthetic static fields used by Android-Lua scripts (RESULT_*, MODE_*,
     * ACTION_*, FLAG_*, service-name constants such as ACTIVITY_SERVICE /
     * LAYOUT_INFLATER_SERVICE / CLIPBOARD_SERVICE on [android.content.Context]).
     * Never invents names that reflection cannot see from the host android.jar.
     */
    private fun publicStaticFields(clazz: Class<*>): List<Field> = publicFields(clazz, static = true)

    private fun publicInstanceFields(clazz: Class<*>): List<Field> = publicFields(clazz, static = false)

    /**
     * Public, non-synthetic/non-bridge methods. [Class.getMethods] already returns the
     * public inherited surface used by Activity/Context/Intent corpora (lifecycle, extras,
     * system services). Bridge/synthetic overloads are dropped so call-signature counts
     * reflect real Android API shapes rather than compiler artifacts.
     *
     * Nested interfaces such as [java.util.Map.Entry] declare static helpers
     * (`comparingByKey` / `comparingByValue`). Those are public and must appear on the
     * binary-name provider module so bindClass mounts have real members to resolve.
     * Declared public static methods are merged in for interfaces/enums so host JDKs that
     * omit interface statics from [Class.getMethods] still surface reflection-backed
     * members (never invented).
     */
    private fun publicStaticMethods(clazz: Class<*>): List<Method> {
        val fromPublicApi = clazz.methods.asSequence()
        val fromDeclared = if (clazz.isInterface || clazz.isEnum) {
            clazz.declaredMethods.asSequence()
        } else {
            emptySequence()
        }
        return (fromPublicApi + fromDeclared)
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    Modifier.isStatic(method.modifiers) &&
                    isReflectableMethod(method)
            }
            .distinctBy { method ->
                buildString {
                    append(method.name)
                    append('#')
                    method.parameterTypes.forEach { parameter ->
                        append(parameter.name)
                        append(';')
                    }
                }
            }
            .toList()
    }

    private fun publicInstanceMethods(clazz: Class<*>): List<Method> {
        return clazz.methods
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    !Modifier.isStatic(method.modifiers) &&
                    isReflectableMethod(method)
            }
    }

    private fun isReflectableMethod(method: Method): Boolean {
        return !method.isSynthetic && !method.isBridge
    }

    private fun constructorTypesFor(clazz: Class<*>, owner: JavaTypeName): JavaOverloadSet<JavaConstructorType> {
        return JavaOverloadSet(
            clazz.constructors
                .filter { constructor ->
                    Modifier.isPublic(constructor.modifiers) && !constructor.isSynthetic
                }
                .map { constructor ->
                    JavaConstructorType(
                        owner = owner,
                        signature = constructorSignature(constructor),
                        signatureMetadata = javaSignatureMetadata(constructor)
                    )
                }
        )
    }

    private fun constructorSignature(constructor: Constructor<*>): FunctionType {
        return FunctionType(
            parameters = executableParameters(constructor),
            returnType = JavaInstanceType(typeReferenceForJavaClass(constructor.declaringClass)),
            typeParameters = constructor.typeParameters.map(::javaTypeParameterFor)
        )
    }

    private fun javaMethodType(methods: List<Method>): Type {
        val signatures = methods.map(::methodSignature)
        return when (signatures.size) {
            0 -> FunctionType(returnType = UnknownType)
            1 -> signatures.single()
            else -> JavaOverloadType(
                javaName = javaTypeNameFor(methods.first().declaringClass),
                overloadName = methods.first().name,
                callSignatures = signatures,
                signatureMetadata = methods.map { javaSignatureMetadata(it, it.genericReturnType) }
            )
        }
    }

    private fun methodSignature(method: Method): FunctionType {
        return FunctionType(
            parameters = executableParameters(method),
            returnType = javaTypeToType(method.genericReturnType),
            typeParameters = method.typeParameters.map(::javaTypeParameterFor)
        )
    }

    private fun executableParameters(executable: Executable): List<FunctionParameter> {
        val genericParameterTypes = executable.genericParameterTypes
        return executable.parameterTypes.mapIndexed { index, parameterType ->
            FunctionParameter(
                name = "arg${index + 1}",
                type = javaTypeToType(genericParameterTypes.getOrNull(index) ?: parameterType),
                vararg = executable.isVarArgs && index == executable.parameterTypes.lastIndex
            )
        }
    }

    private fun javaSignatureMetadata(
        executable: Executable,
        genericReturnType: ReflectType? = null
    ): JavaSignatureMetadata {
        return JavaSignatureMetadata(
            isVarArgs = executable.isVarArgs,
            typeParameters = executable.typeParameters.map(::javaTypeParameterFor),
            genericParameterTypeNames = executable.genericParameterTypes.map { it.typeName },
            genericReturnTypeName = genericReturnType?.typeName
        )
    }

    private fun moduleMembers(moduleType: ModuleType): List<ModuleExportSurface.MemberExport> {
        val output = mutableListOf<ModuleExportSurface.MemberExport>()
        moduleType.fields.forEach { (name, type) ->
            output += ModuleExportSurface.MemberExport(
                name = name,
                exportPath = listOf(name),
                kind = io.github.dingyi222666.luaparser.semantic.api.SymbolKind.FIELD,
                type = type,
                range = null
            )
            if (name == "__class") {
                output += classMembers(type)
            }
        }
        moduleType.methods.forEach { (name, type) ->
            output += ModuleExportSurface.MemberExport(
                name = name,
                exportPath = listOf(name),
                kind = io.github.dingyi222666.luaparser.semantic.api.SymbolKind.METHOD,
                type = type,
                range = null
            )
        }
        return output.sortedWith(compareBy<ModuleExportSurface.MemberExport>({ it.exportPath.size }, { it.name }))
    }

    internal fun classMembers(type: Type, exportPathPrefix: List<String> = listOf("__class")): List<ModuleExportSurface.MemberExport> {
        val output = mutableListOf<ModuleExportSurface.MemberExport>()
        // O(n) dedupe (adversarial audit): a HashSet of already-exported export paths
        // replaces the previous O(n^2) `output.none { ... }` linear rescan. Keyed by the
        // full export path (not just the member name) so differently prefixed surfaces that
        // reuse a name never collide. First occurrence wins, preserving the previous
        // precedence: static members, then inner classes, then instance members.
        val exportedPaths = HashSet<List<String>>()
        fun addExport(name: String, kind: io.github.dingyi222666.luaparser.semantic.api.SymbolKind, memberType: Type) {
            val exportPath = exportPathPrefix + name
            if (!exportedPaths.add(exportPath)) {
                return
            }
            output += ModuleExportSurface.MemberExport(
                name = name,
                exportPath = exportPath,
                kind = kind,
                type = memberType,
                range = null
            )
        }
        when (type) {
            is JavaInstanceType -> appendClassExports(type.classType, type.allInstanceMembers(), ::addExport)
            is JavaClassType -> appendClassExports(type, type.allInstanceMembers(), ::addExport)
            else -> {
                val classType = type as? ClassType ?: return emptyList()
                classType.getAllFields().forEach { (name, memberType) ->
                    addExport(name, io.github.dingyi222666.luaparser.semantic.api.SymbolKind.FIELD, memberType)
                }
                classType.getAllMethods().forEach { (name, memberType) ->
                    addExport(name, io.github.dingyi222666.luaparser.semantic.api.SymbolKind.METHOD, memberType)
                }
            }
        }
        // Bounded export surface (adversarial audit): a hard per-class cap is applied AFTER
        // deterministic sorting — alphabetical by (export path depth, member name, joined
        // path), the same comparator family as moduleMembers' final sort — so truncation
        // deterministically keeps the alphabetically first [MAX_CLASS_EXPORT_MEMBERS]
        // members and the final module export order is unchanged for under-cap classes.
        return output
            .sortedWith(
                compareBy<ModuleExportSurface.MemberExport>(
                    { it.exportPath.size },
                    { it.name },
                    { it.exportPath.joinToString("/") }
                )
            )
            .take(MAX_CLASS_EXPORT_MEMBERS)
    }

    /**
     * Export loop shared verbatim by the instance-shell and bare-class [classMembers]
     * branches: static members + inner classes of [classType], then [instanceMembers] —
     * in that order, so the [addExport] dedupe keeps the documented precedence
     * (statics win name collisions over instance members).
     */
    private fun appendClassExports(
        classType: JavaClassType,
        instanceMembers: Map<String, JavaInstanceMemberType>,
        addExport: (String, io.github.dingyi222666.luaparser.semantic.api.SymbolKind, Type) -> Unit
    ) {
        // Nested / interface static helpers (Map$Entry.comparingByKey) live on the class
        // surface. Export them under __class so workspace export lookup, goto, and
        // fingerprint can resolve binary-name bindClass mounts without inventing members.
        classType.allStaticMembers().forEach { (name, member) ->
            addExport(name, symbolKindForJavaMember(member.memberKind, member.valueType), member.valueType)
        }
        classType.allInnerClasses().forEach { (name, innerClass) ->
            addExport(name, io.github.dingyi222666.luaparser.semantic.api.SymbolKind.CLASS, innerClass)
        }
        instanceMembers.forEach { (name, member) ->
            // Prefer static METHOD exports when names collide with instance members.
            addExport(name, symbolKindForJavaMember(member.memberKind, member.valueType), member.valueType)
        }
    }

    private fun symbolKindForJavaMember(
        memberKind: JavaMemberKind,
        valueType: Type
    ): io.github.dingyi222666.luaparser.semantic.api.SymbolKind {
        return when {
            memberKind == JavaMemberKind.METHOD -> io.github.dingyi222666.luaparser.semantic.api.SymbolKind.METHOD
            valueType is io.github.dingyi222666.luaparser.semantic.types.model.CallableType ->
                io.github.dingyi222666.luaparser.semantic.api.SymbolKind.METHOD
            else -> io.github.dingyi222666.luaparser.semantic.api.SymbolKind.FIELD
        }
    }

    private fun javaTypeToType(type: ReflectType): Type {
        return javaTypeToType(type, emptySet())
    }

    private fun javaTypeToType(type: ReflectType, resolvingTypeParameters: Set<String>): Type {
        return when (type) {
            is Class<*> -> javaClassToType(type, resolvingTypeParameters)
            is ParameterizedType -> javaParameterizedTypeToType(type, resolvingTypeParameters)
            is TypeVariable<*> -> TypeParameterType(
                name = type.name,
                constraint = if (type.name in resolvingTypeParameters) {
                    null
                } else {
                    type.bounds.firstKnownBoundType(resolvingTypeParameters + type.name)
                }
            )
            is GenericArrayType -> ArrayType(javaTypeToType(type.genericComponentType, resolvingTypeParameters))
            is WildcardType -> type.upperBounds.firstKnownBoundType(resolvingTypeParameters) ?: UnknownType
            else -> UnknownType
        }
    }

    private fun javaClassToType(type: Class<*>, resolvingTypeParameters: Set<String>): Type {
        return when {
            type == Void.TYPE -> PrimitiveType.NIL
            type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> PrimitiveType.BOOLEAN
            type == java.lang.Byte.TYPE || type == java.lang.Short.TYPE || type == java.lang.Integer.TYPE ||
                type == java.lang.Long.TYPE || type == java.lang.Float.TYPE || type == java.lang.Double.TYPE ||
                Number::class.java.isAssignableFrom(type) -> PrimitiveType.NUMBER
            type == java.lang.Character.TYPE || type == Char::class.java || type == String::class.java ||
                CharSequence::class.java.isAssignableFrom(type) -> PrimitiveType.STRING
            type.isArray -> ArrayType(javaTypeToType(type.componentType, resolvingTypeParameters))
            type.isPrimitive -> PrimitiveType.ANY
            else -> JavaInstanceType(typeReferenceForJavaClass(type, resolvingTypeParameters))
        }
    }

    private fun javaParameterizedTypeToType(type: ParameterizedType, resolvingTypeParameters: Set<String>): Type {
        val rawClass = type.rawType as? Class<*> ?: return UnknownType
        val rawType = javaClassToType(rawClass, resolvingTypeParameters)
        val typeArguments = type.actualTypeArguments.map { javaTypeToType(it, resolvingTypeParameters) }
        return when (rawType) {
            is JavaInstanceType -> rawType.copy(typeArguments = typeArguments)
            else -> rawType
        }
    }

    private fun Array<ReflectType>.firstKnownBoundType(resolvingTypeParameters: Set<String>): Type? {
        return firstOrNull { it != Any::class.java }?.let { javaTypeToType(it, resolvingTypeParameters) }
    }

    private fun javaTypeParameterFor(parameter: TypeVariable<*>): TypeParameterType {
        return javaTypeParameterFor(parameter, emptySet())
    }

    private fun javaTypeParameterFor(
        parameter: TypeVariable<*>,
        resolvingTypeParameters: Set<String>
    ): TypeParameterType {
        val nextResolvingTypeParameters = resolvingTypeParameters + parameter.name
        return TypeParameterType(
            name = parameter.name,
            constraint = if (parameter.name in resolvingTypeParameters) {
                null
            } else {
                parameter.bounds.firstKnownBoundType(nextResolvingTypeParameters)
            }
        )
    }

    private fun typeReferenceForJavaClass(type: Class<*>): JavaClassType {
        return typeReferenceForJavaClass(type, emptySet())
    }

    private fun typeReferenceForJavaClass(type: Class<*>, resolvingTypeParameters: Set<String>): JavaClassType {
        val localTypeParameterNames = type.typeParameters.mapTo(linkedSetOf()) { it.name }
        val nextResolvingTypeParameters = resolvingTypeParameters + localTypeParameterNames
        return JavaClassType(
            javaName = javaTypeNameFor(type),
            typeParameters = type.typeParameters.map { javaTypeParameterFor(it, nextResolvingTypeParameters) }
        )
    }

    private fun javaTypeNameFor(type: Class<*>): JavaTypeName {
        val packageName = type.`package`?.name.orEmpty()
        val binaryClassName = type.name.removePrefix(packageName.takeIf(String::isNotEmpty)?.let { "$it." } ?: "")
        val simpleNames = binaryClassName
            .split('$')
            .filter(String::isNotBlank)
            .ifEmpty { listOf(type.simpleName.takeIf(String::isNotBlank) ?: type.name) }
        return JavaTypeName(
            packageName = packageName,
            simpleNames = simpleNames,
            binaryName = type.name,
            canonicalName = type.canonicalName ?: type.name.replace('$', '.')
        )
    }

    companion object {
        private const val MAX_REFLECTED_INNER_CLASS_DEPTH = 1

        /**
         * Process-wide single extraction of the bundled runtime jar.
         *
         * Provider instances are created per workspace update (per keystroke in an LSP
         * session); an instance-level temp extraction used to leak one 1.3MB copy per
         * update into java.io.tmpdir — ~36k copies / ~45GiB over a long demo session.
         * One shared lazy + deleteOnExit caps the cost at one file per JVM, removed on
         * clean exit.
         */
        private val companionRuntimeJarRef: File? by lazy {
            runCatching {
                val resource = JvmClassModuleProvider::class.java
                    .getResourceAsStream("/io/github/dingyi222666/luaparser/interop/jvm/androlua-runtime.jar")
                    ?: return@lazy null
                val target = Files.createTempFile("androlua-runtime", ".jar")
                resource.use { input ->
                    Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                val file = target.toFile().takeIf { it.isFile && it.length() > 0 }
                file?.deleteOnExit()
                file
            }.getOrNull()
        }
        // Lua standard library module names a reflected nested class simple name must never
        // claim bare (android.R$string → "string" used to shadow the string library).
        private val LUA_STD_MODULE_NAMES = setOf(
            "bit32", "coroutine", "debug", "io", "math", "os", "package", "string", "table", "utf8"
        )
        // Cycle guard + practical member expand depth for deep class providers.
        private const val MAX_DEEP_HIERARCHY_EXPAND_DEPTH = 2
        // Lightweight hierarchy skeleton depth for assignability (transitive supers/interfaces).
        // Bounded well below the test maxHierarchyDepth guard (33) while covering JDK chains
        // such as ArrayList -> List -> Collection -> Iterable and AbstractList -> Object.
        private const val MAX_HIERARCHY_SKELETON_DEPTH = 16
        /**
         * Hard per-class cap on exported `__class` members (adversarial audit: Android
         * framework types such as Context expand to ~250 members and were previously
         * unbounded). Applied after deterministic alphabetical sorting in [classMembers],
         * so exactly the alphabetically first members survive truncation. Internal for the
         * robustness test corpus; treat as a product heap bound, not a tuning knob.
         */
        internal const val MAX_CLASS_EXPORT_MEMBERS = 400
        const val UNSUPPORTED_PREFIXED_IMPORT_CODE = "jvm.import.prefixed.unsupported"

        const val CLASSES_METADATA_KEY = "jvm.classes"
        const val IMPORTS_METADATA_KEY = "androlua.imports"

        val DEFAULT_IMPORT_PREFIXES: List<String> = listOf(
            "java.lang",
            "java.util",
            "java.io",
            "android.app",
            "android.content",
            "android.view",
            "android.widget",
            "com.androlua"
        )
    }
}
