package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.PrefixedImportUnsupportedReason
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-427 corpus: ANDROID_DEX_UNSUPPORTED reason surface for dex/apk (and related)
 * path-prefixed JVM imports.
 *
 * Acceptance focus:
 * - Path prefixes that look like Android dex/apk input surface
 *   [PrefixedImportUnsupportedReason.ANDROID_DEX_UNSUPPORTED] (not the generic
 *   non-classpath reason).
 * - Provider import diagnostics preserve prefix + class target and embed the
 *   reason's diagnosticDetail under code [JvmClassModuleProvider.UNSUPPORTED_PREFIXED_IMPORT_CODE].
 * - Ordinary / valid jar-or-directory prefixes do not emit the dex reason.
 *
 * Test-only. No production edits. Workers do not run Gradle; verification is
 * review-owned serial jvmTest (TASK-043).
 *
 * Host android.jar (when present, never invent, never hardcode G:/):
 * - /Users/dingyi/Downloads/android.jar
 * - /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
 */
class PrefixedDexImportUnsupportedTddTest {

    private val configuration = JvmWorkspaceConfiguration()
    private val provider = JvmClassModuleProvider()

    private val dexReasonDetail =
        PrefixedImportUnsupportedReason.ANDROID_DEX_UNSUPPORTED.diagnosticDetail
    private val nonClasspathDetail =
        PrefixedImportUnsupportedReason.NOT_JVM_CLASSPATH_ENTRY.diagnosticDetail

    // ------------------------------------------------------------------
    // Direct reason surface: dex / apk / odex / vdex / literal dexPath
    // ------------------------------------------------------------------

    @Test
    fun dex_extension_prefix_surfaces_android_dex_unsupported_reason() {
        assertDexReason("plugin.dex")
        assertDexReason("build/output.dex")
        assertDexReason("/tmp/classes.dex")
    }

    @Test
    fun apk_extension_prefix_surfaces_android_dex_unsupported_reason() {
        assertDexReason("plugin.apk")
        assertDexReason("app/release.apk")
        assertDexReason("/data/app/base.apk")
    }

    @Test
    fun odex_and_vdex_extension_prefixes_surface_android_dex_unsupported_reason() {
        assertDexReason("classes.odex")
        assertDexReason("boot.vdex")
        assertDexReason("system/framework/boot.odex")
        assertDexReason("system/framework/boot.vdex")
    }

    @Test
    fun literal_dex_path_token_surfaces_android_dex_unsupported_reason() {
        // Runtime Android-Lua / AndroLua convention: path prefix "dexPath".
        assertDexReason("dexPath")
        assertDexReason("DexPath")
        assertDexReason("DEXPATH")
        assertDexReason("  dexPath  ")
    }

    @Test
    fun dex_extension_matching_is_case_insensitive() {
        assertDexReason("Plugin.DEX")
        assertDexReason("Plugin.Apk")
        assertDexReason("Plugin.ODEX")
        assertDexReason("Plugin.VDEX")
    }

    @Test
    fun android_dex_unsupported_diagnostic_detail_mentions_dex_apk_and_jar_guidance() {
        assertTrue(dexReasonDetail.contains("dex", ignoreCase = true))
        assertTrue(dexReasonDetail.contains("apk", ignoreCase = true))
        assertTrue(
            dexReasonDetail.contains("jar", ignoreCase = true) ||
                dexReasonDetail.contains("classpath", ignoreCase = true),
            "Expected jar/classpath remediation guidance; detail='$dexReasonDetail'"
        )
        assertTrue(
            dexReasonDetail.contains("not loadable", ignoreCase = true) ||
                dexReasonDetail.contains("JVM", ignoreCase = true),
            "Expected JVM-reflection loadability note; detail='$dexReasonDetail'"
        )
    }

    // ------------------------------------------------------------------
    // Contrast: non-dex missing prefixes → NOT_JVM_CLASSPATH_ENTRY
    // ------------------------------------------------------------------

