package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.ast.node.Position
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

/**
 * TASK-212 / TASK-573 corpus: LuaJava `createProxy` multi-interface success and missing-method diagnostics.
 *
 * Acceptance (test-only; review-owned verification):
 * - Multi-interface fixtures for success (varargs + comma-separated interface lists).
 * - Missing-method diagnostics on multi-interface proxies.
 * - Colon-call / local shadowing guards remain intact for `createProxy`.
 * - Local shadows must not invent multi-interface proxy surfaces (TASK-573).
 *
 * Product sources are out of scope for this worker when ExpressionTypeEvaluator is locked;
 * no Gradle from workers.
 */
class LuaJavaCreateProxyMultiInterfaceTddTest {

    // ------------------------------------------------------------------
    // Success: multi-interface member surfaces
    // ------------------------------------------------------------------

    @Test
    fun multi_interface_varargs_expose_methods_from_each_interface() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                local compare = proxy.compare
                return run, compare
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCallableMethodHover(harness, "compare", occurrence = 2)
    }
    @Test
    fun multi_interface_comma_list_exposes_methods_from_each_interface() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable, java.util.Comparator", {})
                local run = proxy.run
                local compare = proxy.compare
                return run, compare
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCallableMethodHover(harness, "compare", occurrence = 2)
    }
    @Test
    fun multi_interface_completion_includes_each_interface_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local compare = proxy.compare
                return compare
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            memberPosition(harness, "proxy.compare")
        )

        assertCompletion(completions, "compare", CompletionItemKind.METHOD)
        assertCompletion(completions, "run", CompletionItemKind.METHOD)
    }
    @Test
    fun multi_interface_document_facts_record_each_string_target() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                return proxy
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)
        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL && it.target == "java.lang.Runnable" })
        assertTrue(loads.any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL && it.target == "java.util.Comparator" })
        assertFalse(loads.any { it.target.contains(",") })
    }
    @Test
    fun multi_interface_missing_method_reports_invalid_member_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local missing = proxy.definitelyMissingProxyMethod
                return missing
            """.trimIndent()
        )

        assertInvalidMemberDiagnostic(harness, "definitelyMissingProxyMethod")
    }
    @Test
    fun multi_interface_partial_missing_interface_still_exposes_known_members() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "missing.DoesNotExist", {})
                local run = proxy.run
                return run, proxy
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertUnknownTargetDiagnostic(harness, "missing.DoesNotExist")
    }
    @Test
    fun colon_create_proxy_call_does_not_model_multi_interface_proxy() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava:createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                local compare = proxy.compare
                return proxy, run, compare
            """.trimIndent()
        )

        assertHoverDisplay(harness, "proxy", "unknown", occurrence = 2)
        assertHoverDisplay(harness, "run", "unknown", occurrence = 2)
        assertHoverDisplay(harness, "compare", "unknown", occurrence = 2)
        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL },
            "Colon createProxy must not record CREATE_PROXY_CALL facts."
        )
    }
    @Test
    fun real_dot_create_proxy_still_works_alongside_colon_guard() {
        // Guard regression: ensuring colon rejection does not break legitimate multi-interface modeling.
        val harness = jvmHarness(
            "main.lua" to """
                local colonProxy = luajava:createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                local compare = proxy.compare
                return colonProxy, run, compare
            """.trimIndent()
        )

        assertHoverDisplay(harness, "colonProxy", "unknown", occurrence = 2)
        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCallableMethodHover(harness, "compare", occurrence = 2)
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

    private fun assertCallableMethodHover(harness: WorkspaceSemanticHarness, needle: String, occurrence: Int = 1) {
        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, occurrence))
        )
        assertEquals(SymbolKind.METHOD, hover.symbol?.kind)
        assertCallable(hover.typeInfo?.displayName)
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


    /**
     * Local createProxy shadows return ordinary tables. Product hover collapses structural
     * `{ field: ... }` displays to coarse `table` via preferredHoverType (TASK-573 / HelperShadowing).
     */
    private fun assertLocalTableShadowHover(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ) {
        val display = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )?.typeInfo?.displayName.orEmpty()
        assertTrue(
            display == "table" || display.startsWith("{"),
            "Expected local table shadow hover for $needle (table or structural), got '$display'."
        )
        assertNotMultiInterfaceProxyDisplay(display, needle)
    }

    private fun assertNotMultiInterfaceProxySurface(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ) {
        val display = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )?.typeInfo?.displayName.orEmpty()
        assertNotMultiInterfaceProxyDisplay(display, needle)
    }

    private fun assertNotMultiInterfaceProxyDisplay(display: String, needle: String) {
        assertFalse(
            display.contains("Runnable", ignoreCase = false) ||
                display.contains("Comparator", ignoreCase = false) ||
                display.contains("JavaProxy", ignoreCase = false) ||
                display.contains("fun(") ||
                display.contains("&") ||
                display.contains("java.lang") ||
                display.contains("java.util"),
            "Local createProxy shadow $needle must not invent multi-interface proxy surface; got '$display'."
        )
    }

    private fun assertCallable(displayName: String?) {
        assertTrue(
            displayName.orEmpty().contains("fun("),
            "Expected callable type, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java type, got unknown.")
    }

    private fun assertCompletion(completions: List<CompletionItem>, label: String, kind: CompletionItemKind) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    private fun assertInvalidMemberDiagnostic(harness: WorkspaceSemanticHarness, member: String) {
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(
            diagnostics.containsInvalidMember(member),
            "Expected invalid multi-interface proxy member diagnostic for $member; actual diagnostics: ${diagnostics.map { it.message }}."
        )
    }

    private fun assertUnknownTargetDiagnostic(harness: WorkspaceSemanticHarness, target: String) {
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(
            diagnostics.containsTarget(target),
            "Expected unknown LuaJava createProxy target diagnostic for $target; actual diagnostics: ${diagnostics.map { it.message }}."
        )
    }

    private fun List<Diagnostic>.containsInvalidMember(member: String): Boolean {
        return any { diagnostic ->
            diagnostic.message.contains(member) &&
                (diagnostic.message.contains("member", ignoreCase = true) ||
                    diagnostic.message.contains("method", ignoreCase = true) ||
                    diagnostic.message.contains("field", ignoreCase = true) ||
                    diagnostic.message.contains("unknown", ignoreCase = true) ||
                    diagnostic.message.contains("unresolved", ignoreCase = true) ||
                    diagnostic.message.contains("not found", ignoreCase = true))
        }
    }

    private fun List<Diagnostic>.containsTarget(target: String): Boolean {
        return any { diagnostic ->
            diagnostic.message.contains(target) &&
                (diagnostic.message.contains("unknown", ignoreCase = true) ||
                    diagnostic.message.contains("not found", ignoreCase = true) ||
                    diagnostic.message.contains("unresolved", ignoreCase = true))
        }
    }

    private fun memberPosition(harness: WorkspaceSemanticHarness, memberAccess: String): Position {
        val dotIndex = memberAccess.indexOf('.')
        check(dotIndex >= 0) { "Expected member access with dot, got $memberAccess." }
        val source = harness.files.getValue(harness.path("main.lua"))
        val index = source.indexOf(memberAccess)
        check(index >= 0) { "Missing '$memberAccess' in main.lua." }
        return positionAt(source, index + dotIndex + 1)
    }

    private fun positionAt(source: String, index: Int): Position {
        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return Position(line, column)
    }
}
