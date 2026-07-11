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
 * TASK-212 corpus: LuaJava `createProxy` multi-interface success and missing-method diagnostics.
 *
 * Acceptance (test-only; review-owned verification):
 * - Multi-interface fixtures for success (varargs + comma-separated interface lists).
 * - Missing-method diagnostics on multi-interface proxies.
 * - Colon-call / local shadowing guards remain intact for `createProxy`.
 *
 * Product sources are out of scope; no Gradle from workers.
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
    fun multi_interface_comma_list_completion_includes_each_interface_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable, java.util.Comparator", {})
                local run = proxy.run
                return run
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            memberPosition(harness, "proxy.run")
        )

        assertCompletion(completions, "run", CompletionItemKind.METHOD)
        assertCompletion(completions, "compare", CompletionItemKind.METHOD)
    }

    @Test
    fun multi_interface_definition_points_to_provider_for_each_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                local compare = proxy.compare
                return run, compare
            """.trimIndent()
        )

        val runDefs = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "run", occurrence = 2)
        )
        val compareDefs = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "compare", occurrence = 2)
        )

        assertEquals(listOf(harness.path("__jvm__/classes/java/lang/Runnable.lua")), runDefs.map { it.path })
        assertEquals(listOf(harness.path("__jvm__/classes/java/util/Comparator.lua")), compareDefs.map { it.path })
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
    fun multi_interface_comma_list_document_facts_split_targets() {
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
    }

    @Test
    fun multi_interface_alias_preserves_each_interface_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava.createProxy
                local proxy = createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                local compare = proxy.compare
                return run, compare
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCallableMethodHover(harness, "compare", occurrence = 2)
    }

    @Test
    fun multi_interface_chained_alias_preserves_each_interface_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava.createProxy
                local create = createProxy
                local again = create
                local proxy = again("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                local compare = proxy.compare
                return run, compare
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCallableMethodHover(harness, "compare", occurrence = 2)
    }

    @Test
    fun multi_interface_three_targets_expose_all_members() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy(
                    "java.lang.Runnable",
                    "java.util.Comparator",
                    "java.util.concurrent.Callable",
                    {}
                )
                local run = proxy.run
                local compare = proxy.compare
                local call = proxy.call
                return run, compare, call
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCallableMethodHover(harness, "compare", occurrence = 2)
        assertCallableMethodHover(harness, "call", occurrence = 2)
    }

    @Test
    fun multi_interface_success_with_implemented_table_does_not_require_member_diagnostics() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {
                    run = function() end,
                    compare = function(a, b) return 0 end
                })
                local run = proxy.run
                local compare = proxy.compare
                return run, compare
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCallableMethodHover(harness, "compare", occurrence = 2)
        assertFalse(
            harness.queries.diagnostics(harness.path("main.lua")).containsInvalidMember("run"),
            "Valid multi-interface createProxy should not report invalid-member diagnostics for interface methods."
        )
        assertFalse(
            harness.queries.diagnostics(harness.path("main.lua")).containsInvalidMember("compare"),
            "Valid multi-interface createProxy should not report invalid-member diagnostics for interface methods."
        )
    }

    // ------------------------------------------------------------------
    // Missing-method diagnostics on multi-interface proxies
    // ------------------------------------------------------------------

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
    fun multi_interface_missing_method_call_reports_invalid_member_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local value = proxy.missingProxyMethod()
                return value
            """.trimIndent()
        )

        assertInvalidMemberDiagnostic(harness, "missingProxyMethod")
    }

    @Test
    fun multi_interface_comma_list_missing_method_reports_invalid_member_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable, java.util.Comparator", {})
                local value = proxy.notAnInterfaceMethod
                return value
            """.trimIndent()
        )

        assertInvalidMemberDiagnostic(harness, "notAnInterfaceMethod")
    }

    @Test
    fun multi_interface_missing_method_coexists_with_valid_members() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                local missing = proxy.missingProxyMethod
                local compare = proxy.compare
                return run, missing, compare
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCallableMethodHover(harness, "compare", occurrence = 2)
        assertInvalidMemberDiagnostic(harness, "missingProxyMethod")
    }

    @Test
    fun multi_interface_missing_method_hover_stays_unknown_without_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local missing = proxy.missingProxyMethod
                return missing
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "missing", occurrence = 2)
        )
        // Missing member may be unknown / absent; pipeline must not crash.
        assertTrue(
            hover == null || hover.typeInfo?.displayName == "unknown" || hover.typeInfo?.displayName.isNullOrBlank(),
            "Expected missing multi-interface proxy member hover to stay unknown/absent; got ${hover?.typeInfo?.displayName}."
        )
        assertInvalidMemberDiagnostic(harness, "missingProxyMethod")
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
    fun multi_interface_all_missing_interfaces_report_diagnostics() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("missing.FirstIface", "missing.SecondIface", {})
                return proxy
            """.trimIndent()
        )

        assertUnknownTargetDiagnostic(harness, "missing.FirstIface")
        assertUnknownTargetDiagnostic(harness, "missing.SecondIface")
    }

    @Test
    fun multi_interface_comma_list_partial_missing_interface_reports_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable, missing.DoesNotExist", {})
                local run = proxy.run
                return run, proxy
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertUnknownTargetDiagnostic(harness, "missing.DoesNotExist")
    }

    // ------------------------------------------------------------------
    // Colon-call / shadowing guards remain intact
    // ------------------------------------------------------------------

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
    fun colon_create_proxy_alias_does_not_model_multi_interface_proxy() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava:createProxy
                local proxy = createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                return proxy, run
            """.trimIndent()
        )

        assertHoverDisplay(harness, "proxy", "unknown", occurrence = 2)
        assertHoverDisplay(harness, "run", "unknown", occurrence = 2)
    }

    @Test
    fun local_create_proxy_function_does_not_gain_multi_interface_proxy_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local function createProxy(target, other, impl)
                    return { value = target }
                end

                local proxy = createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                local compare = proxy.compare
                return proxy, run, compare
            """.trimIndent()
        )

        // REVIEW22B / product (TASK-138 bare-function return inference + TypeResolver):
        // local function createProxy(...) return { value = target } end infers table-shape
        // hover `{ value: unknown }` (same as LuaJavaHelperShadowingTddTest). Guard still holds:
        // run/compare stay unknown; no multi-iface proxy surface.
        assertHoverDisplay(harness, "proxy", "{ value: unknown }", occurrence = 2)
        assertHoverDisplay(harness, "run", "unknown", occurrence = 2)
        assertHoverDisplay(harness, "compare", "unknown", occurrence = 2)
    }

    @Test
    fun local_luajava_create_proxy_member_does_not_gain_multi_interface_proxy_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    createProxy = function(target, other, impl)
                        return { value = target }
                    end
                }

                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                local run = proxy.run
                local compare = proxy.compare
                return proxy, run, compare
            """.trimIndent()
        )

        // REVIEW24: local table-member shadow returns table-shape hover `{ value: unknown }`
        // (product models the anonymous local createProxy return table). Guard still holds:
        // no multi-interface proxy surface on run/compare.
        assertHoverDisplay(harness, "proxy", "{ value: unknown }", occurrence = 2)
        assertHoverDisplay(harness, "run", "unknown", occurrence = 2)
        assertHoverDisplay(harness, "compare", "unknown", occurrence = 2)
    }

    @Test
    fun shadowed_create_proxy_does_not_record_create_proxy_class_loads() {
        val harness = jvmHarness(
            "main.lua" to """
                local function createProxy(target, other, impl)
                    return { value = target }
                end

                local proxy = createProxy("java.lang.Runnable", "java.util.Comparator", {})
                return proxy
            """.trimIndent()
        )

        assertFalse(
            jvmClassLoads(harness).any {
                it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL &&
                    (it.target == "java.lang.Runnable" || it.target == "java.util.Comparator")
            },
            "Local createProxy shadow must not emit CREATE_PROXY_CALL facts for interface targets."
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
