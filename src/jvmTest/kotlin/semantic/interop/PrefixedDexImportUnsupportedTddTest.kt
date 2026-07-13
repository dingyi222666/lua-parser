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
