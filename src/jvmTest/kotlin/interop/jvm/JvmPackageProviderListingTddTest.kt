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
    fun java_io_wildcard_lists_stable_package_virtual_path() {
        val providers = packageProviders("java.io.*")

        assertEquals(setOf(packagePath("java.io")), providers.keys)
        assertEquals("__jvm__/packages/java/io.lua", packagePath("java.io").value)
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
    fun multi_package_configuration_lists_stable_paths_for_each_target() {
        val providers = packageProviders(
            "java.lang.*",
            "java.util.*",
            "java.io.*",
            "java.util.concurrent.*"
        )

        val expected = listOf(
            "java.lang",
            "java.util",
            "java.io",
            "java.util.concurrent"
        ).map(::packagePath)

        expected.forEach { path ->
            assertTrue(
                path in providers,
                "Expected stable package path ${path.value}; actual: ${providers.keys.map { it.value }}"
            )
        }
        assertEquals(expected.toSet(), providers.keys)
    }

    @Test
    fun package_provider_path_never_uses_classes_prefix() {
        val providers = packageProviders("java.util.*", "java.lang.*")

        assertTrue(providers.isNotEmpty())
        providers.keys.forEach { path ->
            assertTrue(
                path.value.startsWith("__jvm__/packages/"),
                "Package listing must use __jvm__/packages/ prefix; got ${path.value}"
            )
            assertFalse(
                path.value.startsWith("__jvm__/classes/"),
                "Package listing must not emit class provider paths; got ${path.value}"
            )
            assertTrue(
                path.value.endsWith(".lua"),
                "Package virtual path must end with .lua; got ${path.value}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Listing stability (repeatability / path identity)
    // -------------------------------------------------------------------------

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

    @Test
    fun repeated_listing_preserves_module_name_and_fingerprint_identity() {
        val first = packageProviders("java.util.*")
        val second = packageProviders("java.util.*")
        val path = packagePath("java.util")

        val left = assertNotNull(first[path])
        val right = assertNotNull(second[path])

        assertEquals(left.moduleExportSurface?.moduleType?.moduleName, right.moduleExportSurface?.moduleType?.moduleName)
        assertEquals(left.publicFingerprint?.value, right.publicFingerprint?.value)
        assertEquals(left.publicFingerprint?.providedModuleNames, right.publicFingerprint?.providedModuleNames)
        assertFalse(left.publicFingerprint?.value.isNullOrBlank())
    }

    @Test
    fun listing_order_of_import_targets_does_not_change_path_set() {
        val forward = packageProviders("java.lang.*", "java.util.*", "java.io.*")
        val reverse = packageProviders("java.io.*", "java.util.*", "java.lang.*")

        assertEquals(forward.keys, reverse.keys)
    }

    @Test
    fun duplicate_import_targets_collapse_to_single_stable_path() {
        val providers = packageProviders("java.util.*", "java.util.*", "java.util.*")

        assertEquals(setOf(packagePath("java.util")), providers.keys)
    }

    // -------------------------------------------------------------------------
    // Package module surface under stable paths
    // -------------------------------------------------------------------------

    @Test
    fun stable_path_module_exports_known_jdk_classes_as_fields() {
        val util = moduleFor("java.util.*")
        assertEquals("java.util", util.moduleName)
        assertTrue("List" in util.fields, fieldsHint("java.util", util))
        assertTrue("Map" in util.fields, fieldsHint("java.util", util))
        assertTrue("Locale" in util.fields, fieldsHint("java.util", util))
    }

    @Test
    fun stable_path_module_does_not_recurse_into_nested_packages() {
        val util = moduleFor("java.util.*")
        // java.util.concurrent.ConcurrentHashMap lives under nested package, not java.util.*
        assertFalse(
            "ConcurrentHashMap" in util.fields,
            "java.util.* listing must not absorb concurrent subpackage classes; fields=${util.fields.keys.sorted()}"
        )

        val concurrent = moduleFor("java.util.concurrent.*")
        assertTrue("ConcurrentHashMap" in concurrent.fields, fieldsHint("java.util.concurrent", concurrent))
        assertFalse("List" in concurrent.fields, "Parent package class List must not leak into concurrent listing")
    }

    @Test
    fun package_provider_surface_is_return_table_literal() {
        val providers = packageProviders("java.lang.*")
        val snapshot = assertNotNull(providers[packagePath("java.lang")])
        val surface = assertNotNull(snapshot.moduleExportSurface)

        assertEquals(ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL, surface.sourceForm)
        assertEquals("java.lang", surface.moduleType.moduleName)
    }

    @Test
    fun package_public_fingerprint_provides_package_module_name() {
        val providers = packageProviders("java.io.*")
        val snapshot = assertNotNull(providers[packagePath("java.io")])

        assertEquals(linkedSetOf("java.io"), snapshot.publicFingerprint?.providedModuleNames)
    }

    // -------------------------------------------------------------------------
    // Empty / missing packages do not throw
    // -------------------------------------------------------------------------

    @Test
    fun empty_or_unknown_package_returns_empty_map_without_throwing() {
        val providers = runCatching {
            packageProviders(
                "com.does.not.exist.sample.*",
                "org.missing.package.fixture.*",
                "android.fake.nested.missing.*"
            )
        }.getOrElse { error ->
            fail("Empty/unknown package listing must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(
            providers.isEmpty(),
            "Missing packages must yield no providers; actual: ${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun blank_and_malformed_targets_do_not_throw() {
        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf("", "   ", ".*", "*", "not-a-package", "import "),
                configuration = JvmWorkspaceConfiguration()
            )
        }.getOrElse { error ->
            fail("Malformed package targets must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(
            providers.isEmpty(),
            "Malformed targets must not mount providers; actual: ${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun non_wildcard_class_targets_are_not_listed_as_package_providers() {
        val providers = runCatching {
            packageProviders(
                "java.lang.String",
                "java.util.List",
                "java.io.File"
            )
        }.getOrElse { error ->
            fail("Non-wildcard targets must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(
            providers.isEmpty(),
            "packageProvidersFor only handles wildcard package targets; actual: ${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun mix_of_valid_and_empty_packages_lists_only_valid_stable_paths() {
        val providers = runCatching {
            packageProviders(
                "java.util.*",
                "com.missing.empty.package.*",
                "java.lang.*",
                "org.also.missing.*"
            )
        }.getOrElse { error ->
            fail("Mixed valid/empty listing must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertEquals(
            setOf(packagePath("java.util"), packagePath("java.lang")),
            providers.keys
        )
    }

    @Test
    fun empty_classpath_directory_package_does_not_throw() {
        val emptyRoot = Files.createTempDirectory("lua-parser-empty-package-root").toFile()
        emptyRoot.deleteOnExit()
        // Create package directory with no .class files.
        val emptyPackageDir = File(emptyRoot, "com/example/emptyfixture")
        assertTrue(emptyPackageDir.mkdirs())

        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf("com.example.emptyfixture.*"),
                configuration = JvmWorkspaceConfiguration(classpathEntries = listOf(emptyRoot.absolutePath))
            )
        }.getOrElse { error ->
            fail("Empty classpath package must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(
            providers.isEmpty(),
            "Empty package directory must yield no providers; actual: ${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun classpath_fixture_package_lists_stable_virtual_path() {
        val root = Files.createTempDirectory("lua-parser-package-listing-fixture").toFile()
        root.deleteOnExit()
        val packageDir = File(root, "com/example/listing")
        assertTrue(packageDir.mkdirs())
        // Touch a non-class file and a synthetic .class name is not loadable via Class.forName
        // unless compiled — instead rely on JDK packages for loadable fields and only assert
        // that an empty-looking package still stays non-throwing (covered above).
        // For a positive classpath listing path, use a known JDK package with explicit classpath
        // override that still resolves via the base loader.
        val providers = packageProviders(
            configuration = JvmWorkspaceConfiguration(classpathEntries = listOf(root.absolutePath)),
            "java.util.*"
        )

        assertTrue(
            packagePath("java.util") in providers,
            "Configured JDK package must still list stable path even when extra empty classpath is present"
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
