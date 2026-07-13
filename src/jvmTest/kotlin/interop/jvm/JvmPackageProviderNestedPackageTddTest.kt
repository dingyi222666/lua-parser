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
 * TASK-301 — JVM package provider nested package corpus.
 *
 * Encodes the contract that [JvmClassModuleProvider.packageProvidersFor]:
 * - mounts nested package providers at full-segment stable virtual paths
 *   (`__jvm__/packages/<a>/<b>/<c>.lua`),
 * - lists direct classes of each nested package without absorbing parent /
 *   sibling / deeper package members,
 * - accepts multi-depth nested package targets together (subpackages + classes),
 * - returns empty maps without throwing for missing nested packages, empty
 *   classpath package trees, and non-wildcard nested class targets.
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class JvmPackageProviderNestedPackageTddTest {
    private val provider = JvmClassModuleProvider()

    // -------------------------------------------------------------------------
    // Nested package virtual paths + class listings
    // -------------------------------------------------------------------------

    @Test
    fun depth2_java_util_concurrent_lists_nested_classes_at_full_segment_path() {
        val providers = packageProviders("java.util.concurrent.*")
        val path = packagePath("java.util.concurrent")

        assertEquals(setOf(path), providers.keys)
        assertEquals("__jvm__/packages/java/util/concurrent.lua", path.value)

        val module = moduleAt(providers, "java.util.concurrent")
        assertEquals("java.util.concurrent", module.moduleName)
        assertTrue("ConcurrentHashMap" in module.fields, fieldsHint("java.util.concurrent", module))
        assertTrue("ConcurrentLinkedQueue" in module.fields, fieldsHint("java.util.concurrent", module))
        assertTrue("ExecutorService" in module.fields, fieldsHint("java.util.concurrent", module))
        // Parent package class must not leak into nested listing.
        assertFalse("List" in module.fields, "java.util.List must not appear under concurrent")
        // Deeper package class must not be absorbed by parent nested package.
        assertFalse(
            "AtomicInteger" in module.fields,
            "java.util.concurrent.* must not absorb concurrent.atomic classes; fields=${module.fields.keys.sorted()}"
        )
    }
    @Test
    fun multi_depth_nested_targets_list_each_subpackage_provider_and_its_classes() {
        val providers = packageProviders(
            "java.util.*",
            "java.util.concurrent.*",
            "java.util.concurrent.atomic.*",
            "java.util.function.*",
            "java.nio.file.*",
            "java.lang.reflect.*"
        )

        val expectedPackages = listOf(
            "java.util",
            "java.util.concurrent",
            "java.util.concurrent.atomic",
            "java.util.function",
            "java.nio.file",
            "java.lang.reflect"
        )
        val expectedPaths = expectedPackages.map(::packagePath).toSet()
        assertEquals(
            expectedPaths,
            providers.keys,
            "Nested package listing must mount each configured subpackage path; " +
                "actual=${providers.keys.map { it.value }.sorted()}"
        )

        val util = moduleAt(providers, "java.util")
        val concurrent = moduleAt(providers, "java.util.concurrent")
        val atomic = moduleAt(providers, "java.util.concurrent.atomic")
        val function = moduleAt(providers, "java.util.function")
        val nioFile = moduleAt(providers, "java.nio.file")
        val reflect = moduleAt(providers, "java.lang.reflect")

        // Parent package classes.
        assertTrue("List" in util.fields, fieldsHint("java.util", util))
        assertTrue("Map" in util.fields, fieldsHint("java.util", util))
        // Nested / subpackage classes stay on their own modules.
        assertTrue("ConcurrentHashMap" in concurrent.fields, fieldsHint("java.util.concurrent", concurrent))
        assertTrue("AtomicInteger" in atomic.fields, fieldsHint("java.util.concurrent.atomic", atomic))
        assertTrue("Function" in function.fields, fieldsHint("java.util.function", function))
        assertTrue("Path" in nioFile.fields, fieldsHint("java.nio.file", nioFile))
        assertTrue("Method" in reflect.fields, fieldsHint("java.lang.reflect", reflect))

        // Cross-depth isolation for nested packages + classes.
        assertFalse("ConcurrentHashMap" in util.fields)
        assertFalse("AtomicInteger" in util.fields)
        assertFalse("AtomicInteger" in concurrent.fields)
        assertFalse("List" in concurrent.fields)
        assertFalse("List" in atomic.fields)
        assertFalse("ConcurrentHashMap" in atomic.fields)
        assertFalse("List" in function.fields)
        assertFalse("String" in reflect.fields)
        assertFalse("Buffer" in nioFile.fields)
    }
    @Test
    fun parent_package_alone_does_not_mount_nested_subpackage_providers() {
        val providers = packageProviders("java.util.*")

        assertEquals(setOf(packagePath("java.util")), providers.keys)
        assertFalse(
            packagePath("java.util.concurrent") in providers,
            "Parent wildcard must not auto-mount nested subpackage providers"
        )
        assertFalse(packagePath("java.util.concurrent.atomic") in providers)
        assertFalse(packagePath("java.util.function") in providers)

        val util = moduleAt(providers, "java.util")
        assertTrue("List" in util.fields)
        assertFalse("ConcurrentHashMap" in util.fields)
        assertFalse("AtomicInteger" in util.fields)
        assertFalse("Function" in util.fields)
    }
    @Test
    fun nested_package_provider_surface_is_return_table_literal_with_package_module_name() {
        val providers = packageProviders("java.util.concurrent.*")
        val snapshot = assertNotNull(providers[packagePath("java.util.concurrent")])
        val surface = assertNotNull(snapshot.moduleExportSurface)

        assertEquals(ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL, surface.sourceForm)
        assertEquals("java.util.concurrent", surface.moduleType.moduleName)
        assertEquals(
            linkedSetOf("java.util.concurrent"),
            snapshot.publicFingerprint?.providedModuleNames
        )
        assertFalse(snapshot.publicFingerprint?.value.isNullOrBlank())
    }
    @Test
    fun missing_nested_packages_return_empty_map_without_throwing() {
        val providers = runCatching {
            packageProviders(
                "com.does.not.exist.nested.sample.*",
                "org.missing.nested.package.fixture.*",
                "java.util.concurrent.this.is.not.a.real.package.*"
            )
        }.getOrElse { error ->
            fail("Missing nested package listing must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(
            providers.isEmpty(),
            "Missing nested packages must yield no providers; actual: ${providers.keys.map { it.value }}"
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
            "Expected nested package provider at ${path.value}; actual: ${providers.keys.map { it.value }}"
        )
        return assertNotNull(
            snapshot.moduleExportSurface?.moduleType,
            "Expected module export surface for nested package $packageName"
        )
    }

    private fun packagePath(packageName: String): VirtualPath {
        return VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua")
    }

    private fun fieldsHint(packageName: String, module: ModuleType): String {
        return "Expected nested package $packageName fields to include known JDK types; fields=${module.fields.keys.sorted()}"
    }
}
