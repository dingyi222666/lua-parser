package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness

class JvmClassloaderConfigurationTddTest {
    /**
     * Host android-35 jar hard-lock for TASK-568 / TASK-608.
     *
     * Prefer multi-OS discovery (DEFAULT_ANDROID_JAR_PATH / ANDROID_HOME /
     * ANDROID_SDK_ROOT / LOCALAPPDATA well-known roots), then WAVE mac path.
     * Never hard-require Windows-only G:/Android/Sdk or a macOS-only absolute path.
     */
    private val androidJar: File = resolveHostAndroidJar()
    private val provider = JvmClassModuleProvider()

    @Test
    fun jdk_classes_remain_loadable_without_custom_classpath_or_android_jar() {
        val providers = provider.providersFor(
            JvmWorkspaceConfiguration(
                classes = linkedSetOf("java.lang.String", "java.util.Locale")
            )
        )

        providers.assertModule("java.lang.String").assertClassName("java.lang.String")
        providers.assertModule("java.util.Locale").assertClassName("java.util.Locale")
    }
    @Test
    fun explicit_android_jar_configuration_remains_authoritative_over_sdk_environment() {
        val sdkRoot = fakeAndroidSdk(35)
        val explicitJar = Files.createTempFile("lua-parser-explicit-android", ".jar")
        val environment = mapOf(JvmWorkspaceConfiguration.ANDROID_HOME_ENV to sdkRoot.toString())
        val configuration = JvmWorkspaceConfiguration(androidJar = explicitJar.toString())

        assertEquals(listOf(explicitJar.toString()), configuration.reflectionClasspathEntries(environment))
        assertTrue(
            configuration.androidJarConfigurationNote(environment)
                .orEmpty()
                .contains(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY)
        )
    }
    @Test
    fun workspace_metadata_classpath_directory_mounts_external_class_provider() {
        val fixture = fixtureWorkspace(configuredThingSource())
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local ConfiguredThing = require(\"ConfiguredThing\")\nreturn ConfiguredThing.NAME",
            metadata = mapOf(
                JvmWorkspaceConfiguration.CLASSPATH_METADATA_KEY to fixture.classesDir.toString(),
                JvmClassModuleProvider.CLASSES_METADATA_KEY to CONFIGURED_THING_CLASS
            ),
            engine = JvmWorkspaceEngine()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "ConfiguredThing")
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "NAME"))

        assertEquals(harness.path("__jvm__/classes/fixture/config/ConfiguredThing.lua"), resolved.provider?.path)
        assertEquals("string", hover?.typeInfo?.displayName)
    }
    @Test
    fun wildcard_package_provider_enumerates_top_level_classes_from_configured_jar() {
        val fixture = fixtureWorkspace(
            configuredThingSource(),
            FixtureSource(
                "fixture/config/SecondThing.java",
                """
                package fixture.config;

                public class SecondThing {
                    public static final int COUNT = 2;
                }
                """.trimIndent()
            )
        )

        val packageModule = provider.packageProvidersFor(
            importTargets = listOf("fixture.config.*"),
            configuration = JvmWorkspaceConfiguration(classpathEntries = listOf(fixture.jarPath.toString()))
        ).assertPackageModule("fixture.config")

        assertTrue("ConfiguredThing" in packageModule.fields)
        assertTrue("SecondThing" in packageModule.fields)
        assertFalse("Nested" in packageModule.fields, "Wildcard package enumeration should not expose inner classes as top-level names.")
    }

    private fun configuredThingSource(): FixtureSource {
        return FixtureSource(
            "fixture/config/ConfiguredThing.java",
            """
            package fixture.config;

            public class ConfiguredThing {
                public static final String NAME = "configured";

                public String describe() {
                    return NAME;
                }

                public static class Nested {
                    public static final int VALUE = 42;
                }
            }
            """.trimIndent()
        )
    }

    private fun pluginMarkerSource(staticField: String): FixtureSource {
        return FixtureSource(
            "fixture/dupe/PluginMarker.java",
            """
            package fixture.dupe;

            public class PluginMarker {
                public static final String $staticField = "$staticField";
            }
            """.trimIndent()
        )
    }

    private fun fixtureWorkspace(vararg sources: FixtureSource): FixtureWorkspace {
        val root = Files.createTempDirectory("lua-parser-jvm-fixture-")
        val classesDir = compileJavaSources(root, sources.toList())
        val jarPath = jarClasses(classesDir, root.resolve("fixture.jar"))
        return FixtureWorkspace(classesDir, jarPath)
    }

    private fun compileJavaSources(root: Path, sources: List<FixtureSource>): Path {
        val sourceDir = root.resolve("src")
        val classesDir = root.resolve("classes")
        Files.createDirectories(sourceDir)
        Files.createDirectories(classesDir)

        val javaFiles = sources.map { source ->
            val path = sourceDir.resolve(source.relativePath)
            Files.createDirectories(path.parent)
            Files.write(path, source.contents.toByteArray(StandardCharsets.UTF_8))
            path
        }

        val compiler = assertNotNull(
            ToolProvider.getSystemJavaCompiler(),
            "Expected JVM classloader configuration tests to run on a JDK with a system Java compiler."
        )
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { fileManager ->
            val compilationUnits = fileManager.getJavaFileObjectsFromFiles(javaFiles.map { it.toFile() })
            val success = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf("-d", classesDir.toString()),
                null,
                compilationUnits
            ).call()

            assertTrue(
                success,
                "Expected Java fixture compilation to succeed; diagnostics: " +
                    diagnostics.diagnostics.joinToString("\n") { it.toString() }
            )
        }
        return classesDir
    }

    private fun jarClasses(classesDir: Path, jarPath: Path): Path {
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            val classFiles = Files.walk(classesDir)
            try {
                classFiles
                    .filter { Files.isRegularFile(it) }
                    .forEach { classFile ->
                        val entryName = classesDir.relativize(classFile).toString().replace(File.separatorChar, '/')
                        jar.putNextEntry(JarEntry(entryName))
                        Files.copy(classFile, jar)
                        jar.closeEntry()
                    }
            } finally {
                classFiles.close()
            }
        }
        return jarPath
    }

    private fun Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>.assertModule(className: String): ModuleType {
        val path = VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")
        return assertNotNull(
            this[path]?.moduleExportSurface?.moduleType,
            "Expected reflected module for $className at $path; actual providers: ${keys.map { it.value }}."
        )
    }

    private fun Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>.assertPackageModule(packageName: String): ModuleType {
        val path = VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua")
        return assertNotNull(
            this[path]?.moduleExportSurface?.moduleType,
            "Expected reflected package module for $packageName at $path; actual providers: ${keys.map { it.value }}."
        )
    }

    private fun ModuleType.assertClassName(binaryName: String, canonicalName: String = binaryName.replace('$', '.')) {
        val javaName = javaClassType().javaName
        assertEquals(binaryName, javaName.binaryName)
        assertEquals(canonicalName, javaName.canonicalName)
    }

    private fun ModuleType.javaClassType(): JavaClassType = javaInstanceType().classType

    private fun ModuleType.javaInstanceType(): JavaInstanceType {
        val type = assertNotNull(fields["__class"], "Expected __class field on reflected module $moduleName.")
        return type as? JavaInstanceType
            ?: error("Expected __class on reflected module $moduleName to be JavaInstanceType, was ${type.displayName}.")
    }

    /**
     * Green-lock host android.jar presence for configured_android_jar_* methods (TASK-615).
     *
     * When dual-path discovery finds a real jar, asserts size/path constraints (no G: invent).
     * When truly absent (common Windows CI without AppData android-35), soft-skips with an
     * explicit multi-OS recovery reason — never hard-fails on a missing AppData messaging
     * candidate alone, and never cites a macOS-only absolute path as the sole requirement.
     */
    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
        assertTrue(androidJar.length() > 0, "Expected non-empty Android platform jar at ${androidJar.path}.")
        assertTrue(
            androidJar.length() > 1_000_000L,
            "Expected real android-35 sized jar (~27MB) at ${androidJar.path}; size=${androidJar.length()}"
        )
        assertTrue(
            !androidJar.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true),
            "Host resolution must not hardcode Windows G:/Android/Sdk; got ${androidJar.path}."
        )
    }

    private fun missingAndroidJarSkipReason(missing: File): String {
        val productReason = JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-615")
        return "TASK-615 soft-skip: android.jar not found at ${missing.path}. $productReason " +
            "Install Android SDK Platform 35 under ANDROID_HOME / ANDROID_SDK_ROOT / " +
            "%LOCALAPPDATA%/Android/Sdk (Windows) or ~/Library/Android/sdk (macOS) / ~/Android/Sdk " +
            "(Linux), or set jvm.androidJar. Never invent presence; never hard-require a missing " +
            "AppData android-35 path or G:/Android/Sdk alone; never require macOS-only " +
            "$HOST_ANDROID_35_JAR on Windows CI."
    }

    private fun fakeAndroidSdk(vararg apiLevels: Int): Path {
        val root = Files.createTempDirectory("lua-parser-android-sdk-")
        apiLevels.forEach { apiLevel ->
            val platformDirectory = root.resolve("platforms/android-$apiLevel")
            Files.createDirectories(platformDirectory)
            Files.createFile(platformDirectory.resolve("android.jar"))
        }
        return root
    }

    private class RecordingUrlClassLoader(
        urls: Array<URL>,
        parent: ClassLoader
    ) : URLClassLoader(urls, parent) {
        val requestedClassNames = mutableListOf<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("fixture.")) {
                requestedClassNames += name
            }
            return super.loadClass(name, resolve)
        }
    }

    private data class FixtureSource(
        val relativePath: String,
        val contents: String
    )

    private data class FixtureWorkspace(
        val classesDir: Path,
        val jarPath: Path
    )

    private companion object {
        const val CONFIGURED_THING_CLASS = "fixture.config.ConfiguredThing"
        const val PLUGIN_MARKER_CLASS = "fixture.dupe.PluginMarker"

        /** WAVE / host hard-lock path for android-35 on mac agents (never G:/). */
        const val HOST_ANDROID_35_JAR =
            "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"

        /**
         * Dual-path host android.jar discovery for TASK-568 / TASK-608 / TASK-615:
         * 1) [JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath] (env + well-known)
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS candidate
         * 3) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
         * 4) well-known roots: Windows %LOCALAPPDATA%/Android/Sdk and user-home AppData,
         *    macOS Library/Android/sdk, Linux Android/Sdk
         * 5) WAVE mac absolute path last (present only on mac agents; never required on Windows)
         *
         * Prefers any present non-G jar. Never hard-requires a missing Windows AppData
         * android-35 path alone, Windows-only G:/Android/Sdk, or a macOS-only absolute path
         * on Windows CI. When all candidates are absent, returns a multi-OS messaging
         * candidate (may be missing); callers soft-skip via [missingAndroidJarSkipReason].
         */
        private fun resolveHostAndroidJar(): File {
            val home = System.getProperty("user.home").orEmpty()
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: System.getenv("LocalAppData")
                ?: home.takeIf { it.isNotBlank() }?.let { "$it${File.separator}AppData${File.separator}Local" }
            val candidates = linkedSetOf<File>()

            // Prefer product discovery that only returns existing jars first, then the
            // multi-OS default messaging candidate (may be absent).
            runCatching {
                JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()
            }.getOrNull()?.let { candidates += File(it) }
            runCatching { JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates += File(it) }

            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env ->
                    System.getenv(env)?.trim()?.takeIf(String::isNotEmpty)
                        ?: System.getenv().entries.firstOrNull { it.key.equals(env, ignoreCase = true) }?.value
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }

            // Explicit well-known dual-path roots for Windows CI / mac / Linux hosts.
            // Prefer present jars under any of these; never sole-hard-lock missing AppData.
            if (!localAppData.isNullOrBlank()) {
                candidates += File(localAppData, "Android/Sdk/platforms/android-35/android.jar")
                candidates += File(localAppData, "Android/Sdk/platforms/android-34/android.jar")
            }
            if (home.isNotBlank()) {
                candidates += File(home, "AppData/Local/Android/Sdk/platforms/android-35/android.jar")
                candidates += File(home, "AppData/Local/Android/Sdk/platforms/android-34/android.jar")
                candidates += File(home, "Library/Android/sdk/platforms/android-35/android.jar")
                candidates += File(home, "Library/Android/sdk/platforms/android-34/android.jar")
                candidates += File(home, "Android/Sdk/platforms/android-35/android.jar")
                candidates += File(home, "Android/Sdk/platforms/android-34/android.jar")
            }
            // Documented mac host path last (WAVE agents only; never required on Windows CI).
            candidates += File(HOST_ANDROID_35_JAR)

            fun isForbiddenGPath(file: File): Boolean {
                return file.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true)
            }

            fun isMacOnlyAbsolute(file: File): Boolean {
                return file.path.replace('\\', '/') == HOST_ANDROID_35_JAR
            }

            val presentNonG = candidates.firstOrNull { it.isFile && !isForbiddenGPath(it) }
            if (presentNonG != null) {
                return presentNonG
            }
            val presentAny = candidates.firstOrNull { it.isFile }
            if (presentAny != null) {
                return presentAny
            }
            // Messaging candidate: prefer multi-OS discovery / AppData / env over mac-only absolute.
            // Never sole-return mac HOST path when another non-G candidate exists for messaging.
            return candidates.firstOrNull { !isForbiddenGPath(it) && !isMacOnlyAbsolute(it) }
                ?: candidates.firstOrNull { !isForbiddenGPath(it) }
                ?: candidates.first()
        }
    }
}
