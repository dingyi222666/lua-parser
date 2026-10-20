package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * loadlayout layout-source + ids-sink binding (AndroLua alyloader semantics):
 *
 * 1. `loadlayout("layout/main")` — the string form must actually resolve the workspace
 *    `.aly` file, and since no ids table is passed, every `id="..."` in the layout tree
 *    is registered into `_G` (typed as its row's view class).
 * 2. `loadlayout("layout/page_item", ids)` — string source with an ids table binds the
 *    ids as fields of that table.
 * 3. `loadlayout(src, _pageids[i])` — an index-expression sink binds the ids onto the
 *    elements of the base table (`_pageids[i].recy`).
 * 4. `loadlayout(inlineTable, nil)` — explicit nil sink falls back to `_G`.
 *
 * Free reads of injected ids must not flag `checker.global.unresolved`.
 */
class LspLoadlayoutAlyBindingTddTest {

    @Test
    fun string_path_without_ids_registers_typed_globals() {
        val service = workspaceService()
        val uri = openProject(service)

        // `  navBar.getChildAt(0)` — caret on `getChildAt`.
        val line = MAIN_LINES.first { it.contains("navBar.getChildAt") }
        val col = line.indexOf("navBar.") + "navBar.".length
        val labels = service.completion(uri, MAIN.lineOf(line), col).items.map { it.label }
        assertTrue(
            "getChildAt" in labels,
            "loadlayout(\"layout/main\") must register navBar as a typed _G id; got $labels"
        )
    }

    @Test
    fun string_path_globals_do_not_flag_unresolved() {
        val service = workspaceService()
        val uri = openProject(service)

        val unresolved = diagnostics(service, uri).filter {
            codeOf(it) == "checker.global.unresolved" && it.message.contains("searchText")
        }
        assertTrue(
            unresolved.isEmpty(),
            "loadlayout-injected id searchText must not flag unresolved; got ${unresolved.map { it.message }}"
        )
    }

    @Test
    fun string_path_hover_shows_view_surface() {
        val service = workspaceService()
        val uri = openProject(service)

        val line = MAIN_LINES.first { it.contains("searchText.setText") }
        val col = line.indexOf("searchText")
        val hover = service.hover(
            HoverParams(document(uri), org.eclipse.lsp4j.Position(MAIN.lineOf(line), col))
        )
        val markup = hoverMarkup(hover)
        assertTrue(
            markup.contains("EditText") || markup.contains("searchText"),
            "searchText hover must keep the layout row view surface; got: $markup"
        )
    }

    @Test
    fun string_path_with_ids_table_binds_fields() {
        val service = workspaceService()
        val uri = openProject(service)

        // `  ids.progress.setVisibility(0)` — caret on `setVisibility`.
        val line = MAIN_LINES.first { it.contains("ids.progress.setVisibility") }
        val col = line.indexOf("ids.progress.") + "ids.progress.".length
        val labels = service.completion(uri, MAIN.lineOf(line), col).items.map { it.label }
        assertTrue(
            "setVisibility" in labels,
            "loadlayout(\"layout/page_item\", ids) must bind progress into ids; got $labels"
        )
    }

    @Test
    fun index_expression_sink_binds_onto_elements() {
        val service = workspaceService()
        val uri = openProject(service)

        // `  _pageids[1].pager.setCurrentItem(0)` — caret on `setCurrentItem`.
        val line = MAIN_LINES.first { it.contains("_pageids[1].pager.setCurrentItem") }
        val col = line.indexOf("_pageids[1].pager.") + "_pageids[1].pager.".length
        val labels = service.completion(uri, MAIN.lineOf(line), col).items.map { it.label }
        assertTrue(
            "setCurrentItem" in labels,
            "_pageids[i] sink must bind pager (PageView) onto the base table elements; got $labels"
        )
    }

    @Test
    fun inline_table_with_nil_sink_registers_globals() {
        val service = workspaceService()
        val uri = openProject(service)

        // `  titleCard.setRadius(8)` — caret on `setRadius`.
        val line = MAIN_LINES.first { it.contains("titleCard.setRadius") }
        val col = line.indexOf("titleCard.") + "titleCard.".length
        val labels = service.completion(uri, MAIN.lineOf(line), col).items.map { it.label }
        assertTrue(
            "setRadius" in labels,
            "loadlayout({...}, nil) must register titleCard (CardView) into _G; got $labels"
        )
    }

    @Test
    fun inline_named_sink_still_binds() {
        val service = workspaceService()
        val uri = openProject(service)

        // `  _ids.pg.setCurrentItem(0)` — caret on `setCurrentItem`.
        val line = MAIN_LINES.first { it.contains("_ids.pg.setCurrentItem") }
        val col = line.indexOf("_ids.pg.") + "_ids.pg.".length
        val labels = service.completion(uri, MAIN.lineOf(line), col).items.map { it.label }
        assertTrue(
            "setCurrentItem" in labels,
            "inline layout table must bind pg (PageView) into _ids; got $labels"
        )
    }

    @Test
    fun injected_ids_are_enumerated_as_globals() {
        val service = workspaceService()
        val uri = openProject(service)

        // `  navBar.getChildAt(0)` — caret on `navBar` (word prefix completion).
        val line = MAIN_LINES.first { it.contains("navBar.getChildAt") }
        val col = line.indexOf("navBar")
        val labels = service.completion(uri, MAIN.lineOf(line), col).items.map { it.label }
        assertTrue(
            "navBar" in labels,
            "loadlayout-injected id navBar must be enumerated in global completion; got $labels"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun document(uri: String) = org.eclipse.lsp4j.TextDocumentIdentifier(uri)

    private fun hoverMarkup(hover: org.eclipse.lsp4j.Hover?): String {
        val contents = hover?.contents ?: return ""
        return when {
            contents.isLeft -> contents.left?.toString().orEmpty()
            else -> contents.right?.value.orEmpty()
        }
    }

    private fun diagnostics(service: LuaLanguageService, uri: String): List<Diagnostic> =
        service.diagnosticsForUri(uri).diagnostics

    private fun openProject(service: LuaLanguageService): String {
        // Layout files are indexed before the consumer in a real workspace folder walk;
        // opening them first matches that order (adding a layout file later does not
        // re-analyze the consumer — there is no require edge).
        val files = listOf(
            "file:///workspace/layout/main.aly" to MAIN_ALY.trimIndent(),
            "file:///workspace/layout/page_item.aly" to PAGE_ITEM_ALY.trimIndent(),
            "file:///workspace/main.lua" to MAIN.trimIndent()
        )
        files.forEach { (uri, source) ->
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
        }
        return "file:///workspace/main.lua"
    }

    private fun String.lineOf(needle: String): Int =
        lineSequence().indexOfFirst { it.contains(needle) }

    private val MAIN_LINES: List<String> get() = MAIN.trimIndent().lines()

    private companion object {
        private val MAIN = """
            require "import"
            import "android.widget.*"
            import "android.view.*"

            activity.setContentView(loadlayout("layout/main"))

            local ids={}
            local _pageids={}
            local _ids={}

            local function ready()
              navBar.getChildAt(0)
              searchText.setText("")
              loadlayout("layout/page_item",ids)
              ids.progress.setVisibility(0)
              _pageids[1]={}
              loadlayout("layout/page_item",_pageids[1])
              _pageids[1].pager.setCurrentItem(0)
              loadlayout({
                CardView;
                id="titleCard";
              },nil)
              titleCard.setRadius(8)
              local parentLayout={ LinearLayout; id="holder"; { PageView; id="pg" } }
              loadlayout(parentLayout,_ids)
              _ids.pg.setCurrentItem(0)
            end
        """.trimIndent()

        private val MAIN_ALY = """
            {
              LinearLayout,
              layout_height="fill",
              id="navBar",
              {
                EditText,
                id="searchText",
              },
              {
                PageView,
                id="pager",
              },
            }
        """.trimIndent()

        private val PAGE_ITEM_ALY = """
            {
              LinearLayout,
              id="progress",
              {
                PageView,
                id="pager",
              },
            }
        """.trimIndent()
    }
}
