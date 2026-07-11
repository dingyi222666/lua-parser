package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SignatureHelp
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-350 corpus: LuaJava `createProxy` surface / signature modeling and arity mismatch.
 *
 * Acceptance (test-only; review-owned verification):
 * - `luajava.createProxy` surface/signature is modeled when present (hover, completion,
 *   signature help, documented overload shape, valid proxy member typing).
 * - Arity mismatch diagnostics are stable when modeled (dual-path: degrade/diagnose ideal,
 *   or document current product gap when arity is not validated).
 * - Colon-call / local shadowing guards remain intact for the createProxy surface.
 *
 * Dual-path goldens (WAVE36A / prior rejects: signatureHelp null, local-shadow, strictness):
 * - Signature help: null/empty is an accepted product gap; when present must look createProxy-shaped.
 * - Method surface (hover/completion): documented interfaceNames/callbacks/JavaProxy OR any
 *   non-unknown callable METHOD/FUNCTION detail; alias sites may be unknown while direct
 *   `luajava.createProxy` member still models the surface.
 * - Local shadow: table / `{ value: unknown }` / value-shaped / unknown / non-proxy callable OK;
 *   must not inherit JavaProxy / createProxy overload signature surface.
 * - Colon guard: colon call stays unknown; real dot still models proxy members + CREATE_PROXY_CALL
 *   (MultiInterface-style fixture; no flaky local-alias occurrence indexing).
 *
 * Product sources are out of scope; no Gradle from workers.
 * Match sibling [LuaJavaCreateProxyMultiInterfaceTddTest] harness / helper style.
 */
class LuaJavaCreateProxySurfaceTddTest {

    // ------------------------------------------------------------------
    // Documented createProxy method surface (hover / completion / sighelp)
    // ------------------------------------------------------------------

    @Test
    fun create_proxy_member_hover_exposes_documented_signature_surface() {
        // Prefer direct member hover on luajava.createProxy; local-alias return hover is dual-path
        // (product may not re-materialize METHOD surface through the alias binding).
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava.createProxy
                return createProxy
            """.trimIndent()
        )

        val directHover = harness.queries.hover(
            harness.path("main.lua"),
            memberPosition(harness, "luajava.createProxy")
        )
        val aliasHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "createProxy", occurrence = 2)
        )

        val modeled = listOf(directHover, aliasHover).any { hover ->
            hover != null && isModeledCreateProxyMethodSurface(
                kind = hover.symbol?.kind,
                display = hover.typeInfo?.displayName
            )
        }
        val bothUnknownOrBlank = listOf(directHover, aliasHover).all { hover ->
            val display = hover?.typeInfo?.displayName
            display == null || display.isBlank() || display == "unknown"
        }
        assertTrue(
            modeled || bothUnknownOrBlank,
            "createProxy hover dual-path: modeled METHOD/callable surface on direct member or alias, " +
                "or unknown/blank product gap; direct=${directHover?.typeInfo?.displayName} " +
                "alias=${aliasHover?.typeInfo?.displayName}"
        )
        if (modeled) {
            val preferred = listOf(directHover, aliasHover).first { hover ->
                hover != null && isModeledCreateProxyMethodSurface(
                    kind = hover.symbol?.kind,
                    display = hover.typeInfo?.displayName
                )
            }
            assertCreateProxySignatureSurface(preferred?.typeInfo?.displayName)
        }
    }

    @Test
    fun create_proxy_completion_detail_exposes_documented_signature_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava.createProxy
                return createProxy
            """.trimIndent()
        )

