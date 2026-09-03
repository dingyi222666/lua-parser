package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A simple Lua table argument must be accepted for Java array-shaped parameter slots:
 * `float...` varargs and fixed `T[]` parameters. LuaJava converts the table to the
 * component array at runtime, so call checking must not reject `{30, 0}` for
 * `ObjectAnimator.ofFloat(target, name, float...)` style surfaces.
 */
class JavaVarargTableArgumentTddTest {
    @Test
    fun lua_table_fills_float_vararg_slot() {
        val harness = harness(
            "main.lua" to """
                local Anim = luajava.bindClass("semantic.interop.JavaVarargTableArgumentTddTest${'$'}VarargAnim")
                local value = Anim.ofFloat("view", "y", {30, 0})
                local spread = Anim.ofFloat("view", "y", 30, 0)
                return value, spread
            """.trimIndent()
        )

        assertNoDiagnostics(harness)
        assertHoverType(harness, "value", "number")
        assertHoverType(harness, "spread", "number")
    }

    @Test
    fun lua_table_fills_typed_array_parameter() {
        val harness = harness(
            "main.lua" to """
                local Host = luajava.bindClass("semantic.interop.JavaVarargTableArgumentTddTest${'$'}ArrayHost")
                local joined = Host.join({"a", "b"})
                return joined
            """.trimIndent()
        )

        assertNoDiagnostics(harness)
        assertHoverType(harness, "joined", "string")
    }

    @Test
    fun lua_table_fills_generic_array_parameter() {
        val harness = harness(
            "main.lua" to """
                local Host = luajava.bindClass("semantic.interop.JavaVarargTableArgumentTddTest${'$'}ArrayHost")
                local first = Host.firstOf({1, 2})
                return first
            """.trimIndent()
        )

        assertNoDiagnostics(harness)
        assertNotNull(harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "firstOf")))
    }

    private fun harness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(
                JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                    "semantic.interop.JavaVarargTableArgumentTddTest${'$'}VarargAnim",
                    "semantic.interop.JavaVarargTableArgumentTddTest${'$'}ArrayHost"
                ).joinToString("\n")
            ),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String
    ) {
        val hover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", needle, 2)),
            "Expected hover for local '$needle'."
        )
        assertEquals(expected, hover.typeInfo?.displayName)
    }

    private fun assertNoDiagnostics(harness: WorkspaceSemanticHarness) {
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(
            diagnostics.isEmpty(),
            "Expected no diagnostics; actual: ${diagnostics.map { it.message }}."
        )
    }

    @Suppress("unused")
    class VarargAnim {
        companion object {
            @JvmStatic
            fun ofFloat(target: Any?, propertyName: String, vararg values: Float): Float = 0f
        }
    }

    @Suppress("unused")
    class ArrayHost {
        companion object {
            @JvmStatic
            fun join(values: Array<String>): String = values.joinToString(",")

            @JvmStatic
            fun <T> firstOf(values: Array<T>): T? = values.firstOrNull()
        }
    }
}
