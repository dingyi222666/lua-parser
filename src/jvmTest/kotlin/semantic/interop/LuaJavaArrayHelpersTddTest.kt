package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LuaJavaArrayHelpersTddTest {
    @Test
    fun create_array_string_target_returns_string_java_array() {
        val harness = jvmHarness(
            "main.lua" to "local values = luajava.createArray(\"java.lang.String\", { \"a\", \"b\" })\nreturn values"
        )

        assertHoverType(harness, "values", "string[]", occurrence = 2)
        assertCreateArrayLoad(harness, "java.lang.String")
        // String is a JDK class; reflective provider should mount from CREATE_ARRAY_CALL alone.
        assertProviderPath(harness, "java.lang.String")
    }

    @Test
    fun create_array_primitive_target_returns_number_element_on_index() {
        val harness = jvmHarness(
            "main.lua" to "local ids = luajava.createArray(\"int\", { 1, 2 })\nlocal first = ids[1]\nreturn first"
        )

        assertHoverType(harness, "first", "number", occurrence = 2)
        assertCreateArrayLoad(harness, "int")
        // Primitive targets stay typed without inventing a reflective class provider.
    }

    @Test
    fun create_array_object_target_returns_reflected_object_elements() {
        val harness = jvmHarness(
            "main.lua" to "local locales = luajava.createArray(\"java.util.Locale\", {})\nlocal first = locales[1]\nreturn first"
        )

        assertHoverType(harness, "locales", "java.util.Locale[]", occurrence = 2)
        assertHoverType(harness, "first", "java.util.Locale", occurrence = 2)
        assertCreateArrayLoad(harness, "java.util.Locale")
        // TASK-528: only createArray references Locale — still mount component provider.
        assertProviderPath(harness, "java.util.Locale")
    }

    @Test
    fun create_array_result_exposes_java_array_length() {
        val harness = jvmHarness(
            "main.lua" to "local values = luajava.createArray(\"java.lang.String\", {})\nlocal count = values.length\nreturn count"
        )

        assertHoverType(harness, "count", "number", occurrence = 2)
        assertProviderPath(harness, "java.lang.String")
    }

    @Test
    fun create_array_alias_preserves_typed_array_result_and_load_fact() {
        val harness = jvmHarness(
            "main.lua" to "local createArray = luajava.createArray\nlocal values = createArray(\"java.lang.String\", {})\nreturn values"
        )

        val loads = jvmClassLoads(harness)

        assertHoverType(harness, "values", "string[]", occurrence = 2)
        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_ARRAY_CALL && it.target == "java.lang.String" })
        assertProviderPath(harness, "java.lang.String")
    }

    @Test
    fun create_array_only_reference_mounts_component_provider_without_bind_or_import() {
        // No bindClass / import / newInstance — createArray is the sole class-load fact.
        val harness = jvmHarness(
            "main.lua" to """
                local files = luajava.createArray("java.io.File", {})
                local first = files[1]
                return files, first
            """.trimIndent()
        )

        assertHoverType(harness, "files", "java.io.File[]", occurrence = 2)
        assertHoverType(harness, "first", "java.io.File", occurrence = 2)
        assertCreateArrayLoad(harness, "java.io.File")
        assertProviderPath(harness, "java.io.File")
        // Guard: no accidental bind/import facts inventing mounts via another path.
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.none {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL ||
                    it.kind == DocumentFacts.JvmClassLoadKind.IMPORT_CALL ||
                    it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL
            },
            "createArray-only fixture must not emit bind/import/newInstance class-load facts; actual=$loads"
        )
    }

    @Test
    fun new_array_bound_class_returns_typed_java_array() {
        val harness = jvmHarness(
            "main.lua" to "local Locale = luajava.bindClass(\"java.util.Locale\")\nlocal locales = luajava.newArray(Locale, 2)\nlocal first = locales[1]\nreturn locales, first"
        )

        assertHoverType(harness, "locales", "java.util.Locale[]", occurrence = 2)
        assertHoverType(harness, "first", "java.util.Locale", occurrence = 2)
        // newArray typing relies on the prior bindClass mount, not unrelated imports.
        assertProviderPath(harness, "java.util.Locale")
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any { it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL && it.target == "java.util.Locale" },
            "Expected BIND_CLASS_CALL for Locale; actual=$loads"
        )
    }

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun jvmClassLoads(harness: WorkspaceSemanticHarness): List<DocumentFacts.JvmClassLoadFact> {
        return harness.snapshot.files.getValue(harness.path("main.lua")).documentFacts?.jvmClassLoads.orEmpty()
    }

    private fun assertCreateArrayLoad(harness: WorkspaceSemanticHarness, target: String) {
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_ARRAY_CALL && it.target == target },
            "Expected CREATE_ARRAY_CALL for $target; actual=$loads"
        )
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, occurrence))
        assertEquals(expected, hover?.typeInfo?.displayName)
    }
}
