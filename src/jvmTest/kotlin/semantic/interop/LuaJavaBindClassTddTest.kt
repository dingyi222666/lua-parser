package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LuaJavaBindClassTddTest {
    @Test
    fun bind_class_mounts_string_builder_provider() {
        val harness = jvmHarness(
            "main.lua" to "local StringBuilder = luajava.bindClass(\"java.lang.StringBuilder\")\nreturn StringBuilder"
        )

        assertProviderPath(harness, "java.lang.StringBuilder")
    }
    @Test
    fun bind_class_static_field_hover_reports_reflected_type() {
        val harness = jvmHarness(
            "main.lua" to "local Locale = luajava.bindClass(\"java.util.Locale\")\nlocal root = Locale.ROOT\nreturn root"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "ROOT"))

        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals("java.util.Locale", hover?.typeInfo?.displayName)
    }
    @Test
    fun bind_class_static_method_hover_reports_callable_type() {
        val harness = jvmHarness(
            "main.lua" to "local System = luajava.bindClass(\"java.lang.System\")\nlocal now = System.currentTimeMillis\nreturn now"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertCallable(hover?.typeInfo?.displayName)
    }
    @Test
    fun bind_class_completion_includes_static_members() {
        val harness = jvmHarness(
            "main.lua" to "local Integer = luajava.bindClass(\"java.lang.Integer\")\nlocal max = Integer.MAX_VALUE\nreturn max"
        )

        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "MAX_VALUE"))

        assertCompletion(completions, "MAX_VALUE", CompletionItemKind.FIELD)
        assertCompletion(completions, "parseInt", CompletionItemKind.METHOD)
    }
    @Test
    fun bind_class_constructor_call_returns_instance_class_type() {
        val harness = jvmHarness(
            "main.lua" to "local StringBuilder = luajava.bindClass(\"java.lang.StringBuilder\")\nlocal builder = StringBuilder()\nreturn builder"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "builder", occurrence = 2))

        assertEquals("java.lang.StringBuilder", hover?.typeInfo?.displayName)
    }
    @Test
    fun bind_class_alias_document_facts_record_bind_class_load() {
        val harness = jvmHarness(
            "main.lua" to "local bind = luajava.bindClass\nlocal Locale = bind(\"java.util.Locale\")\nreturn Locale"
        )

        val loads = jvmClassLoads(harness)

        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL && it.target == "java.util.Locale" })
    }
    @Test
    fun bind_class_colon_call_does_not_accidentally_model_luajava_helper() {
        val harness = jvmHarness(
            "main.lua" to "local Locale = luajava:bindClass(\"java.util.Locale\")\nreturn Locale"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "Locale", occurrence = 3))

        assertEquals("unknown", hover?.typeInfo?.displayName)
    }
    @Test
    fun new_instance_result_hover_reports_instance_class() {
        val harness = jvmHarness(
            "main.lua" to "local builder = luajava.newInstance(\"java.lang.StringBuilder\")\nreturn builder"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "builder", occurrence = 2))

        assertEquals("java.lang.StringBuilder", hover?.typeInfo?.displayName)
    }
    @Test
    fun new_instance_document_facts_record_new_instance_load() {
        val harness = jvmHarness(
            "main.lua" to "local make = luajava.newInstance\nlocal builder = make(\"java.lang.StringBuilder\")\nreturn builder"
        )

        val loads = jvmClassLoads(harness)

        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL && it.target == "java.lang.StringBuilder" })
    }
    @Test
    fun create_proxy_single_interface_result_exposes_interface_method() {
        val harness = jvmHarness(
            "main.lua" to "local proxy = luajava.createProxy(\"java.lang.Runnable\", {})\nlocal run = proxy.run\nreturn run"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "run", occurrence = 2))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertCallable(hover?.typeInfo?.displayName)
    }
    @Test
    fun load_lib_static_method_result_is_callable() {
        val harness = jvmHarness(
            "main.lua" to "local currentTimeMillis = luajava.loadLib(\"java.lang.System\", \"currentTimeMillis\")\nreturn currentTimeMillis"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2))

        assertCallable(hover?.typeInfo?.displayName)
    }
    @Test
    fun bind_class_missing_target_reports_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to "local Missing = luajava.bindClass(\"missing.DoesNotExist\")\nreturn Missing"
        )

        assertUnknownTargetDiagnostic(harness, "missing.DoesNotExist")
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

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    private fun assertCompletion(completions: List<CompletionItem>, label: String, kind: CompletionItemKind) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    private fun assertCallable(displayName: String?) {
        assertTrue(
            displayName.orEmpty().contains("fun("),
            "Expected callable type, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    private fun assertHoverDisplay(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, occurrence))
        assertEquals(expected, hover?.typeInfo?.displayName)
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
