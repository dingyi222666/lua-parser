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
    fun empty_package_tree_under_root_does_not_throw_and_lists_empty() {
        val root = Files.createTempDirectory("lua-parser-empty-package-tree-root").toFile()
        root.deleteOnExit()
        // Package directories exist under the root but contain no .class files.
        val packageDir = File(root, "com/example/emptytree")
        assertTrue(packageDir.mkdirs())
        File(root, "com/example/emptytree/nested").mkdirs()

        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf(
                    "com.example.emptytree.*",
                    "com.example.emptytree.nested.*"
                ),
                configuration = JvmWorkspaceConfiguration(
                    classpathEntries = listOf(root.absolutePath)
                )
            )
        }.getOrElse { error ->
            fail(
                "Empty package tree under root must not throw; got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertTrue(
            providers.isEmpty(),
            "Empty package tree must yield no providers; actual=${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun multiple_empty_and_missing_roots_do_not_throw() {
        val emptyRoot = Files.createTempDirectory("lua-parser-multi-empty-root-a").toFile()
        emptyRoot.deleteOnExit()
        File(emptyRoot, "com/example/a").mkdirs()

        val anotherEmpty = Files.createTempDirectory("lua-parser-multi-empty-root-b").toFile()
        anotherEmpty.deleteOnExit()

        val missingRoot = File(
            System.getProperty("java.io.tmpdir"),
            "lua-parser-multi-missing-root-${System.nanoTime()}"
        )
        assertFalse(missingRoot.exists())

        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf(
                    "com.example.a.*",
                    "com.example.b.*",
                    "org.missing.multi.*"
                ),
                configuration = JvmWorkspaceConfiguration(
                    classpathEntries = listOf(
                        emptyRoot.absolutePath,
                        anotherEmpty.absolutePath,
                        missingRoot.absolutePath
                    )
                )
            )
        }.getOrElse { error ->
            fail(
                "Multiple empty/missing roots must not throw; got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertTrue(
            providers.isEmpty(),
            "Only empty/missing roots must yield no providers; actual=${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun blank_and_whitespace_classpath_roots_are_ignored_without_throw() {
        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf("com.example.blankroot.*"),
                configuration = JvmWorkspaceConfiguration(
                    classpathEntries = listOf("", "   ", "\t")
                )
            )
        }.getOrElse { error ->
            fail(
                "Blank classpath roots must not throw; got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertTrue(
            providers.isEmpty(),
            "Blank roots + missing package must yield no providers; actual=${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun default_empty_configuration_missing_package_is_empty_without_throw() {
        val providers = runCatching {
            packageProviders(
                "com.does.not.exist.emptyroot.*",
                "org.missing.empty.root.fixture.*"
            )
        }.getOrElse { error ->
            fail(
                "Default empty configuration missing packages must not throw; " +
                    "got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertTrue(
            providers.isEmpty(),
            "Missing packages under default roots must yield no providers; actual=${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun empty_import_targets_with_empty_root_return_empty_map_without_throw() {
        val emptyRoot = Files.createTempDirectory("lua-parser-empty-targets-root").toFile()
        emptyRoot.deleteOnExit()

        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = emptyList(),
                configuration = JvmWorkspaceConfiguration(
                    classpathEntries = listOf(emptyRoot.absolutePath)
                )
            )
        }.getOrElse { error ->
            fail(
                "Empty import targets + empty root must not throw; got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertTrue(providers.isEmpty())
    }

    @Test
    fun root_only_and_blank_wildcards_do_not_mount_providers_or_throw() {
        val emptyRoot = Files.createTempDirectory("lua-parser-root-only-wildcard").toFile()
        emptyRoot.deleteOnExit()

        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf(
                    "",
                    "   ",
                    ".*",
                    "*",
                    "import ",
                    "import .*"
                ),
                configuration = JvmWorkspaceConfiguration(
                    classpathEntries = listOf(emptyRoot.absolutePath)
                )
            )
        }.getOrElse { error ->
            fail(
                "Root-only/blank wildcards must not throw; got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertTrue(
            providers.isEmpty(),
            "Root-only/blank wildcards must not mount providers; actual=${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun non_wildcard_targets_with_empty_root_do_not_throw_or_mount() {
        val emptyRoot = Files.createTempDirectory("lua-parser-non-wildcard-empty-root").toFile()
        emptyRoot.deleteOnExit()

        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf(
                    "java.lang.String",
                    "java.util.List",
                    "com.example.EmptyRootType"
                ),
                configuration = JvmWorkspaceConfiguration(
                    classpathEntries = listOf(emptyRoot.absolutePath)
                )
            )
        }.getOrElse { error ->
            fail(
                "Non-wildcard targets with empty root must not throw; got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertTrue(
            providers.isEmpty(),
            "packageProvidersFor only handles wildcard package targets; actual=${providers.keys.map { it.value }}"
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

    // -------------------------------------------------------------------------
    // Non-empty roots / packages still list packages and classes
    // -------------------------------------------------------------------------

    @Test
    fun non_empty_jdk_package_still_lists_classes_under_default_roots() {
        val module = moduleFor("java.util.*")
        assertEquals("java.util", module.moduleName)
        assertTrue("List" in module.fields, fieldsHint("java.util", module))
        assertTrue("Map" in module.fields, fieldsHint("java.util", module))
        assertTrue("Locale" in module.fields, fieldsHint("java.util", module))
    }

    @Test
    fun non_empty_nested_jdk_package_still_lists_classes() {
        val module = moduleFor("java.util.concurrent.*")
        assertEquals("java.util.concurrent", module.moduleName)
        assertTrue("ConcurrentHashMap" in module.fields, fieldsHint("java.util.concurrent", module))
        assertTrue("ExecutorService" in module.fields, fieldsHint("java.util.concurrent", module))
        assertFalse(
            "List" in module.fields,
            "Parent package class List must not leak into concurrent listing"
        )
    }

    @Test
    fun non_empty_packages_still_list_when_empty_and_missing_roots_are_present() {
        val emptyRoot = Files.createTempDirectory("lua-parser-mixed-empty-root").toFile()
        emptyRoot.deleteOnExit()
        File(emptyRoot, "com/example/emptyonly").mkdirs()

        val missingRoot = File(
            System.getProperty("java.io.tmpdir"),
            "lua-parser-mixed-missing-root-${System.nanoTime()}"
        )
        assertFalse(missingRoot.exists())

        val providers = runCatching {
            provider.packageProvidersFor(
                importTargets = listOf(
                    "java.util.*",
                    "java.lang.*",
                    "com.example.emptyonly.*",
                    "org.missing.mixed.*"
                ),
                configuration = JvmWorkspaceConfiguration(
                    classpathEntries = listOf(
                        emptyRoot.absolutePath,
                        missingRoot.absolutePath,
                        "",
                        "   "
                    )
                )
            )
        }.getOrElse { error ->
            fail(
                "Mixed empty/missing roots with non-empty JDK packages must not throw; " +
                    "got ${error::class.simpleName}: ${error.message}"
            )
        }

        assertEquals(
            setOf(packagePath("java.util"), packagePath("java.lang")),
            providers.keys,
            "Only non-empty packages must mount; actual=${providers.keys.map { it.value }.sorted()}"
        )

        val util = moduleAt(providers, "java.util")
        val lang = moduleAt(providers, "java.lang")
        assertTrue("List" in util.fields, fieldsHint("java.util", util))
        assertTrue("Map" in util.fields, fieldsHint("java.util", util))
        assertTrue("String" in lang.fields, fieldsHint("java.lang", lang))
        assertTrue("Object" in lang.fields, fieldsHint("java.lang", lang))
        assertFalse(packagePath("com.example.emptyonly") in providers)
        assertFalse(packagePath("org.missing.mixed") in providers)
    }

    @Test
    fun multiple_non_empty_packages_list_stable_paths_and_classes_despite_empty_root() {
        val emptyRoot = Files.createTempDirectory("lua-parser-multi-nonempty-with-empty-root").toFile()
        emptyRoot.deleteOnExit()

        val providers = packageProviders(
            configuration = JvmWorkspaceConfiguration(
                classpathEntries = listOf(emptyRoot.absolutePath)
            ),
            "java.io.*",
            "java.util.*",
            "java.util.concurrent.*",
            "java.lang.reflect.*"
        )

        val expected = setOf(
            packagePath("java.io"),
            packagePath("java.util"),
            packagePath("java.util.concurrent"),
            packagePath("java.lang.reflect")
        )
        assertEquals(expected, providers.keys)

        assertTrue("File" in moduleAt(providers, "java.io").fields)
        assertTrue("List" in moduleAt(providers, "java.util").fields)
        assertTrue("ConcurrentHashMap" in moduleAt(providers, "java.util.concurrent").fields)
        assertTrue("Method" in moduleAt(providers, "java.lang.reflect").fields)

        // Cross-package isolation still holds with empty roots present.
        assertFalse("ConcurrentHashMap" in moduleAt(providers, "java.util").fields)
        assertFalse("List" in moduleAt(providers, "java.util.concurrent").fields)
        assertFalse("String" in moduleAt(providers, "java.lang.reflect").fields)
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

    @Test
    fun package_paths_never_use_classes_prefix_for_non_empty_roots() {
        val emptyRoot = Files.createTempDirectory("lua-parser-path-prefix-empty-root").toFile()
        emptyRoot.deleteOnExit()

        val providers = packageProviders(
            configuration = JvmWorkspaceConfiguration(
                classpathEntries = listOf(emptyRoot.absolutePath)
            ),
            "java.util.*",
            "java.lang.*"
        )

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
            assertTrue(path.value.endsWith(".lua"), "Package path must end with .lua; got ${path.value}")
        }
    }

    @Test
    fun repeated_non_empty_listing_with_empty_root_is_path_and_fingerprint_stable() {
        val emptyRoot = Files.createTempDirectory("lua-parser-nonempty-stable-empty-root").toFile()
        emptyRoot.deleteOnExit()
        val configuration = JvmWorkspaceConfiguration(
            classpathEntries = listOf(emptyRoot.absolutePath)
        )
        val targets = listOf("java.util.*", "java.io.*")

        val first = provider.packageProvidersFor(targets, configuration)
        val second = provider.packageProvidersFor(targets, configuration)

        assertEquals(first.keys, second.keys)
        first.keys.forEach { path ->
            val left = assertNotNull(first[path])
            val right = assertNotNull(second[path])
            assertEquals(
                left.moduleExportSurface?.moduleType?.moduleName,
                right.moduleExportSurface?.moduleType?.moduleName
            )
            assertEquals(left.publicFingerprint?.value, right.publicFingerprint?.value)
            assertEquals(
                left.publicFingerprint?.providedModuleNames,
                right.publicFingerprint?.providedModuleNames
            )
        }
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
