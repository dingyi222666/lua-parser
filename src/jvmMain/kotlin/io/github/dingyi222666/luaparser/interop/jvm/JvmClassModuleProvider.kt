package io.github.dingyi222666.luaparser.interop.jvm

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
import java.nio.file.Path
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

    fun providersFor(metadata: Map<String, String>): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        return providersFor(JvmWorkspaceConfiguration.fromMetadata(metadata))
    }

    fun providersFor(configuration: JvmWorkspaceConfiguration): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        val requested = requestedClassLoads(normalized, classLoader)
        if (requested.isEmpty()) {
            return emptyMap()
        }

        return requested.mapNotNull { request ->
            // Never invent framework members: only mount classes that Class.forName can load.
            runCatching { Class.forName(request.className, false, request.classLoader) }
                .getOrNull()
                ?.let(::providerForClass)
        }.associate { it.first to it.second }
    }

    internal fun packageProvidersFor(
        importTargets: Collection<String>,
        configuration: JvmWorkspaceConfiguration
    ): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        // Always prefer a reflective classpath loader for package wildcards so host
        // android.jar enumeration is not blocked by a caller-supplied ClassLoader that
        // cannot see the configured jar. Caller classLoader is used only as the parent.
        val classLoader = classLoaderForPackageEnumeration(normalized)
        return importTargets
            .asSequence()
            .mapNotNull { importedPackageProvider(it, normalized, classLoader) }
            .associate { it.first to it.second }
    }

    internal fun requestedClasses(metadata: Map<String, String>): Set<String> {
        val configuration = JvmWorkspaceConfiguration.fromMetadata(metadata)
        return requestedClasses(configuration, configuration.classLoader ?: classLoaderFor(configuration))
    }

    internal fun requestedClasses(configuration: JvmWorkspaceConfiguration): Set<String> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        return requestedClasses(normalized, normalized.classLoader ?: classLoaderFor(normalized))
    }

    internal fun resolveImport(importText: String): String? {
        val configuration = JvmWorkspaceConfiguration().normalized(defaultImportPrefixes)
        return resolveImport(importText, configuration, configuration.classLoader ?: classLoaderFor(configuration))
    }

    internal fun resolveImport(importText: String, configuration: JvmWorkspaceConfiguration): String? {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        return resolveImport(importText, normalized, classLoader)
    }

    internal fun importedClassName(importText: String, configuration: JvmWorkspaceConfiguration): String? {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        return resolveImport(importText, normalized, classLoader)
    }

    internal fun importedClassNames(importText: String, configuration: JvmWorkspaceConfiguration): List<String> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        return resolveImports(importText, normalized.importPrefixes, classLoader, normalized)
    }

    internal fun importDiagnostics(configuration: JvmWorkspaceConfiguration): List<ImportDiagnostic> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        return (normalized.classes + normalized.androluaImports)
            .mapNotNull { importDiagnostic(it, normalized) }
            .distinctBy { diagnostic ->
                listOf(diagnostic.importText, diagnostic.pathPrefix, diagnostic.className, diagnostic.code)
            }
    }

    internal fun importDiagnostics(
        importTargets: Collection<String>,
        configuration: JvmWorkspaceConfiguration
    ): List<ImportDiagnostic> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        return importTargets
            .mapNotNull { importDiagnostic(it, normalized) }
            .distinctBy { diagnostic ->
                listOf(diagnostic.importText, diagnostic.pathPrefix, diagnostic.className, diagnostic.code)
            }
    }

    internal fun importedSymbolForTarget(importText: String, configuration: JvmWorkspaceConfiguration): WorkspaceImportedSymbol? {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = classLoaderForPackageEnumeration(normalized)
        // 1) wildcard package  2) loadable class  3) package-name alias when package enumerates.
        return when {
            isWildcardImport(importText) -> importedPackageSymbol(importText, normalized, classLoader)
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
        val clazz = loads.firstOrNull()?.let { request ->
            runCatching { Class.forName(request.className, false, request.classLoader) }.getOrNull()
        } ?: return null
        val (providerPath, _) = providerForClass(clazz)
        return WorkspaceImportedSymbol(
            alias = clazz.simpleName,
            moduleName = clazz.simpleName,
            providerPath = providerPath,
            moduleType = moduleTypeFor(clazz)
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
            alias = packageName.substringBeforeLast('.', packageName),
            moduleName = moduleType.moduleName,
            providerPath = providerPath,
            moduleType = moduleType
        )
    }

    private fun importedPackageProvider(
        importText: String,
        configuration: JvmWorkspaceConfiguration,
        classLoader: ClassLoader
    ): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot>? {
        val packageName = packageNameFromImport(importText, configuration, classLoader) ?: return null
        val classes = resolvedPackageClasses(packageName, classLoader, configuration)
        if (classes.isEmpty()) {
            return null
        }
        return providerForPackage(packageName, packageModuleTypeFor(packageName, classes))
    }

    private fun requestedClasses(configuration: JvmWorkspaceConfiguration, classLoader: ClassLoader): Set<String> {
        return requestedClassLoads(configuration, classLoader)
            .mapTo(linkedSetOf()) { it.className }
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

    private fun resolveImport(importText: String, importPrefixes: List<String>, classLoader: ClassLoader): String? {
        return resolveImports(importText, importPrefixes, classLoader).firstOrNull()
    }

    private fun resolveImport(
        importText: String,
        configuration: JvmWorkspaceConfiguration,
        classLoader: ClassLoader
    ): String? {
        return resolveImports(importText, configuration.importPrefixes, classLoader, configuration).firstOrNull()
    }

    private fun resolveImports(importText: String, importPrefixes: List<String>, classLoader: ClassLoader): List<String> {
        return resolveImports(importText, importPrefixes, classLoader, JvmWorkspaceConfiguration())
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

    private fun resolvedImportClasses(
        importText: String,
        importPrefixes: List<String>,
        classLoader: ClassLoader,
        configuration: JvmWorkspaceConfiguration
    ): List<Class<*>> {
        return resolveClassLoads(importText, importPrefixes, classLoader, configuration)
            .mapNotNull { request ->
                runCatching { Class.forName(request.className, false, request.classLoader) }.getOrNull()
            }
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
            // Package-name alias support (import "android.widget") only when explicitly allowed.
            // Default class-load paths keep this off so providersFor does not reflect whole packages.
            if (allowPackageEnumeration) {
                return resolveWildcardImports(target, targetClassLoader, configuration)
                    .map { ResolvedClassLoad(it, targetClassLoader) }
            }
            return emptyList()
        }
        return importPrefixes.firstNotNullOfOrNull { prefix ->
            candidateClassNames("$prefix.$target").firstNotNullOfOrNull { candidate ->
                runCatching { Class.forName(candidate, false, targetClassLoader) }
                    .getOrNull()
                    ?.name
                    ?.let { listOf(ResolvedClassLoad(it, targetClassLoader)) }
            }
        }.orEmpty()
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
        return URLClassLoader(arrayOf(entry.toURI().toURL()), fallback)
    }

    private fun isWildcardImport(importText: String): Boolean {
        return wildcardPackageName(importText) != null
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
        val classLoader: ClassLoader
    )

    private fun packageModuleTypeFor(
        packageName: String,
        classes: List<Class<*>>
    ): ModuleType {
        // Stable simple-name fields for AndroLua package modules (Activity/Context/View/TextView).
        // When two public top-level types share a simpleName (should not happen in one package),
        // first-by-sorted-name wins; never invent names that reflection did not load.
        //
        // Reflection of a single member must not abort the whole package module: if one
        // obscure type fails to model, still surface Activity/Context/View/TextView peers.
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
            val memberType = runCatching { moduleTypeFor(clazz) }.getOrNull()
                ?: runCatching { JavaInstanceType(typeReferenceForJavaClass(clazz)) }.getOrNull()
                ?: return@forEach
            members[simpleName] = memberType
        }
        return ModuleType(
            moduleName = packageModuleName(packageName),
            fields = members.toSortedMap(),
            indexSignature = ModuleType.IndexSignature(PrimitiveType.STRING, UnknownType)
        )
    }

    private fun packageModuleName(packageName: String): String {
        return packageName
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
            // Context_BindServiceFlags → Map$Entry / View$OnClickListener / Context$BindServiceFlags
            trimmed.takeIf { '_' in it }?.replace('_', '$')?.let(::add)
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
        return classNames
            .asSequence()
            .filter { binaryName -> isTopLevelPackageBinaryName(binaryName, packageName) }
            .sorted()
            .toList()
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
            entry.isDirectory -> collectClassesFromDirectoryRoot(entry.toPath(), packagePath, packageName, output)
            entry.isFile && entry.extension.equals("jar", ignoreCase = true) ->
                collectClassesFromJarFile(entry, packagePath, output)
        }
    }

    private fun collectClassesFromDirectoryRoot(
        root: Path,
        packagePath: String,
        packageName: String,
        output: MutableSet<String>
    ) {
        collectClassesFromDirectory(root.resolve(packagePath).toString(), packageName, output)
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
     * Existing reflective classpath files for ClassLoader + package enumeration.
     *
     * Uses [JvmWorkspaceConfiguration.reflectionClasspathEntries] first. When an explicit
     * `jvm.androidJar` metadata path is missing on disk (common with Windows-only `G:/...`
     * fixtures on macOS hosts), soft-falls back to host SDK discovery via
     * [JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath] so nested AndroLua
     * aliases (`View$OnClickListener`, `Map$Entry`) still resolve when the host jar is present.
     *
     * Never invents framework classes: only existing directories/jars are returned.
     * Never hard-requires `G:/`.
     */
    private fun reflectiveClasspathFiles(configuration: JvmWorkspaceConfiguration): List<File> {
        val entries = configuration.reflectionClasspathEntries()
            .map(::File)
            .filter { entry ->
                entry.isDirectory || (entry.isFile && entry.extension.equals("jar", ignoreCase = true))
            }
            .toMutableList()
        val hasAndroidJar = entries.any { entry ->
            entry.isFile && entry.name.equals("android.jar", ignoreCase = true)
        }
        if (!hasAndroidJar && shouldSoftFallbackToHostAndroidJar(configuration)) {
            // Legacy Windows G:/ android.jar metadata fixtures soft-fall back to host SDK
            // discovery so nested AndroLua aliases still resolve on macOS. Explicit missing
            // non-G paths (isolation tests) stay empty and never invent framework classes.
            JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()
                ?.let(::File)
                ?.takeIf { hostJar ->
                    hostJar.isFile && hostJar.extension.equals("jar", ignoreCase = true)
                }
                ?.let { hostJar -> entries += hostJar }
        }
        return entries.distinctBy { it.absolutePath }
    }

    private fun shouldSoftFallbackToHostAndroidJar(configuration: JvmWorkspaceConfiguration): Boolean {
        val configuredJar = configuration.androidJar?.trim()?.takeIf(String::isNotEmpty)
        if (configuredJar.isNullOrBlank()) {
            // reflectionClasspathEntries already discovers when androidJar is blank; keep a
            // defensive host mount if discovery returned nothing earlier.
            return true
        }
        val normalized = configuredJar.replace('\\', '/')
        val isWindowsDocumentedSdkPath = normalized.startsWith("G:/Android/Sdk", ignoreCase = true)
        return isWindowsDocumentedSdkPath && !File(configuredJar).isFile
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
        val parent = configuration.classLoader ?: baseClassLoader
        val entries = reflectiveClasspathFiles(configuration)
        if (entries.isEmpty()) {
            return parent
        }
        val urls = entries
            .map(File::toURI)
            .map { it.toURL() }
            .toTypedArray()
        return URLClassLoader(urls, parent)
    }

    private fun classLoaderFor(configuration: JvmWorkspaceConfiguration): ClassLoader {
        // Only existing reflective classpath entries are mounted. Missing android.jar paths
        // (including G:/ candidates) never invent framework classes; host SDK soft-fallback
        // mounts only when discovery finds a real jar (see reflectiveClasspathFiles).
        val entries = reflectiveClasspathFiles(configuration)
        if (entries.isEmpty()) {
            return configuration.classLoader ?: baseClassLoader
        }
        val parent = configuration.classLoader ?: baseClassLoader
        val urls = entries
            .map(File::toURI)
            .map { it.toURL() }
            .toTypedArray()
        return URLClassLoader(urls, parent)
    }

    private fun providerForClass(clazz: Class<*>): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val moduleType = moduleTypeFor(clazz)
        val path = VirtualPath.of("__jvm__/classes/${clazz.name.replace('.', '/')}.lua")
        val source = buildString {
            append("-- reflected JVM class provider for ")
            append(clazz.name)
            append('\n')
        }
        val surface = ModuleExportSurface(
            moduleType = moduleType,
            sourceForm = ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL,
            members = moduleMembers(moduleType)
        )
        val fingerprintPayload = buildString {
            append(clazz.name)
            append('\n')
            append(moduleType.displayName)
            append('\n')
            append(surface.members.joinToString("|") { "${it.kind}:${it.name}:${it.type.displayName}" })
        }
        return path to WorkspaceSnapshot.FileSnapshot(
            cacheKey = workspaceFingerprintHash(source),
            moduleExportSurface = surface,
            publicFingerprint = io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint(
                providedModuleNames = linkedSetOf(clazz.simpleName),
                value = workspaceFingerprintHash(fingerprintPayload)
            )
        )
    }

    private fun providerForPackage(
        packageName: String,
        moduleType: ModuleType
    ): Pair<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val path = VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua")
        val source = buildString {
            append("-- reflected JVM package provider for ")
            append(packageName)
            append('\n')
        }
        val surface = ModuleExportSurface(
            moduleType = moduleType,
            sourceForm = ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL,
            members = moduleMembers(moduleType)
        )
        val fingerprintPayload = buildString {
            append(packageName)
            append('\n')
            append(moduleType.displayName)
            append('\n')
            append(surface.members.joinToString("|") { "${it.kind}:${it.name}:${it.type.displayName}" })
        }
        return path to WorkspaceSnapshot.FileSnapshot(
            cacheKey = workspaceFingerprintHash(source),
            moduleExportSurface = surface,
            publicFingerprint = io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint(
                providedModuleNames = linkedSetOf(moduleType.moduleName),
                value = workspaceFingerprintHash(fingerprintPayload)
            )
        )
    }

    private fun moduleTypeFor(clazz: Class<*>): ModuleType {
        return moduleTypeFor(clazz, emptySet(), 0)
    }

    private fun moduleTypeFor(
        clazz: Class<*>,
        reflectedClassStack: Set<String>,
        innerClassDepth: Int
    ): ModuleType {
        val nextReflectedClassStack = reflectedClassStack + clazz.name
        val classType = javaClassTypeFor(clazz)
        val instanceType = JavaInstanceType(classType)
        val fields = linkedMapOf<String, Type>("__class" to instanceType)
        val methods = linkedMapOf<String, Type>()

        if (classType.constructors.isNotEmpty) {
            fields["__call"] = classType
        }

        publicStaticFields(clazz).forEach { field ->
            fields[field.name] = javaTypeToType(field.genericType)
        }

        clazz.classes
            .filter { Modifier.isPublic(it.modifiers) }
            .forEach { innerClass ->
                fields[innerClass.simpleName] = reflectedInnerClassTypeFor(
                    innerClass = innerClass,
                    reflectedClassStack = nextReflectedClassStack,
                    innerClassDepth = innerClassDepth
                )
            }

        publicStaticMethods(clazz)
            .groupBy(Method::getName)
            .forEach { (name, overloads) ->
                methods[name] = javaMethodType(overloads)
            }

        return ModuleType(
            moduleName = clazz.simpleName,
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
        return javaClassTypeFor(clazz, emptySet(), 0)
    }

    private fun javaClassTypeFor(
        clazz: Class<*>,
        hierarchyStack: Set<String>,
        hierarchyDepth: Int
    ): JavaClassType {
        if (clazz.name in hierarchyStack || hierarchyDepth >= MAX_REFLECTED_HIERARCHY_DEPTH) {
            return typeReferenceForJavaClass(clazz)
        }
        val nextHierarchyStack = hierarchyStack + clazz.name
        val javaName = javaTypeNameFor(clazz)
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
        return JavaClassType(
            javaName = javaName,
            constructors = constructorTypesFor(clazz, javaName),
            staticMembers = (staticFields + staticMethods).toSortedMap(),
            instanceMembers = (instanceFields + instanceMethods).toSortedMap(),
            innerClasses = clazz.classes
                .filter { Modifier.isPublic(it.modifiers) }
                .associate { it.simpleName to typeReferenceForJavaClass(it) }
                .toSortedMap(),
            superClass = clazz.superclass
                ?.let { javaClassTypeFor(it, nextHierarchyStack, hierarchyDepth + 1) },
            interfaces = clazz.interfaces.map { javaClassTypeFor(it, nextHierarchyStack, hierarchyDepth + 1) },
            typeParameters = clazz.typeParameters.map(::javaTypeParameterFor)
        )
    }


    /**
     * Public, non-synthetic static fields used by Android-Lua scripts (RESULT_*, MODE_*,
     * ACTION_*, FLAG_*, service-name constants such as ACTIVITY_SERVICE /
     * LAYOUT_INFLATER_SERVICE / CLIPBOARD_SERVICE on [android.content.Context]).
     * Never invents names that reflection cannot see from the host android.jar.
     */
    private fun publicStaticFields(clazz: Class<*>): List<Field> {
        return clazz.fields
            .filter { field ->
                Modifier.isPublic(field.modifiers) &&
                    Modifier.isStatic(field.modifiers) &&
                    !field.isSynthetic
            }
    }

    private fun publicInstanceFields(clazz: Class<*>): List<Field> {
        return clazz.fields
            .filter { field ->
                Modifier.isPublic(field.modifiers) &&
                    !Modifier.isStatic(field.modifiers) &&
                    !field.isSynthetic
            }
    }

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

    private fun classMembers(type: Type, exportPathPrefix: List<String> = listOf("__class")): List<ModuleExportSurface.MemberExport> {
        val output = mutableListOf<ModuleExportSurface.MemberExport>()
        if (type is JavaInstanceType) {
            // Nested / interface static helpers (Map$Entry.comparingByKey) live on the class
            // surface. Export them under __class so workspace export lookup, goto, and
            // fingerprint can resolve binary-name bindClass mounts without inventing members.
            type.classType.allStaticMembers().forEach { (name, member) ->
                output += ModuleExportSurface.MemberExport(
                    name = name,
                    exportPath = exportPathPrefix + name,
                    kind = symbolKindForJavaMember(member.memberKind, member.valueType),
                    type = member.valueType,
                    range = null
                )
            }
            type.classType.allInnerClasses().forEach { (name, innerClass) ->
                output += ModuleExportSurface.MemberExport(
                    name = name,
                    exportPath = exportPathPrefix + name,
                    kind = io.github.dingyi222666.luaparser.semantic.api.SymbolKind.CLASS,
                    type = innerClass,
                    range = null
                )
            }
            type.allInstanceMembers().forEach { (name, member) ->
                // Prefer static METHOD exports when names collide with instance members.
                if (output.none { it.exportPath == exportPathPrefix + name }) {
                    output += ModuleExportSurface.MemberExport(
                        name = name,
                        exportPath = exportPathPrefix + name,
                        kind = symbolKindForJavaMember(member.memberKind, member.valueType),
                        type = member.valueType,
                        range = null
                    )
                }
            }
            return output
        }

        if (type is JavaClassType) {
            type.allStaticMembers().forEach { (name, member) ->
                output += ModuleExportSurface.MemberExport(
                    name = name,
                    exportPath = exportPathPrefix + name,
                    kind = symbolKindForJavaMember(member.memberKind, member.valueType),
                    type = member.valueType,
                    range = null
                )
            }
            type.allInnerClasses().forEach { (name, innerClass) ->
                output += ModuleExportSurface.MemberExport(
                    name = name,
                    exportPath = exportPathPrefix + name,
                    kind = io.github.dingyi222666.luaparser.semantic.api.SymbolKind.CLASS,
                    type = innerClass,
                    range = null
                )
            }
            type.allInstanceMembers().forEach { (name, member) ->
                if (output.none { it.exportPath == exportPathPrefix + name }) {
                    output += ModuleExportSurface.MemberExport(
                        name = name,
                        exportPath = exportPathPrefix + name,
                        kind = symbolKindForJavaMember(member.memberKind, member.valueType),
                        type = member.valueType,
                        range = null
                    )
                }
            }
            return output
        }

        val classType = type as? ClassType ?: return emptyList()
        classType.getAllFields().forEach { (name, memberType) ->
            output += ModuleExportSurface.MemberExport(
                name = name,
                exportPath = exportPathPrefix + name,
                kind = io.github.dingyi222666.luaparser.semantic.api.SymbolKind.FIELD,
                type = memberType,
                range = null
            )
        }
        classType.getAllMethods().forEach { (name, memberType) ->
            output += ModuleExportSurface.MemberExport(
                name = name,
                exportPath = exportPathPrefix + name,
                kind = io.github.dingyi222666.luaparser.semantic.api.SymbolKind.METHOD,
                type = memberType,
                range = null
            )
        }
        return output
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
        private const val MAX_REFLECTED_HIERARCHY_DEPTH = 32
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
