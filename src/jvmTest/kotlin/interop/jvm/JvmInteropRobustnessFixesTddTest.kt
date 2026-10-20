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
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Robustness fixes from the adversarial interop audit (FIXER wave C).
 *
 * 1. Prefixed-import target classloaders are cached per (entry path, parent identity)
 *    instead of leaking one unclosed URLClassLoader per import resolution.
 * 2. Broken reflective metadata is reported and omitted without fabricating a class.
 * 3. Exported __class member surfaces are complete and O(n)-deduped before sorting.
 * 4. Explicit framework metadata is authoritative, including when its file is missing.
 *
 * Fixtures include actual compiled Java classes with an intentionally missing dependency.
 */
class JvmInteropRobustnessFixesTddTest {
    @Test
    fun broken_class_metadata_is_reported_without_mounting_a_fake_class() {
        val root = Files.createTempDirectory("lua-parser-missing-member-type-")
        val sources = mapOf(
            "Missing" to "public class Missing {}",
            "Broken" to "public class Broken { public Missing value() { return null; } }",
            "Healthy" to "public class Healthy { public static int answer() { return 42; } }"
        ).map { (name, body) ->
            root.resolve("$name.java").also { Files.writeString(it, "package fixture.metadata; $body") }
        }
        val compiler = assertNotNull(javax.tools.ToolProvider.getSystemJavaCompiler())
        val args = listOf("-d", root.toString()) + sources.map { it.toString() }
        assertEquals(0, compiler.run(null, null, null, *args.toTypedArray()))
        val missing = root.resolve("fixture/metadata/Missing.class")
        val bytes = Files.readAllBytes(missing)
        Files.delete(missing)
        val provider = JvmClassModuleProvider()
        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
            val config = JvmWorkspaceConfiguration(classLoader = loader, classes = linkedSetOf("fixture.metadata.Broken", "fixture.metadata.Healthy"))
            val mounted = provider.providersFor(config)
            assertEquals(listOf("Healthy"), mounted.values.map { it.moduleExportSurface!!.moduleType.moduleName })
            assertNull(provider.importedSymbolForTarget("fixture.metadata.Broken", config))
            val failure = provider.importDiagnostics(config).single()
            assertEquals("jvm.metadata-unavailable", failure.code)
            assertTrue(failure.message.contains("Missing"))
        }
        Files.write(missing, bytes)
        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
            val config = JvmWorkspaceConfiguration(classLoader = loader, classes = linkedSetOf("fixture.metadata.Broken"))
            assertNotNull(provider.importedSymbolForTarget("fixture.metadata.Broken", config))
            assertTrue(provider.importDiagnostics(config).isEmpty(), "Failures belong to their defining class loader.")
        }
    }

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
    // Fix 2: deferred catalog references retain real class identity
    // ------------------------------------------------------------------

    @Test
    fun deferred_catalog_reference_has_no_eager_members() {
        val shell: ModuleType = provider.deferredModuleTypeFor(String::class.java)

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
    fun class_members_surface_retains_every_real_member_after_deterministic_sort() {
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
            450,
            members.size,
            "A large real class must not silently lose its last members."
        )
        val names = members.map { it.name }
        assertEquals(names.sorted(), names, "The complete surface stays deterministically sorted.")
        assertEquals("m449", members.last().name)
        assertTrue(names.contains("m449"))
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

    @Test
    fun explicit_missing_framework_paths_never_substitute_the_host_sdk() {
        for (path in listOf(
            "/Users/someone/Library/Android/sdk/platforms/android-35/android.jar",
            "G:/Android/Sdk/platforms/android-35/android.jar",
            "/nonexistent/random/tools.jar"
        )) {
            val configuration = JvmWorkspaceConfiguration(androidJar = path)
            assertEquals(listOf(path), configuration.reflectionClasspathEntries())
            assertTrue(provider.importedClassNames("android.app.Activity", configuration).isEmpty(), path)
        }
    }
}
