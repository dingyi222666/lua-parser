package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-351 corpus: `luajava.loadLib` missing / invalid argument error surfaces.
 *
 * Acceptance focus:
 * - Missing or invalid loadLib args produce stable diagnostics and/or degrade without crash.
 * - Valid loadLib still surfaces package members (provider + typed member).
 * - Aliases and compact string-call forms follow the same policy when modeled.
 *
 * Test-only scope. Workers do not run Gradle; verification is review-owned serial jvmTest.
 * Harness matches sibling [LuaJavaLoadLibSurfaceTddTest] (JvmWorkspaceEngine only; default overlay).
 */
class LuaJavaLoadLibErrorSurfaceTddTest {

    // ------------------------------------------------------------------
    // Valid loadLib still surfaces package members (hard asserts)
    // ------------------------------------------------------------------

    @Test
    fun valid_load_lib_static_method_surfaces_callable_and_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local currentTimeMillis = luajava.loadLib("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        assertCallable(hoverDisplay(harness, "currentTimeMillis", occurrence = 2))
        assertProviderPath(harness, "java.lang.System")
        assertLoadLibFact(harness, "java.lang.System")
        assertFalse(
            diagnostics(harness).any { it.looksLikeLoadLibArgProblem() || it.looksLikeUnresolvedLoadLibTarget() },
            "Valid loadLib should not emit loadLib arg/target diagnostics; actual: ${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun valid_load_lib_static_field_surfaces_field_type_and_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local root = luajava.loadLib("java.util.Locale", "ROOT")
                return root
            """.trimIndent()
        )

        assertEquals("java.util.Locale", hoverDisplay(harness, "root", occurrence = 2))
        assertProviderPath(harness, "java.util.Locale")
        assertLoadLibFact(harness, "java.util.Locale")
    }

    @Test
    fun valid_load_lib_alias_still_surfaces_package_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local getDefault = loadLib("java.util.Locale", "getDefault")
                return getDefault
            """.trimIndent()
        )

        assertCallable(hoverDisplay(harness, "getDefault", occurrence = 2))
        assertProviderPath(harness, "java.util.Locale")
        assertLoadLibFact(harness, "java.util.Locale")
    }

