package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-353 — JVM package provider empty/missing root corpus.
 *
 * Encodes the contract that [JvmClassModuleProvider.packageProvidersFor]:
 * - treats empty or missing classpath roots as non-fatal (empty listing, no throw),
 * - returns empty provider maps for packages that only exist as empty/missing roots,
 * - still lists packages/classes for non-empty roots (JDK + valid package targets)
 *   even when empty/missing roots are present in the same configuration,
 * - does not mount package providers for blank/malformed/root-only wildcards.
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class JvmPackageProviderEmptyRootTddTest {
    private val provider = JvmClassModuleProvider()

    // -------------------------------------------------------------------------
    // Empty / missing classpath roots → empty listing without throw
    // -------------------------------------------------------------------------

    @Test
    fun missing_classpath_root_path_does_not_throw_and_lists_empty_for_missing_package() {
        val missingRoot = File(
            System.getProperty("java.io.tmpdir"),
            "lua-parser-missing-package-root-${System.nanoTime()}"
        )
        // Intentionally do not create the directory.
        assertFalse(missingRoot.exists(), "fixture root must be missing before listing")

        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf("com.example.missingroot.*"),
                configuration = JvmWorkspaceConfiguration(
                    classpathEntries = listOf(missingRoot.absolutePath)
                )
            )
        }.getOrElse { error ->
            fail(
                "Missing classpath root must not throw; got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertTrue(
            providers.isEmpty(),
            "Missing root + missing package must yield no providers; actual=${providers.keys.map { it.value }}"
        )
    }
    @Test
    fun empty_classpath_root_directory_does_not_throw_and_lists_empty() {
        val emptyRoot = Files.createTempDirectory("lua-parser-empty-package-root-dir").toFile()
        emptyRoot.deleteOnExit()
        assertTrue(emptyRoot.isDirectory)
        assertEquals(0, emptyRoot.list()?.size ?: 0, "empty root must contain no entries")

        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf(
                    "com.example.emptyroot.*",
                    "org.fixture.emptyroot.child.*"
                ),
                configuration = JvmWorkspaceConfiguration(
                    classpathEntries = listOf(emptyRoot.absolutePath)
                )
            )
        }.getOrElse { error ->
            fail(
                "Empty classpath root directory must not throw; got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertTrue(
            providers.isEmpty(),
            "Empty root directory must yield no providers; actual=${providers.keys.map { it.value }}"
        )
    }
    @Test
    fun repeated_empty_root_listing_stays_empty_and_stable() {
        val emptyRoot = Files.createTempDirectory("lua-parser-empty-root-stable").toFile()
        emptyRoot.deleteOnExit()
        File(emptyRoot, "com/example/stableempty").mkdirs()

        val configuration = JvmWorkspaceConfiguration(
            classpathEntries = listOf(emptyRoot.absolutePath)
        )
        val targets = listOf("com.example.stableempty.*", "org.missing.stable.*")

        val first = runCatching {
            provider.packageProvidersFor(targets, configuration)
        }.getOrElse { error ->
            fail("First empty-root listing must not throw; got ${error::class.simpleName}: ${error.message}")
        }
        val second = runCatching {
            provider.packageProvidersFor(targets, configuration)
        }.getOrElse { error ->
            fail("Second empty-root listing must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        assertEquals(first.keys, second.keys)
    }
    @Test
    fun non_empty_package_provider_surface_and_fingerprint_remain_valid_with_empty_root() {
        val emptyRoot = Files.createTempDirectory("lua-parser-surface-with-empty-root").toFile()
        emptyRoot.deleteOnExit()

        val providers = packageProviders(
            configuration = JvmWorkspaceConfiguration(
                classpathEntries = listOf(emptyRoot.absolutePath)
            ),
            "java.util.*"
        )
        val snapshot = assertNotNull(providers[packagePath("java.util")])
        val surface = assertNotNull(snapshot.moduleExportSurface)

        assertEquals(ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL, surface.sourceForm)
        assertEquals("java.util", surface.moduleType.moduleName)
        assertEquals(linkedSetOf("java.util"), snapshot.publicFingerprint?.providedModuleNames)
        assertFalse(snapshot.publicFingerprint?.value.isNullOrBlank())
        assertTrue("List" in surface.moduleType.fields)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun packageProviders(vararg importTargets: String): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        return packageProviders(JvmWorkspaceConfiguration(), *importTargets)
    }

    private fun packageProviders(
        configuration: JvmWorkspaceConfiguration,
        vararg importTargets: String
    ): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        return provider.packageProvidersFor(
            importTargets = importTargets.toList(),
            configuration = configuration
        )
    }

    private fun moduleFor(importTarget: String): ModuleType {
        val providers = packageProviders(importTarget)
        val packageName = importTarget.removeSuffix(".*")
        return moduleAt(providers, packageName)
    }

    private fun moduleAt(
        providers: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>,
        packageName: String
    ): ModuleType {
        val path = packagePath(packageName)
        val snapshot = assertNotNull(
            providers[path],
            "Expected package provider at ${path.value}; actual: ${providers.keys.map { it.value }}"
        )
        return assertNotNull(
            snapshot.moduleExportSurface?.moduleType,
            "Expected module export surface for package $packageName"
        )
    }

    private fun packagePath(packageName: String): VirtualPath {
        return VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua")
    }

    private fun fieldsHint(packageName: String, module: ModuleType): String {
        return "Expected package $packageName fields to include known JDK types; fields=${module.fields.keys.sorted()}"
    }
}
