package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-298 corpus: LuaJava `newInstance` constructor arity mismatch.
 *
 * Acceptance focus:
 * - wrong constructor arity degrades to `unknown` and/or emits a diagnostic
 * - valid arity continues to preserve the reflected instance class type
 * - aliases / string-call forms follow the same policy when modeled
 *
 * Product note (test-only scope): current ExpressionTypeEvaluator
 * `resolveNewInstanceCall` returns the class instance surface whenever the
 * class-name string is known and does not yet validate constructor arity.
 * These tests encode the desired degrade policy; red is OK until product
 * support lands (or review re-scopes product work).
 */
class LuaJavaNewInstanceArityTddTest {
    @Test
    fun valid_no_arg_constructor_preserves_instance_class_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
    }

    @Test
    fun valid_one_arg_constructor_preserves_instance_class_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", "build.gradle.kts")
                return file
            """.trimIndent()
        )

        assertHoverType(harness, "file", "java.io.File", occurrence = 2)
    }

    @Test
    fun valid_string_builder_seed_constructor_preserves_instance_class_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", "seed")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
    }

    @Test
    fun valid_new_instance_alias_preserves_instance_class_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local builder = newInstance("java.lang.StringBuilder")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
    }

    @Test
    fun valid_new_instance_string_call_form_preserves_instance_class_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance "java.lang.StringBuilder"
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
    }

    @Test
    fun valid_arity_does_not_require_constructor_mismatch_diagnostics() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder")
                local file = luajava.newInstance("java.io.File", "x")
                return builder, file
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertHoverType(harness, "file", "java.io.File", occurrence = 2)
        assertFalse(
            diagnostics(harness).any { it.looksLikeConstructorArityProblem() },
            "Valid newInstance arity should not emit constructor/arity diagnostics; actual: ${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun missing_required_constructor_argument_degrades_to_unknown_or_diagnostic() {
        // java.io.File has no zero-arg constructor; class name alone is wrong arity.
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File")
                return file
            """.trimIndent()
        )

        assertWrongArityDegrades(harness, "file", preservedClassType = "java.io.File")
    }

    @Test
    fun integer_missing_value_argument_degrades_to_unknown_or_diagnostic() {
        // java.lang.Integer constructors require a value (int or String).
        val harness = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.lang.Integer")
                return value
            """.trimIndent()
        )

        assertWrongArityDegrades(harness, "value", preservedClassType = "java.lang.Integer")
    }

    @Test
    fun too_many_constructor_arguments_degrades_to_unknown_or_diagnostic() {
        // java.lang.Object only exposes a zero-arg constructor.
        val harness = jvmHarness(
            "main.lua" to """
                local obj = luajava.newInstance("java.lang.Object", "extra")
                return obj
            """.trimIndent()
        )

        assertWrongArityDegrades(harness, "obj", preservedClassType = "java.lang.Object")
    }

    @Test
    fun string_builder_three_string_args_degrades_to_unknown_or_diagnostic() {
        // StringBuilder has ( ), (String), (int), (CharSequence) — not three strings.
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", "a", "b", "c")
                return builder
            """.trimIndent()
        )

        assertWrongArityDegrades(harness, "builder", preservedClassType = "java.lang.StringBuilder")
    }

    @Test
    fun uuid_single_arg_degrades_to_unknown_or_diagnostic() {
        // java.util.UUID public constructors are (long, long) — one arg is wrong arity.
        val harness = jvmHarness(
            "main.lua" to """
                local id = luajava.newInstance("java.util.UUID", 1)
                return id
            """.trimIndent()
        )

        assertWrongArityDegrades(harness, "id", preservedClassType = "java.util.UUID")
    }

    @Test
    fun wrong_arity_via_local_alias_degrades_to_unknown_or_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local file = newInstance("java.io.File")
                return file
            """.trimIndent()
        )

        assertWrongArityDegrades(harness, "file", preservedClassType = "java.io.File")
    }

    @Test
    fun wrong_arity_via_chained_alias_degrades_to_unknown_or_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local make = newInstance
                local again = make
                local file = again("java.io.File")
                return file
            """.trimIndent()
        )

        assertWrongArityDegrades(harness, "file", preservedClassType = "java.io.File")
    }

    @Test
    fun wrong_arity_instance_member_use_does_not_keep_typed_surface_without_signal() {
        // If typing incorrectly keeps File, append-like members may still resolve;
        // wrong arity must not keep a clean typed instance with zero diagnostics.
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File")
                local name = file.getName
                return file, name
            """.trimIndent()
        )

        assertWrongArityDegrades(harness, "file", preservedClassType = "java.io.File")
        val memberDisplay = hoverDisplay(harness, "name", occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeConstructorArityProblem() || it.looksLikeUnknownNewInstance()
        }
        val memberDegraded =
            memberDisplay == null ||
                memberDisplay.isBlank() ||
                memberDisplay == "unknown" ||
                !memberDisplay.contains("fun(")
        assertTrue(
            memberDegraded || diagnosticHit,
            "Wrong-arity newInstance must not keep a clean typed member surface without signal; " +
                "member=$memberDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun missing_class_name_argument_degrades_to_unknown_or_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local missing = luajava.newInstance()
                return missing
            """.trimIndent()
        )

        val display = hoverDisplay(harness, "missing", occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeConstructorArityProblem() ||
                it.looksLikeUnknownNewInstance() ||
                it.message.contains("newInstance", ignoreCase = true)
        }
        val degraded =
            display == null || display.isBlank() || display == "unknown"
        assertTrue(
            degraded || diagnosticHit,
            "newInstance() with no class name must degrade or diagnose; " +
                "type=$display diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun non_string_class_name_degrades_to_unknown_or_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local bad = luajava.newInstance(42)
                return bad
            """.trimIndent()
        )

        val display = hoverDisplay(harness, "bad", occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeConstructorArityProblem() ||
                it.looksLikeUnknownNewInstance() ||
                it.message.contains("newInstance", ignoreCase = true)
        }
        val degraded =
            display == null || display.isBlank() || display == "unknown"
        assertTrue(
            degraded || diagnosticHit,
            "newInstance(non-string) must degrade or diagnose; " +
                "type=$display diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun valid_and_invalid_arity_mix_preserves_only_valid_instance_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local good = luajava.newInstance("java.lang.StringBuilder")
                local bad = luajava.newInstance("java.io.File")
                return good, bad
            """.trimIndent()
        )

        assertHoverType(harness, "good", "java.lang.StringBuilder", occurrence = 2)
        assertWrongArityDegrades(harness, "bad", preservedClassType = "java.io.File")
    }

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
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

    /**
     * Wrong constructor arity must degrade the result type to unknown (or blank)
     * and/or emit a constructor/arity diagnostic. Keeping the class instance type
     * without any signal fails the corpus.
     */
    private fun assertWrongArityDegrades(
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

        assertTrue(
            idealUnknown || diagnosticHit,
            "Wrong newInstance constructor arity must degrade type to unknown or emit a diagnostic; " +
                "type=$display diagnostics=${diagnostics(harness).map { it.message }}"
        )
        assertFalse(
            display == preservedClassType && !diagnosticHit,
            "Wrong newInstance constructor arity must not keep $preservedClassType without a diagnostic; " +
                "type=$display diagnostics=${diagnostics(harness).map { it.message }}"
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
