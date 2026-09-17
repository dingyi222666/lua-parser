package lsp

import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Control-flow member assignment inference: `data.scrollData.page = a` assigned inside
 * a later callback must surface on `data.scrollData.` completion even though the field
 * never appears in the table constructor (`local data = { scrollData = {} }`) and the
 * assignment site is textually AFTER the completion position. Nested member paths
 * (`data.scrollData.<member>`) merge into the sub-table's type, mirroring how direct
 * members (`data.x = ...`) attach to the root.
 */
class NestedMemberPathCompletionTddTest {

    @Test
    fun nested_member_assignments_complete_on_the_sub_table() {
        val service = workspaceService()
        val uri = "file:///workspace/nested-path.lua"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, SOURCE.trimIndent())))

        val lines = SOURCE.trimIndent().lines()
        val dotLine = lines.indexOfFirst { it.contains("data.scrollData.") }
        val dotCol = lines[dotLine].indexOf("data.scrollData.") + "data.scrollData.".length
        val labels = service.completion(uri, dotLine, dotCol).items.map { it.label }

        assertTrue("scroll" in labels, "assigned nested fields must complete; got $labels")
        assertTrue("page" in labels, "assigned nested fields must complete; got $labels")
    }

    @Test
    fun unknown_typed_assignments_still_offer_completion_presence() {
        val service = workspaceService()
        val uri = "file:///workspace/nested-path-unknown.lua"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, UNKNOWN_RHS_SOURCE.trimIndent())))

        val lines = UNKNOWN_RHS_SOURCE.trimIndent().lines()
        val dotLine = lines.indexOfFirst { it.contains("data.scrollData.") }
        val dotCol = lines[dotLine].indexOf("data.scrollData.") + "data.scrollData.".length
        val labels = service.completion(uri, dotLine, dotCol).items.map { it.label }

        assertTrue(
            "last" in labels && "page" in labels,
            "RHS typed as a bare parameter (unknown) must still offer the field; got $labels"
        )
    }

    private companion object {
        private val SOURCE = """
            local data = {
              scrollData = {},
            }
            local function onPageScrolled(a, b)
              data.scrollData.
              if data.scrollData.scroll and b ~= 0 then
                data.scrollData.page = a
                data.scrollData.scroll = a > 0
              end
            end
        """.trimIndent()

        private val UNKNOWN_RHS_SOURCE = """
            local data = {
              scrollData = {},
            }
            local function onPageScrolled(a, b)
              data.scrollData.
              if data.scrollData.last and data.scrollData.last < b then
                data.scrollData.page = a
                data.scrollData.last = b
              end
            end
        """.trimIndent()
    }
}