    @Test
    fun missing_jar_prefix_surfaces_not_jvm_classpath_entry_not_dex_reason() {
        val reason = configuration.prefixedImportUnsupportedReason("missing/prefix.jar")
        assertEquals(PrefixedImportUnsupportedReason.NOT_JVM_CLASSPATH_ENTRY, reason)
        assertTrue(reason!!.diagnosticDetail == nonClasspathDetail)
        assertTrue(reason.diagnosticDetail.contains("classpath", ignoreCase = true))
    }

    @Test
    fun missing_directory_prefix_surfaces_not_jvm_classpath_entry() {
        val reason = configuration.prefixedImportUnsupportedReason("no/such/classes-root")
        assertEquals(PrefixedImportUnsupportedReason.NOT_JVM_CLASSPATH_ENTRY, reason)
    }

    @Test
    fun non_dex_file_extension_without_existing_entry_is_not_android_dex_reason() {
        // .so / .aar / bare names are not in ANDROID_DEX_IMPORT_EXTENSIONS.
        for (prefix in listOf("libnative.so", "feature.aar", "somewhere/classes.zip", "randomPrefix")) {
            val reason = configuration.prefixedImportUnsupportedReason(prefix)
            assertEquals(
                PrefixedImportUnsupportedReason.NOT_JVM_CLASSPATH_ENTRY,
                reason,
                "Expected generic non-classpath reason for '$prefix'"
            )
        }
    }

    // ------------------------------------------------------------------
    // Valid jar / directory prefixes → no unsupported reason
    // ------------------------------------------------------------------

    @Test
    fun existing_temp_jar_prefix_has_no_unsupported_reason() {
        val jar = File.createTempFile("prefixed-import-", ".jar")
        jar.deleteOnExit()
        try {
            assertTrue(jar.isFile)
            assertNull(
                configuration.prefixedImportUnsupportedReason(jar.path),
                "Existing .jar must be a loadable classpath entry"
            )
            assertNotNull(configuration.prefixedImportClasspathEntry(jar.path))
        } finally {
            jar.delete()
        }
    }

