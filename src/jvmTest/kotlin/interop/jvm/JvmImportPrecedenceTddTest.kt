package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Wave K import-precedence corpus (wildcard-audit finding 1).
 *
 * AndroLua installs imports into _G sequentially, so the LAST document import wins
 * short-name collisions. The engine assembled short-name `importPrefixes` with the
 * document's wildcard prefixes appended AFTER [JvmClassModuleProvider.DEFAULT_IMPORT_PREFIXES],
 * so a document import could never override a default prefix:
 * `import "android.widget.*"` followed by `import "android.support.v7.widget.*"` kept
 * resolving `Toolbar` to android.widget.Toolbar instead of the support class.
 *
 * The fix puts the document's own wildcard prefixes FIRST (reverse document order — last
 * import highest priority) ahead of the configured/default prefixes, which stay as fallbacks
 * for identifiers the document never imported (bare `Button` still lands on
 * android.widget.Button even when the document only imported the support package).
 *
 * android.jar hosts android.widget.Toolbar; the support package is provided by a compiled
 * fixture jar on `jvm.classpath` carrying a marker static field no android.widget class has.
 * The corpus soft-skips with an explicit reason when the host android.jar is missing.
 * This worker does not run Gradle; serial review owns verification.
 */
class JvmImportPrecedenceTddTest {
    private val androidJar = resolveAndroidJar()

    @Test
    fun last_document_wildcard_import_wins_short_name_over_default_prefix() {
        requireAndroidJarOrSkip()
        val fixture = supportToolbarFixture()
        val context = workspaceContextFor(
            """
            import "android.widget.*"
            import "android.support.v7.widget.*"
            local t = Toolbar()
            """.trimIndent(),
            fixture
        )

        val toolbar = context.importedSymbols["Toolbar"]
            ?: fail("Expected the free Toolbar identifier to activate an import symbol; got=${context.importedSymbols.keys.sorted()}")
        assertEquals(
            "__jvm__/classes/android/support/v7/widget/Toolbar.lua",
            toolbar.providerPath.value,
            "The last document wildcard import (android.support.v7.widget.*) must override " +
                "the default android.widget prefix for short-name resolution"
        )
        assertTrue(
            SUPPORT_FIXTURE_MARKER in toolbar.moduleType.fields,
            "Expected the support fixture marker on the resolved Toolbar module; " +
                "fields=${toolbar.moduleType.fields.keys.sorted().take(20)}"
        )
    }

    @Test
    fun reversed_document_order_flips_the_winner_back_to_android_widget() {
        requireAndroidJarOrSkip()
        val fixture = supportToolbarFixture()
        val context = workspaceContextFor(
            """
            import "android.support.v7.widget.*"
            import "android.widget.*"
            local t = Toolbar()
            """.trimIndent(),
            fixture
        )

        val toolbar = context.importedSymbols["Toolbar"]
            ?: fail("Expected the free Toolbar identifier to activate an import symbol; got=${context.importedSymbols.keys.sorted()}")
        assertEquals(
            "__jvm__/classes/android/widget/Toolbar.lua",
            toolbar.providerPath.value,
            "With android.widget.* last, the last document import must win (android.widget.Toolbar)"
        )
    }

    @Test
    fun identifier_without_document_import_still_resolves_through_default_prefixes() {
        requireAndroidJarOrSkip()
        val fixture = supportToolbarFixture()
        val context = workspaceContextFor(
            """
            import "android.support.v7.widget.*"
            local b = Button()
            """.trimIndent(),
            fixture
        )

        val button = context.importedSymbols["Button"]
            ?: fail("Expected the free Button identifier to activate an import symbol; got=${context.importedSymbols.keys.sorted()}")
        assertEquals(
            "__jvm__/classes/android/widget/Button.lua",
            button.providerPath.value,
            "Defaults stay the fallback: Button is not in the support fixture, so it must " +
                "resolve through the android.widget default prefix"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the workspace and returns the ENGINE-level import map for the document.
     * The engine map is what the fixed import-prefix chain governs: free UpperCamel
     * identifiers resolve through [JvmClassModuleProvider] short-name candidate walks in
     * `importPrefixes` order, so the resolved provider path pins the winning prefix.
     */
    private fun workspaceContextFor(source: String, fixture: FixtureWorkspace) =
        with(JvmWorkspaceEngine()) {
            val mainPath = VirtualPath.of("main.lua")
            val input = LuaWorkspaceInput(
                files = linkedMapOf(mainPath to source),
                metadata = mapOf(
                    JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path,
                    JvmWorkspaceConfiguration.CLASSPATH_METADATA_KEY to fixture.jarPath.toString()
                )
            )
            val snapshot = build(input).snapshot
            workspaceContext(input, mainPath, snapshot)
        }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private fun supportToolbarFixture(): FixtureWorkspace {
        return fixtureWorkspace(
            FixtureSource(
                "android/support/v7/widget/Toolbar.java",
                """
                package android.support.v7.widget;

                public class Toolbar {
                    public static final String $SUPPORT_FIXTURE_MARKER = "wave-k-precedence";
                }
                """.trimIndent()
            )
        )
    }

    private fun fixtureWorkspace(vararg sources: FixtureSource): FixtureWorkspace {
        val root = Files.createTempDirectory("lua-parser-import-precedence-")
        val classesDir = compileJavaSources(root, sources.toList())
        val jarPath = jarClasses(classesDir, root.resolve("support-fixture.jar"))
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
            "Expected the import precedence corpus to run on a JDK with a system Java compiler."
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

    private data class FixtureSource(
        val relativePath: String,
        val contents: String
    )

    private data class FixtureWorkspace(
        val classesDir: Path,
        val jarPath: Path
    )

    private companion object {
        const val SUPPORT_FIXTURE_MARKER = "SUPPORT_FIXTURE_MARKER"

        /**
         * Host-resolution order for the android.jar gate (mirrors the wave-J corpus):
         * mac SDK path, DEFAULT_ANDROID_JAR_PATH, then ANDROID_HOME / ANDROID_SDK_ROOT
         * platforms 35/34. Never hard-requires a missing jar; callers soft-skip.
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

        private fun missingAndroidJarSkipReason(androidJar: File): String {
            return "WAVE-K skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT / " +
                "jvm.androidJar) before running the import precedence corpus."
        }
    }
}
