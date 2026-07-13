package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import java.io.File
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-211 / TASK-584 — Android.jar package enumeration depth corpus + product lock.
 *
 * Covers nested packages used by Android-Lua / LuaJava samples
 * (`android.os`, `android.net`, `android.util`, `android.graphics`,
 * `android.graphics.drawable`, `android.graphics.drawable.shapes`,
 * `android.content.pm`, `android.content.res`, `android.app.job`, …).
 *
 * Product lock (TASK-584): when host android-35 jar is present,
 * `android.app.*` / `android.content.*` / `android.view.*` / `android.widget.*`
 * must enumerate Activity / Context / View / TextView as top-level fields and
 * must never invent those members when the jar is absent. Inner classes are skipped.
 *
 * When the host android.jar is missing, jar-dependent tests skip with an
 * explicit TASK-211 reason rather than failing hard or returning silently.
 */
class AndroidJarPackageEnumerationTddTest {
    private val provider = JvmClassModuleProvider()
    private val androidJar = resolveAndroidJar()

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-211"), "Skip reason must name TASK-211; got: $reason")
        assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
        assertTrue(reason.contains(missing.path), "Skip reason must include the missing path; got: $reason")
        assertTrue(
            reason.contains("ANDROID_HOME") || reason.contains("ANDROID_SDK_ROOT") || reason.contains("Install"),
            "Skip reason must tell the host how to recover; got: $reason"
        )
    }
    @Test
    fun android_jar_present_or_skipped_with_explicit_reason() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
        assertTrue(androidJar.isFile)
        assertTrue(
            androidJar.length() > 0,
            "Expected non-empty Android platform jar at ${androidJar.path}."
        )
    }
    @Test
    fun depth1_android_graphics_wildcard_enumerates_color_but_not_drawable_subpackage_classes() {
        requireAndroidJarOrSkip()
        val packageModule = packageModuleFor("android.graphics.*")

        assertEquals("android.graphics", packageModule.moduleName)
        assertTrue("Color" in packageModule.fields, fieldsHint("android.graphics", packageModule))
        assertTrue("Bitmap" in packageModule.fields, fieldsHint("android.graphics", packageModule))
        assertTrue("Paint" in packageModule.fields, fieldsHint("android.graphics", packageModule))
        // Nested package classes live under android.graphics.drawable, not the parent package.
        assertFalse(
            "ColorDrawable" in packageModule.fields,
            "android.graphics.* must not recurse into android.graphics.drawable; ColorDrawable should be absent."
        )
        assertFalse(
            "RectShape" in packageModule.fields,
            "android.graphics.* must not recurse into android.graphics.drawable.shapes."
        )
    }
    @Test
    fun nested_package_enumeration_skips_inner_classes_at_every_depth() {
        requireAndroidJarOrSkip()

        val os = packageModuleFor("android.os.*")
        assertTrue("Build" in os.fields)
        assertFalse("VERSION" in os.fields)
        assertFalse("VERSION_CODES" in os.fields)

        val net = packageModuleFor("android.net.*")
        assertTrue("Uri" in net.fields)
        assertFalse("Builder" in net.fields)

        val res = packageModuleFor("android.content.res.*")
        assertTrue("Resources" in res.fields)
        assertFalse("Theme" in res.fields)
        assertFalse("NotFoundException" in res.fields)

        val job = packageModuleFor("android.app.job.*")
        assertTrue("JobInfo" in job.fields)
        assertFalse("Builder" in job.fields)
    }
    @Test
    fun empty_or_unknown_nested_package_yields_no_provider() {
        requireAndroidJarOrSkip()
        val providers = provider.packageProvidersFor(
            importTargets = listOf(
                "android.does.not.exist.nested.*",
                "com.missing.sample.package.*"
            ),
            configuration = androidConfiguration()
        )

        assertTrue(
            providers.isEmpty(),
            "Expected no package providers for missing nested packages; actual: ${providers.keys.map { it.value }}"
        )
    }
    @Test
    fun non_wildcard_nested_target_is_not_treated_as_package_provider() {
        requireAndroidJarOrSkip()
        val providers = provider.packageProvidersFor(
            importTargets = listOf(
                "android.os.Build",
                "android.net.Uri",
                "android.graphics.drawable.ColorDrawable"
            ),
            configuration = androidConfiguration()
        )

        assertTrue(
            providers.isEmpty(),
            "packageProvidersFor only handles wildcard package targets; actual: ${providers.keys.map { it.value }}"
        )
    }

    private fun packageModuleFor(importTarget: String): ModuleType {
        val providers = provider.packageProvidersFor(listOf(importTarget), androidConfiguration())
        val packageName = importTarget.removeSuffix(".*")
        val path = VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua")
        return assertNotNull(
            providers[path]?.moduleExportSurface?.moduleType,
            "Expected Android nested package provider for $importTarget from ${androidJar.path}; " +
                "actual providers: ${providers.keys.map { it.value }}"
        )
    }

    private fun androidConfiguration(): JvmWorkspaceConfiguration {
        return JvmWorkspaceConfiguration(androidJar = androidJar.path)
    }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private fun fieldsHint(packageName: String, module: ModuleType): String {
        return "Expected nested package $packageName from ${androidJar.path}; fields=${module.fields.keys.sorted()}"
    }

    companion object {
        /**
         * Host-resolution order for TASK-211 corpus:
         * 1) WAVE21C mac SDK path from worker prompt
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
         * 3) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35/android.jar
         */
        private fun resolveAndroidJar(): File {
            val candidates = linkedSetOf<File>()
            candidates += File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")
            candidates += File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env -> System.getenv(env)?.trim()?.takeIf(String::isNotEmpty) }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }
            return candidates.firstOrNull { it.isFile } ?: candidates.first()
        }

        internal fun missingAndroidJarSkipReason(androidJar: File): String {
            return "TASK-211 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar) " +
                "before running nested package enumeration depth corpus."
        }
    }
}