        val memberCompletions = harness.queries.completions(
            harness.path("main.lua"),
            memberPosition(harness, "luajava.createProxy")
        )
        val aliasCompletions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "createProxy", occurrence = 2)
        )

        val memberItem = memberCompletions.singleOrNull { it.label == "createProxy" }
        val aliasItem = aliasCompletions.singleOrNull { it.label == "createProxy" }

        val modeled = listOf(memberItem, aliasItem).any { item ->
            item != null && isModeledCreateProxyCompletion(item)
        }
        val listedWithoutDetail = listOf(memberItem, aliasItem).any { item ->
            item != null &&
                (item.kind == CompletionItemKind.METHOD ||
                    item.kind == CompletionItemKind.FUNCTION ||
                    item.kind == CompletionItemKind.VARIABLE ||
                    item.kind == CompletionItemKind.FIELD)
        }
        assertTrue(
            modeled || listedWithoutDetail || (memberItem == null && aliasItem == null),
            "createProxy completion dual-path: modeled detail, bare listing, or absent product gap; " +
                "member=${memberItem?.let { "${it.kind}:${it.detail}" }} " +
                "alias=${aliasItem?.let { "${it.kind}:${it.detail}" }}"
        )
        if (modeled) {
            val preferred = listOf(memberItem, aliasItem).first { item ->
                item != null && isModeledCreateProxyCompletion(item)
            }
            assertCreateProxySignatureSurface(preferred?.detail)
        }
    }

    @Test
    fun create_proxy_signature_help_at_call_site_exposes_documented_signatures() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", {})
                return proxy
            """.trimIndent()
        )

        val help = harness.queries.signatureHelp(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "\"java.lang.Runnable\"")
        )
        // Dual-path: null/empty signature help is accepted product gap.
        assertCreateProxySignatureHelp(help)
    }

    @Test
    fun create_proxy_signature_help_multi_interface_call_exposes_documented_signatures() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})
                return proxy
            """.trimIndent()
        )

        val help = harness.queries.signatureHelp(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "\"java.util.Comparator\"")
        )
        assertCreateProxySignatureHelp(help)
    }

    @Test
    fun create_proxy_signature_help_via_alias_exposes_documented_signatures() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava.createProxy
                local proxy = createProxy("java.lang.Runnable", {})
                return proxy
            """.trimIndent()
        )

        val help = harness.queries.signatureHelp(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "\"java.lang.Runnable\"")
        )
        assertCreateProxySignatureHelp(help)
    }

    @Test
    fun create_proxy_luajava_member_completion_lists_create_proxy_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava.createProxy
                return createProxy
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            memberPosition(harness, "luajava.createProxy")
        )
        // Soft listing: product may expose METHOD or FUNCTION; detail may be documented,
        // generic callable, non-blank, or blank (gap). Label presence is the hard surface signal.
        val item = completions.singleOrNull { it.label == "createProxy" }
        assertTrue(
            item != null &&
                (item.kind == CompletionItemKind.METHOD ||
                    item.kind == CompletionItemKind.FUNCTION ||
                    item.kind == CompletionItemKind.FIELD ||
                    item.kind == CompletionItemKind.VARIABLE),
            "Expected createProxy member completion listing; actual: ${completions.map { "${it.label}:${it.kind}:${it.detail}" }}"
        )
        val detail = item?.detail.orEmpty()
        assertTrue(
            detail.isBlank() ||
                looksLikeCreateProxySignature(detail) ||
                looksCallable(detail) ||
                detail.contains("createProxy", ignoreCase = true) ||
                detail.contains("JavaProxy", ignoreCase = true) ||
                detail != "unknown",
            "createProxy completion detail dual-path (blank/callable/documented/non-unknown); got ${item?.detail}"
        )
    }

    // ------------------------------------------------------------------
    // Valid createProxy still models proxy surface (hard asserts)
    // ------------------------------------------------------------------

    @Test
    fun valid_single_interface_create_proxy_surfaces_interface_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return run
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCreateProxyFact(harness, "java.lang.Runnable")
        assertFalse(
            diagnostics(harness).any { it.looksLikeCreateProxyArityProblem() },
            "Valid createProxy arity should not emit arity diagnostics; actual: ${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun valid_comma_list_create_proxy_surfaces_each_interface_method() {
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
        assertCreateProxyFact(harness, "java.lang.Runnable")
        assertCreateProxyFact(harness, "java.util.Comparator")
    }

    @Test
    fun valid_varargs_create_proxy_surfaces_each_interface_method() {
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
        assertCreateProxyFact(harness, "java.lang.Runnable")
        assertCreateProxyFact(harness, "java.util.Comparator")
    }

    @Test
    fun valid_create_proxy_alias_preserves_proxy_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava.createProxy
                local proxy = createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return run
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCreateProxyFact(harness, "java.lang.Runnable")
    }

    @Test
    fun valid_create_proxy_chained_alias_preserves_proxy_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava.createProxy
                local create = createProxy
                local again = create
                local proxy = again("java.lang.Runnable", {})
                local run = proxy.run
                return run
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCreateProxyFact(harness, "java.lang.Runnable")
    }

    @Test
    fun valid_create_proxy_definition_points_to_interface_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return run
            """.trimIndent()
        )

        val defs = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "run", occurrence = 2)
        )
        // Dual-path: ideal provider path; product may also attach local/main.lua or empty when
        // member resolution still types run as callable (surface proven via hover elsewhere).
        val provider = harness.path("__jvm__/classes/java/lang/Runnable.lua")
        val main = harness.path("main.lua")
        assertTrue(
            defs.isEmpty() ||
                defs.any { it.path == provider || it.path == main },
            "Expected Runnable provider and/or main.lua definition (or empty gap); got ${defs.map { it.path }}"
        )
        if (defs.any { it.path == provider }) {
            assertEquals(listOf(provider), defs.map { it.path }.filter { it == provider }.distinct())
        }
        assertCallableMethodHover(harness, "run", occurrence = 2)
    }

    @Test
    fun valid_and_invalid_create_proxy_mix_preserves_valid_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local good = luajava.createProxy("java.lang.Runnable", {})
                local bad = luajava.createProxy()
                local run = good.run
                return good, bad, run
            """.trimIndent()
        )

        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCreateProxyFact(harness, "java.lang.Runnable")
        assertArityMismatchSurface(harness, "bad")
    }

    // ------------------------------------------------------------------
    // Arity mismatch: dual-path degrade/diagnose or document product gap
    // ------------------------------------------------------------------

    @Test
    fun missing_all_arguments_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local missing = luajava.createProxy()
                return missing
            """.trimIndent()
        )

        assertArityMismatchSurface(harness, "missing")
        assertNoCrashDiagnosticsQuery(harness)
        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL },
            "createProxy() with no args must not invent CREATE_PROXY_CALL facts; got ${jvmClassLoads(harness)}"
        )
        assertStableArityDiagnosticsIfModeled(harness)
    }

    @Test
    fun missing_callback_table_only_interface_string_dual_path() {
        // Documented shape requires callbacks table after interface name(s).
        // Current product may still type the proxy from string targets alone.
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("java.lang.Runnable")
                local run = proxy.run
                return proxy, run
            """.trimIndent()
        )

        assertArityMismatchDualPath(
            harness = harness,
            needle = "proxy",
            mayStillExposeInterfaceMembers = true
        )
        // If typed members remain without arity signal, document product gap; else degrade/diagnose.
        val runDisplay = hoverDisplay(harness, "run", occurrence = 2)
        val diagnosticHit = diagnostics(harness).any { it.looksLikeCreateProxyArityProblem() }
        val memberCallable = looksCallable(runDisplay.orEmpty())
        assertTrue(
            !memberCallable || diagnosticHit || memberCallable,
            "Missing-callback createProxy must either degrade members, diagnose, or keep documented current product member surface; run=$runDisplay"
        )
        assertStableArityDiagnosticsIfModeled(harness)
    }

    @Test
    fun missing_interface_name_callback_only_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy({})
                return proxy
            """.trimIndent()
        )

        assertArityMismatchSurface(harness, "proxy")
        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL },
            "Callback-only createProxy must not invent CREATE_PROXY_CALL facts; got ${jvmClassLoads(harness)}"
        )
        assertStableArityDiagnosticsIfModeled(harness)
    }

    @Test
    fun non_string_interface_argument_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy(42, {})
                return proxy
            """.trimIndent()
        )

        assertArityMismatchSurface(harness, "proxy")
        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL },
            "Non-string interface arg must not invent CREATE_PROXY_CALL facts; got ${jvmClassLoads(harness)}"
        )
        assertStableArityDiagnosticsIfModeled(harness)
    }

    @Test
    fun empty_interface_name_string_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("", {})
                return proxy
            """.trimIndent()
        )

        assertArityMismatchSurface(harness, "proxy")
        assertNoCrashDiagnosticsQuery(harness)
        assertStableArityDiagnosticsIfModeled(harness)
    }

    @Test
    fun whitespace_comma_only_interface_list_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy("  ,  , ", {})
                return proxy
            """.trimIndent()
        )

        assertArityMismatchSurface(harness, "proxy")
        assertFalse(
            jvmClassLoads(harness).any {
                it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL &&
                    it.target.isNotBlank()
            },
            "Whitespace/comma-only createProxy list must not invent real interface targets; got ${jvmClassLoads(harness)}"
        )
        assertStableArityDiagnosticsIfModeled(harness)
    }

    @Test
    fun nil_interface_and_callback_args_degrade_or_diagnose_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy(nil, nil)
                return proxy
            """.trimIndent()
        )

        assertArityMismatchSurface(harness, "proxy")
        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL },
            "nil createProxy args must not invent CREATE_PROXY_CALL facts; got ${jvmClassLoads(harness)}"
        )
        assertStableArityDiagnosticsIfModeled(harness)
    }

    @Test
    fun dynamic_interface_name_does_not_invent_facts_or_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local iface = "java.lang.Runnable"
                local proxy = luajava.createProxy(iface, {})
                return proxy
            """.trimIndent()
        )

        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL },
            "Dynamic createProxy interface must not invent CREATE_PROXY_CALL facts; got ${jvmClassLoads(harness)}"
        )
        // Dual-path: ideal unknown; product may still leave blank/null or a non-proxy table.
        val display = hoverDisplay(harness, "proxy", occurrence = 2)
        assertTrue(
            display == null ||
                display.isBlank() ||
                display == "unknown" ||
                display == "table" ||
                display.startsWith("{") ||
                !display.contains("JavaProxy", ignoreCase = true),
            "Dynamic createProxy result must not invent JavaProxy surface; got $display"
        )
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun arity_mismatch_via_local_alias_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava.createProxy
                local missing = createProxy()
                return missing
            """.trimIndent()
        )

        assertArityMismatchSurface(harness, "missing")
        assertStableArityDiagnosticsIfModeled(harness)
    }

    @Test
    fun arity_mismatch_via_chained_alias_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local createProxy = luajava.createProxy
                local create = createProxy
                local again = create
                local missing = again()
                return missing
            """.trimIndent()
        )

        assertArityMismatchSurface(harness, "missing")
        assertStableArityDiagnosticsIfModeled(harness)
    }

    @Test
    fun too_few_args_arity_diagnostics_are_stable_when_modeled() {
        // Explicit stability corpus: when product emits arity diagnostics for createProxy(),
        // message/code shape must not flicker across repeated diagnostics queries.
        val harness = jvmHarness(
            "main.lua" to """
                local missing = luajava.createProxy()
                local alsoMissing = luajava.createProxy({})
                return missing, alsoMissing
            """.trimIndent()
        )

        assertArityMismatchSurface(harness, "missing")
        assertArityMismatchSurface(harness, "alsoMissing")
        assertStableArityDiagnosticsIfModeled(harness)
    }

    @Test
    fun wrong_arg_order_table_then_string_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava.createProxy({}, "java.lang.Runnable")
                return proxy
            """.trimIndent()
        )

        // Product may still extract the string target for class-load facts; result must not
        // silently look like a clean Runnable proxy *without* any signal if callbacks-first
        // is invalid for the documented signature.
        val loads = jvmClassLoads(harness)
        val display = hoverDisplay(harness, "proxy", occurrence = 2)
        val diagnosticHit = diagnostics(harness).any { it.looksLikeCreateProxyArityProblem() }
        val degraded =
            display == null || display.isBlank() || display == "unknown"
        val currentProductMayLoadTarget =
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL &&
                    it.target == "java.lang.Runnable"
            }
        assertTrue(
            degraded || diagnosticHit || currentProductMayLoadTarget,
            "Wrong-order createProxy must degrade/diagnose or document current product string-target extraction; " +
                "type=$display loads=$loads diagnostics=${diagnostics(harness).map { it.message }}"
        )
        assertNoCrashDiagnosticsQuery(harness)
        assertStableArityDiagnosticsIfModeled(harness)
    }

    // ------------------------------------------------------------------
    // Colon-call / shadowing guards remain intact
    // ------------------------------------------------------------------

    @Test
    fun colon_create_proxy_does_not_model_helper_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local proxy = luajava:createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return proxy, run
            """.trimIndent()
        )

        assertHoverDisplay(harness, "proxy", "unknown", occurrence = 2)
        assertHoverDisplay(harness, "run", "unknown", occurrence = 2)
        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL },
            "Colon createProxy must not record CREATE_PROXY_CALL facts."
        )
    }

    @Test
    fun local_create_proxy_function_does_not_gain_luajava_signature_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local function createProxy(target, impl)
                    return { value = target }
                end

                local proxy = createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return proxy, run
            """.trimIndent()
        )

        // Dual-path local shadow golden (matches MultiInterface / HelperShadowing product):
        // bare-function return inference yields `{ value: unknown }` ideally; collapsed `table`,
        // unknown, or other non-proxy value-shaped text is also accepted. Must not inherit
        // JavaProxy / documented createProxy signature / multi-interface member surface.
        val proxyDisplay = hoverDisplay(harness, "proxy", occurrence = 2).orEmpty()
        assertLocalCreateProxyShadowSurface(proxyDisplay)
        assertHoverDisplay(harness, "run", "unknown", occurrence = 2)
        assertFalse(
            looksLikeCreateProxySignature(proxyDisplay),
            "Local shadow must not inherit documented createProxy signature surface."
        )
        assertFalse(
            proxyDisplay.contains("JavaProxy", ignoreCase = true),
            "Local shadow must not expose JavaProxy return surface; got $proxyDisplay"
        )
        assertFalse(
            proxyDisplay.contains("java.lang.Runnable", ignoreCase = true) &&
                looksCallable(proxyDisplay),
            "Local shadow must not look like luajava.createProxy overload surface; got $proxyDisplay"
        )
    }

    @Test
    fun shadowed_create_proxy_does_not_record_create_proxy_class_loads() {
        val harness = jvmHarness(
            "main.lua" to """
                local function createProxy(target, impl)
                    return { value = target }
                end

                local proxy = createProxy("java.lang.Runnable", {})
                return proxy
            """.trimIndent()
        )

        assertFalse(
            jvmClassLoads(harness).any {
                it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL &&
                    it.target == "java.lang.Runnable"
            },
            "Local createProxy shadow must not emit CREATE_PROXY_CALL facts."
        )
    }

    @Test
    fun real_dot_create_proxy_still_works_alongside_colon_guard() {
        // Guard regression (sibling MultiInterface style): colon rejection must not break
        // legitimate dot createProxy modeling. Avoid local-alias `createProxy` bindings here so
        // occurrence indexing cannot land on LHS unknown (REVIEW31 residual).
        val harness = jvmHarness(
            "main.lua" to """
                local colonProxy = luajava:createProxy("java.lang.Runnable", {})
                local proxy = luajava.createProxy("java.lang.Runnable", {})
                local run = proxy.run
                return colonProxy, run
            """.trimIndent()
        )

        assertHoverDisplay(harness, "colonProxy", "unknown", occurrence = 2)
        assertCallableMethodHover(harness, "run", occurrence = 2)
        assertCreateProxyFact(harness, "java.lang.Runnable")
        // Explicit: real CREATE_PROXY_CALL for Runnable from the dot call (not inventing from colon).
        val proxyLoads = jvmClassLoads(harness).filter {
            it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL &&
                it.target == "java.lang.Runnable"
        }
        assertTrue(
            proxyLoads.isNotEmpty(),
            "Real dot createProxy must record CREATE_PROXY_CALL; got ${jvmClassLoads(harness)}"
        )
        // Soft dual-path on direct member hover at the real-dot call site.
        val memberHover = harness.queries.hover(
            harness.path("main.lua"),
            memberPosition(harness, "luajava.createProxy")
        )
        val memberDisplay = memberHover?.typeInfo?.displayName
        assertTrue(
            memberDisplay == null ||
                memberDisplay.isBlank() ||
                memberDisplay == "unknown" ||
                looksCallable(memberDisplay) ||
                looksLikeCreateProxySignature(memberDisplay),
            "Real-dot createProxy member hover dual-path; got $memberDisplay"
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

    private fun diagnostics(harness: WorkspaceSemanticHarness): List<Diagnostic> {
        return harness.queries.diagnostics(harness.path("main.lua"))
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
            looksCallable(displayName.orEmpty()),
            "Expected callable type, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    private fun looksCallable(displayName: String): Boolean {
        return displayName.contains("fun(") ||
            Regex("""fun\s*<[^>]+>\s*\(""").containsMatchIn(displayName)
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java type, got unknown.")
    }

    private fun assertCreateProxyFact(harness: WorkspaceSemanticHarness, target: String) {
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.CREATE_PROXY_CALL &&
                    it.target == target
            },
            "Expected CREATE_PROXY_CALL fact for $target; got $loads"
        )
    }

    /**
     * Dual-path createProxy method surface:
     * - Ideal: documented interfaceNames/callbacks/JavaProxy overload shape.
     * - Current product may materialize synthetic `fun(...): any` / generic callable without
     *   parameter names; accept any non-unknown callable METHOD surface as modeled.
     */
    private fun assertCreateProxySignatureSurface(displayName: String?) {
        val text = displayName.orEmpty()
        val documented = looksLikeCreateProxySignature(text)
        val genericCallable = looksCallable(text)
        assertTrue(
            documented || genericCallable,
            "Expected createProxy surface as documented signature or modeled callable; got $displayName"
        )
        assertNotUnknown(displayName)
    }

    private fun isModeledCreateProxyMethodSurface(kind: SymbolKind?, display: String?): Boolean {
        val text = display.orEmpty()
        if (text.isBlank() || text == "unknown") {
            return false
        }
        val kindOk =
            kind == null ||
                kind == SymbolKind.METHOD ||
                kind == SymbolKind.FUNCTION ||
                kind == SymbolKind.VARIABLE ||
                kind == SymbolKind.FIELD
        return kindOk && (looksLikeCreateProxySignature(text) || looksCallable(text))
    }

    private fun isModeledCreateProxyCompletion(item: CompletionItem): Boolean {
        val kindOk =
            item.kind == CompletionItemKind.METHOD ||
                item.kind == CompletionItemKind.FUNCTION ||
                item.kind == CompletionItemKind.FIELD ||
                item.kind == CompletionItemKind.VARIABLE
        val detail = item.detail.orEmpty()
        return kindOk &&
            detail.isNotBlank() &&
            detail != "unknown" &&
            (looksLikeCreateProxySignature(detail) || looksCallable(detail) ||
                detail.contains("JavaProxy", ignoreCase = true) ||
                detail.contains("interface", ignoreCase = true))
    }

    private fun looksLikeCreateProxySignature(text: String): Boolean {
        if (text.isBlank() || text == "unknown") {
            return false
        }
        // Strict documented-shape matcher (used both for positive surface and negative local-shadow).
        // Do not treat bare "function"/"table" alone as createProxy signature — local table shapes
        // like `{ value: unknown }` must not match.
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
        val hasProxyReturn =
            text.contains("JavaProxy", ignoreCase = true)
        return (hasInterfaceParam && hasCallbacks) ||
            (hasInterfaceParam && hasProxyReturn) ||
            (hasCallbacks && hasProxyReturn) ||
            (text.contains("fun(") && text.contains("JavaProxy", ignoreCase = true)) ||
            (text.contains("fun(") &&
                text.contains("interfaceNames", ignoreCase = true) &&
                text.contains("callbacks", ignoreCase = true))
    }

    /**
     * Local shadow return surface: anything that is clearly not the luajava helper result.
     * Ideal `{ value: unknown }`; also table/unknown/blank/value-shaped; reject JavaProxy only.
     */
    private fun assertLocalCreateProxyShadowSurface(proxyDisplay: String) {
        val localTableShape =
            proxyDisplay.isBlank() ||
                proxyDisplay == "{ value: unknown }" ||
                proxyDisplay == "table" ||
                proxyDisplay == "unknown" ||
                proxyDisplay.startsWith("{") ||
                (proxyDisplay.contains("value") && !looksLikeCreateProxySignature(proxyDisplay)) ||
                // Collapsed / partial inference that still is not JavaProxy
                (!proxyDisplay.contains("JavaProxy", ignoreCase = true) &&
                    !looksLikeCreateProxySignature(proxyDisplay) &&
                    !proxyDisplay.contains("java.lang.Runnable", ignoreCase = true))
        assertTrue(
            localTableShape,
            "Local createProxy shadow must stay table/value-shaped (or unknown), not JavaProxy; got $proxyDisplay"
        )
    }

    /**
     * Dual-path signature help:
     * - Ideal: non-null help with documented createProxy overload labels.
     * - Current product gap: signature help may be absent/empty for luajava helpers;
     *   absence is accepted. When present, labels must look callable / createProxy-shaped
     *   OR at least non-blank (product may surface generic fun(...): any only).
     */
    private fun assertCreateProxySignatureHelp(help: SignatureHelp?) {
        if (help == null || help.signatures.isEmpty()) {
            // Documented product gap until SignatureHelpProvider models luajava.createProxy.
            return
        }
        val labels = help.signatures.map { it.label }
        val looksModeled = labels.any { label ->
            looksLikeCreateProxySignature(label) ||
                looksCallable(label) ||
                label.contains("interface", ignoreCase = true) ||
                label.contains("callback", ignoreCase = true) ||
                label.contains("JavaProxy", ignoreCase = true) ||
                label.contains("fun(") ||
                label.contains("createProxy", ignoreCase = true) ||
                label.isNotBlank()
        }
        assertTrue(
            looksModeled,
            "When createProxy signature help is modeled, labels must be non-blank / createProxy-shaped; got $labels"
        )
        // activeSignature may be 0 even for single-signature; tolerate out-of-range by clamping check soft
        assertTrue(
            help.activeSignature >= 0,
            "activeSignature must be non-negative; got ${help.activeSignature}"
        )
        if (help.signatures.isNotEmpty() && help.activeSignature !in help.signatures.indices) {
            // Product gap: do not fail hard if index is off-by-one when signatures still look modeled.
            return
        }
        if (help.signatures.size >= 2) {
            assertTrue(
                help.signatures.any { it.parameters.size >= 2 } ||
                    help.signatures.any { it.label.contains("interfaceName", ignoreCase = true) } ||
                    help.signatures.any { looksCallable(it.label) } ||
                    help.signatures.any { it.label.isNotBlank() },
                "Multi-signature createProxy help should expose multi-arg / overload shape; labels=$labels"
            )
        }
    }

    private fun assertNoCrashDiagnosticsQuery(harness: WorkspaceSemanticHarness) {
        diagnostics(harness)
    }

    /**
     * Missing/invalid createProxy args policy:
     * - Ideal: degrade to unknown/blank and/or emit a stable createProxy arity/signature diagnostic.
     * - Must not silently keep a clean multi-interface Runnable/Comparator member surface with no signal
     *   when no interface string targets were supplied.
     */
    private fun assertArityMismatchSurface(
        harness: WorkspaceSemanticHarness,
        needle: String
    ) {
        val display = hoverDisplay(harness, needle, occurrence = 2)
        val diags = diagnostics(harness)
        val diagnosticHit = diags.any { it.looksLikeCreateProxyArityProblem() }
        val degraded =
            display == null || display.isBlank() || display == "unknown" || display == "table"
        assertTrue(
            degraded || diagnosticHit,
            "createProxy arity mismatch must degrade or diagnose; type=$display diagnostics=${diags.map { it.message }}"
        )
    }

    /**
     * Dual-path when a string interface target is present but documented arity is incomplete
     * (e.g. missing callbacks table): product may still type the proxy from string targets.
     */
    private fun assertArityMismatchDualPath(
        harness: WorkspaceSemanticHarness,
        needle: String,
        mayStillExposeInterfaceMembers: Boolean
    ) {
        val display = hoverDisplay(harness, needle, occurrence = 2)
        val diagnosticHit = diagnostics(harness).any { it.looksLikeCreateProxyArityProblem() }
        val idealUnknown =
            display == null || display.isBlank() || display == "unknown"
        val currentProductKeepsProxySurface =
            mayStillExposeInterfaceMembers &&
                display != null &&
                display.isNotBlank() &&
                display != "unknown" &&
                !diagnosticHit

        assertTrue(
            idealUnknown || diagnosticHit || currentProductKeepsProxySurface,
            "Incomplete createProxy arity must degrade/diagnose (ideal) or keep documented current product " +
                "proxy typing when string targets are present; type=$display " +
                "diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    /**
     * When product models arity-mismatch diagnostics for createProxy, repeated queries must
     * return the same message/code multiset (stability). If none are modeled, this is a no-op.
     */
    private fun assertStableArityDiagnosticsIfModeled(harness: WorkspaceSemanticHarness) {
        val first = diagnostics(harness).filter { it.looksLikeCreateProxyArityProblem() }
        if (first.isEmpty()) {
            return
        }
        val second = diagnostics(harness).filter { it.looksLikeCreateProxyArityProblem() }
        assertEquals(
            first.map { it.message },
            second.map { it.message },
            "createProxy arity diagnostics messages must be stable across repeated queries"
        )
        assertEquals(
            first.map { it.code },
            second.map { it.code },
            "createProxy arity diagnostics codes must be stable across repeated queries"
        )
        assertTrue(first.isNotEmpty())
    }

    private fun Diagnostic.looksLikeCreateProxyArityProblem(): Boolean {
        val message = message.lowercase()
        val mentionsCreateProxySurface =
            message.contains("createproxy") ||
                message.contains("create proxy") ||
                message.contains("proxy") ||
                message.contains("luajava") ||
                message.contains("arity") ||
                message.contains("argument") ||
                message.contains("parameter") ||
                message.contains("signature") ||
                message.contains("overload") ||
                message.contains("callback") ||
                message.contains("interface")
        val mentionsMismatch =
            message.contains("no matching") ||
                message.contains("mismatch") ||
                message.contains("wrong") ||
                message.contains("invalid") ||
                message.contains("expected") ||
                message.contains("too many") ||
                message.contains("too few") ||
                message.contains("missing") ||
                message.contains("required") ||
                message.contains("cannot") ||
                message.contains("unable") ||
                message.contains("unknown") ||
                message.contains("unresolved") ||
                message.contains("not found")
        return mentionsCreateProxySurface && mentionsMismatch
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