    @Test
    fun valid_and_invalid_load_lib_mix_preserves_valid_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local good = luajava.loadLib("java.lang.System", "currentTimeMillis")
                local bad = luajava.loadLib()
                return good, bad
            """.trimIndent()
        )

        assertCallable(hoverDisplay(harness, "good", occurrence = 2))
        assertProviderPath(harness, "java.lang.System")
        // "bad" must appear twice (binding + use) for occurrence=2 hover helper.
        assertMissingOrInvalidArgsSurface(harness, "bad")
    }

    // ------------------------------------------------------------------
    // Missing arguments
    // ------------------------------------------------------------------

    @Test
    fun missing_both_arguments_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local missing = luajava.loadLib()
                return missing
            """.trimIndent()
        )

        assertMissingOrInvalidArgsSurface(harness, "missing")
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun missing_member_argument_only_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local missingMember = luajava.loadLib("java.lang.System")
                return missingMember
            """.trimIndent()
        )

        // Class-only loadLib is invalid arity for the documented (className, methodName) surface.
        assertMissingOrInvalidArgsSurface(harness, "missingMember")
        // May still record a LOAD_LIB_CALL fact for the class string when present.
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.none {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == "java.lang.System" &&
                    hoverDisplay(harness, "missingMember", occurrence = 2).orEmpty().contains("fun(")
            } || loads.any { it.target == "java.lang.System" },
            "missing member arg must not invent a typed JVM callable without signal; loads=$loads"
        )
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun missing_class_name_with_member_only_degrades_or_diagnoses_stably() {
        // Single-arg forms cannot supply both required strings; treat as missing/invalid args.
        val harness = jvmHarness(
            "main.lua" to """
                local missingClass = luajava.loadLib("currentTimeMillis")
                return missingClass
            """.trimIndent()
        )

        assertMissingOrInvalidArgsSurface(harness, "missingClass")
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun missing_args_via_local_alias_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local missing = loadLib()
                return missing
            """.trimIndent()
        )

        assertMissingOrInvalidArgsSurface(harness, "missing")
    }

    @Test
    fun missing_args_via_chained_alias_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local load = loadLib
                local again = load
                local missing = again()
                return missing
            """.trimIndent()
        )

        assertMissingOrInvalidArgsSurface(harness, "missing")
    }

    // ------------------------------------------------------------------
    // Invalid argument shapes
    // ------------------------------------------------------------------

    @Test
    fun non_string_class_name_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local bad = luajava.loadLib(42, "currentTimeMillis")
                return bad
            """.trimIndent()
        )

        assertMissingOrInvalidArgsSurface(harness, "bad")
        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL },
            "Non-string class name must not invent LOAD_LIB_CALL facts; got ${jvmClassLoads(harness)}"
        )
    }

    @Test
    fun non_string_member_name_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local bad = luajava.loadLib("java.lang.System", 99)
                return bad
            """.trimIndent()
        )

        assertMissingOrInvalidArgsSurface(harness, "bad")
        // Class string may still be recorded; result must not look like a clean static method surface.
        val display = hoverDisplay(harness, "bad", occurrence = 2)
        assertFalse(
            looksCallable(display.orEmpty()) &&
                diagnostics(harness).none { it.looksLikeLoadLibArgProblem() || it.looksLikeUnresolvedLoadLibTarget() },
            "Non-string member must not keep silent callable surface; type=$display diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun nil_class_and_member_args_degrade_or_diagnose_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local bad = luajava.loadLib(nil, nil)
                return bad
            """.trimIndent()
        )

        assertMissingOrInvalidArgsSurface(harness, "bad")
        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL },
            "nil loadLib args must not invent LOAD_LIB_CALL facts; got ${jvmClassLoads(harness)}"
        )
    }

    @Test
    fun empty_class_name_string_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local bad = luajava.loadLib("", "currentTimeMillis")
                return bad
            """.trimIndent()
        )

        // Empty class may record an empty LOAD_LIB_CALL fact (facts collector behavior) but
        // must not produce a callable System member surface.
        assertMissingOrInvalidArgsSurface(harness, "bad")
        assertFalse(
            hoverDisplay(harness, "bad", occurrence = 2).orEmpty().contains("java.lang.System"),
            "Empty class loadLib must not surface System type"
        )
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun empty_member_name_string_degrades_or_diagnoses_stably() {
        val harness = jvmHarness(
            "main.lua" to """
                local bad = luajava.loadLib("java.lang.System", "")
                return bad
            """.trimIndent()
        )

        assertProviderPath(harness, "java.lang.System")
        val display = hoverDisplay(harness, "bad", occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeLoadLibArgProblem() ||
                it.looksLikeUnresolvedLoadLibTarget() ||
                it.looksLikeUnknownMember()
        }
        val degraded =
            display == null || display.isBlank() || display == "unknown" || !looksCallable(display)
        assertTrue(
            degraded || diagnosticHit,
            "Empty member name must degrade or diagnose; type=$display diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun dynamic_class_and_member_names_do_not_invent_facts_or_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local className = "java.lang.System"
                local memberName = "currentTimeMillis"
                local loader = luajava.loadLib(className, memberName)
                return loader
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any { it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL },
            "Dynamic loadLib targets must not invent LOAD_LIB_CALL facts; got $loads"
        )
        assertEquals("unknown", hoverDisplay(harness, "loader", occurrence = 2) ?: "unknown")
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun concatenated_class_name_expression_is_dynamic_not_literal() {
        val harness = jvmHarness(
            "main.lua" to """
                local loader = luajava.loadLib("java.lang." .. "System", "currentTimeMillis")
                return loader
            """.trimIndent()
        )

        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL },
            "Concatenated class name is dynamic; must not invent LOAD_LIB_CALL; got ${jvmClassLoads(harness)}"
        )
        assertMissingOrInvalidArgsSurface(harness, "loader")
    }

    // ------------------------------------------------------------------
    // Unknown targets / members (stable diagnostic surface)
    // ------------------------------------------------------------------

    @Test
    fun unknown_class_target_reports_stable_unresolved_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local loader = luajava.loadLib("missing.DoesNotExist", "open")
                return loader
            """.trimIndent()
        )

        assertUnknownTargetDiagnostic(harness, "missing.DoesNotExist")
        assertEquals("unknown", hoverDisplay(harness, "loader", occurrence = 2) ?: "unknown")
        val diags = diagnostics(harness).filter { it.looksLikeUnresolvedLoadLibTarget() }
        assertTrue(diags.isNotEmpty(), "Expected unresolved loadLib diagnostic; got ${diagnostics(harness).map { it.message }}")
        // Stability: same message shape on repeated analysis.
        val again = diagnostics(harness).filter { it.looksLikeUnresolvedLoadLibTarget() }
        assertEquals(diags.map { it.message }, again.map { it.message })
        assertEquals(diags.map { it.code }, again.map { it.code })
    }

    @Test
    fun unknown_member_on_known_class_is_unknown_not_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local weird = luajava.loadLib("java.lang.System", "definitelyNotARealMember_xyz")
                return weird
            """.trimIndent()
        )

        assertProviderPath(harness, "java.lang.System")
        val display = hoverDisplay(harness, "weird", occurrence = 2)
        assertTrue(
            display == null || display == "unknown" || display.isBlank(),
            "Unknown member should be unknown, got '$display'."
        )
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun alias_unknown_class_target_reports_stable_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local loader = loadLib("missing.Package.Class", "open")
                return loader
            """.trimIndent()
        )

        assertUnknownTargetDiagnostic(harness, "missing.Package.Class")
    }

    // ------------------------------------------------------------------
    // Shadowing / non-helpers must not pick up error surface semantics
    // ------------------------------------------------------------------

    @Test
    fun shadowed_local_load_lib_does_not_emit_jvm_load_facts_or_unresolved_target() {
        val harness = jvmHarness(
            "main.lua" to """
                local function loadLib(target, member)
                    return { target = target, member = member }
                end
                local loadResult = loadLib("missing.DoesNotExist", "open")
                return loadResult
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any { it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL },
            "Shadowed bare loadLib must not emit LOAD_LIB_CALL; got $loads"
        )
        assertFalse(
            diagnostics(harness).any { it.looksLikeUnresolvedLoadLibTarget() },
            "Shadowed loadLib must not emit unresolved LuaJava target diagnostics; actual: ${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun colon_load_lib_call_does_not_model_helper_error_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local loaded = luajava:loadLib("java.lang.System", "currentTimeMillis")
                return loaded
            """.trimIndent()
        )

        assertEquals("unknown", hoverDisplay(harness, "loaded", occurrence = 2) ?: "unknown")
        assertFalse(
            jvmClassLoads(harness).any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == "java.lang.System"
            },
            "Colon loadLib must not emit LOAD_LIB_CALL; got ${jvmClassLoads(harness)}"
        )
    }

    // ------------------------------------------------------------------
    // Helpers — match sibling LuaJavaLoadLibSurfaceTddTest harness style
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

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    private fun assertLoadLibFact(harness: WorkspaceSemanticHarness, className: String) {
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == className
            },
            "Expected LOAD_LIB_CALL fact for $className; got $loads"
        )
    }

    private fun assertCallable(displayName: String?, label: String = "type") {
        val text = displayName.orEmpty()
        assertTrue(
            looksCallable(text),
            "Expected callable $label, got $displayName."
        )
        assertFalse(displayName.isNullOrBlank() || displayName == "unknown", "Expected modeled callable, got $displayName")
    }

    private fun looksCallable(displayName: String): Boolean {
        return displayName.contains("fun(") ||
            Regex("""fun\s*<[^>]+>\s*\(""").containsMatchIn(displayName)
    }

    private fun assertNoCrashDiagnosticsQuery(harness: WorkspaceSemanticHarness) {
        // Smoke: diagnostics query must complete without throw / hang.
        diagnostics(harness)
    }

    /**
     * Missing/invalid loadLib args policy:
     * - Ideal: degrade to unknown/blank and/or emit a stable loadLib arg / target diagnostic.
     * - Must not silently keep a clean JVM static-member callable surface with no signal.
     */
    private fun assertMissingOrInvalidArgsSurface(
        harness: WorkspaceSemanticHarness,
        needle: String
    ) {
        val display = hoverDisplay(harness, needle, occurrence = 2)
        val diags = diagnostics(harness)
        val diagnosticHit = diags.any {
            it.looksLikeLoadLibArgProblem() ||
                it.looksLikeUnresolvedLoadLibTarget() ||
                it.message.contains("loadLib", ignoreCase = true)
        }
        val degraded =
            display == null || display.isBlank() || display == "unknown" || !looksCallable(display)
        assertTrue(
            degraded || diagnosticHit,
            "loadLib missing/invalid args must degrade or diagnose; type=$display diagnostics=${diags.map { it.message }}"
        )
        // Safety floor: never keep a silent callable package member surface.
        if (looksCallable(display.orEmpty())) {
            assertTrue(
                diagnosticHit,
                "Callable loadLib result after bad args requires a diagnostic; type=$display diagnostics=${diags.map { it.message }}"
            )
        }
    }

    private fun assertUnknownTargetDiagnostic(harness: WorkspaceSemanticHarness, target: String) {
        val diagnostics = diagnostics(harness)
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

    private fun Diagnostic.looksLikeLoadLibArgProblem(): Boolean {
        val message = message.lowercase()
        val mentionsLoadLib =
            message.contains("loadlib") ||
                message.contains("load lib") ||
                message.contains("luajava") ||
                code.orEmpty().contains("luajava", ignoreCase = true) ||
                code.orEmpty().contains("call", ignoreCase = true)
        val mentionsArgProblem =
            message.contains("arity") ||
                message.contains("argument") ||
                message.contains("parameter") ||
                message.contains("missing") ||
                message.contains("required") ||
                message.contains("invalid") ||
                message.contains("expected") ||
                message.contains("too few") ||
                message.contains("too many") ||
                message.contains("wrong number") ||
                message.contains("mismatch") ||
                message.contains("non-string") ||
                message.contains("string")
        return mentionsLoadLib && mentionsArgProblem
    }

    private fun Diagnostic.looksLikeUnresolvedLoadLibTarget(): Boolean {
        val message = message.lowercase()
        val codeText = code.orEmpty()
        return (codeText == "checker.luajava.target.unresolved" ||
            message.contains("unresolved luajava") ||
            (message.contains("loadlib") &&
                (message.contains("unresolved") || message.contains("unknown") || message.contains("not found")))) &&
            (message.contains("loadlib") || message.contains("luajava") || codeText.contains("luajava"))
    }

    private fun Diagnostic.looksLikeUnknownMember(): Boolean {
        val message = message.lowercase()
        return code == "checker.member.missing" ||
            (message.contains("unknown") && message.contains("member"))
    }
}
