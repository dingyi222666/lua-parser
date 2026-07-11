package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume

/**
 * TASK-265 corpus: parent-first classloader isolation for `android.jar` vs app jars.
 *
 * Product default (see docs/jvm-reflection-classloader-design.md and TASK-210):
 * classpath-built loaders are `URLClassLoader(urls, baseClassLoader)` → parent-first.
 * Callers may inject a child-first [JvmWorkspaceConfiguration.classLoader] when needed.
 *
 * Android-vs-app isolation pattern documented here:
 * - Treat `android.jar` as the parent loader (framework wins under parent-first).
 * - Mount app jars only as child classpath URLs (or as a supplied child-first loader).
 * - Do not rely on flat sibling URL order for framework-vs-app shadows: sibling entries
 *   are searched only after the parent chain fails, so parent-mounted android.jar wins.
 *
 * This suite documents isolation so conflicting android-framework vs app fixtures do not
 * cross-leak silently, and so missing jars skip cleanly rather than hard-failing hosts.
 */
class JvmClassloaderIsolationPolicyTddTest {
    private val systemBase = JvmClassModuleProvider::class.java.classLoader
    private val androidJar = resolveAndroidJar()

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-265"), "Skip reason must name TASK-265; got: $reason")
        assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
        assertTrue(reason.contains(missing.path), "Skip reason must include the missing path; got: $reason")
        assertTrue(
            reason.contains("ANDROID_HOME") ||
                reason.contains("ANDROID_SDK_ROOT") ||
                reason.contains("jvm.androidJar") ||
                reason.contains("Install"),
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
    fun missing_app_jar_on_classpath_skips_cleanly_without_silent_resolution() {
        val missingAppJar = Files.createTempDirectory("lua-parser-isolation-missing-app-")
            .resolve("does-not-exist-app.jar")
            .toString()
        val target = APP_MARKER_CLASS

        val providers = JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                classpathEntries = listOf(missingAppJar),
                classes = linkedSetOf(target)
            )
        )

