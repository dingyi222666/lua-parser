package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaStaticMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Robustness fixes from the adversarial interop audit (FIXER wave C).
 *
 * 1. Prefixed-import target classloaders are cached per (entry path, parent identity)
 *    instead of leaking one unclosed URLClassLoader per import resolution.
 * 2. Deep reflection over a broken jar entry (NoClassDefFoundError) degrades to a
 *    member-less shell module instead of aborting the workspace update.
 * 3. Exported __class member surfaces are O(n)-deduped and hard-capped (400) after
 *    deterministic alphabetical sorting.
 * 4. The blank-jar host-android.jar soft fallback only applies to configurations that
 *    actually carry JVM interop configuration.
 *
 * All tests are jar-free: JDK classes, synthetic type-model graphs, and temp directories.
 */
class JvmInteropRobustnessFixesTddTest {
    private val provider = JvmClassModuleProvider()
    private val shellOwner = JavaTypeName(packageName = "fixture.shell", simpleNames = listOf("Shell"))

    // ------------------------------------------------------------------
    // Fix 1: import-target classloader caching (classloader leak bound)
    // ------------------------------------------------------------------

    @Test
    fun import_target_classloader_is_cached_per_entry_path_and_parent() {
        val entry = Files.createTempDirectory("lua-parser-import-target-cache-").toFile()
        val parent = URLClassLoader(emptyArray(), JvmClassModuleProvider::class.java.classLoader)

        val first = assertNotNull(
            provider.cachedImportTargetClassLoader(entry, parent),
            "Expected a loader for an existing directory entry."
        )
        val second = provider.cachedImportTargetClassLoader(entry, parent)
        assertSame(first, second, "Repeated resolutions of the same entry path must reuse one loader.")

        val otherParent = URLClassLoader(emptyArray(), JvmClassModuleProvider::class.java.classLoader)
        val third = provider.cachedImportTargetClassLoader(entry, otherParent)
        assertNotNull(third)
        assertNotSame(first, third, "A different parent chain gets its own cached loader.")
    }

    @Test
    fun prefixed_import_resolution_shares_the_cached_loader_and_never_throws() {
        val entry = Files.createTempDirectory("lua-parser-import-target-resolve-").toFile()
        val parent = URLClassLoader(emptyArray(), JvmClassModuleProvider::class.java.classLoader)
        val loader = assertNotNull(provider.cachedImportTargetClassLoader(entry, parent))
        val configuration = JvmWorkspaceConfiguration(classLoader = parent)

        // Missing class under an existing classpath entry: resolution fails cleanly and
        // reuses the cached loader rather than constructing fresh ones per resolve.
        repeat(3) {
            val resolved = provider.importedClassNames("$entry:fixture.missing.Missing", configuration)
            assertTrue(resolved.isEmpty(), "Expected no loadable class under the empty entry.")
        }
        assertSame(
            loader,
            provider.cachedImportTargetClassLoader(entry, parent),
            "Resolution must keep using the single cached import-target loader."
        )
    }

    // ------------------------------------------------------------------
    // Fix 2: reflection failure degrades to a member-less shell
    // ------------------------------------------------------------------

    @Test
    fun shell_module_type_is_a_member_less_class_shell() {
        val shell: ModuleType = provider.shellModuleTypeFor(String::class.java)

        assertEquals("String", shell.moduleName)
        assertEquals(setOf("__class"), shell.fields.keys, "Shell module must only carry the __class shell.")
        assertTrue(shell.methods.isEmpty(), "Shell module must not invent methods.")

        val instanceType = assertNotNull(
            shell.fields["__class"] as? JavaInstanceType,
            "Expected __class to be a cheap JavaInstanceType shell."
        )
        assertEquals("java.lang.String", instanceType.classType.javaName.binaryName)
        assertTrue(instanceType.classType.allStaticMembers().isEmpty())
        assertTrue(instanceType.classType.allInstanceMembers().isEmpty())
        assertTrue(instanceType.classType.allInnerClasses().isEmpty())
        assertTrue(instanceType.classType.constructors.isEmpty)
    }

    @Test
    fun providers_for_still_expands_full_member_surface_after_failure_guard() {
        val providers = provider.providersFor(
            JvmWorkspaceConfiguration(classes = linkedSetOf("java.lang.String"))
        )
        val module = assertNotNull(
            providers.values.singleOrNull()?.moduleExportSurface?.moduleType,
            "Expected exactly one provider for java.lang.String."
        )
        // The runCatching guard must only intercept failures, never swallow healthy
        // reflection: statics and static method groups keep their full surface.
        assertTrue("CASE_INSENSITIVE_ORDER" in module.fields)
        assertTrue("format" in module.methods)
        assertTrue("__call" in module.fields, "Public constructors still produce __call.")
    }

