package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaVoidReturnTest {
    @Test
    fun reflection_void_signatures_are_distinct_from_lua_nil_and_keep_fluent_receivers() {
        val source = """
            import 'java.lang.System'
            import 'java.lang.StringBuilder'
            local noValues = System.gc()
            local fluent = StringBuilder().setLength(2)
            return noValues, fluent
        """.trimIndent()
        val harness = WorkspaceSemanticHarness.build("main.lua" to source, engine = JvmWorkspaceEngine())
        val path = harness.path("main.lua")
        val gc = harness.positionOf("main.lua", "gc", 1)
        val length = harness.positionOf("main.lua", "setLength", 1)
        assertEquals("fun(): void", harness.queries.completions(path, gc).single { it.label == "gc" }.detail)
        val help = assertNotNull(harness.queries.signatureHelp(path, length.copy(column = length.column + "setLength(".length)))
        assertTrue(help.signatures[help.activeSignature].label.endsWith(": void"), help.toString())
        assertEquals("nil", harness.queries.hover(path, harness.positionOf("main.lua", "noValues", 2))?.typeInfo?.displayName)
        assertTrue(harness.queries.hover(path, harness.positionOf("main.lua", "fluent", 2))?.typeInfo?.displayName.orEmpty().contains("StringBuilder"))
    }
}
