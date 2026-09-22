package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.interop.dex.DexClass
import io.github.dingyi222666.luaparser.interop.dex.parseDex
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.util.zip.ZipFile

/**
 * Process-wide mounter for dex library files (`.dex`, and `.apk` via its bundled
 * `classes*.dex` zip entries) behind the workspace JVM provider.
 *
 * Division of labor with the rest of the interop stack:
 * - this object owns file classification, parse-once-per-(path, mtime, size)
 *   caching, name resolution (dotted binary / nested `$` / simple alias) and the
 *   per-class [ModuleType] cache;
 * - [DexClassModelAdapter] turns a [DexClass] into the reflected-shape
 *   [ModuleType] (`__class` / `__call` / static / instance members);
 * - [JvmClassModuleProvider] owns provider snapshots, workspace symbols and the
 *   actual resolution wiring (classpath entries, `path:Class` import prefixes,
 *   bare `import "libs/classes.dex"` targets).
 *
 * Optimized device artifacts (`.odex` / `.vdex`) are NOT mountable — they stay on
 * the unsupported-diagnostic path in [JvmWorkspaceConfiguration].
 *
 * Never throws on malformed input: [parseDex] reports through diagnostics which
 * this layer swallows (no provider diagnostics channel is reachable here), and an
 * unparsable / empty library resolves to `null` so callers never mount an empty
 * surface.
 */
object DexLibraryMounter {

    /** File extensions carrying a dex payload this mounter can load. */
    val MOUNTABLE_EXTENSIONS: Set<String> = setOf("dex", "apk")

    /** Optimized dex artifacts that remain unsupported (diagnostic path only). */
    val ODEX_LIKE_EXTENSIONS: Set<String> = setOf("odex", "vdex")

    /**
     * Lua std module names a dex class simple-name ALIAS must never resolve bare
     * (mirror of the `JvmClassModuleProvider` / `DexClassModelAdapter` guard:
     * `bindClass("string")` must not land on a dex class named `string`).
     * Dotted binary-name resolution is unaffected — `android.R.string` style
     * targets always resolve; only the bare-alias surface is guarded.
     */
    private val LUA_STD_MODULE_NAMES: Set<String> = setOf(
        "bit32", "coroutine", "debug", "io", "math", "os", "package", "string", "table", "utf8"
    )

    /** Hard bound on dot→`$` nested-candidate enumeration for pathological inputs. */
    private const val MAX_NESTED_CANDIDATE_SEGMENTS = 12

    /** Process-wide parse cache bound (libraries are MB-scale; a handful suffices). */
    private const val MAX_CACHED_LIBRARIES = 8

