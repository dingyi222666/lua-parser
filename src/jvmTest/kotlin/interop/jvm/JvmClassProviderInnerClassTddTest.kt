package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-352 — JVM class provider inner class corpus.
 * TASK-526 — nested/interface static reflection for binary names (Map$Entry).
 *
 * Encodes the contract that [JvmClassModuleProvider]:
 * - resolves public nested / inner classes via dotted or binary (`$`) names,
 * - mounts them at stable `__jvm__/classes/...` virtual paths that preserve the
 *   binary `$` separator,
 * - lists public nested classes on the enclosing class provider surface
 *   (`fields` + `JavaClassType.innerClasses`),
 * - reflects nested/interface static members (`comparingByKey`) for binary-name
 *   mounts so semantic surfaces have real members (never invented),
 * - returns empty maps without throwing for missing outer/inner class names.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class JvmClassProviderInnerClassTddTest {
    private val provider = JvmClassModuleProvider()

    // -------------------------------------------------------------------------
    // Resolve / list public nested classes via provider
    // -------------------------------------------------------------------------

    @Test
    fun map_entry_binary_name_resolves_to_stable_inner_class_path() {
        val file = providerFile("java.util.Map\$Entry")

        assertEquals(jvmClassPath("java.util.Map\$Entry"), file.path)
        assertEquals("__jvm__/classes/java/util/Map\$Entry.lua", file.path.value)
        assertEquals("Entry", file.module.moduleName)
    }

    @Test
    fun map_entry_nested_interface_static_helpers_are_reflected() {
        // TASK-526: bindClass("java.util.Map$Entry") must expose interface static helpers
        // that JDK reflection can see (comparingByKey / comparingByValue / copyOf).
        val module = module("java.util.Map\$Entry")
        val classType = classType("java.util.Map\$Entry").classType

        assertTrue(
            "comparingByKey" in module.methods,
            "Map\$Entry module methods must include comparingByKey; methods=${module.methods.keys.sorted()}"
        )
        assertTrue(
            "comparingByValue" in module.methods,
            "Map\$Entry module methods must include comparingByValue; methods=${module.methods.keys.sorted()}"
        )
        assertTrue(
            "comparingByKey" in classType.staticMembers,
            "Map\$Entry staticMembers must include comparingByKey; static=${classType.staticMembers.keys.sorted()}"
        )
        assertTrue(
            "comparingByValue" in classType.staticMembers,
            "Map\$Entry staticMembers must include comparingByValue; static=${classType.staticMembers.keys.sorted()}"
        )
        assertEquals(
            "java.util.Map\$Entry",
            classType.staticMembers.getValue("comparingByKey").owner.binaryName
        )
        // Export surface under __class must also list static helpers for workspace export lookup.
        val surfaceNames = providerFile("java.util.Map\$Entry").surface.members
            .filter { it.exportPath.firstOrNull() == "__class" || it.exportPath.size == 1 }
            .map { it.name }
            .toSet()
        assertTrue(
            "comparingByKey" in surfaceNames,
            "Provider export surface must list comparingByKey; members=${surfaceNames.sorted()}"
        )
        assertTrue(
            "comparingByValue" in surfaceNames,
            "Provider export surface must list comparingByValue; members=${surfaceNames.sorted()}"
        )
    }

    @Test
    fun map_entry_dotted_and_binary_share_static_surface_fingerprint() {
        val dotted = providerFile("java.util.Map.Entry")
        val binary = providerFile("java.util.Map\$Entry")

        assertEquals(binary.path, dotted.path)
        assertEquals(
            binary.snapshot.publicFingerprint?.value,
            dotted.snapshot.publicFingerprint?.value
        )
        assertTrue("comparingByKey" in dotted.module.methods)
        assertTrue("comparingByKey" in binary.module.methods)
    }

    @Test
    fun map_entry_dotted_name_resolves_same_inner_class_provider() {
        val dotted = providerFile("java.util.Map.Entry")
        val binary = providerFile("java.util.Map\$Entry")

        assertEquals(binary.path, dotted.path)
        assertEquals("Entry", dotted.module.moduleName)
        assertEquals(
            binary.snapshot.publicFingerprint?.value,
            dotted.snapshot.publicFingerprint?.value
        )
    }

    @Test
    fun thread_state_inner_enum_resolves_and_lists_via_provider() {
        val file = providerFile("java.lang.Thread.State")

        assertEquals(jvmClassPath("java.lang.Thread\$State"), file.path)
        assertEquals("State", file.module.moduleName)
        assertEquals(linkedSetOf("State"), file.snapshot.publicFingerprint?.providedModuleNames)
        assertIs<JavaInstanceType>(file.module.fields["__class"])
    }

    @Test
    fun abstract_map_simple_entry_nested_class_resolves() {
        val file = providerFile("java.util.AbstractMap.SimpleEntry")

        assertEquals(jvmClassPath("java.util.AbstractMap\$SimpleEntry"), file.path)
        assertEquals("SimpleEntry", file.module.moduleName)
        assertEquals(
            "java.util.AbstractMap\$SimpleEntry",
            assertIs<JavaInstanceType>(file.module.fields["__class"]).classType.javaName.binaryName
        )
    }

    @Test
    fun character_unicode_block_nested_class_resolves() {
        val file = providerFile("java.lang.Character.UnicodeBlock")

        assertEquals(jvmClassPath("java.lang.Character\$UnicodeBlock"), file.path)
        assertEquals("UnicodeBlock", file.module.moduleName)
    }

    @Test
    fun multi_inner_class_metadata_lists_each_stable_path() {
        val files = provider.providersFor(
            mapOf(
                JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                    "java.util.Map.Entry",
                    "java.lang.Thread.State",
                    "java.util.AbstractMap.SimpleEntry"
                ).joinToString(";")
            )
        )

        assertTrue(jvmClassPath("java.util.Map\$Entry") in files)
        assertTrue(jvmClassPath("java.lang.Thread\$State") in files)
        assertTrue(jvmClassPath("java.util.AbstractMap\$SimpleEntry") in files)
        assertEquals(3, files.size)
    }

    // -------------------------------------------------------------------------
    // Parent class surface lists nested classes
    // -------------------------------------------------------------------------

    @Test
    fun map_provider_lists_entry_nested_class_as_field() {
        val module = module("java.util.Map")

        assertTrue(
            "Entry" in module.fields,
            "Map provider must list public nested class Entry; fields=${module.fields.keys.sorted()}"
        )
        val entryField = module.fields.required("Entry")
        // Nested class field may surface as ModuleType (depth-bounded) or JavaClassType reference.
        when (entryField) {
            is ModuleType -> {
                assertEquals("Entry", entryField.moduleName)
                assertIs<JavaInstanceType>(entryField.fields["__class"])
            }
            is JavaClassType -> {
                assertEquals("java.util.Map\$Entry", entryField.javaName.binaryName)
                assertEquals("Entry", entryField.javaName.simpleNames.last())
            }
            is JavaInstanceType -> {
                assertEquals("java.util.Map\$Entry", entryField.classType.javaName.binaryName)
            }
            else -> fail("Unexpected Entry field type: ${entryField::class.simpleName}")
        }
    }

    @Test
    fun map_java_class_type_inner_classes_map_includes_entry() {
        val classType = classType("java.util.Map").classType

        assertTrue(
            "Entry" in classType.innerClasses,
            "JavaClassType.innerClasses must include Entry; actual=${classType.innerClasses.keys.sorted()}"
        )
        assertEquals(
            "java.util.Map\$Entry",
            classType.innerClasses.getValue("Entry").javaName.binaryName
        )
        assertEquals(
            listOf("Map", "Entry"),
            classType.innerClasses.getValue("Entry").javaName.simpleNames
        )
    }

    @Test
    fun thread_provider_lists_state_nested_enum() {
        val module = module("java.lang.Thread")

        assertTrue(
            "State" in module.fields,
            "Thread provider must list public nested enum State; fields=${module.fields.keys.sorted()}"
        )
        val classType = classType("java.lang.Thread").classType
        assertTrue("State" in classType.innerClasses)
        assertEquals("java.lang.Thread\$State", classType.innerClasses.getValue("State").javaName.binaryName)
    }

    @Test
    fun abstract_map_lists_simple_entry_and_simple_immutable_entry() {
        val module = module("java.util.AbstractMap")
        val classType = classType("java.util.AbstractMap").classType

        assertTrue("SimpleEntry" in module.fields, fieldsHint("java.util.AbstractMap", module))
        assertTrue("SimpleImmutableEntry" in module.fields, fieldsHint("java.util.AbstractMap", module))
        assertTrue("SimpleEntry" in classType.innerClasses)
        assertTrue("SimpleImmutableEntry" in classType.innerClasses)
    }

    @Test
    fun nested_class_path_preserves_dollar_not_only_dots() {
        val file = providerFile("java.util.Map.Entry")

        assertTrue(
            file.path.value.contains("Map\$Entry"),
            "Inner class virtual path must preserve binary \$ separator; got ${file.path.value}"
        )
        assertFalse(
            file.path.value.endsWith("Map/Entry.lua"),
            "Inner class must not collapse to package-like path segments alone; got ${file.path.value}"
        )
        assertTrue(file.path.value.startsWith("__jvm__/classes/"))
        assertTrue(file.path.value.endsWith(".lua"))
    }

    @Test
    fun nested_class_provider_surface_is_return_table_literal() {
        val file = providerFile("java.lang.Thread.State")

        assertEquals(ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL, file.surface.sourceForm)
        assertFalse(file.snapshot.cacheKey.isNullOrBlank())
        assertFalse(file.snapshot.publicFingerprint?.value.isNullOrBlank())
    }

    @Test
    fun nested_class_module_exposes_class_field_with_binary_name() {
        val classField = assertIs<JavaInstanceType>(module("java.util.Map.Entry").fields["__class"])

        assertEquals("java.util.Map\$Entry", classField.classType.javaName.binaryName)
        assertEquals("java.util.Map.Entry", classField.classType.javaName.canonicalName)
    }

    @Test
    fun repeated_inner_class_resolution_is_path_and_fingerprint_stable() {
        val first = providerFile("java.util.Map.Entry")
        val second = providerFile("java.util.Map\$Entry")

        assertEquals(first.path, second.path)
        assertEquals(first.snapshot.publicFingerprint?.value, second.snapshot.publicFingerprint?.value)
        assertEquals(first.module.moduleName, second.module.moduleName)
    }

    @Test
    fun parent_and_inner_class_providers_are_distinct_paths() {
        val parent = providerFile("java.util.Map")
        val inner = providerFile("java.util.Map.Entry")

        assertNotEqualsPath(parent.path, inner.path)
        assertEquals("__jvm__/classes/java/util/Map.lua", parent.path.value)
        assertEquals("__jvm__/classes/java/util/Map\$Entry.lua", inner.path.value)
    }

    // -------------------------------------------------------------------------
    // Missing inner class empty without throw
    // -------------------------------------------------------------------------

    @Test
    fun missing_inner_class_returns_empty_map_without_throwing() {
        val files = runCatching {
            provider.providersFor(
                mapOf(
                    JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                        "java.util.Map.DoesNotExistInner",
                        "java.lang.Thread.NoSuchState",
                        "com.missing.Outer.Inner"
                    ).joinToString(";")
                )
            )
        }.getOrElse { error ->
            fail("Missing inner class resolution must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(
            files.isEmpty(),
            "Missing inner classes must not mount providers; actual: ${files.keys.map { it.value }}"
        )
    }

    @Test
    fun missing_outer_and_inner_mix_lists_only_valid_without_throw() {
        val files = runCatching {
            provider.providersFor(
                mapOf(
                    JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                        "java.util.Map.Entry",
                        "java.util.Map.MissingNested",
                        "java.lang.Thread.State",
                        "org.no.such.Outer.Inner"
                    ).joinToString(",")
                )
            )
        }.getOrElse { error ->
            fail("Mixed valid/missing inner listing must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertEquals(
            setOf(
                jvmClassPath("java.util.Map\$Entry"),
                jvmClassPath("java.lang.Thread\$State")
            ),
            files.keys
        )
    }

    @Test
    fun blank_malformed_and_fake_inner_targets_do_not_throw_or_mount() {
        val files = runCatching {
            provider.providersFor(
                JvmWorkspaceConfiguration(
                    classes = linkedSetOf(
                        "",
                        "   ",
                        "java.util.Map.",
                        ".Inner",
                        "Map\$",
                        "not-an-inner",
                        "java.util.Map.NoSuch\$Inner"
                    )
                )
            )
        }.getOrElse { error ->
            fail("Malformed inner targets must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(
            files.isEmpty(),
            "Malformed / missing inner targets must not mount providers; actual: ${files.keys.map { it.value }}"
        )
    }

    @Test
    fun androlua_import_of_missing_inner_class_is_empty_without_throw() {
        val files = runCatching {
            provider.providersFor(
                mapOf(JvmClassModuleProvider.IMPORTS_METADATA_KEY to "Map.DoesNotExistInner")
            )
        }.getOrElse { error ->
            fail("Missing androlua inner import must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(files.isEmpty())
    }

    @Test
    fun androlua_import_of_known_nested_simple_name_is_not_required_but_must_not_throw() {
        // Simple-name imports only search configured prefixes; nested simple names are not
        // auto-resolved. Contract: never throw, may be empty.
        val files = runCatching {
            provider.providersFor(
                mapOf(JvmClassModuleProvider.IMPORTS_METADATA_KEY to "Entry")
            )
        }.getOrElse { error ->
            fail("Simple-name nested import probe must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        // Either empty (no prefix yields Map$Entry) or a coincidental match — never crash.
        files.keys.forEach { path ->
            assertTrue(path.value.startsWith("__jvm__/classes/"), "Unexpected path ${path.value}")
        }
    }

    @Test
    fun configuration_classes_list_missing_inner_does_not_throw() {
        val files = runCatching {
            provider.providersFor(
                JvmWorkspaceConfiguration(
                    classes = linkedSetOf(
                        "java.util.concurrent.ConcurrentHashMap.MissingNode",
                        "java.lang.Class.NoSuchInner"
                    )
                )
            )
        }.getOrElse { error ->
            fail("Configuration missing inner classes must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(files.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun providerFile(className: String): ProviderFile {
        val files = provider.providersFor(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to className)
        )
        assertTrue(
            files.isNotEmpty(),
            "Expected provider for $className; actual paths=${files.keys.map { it.value }}"
        )
        // Prefer binary-name path when caller used dotted nested form.
        val path = files.keys.singleOrNull()
            ?: files.keys.firstOrNull { it.value.contains('$') }
            ?: files.keys.first()
        val snapshot = files[path] ?: fail("Missing provider for $className at ${path.value}")
        val surface = snapshot.moduleExportSurface ?: fail("Missing export surface for $className")
        return ProviderFile(path, snapshot, surface, surface.moduleType)
    }

    private fun module(className: String): ModuleType = providerFile(className).module

    private fun classType(className: String): JavaInstanceType =
        assertIs(module(className).fields.required("__class"))

    private fun jvmClassPath(className: String): VirtualPath =
        VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")

    private fun assertNotEqualsPath(left: VirtualPath, right: VirtualPath) {
        assertTrue(
            left != right,
            "Expected distinct virtual paths but both were ${left.value}"
        )
    }

    private fun <T> Map<String, T>.required(name: String): T =
        this[name] ?: fail("Missing reflected member '$name'; available: ${keys.sorted().joinToString()}")

    private fun fieldsHint(className: String, module: ModuleType): String {
        return "Expected $className fields to include known nested types; fields=${module.fields.keys.sorted()}"
    }

    private data class ProviderFile(
        val path: VirtualPath,
        val snapshot: WorkspaceSnapshot.FileSnapshot,
        val surface: ModuleExportSurface,
        val module: ModuleType
    )
}
