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

    fun androidJarConfigurationNote(environment: Map<String, String> = System.getenv()): String? {
        if (!androidJar.isNullOrBlank()) {
            return "$ANDROID_JAR_METADATA_KEY is configured explicitly; Android SDK environment discovery is skipped."
        }
        return discoverAndroidJar(environment).note
    }

    fun reflectionClasspathEntries(environment: Map<String, String> = System.getenv()): List<String> = buildList {
        addAll(effectiveClasspathEntries())
        if (androidJar.isNullOrBlank()) {
            discoverAndroidJar(environment).path?.let(::add)
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
         * Host-resolved default `android.jar` path for tests and convenience consumers.
         *
         * Prefers `ANDROID_HOME` / `ANDROID_SDK_ROOT`, then well-known SDK install roots
         * (macOS `~/Library/Android/sdk`, Linux `~/Android/Sdk`, Windows `%LOCALAPPDATA%/Android/Sdk`
         * and the documented Windows path). When a real jar is present it returns that absolute path;
         * otherwise it returns a host-preferred candidate path that may not exist
         * (callers must check [File.isFile] and skip when absent).
         */
        val DEFAULT_ANDROID_JAR_PATH: String
            get() = resolveDefaultAndroidJarPath()

        private val ANDROID_PLATFORM_DIRECTORY = Regex("""android-(\d+)""")
        private val ANDROID_DEX_IMPORT_EXTENSIONS = setOf("dex", "apk", "odex", "vdex")
        private const val WINDOWS_DOCUMENTED_SDK_ROOT = "G:/Android/Sdk"
        private const val PREFERRED_PLATFORM_API = 35

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
         * Resolve a host-local default `android.jar` path for tests/docs convenience.
         *
         * Order: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then well-known host SDK roots.
         * Never invents a classpath entry for a missing jar: when no jar exists, returns a
         * preferred candidate path for messaging/skip guards only.
         */
        fun resolveDefaultAndroidJarPath(
            environment: Map<String, String> = System.getenv(),
            userHome: File = defaultUserHome(),
            localAppData: String? = environmentValue(environment, LOCAL_APPDATA_ENV)
        ): String {
            discoverAndroidJar(environment).path?.let { return it }
            discoverWellKnownAndroidJar(userHome, localAppData).path?.let { return it }
            return preferredDefaultAndroidJarCandidate(userHome, localAppData).path
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
            val wellKnown = wellKnownSdkRoots(userHome, localAppData)
                .asSequence()
                .filter { it.isDirectory }
                .mapNotNull { sdkRoot ->
                    highestAndroidPlatformJar(sdkRoot)?.let { jar -> sdkRoot to jar }
                }
                .sortedWith(
                    compareByDescending<Pair<File, AndroidPlatformJar>> { it.second.apiLevel }
                        .thenBy { it.first.path }
                )
                .firstOrNull()
                ?: return AndroidJarDiscovery(path = null, note = null)

            val (sdkRoot, platformJar) = wellKnown
            return AndroidJarDiscovery(
                path = platformJar.file.path,
                note = "Discovered android.jar from well-known Android SDK location ${sdkRoot.path}: ${platformJar.file.path}."
            )
        }

        private fun preferredDefaultAndroidJarCandidate(userHome: File, localAppData: String?): File {
            val preferredRoot = wellKnownSdkRoots(userHome, localAppData).firstOrNull()
                ?: File(userHome, "Android/Sdk")
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
                    ordered += File(WINDOWS_DOCUMENTED_SDK_ROOT)
                }
                osName.contains("win") -> {
                    localAppData?.takeIf { it.isNotBlank() }?.let { ordered += File(it, "Android/Sdk") }
                    ordered += File(userHome, "AppData/Local/Android/Sdk")
                    ordered += File(WINDOWS_DOCUMENTED_SDK_ROOT)
                    ordered += File(userHome, "Library/Android/sdk")
                    ordered += File(userHome, "Android/Sdk")
                    ordered += File(userHome, "Android/sdk")
                }
                else -> {
                    ordered += File(userHome, "Android/Sdk")
                    ordered += File(userHome, "Android/sdk")
                    ordered += File(userHome, "Library/Android/sdk")
                    localAppData?.takeIf { it.isNotBlank() }?.let { ordered += File(it, "Android/Sdk") }
                    ordered += File(userHome, "AppData/Local/Android/Sdk")
                    ordered += File(WINDOWS_DOCUMENTED_SDK_ROOT)
                }
            }
            return ordered.distinctBy { it.absolutePath }
        }

        private fun defaultUserHome(): File {
            return File(System.getProperty("user.home") ?: ".")
        }

        private fun environmentValue(environment: Map<String, String>, name: String): String? {
            return environment[name] ?: environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        private fun highestAndroidPlatformJar(sdkRoot: File): AndroidPlatformJar? {
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
                .firstOrNull()
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
