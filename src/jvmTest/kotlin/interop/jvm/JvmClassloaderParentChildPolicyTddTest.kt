package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import semantic.support.WorkspaceSemanticHarness
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
import kotlin.test.fail

/**
 * TASK-210 corpus: parent-first vs child-first classloader policy for JVM reflection.
 *
 * Product defaults (see docs/jvm-reflection-classloader-design.md):
 * - classpath-built loaders are `URLClassLoader(urls, baseClassLoader)` → parent-first
 * - an explicit [JvmWorkspaceConfiguration.classLoader] is used as-is (caller can supply
 *   a child-first loader when they need child-first semantics)
 *
 * This suite encodes those policies with conflicting same-binary fixtures and asserts
 * that misconfiguration surfaces clear, actionable errors rather than silent wrong wins.
 */
class JvmClassloaderParentChildPolicyTddTest {
    private val systemBase = JvmClassModuleProvider::class.java.classLoader

    @Test
    fun default_classpath_built_loader_is_parent_first_when_parent_defines_same_binary() {
        val parentFixture = fixtureWorkspace(pluginMarkerSource(staticField = "PARENT_MARKER"))
        val childFixture = fixtureWorkspace(pluginMarkerSource(staticField = "CHILD_MARKER"))
        val parentLoader = URLClassLoader(
            arrayOf(parentFixture.jarPath.toUri().toURL()),
            systemBase
        )
        val provider = JvmClassModuleProvider(baseClassLoader = parentLoader)

        val module = provider.providersFor(
            JvmWorkspaceConfiguration(
                classpathEntries = listOf(childFixture.jarPath.toString()),
                classes = linkedSetOf(PLUGIN_MARKER_CLASS)
            )
        ).assertModule(PLUGIN_MARKER_CLASS)

        assertTrue(
            "PARENT_MARKER" in module.fields,
            "Parent-first default must prefer the parent-defined $PLUGIN_MARKER_CLASS marker."
        )
        assertFalse(
            "CHILD_MARKER" in module.fields,
            "Parent-first default must not expose the shadowed child classpath marker."
        )
    }
    @Test
    fun parent_first_policy_via_explicit_parent_classloader_chain_prefers_parent_fixture() {
        val parentFixture = fixtureWorkspace(pluginMarkerSource(staticField = "PARENT_ONLY"))
        val childFixture = fixtureWorkspace(pluginMarkerSource(staticField = "CHILD_ONLY"))
        val parentLoader = ParentFirstUrlClassLoader(
            urls = arrayOf(parentFixture.jarPath.toUri().toURL()),
            parent = systemBase
        )
        val childFirstWouldLose = ParentFirstUrlClassLoader(
            urls = arrayOf(childFixture.jarPath.toUri().toURL()),
            parent = parentLoader
        )

        val module = JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                classLoader = childFirstWouldLose,
                classes = linkedSetOf(PLUGIN_MARKER_CLASS)
            )
        ).assertModule(PLUGIN_MARKER_CLASS)

        assertTrue("PARENT_ONLY" in module.fields)
        assertFalse("CHILD_ONLY" in module.fields)
        assertTrue(
            PLUGIN_MARKER_CLASS in parentLoader.requestedClassNames,
            "Parent-first chain must consult the parent loader for $PLUGIN_MARKER_CLASS."
        )
    }
    @Test
    fun child_first_supplied_classloader_prefers_child_fixture_over_parent() {
        val parentFixture = fixtureWorkspace(pluginMarkerSource(staticField = "PARENT_MARKER"))
        val childFixture = fixtureWorkspace(pluginMarkerSource(staticField = "CHILD_MARKER"))
        val parentLoader = URLClassLoader(
            arrayOf(parentFixture.jarPath.toUri().toURL()),
            systemBase
        )
        val childFirst = ChildFirstUrlClassLoader(
            urls = arrayOf(childFixture.jarPath.toUri().toURL()),
            parent = parentLoader
        )

        val module = JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                classLoader = childFirst,
                // Conflicting classpath entry must not override the supplied child-first loader.
                classpathEntries = listOf(parentFixture.jarPath.toString()),
                classes = linkedSetOf(PLUGIN_MARKER_CLASS)
            )
        ).assertModule(PLUGIN_MARKER_CLASS)

        assertTrue(
            "CHILD_MARKER" in module.fields,
            "Child-first policy must prefer the child-defined $PLUGIN_MARKER_CLASS marker."
        )
        assertFalse(
            "PARENT_MARKER" in module.fields,
            "Child-first policy must not expose the shadowed parent marker for the same binary."
        )
        assertTrue(
            PLUGIN_MARKER_CLASS in childFirst.childRequestedClassNames,
            "Expected the child-first loader to attempt a local load for $PLUGIN_MARKER_CLASS."
        )
    }
    @Test
    fun policy_isolation_across_providers_does_not_mutate_process_classpath() {
        val parentFixture = fixtureWorkspace(pluginMarkerSource(staticField = "ISOLATED_PARENT"))
        val childFixture = fixtureWorkspace(pluginMarkerSource(staticField = "ISOLATED_CHILD"))
        val parentLoader = URLClassLoader(
            arrayOf(parentFixture.jarPath.toUri().toURL()),
            systemBase
        )
        val childFirst = ChildFirstUrlClassLoader(
            urls = arrayOf(childFixture.jarPath.toUri().toURL()),
            parent = parentLoader
        )

        JvmClassModuleProvider(baseClassLoader = parentLoader).providersFor(
            JvmWorkspaceConfiguration(
                classpathEntries = listOf(childFixture.jarPath.toString()),
                classes = linkedSetOf(PLUGIN_MARKER_CLASS)
            )
        )
        JvmClassModuleProvider().providersFor(
            JvmWorkspaceConfiguration(
                classLoader = childFirst,
                classes = linkedSetOf(PLUGIN_MARKER_CLASS)
            )
        )

        val processLoad = runCatching {
            Class.forName(PLUGIN_MARKER_CLASS, false, systemBase)
        }
        assertTrue(
            processLoad.isFailure,
            "Reflection classloaders must not install fixture classes onto the process/system loader."
        )
    }

    private fun assertActionableMissingClassDiagnostic(messages: List<String>, target: String) {
        val hit = messages.any { message ->
            message.contains(target) &&
                listOf("missing", "not found", "unknown", "unresolved", "cannot resolve").any {
                    message.contains(it, ignoreCase = true)
                }
        }
        assertTrue(
            hit,
            "Expected actionable missing-class diagnostic mentioning $target; actual: $messages"
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
            }
            """.trimIndent()
        )
    }

    private fun fixtureWorkspace(vararg sources: FixtureSource): FixtureWorkspace {
        val root = Files.createTempDirectory("lua-parser-parent-child-policy-")
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
            "Expected parent/child classloader policy tests to run on a JDK with a system Java compiler."
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

    private fun ModuleType.javaInstanceType(): JavaInstanceType {
        val type = assertNotNull(fields["__class"], "Expected __class field on reflected module $moduleName.")
        return type as? JavaInstanceType
            ?: fail("Expected __class on reflected module $moduleName to be JavaInstanceType, was ${type.displayName}.")
    }

    /**
     * Explicit parent-first URL class loader used to record parent consultation.
     * Mirrors the default JVM [URLClassLoader] delegation model.
     */
    private class ParentFirstUrlClassLoader(
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

    /**
     * Child-first URL class loader: try local URLs before delegating to the parent.
     * Callers inject this via [JvmWorkspaceConfiguration.classLoader] when they need
     * child-first reflection semantics (product default remains parent-first).
     */
    private class ChildFirstUrlClassLoader(
        urls: Array<URL>,
        parent: ClassLoader?
    ) : URLClassLoader(urls, parent) {
        val childRequestedClassNames = mutableListOf<String>()
        val parentDelegatedClassNames = mutableListOf<String>()

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
                    if (name.startsWith("fixture.")) {
                        childRequestedClassNames += name
                    }
                    if (resolve) {
                        resolveClass(local)
                    }
                    return local
                }

                if (name.startsWith("fixture.")) {
                    parentDelegatedClassNames += name
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
        const val PLUGIN_MARKER_CLASS = "fixture.dupe.PluginMarker"
        const val CONFIGURED_THING_CLASS = "fixture.config.ConfiguredThing"
    }
}
