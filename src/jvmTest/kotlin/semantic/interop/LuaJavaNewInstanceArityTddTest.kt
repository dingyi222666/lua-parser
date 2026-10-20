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
 * Acceptance focus (dual-path):
 * - Ideal: wrong constructor arity degrades to `unknown` and/or emits a diagnostic.
 * - Current product gap: [ExpressionTypeEvaluator.resolveNewInstanceCall] returns the
 *   class instance surface whenever the class-name string is known and does **not**
 *   validate constructor arity / overload match. Keeping the reflected instance type
 *   without a constructor/arity diagnostic is therefore accepted and documented here
 *   until product support lands (or review re-scopes product work).
 * - Valid arity continues to preserve the reflected instance class type (hard assert).
 * - Aliases / string-call forms follow the same dual-path policy when modeled.
 *
 * Test-only scope. Workers do not run Gradle; verification is review-owned serial jvmTest.
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
    fun missing_required_constructor_argument_degrades_or_documents_current_product_gap() {
        // java.io.File has no zero-arg constructor; class name alone is wrong arity.
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File")
                return file
            """.trimIndent()
        )

        assertWrongArityDualPath(harness, "file", preservedClassType = "java.io.File")
    }
    @Test
    fun too_many_constructor_arguments_degrades_or_documents_current_product_gap() {
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
    fun valid_and_invalid_arity_mix_preserves_valid_and_dual_paths_invalid() {
        val harness = jvmHarness(
            "main.lua" to """
                local good = luajava.newInstance("java.lang.StringBuilder")
                local bad = luajava.newInstance("java.io.File")
                return good, bad
            """.trimIndent()
        )

        assertHoverType(harness, "good", "java.lang.StringBuilder", occurrence = 2)
        assertWrongArityDualPath(harness, "bad", preservedClassType = "java.io.File")
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
     * Dual-path wrong constructor arity policy (REVIEW28 rework):
     *
     * - Ideal: type degrades to unknown/blank and/or a constructor/arity diagnostic fires.
     * - Current product gap: known class-name strings still yield the instance class type
     *   because resolveNewInstanceCall does not validate constructor arity. Document that
     *   permissive keep as an accepted corpus outcome until product validates constructors.
     *
     * Unexpected third outcomes (e.g. unrelated class type with no diagnostic) still fail.
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

        // Safety floor once product starts validating: do not silently keep a *different*
        // class type than the named target without a diagnostic.
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
