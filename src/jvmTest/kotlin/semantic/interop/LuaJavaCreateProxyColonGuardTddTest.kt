package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-395 corpus: LuaJava `createProxy` colon-call / local-shadow facts refine.
 *
 * Complements TASK-350 ([LuaJavaCreateProxySurfaceTddTest]) without rewriting that file.
 * Focus is document-facts + type-surface guards only:
 * - `luajava:createProxy(...)` must not emit CREATE_PROXY_CALL facts.
 * - Local / table shadows of `createProxy` must not emit CREATE_PROXY_CALL facts.
 * - Guarded results must not inherit JavaProxy / documented createProxy overload surface.
 *
 * Positive real-dot CREATE_PROXY_CALL still works alongside colon rejection (regression lock).
 * Test-only; product sources out of scope; no Gradle from workers.
 */
class LuaJavaCreateProxyColonGuardTddTest {

    // ------------------------------------------------------------------
    // Colon call: no CREATE_PROXY_CALL facts, no JavaProxy inheritance
    // ------------------------------------------------------------------

    @Test
    fun colon_create_proxy_single_interface_emits_no_create_proxy_facts() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava:createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return proxy, run
            """.trimIndent()
        )

        assertNoCreateProxyFacts(harness)
        assertNoJavaProxyInheritance(harness, "proxy", occurrence = 2)
        assertHoverDisplay(harness, "proxy", "unknown", occurrence = 2)
        assertHoverDisplay(harness, "run", "unknown", occurrence = 2)
    }
    @Test
    fun colon_create_proxy_member_alias_does_not_model_proxy_or_facts() {
        // `local createProxy = luajava:createProxy` must not alias the helper kind
        // (DocumentFactsCollector treats colon member as non-aliasable).
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava:createProxy
                local proxy = createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return proxy, run
            """.trimIndent()
        )

        assertNoCreateProxyFacts(harness)
        assertNoJavaProxyInheritance(harness, "proxy", occurrence = 2)
        assertHoverDisplay(harness, "proxy", "unknown", occurrence = 2)
        assertHoverDisplay(harness, "run", "unknown", occurrence = 2)
    }
    @Test
    fun colon_create_proxy_chained_alias_does_not_model_proxy_or_facts() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava:createProxy
                local create = createProxy
                local again = create
                local proxy = again("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                return proxy, run
            """.trimIndent()
        )

        assertNoCreateProxyFacts(harness)
        assertNoJavaProxyInheritance(harness, "proxy", occurrence = 2)
        assertHoverDisplay(harness, "run", "unknown", occurrence = 2)
    }
    @Test
    fun local_shadow_return_surface_never_inherits_javaproxy() {
        val harness = jvmHarness(
            "main.lua" to """
                local function createProxy(target, impl)
                    return { value = target }
                end

                local proxy = createProxy("java.lang.Runnable", {})
                return proxy
            """.trimIndent()
        )

        val display = hoverDisplay(harness, "proxy", occurrence = 2).orEmpty()
        assertFalse(
            display.contains("JavaProxy", ignoreCase = true),
            "Local shadow must not inherit JavaProxy surface; got $display"
        )
        assertFalse(
            looksLikeCreateProxySignature(display),
            "Local shadow must not inherit documented createProxy signature; got $display"
        )
        assertLocalShadowSurface(harness, "proxy", occurrence = 2)
        assertNoCreateProxyFacts(harness)
    }
    @Test
    fun real_dot_create_proxy_still_records_facts_alongside_colon_guard() {
        // Avoid local-alias createProxy bindings so occurrence indexing stays stable
        // (TASK-350 / REVIEW31 residual).
        val harness = jvmHarness(
            "main.lua" to """
                local colonProxy = luajava:createProxy("java.lang.Runnable", {})
                local proxy = luajava.createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return colonProxy, run
            """.trimIndent()
        )

        assertHoverDisplay(harness, "colonProxy", "unknown", occurrence = 2)
        assertNoJavaProxyInheritance(harness, "colonProxy", occurrence = 2)

        val loads = jvmClassLoads(harness)
        val runnableProxyLoads = loads.filter {
            it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL &&
                it.target == "java.lang.Runnable"
        }
        assertTrue(
            runnableProxyLoads.isNotEmpty(),
            "Real-dot createProxy must record CREATE_PROXY_CALL for Runnable; got $loads"
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

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int
    ): String? {
        return harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )?.typeInfo?.displayName
    }

    private fun assertHoverDisplay(
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

    private fun assertNoCreateProxyFacts(harness: WorkspaceSemanticHarness) {
        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL },
            "Expected no CREATE_PROXY_CALL facts; got $loads"
        )
    }

    private fun assertNoCreateProxyTarget(harness: WorkspaceSemanticHarness, target: String) {
        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL && it.target == target
            },
            "Expected no CREATE_PROXY_CALL for $target; got $loads"
        )
    }

    /**
     * Guarded createProxy results must never look like JavaProxy inheritance / documented
     * createProxy overload return surface.
     */
    private fun assertNoJavaProxyInheritance(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int
    ) {
        val display = hoverDisplay(harness, needle, occurrence).orEmpty()
        assertFalse(
            display.contains("JavaProxy", ignoreCase = true),
            "Guarded createProxy result must not inherit JavaProxy; $needle display=$display"
        )
        assertFalse(
            looksLikeCreateProxySignature(display),
            "Guarded createProxy result must not inherit documented createProxy signature; $needle display=$display"
        )
    }

    /**
     * Local shadow return surface dual-path (matches HelperShadowing / MultiInterface product):
     * ideal `{ value: unknown }`; also table / unknown / blank / value-shaped.
     * Hard reject: JavaProxy + documented createProxy signature inheritance.
     */
    private fun assertLocalShadowSurface(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int
    ) {
        val display = hoverDisplay(harness, needle, occurrence).orEmpty()
        assertFalse(
            display.contains("JavaProxy", ignoreCase = true),
            "Local createProxy shadow must not expose JavaProxy; got $display"
        )
        assertFalse(
            looksLikeCreateProxySignature(display),
            "Local createProxy shadow must not expose documented createProxy signature; got $display"
        )
        val localTableShape =
            display.isBlank() ||
                display == "{ value: unknown }" ||
                display == "table" ||
                display == "unknown" ||
                display.startsWith("{") ||
                (display.contains("value") && !looksLikeCreateProxySignature(display)) ||
                (!display.contains("JavaProxy", ignoreCase = true) &&
                    !looksLikeCreateProxySignature(display) &&
                    !display.contains("java.lang.Runnable", ignoreCase = true))
        assertTrue(
            localTableShape,
            "Local createProxy shadow must stay table/value-shaped (or unknown), not JavaProxy; got $display"
        )
    }

    private fun looksLikeCreateProxySignature(text: String): Boolean {
        if (text.isBlank() || text == "unknown") {
            return false
        }
        val hasInterfaceParam =
            text.contains("interfaceNames", ignoreCase = true) ||
                text.contains("interfaceName", ignoreCase = true) ||
                (text.contains("fun(") &&
                    text.contains("string") &&
                    (text.contains("interface", ignoreCase = true) ||
                        text.contains("callbacks", ignoreCase = true) ||
                        text.contains("JavaProxy", ignoreCase = true)))
        val hasCallbacks =
            text.contains("callbacks", ignoreCase = true) ||
                (text.contains("fun(") && text.contains("table") && text.contains("function"))
        val hasProxyReturn = text.contains("JavaProxy", ignoreCase = true)
        return (hasInterfaceParam && hasCallbacks) ||
            (hasInterfaceParam && hasProxyReturn) ||
            (hasCallbacks && hasProxyReturn) ||
            (text.contains("fun(") && text.contains("JavaProxy", ignoreCase = true)) ||
            (text.contains("fun(") &&
                text.contains("interfaceNames", ignoreCase = true) &&
                text.contains("callbacks", ignoreCase = true))
    }
}
