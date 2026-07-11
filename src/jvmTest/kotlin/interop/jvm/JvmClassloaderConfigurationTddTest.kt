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
import semantic.support.WorkspaceSemanticHarness

class JvmClassloaderConfigurationTddTest {
    /**
     * Host android-35 jar hard-lock for TASK-568.
     *
     * Prefer the WAVE worker path, then multi-OS discovery, then ANDROID_HOME /
     * ANDROID_SDK_ROOT. Never hard-require Windows-only `G:/Android/Sdk`.
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
    fun android_home_discovers_highest_installed_platform_android_jar_when_explicit_config_is_absent() {
        val sdkRoot = fakeAndroidSdk(23, 35, 34)
        val expectedJar = sdkRoot.resolve("platforms/android-35/android.jar").toString()
        val environment = mapOf(JvmWorkspaceConfiguration.ANDROID_HOME_ENV to sdkRoot.toString())
        val configuration = JvmWorkspaceConfiguration()

        assertEquals(listOf(expectedJar), configuration.reflectionClasspathEntries(environment))
        assertTrue(
            configuration.androidJarConfigurationNote(environment)
                .orEmpty()
                .contains("${JvmWorkspaceConfiguration.ANDROID_HOME_ENV}: $expectedJar")
        )
    }

    @Test
    fun android_sdk_root_discovers_platform_android_jar_when_android_home_is_absent() {
        val sdkRoot = fakeAndroidSdk(33)
        val expectedJar = sdkRoot.resolve("platforms/android-33/android.jar").toString()
        val environment = mapOf(JvmWorkspaceConfiguration.ANDROID_SDK_ROOT_ENV to sdkRoot.toString())
        val configuration = JvmWorkspaceConfiguration()

        assertEquals(listOf(expectedJar), configuration.reflectionClasspathEntries(environment))
        assertTrue(
            configuration.androidJarConfigurationNote(environment)
                .orEmpty()
                .contains("${JvmWorkspaceConfiguration.ANDROID_SDK_ROOT_ENV}: $expectedJar")
        )
    }

    @Test
    fun android_home_precedes_android_sdk_root_when_both_discover_platform_jars() {
        val androidHome = fakeAndroidSdk(34)
        val androidSdkRoot = fakeAndroidSdk(35)
        val expectedJar = androidHome.resolve("platforms/android-34/android.jar").toString()
        val environment = mapOf(
            JvmWorkspaceConfiguration.ANDROID_HOME_ENV to androidHome.toString(),
            JvmWorkspaceConfiguration.ANDROID_SDK_ROOT_ENV to androidSdkRoot.toString()
        )
        val configuration = JvmWorkspaceConfiguration()

        assertEquals(listOf(expectedJar), configuration.reflectionClasspathEntries(environment))
        assertTrue(
            configuration.androidJarConfigurationNote(environment)
                .orEmpty()
                .contains("selected by precedence")
        )
    }

    @Test
    fun missing_android_sdk_environment_reports_configuration_note_without_classpath_fallback() {
        // Isolate userHome / LOCALAPPDATA so host well-known SDK roots (and G:/) cannot
        // silently refill the reflective classpath when ANDROID_HOME / ANDROID_SDK_ROOT are unset.
        val emptyHome = Files.createTempDirectory("lua-parser-missing-sdk-home-").toFile()
        val emptyLocalAppData = Files.createTempDirectory("lua-parser-missing-localappdata-").toString()
        val configuration = JvmWorkspaceConfiguration()

        val classpath = configuration.reflectionClasspathEntries(
            environment = emptyMap(),
            userHome = emptyHome,
            localAppData = emptyLocalAppData
        )
        assertEquals(
            emptyList(),
            classpath,
            "Missing SDK env + empty well-known roots must not invent classpath entries " +
                "(including silent G:/ or host SDK fallback); got $classpath"
        )
        assertTrue(
            classpath.none { it.replace('\\', '/').startsWith("G:/", ignoreCase = true) },
            "Classpath must not silently fall back to G:/; got $classpath"
        )

        val note = configuration.androidJarConfigurationNote(
            environment = emptyMap(),
            userHome = emptyHome,
            localAppData = emptyLocalAppData
        ).orEmpty()
        assertTrue(
            note.contains(
                "neither ${JvmWorkspaceConfiguration.ANDROID_HOME_ENV} nor " +
                    JvmWorkspaceConfiguration.ANDROID_SDK_ROOT_ENV
            ),
            "Expected missing-env configuration note; got: $note"
        )
        assertTrue(
            note.contains(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY) ||
                note.contains("well-known") ||
                note.contains("Library/Android/sdk"),
            "Note should explain recovery via jvm.androidJar or host SDK roots; got: $note"
        )
        assertTrue(
            !note.contains("G:/Android/Sdk") ||
                note.contains("never") ||
                note.contains("last") ||
                note.contains("well-known"),
            "Missing-SDK note must not hard-require G:/; got: $note"
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
    fun configurable_jar_classpath_entry_loads_external_class_members() {
        val fixture = fixtureWorkspace(configuredThingSource())
        val module = provider.providersFor(
            JvmWorkspaceConfiguration(
                classpathEntries = listOf(fixture.jarPath.toString()),
                classes = linkedSetOf(CONFIGURED_THING_CLASS)
            )
        ).assertModule(CONFIGURED_THING_CLASS)

        assertTrue("NAME" in module.fields)
        assertTrue("describe" in module.javaInstanceType().allInstanceMembers())
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

    @Test
    fun separate_classpath_configurations_are_isolated_for_same_binary_name() {
        val alpha = fixtureWorkspace(pluginMarkerSource(staticField = "ALPHA_ONLY"))
        val beta = fixtureWorkspace(pluginMarkerSource(staticField = "BETA_ONLY"))

        val alphaModule = provider.providersFor(
            JvmWorkspaceConfiguration(
                classpathEntries = listOf(alpha.jarPath.toString()),
                classes = linkedSetOf(PLUGIN_MARKER_CLASS)
            )
        ).assertModule(PLUGIN_MARKER_CLASS)
        val betaModule = provider.providersFor(
            JvmWorkspaceConfiguration(
                classpathEntries = listOf(beta.jarPath.toString()),
                classes = linkedSetOf(PLUGIN_MARKER_CLASS)
            )
        ).assertModule(PLUGIN_MARKER_CLASS)

        assertTrue("ALPHA_ONLY" in alphaModule.fields)
        assertFalse("BETA_ONLY" in alphaModule.fields)
        assertTrue("BETA_ONLY" in betaModule.fields)
        assertFalse("ALPHA_ONLY" in betaModule.fields)
    }

    @Test
    fun supplied_classloader_is_reused_and_wins_over_conflicting_classpath_entries() {
        val alpha = fixtureWorkspace(pluginMarkerSource(staticField = "FROM_SUPPLIED_LOADER"))
        val beta = fixtureWorkspace(pluginMarkerSource(staticField = "FROM_CLASSPATH_ENTRY"))
        val loader = RecordingUrlClassLoader(
            urls = arrayOf(alpha.jarPath.toUri().toURL()),
            parent = JvmClassModuleProvider::class.java.classLoader
        )

        val module = provider.providersFor(
            JvmWorkspaceConfiguration(
                classLoader = loader,
                classpathEntries = listOf(beta.jarPath.toString()),
                classes = linkedSetOf(PLUGIN_MARKER_CLASS)
            )
        ).assertModule(PLUGIN_MARKER_CLASS)

        assertTrue("FROM_SUPPLIED_LOADER" in module.fields)
        assertFalse("FROM_CLASSPATH_ENTRY" in module.fields)
        assertTrue(
            PLUGIN_MARKER_CLASS in loader.requestedClassNames,
            "Expected the configured ClassLoader instance to receive the reflection load for $PLUGIN_MARKER_CLASS."
        )
    }

    @Test
    fun java_inner_classes_resolve_from_binary_dotted_and_android_lua_underscore_names() {
        val fixture = fixtureWorkspace(configuredThingSource())
        val configuration = JvmWorkspaceConfiguration(
            classpathEntries = listOf(fixture.jarPath.toString()),
            classes = linkedSetOf(
                "fixture.config.ConfiguredThing\$Nested",
                "fixture.config.ConfiguredThing.Nested",
                "fixture.config.ConfiguredThing_Nested"
            )
        )

        val requested = provider.requestedClasses(configuration)
        val module = provider.providersFor(configuration).assertModule("fixture.config.ConfiguredThing\$Nested")

        assertEquals(linkedSetOf("fixture.config.ConfiguredThing\$Nested"), requested)
        module.assertClassName("fixture.config.ConfiguredThing\$Nested")
        assertTrue("VALUE" in module.fields)
    }

    @Test
    fun configured_android_jar_loads_framework_classes_from_expected_sdk_path() {
        assertAndroidJarPresent()
        val providers = provider.providersFor(
            JvmWorkspaceConfiguration(
                androidJar = androidJar.path,
                classes = linkedSetOf("android.content.Context", "android.widget.TextView")
            )
        )

        providers.assertModule("android.content.Context").assertClassName("android.content.Context")
        providers.assertModule("android.widget.TextView").assertClassName("android.widget.TextView")

        // Effective classpath must include the resolved host jar only when discovery/config succeeds.
        val configuration = JvmWorkspaceConfiguration(androidJar = androidJar.path)
        assertTrue(
            configuration.effectiveClasspathEntries().any {
                File(it).canonicalFile == androidJar.canonicalFile
            },
            "effectiveClasspathEntries must include configured host android.jar at ${androidJar.path}"
        )
        assertTrue(
            !androidJar.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true),
            "Host hard-lock must not remain Windows-only G:/ path; got ${androidJar.path}"
        )
    }

    @Test
    fun configured_android_jar_resolves_nested_framework_classes_by_binary_and_dotted_names() {
        assertAndroidJarPresent()
        val configuration = JvmWorkspaceConfiguration(
            androidJar = androidJar.path,
            classes = linkedSetOf(
                "android.view.View\$OnClickListener",
                "android.view.View.OnClickListener",
                "android.widget.TextView.BufferType"
            )
        )

        val requested = provider.requestedClasses(configuration)

        assertTrue("android.view.View\$OnClickListener" in requested)
        assertTrue("android.widget.TextView\$BufferType" in requested)
        provider.providersFor(configuration).assertModule("android.view.View\$OnClickListener")
        provider.providersFor(configuration).assertModule("android.widget.TextView\$BufferType")
    }

    @Test
    fun missing_luajava_target_reports_actionable_diagnostic_with_configured_classpath_context() {
        val fixture = fixtureWorkspace(configuredThingSource())
        val target = "fixture.missing.DoesNotExist"
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local Missing = luajava.bindClass(\"$target\")\nreturn Missing",
            metadata = mapOf(JvmWorkspaceConfiguration.CLASSPATH_METADATA_KEY to fixture.jarPath.toString()),
            engine = JvmWorkspaceEngine()
        )

        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))

        assertTrue(
            diagnostics.any { diagnostic ->
                diagnostic.message.contains(target) &&
                    listOf("missing", "not found", "unknown", "unresolved").any {
                        diagnostic.message.contains(it, ignoreCase = true)
                    }
            },
            "Expected missing JVM class diagnostic mentioning $target; actual diagnostics: ${diagnostics.map { it.message }}."
        )
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

    private fun assertAndroidJarPresent() {
        assertTrue(
            androidJar.isFile,
            "TASK-568 requires Android platform jar at ${androidJar.path}. " +
                "Install Android SDK Platform 35 at " +
                "$HOST_ANDROID_35_JAR or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar " +
                "before running Android reflection TDD tests. Never hard-require G:/Android/Sdk."
        )
        assertTrue(androidJar.length() > 0, "Expected non-empty Android platform jar at ${androidJar.path}.")
        assertTrue(
            androidJar.length() > 1_000_000L,
            "Expected real android-35 sized jar (~27MB) at ${androidJar.path}; size=${androidJar.length()}"
        )
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

        /** WAVE / host hard-lock path for android-35 (never G:/). */
        const val HOST_ANDROID_35_JAR =
            "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"

        /**
         * Host-resolution order for TASK-568:
         * 1) mac SDK path used by WAVE workers
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
         * 3) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
         *
         * Never hard-requires Windows-only G:/Android/Sdk.
         */
        private fun resolveHostAndroidJar(): File {
            val candidates = linkedSetOf<File>()
            candidates += File(HOST_ANDROID_35_JAR)
            candidates += File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env -> System.getenv(env)?.trim()?.takeIf(String::isNotEmpty) }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }
            val resolved = candidates.firstOrNull { it.isFile } ?: candidates.first()
            // Drop any accidental G:/ hardcode when a real host jar exists among candidates.
            if (!resolved.isFile) {
                return resolved
            }
            val normalized = resolved.path.replace('\\', '/')
            if (normalized.startsWith("G:/Android/Sdk", ignoreCase = true)) {
                val nonWindows = candidates.firstOrNull {
                    it.isFile && !it.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true)
                }
                if (nonWindows != null) {
                    return nonWindows
                }
            }
            return resolved
        }
    }
}
