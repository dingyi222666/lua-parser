package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression corpus for `self.` member completion inside colon-method bodies
 * (the AndroLua AppListStream pattern: `---@class X` blocks on a local table plus
 * `---@param self X` on every method).
 *
 * Two defects landed here:
 * 1. The implicit `self` identifier was never typed — identifier evaluation returned
 *    UnknownType, so every `self.` member surface collapsed to empty/lexical fallback.
 *    Fix: ExpressionTypeEvaluator.implicitSelfType consults the enclosing method's
 *    resolved `---@param self` documentation type.
 * 2. The parser's bare-expression-statement recovery set a recovery CallExpression as
 *    its own parent (`this@apply` bound to the inner apply), creating an AST
 *    parent-cycle. Any `self.r`-style statement typed mid-edit hung or truncated every
 *    parent-chain walk (findAncestorFunction), so `self` in freshly typed lines resolved
 *    to nothing even with fix 1 in place.
 *
 * `self.` inside `readyLoad(self)` (explicit parameter) always worked and guards the
 * positive control.
 */
class LspSelfMemberCompletionTddTest {

    @Test
    fun self_dot_completion_inside_colon_method_lists_class_fields() {
        val service = workspaceService()
        val source = FLAT_SOURCE.trimIndent()
        val uri = open(service, "self-flat.lua", source)
        val line = lineOf(source, "  self.r")

        // Caret after `r` and on `r` must both offer the class member surface.
        for (col in 7..8) {
            val labels = service.completion(uri, line, col).items.map { it.label }
            assertTrue("readyBuild" in labels, "col=$col self. surface must list class fields; got $labels")
            assertTrue("readyLoad" in labels, "col=$col self. surface must list function fields; got $labels")
        }
    }

    @Test
    fun nested_field_completion_reaches_field_only_class() {
        val service = workspaceService()
        val source = NESTED_SOURCE.trimIndent()
        val uri = open(service, "self-nested.lua", source)
        val line = lineOf(source, "  self.readyBuild.s")

        // `Ready` is declared only through `---@class`/`---@field` blocks; the caret on
        // the nested member must still surface its fields.
        for (col in 17..18) {
            val labels = service.completion(uri, line, col).items.map { it.label }
            assertTrue("mode" in labels, "col=$col nested surface must list Ready fields; got $labels")
            assertTrue("search" in labels, "col=$col nested surface must list Ready fields; got $labels")
        }
    }

    @Test
    fun explicit_self_parameter_still_completes_class_members() {
        val service = workspaceService()
        val source = EXPLICIT_SOURCE.trimIndent()
        val uri = open(service, "self-explicit.lua", source)
        val line = lineOf(source, "  self.r")

        val labels = service.completion(uri, line, 8).items.map { it.label }
        assertTrue("readyData" in labels, "explicit self surface must list class fields; got $labels")
    }

    // --- helpers -----------------------------------------------------------------

    private fun lineOf(source: String, needle: String): Int =
        source.lines().indexOfFirst { it == needle }.also { check(it >= 0) { "Missing '$needle'." } }

    private fun open(service: LuaLanguageService, name: String, source: String): String {
        val uri = "file:///workspace/$name"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
        return uri
    }

    private companion object {
        private val FLAT_SOURCE = """
            ---@class Stream
            ---@field readyBuild StreamReady
            ---@field readyData StreamData
            ---@field readyLoad fun(self: Stream)

            ---@class StreamReady
            ---@field mode integer
            ---@field search string

            ---@class StreamData
            ---@field system table

            ---@type Stream
            local t = {}

            ---@param self Stream
            ---@param text string
            function t:search(text)
              self.r
            end
        """.trimIndent()

        private val NESTED_SOURCE = """
            ---@class Ready
            ---@field mode integer
            ---@field search string

            ---@class Stream
            ---@field readyBuild Ready

            ---@type Stream
            local t = {}

            ---@param self Stream
            function t:search(text)
              self.readyBuild.s
            end
        """.trimIndent()

        private val EXPLICIT_SOURCE = """
            ---@class Stream
            ---@field readyData StreamData

            ---@param self Stream
            local function readyLoad(self)
              self.r
            end
        """.trimIndent()
    }
}
