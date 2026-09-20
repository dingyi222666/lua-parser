package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.source.AST2Lua
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextEdit
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial-audit follow-up: LSP document formatting must never hand the editor a
 * full-document replacement that does not parse.
 *
 * Two layers are pinned here:
 *
 * 1. **Printer** — `AST2Lua` emits exactly one terminal `end` per if-statement. Formatting a
 *    document with `if … end` (single clause, if/elseif/else chains, trailing statements)
 *    keeps the `end`, re-parses without recovery diagnostics, and preserves the statement
 *    count / AST shape. Before the fix the terminator was dropped and every statement after
 *    the `if` was absorbed into its body on reparse.
 * 2. **Guard rail** — `LuaLanguageService.formatSourceText` re-parses the exact text it is
 *    about to return. When the printer surface does not parse back cleanly (any recovery
 *    diagnostic or parser throw) it falls back to the safe indent/newline normalization of
 *    the original text, so the editor only ever receives text that the parser accepts.
 *    [formatting_falls_back_to_safe_normalization_when_printer_surface_does_not_reparse]
 *    exercises that path with a construct the printer still cannot reproduce (a
 *    parenthesized string receiver `("x"):rep(3)`; the printer drops the parens and the
 *    result no longer parses).
 */
class LspFormattingIfEndReparseGuardTddTest {

    private val standaloneEndLine = Regex("(?m)^[ \\t]*end[ \\t]*$")

    @Test
    fun formatting_single_clause_if_keeps_terminal_end_and_reparses_cleanly() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fmt_if_end.lua"
        val source = "if a then print(1) end"
        service.didOpen(openParams(uri, source))

