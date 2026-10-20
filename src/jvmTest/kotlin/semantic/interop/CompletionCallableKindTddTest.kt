package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Callable members must not surface value/text completion kinds: a Lua function stored in a
 * table/module field completes as FUNCTION, Java instance methods stay METHOD, and Java
 * field/JavaBean aliases stay FIELD. This keeps Monaco (and any LSP client) from rendering
 * function completions with the plain text/value icon.
 */
class CompletionCallableKindTddTest {
    @Test
    fun lua_module_function_member_completes_as_function() {
        val harness = harness(
            "main.lua" to """
                local helper = require("helper")
                helper.format
            """.trimIndent(),
            "helper.lua" to """
                local helper = {}
                function helper.format(value)
                    return tostring(value)
                end
                return helper
            """.trimIndent()
        )

        val item = completion(harness, "format")
        assertEquals(CompletionItemKind.FUNCTION, item.kind, "Lua module function member must complete as FUNCTION")
        assertTrue(
            item.detail.orEmpty().startsWith("fun("),
            "Expected callable detail, got ${item.detail}"
        )
    }

    @Test
    fun java_instance_members_keep_method_and_field_kinds() {
        val harness = harness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                local f = File("x")
                f.getName
                f.name
            """.trimIndent()
        )

        assertEquals(
            CompletionItemKind.METHOD,
            completion(harness, "getName").kind,
            "Java instance method must complete as METHOD"
        )
        assertEquals(
            CompletionItemKind.FIELD,
            completion(harness, "name").kind,
            "JavaBean property alias must complete as FIELD"
        )
    }

    @Test
    fun java_static_field_members_stay_field_kind() {
        val harness = harness(
            "main.lua" to """
                local File = luajava.bindClass("java.io.File")
                File.separator
            """.trimIndent()
        )

        assertEquals(
            CompletionItemKind.FIELD,
            completion(harness, "separator").kind,
            "Java static field must complete as FIELD"
        )
    }

    private fun harness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(
                JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.io.File"
            ),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun completion(
        harness: WorkspaceSemanticHarness,
        needle: String,
        file: String = "main.lua"
    ): io.github.dingyi222666.luaparser.semantic.api.CompletionItem {
        val items = harness.queries.completions(harness.path(file), harness.positionOf(file, needle))
        return assertNotNull(
            items.firstOrNull { it.label == needle },
            "Expected completion '$needle'; actual: ${items.map { it.label }}"
        )
    }
}
