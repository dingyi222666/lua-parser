package io.github.dingyi222666.luaparser.interop.jvm

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

    fun normalized(defaultImportPrefixes: List<String> = JvmClassModuleProvider.DEFAULT_IMPORT_PREFIXES): JvmWorkspaceConfiguration {
        return copy(
            classpathEntries = classpathEntries.map(String::trim).filter(String::isNotEmpty),
            androidJar = androidJar?.trim()?.takeIf(String::isNotEmpty),
            classes = classes.map(String::trim).filter(String::isNotEmpty).toCollection(linkedSetOf()),
            androluaImports = androluaImports.map(String::trim).filter(String::isNotEmpty),
            importPrefixes = importPrefixes.map(String::trim).filter(String::isNotEmpty).ifEmpty { defaultImportPrefixes }
        )
    }

    fun overlay(overrides: JvmWorkspaceConfiguration): JvmWorkspaceConfiguration {
        val base = normalized()
        val extra = overrides.normalized()
        return JvmWorkspaceConfiguration(
            classLoader = extra.classLoader ?: base.classLoader,
            classpathEntries = if (extra.classpathEntries.isNotEmpty()) extra.classpathEntries else base.classpathEntries,
            androidJar = extra.androidJar ?: base.androidJar,
            classes = (base.classes + extra.classes).toCollection(linkedSetOf()),
            androluaImports = (base.androluaImports + extra.androluaImports).distinct(),
            importPrefixes = if (extra.importPrefixes.isNotEmpty()) extra.importPrefixes else base.importPrefixes
        )
    }

    fun applyToMetadata(metadata: Map<String, String>): Map<String, String> {
        val normalized = normalized()
        val result = metadata.toMutableMap()
        result[JvmClassModuleProvider.CLASSES_METADATA_KEY] = normalized.classes.joinToString("\n")
        result[JvmClassModuleProvider.IMPORTS_METADATA_KEY] = normalized.androluaImports.joinToString("\n")
        result[CLASSPATH_METADATA_KEY] = normalized.classpathEntries.joinToString("\n")
        normalized.androidJar?.let { result[ANDROID_JAR_METADATA_KEY] = it } ?: result.remove(ANDROID_JAR_METADATA_KEY)
        result[IMPORT_PREFIXES_METADATA_KEY] = normalized.importPrefixes.joinToString("\n")
        return result
    }

    companion object {
        const val CLASSPATH_METADATA_KEY = "jvm.classpath"
        const val ANDROID_JAR_METADATA_KEY = "jvm.androidJar"
        const val IMPORT_PREFIXES_METADATA_KEY = "jvm.importPrefixes"

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
            ).normalized()
        }
    }
}