    /**
     * Parse-once cache keyed by (absolute path + lastModified + length). Provider
     * instances are created per workspace update, so instance-level caches would
     * re-parse a 1.4MB dex per keystroke; this companion cache mirrors the
     * single-extraction precedent of `JvmClassModuleProvider.companionRuntimeJarRef`.
     * LRU with access order so long sessions bound resident parsed classes.
     */
    private val libraryCache: MutableMap<String, DexLibrary> =
        Collections.synchronizedMap(object : LinkedHashMap<String, DexLibrary>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DexLibrary>?): Boolean {
                return size > MAX_CACHED_LIBRARIES
            }
        })

    /** True when [file] is an existing, readable dex/apk library this mounter can load. */
    fun isMountableDexLibrary(file: File): Boolean {
        return file.isFile && file.extension.lowercase() in MOUNTABLE_EXTENSIONS
    }

    /** Path-string convenience for [isMountableDexLibrary] (trims surrounding blanks). */
    fun isMountableDexLibraryPath(path: String): Boolean {
        return isMountableDexLibrary(File(path.trim()))
    }

    /**
     * Parsed, cached view of [file], or null when the file is missing, is not a
     * mountable dex/apk library, or parses to zero classes.
     */
    fun libraryFor(file: File): DexLibrary? {
        if (!isMountableDexLibrary(file)) {
            return null
        }
        val identity = cacheIdentity(file)
        libraryCache[identity]?.let { return it }
        val classes = readClasses(file)
        if (classes.isEmpty()) {
            return null
        }
        val library = DexLibrary(file, identity, classes)
        libraryCache[identity] = library
        return library
    }

    private fun cacheIdentity(file: File): String {
        return "${file.absolutePath}#${file.lastModified()}#${file.length()}"
    }

    /**
     * Raw dex classes for [file]: `.dex` parses the file bytes directly; `.apk`
     * opens the zip and merges every `classes<N>.dex` entry in name order
     * (classes.dex, classes2.dex, ...). Parse failures degrade to an empty list.
     */
    private fun readClasses(file: File): List<DexClass> {
        return runCatching {
            when (file.extension.lowercase()) {
                "apk" -> ZipFile(file).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && Regex("classes\\d*\\.dex").matches(it.name.substringAfterLast('/')) }
                        .sortedBy { it.name }
                        .flatMap { entry -> parseDex(zip.getInputStream(entry).readBytes()).classes.asSequence() }
                        .toList()
                }
                else -> parseDex(file.readBytes()).classes
            }
        }.getOrDefault(emptyList()).distinctBy(DexClass::binaryName)
    }

    /**
     * A parsed dex library: name-resolution indexes plus the per-class module
     * cache. Instances are immutable and shared across provider instances, so the
     * module cache is synchronized.
     */
    class DexLibrary internal constructor(
        val source: File,
        val cacheIdentity: String,
        val classes: List<DexClass>
    ) {
        private val bySlashBinaryName: Map<String, DexClass> = classes.associateBy(DexClass::binaryName)
        private val bySimpleAlias: Map<String, List<DexClass>> = classes.groupBy(::simpleAliasOf)
        private val moduleTypeCache: MutableMap<String, ModuleType> =
            Collections.synchronizedMap(LinkedHashMap())

        /**
         * Resolve a target against this library.
         *
         * Accepted forms (mirroring `JvmClassModuleProvider.candidateClassNames`):
         * - dotted binary name: `com.foo.Bar`
         * - slash binary name: `com/foo/Bar` (bare `import "libs/classes.dex"` targets
         *   are NOT class names, so path-shaped inputs simply miss)
         * - dotted nested names: `com.foo.Outer.Inner` → `com/foo/Outer$Inner`
         * - binary `$` names: `com.foo.Outer$Inner`
         * - AndroLua underscore aliases: `com.foo.Outer_Inner` → `com/foo/Outer$Inner`
         */
        fun classForTarget(target: String): DexClass? {
            val trimmed = target.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            bySlashBinaryName[trimmed]?.let { return it }
            bySlashBinaryName[trimmed.replace('.', '/')]?.let { return it }
            if ('_' in trimmed) {
                bySlashBinaryName[trimmed.replace('_', '$').replace('.', '/')]?.let { return it }
            }
            // Dotted-nested subset enumeration: replace each dot with '$' to find
            // `Outer.Inner` → `Outer$Inner` style binary names.
            val separatorIndexes = trimmed.indices.filter { trimmed[it] == '.' }
            if (separatorIndexes.isEmpty() || separatorIndexes.size > MAX_NESTED_CANDIDATE_SEGMENTS) {
                return null
            }
            val combinations = 1 shl separatorIndexes.size
            for (mask in 1 until combinations) {
                val chars = trimmed.toCharArray()
                separatorIndexes.forEachIndexed { index, separator ->
                    if ((mask and (1 shl index)) != 0) {
                        chars[separator] = '$'
                    }
                }
                bySlashBinaryName[String(chars).replace('.', '/')]?.let { return it }
            }
            return null
        }

        /**
         * Simple-name alias resolution (bare `Greeter` after `import "libs/classes.dex"`).
         * Ambiguous aliases (two dex classes sharing the simple name) never resolve —
         * no arbitrary wins; std-colliding names never resolve bare (see the guard above).
         */
        fun classForSimpleAlias(simple: String): DexClass? {
            val trimmed = simple.trim()
            if (trimmed.isEmpty() || trimmed in LUA_STD_MODULE_NAMES) {
                return null
            }
            if ('.' in trimmed || '/' in trimmed || '$' in trimmed) {
                return null
            }
            return bySimpleAlias[trimmed]?.singleOrNull()
        }

        /** Exactly one class for [binaryName] (slash form), or null. */
        fun classForBinaryName(binaryName: String): DexClass? {
            return bySlashBinaryName[binaryName]
        }

        /**
         * Adapter-built module table for [cls], cached per library instance.
         * Built against the FULL dex class set so same-dex member references
         * hydrate one level of member surface (adapter policy).
         */
        fun moduleTypeFor(cls: DexClass): ModuleType {
            moduleTypeCache[cls.binaryName]?.let { return it }
            val moduleType = DexClassModelAdapter.cachedModuleType(cls, classes)
            moduleTypeCache[cls.binaryName] = moduleType
            return moduleType
        }

        private fun simpleAliasOf(cls: DexClass): String {
            return cls.binaryName.substringAfterLast('/').substringAfterLast('$')
        }
    }
}
