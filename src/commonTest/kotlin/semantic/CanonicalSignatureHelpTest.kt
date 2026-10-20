package semantic

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanonicalSignatureHelpTest {
    private fun model(source: String) = SemanticPipeline().analyzeSnapshot(LuaParser().parse(source),
        SemanticWorkspaceContext(overlayGlobals = BuiltinOverlaySnapshot.EMPTY.globals)).model

    @Test
    fun a_member_signature_never_binds_an_unrelated_same_named_local() {
        val source = """
            ---@param value number
            ---@return boolean
            local function convert(value) return true end
            local receiver = {}
            ---@param value string
            ---@return string
            function receiver.convert(value) return value end
            receiver.convert('item')
        """.trimIndent()
        val help = assertNotNull(model(source).getSignatureHelpAt(Position(8, 21)))
        assertEquals(listOf("value: string"), help.signatures.single().parameters.map { it.label })
        assertTrue(help.signatures.single().label.endsWith(": string"), help.toString())
    }

    @Test
    fun factory_call_signature_uses_the_returned_callable_instead_of_the_factory() {
        val source = """
            ---@return fun(value: number): string
            local function factory()
                return function(value) return 'ok' end
            end
            factory()(42)
        """.trimIndent()
        val help = assertNotNull(model(source).getSignatureHelpAt(Position(5, 12)))
        assertEquals(listOf("value: number"), help.signatures.single().parameters.map { it.label })
        assertTrue(help.signatures.single().label.endsWith(": string"), help.toString())
    }

    @Test
    fun compact_table_calls_use_the_same_argument_list_and_self_offset() {
        val source = """
            local receiver = {}
            ---@param options table
            ---@param extra string
            ---@return boolean
            function receiver:accept(options, extra) return true end
            receiver:accept { enabled=true }, 'more'
        """.trimIndent()
        val help = assertNotNull(model(source).getSignatureHelpAt(Position(6, 36)))
        assertEquals(2, help.activeParameter)
        assertEquals(listOf("self", "options", "extra"), help.signatures.single().parameters.map { it.label.substringBefore(':') })
    }
}
