package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * TDD coverage for multi-OS android.jar default path discovery (TASK-245).
 *
 * Default path resolution prefers explicit env (`ANDROID_HOME` / `ANDROID_SDK_ROOT`),
 * then well-known host SDK roots. Missing jars are never invented as existing files;
 * consumers of [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] must still
 * check [File.isFile] and skip when absent.
 */
class AndroidJarDefaultPathDiscoveryTddTest {
    @Test
    fun default_android_jar_path_is_not_a_windows_only_hardcode_when_host_sdk_is_present() {
        val resolved = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
        val windowsOnlyHardcode = File("G:/Android/Sdk/platforms/android-35/android.jar")
        val hostMacJar = File(
            System.getProperty("user.home"),
            "Library/Android/sdk/platforms/android-35/android.jar"
        )

        if (hostMacJar.isFile) {
            assertTrue(
                resolved.isFile,
                "Expected DEFAULT_ANDROID_JAR_PATH to resolve an existing jar on this host; got ${resolved.path}."
            )
            assertTrue(
                resolved.length() > 0,
                "Expected non-empty android.jar at ${resolved.path}."
            )
            assertNotEquals(
                windowsOnlyHardcode.path.replace('\\', '/'),
                resolved.path.replace('\\', '/'),
                "DEFAULT_ANDROID_JAR_PATH must not remain the Windows-only G:/ hardcode when a host SDK jar exists."
            )
            assertTrue(
                resolved.path.replace('\\', '/').contains("/platforms/android-") &&
                    resolved.name.equals("android.jar", ignoreCase = true),
                "Expected resolved default to be an android.jar under platforms/; got ${resolved.path}."
            )
            return
        }

        if (resolved.isFile) {
            assertTrue(
                resolved.name.equals("android.jar", ignoreCase = true),
                "Expected discovered default file to be android.jar; got ${resolved.path}."
            )
            assertNotEquals(
                windowsOnlyHardcode.absolutePath,
                resolved.absolutePath,
                "When a real host jar is present, DEFAULT_ANDROID_JAR_PATH should not be only the G:/ hardcode."
            )
            return
        }

        assertFalse(
            resolved.isFile,
            "Without a host SDK, DEFAULT_ANDROID_JAR_PATH must not invent an existing jar file."
        )
        println(
            "SKIP reason: no host android.jar at ${hostMacJar.path}; " +
                "DEFAULT_ANDROID_JAR_PATH candidate is ${resolved.path} (absent)."
        )
    }

    @Test
    fun resolve_default_prefers_android_home_highest_platform_over_well_known_roots() {
        val androidHome = fakeAndroidSdk(23, 34, 35)
        val wellKnownHome = Files.createTempDirectory("lua-parser-user-home-")
        writeFakePlatformJar(wellKnownHome.resolve("Library/Android/sdk"), 30)

        val resolved = JvmWorkspaceConfiguration.resolveDefaultAndroidJarPath(
            environment = mapOf(JvmWorkspaceConfiguration.ANDROID_HOME_ENV to androidHome.toString()),
            userHome = wellKnownHome.toFile(),
            localAppData = null
        )

        assertEquals(
            androidHome.resolve("platforms/android-35/android.jar").toString(),
            resolved
        )
    }

    @Test
    fun resolve_default_prefers_android_sdk_root_when_android_home_absent() {
        val androidSdkRoot = fakeAndroidSdk(33)
        val wellKnownHome = Files.createTempDirectory("lua-parser-user-home-sdkroot-")
        writeFakePlatformJar(wellKnownHome.resolve("Library/Android/sdk"), 35)

        val resolved = JvmWorkspaceConfiguration.resolveDefaultAndroidJarPath(
            environment = mapOf(JvmWorkspaceConfiguration.ANDROID_SDK_ROOT_ENV to androidSdkRoot.toString()),
            userHome = wellKnownHome.toFile(),
            localAppData = null
        )

        assertEquals(
            androidSdkRoot.resolve("platforms/android-33/android.jar").toString(),
            resolved
        )
    }

    @Test
    fun resolve_default_uses_macos_well_known_sdk_when_env_is_absent() {
        val userHome = Files.createTempDirectory("lua-parser-mac-home-")
        val expectedJar = writeFakePlatformJar(userHome.resolve("Library/Android/sdk"), 35)

        val resolved = JvmWorkspaceConfiguration.resolveDefaultAndroidJarPath(
            environment = emptyMap(),
            userHome = userHome.toFile(),
            localAppData = null
        )

        assertEquals(expectedJar.toFile().path, resolved)
        assertTrue(File(resolved).isFile)
    }