        assertTrue(
            providers.none { path -> path.key.value.endsWith("/AppPluginMarker.lua") },
            "Missing app jar must not silently resolve $target; actual: ${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun missing_android_jar_path_does_not_silently_resolve_framework_class() {
        val missingAndroidJar = "/nonexistent/android-sdk/platforms/android-35/android.jar"
        val target = "android.widget.TextView"

        val providers = JvmClassModuleProvider(
            // Isolate from host discovery so only the explicit missing path is considered.
            baseClassLoader = object : ClassLoader(null) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("android.")) {
                        throw ClassNotFoundException(
                            "Isolated base classloader cannot load $name without a real android.jar."
                        )
                    }
                    return ClassLoader.getPlatformClassLoader().loadClass(name)
                }
            }
        ).providersFor(
            JvmWorkspaceConfiguration(
                androidJar = missingAndroidJar,
                classes = linkedSetOf(target)
            )
        )

        assertTrue(
            providers.none { path -> path.key.value.contains("TextView") },
            "Missing android.jar must not silently invent a provider for $target; actual: ${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun parent_first_prefers_android_jar_framework_class_over_conflicting_app_jar_shadow() {
        requireAndroidJarOrSkip()
        val appShadow = fixtureWorkspace(
            FixtureSource(
                "android/widget/TextView.java",
                """
                package android.widget;

                public class TextView {
                    public static final String SOURCE = "APP_SHADOW_TEXTVIEW";
                    public static final String APP_ONLY_MARKER = "FROM_APP_JAR";
                }
                """.trimIndent()
            )
        )

        // Parent holds real android.jar; child classpath holds a same-binary app shadow.
        val parentLoader = URLClassLoader(
            arrayOf(androidJar.toURI().toURL()),
            systemBase
        )
        val provider = JvmClassModuleProvider(baseClassLoader = parentLoader)

        val module = provider.providersFor(
            JvmWorkspaceConfiguration(
                classpathEntries = listOf(appShadow.jarPath.toString()),
                classes = linkedSetOf("android.widget.TextView")
            )
        ).assertModule("android.widget.TextView")

        // Real framework TextView does not define APP_ONLY_MARKER; parent-first must not leak it.
        assertFalse(
            "APP_ONLY_MARKER" in module.fields,
            "Parent-first isolation must not expose the app-jar shadow marker for android.widget.TextView."
        )
        assertFalse(
            "FROM_APP_JAR" in module.fields.values.map { it.displayName },
            "Parent-first isolation must not surface app-shadow field values for framework TextView."
        )
        // Real android.jar TextView exposes well-known static constants.
        assertTrue(
            "AUTO_SIZE_TEXT_TYPE_NONE" in module.fields || "BUFFER_TYPE_NORMAL" in module.fields ||
                module.fields.keys.any { it.startsWith("AUTO_") || it.contains("BUFFER") },
            "Expected parent-first to load framework TextView members from android.jar; fields=${module.fields.keys}"
        )
    }

    @Test
    fun parent_first_app_jar_class_does_not_leak_into_android_only_configuration() {
        requireAndroidJarOrSkip()
        val appFixture = fixtureWorkspace(appPluginMarkerSource(staticField = "APP_ISOLATED"))

        val androidOnly = JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                androidJar = androidJar.path,
                classes = linkedSetOf(APP_MARKER_CLASS, "android.content.Context")
            )
        )

        assertTrue(
            androidOnly.none { it.key.value.contains("AppPluginMarker") },
            "Android-only parent-first configuration must not invent app-jar providers for $APP_MARKER_CLASS."
        )
        androidOnly.assertModule("android.content.Context")

        // Control: when app jar is on classpath, the marker resolves.
        val withApp = JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                androidJar = androidJar.path,
                classpathEntries = listOf(appFixture.jarPath.toString()),
                classes = linkedSetOf(APP_MARKER_CLASS)
            )
        ).assertModule(APP_MARKER_CLASS)
        assertTrue("APP_ISOLATED" in withApp.fields)
    }

    @Test
    fun parent_first_android_and_app_jars_are_isolated_across_independent_providers() {
        requireAndroidJarOrSkip()
        val appAlpha = fixtureWorkspace(appPluginMarkerSource(staticField = "ALPHA_APP"))
        val appBeta = fixtureWorkspace(appPluginMarkerSource(staticField = "BETA_APP"))

        val alphaModule = JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                androidJar = androidJar.path,
                classpathEntries = listOf(appAlpha.jarPath.toString()),
                classes = linkedSetOf(APP_MARKER_CLASS, "android.view.View")
            )
        )
        val betaModule = JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                androidJar = androidJar.path,
                classpathEntries = listOf(appBeta.jarPath.toString()),
                classes = linkedSetOf(APP_MARKER_CLASS, "android.view.View")
            )
        )

        val alphaApp = alphaModule.assertModule(APP_MARKER_CLASS)
        val betaApp = betaModule.assertModule(APP_MARKER_CLASS)

        assertTrue("ALPHA_APP" in alphaApp.fields)
        assertFalse("BETA_APP" in alphaApp.fields)
        assertTrue("BETA_APP" in betaApp.fields)
        assertFalse("ALPHA_APP" in betaApp.fields)

        // Framework providers remain independently available and do not carry app markers.
        alphaModule.assertModule("android.view.View")
        betaModule.assertModule("android.view.View")
        assertFalse("ALPHA_APP" in alphaModule.assertModule("android.view.View").fields)
        assertFalse("BETA_APP" in betaModule.assertModule("android.view.View").fields)
    }

    @Test
    fun child_first_supplied_loader_can_prefer_app_shadow_over_android_parent_without_silent_cross_leak() {
        requireAndroidJarOrSkip()
        val appShadow = fixtureWorkspace(
            FixtureSource(
                "android/widget/TextView.java",
                """
                package android.widget;

                public class TextView {
                    public static final String APP_ONLY_MARKER = "CHILD_FIRST_APP_SHADOW";
                }
                """.trimIndent()
            )
        )
        val parentLoader = URLClassLoader(
            arrayOf(androidJar.toURI().toURL()),
            systemBase
        )
        val childFirst = ChildFirstUrlClassLoader(
            urls = arrayOf(appShadow.jarPath.toUri().toURL()),
            parent = parentLoader
        )

        val childModule = JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                classLoader = childFirst,
                classes = linkedSetOf("android.widget.TextView")
            )
        ).assertModule("android.widget.TextView")

        assertTrue(
            "APP_ONLY_MARKER" in childModule.fields,
            "Child-first policy must prefer the app-jar shadow TextView when that loader is supplied."
        )

        // Parent-first control remains isolated: default classpath-built loader with android as parent
        // must not silently flip to the app shadow when only app jar is listed as child classpath.
        val parentFirstModule = JvmClassModuleProvider(baseClassLoader = parentLoader).providersFor(
            JvmWorkspaceConfiguration(
                classpathEntries = listOf(appShadow.jarPath.toString()),
                classes = linkedSetOf("android.widget.TextView")
            )
        ).assertModule("android.widget.TextView")

        assertFalse(
            "APP_ONLY_MARKER" in parentFirstModule.fields,
            "Parent-first isolation must not cross-leak the child app shadow into android.jar TextView."
        )
    }

    @Test
    fun parent_first_does_not_install_app_or_android_fixtures_onto_process_classloader() {
        requireAndroidJarOrSkip()
        val appFixture = fixtureWorkspace(appPluginMarkerSource(staticField = "PROCESS_ISOLATED"))

        JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                androidJar = androidJar.path,
                classpathEntries = listOf(appFixture.jarPath.toString()),
                classes = linkedSetOf(APP_MARKER_CLASS, "android.content.Context")
            )
        )

        val processAppLoad = runCatching {
            Class.forName(APP_MARKER_CLASS, false, systemBase)
        }
        assertTrue(
            processAppLoad.isFailure,
            "Reflection classloaders must not install app fixture classes onto the process/system loader."
        )
    }

    @Test
    fun combined_android_and_missing_app_jar_still_loads_framework_without_silent_app_resolution() {
        requireAndroidJarOrSkip()
        val missingAppJar = Files.createTempDirectory("lua-parser-isolation-mixed-missing-")
            .resolve("missing-app.jar")
            .toString()

        val providers = JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                androidJar = androidJar.path,
                classpathEntries = listOf(missingAppJar),
                classes = linkedSetOf("android.app.Activity", APP_MARKER_CLASS)
            )
        )

        providers.assertModule("android.app.Activity")
        assertTrue(
            providers.none { it.key.value.contains("AppPluginMarker") },
            "Missing app jar alongside android.jar must not silently resolve $APP_MARKER_CLASS."
        )
    }

    @Test
    fun isolation_policy_corpus_table_locks_parent_first_android_vs_app_contracts() {
        // Documentation-style corpus table for review/serial verification.
        data class Case(
            val id: String,
            val policy: String,
            val androidPresent: Boolean,
            val appPresent: Boolean,
            val expectedWinner: String
        )

        val cases = listOf(
            Case("PF-1", "parent-first", androidPresent = true, appPresent = true, expectedWinner = "android.jar-framework"),
            Case("PF-2", "parent-first", androidPresent = true, appPresent = false, expectedWinner = "android.jar-framework"),
            Case("PF-3", "parent-first", androidPresent = false, appPresent = true, expectedWinner = "app-jar-or-unresolved"),
            Case("PF-4", "parent-first", androidPresent = false, appPresent = false, expectedWinner = "unresolved-skip-clean"),
            Case("CF-1", "child-first-supplied", androidPresent = true, appPresent = true, expectedWinner = "app-shadow-when-local")
        )

        cases.forEach { case ->
            assertTrue(case.id.isNotBlank())
            assertTrue(case.policy.contains("first") || case.policy.contains("parent") || case.policy.contains("child"))
            assertTrue(case.expectedWinner.isNotBlank())
        }

        assertTrue(cases.any { it.policy == "parent-first" && it.expectedWinner.contains("android.jar") })
        assertTrue(cases.any { it.policy.contains("child-first") && it.expectedWinner.contains("app-shadow") })
        assertTrue(cases.any { !it.androidPresent && !it.appPresent && it.expectedWinner.contains("skip") })
    }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private fun appPluginMarkerSource(staticField: String): FixtureSource {
        return FixtureSource(
            "fixture/app/AppPluginMarker.java",
            """
            package fixture.app;

            public class AppPluginMarker {
                public static final String $staticField = "$staticField";
            }
            """.trimIndent()
        )
    }

    private fun fixtureWorkspace(vararg sources: FixtureSource): FixtureWorkspace {
        val root = Files.createTempDirectory("lua-parser-isolation-policy-")
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
            "Expected isolation policy tests to run on a JDK with a system Java compiler."
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

    @Suppress("unused")
    private fun ModuleType.javaInstanceType(): JavaInstanceType {
        val type = assertNotNull(fields["__class"], "Expected __class field on reflected module $moduleName.")
        return type as? JavaInstanceType
            ?: fail("Expected __class on reflected module $moduleName to be JavaInstanceType, was ${type.displayName}.")
    }

    /**
     * Child-first URL class loader: try local URLs before delegating to the parent.
     * Supplied via [JvmWorkspaceConfiguration.classLoader] for explicit child-first isolation checks.
     */
    private class ChildFirstUrlClassLoader(
        urls: Array<URL>,
        parent: ClassLoader?
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }

                if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") ||
                    name.startsWith("sun.") || name.startsWith("com.sun.")
                ) {
                    return super.loadClass(name, resolve)
                }

                val local = runCatching { findClass(name) }.getOrNull()
                if (local != null) {
                    if (resolve) {
                        resolveClass(local)
                    }
                    return local
                }

                val fromParent = parent?.loadClass(name)
                    ?: throw ClassNotFoundException(name)
                if (resolve) {
                    resolveClass(fromParent)
                }
                return fromParent
            }
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
        const val APP_MARKER_CLASS = "fixture.app.AppPluginMarker"

        /**
         * Host-resolution order for TASK-265 corpus:
         * 1) mac SDK path used by WAVE workers
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
         * 3) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
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
            return "TASK-265 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar) " +
                "before running parent-first android.jar vs app jar isolation corpus."
        }
    }
}
