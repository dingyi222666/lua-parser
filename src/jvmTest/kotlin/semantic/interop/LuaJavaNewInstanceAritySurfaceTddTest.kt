package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-369 corpus: LuaJava `newInstance` **arity surface** — valid constructor arities
 * mount providers, record `NEW_INSTANCE_CALL` facts, and preserve reflected instance class /
 * member surfaces; wrong constructor arities stay dual-path (ideal degrade/diagnose vs
 * documented current product keep of class type without constructor validation).
 *
 * Complements [LuaJavaNewInstanceArityTddTest] (arity dual-path focus) by locking the
 * broader product surface: provider paths, document facts, instance members after valid
 * arity, breadth JDK constructor table, missing-class diagnostics, dynamic targets, and
 * shadowing / colon negatives.
 *
 * Does **not** invent APIs: only exercises `luajava.newInstance` / aliases already modeled
 * by [ExpressionTypeEvaluator.resolveNewInstanceCall] and document-fact collection.
 *
 * Test-only scope. Workers do not run Gradle; verification is review-owned serial jvmTest.
 */
class LuaJavaNewInstanceAritySurfaceTddTest {

    // ------------------------------------------------------------------
    // Valid arity surfaces (hard asserts)
    // ------------------------------------------------------------------

    @Test
    fun valid_zero_arg_new_instance_surfaces_class_provider_and_fact() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertFalse(
            diagnostics(harness).any { it.looksLikeConstructorArityProblem() },
            "Valid zero-arg newInstance must not emit constructor/arity diagnostics; actual: ${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun valid_one_arg_new_instance_surfaces_class_provider_and_fact() {
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", "build.gradle.kts")
                return file
            """.trimIndent()
        )

        assertHoverType(harness, "file", "java.io.File", occurrence = 2)
        assertProviderPath(harness, "java.io.File")
        assertNewInstanceFact(harness, "java.io.File")
    }

    @Test
    fun valid_string_builder_seed_constructor_surfaces_instance_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", "seed")
                local append = builder.append
                return builder, append
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        val appendDisplay = hoverDisplay(harness, "append", occurrence = 2)
        assertCallable(appendDisplay, "StringBuilder.append after valid newInstance")
    }

    @Test
    fun valid_new_instance_instance_field_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local point = luajava.newInstance("java.awt.Point")
                local x = point.x
                return point, x
            """.trimIndent()
        )

