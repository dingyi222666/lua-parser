package io.github.dingyi222666.luaparser.interop.jvm

import java.io.File

/**
 * JVM-specific workspace configuration for reflective module providers.
 *
 * This offers a structured alternative to raw metadata strings while still
 * serializing into workspace metadata so snapshots and update flows can retain
 * reproducible provider configuration.
 */
data class JvmWorkspaceConfiguration(
    val classLoader: ClassLoader? = null,
    val classpathEntries: List<String> = emptyList(),
    val androidJar: String? = null,
    val classes: Set<String> = emptySet(),
    val androluaImports: List<String> = emptyList(),
    val importPrefixes: List<String> = emptyList()
) {
    fun effectiveClasspathEntries(): List<String> = buildList {
        classpathEntries
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach(::add)
        androidJar
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::add)
    }

    fun androidJarConfigurationNote(
        environment: Map<String, String> = System.getenv(),
        userHome: File = defaultUserHome(),
        localAppData: String? = environmentValue(environment, LOCAL_APPDATA_ENV)
    ): String? {
        if (!androidJar.isNullOrBlank()) {
            return "$ANDROID_JAR_METADATA_KEY is configured explicitly; Android SDK environment discovery is skipped."
        }
        val envDiscovery = discoverAndroidJar(environment)
        if (envDiscovery.path != null) {
            return envDiscovery.note
        }
        val wellKnown = discoverWellKnownAndroidJar(userHome, localAppData)
        if (wellKnown.path != null) {
            return wellKnown.note
        }
        // Soft-skip reason when metadata, env, and well-known SDK roots all miss a jar.
        // Downloads copies are never auto-selected; pass them only via jvm.androidJar.
        // Never invent a silent G: classpath fallback when discovery fails.
        return envDiscovery.note?.let { note ->
            if (note.contains("well-known") || note.contains("Library/Android/sdk")) {
                note
            } else {
                note.trimEnd('.') + "; well-known host SDK roots " +
                    "(macOS ~/Library/Android/sdk, Linux ~/Android/Sdk, Windows %LOCALAPPDATA%/Android/Sdk) " +
                    "also yielded no platforms/android-*/android.jar. " +
                    "Set $ANDROID_JAR_METADATA_KEY explicitly (Downloads jars are metadata-only) " +
                    "or install an Android SDK platform."
            }
        } ?: (
            "No $ANDROID_JAR_METADATA_KEY is configured and neither $ANDROID_HOME_ENV nor " +
                "$ANDROID_SDK_ROOT_ENV nor well-known host SDK roots produced platforms/android-*/android.jar; " +
                "set $ANDROID_JAR_METADATA_KEY explicitly (Downloads jars are metadata-only) " +
                "or install an Android SDK platform."
        )
    }

    /**
     * Reflective classpath entries for JVM module providers.
     *
     * Includes explicit [classpathEntries] and [androidJar] metadata first. When
     * [androidJar] is unset, discovers an existing platform android.jar with the
     * same precedence as [DEFAULT_ANDROID_JAR_PATH]: ANDROID_HOME /
     * ANDROID_SDK_ROOT, then well-known host SDK roots (macOS
     * ~/Library/Android/sdk, Linux ~/Android/Sdk, Windows %LOCALAPPDATA%/Android/Sdk).
     * Missing jars are never invented; absolute drive-letter SDK roots such as G:
     * are never auto-selected. Downloads host jars remain an explicit metadata
     * override only. Effective classpath includes a resolved android.jar only when
     * discovery (or explicit config) succeeds.
     */
    fun reflectionClasspathEntries(
        environment: Map<String, String> = System.getenv(),
        userHome: File = defaultUserHome(),
        localAppData: String? = environmentValue(environment, LOCAL_APPDATA_ENV)
    ): List<String> = buildList {
        addAll(effectiveClasspathEntries())
        if (androidJar.isNullOrBlank()) {
            discoverReflectiveAndroidJarPath(environment, userHome, localAppData)?.let(::add)
        }
    }.distinct()

    internal fun prefixedImportClasspathEntry(pathPrefix: String): File? {
        val file = File(pathPrefix.trim())
        return file.takeIf { entry ->
            entry.isDirectory || entry.isFile && entry.extension.equals("jar", ignoreCase = true)
        }
    }

    internal fun prefixedImportUnsupportedReason(pathPrefix: String): PrefixedImportUnsupportedReason? {
        if (prefixedImportClasspathEntry(pathPrefix) != null) {
            return null
        }
        return if (looksLikeDexImportPrefix(pathPrefix)) {
            PrefixedImportUnsupportedReason.ANDROID_DEX_UNSUPPORTED
        } else {
            PrefixedImportUnsupportedReason.NOT_JVM_CLASSPATH_ENTRY
        }
    }

    fun normalized(defaultImportPrefixes: List<String> = JvmClassModuleProvider.DEFAULT_IMPORT_PREFIXES): JvmWorkspaceConfiguration {
        return copy(
            classpathEntries = classpathEntries.map(String::trim).filter(String::isNotEmpty),
            androidJar = androidJar?.trim()?.takeIf(String::isNotEmpty),
            classes = classes.map(String::trim).filter(String::isNotEmpty).toCollection(linkedSetOf()),
            androluaImports = androluaImports.map(String::trim).filter(String::isNotEmpty),
            importPrefixes = normalizedImportPrefixes(defaultImportPrefixes)
        )
    }

    private fun normalizedOverride(): JvmWorkspaceConfiguration {
        return copy(
            classpathEntries = classpathEntries.map(String::trim).filter(String::isNotEmpty),
            androidJar = androidJar?.trim()?.takeIf(String::isNotEmpty),
            classes = classes.map(String::trim).filter(String::isNotEmpty).toCollection(linkedSetOf()),
            androluaImports = androluaImports.map(String::trim).filter(String::isNotEmpty),
            importPrefixes = importPrefixes.map(String::trim).filter(String::isNotEmpty)
        )
    }

    fun overlay(overrides: JvmWorkspaceConfiguration): JvmWorkspaceConfiguration {
        val base = normalized()
        val extra = overrides.normalizedOverride()
        return JvmWorkspaceConfiguration(
            classLoader = extra.classLoader ?: base.classLoader,
            classpathEntries = if (extra.classpathEntries.isNotEmpty()) extra.classpathEntries else base.classpathEntries,
            androidJar = extra.androidJar ?: base.androidJar,
            classes = (base.classes + extra.classes).toCollection(linkedSetOf()),
            androluaImports = (base.androluaImports + extra.androluaImports).distinct(),
            importPrefixes = if (overrides.hasExplicitImportPrefixes()) extra.importPrefixes else base.importPrefixes
        )
    }

    fun applyToMetadata(metadata: Map<String, String>): Map<String, String> {
        val normalized = normalized()
        val result = metadata.toMutableMap()
        result[JvmClassModuleProvider.CLASSES_METADATA_KEY] = normalized.classes.joinToString("\n")
        result[JvmClassModuleProvider.IMPORTS_METADATA_KEY] = normalized.androluaImports.joinToString("\n")
        result[CLASSPATH_METADATA_KEY] = normalized.classpathEntries.joinToString("\n")
        normalized.androidJar?.let { result[ANDROID_JAR_METADATA_KEY] = it } ?: result.remove(ANDROID_JAR_METADATA_KEY)
        if (normalized.hasExplicitImportPrefixes()) {
            result[IMPORT_PREFIXES_METADATA_KEY] = normalized.importPrefixes.joinToString("\n")
        } else {
            result.remove(IMPORT_PREFIXES_METADATA_KEY)
        }
        return result
    }

    private fun normalizedImportPrefixes(defaultImportPrefixes: List<String>): List<String> {
        val explicit = hasExplicitImportPrefixes()
        val prefixes = importPrefixes.map(String::trim).filter(String::isNotEmpty).ifEmpty { defaultImportPrefixes }
        return MetadataImportPrefixes(prefixes, explicit)
    }

    private fun hasExplicitImportPrefixes(): Boolean {
        val metadataPrefixes = importPrefixes as? MetadataImportPrefixes
        return metadataPrefixes?.explicit ?: importPrefixes.any { it.trim().isNotEmpty() }
    }

    private class MetadataImportPrefixes(
        private val values: List<String>,
        val explicit: Boolean
    ) : AbstractList<String>() {
        override val size: Int
            get() = values.size

        override fun get(index: Int): String = values[index]
    }

    companion object {
        const val CLASSPATH_METADATA_KEY = "jvm.classpath"
        const val ANDROID_JAR_METADATA_KEY = "jvm.androidJar"
        const val IMPORT_PREFIXES_METADATA_KEY = "jvm.importPrefixes"
        const val ANDROID_HOME_ENV = "ANDROID_HOME"
        const val ANDROID_SDK_ROOT_ENV = "ANDROID_SDK_ROOT"
        const val LOCAL_APPDATA_ENV = "LOCALAPPDATA"

        /**
         * Host-resolved default android.jar path for tests and convenience consumers.
         *
         * Prefers ANDROID_HOME / ANDROID_SDK_ROOT, then well-known SDK install roots
         * (macOS ~/Library/Android/sdk, Linux ~/Android/Sdk, Windows
         * %LOCALAPPDATA%/Android/Sdk and user-home AppData layouts). When a real jar is
         * present it returns that absolute path; otherwise it returns a host-preferred
         * candidate under platforms/android-35 that may not exist (callers must check
         * File.isFile and soft-skip when absent). Never hard-requires a missing Windows
         * AppData android-35 path alone when another present host jar can be discovered.
         * Joint green-lock and sibling isolation suites must soft-skip with
         * [missingAndroidJarSoftSkipReason] when discovery returns a non-file candidate.
         *
         * Downloads jars and other non-SDK copies are never auto-selected; pass them via
         * ANDROID_JAR_METADATA_KEY / androidJar only. Absolute drive-letter roots such as
         * G: are never auto-selected as inventing defaults.
         */
        val DEFAULT_ANDROID_JAR_PATH: String
            get() = resolveDefaultAndroidJarPath()

        private val ANDROID_PLATFORM_DIRECTORY = Regex("""android-(\d+)""")
        private val ANDROID_DEX_IMPORT_EXTENSIONS = setOf("dex", "apk", "odex", "vdex")
        /** Preferred platform API for absent-jar candidate messaging (host dual-path hard-lock). */
        private const val PREFERRED_PLATFORM_API = 35
        /**
         * Absolute drive-letter SDK roots that must never be auto-discovered.
         * They may still be used when ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar
         * explicitly points at them.
         */
        private val FORBIDDEN_AUTO_SDK_ROOT_PREFIXES = listOf(
            "G:/Android/Sdk",
            "G:\\Android\\Sdk"
        )

        fun fromMetadata(metadata: Map<String, String>): JvmWorkspaceConfiguration {
            return JvmWorkspaceConfiguration(
                classpathEntries = metadata[CLASSPATH_METADATA_KEY]
                    .orEmpty()
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList(),
                androidJar = metadata[ANDROID_JAR_METADATA_KEY]
                    ?.trim()
                    ?.takeIf(String::isNotEmpty),
                classes = metadata[JvmClassModuleProvider.CLASSES_METADATA_KEY]
                    .orEmpty()
                    .split(',', ';', '\n')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toCollection(linkedSetOf()),
                androluaImports = metadata[JvmClassModuleProvider.IMPORTS_METADATA_KEY]
                    .orEmpty()
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList(),
                importPrefixes = metadata[IMPORT_PREFIXES_METADATA_KEY]
                    .orEmpty()
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList()
                    .let { MetadataImportPrefixes(it, metadata.containsKey(IMPORT_PREFIXES_METADATA_KEY)) }
            ).normalized()
        }

        /**
         * Resolve a host-local default android.jar path for tests/docs convenience.
         *
         * Order: ANDROID_HOME, ANDROID_SDK_ROOT, then well-known host SDK roots.
         * Never invents a classpath entry for a missing jar: when no jar exists, returns a
         * preferred candidate under platforms/android-35 for messaging/skip guards only.
         * Explicit Downloads jars are not auto-selected; pass them via androidJar metadata.
         * Absolute G: SDK roots are never auto-selected.
         */
        fun resolveDefaultAndroidJarPath(
            environment: Map<String, String> = System.getenv(),
            userHome: File = defaultUserHome(),
            localAppData: String? = environmentValue(environment, LOCAL_APPDATA_ENV)
        ): String {
            discoverReflectiveAndroidJarPath(environment, userHome, localAppData)?.let { return it }
            return preferredDefaultAndroidJarCandidate(userHome, localAppData).path
        }

        /**
         * Existing platform android.jar for reflective classpaths / defaults.
         *
         * Same discovery order as [DEFAULT_ANDROID_JAR_PATH] without inventing a
         * missing candidate path. Explicit Downloads jars are not auto-selected;
         * pass them via androidJar metadata. Absolute G: SDK roots are never
         * auto-selected as inventing defaults (env/metadata may still point there).
         */
        fun discoverReflectiveAndroidJarPath(
            environment: Map<String, String> = System.getenv(),
            userHome: File = defaultUserHome(),
            localAppData: String? = environmentValue(environment, LOCAL_APPDATA_ENV)
        ): String? {
            discoverAndroidJar(environment).path?.let { return it }
            return discoverWellKnownAndroidJar(userHome, localAppData).path
        }

        /**
         * Explicit soft-skip reason when no reflective android.jar is available.
         *
         * Used by Android-Lua workspace/import suites when ANDROID_JAR_METADATA_KEY is unset
         * and env + well-known SDK discovery both miss. Never invents a jar; Downloads paths
         * remain metadata-only. Absolute G: roots are never auto-selected, so isolation tests
         * with empty env/userHome do not claim presence solely because a G: android-36 jar
         * exists on the agent.
         */
        fun missingAndroidJarSoftSkipReason(
            environment: Map<String, String> = System.getenv(),
            userHome: File = defaultUserHome(),
            localAppData: String? = environmentValue(environment, LOCAL_APPDATA_ENV),
            taskId: String? = null
        ): String {
            val discovered = discoverReflectiveAndroidJarPath(environment, userHome, localAppData)
            if (discovered != null) {
                return "android.jar is present at $discovered; soft-skip is not required."
            }
            val note = JvmWorkspaceConfiguration().androidJarConfigurationNote(environment, userHome, localAppData)
                ?: (
                    "No $ANDROID_JAR_METADATA_KEY is configured and Android SDK discovery " +
                        "(ANDROID_HOME/ANDROID_SDK_ROOT then well-known host SDK roots) found no " +
                        "platforms/android-*/android.jar; set $ANDROID_JAR_METADATA_KEY explicitly " +
                        "(Downloads jars are metadata-only) or install an Android SDK platform."
                )
            val prefix = taskId?.takeIf { it.isNotBlank() }?.let { "$it: " }.orEmpty()
            return prefix + note
        }

        private fun discoverAndroidJar(environment: Map<String, String>): AndroidJarDiscovery {
            val attempts = listOf(ANDROID_HOME_ENV, ANDROID_SDK_ROOT_ENV)
                .mapNotNull { name ->
                    environmentValue(environment, name)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { value -> AndroidSdkAttempt(name, File(value), highestAndroidPlatformJar(File(value))) }
                }

            if (attempts.isEmpty()) {
                return AndroidJarDiscovery(
                    path = null,
                    note = "No $ANDROID_JAR_METADATA_KEY is configured and neither $ANDROID_HOME_ENV nor " +
                        "$ANDROID_SDK_ROOT_ENV is set; Android framework classes remain unavailable."
                )
            }

            val selected = attempts.firstOrNull { it.androidJar != null }
            if (selected != null) {
                val selectedJar = selected.androidJar?.file ?: return AndroidJarDiscovery(path = null, note = null)
                val note = buildString {
                    append("Discovered android.jar from ${selected.environmentName}: ${selectedJar.path}.")
                    if (hasLowerPrioritySdkWithDifferentJar(attempts, selected)) {
                        append(" Multiple Android SDK environment variables contain platform jars; ")
                        append("${selected.environmentName} was selected by precedence. ")
                        append("Set $ANDROID_JAR_METADATA_KEY explicitly to avoid ambiguity.")
                    }
                }
                return AndroidJarDiscovery(path = selectedJar.path, note = note)
            }

            return AndroidJarDiscovery(
                path = null,
                note = "No $ANDROID_JAR_METADATA_KEY is configured and Android SDK environment discovery found no " +
                    "platforms/android-*/android.jar under " +
                    attempts.joinToString { "${it.environmentName}=${it.sdkRoot.path}" } +
                    "; set $ANDROID_JAR_METADATA_KEY explicitly or install an Android SDK platform."
            )
        }

        private fun discoverWellKnownAndroidJar(
            userHome: File,
            localAppData: String?
        ): AndroidJarDiscovery {
            val discovered = wellKnownSdkRoots(userHome, localAppData)
                .asSequence()
                .filter { it.isDirectory }
                .filterNot { isForbiddenAutoSdkRoot(it) }
                .mapNotNull { sdkRoot ->
                    preferredAndroidPlatformJar(sdkRoot)?.let { jar -> sdkRoot to jar }
                }
                .toList()

            if (discovered.isEmpty()) {
                return AndroidJarDiscovery(path = null, note = null)
            }

            // Prefer preferred API (35) when present, else highest installed platform.
            // Never auto-select absolute G: inventing roots (filtered above).
            val preferred = discovered
                .sortedWith(
                    compareByDescending<Pair<File, AndroidPlatformJar>> {
                        if (it.second.apiLevel == PREFERRED_PLATFORM_API) 1 else 0
                    }
                        .thenByDescending { it.second.apiLevel }
                        .thenBy { it.first.path }
                )
                .first()

            val (sdkRoot, platformJar) = preferred
            return AndroidJarDiscovery(
                path = platformJar.file.path,
                note = "Discovered android.jar from well-known Android SDK location ${sdkRoot.path}: ${platformJar.file.path}."
            )
        }

        private fun preferredDefaultAndroidJarCandidate(userHome: File, localAppData: String?): File {
            // Prefer an existing non-forbidden well-known SDK root for messaging/skip paths.
            // Never invent absolute G: candidates. Host dual-path hard-lock prefers
            // platforms/android-35 under macOS Library/Android/sdk or Windows
            // %LOCALAPPDATA%/Android/Sdk / user-home AppData layouts when those roots exist.
            // If LOCALAPPDATA is set but empty, fall through to other present roots instead
            // of hard-locking the missing AppData android-35 path alone.
            val roots = wellKnownSdkRoots(userHome, localAppData)
                .filterNot { isForbiddenAutoSdkRoot(it) }
            val preferredRoot = roots.firstOrNull { root ->
                root.isDirectory && preferredAndroidPlatformJar(root) != null
            }
                ?: roots.firstOrNull { it.isDirectory }
                ?: roots.firstOrNull()
                ?: File(userHome, "Android/Sdk")
            // If the chosen root already has a real jar, surface that path (preferred API
            // first). Otherwise keep the platforms/android-35 messaging candidate.
            preferredAndroidPlatformJar(preferredRoot)?.let { return it.file }
            return File(preferredRoot, "platforms/android-$PREFERRED_PLATFORM_API/android.jar")
        }

        private fun wellKnownSdkRoots(userHome: File, localAppData: String?): List<File> {
            val osName = System.getProperty("os.name").orEmpty().lowercase()
            val ordered = mutableListOf<File>()
            when {
                osName.contains("mac") || osName.contains("darwin") -> {
                    ordered += File(userHome, "Library/Android/sdk")
                    ordered += File(userHome, "Android/Sdk")
                    ordered += File(userHome, "Android/sdk")
                    localAppData?.takeIf { it.isNotBlank() }?.let { ordered += File(it, "Android/Sdk") }
                    ordered += File(userHome, "AppData/Local/Android/Sdk")
                }
                osName.contains("win") -> {
                    // Windows dual-path: LOCALAPPDATA first, then user-home AppData, then
                    // portable layouts. Do not sole-hardcode G: or mac-only absolute paths.
                    localAppData?.takeIf { it.isNotBlank() }?.let { ordered += File(it, "Android/Sdk") }
                    ordered += File(userHome, "AppData/Local/Android/Sdk")
                    ordered += File(userHome, "AppData/Local/Android/sdk")
                    ordered += File(userHome, "Android/Sdk")
                    ordered += File(userHome, "Android/sdk")
                    ordered += File(userHome, "Library/Android/sdk")
                }
                else -> {
                    ordered += File(userHome, "Android/Sdk")
                    ordered += File(userHome, "Android/sdk")
                    ordered += File(userHome, "Library/Android/sdk")
                    localAppData?.takeIf { it.isNotBlank() }?.let { ordered += File(it, "Android/Sdk") }
                    ordered += File(userHome, "AppData/Local/Android/Sdk")
                }
            }
            // Absolute drive-letter inventing roots (G:/Android/Sdk) are intentionally
            // excluded from well-known auto-discovery so empty-home isolation tests and
            // multi-host agents never claim presence solely because android-36 exists there.
            return ordered
                .filterNot { isForbiddenAutoSdkRoot(it) }
                .distinctBy { it.absolutePath }
        }

        private fun isForbiddenAutoSdkRoot(root: File): Boolean {
            val normalized = root.path.replace('\\', '/').trimEnd('/')
            return FORBIDDEN_AUTO_SDK_ROOT_PREFIXES.any { prefix ->
                val prefixNorm = prefix.replace('\\', '/').trimEnd('/')
                normalized.equals(prefixNorm, ignoreCase = true) ||
                    normalized.startsWith("$prefixNorm/", ignoreCase = true)
            }
        }

        private fun defaultUserHome(): File {
            return File(System.getProperty("user.home") ?: ".")
        }

        private fun environmentValue(environment: Map<String, String>, name: String): String? {
            return environment[name] ?: environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        /**
         * Prefer platforms/android-35 when present (host dual-path hard-lock), else highest API.
         * Never invents a missing jar.
         */
        private fun preferredAndroidPlatformJar(sdkRoot: File): AndroidPlatformJar? {
            val jars = androidPlatformJars(sdkRoot)
            if (jars.isEmpty()) {
                return null
            }
            return jars.firstOrNull { it.apiLevel == PREFERRED_PLATFORM_API }
                ?: jars.maxByOrNull { it.apiLevel }
        }

        private fun highestAndroidPlatformJar(sdkRoot: File): AndroidPlatformJar? {
            // Env discovery keeps highest installed platform (existing ANDROID_HOME contract).
            return androidPlatformJars(sdkRoot).maxByOrNull { it.apiLevel }
        }

        private fun androidPlatformJars(sdkRoot: File): List<AndroidPlatformJar> {
            val platformDirectories = File(sdkRoot, "platforms").listFiles().orEmpty()
            return platformDirectories
                .asSequence()
                .filter { it.isDirectory }
                .mapNotNull { platformDirectory ->
                    val apiLevel = ANDROID_PLATFORM_DIRECTORY
                        .matchEntire(platformDirectory.name)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?: return@mapNotNull null
                    val androidJar = File(platformDirectory, "android.jar")
                    if (androidJar.isFile) AndroidPlatformJar(apiLevel, androidJar) else null
                }
                .sortedWith(compareByDescending<AndroidPlatformJar> { it.apiLevel }.thenBy { it.file.path })
                .toList()
        }

        private fun hasLowerPrioritySdkWithDifferentJar(
            attempts: List<AndroidSdkAttempt>,
            selected: AndroidSdkAttempt
        ): Boolean {
            val selectedIndex = attempts.indexOf(selected)
            return attempts
                .drop(selectedIndex + 1)
                .mapNotNull { it.androidJar?.file }
                .any { !sameFile(it, selected.androidJar?.file) }
        }

        private fun sameFile(left: File?, right: File?): Boolean {
            if (left == null || right == null) {
                return false
            }
            return runCatching { left.canonicalFile == right.canonicalFile }
                .getOrElse { left.absolutePath == right.absolutePath }
        }

        private data class AndroidJarDiscovery(
            val path: String?,
            val note: String?
        )

        private data class AndroidSdkAttempt(
            val environmentName: String,
            val sdkRoot: File,
            val androidJar: AndroidPlatformJar?
        )

        private data class AndroidPlatformJar(
            val apiLevel: Int,
            val file: File
        )

        private fun looksLikeDexImportPrefix(pathPrefix: String): Boolean {
            val trimmed = pathPrefix.trim()
            val extension = File(trimmed).extension.lowercase()
            if (extension in ANDROID_DEX_IMPORT_EXTENSIONS) {
                return true
            }
            return File(trimmed).name.equals("dexPath", ignoreCase = true)
        }
    }
}

internal enum class PrefixedImportUnsupportedReason(
    val diagnosticDetail: String
) {
    ANDROID_DEX_UNSUPPORTED(
        "Android dex/apk import prefixes are not loadable by the JVM reflection provider; " +
            "convert the dex input to a JVM jar or add a class-directory root to jvm.classpath."
    ),
    NOT_JVM_CLASSPATH_ENTRY(
        "Import prefixes must name an existing JVM classpath directory or jar."
    )
}
