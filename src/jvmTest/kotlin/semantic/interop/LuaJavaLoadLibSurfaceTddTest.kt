package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * TASK-264 corpus: `luajava.loadLib` / loadLib aliases expose documented package/function
 * surfaces (static methods and fields) or unknown with diagnostics. Shadowed locals must not
 * inherit JVM loadLib semantics.
 *
 * Test-only; product fixes belong in separately scoped tasks.
 */
class LuaJavaLoadLibSurfaceTddTest {

    // ------------------------------------------------------------------
    // Direct luajava.loadLib package/function surfaces
    // ------------------------------------------------------------------

    @Test
    fun load_lib_static_method_result_is_callable() {
        val harness = jvmHarness(
            "main.lua" to """
                local currentTimeMillis = luajava.loadLib("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2)
        )

        assertCallable(hover?.typeInfo?.displayName)
        assertProviderPath(harness, "java.lang.System")
    }
    @Test
    fun load_lib_static_field_result_uses_reflected_field_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local root = luajava.loadLib("java.util.Locale", "ROOT")
                return root
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "root", occurrence = 2)
        )

        assertEquals("java.util.Locale", hover?.typeInfo?.displayName)
        assertNotUnknown(hover?.typeInfo?.displayName)
        assertProviderPath(harness, "java.util.Locale")
    }
    @Test
    fun load_lib_document_facts_record_loaded_class_target() {
        val harness = jvmHarness(
            "main.lua" to """
                local currentTimeMillis = luajava.loadLib("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)

        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == "java.lang.System"
            },
            "Expected LOAD_LIB_CALL fact for java.lang.System; got $loads"
        )
    }
    @Test
    fun load_lib_local_alias_resolves_static_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local currentTimeMillis = loadLib("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2)
        )

        assertCallable(hover?.typeInfo?.displayName)
        assertProviderPath(harness, "java.lang.System")
    }
    @Test
    fun colon_load_lib_call_does_not_accidentally_model_helper() {
        val harness = jvmHarness(
            "main.lua" to """
                local loaded = luajava:loadLib("java.lang.System", "currentTimeMillis")
                return loaded
            """.trimIndent()
        )

        assertHoverType(harness, "loaded", "unknown", occurrence = 2)
        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == "java.lang.System"
            },
            "Colon loadLib must not emit LOAD_LIB_CALL; got $loads"
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun jvmClassLoads(harness: WorkspaceSemanticHarness): List<DocumentFacts.JvmClassLoadFact> {
        return harness.snapshot.files.getValue(harness.path("main.lua")).documentFacts?.jvmClassLoads.orEmpty()
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    /**
     * Product type rendering for JVM methods may use monomorphic `fun(...)` or generic
     * `fun<T>(...)` / union-of-overloads forms. Accept any callable-looking displayName.
     */
    private fun assertCallable(displayName: String?, label: String = "type") {
        val text = displayName.orEmpty()
        assertTrue(
            looksCallable(text),
            "Expected callable $label, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    private fun looksCallable(displayName: String): Boolean {
        // fun(...), fun<T>(...), fun<T,U>(...), or multi-overload unions of those.
        return displayName.contains("fun(") ||
            Regex("""fun\s*<[^>]+>\s*\(""").containsMatchIn(displayName)
    }

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
        assertEquals(expected, hover?.typeInfo?.displayName)
    }

    /**
     * Shadowed local loadLib returns a plain table; product may keep field shapes or collapse to
     * `table`. Reject JVM method/callable surfaces and java.* class types.
     */
    private fun assertHoverTypeIsTableLikeNotJvmCallable(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
        val display = hover?.typeInfo?.displayName.orEmpty()
        assertTrue(
            display == "table" ||
                display.startsWith("{") ||
                (display.contains("target") && display.contains("member")),
            "Expected table-like shadowed loadLib result, got '$display'."
        )
        assertFalse(
            looksCallable(display),
            "Shadowed loadLib must not expose JVM callable surface, got '$display'."
        )
        assertFalse(
            display.startsWith("java."),
            "Shadowed loadLib must not expose JVM class type, got '$display'."
        )
    }

    private fun assertHoverTypeIsNot(
        harness: WorkspaceSemanticHarness,
        needle: String,
        unexpected: String,
        substring: Boolean = false,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
        val display = hover?.typeInfo?.displayName
        if (substring) {
            assertFalse(
                display.orEmpty().contains(unexpected),
                "Expected type not containing '$unexpected', got '$display'."
            )
        } else {
            assertNotEquals(unexpected, display)
        }
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java type, got unknown.")
    }

    private fun assertUnknownTargetDiagnostic(harness: WorkspaceSemanticHarness, target: String) {
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(
            diagnostics.containsTarget(target),
            "Expected unknown LuaJava target diagnostic for $target; actual diagnostics: ${diagnostics.map { it.message }}."
        )
    }

    private fun List<Diagnostic>.containsTarget(target: String): Boolean {
        return any { diagnostic ->
            diagnostic.message.contains(target) &&
                (diagnostic.message.contains("unknown", ignoreCase = true) ||
                    diagnostic.message.contains("not found", ignoreCase = true) ||
                    diagnostic.message.contains("unresolved", ignoreCase = true))
        }
    }
}