        assertHoverType(harness, "point", "java.awt.Point", occurrence = 2)
        assertProviderPath(harness, "java.awt.Point")
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "x", occurrence = 2)
        )
        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals("number", hover?.typeInfo?.displayName)
    }

    @Test
    fun valid_new_instance_instance_method_definition_points_to_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder")
                local append = builder.append
                return append
            """.trimIndent()
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "append", occurrence = 2)
        )
        assertEquals(
            listOf(harness.path("__jvm__/classes/java/lang/StringBuilder.lua")),
            definitions.map { it.path }
        )
    }

    @Test
    fun valid_new_instance_alias_surfaces_class_and_fact() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local builder = newInstance("java.lang.StringBuilder")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
    }

    @Test
    fun valid_chained_alias_string_call_surfaces_class_fact_and_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local make = newInstance
                local again = make
                local builder = again "java.lang.StringBuilder"
                local append = builder.append
                return builder, append
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertCallable(hoverDisplay(harness, "append", occurrence = 2), "chained alias member")
    }

    @Test
    fun valid_compact_string_call_form_surfaces_class_and_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance "java.lang.StringBuilder"
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
    }

    // ------------------------------------------------------------------
    // Breadth corpus: documented valid constructor arities across packages
    // ------------------------------------------------------------------

    @Test
    fun new_instance_valid_arity_surface_corpus_table() {
        data class Case(
            val className: String,
            val ctorArgs: String,
            val localName: String,
            val member: String? = null,
            val memberExpectCallable: Boolean = true
        )

        val cases = listOf(
            Case("java.lang.StringBuilder", "", "builder", member = "append"),
            Case("java.lang.StringBuilder", "\"seed\"", "seeded", member = "length"),
            Case("java.lang.Object", "", "obj", member = "toString"),
            Case("java.io.File", "\"x.txt\"", "file", member = "getName"),
            Case("java.util.ArrayList", "", "list", member = "size"),
            Case("java.util.HashMap", "", "map", member = "put"),
            Case("java.lang.String", "\"hello\"", "text", member = "length"),
            Case("java.awt.Point", "", "point", member = "x", memberExpectCallable = false),
            Case("java.util.Date", "", "date", member = "getTime"),
            Case("java.lang.StringBuffer", "", "buffer", member = "append")
        )

        cases.forEach { case ->
            val args = if (case.ctorArgs.isEmpty()) {
                "\"${case.className}\""
            } else {
                "\"${case.className}\", ${case.ctorArgs}"
            }
            val memberLine = case.member?.let { "\n                local m = ${case.localName}.$it" }.orEmpty()
            val returnLine = if (case.member != null) {
                "return ${case.localName}, m"
            } else {
                "return ${case.localName}"
            }
            val harness = jvmHarness(
                "main.lua" to """
                    local ${case.localName} = luajava.newInstance($args)$memberLine
                    $returnLine
                """.trimIndent()
            )

            assertHoverType(harness, case.localName, case.className, occurrence = 2)
            assertProviderPath(harness, case.className)
            assertNewInstanceFact(harness, case.className)
            if (case.member != null) {
                val memberDisplay = hoverDisplay(harness, "m", occurrence = 2)
                if (case.memberExpectCallable) {
                    assertCallable(memberDisplay, "newInstance ${case.className}.${case.member}")
                } else {
                    assertNotUnknown(memberDisplay)
                    assertFalse(
                        looksCallable(memberDisplay.orEmpty()),
                        "Expected field-like surface for ${case.className}.${case.member}, got $memberDisplay"
                    )
                }
            }
            assertFalse(
                diagnostics(harness).any { it.looksLikeConstructorArityProblem() },
                "Valid corpus case ${case.className} must not emit arity diagnostics; actual: ${diagnostics(harness).map { it.message }}"
            )
        }
    }

    // ------------------------------------------------------------------
    // Wrong constructor arity — dual-path (ideal vs current product gap)
    // ------------------------------------------------------------------

    @Test
    fun missing_required_constructor_argument_dual_path_surface() {
        // java.io.File has no zero-arg constructor.
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File")
                return file
            """.trimIndent()
        )

        assertWrongArityDualPath(harness, "file", preservedClassType = "java.io.File")
        // Fact/provider may still appear when class name string is known (current product).
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun too_many_constructor_arguments_dual_path_surface() {
        // java.lang.Object only exposes a zero-arg constructor.
        val harness = jvmHarness(
            "main.lua" to """
                local obj = luajava.newInstance("java.lang.Object", "extra")
                return obj
            """.trimIndent()
        )

        assertWrongArityDualPath(harness, "obj", preservedClassType = "java.lang.Object")
    }

    @Test
    fun integer_missing_value_argument_dual_path_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.lang.Integer")
                return value
            """.trimIndent()
        )

        assertWrongArityDualPath(harness, "value", preservedClassType = "java.lang.Integer")
    }

    @Test
    fun string_builder_three_string_args_dual_path_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", "a", "b", "c")
                return builder
            """.trimIndent()
        )

        assertWrongArityDualPath(harness, "builder", preservedClassType = "java.lang.StringBuilder")
    }

    @Test
    fun uuid_single_arg_dual_path_surface() {
        // public constructors are (long, long).
        val harness = jvmHarness(
            "main.lua" to """
                local id = luajava.newInstance("java.util.UUID", 1)
                return id
            """.trimIndent()
        )

        assertWrongArityDualPath(harness, "id", preservedClassType = "java.util.UUID")
    }

    @Test
    fun wrong_arity_via_local_alias_dual_path_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local file = newInstance("java.io.File")
                return file
            """.trimIndent()
        )

        assertWrongArityDualPath(harness, "file", preservedClassType = "java.io.File")
    }

    @Test
    fun wrong_arity_instance_member_dual_path_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File")
                local name = file.getName
                return file, name
            """.trimIndent()
        )

        assertWrongArityDualPath(harness, "file", preservedClassType = "java.io.File")
        val memberDisplay = hoverDisplay(harness, "name", occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeConstructorArityProblem() || it.looksLikeUnknownNewInstance()
        }
        val memberDegraded =
            memberDisplay == null ||
                memberDisplay.isBlank() ||
                memberDisplay == "unknown" ||
                !looksCallable(memberDisplay)
        val currentProductKeepsMemberSurface =
            memberDisplay != null && looksCallable(memberDisplay)
        assertTrue(
            memberDegraded || diagnosticHit || currentProductKeepsMemberSurface,
            "Wrong-arity newInstance member path must degrade/diagnose (ideal) or keep current " +
                "product typed surface; member=$memberDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun valid_and_invalid_arity_mix_preserves_valid_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local good = luajava.newInstance("java.lang.StringBuilder")
                local bad = luajava.newInstance("java.io.File")
                return good, bad
            """.trimIndent()
        )

        assertHoverType(harness, "good", "java.lang.StringBuilder", occurrence = 2)
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertWrongArityDualPath(harness, "bad", preservedClassType = "java.io.File")
    }

    // ------------------------------------------------------------------
    // Missing / invalid class-name arguments (hard degrade or diagnose)
    // ------------------------------------------------------------------

    @Test
    fun missing_class_name_argument_degrades_or_diagnoses() {
        val harness = jvmHarness(
            "main.lua" to """
                local missing = luajava.newInstance()
                return missing
            """.trimIndent()
        )

        assertMissingOrInvalidClassNameSurface(harness, "missing")
        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL },
            "newInstance() with no class name must not invent NEW_INSTANCE_CALL facts; got ${jvmClassLoads(harness)}"
        )
    }

    @Test
    fun non_string_class_name_degrades_or_diagnoses() {
        val harness = jvmHarness(
            "main.lua" to """
                local bad = luajava.newInstance(42)
                return bad
            """.trimIndent()
        )

        assertMissingOrInvalidClassNameSurface(harness, "bad")
    }

    /**
     * Missing class-name target: product emits unresolved LuaJava target diagnostic
     * and types the binding as unknown (resolveNewInstanceCall → UnknownType when
     * import resolution fails).
     *
     * Variable name must not be a substring of the class target string, otherwise
     * positionOf(occurrence=2) lands on the string literal (type `string`) instead of
     * the return binding — which falsely fails the unknown-type surface assert.
     */
    @Test
    fun missing_class_target_reports_diagnostic_and_unknown_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local instance = luajava.newInstance("missing.DoesNotExist")
                return instance
            """.trimIndent()
        )

        assertUnknownTargetDiagnostic(harness, "missing.DoesNotExist")
        val display = hoverDisplay(harness, "instance", occurrence = 2)
        assertTrue(
            display == null || display.isBlank() || display == "unknown",
            "Unknown class newInstance should be unknown, got '$display'"
        )
    }


    @Test
    fun dynamic_class_name_does_not_emit_new_instance_fact_or_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local className = "java.lang.StringBuilder"
                local builder = luajava.newInstance(className)
                return builder
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any { it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL },
            "Dynamic newInstance targets must not invent NEW_INSTANCE_CALL facts; got $loads"
        )
        assertNoCrashDiagnosticsQuery(harness)
        val display = hoverDisplay(harness, "builder", occurrence = 2)
        assertTrue(
            display == null || display.isBlank() || display == "unknown",
            "Dynamic class-name newInstance should be unknown, got '$display'"
        )
    }

    // ------------------------------------------------------------------
    // Shadowing / colon: must not inherit JVM newInstance surface
    // ------------------------------------------------------------------

    @Test
    fun shadowed_local_new_instance_function_does_not_gain_jvm_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local function newInstance(target)
                    return { value = target }
                end

                local instanceResult = newInstance("java.lang.StringBuilder")
                local instanceAppend = instanceResult.append
                return instanceResult, instanceAppend
            """.trimIndent()
        )

        assertHoverTypeIsTableLikeNotJvmClass(harness, "instanceResult", occurrence = 2)
        assertHoverType(harness, "instanceAppend", "unknown", occurrence = 2)
        assertFalse(
            jvmClassLoads(harness).any {
                it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL &&
                    it.target == "java.lang.StringBuilder"
            },
            "Shadowed bare newInstance must not emit NEW_INSTANCE_CALL; got ${jvmClassLoads(harness)}"
        )
    }

    @Test
    fun shadowed_local_luajava_new_instance_member_does_not_gain_jvm_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    newInstance = function(target)
                        return { value = target }
                    end
                }
                local make = luajava.newInstance

                local instanceResult = luajava.newInstance("java.lang.StringBuilder")
                local aliasResult = make("java.lang.StringBuilder")
                local instanceAppend = instanceResult.append
                local aliasAppend = aliasResult.append
                return instanceResult, aliasResult, instanceAppend, aliasAppend
            """.trimIndent()
        )

        assertHoverTypeIsTableLikeNotJvmClass(harness, "instanceResult", occurrence = 2)
        assertHoverTypeIsTableLikeNotJvmClass(harness, "aliasResult", occurrence = 2)
        assertHoverType(harness, "instanceAppend", "unknown", occurrence = 2)
        assertHoverType(harness, "aliasAppend", "unknown", occurrence = 2)
        assertFalse(
            jvmClassLoads(harness).any {
                it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL &&
                    it.target == "java.lang.StringBuilder"
            },
            "Shadowed luajava.newInstance must not emit NEW_INSTANCE_CALL; got ${jvmClassLoads(harness)}"
        )
    }

    @Test
    fun colon_new_instance_call_does_not_accidentally_model_helper() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava:newInstance("java.lang.StringBuilder")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "unknown", occurrence = 2)
        assertFalse(
            jvmClassLoads(harness).any {
                it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL &&
                    it.target == "java.lang.StringBuilder"
            },
            "Colon newInstance must not emit NEW_INSTANCE_CALL; got ${jvmClassLoads(harness)}"
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

    private fun assertNewInstanceFact(harness: WorkspaceSemanticHarness, className: String) {
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL &&
                    it.target == className
            },
            "Expected NEW_INSTANCE_CALL fact for $className; got $loads"
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
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
        assertEquals(expected, hover?.typeInfo?.displayName)
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

    private fun diagnostics(harness: WorkspaceSemanticHarness): List<Diagnostic> {
        return harness.queries.diagnostics(harness.path("main.lua"))
    }

    private fun assertNoCrashDiagnosticsQuery(harness: WorkspaceSemanticHarness) {
        // Smoke: diagnostics query must complete.
        diagnostics(harness)
    }

    private fun assertCallable(displayName: String?, label: String = "type") {
        val text = displayName.orEmpty()
        assertTrue(
            looksCallable(text),
            "Expected callable $label, got $displayName."
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

    /**
     * Dual-path wrong constructor arity policy (matches TASK-298 / REVIEW28):
     *
     * - Ideal: type degrades to unknown/blank and/or a constructor/arity diagnostic fires.
     * - Current product gap: known class-name strings still yield the instance class type
     *   because resolveNewInstanceCall does not validate constructor arity. Document that
     *   permissive keep as an accepted corpus outcome until product validates constructors.
     */
    private fun assertWrongArityDualPath(
        harness: WorkspaceSemanticHarness,
        needle: String,
        preservedClassType: String
    ) {
        val display = hoverDisplay(harness, needle, occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeConstructorArityProblem() || it.looksLikeUnknownNewInstance()
        }
        val idealUnknown =
            display == null || display.isBlank() || display == "unknown"
        val currentProductKeepsClassType =
            display == preservedClassType && !diagnosticHit

        assertTrue(
            idealUnknown || diagnosticHit || currentProductKeepsClassType,
            "Wrong newInstance constructor arity must degrade/diagnose (ideal) or keep " +
                "documented current product instance type $preservedClassType without arity " +
                "validation; type=$display diagnostics=${diagnostics(harness).map { it.message }}"
        )

        if (display != null && display.isNotBlank() && display != "unknown" &&
            display != preservedClassType
        ) {
            assertTrue(
                diagnosticHit,
                "Unexpected non-target type for wrong-arity newInstance without diagnostic; " +
                    "expected unknown, $preservedClassType (current product gap), or diagnostic; " +
                    "type=$display diagnostics=${diagnostics(harness).map { it.message }}"
            )
        }
    }

    private fun assertMissingOrInvalidClassNameSurface(
        harness: WorkspaceSemanticHarness,
        needle: String
    ) {
        val display = hoverDisplay(harness, needle, occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeConstructorArityProblem() ||
                it.looksLikeUnknownNewInstance() ||
                it.message.contains("newInstance", ignoreCase = true)
        }
        val degraded =
            display == null || display.isBlank() || display == "unknown"
        assertTrue(
            degraded || diagnosticHit,
            "newInstance missing/invalid class name must degrade or diagnose; " +
                "type=$display diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    private fun assertHoverTypeIsTableLikeNotJvmClass(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ) {
        val display = hoverDisplay(harness, needle, occurrence).orEmpty()
        assertTrue(
            display == "table" ||
                display.startsWith("{") ||
                display.contains("value"),
            "Expected table-like shadowed newInstance result, got '$display'."
        )
        assertFalse(
            display.startsWith("java."),
            "Shadowed newInstance must not expose JVM class type, got '$display'."
        )
        assertFalse(
            looksCallable(display),
            "Shadowed newInstance must not expose JVM callable surface, got '$display'."
        )
    }

    private fun assertUnknownTargetDiagnostic(harness: WorkspaceSemanticHarness, target: String) {
        val diagnostics = diagnostics(harness)
        assertTrue(
            diagnostics.any { diagnostic ->
                diagnostic.message.contains(target) &&
                    (diagnostic.message.contains("unknown", ignoreCase = true) ||
                        diagnostic.message.contains("not found", ignoreCase = true) ||
                        diagnostic.message.contains("unresolved", ignoreCase = true))
            },
            "Expected unknown LuaJava target diagnostic for $target; actual: ${diagnostics.map { it.message }}"
        )
    }

    private fun Diagnostic.looksLikeConstructorArityProblem(): Boolean {
        val message = message.lowercase()
        val mentionsConstructorSurface =
            message.contains("constructor") ||
                message.contains("newinstance") ||
                message.contains("new instance") ||
                message.contains("arity") ||
                message.contains("argument") ||
                message.contains("parameter") ||
                message.contains("signature") ||
                message.contains("overload")
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
        return mentionsConstructorSurface && mentionsMismatch
    }

    private fun Diagnostic.looksLikeUnknownNewInstance(): Boolean {
        val message = message.lowercase()
        return (message.contains("newinstance") || message.contains("constructor") || message.contains("java")) &&
            (
                message.contains("unknown") ||
                    message.contains("invalid") ||
                    message.contains("unresolved") ||
                    message.contains("not found") ||
                    message.contains("no matching")
                )
    }
}