    @Test
    fun existing_temp_directory_prefix_has_no_unsupported_reason() {
        val dir = Files.createTempDirectory("prefixed-import-cp-").toFile()
        try {
            assertTrue(dir.isDirectory)
            assertNull(
                configuration.prefixedImportUnsupportedReason(dir.path),
                "Existing directory must be a loadable classpath entry"
            )
            assertNotNull(configuration.prefixedImportClasspathEntry(dir.path))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun host_android_jar_when_present_is_valid_classpath_entry_not_dex_reason() {
        val hosts = listOf(
            File("/Users/dingyi/Downloads/android.jar"),
            File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"),
            File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
        ).distinctBy { it.absolutePath }

        val present = hosts.filter { it.isFile }
        if (present.isEmpty()) {
            // Host jar optional for this corpus; skip soft when absent.
            return
        }
        present.forEach { jar ->
            assertNull(
                configuration.prefixedImportUnsupportedReason(jar.path),
                "Host android.jar must not be classified as ANDROID_DEX_UNSUPPORTED: ${jar.path}"
            )
            assertNotNull(configuration.prefixedImportClasspathEntry(jar.path))
        }
    }

    // ------------------------------------------------------------------
    // Provider diagnostic surface embeds ANDROID_DEX_UNSUPPORTED detail
    // ------------------------------------------------------------------

    @Test
    fun provider_diagnostics_for_dex_prefix_import_embed_android_dex_detail() {
        val importText = "plugin.dex:android.content.Context"
        val diagnostics = provider.importDiagnostics(
            configuration = JvmWorkspaceConfiguration(androluaImports = listOf(importText))
        )

        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics.single()
        assertEquals(JvmClassModuleProvider.UNSUPPORTED_PREFIXED_IMPORT_CODE, diagnostic.code)
        assertEquals("plugin.dex", diagnostic.pathPrefix)
        assertEquals("android.content.Context", diagnostic.className)
        assertEquals(importText, diagnostic.importText)
        assertTrue(
            diagnostic.message.contains(dexReasonDetail),
            "Diagnostic must embed ANDROID_DEX_UNSUPPORTED.diagnosticDetail; message='${diagnostic.message}'"
        )
        assertTrue(diagnostic.message.contains("android.content.Context"))
        assertTrue(diagnostic.message.contains("plugin.dex"))
        assertTrue(diagnostic.message.contains("Unsupported prefixed JVM import", ignoreCase = true))
    }

    @Test
    fun provider_diagnostics_for_apk_and_dex_path_imports_use_dex_reason_not_generic() {
        val imports = listOf(
            "dexPath:java.io.File",
            "plugin.apk:android.app.Activity",
            "classes.odex:java.lang.String",
            "boot.vdex:java.util.Locale"
        )
        val diagnostics = provider.importDiagnostics(
            configuration = JvmWorkspaceConfiguration(androluaImports = imports)
        )

        assertEquals(imports.size, diagnostics.size)
        assertTrue(diagnostics.all { it.code == JvmClassModuleProvider.UNSUPPORTED_PREFIXED_IMPORT_CODE })
        assertEquals(
            setOf("dexPath", "plugin.apk", "classes.odex", "boot.vdex"),
            diagnostics.map { it.pathPrefix }.toSet()
        )
        diagnostics.forEach { diagnostic ->
            assertTrue(
                diagnostic.message.contains(dexReasonDetail),
                "Expected ANDROID_DEX_UNSUPPORTED detail in message for ${diagnostic.importText}; " +
                    "message='${diagnostic.message}'"
            )
            assertTrue(
                !diagnostic.message.contains(nonClasspathDetail),
                "Dex/apk prefixes must not embed NOT_JVM_CLASSPATH_ENTRY detail; " +
                    "import=${diagnostic.importText} message='${diagnostic.message}'"
            )
        }
    }

    @Test
    fun provider_diagnostics_for_missing_jar_prefix_use_non_classpath_detail_not_dex() {
        val importText = "missing/prefix.jar:java.util.Locale"
        val diagnostics = provider.importDiagnostics(
            configuration = JvmWorkspaceConfiguration(androluaImports = listOf(importText))
        )

        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics.single()
        assertEquals("missing/prefix.jar", diagnostic.pathPrefix)
        assertEquals("java.util.Locale", diagnostic.className)
        assertTrue(
            diagnostic.message.contains(nonClasspathDetail),
            "Missing jar must embed NOT_JVM_CLASSPATH_ENTRY detail; message='${diagnostic.message}'"
        )
        assertTrue(
            !diagnostic.message.contains(dexReasonDetail),
            "Missing jar must not embed ANDROID_DEX_UNSUPPORTED detail; message='${diagnostic.message}'"
        )
    }

    @Test
    fun mixed_dex_and_missing_jar_and_ordinary_imports_partition_reasons() {
        val configuration = JvmWorkspaceConfiguration(
            androluaImports = listOf(
                "dexPath:java.io.File",
                "plugin.dex:android.content.Context",
                "missing/prefix.jar:java.util.Locale",
                "java.io.File"
            )
        )

        val diagnostics = runCatching { provider.importDiagnostics(configuration) }
            .getOrElse { error ->
                fail("Mixed imports must not throw; got ${error::class.simpleName}: ${error.message}")
            }
        val requested = provider.requestedClasses(configuration)

        assertEquals(3, diagnostics.size)
        assertEquals(
            setOf("dexPath", "plugin.dex", "missing/prefix.jar"),
            diagnostics.map { it.pathPrefix }.toSet()
        )

        val byPrefix = diagnostics.associateBy { it.pathPrefix }
        assertTrue(byPrefix.getValue("dexPath").message.contains(dexReasonDetail))
        assertTrue(byPrefix.getValue("plugin.dex").message.contains(dexReasonDetail))
        assertTrue(byPrefix.getValue("missing/prefix.jar").message.contains(nonClasspathDetail))
        assertTrue(!byPrefix.getValue("missing/prefix.jar").message.contains(dexReasonDetail))

        // Concrete ordinary import still resolves; prefixed class names may resolve via fallback loader.
        assertTrue("java.io.File" in requested)
        // android.content.Context only resolves when android.jar is on the reflective classpath.
        assertTrue(
            "java.io.File" in requested &&
                diagnostics.map { it.className }.toSet() ==
                setOf("java.io.File", "android.content.Context", "java.util.Locale"),
            "Prefixed diagnostics must preserve class targets; requested=$requested diagnostics=$diagnostics"
        )
    }

    @Test
    fun explicit_target_overload_surfaces_dex_reason_for_apk_and_dex_path() {
        val diagnostics = provider.importDiagnostics(
            importTargets = listOf(
                "dexPath:java.lang.String",
                "plugin.apk:android.app.Activity",
                "not-a-prefix-only",
                ""
            ),
            configuration = JvmWorkspaceConfiguration()
        )

        assertEquals(2, diagnostics.size)
        assertEquals(setOf("dexPath", "plugin.apk"), diagnostics.map { it.pathPrefix }.toSet())
        assertTrue(diagnostics.all { it.message.contains(dexReasonDetail) })
        assertTrue(diagnostics.all { it.code == JvmClassModuleProvider.UNSUPPORTED_PREFIXED_IMPORT_CODE })
    }

    @Test
    fun ordinary_class_import_emits_no_prefixed_unsupported_diagnostic() {
        val diagnostics = provider.importDiagnostics(
            configuration = JvmWorkspaceConfiguration(
                androluaImports = listOf("java.io.File", "java.util.Locale")
            )
        )
        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun existing_jar_prefix_import_emits_no_unsupported_diagnostic() {
        val jar = File.createTempFile("prefixed-import-ok-", ".jar")
        jar.deleteOnExit()
        try {
            val importText = "${jar.path}:java.lang.String"
            val diagnostics = provider.importDiagnostics(
                configuration = JvmWorkspaceConfiguration(androluaImports = listOf(importText))
            )
            assertEquals(
                emptyList(),
                diagnostics,
                "Valid jar classpath prefix must not emit prefixed.unsupported; actual=$diagnostics"
            )
        } finally {
            jar.delete()
        }
    }

    @Test
    fun reason_enum_names_are_stable_for_corpus_goldens() {
        assertEquals("ANDROID_DEX_UNSUPPORTED", PrefixedImportUnsupportedReason.ANDROID_DEX_UNSUPPORTED.name)
        assertEquals("NOT_JVM_CLASSPATH_ENTRY", PrefixedImportUnsupportedReason.NOT_JVM_CLASSPATH_ENTRY.name)
        assertEquals(
            JvmClassModuleProvider.UNSUPPORTED_PREFIXED_IMPORT_CODE,
            "jvm.import.prefixed.unsupported"
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun assertDexReason(pathPrefix: String) {
        val reason = configuration.prefixedImportUnsupportedReason(pathPrefix)
        assertEquals(
            PrefixedImportUnsupportedReason.ANDROID_DEX_UNSUPPORTED,
            reason,
            "Expected ANDROID_DEX_UNSUPPORTED for pathPrefix='$pathPrefix'"
        )
        assertEquals(dexReasonDetail, reason!!.diagnosticDetail)
        assertNull(
            configuration.prefixedImportClasspathEntry(pathPrefix),
            "Dex-like prefixes must not resolve as classpath entries: '$pathPrefix'"
        )
    }
}
