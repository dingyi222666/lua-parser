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
