package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.resolve.DocFunctionTypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocFunctionTypeSyntaxParserTest {

    private val parser = DocFunctionTypeSyntaxParser()

    @Test
    fun parsesDirectFunctionTypesAndBareSignatures() {
        assertEquals(
            "fun(value: string): number",
            TypeSyntaxRenderer.render(requireParsed("fun(value: string): number"))
        )

        assertEquals(
            "fun(value: table<string, { ok: boolean }>, extra?: [string, number]): number",
            TypeSyntaxRenderer.render(requireParsed("(value: table<string, { ok: boolean }>, extra?: [string, number]): number"))
        )
    }

    @Test
    fun parsesVarargBareSignatures() {
        assertEquals(
            "fun(string...): nil",
            TypeSyntaxRenderer.render(requireParsed("(string...): nil"))
        )

        assertEquals(
            "fun(rest: string...): nil",
            TypeSyntaxRenderer.render(requireParsed("(rest: string...): nil"))
        )
    }

    @Test
    fun returnsNullForMalformedSignatures() {
        assertNull(parser.parseOrNull("(value: string: number"))
        assertNull(parser.parseOrNull("(value: table<string, number>):"))
        assertNull(parser.parseOrNull("(value extra): number trailing"))
    }

    private fun requireParsed(text: String): FunctionTypeSyntax {
        return parser.parseOrNull(text) ?: error("Expected parser to accept: $text")
    }
}
