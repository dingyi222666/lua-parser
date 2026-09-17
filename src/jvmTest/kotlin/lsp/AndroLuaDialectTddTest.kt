package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AndroLua dialect corpus for switch bodies and shorthand conditionals, driven by the
 * real demo project files (mods/dingyi.lua, model/AppListStream.lua).
 *
 * Two product defects landed here:
 * 1. The parser's location-mark stack leaked one entry per switch/case/when statement
 *    (marks pushed but finishNode never called), corrupting every enclosing node's
 *    range — function scopes started a line late, so scope-by-position lookups missed
 *    switch conditions against function parameters.
 * 2. The binder routed case causes through base visitStatementNode, whose when-table
 *    has no CaseCause/DefaultCause branches, so NOTHING inside a case body was bound:
 *    callback parameters and for-in control variables resolved as unresolved globals
 *    while the same code outside a switch analyzed cleanly.
 *
 * The `if cond else` shorthand (`if io.readall else`, mods/dingyi.lua) is accepted
 * silently under AndroLua: `then` is optional when a block boundary follows.
 */
class AndroLuaDialectTddTest {

    @Test
    fun bindings_inside_switch_case_bodies_resolve() {
        val service = workspaceService()
        val uri = "file:///workspace/iso.lua"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, ISO_SOURCE)))

        val unresolved = diagnostics(service, uri).filter {
            codeOf(it) == "checker.global.unresolved"
        }
        assertTrue(
            unresolved.isEmpty(),
            "callback params and for-in control variables inside switch bodies must " +
                "resolve; got ${unresolved.map { it.message }}"
        )
    }

    @Test
    fun if_without_then_before_else_parses_silently_under_androlua() {
        val service = workspaceService()
        val uri = "file:///workspace/androlua-if-else.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, IF_ELSE_SOURCE.trimIndent())
            )
        )

        val parseProblems = diagnostics(service, uri).filter {
            it.message.contains("<then> expected")
        }
        assertTrue(
            parseProblems.isEmpty(),
            "AndroLua if-cond-else shorthand must parse silently; got ${parseProblems.map { it.message }}"
        )
    }

    @Test
    fun switch_condition_resolves_enclosing_function_parameter() {
        val service = workspaceService()
        val uri = "file:///workspace/switch-cond.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, SWITCH_CONDITION_SOURCE.trimIndent())
            )
        )

        val unresolved = diagnostics(service, uri).filter {
            codeOf(it) == "checker.global.unresolved" && it.message.contains("mode")
        }
        assertTrue(
            unresolved.isEmpty(),
            "switch condition must resolve the enclosing function parameter; " +
                "got ${unresolved.map { it.message }}"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun diagnostics(service: LuaLanguageService, uri: String): List<Diagnostic> =
        service.diagnosticsForUri(uri).diagnostics

    private companion object {
        private val ISO_SOURCE = """
            require "import"
            local t = {x = 1}
            table.foreach(t, function(k, v) print(v) end)
            for kk, vv in pairs(t) do print(vv) end
            switch t.x do
              case 1
                table.foreach(t, function(k2, v2) print(v2) end)
              default
                for k3, v3 in pairs(t) do print(v3) end
            end
        """.trimIndent()

        private val IF_ELSE_SOURCE = """
            if io.readall else
              io.readall = function(path)
                return path
              end
            end
        """.trimIndent()

        private val SWITCH_CONDITION_SOURCE = """
            ViewUtil = {}
            ViewUtil.ripple = function(mode, color)
              switch mode do
                case 1
                  return mode
                default
                  return color
              end
            end
        """.trimIndent()
    }
}