        val edits = service.formatting(
            DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, true))
        )
        assertTrue(edits.isNotEmpty(), "single-line if must be re-laid out by the printer path")
        val formatted = applyEdits(source, edits)

        assertTrue(formatted.contains("if a then"), formatted)
        assertTrue(formatted.contains("print(1)"), formatted)
        assertEquals(1, standaloneEndLine.findAll(formatted).count(), "formatted if must keep exactly one end:\n$formatted")
        assertTrue(formatted.trimEnd().endsWith("end"), "formatted if must terminate with end:\n$formatted")

        assertReparsesCleanlyWithSameShape(source, formatted)
    }

    @Test
    fun formatting_if_elseif_else_chain_with_trailing_statements_keeps_statement_count() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fmt_if_chain.lua"
        val source = """
            if a then
              x = 1
            elseif b then
              x = 2
            else
              x = 3
            end
            local after = x
            return after
        """.trimIndent() + "\n"
        service.didOpen(openParams(uri, source))

        val edits = service.formatting(
            DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, true))
        )
        val formatted = applyEdits(source, edits)

        assertTrue(formatted.contains("if a then"), formatted)
        assertTrue(formatted.contains("elseif b then"), formatted)
        assertTrue(formatted.contains("else"), formatted)
        assertTrue(formatted.contains("local after = x"), formatted)
        assertTrue(formatted.contains("return after"), formatted)
        assertEquals(
            1,
            standaloneEndLine.findAll(formatted).count(),
            "one end for the whole if/elseif/else chain (never one per clause):\n$formatted"
        )
        assertTrue(formatted.endsWith("\n"), "trailing newline of the source is preserved:\n$formatted")

        val original = LuaParser().parseWithDiagnostics(source)
        val reparsed = LuaParser().parseWithDiagnostics(formatted)
        assertTrue(
            reparsed.recoveryDiagnostics.isEmpty(),
            "formatted document must reparse cleanly; diagnostics=${reparsed.recoveryDiagnostics}\n$formatted"
        )
        assertEquals(
            original.chunk.body.statements.size,
            reparsed.chunk.body.statements.size,
            "statements after the if must not be absorbed into its body:\n$formatted"
        )
        assertTrue(reparsed.chunk.body.returnStatement != null, "trailing return survives formatting:\n$formatted")
        assertEquals(renderShape(original.chunk), renderShape(reparsed.chunk), "shape drift:\n$formatted")
    }

    @Test
    fun formatting_nested_if_inside_function_emits_one_end_per_block() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fmt_if_nested.lua"
        val source = "local function pick(a, b) if a then return a else return b end end"
        service.didOpen(openParams(uri, source))

        val edits = service.formatting(
            DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(2, true))
        )
        val formatted = applyEdits(source, edits)

        assertEquals(2, standaloneEndLine.findAll(formatted).count(), "inner if end + function end:\n$formatted")
        assertReparsesCleanlyWithSameShape(source, formatted)
    }

    @Test
    fun formatting_with_tabs_keeps_terminal_end_and_reparses_cleanly() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fmt_if_tabs.lua"
        val source = "if ready then start() elseif retry then again() end"
        service.didOpen(openParams(uri, source))

        val edits = service.formatting(
            DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, false))
        )
        val formatted = applyEdits(source, edits)

        assertEquals(1, standaloneEndLine.findAll(formatted).count(), formatted)
        assertReparsesCleanlyWithSameShape(source, formatted)
    }

    @Test
    fun range_formatting_of_an_if_statement_keeps_terminal_end_and_reparses_cleanly() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fmt_if_range.lua"
        val ifLine = "if a then print(1) end"
        val source = "local before = 1\n$ifLine\nreturn before"
        service.didOpen(openParams(uri, source))

        val edits = service.rangeFormatting(
            DocumentRangeFormattingParams(
                TextDocumentIdentifier(uri),
                FormattingOptions(4, true),
                Range(Position(1, 0), Position(1, ifLine.length))
            )
        )
        assertTrue(edits.isNotEmpty(), "selected single-line if must be re-laid out by the printer path")
        val formatted = applyEdits(source, edits)

        assertTrue(formatted.startsWith("local before = 1\n"), "unselected prefix untouched:\n$formatted")
        assertTrue(formatted.endsWith("return before"), "unselected suffix untouched:\n$formatted")
        assertEquals(1, standaloneEndLine.findAll(formatted).count(), formatted)
        assertReparsesCleanlyWithSameShape(source, formatted)
    }

    @Test
    fun formatting_falls_back_to_safe_normalization_when_printer_surface_does_not_reparse() {
        val source = "local padded = (\"x\"):rep(3)\nreturn padded"

        // Precondition: the raw printer surface for this chunk must NOT parse back cleanly,
        // otherwise this sample no longer exercises the guard and needs a new trigger.
        val rawPrinted = AST2Lua().asCode(LuaParser().parse(source))
        val rawReparsesCleanly = runCatching {
            LuaParser().parseWithDiagnostics(rawPrinted).recoveryDiagnostics.isEmpty()
        }.getOrDefault(false)
        assertTrue(
            !rawReparsesCleanly,
            "precondition: raw AST2Lua surface should not reparse cleanly for this sample:\n$rawPrinted"
        )

        val service = plainService()
        val uri = "file:///workspace/src/app/fmt_guard_fallback.lua"
        service.didOpen(openParams(uri, source))

        val edits = service.formatting(
            DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(4, true))
        )
        val formatted = applyEdits(source, edits)

        // The guard must reject the corrupt printer surface: whatever is returned parses
        // cleanly and still carries the parenthesized receiver.
        val reparsed = LuaParser().parseWithDiagnostics(formatted)
        assertTrue(
            reparsed.recoveryDiagnostics.isEmpty(),
            "guarded formatting must only return parseable text; diagnostics=${reparsed.recoveryDiagnostics}\n$formatted"
        )
        assertTrue(formatted.contains("(\"x\"):rep(3)"), "parenthesized receiver must survive:\n$formatted")
        assertEquals(renderShape(LuaParser().parse(source)), renderShape(reparsed.chunk), "shape drift:\n$formatted")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun assertReparsesCleanlyWithSameShape(source: String, formatted: String) {
        val reparsed = LuaParser().parseWithDiagnostics(formatted)
        assertTrue(
            reparsed.recoveryDiagnostics.isEmpty(),
            "formatted text must reparse without recovery diagnostics; " +
                "diagnostics=${reparsed.recoveryDiagnostics}\nformatted:\n$formatted"
        )
        assertEquals(
            renderShape(LuaParser().parse(source)),
            renderShape(reparsed.chunk),
            "formatted text must keep the AST shape\nformatted:\n$formatted"
        )
    }

    private fun plainService(): LuaLanguageService {
        return LuaLanguageService().also { it.initialize(InitializeParams()) }
    }

    private fun openParams(uri: String, text: String, version: Int = 1): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", version, text))
    }

    /** Applies LSP edits (later edits first so earlier offsets stay valid). */
    private fun applyEdits(source: String, edits: List<TextEdit>): String {
        var result = source
        edits
            .sortedWith(
                compareByDescending<TextEdit> { it.range.start.line }
                    .thenByDescending { it.range.start.character }
            )
            .forEach { edit ->
                val start = offsetOf(result, edit.range.start)
                val end = offsetOf(result, edit.range.end).coerceAtLeast(start)
                result = result.substring(0, start) + edit.newText + result.substring(end)
            }
        return result
    }

    private fun offsetOf(text: String, position: Position): Int {
        var line = 0
        var index = 0
        while (line < position.line && index < text.length) {
            if (text[index] == '\n') {
                line += 1
            }
            index += 1
        }
        return (index + position.character).coerceIn(0, text.length)
    }
}