    @Test
    fun resolve_default_uses_linux_well_known_sdk_layout() {
        val userHome = Files.createTempDirectory("lua-parser-linux-home-")
        val expectedJar = writeFakePlatformJar(userHome.resolve("Android/Sdk"), 34)

        val resolved = JvmWorkspaceConfiguration.resolveDefaultAndroidJarPath(
            environment = emptyMap(),
            userHome = userHome.toFile(),
            localAppData = null
        )

        assertEquals(expectedJar.toFile().path, resolved)
    }

    @Test
    fun resolve_default_uses_windows_localappdata_sdk_layout() {
        val userHome = Files.createTempDirectory("lua-parser-win-home-")
        val localAppData = Files.createTempDirectory("lua-parser-localappdata-")
        val expectedJar = writeFakePlatformJar(localAppData.resolve("Android/Sdk"), 33)

        val resolved = JvmWorkspaceConfiguration.resolveDefaultAndroidJarPath(
            environment = emptyMap(),
            userHome = userHome.toFile(),
            localAppData = localAppData.toString()
        )

        assertEquals(expectedJar.toFile().path, resolved)
    }

    @Test
    fun resolve_default_does_not_invent_missing_jar_file() {
        val emptyHome = Files.createTempDirectory("lua-parser-empty-home-")

        val candidate = JvmWorkspaceConfiguration.resolveDefaultAndroidJarPath(
            environment = emptyMap(),
            userHome = emptyHome.toFile(),
            localAppData = emptyHome.resolve("LocalAppData-missing").toString()
        )
        val file = File(candidate)

        assertFalse(file.isFile, "Candidate path must not invent a jar file: ${file.path}")
        assertTrue(
            candidate.replace('\\', '/').endsWith("/platforms/android-35/android.jar"),
            "Expected preferred candidate under platforms/android-35; got $candidate"
        )
    }

    @Test
    fun file_constructed_from_default_android_jar_path_is_skip_safe_when_absent() {
        val emptyHome = Files.createTempDirectory("lua-parser-skip-home-")
        val candidate = JvmWorkspaceConfiguration.resolveDefaultAndroidJarPath(
            environment = emptyMap(),
            userHome = emptyHome.toFile(),
            localAppData = null
        )
        val file = File(candidate)

        assertFalse(file.isFile, "Candidate path must not invent a jar file: ${file.path}")
        // Mirrors existing consumer pattern: File(DEFAULT_ANDROID_JAR_PATH) + isFile guard.
        if (!file.isFile) {
            println("SKIP reason: android.jar absent at ${file.path}")
        }
    }

    @Test
    fun explicit_configuration_still_authoritative_for_reflection_classpath() {
        val androidHome = fakeAndroidSdk(35)
        val explicit = Files.createTempFile("lua-parser-explicit-android", ".jar")
        val configuration = JvmWorkspaceConfiguration(androidJar = explicit.toString())

        assertEquals(
            listOf(explicit.toString()),
            configuration.reflectionClasspathEntries(
                mapOf(JvmWorkspaceConfiguration.ANDROID_HOME_ENV to androidHome.toString())
            )
        )
    }

    @Test
    fun reflection_classpath_still_discovers_from_android_home_without_default_constant() {
        val androidHome = fakeAndroidSdk(34, 35)
        val expectedJar = androidHome.resolve("platforms/android-35/android.jar").toString()
        val configuration = JvmWorkspaceConfiguration()

        assertEquals(
            listOf(expectedJar),
            configuration.reflectionClasspathEntries(
                mapOf(JvmWorkspaceConfiguration.ANDROID_HOME_ENV to androidHome.toString())
            )
        )
    }

    private fun fakeAndroidSdk(vararg apiLevels: Int): Path {
        val root = Files.createTempDirectory("lua-parser-android-sdk-")
        apiLevels.forEach { apiLevel ->
            writeFakePlatformJar(root, apiLevel)
        }
        return root
    }

    private fun writeFakePlatformJar(sdkRoot: Path, apiLevel: Int): Path {
        val platformDirectory = sdkRoot.resolve("platforms/android-$apiLevel")
        Files.createDirectories(platformDirectory)
        val jar = platformDirectory.resolve("android.jar")
        if (!Files.exists(jar)) {
            Files.createFile(jar)
        }
        return jar
    }
}
