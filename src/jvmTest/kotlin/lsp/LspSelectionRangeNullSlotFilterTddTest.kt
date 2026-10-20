package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial-audit fix: mixed `textDocument/selectionRange` requests must not carry
 * JSON-null slots for positions that resolve to no AST node. The service drops those
 * items, so the lsp4j layer never hands a `List<SelectionRange>` with nulls to the
 * client. Resolvable positions keep their chains; an all-unresolvable request is empty.
 *
 * Test-only. Verification is review-owned (TASK-043). Workers must not run Gradle.
 */
class LspSelectionRangeNullSlotFilterTddTest {

    @Test
    fun mixed_request_drops_unresolvable_positions_instead_of_null_slots() {
        val service = service()
        val uri = open(service, "workspace/selection-null-slots.lua", SOURCE)

        val ranges = service.selectionRanges(
            SelectionRangeParams(
                TextDocumentIdentifier(uri),
                listOf(
                    Position(0, 7),        // inside `value` on the local statement
                    Position(500, 0),      // far beyond the end of the document
                    Position(1, 8)         // inside `value` on the return statement
                )
            )
        )

        assertEquals(2, ranges.size, "only the two resolvable positions yield chains; got $ranges")
        ranges.forEach { range ->
            assertTrue(range.range.start.line in 0..1, "chain must stay inside the document; got ${range.range}")
        }
    }

    @Test
    fun all_unresolvable_positions_yield_an_empty_list() {
        val service = service()
        val uri = open(service, "workspace/selection-all-null.lua", SOURCE)

        val ranges = service.selectionRanges(
            SelectionRangeParams(
                TextDocumentIdentifier(uri),
                listOf(Position(500, 0), Position(900, 3))
            )
        )

        assertTrue(ranges.isEmpty(), "no resolvable position means an empty list; got $ranges")
    }

    @Test
    fun lsp4j_layer_never_returns_null_elements() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val uri = open(service, "workspace/selection-lsp4j-null.lua", SOURCE)

        val result = textDocuments.selectionRange(
            SelectionRangeParams(
                TextDocumentIdentifier(uri),
                listOf(Position(500, 0), Position(0, 7))
            )
        ).get()

        assertEquals(1, result.size, "one resolvable position, no null slot; got $result")
        assertTrue(result.none { it == null }, "lsp4j selectionRange list must not contain null slots")
    }

    // --- helpers -----------------------------------------------------------------

    private fun open(service: LuaLanguageService, path: String, source: String): String {
        val uri = "file:///$path"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
        return uri
    }

    companion object {
        private const val SOURCE = "local value = 1\nreturn value"
    }
}
