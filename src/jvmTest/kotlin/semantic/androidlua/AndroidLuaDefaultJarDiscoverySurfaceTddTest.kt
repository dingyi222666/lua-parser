package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import java.io.File
import java.nio.file.Files
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-537 — Android-Lua workspace import/reflection when `jvm.androidJar` metadata is unset.
 *
 * Locks discovery parity with [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH]:
 * ANDROID_HOME / ANDROID_SDK_ROOT, then well-known host SDK roots (macOS
 * `~/Library/Android/sdk`). Downloads jars remain explicit-metadata only.
 * Never hard-requires `G:/Android/Sdk`.
 *
 * Missing jar soft-skips with an explicit reason; present host android-35 jar
 * (~27MB) mounts `android.widget` providers for import suites.
 *
 * Verification is review-owned (TASK-043); workers must not run Gradle.
 */
class AndroidLuaDefaultJarDiscoverySurfaceTddTest {
    private val hostAndroidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
    private val discoveredJarPath = JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()

    @Test
    fun missing_android_jar_soft_skip_documents_explicit_reason() {
        val emptyHome = Files.createTempDirectory("lua-parser-androidlua-soft-skip-").toFile()
        val reason = JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(
            environment = emptyMap(),
            userHome = emptyHome,
            localAppData = File(emptyHome, "LocalAppData-missing").path,
            taskId = "TASK-537"
        )

        assertTrue(reason.contains("TASK-537"), "Soft-skip must name TASK-537; got: $reason")
        assertTrue(reason.contains("android.jar"), "Soft-skip must mention android.jar; got: $reason")
        assertTrue(
            reason.contains(JvmWorkspaceConfiguration.ANDROID_HOME_ENV) ||
                reason.contains(JvmWorkspaceConfiguration.ANDROID_SDK_ROOT_ENV),
            "Soft-skip must mention SDK env vars; got: $reason"
        )
        assertTrue(
            reason.contains(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY) ||
                reason.contains("well-known") ||
                reason.contains("Library/Android/sdk"),
            "Soft-skip must explain metadata/well-known recovery; got: $reason"
        )
        assertFalse(
            reason.replace('\\', '/').contains("G:/Android/Sdk") &&
                !reason.contains("never") &&
                !reason.contains("last-resort"),
            "Soft-skip must not hard-require G:/Android/Sdk; got: $reason"
        )
    }
    @Test
    fun provider_reflection_without_metadata_loads_textview_from_discovered_jar() {
        requireDiscoveredJarOrSkip()
        val provider = JvmClassModuleProvider()
        val configuration = JvmWorkspaceConfiguration(
            classes = setOf("android.widget.TextView")
            // androidJar deliberately unset — discovery must supply the jar.
        )

        val providers = provider.providersFor(configuration)
        val path = "__jvm__/classes/android/widget/TextView.lua"
        val entry = providers.entries.firstOrNull { it.key.value == path }
        assertTrue(
            entry != null,
            "Expected reflected TextView without explicit androidJar; keys=${providers.keys.map { it.value }}; " +
                "classpath=${configuration.reflectionClasspathEntries()}"
        )
        val surface = entry!!.value.moduleExportSurface
        assertNotNull(surface, "TextView provider must expose ModuleExportSurface")
        val moduleName = surface!!.moduleType.moduleName
        assertEquals("TextView", moduleName)
        assertTrue(
            surface.members.isNotEmpty(),
            "Expected non-empty TextView member surface from discovered android.jar"
        )
    }
    @Test
    fun host_android_35_jar_size_is_substantial_when_present() {
        requireDiscoveredJarOrSkip()
        val jar = File(discoveredJarPath!!)
        // Real android-35 platform jar is ~27MB on this host; reject tiny fakes.
        assertTrue(
            jar.length() > 1_000_000L,
            "Expected substantial android.jar (~27MB android-35); size=${jar.length()} path=${jar.path}"
        )
        assertTrue(
            hostAndroidJar.isFile && hostAndroidJar.length() > 1_000_000L || jar.length() > 1_000_000L,
            "DEFAULT_ANDROID_JAR_PATH / discovered jar should be real platform jar"
        )
    }

    private fun requireDiscoveredJarOrSkip() {
        if (discoveredJarPath == null || !File(discoveredJarPath).isFile) {
            Assume.assumeTrue(
                JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-537"),
                false
            )
        }
    }

}
