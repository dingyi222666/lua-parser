package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.semantic.WorkspaceImportedSymbol
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.workspaceFingerprintHash
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths

class JvmClassModuleProvider(
    private val baseClassLoader: ClassLoader = JvmClassModuleProvider::class.java.classLoader,
    private val defaultImportPrefixes: List<String> = DEFAULT_IMPORT_PREFIXES
) {
    fun providersFor(metadata: Map<String, String>): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        return providersFor(JvmWorkspaceConfiguration.fromMetadata(metadata))
    }

    fun providersFor(configuration: JvmWorkspaceConfiguration): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        val requested = requestedClasses(normalized, classLoader)
        if (requested.isEmpty()) {
            return emptyMap()
        }

        return requested.mapNotNull { className ->
            runCatching { Class.forName(className, false, classLoader) }
                .getOrNull()
                ?.let(::providerForClass)
        }.associate { it.first to it.second }
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
        return resolveImport(importText, defaultImportPrefixes, baseClassLoader)
    }

    internal fun resolveImport(importText: String, configuration: JvmWorkspaceConfiguration): String? {
        val normalized = configuration.normalized(defaultImportPrefixes)
        return resolveImport(importText, normalized.importPrefixes, normalized.classLoader ?: classLoaderFor(normalized))
    }

    internal fun importedClassName(importText: String, configuration: JvmWorkspaceConfiguration): String? {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        return resolveImport(importText, normalized.importPrefixes, classLoader)
    }

    internal fun importedClassNames(importText: String, configuration: JvmWorkspaceConfiguration): List<String> {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        return resolveImports(importText, normalized.importPrefixes, classLoader)
    }

    internal fun importedSymbol(importText: String, configuration: JvmWorkspaceConfiguration): WorkspaceImportedSymbol? {
        val normalized = configuration.normalized(defaultImportPrefixes)
        val classLoader = normalized.classLoader ?: classLoaderFor(normalized)
        val className = resolveImport(importText, normalized.importPrefixes, classLoader) ?: return null
        val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: return null
        val (providerPath, _) = providerForClass(clazz)
        return WorkspaceImportedSymbol(
            alias = clazz.simpleName,
            moduleName = clazz.simpleName,
            providerPath = providerPath,
            moduleType = moduleTypeFor(clazz)
        )
    }

    private fun requestedClasses(configuration: JvmWorkspaceConfiguration, classLoader: ClassLoader): Set<String> {
        val explicit = configuration.classes.flatMapTo(linkedSetOf()) { className ->
            resolveImports(className, configuration.importPrefixes, classLoader)
        }
        val imports = configuration.androluaImports
            .flatMapTo(linkedSetOf()) { resolveImports(it, configuration.importPrefixes, classLoader) }
        return (explicit + imports).toCollection(linkedSetOf())
    }

    private fun resolveImport(importText: String, importPrefixes: List<String>, classLoader: ClassLoader): String? {
        return resolveImports(importText, importPrefixes, classLoader).firstOrNull()
    }

    private fun resolveImports(importText: String, importPrefixes: List<String>, classLoader: ClassLoader): List<String> {
        val normalized = importText.removePrefix("import ").trim()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        if (normalized.endsWith(".*")) {
            return resolveWildcardImports(normalized.removeSuffix(".*"), classLoader)
        }
        if ('.' in normalized) {
            return listOf(normalized)
        }
        return importPrefixes.firstNotNullOfOrNull { prefix ->
            val candidate = "$prefix.$normalized"
            runCatching { Class.forName(candidate, false, classLoader) }.getOrNull()?.name?.let(::listOf)
        }.orEmpty()
    }

    private fun resolveWildcardImports(packageName: String, classLoader: ClassLoader): List<String> {
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
        return classNames.toList()
    }

    private fun collectClassesFromDirectory(directoryPath: String, packageName: String, output: MutableSet<String>) {
        val root = runCatching { Paths.get(URI.create("file:$directoryPath")) }
            .recoverCatching { Paths.get(directoryPath) }
            .getOrNull()
            ?: return
        if (!Files.isDirectory(root)) {
            return
        }
        Files.list(root).use { entries ->
            entries
                .filter { entry -> Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".class") }
                .forEach { entry ->
                    val fileName = entry.fileName.toString().removeSuffix(".class")
                    if ('$' !in fileName) {
                        output += "$packageName.$fileName"
                    }
                }
        }
    }

    private fun collectClassesFromJar(url: java.net.URL, packagePath: String, output: MutableSet<String>) {
        val connection = runCatching { url.openConnection() as JarURLConnection }.getOrNull() ?: return
        val jarFile = runCatching { connection.jarFile }.getOrNull() ?: return
        jarFile.use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("$packagePath/") && it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class") }
                .filter { name -> '/' !in name.removePrefix("$packagePath/") }
                .filter { '$' !in it.substringAfterLast('/') }
                .forEach { entry -> output += entry.replace('/', '.') }
        }
    }

    private fun collectClassesFromJrt(packagePath: String, output: MutableSet<String>) {
        val root = runCatching { Paths.get(URI.create("jrt:/modules")) }.getOrNull() ?: return
        if (!Files.isDirectory(root)) {
            return
        }
        Files.list(root).use { modules ->
            modules.forEach { module ->
                val packageDir = module.resolve(packagePath)
                if (!Files.isDirectory(packageDir)) {
                    return@forEach
                }
                Files.list(packageDir).use { entries ->
                    entries
                        .filter { entry -> Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".class") }
                        .forEach { entry ->
                            val fileName = entry.fileName.toString().removeSuffix(".class")
                            if ('$' !in fileName) {
                                output += "${packagePath.replace('/', '.')}.$fileName"
                            }
                        }
                }
            }
        }
    }

    private fun classLoaderFor(configuration: JvmWorkspaceConfiguration): ClassLoader {
        val entries = configuration.effectiveClasspathEntries()
        if (entries.isEmpty()) {
            return baseClassLoader
        }
        val urls = entries
            .map(::File)
            .map(File::toURI)
            .map { it.toURL() }
            .toTypedArray()
        return URLClassLoader(urls, baseClassLoader)
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

    private fun moduleTypeFor(clazz: Class<*>): ModuleType {
        val classType = classTypeFor(clazz)
        val fields = linkedMapOf<String, Type>("__class" to classType)
        val methods = linkedMapOf<String, Type>()

        clazz.methods
            .filter { Modifier.isStatic(it.modifiers) }
            .groupBy(Method::getName)
            .forEach { (name, overloads) ->
                methods[name] = overloadFunctionType(overloads)
            }

        return ModuleType(
            moduleName = clazz.simpleName,
            fields = fields,
            methods = methods
        )
    }

    private fun classTypeFor(clazz: Class<*>): ClassType {
        val superClass = clazz.superclass
            ?.takeUnless { it == Any::class.java }
            ?.let(::typeReferenceForClass)
        val fields = clazz.fields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .associate { it.name to javaTypeToType(it.type) }
            .toSortedMap()
        val methods = clazz.methods
            .filterNot { Modifier.isStatic(it.modifiers) }
            .groupBy(Method::getName)
            .mapValues { (_, overloads) -> overloadFunctionType(overloads) }
            .toSortedMap()
        return ClassType(
            name = clazz.name,
            fields = fields,
            methods = methods,
            superClass = superClass
        )
    }

    private fun overloadFunctionType(methods: List<Method>): Type {
        val signatures = methods.map { method ->
            FunctionType(
                parameters = method.parameterTypes.mapIndexed { index, parameterType ->
                    FunctionParameter("arg${index + 1}", javaTypeToType(parameterType))
                },
                returnType = javaTypeToType(method.returnType)
            )
        }
        return when (signatures.size) {
            0 -> FunctionType(returnType = UnknownType)
            1 -> signatures.single()
            else -> OverloadedFunctionType(signatures)
        }
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
        return output.sortedBy { it.name }
    }

    private fun javaTypeToType(type: Class<*>): Type {
        return when {
            type == Void.TYPE -> PrimitiveType.NIL
            type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> PrimitiveType.BOOLEAN
            type == java.lang.Byte.TYPE || type == java.lang.Short.TYPE || type == java.lang.Integer.TYPE ||
                type == java.lang.Long.TYPE || type == java.lang.Float.TYPE || type == java.lang.Double.TYPE ||
                Number::class.java.isAssignableFrom(type) -> PrimitiveType.NUMBER
            type == java.lang.Character.TYPE || type == Char::class.java || type == String::class.java ||
                CharSequence::class.java.isAssignableFrom(type) -> PrimitiveType.STRING
            type.isArray -> ArrayType(javaTypeToType(type.componentType))
            type.isPrimitive -> PrimitiveType.ANY
            else -> typeReferenceForClass(type)
        }
    }

    private fun typeReferenceForClass(type: Class<*>): ClassType {
        return ClassType(type.name)
    }

    companion object {
        const val CLASSES_METADATA_KEY = "jvm.classes"
        const val IMPORTS_METADATA_KEY = "androlua.imports"

        val DEFAULT_IMPORT_PREFIXES: List<String> = listOf(
            "java.lang",
            "java.util",
            "java.io",
            "android.app",
            "android.content",
            "android.view",
            "android.widget"
        )
    }
}
