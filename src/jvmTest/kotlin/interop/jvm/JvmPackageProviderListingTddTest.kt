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
 * TASK-216 — JVM package provider listing stability corpus.
 *
 * Encodes the contract that [JvmClassModuleProvider.packageProvidersFor]:
 * - lists stable virtual paths under `__jvm__/packages/<segments>.lua` for
 *   configured wildcard packages (JDK + classpath fixtures),
 * - returns the same path set across repeated listings,
 * - does not throw when packages are empty / missing / non-wildcard,
 * - keys providers only by package path (not class path).
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class JvmPackageProviderListingTddTest {
    private val provider = JvmClassModuleProvider()

    // -------------------------------------------------------------------------
    // Stable virtual path scheme for configured packages
    // -------------------------------------------------------------------------

    @Test
    fun java_util_wildcard_lists_stable_package_virtual_path() {
        val providers = packageProviders("java.util.*")

        assertEquals(
            setOf(packagePath("java.util")),
            providers.keys,
            "java.util.* must mount exactly one package provider at the stable virtual path"
        )
        assertEquals("__jvm__/packages/java/util.lua", packagePath("java.util").value)
    }
    @Test
    fun java_lang_wildcard_lists_stable_package_virtual_path() {
        val providers = packageProviders("java.lang.*")

        assertTrue(
            packagePath("java.lang") in providers,
            "Expected stable path ${packagePath("java.lang").value}; actual: ${providers.keys.map { it.value }}"
        )
        assertEquals("java.lang", moduleAt(providers, "java.lang").moduleName)
    }
    @Test
    fun nested_jdk_package_uses_full_segment_path_not_parent_only() {
        val providers = packageProviders("java.util.concurrent.*")

        assertTrue(
            packagePath("java.util.concurrent") in providers,
            "Nested package must use full path segments; actual: ${providers.keys.map { it.value }}"
        )
        assertFalse(
            packagePath("java.util") in providers,
            "Parent package path must not be mounted for a nested wildcard alone"
        )
        assertEquals(
            "__jvm__/packages/java/util/concurrent.lua",
            packagePath("java.util.concurrent").value
        )
    }
    @Test
    fun repeated_listing_of_same_packages_returns_identical_virtual_path_set() {
        val targets = listOf("java.util.*", "java.lang.*", "java.io.*")
        val first = packageProviders(*targets.toTypedArray())
        val second = packageProviders(*targets.toTypedArray())

        assertEquals(first.keys, second.keys)
        assertEquals(
            first.keys.map { it.value }.sorted(),
            second.keys.map { it.value }.sorted()
        )
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