    // ------------------------------------------------------------------
    // Fix 3: O(n) dedupe + deterministic hard cap on class member exports
    // ------------------------------------------------------------------

    @Test
    fun class_members_surface_is_hard_capped_after_deterministic_sort() {
        val instanceMembers = (0 until 450).associate { index ->
            val name = "m%03d".format(index)
            name to JavaInstanceMemberType(
                owner = shellOwner,
                memberName = name,
                valueType = PrimitiveType.NUMBER,
                memberKind = JavaMemberKind.METHOD
            )
        }
        val classType = JavaClassType(javaName = shellOwner, instanceMembers = instanceMembers)

        val members = provider.classMembers(JavaInstanceType(classType))

        assertEquals(
            JvmClassModuleProvider.MAX_CLASS_EXPORT_MEMBERS,
            members.size,
            "Member export surface must be hard-capped."
        )
        val names = members.map { it.name }
        assertEquals(names.sorted(), names, "Capped surface must stay alphabetically sorted (deterministic).")
        assertEquals("m399", members.last().name, "Truncation keeps the alphabetically first members.")
        assertFalse(names.contains("m449"))
    }

    @Test
    fun class_members_dedupe_prefers_static_exports_on_name_collisions() {
        val staticMembers = mapOf(
            "m00" to JavaStaticMemberType(
                owner = shellOwner,
                memberName = "m00",
                valueType = PrimitiveType.NUMBER,
                memberKind = JavaMemberKind.METHOD
            )
        )
        val instanceMembers = (0 until 10).associate { index ->
            val name = "m%02d".format(index)
            name to JavaInstanceMemberType(
                owner = shellOwner,
                memberName = name,
                valueType = PrimitiveType.NUMBER,
                memberKind = JavaMemberKind.FIELD
            )
        }
        val classType = JavaClassType(
            javaName = shellOwner,
            staticMembers = staticMembers,
            instanceMembers = instanceMembers
        )

        val members = provider.classMembers(JavaInstanceType(classType))

        assertEquals(10, members.size, "Colliding instance member must not duplicate the static export.")
        assertEquals(1, members.count { it.name == "m00" })
        val staticExport = members.first { it.name == "m00" }
        assertEquals(
            SymbolKind.METHOD,
            staticExport.kind,
            "Static METHOD export must win the name collision."
        )
        val names = members.map { it.name }
        assertEquals(names.sorted(), names, "Under-cap surfaces stay deterministically sorted.")
    }

    // ------------------------------------------------------------------
    // Fix 4: blank-jar host fallback requires deliberate interop configuration
    // ------------------------------------------------------------------

    @Test
    fun blank_android_jar_fallback_requires_deliberate_interop_configuration() {
        assertFalse(
            provider.shouldSoftFallbackToHostAndroidJar(JvmWorkspaceConfiguration()),
            "A fully empty configuration must not silently mount a host SDK jar."
        )
        assertTrue(
            provider.shouldSoftFallbackToHostAndroidJar(
                JvmWorkspaceConfiguration(classpathEntries = listOf("does-not-need-to-exist/app.jar"))
            ),
            "Explicit classpath configuration keeps the host-jar fallback available."
        )
        assertTrue(
            provider.shouldSoftFallbackToHostAndroidJar(
                JvmWorkspaceConfiguration(classes = linkedSetOf("android.view.View"))
            ),
            "Explicit classes configuration keeps the host-jar fallback available."
        )
        assertTrue(
            provider.shouldSoftFallbackToHostAndroidJar(
                JvmWorkspaceConfiguration(androluaImports = listOf("android.view.View"))
            ),
            "Explicit import configuration keeps the host-jar fallback available."
        )
    }

    @Test
    fun missing_configured_android_jar_fallback_still_follows_well_known_path_rules() {
        val wellKnownMacCandidate = "/Users/someone/Library/Android/sdk/platforms/android-35/android.jar"
        assertTrue(
            provider.shouldSoftFallbackToHostAndroidJar(JvmWorkspaceConfiguration(androidJar = wellKnownMacCandidate)),
            "Missing well-known dual-path SDK candidates still soft-fall back."
        )
        assertFalse(
            provider.shouldSoftFallbackToHostAndroidJar(
                JvmWorkspaceConfiguration(androidJar = "/nonexistent/random/tools.jar")
            ),
            "Arbitrary missing configured paths never invent a host jar."
        )
        val existingJar = Files.createTempFile("lua-parser-existing-configured", ".jar").toFile()
        existingJar.deleteOnExit()
        assertFalse(
            provider.shouldSoftFallbackToHostAndroidJar(
                JvmWorkspaceConfiguration(androidJar = existingJar.absolutePath)
            ),
            "An existing configured jar is authoritative; no fallback."
        )
    }
}
