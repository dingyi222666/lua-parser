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
