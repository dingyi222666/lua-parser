package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Numeric for-loop control variable is always number in Lua:
 *   for i = 1, #items do ... end
 * Hover on `i` must surface number (not bare unknown). Typed at bind time so
 * the normal declaredType / hover path participates — no Emmy or hover-only wrap.
 */
class MonacoNumericForLoopVarTddTest {

    @Test
    fun hover_on_numeric_for_control_var_is_number() {
        val root = Files.createTempDirectory("monaco-numeric-for-")
        val source = JOIN_SOURCE
        write(root, "utils.lua", source)
        val service = openService(root, source)
        val hover = assertNotNull(
            service.hover(hoverParams(root, source, "i", occurrence = 1)),
            "expected hover on for-loop control variable i"
        )
        val text = hoverMarkup(hover).lowercase()
        println("HOVER for-i markup=$text")
        assertTrue(
            text.contains("number") || text.contains("integer"),
            "numeric for control var must be number; got: $text"
        )
        assertFalse(
            Regex("""\btype:\s*`?unknown`?\b""").containsMatchIn(text) &&
                !text.contains("number"),
            "i must not be bare unknown; got: $text"
        )
    }

    @Test
    fun hover_on_numeric_for_var_use_site_is_number() {
        val root = Files.createTempDirectory("monaco-numeric-for-use-")
        val source = JOIN_SOURCE
        write(root, "utils.lua", source)
        val service = openService(root, source)
        // Source uses only the control var name "i" (no "items" substring containing i).
        // occ1 = for header; occ2 = out[i]; occ3 = items[i]
        val hover = assertNotNull(
            service.hover(hoverParams(root, source, "i", occurrence = 2)),
            "expected hover on use of for-loop i"
        )
        val text = hoverMarkup(hover).lowercase()
        println("HOVER use-i markup=$text")
        assertTrue(
            text.contains("number") || text.contains("integer"),
            "use of numeric for var must be number; got: $text"
        )
    }

    private fun openService(root: Path, source: String): LuaLanguageService {
        val service = LuaLanguageService()
        service.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "ws"))
        })
        val uri = root.resolve("utils.lua").toUri().toString()
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
        return service
    }

    private fun hoverParams(root: Path, source: String, needle: String, occurrence: Int): HoverParams {
        val uri = root.resolve("utils.lua").toUri().toString()
        return HoverParams(TextDocumentIdentifier(uri), positionOf(source, needle, occurrence))
    }

    private fun write(root: Path, name: String, source: String) {
        root.resolve(name).parent?.createDirectories()
        root.resolve(name).writeText(source)
    }

    private fun positionOf(source: String, needle: String, occurrence: Int): Position {
        var idx = -1
        var from = 0
        var found = 0
        while (found < occurrence) {
            idx = source.indexOf(needle, from)
            require(idx >= 0) { "missing occurrence $occurrence of $needle" }
            // whole-identifier only (not substring of items/join/...)
            val beforeOk = idx == 0 || !source[idx - 1].isLuaIdentChar()
            val afterOk = idx + needle.length >= source.length ||
                !source[idx + needle.length].isLuaIdentChar()
            from = idx + needle.length
            if (beforeOk && afterOk) {
                found += 1
            }
        }
        return offsetToPosition(source, idx)
    }

    private fun Char.isLuaIdentChar(): Boolean =
        this.isLetterOrDigit() || this == '_'

    private fun offsetToPosition(source: String, offset: Int): Position {
        var line = 0
        var lineStart = 0
        for (i in 0 until offset) {
            if (source[i] == '\n') {
                line += 1
                lineStart = i + 1
            }
        }
        return Position(line, offset - lineStart)
    }

    private fun hoverMarkup(hover: Hover): String {
        val contents = hover.contents ?: return ""
        return when {
            contents.isRight -> contents.right?.value.orEmpty()
            contents.isLeft -> contents.left.orEmpty().joinToString("\n") { either ->
                when {
                    either.isRight -> either.right?.value.orEmpty()
                    either.isLeft -> either.left?.toString().orEmpty()
                    else -> ""
                }
            }
            else -> hover.toString()
        }
    }

    companion object {
        // Avoid identifiers that contain the letter sequence "i" as substring of another name
        // for simpler whole-identifier occurrence counting (items → list).
        private val JOIN_SOURCE = """
            local M = {}
            ---@param list table
            function M.join(list, sep)
                sep = sep or ", "
                local out = {}
                for i = 1, #list do
                    out[i] = tostring(list[i])
                end
                return table.concat(out, sep)
            end
            return M
        """.trimIndent() + "\n"
    }
}
