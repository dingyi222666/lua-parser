package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LuaJavaCreateProxyTddTest {
    @Test
    fun create_proxy_comma_interface_list_exposes_each_interface_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable, java.util.Comparator", {})
                local run = proxy.run
                local compare = proxy.compare
                return run, compare
            """.trimIndent()
        )

        val runHover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "run", occurrence = 2))
        val compareHover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "compare", occurrence = 2))

        assertEquals(SymbolKind.METHOD, runHover?.symbol?.kind)
        assertCallable(runHover?.typeInfo?.displayName)
        assertEquals(SymbolKind.METHOD, compareHover?.symbol?.kind)
        assertCallable(compareHover?.typeInfo?.displayName)
    }

    @Test
    fun create_proxy_comma_interface_list_records_separate_class_load_targets() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable, java.util.Comparator", {})
                return proxy
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)

        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL && it.target == "java.lang.Runnable" })
        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL && it.target == "java.util.Comparator" })
        assertFalse(loads.any { it.target == "java.lang.Runnable, java.util.Comparator" })
        assertEquals(emptyList(), sourceImports(harness))
    }

    @Test
    fun create_proxy_comma_interface_list_preserves_single_target_behavior() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return run
            """.trimIndent()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "run", occurrence = 2))
        val loads = jvmClassLoads(harness)

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertCallable(hover?.typeInfo?.displayName)
        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL && it.target == "java.lang.Runnable" })
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

    private fun sourceImports(harness: WorkspaceSemanticHarness): List<String> {
        return harness.snapshot.files.getValue(harness.path("main.lua")).documentFacts?.sourceImports.orEmpty()
            .map { it.target }
    }

    private fun assertCallable(displayName: String?) {
        assertTrue(
            displayName.orEmpty().contains("fun("),
            "Expected callable type, got $displayName."
        )
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java type, got unknown.")
    }
}
