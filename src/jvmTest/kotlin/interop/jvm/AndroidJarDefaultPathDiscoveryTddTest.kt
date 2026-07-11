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
 * TDD coverage for multi-OS android.jar default path discovery (TASK-245 / TASK-606).
 *
 * Default path resolution prefers explicit env (ANDROID_HOME / ANDROID_SDK_ROOT),
 * then well-known host SDK roots. Missing jars are never invented as existing files;
 * consumers of [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] must still
 * check [File.isFile] and skip when absent. Absolute G: inventing roots are never
 * auto-selected, so isolation tests stay honest on multi-host agents.
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
        val normalized = candidate.replace('\\', '/')

        assertFalse(file.isFile, "Candidate path must not invent a jar file: ${file.path}")
        assertTrue(
            normalized.endsWith("/platforms/android-35/android.jar"),
            "Expected preferred candidate under platforms/android-35; got $candidate"
        )
        assertFalse(
            normalized.startsWith("G:/Android/Sdk", ignoreCase = true) ||
                normalized.contains("/platforms/android-36/"),
            "Isolation must not invent G: or android-36 defaults; got $candidate"
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
        val normalized = candidate.replace('\\', '/')

        assertFalse(file.isFile, "Candidate path must not invent a jar file: ${file.path}")
        assertFalse(
            normalized.startsWith("G:/Android/Sdk", ignoreCase = true),
            "Skip-safe candidate must not be absolute G: inventing root; got $candidate"
        )
        assertFalse(
            normalized.contains("/platforms/android-36/"),
            "Skip-safe candidate must not invent android-36; got $candidate"
        )
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

    @Test
    fun reflection_classpath_discovers_well_known_macos_when_metadata_unset() {
        val userHome = Files.createTempDirectory("lua-parser-mac-reflect-home-")
        val expectedJar = writeFakePlatformJar(userHome.resolve("Library/Android/sdk"), 35).toFile().path
        val configuration = JvmWorkspaceConfiguration() // no jvm.androidJar metadata

        assertEquals(
            listOf(expectedJar),
            configuration.reflectionClasspathEntries(
                environment = emptyMap(),
                userHome = userHome.toFile(),
                localAppData = null
            )
        )
        val note = configuration.androidJarConfigurationNote(
            environment = emptyMap(),
            userHome = userHome.toFile(),
            localAppData = null
        ).orEmpty()
        assertTrue(note.contains("well-known"), "Expected well-known discovery note; got: $note")
        assertTrue(note.contains(expectedJar), "Note should include discovered path; got: $note")
    }

    @Test
    fun reflection_classpath_never_auto_selects_downloads_android_jar() {
        val userHome = Files.createTempDirectory("lua-parser-downloads-home-")
        val downloadsJar = userHome.resolve("Downloads/android.jar")
        Files.createDirectories(downloadsJar.parent)
        Files.createFile(downloadsJar)
        // Well-known SDK roots intentionally empty; only Downloads has a jar.
        val configuration = JvmWorkspaceConfiguration()

        val classpath = configuration.reflectionClasspathEntries(
            environment = emptyMap(),
            userHome = userHome.toFile(),
            localAppData = null
        )

        assertTrue(
            classpath.none { it.replace('\\', '/').contains("/Downloads/android.jar") },
            "Downloads android.jar must remain explicit metadata only; classpath=$classpath"
        )
        assertTrue(
            classpath.none { path -> File(path).canonicalFile == downloadsJar.toFile().canonicalFile },
            "Downloads jar must not be auto-selected; classpath=$classpath"
        )

        val explicit = JvmWorkspaceConfiguration(androidJar = downloadsJar.toString())
        assertEquals(
            listOf(downloadsJar.toString()),
            explicit.reflectionClasspathEntries(
                environment = emptyMap(),
                userHome = userHome.toFile(),
                localAppData = null
            )
        )
    }

    @Test
    fun missing_android_jar_soft_skip_reason_is_explicit() {
        val emptyHome = Files.createTempDirectory("lua-parser-soft-skip-home-")
        val reason = JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(
            environment = emptyMap(),
            userHome = emptyHome.toFile(),
            localAppData = emptyHome.resolve("LocalAppData-missing").toString(),
            taskId = "TASK-537"
        )
        val normalizedReason = reason.replace('\\', '/')

        assertTrue(reason.contains("TASK-537"), "Soft-skip must name task; got: $reason")
        assertTrue(reason.contains("android.jar"), "Soft-skip must mention android.jar; got: $reason")
        assertTrue(
            reason.contains(JvmWorkspaceConfiguration.ANDROID_HOME_ENV) ||
                reason.contains(JvmWorkspaceConfiguration.ANDROID_SDK_ROOT_ENV),
            "Soft-skip must mention SDK env vars; got: $reason"
        )
        assertTrue(
            reason.contains("well-known") ||
                reason.contains("Library/Android/sdk") ||
                reason.contains(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY),
            "Soft-skip must explain recovery / discovery roots; got: $reason"
        )
        assertFalse(
            reason.contains("is present at", ignoreCase = true) &&
                (normalizedReason.contains("G:/Android/Sdk") || normalizedReason.contains("android-36")),
            "Soft-skip isolation must not claim G:/android-36 presence; got: $reason"
        )
        assertTrue(
            !reason.contains("G:/Android/Sdk") || reason.contains("never") || reason.contains("last"),
            "Soft-skip must not hard-require G:/; got: $reason"
        )
    }

    @Test
    fun host_default_android_jar_path_matches_reflective_discovery_when_present() {
        val hostMacJar = File(
            System.getProperty("user.home"),
            "Library/Android/sdk/platforms/android-35/android.jar"
        )
        if (!hostMacJar.isFile) {
            println(
                "SKIP reason: host android.jar absent at ${hostMacJar.path}; " +
                    JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-537")
            )
            return
        }

        val discovered = JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()
        val defaultPath = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
        val reflective = JvmWorkspaceConfiguration().reflectionClasspathEntries()

        assertTrue(discovered != null, "Expected reflective discovery to find host jar")
        assertTrue(File(discovered!!).isFile)
        assertTrue(defaultPath.isFile)
        assertTrue(
            reflective.any { File(it).canonicalFile == File(discovered).canonicalFile },
            "reflectionClasspathEntries without metadata must include discovered jar; got $reflective"
        )
        assertTrue(
            !defaultPath.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true),
            "DEFAULT_ANDROID_JAR_PATH must not hard-require G:/; got ${defaultPath.path}"
        )
        assertTrue(
            defaultPath.length() > 1_000_000L || hostMacJar.length() > 1_000_000L,
            "Expected real android-35 sized jar (~27MB); default=${defaultPath.length()} host=${hostMacJar.length()}"
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
