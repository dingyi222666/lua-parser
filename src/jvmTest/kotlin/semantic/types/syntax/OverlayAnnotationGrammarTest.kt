package semantic.types.syntax

import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Locks the single annotation grammar against the real shipped overlay corpus.
 *
 * `TypeSyntaxParser` is now the only type-annotation parser in the repo — the legacy
 * `TypeAnnotationParser.Parser` was deleted and its facade delegates here. This test pins the
 * grammar forms that only the deleted parser used to accept, so they cannot regress silently.
 */
class OverlayAnnotationGrammarTest {

    private fun overlayTypeTexts(): List<String> {
        val root = File("src/commonMain/resources")
        val texts = linkedSetOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "lua" }.forEach { file ->
            file.readLines().forEach { rawLine ->
                val line = rawLine.trim()
                if (!line.startsWith("---@")) return@forEach
                val body = line.removePrefix("---@")
                val tag = body.substringBefore(' ', body)
                val rest = body.removePrefix(tag).trim()
                when (tag) {
                    "param" -> texts += rest.substringAfter(' ', "").trim()
                    "return", "type" -> texts += rest
                }
            }
        }
        return texts.filter { it.isNotBlank() }.toList()
    }

    @Test
    fun `legacy-only grammar forms still parse`() {
        // Every one of these was accepted by the deleted parser and rejected by the surviving
        // one before the grammars were merged.
        listOf(
            "fun()",
            "fun(message: any)",
            "fun(title: string, error: any)",
            "fun(path: string, arg?: table)",
            "string|fun()",
            "fun(view: AndroidView)|JavaProxy|string|table",
            "fun(...: any): string",
            "fun(...)"
        ).forEach { text ->
            val parsed = runCatching { TypeSyntaxParser.parse(text) }
            assertTrue(parsed.isSuccess, "expected '$text' to parse, got ${parsed.exceptionOrNull()}")
        }
    }

    @Test
    fun `no-return function type defaults to nil`() {
        val parsed = assertIs<FunctionTypeSyntax>(TypeSyntaxParser.parse("fun(message: any)"))
        // Matches what the deleted legacy parser produced for an omitted return annotation.
        // Note the explicit `: nil` spelling parses as LiteralTypeSyntax instead; that asymmetry
        // predates this merge and both resolve to a nil-typed return.
        assertEquals(NamedTypeSyntax("nil"), parsed.returnType)
        assertEquals(1, parsed.parameters.size)
    }

    @Test
    fun `prefix and postfix vararg parameters agree`() {
        assertEquals(
            TypeSyntaxParser.parse("fun(any...): string"),
            TypeSyntaxParser.parse("fun(...: any): string")
        )
    }

    @Test
    fun `every shipped overlay annotation parses`() {
        val unparsed = overlayTypeTexts().filter { text ->
            runCatching { TypeSyntaxParser.parse(text) }.isFailure
        }
        assertEquals(emptyList(), unparsed, "overlay annotations rejected by the type grammar")
    }
}
