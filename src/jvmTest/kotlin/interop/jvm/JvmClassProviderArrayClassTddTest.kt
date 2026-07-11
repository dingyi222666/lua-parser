package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
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
 * TASK-370 — JVM class provider array class corpus.
 *
 * Encodes the *current* [JvmClassModuleProvider] surface for JVM array *classes*
 * without inventing product APIs:
 *
 * - Object-array binary descriptors that contain `.` (e.g. `[Ljava.lang.String;`)
 *   resolve via the same class-list path as ordinary FQCNs when the full descriptor
 *   is preserved through [JvmWorkspaceConfiguration.classes] (Set).
 * - Mount path mirrors product construction:
 *   `__jvm__/classes/${Class.getName().replace('.', '/')}.lua`
 *   so descriptor brackets / `;` are preserved after `.` → `/`.
 * - Module surface matches ordinary reflected classes (`moduleName` =
 *   `Class.getSimpleName()`, `__class` binary/canonical names, RETURN_TABLE_LITERAL).
 * - Missing / malformed / source-like array targets stay empty without throw.
 *
 * Deliberately *not* invented here (soft / non-required only):
 * - Primitive descriptors without `.` (`[I`, `[Z`, …): product
 *   `resolveClassLoads` only tries import-prefix candidates when no `.` is present,
 *   so these are not auto-mounted. Soft probe must not throw.
 * - Metadata multi-lists of object-array descriptors (`jvm.classes` string via
 *   [JvmWorkspaceConfiguration.fromMetadata]): splits on `,` / `;` / `\n`, so a
 *   trailing descriptor `;` collides with the delimiter even when entries are
 *   newline-separated (`[Ljava.lang.String;` → `[Ljava.lang.String`). Prefer
 *   configuration `classes` sets; do not invent a metadata escape API here.
 *
 * Distinct from semantic LuaJava helper typing corpora (`newArray` / `createArray` /
 * `.length` on array *values*). This file owns provider mounting of array *Class*
 * objects only.
 *
 * Match sibling [JvmClassProviderInnerClassTddTest] harness / helper style.
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class JvmClassProviderArrayClassTddTest {
    private val provider = JvmClassModuleProvider()

    // -------------------------------------------------------------------------
    // Resolve / mount object array classes via binary descriptors
    // -------------------------------------------------------------------------

    @Test
    fun string_object_array_binary_descriptor_resolves_to_stable_path() {
        val file = providerFile("[Ljava.lang.String;")

        assertEquals(jvmArrayClassPath("[Ljava.lang.String;"), file.path)
        assertEquals("__jvm__/classes/[Ljava/lang/String;.lua", file.path.value)
        assertEquals("String[]", file.module.moduleName)
        assertEquals(linkedSetOf("String[]"), file.snapshot.publicFingerprint?.providedModuleNames)
    }

    @Test
    fun locale_object_array_binary_descriptor_resolves() {
        val file = providerFile("[Ljava.util.Locale;")

        assertEquals(jvmArrayClassPath("[Ljava.util.Locale;"), file.path)
        assertEquals("Locale[]", file.module.moduleName)
        assertEquals(
            "[Ljava.util.Locale;",
            assertIs<JavaInstanceType>(file.module.fields["__class"]).classType.javaName.binaryName
        )
        assertEquals(
            "java.util.Locale[]",
            assertIs<JavaInstanceType>(file.module.fields["__class"]).classType.javaName.canonicalName
        )
    }

    @Test
    fun multi_dimension_object_array_descriptor_resolves() {
        val file = providerFile("[[Ljava.lang.String;")

        assertEquals(jvmArrayClassPath("[[Ljava.lang.String;"), file.path)
        assertEquals("__jvm__/classes/[[Ljava/lang/String;.lua", file.path.value)
        assertEquals("String[][]", file.module.moduleName)
        assertEquals(
            "[[Ljava.lang.String;",
            assertIs<JavaInstanceType>(file.module.fields["__class"]).classType.javaName.binaryName
        )
        assertEquals(
            "java.lang.String[][]",
            assertIs<JavaInstanceType>(file.module.fields["__class"]).classType.javaName.canonicalName
        )
    }

    @Test
    fun multi_object_array_configuration_lists_each_stable_path() {
        // Prefer configuration.classes (Set) so object-array trailing ';' is preserved.
        // Metadata delimiters (',', ';', '\n') collide with binary descriptors —
        // see soft probes below; do not hard-lock metadata multi-lists.
        val files = provider.providersFor(
            JvmWorkspaceConfiguration(
                classes = linkedSetOf(
                    "[Ljava.lang.String;",
                    "[Ljava.util.Locale;",
                    "[[Ljava.io.File;"
                )
            )
        )

        assertTrue(jvmArrayClassPath("[Ljava.lang.String;") in files)
        assertTrue(jvmArrayClassPath("[Ljava.util.Locale;") in files)
        assertTrue(jvmArrayClassPath("[[Ljava.io.File;") in files)
        assertEquals(3, files.size)
    }

    // -------------------------------------------------------------------------
    // Provider surface for array classes
    // -------------------------------------------------------------------------

    @Test
    fun array_class_provider_surface_is_return_table_literal() {
        val file = providerFile("[Ljava.lang.String;")

        assertEquals(ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL, file.surface.sourceForm)
        assertFalse(file.snapshot.cacheKey.isNullOrBlank())
        assertFalse(file.snapshot.publicFingerprint?.value.isNullOrBlank())
    }

    @Test
    fun array_class_module_exposes_class_field_not_collapsed_to_element_class() {
        val stringArray = assertIs<JavaInstanceType>(module("[Ljava.lang.String;").fields["__class"])
        val elementClass = assertIs<JavaInstanceType>(module("java.lang.String").fields["__class"])

        assertEquals("[Ljava.lang.String;", stringArray.classType.javaName.binaryName)
        assertEquals("java.lang.String", elementClass.classType.javaName.binaryName)
        assertTrue(
            stringArray.classType.javaName.binaryName != elementClass.classType.javaName.binaryName,
            "Array class provider must remain distinct from its element class provider"
        )
    }

    @Test
    fun array_and_element_class_providers_are_distinct_paths() {
        val array = providerFile("[Ljava.lang.String;")
        val element = providerFile("java.lang.String")

        assertNotEqualsPath(array.path, element.path)
        assertEquals("__jvm__/classes/[Ljava/lang/String;.lua", array.path.value)
        assertEquals("__jvm__/classes/java/lang/String.lua", element.path.value)
    }

    @Test
    fun array_class_virtual_path_preserves_descriptor_brackets_and_semicolon() {
        val file = providerFile("[Ljava.util.Locale;")

        assertTrue(
            file.path.value.contains("[Ljava"),
            "Array virtual path must preserve leading descriptor brackets; got ${file.path.value}"
        )
        assertTrue(
            file.path.value.endsWith("Locale;.lua"),
            "Array virtual path must preserve trailing descriptor ';' before .lua; got ${file.path.value}"
        )
        assertTrue(file.path.value.startsWith("__jvm__/classes/"))
        assertTrue(file.path.value.endsWith(".lua"))
        assertFalse(
            file.path.value.contains("Locale[].lua") || file.path.value.endsWith("Locale/[].lua"),
            "Array path must use binary descriptor form, not source-like Locale[]; got ${file.path.value}"
        )
    }

    @Test
    fun nested_class_module_exposes_class_field_with_binary_name() {
        // Mirror InnerClass helper naming for __class binary/canonical lock on arrays.
        val classField = assertIs<JavaInstanceType>(module("[Ljava.util.Locale;").fields["__class"])

        assertEquals("[Ljava.util.Locale;", classField.classType.javaName.binaryName)
        assertEquals("java.util.Locale[]", classField.classType.javaName.canonicalName)
    }

    @Test
    fun repeated_array_class_resolution_is_path_and_fingerprint_stable() {
        val first = providerFile("[Ljava.lang.String;")
        val second = providerFile("[Ljava.lang.String;")

        assertEquals(first.path, second.path)
        assertEquals(first.snapshot.publicFingerprint?.value, second.snapshot.publicFingerprint?.value)
        assertEquals(first.module.moduleName, second.module.moduleName)
    }

    @Test
    fun configuration_classes_list_mounts_object_array_descriptors() {
        val files = provider.providersFor(
            JvmWorkspaceConfiguration(
                classes = linkedSetOf(
                    "[Ljava.io.File;",
                    "[[Ljava.lang.String;"
                )
            )
        )

        assertEquals(
            setOf(
                jvmArrayClassPath("[Ljava.io.File;"),
                jvmArrayClassPath("[[Ljava.lang.String;")
            ),
            files.keys
        )
    }

    @Test
    fun array_class_provider_does_not_require_length_as_static_field() {
        // `.length` is an instance-language feature of array *values*, not a static
        // reflective field on the array Class provider. Contract: do not invent a
        // static length export; absence is fine. Presence of unrelated Object
        // members is allowed.
        val module = module("[Ljava.lang.String;")

        assertTrue("__class" in module.fields)
        assertIs<JavaInstanceType>(module.fields["__class"])
    }

    // -------------------------------------------------------------------------
    // Soft probes — product does not invent these surfaces today
    // -------------------------------------------------------------------------

    @Test
    fun primitive_array_descriptor_probe_is_not_required_but_must_not_throw() {
        // No '.' in `[I` / `[Z` → resolveClassLoads only tries import-prefix candidates.
        // Contract matches InnerClass soft import probes: never throw; empty is fine.
        // If product later mounts them, path must stay binary-descriptor form.
        val files = runCatching {
            provider.providersFor(
                JvmWorkspaceConfiguration(
                    classes = linkedSetOf("[I", "[Z", "[B", "[C", "[J", "[D", "[[I")
                )
            )
        }.getOrElse { error ->
            fail("Primitive array descriptor probe must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        files.keys.forEach { path ->
            assertTrue(path.value.startsWith("__jvm__/classes/"), "Unexpected path ${path.value}")
            assertTrue(
                path.value.contains('['),
                "If primitive arrays mount, path must preserve binary descriptor brackets; got ${path.value}"
            )
            assertFalse(
                path.value.contains("int") || path.value.contains("boolean"),
                "Primitive array path must not invent source-like names; got ${path.value}"
            )
        }
    }

    @Test
    fun object_array_metadata_newline_list_is_not_required_but_must_not_throw() {
        // fromMetadata splits on ',', ';', '\n'. Even newline-joined entries lose the
        // trailing descriptor ';' (`[Ljava.lang.String;` → `[Ljava.lang.String`).
        // Contract: never throw; may be empty or partial — prefer configuration.classes
        // to preserve full descriptors. Do not invent a metadata escape API here.
        val files = runCatching {
            provider.providersFor(
                mapOf(
                    JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                        "[Ljava.lang.String;",
                        "[Ljava.util.Locale;",
                        "[[Ljava.lang.String;"
                    ).joinToString("\n")
                )
            )
        }.getOrElse { error ->
            fail("Newline-delimited object-array metadata must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        files.keys.forEach { path ->
            assertTrue(path.value.startsWith("__jvm__/classes/"), "Unexpected path ${path.value}")
            // If anything mounts despite delimiter collision, keep binary form (brackets).
            assertTrue(
                path.value.contains('['),
                "If metadata multi mounts, path must preserve descriptor brackets; got ${path.value}"
            )
        }
    }

    @Test
    fun object_array_semicolon_delimited_metadata_is_not_required_but_must_not_throw() {
        // Metadata split on ';' collides with object-array trailing ';'.
        // Contract: never throw; may be empty or partial — do not invent an escape API here.
        val files = runCatching {
            provider.providersFor(
                mapOf(
                    JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                        "[Ljava.lang.String;",
                        "[Ljava.util.Locale;"
                    ).joinToString(";")
                )
            )
        }.getOrElse { error ->
            fail("Semicolon-delimited object-array metadata must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        files.keys.forEach { path ->
            assertTrue(path.value.startsWith("__jvm__/classes/"), "Unexpected path ${path.value}")
        }
    }

    // -------------------------------------------------------------------------
    // Missing / malformed array targets empty without throw
    // -------------------------------------------------------------------------

    @Test
    fun missing_object_array_component_returns_empty_without_throwing() {
        val files = runCatching {
            provider.providersFor(
                JvmWorkspaceConfiguration(
                    classes = linkedSetOf(
                        "[Lcom.missing.DoesNotExist;",
                        "[Lno.such.Type;",
                        "[[Lcom.missing.Outer;"
                    )
                )
            )
        }.getOrElse { error ->
            fail("Missing array component resolution must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(
            files.isEmpty(),
            "Missing array classes must not mount providers; actual: ${files.keys.map { it.value }}"
        )
    }

    @Test
    fun missing_and_valid_array_mix_lists_only_valid_without_throw() {
        val files = runCatching {
            provider.providersFor(
                JvmWorkspaceConfiguration(
                    classes = linkedSetOf(
                        "[Ljava.lang.String;",
                        "[Lcom.missing.NoSuch;",
                        "[Ljava.util.Locale;",
                        "[Ltotally.Fake;"
                    )
                )
            )
        }.getOrElse { error ->
            fail("Mixed valid/missing array listing must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertEquals(
            setOf(
                jvmArrayClassPath("[Ljava.lang.String;"),
                jvmArrayClassPath("[Ljava.util.Locale;")
            ),
            files.keys
        )
    }

    @Test
    fun blank_malformed_and_source_like_array_targets_do_not_throw() {
        // Source-like names (`String[]`, `int[]`) are not Class.forName binary forms.
        // Contract: never throw; may be empty (no invented alias API required here).
        val files = runCatching {
            provider.providersFor(
                JvmWorkspaceConfiguration(
                    classes = linkedSetOf(
                        "",
                        "   ",
                        "[]",
                        "[",
                        "[L",
                        "[L;",
                        "[Ljava.lang.String",
                        "java.lang.String[]",
                        "int[]",
                        "String[]",
                        "[[",
                        "[Q", // invalid primitive descriptor
                        "not-an-array"
                    )
                )
            )
        }.getOrElse { error ->
            fail("Malformed / source-like array targets must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        // Must not mount invalid / source-like descriptors as invented paths.
        files.keys.forEach { path ->
            assertTrue(
                path.value.startsWith("__jvm__/classes/"),
                "Unexpected path ${path.value}"
            )
            assertFalse(
                path.value.contains("String[]") || path.value.endsWith("int[].lua"),
                "Source-like array names must not invent non-descriptor paths; got ${path.value}"
            )
        }
    }

    @Test
    fun androlua_import_of_array_binary_descriptor_is_safe() {
        // Imports typically resolve class/simple names; array binary forms are unusual.
        // Contract: never throw; empty or a valid __jvm__/classes mount only.
        val files = runCatching {
            provider.providersFor(
                mapOf(JvmClassModuleProvider.IMPORTS_METADATA_KEY to "[Ljava.lang.String;")
            )
        }.getOrElse { error ->
            fail("Array binary import probe must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        files.keys.forEach { path ->
            assertTrue(path.value.startsWith("__jvm__/classes/"), "Unexpected path ${path.value}")
        }
    }

    @Test
    fun androlua_import_of_source_like_array_name_is_empty_without_throw() {
        val files = runCatching {
            provider.providersFor(
                mapOf(JvmClassModuleProvider.IMPORTS_METADATA_KEY to "String[]")
            )
        }.getOrElse { error ->
            fail("Source-like array import must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(
            files.isEmpty(),
            "Source-like array import must not mount a provider; actual: ${files.keys.map { it.value }}"
        )
    }

    @Test
    fun configuration_classes_list_missing_array_does_not_throw() {
        val files = runCatching {
            provider.providersFor(
                JvmWorkspaceConfiguration(
                    classes = linkedSetOf(
                        "[Lcom.missing.ArrayComponent;",
                        "[[[Lcom.missing.Deep;"
                    )
                )
            )
        }.getOrElse { error ->
            fail("Configuration missing array classes must not throw; got ${error::class.simpleName}: ${error.message}")
        }

        assertTrue(files.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Helpers — match JvmClassProviderInnerClassTddTest style
    // -------------------------------------------------------------------------

    private fun providerFile(className: String): ProviderFile {
        // Use configuration.classes so object-array trailing ';' is not split away
        // by fromMetadata delimiters (',', ';', '\n').
        val files = provider.providersFor(
            JvmWorkspaceConfiguration(classes = linkedSetOf(className))
        )
        assertTrue(
            files.isNotEmpty(),
            "Expected provider for array class $className; actual paths=${files.keys.map { it.value }}"
        )
        val path = files.keys.singleOrNull()
            ?: files.keys.firstOrNull { it.value.contains('[') }
            ?: files.keys.first()
        val snapshot = files[path] ?: fail("Missing provider for $className at ${path.value}")
        val surface = snapshot.moduleExportSurface ?: fail("Missing export surface for $className")
        return ProviderFile(path, snapshot, surface, surface.moduleType)
    }

    private fun module(className: String): ModuleType = providerFile(className).module

    /**
     * Mirrors [JvmClassModuleProvider] path construction:
     * `__jvm__/classes/${Class.getName().replace('.', '/')}.lua`
     */
    private fun jvmArrayClassPath(binaryName: String): VirtualPath =
        VirtualPath.of("__jvm__/classes/${binaryName.replace('.', '/')}.lua")

    private fun assertNotEqualsPath(left: VirtualPath, right: VirtualPath) {
        assertTrue(
            left != right,
            "Expected distinct virtual paths but both were ${left.value}"
        )
    }

    private data class ProviderFile(
        val path: VirtualPath,
        val snapshot: WorkspaceSnapshot.FileSnapshot,
        val surface: ModuleExportSurface,
        val module: ModuleType
    )
}
