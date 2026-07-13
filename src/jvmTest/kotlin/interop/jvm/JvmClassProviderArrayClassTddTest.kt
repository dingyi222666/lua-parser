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
    fun array_class_provider_surface_is_return_table_literal() {
        val file = providerFile("[Ljava.lang.String;")

        assertEquals(ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL, file.surface.sourceForm)
        assertFalse(file.snapshot.cacheKey.isNullOrBlank())
        assertFalse(file.snapshot.publicFingerprint?.value.isNullOrBlank())
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
    fun array_class_provider_does_not_require_length_as_static_field() {
        // `.length` is an instance-language feature of array *values*, not a static
        // reflective field on the array Class provider. Contract: do not invent a
        // static length export; absence is fine. Presence of unrelated Object
        // members is allowed.
        val module = module("[Ljava.lang.String;")

        assertTrue("__class" in module.fields)
        assertIs<JavaInstanceType>(module.fields["__class"])
    }
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
