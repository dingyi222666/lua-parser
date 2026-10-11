package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmClassLookupTest {
    private class RecordingLoader : ClassLoader(JvmClassModuleProvider::class.java.classLoader) {
        val requests = mutableListOf<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            requests += name
            return super.loadClass(name, resolve)
        }
    }

    @Test
    fun exact_binary_import_avoids_failed_nested_class_probes() {
        val loader = RecordingLoader()
        val provider = JvmClassModuleProvider()
        val configuration = JvmWorkspaceConfiguration(classLoader = loader)

        assertEquals("java.util.ArrayList", provider.importedClassName("java.util.ArrayList", configuration))
        assertEquals(listOf("java.util.ArrayList"), loader.requests)
    }

    @Test
    fun short_import_under_package_prefix_avoids_nested_class_probes() {
        val loader = RecordingLoader()
        val provider = JvmClassModuleProvider()
        val configuration = JvmWorkspaceConfiguration(classLoader = loader, importPrefixes = listOf("java.util"))

        assertEquals("java.util.ArrayList", provider.importedClassName("ArrayList", configuration))
        assertEquals(listOf("java.util.ArrayList"), loader.requests)
    }

    @Test
    fun short_import_under_enclosing_class_prefix_still_prefers_nested_binary_name() {
        val loader = RecordingLoader()
        val provider = JvmClassModuleProvider()
        val configuration = JvmWorkspaceConfiguration(classLoader = loader, importPrefixes = listOf("java.util.Map"))

        assertEquals("java.util.Map\$Entry", provider.importedClassName("Entry", configuration))
        assertEquals(listOf("java.util.Map\$Entry"), loader.requests)
    }

    @Test
    fun dotted_and_underscore_nested_aliases_still_resolve() {
        val provider = JvmClassModuleProvider()
        val configuration = JvmWorkspaceConfiguration(classLoader = RecordingLoader())

        for (target in listOf("java.util.Map.Entry", "java.util.Map_Entry", "java.util.Map\$Entry")) {
            assertEquals("java.util.Map\$Entry", provider.importedClassName(target, configuration), target)
        }
    }

    @Test
    fun missing_twelve_segment_name_has_linear_class_loader_probes() {
        val loader = RecordingLoader()
        val target = List(12) { "absent" }.joinToString(".")
        assertNull(JvmClassModuleProvider().importedClassName(target,
            JvmWorkspaceConfiguration(classLoader = loader)))
        assertEquals(12, loader.requests.size)
        assertEquals(target, loader.requests.first())
    }

    @Test
    fun pathological_missing_import_does_not_expand_every_separator_combination() {
        val loader = RecordingLoader()
        val provider = JvmClassModuleProvider()
        val target = List(40) { "missing" }.joinToString(".")

        assertNull(provider.importedClassName(target, JvmWorkspaceConfiguration(classLoader = loader)))
        assertEquals(target, loader.requests.first())
        assertEquals(40, loader.requests.distinct().size)
        assertEquals(loader.requests.distinct(), loader.requests)
    }
}
